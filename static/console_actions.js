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

// M9 — enrollment preview + confirmation. Reuses the exact auth/idempotency
// helpers above; adds no new fetch pattern. The browser only ever submits
// the closed schema below (endpoint, vendor hint, opaque credential/trust
// references, tags) -- never a credential, a command, or argv (condition
// 6/7/8). Polling (not SSE) for the probe job's terminal state: simpler and
// this dialog only ever watches one job at a time.

const _M9_TRUST_PROFILE_SENTINEL = "system_known_hosts";
const _M9_CREDENTIAL_PROFILE_SENTINEL = "system_cp_config_ssh";
const _M9_POLL_INTERVAL_MS = 1000;
const _M9_POLL_TIMEOUT_MS = 60000;

async function _consoleSubmitEnrollmentProbe(body) {
    const response = await fetch("/api/enrollment/probe", {
        method: "POST",
        headers: _consoleAuthHeaders({
            "Content-Type": "application/json",
            "Idempotency-Key": _consoleNewIdempotencyKey(),
        }),
        body: JSON.stringify(body),
    });
    const parsed = await response.json().catch(() => ({}));
    if (!response.ok) {
        const detail = typeof parsed.detail === "string" ? parsed.detail : JSON.stringify(parsed.detail || parsed);
        throw new Error(`identity probe request refused: HTTP ${response.status} — ${detail}`);
    }
    return parsed;
}

async function _consoleFetchJob(jobId) {
    const response = await fetch(`/api/jobs/${encodeURIComponent(jobId)}`, { headers: _consoleAuthHeaders() });
    if (!response.ok) throw new Error(`job fetch failed: HTTP ${response.status}`);
    return response.json();
}

// Deliberately not `_consoleWatchJob` (SSE reader): this dialog is a single,
// short-lived, one-job-at-a-time wait, so a bounded poll loop is simpler and
// carries no persistent-connection lifecycle to manage across dialog close.
async function _consolePollJobUntilTerminal(jobId) {
    const deadline = Date.now() + _M9_POLL_TIMEOUT_MS;
    for (;;) {
        const record = await _consoleFetchJob(jobId);
        if (["succeeded", "failed", "blocked", "skipped"].includes(record.state)) return record;
        if (Date.now() > deadline) throw new Error("identity probe timed out waiting for a result");
        await new Promise((resolve) => setTimeout(resolve, _M9_POLL_INTERVAL_MS));
    }
}

async function _consoleConfirmEnrollment(body) {
    const response = await fetch("/api/registry/enrollments", {
        method: "POST",
        headers: _consoleAuthHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify(body),
    });
    const parsed = await response.json().catch(() => ({}));
    if (!response.ok) {
        const detail = typeof parsed.detail === "string" ? parsed.detail : JSON.stringify(parsed.detail || parsed);
        throw new Error(`enrollment refused: HTTP ${response.status} — ${detail}`);
    }
    return parsed;
}

function _m9SetStatus(text) {
    const el = document.getElementById("m9EnrollStatus");
    if (el) el.textContent = text || "";
}

function _m9RenderPreview(preview) {
    const el = document.getElementById("m9EnrollPreview");
    const confirmButton = document.getElementById("m9EnrollConfirmButton");
    if (!el) return;
    if (!preview) {
        el.hidden = true;
        el.innerHTML = "";
        if (confirmButton) confirmButton.hidden = true;
        return;
    }
    el.hidden = false;
    const rows = [
        ["Platform", preview.platform_label || preview.platform_family || "unknown"],
        ["Model", preview.model || "unknown"],
        ["Software version", preview.sw_version || "unknown"],
        ["Identity gate", preview.identity_gate_status || "unknown"],
        ["HA role", preview.ha_role || "n/a"],
    ];
    el.innerHTML = `
        <table class="m9-preview-table">
            <tbody>
                ${rows.map(([label, value]) => `<tr><th>${escapeHtml(label)}</th><td>${escapeHtml(String(value))}</td></tr>`).join("")}
            </tbody>
        </table>
    `;
    if (confirmButton) confirmButton.hidden = false;
}

// Bound once per probe attempt: the confirm step must resend the exact same
// intent the server bound the preview to (condition 13) -- this closure is
// the browser-side half of that binding, not a security boundary (the
// server independently re-derives and compares -- `probe_intent_mismatch`).
let _m9LastProbe = null;

function _m9ResetDialogState() {
    _m9LastProbe = null;
    _m9RenderPreview(null);
    _m9SetStatus("");
}

async function _m9HandleProbeSubmit(dialog) {
    const endpoint = dialog.querySelector("#m9EnrollEndpoint")?.value.trim() || "";
    const vendorHint = dialog.querySelector("#m9EnrollVendorHint")?.value || "unknown";
    const credentialRef = _M9_CREDENTIAL_PROFILE_SENTINEL;
    const tagsRaw = dialog.querySelector("#m9EnrollTags")?.value.trim() || "";

    if (!endpoint) {
        _m9SetStatus("Endpoint is required.");
        return;
    }

    let tags = {};
    if (tagsRaw) {
        try {
            tags = Object.fromEntries(
                tagsRaw.split(",").map((pair) => pair.split("=").map((s) => s.trim())).filter(([k]) => k)
            );
        } catch (error) {
            _m9SetStatus("Tags must be comma-separated key=value pairs.");
            return;
        }
    }

    _m9RenderPreview(null);
    _m9SetStatus("Probing identity (read-only, no configuration is written)...");

    const probeBody = {
        endpoint,
        vendor_hint: vendorHint,
        credential_profile_ref: credentialRef,
        trust_profile_ref: _M9_TRUST_PROFILE_SENTINEL,
    };

    try {
        const record = await _consoleSubmitEnrollmentProbe(probeBody);
        const terminal = await _consolePollJobUntilTerminal(record.job_id);
        if (terminal.state !== "succeeded" || !terminal.preview) {
            const reason = (terminal.outcome_counts && terminal.outcome_counts.probe_status)
                || terminal.error_code || "no positive identity evidence";
            _m9SetStatus(`No positive identity evidence: ${reason}. Nothing was enrolled.`);
            _m9LastProbe = null;
            return;
        }
        _m9LastProbe = { probeJobId: record.job_id, endpoint, vendorHint, credentialRef, tags };
        _m9SetStatus("Identity confirmed. Review below, then confirm to enroll.");
        _m9RenderPreview(terminal.preview);
    } catch (error) {
        _m9SetStatus(error.message);
        _m9LastProbe = null;
    }
}

async function _m9HandleConfirmClick() {
    if (!_m9LastProbe) {
        _m9SetStatus("Probe an identity first.");
        return;
    }
    _m9SetStatus("Enrolling...");
    try {
        const device = await _consoleConfirmEnrollment({
            probe_job_id: _m9LastProbe.probeJobId,
            endpoint: _m9LastProbe.endpoint,
            vendor_hint: _m9LastProbe.vendorHint,
            credential_profile_ref: _m9LastProbe.credentialRef,
            trust_profile_ref: _M9_TRUST_PROFILE_SENTINEL,
            tags: _m9LastProbe.tags,
            confirm: true,
        });
        _m9SetStatus(`Enrolled: device_id ${device.device_id} (state ${device.state}).`);
        _m9LastProbe = null;
        _m9RenderPreview(null);
    } catch (error) {
        _m9SetStatus(error.message);
    }
}

function consoleInitEnrollmentDialog() {
    const dialog = document.getElementById("m9EnrollDialog");
    if (!dialog) return; // not on this shell build

    // PO-NAV-1: both the Devices pane header button and the Administration ->
    // Device Management button open this exact same dialog -- one enrollment
    // implementation, two entry points, never two dialogs.
    const openButtons = [
        document.getElementById("m9EnrollOpenButton"),
        document.getElementById("m9EnrollOpenButtonAdmin"),
    ].filter(Boolean);
    openButtons.forEach((openButton) => {
        openButton.addEventListener("click", () => {
            _m9ResetDialogState();
            dialog.querySelectorAll("input").forEach((input) => { input.value = ""; });
            if (typeof dialog.showModal === "function") dialog.showModal();
            else dialog.setAttribute("open", "open");
        });
    });
    dialog.querySelector("#m9EnrollCloseButton")?.addEventListener("click", () => {
        dialog.close ? dialog.close() : dialog.removeAttribute("open");
        consoleRefreshDeviceManagementTable().catch(() => {});
    });
    dialog.querySelector("#m9EnrollProbeButton")?.addEventListener("click", (event) => {
        event.preventDefault();
        _m9HandleProbeSubmit(dialog).catch((error) => _m9SetStatus(error.message));
    });
    dialog.querySelector("#m9EnrollConfirmButton")?.addEventListener("click", (event) => {
        event.preventDefault();
        _m9HandleConfirmClick().catch((error) => _m9SetStatus(error.message));
    });
}

// PO-NAV-1 -- Administration -> Device Management, the second frozen
// enrollment entry point. Read-only registry listing; all enrollment writes
// still go exclusively through the one #m9EnrollDialog contract above.
async function consoleRefreshDeviceManagementTable() {
    const container = document.getElementById("deviceManagementTable");
    if (!container) return; // not on this shell build
    const response = await fetch("/api/registry/devices", { headers: _consoleAuthHeaders() });
    if (!response.ok) {
        container.textContent = `Device registry unavailable: HTTP ${response.status}`;
        return;
    }
    const devices = await response.json();
    if (!Array.isArray(devices) || devices.length === 0) {
        container.textContent = "No devices enrolled yet.";
        return;
    }
    const rows = devices.map((device) => `
        <tr>
            <td>${escapeHtml(device.endpoint || "")}</td>
            <td>${escapeHtml(device.vendor || "unknown")}</td>
            <td>${escapeHtml(device.state || "")}</td>
            <td>${escapeHtml(device.enrollment_source || "")}</td>
        </tr>
    `).join("");
    container.innerHTML = `
        <table class="m9-preview-table">
            <thead><tr><th>Endpoint</th><th>Vendor</th><th>State</th><th>Source</th></tr></thead>
            <tbody>${rows}</tbody>
        </table>
    `;
}

async function consoleInitDeviceManagementPanel() {
    if (!document.getElementById("deviceManagementTable")) return; // not on this shell build
    await consoleRefreshDeviceManagementTable();
}

_consoleLaunchToken = _consoleReadLaunchToken();
consoleRefreshPayloads();
consoleInitJobsPanel().catch(() => {});
consoleInitEnrollmentDialog();
consoleInitDeviceManagementPanel().catch(() => {});

document.getElementById("consoleRefreshButton")?.addEventListener("click", () => {
    consoleRefreshPayloads().catch(() => {});
});
