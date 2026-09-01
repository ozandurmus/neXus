"""frontend_rendering_boundary -- CSP + escaping regression tests.

Contract: docs/history/phase/FRONTEND_RENDERING_BOUNDARY.md. This is the
implementation session's own regression suite, covering:

  AC-1  the CSP <meta> tag (D-CSP1) is present in the rendered report,
        exact string match -- a future edit cannot silently drop or loosen
        a directive.
  AC-3  a hostile device `displayName` (baked into
        tests/fixtures/uitest/unified.json by build_fixture.py) never
        appears as raw markup anywhere in the static output HTML outside
        the inline <script> block's JSON payload -- the primary check,
        static, no JS runtime required.
  AC-4  (skippable, real Chromium via Playwright) the same hostile payload,
        once the client's own escapeHtml() actually runs, renders as
        escaped text in the Network Inventory device list -- never a live
        element -- and fires no alert/dialog. Defense-in-depth on top of
        AC-3, per the contract's own note that AC-3 must not depend on a
        JS runtime being available in every environment.
  AC-5  utils.html_export._script_json's </script>-breakout neutralization,
        previously correct but untested.

The AC-2 exhaustive sink audit itself found zero gaps (every one of the 97
`.innerHTML` sinks in static/app.js already routes device/vendor-derived
values through escapeHtml() or a helper this session verified escapes
correctly) -- see docs/history/phase/FRONTEND_RENDERING_BOUNDARY.md's
"Audit findings" for the record. This file's job is regression coverage
for that verified state, not a fix.
"""
import json
import re
import sys
from pathlib import Path

import pytest

from utils.html_export import _script_json, run_html_export

pytestmark = pytest.mark.render

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

CSP_META = (
    '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; '
    "script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' data:; "
    "font-src 'self' data:; connect-src 'none'; object-src 'none'; "
    "base-uri 'none'; form-action 'none'\">"
)

# Must match tests/fixtures/uitest/build_fixture.py's hostile unified() entries
# verbatim.
HOSTILE_IMG = "<img src=x onerror=alert(1)>"
HOSTILE_SCRIPT = '"><script>alert(1)</script>'


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


@pytest.fixture(scope="module")
def rendered_html(tmp_path_factory) -> Path:
    from scripts.render_uitest import render
    out = tmp_path_factory.mktemp("frontend_boundary_render")
    return render(out)


def test_csp_meta_tag_present_verbatim(rendered_html):
    """AC-1."""
    html = rendered_html.read_text(encoding="utf-8")
    assert CSP_META in html
    assert html.count("Content-Security-Policy") == 1


def _script_block_span(html: str) -> tuple[int, int]:
    start = html.index("<script>")
    # The report's own closing tag is never touched by _script_json's
    # `</` -> `<\/` neutralization -- that only rewrites device-supplied
    # text *inside* the JSON payloads -- so the real </script> boundary is
    # intact and is the first literal `</script>` from `start`.
    end = html.index("</script>", start)
    return start, end


def test_hostile_device_label_never_appears_as_raw_markup(rendered_html):
    """AC-3 (primary, static, no JS runtime required).

    The hostile `device` values baked into
    tests/fixtures/uitest/unified.json by build_fixture.py must appear
    ONLY inside the inline <script> block's JSON data -- never as literal
    markup anywhere else in the page. That would mean some code path
    stopped going through the JSON-payload + client-side escapeHtml()
    pipeline this contract's AC-2 audit verified.
    """
    html = rendered_html.read_text(encoding="utf-8")
    start, end = _script_block_span(html)
    before, script_block, after = html[:start], html[start:end], html[end:]

    for hostile in (HOSTILE_IMG, HOSTILE_SCRIPT):
        assert hostile not in before
        assert hostile not in after

    # Round-trip proof: _script_json must not have mangled or silently
    # stripped either payload -- the server makes zero escaping assumption
    # and passes device data through faithfully; escaping is entirely the
    # client's job (D-ESC1). Verified for real by the Playwright test below
    # when a browser is available.
    m = re.search(r"\brawData: (.*?),\n", script_block)
    assert m, "rawData declaration not found in the generated <script>"
    raw = json.loads(m.group(1).replace("<\\/", "</"))
    devices = {entry.get("device") for entry in raw}
    assert HOSTILE_IMG in devices
    assert HOSTILE_SCRIPT in devices


@pytest.mark.skipif(
    not _playwright_chromium_available(),
    reason="playwright not installed or no Chromium resolvable — "
           "pip install -r requirements-dev.txt && playwright install chromium",
)
def test_headless_hostile_label_renders_escaped_not_executed(rendered_html):
    """AC-4 (defense-in-depth, real Chromium).

    Once the client JS actually runs, the hostile device name must render
    as escaped text in the Network Inventory device list -- never a live
    <img>/<script> element -- and must never fire an alert/dialog.
    """
    from playwright.sync_api import sync_playwright

    dialogs = []
    with sync_playwright() as p:
        launch_kwargs = {}
        chromium_path = Path("/opt/pw-browsers/chromium-1194/chrome-linux/chrome")
        if chromium_path.exists():
            launch_kwargs["executable_path"] = str(chromium_path)
        browser = p.chromium.launch(**launch_kwargs)
        try:
            page = browser.new_page()
            page.on("dialog", lambda d: (dialogs.append(d.message), d.dismiss()))
            page.goto(rendered_html.resolve().as_uri())
            page.eval_on_selector(
                '.module-nav-item[data-module="inventory"]', "el => el.click()"
            )
            page.wait_for_timeout(150)

            # No live <img>/<script> element was created inside the device
            # list -- the hostile markup rendered as inert escaped text, not
            # DOM structure. Stronger than a substring check: this proves no
            # element with that tag name exists in the actual DOM, wherever
            # its escaped text representation happens to place the letters
            # "img"/"script".
            assert page.eval_on_selector_all("#deviceList img", "els => els.length") == 0
            assert page.eval_on_selector_all("#deviceList script", "els => els.length") == 0

            list_html = page.eval_on_selector("#deviceList", "el => el.innerHTML")
            assert "<img" not in list_html
            assert "&lt;img src=x onerror=alert(1)&gt;" in list_html
            # A browser's own innerHTML serializer normalizes an escaped
            # `&quot;` back to a plain `"` when the surrounding position is
            # text content rather than an attribute value (unlike escapeHtml()
            # itself, which always encodes it -- both are correct, since
            # `&quot;` is only load-bearing inside an attribute). Assert what
            # the DOM actually reports here, not escapeHtml()'s literal output.
            assert '"&gt;&lt;script&gt;alert(1)&lt;/script&gt;' in list_html
        finally:
            browser.close()

    assert not dialogs, f"unexpected alert/dialog fired: {dialogs}"


# --- AC-5: _script_json </script>-breakout neutralization -----------------


def test_script_json_neutralizes_script_breakout():
    payload = {"device": "</script><script>alert(1)</script>"}
    encoded = _script_json(payload)
    assert "</script>" not in encoded
    assert "<\\/script>" in encoded
    # Round-trips back to the exact original value once the client's own
    # <\/ -> </ unescape (implicit inside a JS string literal) is undone --
    # proves this is breakout-prevention only, not data loss.
    assert json.loads(encoded.replace("<\\/", "</")) == payload


def test_script_json_breakout_does_not_break_the_whole_page(tmp_path):
    """A device value containing a literal </script> must not truncate the
    inline script or corrupt the surrounding template -- the full page
    still parses as one document (_fill_template unaffected)."""
    unified = tmp_path / "unified.json"
    unified.write_text(
        json.dumps([{"device": "</script><script>alert(1)</script>"}]),
        encoding="utf-8",
    )
    output_html = tmp_path / "index.html"
    run_html_export(unified_json=unified, output_html=output_html)
    html = output_html.read_text(encoding="utf-8")

    # A stray, harmless "<script>" substring can legitimately occur elsewhere
    # in the page (e.g. descriptive prose in an embedded project-plan note) --
    # an HTML tokenizer inside an already-open <script> element only treats a
    # literal "</script>" as significant, per spec, so that is the one
    # invariant that actually matters here: exactly one, the report's own
    # real closing tag, never a second one manufactured from device data.
    assert html.count("</script>") == 1
    assert "<\\/script>" in html

    m = re.search(r"\brawData: (.*?),\n", html)
    assert m
    raw = json.loads(m.group(1).replace("<\\/", "</"))
    assert raw[0]["device"] == "</script><script>alert(1)</script>"
