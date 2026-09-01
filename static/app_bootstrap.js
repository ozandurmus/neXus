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
let activeModule = "overview";
function savedModule() {
    const hashModule = safe(window.location.hash).replace("#", "");
    if (["overview", "inventory", "configuration", "compliance", "project-plan"].includes(hashModule)) {
        return hashModule;
    }
    try {
        const value = localStorage.getItem("securityexpert-module");
        return ["overview", "inventory", "configuration", "compliance", "discovery", "exclusions", "project-plan"].includes(value) ? value : "overview";
    } catch (error) {
        return "overview";
    }
}


function switchModule(nextModule) {
    activeModule = ["overview", "inventory", "configuration", "compliance", "discovery", "exclusions", "project-plan"].includes(nextModule)
        ? nextModule
        : "overview";

    document.querySelectorAll("[data-module-panel]").forEach(panel => {
        panel.classList.toggle("active", panel.dataset.modulePanel === activeModule);
    });
    document.querySelectorAll(".module-nav-item").forEach(button => {
        button.classList.toggle("active", button.dataset.module === activeModule);
    });

    const inventoryControls = document.getElementById("inventoryTopControls");
    const configurationControls = document.getElementById("configurationTopControls");
    if (inventoryControls) inventoryControls.hidden = activeModule !== "inventory";
    if (configurationControls) configurationControls.hidden = activeModule !== "configuration";

    try {
        localStorage.setItem("securityexpert-module", activeModule);
    } catch (error) {
        // Standalone file exports can restrict storage.
    }
    try {
        if (window.location.hash !== `#${activeModule}`) {
            history.replaceState(null, "", `#${activeModule}`);
        }
    } catch (error) {
        // file:// history can be restricted; module switching still works.
    }

    if (activeModule === "overview") renderOverviewModule();
    if (activeModule === "inventory") renderDeviceList();
    if (activeModule === "configuration") {
        renderConfigDeviceList();
        renderConfigSelected();
    }
    if (activeModule === "compliance") renderComplianceModule();
    if (activeModule === "discovery") renderDiscoveryModule();
    if (activeModule === "exclusions") renderExclusionsModule();
    if (activeModule === "project-plan") renderProjectPlan();
}


document.querySelectorAll(".module-nav-item").forEach(button => {
    button.addEventListener("click", () => switchModule(button.dataset.module));
});

document.getElementById("overviewOpenConfiguration")?.addEventListener("click", () => switchModule("configuration"));
document.getElementById("overviewOpenCompliance")?.addEventListener("click", () => switchModule("compliance"));
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

    // These derived collections are computed from the payloads above, not
    // read from them directly on every render (unlike every renderX()
    // function below) -- so they must be rebuilt explicitly here, before any
    // render call, or a refresh would leave them stuck on the previous
    // payload's data (or, on the very first render, the empty default).
    rebuildInventoryModel();
    rebuildConfigDevices();
    rebuildComplianceSubjects();

    renderOverviewModule();
    renderComplianceModule();
    renderDiscoveryModule();
    renderExclusionsModule();
    renderProjectPlan();
    renderConfigDeviceList();
    renderConfigSelected();
    switchConfigTab(activeConfigTab);
    switchModule(savedModule());
}
