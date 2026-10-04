// SecurityExpert report UI — failover_readiness_ui: Failover module (OP.0c)
//
// Read-only. Renders utils.failover_readiness_ui's payload verbatim -- no
// verdict/check computation happens here, only formatting. There is
// deliberately no execute/prepare/authorise control anywhere in this file.

function failoverVerdictTone(verdict) {
    const payload = failoverReadinessData || {};
    return (payload.verdict_tones || {})[verdict] || "neutral";
}


function failoverCheckStatusTone(status) {
    const payload = failoverReadinessData || {};
    return (payload.check_status_tones || {})[status] || "neutral";
}


function renderFailoverModule() {
    const payload = failoverReadinessData || {};
    const summary = payload.summary || {};
    const units = Array.isArray(payload.units) ? payload.units : [];
    const verdictLabels = payload.verdict_labels || {};
    const checkStatusLabels = payload.check_status_labels || {};
    const unitTypeLabels = payload.unit_type_labels || {};

    const framingHost = document.getElementById("failoverFramingNote");
    if (framingHost) {
        framingHost.textContent = payload.framing_note
            || "Failover readiness evidence is not available for this run.";
    }

    const executionHost = document.getElementById("failoverExecutionNote");
    if (executionHost) {
        executionHost.textContent = payload.execution_unavailable_note || "";
    }

    const summaryHost = document.getElementById("failoverFleetSummary");
    if (summaryHost) {
        const total = Object.values(summary).reduce((sum, value) => sum + Number(value || 0), 0);
        const verdictOrder = [
            "SAFE_TO_FAILOVER", "DEGRADED_PROCEED_WITH_RISK", "UNSAFE_DO_NOT_FAILOVER",
            "INSUFFICIENT_EVIDENCE", "NOT_A_FAILOVER_UNIT",
        ];
        summaryHost.innerHTML = `
            <article class="project-progress-card primary">
                <div class="eyebrow">HA units assessed</div>
                <div class="project-progress-value">${escapeHtml(formatNumber(total))}</div>
                <p>Every unit reports one of five verdicts below -- INSUFFICIENT_EVIDENCE is the honest default until the OP.0b preflight battery exists.</p>
            </article>
            <article class="project-progress-card">
                <div class="eyebrow">Verdicts</div>
                <div class="project-status-summary">
                    ${verdictOrder.map(key => `<span>${escapeHtml(verdictLabels[key] || key)}<strong>${escapeHtml(formatNumber(summary[key]))}</strong></span>`).join("")}
                </div>
            </article>
        `;
    }

    const tableHost = document.getElementById("failoverUnitTable");
    if (tableHost) {
        // Cluster-centric presentation: a unit with a parent_id (a VSX
        // Virtual System) is subordinate evidence, never a separate
        // top-level failover target -- it renders nested under its physical
        // cluster/host row, immediately after it. Its own verdict/checks are
        // unchanged; only where it appears in the table changes.
        const topLevelUnits = units.filter(unit => !unit.parent_id);
        const childrenByParent = {};
        units.forEach(unit => {
            if (unit.parent_id) {
                (childrenByParent[unit.parent_id] = childrenByParent[unit.parent_id] || []).push(unit);
            }
        });

        function renderUnitRow(unit, isChild) {
            // Canonical backend identity (display_name, falling back to the
            // canonical unit_id) is always the primary label. `context_vsys`
            // (OP.0b S9) is subordinate, informational-only context -- it is
            // never allowed to define or compose the unit's own name.
            const label = escapeHtml(unit.display_name || unit.unit_id);
            const contextVsys = Array.isArray(unit.context_vsys) ? unit.context_vsys : [];
            const contextLine = contextVsys.length
                ? `<br><span class="eyebrow">VSYS: ${escapeHtml(contextVsys.join(", "))}</span>`
                : '';
            return `
                <tr${isChild ? ' class="failover-child-row"' : ''}>
                    <td${isChild ? ' class="failover-child-cell"' : ''}>${isChild ? '<span class="eyebrow">Virtual System</span> ' : ''}${label}${contextLine}</td>
                    <td>${escapeHtml(unitTypeLabels[unit.unit_type] || unit.unit_type)}</td>
                    <td>${escapeHtml(unit.vendor)}</td>
                    <td>${escapeHtml((unit.members || []).join(", "))}</td>
                    <td>${escapeHtml(unit.cluster_mode)}</td>
                    <td>${statusPill(verdictLabels[unit.verdict] || unit.verdict, failoverVerdictTone(unit.verdict))}</td>
                    <td>${escapeHtml(unit.reason_display || unit.reason)}</td>
                    <td>
                        <details class="failover-check-details">
                            <summary>${escapeHtml((unit.checks || []).length)} checks</summary>
                            <table class="data-table compact">
                                <thead><tr><th>Stop-condition</th><th>Status</th><th>Reason</th><th>Missing evidence</th></tr></thead>
                                <tbody>${(unit.checks || []).map(check => `
                                    <tr>
                                        <td>${escapeHtml(check.label)}</td>
                                        <td>${statusPill(checkStatusLabels[check.status] || check.status, failoverCheckStatusTone(check.status))}</td>
                                        <td>${escapeHtml(check.reason_display || check.reason)}</td>
                                        <td>${escapeHtml(check.missing_evidence || "—")}</td>
                                    </tr>
                                `).join("")}</tbody>
                            </table>
                        </details>
                    </td>
                </tr>
            `;
        }

        tableHost.innerHTML = units.length
            ? `<div class="table-wrap"><table class="data-table"><thead><tr>
                <th>Unit</th><th>Type</th><th>Vendor</th><th>Members</th><th>Mode</th><th>Verdict</th><th>Reason</th><th>Stop-conditions</th>
            </tr></thead><tbody>${topLevelUnits.map(unit => {
                const children = childrenByParent[unit.unit_id] || [];
                return renderUnitRow(unit, false) + children.map(child => renderUnitRow(child, true)).join("");
            }).join("")}</tbody></table></div>`
            : `<div class="empty-state compact"><span>No HA cluster/pair units found in the current inventory.</span></div>`;
    }
}
