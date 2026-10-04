"""OP.1 — the failover plan compiler and dry-run.

Contract: docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md
(FROZEN — PRODUCT OWNER APPROVED 2026-09-09), section 9's eight acceptance
criteria. `utils.failover_plan` is a structurally separate, read-only
consumer of `utils.operate.adapter`/`utils.operate.eligibility` types and
`checkpoint.clusterxl_capability_adapter` -- it performs no I/O, constructs
no `ActionCoordinator`, and executes nothing.

Readiness-record fixtures reuse `tests/test_op0b_s7_readiness_v2.py`'s own
`cp_rows`/`happy_cp`/`cp_report` builders rather than re-deriving the S7
preflight-snapshot machinery -- an established cross-test-file import
pattern in this suite (e.g. `test_ce2_compliance_check_engine_primitives.py`
importing from `test_phase0_7_3_compliance_check_engine.py`).
"""
from __future__ import annotations

import json
import socket
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT / "tests") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "tests"))

from test_op0b_s7_readiness_v2 import _CP_UNIT, cp_report, cp_rows, happy_cp, unit  # noqa: E402

from checkpoint.clusterxl_capability_adapter import (  # noqa: E402
    ACTION_TYPE_HA_GRACEFUL_FAILBACK,
    ACTION_TYPE_HA_GRACEFUL_FAILOVER,
    ENTITY_KIND_CP_CLUSTER,
    CPClusterXLCapabilityAdapter,
)
from utils import action_taxonomy  # noqa: E402
from utils.failover.assessment import compute_ha_readiness  # noqa: E402
from utils.failover_plan import (  # noqa: E402
    FailoverPlan,
    compile_failover_plan,
    evaluate_dry_run,
)
from utils.operate import authorization as operate_authorization  # noqa: E402
from utils.operate.eligibility import PreflightSnapshot  # noqa: E402

_ACTIVE_STANDBY = {
    "m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
    "m2": {"ha_role": "STANDBY", "ha_cluster_mode": "ha_new_mode"},
}


def _resolvable_safe():
    """plan_compilable=True, readiness_verdict=SAFE_TO_FAILOVER."""
    record = cp_report(happy_cp(), cp_ha_runtime=_ACTIVE_STANDBY)
    return record, _ACTIVE_STANDBY


def _resolvable_unsafe():
    """plan_compilable=True, readiness_verdict=UNSAFE_DO_NOT_FAILOVER
    (no standby-capable peer -> `no_viable_target`, but exactly one ACTIVE
    among exactly two members, so member-token resolution still succeeds)."""
    runtime = {
        "m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
        "m2": {"ha_role": "DOWN", "ha_cluster_mode": "ha_new_mode"},
    }
    record = compute_ha_readiness(cp_rows(), cp_ha_runtime=runtime)
    return record, runtime


def _resolvable_insufficient():
    """plan_compilable=True, readiness_verdict=INSUFFICIENT_EVIDENCE
    (stored telemetry only, no preflight snapshot -- parity/sync/etc. are
    not evaluable, but role identity still resolves)."""
    record = compute_ha_readiness(cp_rows(), cp_ha_runtime=_ACTIVE_STANDBY)
    return record, _ACTIVE_STANDBY


_RESOLVABLE_FIXTURES = {
    "safe": _resolvable_safe,
    "unsafe": _resolvable_unsafe,
    "insufficient": _resolvable_insufficient,
}


def _blocked_unresolved_cluster_mode():
    """No `ha_cluster_mode` at all -> unresolved -> unsupported_cluster_mode."""
    runtime = {"m1": {"ha_role": "ACTIVE"}, "m2": {"ha_role": "STANDBY"}}
    return compute_ha_readiness(cp_rows(), cp_ha_runtime=runtime), runtime


def _blocked_non_ha_cluster_mode():
    """Load Sharing -> not the adapter's HA token -> unsupported_cluster_mode."""
    runtime = {
        "m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "load_sharing_unicast"},
        "m2": {"ha_role": "ACTIVE", "ha_cluster_mode": "load_sharing_unicast"},
    }
    return compute_ha_readiness(cp_rows(), cp_ha_runtime=runtime), runtime


def _blocked_ambiguous_roles_both_active():
    runtime = {
        "m1": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
        "m2": {"ha_role": "ACTIVE", "ha_cluster_mode": "ha_new_mode"},
    }
    return compute_ha_readiness(cp_rows(), cp_ha_runtime=runtime), runtime


def _blocked_missing_roles():
    runtime = {
        "m1": {"ha_cluster_mode": "ha_new_mode"},
        "m2": {"ha_cluster_mode": "ha_new_mode"},
    }
    return compute_ha_readiness(cp_rows(), cp_ha_runtime=runtime), runtime


def _blocked_vsx_host():
    from test_op0b_s7_readiness_v2 import vsx_rows

    record = compute_ha_readiness(vsx_rows(devices=("vsx-1",)))
    unit_id = next(u["unit_id"] for u in record["units"] if u["unit_type"] == "cp_vsx_host")
    return record, unit_id


def _blocked_pan_pair():
    from test_op0b_s7_readiness_v2 import _PAN_PEERS, _PAN_RUNTIME, _PAN_UNIT, pan_rows

    record = compute_ha_readiness(pan_rows(), pan_ha_runtime=_PAN_RUNTIME, pan_ha_peers=_PAN_PEERS)
    return record, _PAN_UNIT


# ---------------------------------------------------------------------------
# AC-1 -- compile_failover_plan never performs I/O.
# ---------------------------------------------------------------------------

def test_ac1_poison_resolver_is_never_invoked_over_every_fixture():
    """Structural proof, not a documented intention: the adapter this module
    constructs is given a resolver that raises `AssertionError` if invoked.
    Every fixture below -- including the SAFE_TO_FAILOVER one -- must compile
    (or block) without ever reaching it."""
    for build in _RESOLVABLE_FIXTURES.values():
        record, runtime = build()
        plan = compile_failover_plan(_CP_UNIT, readiness_record=record, cp_ha_runtime=runtime)
        assert plan.plan_compilable is True
        evaluate_dry_run(plan)

    for build in (
        _blocked_unresolved_cluster_mode,
        _blocked_non_ha_cluster_mode,
        _blocked_ambiguous_roles_both_active,
        _blocked_missing_roles,
    ):
        record, runtime = build()
        plan = compile_failover_plan(_CP_UNIT, readiness_record=record, cp_ha_runtime=runtime)
        assert plan.plan_compilable is False


def test_ac1_compiler_module_never_imports_a_session_resolver_type():
    """`compile_failover_plan` must never construct anything that could
    resolve a real `ClusterXLMemberSession` -- checked at the source level so
    a future edit cannot silently add a live resolver path."""
    import utils.failover_plan.compiler as compiler_module

    source = Path(compiler_module.__file__).read_text(encoding="utf-8")
    assert "import ClusterXLMemberSession" not in source
    assert "_poison_session_resolver" in source


# ---------------------------------------------------------------------------
# AC-2 -- plan_compilable is False with a named reason, never a fabrication.
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("build,expected_reason", [
    (_blocked_unresolved_cluster_mode, "unsupported_cluster_mode"),
    (_blocked_non_ha_cluster_mode, "unsupported_cluster_mode"),
    (_blocked_ambiguous_roles_both_active, "insufficient_member_identity_evidence"),
    (_blocked_missing_roles, "insufficient_member_identity_evidence"),
])
def test_ac2_blocked_cluster_and_role_cases(build, expected_reason):
    record, runtime = build()
    plan = compile_failover_plan(_CP_UNIT, readiness_record=record, cp_ha_runtime=runtime)
    assert plan.plan_compilable is False
    assert plan.compilation_blocked_reason == expected_reason
    assert plan.step is None
    assert plan.reversal is None


def test_ac2_vsx_unit_is_blocked_vendor_scope_deferred():
    record, unit_id = _blocked_vsx_host()
    plan = compile_failover_plan(unit_id, readiness_record=record, cp_ha_runtime={})
    assert plan.plan_compilable is False
    assert plan.compilation_blocked_reason == "vendor_scope_vsx_vsls_deferred"
    assert plan.step is None


def test_ac2_pan_ha_pair_is_blocked_unsupported_unit_type():
    record, unit_id = _blocked_pan_pair()
    plan = compile_failover_plan(unit_id, readiness_record=record, cp_ha_runtime={})
    assert plan.plan_compilable is False
    assert plan.compilation_blocked_reason == "unsupported_unit_type"
    assert plan.step is None


def test_ac2_every_non_clusterxl_unit_type_in_a_mixed_readiness_record_is_blocked():
    from test_op0b_s7_readiness_v2 import _PAN_PEERS, _PAN_RUNTIME, pan_rows, vsx_rows

    rows = cp_rows() + vsx_rows(devices=("vsx-1",)) + pan_rows()
    record = compute_ha_readiness(
        rows, cp_ha_runtime=_ACTIVE_STANDBY, pan_ha_runtime=_PAN_RUNTIME, pan_ha_peers=_PAN_PEERS,
    )
    for raw_unit in record["units"]:
        plan = compile_failover_plan(
            raw_unit["unit_id"], readiness_record=record, cp_ha_runtime=_ACTIVE_STANDBY,
        )
        if raw_unit["unit_type"] != "cp_clusterxl_cluster":
            assert plan.plan_compilable is False
            assert plan.compilation_blocked_reason is not None


# ---------------------------------------------------------------------------
# AC-3 -- every plan_compilable=True case carries exactly the seven
# STOP_CONDITIONS, verbatim -- generated matrix test.
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("fixture_name", sorted(_RESOLVABLE_FIXTURES))
def test_ac3_compiled_step_carries_exactly_the_seven_stop_conditions(fixture_name):
    from utils.failover.assessment import STOP_CONDITIONS

    record, runtime = _RESOLVABLE_FIXTURES[fixture_name]()
    plan = compile_failover_plan(_CP_UNIT, readiness_record=record, cp_ha_runtime=runtime)
    assert plan.plan_compilable is True

    expected_ids = [check_id for check_id, _label in STOP_CONDITIONS]
    actual_ids = [binding.id for binding in plan.step.preconditions]
    assert actual_ids == expected_ids

    raw_unit = unit(record, _CP_UNIT)
    raw_checks = {c["id"]: c for c in raw_unit["checks"]}
    for binding in plan.step.preconditions:
        raw = raw_checks[binding.id]
        assert binding.label == raw["label"]
        assert binding.status == raw["status"]
        assert binding.reason == raw["reason"]
        assert binding.missing_evidence == (raw.get("missing_evidence") or "")


# ---------------------------------------------------------------------------
# AC-4 -- ReversalStep.intended_postcondition reproduces build_plan()'s own
# three-way D-V7b disclosure unchanged; never a fourth value.
# ---------------------------------------------------------------------------

def test_ac4_compiled_reversal_is_always_the_disclosed_unknown():
    """D-V7b has no approved machine-readable read; `compile_failover_plan`
    always feeds `recovery_mode="unknown"` (module docstring). Every compiled
    reversal must therefore disclose exactly `"UNKNOWN"`, never a guess."""
    for build in _RESOLVABLE_FIXTURES.values():
        record, runtime = build()
        plan = compile_failover_plan(_CP_UNIT, readiness_record=record, cp_ha_runtime=runtime)
        assert plan.reversal.intended_postcondition == "UNKNOWN"


def test_ac4_build_plan_three_way_disclosure_is_reproduced_unchanged():
    """The adapter's own three-way disclosure this contract reuses (never
    re-derives): `maintain_current_active` -> subject returns to STANDBY,
    `switch_to_higher_priority` -> subject becomes ACTIVE again, anything
    else -> the disclosed `UNKNOWN`. No fourth value is ever produced."""
    adapter = CPClusterXLCapabilityAdapter(session_resolver=lambda token: (_ for _ in ()).throw(AssertionError()))
    base_statuses = {
        "cluster_mode": "ha", "subject_member_token": "member-a", "peer_member_token": "member-b",
    }

    seen = set()
    for recovery_mode, expected in (
        ("maintain_current_active", "subject_member_standby"),
        ("switch_to_higher_priority", "subject_member_active"),
        ("unknown", "UNKNOWN"),
        ("some_future_unhandled_value", "UNKNOWN"),
    ):
        evidence = PreflightSnapshot(
            preflight_run_id="pf1", action_id="a1", operational_entity_id="e1", coherent=True,
            readiness_verdict="positive", check_statuses={**base_statuses, "recovery_mode": recovery_mode},
        )
        reversal_plan = adapter.build_plan(entity=evidence, action_type=ACTION_TYPE_HA_GRACEFUL_FAILBACK, evidence=evidence)
        assert reversal_plan.intended_postcondition == expected
        seen.add(reversal_plan.intended_postcondition)

    assert seen == {"subject_member_standby", "subject_member_active", "UNKNOWN"}


# ---------------------------------------------------------------------------
# AC-5 -- DryRunReport.would_proceed is True iff plan_compilable and
# readiness_verdict == SAFE_TO_FAILOVER -- exhaustive over the verdict enum.
# ---------------------------------------------------------------------------

_ALL_VERDICTS = (
    "SAFE_TO_FAILOVER", "DEGRADED_PROCEED_WITH_RISK", "UNSAFE_DO_NOT_FAILOVER",
    "INSUFFICIENT_EVIDENCE", "NOT_A_FAILOVER_UNIT",
)


def _plan(*, plan_compilable, readiness_verdict):
    return FailoverPlan(
        unit_id="u1", vendor="checkpoint", capability_id="cp_clusterxl_admin_state_v1",
        evidence_basis="op0a_stored_telemetry", readiness_verdict=readiness_verdict,
        plan_compilable=plan_compilable,
        compilation_blocked_reason=None if plan_compilable else "unsupported_cluster_mode",
        step=None, reversal=None, generated_at="2026-09-09T00:00:00Z",
    )


@pytest.mark.parametrize("plan_compilable", [True, False])
@pytest.mark.parametrize("verdict", _ALL_VERDICTS)
def test_ac5_would_proceed_exhaustive_over_verdict_and_compilability(plan_compilable, verdict):
    plan = _plan(plan_compilable=plan_compilable, readiness_verdict=verdict)
    # step=None even when plan_compilable=True in this synthetic fixture --
    # evaluate_dry_run must not require a real step to compute would_proceed.
    report = evaluate_dry_run(plan)
    expected = plan_compilable and verdict == "SAFE_TO_FAILOVER"
    assert report.would_proceed is expected


def test_ac5_would_proceed_true_only_for_the_one_real_compiled_case():
    record, runtime = _resolvable_safe()
    plan = compile_failover_plan(_CP_UNIT, readiness_record=record, cp_ha_runtime=runtime)
    assert evaluate_dry_run(plan).would_proceed is True

    for fixture_name in ("unsafe", "insufficient"):
        record, runtime = _RESOLVABLE_FIXTURES[fixture_name]()
        plan = compile_failover_plan(_CP_UNIT, readiness_record=record, cp_ha_runtime=runtime)
        assert evaluate_dry_run(plan).would_proceed is False


# ---------------------------------------------------------------------------
# AC-6 -- CLASS_2_OPERATIONAL_STATE_CHANGE gains no member; DenyAllAuthorizer
# remains the only production Authorizer.
# ---------------------------------------------------------------------------

def test_ac6_class_2_still_has_no_member_and_deny_all_is_still_the_only_authorizer():
    assert action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE.permitted is False
    assert action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE.console_submittable is False

    source = Path(operate_authorization.__file__).read_text(encoding="utf-8")
    assert "DenyAllAuthorizer" in source
    assert "class PermitAllAuthorizer" not in source


def test_ac6_op1_package_never_constructs_an_action_coordinator():
    for py_file in (REPO_ROOT / "utils" / "failover_plan").glob("*.py"):
        text = py_file.read_text(encoding="utf-8")
        assert "ActionCoordinator(" not in text
        assert ".check_precondition(" not in text
        assert ".execute_once(" not in text
        assert ".observe_postcondition(" not in text


# ---------------------------------------------------------------------------
# AC-7 -- one-way dependency: nothing under utils/operate/ or
# checkpoint/clusterxl_capability_adapter.py imports utils.failover_plan.
# ---------------------------------------------------------------------------

def test_ac7_utils_operate_and_the_cp_adapter_never_import_failover_plan():
    marker_module = "utils.failover_plan"
    marker_package = "utils import failover_plan"

    candidates = list((REPO_ROOT / "utils" / "operate").glob("*.py"))
    candidates.append(REPO_ROOT / "checkpoint" / "clusterxl_capability_adapter.py")

    offenders = []
    for py_file in candidates:
        text = py_file.read_text(encoding="utf-8")
        if marker_module in text or marker_package in text:
            offenders.append(py_file.relative_to(REPO_ROOT))
    assert offenders == []


# ---------------------------------------------------------------------------
# AC-8 -- --failover-plan-dry-run performs no network/credential access --
# CLI smoke test over a fixture unified.json.
# ---------------------------------------------------------------------------

def test_ac8_cli_flag_parses_and_is_a_mutually_exclusive_maintenance_mode():
    from application.cli import build_parser, validate_modes

    parser = build_parser()
    args = parser.parse_args(["--failover-plan-dry-run"])
    assert args.failover_plan_dry_run is True
    validate_modes(args, parser)  # must not raise


def test_ac8_cli_flag_rejects_combination_with_collection_and_apply():
    from application.cli import build_parser, validate_modes

    parser = build_parser()
    args = parser.parse_args(["--failover-plan-dry-run", "--render-only"])
    with pytest.raises(SystemExit):
        validate_modes(args, parser)

    parser = build_parser()
    args = parser.parse_args(["--failover-plan-dry-run", "--apply"])
    with pytest.raises(SystemExit):
        validate_modes(args, parser)


def test_ac8_failover_plan_unit_requires_the_dry_run_flag():
    from application.cli import build_parser, validate_modes

    parser = build_parser()
    args = parser.parse_args(["--failover-plan-unit", "grp-cp-1"])
    with pytest.raises(SystemExit):
        validate_modes(args, parser)


def test_ac8_dry_run_workflow_performs_no_network_or_credential_access(tmp_path, monkeypatch):
    def _forbidden(*args, **kwargs):  # pragma: no cover - must never run
        raise AssertionError("OP.1 dry-run attempted network I/O")

    monkeypatch.setattr(socket, "socket", _forbidden)
    monkeypatch.setattr(socket, "create_connection", _forbidden)
    monkeypatch.setattr(socket, "getaddrinfo", _forbidden)

    output_root = tmp_path / "output"
    data_root = tmp_path / "data"
    output_root.mkdir()
    data_root.mkdir()
    (output_root / "unified.json").write_text(json.dumps(cp_rows()), encoding="utf-8")

    ctx = SimpleNamespace(
        runtime_paths=SimpleNamespace(output_root=output_root, data_root=data_root),
        args=SimpleNamespace(failover_plan_unit=None),
    )

    from application.workflows.failover import failover_plan_dry_run

    assert failover_plan_dry_run(ctx) == 0

    state_path = data_root / "state" / "failover_plan" / "dry_run.json"
    document = json.loads(state_path.read_text(encoding="utf-8"))
    assert document["schema"] == "securityexpert-failover-plan-dry-run-v1"
    assert len(document["reports"]) == 1
    assert document["reports"][0]["plan"]["unit_id"] == _CP_UNIT


def test_ac8_dry_run_workflow_rejects_unknown_requested_unit(tmp_path):
    output_root = tmp_path / "output"
    data_root = tmp_path / "data"
    output_root.mkdir()
    data_root.mkdir()
    (output_root / "unified.json").write_text(json.dumps(cp_rows()), encoding="utf-8")

    ctx = SimpleNamespace(
        runtime_paths=SimpleNamespace(output_root=output_root, data_root=data_root),
        args=SimpleNamespace(failover_plan_unit="no-such-unit"),
    )

    from application.workflows.failover import failover_plan_dry_run

    assert failover_plan_dry_run(ctx) == 2
