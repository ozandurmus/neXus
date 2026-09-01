from pathlib import Path
import inspect

from lxml import etree

from configuration import panorama_config_collector as pan_collector
from utils.config_ui import build_configuration_ui_payload
import pytest
from utils.html_export import compose_report_script as _composed_report_script

pytestmark = pytest.mark.configuration


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
# codebase_modularization (backend): the mode bodies moved out of main.py into
# the application/ package. Source-level contract checks now read the module
# that owns the block.
CLI = (ROOT / "application" / "cli.py").read_text(encoding="utf-8")
CHECKPOINT_WF = (ROOT / "application" / "workflows" / "checkpoint.py").read_text(encoding="utf-8")
MAINTENANCE_WF = (ROOT / "application" / "workflows" / "maintenance.py").read_text(encoding="utf-8")
CP_SCRIPT = (ROOT / "checkpoint" / "scripts" / "cp_inventory.sh").read_text(encoding="utf-8")
APP = _composed_report_script()


def test_pan_target_ha_runtime_parser_uses_local_runtime_state(monkeypatch):
    response = etree.fromstring(
        b"""<response status='success'><result><enabled>yes</enabled><group>
        <local-info><state>active</state><mode>Active-Passive</mode><state-sync>Complete</state-sync></local-info>
        <peer-info><state>passive</state></peer-info>
        </group></result></response>"""
    )
    seen = {}

    def fake_api_post(host, key, data, *, verify, timeout, operation):
        seen.update(data)
        return response

    monkeypatch.setattr(pan_collector, "api_post", fake_api_post)
    result = pan_collector.get_target_ha_runtime_state(
        "https://panorama.example",
        "secret-key",
        "SER123",
        verify=False,
        timeout=10,
    )

    assert seen["type"] == "op"
    assert seen["target"] == "SER123"
    assert "<show><high-availability><state>" in seen["cmd"]
    assert result == {
        "enabled": "yes",
        "state": "active",
        "mode": "Active-Passive",
        "peer_state": "passive",
        "state_sync": "Complete",
    }


def test_pan_target_ha_runtime_parser_handles_ha_disabled_without_inventing_role(monkeypatch):
    response = etree.fromstring(b"<response status='success'><result><enabled>no</enabled></result></response>")
    monkeypatch.setattr(pan_collector, "api_post", lambda *args, **kwargs: response)
    result = pan_collector.get_target_ha_runtime_state(
        "https://panorama.example", "key", "SER123", verify=False, timeout=10
    )
    assert result["enabled"] == "no"
    assert result["state"] is None
    assert result["peer_state"] is None


def test_workflow_modes_are_explicit_and_render_only_precedes_vendor_imports():
    assert '"--render-only"' in CLI
    assert 'args.only == "pan-config"' in CHECKPOINT_WF
    assert 'args.only == "cp"' in CHECKPOINT_WF
    assert 'args.only == "vsx"' in CHECKPOINT_WF
    assert '_workflow_context("checkpoint", run_id=run_ctx.run_id)' in CHECKPOINT_WF
    assert "MIXED-CYCLE DEVELOPMENT VIEW / NOT A CHECKPOINT" in CHECKPOINT_WF
    # render-only is dispatched before the checkpoint workflow (which owns the
    # vendor imports) is ever entered.
    assert CLI.index("if args.render_only:") < CLI.index("integration_checkpoint(ctx)")
    assert "from checkpoint.cp_runner import run_cp" not in CLI
    assert "NO NETWORK / NO CREDENTIALS / NOT A CHECKPOINT" in MAINTENANCE_WF


def test_full_run_keeps_cp_stage_cooldown_guardrail_contract():
    assert "SECURITYEXPERT_CP_STAGE_COOLDOWN_SECONDS" in CHECKPOINT_WF
    assert '_cp_stage_cooldown("vsx_collect")' in CHECKPOINT_WF
    assert '_cp_stage_cooldown("cp_config")' in CHECKPOINT_WF


def test_cp_stage_cooldown_default_does_not_sleep(monkeypatch):
    import main as main_module
    from application.workflows import checkpoint as _cp_wf

    sleeps = []
    logs = []

    monkeypatch.delenv("SECURITYEXPERT_CP_STAGE_COOLDOWN_SECONDS", raising=False)
    monkeypatch.setattr(main_module.time, "sleep", lambda seconds: sleeps.append(seconds))
    monkeypatch.setattr(_cp_wf, "info", lambda message: logs.append(message))

    main_module._cp_stage_cooldown("vsx_collect")

    assert sleeps == []
    assert any("no stage cooldown configured" in message for message in logs)


def test_cp_stage_cooldown_env_override_is_bounded_and_sleeps(monkeypatch):
    import main as main_module
    from application.workflows import checkpoint as _cp_wf

    sleeps = []
    logs = []

    monkeypatch.setenv("SECURITYEXPERT_CP_STAGE_COOLDOWN_SECONDS", "999")
    monkeypatch.setattr(main_module.time, "sleep", lambda seconds: sleeps.append(seconds))
    monkeypatch.setattr(_cp_wf, "info", lambda message: logs.append(message))

    main_module._cp_stage_cooldown("cp_config")

    assert sleeps == [30]
    assert any("waiting 30s before cp_config" in message for message in logs)


def test_only_cp_has_explicit_non_vsx_remote_scope_and_full_scope_stays_available():
    signature = inspect.signature(__import__("checkpoint.cp_runner", fromlist=["run_cp"]).run_cp)
    assert "exclude_vsx" in signature.parameters
    assert "SECURITYEXPERT_CP_EXCLUDE_VSX" in CP_SCRIPT
    assert "(! vsx_cluster_member='true')" in CP_SCRIPT
    assert "(! vs_cluster_member='true')" in CP_SCRIPT
    assert "(! vsx_netobj='true')" in CP_SCRIPT
    assert "baseline-all-managed-cp" in CP_SCRIPT
    assert 'run_cp(cfg, exclude_vsx=(args.only == "cp"))' in CHECKPOINT_WF


def test_workflow_context_is_embedded_and_frontend_marks_partial_views():
    payload = build_configuration_ui_payload(
        None,
        workflow_context={
            "mode": "render-only",
            "label": "Render only",
            "checkpoint": False,
            "mixed_cycle": True,
        },
    )
    assert payload["workflow"]["mode"] == "render-only"
    assert payload["workflow"]["checkpoint"] is False
    assert "Development view" in APP
    assert "Mixed-cycle artifacts · not a checkpoint" in APP


def _write_json(path: Path, value):
    import json
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def test_only_cp_runs_non_vsx_scope_then_merge_and_render(monkeypatch, tmp_path):
    import sys
    import main as main_module
    # DEV.2.1: _build_runtime_config prompts only when stdin is a TTY.
    monkeypatch.setattr(main_module.sys.stdin, "isatty", lambda: True)
    import checkpoint.cp_runner as cp_runner
    import checkpoint.vsx_runner as vsx_runner
    import panorama.panorama_runtime_runner as pan_runner
    import configuration.panorama_config_collector as config_collector
    import utils.merge as merge_module
    import utils.html_export as html_module

    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("SECURITYEXPERT_RUNTIME_ROOT", str(tmp_path / "runtime"))
    _write_json(tmp_path / "runtime" / "output" / "vsx.json", [])
    _write_json(tmp_path / "runtime" / "output" / "panorama_runtime.json", [])
    _write_json(tmp_path / "runtime" / "output" / "pan_config_telemetry.json", {"summary": {}, "devices": []})

    calls = []

    def fake_cp(cfg, *, exclude_vsx=False):
        calls.append(("cp", exclude_vsx))
        _write_json(tmp_path / "runtime" / "output" / "cp.json", [])
        return {"summary": {}}

    def forbidden(*args, **kwargs):
        raise AssertionError("unrequested collector executed")

    def fake_merge(*args, **kwargs):
        calls.append(("merge", None))
        _write_json(tmp_path / "runtime" / "output" / "unified.json", [])

    def fake_html(*args, **kwargs):
        calls.append(("html", kwargs.get("workflow_context", {}).get("mode")))

    monkeypatch.setattr(cp_runner, "run_cp", fake_cp)
    monkeypatch.setattr(vsx_runner, "run_vsx", forbidden)
    monkeypatch.setattr(pan_runner, "run_panorama_runtime", forbidden)
    monkeypatch.setattr(config_collector, "run_panorama_config_evidence", forbidden)
    monkeypatch.setattr(merge_module, "run_merge", fake_merge)
    monkeypatch.setattr(html_module, "run_html_export", fake_html)
    monkeypatch.setattr("builtins.input", lambda prompt="": "user")
    monkeypatch.setattr("getpass.getpass", lambda prompt="": "password")
    monkeypatch.setattr(sys, "argv", ["main.py", "--only", "cp"])

    main_module.main()

    assert ("cp", True) in calls
    assert ("merge", None) in calls
    assert ("html", "cp") in calls


def test_only_vsx_runs_vsx_parser_then_merge_and_render_without_cp(monkeypatch, tmp_path):
    import sys
    import main as main_module
    # DEV.2.1: _build_runtime_config prompts only when stdin is a TTY.
    monkeypatch.setattr(main_module.sys.stdin, "isatty", lambda: True)
    import checkpoint.cp_runner as cp_runner
    import checkpoint.vsx_runner as vsx_runner
    import checkpoint.vsx_parser as vsx_parser
    import panorama.panorama_runtime_runner as pan_runner
    import configuration.panorama_config_collector as config_collector
    import utils.merge as merge_module
    import utils.html_export as html_module

    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("SECURITYEXPERT_RUNTIME_ROOT", str(tmp_path / "runtime"))
    _write_json(tmp_path / "runtime" / "output" / "cp.json", [])
    _write_json(tmp_path / "runtime" / "output" / "panorama_runtime.json", [])
    _write_json(tmp_path / "runtime" / "output" / "pan_config_telemetry.json", {"summary": {}, "devices": []})

    calls = []

    def fake_vsx(cfg):
        calls.append("vsx")
        _write_json(tmp_path / "output" / "vsx_raw.json", [])
        _write_json(tmp_path / "output" / "vsx_telemetry.json", {})

    def fake_parse(*args, **kwargs):
        calls.append("parse")
        _write_json(tmp_path / "runtime" / "output" / "vsx.json", [])

    def forbidden(*args, **kwargs):
        raise AssertionError("unrequested collector executed")

    def fake_merge(*args, **kwargs):
        calls.append("merge")
        _write_json(tmp_path / "runtime" / "output" / "unified.json", [])

    def fake_html(*args, **kwargs):
        calls.append(kwargs.get("workflow_context", {}).get("mode"))

    monkeypatch.setattr(cp_runner, "run_cp", forbidden)
    monkeypatch.setattr(vsx_runner, "run_vsx", fake_vsx)
    monkeypatch.setattr(vsx_parser, "run_vsx_parse", fake_parse)
    monkeypatch.setattr(pan_runner, "run_panorama_runtime", forbidden)
    monkeypatch.setattr(config_collector, "run_panorama_config_evidence", forbidden)
    monkeypatch.setattr(merge_module, "run_merge", fake_merge)
    monkeypatch.setattr(html_module, "run_html_export", fake_html)
    monkeypatch.setattr("builtins.input", lambda prompt="": "user")
    monkeypatch.setattr("getpass.getpass", lambda prompt="": "password")
    monkeypatch.setattr(sys, "argv", ["main.py", "--only", "vsx"])

    main_module.main()

    assert calls == ["vsx", "parse", "merge", "vsx"]
