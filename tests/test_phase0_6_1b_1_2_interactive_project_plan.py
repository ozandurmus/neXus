import json
from pathlib import Path

import pytest

from configuration import checkpoint_config_collector as collector
from utils.project_plan import build_project_plan_payload

ROOT = Path(__file__).resolve().parents[1]
APP = (ROOT / "static" / "app.js").read_text(encoding="utf-8")
CSS = (ROOT / "static" / "style.css").read_text(encoding="utf-8")
TEMPLATE = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")
HTML_EXPORT = (ROOT / "utils" / "html_export.py").read_text(encoding="utf-8")
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")


def _result(stdout="", *, success=True, error_class=None):
    return {
        "success": success,
        "error_class": error_class or ("none" if success else "cli_rejected"),
        "error_detail": None,
        "timeout": False,
        "exit_status": None,
        "duration_ms": 1,
        "stdout": stdout,
        "stderr": "",
    }


class FakeInteractive:
    def __init__(self, responses):
        self.responses = responses
        self.commands = []

    def run(self, command, _timeout):
        self.commands.append(command)
        return self.responses.get(command, _result("Unknown command", success=False))


def test_interactive_shell_detection_proves_direct_clish_before_expert_wrapper():
    session = FakeInteractive({"show hostname": _result("CP-DIRECT-CLISH-TEST\n")})
    mode, result = collector._detect_gaia_shell_interactive(session, 5)
    assert mode == "interactive_direct_clish"
    assert result["success"] is True
    assert session.commands == ["show hostname"]


def test_interactive_shell_detection_proves_expert_via_explicit_clish():
    expert_command = "clish -c 'show hostname'"
    session = FakeInteractive({
        "show hostname": _result("bash: show: command not found", success=False),
        expert_command: _result("FW-CKP-01\n"),
    })
    mode, result = collector._detect_gaia_shell_interactive(session, 5)
    assert mode == "interactive_expert_explicit_clish"
    assert result["success"] is True
    assert session.commands == ["show hostname", expert_command]


def test_interactive_gaia_dispatch_is_read_only_and_reuses_observed_mode():
    session = FakeInteractive({"show configuration": _result("set hostname SPARK1\n")})
    result, mode = collector._run_gaia_interactive_read(
        session, "show configuration", 5, "interactive_direct_clish", require_set_lines=True
    )
    assert result["success"] is True
    assert mode == "interactive_direct_clish"
    assert session.commands == ["show configuration"]
    with pytest.raises(ValueError):
        collector._run_gaia_interactive_read(session, "set hostname bad", 5, "interactive_direct_clish")


def test_collector_identity_can_use_hostname_plus_successful_read_only_config_when_version_surface_is_limited():
    target = collector.ProbeTarget(
        role="standalone_gateway",
        device="CP-DIRECT-CLISH-TEST",
        management_ip="192.0.2.61",
        object_type="gateway",
        cma=None,
        selection_source="management_discovery",
    )
    gate = collector._collector_identity_gate(
        target=target,
        observed_hostname="CP-DIRECT-CLISH-TEST",
        hostname_success=True,
        version_success=False,
        configuration_success=True,
        authenticated=True,
    )
    assert gate["accepted"] is True
    assert gate["confidence"] == "MEDIUM"
    assert gate["acceptance_basis"] == "hostname_plus_read_only_configuration_capability"


def test_project_plan_payload_is_data_driven_and_percentages_are_bounded():
    payload = build_project_plan_payload()
    # current_build is driven by roadmap.json; assert it is set and non-empty.
    assert payload["current_build"]
    assert payload["current_track"] == "0.6.x"
    assert 0 <= payload["overall_progress_percent"] <= 100
    assert 0 < payload["current_track_progress_percent"] < 100
    features = {row["id"]: row for track in payload["tracks"] for row in track["features"]}
    assert features["content_addressed_history"]["progress_percent"] == 100
    assert 0 < features["cp_coverage_closure"]["progress_percent"] < 100
    # 0.6.4 CP SSH strict host-key production closure is contract-frozen.
    assert payload["now_next"]["now"]["build"] == "0.6.4"
    assert "host-key" in payload["now_next"]["next"]["title"].lower()
    assert any(item["id"] == "cp_ssh_trust" for item in payload["backlog"])
    assert any(item["build"] == "0.6.1B.1.1" for item in payload["build_history"])


def test_project_plan_ui_contract_and_export_embedding_are_present():
    for marker in [
        'id="projectPlanNav"',
        'id="projectPlanModule"',
        'id="projectPlanHero"',
        'id="projectRoadmapTracks"',
        'id="projectBacklog"',
        'id="projectCompletedFeatures"',
        'id="projectBuildHistory"',
        "__PROJECT_PLAN_JSON_PLACEHOLDER__",
    ]:
        assert marker in TEMPLATE
    assert "const projectPlanData" in TEMPLATE
    assert "function renderProjectPlan(" in APP
    assert '"project-plan"' in APP
    assert ".project-plan-shell" in CSS
    assert "build_project_plan_payload" in HTML_EXPORT
    assert '"__PROJECT_PLAN_JSON_PLACEHOLDER__"' in HTML_EXPORT


def test_b112_source_contract_uses_interactive_session_for_host_gaia_reads_and_reports_timing():
    source = (ROOT / "configuration" / "checkpoint_config_collector.py").read_text(encoding="utf-8")
    assert "InteractiveSshSession(ssh" in source
    assert "_detect_gaia_shell_interactive" in source
    assert "_run_gaia_interactive_read" in source
    assert 'interactive.run("cphaprob stat"' in source
    assert collector.PHASE == "0.6.1B.1.2"
    assert "0.6.1B.1.2 SAFE COLLECTION SUMMARY" in MAIN
    assert "Collection duration:" in MAIN


def test_project_plan_metadata_has_no_integrity_warnings_and_future_numbering_is_explicitly_provisional():
    payload = build_project_plan_payload()
    assert payload["metadata_warnings"] == []
    assert any("planning map" in note for note in payload["roadmap_notes"])
    assert any(item["id"] == "html_render_performance" for item in payload["backlog"])


def test_configuration_unsupported_is_capability_gap_even_when_platform_is_not_yet_classified():
    result = _result("Unknown command: show configuration", success=False)
    reason, family = collector._configuration_failure_reason(result, "unknown")
    assert reason == "gaia_configuration_capability_unsupported"
    assert family == "capability_gap"


def test_interactive_adapter_requests_wide_pty_and_keeps_exec_fallback_contract():
    source = (ROOT / "configuration" / "checkpoint_config_collector.py").read_text(encoding="utf-8")
    assert 'invoke_shell(term="vt100", width=4096, height=10000)' in source
    assert "if not interactive_mode:" in source
    assert "shell_mode, hostname_result = _detect_gaia_shell(ssh, command_timeout)" in source
