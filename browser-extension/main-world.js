// 内容脚本跑在 isolated world，读不到页面的 window.__INITIAL_STATE__；
// 这个脚本注入主世界，把 cid/bvid 写进 <html> 的 data 属性，属性是跨世界可见的。
(() => {
  const KEY = "adskipperVideo";
  let last = "";

  // 多 P 页面里 videoData.cid 恒等于第一 P（实测 ?p=3 时它还是 P1 的 cid），
  // 真正的当前分 P 要按 URL 的 ?p= 去 pages[] 里取，否则会拿别的 P 的标注来跳
  function currentCid(st, vd) {
    const p = Number(new URLSearchParams(location.search).get("p")) || Number(st.page) || 1;
    const pages = Array.isArray(vd.pages) ? vd.pages : null;
    if (pages && pages.length) {
      const cur = pages[p - 1] || pages[0];
      if (cur && cur.cid) return String(cur.cid);
    }
    return vd.cid ? String(vd.cid) : "";
  }

  function publish() {
    const st = window.__INITIAL_STATE__ || {};
    const vd = st.videoData || {};
    const m = (location.pathname.match(/\/video\/(BV[0-9A-Za-z]{10})/) || [])[1] || vd.bvid || "";
    const payload = JSON.stringify({ bvid: m, cid: currentCid(st, vd) });
    if (payload !== last) {
      last = payload;
      document.documentElement.dataset[KEY] = payload;
    }
  }

  publish();
  // 切分 P / 连集不刷新页面，__INITIAL_STATE__ 是异步更新的，靠轮询兜住
  setInterval(publish, 300);
})();
