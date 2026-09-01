"""Tests for the Inventory Exclusions UI payload and wiring — 0.6.1C phase 1.

Contract: docs/history/phase/PHASE0_6_1C_INVENTORY_EXCLUSIONS_UI.md. Phase 1
is strictly read-only; there is no write path under test here.
"""
from pathlib import Path

from utils.inventory_exclusions import InventoryExclusion, InventoryExclusionPolicy
from utils.inventory_exclusions_ui import build_inventory_exclusions_payload
import pytest
from utils.html_export import compose_report_script as _composed_report_script

pytestmark = pytest.mark.discovery


ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")
APP = _composed_report_script()
CSS = (ROOT / "static" / "style.css").read_text(encoding="utf-8")
HTML_EXPORT = (ROOT / "utils" / "html_export.py").read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# Payload builder — empty state (AC-3)
# ---------------------------------------------------------------------------

def test_no_policy_argument_has_explicit_empty_state():
    payload = build_inventory_exclusions_payload()
    assert payload["source"] == "missing"
    assert payload["fleet_summary"]["total_exclusions"] == 0
    assert payload["entities"] == []


def test_policy_with_missing_source_and_no_entries_has_explicit_empty_state():
    policy = InventoryExclusionPolicy(source="missing", entries=())
    payload = build_inventory_exclusions_payload(policy)
    assert payload["source"] == "missing"
    assert payload["fleet_summary"]["total_exclusions"] == 0
    assert payload["entities"] == []


# ---------------------------------------------------------------------------
# Payload builder — populated (AC-1)
# ---------------------------------------------------------------------------

def test_payload_reflects_entries_and_per_vendor_counts():
    policy = InventoryExclusionPolicy(
        source="runtime-policy",
        entries=(
            InventoryExclusion(vendor="checkpoint", identity="fake-jump-host-01", reason="not a firewall"),
            InventoryExclusion(vendor="checkpoint", identity="fake-decoy-02", reason="manual"),
            InventoryExclusion(vendor="paloalto", identity="fake-lab-fw-03", reason="lab device"),
        ),
    )
    payload = build_inventory_exclusions_payload(policy)
    assert payload["source"] == "runtime-policy"
    assert payload["fleet_summary"]["total_exclusions"] == 3
    assert payload["fleet_summary"]["vendor_counts"] == {"checkpoint": 2, "paloalto": 1}
    assert len(payload["entities"]) == 3
    row = next(r for r in payload["entities"] if r["identity"] == "fake-jump-host-01")
    assert row["vendor"] == "checkpoint"
    assert row["reason"] == "not a firewall"


def test_payload_entities_are_sorted_by_vendor_then_identity():
    policy = InventoryExclusionPolicy(
        source="runtime-policy",
        entries=(
            InventoryExclusion(vendor="paloalto", identity="z-device", reason="manual"),
            InventoryExclusion(vendor="checkpoint", identity="b-device", reason="manual"),
            InventoryExclusion(vendor="checkpoint", identity="a-device", reason="manual"),
        ),
    )
    payload = build_inventory_exclusions_payload(policy)
    ordered = [(r["vendor"], r["identity"]) for r in payload["entities"]]
    assert ordered == [
        ("checkpoint", "a-device"),
        ("checkpoint", "b-device"),
        ("paloalto", "z-device"),
    ]


# ---------------------------------------------------------------------------
# Privacy contract (AC-5) — only vendor/identity/reason, nothing else
# ---------------------------------------------------------------------------

def test_payload_carries_only_vendor_identity_and_reason_per_entry():
    policy = InventoryExclusionPolicy(
        source="runtime-policy",
        entries=(InventoryExclusion(vendor="checkpoint", identity="fake-device", reason="manual"),),
    )
    payload = build_inventory_exclusions_payload(policy)
    assert set(payload["entities"][0]) == {"vendor", "identity", "reason"}


def test_payload_has_no_credential_or_ip_shaped_fields():
    payload = build_inventory_exclusions_payload()
    assert "management_ip" not in str(payload)
    assert "credential" not in str(payload).lower()
    assert "secret" not in str(payload).lower()


# ---------------------------------------------------------------------------
# UI wiring contract (additive markers, AC-1/AC-4)
# ---------------------------------------------------------------------------

def test_template_has_exclusions_module_markers():
    for marker in [
        'id="exclusionsNav"',
        'id="exclusionsModule"',
        'id="exclusionsFleetSummary"',
        'id="exclusionsEntityTable"',
        "__EXCLUSIONS_JSON_PLACEHOLDER__",
    ]:
        assert marker in TEMPLATE


def test_template_preserves_existing_module_markers():
    """The new module must not remove any pre-existing module marker."""
    for marker in [
        'id="discoveryNav"',
        'id="discoveryModule"',
        'id="projectPlanNav"',
        'id="projectPlanModule"',
        "__DISCOVERY_JSON_PLACEHOLDER__",
        "__PROJECT_PLAN_JSON_PLACEHOLDER__",
    ]:
        assert marker in TEMPLATE


def test_app_js_has_exclusions_render_function_and_wiring():
    assert "function renderExclusionsModule()" in APP
    assert '"exclusions"' in APP
    assert "renderExclusionsModule();" in APP


def test_html_export_wires_exclusions_payload():
    assert "build_inventory_exclusions_payload" in HTML_EXPORT
    assert "__EXCLUSIONS_JSON_PLACEHOLDER__" in HTML_EXPORT
    assert "load_inventory_exclusions" in HTML_EXPORT


def test_style_css_has_generic_table_classes():
    assert ".table-wrap" in CSS
    assert ".data-table" in CSS


# ---------------------------------------------------------------------------
# End-to-end render smoke test — no leftover placeholder, valid embed,
# and a malformed local policy degrades to empty state rather than crashing
# report rendering (AC-3, without weakening cp_runner.py's fail-closed gate).
# ---------------------------------------------------------------------------

def test_run_html_export_embeds_exclusions_payload_without_leftover_placeholder(tmp_path):
    from utils.html_export import run_html_export

    unified = tmp_path / "unified.json"
    unified.write_text("[]", encoding="utf-8")
    output_html = tmp_path / "index.html"

    data_root = tmp_path / "data"
    state_dir = data_root / "state"
    state_dir.mkdir(parents=True)
    (state_dir / "inventory_exclusions.json").write_text(
        '{"version": 1, "exclusions": ['
        '{"vendor": "checkpoint", "identity": "fake-excluded-01", "reason": "decoy"}'
        ']}',
        encoding="utf-8",
    )

    run_html_export(
        unified_json=unified,
        output_html=output_html,
        data_root=data_root,
    )

    html = output_html.read_text(encoding="utf-8")
    assert "__EXCLUSIONS_JSON_PLACEHOLDER__" not in html
    assert "exclusionsUiData" in html
    assert "fake-excluded-01" in html


def test_run_html_export_malformed_policy_degrades_to_empty_state_not_a_crash(tmp_path):
    from utils.html_export import run_html_export

    unified = tmp_path / "unified.json"
    unified.write_text("[]", encoding="utf-8")
    output_html = tmp_path / "index.html"

    data_root = tmp_path / "data"
    state_dir = data_root / "state"
    state_dir.mkdir(parents=True)
    (state_dir / "inventory_exclusions.json").write_text("not valid json", encoding="utf-8")

    # Must not raise -- render degrades to the exclusions payload's own
    # explicit empty state. cp_runner.py's own collection-time load remains
    # fail-closed and unaffected by this rendering-path degradation.
    run_html_export(
        unified_json=unified,
        output_html=output_html,
        data_root=data_root,
    )

    html = output_html.read_text(encoding="utf-8")
    assert "__EXCLUSIONS_JSON_PLACEHOLDER__" not in html
    assert "exclusionsUiData" in html
