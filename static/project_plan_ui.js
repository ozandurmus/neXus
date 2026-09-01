// SecurityExpert report UI — project_plan_ui: Project Plan module

function roadmapStatusTone(status) {
    const value = safe(status).toLowerCase();
    if (value === "done" || value === "complete") return "success";
    if (value === "in_progress" || value === "complete_with_followup") return "info";
    if (value === "blocked") return "danger";
    if (value === "deferred") return "warning";
    return "muted";
}


function roadmapStatusLabel(status) {
    const value = safe(status || "planned").replaceAll("_", " ");
    return value.replace(/\b\w/g, char => char.toUpperCase());
}


function roadmapProgress(value, compact = false) {
    const numeric = Math.max(0, Math.min(100, Number(value || 0)));
    return `
        <div class="roadmap-progress ${compact ? "compact" : ""}" aria-label="${escapeHtml(formatPercent(numeric))} complete">
            <div class="roadmap-progress-track"><span style="width:${numeric}%"></span></div>
            <strong>${escapeHtml(formatPercent(numeric))}</strong>
        </div>
    `;
}


function renderProjectPlan() {
    const plan = projectPlanData || {};
    const badge = document.getElementById("projectPlanBuildBadge");
    if (badge) {
        badge.innerHTML = `<span>Current build</span><strong>${escapeHtml(plan.current_build || "Unknown")}</strong>`;
    }

    const hero = document.getElementById("projectPlanHero");
    if (hero) {
        hero.innerHTML = `
            <article class="project-progress-card primary">
                <div class="eyebrow">Declared roadmap completion</div>
                <div class="project-progress-value">${escapeHtml(formatPercent(plan.overall_progress_percent))}</div>
                ${roadmapProgress(plan.overall_progress_percent)}
                <p>Across the declared 0.5 → 1.0 product roadmap. This is acceptance-criterion completion, not a time estimate.</p>
            </article>
            <article class="project-progress-card">
                <div class="eyebrow">Current major track</div>
                <div class="project-progress-title">${escapeHtml(plan.current_track || "—")}</div>
                <div class="project-progress-value small">${escapeHtml(formatPercent(plan.current_track_progress_percent))}</div>
                ${roadmapProgress(plan.current_track_progress_percent)}
                <p>${escapeHtml(plan.progress_contract || "Roadmap progress is computed from completed acceptance criteria.")}</p>
            </article>
            <article class="project-progress-card">
                <div class="eyebrow">Open backlog</div>
                <div class="project-progress-value small">${escapeHtml(formatNumber((plan.backlog || []).filter(item => item.status !== "done").length))}</div>
                <div class="project-status-summary">
                    ${Object.entries(plan.backlog_counts || {}).map(([key, value]) => `<span>${escapeHtml(roadmapStatusLabel(key))}<strong>${escapeHtml(formatNumber(value))}</strong></span>`).join("")}
                </div>
                <p>Security, collection, architecture, reliability and UX debt stays visible until explicitly closed or deferred.</p>
            </article>
        `;
    }

    const nowNext = document.getElementById("projectNowNext");
    if (nowNext) {
        const horizon = plan.now_next || {};
        const now = horizon.now || {};
        const next = horizon.next || {};
        const upcoming = Array.isArray(horizon.upcoming) ? horizon.upcoming : [];
        nowNext.innerHTML = `
            <article class="horizon-card now">
                <div class="horizon-label">NOW</div>
                <strong>${escapeHtml(now.build || "—")}</strong>
                <h3>${escapeHtml(now.title || "Current build")}</h3>
                <p>${escapeHtml(now.goal || "")}</p>
            </article>
            <article class="horizon-card next">
                <div class="horizon-label">NEXT</div>
                <strong>${escapeHtml(next.build || "—")}</strong>
                <h3>${escapeHtml(next.title || "Next milestone")}</h3>
                <p>${escapeHtml(next.goal || "")}</p>
            </article>
            <article class="horizon-card upcoming">
                <div class="horizon-label">UPCOMING</div>
                <div class="upcoming-list">
                    ${upcoming.map(item => `<div><span>${escapeHtml(item.build || "")}</span><strong>${escapeHtml(item.title || "")}</strong>${statusPill(roadmapStatusLabel(item.status), roadmapStatusTone(item.status))}</div>`).join("")}
                </div>
            </article>
        `;
    }

    const tracksHost = document.getElementById("projectRoadmapTracks");
    if (tracksHost) {
        const tracks = Array.isArray(plan.tracks) ? plan.tracks : [];
        tracksHost.innerHTML = `<div class="roadmap-track-list">${tracks.map(track => `
            <article class="roadmap-track ${safe(track.id) === safe(plan.current_track) ? "current" : ""}">
                <div class="roadmap-track-head">
                    <div>
                        <div class="roadmap-track-id">${escapeHtml(track.id || "")}${track.theme ? ` · ${escapeHtml(track.theme)}` : ""}</div>
                        <h3>${escapeHtml(track.title || "")}</h3>
                    </div>
                    ${statusPill(roadmapStatusLabel(track.status), roadmapStatusTone(track.status))}
                </div>
                ${roadmapProgress(track.progress_percent, true)}
                <div class="roadmap-track-meta">${escapeHtml(formatNumber(track.done_features))} complete features · ${escapeHtml(formatNumber(track.feature_count))} tracked</div>
                <details class="roadmap-track-details">
                    <summary>Show feature map</summary>
                    <div class="roadmap-feature-map">
                        ${(track.features || []).map(feature => `
                            <div class="roadmap-feature-row">
                                <div>
                                    <strong>${escapeHtml(feature.title || "")}</strong>
                                    <span>${escapeHtml(feature.target || feature.introduced || "")}</span>
                                </div>
                                <div class="roadmap-feature-state">${statusPill(roadmapStatusLabel(feature.status), roadmapStatusTone(feature.status))}${roadmapProgress(feature.progress_percent, true)}</div>
                            </div>
                        `).join("")}
                    </div>
                </details>
            </article>
        `).join("")}</div>`;
    }

    const backlogHost = document.getElementById("projectBacklog");
    if (backlogHost) {
        const items = Array.isArray(plan.backlog) ? plan.backlog : [];
        const categories = new Map();
        items.forEach(item => {
            const category = safe(item.category || "Other");
            if (!categories.has(category)) categories.set(category, []);
            categories.get(category).push(item);
        });
        backlogHost.innerHTML = `<div class="backlog-groups">${Array.from(categories.entries()).map(([category, rows]) => `
            <details class="backlog-group" ${rows.some(row => row.priority === "P0" || row.status === "in_progress") ? "open" : ""}>
                <summary><span>${escapeHtml(category)}</span><strong>${escapeHtml(formatNumber(rows.length))}</strong></summary>
                <div class="backlog-items">
                    ${rows.map(item => `
                        <article class="backlog-item">
                            <div class="backlog-item-head">
                                <strong>${escapeHtml(item.title || "")}</strong>
                                <div>${item.priority ? `<span class="priority-badge">${escapeHtml(item.priority)}</span>` : ""}${statusPill(roadmapStatusLabel(item.status), roadmapStatusTone(item.status))}</div>
                            </div>
                            <div class="backlog-target">Target: ${escapeHtml(item.target || "Unscheduled")}</div>
                            ${item.note ? `<p>${escapeHtml(item.note)}</p>` : ""}
                        </article>
                    `).join("")}
                </div>
            </details>
        `).join("")}</div>`;
    }

    const completedHost = document.getElementById("projectCompletedFeatures");
    if (completedHost) {
        const features = Array.isArray(plan.completed_features) ? plan.completed_features : [];
        completedHost.innerHTML = features.map(feature => `
            <article class="completed-feature-card">
                <div class="completed-feature-head">
                    <div><div class="eyebrow">${escapeHtml(feature.introduced || "Delivered")}</div><h3>${escapeHtml(feature.title || "")}</h3></div>
                    ${statusPill("COMPLETE", "success")}
                </div>
                <p>${escapeHtml(feature.summary || "")}</p>
                <div class="feature-why"><strong>Why it matters</strong><span>${escapeHtml(feature.why || "")}</span></div>
                ${feature.evidence ? `<div class="feature-evidence"><strong>Validated evidence</strong><span>${escapeHtml(feature.evidence)}</span></div>` : ""}
            </article>
        `).join("");
    }

    const historyHost = document.getElementById("projectBuildHistory");
    if (historyHost) {
        const builds = Array.isArray(plan.build_history) ? plan.build_history : [];
        historyHost.innerHTML = `<div class="build-history-list">${builds.map(item => `
            <article class="build-history-row">
                <div class="build-history-version">${escapeHtml(item.build || "")}</div>
                <div class="build-history-copy"><strong>${escapeHtml(item.title || "")}</strong><span>${escapeHtml(item.summary || "")}</span></div>
                <div>${statusPill(roadmapStatusLabel(item.status), roadmapStatusTone(item.status))}</div>
            </article>
        `).join("")}</div>`;
    }

    const notesHost = document.getElementById("projectRoadmapNotes");
    if (notesHost) {
        const metadataWarnings = Array.isArray(plan.metadata_warnings) ? plan.metadata_warnings : [];
        notesHost.innerHTML = `
            ${metadataWarnings.length ? `<div class="project-metadata-warning"><strong>Roadmap metadata warning</strong>${metadataWarnings.map(note => `<span>${escapeHtml(note)}</span>`).join("")}</div>` : ""}
            <div class="roadmap-note-list">${(plan.roadmap_notes || []).map(note => `<div><span>•</span><p>${escapeHtml(note)}</p></div>`).join("")}</div>
        `;
    }
}



