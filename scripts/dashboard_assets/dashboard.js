/* neXus PO + Orchestrator workbench -- vanilla JS, no build step, no
 * third-party dependency (AC-6). Talks only to this same origin's /api/*
 * endpoints; every state-changing action ends in a relay-write on the
 * backend (AC-4) -- this file never runs git/gh/shell, it only POSTs a
 * registered action id or a message and shows the honest result. */
(function () {
  "use strict";

  var TOKEN = null;
  var POLL_TIMER = null;
  var SELECTED_MOVEMENT = null;
  var RECENT_EXPANDED = false;
  var TOKEN_STORAGE_KEY = "nexus.dashboard.token";

  function readTokenFromHash() {
    var match = /(?:^|[#&])t=([^&]+)/.exec(window.location.hash);
    if (match) {
      TOKEN = decodeURIComponent(match[1]);
      try { sessionStorage.setItem(TOKEN_STORAGE_KEY, TOKEN); } catch (_) {}
      history.replaceState(null, "", window.location.pathname + window.location.search);
    } else {
      try { TOKEN = sessionStorage.getItem(TOKEN_STORAGE_KEY); } catch (_) { TOKEN = null; }
    }
  }

  function api(method, path, body) {
    var opts = { method: method, headers: {} };
    if (TOKEN) opts.headers["Authorization"] = "Bearer " + TOKEN;
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    return fetch(path, opts).then(function (resp) {
      return resp.json().then(function (data) {
        if (!resp.ok) {
          var err = new Error((data && data.error) || ("HTTP " + resp.status));
          err.data = data;
          throw err;
        }
        return data;
      });
    });
  }

  function esc(s) {
    var div = document.createElement("div");
    div.textContent = s === undefined || s === null ? "" : String(s);
    return div.innerHTML;
  }

  function fmtAge(seconds) {
    if (seconds === null || seconds === undefined || seconds < 0) return "-";
    var total = Math.floor(seconds);
    if (total < 60) return total + "s";
    var m = Math.floor(total / 60), s = total % 60;
    if (m < 60) return m + "m" + String(s).padStart(2, "0") + "s";
    var h = Math.floor(m / 60);
    return h + "h" + String(m % 60).padStart(2, "0") + "m";
  }

  // -- main list ------------------------------------------------------

  function refresh() {
    api("GET", "/api/movements").then(renderMovements).catch(function (err) {
      document.getElementById("summary-strip").textContent = "error loading movements: " + err.message;
    });
  }

  function renderMovements(data) {
    var s = data.summary || {};
    document.getElementById("summary-strip").textContent =
      s.running + " running - " + s.awaiting_decision + " awaiting decision - " + s.in_integration + " in integration";

    var movements = data.movements || [];
    var awaiting = movements.filter(function (m) { return m.pending_action; });
    var running = movements.filter(function (m) { return m.work_stage !== "merged"; });
    var integration = movements.filter(function (m) { return m.work_stage === "integration"; });
    var recent = movements.filter(function (m) { return m.work_stage === "merged"; });

    renderCards("cards-awaiting", awaiting, true);
    renderCards("cards-running", running, false);
    renderCards("cards-integration", integration, false);
    renderCards("cards-recent", recent, false);

    if (SELECTED_MOVEMENT) {
      var still = movements.find(function (m) { return m.movement_id === SELECTED_MOVEMENT; });
      if (still) openDetail(SELECTED_MOVEMENT, false);
    }
  }

  function renderCards(containerId, list, withExecCard) {
    var el = document.getElementById(containerId);
    el.innerHTML = "";
    if (!list.length) {
      var empty = document.createElement("p");
      empty.className = "muted";
      empty.textContent = "(none)";
      el.appendChild(empty);
      return;
    }
    list.forEach(function (m) {
      el.appendChild(renderCard(m, withExecCard));
    });
  }

  function renderCard(m, withExecCard) {
    var card = document.createElement("div");
    card.className = "card";
    card.onclick = function (ev) {
      if (ev.target.closest(".exec-card")) return;
      openDetail(m.movement_id, true);
    };

    var top = document.createElement("div");
    top.className = "card-top";
    top.innerHTML =
      '<span class="card-id">' + esc(m.movement_id) + '</span>' +
      '<span class="badge ' + esc(m.process_status) + '">' + esc(m.process_status) + '</span>';
    card.appendChild(top);

    var fields = document.createElement("div");
    fields.className = "card-fields";
    fields.innerHTML =
      '<span>stage: ' + esc(m.work_stage) + '</span>' +
      '<span>last activity: ' + esc(m.last_activity || "unknown") + '</span>' +
      '<span>branch: ' + esc(m.branch || "-") + '</span>' +
      (m.open_pr ? '<span>PR #' + esc(m.open_pr.number) + " (" + esc(m.open_pr.ci) + ")</span>" : "");
    card.appendChild(fields);

    if (withExecCard && m.pending_action) {
      card.appendChild(renderExecCard(m));
    }
    return card;
  }

  function renderExecCard(m) {
    var pa = m.pending_action;
    var box = document.createElement("div");
    box.className = "exec-card";
    box.innerHTML =
      '<div><strong>Requested:</strong> ' + esc(pa.requested_action) + '</div>' +
      '<div class="muted">' + esc(pa.detail) + '</div>' +
      '<div class="muted">Blocker source: ' + esc(pa.blocker_source) + '</div>';

    if (pa.blocker_source === "platform_policy") {
      var blocked = document.createElement("div");
      blocked.className = "platform-blocked";
      blocked.textContent = "Platform/policy block -- no button here can override this.";
      box.appendChild(blocked);
      return box;
    }

    var buttons = document.createElement("div");
    buttons.className = "buttons";

    var primaryLabel = pa.primary_operation === "route_to_engineer" ? "Route to engineer" : "Approve & record decision";
    var primaryBtn = document.createElement("button");
    primaryBtn.className = "primary";
    primaryBtn.textContent = primaryLabel;
    primaryBtn.onclick = function (ev) {
      ev.stopPropagation();
      executeAction(m.movement_id, pa.primary_action_id, primaryBtn);
    };
    buttons.appendChild(primaryBtn);

    var rejectBtn = document.createElement("button");
    rejectBtn.className = "danger";
    rejectBtn.textContent = "Reject";
    rejectBtn.onclick = function (ev) {
      ev.stopPropagation();
      executeAction(m.movement_id, pa.reject_action_id, rejectBtn);
    };
    buttons.appendChild(rejectBtn);

    box.appendChild(buttons);
    return box;
  }

  function executeAction(movementId, actionId, btn) {
    if (!actionId) return;
    btn.disabled = true;
    api("POST", "/api/movements/" + encodeURIComponent(movementId) + "/actions/" + encodeURIComponent(actionId) + "/execute", {})
      .then(function () { refresh(); })
      .catch(function (err) {
        alert("Action failed: " + err.message);
        btn.disabled = false;
      });
  }

  // -- detail panel -----------------------------------------------------

  function openDetail(movementId, resetTab) {
    SELECTED_MOVEMENT = movementId;
    var panel = document.getElementById("detail-panel");
    panel.classList.remove("hidden");
    document.getElementById("detail-title").textContent = movementId;
    if (resetTab) selectTab("summary");

    api("GET", "/api/movements/" + encodeURIComponent(movementId)).then(function (detail) {
      renderSummaryTab(detail);
      renderActivityTab(detail);
      renderConversationTab(detail);
      renderChangesTab(detail);
      renderEvidenceTab(detail);
      renderHistoryTab(detail);
    }).catch(function (err) {
      document.getElementById("pane-summary").textContent = "error: " + err.message;
    });
  }

  function closeDetail() {
    SELECTED_MOVEMENT = null;
    document.getElementById("detail-panel").classList.add("hidden");
  }

  function renderSummaryTab(d) {
    var task = d.approved_task || {};
    var report = task.report || {};
    var ac = report.acceptance_criteria || [];
    var html = '<dl class="kv">' +
      '<dt>Process status</dt><dd>' + esc(d.process_status) + '</dd>' +
      '<dt>Work stage</dt><dd>' + esc(d.work_stage) + '</dd>' +
      '<dt>Last activity</dt><dd>' + esc(d.last_activity || "unknown") + '</dd>' +
      '<dt>Branch</dt><dd>' + esc(d.branch || "-") + '</dd>' +
      '<dt>Worktree</dt><dd>' + esc(d.worktree_path || "-") + '</dd>' +
      '<dt>Objective</dt><dd>' + esc(report.objective || "-") + '</dd>' +
      '</dl>';
    if (ac.length) {
      html += "<strong>Acceptance criteria</strong><ul>";
      ac.forEach(function (item) { html += "<li>" + esc(item) + "</li>"; });
      html += "</ul>";
    }
    document.getElementById("pane-summary").innerHTML = html;
  }

  function renderActivityTab(d) {
    api("GET", "/api/movements/" + encodeURIComponent(d.movement_id) + "/log?tail=200").then(function (log) {
      var lines = (log.lines || []).join("\n") || "(no log yet)";
      document.getElementById("pane-activity").innerHTML =
        '<div class="muted">last observed: ' + esc(d.last_activity || "unknown") + '</div>' +
        '<pre class="log">' + esc(lines) + '</pre>';
    });
  }

  function renderConversationTab(d) {
    var entries = d.relay_entries || [];
    var html = "";
    entries.forEach(function (e) {
      html += '<div class="entry"><div class="entry-head">#' + esc(e.seq) + " " + esc(e.actor) + " " +
        esc(e.marker) + " @ " + esc(e.timestamp) + '</div>';
      if (e.subject) html += "<div><strong>" + esc(e.subject) + "</strong></div>";
      if (e.text) html += "<div>" + esc(e.text) + "</div>";
      html += "</div>";
    });
    if (d.outbox && d.outbox.length) {
      html += '<div class="muted"><strong>Queued, not yet appended (not this movement\'s PO turn):</strong></div>';
      d.outbox.forEach(function (o) {
        html += '<div class="entry"><div class="entry-head">saved ' + esc(o.saved_at) + '</div><div>' + esc(o.text) + '</div></div>';
      });
    }
    html +=
      '<div class="msg-box">' +
      '<textarea id="msg-text" placeholder="Send a note to this movement..."></textarea>' +
      '<button id="msg-send">Send</button>' +
      '<div class="msg-state" id="msg-state"></div>' +
      '</div>';
    document.getElementById("pane-conversation").innerHTML = html;

    document.getElementById("msg-send").onclick = function () {
      var text = document.getElementById("msg-text").value.trim();
      if (!text) return;
      api("POST", "/api/movements/" + encodeURIComponent(d.movement_id) + "/message", { text: text })
        .then(function (result) {
          document.getElementById("msg-state").textContent =
            "Saved -> queued for next resume" + (result.appended ? "" : " (not this movement's PO turn yet -- saved locally)");
          document.getElementById("msg-text").value = "";
          refresh();
        })
        .catch(function (err) {
          document.getElementById("msg-state").textContent = "error: " + err.message;
        });
    };
  }

  function renderChangesTab(d) {
    var html = '<dl class="kv">' +
      '<dt>Branch</dt><dd>' + esc(d.branch || "-") + '</dd>' +
      '<dt>Ahead / behind</dt><dd>' + esc(d.ahead === null || d.ahead === undefined ? "unknown" : d.ahead) +
      " / " + esc(d.behind === null || d.behind === undefined ? "unknown" : d.behind) + '</dd>' +
      '<dt>Open PR</dt><dd>' + (d.open_pr ? ("#" + esc(d.open_pr.number) + " (" + esc(d.open_pr.state) + ", CI " + esc(d.open_pr.ci) + ")") : "none / unknown (gh not available or no PR yet)") + '</dd>' +
      '</dl>';
    document.getElementById("pane-changes").innerHTML = html;
  }

  function renderEvidenceTab() {
    document.getElementById("pane-evidence").innerHTML =
      '<p class="muted">No artifact producer configured yet -- real screenshots/render output are explicitly deferred past this first delivery (project/backlog.json scope boundary).</p>';
  }

  function renderHistoryTab(d) {
    var entries = d.relay_entries || [];
    var html = "";
    entries.forEach(function (e) {
      html += '<div class="entry"><div class="entry-head">#' + esc(e.seq) + " " + esc(e.marker) + " @ " + esc(e.timestamp) + '</div>' +
        "<div>" + esc(e.actor) + (e.subject ? " -- " + esc(e.subject) : "") + "</div></div>";
    });
    document.getElementById("pane-history").innerHTML = html || '<p class="muted">no entries</p>';
  }

  function selectTab(name) {
    document.querySelectorAll("#detail-tabs button").forEach(function (b) {
      b.classList.toggle("active", b.dataset.tab === name);
    });
    document.querySelectorAll(".tab-pane").forEach(function (p) {
      p.classList.toggle("active", p.dataset.pane === name);
    });
  }

  // -- config -----------------------------------------------------------

  function loadConfig() {
    api("GET", "/api/config").then(function (cfg) {
      document.getElementById("poll-interval").value = cfg.poll_interval_seconds;
      schedulePoll(cfg.poll_interval_seconds);
    }).catch(function () { schedulePoll(5); });
  }

  function schedulePoll(seconds) {
    if (POLL_TIMER) clearInterval(POLL_TIMER);
    POLL_TIMER = setInterval(refresh, Math.max(2, seconds) * 1000);
  }

  function saveConfig() {
    var value = parseInt(document.getElementById("poll-interval").value, 10);
    if (!value) return;
    var stateEl = document.getElementById("poll-save-state");
    api("PUT", "/api/config", { poll_interval_seconds: value })
      .then(function (cfg) {
        stateEl.textContent = "saved";
        schedulePoll(cfg.poll_interval_seconds);
        setTimeout(function () { stateEl.textContent = ""; }, 2000);
      })
      .catch(function (err) { stateEl.textContent = "error: " + err.message; });
  }

  // -- wiring -------------------------------------------------------------

  document.addEventListener("DOMContentLoaded", function () {
    readTokenFromHash();

    document.getElementById("detail-close").onclick = closeDetail;
    document.querySelectorAll("#detail-tabs button").forEach(function (btn) {
      btn.onclick = function () { selectTab(btn.dataset.tab); };
    });
    document.getElementById("recent-toggle").onclick = function () {
      RECENT_EXPANDED = !RECENT_EXPANDED;
      document.getElementById("section-recent").classList.toggle("collapsed", !RECENT_EXPANDED);
    };
    document.getElementById("poll-interval").addEventListener("change", saveConfig);

    loadConfig();
    refresh();
  });
})();
