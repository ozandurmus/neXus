"""PCP.8 — closed catalog of read-only diagnostic runbooks.

docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md section 15;
project/backlog.json id ``diagnostic_runbooks_read_only``. Proves the safety
boundary: a runbook step must already be a registered CE.2 primitive, must be
CLASS_0_READ, and must belong to the runbook's declared vendor; a runbook
shares CE.2's own registry rather than a parallel one; execution through the
shared registry's own transport helpers still discards raw output and only
ever surfaces the same redacted shape.
"""
from __future__ import annotations

import pytest

from configuration.command_primitives import (
    CommandGateReview,
    CommandPrimitive,
    PRIMITIVE_REGISTRY,
    PrimitiveExecutionTracker,
    run_check_point_primitives,
)
from configuration.runbook_catalog import (
    RUNBOOK_CATALOG,
    Runbook,
    RunbookValidationError,
    register_runbook,
    validate_runbook_steps,
)
from utils.action_taxonomy import CLASS_0_READ, CLASS_1_RECOVERY_WRITE

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


# --- AC-2: catalog structure — ordered sequences of existing CE.2 primitives

def test_catalog_has_at_least_one_runbook():
    assert len(RUNBOOK_CATALOG) >= 1


def test_every_runbook_step_is_the_literal_shared_ce2_registry_entry():
    """Proves the catalog and CE.2 are one registry, not two: each resolved
    step must be the exact same object PRIMITIVE_REGISTRY holds, not a copy
    or a second definition of the same primitive_id."""
    for runbook in RUNBOOK_CATALOG.values():
        for step_id, resolved in zip(runbook.step_ids, runbook.resolve_steps()):
            assert resolved is PRIMITIVE_REGISTRY[step_id]


def test_every_runbook_step_is_class_0_read():
    for runbook in RUNBOOK_CATALOG.values():
        for step in runbook.resolve_steps():
            assert step.gate_review.action_class_id == CLASS_0_READ.id


def test_every_runbook_step_vendor_matches_the_runbook_vendor():
    for runbook in RUNBOOK_CATALOG.values():
        for step in runbook.resolve_steps():
            assert step.vendor == runbook.vendor


def test_runbook_step_order_is_preserved():
    runbook = RUNBOOK_CATALOG["cp_basic_health_check"]
    resolved = runbook.resolve_steps()
    assert [p.primitive_id for p in resolved] == list(runbook.step_ids)


# --- AC-3: validator rejects any CLASS 1+ or unregistered step -----------

def test_validate_runbook_steps_rejects_empty_sequence():
    with pytest.raises(RunbookValidationError):
        validate_runbook_steps([], "check_point")


def test_validate_runbook_steps_rejects_unregistered_step():
    with pytest.raises(RunbookValidationError, match="not a registered"):
        validate_runbook_steps(["not_a_real_primitive_id"], "check_point")


def test_validate_runbook_steps_rejects_a_class_1_step():
    """A hostile/malformed registry (bypassing command_primitives.register_
    primitive's own gate) must still be refused at the runbook layer — the
    runbook validator is independent defense-in-depth, not a second read of
    the same already-trusted check."""
    hostile = CommandPrimitive(
        primitive_id="cp_hostile_write",
        vendor="check_point",
        command="clish -c 'show version all'",
        gate_review=_review(action_class_id=CLASS_1_RECOVERY_WRITE.id),
        redact=_redact_stub,
    )
    hostile_registry = {hostile.primitive_id: hostile}
    with pytest.raises(RunbookValidationError, match="action class"):
        validate_runbook_steps([hostile.primitive_id], "check_point", registry=hostile_registry)


def test_validate_runbook_steps_rejects_vendor_mismatch():
    real_cp_step = next(
        p.primitive_id for p in PRIMITIVE_REGISTRY.values() if p.vendor == "check_point"
    )
    with pytest.raises(RunbookValidationError, match="vendor"):
        validate_runbook_steps([real_cp_step], "palo_alto")


def test_register_runbook_rejects_a_catalog_entry_with_a_bad_step():
    bad = Runbook(
        runbook_id="bad_runbook",
        vendor="check_point",
        title="bad",
        purpose="bad",
        step_ids=("not_a_real_primitive_id",),
    )
    with pytest.raises(RunbookValidationError):
        register_runbook(bad)


def test_the_real_catalog_entries_pass_registration_unchanged():
    for runbook in RUNBOOK_CATALOG.values():
        assert register_runbook(runbook) is runbook


# --- AC-5: output-limit / redaction boundary, exercised end to end -------

class _FakeTarget:
    management_ip = "10.0.0.9"


def test_runbook_execution_never_returns_raw_command_output(monkeypatch):
    def fake_connect(target, username, secret, *, strict, connect_timeout):
        return object(), "aa:bb"

    def fake_run_exec(ssh, command, timeout_seconds):
        return {
            "success": True,
            "error_class": "none",
            "stdout": "SECRET-LOOKING-CONFIG-LINE\nversion R81.20",
            "stderr": "",
        }

    monkeypatch.setattr("configuration.checkpoint_config_probe._connect", fake_connect)
    monkeypatch.setattr("configuration.checkpoint_config_probe._run_exec", fake_run_exec)

    runbook = RUNBOOK_CATALOG["cp_basic_health_check"]
    tracker = PrimitiveExecutionTracker()
    results = run_check_point_primitives(
        _FakeTarget(),
        username="u",
        secret="s",
        strict_host_key=False,
        connect_timeout=5,
        tracker=tracker,
        primitives=runbook.resolve_steps(),
    )

    assert len(results) == len(runbook.step_ids)
    assert all(r.success for r in results)
    for result in results:
        safe = result.to_safe_dict()
        serialized = str(safe)
        assert "SECRET-LOOKING-CONFIG-LINE" not in serialized
        assert set(safe["redacted"].keys()) == {"bytes", "lines", "fingerprint_sha256"}
