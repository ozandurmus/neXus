"""0.7.6 — automated HTML render harness.

Renders the report from the committed `tests/fixtures/uitest` bundle so every
module is populated, then:
  * (always) checks every embedded `const X = ...;` payload is valid JSON — the
    0.7.4a bug class, catchable with no JS engine;
  * (when a JS runtime + the harness deps are present) runs
    `tools/render-harness/check-render.mjs`, which executes the script in a DOM
    and clicks every nav module + inner tab, asserting panels switch with no
    console errors;
  * (when Playwright + a Chromium are present) runs
    `tools/render-harness/check_render_playwright.py`, a real-browser
    alternative that performs the same checks -- not gated on the same
    happy-dom toolchain, so it still catches a render regression if that
    toolchain breaks.

render_harness_happydom_pin (discovered 2026-08-30, root-caused 2026-08-31):
happy-dom's per-Window script execution runs inside a `node:vm` context.
Under Bun, that context's globals come back broken -- `window.eval` is an
own property that is simply `undefined` (`TypeError: window.eval is not a
function`), and even built-ins like `Map`/`Error` resolve to `undefined`
inside a script run there. This reproduces on every happy-dom major back to
16.x, i.e. it is not a happy-dom version regression -- Bun's `node:vm` shim
does not correctly implement what happy-dom needs. Under real Node.js the
exact same happy-dom version (including the currently pinned ^20.0.0) works
correctly: `window.eval` is a real function and executes with normal global
semantics. `check-render.mjs` itself needed no change; `_js_runtime()` below
now prefers a real `node` binary over `bun` to execute it, falling back to
`bun` (broken for this specific check, kept as a last resort) only when no
Node is on PATH. `bun install`/`bun.lock` are unaffected -- Bun's package
resolution is not implicated, only its `vm` module.
"""
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.render

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

HARNESS = ROOT / "tools" / "render-harness" / "check-render.mjs"
HARNESS_DEPS = ROOT / "tools" / "render-harness" / "node_modules" / "happy-dom"
PLAYWRIGHT_HARNESS = ROOT / "tools" / "render-harness" / "check_render_playwright.py"

_PAYLOAD_CONSTS = (
    "rawData", "configUiData", "complianceUiData",
    "cryptoUiData", "projectPlanData", "discoveryUiData",
    "exclusionsUiData", "failoverReadinessData",
)


def _bun() -> str | None:
    found = shutil.which("bun")
    if found:
        return found
    fallback = Path(os.path.expanduser("~")) / ".bun" / "bin" / "bun"
    return str(fallback) if fallback.exists() else None


def _js_runtime() -> str | None:
    """A JS runtime to execute check-render.mjs under.

    Prefer real Node.js: its `node:vm` implementation is what happy-dom's
    per-Window script execution actually needs (see module docstring,
    render_harness_happydom_pin). `bun install` still resolves/installs the
    harness's node_modules just fine -- only Bun's vm module breaks this
    specific check, so it stays a last-resort fallback in case a session has
    Bun but no Node at all.
    """
    return shutil.which("node") or _bun()


def _playwright_chromium_available() -> bool:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return False
    try:
        # Pre-installed Chromium documented for this repo's dev/CI notes
        # takes precedence (matches check_render_playwright.py's own
        # resolution); otherwise fall back to whatever Playwright itself
        # resolves (a `playwright install chromium`'d browser). Playwright's
        # executable_path is a resolved path string regardless of whether a
        # browser actually exists there, so check the file itself.
        if Path("/opt/pw-browsers/chromium-1194/chrome-linux/chrome").exists():
            return True
        with sync_playwright() as p:
            return Path(p.chromium.executable_path).exists()
    except Exception:
        return False


@pytest.fixture(scope="module")
def rendered_html(tmp_path_factory) -> Path:
    from scripts.render_uitest import render
    out = tmp_path_factory.mktemp("uitest_render")
    return render(out)


def test_every_embedded_payload_is_valid_json(rendered_html):
    """No JS engine needed — the 0.7.4a class (a corrupted payload literal that
    kills the whole <script>) shows up as invalid JSON here."""
    html = rendered_html.read_text(encoding="utf-8")
    for name in _PAYLOAD_CONSTS:
        m = re.search(rf"\b{re.escape(name)}: (.*?),\n", html)
        assert m, f"{name} not found in the generated <script>"
        json.loads(m.group(1).replace("<\\/", "</"))


def _const(html: str, name: str):
    # CON.1 C1-2/C1-3: report initialization is the initializeReport({...})
    # object literal (app_bootstrap.js), not top-level `const name = ...;`
    # declarations — see templates/index.html.
    m = re.search(rf"\b{re.escape(name)}: (.*?),\n", html)
    assert m, f"{name} not found"
    return json.loads(m.group(1).replace("<\\/", "</"))


def test_all_six_modules_are_populated(rendered_html):
    """The uitest bundle must actually exercise every module, not just render the
    empty states (that is scripts/render_sample.py's job)."""
    html = rendered_html.read_text(encoding="utf-8")
    compliance = _const(html, "complianceUiData")
    assert compliance.get("available") is True
    ov = compliance["compliance_overview"]
    assert ov["subjects"] >= 1
    assert len(ov["history"]) >= 2 and ov["trend"] is not None       # 0.7.5 trend renders
    assert _const(html, "cryptoUiData").get("available") is True
    assert _const(html, "discoveryUiData")["entities"]


def test_all_topologies_present(rendered_html):
    """The bundle is a topology matrix. If a builder change drops a device shape,
    the harness would keep passing on a thinner render — this fails loudly."""
    html = rendered_html.read_text(encoding="utf-8")
    devices = _const(html, "configUiData")["devices"]

    cp = [d for d in devices if d["vendor_key"] == "check_point"]
    pan = [d for d in devices if d["vendor_key"] == "palo_alto"]
    entity_types = {d["entity_type"] for d in cp}
    assert {"gateway", "clusterxl_member", "vsx_host", "virtual_system"} <= entity_types

    cp_ha = {d.get("ha_role") for d in cp}
    assert {"active", "standby"} <= cp_ha                              # ClusterXL + VSX cluster
    pan_ha = {d.get("ha_role") for d in pan}
    assert {"Local Active", "Local Passive", "HA Disabled"} <= pan_ha

    assert any((d.get("vsys_count") or 0) >= 3 for d in pan)          # PAN multi-vsys
    assert any(d.get("vsys_count") == 2 for d in pan)                  # PAN multi-vsys HA
    assert any(d["entity_type"] == "virtual_system" for d in cp)       # CP VSID

    assert any(not d["connected"] for d in devices)                    # an UNAVAILABLE device
    change_states = {(_dig(d, "history", "actual_change_state")
                      or _dig(d, "history", "effective_change_state")) for d in devices}
    assert {"changed", "same", "first"} <= change_states

    classes = set()
    for d in devices:
        for f in d.get("alignment", {}).get("findings", []):
            classes.add(f.get("classification"))
    assert {"ALIGNED", "LOCAL_OVERRIDE", "MEMBER_SPECIFIC", "EFFECTIVE_DRIFT"} <= classes

    inv = _const(html, "rawData")
    statuses = {(_dig(r, "inventory_status", "data_state")) for r in inv}
    assert {"live", "last_known_good", "no_data"} <= statuses          # stale + disconnected inventory
    assert any(r.get("source") == "vsx" for r in inv)
    assert any((r.get("vs_id") or "") for r in inv)                    # per-VSID inventory rows

    crypto_status = set()
    for s in _const(html, "cryptoUiData")["subjects"]:
        for f in s["findings"]:
            crypto_status.add(f["status"])
    assert {"PASS", "FINDING", "UNKNOWN"} <= crypto_status


def _dig(obj, *keys):
    for k in keys:
        obj = (obj or {}).get(k) if isinstance(obj, dict) else None
    return obj


def test_discovery_fixture_entities_match_the_real_builder_shape(rendered_html):
    """discovery_fixture_shape_drift regression: discovery_ui.json is
    hand-authored and injected via a monkeypatch (unlike
    inventory_exclusions.json, which flows through the real builder), so a
    key-name mismatch here is invisible to every other check in this file --
    the harness would keep passing on a payload static/app.js's
    renderDiscoveryModule() cannot actually read. Directly compare the
    fixture's key set against a real build_discovery_capability_payload()
    call.
    """
    from utils.capability_registry import CapabilityProfile, CapabilityStore, ShellType
    from utils.collection_executor import CollectionCoordinator, Provenance
    from utils.discovery_capability_ui import build_discovery_capability_payload
    from utils.discovery_lifecycle import LifecycleStore

    lifecycle = LifecycleStore()
    lifecycle.observe("checkpoint", "REAL-SHAPE-PROBE", confidence=50)
    capability = CapabilityStore()
    capability.put(CapabilityProfile(
        vendor="checkpoint", canonical_id="REAL-SHAPE-PROBE",
        shell_type=ShellType.EXPERT, platform_family="gaia",
    ))
    coordinator = CollectionCoordinator()
    coordinator.admit("checkpoint", "checkpoint", ["REAL-SHAPE-PROBE"], provenance=Provenance.MANUAL.value)
    coordinator.release(coordinator.active_jobs()[0].job_id)

    real_payload = build_discovery_capability_payload(lifecycle, capability, coordinator)
    real_entity_keys = set(real_payload["entities"][0])
    real_job_keys = set(real_payload["coordinator"]["recent_jobs"][0])

    html = rendered_html.read_text(encoding="utf-8")
    fixture_payload = _const(html, "discoveryUiData")
    assert fixture_payload["entities"], "fixture must carry at least one entity"
    for entity in fixture_payload["entities"]:
        assert set(entity) == real_entity_keys, (
            f"fixture entity keys {sorted(entity)} != real builder keys {sorted(real_entity_keys)}"
        )
    for job in fixture_payload["coordinator"]["recent_jobs"]:
        assert set(job) == real_job_keys, (
            f"fixture job keys {sorted(job)} != real builder keys {sorted(real_job_keys)}"
        )


@pytest.mark.skipif(_js_runtime() is None, reason="neither node nor bun is installed")
@pytest.mark.skipif(
    not HARNESS_DEPS.exists(),
    reason="render-harness deps missing — run `bun install` (or `npm install`) in tools/render-harness",
)
def test_headless_navigation_smoke(rendered_html):
    """Execute the script in a DOM and click every nav module + inner tab."""
    proc = subprocess.run(
        [_js_runtime(), str(HARNESS), str(rendered_html)],
        capture_output=True, text=True, timeout=120,
    )
    assert proc.returncode == 0, (
        f"render harness failed (exit {proc.returncode})\n"
        f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
    )


@pytest.mark.skipif(
    not _playwright_chromium_available(),
    reason="playwright not installed or no Chromium resolvable — "
           "pip install -r requirements-dev.txt && playwright install chromium",
)
def test_headless_navigation_smoke_playwright(rendered_html):
    """Real-Chromium alternative to test_headless_navigation_smoke, not gated
    on the bun/happy-dom toolchain -- see the module docstring."""
    proc = subprocess.run(
        [sys.executable, str(PLAYWRIGHT_HARNESS), str(rendered_html)],
        capture_output=True, text=True, timeout=120,
    )
    assert proc.returncode == 0, (
        f"playwright render harness failed (exit {proc.returncode})\n"
        f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
    )
