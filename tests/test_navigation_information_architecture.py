"""NAV.1 — left vertical product navigation, AC-1…AC-12.

Contract: `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` (FROZEN).

The horizontal one-root-per-module topbar strip is replaced by a left vertical
rail grouped by product domain. Two properties carry real product weight and
are therefore checked in a real browser rather than only as source strings:

  * **D-NAV6, the anti-placeholder law** — an entry renders *iff* the shell it
    runs in actually ships the `[data-module-panel]` it points at. A capability
    with no surface is omitted, never drawn disabled/greyed/"coming soon". The
    two shells run the identical composed script, so the only thing that may
    differ between them is which panels the shell ships (§5).
  * **D-NAV2, collapse is presentation only** — the collapsed rail renders the
    exact same entry set as the expanded one. Density, never availability and
    never authorization.

The static half of this file is deliberately source/template string checks in
the style of the repository's other UI contract tests; the DOM half skips
cleanly when Playwright/Chromium is absent, exactly like
`tests/test_html_render_harness.py`.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.render

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

NAV_JS = (ROOT / "static" / "navigation_ui.js").read_text(encoding="utf-8")
BOOTSTRAP_JS = (ROOT / "static" / "app_bootstrap.js").read_text(encoding="utf-8")
CSS = (ROOT / "static" / "style.css").read_text(encoding="utf-8")
REPORT_SHELL = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")
CONSOLE_SHELL = (ROOT / "templates" / "console.html").read_text(encoding="utf-8")
CONTRACT = ROOT / "docs" / "design" / "NAVIGATION_INFORMATION_ARCHITECTURE.md"

_LINE_COMMENT_RE = re.compile(r"//[^\n]*")
_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)


def _code_only(source: str) -> str:
    """Executable source with comments removed.

    Every check below that forbids a word is a check about what the code *does*,
    so the prose explaining why it does not do it must not trip it. Template
    literals are kept — the rail's markup lives in them and is exactly what the
    anti-placeholder check needs to see."""
    return _LINE_COMMENT_RE.sub("", _BLOCK_COMMENT_RE.sub("", source))


NAV_JS_CODE = _code_only(NAV_JS)

#: The six product-domain roots in NAV.1 §3 order.
EXPECTED_ROOTS = [
    "overview", "devices", "configuration", "operations", "compliance", "administration",
]

#: Every route that existed before NAV.1 and must keep resolving (D-NAV8).
LEGACY_ROUTES = [
    "overview", "inventory", "configuration", "compliance",
    "discovery", "failover", "exclusions", "project-plan",
]

#: Device-scoped views — device tabs, never navigation roots (NAV.1 §3).
DEVICE_TAB_IDS = ["overview", "current", "alignment", "policy", "history", "evidence", "backup"]


def _playwright_chromium_available() -> bool:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return False
    try:
        if Path("/opt/pw-browsers/chromium-1194/chrome-linux/chrome").exists():
            return True
        with sync_playwright() as p:
            return Path(p.chromium.executable_path).exists()
    except Exception:
        return False


def _launch_kwargs() -> dict:
    chromium = Path("/opt/pw-browsers/chromium-1194/chrome-linux/chrome")
    return {"executable_path": str(chromium)} if chromium.exists() else {}


@pytest.fixture(scope="module")
def rendered_report(tmp_path_factory) -> Path:
    """The exported static report, rendered from the committed uitest bundle."""
    from scripts.render_uitest import render

    return render(tmp_path_factory.mktemp("nav_render"))


# --- AC-1: the horizontal strip is gone; one rail, inside the shell ---------


def test_ac1_no_shell_ships_a_horizontal_module_nav_strip():
    for name, shell in (("index.html", REPORT_SHELL), ("console.html", CONSOLE_SHELL)):
        assert '<nav class="module-nav"' not in shell, (
            f"{name} still ships the horizontal topbar module strip (NAV.1 D-NAV1)"
        )
        assert shell.count("data-primary-nav") == 1, (
            f"{name} must ship exactly one [data-primary-nav] rail"
        )
        # The rail lives inside the app body, before the module canvas.
        assert shell.index('<div class="app-body">') < shell.index("data-primary-nav")
        assert shell.index("data-primary-nav") < shell.index('<div class="app-canvas">')
        assert shell.index('<div class="app-canvas">') < shell.index('data-module-panel="overview"')

    # The per-id label-abbreviation hack the horizontal strip needed below
    # 900px went with it; a rail does not truncate labels to fit.
    for dead in ("#overviewNav::before", "#inventoryNav::before", "#configurationNav::before"):
        assert dead not in CSS, f"{dead} is a leftover of the horizontal strip"


# --- AC-2 / AC-3: roots, order, and nothing that points nowhere -------------


def test_ac2_model_declares_exactly_the_six_evaluated_roots_in_order():
    roots = re.findall(r"^        id: \"([a-z-]+)\",$", NAV_JS, re.MULTILINE)
    assert roots == EXPECTED_ROOTS, (
        "the rendered root set/order must be the information architecture "
        "evaluated in NAV.1 §3, not one root per view"
    )


def test_ac2_every_modelled_module_has_a_panel_in_at_least_one_shell():
    modules = set(re.findall(r"module: \"([a-z-]+)\"", NAV_JS))
    for module in modules:
        marker = f'data-module-panel="{module}"'
        assert marker in REPORT_SHELL or marker in CONSOLE_SHELL, (
            f"model entry {module!r} points at a panel no shell ships — a model "
            f"row without a surface anywhere is a placeholder by another name"
        )


def test_ac3_availability_is_decided_by_shipped_surface_only():
    """D-NAV6. The predicate must be the panel-existence question and nothing
    else — not a mode string (console.html sets SECURITYEXPERT_MODE only after
    the composed script runs, so a load-time reportMode() gate would silently
    read "static" in the console) and not a hard-coded per-shell list."""
    predicate = NAV_JS[NAV_JS.index("function navigationShellHasPanel"):]
    predicate = predicate[: predicate.index("\n}\n")]
    assert "data-module-panel" in predicate
    assert "reportMode" not in predicate

    entry_rule = NAV_JS[NAV_JS.index("function navigationEntryAvailable"):]
    entry_rule = entry_rule[: entry_rule.index("\n}\n")]
    assert entry_rule.count("navigationShellHasPanel") == 1
    assert "console" not in entry_rule and "static" not in entry_rule


def test_ac3_nothing_renders_a_disabled_or_placeholder_entry():
    """An unavailable capability is omitted, not drawn inert. The rail's
    renderers must therefore emit no disabled/aria-disabled/"coming soon"
    affordance at all."""
    markup = "".join(
        NAV_JS_CODE[NAV_JS_CODE.index(f"function {name}"):][: NAV_JS_CODE[NAV_JS_CODE.index(f"function {name}"):].index("\n}\n")]
        for name in ("navigationRootMarkup", "renderPrimaryNavigation", "renderDeviceTabs")
    ).lower()
    for forbidden in ("disabled", "coming soon", "not-implemented", "unavailable", "placeholder"):
        assert forbidden not in markup, (
            f"a navigation renderer emits {forbidden!r}; D-NAV6 omits an unbacked "
            f"capability instead of rendering it in a dead state"
        )


# --- AC-4: "Add Device" is not a root, and is not rendered at all ----------


def test_ac4_add_device_is_a_contextual_action_and_renders_nowhere():
    # Declared against the devices domain, not as a navigation root (D-NAV5).
    action_block = NAV_JS[NAV_JS.index("NAVIGATION_CONTEXTUAL_ACTIONS"):NAV_JS.index("NAVIGATION_DEVICE_TABS")]
    assert 'id: "add_device"' in action_block
    assert 'domain: "devices"' in action_block
    assert "available: false" in action_block
    assert "pcp_console_registry_write_gate" in action_block, (
        "the reason an enrollment affordance does not exist must name the open "
        "decision that holds it, not be an unexplained absence"
    )

    # Not a root, not a child, and not a nav label in either shell.
    model_block = NAV_JS[NAV_JS.index("const NAVIGATION_MODEL"):NAV_JS.index("NAVIGATION_CONTEXTUAL_ACTIONS")]
    assert "add_device" not in model_block
    for name, shell in (("index.html", REPORT_SHELL), ("console.html", CONSOLE_SHELL)):
        assert "Add Device" not in shell and "Add device" not in shell, (
            f"{name} ships an enrollment affordance; enrollment is CLI-only "
            f"(PCP.1) and its console gate is undecided"
        )

    # Only actions whose backend contract exists may reach a renderer.
    getter = NAV_JS[NAV_JS.index("function navigationContextualActions"):]
    getter = getter[: getter.index("\n}\n")]
    assert "action.available === true" in getter


# --- AC-5: collapse is density, never availability -------------------------


def test_ac5_collapse_state_is_presentation_only():
    setter = NAV_JS[NAV_JS.index("function setNavigationCollapsed"):]
    setter = setter[: setter.index("\n}\n")]
    # It may touch storage, a class and the toggle's own labels — never the
    # model, the entry set or an availability decision.
    for forbidden in ("navigationAvailableRoots", "navigationEntryAvailable", "innerHTML", "remove()"):
        assert forbidden not in setter, (
            f"setNavigationCollapsed touches {forbidden!r}; collapsing must not "
            f"re-decide what exists (D-NAV2)"
        )
    assert "nav-collapsed" in setter and "securityexpert-nav-collapsed" in NAV_JS

    # The collapsed rules must hide labels/chrome only — never a nav item.
    collapsed_css = CSS[CSS.index(".app-body.nav-collapsed .nav-head-label"):]
    collapsed_css = collapsed_css[: collapsed_css.index(".app-canvas {")]
    assert ".module-nav-item {\n    display: none" not in collapsed_css


# --- AC-6 / AC-8: routes are derived, and every legacy route survives ------


def test_ac6_route_universe_is_derived_from_the_model_not_relisted():
    """D-NAV8. Two hard-coded lists were the previous shape, and they had
    already drifted: the hash list honoured five modules while the localStorage
    list honoured eight, so `#discovery`, `#failover` and `#exclusions` fell
    back to Overview from the URL."""
    assert "navigationModuleIds()" in BOOTSTRAP_JS
    for stale in ('"discovery", "failover", "exclusions"', '"overview", "inventory", "configuration", "compliance", "project-plan"'):
        assert stale not in BOOTSTRAP_JS, "the module id universe is re-listed instead of derived"


def test_ac8_report_ships_no_jobs_panel_and_console_does():
    """NAV.1 §5. The CON.2 job engine exists only behind the authenticated
    loopback console; the exported report is action-free by contract, so it
    ships no jobs panel — and by D-NAV6 therefore shows no Jobs entry, rather
    than a disabled one."""
    assert 'data-module-panel="jobs"' not in REPORT_SHELL
    assert 'data-module-panel="jobs"' in CONSOLE_SHELL
    assert 'module: "jobs"' in NAV_JS
    # The console job surface moved panel, not boundary: the submission path is
    # untouched and still lives entirely in console_actions.js.
    assert "consoleJobTypes" in CONSOLE_SHELL and "consoleJobsTable" in CONSOLE_SHELL
    assert "consoleJobTypes" not in REPORT_SHELL and "consoleJobsTable" not in REPORT_SHELL


# --- AC-9: device-scoped functions are tabs, never roots -------------------


def test_ac9_device_scoped_views_are_tabs_not_roots():
    tabs = re.findall(r"\{ tab: \"([a-z]+)\"", NAV_JS)
    assert tabs == DEVICE_TAB_IDS

    root_modules = set(re.findall(r"module: \"([a-z-]+)\"", NAV_JS))
    device_only = {"current", "alignment", "policy", "history", "evidence", "backup"}
    assert not (root_modules & device_only), (
        "a device-scoped view leaked into the root navigation; NAV.1 §3 keeps "
        "them inside the device experience"
    )

    # Both shells hand the strip to the model rather than hard-coding buttons.
    for shell in (REPORT_SHELL, CONSOLE_SHELL):
        assert 'class="config-tabs" data-device-tabs' in shell
        assert 'data-config-tab="' not in shell

    # A tab whose panel is absent is dropped, not left dead.
    renderer = NAV_JS[NAV_JS.index("function renderDeviceTabs"):]
    renderer = renderer[: renderer.index("\n}\n")]
    assert "document.getElementById(entry.panel)" in renderer


# --- AC-10: authorization seam, with no RBAC simulated --------------------


def test_ac10_navigation_is_authorization_aware_but_simulates_nothing():
    seam = NAV_JS[NAV_JS.index("function navigationAuthorizationContext"):]
    seam = seam[: seam.index("\n}\n")]
    assert 'model: "none"' in seam
    assert "DEPLOY.1A" in seam, "the seam must name the gate that will supply the real model"

    # No role/permission/scope/claim anywhere in the navigation path — an
    # invented authorization model is worse than no model (AGENTS.md
    # UNKNOWN/fail-closed law), and a "hidden because you lack access" state
    # cannot honestly exist while there is nothing to grant access.
    # Everything except the seam's own explanation, which is the one place
    # allowed to *name* the model that does not exist yet.
    head, _, rest = NAV_JS_CODE.partition("function navigationAuthorizationContext")
    body = (head + rest.partition("\n}\n")[2]).lower()
    for forbidden in ("role", "permission", "rbac", "grant", "scope", "claim", "isadmin", "canaccess"):
        assert forbidden not in body, (
            f"navigation code references {forbidden!r} — NAV.1 D-NAV9 forbids "
            f"implementing or simulating RBAC in this movement"
        )


def test_contract_document_is_frozen_and_linked():
    text = CONTRACT.read_text(encoding="utf-8")
    assert "**FROZEN" in text
    assert "docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md" in NAV_JS


# --- Browser-verified halves ----------------------------------------------


@pytest.mark.skipif(
    not _playwright_chromium_available(),
    reason="playwright not installed or no Chromium resolvable — "
           "pip install -r requirements-dev.txt && playwright install chromium",
)
def test_ac2_ac6_ac7_rail_renders_groups_and_every_legacy_route_resolves(rendered_report):
    from playwright.sync_api import sync_playwright

    errors: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(**_launch_kwargs())
        try:
            page = browser.new_page(viewport={"width": 1500, "height": 940})
            page.on("pageerror", lambda exc: errors.append(str(exc)))
            page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
            page.goto(rendered_report.resolve().as_uri())
            page.wait_for_timeout(300)

            # AC-2: grouped rail, not a flat list of roots.
            groups = page.eval_on_selector_all(
                "[data-nav-group]", "els => els.map(e => e.dataset.navGroup)"
            )
            assert groups == ["devices", "operations", "administration"]
            roots = page.eval_on_selector_all(
                "[data-nav-root]", "els => els.map(e => e.dataset.navRoot)"
            )
            assert roots == ["overview", "configuration", "compliance"]

            # AC-3: every rendered entry has a panel in THIS shell.
            rendered = page.eval_on_selector_all(
                ".module-nav-item", "els => els.map(e => e.dataset.module)"
            )
            panels = page.eval_on_selector_all(
                "[data-module-panel]", "els => els.map(e => e.dataset.modulePanel)"
            )
            assert set(rendered) <= set(panels)
            assert "jobs" not in rendered, "the action-free report must show no Jobs entry"
            assert sorted(rendered) == sorted(LEGACY_ROUTES)

            # AC-6 + AC-7: every pre-existing route still activates its panel,
            # and the button itself, with no console error.
            for module in LEGACY_ROUTES:
                before = len(errors)
                page.evaluate("m => location.hash = '#' + m", module)
                page.evaluate("m => switchModule(m)", module)
                page.wait_for_timeout(80)
                assert page.eval_on_selector(
                    f'[data-module-panel="{module}"]', "el => el.classList.contains('active')"
                ), f"route #{module} did not activate its panel"
                assert page.eval_on_selector(
                    f'.module-nav-item[data-module="{module}"]',
                    "el => el.classList.contains('active') && el.getAttribute('aria-current') === 'page'",
                ), f"route #{module} did not mark its rail entry current"
                assert len(errors) == before, errors[before:]

            # An unknown route falls back to Overview rather than a blank shell.
            page.evaluate("switchModule('does-not-exist')")
            page.wait_for_timeout(60)
            assert page.eval_on_selector(
                '[data-module-panel="overview"]', "el => el.classList.contains('active')"
            )
        finally:
            browser.close()

    assert not errors, errors


@pytest.mark.skipif(
    not _playwright_chromium_available(),
    reason="playwright not installed or no Chromium resolvable — "
           "pip install -r requirements-dev.txt && playwright install chromium",
)
def test_ac5_collapsing_the_rail_changes_density_not_the_entry_set(rendered_report):
    from playwright.sync_api import sync_playwright

    errors: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(**_launch_kwargs())
        try:
            page = browser.new_page(viewport={"width": 1500, "height": 940})
            page.on("pageerror", lambda exc: errors.append(str(exc)))
            page.goto(rendered_report.resolve().as_uri())
            page.wait_for_timeout(300)

            def entries():
                return page.eval_on_selector_all(
                    ".module-nav-item", "els => els.map(e => e.dataset.module)"
                )

            def rail_width():
                return page.eval_on_selector(".primary-nav", "el => el.getBoundingClientRect().width")

            expanded_entries, expanded_width = entries(), rail_width()
            page.eval_on_selector("#navCollapseToggle", "el => el.click()")
            page.wait_for_timeout(300)
            collapsed_entries, collapsed_width = entries(), rail_width()

            assert collapsed_entries == expanded_entries, (
                "collapsing removed or reordered an entry; D-NAV2 makes collapse "
                "a density change only"
            )
            assert collapsed_width < expanded_width, "the collapsed rail is not denser"
            # Every entry is still a real, clickable target in the collapsed rail.
            assert page.eval_on_selector_all(
                ".module-nav-item",
                "els => els.every(e => e.getBoundingClientRect().width > 0)",
            )
            page.eval_on_selector('.module-nav-item[data-module="compliance"]', "el => el.click()")
            page.wait_for_timeout(120)
            assert page.eval_on_selector(
                '[data-module-panel="compliance"]', "el => el.classList.contains('active')"
            )
            assert page.evaluate(
                "() => localStorage.getItem('securityexpert-nav-collapsed')"
            ) == "true"
        finally:
            browser.close()

    assert not errors, errors


@pytest.mark.skipif(
    not _playwright_chromium_available(),
    reason="playwright not installed or no Chromium resolvable — "
           "pip install -r requirements-dev.txt && playwright install chromium",
)
def test_ac3_ac9_a_shell_without_a_panel_renders_no_entry_and_no_dead_tab(rendered_report, tmp_path):
    """D-NAV6/D-NAV7 proved by construction rather than by assertion: strip a
    panel out of a rendered report and its navigation entry must disappear —
    not turn into a dead button. The same for a device tab."""
    from playwright.sync_api import sync_playwright

    html = rendered_report.read_text(encoding="utf-8")
    stripped = html.replace('data-module-panel="exclusions"', 'data-module-panel="exclusions-removed"')
    stripped = stripped.replace('id="configBackupPanel"', 'id="configBackupPanel-removed"')
    target = tmp_path / "stripped.html"
    target.write_text(stripped, encoding="utf-8")

    errors: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(**_launch_kwargs())
        try:
            page = browser.new_page()
            page.on("pageerror", lambda exc: errors.append(str(exc)))
            page.goto(target.resolve().as_uri())
            page.wait_for_timeout(300)

            rendered = page.eval_on_selector_all(
                ".module-nav-item", "els => els.map(e => e.dataset.module)"
            )
            assert "exclusions" not in rendered
            assert page.eval_on_selector_all(
                '.module-nav-item[data-module="exclusions"]', "els => els.length"
            ) == 0, "an entry with no surface was rendered instead of omitted"
            # Administration keeps its remaining child rather than vanishing.
            assert "project-plan" in rendered

            tabs = page.eval_on_selector_all(
                ".config-tab", "els => els.map(e => e.dataset.configTab)"
            )
            assert "backup" not in tabs and "alignment" in tabs
        finally:
            browser.close()

    assert not errors, errors


def test_ac12_navigation_change_keeps_the_exported_report_action_free(rendered_report):
    """CON.0 §6. The rail introduces navigation, never an action: the exported
    report must still contain no form, no submit control and no fetch."""
    html = rendered_report.read_text(encoding="utf-8")
    assert "<form" not in html.lower()
    assert 'type="submit"' not in html
    # console_actions.js (the only module that can submit anything) is never
    # inlined into the report.
    # console_actions.js is the only module that can submit anything, and it is
    # never part of the composed report script; its containers are absent too,
    # so consoleInitJobsPanel() would no-op even if it were.
    assert "_consoleSubmitJob" not in html
    assert 'id="consoleJobTypes"' not in html
    assert 'id="consoleJobsTable"' not in html
    # Against the composed script itself, not the whole file: the embedded
    # project-plan payload legitimately *describes* console routes in prose.
    from utils.html_export import compose_report_script

    assert "fetch(" not in compose_report_script(), (
        "the composed report script must issue no request"
    )


def test_ac11_navigation_module_is_composed_second(rendered_report):
    from utils.html_export import SCRIPT_MODULE_FILENAMES

    assert SCRIPT_MODULE_FILENAMES.index("navigation_ui.js") == 1
    assert SCRIPT_MODULE_FILENAMES.index("navigation_ui.js") < SCRIPT_MODULE_FILENAMES.index("app_bootstrap.js")
    # And it really is inlined into the exported report (the console composes
    # the identical list through utils.html_export.compose_modules).
    assert "NAVIGATION_MODEL" in rendered_report.read_text(encoding="utf-8")


def test_uitest_fixture_payloads_are_untouched_by_this_movement():
    """NAV.1 changes no payload field, so the render-harness fixture bundle
    needs no update (AGENTS.md "Project-state update rule" trigger not met).
    Guard that the movement did not quietly grow a payload dependency."""
    fixture = ROOT / "tests" / "fixtures" / "uitest"
    assert fixture.is_dir()
    for payload in ("rawData", "configUiData", "projectPlanData"):
        assert payload not in NAV_JS, (
            f"navigation_ui.js reads {payload}; the navigation model is derived "
            f"from shipped surfaces, not from evidence payloads"
        )
    assert json.loads((fixture / "unified.json").read_text(encoding="utf-8"))


# --- AC-8 in the shell that actually ships the surface ---------------------

# Reuses CON.1's own rendered-fixture runtime-paths fixture rather than
# rebuilding it: the point of this check is the real console shell, served by
# the real console app, not a second fixture that could drift from it.
from tests.test_con1_operator_console_read_only import uitest_runtime_paths  # noqa: E402,F401


@pytest.mark.skipif(
    not _playwright_chromium_available(),
    reason="playwright not installed or no Chromium resolvable — "
           "pip install -r requirements-dev.txt && playwright install chromium",
)
def test_ac8_the_console_shell_renders_the_jobs_entry_the_report_cannot(uitest_runtime_paths):
    """The two shells run the identical composed script; the only difference is
    which panels they ship (NAV.1 §5). The console ships the CON.2 jobs panel,
    so — and only so — its rail carries a Jobs entry under Operations."""
    import socket
    import threading
    import time

    import uvicorn
    from playwright.sync_api import sync_playwright

    from console.app import create_app
    from console.auth import generate_launch_token

    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    token = generate_launch_token()
    origin = f"http://127.0.0.1:{port}"
    server = uvicorn.Server(uvicorn.Config(
        create_app(runtime_paths=uitest_runtime_paths, launch_token=token, bound_origin=origin),
        host="127.0.0.1", port=port, log_level="warning",
    ))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    try:
        deadline = time.time() + 10
        while not server.started and time.time() < deadline:
            time.sleep(0.05)
        assert server.started, "console server did not start in time"

        errors: list[str] = []
        with sync_playwright() as p:
            browser = p.chromium.launch(**_launch_kwargs())
            try:
                page = browser.new_page(viewport={"width": 1500, "height": 940})
                page.on("pageerror", lambda exc: errors.append(str(exc)))
                page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
                # The console deliberately serves no favicon route (CON.1: "no
                # other route exists"), so the browser's own /favicon.ico probe
                # 404s and lands in the console-error stream as noise unrelated
                # to the page. Answer it here rather than widening the console's
                # route surface for a test.
                page.route("**/favicon.ico", lambda route: route.fulfill(status=204, body=""))
                page.goto(f"{origin}/#t={token}")
                page.wait_for_timeout(500)

                rendered = page.eval_on_selector_all(
                    ".module-nav-item", "els => els.map(e => e.dataset.module)"
                )
                assert "jobs" in rendered, "the console ships a jobs panel but rendered no entry"
                assert sorted(rendered) == sorted(LEGACY_ROUTES + ["jobs"])
                # Under Operations, not as a seventh root.
                assert page.eval_on_selector(
                    '.module-nav-item[data-module="jobs"]',
                    "el => el.closest('[data-nav-group]').dataset.navGroup",
                ) == "operations"

                page.eval_on_selector('.module-nav-item[data-module="jobs"]', "el => el.click()")
                page.wait_for_timeout(250)
                assert page.eval_on_selector(
                    '[data-module-panel="jobs"]', "el => el.classList.contains('active')"
                )
                # The job surface moved panel, not boundary: it is still the
                # CON.2 typed-intent submission path, rendered from /api/job-types.
                assert page.eval_on_selector("#consoleJobTypes", "el => el.innerHTML.length") > 0
            finally:
                browser.close()

        assert not errors, errors
    finally:
        server.should_exit = True
        thread.join(timeout=10)
