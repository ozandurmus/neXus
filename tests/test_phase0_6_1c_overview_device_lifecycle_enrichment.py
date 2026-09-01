"""Tests for Overview device-family enrichment — 0.6.1C Inventory UX, increment 1.

Contract: docs/history/phase/PHASE0_6_1C_OVERVIEW_DEVICE_LIFECYCLE_ENRICHMENT.md.
Purely client-side aggregation over the already-embedded `configUiData` --
no new Python payload builder, no new sentinel, no schema bump. These tests
therefore check (a) the wiring/markers via source-string assertions, matching
the established pattern for JS-only UI additions in this repo, and (b) the
actual aggregation logic via a real JS engine (bun), skipped cleanly when bun
is unavailable.
"""
import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest
from utils.html_export import compose_report_script as _composed_report_script

pytestmark = pytest.mark.discovery


ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")
APP = _composed_report_script()


def _bun() -> str | None:
    found = shutil.which("bun")
    if found:
        return found
    fallback = Path(os.path.expanduser("~")) / ".bun" / "bin" / "bun"
    return str(fallback) if fallback.exists() else None


# ---------------------------------------------------------------------------
# Wiring contract (additive markers)
# ---------------------------------------------------------------------------

def test_template_has_overview_device_families_marker():
    assert 'id="overviewDeviceFamilies"' in TEMPLATE


def test_template_preserves_existing_overview_markers():
    for marker in [
        'id="overviewHeroCards"',
        'id="overviewAlignmentSummary"',
        'id="overviewEvidenceSummary"',
        'id="overviewComplianceSummary"',
        'id="overviewBackupSummary"',
    ]:
        assert marker in TEMPLATE


def test_app_js_has_device_families_helper_and_render_wiring():
    assert "function deviceLifecycleFamilies()" in APP
    assert "overviewDeviceFamilies" in APP


def test_no_new_json_placeholder_or_python_wiring_needed():
    """Increment 1 is pure client-side aggregation over the already-embedded
    configUiData -- confirms no accidental new sentinel was introduced.
    """
    assert "__DEVICE_FAMILIES_JSON_PLACEHOLDER__" not in TEMPLATE
    html_export = (ROOT / "utils" / "html_export.py").read_text(encoding="utf-8")
    assert "device_lifecycle" not in html_export.lower()
    assert "deviceLifecycleFamilies" not in html_export


# ---------------------------------------------------------------------------
# Privacy contract (AC-2): no per-device hostname/serial exposure
# ---------------------------------------------------------------------------

def test_render_function_never_reads_serial_or_management_ip_fields():
    """deviceLifecycleFamilies()/its render call must only ever touch vendor,
    model, sw_version and entity_type -- never serial/management_ip/name/id,
    which stay a Configuration-module concern.
    """
    start = APP.index("function deviceLifecycleFamilies()")
    end = APP.index("\n}\n", start) + 3
    body = APP[start:end]
    for forbidden in ("serial", "management_ip", "device_name", "\"id\"", ".name"):
        assert forbidden not in body, f"{forbidden!r} must not appear in deviceLifecycleFamilies()"


# ---------------------------------------------------------------------------
# Aggregation correctness (AC-1) -- real JS engine, no DOM needed
# ---------------------------------------------------------------------------

@pytest.mark.skipif(_bun() is None, reason="bun not installed")
def test_device_lifecycle_families_aggregates_by_vendor_model_version(tmp_path):
    """Runs the real deviceLifecycleFamilies() function (extracted from
    static/app.js, no DOM/happy-dom needed) against a synthetic device list.
    """
    import re

    match = re.search(r"function deviceLifecycleFamilies\(\)[\s\S]*?\n}", APP)
    assert match, "deviceLifecycleFamilies() not found in static/app.js"

    script = tmp_path / "verify.mjs"
    script.write_text(
        f"""
const configUiData = {{
  devices: [
    {{ vendor: "Check Point", model: "6500", sw_version: "R82", entity_type: "clusterxl_member" }},
    {{ vendor: "Check Point", model: "6500", sw_version: "R82", entity_type: "clusterxl_member" }},
    {{ vendor: "Check Point", model: "6500", sw_version: "R81.20", entity_type: "vsx_host" }},
    {{ vendor: "Check Point", model: "Spark 1600", sw_version: "R81.10", entity_type: "standalone_gateway" }},
    {{ vendor: "Palo Alto Networks", model: "PA-3220", sw_version: "11.1.2" }},
    // virtual_system rows must never inflate the physical device count.
    {{ vendor: "Check Point", model: "6500", sw_version: "R81.20", entity_type: "virtual_system" }},
  ],
}};
const deviceLifecycleFamilies = new Function(
    "configUiData",
    {json.dumps(match.group(0).replace("function deviceLifecycleFamilies()", "", 1))}
    + "\\nreturn deviceLifecycleFamilies();"
);
const result = deviceLifecycleFamilies(configUiData);
console.log(JSON.stringify(result));
""",
        encoding="utf-8",
    )
    proc = subprocess.run([_bun(), str(script)], capture_output=True, text=True, timeout=30)
    assert proc.returncode == 0, proc.stderr
    result = json.loads(proc.stdout.strip())

    # 4 distinct (vendor, model, sw_version) families -- the virtual_system
    # row is excluded, never counted as a 5th/duplicate entry.
    assert len(result) == 4
    total_devices = sum(row["count"] for row in result)
    assert total_devices == 5  # 6 rows minus the excluded virtual_system

    top = result[0]
    assert top["vendor"] == "Check Point"
    assert top["model"] == "6500"
    assert top["sw_version"] == "R82"
    assert top["count"] == 2  # highest count sorts first

    unknown_family = next((r for r in result if r["model"] == "PA-3220"), None)
    assert unknown_family is not None
    assert unknown_family["count"] == 1


@pytest.mark.skipif(_bun() is None, reason="bun not installed")
def test_device_lifecycle_families_handles_missing_fields_without_crashing(tmp_path):
    import re

    match = re.search(r"function deviceLifecycleFamilies\(\)[\s\S]*?\n}", APP)
    assert match

    script = tmp_path / "verify_empty.mjs"
    script.write_text(
        f"""
const configUiData = {{ devices: [] }};
const deviceLifecycleFamilies = new Function(
    "configUiData",
    {json.dumps(match.group(0).replace("function deviceLifecycleFamilies()", "", 1))}
    + "\\nreturn deviceLifecycleFamilies();"
);
console.log(JSON.stringify(deviceLifecycleFamilies(configUiData)));

const configUiDataUndefined = {{}};
const deviceLifecycleFamilies2 = new Function(
    "configUiData",
    {json.dumps(match.group(0).replace("function deviceLifecycleFamilies()", "", 1))}
    + "\\nreturn deviceLifecycleFamilies();"
);
console.log(JSON.stringify(deviceLifecycleFamilies2(configUiDataUndefined)));
""",
        encoding="utf-8",
    )
    proc = subprocess.run([_bun(), str(script)], capture_output=True, text=True, timeout=30)
    assert proc.returncode == 0, proc.stderr
    lines = proc.stdout.strip().splitlines()
    assert json.loads(lines[0]) == []
    assert json.loads(lines[1]) == []


# ---------------------------------------------------------------------------
# uitest fixture already exercises >=2 distinct families (AC-5) -- confirmed
# against the real fixture used by the render harness, no fixture change
# needed for this build.
# ---------------------------------------------------------------------------

def test_uitest_fixture_has_at_least_two_distinct_device_families():
    fixture = json.loads(
        (ROOT / "tests" / "fixtures" / "uitest" / "configuration_ui.json").read_text(encoding="utf-8")
    )
    families = {
        (d.get("vendor"), d.get("model"), d.get("sw_version"))
        for d in fixture.get("devices", [])
        if d.get("entity_type") != "virtual_system"
    }
    assert len(families) >= 2
