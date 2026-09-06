"""M2 — NAV.1 accessibility closure, `AC-A11Y-1`…`AC-A11Y-5`.

Contract: `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` §13/§13.1 —
status **FROZEN — PRODUCT OWNER APPROVED** (reviewed head `ba56d2b`). This
movement (`M2`) owns exactly four named accessibility gaps and blocks the
`NAV.1` prototype's implementation merge until they close:

  * `AC-A11Y-1` — every rail entry has a real accessible name, expanded and
    icon-only.
  * `AC-A11Y-2` — rail/accordion motion is suppressed under
    `prefers-reduced-motion: reduce`.
  * `AC-A11Y-3` — activating a navigation entry moves focus to the activated
    workspace heading.
  * `AC-A11Y-4` — each group's children are associated with its visible label
    for assistive technology.

`AC-A11Y-5` (wide comparison tables scroll inside their own container, never
the page body) is not owned by `M2` but is merge-blocking; the last test here
confirms it still holds, since this movement's own rail/CSS changes are the
change under test.

Every check below inspects the **computed or semantically exposed** name,
role or focus target (Playwright's `get_by_role` resolves the real browser
accessibility tree; `document.activeElement` and `getComputedStyle` are the
actual runtime state) — never a source-string search — per the movement's
own test requirement.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from tests.test_navigation_information_architecture import (
    _launch_kwargs,
    _playwright_chromium_available,
    rendered_report,
)

pytestmark = pytest.mark.render

_SKIP = pytest.mark.skipif(
    not _playwright_chromium_available(),
    reason="playwright not installed or no Chromium resolvable — "
           "pip install -r requirements-dev.txt && playwright install chromium",
)


def _new_page(browser, errors, *, viewport=None):
    page = browser.new_page(viewport=viewport or {"width": 1500, "height": 940})
    page.on("pageerror", lambda exc: errors.append(str(exc)))
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
    return page


# --- AC-A11Y-1: accessible names survive expanded and icon-only mode -------


@_SKIP
def test_ac_a11y_1_rail_entries_have_real_accessible_names_expanded_and_collapsed(rendered_report):
    from playwright.sync_api import sync_playwright

    errors: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(**_launch_kwargs())
        try:
            page = _new_page(browser, errors)
            page.goto(rendered_report.resolve().as_uri())
            page.wait_for_timeout(300)

            # A root link, a group control and a grouped child, all resolved by
            # their *computed* accessible name (not a title/text scrape).
            assert page.get_by_role("button", name="Overview", exact=True).count() == 1
            assert page.get_by_role("button", name="Devices", exact=True).count() == 1, (
                "the Devices group toggle must expose 'Devices' as its "
                "accessible name, not only as visible text"
            )
            assert page.get_by_role("button", name="Devices · Inventory", exact=True).count() == 1
            assert page.get_by_role("button", name="Devices · Discovery", exact=True).count() == 1

            # Decorative icons must not create a duplicate or misleading name:
            # the accessible name is exactly the label, never "svg" / path data
            # / a doubled label.
            overview_name = page.eval_on_selector(
                '.module-nav-item[data-module="overview"]',
                "el => el.getAttribute('aria-label')",
            )
            assert overview_name == "Overview", overview_name

            # Collapse to icon-only density and prove the same names survive —
            # not merely a `title` that only shows on hover (AC-A11Y-1 explicitly
            # forbids relying on tooltip as the sole accessible name).
            page.eval_on_selector("#navCollapseToggle", "el => el.click()")
            page.wait_for_timeout(200)
            assert page.evaluate(
                "() => document.querySelector('.app-body').classList.contains('nav-collapsed')"
            )
            # The visible label span is now hidden by CSS — the accessible
            # name must still resolve via aria-label, not name-from-content.
            assert page.eval_on_selector(".nav-label", "el => getComputedStyle(el).display") == "none"
            assert page.get_by_role("button", name="Overview", exact=True).count() == 1
            assert page.get_by_role("button", name="Devices", exact=True).count() == 1
            assert page.get_by_role("button", name="Devices · Inventory", exact=True).count() == 1
        finally:
            browser.close()

    assert not errors, errors


# --- AC-A11Y-2: reduced motion suppresses rail/accordion transitions -------


@_SKIP
def test_ac_a11y_2_reduced_motion_suppresses_rail_and_accordion_transitions(rendered_report):
    from playwright.sync_api import sync_playwright

    errors: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(**_launch_kwargs())
        try:
            # Baseline: no reduced-motion preference — the transitions exist.
            page = _new_page(browser, errors)
            page.goto(rendered_report.resolve().as_uri())
            page.wait_for_timeout(200)
            rail_duration = page.eval_on_selector(
                ".primary-nav", "el => getComputedStyle(el).transitionDuration"
            )
            chevron_duration = page.eval_on_selector(
                ".nav-chevron", "el => getComputedStyle(el).transitionDuration"
            )
            assert rail_duration != "0s", "no baseline rail transition to suppress — test is meaningless"
            assert chevron_duration != "0s", "no baseline chevron transition to suppress — test is meaningless"
            page.close()

            # Emulated prefers-reduced-motion: reduce — both must collapse to 0s.
            page = _new_page(browser, errors)
            page.emulate_media(reduced_motion="reduce")
            page.goto(rendered_report.resolve().as_uri())
            page.wait_for_timeout(200)
            assert page.eval_on_selector(
                ".primary-nav", "el => getComputedStyle(el).transitionDuration"
            ) == "0s"
            assert page.eval_on_selector(
                ".nav-chevron", "el => getComputedStyle(el).transitionDuration"
            ) == "0s"

            # The rail still functionally collapses/expands and groups still
            # open/close — only the animated transition is suppressed, not the
            # underlying state change.
            page.eval_on_selector("#navCollapseToggle", "el => el.click()")
            page.wait_for_timeout(80)
            assert page.evaluate(
                "() => document.querySelector('.app-body').classList.contains('nav-collapsed')"
            )
            page.eval_on_selector('[data-nav-group-toggle="devices"]', "el => el.click()")
            page.wait_for_timeout(80)
            assert page.eval_on_selector(
                '[data-nav-group-toggle="devices"]', "el => el.getAttribute('aria-expanded')"
            ) == "false"
        finally:
            browser.close()

    assert not errors, errors


# --- AC-A11Y-3: focus transfer to the activated workspace heading ---------


@_SKIP
def test_ac_a11y_3_activation_moves_focus_to_the_workspace_heading(rendered_report):
    from playwright.sync_api import sync_playwright

    errors: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(**_launch_kwargs())
        try:
            page = _new_page(browser, errors)
            page.goto(rendered_report.resolve().as_uri())
            page.wait_for_timeout(250)

            # Passive initial render must NOT steal focus (no navigation entry
            # was "activated" — the page merely loaded/restored its hash).
            assert page.evaluate("() => document.activeElement.tagName") == "BODY", (
                "initial passive render moved focus off <body> — AC-A11Y-3 forbids "
                "focus-stealing during passive rendering"
            )

            # Root link (pointer activation): Compliance's own <h1>.
            page.click('.module-nav-item[data-module="compliance"]')
            page.wait_for_timeout(150)
            assert page.eval_on_selector(
                '[data-module-panel="compliance"]',
                "el => document.activeElement === el.querySelector('h1')",
            ), "activating a root link did not move focus to its workspace heading"
            assert page.evaluate("() => document.activeElement.getAttribute('tabindex')") == "-1", (
                "the focused heading must be programmatically focusable via "
                "tabindex=-1, not join the ordinary tab order"
            )

            # Grouped child (keyboard activation): Devices -> Inventory.
            page.eval_on_selector('.module-nav-item[data-module="inventory"]', "el => el.focus()")
            page.keyboard.press("Enter")
            page.wait_for_timeout(150)
            assert page.eval_on_selector(
                '[data-module-panel="inventory"]',
                "el => document.activeElement === el.querySelector('h1')",
            ), "keyboard-activating a grouped child did not move focus to its workspace heading"

            # Hash-based restoration (a fresh load landing directly on a route,
            # as a back-navigation or a reload would) is passive, not an
            # "activation" — it must not steal focus either, and must not loop.
            # A brand-new page is required: navigating the same document to a
            # new hash fires no hashchange handler in this app (by design —
            # only the initial load reads the hash) and so is not the scenario
            # AC-A11Y-3 is guarding.
            fresh = _new_page(browser, errors)
            fresh.goto(rendered_report.resolve().as_uri() + "#failover")
            fresh.wait_for_timeout(250)
            assert fresh.evaluate("() => document.activeElement.tagName") == "BODY", (
                "a fresh load restoring a route from its hash moved focus off "
                "<body> — this is passive rendering, not an activation"
            )
            assert fresh.eval_on_selector(
                '[data-module-panel="failover"]', "el => el.classList.contains('active')"
            ), "the hash route itself must still resolve"
            fresh.close()
        finally:
            browser.close()

    assert not errors, errors


# --- AC-A11Y-4: group children associated with the group's visible label --


@_SKIP
def test_ac_a11y_4_group_children_are_labelled_for_devices_operations_administration(rendered_report):
    from playwright.sync_api import sync_playwright

    errors: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(**_launch_kwargs())
        try:
            page = _new_page(browser, errors)
            page.goto(rendered_report.resolve().as_uri())
            page.wait_for_timeout(250)

            for group_label, expected_children in (
                ("Devices", {"Devices · Inventory", "Devices · Discovery"}),
                ("Operations", {"Operations · HA & readiness"}),
                ("Administration", {"Administration · Inventory exclusions", "Administration · Project plan"}),
            ):
                group = page.get_by_role("group", name=group_label, exact=True)
                assert group.count() == 1, f"no accessible group named {group_label!r} found"
                names = set(
                    group.evaluate(
                        "el => [...el.querySelectorAll('button')].map(b => b.getAttribute('aria-label'))"
                    )
                )
                assert names == expected_children, (group_label, names)

            # Expand/collapse state is exposed on the toggle itself.
            assert page.eval_on_selector(
                '[data-nav-group-toggle="devices"]', "el => el.getAttribute('aria-expanded')"
            ) == "true"

            # The association must survive the rail's icon-only (collapsed)
            # density too — same role/name resolution, not just visual layout.
            page.eval_on_selector("#navCollapseToggle", "el => el.click()")
            page.wait_for_timeout(150)
            assert page.get_by_role("group", name="Devices", exact=True).count() == 1
            assert page.get_by_role("group", name="Operations", exact=True).count() == 1
            assert page.get_by_role("group", name="Administration", exact=True).count() == 1
        finally:
            browser.close()

    assert not errors, errors


# --- Both shells: console parity for the Jobs group + one root/child pair --


from tests.test_con1_operator_console_read_only import uitest_runtime_paths  # noqa: E402,F401


@_SKIP
def test_ac_a11y_1_and_4_hold_in_the_console_shell_too(uitest_runtime_paths):
    """The console renders the identical composed script from one navigation
    model (contract §5/`AC-SH-3`); this proves the accessibility fixes are not
    report-only by re-checking one root, one grouped child, focus transfer and
    the console-only Jobs group against the real console app."""
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
                page = _new_page(browser, errors)
                page.route("**/favicon.ico", lambda route: route.fulfill(status=204, body=""))
                page.goto(f"{origin}/#t={token}")
                page.wait_for_timeout(500)

                assert page.get_by_role("button", name="Overview", exact=True).count() == 1
                assert page.get_by_role("button", name="Operations · Jobs", exact=True).count() == 1
                group = page.get_by_role("group", name="Operations", exact=True)
                assert group.count() == 1
                names = set(
                    group.evaluate(
                        "el => [...el.querySelectorAll('button')].map(b => b.getAttribute('aria-label'))"
                    )
                )
                assert "Operations · Jobs" in names

                page.click('.module-nav-item[data-module="jobs"]')
                page.wait_for_timeout(200)
                assert page.eval_on_selector(
                    '[data-module-panel="jobs"]',
                    "el => document.activeElement === el.querySelector('h1')",
                ), "activating the console-only Jobs entry did not focus its heading"
            finally:
                browser.close()

        assert not errors, errors
    finally:
        pass


# --- AC-A11Y-5 (merge-blocking, not M2-owned): confirm it still holds ------


@_SKIP
def test_ac_a11y_5_wide_interface_matrix_scrolls_in_its_own_container_not_the_page(rendered_report):
    """Not an M2-owned criterion, but merge-blocking: this movement's own rail
    and CSS changes must not have regressed the existing table-container
    scroll containment. Selects the ClusterXL logical entity (the widest
    existing comparison table — an interface matrix with a member column per
    physical member) at a narrow viewport to force real horizontal overflow,
    then proves the *page* never scrolls horizontally while the table's own
    container does."""
    from playwright.sync_api import sync_playwright

    errors: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(**_launch_kwargs())
        try:
            page = _new_page(browser, errors, viewport={"width": 480, "height": 900})
            page.goto(rendered_report.resolve().as_uri())
            page.wait_for_timeout(200)
            page.click('.module-nav-item[data-module="inventory"]')
            page.wait_for_timeout(150)
            page.click(".device-item:has-text('cp-core-CLS')")
            page.wait_for_timeout(200)

            table_overflows = page.eval_on_selector(
                "#interfaceTable",
                "el => el.getBoundingClientRect().width > el.closest('.table-container').getBoundingClientRect().width",
            )
            assert table_overflows, (
                "the interface matrix did not overflow its container at a narrow "
                "viewport — this check needs real overflow to prove containment"
            )
            container_scrolls = page.eval_on_selector(
                "#interfaceTable",
                "el => { const c = el.closest('.table-container'); return c.scrollWidth > c.clientWidth; }",
            )
            assert container_scrolls, "the table-container is not itself the scrolling element"

            page_scrollwidth = page.evaluate("() => document.documentElement.scrollWidth")
            viewport_width = page.evaluate("() => window.innerWidth")
            assert page_scrollwidth <= viewport_width, (
                f"the page body scrolled horizontally ({page_scrollwidth} > {viewport_width}) "
                f"instead of containing the wide table in its own container"
            )
        finally:
            browser.close()

    assert not errors, errors
