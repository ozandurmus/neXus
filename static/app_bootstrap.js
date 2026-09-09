// SecurityExpert report UI — app_bootstrap: theme, cross-module navigation, report initialization (loads last)

function preferredTheme() {
    let saved = "";
    try {
        saved = localStorage.getItem("securityexpert-theme") || localStorage.getItem("fbuddy-theme") || "";
    } catch (error) {
        saved = "";
    }
    if (saved === "light" || saved === "dark") {
        return saved;
    }
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches
        ? "light"
        : "dark";
}


function applyTheme(theme) {
    const normalized = theme === "light" ? "light" : "dark";
    document.documentElement.dataset.theme = normalized;
    const button = document.getElementById("themeToggle");
    if (button) {
        button.setAttribute("aria-label", normalized === "dark" ? "Switch to light mode" : "Switch to dark mode");
        button.title = normalized === "dark" ? "Light mode" : "Dark mode";
        button.innerHTML = normalized === "dark"
            ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 18a6 6 0 1 1 0-12 6 6 0 0 1 0 12Zm0-16v2m0 16v2M4.93 4.93l1.42 1.42m11.3 11.3 1.42 1.42M2 12h2m16 0h2M4.93 19.07l1.42-1.42m11.3-11.3 1.42-1.42"/></svg>'
            : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20.2 15.3A8.5 8.5 0 0 1 8.7 3.8 8.5 8.5 0 1 0 20.2 15.3Z"/></svg>';
    }
}


function toggleTheme() {
    const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    try {
        localStorage.setItem("securityexpert-theme", next);
        localStorage.setItem("fbuddy-theme", next); // backward-compatible migration key
    } catch (error) {
        // Standalone file:// exports may restrict storage; theme still changes for the session.
    }
    applyTheme(next);
}


applyTheme(preferredTheme());
document.getElementById("themeToggle")?.addEventListener("click", toggleTheme);


// SecurityExpert Phase 0.6.0A4.3.3 — Configuration UI refinement
// NAV.1 D-NAV8: the valid-module universe is *derived* from the rendered
// navigation (navigation_ui.js), never re-listed here. Two hard-coded lists
// were the previous shape and they had already drifted apart — the hash list
// honoured five modules while the localStorage list honoured eight, so
// `#discovery`, `#failover` and `#exclusions` silently fell back to Overview.
// Deriving the set from the model keeps every pre-existing route working and
// makes a route impossible to forget when a shell ships a new panel.
let activeModule = "overview";

// M11 -- shared entity workspace context (contract §6.3): one selected
// entity persists across module navigation. Extends this SAME hash +
// localStorage route model — `#<module>[/<entityId>]` — rather than a second
// mechanism (the recommended option in §6.3, and the routes_preserved
// criterion's own stated design intent). `parsedHashRoute()` is the one place
// the hash is split into its module/entity segments so both stay in sync.
function parsedHashRoute() {
    const raw = safe(window.location.hash).replace("#", "");
    const slash = raw.indexOf("/");
    if (slash === -1) return { module: raw, entityId: "" };
    return { module: raw.slice(0, slash), entityId: raw.slice(slash + 1) };
}

function savedModule() {
    const modules = navigationModuleIds();
    const hashModule = parsedHashRoute().module;
    if (modules.includes(hashModule)) {
        return hashModule;
    }
    try {
        const value = localStorage.getItem("securityexpert-module");
        return modules.includes(value) ? value : navigationDefaultModule();
    } catch (error) {
        return navigationDefaultModule();
    }
}


const SHARED_ENTITY_STORAGE_KEY = "securityexpert-entity";
let sharedEntityId = null;

// A hash entity segment always wins when present (it is the shareable/
// deep-linkable form §6.3 asks for); localStorage is only the last-selection
// fallback for a bare `#<module>` route. Never coerced to a default — no
// selection is a valid, safe value.
function savedSharedEntityId() {
    const fromHash = parsedHashRoute().entityId;
    if (fromHash) return fromHash;
    try {
        return localStorage.getItem(SHARED_ENTITY_STORAGE_KEY) || null;
    } catch (error) {
        return null;
    }
}


// Called once from each module's own selection handler (inventory, config,
// compliance) — never invents a cross-module identity mapping itself; it only
// records what the operator just picked, in whatever id space that module
// natively uses. `navigationAdoptSharedEntityId` is what decides, safely, per
// target module, whether that id also means something there.
function setSharedEntityId(entityId) {
    sharedEntityId = safe(entityId) || null;
    try {
        if (sharedEntityId) {
            localStorage.setItem(SHARED_ENTITY_STORAGE_KEY, sharedEntityId);
        } else {
            localStorage.removeItem(SHARED_ENTITY_STORAGE_KEY);
        }
    } catch (error) {
        // Standalone file:// exports may restrict storage.
    }
    writeActiveHashRoute();
}


// The one honest join point (§6.3: "an unknown or unresolvable entity
// segment falls back to no selection rather than an error"). No canonical id
// is confirmed to span every module's own id space today (relay/
// NXS-LOCAL-0022 RELAY_NOTE) — so a shared id is adopted into a module's own
// selection ONLY when that module's own already-computed id set actually
// contains it. A miss is silently "no selection" for that module, never a
// guessed match onto a different device (AGENTS.md opaque-identifier law).
function navigationAdoptSharedEntityId(knownIds) {
    if (!sharedEntityId) return null;
    const ids = knownIds instanceof Set ? knownIds : new Set(knownIds || []);
    return ids.has(sharedEntityId) ? sharedEntityId : null;
}


function writeActiveHashRoute() {
    const nextHash = sharedEntityId ? `#${activeModule}/${sharedEntityId}` : `#${activeModule}`;
    try {
        if (window.location.hash !== nextHash) {
            history.replaceState(null, "", nextHash);
        }
    } catch (error) {
        // file:// history can be restricted; module switching still works.
    }
}


// AC-A11Y-3: after a genuine navigation activation (never the passive initial
// render or a console payload refresh -- both call switchModule() with no
// options, from initializeReport()), move focus to the activated panel's
// own heading. The heading is given tabindex="-1" once so it is
// programmatically focusable without joining the ordinary tab order; a
// panel with no heading (should not happen -- every [data-module-panel]
// section ships one) is simply a no-op, so a dead/omitted destination can
// never receive focus.
function navigationFocusActivePanelHeading(moduleId) {
    const panel = document.querySelector(`[data-module-panel="${moduleId}"]`);
    const heading = panel?.querySelector("h1");
    if (!heading) return;
    if (!heading.hasAttribute("tabindex")) heading.setAttribute("tabindex", "-1");
    heading.focus();
}


function switchModule(nextModule, { moveFocus = false } = {}) {
    activeModule = navigationModuleIds().includes(nextModule)
        ? nextModule
        : navigationDefaultModule();

    document.querySelectorAll("[data-module-panel]").forEach(panel => {
        panel.classList.toggle("active", panel.dataset.modulePanel === activeModule);
    });
    syncNavigationActiveState(activeModule);

    const inventoryControls = document.getElementById("inventoryTopControls");
    const configurationControls = document.getElementById("configurationTopControls");
    if (inventoryControls) inventoryControls.hidden = activeModule !== "inventory";
    if (configurationControls) configurationControls.hidden = activeModule !== "configuration";

    try {
        localStorage.setItem("securityexpert-module", activeModule);
    } catch (error) {
        // Standalone file exports can restrict storage.
    }
    writeActiveHashRoute();

    if (activeModule === "overview") renderOverviewModule();
    if (activeModule === "inventory") {
        const adopted = navigationAdoptSharedEntityId(inventory.map(item => item.id));
        if (adopted) selectedId = adopted;
        renderDeviceList();
    }
    if (activeModule === "configuration") {
        const adopted = navigationAdoptSharedEntityId(configDevices.map(device => device.id));
        if (adopted) configSelectedId = adopted;
        renderConfigDeviceList();
        renderConfigSelected();
    }
    if (activeModule === "compliance") {
        const adopted = navigationAdoptSharedEntityId(complianceSubjects.map(subject => subject.subject_id));
        if (adopted) complianceSelectedSubjectId = adopted;
        renderComplianceModule();
    }
    if (activeModule === "discovery") renderDiscoveryModule();
    if (activeModule === "failover") renderFailoverModule();
    if (activeModule === "recovery") renderRecoveryModule();
    if (activeModule === "exclusions") renderExclusionsModule();
    if (activeModule === "project-plan") renderProjectPlan();
    // CON.2's job surface is a console-only panel (NAV.1 §5): console_actions.js
    // is not part of the composed report script, so this stays a guarded call —
    // in the exported report the function does not exist and no jobs panel does
    // either, which is exactly why the report renders no Jobs entry.
    if (activeModule === "jobs" && typeof consoleRefreshJobsTable === "function") {
        consoleRefreshJobsTable().catch(() => {});
    }

    if (moveFocus) navigationFocusActivePanelHeading(activeModule);
}


// Primary-navigation clicks are dispatched by navigation_ui.js's one delegated
// listener on the rail, so the rail can be re-rendered without orphaning a
// handler. Nothing binds per-button here any more.

document.getElementById("overviewOpenConfiguration")?.addEventListener("click", () => switchModule("configuration", { moveFocus: true }));
document.getElementById("overviewOpenCompliance")?.addEventListener("click", () => switchModule("compliance", { moveFocus: true }));
document.getElementById("configSearch")?.addEventListener("input", renderConfigDeviceList);
document.getElementById("configHeaderToggle")?.addEventListener("click", () => setConfigHeaderExpanded(!configHeaderExpanded));
document.getElementById("configSidebarToggle")?.addEventListener("click", () => setConfigSidebarOpen(!configSidebarOpen));
window.addEventListener("resize", () => {
    if (window.innerWidth > 900 && configSidebarOpen) setConfigSidebarOpen(false);
});
document.getElementById("configCurrentSearch")?.addEventListener("input", () => renderConfigCurrentPanel(selectedConfigDevice()));
document.getElementById("configClassificationFilter")?.addEventListener("change", () => renderConfigAlignmentPanel(selectedConfigDevice()));
document.getElementById("configAlignmentSearch")?.addEventListener("input", () => renderConfigAlignmentPanel(selectedConfigDevice()));
document.getElementById("complianceVendorFilter")?.addEventListener("change", event => {
    complianceVendorFilter = safe(event?.target?.value || "all");
    complianceSelectedSubjectId = "__fleet__";
    renderComplianceSubjectList();
    renderComplianceContent();
});
document.getElementById("complianceStatusFilter")?.addEventListener("change", event => {
    complianceStatusFilter = safe(event?.target?.value || "all");
    complianceSelectedSubjectId = "__fleet__";
    renderComplianceSubjectList();
    renderComplianceContent();
});

// 0.7.2 — inline "explain" expansion on any compliance control card.
document.addEventListener("click", event => {
    const toggle = event.target?.closest?.("[data-explain-toggle]");
    if (!toggle) return;
    const panel = toggle.nextElementSibling;
    if (!panel || !panel.classList.contains("compliance-explain-panel")) return;
    const open = panel.hidden;
    panel.hidden = !open;
    toggle.setAttribute("aria-expanded", open ? "true" : "false");
    toggle.textContent = open ? "Hide" : "Explain";
});

document.querySelectorAll(".config-tab").forEach(tab => {
    tab.addEventListener("click", () => switchConfigTab(tab.dataset.configTab));
});

// CON.1 C1-2/C1-3: report initialization entry point. Static mode
// (templates/index.html) calls this once with the inline JSON constants;
// console mode (static/console_actions.js) calls it after fetching
// /api/payloads, and again on every manual/auto refresh. Assigning the
// module-scope `let` payload globals (app_core.js) here, then re-running the
// same render sequence the static report always ran, is what makes a refresh
// behave identically to the first render.
function initializeReport(payloads) {
    rawData = payloads.rawData || [];
    configUiData = payloads.configUiData || {};
    complianceUiData = payloads.complianceUiData || {};
    cryptoUiData = payloads.cryptoUiData || {};
    projectPlanData = payloads.projectPlanData || {};
    discoveryUiData = payloads.discoveryUiData || {};
    exclusionsUiData = payloads.exclusionsUiData || {};
    failoverReadinessData = payloads.failoverReadinessData || {};
    recoveryUiData = payloads.recoveryUiData || {};

    // These derived collections are computed from the payloads above, not
    // read from them directly on every render (unlike every renderX()
    // function below) -- so they must be rebuilt explicitly here, before any
    // render call, or a refresh would leave them stuck on the previous
    // payload's data (or, on the very first render, the empty default).
    rebuildInventoryModel();
    rebuildConfigDevices();
    rebuildComplianceSubjects();
    sharedEntityId = savedSharedEntityId();

    renderOverviewModule();
    renderComplianceModule();
    renderDiscoveryModule();
    renderFailoverModule();
    renderRecoveryModule();
    renderExclusionsModule();
    renderProjectPlan();
    renderConfigDeviceList();
    renderConfigSelected();
    switchConfigTab(activeConfigTab);
    switchModule(savedModule());
}
