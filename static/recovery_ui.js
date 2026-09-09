// SecurityExpert report UI — recovery_ui: Recovery module (RB.5)
//
// Read-only. Renders utils.recovery_ui's projection verbatim -- no readiness
// verdict/validation logic happens here, only formatting. Contract §6 frozen
// rules enforced by construction in this file:
//   1. No payload bytes, no download URL, no decrypt affordance -- only the
//      already-projected class/age/validation-level/entity fields are ever
//      touched below.
//   2. restore_proven always renders as an explicit badge; the bare word
//      "verified" never appears.
//   3. available: false renders an explicit empty state, never a silent
//      blank module.

const RECOVERY_STATE_TONES = {
    READY: "success",
    STALE: "warning",
    PARTIAL: "warning",
    UNPROTECTED: "danger",
    UNKNOWN: "muted",
};

const RECOVERY_STATE_LABELS = {
    READY: "Ready",
    STALE: "Stale",
    PARTIAL: "Partial",
    UNPROTECTED: "Unprotected",
    UNKNOWN: "Unknown",
};

const RECOVERY_STATE_ORDER = ["READY", "STALE", "PARTIAL", "UNPROTECTED", "UNKNOWN"];


function recoveryStateTone(state) {
    return RECOVERY_STATE_TONES[state] || "neutral";
}


function recoveryProvenBadge(proven) {
    // Frozen rule 2 (§6): an explicit badge, never the bare word "verified".
    return proven
        ? `<span class="status-pill success">Restore proven</span>`
        : `<span class="status-pill muted">Restore not proven</span>`;
}


function renderRecoveryModule() {
    const payload = recoveryUiData || {};
    const available = payload.available === true;

    const framingHost = document.getElementById("recoveryFramingNote");
    if (framingHost) {
        framingHost.textContent = available
            ? "Manifests and readiness only -- never payload, never a download link, never a decrypt path."
            : "Recovery readiness evidence is not available for this run.";
    }

    const summaryHost = document.getElementById("recoveryFleetSummary");
    if (summaryHost) {
        if (!available) {
            summaryHost.innerHTML = `<div class="empty-state"><strong>Recovery readiness has not been computed yet.</strong><span>Run --restore-readiness-check to populate this module.</span></div>`;
        } else {
            const summary = payload.readiness_summary || {};
            const total = RECOVERY_STATE_ORDER.reduce((sum, key) => sum + Number(summary[key] || 0), 0);
            summaryHost.innerHTML = `
                <article class="project-progress-card primary">
                    <div class="eyebrow">Devices assessed</div>
                    <div class="project-progress-value">${escapeHtml(formatNumber(total))}</div>
                    <p>Fleet restore-readiness roll-up -- READY requires a held artifact at validation level V3+ with a matching running version.</p>
                </article>
                <article class="project-progress-card">
                    <div class="eyebrow">Readiness</div>
                    <div class="project-status-summary">
                        ${RECOVERY_STATE_ORDER.map(key => `<span>${escapeHtml(RECOVERY_STATE_LABELS[key])}<strong>${escapeHtml(formatNumber(summary[key]))}</strong></span>`).join("")}
                    </div>
                </article>
            `;
        }
    }

    const deviceTableHost = document.getElementById("recoveryDeviceTable");
    if (deviceTableHost) {
        const devices = available && Array.isArray(payload.devices) ? payload.devices : [];
        deviceTableHost.innerHTML = devices.length
            ? `<div class="table-wrap"><table class="data-table"><thead><tr>
                <th>Entity</th><th>Readiness</th><th>Artifacts</th>
            </tr></thead><tbody>${devices.map(device => `
                <tr>
                    <td>${escapeHtml(device.entity_id)}</td>
                    <td>${statusPill(RECOVERY_STATE_LABELS[device.state] || device.state, recoveryStateTone(device.state))}</td>
                    <td>${
                        (device.artifacts || []).length
                            ? `<table class="data-table compact"><thead><tr><th>Class</th><th>Age (days)</th><th>Validation level</th><th>Restore proof</th></tr></thead><tbody>${
                                (device.artifacts || []).map(artifact => `
                                    <tr>
                                        <td>${escapeHtml(artifact.class)}</td>
                                        <td>${escapeHtml(artifact.age_days === null || artifact.age_days === undefined ? "—" : formatNumber(artifact.age_days))}</td>
                                        <td>${escapeHtml(artifact.validation_level || "—")}</td>
                                        <td>${recoveryProvenBadge(Boolean(artifact.restore_proven))}</td>
                                    </tr>
                                `).join("")
                            }</tbody></table>`
                            : `<span class="eyebrow">No held artifact</span>`
                    }</td>
                </tr>
            `).join("")}</tbody></table></div>`
            : `<div class="empty-state compact"><span>No recovery-readiness records in the current inventory.</span></div>`;
    }

    const gapHost = document.getElementById("recoveryCoverageGap");
    if (gapHost) {
        if (!available) {
            gapHost.innerHTML = `<div class="empty-state compact"><span>No coverage data in this export.</span></div>`;
        } else {
            const coverage = payload.coverage || {};
            const devices = Array.isArray(payload.devices) ? payload.devices : [];
            const gapDevices = devices.filter(device => !(device.artifacts || []).length);
            gapHost.innerHTML = `
                <div class="summary-list">
                    <div><span>Devices in inventory</span><strong>${formatNumber(coverage.devices_in_inventory)}</strong></div>
                    <div><span>Devices with any artifact</span><strong>${formatNumber(coverage.devices_with_any_artifact)}</strong></div>
                    <div><span>Coverage</span><strong>${formatNumber(coverage.coverage_percent)}%</strong></div>
                </div>
                ${gapDevices.length
                    ? `<div class="table-wrap"><table class="data-table compact"><thead><tr><th>Entity</th><th>Readiness</th></tr></thead><tbody>${
                        gapDevices.map(device => `<tr><td>${escapeHtml(device.entity_id)}</td><td>${statusPill(RECOVERY_STATE_LABELS[device.state] || device.state, recoveryStateTone(device.state))}</td></tr>`).join("")
                    }</tbody></table></div>`
                    : `<div class="inline-message success">Every inventoried device holds at least one recovery artifact.</div>`}
            `;
        }
    }

    const retentionHost = document.getElementById("recoveryRetentionTable");
    if (retentionHost) {
        const pending = available && Array.isArray(payload.retention_pending_deletion) ? payload.retention_pending_deletion : [];
        retentionHost.innerHTML = pending.length
            ? `<div class="table-wrap"><table class="data-table compact"><thead><tr><th>Entity</th><th>Artifact</th><th>Expires</th></tr></thead><tbody>${
                pending.map(row => `<tr><td>${escapeHtml(row.entity_id)}</td><td>${escapeHtml(row.artifact_id)}</td><td>${escapeHtml(row.expires_at)}</td></tr>`).join("")
            }</tbody></table></div>`
            : `<div class="empty-state compact"><span>Nothing is pending deletion under retention policy.</span></div>`;
    }
}
