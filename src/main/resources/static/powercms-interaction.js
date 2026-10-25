(function () {
  if (window.__powercmsInteractionLoaded) return;
  window.__powercmsInteractionLoaded = true;

  function attr(el, name, fallback) {
    var v = el.getAttribute(name);
    return v == null || v === "" ? fallback : v;
  }
  function apiBase(el) {
    var b = attr(el, "data-api-base", "");
    return b ? b.replace(/\/$/, "") : "";
  }
  function url(el, path, query) {
    var u = apiBase(el) + path;
    if (query) {
      var q = new URLSearchParams(query).toString();
      if (q) u += (u.indexOf("?") >= 0 ? "&" : "?") + q;
    }
    return u;
  }
  function escapeHtml(s) {
    return String(s || "")
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }
  function sha256Hex(text) {
    var enc = new TextEncoder().encode(text);
    return crypto.subtle.digest("SHA-256", enc).then(function (buf) {
      var bytes = new Uint8Array(buf);
      var hex = "";
      for (var i = 0; i < bytes.length; i++) hex += bytes[i].toString(16).padStart(2, "0");
      return hex;
    });
  }
  function solvePow(token) {
    var n = 0;
    function step() {
      if (n > 400000) return Promise.reject(new Error("请完成人机验证"));
      var nonce = String(n++);
      return sha256Hex(token + nonce).then(function (hex) {
        return hex.slice(0, 2) === "00" ? nonce : step();
      });
    }
    return step();
  }
  function request(el, method, path, body, query) {
    var opts = { method: method, headers: { "Content-Type": "application/json" }, credentials: "include" };
    if (body) opts.body = JSON.stringify(body);
    return fetch(url(el, path, query), opts).then(function (res) {
      return res.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = { message: text }; }
        if (!res.ok) {
          var err = new Error((data && (data.message || data.error)) || ("HTTP " + res.status));
          err.status = res.status;
          throw err;
        }
        return data;
      });
    });
  }
  function token(el, kind, codeOrId) {
    return request(el, "GET", "/api/v1/public/sites/" + attr(el, "data-site") + "/interaction/submission-token",
      null, { kind: kind, codeOrId: codeOrId || "-" }).then(function (info) {
      var t = typeof info === "string" ? info : info.token;
      var need = !!(info && info.captchaRequired);
      if (!need) return { token: t, nonce: "" };
      return solvePow(t).then(function (nonce) { return { token: t, nonce: nonce }; });
    });
  }
  function renderList(host, items, nested) {
    var html = items.map(function (item) {
      var replies = nested && item.replies && item.replies.length
        ? "<div class=\"pcms-ix-replies\">" + renderList(host, item.replies, false) + "</div>" : "";
      return "<article class=\"pcms-ix-item\" data-id=\"" + escapeHtml(item.id) + "\">"
        + "<div class=\"pcms-ix-meta\"><strong>" + escapeHtml(item.authorName) + "</strong>"
        + "<time>" + escapeHtml(item.createdAt || "") + "</time></div>"
        + "<p class=\"pcms-ix-body\">" + escapeHtml(item.body).replace(/\n/g, "<br>") + "</p>"
        + "<div class=\"pcms-ix-actions\">"
        + "<button type=\"button\" data-act=\"like\">赞 " + (item.likeCount || 0) + "</button>"
        + (nested ? "<button type=\"button\" data-act=\"reply\">回复</button>" : "")
        + "<button type=\"button\" data-act=\"report\">举报</button></div>"
        + replies + "</article>";
    }).join("");
    return html || "<p class=\"pcms-ix-empty\">暂无内容</p>";
  }
  function bindActions(el, site, kind) {
    el.addEventListener("click", function (ev) {
      var btn = ev.target.closest("[data-act]");
      if (!btn) return;
      var item = btn.closest(".pcms-ix-item");
      if (!item) return;
      var id = item.getAttribute("data-id");
      var act = btn.getAttribute("data-act");
      if (act === "like") {
        request(el, "POST", "/api/v1/public/sites/" + site + "/interaction/messages/" + id + "/like")
          .then(function (r) { btn.textContent = "赞 " + (r.likeCount || 0); })
          .catch(function (e) { alert(e.message); });
      } else if (act === "report") {
        var reason = prompt("举报理由：SPAM / PORN / ABUSE / POLITICAL / OTHER", "SPAM");
        if (!reason) return;
        request(el, "POST", "/api/v1/public/sites/" + site + "/interaction/messages/" + id + "/report",
          { reason: reason, detail: "" })
          .then(function () { alert("已提交举报"); })
          .catch(function (e) { alert(e.message); });
      } else if (act === "reply") {
        var box = el.querySelector("form.pcms-ix-form textarea");
        if (box) { box.dataset.parentId = id; box.focus(); }
      }
    });
  }
  function mountForm(el, site, kind, submit) {
    var allowGuest = attr(el, "data-allow-guest", "true") !== "false";
    var form = document.createElement("form");
    form.className = "pcms-ix-form";
    form.innerHTML = (allowGuest ? "<input name=\"authorName\" placeholder=\"昵称\" maxlength=\"50\">" : "")
      + "<textarea name=\"body\" rows=\"3\" maxlength=\"4000\" placeholder=\"请输入内容\" required></textarea>"
      + "<input name=\"website\" autocomplete=\"off\" tabindex=\"-1\" style=\"display:none\">"
      + "<button type=\"submit\">提交</button>";
    form.addEventListener("submit", function (ev) {
      ev.preventDefault();
      var fd = new FormData(form);
      submit({
        body: String(fd.get("body") || ""),
        authorName: String(fd.get("authorName") || ""),
        honeypot: String(fd.get("website") || ""),
        parentId: form.querySelector("textarea").dataset.parentId || null
      }).then(function () {
        form.reset();
        delete form.querySelector("textarea").dataset.parentId;
        load(el);
      }).catch(function (e) { alert(e.message); });
    });
    el.appendChild(form);
  }
  function load(el) {
    var site = attr(el, "data-site");
    var kind = attr(el, "data-interaction", "comment");
    var list = el.querySelector(".pcms-ix-list");
    if (!list) {
      list = document.createElement("div");
      list.className = "pcms-ix-list";
      el.insertBefore(list, el.querySelector("form"));
    }
    var path, query;
    if (kind === "board") {
      path = "/api/v1/public/sites/" + site + "/interaction/boards/" + encodeURIComponent(attr(el, "data-board-code", "default")) + "/messages";
      query = { page: "0", size: attr(el, "data-page-size", "20") };
    } else {
      path = "/api/v1/public/sites/" + site + "/interaction/comments";
      query = {
        threadType: attr(el, "data-thread-type", "CONTENT"),
        page: "0",
        size: attr(el, "data-page-size", "20")
      };
      var tid = attr(el, "data-thread-id", "");
      var key = attr(el, "data-thread-key", "");
      if (tid) query.threadId = tid;
      if (key) query.threadKey = key;
    }
    request(el, "GET", path, null, query).then(function (page) {
      list.innerHTML = renderList(el, (page && page.items) || [], kind !== "board" && attr(el, "data-nested", "true") !== "false");
    }).catch(function (e) {
      list.innerHTML = "<p class=\"pcms-ix-error\">" + escapeHtml(e.message) + "</p>";
    });
  }
  function initComment(el) {
    var site = attr(el, "data-site");
    if (el.dataset.ready) return;
    el.dataset.ready = "1";
    el.classList.add("pcms-ix");
    mountForm(el, site, "COMMENT", function (payload) {
      var subject = attr(el, "data-thread-id", "") || attr(el, "data-thread-key", "-");
      return token(el, "COMMENT", subject).then(function (tok) {
        return request(el, "POST", "/api/v1/public/sites/" + site + "/interaction/comments", {
          threadType: attr(el, "data-thread-type", "CONTENT"),
          threadId: attr(el, "data-thread-id", "") || null,
          threadKey: attr(el, "data-thread-key", "") || null,
          parentId: payload.parentId,
          body: payload.body,
          authorName: payload.authorName,
          honeypot: payload.honeypot,
          submissionToken: tok.token,
          captchaNonce: tok.nonce
        });
      });
    });
    bindActions(el, site, "COMMENT");
    load(el);
  }
  function initBoard(el) {
    var site = attr(el, "data-site");
    if (el.dataset.ready) return;
    el.dataset.ready = "1";
    el.classList.add("pcms-ix");
    var code = attr(el, "data-board-code", "default");
    mountForm(el, site, "BOARD", function (payload) {
      return token(el, "BOARD", code).then(function (tok) {
        return request(el, "POST", "/api/v1/public/sites/" + site + "/interaction/boards/" + encodeURIComponent(code) + "/messages", {
          body: payload.body,
          authorName: payload.authorName,
          honeypot: payload.honeypot,
          submissionToken: tok.token,
          captchaNonce: tok.nonce
        });
      });
    });
    bindActions(el, site, "BOARD");
    load(el);
  }
  function questionHtml(q) {
    var inner = "<label class=\"pcms-ix-q-title\">" + escapeHtml(q.title || q.code)
      + (q.required === "true" ? " <span class=\"pcms-ix-req\">*</span>" : "") + "</label>";
    var type = (q.type || "SINGLE").toUpperCase();
    if (type === "TEXT") {
      inner += "<textarea name=\"q_" + escapeHtml(q.code) + "\" rows=\"3\" maxlength=\"4000\"></textarea>";
    } else if (type === "SCALE" || type === "NPS") {
      var max = type === "NPS" ? 10 : 5;
      var min = type === "NPS" ? 0 : 1;
      inner += "<div class=\"pcms-ix-scale\">";
      for (var v = min; v <= max; v++) {
        inner += "<label class=\"pcms-ix-opt\"><input type=\"radio\" name=\"q_" + escapeHtml(q.code)
          + "\" value=\"" + v + "\"> " + v + "</label>";
      }
      inner += "</div>";
    } else {
      var multi = type === "MULTI";
      var opts = q.options || [];
      inner += "<div class=\"pcms-ix-opts\">";
      opts.forEach(function (o) {
        inner += "<label class=\"pcms-ix-opt\"><input type=\"" + (multi ? "checkbox" : "radio")
          + "\" name=\"q_" + escapeHtml(q.code) + "\" value=\"" + escapeHtml(o.value) + "\"> "
          + escapeHtml(o.label || o.value) + "</label>";
      });
      inner += "</div>";
    }
    return "<fieldset class=\"pcms-ix-question\" data-code=\"" + escapeHtml(q.code)
      + "\" data-qtype=\"" + escapeHtml(type) + "\">" + inner + "</fieldset>";
  }
  function collectAnswers(el) {
    var answers = [];
    el.querySelectorAll(".pcms-ix-question").forEach(function (fs) {
      var code = fs.getAttribute("data-code");
      var qtype = fs.getAttribute("data-qtype") || "SINGLE";
      if (qtype === "TEXT") {
        var text = fs.querySelector("textarea");
        answers.push({ code: code, text: text ? text.value : "" });
        return;
      }
      var values = [];
      fs.querySelectorAll("input:checked").forEach(function (input) { values.push(input.value); });
      if (qtype === "SCALE" || qtype === "NPS") {
        answers.push({ code: code, value: values.length ? values[0] : null });
      } else {
        answers.push({ code: code, value: values });
      }
    });
    return answers;
  }
  function initSurveyLike(el, kind) {
    var site = attr(el, "data-site");
    if (el.dataset.ready) return;
    el.dataset.ready = "1";
    el.classList.add("pcms-ix");
    var defId = attr(el, "data-def-id", "");
    var host = document.createElement("div");
    host.className = "pcms-ix-survey";
    el.appendChild(host);
    var path = "/api/v1/public/sites/" + site + "/interaction/" + (kind === "exam" ? "exams" : "surveys") + "/" + encodeURIComponent(defId);
    request(el, "GET", path).then(function (def) {
      var html = "";
      if (def.description) html += "<p class=\"pcms-ix-desc\">" + escapeHtml(def.description).replace(/\n/g, "<br>") + "</p>";
      html += "<form class=\"pcms-ix-form pcms-ix-quiz\">";
      (def.questions || []).forEach(function (q) { html += questionHtml(q); });
      html += "<input name=\"website\" autocomplete=\"off\" tabindex=\"-1\" style=\"display:none\">";
      html += "<button type=\"submit\">提交</button></form>";
      html += "<div class=\"pcms-ix-result\" style=\"display:none\"></div>";
      host.innerHTML = html;
      if (def.timeLimitMinutes > 0) {
        var btn = host.querySelector("button[type=submit]");
        var remain = def.timeLimitMinutes * 60;
        var timer = setInterval(function () {
          remain -= 1;
          if (remain <= 0) {
            clearInterval(timer);
            if (btn && !btn.disabled) host.querySelector("form").requestSubmit ? host.querySelector("form").requestSubmit() : btn.click();
            return;
          }
          if (btn) btn.textContent = "提交 (" + Math.floor(remain / 60) + ":" + ("0" + (remain % 60)).slice(-2) + ")";
        }, 1000);
        el.dataset.deadline = String(Date.now() + remain * 1000);
      }
      host.querySelector("form").addEventListener("submit", function (ev) {
        ev.preventDefault();
        var fd = new FormData(ev.target);
        token(el, kind === "exam" ? "EXAM" : "POLL", defId).then(function (tok) {
          return request(el, "POST", path + "/submissions", {
            answers: collectAnswers(host),
            honeypot: String(fd.get("website") || ""),
            submissionToken: tok.token,
            captchaNonce: tok.nonce,
            startedAt: el.dataset.startedAt ? parseInt(el.dataset.startedAt, 10) : null
          });
        }).then(function (result) {
          var box = host.querySelector(".pcms-ix-result");
          var form = host.querySelector("form");
          form.style.display = "none";
          box.style.display = "block";
          var text = result.gradeStatus === "PENDING_GRADE" ? "已提交，等待评卷。"
            : result.passed === null || result.passed === undefined ? "已提交，感谢参与。"
              : (result.passed ? "已通过！" : "未通过。");
          if (result.score !== null && result.score !== undefined) {
            text += " 得分 " + result.score + (result.totalScore ? " / " + result.totalScore : "");
          }
          box.innerHTML = "<p>" + escapeHtml(text) + "</p>";
        }).catch(function (e) { alert(e.message); });
      });
      el.dataset.startedAt = String(Math.floor(Date.now() / 1000));
    }).catch(function (e) {
      host.innerHTML = "<p class=\"pcms-ix-error\">" + escapeHtml(e.message) + "</p>";
    });
  }
  function initAd(el) {
    var site = attr(el, "data-site");
    if (el.dataset.ready) return;
    el.dataset.ready = "1";
    el.classList.add("pcms-ix");
    var slot = attr(el, "data-slot-code", "");
    var limit = attr(el, "data-limit", "3");
    // §6.5 v1.1 freq_per_user：访客频控票据（localStorage 跨会话携带；§11.11 未同意时服务端不下发）
    var freqKey = "pcms-ix-ad-freq-" + site;
    var visitorKey = "pcms-ix-ad-visitor-" + site;
    function visitorId() {
      try {
        var v = localStorage.getItem(visitorKey);
        if (!v) { v = "v-" + Date.now() + "-" + Math.random().toString(36).slice(2, 10); localStorage.setItem(visitorKey, v); }
        return v;
      } catch (e) { return ""; }
    }
    function saveTicket(adId, ticket) {
      try { localStorage.setItem(freqKey + ":" + adId, ticket); } catch (e) { }
    }
    function adFrame(ad) {
      // §十.6：HTML/富文本素材在 sandbox iframe 内渲染，与主页面隔离，杜绝 XSS
      var iframe = document.createElement("iframe");
      iframe.setAttribute("sandbox", "allow-scripts");
      iframe.style.border = "0";
      if (ad.width) iframe.width = ad.width;
      if (ad.height) iframe.height = ad.height;
      iframe.srcdoc = ad.htmlBody
        || (ad.mediaUrl ? "<a href=\"" + escapeHtml(ad.targetUrl || "#") + "\" target=\"_blank\"><img src=\"" + escapeHtml(ad.mediaUrl) + "\" alt=\"" + escapeHtml(ad.title || "") + "\" style=\"border:0;display:block\" width=\"" + (ad.width || "") + "\"></a>"
          : "<a href=\"" + escapeHtml(ad.targetUrl || "#") + "\" target=\"_blank\">" + escapeHtml(ad.title || "") + "</a>");
      return iframe;
    }
    function render() {
      var q = { slot: slot, limit: limit, visitorId: visitorId() };
      var priorTicket = "";
      try { priorTicket = localStorage.getItem(freqKey + ":last") || ""; } catch (e) { }
      if (priorTicket) q.freqTicket = priorTicket;
      request(el, "GET", "/api/v1/public/sites/" + site + "/interaction/ads",
        null, q).then(function (ads) {
        if (!ads || !ads.length) { el.innerHTML = ""; return; }
        var host = document.createElement("div");
        host.className = "pcms-ix-ads";
        ads.forEach(function (ad) {
          if (ad.freqTicket) {
            saveTicket(ad.id, ad.freqTicket);
            try { localStorage.setItem(freqKey + ":last", ad.freqTicket); } catch (e) { }
          }
          var wrap = document.createElement("div");
          wrap.className = "pcms-ix-ad";
          wrap.appendChild(adFrame(ad));
          // 点击经外层捕获上报（sandbox 内脚本的导航事件由 target=_blank 自行打开）
          wrap.addEventListener("click", function () {
            request(el, "POST", "/api/v1/public/sites/" + site + "/interaction/ads/" + ad.id + "/click")
              .catch(function () {});
          });
          host.appendChild(wrap);
        });
        el.appendChild(host);
        // §6.5：曝光判定 ≥50% 可见且持续 1s 才上报
        Array.prototype.forEach.call(host.children, function (node, i) {
          if (!ads[i]) return;
          var done = false;
          function hit() {
            if (done) return;
            done = true;
            request(el, "POST", "/api/v1/public/sites/" + site + "/interaction/ads/" + ads[i].id + "/impression")
              .catch(function () {});
          }
          if ("IntersectionObserver" in window) {
            var visibleSince = 0;
            var obs = new IntersectionObserver(function (entries) {
              entries.forEach(function (entry) {
                if (entry.intersectionRatio >= 0.5) {
                  if (!visibleSince) {
                    visibleSince = Date.now();
                    setTimeout(function () {
                      if (visibleSince && Date.now() - visibleSince >= 1000) hit();
                    }, 1050);
                  }
                } else if (visibleSince) {
                  visibleSince = 0;
                }
              });
            }, { threshold: [0.5] });
            obs.observe(node);
          } else hit();
        });
      }).catch(function (e) {
        el.innerHTML = "<p class=\"pcms-ix-error\">" + escapeHtml(e.message) + "</p>";
      });
    }
    if (attr(el, "data-lazy-load", "") === "true" && "IntersectionObserver" in window) {
      var lazy = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) { lazy.disconnect(); render(); }
        });
      }, { rootMargin: "200px" });
      lazy.observe(el);
    } else render();
  }

  function wsBase(el) {
    var b = apiBase(el) || location.origin;
    return b.replace(/^http/, "ws");
  }

  function initChat(el) {
    var site = attr(el, "data-site");
    if (el.dataset.ready) return;
    el.dataset.ready = "1";
    el.classList.add("pcms-ix");
    var defId = attr(el, "data-def-id", "");
    var limit = attr(el, "data-limit", "50");
    var host = document.createElement("div");
    host.className = "pcms-ix-chat";
    el.appendChild(host);
    if (!defId) { host.innerHTML = "<p class=\"pcms-ix-error\">聊天室配置缺失</p>"; return; }

    request(el, "GET", "/api/v1/public/sites/" + site + "/interaction/chat-rooms/by-def/" + encodeURIComponent(defId))
      .then(function (room) {
        host.innerHTML =
          "<header class=\"pcms-ix-chat-head\"><strong>" + escapeHtml(room.title || "聊天室") + "</strong></header>"
          + "<div class=\"pcms-ix-chat-list\"></div>"
          + "<form class=\"pcms-ix-form\">"
          + "<input name=\"authorName\" placeholder=\"昵称(2-50字)\" maxlength=\"50\" style=\"display:none\">"
          + "<textarea name=\"body\" rows=\"2\" maxlength=\"2000\" placeholder=\"说点什么…\" required></textarea>"
          + "<button type=\"submit\">发送</button></form>";
        var list = host.querySelector(".pcms-ix-chat-list");
        var form = host.querySelector("form");
        var ws = null;
        var seq = 0;
        var readonly = room.status === "READONLY" || room.status === "CLOSED";
        function append(m) {
          var item = document.createElement("div");
          item.className = "pcms-ix-chat-msg" + (m.status === "PENDING" ? " pcms-ix-chat-pending" : "");
          item.dataset.seq = m.seq;
          item.innerHTML = "<span class=\"pcms-ix-chat-author\">" + escapeHtml(m.authorName || "匿名") + "</span> "
            + "<span class=\"pcms-ix-chat-text\">" + escapeHtml(m.body).replace(/\n/g, "<br>") + "</span>";
          list.appendChild(item);
          list.scrollTop = list.scrollHeight;
          if (m.seq > seq) seq = m.seq;
        }
        function recall(seq) {
          Array.prototype.forEach.call(list.querySelectorAll("[data-seq]"), function (node) {
            if (String(node.dataset.seq) === String(seq)) node.remove();
          });
        }
        function system(text) {
          var item = document.createElement("div");
          item.className = "pcms-ix-chat-system";
          item.textContent = text;
          list.appendChild(item);
        }
        if (readonly) {
          form.querySelector("textarea").disabled = true;
          form.querySelector("button").disabled = true;
        }
        form.addEventListener("submit", function (ev) {
          ev.preventDefault();
          if (!ws || ws.readyState !== WebSocket.OPEN) { system("连接中，请稍候…"); return; }
          var fd = new FormData(form);
          var body = String(fd.get("body") || "").trim();
          if (!body) return;
          ws.send(JSON.stringify({ type: "MESSAGE", payload: { body: body } }));
          form.querySelector("textarea").value = "";
        });
        function connect() {
          request(el, "GET", "/api/v1/public/sites/" + site + "/interaction/chat-ticket",
            null, { roomId: room.id }).then(function (t) {
            var q = "ticket=" + encodeURIComponent(t.ticket);
            var nameInput = form.querySelector("input[name=authorName]");
            if (t.guest) {
              nameInput.style.display = "block";
              var name = (nameInput.value || "").trim();
              if (name) q += "&name=" + encodeURIComponent(name);
            }
            ws = new WebSocket(wsBase(el) + "/ws/chat/" + room.id + "?" + q);
            ws.onmessage = function (ev) {
              var frame;
              try { frame = JSON.parse(ev.data); } catch (e) { return; }
              if (frame.type === "MESSAGE") append(frame.payload);
              else if (frame.type === "SYSTEM") system((frame.payload && frame.payload.message) || "提示");
              else if (frame.type === "RECALL") recall(frame.seq);
              else if (frame.type === "HISTORY") {
                var items = (frame.payload && frame.payload.items) || [];
                items.slice().reverse().forEach(append);
              }
            };
            ws.onclose = function () { ws = null; setTimeout(connect, 3000); };
          }).catch(function (e) { system(e.message); });
        }
        request(el, "GET", "/api/v1/public/sites/" + site + "/interaction/chat-rooms/" + room.id + "/messages",
          null, { before: "0", limit: limit }).then(function (page) {
          ((page && page.items) || []).slice().reverse().forEach(append);
          if (page && page.cursor) seq = page.cursor;
          connect();
        }).catch(function (e) { system(e.message); });
      }).catch(function (e) {
        host.innerHTML = "<p class=\"pcms-ix-error\">" + escapeHtml(e.message) + "</p>";
      });
  }

  function boot() {
    document.querySelectorAll("[data-interaction]").forEach(function (el) {
      var kind = attr(el, "data-interaction", "");
      if (kind === "comment") initComment(el);
      else if (kind === "board") initBoard(el);
      else if (kind === "survey") initSurveyLike(el, "survey");
      else if (kind === "exam") initSurveyLike(el, "exam");
      else if (kind === "ad") initAd(el);
      else if (kind === "chat") initChat(el);
      else if (!el.dataset.ready) {
        el.dataset.ready = "1";
        el.innerHTML = "<p class=\"pcms-ix-empty\">该互动组件将在后续版本提供</p>";
      }
    });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
