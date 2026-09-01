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

_consoleLaunchToken = _consoleReadLaunchToken();
consoleRefreshPayloads();

document.getElementById("consoleRefreshButton")?.addEventListener("click", () => {
    consoleRefreshPayloads().catch(() => {});
});
