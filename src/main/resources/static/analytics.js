/**
 * PowerCMS 第一方采集脚本（FR-STAT-001/004/007）。
 * 由 GET /analytics.js 分发；第一方存储访客/会话标识；批量上报；
 * SPA 路由钩子；页面卸载 sendBeacon 补发。服务端无会话状态（FR-STAT-101）。
 */
(function () {
  'use strict';
  var config = window.powercmsAnalyticsConfig || {};
  var siteKey = config.siteKey;
  var endpoint = (config.endpoint || '/api/v1/delivery/analytics/collect');
  var flushMs = config.flushMs || 5000;
  var heartbeatMs = config.heartbeatMs || 10000;
  var autoTrackSpa = config.autoTrackSpa !== false;

  if (!siteKey) return;

  var SESSION_TTL = 30 * 60 * 1000; // 30 分钟（FR-STAT-004）
  var LS_VISITOR = 'pca_visitor_id';
  var SS_SESSION = 'pca_session';

  function uuid() {
    return (crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = Math.random() * 16 | 0; return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    }));
  }

  function visitorId() {
    var id = localStorage.getItem(LS_VISITOR);
    if (!id) { id = uuid(); localStorage.setItem(LS_VISITOR, id); }
    return id;
  }

  // 会话判定在客户端完成（FR-STAT-101）：30 分钟超时、跨自然日强制新建
  function session() {
    var raw = sessionStorage.getItem(SS_SESSION);
    var now = Date.now();
    var today = new Date().toDateString();
    var s = raw ? JSON.parse(raw) : null;
    if (!s || now - s.lastActive > SESSION_TTL || s.day !== today) {
      s = { id: uuid(), startedAt: now, seq: 0, day: today };
    }
    s.lastActive = now;
    s.seq += 1;
    sessionStorage.setItem(SS_SESSION, JSON.stringify(s));
    return s;
  }

  var queue = [];
  var timer = null;

  function send(batch, useBeacon) {
    if (!batch.length) return;
    var body = JSON.stringify({ siteKey: siteKey, events: batch });
    if (useBeacon && navigator.sendBeacon) {
      navigator.sendBeacon(endpoint, new Blob([body], { type: 'application/json' }));
      return;
    }
    fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body,
      keepalive: useBeacon,
      // 携带同源 Cookie：前台会员会话经此到达服务端，用于学习进度归因（FR-STAT-037）。
      // 服务端仅从中解析会员 ID，不建立任何会话状态（FR-STAT-101 无状态约束不变）。
      credentials: 'include'
    }).catch(function () { /* 失败计数由服务端指标承接 */ });
  }

  function flush(useBeacon) {
    if (timer) { clearTimeout(timer); timer = null; }
    var batch = queue.splice(0, 50);
    send(batch, useBeacon);
    if (queue.length && !useBeacon) schedule();
  }

  function schedule() {
    if (timer) return;
    timer = setTimeout(function () { flush(false); }, flushMs);
  }

  function track(type, data) {
    var s = session();
    queue.push(Object.assign({
      eventId: uuid(),
      type: type,
      occurredAt: new Date().toISOString(),
      sessionId: s.id,
      visitorId: visitorId(),
      sessionStartedAt: new Date(s.startedAt).toISOString(),
      sessionSeq: s.seq
    }, data || {}));
    if (queue.length >= 50) flush(false); else schedule();
  }

  // 页面浏览（FR-STAT-001/007）：SPA 路由去重
  var lastPath = null;
  function pageView(isSpaNav) {
    var path = location.pathname;
    if (isSpaNav && path === lastPath) return;
    lastPath = path;
    track('PAGEVIEW', { routePath: path, title: document.title, spaNav: !!isSpaNav });
  }

  pageView(false);
  if (autoTrackSpa) {
    (function () {
      var push = history.pushState, replace = history.replaceState;
      history.pushState = function () { push.apply(history, arguments); pageView(true); };
      history.replaceState = function () { replace.apply(history, arguments); pageView(true); };
      window.addEventListener('popstate', function () { pageView(true); });
    })();
  }

  // 页面卸载：DWELL + sendBeacon 补发（FR-STAT-001）
  var loadAt = Date.now();
  window.addEventListener('pagehide', function () {
    if (Date.now() - loadAt > 1000) {
      track('DWELL', { routePath: location.pathname, durationMs: Date.now() - loadAt });
    }
    flush(true);
  });

  // 手动接口
  window.powercmsAnalytics = {
    trackPageView: function (path, title) {
      lastPath = path;
      track('PAGEVIEW', { routePath: path, title: title || document.title, spaNav: true });
    },
    trackEvent: track,
    flush: flush
  };
})();
