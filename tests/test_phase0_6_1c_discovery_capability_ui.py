"""Tests for Phase 3 discovery/capability/coordinator UI payload and wiring — 0.6.1C."""
from pathlib import Path

from utils.discovery_capability_ui import build_discovery_capability_payload
from utils.discovery_lifecycle import LifecycleStore, LifecycleState, TransitionReason, transition
from utils.capability_registry import CapabilityProfile, CapabilityStore, ShellType
from utils.collection_executor import (
    CollectionCoordinator,
    Provenance,
    SchedulerPolicy,
    ScheduledWorkflow,
)


ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")
APP = (ROOT / "static" / "app.js").read_text(encoding="utf-8")
CSS = (ROOT / "static" / "style.css").read_text(encoding="utf-8")
HTML_EXPORT = (ROOT / "utils" / "html_export.py").read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# Empty state
# ---------------------------------------------------------------------------

def test_empty_payload_has_explicit_empty_state():
    payload = build_discovery_capability_payload()
    assert payload["fleet_summary"]["total_entities"] == 0
    assert payload["entities"] == []
    assert payload["coordinator"]["available"] is False
    assert payload["scheduler"]["configured"] is False


# ---------------------------------------------------------------------------
# Populated lifecycle/capability
# ---------------------------------------------------------------------------

def test_payload_reflects_lifecycle_and_capability():
    lifecycle = LifecycleStore()
    lifecycle.observe("checkpoint", "DEV-A", confidence=60)

    capability = CapabilityStore()
    capability.put(CapabilityProfile(
        vendor="checkpoint", canonical_id="DEV-A",
        shell_type=ShellType.EXPERT, confidence=80,
    ))

    payload = build_discovery_capability_payload(lifecycle, capability)
    assert payload["fleet_summary"]["total_entities"] == 1
    row = payload["entities"][0]
    assert row["vendor"] == "checkpoint"
    assert row["canonical_id"] == "DEV-A"
    assert row["lifecycle_state"] == "DISCOVERED"
    assert row["shell_type"] == "expert"
    # Expert shell allowed once validated is not required by planner logic itself;
    # DISCOVERED + expert shell (no identity failure) is allowed.
    assert row["plan_allowed"] is True
    assert row["planned_mode"] == "expert_explicit_clish"


def test_deferred_count_reflects_excluded_entities():
    lifecycle = LifecycleStore()
    r = lifecycle.observe("checkpoint", "DEV-B")
    excluded = transition(r, LifecycleState.EXCLUDED, reason=TransitionReason.RUNTIME_POLICY_EXCLUDE.value)
    lifecycle.put(excluded)

    payload = build_discovery_capability_payload(lifecycle, CapabilityStore())
    assert payload["fleet_summary"]["deferred_count"] == 1
    assert payload["entities"][0]["plan_allowed"] is False


def test_missing_capability_profile_defaults_to_unknown_shell():
    lifecycle = LifecycleStore()
    lifecycle.observe("paloalto", "PAN-DEV-C")
    payload = build_discovery_capability_payload(lifecycle, CapabilityStore())
    row = payload["entities"][0]
    assert row["shell_type"] == "unknown"
    assert row["plan_allowed"] is False  # UNKNOWN shell is never guessed into an allowed plan


# ---------------------------------------------------------------------------
# Coordinator section — no identity leakage
# ---------------------------------------------------------------------------

def test_coordinator_section_available_and_sanitized():
    coord = CollectionCoordinator()
    coord.admit("checkpoint", "checkpoint", ["REAL-DEVICE-SECRET-NAME"], provenance=Provenance.MANUAL.value)

    payload = build_discovery_capability_payload(coordinator=coord)
    section = payload["coordinator"]
    assert section["available"] is True
    assert section["active_job_count"] == 1
    assert "checkpoint" in section["budgets"]
    # canonical_ids must never leak into recent_jobs rows.
    assert "REAL-DEVICE-SECRET-NAME" not in str(section["recent_jobs"])


def test_coordinator_budget_snapshot_present():
    coord = CollectionCoordinator()
    payload = build_discovery_capability_payload(coordinator=coord)
    budgets = payload["coordinator"]["budgets"]
    assert budgets["checkpoint"]["capacity"] == 1
    assert budgets["checkpoint"]["available"] == 1


# ---------------------------------------------------------------------------
# Scheduler section
# ---------------------------------------------------------------------------

def test_scheduler_section_reflects_policy():
    policy = SchedulerPolicy(
        source="test",
        enabled=True,
        workflows=(ScheduledWorkflow(workflow="checkpoint", interval_minutes=60),),
    )
    payload = build_discovery_capability_payload(scheduler_policy=policy)
    section = payload["scheduler"]
    assert section["configured"] is True
    assert section["enabled"] is True
    assert section["workflow_count"] == 1


def test_scheduler_section_default_disabled_when_no_policy():
    payload = build_discovery_capability_payload()
    assert payload["scheduler"]["configured"] is False
    assert payload["scheduler"]["enabled"] is False


# ---------------------------------------------------------------------------
# UI wiring contract (additive markers)
# ---------------------------------------------------------------------------

def test_template_has_discovery_module_markers():
    for marker in [
        'id="discoveryNav"',
        'id="discoveryModule"',
        'id="discoveryFleetSummary"',
        'id="discoveryCoordinator"',
        'id="discoveryScheduler"',
        'id="discoveryEntityTable"',
        'id="discoveryRecentJobs"',
        "__DISCOVERY_JSON_PLACEHOLDER__",
    ]:
        assert marker in TEMPLATE


def test_template_preserves_existing_module_markers():
    """Phase 3 additions must not remove pre-existing module markers."""
    for marker in [
        'id="complianceNav"',
        'id="projectPlanNav"',
        'id="projectPlanModule"',
        "__PROJECT_PLAN_JSON_PLACEHOLDER__",
        "__COMPLIANCE_JSON_PLACEHOLDER__",
    ]:
        assert marker in TEMPLATE


def test_app_js_has_discovery_render_function_and_wiring():
    assert "function renderDiscoveryModule()" in APP
    assert '"discovery"' in APP
    assert "renderDiscoveryModule();" in APP


def test_html_export_wires_discovery_payload():
    assert "build_discovery_capability_payload" in HTML_EXPORT
    assert "__DISCOVERY_JSON_PLACEHOLDER__" in HTML_EXPORT
    assert "lifecycle_store" in HTML_EXPORT
    assert "coordinator" in HTML_EXPORT


def test_style_css_has_generic_table_classes():
    assert ".table-wrap" in CSS
    assert ".data-table" in CSS


# ---------------------------------------------------------------------------
# End-to-end render smoke test — no leftover placeholder, valid embed
# ---------------------------------------------------------------------------

def test_run_html_export_embeds_discovery_payload_without_leftover_placeholder(tmp_path):
    from utils.html_export import run_html_export

    unified = tmp_path / "unified.json"
    unified.write_text("[]", encoding="utf-8")
    output_html = tmp_path / "index.html"

    lifecycle = LifecycleStore()
    lifecycle.observe("checkpoint", "DEV-Z", confidence=55)
    capability = CapabilityStore()
    coord = CollectionCoordinator()

    run_html_export(
        unified_json=unified,
        output_html=output_html,
        lifecycle_store=lifecycle,
        capability_store=capability,
        coordinator=coord,
    )

    html = output_html.read_text(encoding="utf-8")
    assert "__DISCOVERY_JSON_PLACEHOLDER__" not in html
    assert "discoveryUiData" in html
    assert "DEV-Z" in html  # sanitized canonical_id, consistent with other inventory UI modules

