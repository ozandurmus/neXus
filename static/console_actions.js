// SecurityExpert Operator Console — console_actions: mode detection, launch-
// token handling and the payload fetch/refresh cycle only (CON.1 scope). No
// action affordance of any kind lives here or anywhere else in this phase —
// later phases (CON.2+) are the ones allowed to add one, deliberately.
//
// Not part of static/app.js's composition order (utils.html_export.MODULE_ORDER):
// it is loaded only by templates/console.html, after /assets/app.js, and is
// therefore never inlined into the exported static report (AC-10).

// C1-3: the shell sets the mode flag before any module executes; every
// module then reads it through app_core.js's reportMode() accessor.
window.SECURITYEXPERT_MODE = "console";

// C1-5: the launch token lives in the URL fragment only — never in
// localStorage, sessionStorage or a cookie — and is stripped from the
// visible URL (and browser history) the instant it is read, so it does not
// linger in the address bar or get bookmarked/shared by accident.
let _consoleLaunchToken = "";

function _consoleReadLaunchToken() {
    const hash = window.location.hash || "";
    const match = hash.match(/(?:^#|&)t=([^&]+)/);
    const token = match ? decodeURIComponent(match[1]) : "";
    try {
        history.replaceState(null, "", window.location.pathname + window.location.search);
    } catch (error) {
        // Non-browser or restricted history API; the token is still held in
        // the module-scoped variable above and every /api/* call still works.
    }
    return token;
}

async function _consoleFetchPayloads() {
    const response = await fetch("/api/payloads", {
        headers: { Authorization: `Bearer ${_consoleLaunchToken}` },
    });
    if (!response.ok) {
        throw new Error(`console payload fetch failed: HTTP ${response.status}`);
    }
    return response.json();
}

// C1-9: refresh reads artifacts on disk via /api/payloads — never a device
// collection. Called once on load, on every manual click of the refresh
// control, and (opt-in only) on the auto-refresh timer below.
async function consoleRefreshPayloads() {
    const payloads = await _consoleFetchPayloads();
    initializeReport(payloads);
}

const CONSOLE_AUTO_REFRESH_MIN_INTERVAL_MS = 30000;
let _consoleAutoRefreshTimer = null;

// Opt-in, per session, minimum interval 30s (C1-9). Off unless a caller
// (a later phase's settings affordance) explicitly enables it — CON.1 itself
// never calls this with `true`.
function consoleSetAutoRefresh(enabled, intervalMs = CONSOLE_AUTO_REFRESH_MIN_INTERVAL_MS) {
    if (_consoleAutoRefreshTimer) {
        clearInterval(_consoleAutoRefreshTimer);
        _consoleAutoRefreshTimer = null;
    }
    if (!enabled) return;
    const safeInterval = Math.max(intervalMs, CONSOLE_AUTO_REFRESH_MIN_INTERVAL_MS);
    _consoleAutoRefreshTimer = setInterval(() => {
        consoleRefreshPayloads().catch(() => {
            // A transient fetch failure must not crash a running console; the
            // next manual refresh or auto-refresh tick retries on its own.
        });
    }, safeInterval);
}

// CON.2 — job engine + read-class actions. Everything below only ever calls
// /api/job-types, /api/jobs and /api/jobs/{id}/events; it never contacts a
// device directly (the console never does — only main.main(), server-side,
// does that). An operational-write job type always renders BLOCKED (C2-6);
// clicking it is not offered.

function _consoleAuthHeaders(extra = {}) {
    return { Authorization: `Bearer ${_consoleLaunchToken}`, ...extra };
}

async function _consoleFetchJobTypes() {
    const response = await fetch("/api/job-types", { headers: _consoleAuthHeaders() });
    if (!response.ok) throw new Error(`job-types fetch failed: HTTP ${response.status}`);
    return response.json();
}

async function _consoleFetchJobs() {
    const response = await fetch("/api/jobs", { headers: _consoleAuthHeaders() });
    if (!response.ok) throw new Error(`jobs fetch failed: HTTP ${response.status}`);
    return response.json();
}

function _consoleNewIdempotencyKey() {
    return (window.crypto && window.crypto.randomUUID)
        ? window.crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

async function _consoleSubmitJob(jobTypeId, targets) {
    const response = await fetch("/api/jobs", {
        method: "POST",
        headers: _consoleAuthHeaders({
            "Content-Type": "application/json",
            "Idempotency-Key": _consoleNewIdempotencyKey(),
        }),
        body: JSON.stringify({ job_type: jobTypeId, targets: targets || [] }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
        const detail = typeof body.detail === "string" ? body.detail : JSON.stringify(body.detail || body);
        throw new Error(`job submit failed: HTTP ${response.status} — ${detail}`);
    }
    return body;
}

// C2-10: state transitions only, never collector output. EventSource cannot
// carry an Authorization header, so this reads the same bearer-authenticated
// stream manually via fetch() instead of the native EventSource API.
async function _consoleWatchJob(jobId, onUpdate) {
    const response = await fetch(`/api/jobs/${encodeURIComponent(jobId)}/events`, {
        headers: _consoleAuthHeaders(),
    });
    if (!response.ok || !response.body) {
        throw new Error(`job events stream failed: HTTP ${response.status}`);
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let sep;
        while ((sep = buffer.indexOf("\n\n")) !== -1) {
            const chunk = buffer.slice(0, sep);
            buffer = buffer.slice(sep + 2);
            const line = chunk.split("\n").find((l) => l.startsWith("data: "));
            if (line) {
                try {
                    onUpdate(JSON.parse(line.slice(6)));
                } catch (error) {
                    // A malformed frame must not break the stream reader.
                }
            }
        }
    }
}

function _consoleJobStatePill(state) {
    const tone = { succeeded: "success", failed: "danger", blocked: "muted", running: "info" }[state] || "neutral";
    return statusPill(state, tone);
}

function _consoleRenderJobsTable(jobs) {
    const container = document.getElementById("consoleJobsTable");
    if (!container) return;
    if (!jobs.length) {
        container.innerHTML = `<p class="empty-state">No console jobs submitted yet this session.</p>`;
        return;
    }
    const rows = jobs
        .slice(0, 25)
        .map((job) => `
            <tr>
                <td>${escapeHtml(job.job_type)}</td>
                <td>${_consoleJobStatePill(job.state)}</td>
                <td>${escapeHtml(job.requested_at || "")}</td>
                <td>${escapeHtml(job.run_id || "")}</td>
                <td>${escapeHtml(job.error_summary || "")}</td>
            </tr>
        `)
        .join("");
    container.innerHTML = `
        <div class="table-container">
            <table>
                <thead><tr><th>Job type</th><th>State</th><th>Requested</th><th>Run</th><th>Error</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
        </div>
    `;
}

async function consoleRefreshJobsTable() {
    _consoleRenderJobsTable(await _consoleFetchJobs());
}

function _consoleRenderJobTypes(jobTypes) {
    const container = document.getElementById("consoleJobTypes");
    if (!container) return;
    container.innerHTML = jobTypes
        .map((jt) => {
            if (jt.blocked) {
                return `<button class="job-type-button blocked" type="button" disabled
                    title="${escapeHtml(jt.blocked_reason || "blocked")}">${escapeHtml(jt.label)} — BLOCKED</button>`;
            }
            return `<button class="job-type-button" type="button" data-job-type="${escapeHtml(jt.id)}"
                data-target-mode="${escapeHtml(jt.target_mode)}">${escapeHtml(jt.label)}</button>`;
        })
        .join("");
    container.querySelectorAll("button[data-job-type]").forEach((button) => {
        button.addEventListener("click", async () => {
            const jobTypeId = button.getAttribute("data-job-type");
            let targets = [];
            if (button.getAttribute("data-target-mode") === "entity_ids") {
                const raw = window.prompt("Comma-separated entity_id list (blank = cancel):", "");
                if (raw === null || raw.trim() === "") return;
                targets = raw.split(",").map((t) => t.trim()).filter(Boolean);
            }
            button.disabled = true;
            try {
                const record = await _consoleSubmitJob(jobTypeId, targets);
                await consoleRefreshJobsTable();
                _consoleWatchJob(record.job_id, () => {
                    consoleRefreshJobsTable().catch(() => {});
                }).catch(() => {});
            } catch (error) {
                window.alert(error.message);
            } finally {
                button.disabled = false;
            }
        });
    });
}

async function consoleInitJobsPanel() {
    if (!document.getElementById("consoleJobTypes")) return; // not on this shell build
    _consoleRenderJobTypes(await _consoleFetchJobTypes());
    await consoleRefreshJobsTable();
}

_consoleLaunchToken = _consoleReadLaunchToken();
consoleRefreshPayloads();
consoleInitJobsPanel().catch(() => {});

document.getElementById("consoleRefreshButton")?.addEventListener("click", () => {
    consoleRefreshPayloads().catch(() => {});
});
