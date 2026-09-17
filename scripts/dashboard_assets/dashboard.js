/* neXus PO + Orchestrator workbench -- vanilla JS, no build step, no
 * third-party dependency (AC-6/AC-11). Talks only to this same origin's
 * /api/* endpoints; every state-changing action ends in a relay-write on
 * the backend (AC-4 of the original workbench contract) -- this file never
 * runs git/gh/shell, it only POSTs a registered action id or a message and
 * shows the honest result.
 *
 * GOV.ORCH.4-C: one board snapshot drives the table, counts and summary.
 * Drafts are memory-only; selection/request generations isolate responses.
 */
(function () {
  "use strict";

  var TOKEN_KEY = "nexus_dashboard_token";
  var SECTIONS_KEY = "nexus_dashboard_open_sections";
  var TOKEN = null;
  var POLL_TIMER = null;
  var SELECTED_MOVEMENT = null;

  var ROWS = [];
  var FILTER = "active";
  var SHOW_ALL = false;
  var BOARD_GENERATION = 0;
  var DETAIL_GENERATION = 0;
  var DRAFTS = new Map();
  var ACTIONS = new Map();
  var FILTER_LABELS = { active: "Active", needs: "Needs you", archive: "Archive" };

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
    return div.innerHTML.replace(/"/g, "&quot;");
  }

  function available(value) {
    return value === null || value === undefined || value === "" ? "Unavailable" : String(value);
  }

  function fmtAge(seconds) {
    if (seconds === null || seconds === undefined || seconds < 0) return "Unavailable";
    var total = Math.floor(seconds);
    if (total < 60) return total + "s";
    var m = Math.floor(total / 60), s = total % 60;
    if (m < 60) return m + "m" + String(s).padStart(2, "0") + "s";
    return Math.floor(m / 60) + "h" + String(m % 60).padStart(2, "0") + "m";
  }

  function costKind(usage) {
    if (!usage || usage.cost_usd === null || usage.cost_usd === undefined) return null;
    return String(usage.cost_source || "").indexOf("estimated") === 0 ? "estimated" : "reported";
  }

  function fmtCost(usage, row) {
    var kind = costKind(usage);
    if (kind) {
      return (kind === "reported" ? "Reported" : "Estimated") + " model-token cost: $" +
        usage.cost_usd.toFixed(4) + (usage.cost_source === "estimated_from_requested_model" ? " (requested model)" : "");
    }
    var counters = usage && ["input_tokens", "cache_read_input_tokens", "output_tokens", "total_tokens"].some(function (key) {
      return usage[key] !== null && usage[key] !== undefined;
    });
    var model = (usage && usage.model) || row.model_observed || row.model_requested;
    return counters && model ? "Estimate unavailable — model price missing" : "Unknown — token/model evidence absent";
  }

  function fmtTokens(usage) {
    usage = usage || {};
    var ratio = usage.cache_hit_ratio;
    return available(usage.input_tokens) + " in / " + available(usage.cache_read_input_tokens) +
      " cache-read / " + available(usage.output_tokens) + " out (cache ratio " +
      (ratio === null || ratio === undefined ? "Unavailable" : Math.round(ratio * 100) + "%") + ")";
  }

  function fmtModelEffort(row) {
    var model = row.model_observed ? row.model_observed + " (observed)" : "model Unavailable";
    if (row.model_requested) model += " / " + row.model_requested + " (requested)";
    var effort = row.effort_observed ? row.effort_observed + " (observed)" : "effort Unavailable";
    if (row.effort_requested) effort += " / " + row.effort_requested + " (requested)";
    return esc(available(row.provider)) + " · " + esc(model) + " · " + esc(effort);
  }

  // First matching contract evidence; health remains a separate enum.
  function classify(row) {
    if (row.phase === "cancelled" || String(row.terminal_outcome || "").toLowerCase() === "cancelled") return "Stopped";
    if (row.health === "done" || row.phase === "done" || row.relay_status === "CLOSED" || row.closed || row.archived) return "Done";
    var health = { failed: "Failed", awaiting_po: "Awaiting you", exited_without_close: "Exited without close",
      silent: "Silent", handed_over: "Handed over" };
    return (Object.prototype.hasOwnProperty.call(health, row.health) && health[row.health]) || (row.stage === "integration" ? "Integration" : row.health === "healthy" ? "Running" : "Unknown");
  }

  function projectBoard(board) {
    var rows = new Map();
    ["open", "awaiting_po", "closed", "archive"].forEach(function (bucket) {
      (board[bucket] || []).forEach(function (input) {
        var row = Object.assign({}, input, { closed: bucket === "closed" });
        var previous = rows.get(row.movement_id);
        if (previous) {
          var uncertain = previous.evidence_uncertain || ["phase", "terminal_outcome", "health"].some(function (key) {
            return previous[key] != null && row[key] != null && previous[key] !== row[key];
          });
          if (!previous.archived || row.archived) row = previous;
          row.evidence_uncertain = uncertain;
        }
        row.classification = classify(row);
        rows.set(row.movement_id, row);
      });
    });
    return Array.from(rows.values());
  }

  function member(row, filter) {
    var terminal = row.classification === "Stopped" || row.classification === "Done";
    if (filter === "archive") return terminal;
    if (filter === "needs") return ["Failed", "Awaiting you", "Exited without close", "Silent"].indexOf(row.classification) !== -1;
    return !terminal;
  }

  function matchingRows() {
    var query = document.getElementById("movement-search").value.toLowerCase();
    return ROWS.filter(function (row) {
      return member(row, FILTER) && (!query || String(row.movement_id).toLowerCase().includes(query) ||
        String(row.objective || "").toLowerCase().includes(query));
    }).sort(function (a, b) {
      if (FILTER === "archive") {
        var at = Date.parse(a.ended_at), bt = Date.parse(b.ended_at);
        if (!isNaN(at) && !isNaN(bt) && at !== bt) return bt - at;
        if (isNaN(at) !== isNaN(bt)) return isNaN(at) ? 1 : -1;
      }
      return a.movement_id < b.movement_id ? -1 : a.movement_id > b.movement_id ? 1 : 0;
    });
  }

  function replaceHtml(id, html) {
    var el = document.getElementById(id);
    if (el._html === html) return;
    var focusId = el.contains(document.activeElement) ? document.activeElement.id : null;
    el.innerHTML = html;
    el._html = html;
    if (focusId && document.getElementById(focusId)) document.getElementById(focusId).focus({ preventScroll: true });
  }

  function refresh() {
    if (!TOKEN) return;
    var generation = ++BOARD_GENERATION;
    api("GET", "/api/board").then(function (board) {
      if (generation !== BOARD_GENERATION) return;
      ROWS = projectBoard(board);
      renderSummary();
      renderBoard();
      if (SELECTED_MOVEMENT) {
        var row = ROWS.find(function (r) { return r.movement_id === SELECTED_MOVEMENT; });
        if (row && !row.archived) refreshDetail(SELECTED_MOVEMENT);
        else ++DETAIL_GENERATION;
      }
    }).catch(function (err) {
      if (generation !== BOARD_GENERATION) return;
      if (err.status === 401) {
        storageSet(TOKEN_KEY, "");
        TOKEN = null;
        ++DETAIL_GENERATION;
        showTokenPanel("Saved token was rejected -- paste a fresh one.");
        return;
      }
      document.getElementById("summary-strip").textContent = "error loading board: " + err.message;
    });
  }

  function usageTotals(rows) {
    var sums = { reported: null, estimated: null, tokens: null, missing: 0 };
    rows.forEach(function (row) {
      var usage = row.usage || {}, kind = costKind(usage);
      if (kind) sums[kind] = (sums[kind] || 0) + usage.cost_usd;
      else ++sums.missing;
      if (usage.total_tokens != null) sums.tokens = (sums.tokens || 0) + usage.total_tokens;
    });
    function money(value) { return value === null ? "Unavailable" : "$" + value.toFixed(4); }
    return available(sums.tokens) + " recorded tokens · Reported model-token cost " + money(sums.reported) +
      " · Estimated model-token cost " + money(sums.estimated) + " · " + sums.missing + " movements with missing cost";
  }

  function renderSummary() {
    var counts = new Map();
    ROWS.forEach(function (row) { counts.set(row.classification, (counts.get(row.classification) || 0) + 1); });
    var today = new Date().toISOString().slice(0, 10);
    var todayRows = ROWS.filter(function (row) { return row.usage && String(row.usage.last_event_at || "").slice(0, 10) === today; });
    document.getElementById("summary-strip").textContent = Array.from(counts).map(function (pair) {
      return pair[0] + " " + pair[1];
    }).join(" · ") + " | Loaded movement totals: " + usageTotals(ROWS) +
      " | Movement totals last active today (UTC): " + usageTotals(todayRows);
  }

  function renderBoard() {
    Object.keys(FILTER_LABELS).forEach(function (filter) {
      var btn = document.querySelector('[data-filter="' + filter + '"]');
      btn.textContent = FILTER_LABELS[filter] + " (" + ROWS.filter(function (row) { return member(row, filter); }).length + ")";
      btn.setAttribute("aria-pressed", String(FILTER === filter));
    });
    var matching = matchingRows();
    var visible = FILTER === "archive" && !SHOW_ALL ? matching.slice(0, 10) : matching;
    document.getElementById("row-count").textContent = matching.length + " matching loaded movements; " + visible.length + " displayed";
    document.getElementById("empty-state").textContent = matching.length ? "" :
      document.getElementById("movement-search").value ? "No movements match this search in " + FILTER_LABELS[FILTER] + "." : "No loaded movements in " + FILTER_LABELS[FILTER] + ".";
    var limit = document.getElementById("archive-limit");
    limit.classList.toggle("hidden", FILTER !== "archive" || matching.length <= 10);
    limit.textContent = SHOW_ALL ? "Show fewer" : "Show all";
    var focused = document.activeElement;
    var focusMovement = focused && focused.dataset.movement;
    replaceHtml("movement-rows", visible.map(renderRow).join(""));
    document.querySelectorAll(".movement-button").forEach(function (btn) {
      btn.onclick = function () { openDetail(btn.dataset.movement); };
      if (focusMovement === btn.dataset.movement) btn.focus({ preventScroll: true });
    });
    updateDetailNote();
  }

  function line(text) { return '<span class="cell-line">' + esc(text) + '</span>'; }

  function renderRow(row) {
    var provider = String(row.provider || "").toLowerCase();
    var providerClass = provider === "claude" || provider === "codex" ? provider : "neutral";
    var label = row.classification;
    if (label === "Done" && !row.terminal_outcome) label += " — outcome unavailable";
    if (row.evidence_uncertain) label += " — inconsistent evidence";
    return '<tr class="provider-' + providerClass + '"><td><button class="movement-button" data-movement="' +
      esc(row.movement_id) + '" aria-pressed="' + (SELECTED_MOVEMENT === row.movement_id) + '">' + esc(row.movement_id) + '</button>' +
      line(row.objective || "Objective unavailable") + '</td><td>' + line(label) + line("Health: " + available(row.health)) +
      '</td><td><span class="provider-line provider-' + providerClass + '">' + fmtModelEffort(row) + '</span></td><td>' +
      line("Process: " + available(row.process_status)) + line("Stage: " + available(row.stage)) + '</td><td>' +
      line("Duration: " + fmtAge(row.duration_s)) + line("Idle evidence: " + fmtAge(row.idle_seconds)) +
      (row.classification === "Handed over" ? line("Latest commit/activity: " + available(row.last_activity)) : "") + '</td><td>' +
      line(fmtTokens(row.usage)) + line(fmtCost(row.usage, row)) + '</td><td>' +
      line(row.pr_number == null ? "PR unavailable" : "PR #" + row.pr_number + " — " + available(row.pr_state)) +
      line("Verification: " + (row.verify_passed == null ? "Unavailable" : row.verify_passed ? "passed" : "failed")) +
      (row.archived ? line("Relay-only record — details/actions unavailable") : "") + '</td></tr>';
  }

  function updateDetailNote() {
    if (!SELECTED_MOVEMENT) return;
    var row = ROWS.find(function (r) { return r.movement_id === SELECTED_MOVEMENT; });
    document.getElementById("detail-note").textContent = !row ? "Movement is no longer available. Unsent draft retained." :
      row.archived ? "Relay-only record — process details and actions unavailable." :
      !matchingRows().some(function (r) { return r.movement_id === SELECTED_MOVEMENT; }) ? "Selected movement is outside the current filter/search; details remain open." : "";
    document.getElementById("msg-box").classList.toggle("hidden", !!(row && row.archived));
    document.getElementById("msg-text").disabled = !row || row.archived;
    syncMessageState();
    if (!row || row.archived) document.querySelectorAll(".exec-card button").forEach(function (btn) { btn.disabled = true; });
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

  function draft(movementId) {
    if (!DRAFTS.has(movementId)) DRAFTS.set(movementId, { text: "", start: 0, end: 0, direction: "none", version: 0, status: "", sending: false, focused: false });
    return DRAFTS.get(movementId);
  }

  function rememberEditor() {
    if (!SELECTED_MOVEMENT) return;
    var editor = document.getElementById("msg-text"), state = draft(SELECTED_MOVEMENT);
    state.text = editor.value;
    state.start = editor.selectionStart;
    state.end = editor.selectionEnd;
    state.direction = editor.selectionDirection;
    if (document.activeElement === editor) state.focused = true;
  }

  function syncMessageState() {
    if (!SELECTED_MOVEMENT) return;
    var state = draft(SELECTED_MOVEMENT), editor = document.getElementById("msg-text");
    document.getElementById("msg-send").disabled = state.sending || editor.disabled;
    document.getElementById("msg-state").textContent = state.status;
  }

  function openDetail(movementId) {
    rememberEditor();
    SELECTED_MOVEMENT = movementId;
    ++DETAIL_GENERATION;
    document.getElementById("detail-panel").classList.remove("hidden");
    document.getElementById("detail-title").textContent = movementId;
    ["summary", "changes", "evidence", "usage", "log"].forEach(function (pane) { replaceHtml("pane-" + pane, '<p class="muted">Loading / unavailable</p>'); });
    replaceHtml("traffic-entries", "");
    var state = draft(movementId), editor = document.getElementById("msg-text");
    editor.value = state.text;
    editor.setSelectionRange(state.start, state.end, state.direction);
    renderBoard();
    var row = ROWS.find(function (r) { return r.movement_id === movementId; });
    if (row && row.archived) {
      replaceHtml("pane-summary", line(row.objective || "Objective unavailable") + line(row.classification) + line("Outcome: " + available(row.terminal_outcome)));
    } else if (row) refreshDetail(movementId);
    if (state.focused && !editor.disabled && document.querySelector('[data-section="traffic"]').open) editor.focus({ preventScroll: true });
  }

  function closeDetail() {
    rememberEditor();
    var movementId = SELECTED_MOVEMENT;
    SELECTED_MOVEMENT = null;
    ++DETAIL_GENERATION;
    document.getElementById("detail-panel").classList.add("hidden");
    renderBoard();
    var button = Array.from(document.querySelectorAll(".movement-button")).find(function (btn) { return btn.dataset.movement === movementId; });
    (button || document.querySelector('[data-filter="' + FILTER + '"]')).focus({ preventScroll: true });
  }

  function current(movementId, generation) {
    return TOKEN && SELECTED_MOVEMENT === movementId && DETAIL_GENERATION === generation;
  }

  function refreshDetail(movementId) {
    var generation = ++DETAIL_GENERATION;
    api("GET", "/api/movements/" + encodeURIComponent(movementId)).then(function (detail) {
      if (!current(movementId, generation)) return;
      renderSummaryPane(detail);
      renderChangesPane(detail);
      renderEvidencePane(detail);
      renderUsagePane(detail);
    }).catch(function (err) {
      if (current(movementId, generation)) replaceHtml("pane-summary", line("error: " + err.message));
    });
    api("GET", "/api/movements/" + encodeURIComponent(movementId) + "/traffic").then(function (data) {
      if (current(movementId, generation)) renderTrafficPane(data.traffic || []);
    }).catch(function (err) {
      if (current(movementId, generation)) replaceHtml("traffic-entries", line("error: " + err.message));
    });
    renderLogPane(movementId, generation);
  }

  function renderSummaryPane(d) {
    var task = d.approved_task || {};
    var report = task.report || {};
    var ac = report.acceptance_criteria || [];
    var html = '<dl class="kv">' +
      '<dt>Process status</dt><dd>' + esc(available(d.process_status)) + '</dd>' +
      '<dt>Health</dt><dd>' + esc(available(d.health)) + '</dd>' +
      '<dt>Work stage</dt><dd>' + esc(available(d.work_stage)) + '</dd>' +
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
    var actionState = ACTIONS.get(d.movement_id) || {};
    html += '<div id="action-state" role="status">' + esc(actionState.status || "") + '</div>';
    replaceHtml("pane-summary", html);
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
    var state = ACTIONS.get(movementId) || {};
    if (primaryBtn) primaryBtn.disabled = !!state.pending || (state.consumed || []).includes(pa.primary_action_id);
    if (rejectBtn) rejectBtn.disabled = !!state.pending || (state.consumed || []).includes(pa.reject_action_id);
    if (primaryBtn) {
      primaryBtn.onclick = function () { executeAction(movementId, pa.primary_action_id); };
    }
    if (rejectBtn) {
      rejectBtn.onclick = function () { executeAction(movementId, pa.reject_action_id); };
    }
  }

  function executeAction(movementId, actionId) {
    if (!actionId) return;
    var state = ACTIONS.get(movementId) || { consumed: [] };
    if (state.pending || state.consumed.includes(actionId)) return;
    state.pending = actionId;
    state.status = "Recording decision…";
    ACTIONS.set(movementId, state);
    document.querySelectorAll(".exec-card button").forEach(function (button) { button.disabled = true; });
    var generation = DETAIL_GENERATION;
    api("POST", "/api/movements/" + encodeURIComponent(movementId) + "/actions/" + encodeURIComponent(actionId) + "/execute", {})
      .then(function () {
        state.pending = null;
        state.consumed.push(actionId);
        state.status = "Decision recorded";
        refresh();
      }).catch(function (err) {
        state.pending = null;
        state.status = "Action failed: " + err.message;
        if (current(movementId, generation)) {
          document.getElementById("action-state").textContent = state.status;
          // Refresh registry evidence; a response never re-enables a captured button.
          refreshDetail(movementId);
        }
      });
  }

  function renderTrafficPane(items) {
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
    replaceHtml("traffic-entries", html || '<p class="muted">No traffic yet</p>');
  }

  function sendMessage() {
    var movementId = SELECTED_MOVEMENT;
    if (!movementId) return;
    rememberEditor();
    var state = draft(movementId), submitted = state.text, version = state.version;
    if (state.sending || !submitted.trim() || document.getElementById("msg-text").disabled) return;
    state.sending = true;
    state.status = "Saving…";
    syncMessageState();
    api("POST", "/api/movements/" + encodeURIComponent(movementId) + "/message", { text: submitted })
      .then(function (result) {
        state.sending = false;
        state.status = "Saved -> queued for next resume (" + available(result.delivery_state) + ")" +
          (result.appended ? " — appended to relay" : " — saved locally; not yet appended to relay");
        if (state.version === version && state.text === submitted) {
          state.text = "";
          state.start = state.end = 0;
          if (SELECTED_MOVEMENT === movementId) document.getElementById("msg-text").value = "";
        }
        if (SELECTED_MOVEMENT === movementId) syncMessageState();
        refresh();
      }).catch(function (err) {
        state.sending = false;
        state.status = "error: " + err.message;
        if (SELECTED_MOVEMENT === movementId) syncMessageState();
      });
  }

  function renderChangesPane(d) {
    var html = '<dl class="kv">' +
      '<dt>Branch</dt><dd>' + esc(d.branch || "-") + '</dd>' +
      '<dt>Ahead / behind</dt><dd>' + esc(d.ahead === null || d.ahead === undefined ? "unknown" : d.ahead) +
      " / " + esc(d.behind === null || d.behind === undefined ? "unknown" : d.behind) + '</dd>' +
      '<dt>Open PR</dt><dd>' + (d.open_pr ? ("#" + esc(d.open_pr.number) + " (" + esc(d.open_pr.state) + ", CI " + esc(d.open_pr.ci) + ")") : "none / unknown (gh not available or no PR yet)") + '</dd>' +
      '<dt>Worktree</dt><dd>' + esc(d.worktree_path || "-") + '</dd>' +
      '</dl>';
    replaceHtml("pane-changes", html);
  }

  function renderEvidencePane(d) {
    var verify = d.verify;
    if (!verify) {
      replaceHtml("pane-evidence",
        '<p class="muted">Verification unavailable — no orchestrator-side verify recorded.</p>');
      return;
    }
    var html = '<div><strong>Overall:</strong> ' + (verify.passed == null ? "Unavailable" : verify.passed ? "passed" : "failed") + '</div>';
    (verify.steps || []).forEach(function (step) {
      html += '<div class="entry">' +
        '<div class="entry-head">' + esc(step.name) + ' · exit ' + esc(step.exit_code) +
        ' · ' + esc(step.duration_s) + 's</div>' +
        '<pre class="log">' + esc(step.tail || "") + '</pre>' +
        '</div>';
    });
    replaceHtml("pane-evidence", html);
  }

  function renderUsagePane(d) {
    var usage = d.usage || {};
    var html = line(fmtCost(usage, d)) + line(fmtTokens(usage)) +
      '<p class="muted">Recorded model work; neither an invoice nor subscription billing.</p><dl class="kv">';
    Object.keys(usage).forEach(function (key) { html += '<dt>' + esc(key) + '</dt><dd>' + esc(available(usage[key])) + '</dd>'; });
    replaceHtml("pane-usage", html + '</dl>');
  }

  function renderLogPane(movementId, generation) {
    api("GET", "/api/movements/" + encodeURIComponent(movementId) + "/log?tail=200").then(function (log) {
      if (!current(movementId, generation)) return;
      replaceHtml("pane-log", '<pre class="log">' + esc((log.lines || []).join("\n") || "(no log yet)") + '</pre>');
    }).catch(function (err) {
      if (current(movementId, generation)) replaceHtml("pane-log", line("error: " + err.message));
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
    if (!TOKEN) return;
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
    document.querySelectorAll("[data-filter]").forEach(function (btn) {
      btn.onclick = function () { FILTER = btn.dataset.filter; renderBoard(); };
    });
    document.getElementById("movement-search").addEventListener("input", renderBoard);
    document.getElementById("archive-limit").onclick = function () { SHOW_ALL = !SHOW_ALL; renderBoard(); };
    document.getElementById("msg-send").onclick = sendMessage;
    var editor = document.getElementById("msg-text");
    ["input", "select", "keyup", "click", "focus", "blur"].forEach(function (event) {
      editor.addEventListener(event, function () {
        if (SELECTED_MOVEMENT && event === "input") ++draft(SELECTED_MOVEMENT).version;
        rememberEditor();
      });
    });
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
