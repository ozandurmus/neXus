// SecurityExpert report UI — navigation_ui: the ONE navigation model (left
// vertical product navigation + the device-detail tab strip), its availability
// rule, its authorization seam and its renderers.
//
// Contract: docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md (NAV.1, FROZEN).
// Loads second (right after app_core.js) so every feature module and
// app_bootstrap.js can read the model; it owns no payload and renders no
// evidence. It reaches forward to switchModule() only from inside delegated
// event callbacks (the NAVIGATION_PUBLIC_SURFACE allowance in
// tests/test_frontend_module_composition.py).
//
// D-NAV6, the anti-placeholder law, is the load-bearing rule here: an entry is
// rendered *iff the shell it is running in actually ships the panel it points
// at*. A capability with no surface is omitted entirely — never drawn disabled,
// greyed or "coming soon". That single rule is also what makes the two shells
// (the action-free exported report and the Operator Console) able to run the
// identical composed script while exposing different entry sets, and what lets
// a later build light a capability up by shipping its panel plus one model row
// (D-NAV7 progressive population).

// Root order is the information architecture itself (NAV.1 §3): six product
// domains, evaluated against what the repository can actually serve — not one
// root per view. A root is either a `module` link (a domain with exactly one
// shipped view today) or an `items` group; a link becomes a group by gaining
// children, with no route change.
const NAVIGATION_MODEL = [
    {
        id: "overview",
        label: "Overview",
        module: "overview",
        icon: "overview",
    },
    {
        id: "devices",
        label: "Devices",
        icon: "devices",
        items: [
            { module: "inventory", label: "Inventory", icon: "inventory" },
            { module: "discovery", label: "Discovery", icon: "discovery" },
        ],
    },
    {
        id: "configuration",
        label: "Configuration",
        module: "configuration",
        icon: "configuration",
    },
    {
        id: "operations",
        label: "Operations",
        icon: "operations",
        items: [
            { module: "failover", label: "HA & readiness", icon: "failover" },
            // Console-only in practice: the CON.2 job engine exists only behind
            // the authenticated loopback console, so templates/index.html ships
            // no jobs panel and this entry simply does not render there. That is
            // D-NAV6 doing its job, not a special case in the renderer.
            { module: "jobs", label: "Jobs", icon: "jobs" },
        ],
    },
    {
        id: "compliance",
        label: "Compliance",
        module: "compliance",
        icon: "compliance",
    },
    {
        id: "administration",
        label: "Administration",
        icon: "administration",
        items: [
            { module: "exclusions", label: "Inventory exclusions", icon: "exclusions" },
            { module: "project-plan", label: "Project plan", icon: "project-plan" },
        ],
    },
];

// Contextual actions belong to a domain, never to the root navigation (D-NAV5).
// `available` is a statement about a backend contract that exists, not a
// preference: an action with no backend is declared here so its future location
// is decided and reviewable, and omitted by the renderer so nothing is drawn.
const NAVIGATION_CONTEXTUAL_ACTIONS = [
    {
        id: "add_device",
        domain: "devices",
        label: "Add device",
        available: false,
        unavailable_reason:
            "Device enrollment is CLI-only (PCP.1 --registry-enroll). Whether a " +
            "registry write may originate in the browser is the open " +
            "pcp_console_registry_write_gate decision, and the " +
            "inventory_exclusions_management_ui_backend precedent holds it behind " +
            "DEPLOY.1A's authorization boundary. No browser enrollment contract " +
            "exists, so no affordance is rendered.",
    },
];

// The device-detail tab strip (NAV.1 §3 "Device-scoped functions"). These are
// device-scoped views, never navigation roots. The same availability rule
// applies: a tab whose panel the shell does not ship is dropped, so a partly
// shipped device experience degrades to fewer honest tabs instead of dead ones.
const NAVIGATION_DEVICE_TABS = [
    { tab: "overview", label: "Overview", panel: "configOverviewPanel" },
    { tab: "current", label: "Configuration", panel: "configCurrentPanel" },
    { tab: "alignment", label: "Alignment", panel: "configAlignmentPanel" },
    { tab: "policy", label: "Policy & Objects", panel: "configPolicyPanel" },
    { tab: "history", label: "History", panel: "configHistoryPanel" },
    { tab: "evidence", label: "Evidence", panel: "configEvidencePanel" },
    { tab: "backup", label: "Backup", panel: "configBackupPanel" },
];

// Keys are quoted deliberately: a bare property name is a bare identifier
// token, and tests/test_frontend_module_composition.py's AST-lite ordering
// scan cannot distinguish one from a reference to a later module's global of
// the same name (several module ids are also identifiers over there).
const NAVIGATION_ICONS = {
    "overview": '<path d="M4 13h6V4H4v9Zm0 7h6v-5H4v5Zm10 0h6v-9h-6v9Zm0-16v5h6V4h-6Z"/>',
    "devices": '<path d="M4 5h16v10H4V5Zm4 14h8m-4-4v4"/>',
    "configuration": '<path d="M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6Zm8.4 3a8.4 8.4 0 0 0-.1-1.2l2-1.5-2-3.4-2.3 1a8.3 8.3 0 0 0-2-1.2L15.6 3H8.4l-.4 2.7a8.3 8.3 0 0 0-2 1.2l-2.3-1-2 3.4 2 1.5a8.4 8.4 0 0 0 0 2.4l-2 1.5 2 3.4 2.3-1a8.3 8.3 0 0 0 2 1.2l.4 2.7h7.2l.4-2.7a8.3 8.3 0 0 0 2-1.2l2.3 1 2-3.4-2-1.5c.06-.4.1-.8.1-1.2Z"/>',
    "operations": '<path d="M12 3v3m0 12v3m9-9h-3M6 12H3m14.5-6.5-2 2m-7 7-2 2m0-11 2 2m7 7 2 2M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z"/>',
    "compliance": '<path d="M12 3l7 3v6c0 4.2-2.9 7.9-7 9-4.1-1.1-7-4.8-7-9V6l7-3Zm-3 9 2.2 2.2L15 10.4"/>',
    "administration": '<path d="M4 6h16M4 12h16M4 18h10"/>',
    "inventory": '<path d="M4 5h16v5H4V5Zm0 9h16v5H4v-5Zm3-6.5h.01M7 16.5h.01"/>',
    "discovery": '<path d="M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14Zm5 12 4 4"/>',
    "failover": '<path d="M4 9a8 8 0 0 1 13.7-5.7L20 5.6M20 4v4h-4M20 15a8 8 0 0 1-13.7 5.7L4 18.4M4 20v-4h4"/>',
    "jobs": '<path d="M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01"/>',
    "exclusions": '<path d="M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16Zm-5.6 2.4 11.2 11.2"/>',
    "project-plan": '<path d="M6 4h9l4 4v12H6V4Zm9 0v4h4M9 13h6M9 17h4"/>',
};

const NAVIGATION_COLLAPSED_KEY = "securityexpert-nav-collapsed";
const NAVIGATION_GROUPS_KEY = "securityexpert-nav-groups";

let navigationCollapsedState = null;
let navigationCollapsedGroups = null;


// D-NAV9. The one authorization statement the navigation makes: there is no
// authorization model here yet. Nothing in this file reads a role, permission,
// scope or claim, and no entry is ever hidden "because you lack access" — that
// would be a simulated RBAC the repository has not built (AGENTS.md
// UNKNOWN/fail-closed law). When DEPLOY.1A ships the OIDC/RBAC boundary,
// navigationEntryAvailable() gains one additional conjunct and this returns the
// real model; until then availability is a shell/backend fact only.
function navigationAuthorizationContext() {
    return {
        model: "none",
        reason:
            "No authorization model exists in this build. Navigation availability " +
            "is derived from shipped backend surfaces only (NAV.1 D-NAV6/D-NAV9); " +
            "production RBAC arrives with DEPLOY.1A's OIDC boundary and is neither " +
            "implemented nor simulated here.",
    };
}


// D-NAV6. The whole availability rule: does this shell actually ship the panel?
// Deliberately a DOM question rather than a mode question — templates/console.html
// sets window.SECURITYEXPERT_MODE only *after* the composed script runs, so a
// load-time reportMode() check would silently read "static" in the console.
function navigationShellHasPanel(moduleId) {
    if (!safe(moduleId)) return false;
    return Boolean(document.querySelector(`[data-module-panel="${moduleId}"]`));
}


function navigationEntryAvailable(entry) {
    return navigationShellHasPanel(entry?.module);
}


// The roots this shell can honestly render, with unavailable children already
// dropped and a group that lost every child dropped with them.
function navigationAvailableRoots() {
    const roots = [];
    for (const root of NAVIGATION_MODEL) {
        if (root.module) {
            if (navigationEntryAvailable(root)) roots.push({ ...root, items: [] });
            continue;
        }
        const items = (root.items || []).filter(navigationEntryAvailable);
        if (items.length) roots.push({ ...root, items });
    }
    return roots;
}


// The module-id universe every route decision is derived from (D-NAV8), so the
// valid-route set can never drift from what the rail actually shows.
function navigationModuleIds() {
    const ids = [];
    for (const root of navigationAvailableRoots()) {
        if (root.module) ids.push(root.module);
        for (const item of root.items || []) ids.push(item.module);
    }
    return ids;
}


function navigationDefaultModule() {
    const ids = navigationModuleIds();
    return ids.includes("overview") ? "overview" : (ids[0] || "overview");
}


// D-NAV5 + D-NAV6: only actions whose backend contract actually exists are
// returned, so a declared-but-unbacked action (today: every one of them) can
// never reach a renderer.
function navigationContextualActions(domainId) {
    return NAVIGATION_CONTEXTUAL_ACTIONS.filter(
        action => action.domain === domainId && action.available === true
    );
}


function navigationIcon(name) {
    const path = NAVIGATION_ICONS[name] || NAVIGATION_ICONS.administration;
    return `<svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true">${path}</svg>`;
}


function navigationCollapsed() {
    if (navigationCollapsedState !== null) return navigationCollapsedState;
    let stored = "";
    try {
        stored = localStorage.getItem(NAVIGATION_COLLAPSED_KEY) || "";
    } catch (error) {
        stored = "";
    }
    if (stored === "true" || stored === "false") {
        navigationCollapsedState = stored === "true";
    } else {
        // Narrow viewports start condensed; this is density, never availability.
        navigationCollapsedState = Boolean(window.matchMedia?.("(max-width: 1100px)")?.matches);
    }
    return navigationCollapsedState;
}


// D-NAV2: presentation density only. The rendered entry set is untouched — the
// rail keeps every button it had, so no route, capability or permission changes.
function setNavigationCollapsed(collapsed) {
    navigationCollapsedState = Boolean(collapsed);
    try {
        localStorage.setItem(NAVIGATION_COLLAPSED_KEY, String(navigationCollapsedState));
    } catch (error) {
        // Standalone file:// exports may restrict storage; the rail still toggles.
    }
    document.querySelector(".app-body")?.classList.toggle("nav-collapsed", navigationCollapsedState);
    const toggle = document.getElementById("navCollapseToggle");
    if (toggle) {
        toggle.setAttribute("aria-expanded", String(!navigationCollapsedState));
        toggle.title = navigationCollapsedState ? "Expand navigation" : "Collapse navigation";
        toggle.setAttribute("aria-label", toggle.title);
    }
}


function navigationGroupCollapsedSet() {
    if (navigationCollapsedGroups !== null) return navigationCollapsedGroups;
    navigationCollapsedGroups = new Set();
    try {
        const raw = localStorage.getItem(NAVIGATION_GROUPS_KEY) || "";
        if (raw) {
            for (const id of JSON.parse(raw)) navigationCollapsedGroups.add(safe(id));
        }
    } catch (error) {
        navigationCollapsedGroups = new Set();
    }
    return navigationCollapsedGroups;
}


function setNavigationGroupCollapsed(groupId, collapsed) {
    const collapsedGroups = navigationGroupCollapsedSet();
    if (collapsed) {
        collapsedGroups.add(safe(groupId));
    } else {
        collapsedGroups.delete(safe(groupId));
    }
    try {
        localStorage.setItem(NAVIGATION_GROUPS_KEY, JSON.stringify([...collapsedGroups]));
    } catch (error) {
        // Storage-restricted export; the group still toggles for this session.
    }
    const group = document.querySelector(`[data-nav-group="${safe(groupId)}"]`);
    if (!group) return;
    group.classList.toggle("collapsed", Boolean(collapsed));
    const toggle = group.querySelector(".nav-group-toggle");
    if (toggle) toggle.setAttribute("aria-expanded", String(!collapsed));
}


function navigationRootMarkup(root) {
    const collapsedGroup = navigationGroupCollapsedSet().has(root.id);

    if (root.module) {
        return `
            <li class="nav-root" data-nav-root="${escapeHtml(root.id)}">
                <button id="${escapeHtml(root.id)}Nav" class="module-nav-item nav-link nav-root-link"
                        type="button" data-module="${escapeHtml(root.module)}"
                        data-nav-domain="${escapeHtml(root.id)}" title="${escapeHtml(root.label)}">
                    ${navigationIcon(root.icon)}<span class="nav-label">${escapeHtml(root.label)}</span>
                </button>
            </li>
        `;
    }

    const children = root.items
        .map(item => `
            <li>
                <button class="module-nav-item nav-link nav-child-link" type="button"
                        data-module="${escapeHtml(item.module)}"
                        data-nav-domain="${escapeHtml(root.id)}" title="${escapeHtml(root.label)} · ${escapeHtml(item.label)}">
                    <span class="nav-child-rail" aria-hidden="true"></span>
                    ${navigationIcon(item.icon)}<span class="nav-label">${escapeHtml(item.label)}</span>
                </button>
            </li>
        `)
        .join("");

    return `
        <li class="nav-root nav-group${collapsedGroup ? " collapsed" : ""}" data-nav-group="${escapeHtml(root.id)}">
            <button class="nav-group-toggle" type="button" data-nav-group-toggle="${escapeHtml(root.id)}"
                    aria-expanded="${collapsedGroup ? "false" : "true"}" title="${escapeHtml(root.label)}">
                ${navigationIcon(root.icon)}<span class="nav-label">${escapeHtml(root.label)}</span>
                <svg class="nav-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6"/></svg>
            </button>
            <ul class="nav-children">${children}</ul>
        </li>
    `;
}


// Rendered once at load and safe to re-run: every handler is delegated from the
// rail container (bound once, below), so a re-render never orphans a listener.
function renderPrimaryNavigation() {
    const rail = document.querySelector("[data-primary-nav]");
    if (!rail) return;

    const roots = navigationAvailableRoots().map(navigationRootMarkup).join("");
    rail.innerHTML = `
        <div class="nav-head">
            <button id="navCollapseToggle" class="nav-collapse-toggle" type="button"
                    aria-controls="primaryNavList" aria-expanded="true">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16"/></svg>
            </button>
            <div class="nav-head-label">Modules</div>
        </div>
        <ul id="primaryNavList" class="nav-roots">${roots}</ul>
    `;

    setNavigationCollapsed(navigationCollapsed());
}


// Keeps the rail's own state in step with whichever module is active: the
// active button, its aria-current, and — so an active entry is never hidden
// inside a folded group — its parent group expanded.
function syncNavigationActiveState(moduleId) {
    document.querySelectorAll(".module-nav-item").forEach(button => {
        const active = button.dataset.module === moduleId;
        button.classList.toggle("active", active);
        if (active) {
            button.setAttribute("aria-current", "page");
        } else {
            button.removeAttribute("aria-current");
        }
    });

    const active = document.querySelector(`.module-nav-item[data-module="${safe(moduleId)}"]`);
    const group = active?.closest?.("[data-nav-group]");
    if (group?.classList.contains("collapsed")) {
        setNavigationGroupCollapsed(group.dataset.navGroup, false);
    }
}


// The device-detail strip under the same availability rule (D-NAV6/D-NAV9): a
// tab is emitted only when this shell ships its panel, so an unfinished device
// experience shows fewer tabs rather than dead ones. app_bootstrap.js binds the
// generated buttons afterwards, exactly as it bound the previous static markup.
function renderDeviceTabs() {
    const strip = document.querySelector("[data-device-tabs]");
    if (!strip) return;

    strip.innerHTML = NAVIGATION_DEVICE_TABS
        .filter(entry => document.getElementById(entry.panel))
        .map((entry, index) => `
            <button id="config${entry.tab.charAt(0).toUpperCase()}${entry.tab.slice(1)}Tab"
                    class="config-tab${index === 0 ? " active" : ""}" type="button"
                    data-config-tab="${escapeHtml(entry.tab)}">${escapeHtml(entry.label)}</button>
        `)
        .join("");
}


function bindNavigationEvents() {
    const rail = document.querySelector("[data-primary-nav]");
    if (!rail) return;

    rail.addEventListener("click", event => {
        const collapseToggle = event.target?.closest?.("#navCollapseToggle");
        if (collapseToggle) {
            setNavigationCollapsed(!navigationCollapsed());
            return;
        }

        const groupToggle = event.target?.closest?.("[data-nav-group-toggle]");
        if (groupToggle) {
            const groupId = groupToggle.getAttribute("data-nav-group-toggle");
            setNavigationGroupCollapsed(groupId, !navigationGroupCollapsedSet().has(groupId));
            return;
        }

        const link = event.target?.closest?.(".module-nav-item");
        if (link?.dataset?.module) switchModule(link.dataset.module);
    });
}


renderPrimaryNavigation();
renderDeviceTabs();
bindNavigationEvents();
