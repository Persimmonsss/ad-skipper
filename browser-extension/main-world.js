// 内容脚本跑在 isolated world，读不到页面的 window.__INITIAL_STATE__；
// 这个脚本注入主世界，把 cid/bvid 写进 <html> 的 data 属性，属性是跨世界可见的。
(() => {
  const KEY = "adskipperVideo";
  const BV_RE = /(BV[0-9A-Za-z]{10})/;
  let last = "";

  // 活动页（/festival/S16?bvid=…）的链接里没有 /video/，普通视频页又可能被重定向到活动页，
  // 两边都要认。实测点合集换集：?bvid= 和 videoInfo 会跟着换，toBvid 停在首次打开的那条。
  function currentBvid(st) {
    const fromPath = (location.pathname.match(BV_RE) || [])[1] || "";
    const query = (BV_RE.exec(new URLSearchParams(location.search).get("bvid") || "") || [])[1] || "";
    return (
      fromPath ||
      query ||
      (st.videoData && st.videoData.bvid) ||
      (st.videoInfo && st.videoInfo.bvid) ||
      st.toBvid ||
      ""
    );
  }

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
    const vd = st.videoData || st.videoInfo || {};
    const payload = JSON.stringify({ bvid: currentBvid(st), cid: currentCid(st, vd) });
    if (payload !== last) {
      last = payload;
      document.documentElement.dataset[KEY] = payload;
    }
  }

  publish();
  // 切分 P / 连集不刷新页面，__INITIAL_STATE__ 是异步更新的，靠轮询兜住
  setInterval(publish, 300);
})();
