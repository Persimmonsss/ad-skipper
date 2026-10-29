// 跨域请求必须放在 service worker：内容脚本跟着页面走 CORS，三镜像都不会给
// bilibili.com 发 Access-Control-Allow-Origin。host_permissions 已声明，worker 里可直连。

const SEGMENT_HOSTS = ["https://bsbsb.top", "https://bsbsb.cc", "https://www.bsbsb.xyz"];
const CACHE_TTL_MS = 6 * 60 * 60 * 1000;
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/AdSkipper";

const STATS_KEY = "stats";

function log(...args) {
  console.log("[AdSkipper]", ...args);
}

async function readStats() {
  const got = await chrome.storage.local.get(STATS_KEY);
  return Object.assign(
    { skipCount: 0, undoCount: 0, missCount: 0, lastSkip: "-", hostHits: {}, requests: 0 },
    got[STATS_KEY] || {}
  );
}

async function writeStats(stats) {
  await chrome.storage.local.set({ [STATS_KEY]: stats });
  await chrome.action.setBadgeText({ text: stats.skipCount > 0 ? String(stats.skipCount) : "" });
  await chrome.action.setBadgeBackgroundColor({ color: "#FB7299" });
}

function parseSegments(body) {
  const out = [];
  let arr;
  try {
    arr = JSON.parse(body);
  } catch (e) {
    return { error: "bad json" };
  }
  if (!Array.isArray(arr)) return { error: "not array" };
  for (const item of arr) {
    if (!item || item.category !== "sponsor") continue;
    // 只标了 sponsor 分类也可能给出 preview/ignore 这类不跳转的动作
    if (item.actionType && item.actionType !== "skip") continue;
    const seg = item.segment;
    if (!Array.isArray(seg) || seg.length < 2) continue;
    const startMs = Math.round(Number(seg[0]) * 1000);
    const endMs = Math.round(Number(seg[1]) * 1000);
    if (!(endMs > startMs) || !isFinite(startMs)) continue;
    out.push({ uuid: String(item.UUID || `${startMs}-${endMs}`), startMs, endMs, cid: String(item.cid || "") });
  }
  out.sort((a, b) => a.startMs - b.startMs);
  return { segments: out };
}

async function fetchFrom(host, bvid) {
  const url = `${host}/api/skipSegments?videoID=${encodeURIComponent(bvid)}&categories=${encodeURIComponent('["sponsor"]')}`;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 8000);
  try {
    const resp = await fetch(url, {
      signal: ctrl.signal,
      headers: { "User-Agent": UA, Accept: "application/json" },
      cache: "no-store"
    });
    if (resp.status === 404) return { empty: true };
    // 429 的 body 是 {"message":"Too many requests"}，不能当片段数组解析
    if (!resp.ok) return { httpError: resp.status };
    return { body: await resp.text() };
  } catch (e) {
    return { netError: String(e && e.message ? e.message : e) };
  } finally {
    clearTimeout(timer);
  }
}

async function getSegments(bvid, cid, noCache) {
  const cacheKey = `segs:${bvid}`;
  if (!noCache) {
    const stored = await chrome.storage.local.get(cacheKey);
    const hit = stored[cacheKey];
    if (hit && Date.now() - hit.ts < CACHE_TTL_MS) {
      const segs = filterByCid(hit.segments, cid);
      return { ok: true, segments: segs, host: "cache", fromCache: true, miss: segs.length === 0 };
    }
  }

  const stats = await readStats();
  stats.requests++;
  const failures = [];
  for (const host of SEGMENT_HOSTS) {
    const r = await fetchFrom(host, bvid);
    if (r.empty) {
      await chrome.storage.local.set({ [cacheKey]: { ts: Date.now(), segments: [] } });
      stats.missCount++;
      await writeStats(stats);
      return { ok: true, segments: [], host, miss: true };
    }
    if (r.httpError || r.netError) {
      failures.push(`${host.replace("https://", "")}:${r.httpError || r.netError}`);
      continue;
    }
    const parsed = parseSegments(r.body);
    if (parsed.error) {
      failures.push(`${host.replace("https://", "")}:${parsed.error}`);
      continue;
    }
    // 镜像也会用 200 + 空数组表达「没人标过」，这一支要和 404 一起计入未命中
    if (!parsed.segments.length) stats.missCount++;
    stats.hostHits[host] = (stats.hostHits[host] || 0) + 1;
    await writeStats(stats);
    await chrome.storage.local.set({ [cacheKey]: { ts: Date.now(), segments: parsed.segments } });
    const segs = filterByCid(parsed.segments, cid);
    return { ok: true, segments: segs, host, miss: segs.length === 0 };
  }
  await writeStats(stats);
  log("三镜像全部失败", bvid, failures.join(" / "));
  return { ok: false, error: failures.join(" / ") };
}

// 缓存按 BV 存、不分 cid，切分 P 时在本地再过滤一次即可，省一次网络请求
function filterByCid(segments, cid) {
  if (!cid || !Array.isArray(segments)) return segments || [];
  return segments.filter((s) => !s.cid || s.cid === String(cid));
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || !msg.type) return false;

  if (msg.type === "getSegments") {
    getSegments(msg.bvid, msg.cid, msg.noCache)
      .then(sendResponse)
      .catch((e) => sendResponse({ ok: false, error: String(e) }));
    return true;
  }

  if (msg.type === "report") {
    readStats()
      .then(async (stats) => {
        if (msg.kind === "skip") {
          stats.skipCount++;
          stats.lastSkip = msg.detail || stats.lastSkip;
        } else if (msg.kind === "undo") {
          stats.undoCount++;
        }
        await writeStats(stats);
        sendResponse({ ok: true, stats });
      })
      .catch((e) => sendResponse({ ok: false, error: String(e) }));
    return true;
  }

  if (msg.type === "getStats") {
    readStats()
      .then((stats) => sendResponse({ ok: true, stats }))
      .catch((e) => sendResponse({ ok: false, error: String(e) }));
    return true;
  }

  if (msg.type === "resetStats") {
    writeStats({ skipCount: 0, undoCount: 0, missCount: 0, lastSkip: "-", hostHits: {}, requests: 0 })
      .then(() => sendResponse({ ok: true }))
      .catch((e) => sendResponse({ ok: false, error: String(e) }));
    return true;
  }

  if (msg.type === "trace") {
    log(msg.detail);
    sendResponse({ ok: true });
    return true;
  }

  return false;
});

chrome.action.onClicked.addListener(() => {
  chrome.runtime.openOptionsPage();
});

chrome.runtime.onInstalled.addListener(() => {
  log("已安装", chrome.runtime.getManifest().version);
});
