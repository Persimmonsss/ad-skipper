// 抖音网页版：信息流里的整条广告没法「快进」，跳过 = 切到下一条视频。
// 只认视频上的「广告 / 赞助」角标，作者简介、评论里出现「广告」两个字不会触发。
(() => {
  if (window.__adSkipperDouyin) return;
  window.__adSkipperDouyin = true;

  const POLL_MS = 400;
  const BADGE_CONFIRM_MS = 700;
  const VERIFY_MS = 900;
  const UNDO_MS = 4000;
  const SKIP_COOLDOWN_MS = 2500;
  const MAX_AUTO_SKIPS = 8;

  const BADGE = /^(广告|廣告|赞助|贊助|推广|推廣)(\s*[·|｜].*)?$/;
  const VIDEO_ID = /video_(\d+)/;

  let cfg = { enabled: true, bubble: true };
  let badgeSince = 0;
  let lastSkipAt = 0;
  let autoSkips = 0;
  let pending = null;
  let undo = null;

  const skipped = new Set();

  const bubble = document.createElement("div");
  bubble.className = "adskipper-bubble";
  bubble.setAttribute("hidden", "true");
  const label = document.createElement("span");
  const action = document.createElement("button");
  bubble.appendChild(label);
  bubble.appendChild(action);
  let lastKey = "";

  function trace(text) {
    console.log("[AdSkipper]", text);
    chrome.runtime.sendMessage({ type: "trace", detail: `[抖音] ${text}` }, () => void chrome.runtime.lastError);
  }

  function mountBubble() {
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

  function activeItem() {
    const act = document.querySelector('[data-e2e="feed-active-video"]');
    return act ? act.closest('[data-e2e="feed-item"]') : null;
  }

  function videoId(item) {
    if (!item) return "";
    const holder = item.querySelector('[data-e2e="feed-active-video"], [data-e2e="feed-video"]');
    const cls = holder ? String(holder.className) : "";
    const m = cls.match(VIDEO_ID);
    return m ? m[1] : "";
  }

  function videoRect(item) {
    const v = item.querySelector("video");
    return v ? v.getBoundingClientRect() : null;
  }

  // 角标必须是视频范围内的小块文字，评论区里同样写「广告」的发言不算
  function hasAdBadge(item) {
    if (!item) return false;
    const vr = videoRect(item);
    if (!vr) return false;
    for (const el of item.querySelectorAll("span, div, p")) {
      if (el.childElementCount !== 0) continue;
      const t = (el.textContent || "").trim();
      if (!t || t.length > 8 || !BADGE.test(t)) continue;
      const r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0 || r.width > 120 || r.height > 48) continue;
      if (r.bottom < vr.top || r.top > vr.bottom || r.right < vr.left || r.left > vr.right) continue;
      return true;
    }
    return false;
  }

  function arrow(dir) {
    return document.querySelector(`[data-e2e="video-switch-${dir}-arrow"]`);
  }

  function clickArrow(dir) {
    const a = arrow(dir);
    if (!a) return false;
    const cs = getComputedStyle(a);
    if (cs.display === "none" || cs.visibility === "hidden") return false;
    a.click();
    return true;
  }

  function goNext() {
    if (clickArrow("next")) return true;
    // 箭头被隐藏（全屏/窄屏）时退回滚轮：抖音的信息流是整屏滚动
    const target = document.querySelector('[data-e2e="slideList"]') || window;
    target.dispatchEvent(new WheelEvent("wheel", {
      deltaY: window.innerHeight,
      deltaMode: 0,
      bubbles: true,
      cancelable: true
    }));
    return false;
  }

  function doSkip(item) {
    const id = videoId(item);
    pending = { id, at: Date.now() + VERIFY_MS, tries: 1, rect: videoRect(item) };
    hideBubble();
    goNext();
  }

  function verifySkip() {
    const p = pending;
    pending = null;
    if (!p) return;
    const now = videoId(activeItem());
    const moved = now && now !== p.id;
    if (!moved && p.tries < 2) {
      pending = { ...p, tries: p.tries + 1, at: Date.now() + VERIFY_MS };
      goNext();
      return;
    }
    if (!moved) {
      skipped.add(p.id);
      autoSkips = MAX_AUTO_SKIPS;
      trace("切下一条未生效，先停手");
      return;
    }
    skipped.add(p.id);
    autoSkips++;
    lastSkipAt = Date.now();
    chrome.runtime.sendMessage({ type: "report", kind: "skip", detail: "抖音信息流广告" }, () => void chrome.runtime.lastError);
    trace(`已切过广告 ${p.id}`);
    undo = { id: p.id, until: Date.now() + UNDO_MS };
    const timer = setInterval(() => {
      if (!undo || undo.id !== p.id) return clearInterval(timer);
      const left = Math.ceil((undo.until - Date.now()) / 1000);
      if (left <= 0) {
        clearInterval(timer);
        undo = null;
        hideBubble();
        return;
      }
      showBubble(`已跳过广告（${left}s）`, "撤回", () => undoSkip(p.id));
    }, 200);
    showBubble(`已跳过广告（${Math.ceil(UNDO_MS / 1000)}s）`, "撤回", () => undoSkip(p.id));
  }

  function undoSkip(id) {
    if (!undo || undo.id !== id || Date.now() > undo.until) {
      undo = null;
      hideBubble();
      return;
    }
    undo = null;
    hideBubble();
    if (!clickArrow("prev")) trace("撤回失败：没有上一条按钮");
    else {
      chrome.runtime.sendMessage({ type: "report", kind: "undo" }, () => void chrome.runtime.lastError);
      trace("已撤回上一条");
    }
  }

  function tick() {
    mountBubble();
    if (pending && Date.now() >= pending.at) verifySkip();
    if (!cfg.enabled) {
      hideBubble();
      return;
    }
    if (pending || undo || Date.now() - lastSkipAt < SKIP_COOLDOWN_MS) return;
    if (autoSkips >= MAX_AUTO_SKIPS) return;

    const item = activeItem();
    const id = videoId(item);
    if (!item || !id) {
      badgeSince = 0;
      return;
    }
    if (skipped.has(id)) {
      badgeSince = 0;
      return;
    }
    const v = item.querySelector("video");
    if (!v || v.paused || v.ended) {
      badgeSince = 0;
      hideBubble();
      return;
    }
    if (!hasAdBadge(item)) {
      badgeSince = 0;
      hideBubble();
      return;
    }
    const now = Date.now();
    if (!badgeSince) {
      badgeSince = now;
      if (cfg.bubble) showBubble("检测到广告，即将跳过");
      return;
    }
    if (now - badgeSince < BADGE_CONFIRM_MS) return;
    doSkip(item);
  }

  chrome.storage.local.get(["settings"], (got) => {
    cfg = Object.assign(cfg, got.settings || {});
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
  // feed 是 SPA 切换，切走后清掉「广告已确认」的计时，避免把状态带到下一条
  for (const evt of ["timeupdate", "play", "pause", "durationchange"]) {
    document.addEventListener(evt, () => tick(), true);
  }

  mountBubble();
  setInterval(tick, POLL_MS);
})();
