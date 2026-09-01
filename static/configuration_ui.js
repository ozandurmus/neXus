// SecurityExpert report UI — configuration_ui: Configuration module

let activeConfigTab = "overview";
let configSelectedId = "__fleet__";
let configFleetFilter = "all";
let configHeaderExpanded = null;
let configSidebarOpen = false;
// CON.1 C1-3: rawData/configUiData/etc. start empty and are populated later
// by initializeReport(payloads) (static mode: the inline JSON; console mode:
// the /api/payloads fetch) -- so a derived collection computed once here at
// module-load time would stay stuck on the empty default. rebuildConfigDevices()
// is called once below (harmless against the empty default) and again from
// initializeReport, after the real payload lands.
let configDevices = [];
function rebuildConfigDevices() {
    configDevices = Array.isArray(configUiData?.devices) ? configUiData.devices : [];
}
rebuildConfigDevices();


// Pure accessor over the page-level configUiData const. Read by this module's
// checkpointCoverageHtml and by overview_ui.renderOverviewModule (which loads
// after configuration_ui) — relocated here from the contract's overview_ui
// slot (D-MOD4) because both callers reach it and this file loads first.
function currentConfigurationFleet() {
    return configUiData?.available ? (configUiData.fleet || {}) : {};
}


function configDeviceCounts(device) {
    return device?.alignment?.counts || {};
}


function configDeviceStatusHtml(device) {
    const tone = safe(device?.tone || "neutral");
    return statusPill(device?.status_label || "Unknown", tone === "coverage" ? "muted" : tone);
}


function configFleetVendorLabel(filter = configFleetFilter) {
    if (filter === "check_point") return "Check Point";
    if (filter === "palo_alto") return "Palo Alto";
    return "All";
}


function configVendorDevices(filter = configFleetFilter) {
    if (filter === "all") return configDevices.slice();
    return configDevices.filter(device => safe(device.vendor_key) === filter);
}


function configScopedFleet() {
    const globalFleet = currentConfigurationFleet();
    if (configFleetFilter === "all") return globalFleet;
    const devices = configVendorDevices();
    const counts = key => devices.reduce((sum, device) => sum + Number(configDeviceCounts(device)[key] || 0), 0);
    const success = devices.filter(device => device.connected).length;
    const changed = devices.filter(device => safe(device.history?.actual_change_state) === "changed").length;
    const first = devices.filter(device => safe(device.history?.actual_change_state) === "first").length;
    const same = devices.filter(device => safe(device.history?.actual_change_state) === "same").length;
    const coverageGaps = devices.filter(device => !device.connected || safe(device.status_label).toLowerCase().includes("coverage")).length;
    const operationalFailures = devices.filter(device => !device.connected && safe(device.failure_family) !== "capability_gap").length;
    const capabilityGaps = devices.filter(device => safe(device.failure_family) === "capability_gap").length;
    return {
        ...globalFleet,
        selected: devices.length,
        success,
        failed: Math.max(0, devices.length - success),
        primary_evidence_success: success,
        first,
        same,
        changed,
        local_override: counts("LOCAL_OVERRIDE"),
        effective_drift: counts("EFFECTIVE_DRIFT"),
        member_specific: counts("MEMBER_SPECIFIC"),
        devices_with_local_override: devices.filter(device => Number(configDeviceCounts(device).LOCAL_OVERRIDE || 0) > 0).length,
        devices_with_effective_drift: devices.filter(device => Number(configDeviceCounts(device).EFFECTIVE_DRIFT || 0) > 0).length,
        devices_with_coverage_gaps: coverageGaps,
        method_failures: operationalFailures,
        checkpoint_capability_gaps: configFleetFilter === "check_point" ? capabilityGaps : 0,
    };
}


function renderConfigFleetFilters() {
    const host = document.getElementById("configFleetFilters");
    if (!host) return;
    const all = configDevices.length;
    const cp = configDevices.filter(device => device.vendor_key === "check_point").length;
    const pan = configDevices.filter(device => device.vendor_key === "palo_alto").length;
    host.innerHTML = [
        ["all", "All", all],
        ["check_point", "Check Point", cp],
        ["palo_alto", "Palo Alto", pan],
    ].map(([key, label, count]) => `
        <button type="button" class="config-fleet-filter ${configFleetFilter === key ? "active" : ""}" data-config-fleet-filter="${key}">
            <span>${escapeHtml(label)}</span><strong>${formatNumber(count)}</strong>
        </button>
    `).join("");
    host.querySelectorAll("[data-config-fleet-filter]").forEach(button => {
        button.addEventListener("click", () => {
            configFleetFilter = button.dataset.configFleetFilter || "all";
            configSelectedId = "__fleet__";
            renderConfigFleetFilters();
            renderConfigDeviceList();
            renderConfigSelected();
            switchConfigTab("overview");
        });
    });
}


function configDeviceItemHtml(device, {depth = 0, displayName = null} = {}) {
    const counts = configDeviceCounts(device);
    const platform = device.vendor_key === "check_point" && device.platform_label ? device.platform_label : null;
    const meta = [device.vendor, platform && platform !== "Gaia" ? platform : null, device.model, device.sw_version, device.management_ip]
        .filter(Boolean).join(" · ");
    const depthClass = depth ? ` config-device-depth-${Math.min(depth, 3)}` : "";
    return `
        <div class="config-device-item ${device.id === configSelectedId ? "active" : ""}${depthClass}" data-config-device="${escapeHtml(device.id)}">
            <div class="config-device-title-row">
                <div class="config-device-name">${escapeHtml(displayName || device.name || "Unnamed device")}</div>
                ${configDeviceStatusHtml(device)}
            </div>
            <div class="config-device-meta">${escapeHtml(meta)}</div>
            <div class="config-device-meta secondary">${device.parent_name ? `↳ ${escapeHtml(device.parent_name)} · ` : ""}SN ${escapeHtml(device.serial || "—")}</div>
            <div class="config-device-tags compact">
                ${Number(counts.LOCAL_OVERRIDE || 0) ? statusPill(`${formatNumber(counts.LOCAL_OVERRIDE)} override`, "warning") : ""}
                ${Number(counts.EFFECTIVE_DRIFT || 0) ? statusPill(`${formatNumber(counts.EFFECTIVE_DRIFT)} drift`, "danger") : ""}
                ${Number(counts.MEMBER_SPECIFIC || 0) ? statusPill(`${formatNumber(counts.MEMBER_SPECIFIC)} member-specific`, "info") : ""}
                ${device.failure_family === "capability_gap" ? statusPill("platform capability", "muted") : ""}
                ${Number(counts.PROVENANCE_UNVERIFIED || 0) ? statusPill(`${formatNumber(counts.PROVENANCE_UNVERIFIED)} provenance gap`, "muted") : ""}
            </div>
        </div>
    `;
}


function configDeviceHierarchyHtml(devices) {
    const consumed = new Set();
    const chunks = [];
    const vsxGroups = new Map();
    devices.forEach(device => {
        if (device.vendor_key !== "check_point") return;
        if (!["vsx_host", "virtual_system"].includes(device.entity_type)) return;
        const key = safe(device.cluster_group_id) || safe(device.presentation_group_id) || (device.entity_type === "vsx_host" ? `host:${device.id}` : "");
        if (!key) return;
        if (!vsxGroups.has(key)) vsxGroups.set(key, []);
        vsxGroups.get(key).push(device);
    });

    for (const [groupKey, groupDevices] of vsxGroups.entries()) {
        const hosts = groupDevices.filter(device => device.entity_type === "vsx_host");
        const contexts = groupDevices.filter(device => device.entity_type === "virtual_system");
        // A single physical host without shared cluster identity is left in the
        // normal list; grouping is valuable only when there is a VS hierarchy.
        if (!contexts.length) continue;
        const authoritativeLabel = groupDevices.find(device => device.cluster_display_name)?.cluster_display_name;
        const presentationLabel = groupDevices.find(device => device.presentation_group_label)?.presentation_group_label;
        const label = authoritativeLabel || presentationLabel || hosts[0]?.parent_name || hosts[0]?.name || "VSX";
        const groupKind = authoritativeLabel ? "VSX Cluster" : (presentationLabel ? "VSX Pair" : "VSX Host");
        const logicalVsCount = new Set(contexts.map(ctx => `${safe(ctx.vs_id)}|${safe(ctx.name)}`)).size;
        const roleCounts = hosts.reduce((acc, host) => {
            const role = safe(host.ha_role).toUpperCase();
            if (role) acc[role] = (acc[role] || 0) + 1;
            return acc;
        }, {});
        const roleSummary = Object.entries(roleCounts).map(([role, count]) => `${role} ${count}`).join(" · ");
        const aggregateMeta = [
            `${hosts.length} member${hosts.length === 1 ? "" : "s"}`,
            `${logicalVsCount} virtual system${logicalVsCount === 1 ? "" : "s"}`,
            roleSummary || null
        ].filter(Boolean).join(" · ");
        chunks.push(`
            <div class="config-tree-group">
                <div class="config-tree-group-header"><span>${escapeHtml(groupKind)}</span><strong>${escapeHtml(label)}</strong><small>${escapeHtml(aggregateMeta)}</small></div>
                ${hosts.length ? `<div class="config-tree-section-label">Members</div>${hosts.map(host => {
                    consumed.add(host.id);
                    return configDeviceItemHtml(host, {depth: 1});
                }).join("")}` : ""}
                ${contexts.length ? `<div class="config-tree-section-label">Virtual Systems</div>` : ""}
                ${(() => {
                    const logical = new Map();
                    contexts.forEach(ctx => {
                        const logicalKey = `${safe(ctx.vs_id)}|${safe(ctx.name)}`;
                        if (!logical.has(logicalKey)) logical.set(logicalKey, []);
                        logical.get(logicalKey).push(ctx);
                    });
                    return [...logical.values()].sort((a, b) => safe(a[0]?.name).localeCompare(safe(b[0]?.name))).map(items => {
                        const logicalName = items[0]?.name || `VSID ${items[0]?.vs_id || "—"}`;
                        items.forEach(item => consumed.add(item.id));
                        return `
                            <div class="config-logical-vs">
                                <div class="config-logical-vs-header"><strong>${escapeHtml(logicalName)}</strong><span>${formatNumber(items.length)} member view${items.length === 1 ? "" : "s"}</span></div>
                                ${items.sort((a, b) => safe(a.device_name).localeCompare(safe(b.device_name))).map(item => configDeviceItemHtml(item, {
                                    depth: 2,
                                    displayName: `${item.device_name || "Member"} · VSID ${item.vs_id || "—"}`
                                })).join("")}
                            </div>
                        `;
                    }).join("");
                })()}
            </div>
        `);
    }

    devices.filter(device => !consumed.has(device.id)).forEach(device => {
        const depth = device.entity_type === "clusterxl_member" || device.entity_type === "virtual_system" ? 1 : 0;
        chunks.push(configDeviceItemHtml(device, {depth}));
    });
    return chunks.join("");
}


function renderConfigDeviceList() {
    const list = document.getElementById("configDeviceList");
    if (!list) return;
    const fleet = configScopedFleet();
    const query = safe(document.getElementById("configSearch")?.value).trim().toLowerCase();

    renderConfigFleetFilters();
    if (!configUiData?.available) {
        list.innerHTML = `<div class="empty-list">Configuration evidence is not available in this export.</div>`;
        return;
    }

    const devices = configVendorDevices().filter(device => {
        if (!query) return true;
        return [device.name, device.device_name, device.parent_name, device.cluster_display_name, device.serial, device.management_ip, device.model, device.platform_label, device.entity_type]
            .join(" ")
            .toLowerCase()
            .includes(query);
    });

    const fleetLabel = `${configFleetVendorLabel()} Fleet`;
    const fleetItem = `
        <div class="config-device-item fleet ${configSelectedId === "__fleet__" ? "active" : ""}" data-config-device="__fleet__">
            <div class="config-device-title-row">
                <div>
                    <div class="config-device-name">${escapeHtml(fleetLabel)}</div>
                    <div class="config-device-meta">${formatNumber(fleet.primary_evidence_success)} / ${formatNumber(fleet.selected)} current configuration entities</div>
                </div>
                <span class="config-device-count">${formatNumber(fleet.selected)}</span>
            </div>
            <div class="config-device-tags">
                ${Number(fleet.local_override || 0) ? statusPill(`${formatNumber(fleet.local_override)} overrides`, "warning") : ""}
                ${Number(fleet.effective_drift || 0) ? statusPill(`${formatNumber(fleet.effective_drift)} drift`, "danger") : ""}
                ${Number(fleet.checkpoint_capability_gaps || 0) ? statusPill(`${formatNumber(fleet.checkpoint_capability_gaps)} capability gaps`, "muted") : ""}
                ${Number(fleet.method_failures || 0) ? statusPill(`${formatNumber(fleet.method_failures)} ${configFleetFilter === "check_point" ? "operational unavailable" : "method / operational gaps"}`, "danger") : ""}
            </div>
        </div>
    `;

    list.innerHTML = fleetItem + configDeviceHierarchyHtml(devices);
    list.querySelectorAll("[data-config-device]").forEach(item => {
        item.addEventListener("click", () => {
            configSelectedId = item.dataset.configDevice;
            renderConfigDeviceList();
            renderConfigSelected();
            switchConfigTab(configSelectedId === "__fleet__" ? "overview" : "current");
            setConfigSidebarOpen(false);
        });
    });
}


function selectedConfigDevice() {
    return configDevices.find(device => safe(device.id) === safe(configSelectedId)) || null;
}


function panoramaSyncLabel(value) {
    const normalized = safe(value).toLowerCase();
    if (["in sync", "in_sync", "insync", "synchronized", "sync", "yes", "ok"].includes(normalized)) return "IN SYNC";
    if (["out of sync", "out_of_sync", "outofsync", "no"].includes(normalized)) return "OUT OF SYNC";
    return normalized ? normalized.toUpperCase() : "UNKNOWN";
}


function ensureConfigHeaderPreference() {
    if (configHeaderExpanded !== null) return;
    try {
        const saved = localStorage.getItem("securityexpert-config-header-expanded");
        if (saved === "true" || saved === "false") {
            configHeaderExpanded = saved === "true";
            return;
        }
    } catch (error) {
        // Standalone exports can restrict localStorage.
    }
    configHeaderExpanded = !window.matchMedia?.("(max-width: 1100px)")?.matches;
}


function setConfigHeaderExpanded(expanded) {
    configHeaderExpanded = Boolean(expanded);
    try {
        localStorage.setItem("securityexpert-config-header-expanded", String(configHeaderExpanded));
    } catch (error) {
        // Optional preference only.
    }
    renderConfigHeader(selectedConfigDevice());
}


function setConfigSidebarOpen(open) {
    configSidebarOpen = Boolean(open);
    document.querySelector(".config-workspace")?.classList.toggle("sidebar-open", configSidebarOpen);
    const toggle = document.getElementById("configSidebarToggle");
    if (toggle) toggle.setAttribute("aria-expanded", String(configSidebarOpen));
}


function renderConfigHeader(device) {
    ensureConfigHeaderPreference();
    const fleet = configScopedFleet();
    const title = document.getElementById("configDetailTitle");
    const subtitle = document.getElementById("configDetailSubtitle");
    const eyebrow = document.getElementById("configDetailEyebrow");
    const status = document.getElementById("configDetailStatus");
    const facts = document.getElementById("configHeaderFacts");
    const compact = document.getElementById("configHeaderCompact");
    const toggle = document.getElementById("configHeaderToggle");
    const header = document.getElementById("configDetailHeader");

    if (!configUiData?.available) {
        if (title) title.textContent = "Configuration unavailable";
        if (subtitle) subtitle.textContent = "This HTML export does not contain a configuration observation cycle.";
        if (status) status.innerHTML = statusPill("Not collected", "muted");
        if (facts) facts.innerHTML = "";
        if (compact) compact.hidden = true;
        if (toggle) toggle.hidden = true;
        return;
    }

    if (!device) {
        if (eyebrow) eyebrow.textContent = `Configuration · ${configFleetVendorLabel()} estate`;
        if (title) title.textContent = `${configFleetVendorLabel()} Fleet`;
        if (subtitle) subtitle.textContent = `${formatNumber(fleet.selected)} configuration entities · current-state first, alignment separate`;
        if (facts) facts.innerHTML = "";
        if (compact) compact.hidden = true;
        if (toggle) toggle.hidden = true;
        if (header) header.classList.remove("compact");
        if (status) {
            status.innerHTML = [
                statusPill(`${formatNumber(fleet.primary_evidence_success)}/${formatNumber(fleet.selected)} current`, fleet.primary_evidence_success === fleet.selected ? "success" : "warning"),
                Number(fleet.local_override || 0) ? statusPill(`${formatNumber(fleet.local_override)} local overrides`, "warning") : "",
                Number(fleet.effective_drift || 0) ? statusPill(`${formatNumber(fleet.effective_drift)} drift`, "danger") : "",
                Number(fleet.checkpoint_capability_gaps || 0) ? statusPill(`${formatNumber(fleet.checkpoint_capability_gaps)} capability gaps`, "muted") : "",
                configFleetFilter === "check_point" && Number(fleet.checkpoint_operational_failures || 0) ? statusPill(`${formatNumber(fleet.checkpoint_operational_failures)} operational unavailable`, "danger") : "",
            ].join("");
        }
        return;
    }

    if (toggle) {
        toggle.hidden = false;
        toggle.textContent = configHeaderExpanded ? "Collapse details" : "Expand details";
        toggle.setAttribute("aria-expanded", String(configHeaderExpanded));
    }
    if (header) header.classList.toggle("compact", !configHeaderExpanded);
    if (eyebrow) eyebrow.textContent = `Configuration · ${device.vendor || "Managed device"}`;
    if (title) title.textContent = device.name || "Managed device";
    if (subtitle) subtitle.textContent = `Collected ${formatConfigTimestamp(device.completed_at)} · Primary source: ${device.current_configuration?.source_plane || "current evidence"}`;

    const platformSummary = device.vendor_key === "check_point" ? device.platform_label : null;
    if (compact) {
        const compactFacts = [device.vendor, platformSummary && platformSummary !== "Gaia" ? platformSummary : null, device.model, device.sw_version, device.management_ip, device.ha_role]
            .filter(Boolean);
        compact.innerHTML = compactFacts.map(value => `<span>${escapeHtml(value)}</span>`).join("");
        compact.hidden = configHeaderExpanded;
    }
    if (facts) {
        const headerFacts = [
            ["Vendor", device.vendor || "—"],
            ["Model", device.model || "—"],
            ["Software", device.sw_version || "—"],
            ["Management IP", device.management_ip || "—"],
            ["Serial Number", device.serial || "—"],
            ["HA / Role", device.ha_role || "—"],
            ["VSYS / VS", formatNumber(device.vsys_count || 0)],
            [device.policy_scope_label || "Policy scope", device.policy_scope || "—"],
            ["Config freshness", formatConfigTimestamp(device.completed_at)],
            ["Current source", device.current_configuration?.source_plane || "—"]
        ];
        facts.innerHTML = headerFacts.map(([label, value]) => `
            <div class="config-header-fact"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>
        `).join("");
        facts.hidden = !configHeaderExpanded;
    }
    if (status) status.innerHTML = configDeviceStatusHtml(device);
}


function assignmentChips(device) {
    const assignment = device?.assignment || {};
    const stack = assignment.primary_template_stack;
    const deviceGroups = Array.isArray(assignment.device_groups) ? assignment.device_groups : [];
    return `
        <div class="assignment-grid">
            <div class="assignment-block">
                <span class="assignment-label">Template Stack</span>
                <strong>${escapeHtml(stack || "Not mapped")}</strong>
                <small>${escapeHtml(assignment.status || "unknown")}</small>
            </div>
            <div class="assignment-block">
                <span class="assignment-label">Device Group scopes</span>
                <strong>${formatNumber(assignment.policy_scope_count || deviceGroups.length)}</strong>
                <small>${assignment.policy_lineage_complete ? "Lineage complete" : "Lineage incomplete"}</small>
            </div>
            <div class="assignment-block">
                <span class="assignment-label">Template variables unresolved</span>
                <strong>${formatNumber(assignment.unresolved_variable_settings)}</strong>
                <small>${assignment.unresolved_variable_settings ? "Held out of drift claims" : "No unresolved variable settings"}</small>
            </div>
        </div>
    `;
}


function checkpointCoverageHtml(fleet) {
    if (!Number(fleet?.checkpoint_selected || 0) || configFleetFilter === "palo_alto") return "";
    const platforms = fleet.checkpoint_platform_counts || {};
    const gaia = platforms.gaia || {};
    const embedded = platforms.gaia_embedded || {};
    const unknown = platforms.unknown || {};
    const entity = fleet.checkpoint_entity_type_counts || {};
    const reasons = Object.entries(fleet.checkpoint_failure_reason_counts || {}).sort((a, b) => Number(b[1]) - Number(a[1]));
    const reasonRows = reasons.length
        ? reasons.map(([reason, count]) => `<div><span>${escapeHtml(reason.replaceAll("_", " "))}</span><strong>${formatNumber(count)}</strong></div>`).join("")
        : `<div><span>Failure reasons</span><strong>none</strong></div>`;
    const entityLabel = key => ({
        standalone_gateway: "Standalone",
        clusterxl_member: "ClusterXL members",
        vsx_host: "VSX hosts",
        virtual_system: "VSX virtual systems",
    }[key] || key);
    return `
        <section class="config-section-card span-2 checkpoint-coverage-card">
            <div class="section-heading">
                <div><div class="eyebrow">Check Point coverage</div><h2>Platform-aware collection diagnostics</h2></div>
                <div class="section-note">Unsupported capability ≠ transport failure</div>
            </div>
            <div class="config-kpi-grid compact-kpis">
                ${metricCard("Observed / planned", `${formatNumber(fleet.checkpoint_selected)} / ${formatNumber(fleet.checkpoint_planned_entities || fleet.checkpoint_selected)}`, `${formatNumber(fleet.checkpoint_unmaterialized_entities || 0)} planned context(s) not materialized`, fleet.checkpoint_unmaterialized_entities ? "warning" : "success")}
                ${metricCard("Operational failures", formatNumber(fleet.checkpoint_operational_failures), "SSH/auth/identity/command/context failures", fleet.checkpoint_operational_failures ? "danger" : "success")}
                ${metricCard("Capability gaps", formatNumber(fleet.checkpoint_capability_gaps), "Platform command capability held separate", fleet.checkpoint_capability_gaps ? "neutral" : "success")}
                ${metricCard("Mgmt-reported down", formatNumber(fleet.checkpoint_management_reported_down_hosts || 0), "Physical hosts already reported down by management", fleet.checkpoint_management_reported_down_hosts ? "neutral" : "success")}
                ${metricCard("Quantum Spark / Embedded", `${formatNumber(fleet.checkpoint_gaia_embedded_success)} / ${formatNumber(fleet.checkpoint_gaia_embedded_entities)}`, "Current config entities classified from direct evidence", embedded.unavailable ? "warning" : "success")}
                ${metricCard("Model / Serial / HA", `${formatNumber(fleet.checkpoint_model_covered)} / ${formatNumber(fleet.checkpoint_serial_covered)} / ${formatNumber(fleet.checkpoint_ha_role_covered)}`, "Physical metadata coverage counts", "info")}
            </div>
            <div class="checkpoint-coverage-grid">
                <div>
                    <div class="eyebrow">Platform family</div>
                    <div class="summary-list">
                        <div><span>Gaia</span><strong>${formatNumber(gaia.success)} / ${formatNumber(gaia.selected)}</strong></div>
                        <div><span>Quantum Spark / Gaia Embedded</span><strong>${formatNumber(embedded.success)} / ${formatNumber(embedded.selected)}</strong></div>
                        <div><span>Unclassified</span><strong>${formatNumber(unknown.success)} / ${formatNumber(unknown.selected)}</strong></div>
                    </div>
                </div>
                <div>
                    <div class="eyebrow">Entity coverage</div>
                    <div class="summary-list">
                        ${Object.entries(entity).map(([key, row]) => `<div><span>${escapeHtml(entityLabel(key))}</span><strong>${formatNumber(row?.success)} / ${formatNumber(row?.selected)}</strong></div>`).join("")}
                    </div>
                </div>
                <div>
                    <div class="eyebrow">Unavailable reason breakdown</div>
                    <div class="summary-list">${reasonRows}</div>
                </div>
            </div>
        </section>
    `;
}


function renderConfigOverviewPanel(device) {
    const panel = document.getElementById("configOverviewPanel");
    if (!panel) return;
    const fleet = configScopedFleet();

    if (!configUiData?.available) {
        panel.innerHTML = `<div class="empty-state large"><strong>Configuration evidence not available</strong><span>Run <code>py.exe .\\main.py</code> to generate inventory and configuration in one observation cycle.</span></div>`;
        return;
    }

    if (!device) {
        panel.innerHTML = `
            <div class="config-kpi-grid">
                ${metricCard("Current configurations", `${formatNumber(fleet.primary_evidence_success)} / ${formatNumber(fleet.selected)}`, "Current primary device configuration", fleet.primary_evidence_success === fleet.selected ? "success" : "warning")}
                ${metricCard("Devices with overrides", formatNumber(fleet.devices_with_local_override), `${formatNumber(fleet.local_override)} settings`, fleet.devices_with_local_override ? "warning" : "success")}
                ${metricCard("Devices with drift", formatNumber(fleet.devices_with_effective_drift), `${formatNumber(fleet.effective_drift)} settings`, fleet.devices_with_effective_drift ? "danger" : "success")}
                ${metricCard("Coverage gaps", formatNumber(fleet.devices_with_coverage_gaps), "Evidence or representation gaps", fleet.devices_with_coverage_gaps ? "neutral" : "success")}
                ${metricCard("Changed this cycle", formatNumber(fleet.changed), "Effective configuration snapshots", fleet.changed ? "warning" : "success")}
                ${metricCard("Native backup", "NOT CONFIGURED", "Target phase 0.6.0B", "muted")}
            </div>

            <div class="config-section-grid">
                <section class="config-section-card span-2">
                    <div class="section-heading"><div><div class="eyebrow">Module contract</div><h2>Configuration means current actual device state</h2></div></div>
                    <div class="contract-grid vendor-neutral-contract">
                        <div>${statusPill("CONFIGURATION", "success")}<p>What is configured on the device now: system, management, DNS/NTP, HA and later broader device/network configuration.</p></div>
                        <div>${statusPill("ALIGNMENT", "warning")}<p>Expected versus current reconciliation is a separate view. Local override and drift findings live there.</p></div>
                        <div>${statusPill("POLICY & OBJECTS", "info")}<p>Security policy, NAT and object analysis are a separate management plane and expand later for PAN, Check Point and VSX.</p></div>
                    </div>
                </section>
                <section class="config-section-card">
                    <div class="section-heading"><div><div class="eyebrow">Vendor coverage</div><h2>Current adapters</h2></div></div>
                    <div class="summary-list">
                        <div><span>Palo Alto Networks</span><strong>${formatNumber(fleet.vendor_counts?.palo_alto?.success || fleet.pan_success)} / ${formatNumber(fleet.vendor_counts?.palo_alto?.selected || fleet.pan_selected)} current</strong></div>
                        <div><span>Check Point Gaia</span><strong>${formatNumber(fleet.vendor_counts?.check_point?.success || fleet.checkpoint_success)} / ${formatNumber(fleet.vendor_counts?.check_point?.selected || fleet.checkpoint_selected)} current</strong></div>
                        <div><span>Check Point SSH trust</span><strong>${escapeHtml(fleet.checkpoint_host_key_policy || "not collected")}</strong></div>
                    </div>
                </section>
            </div>
            ${checkpointCoverageHtml(fleet)}
        `;
        return;
    }

    const counts = configDeviceCounts(device);
    const current = device.current_configuration || {};
    panel.innerHTML = `
        <div class="config-kpi-grid">
            ${metricCard("Current configuration", current.status === "available" ? "AVAILABLE" : "UNAVAILABLE", current.status === "available" ? "Structured from primary effective evidence" : "Primary structured view unavailable", current.status === "available" ? "success" : "danger")}
            ${metricCard("Last collected", formatConfigTimestamp(device.completed_at), "Same observation cycle", "neutral")}
            ${metricCard("Change state", safe(device.history?.effective_change_state || "unknown").toUpperCase(), "Effective configuration snapshot", safe(device.history?.effective_change_state).toUpperCase() === "CHANGED" ? "warning" : "success")}
            ${metricCard("Local overrides", formatNumber(counts.LOCAL_OVERRIDE), "See Alignment for expected ↔ current", counts.LOCAL_OVERRIDE ? "warning" : "success")}
            ${metricCard("Effective drift", formatNumber(counts.EFFECTIVE_DRIFT), "Unexplained effective difference", counts.EFFECTIVE_DRIFT ? "danger" : "success")}
            ${metricCard("Configured VSYS / VS", formatNumber(device.vsys_count || 0), "Current effective configuration", "info")}
        </div>
        <div class="config-section-grid">
            <section class="config-section-card span-2">
                <div class="section-heading"><div><div class="eyebrow">Quick orientation</div><h2>Where to look next</h2></div></div>
                <div class="contract-grid">
                    <div>${statusPill("CURRENT", "success")}<p><strong>Configuration</strong> shows actual values with vendor-neutral LOCAL / MEMBER / override/provenance semantics.</p></div>
                    <div>${statusPill("VERIFY", "warning")}<p><strong>Alignment</strong> explains centralized intent, local override, drift and evidence coverage.</p></div>
                    <div>${statusPill("TRACE", "info")}<p><strong>History</strong> tracks change state. Full time selection and diff remain the next history increment.</p></div>
                </div>
            </section>
        </div>
    `;
}


function currentOriginLabel(origin, vendorKey = "") {
    const value = safe(origin).toLowerCase();
    if (value === "central") return safe(vendorKey) === "check_point" ? "MANAGEMENT" : "PAN";
    if (value === "management") return "MANAGEMENT";
    if (value === "local_override") return "OVERRIDE";
    if (value === "local") return "LOCAL";
    if (value === "member_specific") return "MEMBER";
    if (value === "effective") return "EFFECTIVE";
    return "UNKNOWN";
}


function currentOriginTone(origin) {
    const value = safe(origin).toLowerCase();
    if (value === "central") return "success";
    if (value === "local_override") return "warning";
    if (value === "member_specific") return "info";
    if (value === "local") return "neutral";
    return "muted";
}


function renderCurrentHighlights(current, device) {
    const highlights = Array.isArray(current?.highlights) ? current.highlights : [];
    if (!highlights.length) return "";
    return `
        <section class="basic-config-block">
            <div class="section-heading basic-config-heading">
                <div><div class="eyebrow">Basic configuration</div><h3>Operator snapshot</h3></div>
                <div class="section-note">Selected current values</div>
            </div>
            <div class="current-config-highlights">
                ${highlights.map(item => `
                    <div class="config-highlight-card">
                        <div class="config-highlight-topline">
                            <span>${escapeHtml(item.section_label || "Configuration")}</span>
                            ${statusPill(currentOriginLabel(item.origin, device?.vendor_key), currentOriginTone(item.origin))}
                        </div>
                        <strong>${escapeHtml(item.label || "Setting")}</strong>
                        <code>${escapeHtml(item.value ?? "—")}</code>
                        ${item.context ? `<small>${escapeHtml(item.context)}</small>` : ""}
                    </div>
                `).join("")}
            </div>
        </section>
    `;
}


function filteredCurrentSections(device) {
    const sections = Array.isArray(device?.current_configuration?.sections)
        ? device.current_configuration.sections
        : [];
    const query = safe(document.getElementById("configCurrentSearch")?.value).trim().toLowerCase();
    if (!query) return sections;
    return sections.map(section => ({
        ...section,
        settings: (section.settings || []).filter(row => [
            row.setting, row.value, row.context, row.origin, row.alignment_classification
        ].join(" ").toLowerCase().includes(query))
    })).filter(section => section.settings.length);
}


function renderConfigCurrentPanel(device) {
    const body = document.getElementById("configCurrentBody");
    if (!body) return;
    if (!configUiData?.available) {
        body.innerHTML = `<div class="empty-state large"><span>Current configuration is not available.</span></div>`;
        return;
    }
    if (!device) {
        body.innerHTML = `<div class="empty-state large"><strong>Select a device</strong><span>Configuration is a per-device current-state view. Fleet posture stays in Overview; expected-versus-current findings stay in Alignment.</span></div>`;
        return;
    }

    const current = device.current_configuration || {};
    if (current.status !== "available") {
        body.innerHTML = `<div class="empty-state large"><strong>Structured current configuration unavailable</strong><span>${escapeHtml(current.reason || "Primary effective configuration could not be projected.")}</span></div>`;
        return;
    }

    const sections = filteredCurrentSections(device);
    body.innerHTML = `
        <div class="current-config-intro">
            <div>
                ${statusPill("CURRENT ACTUAL", "success")}
                <strong>${formatNumber(current.setting_count)} projected settings</strong>
                <span>Source plane: ${escapeHtml(current.source_plane || "effective-running")}</span>
            </div>
            <div class="current-config-legend">
                ${device.vendor_key === "palo_alto" ? statusPill("PAN", "success") : ""}
                ${statusPill("LOCAL", "neutral")}
                ${device.vendor_key === "palo_alto" ? statusPill("OVERRIDE", "warning") : ""}
                ${statusPill("MEMBER", "info")}
            </div>
        </div>
        <div class="inline-message neutral">This local operator view shows selected non-secret current values from ${escapeHtml(current.source_plane || "current evidence")}. Raw configuration blobs and secret-bearing settings are not embedded in the HTML. ${formatNumber(current.redacted_secret_setting_count || 0)} secret-bearing setting(s) were withheld. ${device.vendor_key === "check_point" ? "Check Point expected-versus-actual alignment is intentionally deferred; ClusterXL member-specific differences shown here are intra-cluster current-state semantics." : "Alignment details remain a separate tab."}</div>
        ${renderCurrentHighlights(current, device)}
        <div class="current-config-sections">
            ${sections.length ? sections.map(section => `
                <section class="current-config-section">
                    <div class="section-heading">
                        <div><div class="eyebrow">Current configuration</div><h3>${escapeHtml(section.label)}</h3></div>
                        <div class="section-note">${formatNumber(section.settings?.length || 0)} visible</div>
                    </div>
                    <div class="current-config-table-wrap">
                        <table class="current-config-table">
                            <thead><tr><th>Setting</th><th>Current value</th><th>Origin</th><th>Context</th></tr></thead>
                            <tbody>
                                ${(section.settings || []).map(row => `
                                    <tr>
                                        <td><strong>${escapeHtml(row.setting || "Setting")}</strong>${row.member_specific ? `<small>Expected member-specific value</small>` : ""}</td>
                                        <td><code class="current-config-value">${escapeHtml(row.value ?? "—")}</code></td>
                                        <td>${statusPill(currentOriginLabel(row.origin, device?.vendor_key), currentOriginTone(row.origin))}</td>
                                        <td>${escapeHtml(row.context || "—")}</td>
                                    </tr>
                                `).join("")}
                            </tbody>
                        </table>
                    </div>
                </section>
            `).join("") : `<div class="empty-state compact"><span>No current settings match this filter.</span></div>`}
        </div>
        <section class="config-section-card native-config-backlog">
            <div class="section-heading"><div><div class="eyebrow">Native view</div><h3>Vendor-native configuration</h3></div><div class="section-note">Backlog</div></div>
            <p>A secret-aware native view (PAN effective XML, Check Point <code>show configuration</code>, VSX host/VS context) is part of the Configuration contract but is intentionally deferred until authorization/redaction behavior is hardened.</p>
        </section>
    `;
}


function renderCategoryMatrix(rawCategoryCounts) {
    const rows = Object.entries(rawCategoryCounts || {}).map(([category, counts]) => ({
        category,
        label: categoryLabel(category),
        counts: counts || {}
    }));
    return renderCategoryRows(rows);
}


function renderCategoryRows(rows) {
    if (!Array.isArray(rows) || !rows.length) {
        return `<div class="empty-state compact"><span>No category telemetry available.</span></div>`;
    }
    return `
        <div class="category-table-wrap">
            <table class="category-table">
                <thead><tr><th>Category</th><th>Aligned</th><th>Override</th><th>Drift</th><th>Member diff</th><th>Provenance gap</th><th>Expected only</th><th>Local only</th><th>Unknown</th></tr></thead>
                <tbody>
                ${rows.map(row => {
                    const counts = row.counts || {};
                    return `<tr>
                        <td><strong>${escapeHtml(row.label || categoryLabel(row.category))}</strong></td>
                        <td class="count-success">${formatNumber(counts.ALIGNED)}</td>
                        <td class="count-warning">${formatNumber(counts.LOCAL_OVERRIDE)}</td>
                        <td class="count-danger">${formatNumber(counts.EFFECTIVE_DRIFT)}</td>
                        <td class="count-info">${formatNumber(counts.MEMBER_SPECIFIC)}</td>
                        <td>${formatNumber(counts.PROVENANCE_UNVERIFIED)}</td>
                        <td>${formatNumber(counts.EXPECTED_ONLY)}</td>
                        <td>${formatNumber(counts.LOCAL_ONLY)}</td>
                        <td>${formatNumber(counts.UNKNOWN)}</td>
                    </tr>`;
                }).join("")}
                </tbody>
            </table>
        </div>
    `;
}


function humanSettingLabel(path) {
    const text = safe(path);
    if (!text) return "Setting path unavailable";
    const cleaned = text
        .replace("/config/devices/entry[@name='__DEVICE__']/", "")
        .replaceAll("/entry[@name='", " / ")
        .replaceAll("']", "")
        .replaceAll("/", " › ")
        .replaceAll("-", " ");
    const parts = cleaned.split(" › ").filter(Boolean);
    const tail = parts.slice(-6).map(part => part.trim()).filter(Boolean);
    return tail.join(" › ");
}


function findingTone(classification) {
    return statusTone(classification);
}


function allConfigFindings(device) {
    if (device) return Array.isArray(device.alignment?.findings) ? device.alignment.findings : [];
    return configDevices.flatMap(item => (item.alignment?.findings || []).map(finding => ({
        ...finding,
        device_name: item.name,
        device_id: item.id
    })));
}


function filteredConfigFindings(device) {
    const classification = safe(document.getElementById("configClassificationFilter")?.value);
    const query = safe(document.getElementById("configAlignmentSearch")?.value).trim().toLowerCase();
    return allConfigFindings(device).filter(finding => {
        if (classification && finding.classification !== classification) return false;
        if (!query) return true;
        return [
            finding.setting,
            finding.expected_source_name,
            finding.expected_source_kind,
            finding.category,
            finding.classification,
            finding.device_name
        ].join(" ").toLowerCase().includes(query);
    });
}


function renderFindingsTable(findings, fleetMode) {
    if (!findings.length) {
        return `<div class="empty-state compact"><strong>No operator-facing findings match this filter.</strong><span>Coverage-only EXPECTED_ONLY/LOCAL_ONLY rows stay aggregated to avoid flooding the console.</span></div>`;
    }
    return `
        <div class="findings-list">
            ${findings.map(finding => `
                <article class="finding-card ${escapeHtml(findingTone(finding.classification))}">
                    <div class="finding-status-column">
                        ${statusPill(finding.classification_label || classificationLabel(finding.classification), findingTone(finding.classification))}
                        <span class="finding-confidence">${escapeHtml(finding.confidence ? `Confidence: ${finding.confidence}` : "")}</span>
                    </div>
                    <div class="finding-main">
                        <div class="finding-title">${escapeHtml(humanSettingLabel(finding.setting))}</div>
                        <div class="finding-meta">
                            ${fleetMode && finding.device_name ? `<span>Device: <strong>${escapeHtml(finding.device_name)}</strong></span>` : ""}
                            <span>Category: <strong>${escapeHtml(finding.category_label || categoryLabel(finding.category))}</strong></span>
                            ${finding.expected_source_name ? `<span>Expected source: <strong>${escapeHtml(finding.expected_source_name)}</strong></span>` : ""}
                            ${finding.expected_source_kind ? `<span>Source type: <strong>${escapeHtml(finding.expected_source_kind)}</strong></span>` : ""}
                        </div>
                        <div class="finding-reason">${escapeHtml(finding.reason || "Evidence classified by the semantic alignment engine.")}</div>
                        ${finding.setting ? `<code class="setting-path" title="${escapeHtml(finding.setting)}">${escapeHtml(finding.setting)}</code>` : ""}
                    </div>
                </article>
            `).join("")}
        </div>
    `;
}


function renderConfigAlignmentPanel(device) {
    const body = document.getElementById("configAlignmentBody");
    if (!body) return;
    if (!configUiData?.available) {
        body.innerHTML = `<div class="empty-state large"><span>Configuration alignment is not available.</span></div>`;
        return;
    }
    if (device?.vendor_key === "check_point") {
        const alignment = device.alignment || {};
        const counts = alignment.counts || {};
        const findings = filteredConfigFindings(device);
        const state = alignment.device_status || "INSUFFICIENT_EVIDENCE";
        body.innerHTML = `
            <section class="config-section-card">
                <div class="section-heading">
                    <div><div class="eyebrow">Check Point alignment</div><h3>Management intent ↔ direct actual</h3></div>
                    <div class="section-note">${statusPill(classificationLabel(state), statusTone(state))}</div>
                </div>
                <div class="inline-message ${alignment.available ? "info" : "neutral"}">${escapeHtml(alignment.message || "Alignment evidence is unavailable.")}</div>
                ${alignment.peer_evidence_incomplete ? `<div class="inline-message neutral">Peer evidence is incomplete. The available member remains independently evaluated; member agreement is not inferred.</div>` : ""}
                <div class="config-metric-grid compact">
                    ${metricCard("Aligned", formatNumber(counts.ALIGNED), "Trusted comparable matches", "success")}
                    ${metricCard("Difference observed", formatNumber(counts.DIFFERENCE_OBSERVED), "Not an EFFECTIVE_DRIFT claim", "warning")}
                    ${metricCard("Member-specific", formatNumber(counts.MEMBER_SPECIFIC), "Semantic exclusion", "info")}
                    ${metricCard("Coverage gaps", formatNumber(Number(counts.EXPECTED_ONLY || 0) + Number(counts.ACTUAL_ONLY || 0) + Number(counts.UNKNOWN || 0)), "Expected/actual/identity gaps", "neutral")}
                </div>
            </section>
            <section class="config-section-card alignment-findings-section">
                <div class="section-heading">
                    <div><div class="eyebrow">Bounded semantic evidence</div><h3>Differences & exclusions</h3></div>
                    <div class="section-note">${formatNumber(findings.length)} visible rows</div>
                </div>
                ${renderFindingsTable(findings, false)}
            </section>`;
        return;
    }
    const fleet = configScopedFleet();
    const categorySection = device
        ? renderCategoryRows(device.alignment?.categories || [])
        : renderCategoryMatrix(fleet.category_counts || {});
    const findings = filteredConfigFindings(device);
    body.innerHTML = `
        ${device ? `
        <section class="config-section-card alignment-source-section">
            <div class="section-heading">
                <div><div class="eyebrow">Central intent</div><h3>PAN management assignment</h3></div>
                <div class="section-note">Alignment-only context</div>
            </div>
            ${assignmentChips(device)}
        </section>` : ""}
        <section class="config-section-card alignment-category-section">
            <div class="section-heading">
                <div><div class="eyebrow">Coverage by domain</div><h3>Semantic categories</h3></div>
                <div class="section-note">EXPECTED_ONLY and LOCAL_ONLY are coverage states, not drift.</div>
            </div>
            ${categorySection}
        </section>
        <section class="config-section-card alignment-findings-section">
            <div class="section-heading">
                <div><div class="eyebrow">Operator-facing evidence</div><h3>Findings & semantic exclusions</h3></div>
                <div class="section-note">${formatNumber(findings.length)} visible rows</div>
            </div>
            ${renderFindingsTable(findings, !device)}
        </section>
    `;
}


function renderConfigPolicyPanel(device) {
    const panel = document.getElementById("configPolicyPanel");
    if (!panel) return;
    const scope = device?.policy_scope;
    panel.innerHTML = `
        <div class="policy-placeholder">
            <div class="eyebrow">Separate management plane</div>
            <h2>Policy &amp; Objects</h2>
            <p>Security policy, NAT, objects, groups and future policy analysis are intentionally separate from device configuration.</p>
            <div class="contract-grid">
                <div>${statusPill("PAN", "info")}<p>Panorama Device Group / Shared policy and object lineage.</p></div>
                <div>${statusPill("CHECK POINT", "info")}<p>Management API policy packages, rules, objects and install targets.</p></div>
                <div>${statusPill("FUTURE", "muted")}<p>Policy analysis, bulk operations and controlled write-plane workflows.</p></div>
            </div>
            ${device ? `<div class="policy-device-context">Current management scope: <strong>${escapeHtml(scope || "Not resolved")}</strong></div>` : ""}
        </div>
    `;
}


function evidenceCard(label, artifact, preferred = false) {
    artifact = artifact || {};
    const status = safe(artifact.status || "unavailable");
    return `
        <article class="evidence-card ${preferred ? "primary" : ""}">
            <div class="evidence-card-header">
                <div>
                    <div class="eyebrow">${preferred ? "Primary evidence" : "Supporting evidence"}</div>
                    <h3>${escapeHtml(label)}</h3>
                </div>
                ${statusPill(status.toUpperCase(), status === "success" ? "success" : "danger")}
            </div>
            <p>${escapeHtml(artifact.role || "")}</p>
            <dl class="evidence-definition-list">
                <div><dt>Method</dt><dd>${escapeHtml(artifact.method || "—")}</dd></div>
                <div><dt>Transport</dt><dd>${escapeHtml(artifact.transport || "—")}</dd></div>
                <div><dt>Change state</dt><dd>${escapeHtml((artifact.change_state || "—").toUpperCase())}</dd></div>
                <div><dt>Artifact size</dt><dd>${escapeHtml(formatBytes(artifact.size_bytes))}</dd></div>
                <div><dt>Schema</dt><dd>${escapeHtml(artifact.schema_status || "—")}</dd></div>
            </dl>
        </article>
    `;
}


function renderConfigEvidencePanel(device) {
    const panel = document.getElementById("configEvidencePanel");
    if (!panel) return;
    const fleet = configScopedFleet();
    if (!configUiData?.available) {
        panel.innerHTML = `<div class="empty-state large"><span>Evidence is not available.</span></div>`;
        return;
    }
    if (!device) {
        panel.innerHTML = `
            <div class="config-kpi-grid evidence-kpis">
                ${metricCard("Primary evidence", `${formatNumber(fleet.primary_evidence_success)} / ${formatNumber(fleet.selected)}`, "PAN effective-running + Check Point Gaia actual", "success")}
                ${metricCard("PAN alignment", `${formatNumber(fleet.alignment_supported_success || fleet.alignment_evidence_complete)} / ${formatNumber(fleet.alignment_supported_selected || fleet.pan_selected)}`, "Expected ↔ actual currently implemented for PAN", "success")}
                ${metricCard("Method failures", formatNumber(fleet.method_failures), "Collection method diagnostics", fleet.method_failures ? "danger" : "success")}
                ${metricCard("PAN TLS verification", fleet.tls_verify ? "ENABLED" : "DISABLED", fleet.ca_bundle_configured ? "CA bundle configured" : "CA bundle not configured", fleet.tls_verify ? "success" : "warning")}
                ${fleet.checkpoint_selected ? metricCard("CP SSH trust", fleet.checkpoint_production_trust_ready ? "STRICT" : "COMPATIBILITY", fleet.checkpoint_host_key_policy || "not collected", fleet.checkpoint_production_trust_ready ? "success" : "warning") : ""}
            </div>
            <section class="config-section-card">
                <div class="section-heading"><div><div class="eyebrow">Evidence model</div><h2>Read-only collection plane</h2></div></div>
                <div class="evidence-model-grid">
                    <div><strong>PAN</strong><span>Panorama intent + direct firewall effective-running with semantic alignment.</span></div>
                    <div class="flow-arrow">+</div>
                    <div><strong>Check Point</strong><span>Management-selected endpoint + interactive SSH capability handshake; direct Clish or Expert→explicit-Clish actual evidence, with VSX context preserved.</span></div>
                    <div class="flow-arrow">→</div>
                    <div><strong>Configuration</strong><span>Vendor-neutral current-state UI; alignment remains vendor-specific.</span></div>
                </div>
                ${fleet.tls_verify ? "" : `<div class="inline-message warning">PAN configuration collection currently runs without TLS certificate verification. Treat this as production hardening debt before rollout.</div>`}
                ${fleet.checkpoint_selected && !fleet.checkpoint_production_trust_ready ? `<div class="inline-message warning">Check Point configuration SSH host-key trust is still compatibility mode. Enable trusted known_hosts/pinned host keys before production rollout.</div>` : ""}
            </section>
        `;
        return;
    }
    if (device.vendor_key === "check_point") {
        panel.innerHTML = `
            <div class="evidence-card-grid">
                ${evidenceCard("Gaia current configuration", device.evidence?.actual, true)}
            </div>
            <section class="config-section-card">
                <div class="section-heading"><div><div class="eyebrow">Evidence contract</div><h2>Check Point actual configuration</h2></div></div>
                <div class="contract-grid">
                    <div>${statusPill("PRIMARY", "success")}<p>Direct SSH to the management-selected gateway endpoint using a PTY-backed interactive session; capability evidence selects direct Clish or Expert→explicit-Clish <code>show configuration</code>.</p></div>
                    <div>${statusPill("SECRET-AWARE", "info")}<p>Secret-bearing lines are withheld before CAS/history and browser projection. Raw Gaia configuration is never persisted by this collector.</p></div>
                    <div>${statusPill("READ ONLY", "muted")}<p>VSX contexts use the validated numeric <code>vsenv</code> context before Clish. No set/save/install operation is performed.</p></div>
                </div>
                ${device.host_key_policy === "strict_known_hosts" ? "" : `<div class="inline-message warning">SSH host-key verification is still in compatibility mode for this POC. Production deployment must enable trusted known_hosts or pinned fingerprints.</div>`}
            </section>`;
        return;
    }
    panel.innerHTML = `
        <div class="evidence-card-grid">
            ${evidenceCard("Effective-running", device.evidence?.effective, true)}
            ${evidenceCard("Merged configuration", device.evidence?.merged, false)}
            ${evidenceCard("Local active configuration", device.evidence?.active, false)}
            ${evidenceCard("Panorama control-plane view", device.evidence?.panorama_control, false)}
        </div>
        <section class="config-section-card">
            <div class="section-heading"><div><div class="eyebrow">Evidence contract</div><h2>What SecurityExpert trusts</h2></div></div>
            <div class="contract-grid">
                <div>${statusPill("PRIMARY", "success")}<p>Effective-running is the authoritative actual configuration plane for compliance and alignment.</p></div>
                <div>${statusPill("PROVENANCE", "info")}<p>Merged and local-active explain source and local override behavior; they do not replace effective-running.</p></div>
                <div>${statusPill("READ ONLY", "muted")}<p>This phase creates local evidence only. No push, commit, save, or firewall configuration change is performed.</p></div>
            </div>
        </section>
    `;
}


function changeStatePill(value) {
    const normalized = safe(value || "unknown").toUpperCase();
    const tone = normalized === "CHANGED" ? "warning" : normalized === "SAME" ? "success" : "muted";
    return statusPill(normalized, tone);
}


// Phase 0.6.3 — History timeline + safe normalized diff helpers

function historyChangePill(state) {
    const s = safe(state || "unknown").toUpperCase();
    const tone = s === "CHANGED" ? "warning" : s === "SAME" ? "success" : s === "FIRST" ? "info" : "muted";
    return statusPill(s, tone);
}

function diffChangeBadge(change) {
    const labels = { added: "Added", removed: "Removed", modified: "Changed" };
    const tones  = { added: "success", removed: "danger", modified: "warning" };
    const label  = labels[change] || escapeHtml(change);
    const tone   = tones[change] || "muted";
    return `<span class="status-pill ${escapeHtml(tone)}">${label}</span>`;
}

function renderHistoryTimeline(art) {
    if (!art || !Array.isArray(art.events) || art.events.length === 0) {
        return `<div class="inline-message neutral">No history snapshots recorded for ${escapeHtml(art?.artifact_label || "this artifact")} yet.</div>`;
    }
    const rows = art.events.map(ev => `
        <div class="history-timeline-row">
            <span class="history-timeline-ts">${escapeHtml(formatConfigTimestamp(ev.collected_at))}</span>
            ${historyChangePill(ev.change_state)}
        </div>`).join("");
    const trunc = art.truncated ? `<div class="inline-message neutral">Timeline shows the most recent ${art.events.length} events.</div>` : "";
    return `<div class="history-timeline">${rows}</div>${trunc}`;
}

function renderDiffSummary(pair) {
    if (!pair) return "";
    if (pair.status === "insufficient_evidence") {
        const reason = pair.reason || "diff_not_supported";
        const readable = {
            "cp_raw_text_diff_not_supported_in_0_6_3": "Structured configuration diff for Check Point is not supported in this release. Timeline change signals are available.",
            "previous_snapshot_metadata_not_readable": "The earlier snapshot could not be read; diff is unavailable.",
            "historical_object_not_readable": "A comparison snapshot object could not be resolved; diff is unavailable.",
        }[reason] || "Configuration diff is not available for this snapshot pair.";
        return `<div class="inline-message neutral">${escapeHtml(readable)}</div>`;
    }
    if (pair.status === "unavailable") {
        return `<div class="inline-message neutral">Configuration diff could not be computed for this snapshot pair.</div>`;
    }
    if (!Array.isArray(pair.diff_rows) || pair.diff_rows.length === 0) {
        return `<div class="inline-message success">No allowlisted configuration changes detected between these snapshots.</div>`;
    }

    const sectionOrder = ["system","dns","ntp","management","high_availability","network_summary"];
    const bySection = {};
    for (const row of pair.diff_rows) {
        const s = row.section || "other";
        if (!bySection[s]) bySection[s] = [];
        bySection[s].push(row);
    }
    const sectionLabels = {
        system: "System", dns: "DNS", ntp: "NTP", management: "Management",
        high_availability: "High Availability", network_summary: "Network Summary"
    };
    let html = `<div class="diff-summary-grid">`;
    for (const section of [...sectionOrder, ...Object.keys(bySection).filter(s => !sectionOrder.includes(s))]) {
        const rows = bySection[section];
        if (!rows || rows.length === 0) continue;
        html += `<div class="diff-section">
            <div class="diff-section-label">${escapeHtml(sectionLabels[section] || section)}</div>
            <table class="diff-table"><tbody>`;
        for (const row of rows) {
            html += `<tr class="diff-row diff-${escapeHtml(row.change || "modified")}">
                <td>${diffChangeBadge(row.change)}</td>
                <td class="diff-setting">${escapeHtml(row.setting || "")}</td>
                <td class="diff-before">${row.before != null ? escapeHtml(row.before) : "<em>—</em>"}</td>
                <td class="diff-after">${row.after  != null ? escapeHtml(row.after)  : "<em>—</em>"}</td>
            </tr>`;
        }
        html += `</tbody></table></div>`;
    }
    html += `</div>`;
    if (pair.truncated) {
        html += `<div class="inline-message neutral">Showing first ${pair.diff_rows.length} changes; additional changes exist.</div>`;
    }
    return html;
}

function renderConfigHistoryPanel(device) {
    const panel = document.getElementById("configHistoryPanel");
    if (!panel) return;
    const fleet = configScopedFleet();
    if (!configUiData?.available) {
        panel.innerHTML = `<div class="empty-state large"><span>Configuration history is not available.</span></div>`;
        return;
    }
    if (!device) {
        panel.innerHTML = `
            <div class="config-kpi-grid history-kpis">
                ${metricCard("First snapshot", formatNumber(fleet.first), "New history baseline", "info")}
                ${metricCard("Same", formatNumber(fleet.same), "No configuration content change", "success")}
                ${metricCard("Changed", formatNumber(fleet.changed), "New content hash observed", fleet.changed ? "warning" : "success")}
            </div>
            <section class="config-section-card">
                <div class="section-heading"><div><div class="eyebrow">Current cycle</div><h2>Effective snapshot state</h2></div></div>
                <div class="history-device-grid">
                    ${configDevices.map(item => `<div class="history-device-row"><strong>${escapeHtml(item.name || "")}</strong>${changeStatePill(item.history?.effective_change_state)}</div>`).join("")}
                </div>
            </section>
        `;
        return;
    }

    // 0.6.3: use history_v1 when available; fall back to legacy change-state display.
    const hv1 = device.history_v1;

    if (hv1 && hv1.status !== "unavailable" && Array.isArray(hv1.artifacts) && hv1.artifacts.length > 0) {
        const art = hv1.artifacts[0];
        const pair = Array.isArray(hv1.pair_results) && hv1.pair_results.length > 0 ? hv1.pair_results[0] : null;
        const headerNote = art.event_count > 0
            ? `${art.event_count} snapshot${art.event_count > 1 ? "s" : ""} recorded`
            : "No snapshots yet";
        panel.innerHTML = `
            <section class="config-section-card">
                <div class="section-heading">
                    <div><div class="eyebrow">History · ${escapeHtml(art.artifact_label || "")}</div><h2>Snapshot timeline</h2></div>
                    <div class="section-note">${escapeHtml(headerNote)}</div>
                </div>
                ${renderHistoryTimeline(art)}
            </section>
            ${pair ? `<section class="config-section-card">
                <div class="section-heading">
                    <div><div class="eyebrow">Latest change</div><h2>Configuration change summary</h2></div>
                    <div class="section-note">${escapeHtml(formatConfigTimestamp(pair.newer_collected_at))} vs ${escapeHtml(formatConfigTimestamp(pair.older_collected_at))}</div>
                </div>
                ${renderDiffSummary(pair)}
                <div class="inline-message neutral">This view shows safe allowlisted configuration fields only. Secret-bearing settings, raw configuration and value hashes are not embedded.</div>
            </section>` : ""}
        `;
        return;
    }

    // Legacy fallback: current-run change-state signals only.
    if (device.vendor_key === "check_point") {
        panel.innerHTML = `
            <section class="config-section-card">
                <div class="section-heading"><div><div class="eyebrow">Current collection</div><h2>Snapshot history signal</h2></div><div class="section-note">Collected ${escapeHtml(formatConfigTimestamp(device.completed_at))}</div></div>
                <div class="history-artifact-grid">
                    <div><span>Gaia redacted actual</span>${changeStatePill(device.history?.actual_change_state)}</div>
                </div>
                <div class="inline-message neutral">The content-addressed snapshot contains only secret-aware redacted Gaia configuration. A full raw canonical fingerprint participates in change detection without persisting the raw configuration.</div>
            </section>`;
        return;
    }
    panel.innerHTML = `
        <section class="config-section-card">
            <div class="section-heading"><div><div class="eyebrow">Current collection</div><h2>Snapshot history signals</h2></div><div class="section-note">Collected ${escapeHtml(formatConfigTimestamp(device.completed_at))}</div></div>
            <div class="history-artifact-grid">
                <div><span>Local active</span>${changeStatePill(device.history?.active_change_state)}</div>
                <div><span>Merged</span>${changeStatePill(device.history?.merged_change_state)}</div>
                <div><span>Effective-running</span>${changeStatePill(device.history?.effective_change_state)}</div>
            </div>
            <div class="inline-message neutral">Immutable snapshots and change-state detection are active. Full timeline and safe configuration diff are available when a device is selected and local history is attached.</div>
        </section>
    `;
}


function renderConfigBackupPanel(device) {
    const panel = document.getElementById("configBackupPanel");
    if (!panel) return;
    panel.innerHTML = `
        <div class="backup-placeholder">
            <div class="backup-placeholder-icon" aria-hidden="true">↻</div>
            <div class="eyebrow">Phase ${escapeHtml(configUiData?.backup?.phase || "0.6.0B")}</div>
            <h2>${device?.vendor_key === "check_point" ? "Check Point Native Recovery Backup" : "PAN Native Device-State Backup"}</h2>
            <p>${device?.vendor_key === "check_point" ? "Check Point vendor-native recovery/backup is not configured in 0.6.1B.1. Current Gaia configuration evidence is analysis/history evidence, not a recovery artifact." : escapeHtml(configUiData?.backup?.message || "Native recovery artifacts are not configured yet.")}</p>
            <div class="backup-capability-grid">
                <div><strong>Native recovery artifact</strong><span>Vendor-native device-state export, separate from diffable config evidence.</span></div>
                <div><strong>Integrity</strong><span>SHA-256, size/content validation and immutable history.</span></div>
                <div><strong>Security</strong><span>Local sensitive storage, RBAC and retention controls before production rollout.</span></div>
            </div>
            ${device ? `<div class="backup-device-context">Target device: <strong>${escapeHtml(device.name || "")}</strong></div>` : ""}
        </div>
    `;
}


function renderConfigSelected() {
    const device = configSelectedId === "__fleet__" ? null : selectedConfigDevice();
    if (configSelectedId !== "__fleet__" && !device) configSelectedId = "__fleet__";
    renderConfigHeader(device);
    renderConfigOverviewPanel(device);
    renderConfigCurrentPanel(device);
    renderConfigAlignmentPanel(device);
    renderConfigPolicyPanel(device);
    renderConfigEvidencePanel(device);
    renderConfigHistoryPanel(device);
    renderConfigBackupPanel(device);

    const fleet = configScopedFleet();
    const topStats = document.getElementById("configTopStats");
    if (topStats) {
        topStats.textContent = configUiData?.available
            ? `${formatNumber(fleet.primary_evidence_success)} / ${formatNumber(fleet.selected)} current | ${formatNumber(fleet.devices_with_local_override)} devices with overrides | ${formatNumber(fleet.devices_with_effective_drift)} devices with drift`
            : "Configuration not available";
    }
}


function switchConfigTab(nextTab) {
    activeConfigTab = ["overview", "current", "alignment", "policy", "history", "evidence", "backup"].includes(nextTab)
        ? nextTab
        : "overview";
    document.querySelectorAll(".config-tab").forEach(tab => {
        tab.classList.toggle("active", tab.dataset.configTab === activeConfigTab);
    });
    document.querySelectorAll(".config-panel").forEach(panel => panel.classList.remove("active"));
    const selected = document.getElementById(
        activeConfigTab === "overview" ? "configOverviewPanel" :
        activeConfigTab === "current" ? "configCurrentPanel" :
        activeConfigTab === "alignment" ? "configAlignmentPanel" :
        activeConfigTab === "policy" ? "configPolicyPanel" :
        activeConfigTab === "evidence" ? "configEvidencePanel" :
        activeConfigTab === "history" ? "configHistoryPanel" :
        "configBackupPanel"
    );
    selected?.classList.add("active");
    if (activeConfigTab === "current") renderConfigCurrentPanel(selectedConfigDevice());
    if (activeConfigTab === "alignment") renderConfigAlignmentPanel(selectedConfigDevice());
    if (activeConfigTab === "policy") renderConfigPolicyPanel(selectedConfigDevice());
}

