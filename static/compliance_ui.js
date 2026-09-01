// SecurityExpert report UI — compliance_ui: Compliance / Crypto module

let complianceSelectedSubjectId = "__fleet__";
let complianceVendorFilter = "all";
let complianceStatusFilter = "all";
const COMPLIANCE_FRAMEWORKS = ["CIS", "PCI-DSS", "BDDK"];
const complianceFrameworkFilter = new Set();   // 0.7.2 — empty = no filter

// CON.1 C1-3: complianceUiData starts empty and is populated later by
// initializeReport(payloads) -- see the matching comment in
// configuration_ui.js's rebuildConfigDevices(). rebuildComplianceSubjects()
// is called once below (harmless against the empty default) and again from
// initializeReport, after the real payload lands.
let complianceSubjects = [];
function rebuildComplianceSubjects() {
    complianceSubjects = Array.isArray(complianceUiData?.subjects) ? complianceUiData.subjects : [];
}
rebuildComplianceSubjects();


// 0.7.5 — compliance trend layer. Both render "" when there is not enough
// history, so every empty / first-run state is untouched.
function complianceSparkline(records) {
    const rows = (Array.isArray(records) ? records : [])
        .filter(r => r && Number.isFinite(Number(r.aligned_percent)));
    const points = rows.map(r => Number(r.aligned_percent));
    if (points.length < 2) return "";
    const w = 132;
    const h = 30;
    const pad = 3;
    const min = Math.min(...points);
    const max = Math.max(...points);
    const span = max - min || 1;
    const step = (w - pad * 2) / (points.length - 1);
    const coords = points.map((value, index) => {
        const x = pad + index * step;
        const y = pad + (h - pad * 2) * (1 - (value - min) / span);
        return { x: x.toFixed(1), y: y.toFixed(1), reconstructed: !!rows[index].reconstructed };
    });

    // 0.7.7 -- a reconstructed (offline, narrower-methodology) point never
    // shares a solid line segment with a live checkpoint point: draw one
    // polyline per contiguous live/reconstructed run. Each run also carries
    // the last point of the previous run so the connecting segment still
    // renders (styled as the newer point's kind).
    const runs = [];
    coords.forEach((pt, index) => {
        if (index === 0 || pt.reconstructed !== coords[index - 1].reconstructed) {
            const run = { reconstructed: pt.reconstructed, points: [] };
            if (index > 0) run.points.push(coords[index - 1]);
            runs.push(run);
        }
        runs[runs.length - 1].points.push(pt);
    });

    const polylines = runs.map(run => `
        <polyline points="${run.points.map(p => `${p.x},${p.y}`).join(" ")}" fill="none" stroke="currentColor"
                  stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round"
                  ${run.reconstructed ? 'stroke-dasharray="3,2" opacity="0.55"' : ""} />
    `).join("");
    const markers = coords.map(p => `
        <circle cx="${p.x}" cy="${p.y}" r="${p.reconstructed ? 1.5 : 2}" fill="${p.reconstructed ? "none" : "currentColor"}"
                stroke="currentColor" stroke-width="${p.reconstructed ? 1 : 0}" opacity="${p.reconstructed ? 0.55 : 1}" />
    `).join("");
    const hasReconstructed = coords.some(p => p.reconstructed);
    return `
        <svg class="compliance-sparkline${hasReconstructed ? " has-reconstructed" : ""}" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none"
             role="img" aria-label="Aligned percent over the last ${points.length} checkpoints${hasReconstructed ? " (includes offline-reconstructed points, dashed)" : ""}:
             ${points.map(p => p.toFixed(1)).join(", ")}">
            ${polylines}
            ${markers}
        </svg>
    `;
}

function complianceTrendChip(trend) {
    if (!trend || typeof trend !== "object") return "";
    const delta = Number(trend.delta_aligned_percent || 0);
    const direction = safe(trend.direction || "flat");
    const glyph = direction === "up" ? "▲" : direction === "down" ? "▼" : "·";
    const sign = delta > 0 ? "+" : "";
    const since = safe(trend.previous_date);
    return `
        <span class="compliance-trend-chip ${escapeHtml(direction)}">
            <span aria-hidden="true">${glyph}</span>
            ${escapeHtml(sign + delta.toFixed(1))} pts${since ? ` since ${escapeHtml(since)}` : ""}
        </span>
    `;
}


function complianceStatusTone(status) {
    const value = safe(status).toUpperCase();
    if (value === "PASS") return "success";
    if (value === "FINDING") return "danger";
    if (value === "UNKNOWN") return "muted";
    if (value === "NOT_APPLICABLE") return "info";
    if (value === "PLANNED") return "warning";
    if (value === "WAIVED") return "info";
    return "neutral";
}


function complianceStatusMeaning(status) {
    const value = safe(status).toUpperCase();
    if (value === "PASS") return "Observed evidence supports this control area.";
    if (value === "FINDING") return "Observed evidence indicates a gap or risk signal.";
    if (value === "UNKNOWN") return "Evidence is missing or insufficient for a conclusion.";
    if (value === "NOT_APPLICABLE") return "This control does not apply for the selected vendor context.";
    if (value === "PLANNED") return "This control area is intentionally roadmap-planned.";
    if (value === "WAIVED") return "A dated, approved waiver in the local assignment policy applies to this cell.";
    return "Status meaning unavailable.";
}


function renderComplianceLegend() {
    const host = document.getElementById("complianceLegend");
    if (!host) return;
    const statuses = ["PASS", "FINDING", "UNKNOWN", "PLANNED"];
    host.innerHTML = `
        <div class="compliance-legend-title">How to read statuses</div>
        <div class="compliance-legend-grid">
            ${statuses.map(status => `
                <article class="compliance-legend-item">
                    <div class="compliance-legend-pill">${statusPill(status, complianceStatusTone(status))}</div>
                    <p>${escapeHtml(complianceStatusMeaning(status))}</p>
                </article>
            `).join("")}
        </div>
    `;
}


function compliancePayload() {
    return complianceUiData?.available ? (complianceUiData || {}) : {};
}


function complianceVendorLabel(vendorKey) {
    return safe(vendorKey) === "check_point" ? "Check Point" : "Palo Alto";
}


function complianceSubjectOrdinal(subject) {
    const subjectId = safe(subject?.subject_id);
    const parts = subjectId.split("-");
    const numeric = Number(parts[1] || "0");
    return Number.isFinite(numeric) && numeric > 0 ? numeric : 0;
}


function complianceSourceDevice(subject) {
    const sourceIndex = Number(subject?.source_config_index);
    if (Number.isInteger(sourceIndex) && sourceIndex >= 0 && configDevices[sourceIndex]) {
        return configDevices[sourceIndex];
    }
    const vendorKey = safe(subject?.vendor_key);
    const ordinal = complianceSubjectOrdinal(subject);
    if (!ordinal) return null;
    const pool = configDevices.filter(device => safe(device.vendor_key) === vendorKey);
    return pool[ordinal - 1] || null;
}


function complianceVendorCode(subject) {
    const source = complianceSourceDevice(subject);
    const entityType = safe(source?.entity_type);
    if (entityType === "vsx_host" || entityType === "virtual_system") return "VSX";
    return safe(subject?.vendor_key) === "check_point" ? "CP" : "PAN";
}


function complianceSourceName(subject) {
    const source = complianceSourceDevice(subject);
    if (!source) return "";
    return safe(source.display_name || source.name || source.device_name || source.device || "");
}


function complianceRenderableControls(controls, includeNotApplicable = false) {
    const rows = Array.isArray(controls) ? controls : [];
    const visible = includeNotApplicable
        ? rows
        : rows.filter(control => safe(control?.status).toUpperCase() !== "NOT_APPLICABLE");
    return complianceApplyFrameworkFilter(visible);
}


// 0.7.2 — framework filter chips. A control is shown when the filter is empty
// or it has an applicable membership in one of the selected frameworks.
function complianceControlMatchesFrameworkFilter(control) {
    if (!complianceFrameworkFilter.size) return true;
    const frameworks = Array.isArray(control?.frameworks) ? control.frameworks : [];
    return frameworks.some(f => f && f.applies !== false
        && complianceFrameworkFilter.has(safe(f.framework).toUpperCase()));
}


function complianceApplyFrameworkFilter(controls) {
    const rows = Array.isArray(controls) ? controls : [];
    if (!complianceFrameworkFilter.size) return rows;
    return rows.filter(complianceControlMatchesFrameworkFilter);
}


function complianceFilteredFrameworkNames() {
    return complianceFrameworkFilter.size
        ? COMPLIANCE_FRAMEWORKS.filter(name => complianceFrameworkFilter.has(name))
        : COMPLIANCE_FRAMEWORKS;
}


function renderComplianceFrameworkFilter() {
    const host = document.getElementById("complianceFrameworkFilter");
    if (!host) return;
    if (!complianceUiData?.available) { host.innerHTML = ""; return; }
    const chips = COMPLIANCE_FRAMEWORKS.map(name => {
        const active = complianceFrameworkFilter.has(name);
        return `<button type="button" class="compliance-framework-chip${active ? " active" : ""}" data-framework-chip="${escapeHtml(name)}" aria-pressed="${active ? "true" : "false"}">${escapeHtml(name)}</button>`;
    }).join("");
    const clear = complianceFrameworkFilter.size
        ? `<button type="button" class="compliance-framework-chip clear" data-framework-chip="__clear__">Clear</button>`
        : "";
    host.innerHTML = `<span class="compliance-framework-filter-label">Framework filter</span>${chips}${clear}`;
    host.querySelectorAll("[data-framework-chip]").forEach(btn => {
        btn.addEventListener("click", () => {
            const key = btn.dataset.frameworkChip;
            if (key === "__clear__") {
                complianceFrameworkFilter.clear();
            } else if (complianceFrameworkFilter.has(key)) {
                complianceFrameworkFilter.delete(key);
            } else {
                complianceFrameworkFilter.add(key);
            }
            renderComplianceModule();
        });
    });
}


function complianceSubjectEligible(subject) {
    const vendorKey = safe(subject?.vendor_key);
    if (vendorKey !== "check_point") return true;
    const source = complianceSourceDevice(subject);
    const entityType = safe(source?.entity_type);
    if (entityType === "vsx_host" || entityType === "virtual_system") return false;
    return true;
}


function complianceStatusCount(controls, status) {
    return controls.filter(control => safe(control?.status).toUpperCase() === status).length;
}


function complianceScopedSubjects() {
    let rows = complianceSubjects.filter(subject => safe(subject.availability || "AVAILABLE") === "AVAILABLE");
    rows = rows.filter(complianceSubjectEligible);
    if (complianceVendorFilter !== "all") {
        rows = rows.filter(subject => safe(subject.vendor_key) === complianceVendorFilter);
    }
    if (complianceStatusFilter !== "all") {
        rows = rows.filter(subject => {
            const subjectStatus = safe(subject.status).toUpperCase();
            if (subjectStatus === complianceStatusFilter) return true;
            return (subject.controls || []).some(control => safe(control.status).toUpperCase() === complianceStatusFilter);
        });
    }
    return rows;
}


function selectedComplianceSubject() {
    return complianceScopedSubjects().find(subject => safe(subject.subject_id) === safe(complianceSelectedSubjectId)) || null;
}


function complianceControlCard(control, options = {}) {
    const showFramework = options.showFramework !== false;
    const showRoadmap = options.showRoadmap !== false;
    const showControlId = options.showControlId !== false;
    const showTraceability = options.showTraceability !== false;
    const compact = options.compact === true;
    const status = safe(control?.status || "UNKNOWN").toUpperCase();
    const mappings = control?.framework_mappings || {};
    const severity = safe(control?.severity || "").toLowerCase();
    const rationale = safe(control?.rationale || "");
    const frameworks = Array.isArray(control?.frameworks) ? control.frameworks : [];
    const roadmap = Array.isArray(control?.roadmap_links) ? control.roadmap_links : [];
    const evidenceFields = Array.isArray(control?.evidence_fields) ? control.evidence_fields.filter(Boolean) : [];
    const benchmark = safe(control?.benchmark);
    const benchmarkReference = safe(control?.benchmark_reference);
    const lifecycle = safe(control?.control_lifecycle);
    const plannedReason = safe(control?.planned_reason);
    const futureEvidence = safe(control?.future_evidence_requirement);
    const scope = safe(control?.scope || "");
    const isUserCheck = safe(control?.control_class || "") === "user_check";
    const isAdvisory = control?.advisory === true;
    const packId = safe(control?.pack?.pack_id || "");
    const packVersion = safe(control?.pack?.pack_version || "");
    const checkSteps = Array.isArray(control?.check_steps) ? control.check_steps : [];
    const evidencePlane = safe(control?.evidence_plane || "").replaceAll("_", " ");
    const evidenceCoverage = safe(control?.evidence_coverage || "").replaceAll("_", " ");
    const benchmarkLabel = benchmark && benchmarkReference ? `${benchmark} · ${benchmarkReference}` : (benchmark || benchmarkReference);
    return `
        <article class="compliance-control-card ${escapeHtml(complianceStatusTone(status))}${compact ? " compact" : ""}">
            <div class="compliance-control-head">
                <div>
                    <h3>${escapeHtml(control?.title || "Control")}</h3>
                    ${benchmarkLabel ? `<div class="compliance-control-benchmark">${escapeHtml(benchmarkLabel)}</div>` : ""}
                    ${showControlId ? `<div class="compliance-control-id">${escapeHtml(control?.control_id || "")}</div>` : ""}
                </div>
                <div class="compliance-control-pills">
                    ${isUserCheck ? `<span class="statuspill neutral" title="Defined in a local compliance check pack">user-defined</span>` : ""}
                    ${isAdvisory ? `<span class="statuspill warning" title="Advisory — shown but excluded from the coverage score">advisory</span>` : ""}
                    ${severity ? `<span class="statuspill ${severity === "critical" || severity === "high" ? "danger" : (severity === "medium" ? "warning" : "neutral")}">${escapeHtml(severity)}</span>` : ""}
                    ${statusPill(status, complianceStatusTone(status))}
                </div>
            </div>
            <p>${escapeHtml(control?.evidence_summary || "No summary available.")}</p>
            ${rationale ? `<p class="detail-subtitle">${escapeHtml(rationale)}</p>` : ""}
            ${showTraceability ? `<div class="compliance-traceability-grid">
                ${scope ? `<div><strong>Scope</strong><span>${escapeHtml(scope)}</span></div>` : ""}
                ${lifecycle ? `<div><strong>Lifecycle</strong><span>${escapeHtml(lifecycle.replaceAll("_", " "))}</span></div>` : ""}
                ${evidencePlane ? `<div><strong>Evidence plane</strong><span>${escapeHtml(evidencePlane)}</span></div>` : ""}
                ${evidenceCoverage ? `<div><strong>Coverage</strong><span>${escapeHtml(evidenceCoverage)}</span></div>` : ""}
            </div>` : ""}
            ${showTraceability && evidenceFields.length ? `<div class="compliance-evidence-fields"><strong>Evidence checked</strong><span>${escapeHtml(evidenceFields.join(", "))}</span></div>` : ""}
            ${status === "PLANNED" && plannedReason ? `<div class="compliance-planned-note"><strong>Evidence gap</strong><span>${escapeHtml(plannedReason)}</span>${futureEvidence ? `<span class="future-evidence">Required: ${escapeHtml(futureEvidence)}</span>` : ""}</div>` : ""}
            ${showFramework ? (frameworks.length ? `<div class="compliance-mapping-grid">
                ${frameworks.map(f => {
                    const name = safe(f.framework || "").toUpperCase();
                    const ref = safe(f.reference || "");
                    const applies = f.applies !== false;
                    return `<div><strong>${escapeHtml(name)}</strong><span>${applies ? escapeHtml(ref || "mapped") : "not applicable"}</span><small>${applies ? "evidence-area" : "no equivalent"}</small></div>`;
                }).join("")}
            </div>` : `<div class="compliance-mapping-grid">
                ${["cis", "pci_dss", "bddk"].map(key => {
                    const row = mappings[key] || {};
                    const mappingType = safe(row.mapping_type || "").replaceAll("_", " ");
                    const area = safe(row.control_area || "evidence-backed control area");
                    const ref = safe(row.framework_reference || "");
                    const line = ref ? `${area} (${ref})` : area;
                    return `<div><strong>${escapeHtml(key.toUpperCase())}</strong><span>${escapeHtml(line)}</span>${mappingType ? `<small>${escapeHtml(mappingType)}</small>` : ""}</div>`;
                }).join("")}
            </div>`) : ""}
            ${showRoadmap && roadmap.length ? `<div class="compliance-roadmap-links">${roadmap.map(item => `<button type="button" class="compliance-roadmap-link" data-open-plan="${escapeHtml(item.feature_id || "")}">${escapeHtml(item.title || item.feature_id || "roadmap item")}</button>`).join("")}</div>` : ""}
            ${(rationale || evidenceFields.length || frameworks.length) ? `
            <button type="button" class="compliance-explain-toggle" data-explain-toggle aria-expanded="false">Explain</button>
            <div class="compliance-explain-panel" hidden>
                ${rationale ? `<p class="compliance-explain-rationale">${escapeHtml(rationale)}</p>` : ""}
                ${isUserCheck && packId ? `<div class="compliance-explain-row"><strong>Source pack</strong><span>${escapeHtml(packId)}${packVersion ? ` @ ${escapeHtml(packVersion)}` : ""}</span></div>` : ""}
                ${evidenceFields.length ? `<div class="compliance-explain-row"><strong>Evidence fields</strong><span>${escapeHtml(evidenceFields.join(", "))}</span></div>` : ""}
                ${checkSteps.length ? `<div class="compliance-explain-row"><strong>Evidence steps</strong><span>${checkSteps.map(s => `#${escapeHtml(String(s.step || "?"))}: expected ${escapeHtml(safe(s.expected))} — observed ${escapeHtml(safe(s.observed))}`).join(" · ")}</span></div>` : ""}
                ${frameworks.length ? `<div class="compliance-explain-row"><strong>Framework references</strong><span>${frameworks.map(f => {
                    const name = safe(f.framework || "").toUpperCase();
                    const ref = safe(f.reference || "");
                    return `${escapeHtml(name)} ${escapeHtml(f.applies === false ? "not applicable" : (ref || "mapped"))}`;
                }).join(" · ")}</span></div>` : ""}
            </div>` : ""}
        </article>
    `;
}


function renderComplianceSubjectList() {
    const host = document.getElementById("complianceSubjectList");
    const stats = document.getElementById("complianceFleetStats");
    if (!host) return;
    if (!complianceUiData?.available) {
        host.innerHTML = `<div class="empty-list">Compliance posture is not attached to this export.</div>`;
        if (stats) stats.textContent = "No compliance payload";
        return;
    }
    const rows = complianceScopedSubjects();
    if (stats) {
        const fleet = compliancePayload()?.fleet || {};
        const unavailable = Number(fleet.unavailable_subjects || 0);
        stats.textContent = `${formatNumber(rows.length)} assessed device${rows.length === 1 ? "" : "s"}${unavailable ? ` · ${formatNumber(unavailable)} not assessed` : ""}`;
    }

    host.innerHTML = `
        <div class="compliance-subject-item ${complianceSelectedSubjectId === "__fleet__" ? "active" : ""}" data-compliance-subject="__fleet__">
            <div class="compliance-subject-title">Fleet controls</div>
            <div class="compliance-subject-meta">Global safeguards, platform signals, and roadmap-planned controls</div>
        </div>
        ${rows.map(subject => {
            const status = safe(subject.status).toUpperCase() || "UNKNOWN";
            const sourceName = complianceSourceName(subject);
            const scopedControls = complianceRenderableControls(subject.controls);
            const findingCount = complianceStatusCount(scopedControls, "FINDING");
            return `
                <div class="compliance-subject-item ${safe(subject.subject_id) === safe(complianceSelectedSubjectId) ? "active" : ""}" data-compliance-subject="${escapeHtml(subject.subject_id)}">
                    <div class="compliance-subject-title-row">
                        <div class="compliance-subject-title">${escapeHtml(sourceName || subject.subject_id || "Device")} · ${escapeHtml(complianceVendorCode(subject))}</div>
                        ${findingCount ? `<span class="compliance-finding-badge">${formatNumber(findingCount)} finding${findingCount === 1 ? "" : "s"}</span>` : ""}
                    </div>
                    <div class="compliance-subject-meta">${formatNumber(scopedControls.length)} evaluated controls</div>
                </div>
            `;
        }).join("")}
    `;

    host.querySelectorAll("[data-compliance-subject]").forEach(node => {
        node.addEventListener("click", () => {
            complianceSelectedSubjectId = node.dataset.complianceSubject || "__fleet__";
            renderComplianceSubjectList();
            renderComplianceContent();
        });
    });
}


function renderComplianceFleetCards() {
    const cards = document.getElementById("complianceFleetCards");
    if (!cards) return;
    if (!complianceUiData?.available) {
        cards.innerHTML = "";
        return;
    }
    const fleet = compliancePayload()?.fleet || {};
    const subjectCounts = fleet.subject_status_counts || {};
    cards.innerHTML = [
        metricCard("✓ ASSESSED", formatNumber(fleet.evaluated_subjects), "Devices with current-state evidence", "success"),
        metricCard("! ACTION NEEDED", formatNumber(subjectCounts.FINDING), "Devices with one or more findings", "danger"),
        metricCard("? EVIDENCE GAP", formatNumber(subjectCounts.UNKNOWN), "Assessed devices with incomplete evidence", "muted"),
        metricCard("○ NOT ASSESSED", formatNumber(fleet.unavailable_subjects), "Current-state evidence was not collected", "neutral"),
    ].join("");
}


function compliancePostureTone(posture) {
    const value = safe(posture).toUpperCase();
    if (value === "ALIGNED") return "success";
    if (value === "FINDING") return "danger";
    return "muted";
}


function complianceCoveragePillTone(coverage) {
    const value = safe(coverage).toUpperCase();
    if (value === "COVERED") return "success";
    if (value === "PARTIALLY_COVERED") return "warning";
    return "muted";
}


function renderComplianceCoverageOverview() {
    const host = document.getElementById("complianceCoverageOverview");
    if (!host) return;
    const payload = compliancePayload();
    const ov = payload.compliance_overview;
    if (!complianceUiData?.available || !ov || !ov.total_controls) {
        host.innerHTML = `<div class="section-heading"><div><div class="eyebrow">Coverage</div><h2>Control coverage</h2></div></div>
            <div class="empty-state compact"><span>No control-coverage roll-up in this export.</span></div>`;
        return;
    }
    const cells = ov.cells || {};
    const policy = payload.assignment_policy || {};
    const frameworks = ov.by_framework || {};
    const policyNote = policy.active
        ? `Assignment policy active (${escapeHtml(policy.source || "runtime-policy")}) · default ${escapeHtml(policy.default_mode || "all_applicable")} · ${formatNumber(policy.groups || 0)} group(s) · ${formatNumber(policy.waivers || 0)} waiver(s).`
        : `No local assignment policy — every catalogued control applies to every assessed device.`;

    host.innerHTML = `
        <div class="section-heading">
            <div>
                <div class="eyebrow">Coverage · catalog ${escapeHtml(ov.catalog_version || "")}</div>
                <h2>Control coverage &amp; framework readiness</h2>
                <div class="detail-subtitle">${formatNumber(ov.monitored_controls)} of ${formatNumber(ov.total_controls)} catalogued controls are assigned and have evidence on ${formatNumber(ov.subjects)} assessed device(s).</div>
            </div>
        </div>
        <div class="compliance-kpi-grid">
            ${metricCard("CATALOGUED", formatNumber(ov.total_controls), `${formatNumber(ov.monitored_controls)} monitored · ${formatNumber(ov.unmonitored_controls)} not yet`, ov.unmonitored_controls ? "warning" : "success")}
            ${metricCard("ALIGNED", `${Number(ov.aligned_percent || 0).toFixed(1)}%`, `${formatNumber(cells.aligned)} of ${formatNumber((cells.aligned || 0) + (cells.finding || 0) + (cells.unknown || 0) + (cells.planned || 0))} evaluated cells`, "success")}
            ${metricCard("RISK-WEIGHTED", `${Number(ov.risk_weighted_alignment_percent || 0).toFixed(1)}%`, "Alignment weighted by control severity", "muted")}
            ${metricCard("FINDINGS", formatNumber(cells.finding), `${formatNumber(cells.unknown)} evidence gaps · ${formatNumber(cells.waived)} waived`, Number(cells.finding) > 0 ? "danger" : "success")}
        </div>
        ${(() => {
            const spark = complianceSparkline(ov.history);
            const chip = complianceTrendChip(ov.trend);
            return (spark || chip)
                ? `<div class="compliance-trend-row wide"><span class="compliance-trend-label">Aligned % trend</span>${spark}${chip}</div>`
                : "";
        })()}
        <div class="compliance-framework-readiness">
            ${complianceFilteredFrameworkNames().map(name => {
                const fw = frameworks[name] || {};
                const reqs = Array.isArray(fw.requirements) ? fw.requirements : [];
                const rc = fw.requirement_counts || {};
                const versionLine = [safe(fw.version), safe(fw.profile)].filter(Boolean).join(" · ");
                const bar = reqs.length ? `<div class="compliance-req-bar" role="img" aria-label="${formatNumber(rc.COVERED || 0)} covered, ${formatNumber(rc.PARTIALLY_COVERED || 0)} partial, ${formatNumber(rc.UNCOVERED || 0)} uncovered, ${formatNumber(rc.NOT_APPLICABLE || 0)} not applicable">
                        ${["COVERED", "PARTIALLY_COVERED", "UNCOVERED", "NOT_APPLICABLE"].map(k => {
                            const n = Number(rc[k] || 0);
                            // CON.1: the console's CSP (style-src 'self') blocks a JS-set
                            // inline style="", so each segment's share of the bar is a
                            // fixed w-pct-N class (static/style.css), not a computed
                            // flex value -- rounded to the nearest whole percent of reqs.length.
                            const pct = Math.round((n / reqs.length) * 100);
                            return n ? `<span class="seg ${k.toLowerCase()} w-pct-${pct}" title="${escapeHtml(k.replace(/_/g, " "))}: ${n}">${n}</span>` : "";
                        }).join("")}
                    </div>` : "";
                const rows = reqs.map(r => `
                    <div class="compliance-req-row">
                        <div class="compliance-req-id">${escapeHtml(r.section || "")} · ${escapeHtml(r.id || "")}</div>
                        <div class="compliance-req-title">${escapeHtml(r.title || "")}${(r.control_ids && r.control_ids.length) ? `<small>${r.control_ids.map(escapeHtml).join(", ")}</small>` : `<small>no mapped control</small>`}</div>
                        <div class="compliance-req-pills">
                            ${statusPill(safe(r.coverage || "UNCOVERED").replace(/_/g, " "), complianceCoveragePillTone(r.coverage))}
                            ${statusPill(safe(r.posture || "UNKNOWN"), compliancePostureTone(r.posture))}
                        </div>
                    </div>`).join("");
                return `
                    <article class="compliance-framework-card">
                        <div class="compliance-framework-head">
                            <strong>${escapeHtml(name)}</strong>
                            ${statusPill(safe(fw.coverage || "UNCOVERED").replace(/_/g, " "), complianceCoveragePillTone(fw.coverage))}
                        </div>
                        <div class="compliance-framework-meta">${formatNumber(fw.monitored || 0)} / ${formatNumber(fw.controls || 0)} controls monitored · ${formatNumber(fw.aligned || 0)} aligned · ${formatNumber(fw.finding || 0)} finding(s)</div>
                        ${versionLine ? `<div class="compliance-framework-meta subtle">${escapeHtml(versionLine)}</div>` : ""}
                        ${bar}
                        ${reqs.length ? `<button type="button" class="compliance-explain-toggle" data-explain-toggle aria-expanded="false">Requirements (${formatNumber(reqs.length)})</button>
                        <div class="compliance-explain-panel" hidden><div class="compliance-req-list">${rows}</div>${(fw.unmapped_control_refs && fw.unmapped_control_refs.length) ? `<div class="compliance-req-row subtle"><small>Unmapped control references: ${fw.unmapped_control_refs.map(escapeHtml).join(", ")}</small></div>` : ""}</div>` : ""}
                    </article>
                `;
            }).join("")}
        </div>
        <div class="posture-note"><strong>Assignment:</strong> ${policyNote}</div>
    `;
}


function complianceSubjectStatusCounts(subject) {
    const rows = complianceRenderableControls(subject?.controls);
    return {
        total: rows.length,
        pass: complianceStatusCount(rows, "PASS"),
        finding: complianceStatusCount(rows, "FINDING"),
        unknown: complianceStatusCount(rows, "UNKNOWN"),
    };
}


function renderComplianceHeader(subject) {
    const eyebrow = document.getElementById("complianceEyebrow");
    const title = document.getElementById("complianceTitle");
    const subtitle = document.getElementById("complianceSubtitle");
    const status = document.getElementById("complianceDetailStatus");
    if (!eyebrow || !title || !subtitle || !status) return;

    if (!complianceUiData?.available) {
        eyebrow.textContent = "Compliance Posture";
        title.textContent = "Compliance posture unavailable";
        subtitle.textContent = "This export does not include a compliance evidence cycle.";
        status.innerHTML = statusPill("Not collected", "muted");
        return;
    }

    if (!subject) {
        const fleet = compliancePayload()?.fleet || {};
        eyebrow.textContent = "Compliance · Fleet";
        title.textContent = "Evidence-backed control areas";
        subtitle.textContent = "No compliance certification claim. Results are bounded to observed evidence areas.";
        status.innerHTML = [
            statusPill(`${formatNumber(fleet.evaluated_subjects)} assessed`, "success"),
            Number(fleet.unavailable_subjects || 0) ? statusPill(`${formatNumber(fleet.unavailable_subjects)} not assessed`, "neutral") : "",
        ].join("");
        return;
    }

    const sourceName = complianceSourceName(subject);
    const counts = complianceSubjectStatusCounts(subject);
    eyebrow.textContent = `Compliance · ${complianceVendorCode(subject)}`;
    title.textContent = sourceName || subject.subject_id || "Device";
    subtitle.textContent = `${formatNumber(counts.total)} evaluated control${counts.total === 1 ? "" : "s"} for this device`;
    status.innerHTML = counts.finding
        ? statusPill(`${formatNumber(counts.finding)} finding${counts.finding === 1 ? "" : "s"}`, "danger")
        : (counts.unknown ? statusPill(`${formatNumber(counts.unknown)} unknown`, "muted") : statusPill("No findings", "success"));
}


function renderComplianceFleetView() {
    const disclaimer = document.getElementById("complianceDisclaimer");
    if (disclaimer) {
        const message = safe(complianceUiData?.disclaimer) || "Not a compliance certification or complete framework assessment.";
        disclaimer.innerHTML = `<strong>Disclaimer:</strong> ${escapeHtml(message)} Framework mappings are informational evidence areas only.`;
    }

    renderComplianceLegend();
    renderComplianceFleetCards();
    renderComplianceCoverageOverview();

    const payload = compliancePayload();
    const filterActive = complianceFrameworkFilter.size > 0;
    const filterLabel = complianceFilteredFrameworkNames().join(" / ");
    const emptyMsg = filterActive
        ? `No controls map to the selected framework(s): ${escapeHtml(filterLabel)}.`
        : null;
    const fleetControls = complianceApplyFrameworkFilter(Array.isArray(payload.fleet_controls) ? payload.fleet_controls : []);
    const platformControls = complianceApplyFrameworkFilter(Array.isArray(payload.platform_controls) ? payload.platform_controls : []);
    const fleetHost = document.getElementById("complianceFleetControls");
    const platformHost = document.getElementById("compliancePlatformControls");

    if (fleetHost) {
        fleetHost.innerHTML = fleetControls.length
            ? `<div class="compliance-control-grid">${fleetControls.map(control => complianceControlCard(control, { showFramework: true, showRoadmap: true, showControlId: true })).join("")}</div>`
            : `<div class="empty-state compact"><span>${emptyMsg || "No fleet control rows available."}</span></div>`;
        fleetHost.querySelectorAll("[data-open-plan]").forEach(button => {
            button.addEventListener("click", () => switchModule("project-plan"));
        });
    }
    if (platformHost) {
        platformHost.innerHTML = platformControls.length
            ? `<div class="compliance-control-grid">${platformControls.map(control => complianceControlCard(control, { showFramework: true, showRoadmap: true, showControlId: true })).join("")}</div>`
            : `<div class="empty-state compact"><span>${emptyMsg || "No platform control rows available."}</span></div>`;
        platformHost.querySelectorAll("[data-open-plan]").forEach(button => {
            button.addEventListener("click", () => switchModule("project-plan"));
        });
    }

    renderCryptoPostureCard();
}


function renderCryptoPostureCard() {
    const host = document.getElementById("cryptoPostureCard");
    if (!host) return;
    const data = (typeof cryptoUiData === "object" && cryptoUiData) ? cryptoUiData : {};
    if (!data.available) {
        host.innerHTML = `<div class="empty-state compact"><span>No cryptographic evidence yet. Run a configuration collection (PAN effective-running) to populate IKE/IPsec/TLS/certificate posture.</span></div>`;
        return;
    }
    const counts = (data.fleet && data.fleet.status_counts) || {};
    const chip = (label, key, tone) =>
        `<span class="statuspill ${tone}">${escapeHtml(label)} ${Number(counts[key] || 0)}</span>`;
    const subjects = Array.isArray(data.subjects) ? data.subjects : [];
    const notable = [];
    subjects.forEach(subject => {
        (subject.findings || []).forEach(f => {
            if (f.status === "FINDING" || f.status === "INFORMATIONAL") {
                notable.push({ subject: subject.subject_id, ...f });
            }
        });
    });
    notable.sort((a, b) => (a.status === "FINDING" ? 0 : 1) - (b.status === "FINDING" ? 0 : 1));
    const pack = data.rule_pack || {};
    const rows = notable.slice(0, 12).map(f => `
        <article class="compliance-control-card ${f.status === "FINDING" ? "danger" : "neutral"} compact">
            <div class="compliance-control-head">
                <div>
                    <div class="compliance-control-benchmark">${escapeHtml(f.category || "")}</div>
                    <div class="compliance-control-id">${escapeHtml(f.subject)} · ${escapeHtml(f.control_id || "")}</div>
                </div>
                <span class="statuspill ${f.status === "FINDING" ? "danger" : "neutral"}">${escapeHtml(f.status)}</span>
            </div>
            <p>${escapeHtml(f.summary || "")}</p>
            <p class="detail-subtitle">basis: ${escapeHtml(f.evidence_basis || "")}${(f.framework_refs && f.framework_refs.length) ? " · " + escapeHtml(f.framework_refs.join(", ")) : ""}</p>
        </article>`).join("");
    host.innerHTML = `
        <div class="detail-subtitle">Pack ${escapeHtml(pack.pack_id || "")} @ ${escapeHtml(pack.pack_version || "")} · no certification claim · ${subjects.length} subject(s)</div>
        <div class="compliance-kpi-grid compact subject-summary">
            ${chip("Findings", "FINDING", "danger")}
            ${chip("Informational", "INFORMATIONAL", "neutral")}
            ${chip("Pass", "PASS", "success")}
            ${chip("Insufficient evidence", "INSUFFICIENT_EVIDENCE", "neutral")}
        </div>
        ${rows ? `<div class="compliance-control-grid">${rows}</div>` : `<div class="empty-state compact"><span>No crypto findings — all evaluated rules pass or lack evidence.</span></div>`}
        <p class="detail-subtitle">PQC readiness: ${escapeHtml((data.pqc && data.pqc.status) || "INFORMATIONAL")} — platform capability only, not configured posture.</p>`;
}


function renderComplianceSubjectView(subject) {
    const summaryHost = document.getElementById("complianceSubjectSummary");
    const subjectHost = document.getElementById("complianceSubjectControls");
    if (!summaryHost || !subjectHost) return;

    if (!subject) {
        summaryHost.innerHTML = "";
        subjectHost.innerHTML = `<div class="empty-state compact"><span>Select an assessed device on the left to inspect control results.</span></div>`;
        return;
    }

    const counts = complianceSubjectStatusCounts(subject);
    summaryHost.innerHTML = [
        metricCard("✓ PASS", formatNumber(counts.pass), "Control areas passing", "success"),
        metricCard("! FINDING", formatNumber(counts.finding), "Requires attention", "danger"),
        metricCard("? UNKNOWN", formatNumber(counts.unknown), "Insufficient evidence", "muted"),
    ].join("");

    const assignment = subject.assignment || {};
    if (Array.isArray(assignment.assigned)) {
        const note = document.createElement("div");
        note.className = "posture-note";
        const waived = Array.isArray(assignment.waived) ? assignment.waived.length : 0;
        note.innerHTML = `<strong>Assignment:</strong> ${formatNumber(assignment.assigned.length)} control(s) in scope for this device` +
            (Array.isArray(assignment.not_assigned) && assignment.not_assigned.length ? ` · ${formatNumber(assignment.not_assigned.length)} de-scoped` : "") +
            (waived ? ` · ${formatNumber(waived)} waived` : "") + ".";
        summaryHost.appendChild(note);
    }

    const rows = complianceRenderableControls(subject.controls);
    subjectHost.innerHTML = rows.length
        ? `<div class="compliance-control-grid subject-grid">${rows.map(control => complianceControlCard(control, { showFramework: true, showRoadmap: false, showControlId: true, showTraceability: true, compact: true })).join("")}</div>`
        : `<div class="empty-state compact"><span>No control results available for this device.</span></div>`;

    const extendedHost = document.getElementById("complianceSubjectExtendedControls");
    if (extendedHost) {
        const extRows = complianceRenderableControls(subject.extended_controls);
        extendedHost.innerHTML = extRows.length
            ? `<div class="compliance-control-grid subject-grid">${extRows.map(control => complianceControlCard(control, { showFramework: true, showRoadmap: false, showControlId: true, showTraceability: true, compact: true })).join("")}</div>`
            : `<div class="empty-state compact"><span>No enrichment controls in scope for this device.</span></div>`;
    }
}


function renderComplianceContent() {
    const fleetView = document.getElementById("complianceFleetView");
    const subjectView = document.getElementById("complianceSubjectView");
    const subject = complianceUiData?.available && complianceSelectedSubjectId !== "__fleet__" ? selectedComplianceSubject() : null;

    renderComplianceHeader(subject);
    renderComplianceFrameworkFilter();

    if (fleetView) fleetView.hidden = Boolean(subject);
    if (subjectView) subjectView.hidden = !subject;

    if (subject) {
        renderComplianceSubjectView(subject);
    } else {
        renderComplianceFleetView();
    }
}


function renderComplianceModule() {
    renderComplianceSubjectList();
    renderComplianceContent();
}

