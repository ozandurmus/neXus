/* neXus PO + Orchestrator workbench -- vanilla JS, no build step, no
 * third-party dependency (AC-6/AC-11). Talks only to this same origin's
 * /api/* endpoints; every state-changing action ends in a relay-write on
 * the backend (AC-4 of the original workbench contract) -- this file never
 * runs git/gh/shell, it only POSTs a registered action id or a message and
 * shows the honest result.
 *
 * GOV.ORCH.4 section 3.5: the first screen is a Kanban board (six columns:
 * Awaiting you / Running / Silent-stuck / Integration / Failed / Done),
 * built from GET /api/board; clicking a card opens a detail panel BELOW the
 * board (not a modal, not a side pane) with collapsible sections built
 * from GET /api/movements/<id>, /traffic and /log. Section 3.5's own token
 * fix: the bearer token from the #t= fragment is persisted in
 * localStorage and reused on reload; with no token known at all, the page
 * shows one clear "paste the token" panel and makes no /api/* request.
 */
(function () {
  "use strict";

  var TOKEN_KEY = "nexus_dashboard_token";
  var SECTIONS_KEY = "nexus_dashboard_open_sections";
  var DONE_EXPANDED_LIMIT = 10;

  var TOKEN = null;
  var POLL_TIMER = null;
  var SELECTED_MOVEMENT = null;
  var DONE_EXPANDED = false;

  var COLUMNS = [
    { key: "awaiting_you", title: "Awaiting you" },
    { key: "running", title: "Running" },
    { key: "silent", title: "Silent / stuck" },
    { key: "integration", title: "Integration" },
    { key: "failed", title: "Failed" },
    { key: "done", title: "Done" },
  ];

  // -- small storage helpers -- never let a blocked/absent localStorage
  // (private window, locked-down profile) crash the page.

  function storageGet(key) {
    try { return window.localStorage.getItem(key); } catch (e) { return null; }
  }
  function storageSet(key, value) {
    try { window.localStorage.setItem(key, value); } catch (e) { /* ignore */ }
  }

  function readOpenSections() {
    try { return JSON.parse(storageGet(SECTIONS_KEY) || "{}"); } catch (e) { return {}; }
  }
  function saveOpenSections(map) {
    storageSet(SECTIONS_KEY, JSON.stringify(map));
  }

  // -- token handling (section 3.5's "Token handling fix") --------------

  function readTokenFromHash() {
    var match = /(?:^|[#&])t=([^&]+)/.exec(window.location.hash);
    if (match) {
      var token = decodeURIComponent(match[1]);
      history.replaceState(null, "", window.location.pathname + window.location.search);
      return token;
    }
    return null;
  }

  function showTokenPanel(message) {
    document.getElementById("token-panel").classList.remove("hidden");
    document.getElementById("board-wrap").classList.add("hidden");
    document.getElementById("token-error").textContent = message || "";
  }

  function hideTokenPanel() {
    document.getElementById("token-panel").classList.add("hidden");
    document.getElementById("board-wrap").classList.remove("hidden");
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
          err.status = resp.status;
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

  function fmtIdle(seconds) {
    if (seconds === null || seconds === undefined) return "-";
    if (seconds < 60) return "active";
    return "idle " + fmtAge(seconds);
  }

  function fmtTokens(usage) {
    if (!usage) return "no usage yet";
    var pct = Math.round((usage.cache_hit_ratio || 0) * 100);
    var parts = (usage.input_tokens || 0) + " in / " + (usage.cache_read_input_tokens || 0) +
      " cache-read / " + (usage.output_tokens || 0) + " out (" + pct + "% cache)";
    if (usage.cost_usd !== null && usage.cost_usd !== undefined) {
      parts += " -- $" + usage.cost_usd.toFixed(4);
    } else if (usage.cost_source) {
      parts += " -- cost " + usage.cost_source;
    }
    return parts;
  }

  function fmtModelEffort(row) {
    var model = row.model_observed || (row.model_requested ? row.model_requested + " (requested)" : "-");
    var effort = row.effort_observed || (row.effort_requested ? row.effort_requested + " (requested)" : null);
    return esc(row.provider || "-") + " · " + esc(model) + (effort ? " · " + esc(effort) : "");
  }

  // -- board -------------------------------------------------------------

  function bucketBoard(board) {
    var buckets = { awaiting_you: [], running: [], silent: [], integration: [], failed: [], done: [] };
    (board.awaiting_po || []).forEach(function (r) { buckets.awaiting_you.push(r); });
    (board.open || []).forEach(function (r) {
      if (r.health === "silent") buckets.silent.push(r);
      else if (r.health === "failed") buckets.failed.push(r);
      else if (r.stage === "integration") buckets.integration.push(r);
      else buckets.running.push(r);
    });
    (board.closed || []).slice().sort(function (a, b) {
      return (b.ended_at || b.started_at || "").localeCompare(a.ended_at || a.started_at || "");
    }).forEach(function (r) { buckets.done.push(r); });
    return buckets;
  }

  function refresh() {
    if (!TOKEN) return; // AC-13: never poll without a known token
    Promise.all([api("GET", "/api/board"), api("GET", "/api/movements")])
      .then(function (results) {
        renderSummary(results[1].summary || {});
        renderBoard(bucketBoard(results[0]));
        if (SELECTED_MOVEMENT) refreshDetail(SELECTED_MOVEMENT);
      })
      .catch(function (err) {
        if (err.status === 401) {
          storageSet(TOKEN_KEY, "");
          TOKEN = null;
          showTokenPanel("Saved token was rejected -- paste a fresh one.");
          return;
        }
        document.getElementById("summary-strip").textContent = "error loading board: " + err.message;
      });
  }

  function renderSummary(s) {
    var tokensToday = s.tokens_today || 0;
    var costToday = s.cost_today === null || s.cost_today === undefined ? "-" : "$" + s.cost_today.toFixed(4);
    document.getElementById("summary-strip").textContent =
      (s.running || 0) + " running · " + (s.silent || 0) + " silent · " +
      (s.awaiting_decision || 0) + " awaiting you · " + (s.failed || 0) + " failed · " +
      tokensToday + " tokens today · " + costToday + " today";
  }

  function renderBoard(buckets) {
    var el = document.getElementById("board-columns");
    el.innerHTML = "";
    COLUMNS.forEach(function (col) {
      var rows = buckets[col.key] || [];
      var section = document.createElement("section");
      section.className = "board-column";

      var header = document.createElement("h2");
      header.textContent = col.title + " (" + rows.length + ")";
      section.appendChild(header);

      var shown = rows;
      var showAllToggle = null;
      if (col.key === "done" && rows.length > DONE_EXPANDED_LIMIT && !DONE_EXPANDED) {
        shown = rows.slice(0, DONE_EXPANDED_LIMIT);
      }

      var list = document.createElement("div");
      list.className = "column-cards";
      if (!shown.length) {
        var empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "(none)";
        list.appendChild(empty);
      } else {
        shown.forEach(function (row) { list.appendChild(renderCard(row)); });
      }
      section.appendChild(list);

      if (col.key === "done" && rows.length > DONE_EXPANDED_LIMIT) {
        showAllToggle = document.createElement("button");
        showAllToggle.className = "show-all";
        showAllToggle.textContent = DONE_EXPANDED ? "show latest " + DONE_EXPANDED_LIMIT : "show all " + rows.length;
        showAllToggle.onclick = function () {
          DONE_EXPANDED = !DONE_EXPANDED;
          refresh();
        };
        section.appendChild(showAllToggle);
      }

      el.appendChild(section);
    });
  }

  function renderCard(row) {
    var card = document.createElement("div");
    card.className = "board-card";
    card.onclick = function () { openDetail(row.movement_id); };

    var top = document.createElement("div");
    top.className = "card-top";
    top.innerHTML =
      '<span class="card-id">' + esc(row.movement_id) + '</span>' +
      '<span class="badge health-' + esc(row.health) + '">' + esc(row.health) + '</span>';
    card.appendChild(top);

    var objective = document.createElement("div");
    objective.className = "card-objective";
    objective.textContent = (row.objective || "(no objective on file)").split("\n")[0];
    card.appendChild(objective);

    var meta = document.createElement("div");
    meta.className = "card-fields";
    meta.innerHTML =
      '<span>' + fmtModelEffort(row) + '</span>' +
      '<span>' + esc(row.process_status || "-") + ' · ' + esc(row.stage || "-") + '</span>' +
      '<span>duration ' + fmtAge(row.duration_s) + ' · ' + esc(fmtIdle(row.idle_seconds)) + '</span>' +
      '<span>' + esc(fmtTokens(row.usage)) + '</span>' +
      (row.pr_number ? '<span>PR #' + esc(row.pr_number) + (row.pr_state ? " (" + esc(row.pr_state) + ")" : "") + '</span>' : '');
    card.appendChild(meta);

    return card;
  }

  // -- detail panel (below the board) ------------------------------------

  function applyOpenSections() {
    var open = readOpenSections();
    document.querySelectorAll(".detail-section").forEach(function (el) {
      var key = el.dataset.section;
      if (Object.prototype.hasOwnProperty.call(open, key)) {
        el.open = !!open[key];
      }
    });
  }

  function wireSectionMemory() {
    document.querySelectorAll(".detail-section").forEach(function (el) {
      el.addEventListener("toggle", function () {
        var open = readOpenSections();
        open[el.dataset.section] = el.open;
        saveOpenSections(open);
      });
    });
  }

  function openDetail(movementId) {
    SELECTED_MOVEMENT = movementId;
    document.getElementById("detail-panel").classList.remove("hidden");
    document.getElementById("detail-title").textContent = movementId;
    refreshDetail(movementId);
  }

  function closeDetail() {
    SELECTED_MOVEMENT = null;
    document.getElementById("detail-panel").classList.add("hidden");
  }

  function refreshDetail(movementId) {
    api("GET", "/api/movements/" + encodeURIComponent(movementId)).then(function (detail) {
      renderSummaryPane(detail);
      renderChangesPane(detail);
      renderEvidencePane(detail);
      renderUsagePane(detail);
      renderLogPane(detail);
    }).catch(function (err) {
      document.getElementById("pane-summary").textContent = "error: " + err.message;
    });
    api("GET", "/api/movements/" + encodeURIComponent(movementId) + "/traffic").then(function (data) {
      renderTrafficPane(movementId, data.traffic || []);
    }).catch(function (err) {
      document.getElementById("pane-traffic").textContent = "error: " + err.message;
    });
  }

  function renderSummaryPane(d) {
    var task = d.approved_task || {};
    var report = task.report || {};
    var ac = report.acceptance_criteria || [];
    var html = '<dl class="kv">' +
      '<dt>Process status</dt><dd>' + esc(d.process_status) + '</dd>' +
      '<dt>Health</dt><dd>' + esc(d.health) + '</dd>' +
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
    if (d.pending_action) {
      html += renderExecCardHtml(d.pending_action);
    }
    document.getElementById("pane-summary").innerHTML = html;
    if (d.pending_action && d.pending_action.primary_action_id) {
      wireExecButtons(d.movement_id, d.pending_action);
    }
  }

  function renderExecCardHtml(pa) {
    var html = '<div class="exec-card">' +
      '<div><strong>Requested:</strong> ' + esc(pa.requested_action) + '</div>' +
      '<div class="muted">' + esc(pa.detail) + '</div>' +
      '<div class="muted">Blocker source: ' + esc(pa.blocker_source) + '</div>';
    if (pa.blocker_source === "platform_policy") {
      html += '<div class="platform-blocked">Platform/policy block -- no button here can override this.</div></div>';
      return html;
    }
    var primaryLabel = pa.primary_operation === "route_to_engineer" ? "Route to engineer" : "Approve & record decision";
    html += '<div class="buttons">' +
      '<button class="primary" id="exec-primary">' + esc(primaryLabel) + '</button>' +
      '<button class="danger" id="exec-reject">Reject</button>' +
      '</div></div>';
    return html;
  }

  function wireExecButtons(movementId, pa) {
    var primaryBtn = document.getElementById("exec-primary");
    var rejectBtn = document.getElementById("exec-reject");
    if (primaryBtn) {
      primaryBtn.onclick = function () { executeAction(movementId, pa.primary_action_id, primaryBtn); };
    }
    if (rejectBtn) {
      rejectBtn.onclick = function () { executeAction(movementId, pa.reject_action_id, rejectBtn); };
    }
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

  function renderTrafficPane(movementId, items) {
    var html = "";
    items.forEach(function (item) {
      html += '<div class="entry entry-' + esc(item.direction) + '">' +
        '<div class="entry-head">' + esc(item.timestamp) + ' · ' + esc(item.direction) +
        ' · ' + esc(item.actor) + ' · ' + esc(item.marker) + '</div>';
      if (item.subject) html += "<div><strong>" + esc(item.subject) + "</strong></div>";
      if (item.marker === "SESSION_CLOSE") {
        html += '<div class="muted">outcome: ' + esc(item.outcome) + '</div>';
        if (item.changed) html += '<div class="muted">changed: ' + esc((item.changed || []).join(", ")) + '</div>';
        if (item.validation) html += '<div class="muted">validation: ' + esc(JSON.stringify(item.validation)) + '</div>';
      }
      html += "</div>";
    });
    html += '<div class="msg-box">' +
      '<textarea id="msg-text" placeholder="Send a note to this movement..."></textarea>' +
      '<button id="msg-send">Send</button>' +
      '<div class="msg-state" id="msg-state"></div>' +
      '</div>';
    document.getElementById("pane-traffic").innerHTML = html || '<p class="muted">no traffic yet</p>';
    var sendBtn = document.getElementById("msg-send");
    if (sendBtn) {
      sendBtn.onclick = function () {
        var text = document.getElementById("msg-text").value.trim();
        if (!text) return;
        api("POST", "/api/movements/" + encodeURIComponent(movementId) + "/message", { text: text })
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
  }

  function renderChangesPane(d) {
    var html = '<dl class="kv">' +
      '<dt>Branch</dt><dd>' + esc(d.branch || "-") + '</dd>' +
      '<dt>Ahead / behind</dt><dd>' + esc(d.ahead === null || d.ahead === undefined ? "unknown" : d.ahead) +
      " / " + esc(d.behind === null || d.behind === undefined ? "unknown" : d.behind) + '</dd>' +
      '<dt>Open PR</dt><dd>' + (d.open_pr ? ("#" + esc(d.open_pr.number) + " (" + esc(d.open_pr.state) + ", CI " + esc(d.open_pr.ci) + ")") : "none / unknown (gh not available or no PR yet)") + '</dd>' +
      '<dt>Worktree</dt><dd>' + esc(d.worktree_path || "-") + '</dd>' +
      '</dl>';
    document.getElementById("pane-changes").innerHTML = html;
  }

  function renderEvidencePane(d) {
    var verify = d.verify;
    if (!verify) {
      document.getElementById("pane-evidence").innerHTML =
        '<p class="muted">No orchestrator-side verify recorded yet for this movement (only `orchestrator.py run` records one).</p>';
      return;
    }
    var html = '<div><strong>Overall:</strong> ' + (verify.passed ? "passed" : "failed") + '</div>';
    (verify.steps || []).forEach(function (step) {
      html += '<div class="entry">' +
        '<div class="entry-head">' + esc(step.name) + ' · exit ' + esc(step.exit_code) +
        ' · ' + esc(step.duration_s) + 's</div>' +
        '<pre class="log">' + esc(step.tail || "") + '</pre>' +
        '</div>';
    });
    document.getElementById("pane-evidence").innerHTML = html;
  }

  function renderUsagePane(d) {
    var usage = d.usage;
    if (!usage) {
      document.getElementById("pane-usage").innerHTML = '<p class="muted">no usage yet</p>';
      return;
    }
    var html = '<dl class="kv">';
    Object.keys(usage).forEach(function (key) {
      html += '<dt>' + esc(key) + '</dt><dd>' + esc(usage[key]) + '</dd>';
    });
    html += '</dl>';
    document.getElementById("pane-usage").innerHTML = html;
  }

  function renderLogPane(d) {
    api("GET", "/api/movements/" + encodeURIComponent(d.movement_id) + "/log?tail=200").then(function (log) {
      var lines = (log.lines || []).join("\n") || "(no log yet)";
      document.getElementById("pane-log").innerHTML =
        '<div class="muted">last observed: ' + esc(d.last_activity || "unknown") + '</div>' +
        '<pre class="log">' + esc(lines) + '</pre>';
    });
  }

  // -- config -----------------------------------------------------------

  function loadConfig() {
    api("GET", "/api/config").then(function (cfg) {
      document.getElementById("poll-interval").value = cfg.poll_interval_seconds;
      document.getElementById("stuck-after").value = cfg.stuck_after_seconds;
      schedulePoll(cfg.poll_interval_seconds);
    }).catch(function () { schedulePoll(5); });
  }

  function schedulePoll(seconds) {
    if (POLL_TIMER) clearInterval(POLL_TIMER);
    POLL_TIMER = setInterval(refresh, Math.max(2, seconds) * 1000);
  }

  function saveConfig() {
    var poll = parseInt(document.getElementById("poll-interval").value, 10);
    var stuck = parseInt(document.getElementById("stuck-after").value, 10);
    var stateEl = document.getElementById("poll-save-state");
    var body = {};
    if (poll) body.poll_interval_seconds = poll;
    if (stuck) body.stuck_after_seconds = stuck;
    api("PUT", "/api/config", body)
      .then(function (cfg) {
        stateEl.textContent = "saved";
        if (cfg.poll_interval_seconds) schedulePoll(cfg.poll_interval_seconds);
        setTimeout(function () { stateEl.textContent = ""; }, 2000);
      })
      .catch(function (err) { stateEl.textContent = "error: " + err.message; });
  }

  // -- startup ------------------------------------------------------------

  function start() {
    hideTokenPanel();
    applyOpenSections();
    loadConfig();
    refresh();
  }

  function useToken(token) {
    TOKEN = token;
    storageSet(TOKEN_KEY, token);
    start();
  }

  document.addEventListener("DOMContentLoaded", function () {
    wireSectionMemory();
    document.getElementById("detail-close").onclick = closeDetail;
    document.getElementById("poll-interval").addEventListener("change", saveConfig);
    document.getElementById("stuck-after").addEventListener("change", saveConfig);
    document.getElementById("token-save").onclick = function () {
      var value = document.getElementById("token-input").value.trim();
      if (!value) return;
      useToken(value);
    };

    var fromHash = readTokenFromHash();
    if (fromHash) {
      useToken(fromHash);
      return;
    }
    var stored = storageGet(TOKEN_KEY);
    if (stored) {
      useToken(stored);
      return;
    }
    // AC-13: no token known at all -- show the panel, make no /api/* call.
    showTokenPanel("");
  });
})();
