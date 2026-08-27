from configuration import checkpoint_config_collector as collector


def _result(stdout="", *, success=True, stderr="", error_class=None, timeout=False):
    return {
        "success": success,
        "error_class": error_class or ("none" if success else "command_error"),
        "error_detail": None,
        "timeout": timeout,
        "exit_status": 0 if success else 1,
        "duration_ms": 1,
        "stdout": stdout,
        "stderr": stderr,
    }


def test_shell_detection_prefers_proven_direct_clish_without_prompt_parsing(monkeypatch):
    commands = []
    def fake_exec(_ssh, command, _timeout):
        commands.append(command)
        return _result("FW-SPARK-01\n")
    monkeypatch.setattr(collector, "_run_exec", fake_exec)
    mode, result = collector._detect_gaia_shell(object(), 5)
    assert mode == "direct_login_clish"
    assert result["success"] is True
    assert commands == ["show hostname"]


def test_shell_detection_falls_back_to_explicit_clish_for_expert_login(monkeypatch):
    commands = []
    def fake_exec(_ssh, command, _timeout):
        commands.append(command)
        if command == "show hostname":
            return _result("bash: show: command not found", success=False, error_class="command_error")
        return _result("FW-GAIA-01\n")
    monkeypatch.setattr(collector, "_run_exec", fake_exec)
    mode, result = collector._detect_gaia_shell(object(), 5)
    assert mode == "expert_explicit_clish"
    assert result["success"] is True
    assert commands[0] == "show hostname"
    assert commands[1].startswith("clish -c")


def test_observed_shell_mode_dispatches_all_followup_reads(monkeypatch):
    commands = []
    def fake_exec(_ssh, command, _timeout):
        commands.append(command)
        return _result("set hostname FW1\n")
    monkeypatch.setattr(collector, "_run_exec", fake_exec)
    result, mode = collector._run_gaia_read_mode(object(), "show configuration", 5, "direct_login_clish", require_set_lines=True)
    assert result["success"] is True
    assert mode == "direct_login_clish"
    assert commands == ["show configuration"]


def test_version_capability_uses_show_version_when_show_version_all_is_not_supported(monkeypatch):
    commands = []
    def fake_exec(_ssh, command, _timeout):
        commands.append(command)
        if command == "show version all":
            return _result("Unknown command", success=False, error_class="cli_rejected")
        return _result("Check Point Gaia Embedded R81.10\n")
    monkeypatch.setattr(collector, "_run_exec", fake_exec)
    result, mode, command = collector._run_gaia_first_supported(
        object(), ("show version all", "show version"), 5, "direct_login_clish"
    )
    assert result["success"] is True
    assert mode == "direct_login_clish"
    assert command == "show version"
    assert commands == ["show version all", "show version"]


def test_asset_semantic_parser_recovers_model_and_serial_from_generic_identity_keys():
    sample = """
Hardware Appliance Model     1900
Chassis Serial ID            SPARK-ABC-123
"""
    assert collector._parse_asset_semantic(sample, "model") == "1900"
    assert collector._parse_asset_semantic(sample, "serial") == "SPARK-ABC-123"


def test_phase_and_method_mark_adaptive_shell_contract():
    assert collector.PHASE == "0.6.1B.1.2"
    assert collector.COLLECTOR_VERSION == "0.6.1B.1.2"
    assert "interactive" in collector.PHYSICAL_METHOD and "adaptive" in collector.PHYSICAL_METHOD
