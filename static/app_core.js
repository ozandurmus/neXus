// SecurityExpert report UI — app_core: escaping, formatters, shared pure helpers (loads first)

// CON.1 C1-3: the shell (templates/index.html or templates/console.html) sets
// window.SECURITYEXPERT_MODE before any module executes. Every module reads
// it through this one accessor rather than inferring mode from a URL or a
// fetch capability, so the read stays greppable.
function reportMode() {
    return window.SECURITYEXPERT_MODE === "console" ? "console" : "static";
}

// CON.1 C1-2/C1-3: payload globals populated by initializeReport() in
// app_bootstrap.js — static mode passes the inline JSON constants, console
// mode passes the fetched /api/payloads response. Declared here (the
// first-loaded module) as `let` so every later module can reference them by
// name exactly as before the refactor.
let rawData = [];
let configUiData = {};
let complianceUiData = {};
let cryptoUiData = {};
let projectPlanData = {};
let discoveryUiData = {};
let exclusionsUiData = {};
let failoverReadinessData = {};

// F-Buddy Phase 0.5 Final UI Closure
function safe(value) {
    if (value === null || value === undefined) {
        return "";
    }

    return String(value);
}


function escapeHtml(value) {
    return safe(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}


function normalizedSource(value) {
    const source = safe(value).trim().toLowerCase();

    if (
        source === "panorama-runtime" ||
        source === "panorama_runtime" ||
        source === "pan"
    ) {
        return "panorama";
    }

    if (
        source === "checkpoint" ||
        source === "check point"
    ) {
        return "cp";
    }

    return source;
}


function vendorLabel(source) {
    if (source === "panorama") {
        return "PAN";
    }

    return safe(source).toUpperCase();
}


function vendorTitle(source) {
    if (source === "panorama") {
        return "Palo Alto";
    }

    if (source === "vsx") {
        return "VSX";
    }

    if (source === "cp") {
        return "CP";
    }

    return safe(source).toUpperCase();
}


function formatInventoryTimestamp(value) {
    if (!value) {
        return "No successful collection timestamp";
    }

    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) {
        return value;
    }

    return parsed.toLocaleString(undefined, {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    });
}


function formatNumber(value) {
    const numeric = Number(value || 0);
    return Number.isFinite(numeric) ? numeric.toLocaleString() : "0";
}


function formatPercent(value) {
    const numeric = Number(value || 0);
    if (!Number.isFinite(numeric)) return "0%";
    return numeric.toLocaleString(undefined, {
        minimumFractionDigits: numeric > 0 && numeric < 100 ? 1 : 0,
        maximumFractionDigits: 1
    }) + "%";
}


function formatBytes(value) {
    let numeric = Number(value || 0);
    if (!Number.isFinite(numeric) || numeric <= 0) return "—";
    const units = ["B", "KB", "MB", "GB"];
    let unit = 0;
    while (numeric >= 1024 && unit < units.length - 1) {
        numeric /= 1024;
        unit += 1;
    }
    return numeric.toLocaleString(undefined, {
        minimumFractionDigits: unit ? 1 : 0,
        maximumFractionDigits: 1
    }) + " " + units[unit];
}


function formatConfigTimestamp(value) {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return safe(value);
    return date.toLocaleString(undefined, {
        year: "numeric",
        month: "short",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit"
    });
}


function statusTone(classification) {
    const value = safe(classification).toUpperCase();
    if (["EFFECTIVE_DRIFT", "PANORAMA_OUT_OF_SYNC", "COLLECTION_FAILURE", "FAILED"].includes(value)) {
        return "danger";
    }
    if (value === "LOCAL_OVERRIDE" || value === "DIFFERENCE_OBSERVED") return "warning";
    if (value === "ALIGNED" || value === "SUCCESS" || value === "CURRENT" || value === "IN_SYNC") {
        return "success";
    }
    if (value === "MEMBER_SPECIFIC") return "info";
    if (["PROVENANCE_UNVERIFIED", "IDENTITY_TRANSLATION_REQUIRED", "EXPECTED_ONLY", "ACTUAL_ONLY", "LOCAL_ONLY", "INSUFFICIENT_EVIDENCE", "UNKNOWN"].includes(value)) {
        return "muted";
    }
    return "neutral";
}


function statusPill(label, tone = "neutral") {
    return `<span class="status-pill ${escapeHtml(tone)}"><span class="status-pill-dot"></span>${escapeHtml(label)}</span>`;
}


function classificationLabel(value) {
    return safe(configUiData?.classification_labels?.[value]) || safe(value).replaceAll("_", " ").toLowerCase().replace(/\b\w/g, char => char.toUpperCase());
}


function categoryLabel(value) {
    return safe(configUiData?.category_labels?.[value]) || safe(value).replaceAll("_", " ").replace(/\b\w/g, char => char.toUpperCase());
}


function metricCard(label, value, detail = "", tone = "neutral") {
    return `
        <div class="metric-card ${escapeHtml(tone)}">
            <div class="metric-label">${escapeHtml(label)}</div>
            <div class="metric-value">${escapeHtml(value)}</div>
            ${detail ? `<div class="metric-detail">${escapeHtml(detail)}</div>` : ""}
        </div>
    `;
}

