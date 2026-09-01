"""OP.0c — failover readiness UI (CLASS 0, read-only).

Contract: docs/design/FAILOVER_ENGINE_ARCHITECTURE.md §9 / §10.1. Domain logic
(verdicts, checks) is entirely owned by `utils.failover.compute_ha_readiness`
(OP.0a, tested in `tests/test_op0a_ha_readiness.py`) — this file proves the
*UI projection* over that domain model preserves its fail-closed semantics,
carries the required framing, and introduces no executable failover surface.
"""
from __future__ import annotations

import json
from pathlib import Path

from utils.failover import (
    VERDICT_INSUFFICIENT,
    VERDICT_NOT_A_FAILOVER_UNIT,
    VERDICT_SAFE,
    VERDICT_UNSAFE,
)
from utils.failover_readiness_ui import (
    CHECK_STATUS_TONES,
    VERDICT_TONES,
    build_failover_readiness_payload,
    extract_cp_ha_runtime,
    extract_pan_ha_runtime,
)

ROOT = Path(__file__).resolve().parents[1]

_CP_CLUSTER_ROWS = [
    {
        "source": "cp",
        "device": "cp-core-01",
        "cluster": "cp-core",
        "inventory_status": {"data_state": "live"},
    },
    {
        "source": "cp",
        "device": "cp-core-02",
        "cluster": "cp-core",
        "inventory_status": {"data_state": "live"},
    },
]

_CP_LOAD_SHARING_ROWS = [
    {
        "source": "cp",
        "device": "cp-ls-01",
        "cluster": "cp-ls",
        "inventory_status": {"data_state": "live"},
    },
    {
        "source": "cp",
        "device": "cp-ls-02",
        "cluster": "cp-ls",
        "inventory_status": {"data_state": "live"},
    },
]


def _cp_telemetry(rows):
    return {"devices": rows}


# --- 1/2/3/4/5: payload preserves fail-closed OP.0a semantics ---------------

def test_payload_renders_from_repository_native_evidence_only():
    """No evidence at all -> every unit still appears, verdict
    INSUFFICIENT_EVIDENCE, never omitted and never invented as ready."""
    payload = build_failover_readiness_payload(_CP_CLUSTER_ROWS)
    assert payload["schema_version"]
    assert payload["source_schema"] == "securityexpert-ha-readiness-v1"
    unit = next(u for u in payload["units"] if u["unit_id"] == "cp-core")
    assert unit["verdict"] == VERDICT_INSUFFICIENT
    assert unit["reason"]
    assert len(unit["checks"]) == 7  # every §4 stop-condition, always present


def test_insufficient_evidence_is_explicit_never_collapsed():
    payload = build_failover_readiness_payload(_CP_CLUSTER_ROWS)
    unit = next(u for u in payload["units"] if u["unit_id"] == "cp-core")
    statuses = {c["status"] for c in unit["checks"]}
    assert "INSUFFICIENT_EVIDENCE" in statuses
    # A missing_evidence label is present for every check the UI cannot
    # evaluate -- the operator is told what evidence closes the gap, not
    # left with a bare "no".
    assert all(c["missing_evidence"] for c in unit["checks"] if c["status"] == "INSUFFICIENT_EVIDENCE")


def test_blocking_reasons_are_visible_and_specific():
    """Split-brain (two active members) must surface as the specific
    UNSAFE reason, not a generic 'not ready'."""
    telemetry = _cp_telemetry([
        {"entity_id": "cp-core-01", "ha_role": "ACTIVE", "ha_cluster_mode": "high_availability"},
        {"entity_id": "cp-core-02", "ha_role": "ACTIVE", "ha_cluster_mode": "high_availability"},
    ])
    payload = build_failover_readiness_payload(
        _CP_CLUSTER_ROWS, checkpoint_config_result=telemetry,
    )
    unit = next(u for u in payload["units"] if u["unit_id"] == "cp-core")
    assert unit["verdict"] == VERDICT_UNSAFE
    assert unit["reason"] == "split_brain_observed"


def test_stale_or_unknown_evidence_never_renders_as_ready():
    """No known repository fixture can reach SAFE_TO_FAILOVER (OP.0a AC-6);
    this asserts the UI-facing payload preserves that -- an all-default,
    zero-evidence fleet must never contain a SAFE_TO_FAILOVER unit."""
    payload = build_failover_readiness_payload(_CP_CLUSTER_ROWS)
    verdicts = {u["verdict"] for u in payload["units"]}
    assert VERDICT_SAFE not in verdicts
    assert payload["summary"][VERDICT_SAFE] == 0


def test_unsupported_capability_load_sharing_is_not_a_failover_unit():
    telemetry = _cp_telemetry([
        {"entity_id": "cp-ls-01", "ha_role": "ACTIVE", "ha_cluster_mode": "load_sharing_multicast"},
        {"entity_id": "cp-ls-02", "ha_role": "ACTIVE", "ha_cluster_mode": "load_sharing_multicast"},
    ])
    payload = build_failover_readiness_payload(
        _CP_LOAD_SHARING_ROWS, checkpoint_config_result=telemetry,
    )
    unit = next(u for u in payload["units"] if u["unit_id"] == "cp-ls")
    assert unit["verdict"] == VERDICT_NOT_A_FAILOVER_UNIT
    # Rendered with a neutral tone, never the green "success" tone SAFE gets.
    assert VERDICT_TONES[unit["verdict"]] != "success"


def test_framing_note_and_execution_unavailable_note_are_present():
    payload = build_failover_readiness_payload(_CP_CLUSTER_ROWS)
    assert "INSUFFICIENT_EVIDENCE" in payload["framing_note"]
    assert "SAFE_TO_FAILOVER" in payload["framing_note"]
    assert payload["execution_unavailable_note"]


def test_no_verdict_or_check_status_is_missing_a_tone_label():
    """Every value compute_ha_readiness can emit must have a UI tone/label --
    an unmapped value would silently fall back to 'neutral', which is exactly
    how a real blocker could visually read as harmless."""
    from utils.failover import CHECK_FAIL, CHECK_INSUFFICIENT, CHECK_PASS, STOP_CONDITIONS
    from utils.failover.assessment import (
        VERDICT_DEGRADED,
        VERDICT_UNSAFE as _VERDICT_UNSAFE,
    )
    payload = build_failover_readiness_payload(_CP_CLUSTER_ROWS)
    for verdict in (VERDICT_SAFE, VERDICT_DEGRADED, _VERDICT_UNSAFE, VERDICT_INSUFFICIENT, VERDICT_NOT_A_FAILOVER_UNIT):
        assert verdict in payload["verdict_labels"]
        assert verdict in payload["verdict_tones"]
    for status in (CHECK_PASS, CHECK_FAIL, CHECK_INSUFFICIENT):
        assert status in payload["check_status_labels"]
        assert status in payload["check_status_tones"]
    assert len(STOP_CONDITIONS) == 7


# --- extractor parity (application/workflows/failover.py must call these) --

def test_cp_extractor_is_pure_and_matches_cli_loader_shape():
    doc = _cp_telemetry([{"entity_id": "cp-core-01", "ha_role": "ACTIVE", "ha_cluster_mode": "high_availability"}])
    runtime = extract_cp_ha_runtime(doc)
    assert runtime == {"cp-core-01": {"ha_role": "ACTIVE", "ha_cluster_mode": "high_availability"}}
    assert extract_cp_ha_runtime(None) == {}
    assert extract_cp_ha_runtime({"devices": "not-a-list-of-dicts"}) == {}


def test_pan_extractor_is_pure_and_matches_cli_loader_shape():
    doc = {"devices": [{"entity_id": "pan-01", "ha_runtime": {
        "enabled": "yes", "state": "active", "mode": "active-passive",
        "peer_state": "passive", "state_sync": "in-sync", "peer_ip": "192.0.2.111",
    }}]}
    runtime, peers = extract_pan_ha_runtime(doc)
    assert runtime["pan-01"]["state"] == "active"
    assert peers["pan-01"] == "192.0.2.111"
    assert extract_pan_ha_runtime(None) == ({}, {})


def test_application_workflow_loaders_delegate_to_the_shared_extractors(tmp_path):
    """application/workflows/failover.py must not carry a second, divergent
    implementation of the extraction logic (the refactor this build made)."""
    from application.workflows.failover import _load_cp_ha_runtime, _load_pan_ha_runtime

    (tmp_path / "cp_config_telemetry.json").write_text(
        json.dumps(_cp_telemetry([{"entity_id": "cp-x", "ha_role": "STANDBY", "ha_cluster_mode": "high_availability"}])),
        encoding="utf-8",
    )
    assert _load_cp_ha_runtime(tmp_path) == {"cp-x": {"ha_role": "STANDBY", "ha_cluster_mode": "high_availability"}}
    # Missing pan_config_telemetry.json -> fail-safe empty, never an error.
    assert _load_pan_ha_runtime(tmp_path) == ({}, {})


# --- 6/8: no executable failover control anywhere in the shipped surface ---

_FORBIDDEN_LABELS = ("Prepare Failover", "Authorise", "Execute", "Force Active", "Make Standby", "Change Role")


def test_no_execution_control_markup_in_either_template():
    for template in ("templates/console.html", "templates/index.html"):
        html = (ROOT / template).read_text(encoding="utf-8")
        failover_section = html.split('id="failoverModule"', 1)[1].split("</section>", 1)[0]
        for label in _FORBIDDEN_LABELS:
            assert label not in failover_section, f"{template} failover module contains forbidden control label {label!r}"
        assert "<button" not in failover_section, f"{template} failover module must contain no button (no execution affordance)"


def test_failover_js_module_makes_no_network_call_and_defines_no_submit_path():
    source = (ROOT / "static" / "failover_readiness_ui.js").read_text(encoding="utf-8")
    for forbidden in ("fetch(", "/api/jobs", "XMLHttpRequest", "job_type", "targets:"):
        assert forbidden not in source, f"failover_readiness_ui.js must not submit anything ({forbidden!r} found)"


# --- 7: OP.0c introduces no CLASS 2 job type --------------------------------

def test_failover_ui_registers_no_console_job_type():
    from console.registry import JOB_REGISTRY

    assert not any("failover" in job_id for job_id in JOB_REGISTRY), (
        "OP.0c is read-only visibility; it must not register a console job type"
    )
    assert all(jt.action_class.level < 2 for jt in JOB_REGISTRY.values())


def test_failover_readiness_data_key_present_in_report_payloads():
    from utils.html_export import build_report_payloads

    unified = ROOT / "tests" / "fixtures" / "uitest" / "unified.json"
    payloads = build_report_payloads(unified, repository_root=ROOT)
    assert "failoverReadinessData" in payloads
    assert isinstance(payloads["failoverReadinessData"]["units"], list)


def test_check_status_tones_never_map_insufficient_or_fail_to_success():
    assert CHECK_STATUS_TONES["FAIL"] != "success"
    assert CHECK_STATUS_TONES["INSUFFICIENT_EVIDENCE"] != "success"


# --- cluster-centric identity passthrough (2026-09-02 real-env correction) --

def test_payload_carries_cluster_topology_display_name_and_parent_id():
    """The UI payload passes units through verbatim -- prove the new
    cluster-centric fields (real pipeline shape) reach it unchanged."""
    rows = [
        {"source": "cp", "device": "FW-1", "cluster_topology": {"group_id": "grp-1", "display_name": "FW-CLS"},
         "inventory_status": {"data_state": "live"}},
        {"source": "cp", "device": "FW-2", "cluster_topology": {"group_id": "grp-1", "display_name": "FW-CLS"},
         "inventory_status": {"data_state": "live"}},
    ]
    payload = build_failover_readiness_payload(rows)
    unit = next(u for u in payload["units"] if u["unit_id"] == "grp-1")
    assert unit["display_name"] == "FW-CLS"
    assert unit["parent_id"] is None
    assert sorted(unit["members"]) == ["FW-1", "FW-2"]


def test_payload_links_vsx_virtual_system_to_its_physical_parent():
    rows = [
        {"source": "vsx", "device": "VSX-1", "cluster_topology": {"group_id": "grp-vsx", "display_name": "VSX-CLS"},
         "inventory_status": {"data_state": "live"}},
        {"source": "vsx", "device": "VSX-2", "cluster_topology": {"group_id": "grp-vsx", "display_name": "VSX-CLS"},
         "inventory_status": {"data_state": "live"}},
        {"source": "vsx", "device": "VSX-1", "vs_id": "10", "inventory_status": {"data_state": "live"}},
    ]
    payload = build_failover_readiness_payload(rows)
    parent = next(u for u in payload["units"] if u["unit_id"] == "grp-vsx")
    child = next(u for u in payload["units"] if u["unit_id"] == "grp-vsx__vsid_10")
    assert parent["parent_id"] is None
    assert child["parent_id"] == parent["unit_id"]
    assert child["unit_type"] == "cp_vsx_virtual_system"


def test_no_top_level_unit_falsely_represents_a_vsx_virtual_system_as_independent_gateway():
    """A VS's parent_id must always name a real unit_id in the same payload,
    or be None -- never a dangling/invented reference."""
    rows = [
        {"source": "vsx", "device": "VSX-1", "cluster_topology": {"group_id": "grp-vsx"},
         "inventory_status": {"data_state": "live"}},
        {"source": "vsx", "device": "VSX-1", "vs_id": "10", "inventory_status": {"data_state": "live"}},
    ]
    payload = build_failover_readiness_payload(rows)
    unit_ids = {u["unit_id"] for u in payload["units"]}
    for unit in payload["units"]:
        if unit["parent_id"] is not None:
            assert unit["parent_id"] in unit_ids


def test_failover_js_renders_children_nested_never_as_top_level_rows():
    """Static-source guard: the JS must filter by parent_id before building
    the top-level row set, and must not re-flatten children back in."""
    source = (ROOT / "static" / "failover_readiness_ui.js").read_text(encoding="utf-8")
    assert "parent_id" in source
    assert "filter(unit => !unit.parent_id)" in source
    assert "childrenByParent" in source
