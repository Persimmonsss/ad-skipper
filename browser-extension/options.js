const swEnabled = document.getElementById("sw_enabled");
const swBubble = document.getElementById("sw_bubble");
const statsBox = document.getElementById("stats");
const probeOut = document.getElementById("probe_out");

function load() {
  chrome.storage.local.get(["settings"], (got) => {
    const s = Object.assign({ enabled: true, bubble: true }, got.settings || {});
    swEnabled.checked = !!s.enabled;
    swBubble.checked = !!s.bubble;
  });
  refreshStats();
}

function refreshStats() {
  chrome.runtime.sendMessage({ type: "getStats" }, (resp) => {
    if (chrome.runtime.lastError || !resp || !resp.ok) {
      statsBox.textContent = "读不到后台统计：先刷新本页";
      return;
    }
    const s = resp.stats;
    const hosts = Object.keys(s.hostHits || {})
      .map((h) => `${h.replace("https://", "")} ${s.hostHits[h]}`)
      .join(" · ");
    statsBox.innerHTML =
      `B 站跳过 <b>${s.skipCount}</b> 次 · 撤回 <b>${s.undoCount}</b> 次（最近 ${s.lastSkip}）<br>` +
      `社区库无标注 <b>${s.missCount}</b> 条 · 累计请求 ${s.requests} 次` +
      (hosts ? `<br><span style="color:#8a8f99;font-size:12px">命中的镜像：${hosts}</span>` : "");
  });
}

function save() {
  chrome.storage.local.set({
    settings: { enabled: swEnabled.checked, bubble: swBubble.checked }
  }, refreshStats);
}

swEnabled.onchange = save;
swBubble.onchange = save;

document.getElementById("btn_reset").onclick = () => {
  chrome.runtime.sendMessage({ type: "resetStats" }, refreshStats);
};

document.getElementById("btn_probe").onclick = () => {
  const bvid = document.getElementById("probe_bvid").value.trim();
  if (!/^BV[0-9A-Za-z]{10}$/.test(bvid)) {
    probeOut.textContent = "BV 号格式不对（例：BV1njnn6yEMY）";
    return;
  }
  probeOut.textContent = "查询中（依次尝试三个镜像，最长 24 秒）…";
  chrome.runtime.sendMessage({ type: "getSegments", bvid, cid: "", noCache: true }, (resp) => {
    if (chrome.runtime.lastError) {
      probeOut.textContent = `后台出错：${chrome.runtime.lastError.message}`;
      return;
    }
    if (!resp || !resp.ok) {
      probeOut.textContent = `三个镜像都没答上：${(resp && resp.error) || "unknown"}`;
      refreshStats();
      return;
    }
    if (!resp.segments.length) {
      probeOut.textContent = resp.miss ? `社区库里这条没有 sponsor 标注（${resp.host} 返回 404）` : "社区库返回空";
    } else {
      const list = resp.segments
        .map((s) => `${fmt(s.startMs)}–${fmt(s.endMs)}  cid=${s.cid || "?"}`)
        .join("\n");
      probeOut.textContent = `命中 ${resp.segments.length} 段（来自 ${resp.host}）\n${list}`;
    }
    refreshStats();
  });
};

function fmt(ms) {
  const t = Math.round(ms / 1000);
  return `${String(Math.floor(t / 60)).padStart(2, "0")}:${String(t % 60).padStart(2, "0")}`;
}

load();
