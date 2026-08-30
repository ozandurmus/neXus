"""0.7.6 — automated HTML render harness.

Renders the report from the committed `tests/fixtures/uitest` bundle so every
module is populated, then:
  * (always) checks every embedded `const X = ...;` payload is valid JSON — the
    0.7.4a bug class, catchable with no JS engine;
  * (when `bun` + the harness deps are present) runs
    `tools/render-harness/check-render.mjs`, which executes the script in a DOM
    and clicks every nav module + inner tab, asserting panels switch with no
    console errors.
"""
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

HARNESS = ROOT / "tools" / "render-harness" / "check-render.mjs"
HARNESS_DEPS = ROOT / "tools" / "render-harness" / "node_modules" / "happy-dom"

_PAYLOAD_CONSTS = (
    "rawData", "configUiData", "complianceUiData",
    "cryptoUiData", "projectPlanData", "discoveryUiData",
)


def _bun() -> str | None:
    found = shutil.which("bun")
    if found:
        return found
    fallback = Path(os.path.expanduser("~")) / ".bun" / "bin" / "bun"
    return str(fallback) if fallback.exists() else None


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
        m = re.search(rf"\bconst {re.escape(name)} = (.*?);\n", html)
        assert m, f"{name} not found in the generated <script>"
        json.loads(m.group(1).replace("<\\/", "</"))


def _const(html: str, name: str):
    m = re.search(rf"\bconst {re.escape(name)} = (.*?);\n", html)
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


@pytest.mark.skipif(_bun() is None, reason="bun not installed")
@pytest.mark.skipif(
    not HARNESS_DEPS.exists(),
    reason="render-harness deps missing — run `bun install` in tools/render-harness",
)
def test_headless_navigation_smoke(rendered_html):
    """Execute the script in a DOM and click every nav module + inner tab."""
    proc = subprocess.run(
        [_bun(), str(HARNESS), str(rendered_html)],
        capture_output=True, text=True, timeout=120,
    )
    assert proc.returncode == 0, (
        f"render harness failed (exit {proc.returncode})\n"
        f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
    )
