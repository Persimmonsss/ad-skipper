// 与 App 侧 BiliUiSkip 同一套状态机，但浏览器里能直接拿到 video.currentTime，
// 所以省掉了 App 被迫做的外推、唤控件、拖进度条三段兜底。
(() => {
  if (window.__adSkipperLoaded) return;
  window.__adSkipperLoaded = true;

  const LEAD_MS = 3000;
  const UNDO_MS = 3000;
  const UNDO_BACK_MS = 2000;
  const TAIL_CLAMP_MS = 1500;
  const POLL_MS = 250;
  const MAX_ATTEMPTS = 3;
  const VERIFY_MS = 500;

  let cfg = { enabled: true, bubble: true };
  let bvid = "", cid = "";
  let segs = [];
  const skipped = new Set();
  const attempts = new Map();
  let pending = null;
  let undoUuid = null, undoUntil = 0, undoTo = 0;
  let reqSeq = 0;

  const bubble = document.createElement("div");
  bubble.className = "adskipper-bubble";
  bubble.setAttribute("hidden", "true");
  const label = document.createElement("span");
  const action = document.createElement("button");
  bubble.appendChild(label);
  bubble.appendChild(action);

  let lastKey = "";

  function trace(text) {
    const detail = `[${bvid || "?"}] ${text}`;
    console.log("[AdSkipper]", detail);
    chrome.runtime.sendMessage({ type: "trace", detail }, () => void chrome.runtime.lastError);
  }

  function mountBubble() {
    // 全屏时只有 :fullscreen 子树会显示，挂在 body 上的气泡在全屏里是看不见的
    const host = document.fullscreenElement || document.documentElement;
    if (bubble.parentElement !== host) host.appendChild(bubble);
  }

  function showBubble(text, actionText, onAction) {
    mountBubble();
    const key = `${text}|${actionText || ""}`;
    if (key !== lastKey) {
      label.textContent = text;
      action.textContent = actionText || "";
      action.hidden = !actionText;
      lastKey = key;
    }
    action.onclick = onAction || null;
    bubble.hidden = false;
  }

  function hideBubble() {
    if (!bubble.hidden) bubble.hidden = true;
  }

  function fmt(ms) {
    const t = Math.max(0, Math.round(ms / 1000));
    const m = Math.floor(t / 60);
    const s = t % 60;
    return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  }

  function pageIdentity() {
    const raw = document.documentElement.dataset.adskipperVideo;
    let parsed = {};
    try {
      parsed = raw ? JSON.parse(raw) : {};
    } catch (e) {
      parsed = {};
    }
    const fromUrl = (location.pathname.match(/\/video\/(BV[0-9A-Za-z]{10})/) || [])[1] || "";
    return { bvid: fromUrl || parsed.bvid || "", cid: parsed.cid || "" };
  }

  function currentVideo() {
    let best = null;
    let bestArea = 0;
    for (const v of document.querySelectorAll("video")) {
      const r = v.getBoundingClientRect();
      const area = r.width * r.height;
      if (area > bestArea) {
        bestArea = area;
        best = v;
      }
    }
    return best;
  }

  function requestSegments() {
    const seq = ++reqSeq;
    const token = `${bvid}|${cid}`;
    chrome.runtime.sendMessage({ type: "getSegments", bvid, cid }, (resp) => {
      if (chrome.runtime.lastError) {
        trace(`取片段失败：${chrome.runtime.lastError.message}`);
        return;
      }
      // 切 P / 连集会作废上一次请求，迟到的响应不能写回新会话
      if (seq !== reqSeq || token !== `${bvid}|${cid}`) return;
      if (!resp || !resp.ok) {
        trace(`社区库不可用：${(resp && resp.error) || "unknown"}`);
        return;
      }
      segs = resp.segments || [];
      trace(`匹配 ${bvid}${resp.host === "cache" ? "（缓存）" : `（${resp.host}）`}，${segs.length} 段广告`);
    });
  }

  function syncIdentity() {
    const next = pageIdentity();
    if (next.bvid === bvid && next.cid === cid) return;
    bvid = next.bvid;
    cid = next.cid;
    segs = [];
    skipped.clear();
    attempts.clear();
    pending = null;
    clearUndo();
    hideBubble();
    if (bvid) requestSegments();
  }

  function clearUndo() {
    undoUuid = null;
    undoUntil = 0;
    undoTo = 0;
  }

  function skipTargetMs(seg, video) {
    const durMs = video && isFinite(video.duration) ? video.duration * 1000 : 0;
    // 广告顶到片尾的素材，直接跳 seg.end 会越界，B 站会把视频从头重播
    return durMs > 0 ? Math.max(0, Math.min(seg.endMs, durMs - TAIL_CLAMP_MS)) : seg.endMs;
  }

  function doSkip(seg) {
    const tries = attempts.get(seg.uuid) || 0;
    if (tries >= MAX_ATTEMPTS) {
      skipped.add(seg.uuid);
      trace(`多次跳转未生效，放弃 ${fmt(seg.startMs)}→${fmt(seg.endMs)}`);
      return;
    }
    attempts.set(seg.uuid, tries + 1);
    const video = currentVideo();
    if (!video) return;
    const target = skipTargetMs(seg, video);
    pending = { seg, uuid: seg.uuid, target, dueAt: Date.now() + VERIFY_MS };
    hideBubble();
    video.currentTime = target / 1000;
  }

  // 校验挂在媒体事件上而不是 setTimeout：后台标签里定时器会被降频，seek 结果会一直挂着
  function verifySkip(p) {
    pending = null;
    const seg = p.seg;
    const video = currentVideo();
    const pos = video ? video.currentTime * 1000 : -1;
    if (pos >= p.target - 2000) {
      skipped.add(seg.uuid);
      chrome.runtime.sendMessage(
        { type: "report", kind: "skip", detail: `${fmt(seg.startMs)} → ${fmt(seg.endMs)}` },
        () => void chrome.runtime.lastError
      );
      trace(`跳过生效，当前 ${fmt(pos)}（目标 ${fmt(p.target)}）`);
      offerUndo(seg);
      return;
    }
    trace(`seek 未生效（当前 ${fmt(pos)}，目标 ${fmt(p.target)}），重试`);
    doSkip(seg);
  }

  function offerUndo(seg) {
    undoUuid = seg.uuid;
    undoUntil = Date.now() + UNDO_MS;
    undoTo = Math.max(0, seg.startMs - UNDO_BACK_MS);
    const left = () => Math.max(0, Math.ceil((undoUntil - Date.now()) / 1000));
    showBubble(`已跳过广告（${left()}s）`, "撤回", () => undo(seg));
    const timer = setInterval(() => {
      if (undoUuid !== seg.uuid) {
        clearInterval(timer);
        return;
      }
      const n = left();
      if (n <= 0) {
        clearInterval(timer);
        clearUndo();
        hideBubble();
        return;
      }
      showBubble(`已跳过广告（${n}s）`, "撤回", () => undo(seg));
    }, 200);
  }

  // 撤回后这段留在 skipped 里：本视频不再自动跳它，否则回到广告前 2 秒会立刻被再跳一次
  function undo(seg) {
    if (undoUuid !== seg.uuid || Date.now() > undoUntil) {
      clearUndo();
      hideBubble();
      return;
    }
    const to = undoTo;
    clearUndo();
    hideBubble();
    const video = currentVideo();
    if (!video) return;
    video.currentTime = to / 1000;
    chrome.runtime.sendMessage({ type: "report", kind: "undo" }, () => void chrome.runtime.lastError);
    trace(`已撤回 ${fmt(to)}`);
  }

  function tick() {
    syncIdentity();
    mountBubble();
    const video = currentVideo();
    if (pending && Date.now() >= pending.dueAt) verifySkip(pending);
    if (!cfg.enabled) {
      hideBubble();
      return;
    }
    // 撤回窗口优先于「暂停/拖动中不跳」：刚 seek 完 seeking 还是 true，
    // 让这条分支先跑会把刚弹出的撤回气泡当场收掉，3 秒内根本点不到
    const now = Date.now();
    if (pending || now < undoUntil) return;
    if (!video || video.paused || video.seeking) {
      hideBubble();
      return;
    }
    if (!segs.length) return;

    const est = video.currentTime * 1000;
    const seg = segs.find((s) => est >= s.startMs - LEAD_MS && est < s.endMs);
    if (!seg || skipped.has(seg.uuid)) {
      hideBubble();
      return;
    }
    if (est < seg.startMs) {
      if (cfg.bubble) {
        const n = Math.min(3, Math.max(1, Math.ceil((seg.startMs - est) / 1000)));
        showBubble(`${n} 秒后跳过广告`);
      } else {
        hideBubble();
      }
      return;
    }
    doSkip(seg);
  }

  chrome.storage.local.get(["settings"], (got) => {
    cfg = Object.assign(cfg, got.settings || {});
    trace(`就绪，开关=${cfg.enabled ? "开" : "关"}`);
  });
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes.settings) {
      cfg = Object.assign({ enabled: true, bubble: true }, changes.settings.newValue);
    }
  });
  document.addEventListener("fullscreenchange", () => {
    hideBubble();
    mountBubble();
  });

  // 后台标签里 setInterval 会被降频到 1 分钟一次（实测跳过晚了 4 分钟），媒体事件不受降频：
  // timeupdate 约 4 次/秒，且播放器换元素也不用重新绑定（捕获阶段能收到不冒泡的媒体事件）
  for (const evt of ["timeupdate", "seeked", "play", "pause", "durationchange"]) {
    document.addEventListener(evt, () => tick(), true);
  }

  mountBubble();
  setInterval(tick, POLL_MS);
})();
