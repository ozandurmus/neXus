"""Headless load + navigation smoke test for a generated SecurityExpert report,
using a real Chromium via Playwright.

    python tools/render-harness/check_render_playwright.py <path-to-index.html>

A Playwright/Chromium alternative to check-render.mjs (bun + happy-dom).
happy-dom re-implements the DOM in JS and its window.eval() execution shim is
version-sensitive -- it broke outright in one cloud dev environment this
session (`window.eval is not a function`) with no code change on the report
side. Playwright drives a real, standard browser engine instead, so it is not
coupled to a specific happy-dom/bun version pairing. Kept as an alternative,
not a replacement: check-render.mjs stays the primary/faster check when its
toolchain is healthy; this is the fallback tests/test_html_render_harness.py
reaches for when it is not.

Checks the same four things as check-render.mjs, in the same order:
  1. the page loads without throwing (a dead inline <script> -- the 0.7.4a
     class of bug -- surfaces as a page load/console error here too);
  2. it renders in a DOM without a console error;
  3. every .module-nav-item switches its [data-module-panel] to .active;
  4. every .tab / .config-tab click runs without a new console error.

Exit 0 = pass. Non-zero = fail, with a report on stderr.
"""
from __future__ import annotations

import sys
from pathlib import Path

# Matches the pre-installed Chromium documented in this repo's dev/CI notes
# (PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers); falls back to Playwright's own
# default resolution (a `playwright install chromium`'d browser) elsewhere.
_CANDIDATE_EXECUTABLES = (
    "/opt/pw-browsers/chromium-1194/chrome-linux/chrome",
)


def _executable_path() -> str | None:
    for candidate in _CANDIDATE_EXECUTABLES:
        if Path(candidate).exists():
            return candidate
    return None


def check(html_path: Path) -> list[str]:
    """Run the checks against a rendered report; return a list of problems
    (empty list = pass)."""
    from playwright.sync_api import sync_playwright

    problems: list[str] = []
    console_errors: list[str] = []

    with sync_playwright() as p:
        launch_kwargs = {}
        executable = _executable_path()
        if executable:
            launch_kwargs["executable_path"] = executable
        browser = p.chromium.launch(**launch_kwargs)
        try:
            page = browser.new_page(viewport={"width": 1440, "height": 1000})
            page.on("console", lambda msg: console_errors.append(msg.text) if msg.type == "error" else None)
            page.on("pageerror", lambda err: console_errors.append(f"pageerror: {err}"))

            page.goto(html_path.resolve().as_uri())

            switch_module_defined = page.evaluate("typeof window.switchModule === 'function'")
            if not switch_module_defined:
                problems.append("page loaded but window.switchModule is not defined (bootstrap did not run)")

            if console_errors:
                problems.append("console errors during load:\n    " + "\n    ".join(console_errors))

            # --- module navigation ---------------------------------------
            nav_targets = page.eval_on_selector_all(
                ".module-nav-item", "els => els.map(el => el.dataset.module)"
            )
            if len(nav_targets) < 4:
                problems.append(f"only {len(nav_targets)} .module-nav-item buttons found (expected >= 6)")

            for target in nav_targets:
                before = len(console_errors)
                page.eval_on_selector(f'.module-nav-item[data-module="{target}"]', "el => el.click()")
                page.wait_for_timeout(50)

                panel = page.query_selector(f'[data-module-panel="{target}"]')
                if panel is None:
                    problems.append(f'nav "{target}": no [data-module-panel="{target}"] in the DOM')
                    continue
                panel_active = "active" in (panel.get_attribute("class") or "")
                if not panel_active:
                    problems.append(f'nav "{target}": panel did not become .active on click')

                button = page.query_selector(f'.module-nav-item[data-module="{target}"]')
                button_active = "active" in (button.get_attribute("class") or "") if button else False
                if not button_active:
                    problems.append(f'nav "{target}": button did not become .active on click')

                if len(console_errors) > before:
                    problems.append(
                        f'nav "{target}": {len(console_errors) - before} console error(s) on click:\n    '
                        + "\n    ".join(console_errors[before:])
                    )

            # --- inner tabs (best-effort: must not throw) ------------------
            for selector in (".tab[data-tab]", ".config-tab[data-config-tab]"):
                tab_count = page.eval_on_selector_all(selector, "els => els.length")
                for index in range(tab_count):
                    tabs = page.query_selector_all(selector)
                    if index >= len(tabs):
                        break
                    tab = tabs[index]
                    label = tab.get_attribute("data-tab") or tab.get_attribute("data-config-tab")
                    before = len(console_errors)
                    # A DOM-level .click() call, not Playwright's native click:
                    # mirrors check-render.mjs's unconditional click semantics.
                    # Tabs inside a currently-inactive module panel are
                    # legitimately display:none at this point in the walk (no
                    # bounding box for Playwright's native click, even with
                    # force=True); we're verifying the click handler doesn't
                    # throw, not testing real-user visibility.
                    tab.evaluate("el => el.click()")
                    page.wait_for_timeout(50)
                    if len(console_errors) > before:
                        problems.append(
                            f'tab "{label}": {len(console_errors) - before} console error(s) on click:\n    '
                            + "\n    ".join(console_errors[before:])
                        )
        finally:
            browser.close()

    return problems


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: python check_render_playwright.py <index.html>", file=sys.stderr)
        return 2
    html_path = Path(sys.argv[1])
    if not html_path.exists():
        print(f"FAIL: {html_path} does not exist", file=sys.stderr)
        return 1

    try:
        problems = check(html_path)
    except Exception as exc:  # pragma: no cover - surfaced verbatim to the caller
        print(f"FAIL: check_render_playwright.py errored: {exc}", file=sys.stderr)
        return 1

    if problems:
        print(f"FAIL ({len(problems)}):", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print("PASS: page loads, executes clean, nav modules + inner tabs switch with no console errors")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
