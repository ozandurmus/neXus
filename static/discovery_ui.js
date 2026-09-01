// SecurityExpert report UI — discovery_ui: Discovery + Exclusions modules

function lifecycleStateTone(state) {
    const value = safe(state).toUpperCase();
    if (value === "STABLE") return "success";
    if (value === "VALIDATED") return "info";
    if (value === "DISCOVERED") return "neutral";
    if (value === "EXCLUDED") return "muted";
    if (value === "REMOVED") return "danger";
    return "neutral";
}


function jobStatusTone(status) {
    const value = safe(status).toLowerCase();
    if (value === "completed" || value === "running") return "success";
    if (value === "coalesced") return "info";
    if (value === "failed") return "danger";
    if (value === "cancelled") return "muted";
    return "neutral";
}


function renderDiscoveryModule() {
    const payload = discoveryUiData || {};
    const fleet = payload.fleet_summary || {};
    const entities = Array.isArray(payload.entities) ? payload.entities : [];
    const lifecycleLabels = payload.lifecycle_state_labels || {};
    const modeLabels = payload.collection_mode_labels || {};
    const jobLabels = payload.job_status_labels || {};
    const platformLabels = payload.platform_family_labels || {};

    const summaryHost = document.getElementById("discoveryFleetSummary");
    if (summaryHost) {
        const stateCounts = fleet.lifecycle_state_counts || {};
        summaryHost.innerHTML = `
            <article class="project-progress-card primary">
                <div class="eyebrow">Discovered entities</div>
                <div class="project-progress-value">${escapeHtml(formatNumber(fleet.total_entities))}</div>
                <p>${escapeHtml(formatNumber(fleet.deferred_count))} deferred from collection this cycle (lifecycle or safety reason).</p>
            </article>
            <article class="project-progress-card">
                <div class="eyebrow">Lifecycle states</div>
                <div class="project-status-summary">
                    ${Object.entries(stateCounts).length
                        ? Object.entries(stateCounts).map(([key, value]) => `<span>${escapeHtml(lifecycleLabels[key] || key)}<strong>${escapeHtml(formatNumber(value))}</strong></span>`).join("")
                        : `<span>No lifecycle records yet<strong>0</strong></span>`}
                </div>
                <p>Discovered → Validated → Stable is confidence growth; Excluded/Removed retain a safe reason code.</p>
            </article>
        `;
    }

    const coordinatorHost = document.getElementById("discoveryCoordinator");
    if (coordinatorHost) {
        const coordinator = payload.coordinator || {};
        if (!coordinator.available) {
            coordinatorHost.innerHTML = `<div class="empty-state compact"><span>Coordinator not yet wired into this run.</span></div>`;
        } else {
            const budgets = coordinator.budgets || {};
            coordinatorHost.innerHTML = `
                <div class="stats">
                    <span>Active jobs<strong>${escapeHtml(formatNumber(coordinator.active_job_count))}</strong></span>
                    ${Object.entries(budgets).map(([key, row]) => `<span>${escapeHtml(key)} budget<strong>${escapeHtml(formatNumber(row.available))}/${escapeHtml(formatNumber(row.capacity))}</strong></span>`).join("")}
                </div>
                <p>Per-physical-endpoint lock with fixed, conservative vendor/context concurrency budgets. Lock conflicts coalesce onto the active job — a second device session is never opened.</p>
            `;
        }
    }

    const schedulerHost = document.getElementById("discoveryScheduler");
    if (schedulerHost) {
        const scheduler = payload.scheduler || {};
        if (!scheduler.configured) {
            schedulerHost.innerHTML = `<div class="empty-state compact"><span>No RuntimeRoot scheduler policy present — scheduler is disabled by default.</span></div>`;
        } else {
            schedulerHost.innerHTML = `
                <div class="stats">
                    <span>Enabled${statusPill(scheduler.enabled ? "Yes" : "No", scheduler.enabled ? "success" : "muted")}</span>
                    <span>Allowlisted workflows<strong>${escapeHtml(formatNumber(scheduler.workflow_count))}</strong></span>
                </div>
                <p>Scheduler only triggers existing, allowlisted read-only workflows. Manual and scheduled jobs share the same coordinator admission and coalescing safety.</p>
            `;
        }
    }

    const entityHost = document.getElementById("discoveryEntityTable");
    if (entityHost) {
        entityHost.innerHTML = entities.length
            ? `<div class="table-wrap"><table class="data-table"><thead><tr>
                <th>Vendor</th><th>Entity</th><th>Lifecycle</th><th>Confidence</th>
                <th>Platform</th><th>Shell</th><th>Planned mode</th><th>Allowed</th><th>Reason</th>
            </tr></thead><tbody>${entities.map(row => `
                <tr>
                    <td>${escapeHtml(row.vendor)}</td>
                    <td>${escapeHtml(row.canonical_id)}</td>
                    <td>${statusPill(lifecycleLabels[row.lifecycle_state] || row.lifecycle_state, lifecycleStateTone(row.lifecycle_state))}</td>
                    <td>${escapeHtml(formatNumber(row.confidence))}%</td>
                    <td>${escapeHtml(row.platform_label || platformLabels[row.platform_family] || row.platform_family || "—")}</td>
                    <td>${escapeHtml(row.shell_type)}</td>
                    <td>${escapeHtml(modeLabels[row.planned_mode] || row.planned_mode)}</td>
                    <td>${statusPill(row.plan_allowed ? "Allowed" : "Deferred", row.plan_allowed ? "success" : "muted")}</td>
                    <td>${escapeHtml(row.plan_reason_code)}</td>
                </tr>
            `).join("")}</tbody></table></div>`
            : `<div class="empty-state compact"><span>No discovery lifecycle records available yet. Populated once Phase 4 wires collectors through the coordinator.</span></div>`;
    }

    const jobsHost = document.getElementById("discoveryRecentJobs");
    if (jobsHost) {
        const jobs = (payload.coordinator || {}).recent_jobs || [];
        jobsHost.innerHTML = jobs.length
            ? `<div class="table-wrap"><table class="data-table"><thead><tr>
                <th>Job</th><th>Vendor</th><th>Scope</th><th>Provenance</th><th>Status</th><th>Reason</th>
            </tr></thead><tbody>${jobs.map(job => `
                <tr>
                    <td>${escapeHtml(job.job_id)}</td>
                    <td>${escapeHtml(job.vendor)}</td>
                    <td>${escapeHtml(job.workflow_scope)}</td>
                    <td>${escapeHtml(job.provenance)}</td>
                    <td>${statusPill(jobLabels[job.status] || job.status, jobStatusTone(job.status))}</td>
                    <td>${escapeHtml(job.reason || "—")}</td>
                </tr>
            `).join("")}</tbody></table></div>`
            : `<div class="empty-state compact"><span>No coordinator job history yet.</span></div>`;
    }
}


function renderExclusionsModule() {
    const payload = exclusionsUiData || {};
    const fleet = payload.fleet_summary || {};
    const entities = Array.isArray(payload.entities) ? payload.entities : [];

    const summaryHost = document.getElementById("exclusionsFleetSummary");
    if (summaryHost) {
        const vendorCounts = fleet.vendor_counts || {};
        summaryHost.innerHTML = `
            <article class="project-progress-card primary">
                <div class="eyebrow">Excluded identities</div>
                <div class="project-progress-value">${escapeHtml(formatNumber(fleet.total_exclusions))}</div>
                <p>Applied before direct polling by the local RuntimeRoot policy. No repository defaults.</p>
            </article>
            <article class="project-progress-card">
                <div class="eyebrow">By vendor</div>
                <div class="project-status-summary">
                    ${Object.entries(vendorCounts).length
                        ? Object.entries(vendorCounts).map(([key, value]) => `<span>${escapeHtml(key)}<strong>${escapeHtml(formatNumber(value))}</strong></span>`).join("")
                        : `<span>No exclusions active<strong>0</strong></span>`}
                </div>
            </article>
        `;
    }

    const entityHost = document.getElementById("exclusionsEntityTable");
    if (entityHost) {
        entityHost.innerHTML = entities.length
            ? `<div class="table-wrap"><table class="data-table"><thead><tr>
                <th>Vendor</th><th>Identity</th><th>Reason</th>
            </tr></thead><tbody>${entities.map(row => `
                <tr>
                    <td>${escapeHtml(row.vendor)}</td>
                    <td>${escapeHtml(row.identity)}</td>
                    <td>${escapeHtml(row.reason || "—")}</td>
                </tr>
            `).join("")}</tbody></table></div>`
            : `<div class="empty-state compact"><span>No inventory exclusions active. Add entries to data/state/inventory_exclusions.json (local RuntimeRoot policy, not the repository) to exclude identities from direct polling.</span></div>`;
    }
}

