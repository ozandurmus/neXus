"""CE.2 — curated read-only command-primitive registry.

docs/design/COMPLIANCE_CHECK_ENGINE.md section 5 (decision D8). No network,
no server, no real-environment gate. Proves the safety boundary: read-only
enforcement, per-primitive frequency/session-reuse limits, redaction, and
that no write-capable command can ever be registered.
"""
from __future__ import annotations

import pytest

from configuration.command_primitives import (
    CommandGateReview,
    CommandPrimitive,
    CommandPrimitiveError,
    PRIMITIVE_REGISTRY,
    PrimitiveExecutionTracker,
    register_primitive,
    run_check_point_primitives,
    run_palo_alto_primitives,
)
from utils.action_taxonomy import CLASS_0_READ, CLASS_1_RECOVERY_WRITE
from utils.compliance_check_engine import parse_selector, resolve_source
from utils.compliance_posture import build_compliance_posture

from test_phase0_7_3_compliance_check_engine import (
    _CP_SECTIONS,
    _configuration_payload,
    _pack,
    _write_pack,
)

pytestmark = pytest.mark.compliance


def _review(**overrides) -> CommandGateReview:
    base = dict(
        reason="test",
        action_class_id=CLASS_0_READ.id,
        vendor_platform_shell_context="test",
        timeout_seconds=10,
        retry_count=0,
        max_frequency_per_endpoint_minutes=60,
        session_reuse="test",
        unsupported_behavior="test",
        secret_output_risk="test",
        safe_telemetry="test",
    )
    base.update(overrides)
    return CommandGateReview(**base)


def _redact_stub(raw):
    return {"len": len(str(raw or ""))}


# --- AC-1/AC-2: registry structure + gate review completeness ------------

def test_registry_has_one_primitive_per_major_vendor():
    vendors = {p.vendor for p in PRIMITIVE_REGISTRY.values()}
    assert "check_point" in vendors
    assert "palo_alto" in vendors


def test_every_registered_primitive_is_class_0_read():
    for primitive in PRIMITIVE_REGISTRY.values():
        assert primitive.gate_review.action_class_id == CLASS_0_READ.id


def test_every_registered_primitive_has_a_complete_gate_review():
    required_fields = (
        "reason", "action_class_id", "vendor_platform_shell_context",
        "timeout_seconds", "retry_count", "max_frequency_per_endpoint_minutes",
        "session_reuse", "unsupported_behavior", "secret_output_risk",
        "safe_telemetry",
    )
    for primitive in PRIMITIVE_REGISTRY.values():
        for field_name in required_fields:
            value = getattr(primitive.gate_review, field_name)
            assert value not in (None, ""), (primitive.primitive_id, field_name)


def test_source_namespace_is_primitive_dot_id():
    p = PRIMITIVE_REGISTRY["cp_gaia_show_version_all"]
    assert p.source_namespace == "primitive.cp_gaia_show_version_all"


# --- AC-5: no write-capable command can ever be registered ---------------

@pytest.mark.parametrize("command", [
    "clish -c 'set hostname evil'",
    "clish -c 'add host name evil ip-address 1.2.3.4'",
    "clish -c 'delete host name evil'",
    "<request><system><reboot></reboot></system></request>",
    "<commit></commit>",
])
def test_write_capable_command_is_refused_at_registration(command):
    hostile = CommandPrimitive(
        primitive_id="x_hostile",
        vendor="check_point",
        command=command,
        gate_review=_review(),
        redact=_redact_stub,
    )
    with pytest.raises(CommandPrimitiveError):
        register_primitive(hostile)


def test_declared_action_class_other_than_read_is_refused():
    hostile = CommandPrimitive(
        primitive_id="x_recovery_write",
        vendor="check_point",
        command="clish -c 'show version all'",
        gate_review=_review(action_class_id=CLASS_1_RECOVERY_WRITE.id),
        redact=_redact_stub,
    )
    with pytest.raises(CommandPrimitiveError):
        register_primitive(hostile)


def test_empty_command_is_refused():
    hostile = CommandPrimitive(
        primitive_id="x_empty",
        vendor="check_point",
        command="   ",
        gate_review=_review(),
        redact=_redact_stub,
    )
    with pytest.raises(CommandPrimitiveError):
        register_primitive(hostile)


@pytest.mark.parametrize("field,value", [
    ("timeout_seconds", 0),
    ("timeout_seconds", 121),
    ("retry_count", -1),
    ("retry_count", 3),
    ("max_frequency_per_endpoint_minutes", 0),
])
def test_out_of_range_gate_values_are_refused(field, value):
    hostile = CommandPrimitive(
        primitive_id="x_out_of_range",
        vendor="check_point",
        command="clish -c 'show version all'",
        gate_review=_review(**{field: value}),
        redact=_redact_stub,
    )
    with pytest.raises(CommandPrimitiveError):
        register_primitive(hostile)


def test_the_real_registry_entries_pass_registration_unchanged():
    # register_primitive is idempotent/pure over an already-valid entry.
    for primitive in PRIMITIVE_REGISTRY.values():
        assert register_primitive(primitive) is primitive


# --- AC-5: frequency / once-per-device-per-run admission ------------------

def test_tracker_admits_once_per_primitive_per_endpoint():
    tracker = PrimitiveExecutionTracker()
    p = PRIMITIVE_REGISTRY["cp_gaia_show_version_all"]
    assert tracker.admit(p, "10.0.0.1") is True
    assert tracker.admit(p, "10.0.0.1") is False  # same primitive, same endpoint, same run


def test_tracker_admits_the_same_primitive_against_a_different_endpoint():
    tracker = PrimitiveExecutionTracker()
    p = PRIMITIVE_REGISTRY["cp_gaia_show_version_all"]
    assert tracker.admit(p, "10.0.0.1") is True
    assert tracker.admit(p, "10.0.0.2") is True


def test_tracker_admits_different_primitives_against_the_same_endpoint():
    tracker = PrimitiveExecutionTracker()
    cp = PRIMITIVE_REGISTRY["cp_gaia_show_version_all"]
    pan = PRIMITIVE_REGISTRY["pan_show_system_info"]
    assert tracker.admit(cp, "shared-endpoint") is True
    assert tracker.admit(pan, "shared-endpoint") is True


# --- AC-5: session reuse — one transport session per admitted batch -------

class _FakeTarget:
    management_ip = "10.0.0.9"


def test_check_point_primitives_reuse_one_ssh_session(monkeypatch):
    connect_calls = []
    exec_calls = []

    def fake_connect(target, username, secret, *, strict, connect_timeout):
        connect_calls.append(target)
        return object(), "aa:bb"

    def fake_run_exec(ssh, command, timeout_seconds):
        exec_calls.append(command)
        return {"success": True, "error_class": "none", "stdout": "line1\nline2", "stderr": ""}

    monkeypatch.setattr("configuration.checkpoint_config_probe._connect", fake_connect)
    monkeypatch.setattr("configuration.checkpoint_config_probe._run_exec", fake_run_exec)

    p1 = CommandPrimitive(
        primitive_id="cp_test_one", vendor="check_point",
        command="clish -c 'show version all'", gate_review=_review(), redact=_redact_stub,
    )
    p2 = CommandPrimitive(
        primitive_id="cp_test_two", vendor="check_point",
        command="clish -c 'show hostname'", gate_review=_review(), redact=_redact_stub,
    )
    tracker = PrimitiveExecutionTracker()
    results = run_check_point_primitives(
        _FakeTarget(), username="u", secret="s", strict_host_key=False,
        connect_timeout=5, tracker=tracker, primitives=[p1, p2],
    )

    assert len(connect_calls) == 1  # one SSH session shared across both primitives
    assert len(exec_calls) == 2
    assert len(results) == 2
    assert all(r.success for r in results)
    # raw stdout must never survive into the result.
    for r in results:
        assert "line1" not in str(r.redacted)
        assert r.redacted["len"] == len("line1\nline2")


def test_check_point_primitives_second_probe_run_is_a_noop_for_the_same_tracker(monkeypatch):
    monkeypatch.setattr(
        "configuration.checkpoint_config_probe._connect",
        lambda *a, **k: (object(), "aa:bb"),
    )
    monkeypatch.setattr(
        "configuration.checkpoint_config_probe._run_exec",
        lambda *a, **k: {"success": True, "error_class": "none", "stdout": "x", "stderr": ""},
    )
    p1 = CommandPrimitive(
        primitive_id="cp_test_repeat", vendor="check_point",
        command="clish -c 'show version all'", gate_review=_review(), redact=_redact_stub,
    )
    tracker = PrimitiveExecutionTracker()
    first = run_check_point_primitives(
        _FakeTarget(), username="u", secret="s", strict_host_key=False,
        connect_timeout=5, tracker=tracker, primitives=[p1],
    )
    second = run_check_point_primitives(
        _FakeTarget(), username="u", secret="s", strict_host_key=False,
        connect_timeout=5, tracker=tracker, primitives=[p1],
    )
    assert len(first) == 1
    assert second == []  # already executed against this endpoint this run


def test_palo_alto_primitives_reuse_one_api_key(monkeypatch):
    keygen_calls = []
    op_calls = []

    def fake_get_api_key(cfg, host, *, verify=False):
        keygen_calls.append(host)
        return "fake-key"

    def fake_op_cmd(host, key, cmd, target, *, verify=False):
        op_calls.append((cmd, target))
        import lxml.etree as etree
        return etree.fromstring(b"<response status='success'><result>ok</result></response>")

    monkeypatch.setattr("panorama.panorama_runtime_runner.get_api_key", fake_get_api_key)
    monkeypatch.setattr("panorama.panorama_runtime_runner.op_cmd", fake_op_cmd)
    monkeypatch.setattr("panorama.panorama_runtime_runner.fix_host", lambda h: f"https://{h}")

    p1 = CommandPrimitive(
        primitive_id="pan_test_one", vendor="palo_alto",
        command="<show><system><info></info></system></show>", gate_review=_review(), redact=_redact_stub,
    )
    p2 = CommandPrimitive(
        primitive_id="pan_test_two", vendor="palo_alto",
        command="<show><clock></clock></show>", gate_review=_review(), redact=_redact_stub,
    )

    class _Cfg:
        panorama_ip = "192.0.2.50"

    tracker = PrimitiveExecutionTracker()
    results = run_palo_alto_primitives(
        cfg=_Cfg(), target_serial="SN123", verify=False, tracker=tracker,
        primitives=[p1, p2],
    )

    assert len(keygen_calls) == 1  # one API key shared across both primitives
    assert len(op_calls) == 2
    assert len(results) == 2
    assert all(r.success for r in results)
    for r in results:
        assert "ok" not in str(r.redacted)  # no raw response content persisted


def test_palo_alto_primitive_failure_is_captured_without_raising(monkeypatch):
    monkeypatch.setattr(
        "panorama.panorama_runtime_runner.get_api_key",
        lambda cfg, host, *, verify=False: "fake-key",
    )

    def failing_op_cmd(host, key, cmd, target, *, verify=False):
        raise RuntimeError("device unreachable")

    monkeypatch.setattr("panorama.panorama_runtime_runner.op_cmd", failing_op_cmd)
    monkeypatch.setattr("panorama.panorama_runtime_runner.fix_host", lambda h: f"https://{h}")

    p1 = CommandPrimitive(
        primitive_id="pan_test_fail", vendor="palo_alto",
        command="<show><system><info></info></system></show>", gate_review=_review(), redact=_redact_stub,
    )

    class _Cfg:
        panorama_ip = "192.0.2.50"

    results = run_palo_alto_primitives(
        cfg=_Cfg(), target_serial="SN123", verify=False,
        tracker=PrimitiveExecutionTracker(), primitives=[p1],
    )
    assert len(results) == 1
    assert results[0].success is False
    assert results[0].error_class == "RuntimeError"


# --- AC-5: redaction never leaks raw command output -----------------------

def test_registry_redaction_never_returns_raw_text():
    p = PRIMITIVE_REGISTRY["cp_gaia_show_version_all"]
    secret_bearing_raw = "OS build 123\nsome-shared-secret-token-XYZ"
    redacted = p.redact(secret_bearing_raw)
    assert "some-shared-secret-token-XYZ" not in str(redacted)
    assert set(redacted.keys()) == {"bytes", "lines", "fingerprint_sha256"}


def test_registry_redaction_empty_text_has_no_fingerprint():
    p = PRIMITIVE_REGISTRY["cp_gaia_show_version_all"]
    redacted = p.redact("")
    assert redacted["fingerprint_sha256"] is None
    assert redacted["bytes"] == 0


# --- AC-3: exposed to the engine as source namespace primitive.<id> -------

def test_primitive_namespace_resolves():
    ev = {"primitive": {"cp_gaia_show_version_all": {"bytes": 42, "fingerprint_sha256": "abc"}}}
    got = resolve_source(ev, parse_selector("primitive.cp_gaia_show_version_all.bytes"))
    assert got == 42


def test_primitive_namespace_check_evaluates_against_wired_facts(tmp_path):
    _write_pack(tmp_path, _pack({
        "id": "x_primitive_evidence_present",
        "title": "CP version-evidence primitive collected successfully",
        "rationale": "Proof that a check can reference primitive.<id> evidence.",
        "severity": "informational",
        "applies_to": {"vendor": ["check_point"]},
        "frameworks": [
            {"framework": "CIS", "reference": "x", "applies": True},
            {"framework": "PCI-DSS", "reference": "x", "applies": True},
            {"framework": "BDDK", "reference": "x", "applies": True},
        ],
        "evidence": {"steps": [
            {"source": "primitive.cp_gaia_show_version_all.bytes",
             "assert": {"op": "gte", "value": 1}},
        ]},
    }))

    # no primitive facts wired (the normal-run default) -> on_no_evidence.
    bare = build_compliance_posture(_configuration_payload(_CP_SECTIONS), None, data_root=tmp_path)
    b = next(
        c for c in next(s for s in bare["subjects"] if s["vendor_key"] == "check_point")["extended_controls"]
        if c["control_id"] == "x_primitive_evidence_present"
    )
    assert b["status"] == "UNKNOWN"

    # an opt-in --compliance-probe artifact wired for cp-001 -> PASS.
    facts = {"cp-001": {"cp_gaia_show_version_all": {"bytes": 128, "fingerprint_sha256": "abc"}}}
    wired = build_compliance_posture(
        _configuration_payload(_CP_SECTIONS), None, data_root=tmp_path,
        primitive_facts_by_subject=facts,
    )
    w = next(
        c for c in next(s for s in wired["subjects"] if s["vendor_key"] == "check_point")["extended_controls"]
        if c["control_id"] == "x_primitive_evidence_present"
    )
    assert w["status"] == "PASS"


def test_primitive_facts_by_subject_omitted_is_byte_identical(tmp_path):
    _write_pack(tmp_path, _pack({
        "id": "x_noop_check",
        "title": "noop",
        "rationale": "noop",
        "severity": "informational",
        "applies_to": {"vendor": ["check_point"]},
        "frameworks": [
            {"framework": "CIS", "reference": "x", "applies": True},
            {"framework": "PCI-DSS", "reference": "x", "applies": True},
            {"framework": "BDDK", "reference": "x", "applies": True},
        ],
        "evidence": {"steps": [
            {"source": "primitive.cp_gaia_show_version_all.bytes",
             "assert": {"op": "gte", "value": 1}},
        ]},
    }))
    a = build_compliance_posture(_configuration_payload(_CP_SECTIONS), None, data_root=tmp_path)
    b = build_compliance_posture(
        _configuration_payload(_CP_SECTIONS), None, data_root=tmp_path,
        primitive_facts_by_subject=None,
    )
    assert a == b
