"""OP.2.C follow-up -- the real CP ClusterXL `PreflightProvider`/
`EligibilityEvaluator` wiring (`checkpoint.clusterxl_preflight_provider`).

Exercises `ClusterXLPreflightProvider`/`ClusterXLReadinessEligibilityEvaluator`
both in isolation and wired through the real `utils.operate.coordinator.
ActionCoordinator` + `checkpoint.clusterxl_capability_adapter.
CPClusterXLCapabilityAdapter` (`OP.2.C`). No test contacts a device: every
`run_cp_preflight` call here goes through an in-module fake `preflight_runner`
injected at construction, exactly the discipline `checkpoint.
clusterxl_member_session`/`clusterxl_capability_adapter`'s own tests already
use for their transport seams. `utils.failover.assessment.compute_ha_readiness`
itself is exercised for real (never re-implemented or monkeypatched) so the
canonical verdict this module reads is the genuine readiness authority's own
output, not a stand-in.

Building and testing this wiring does not make CLASS 2 reachable: this file
re-asserts, the same way `tests/test_op2_c_cp_clusterxl_adapter.py` already
does for the adapter, that neither `ClusterXLPreflightProvider` nor
`ClusterXLReadinessEligibilityEvaluator` is referenced by any production
`ActionCoordinator` construction.
"""
from __future__ import annotations

import dataclasses
from pathlib import Path

import pytest

from checkpoint.clusterxl_capability_adapter import (
    ACTION_TYPE_HA_GRACEFUL_FAILOVER,
    ENTITY_KIND_CP_CLUSTER,
    CPClusterXLCapabilityAdapter,
    MemberRoleReading,
    SubmissionConfirmation,
)
from checkpoint.clusterxl_preflight_provider import (
    ClusterXLPreflightProvider,
    ClusterXLReadinessEligibilityEvaluator,
)
from checkpoint.cp_preflight_projection import (
    project_cp_failover_history_facts,
    project_cp_link_health_facts,
    project_cp_pnote_facts,
    project_cp_policy_facts,
    project_cp_preflight_facts,
    project_cp_software_version_fact,
    project_cp_sync_facts,
)
from checkpoint.preflight_collector import CPPhysicalMemberTarget
from utils.failover.assessment import (
    VERDICT_INSUFFICIENT,
    VERDICT_NOT_A_FAILOVER_UNIT,
    VERDICT_SAFE,
    VERDICT_UNSAFE,
)
from utils.failover.preflight_model import (
    FactCategory,
    FactContext,
    FactState,
    OpaqueToken,
    Outcome,
    PreflightFact,
    PreflightMemberEvidence,
    PreflightSnapshot as CollectedPreflightSnapshot,
    Provenance,
    SourceOrigin,
    Transport,
)
from utils.operate.authorization import AuthorizationDecision
from utils.operate.coordinator import ActionCoordinator
from utils.operate.eligibility import EligibilityResult
from utils.operate.eligibility import PreflightSnapshot as EligibilityPreflightSnapshot
from utils.operate.states import ActionState

pytestmark = pytest.mark.operate

REPO_ROOT = Path(__file__).resolve().parents[1]

_UNIT_ID = "grp-cp-1"
_T0 = "2026-09-05T10:00:00Z"
_T1 = "2026-09-05T10:00:01Z"


class PermitAuthorizer:
    def authorize(self, *, actor_ref, action_type, operational_entity_id):
        return AuthorizationDecision(permitted=True, reason_code="test_permit")


# ---------------------------------------------------------------------------
# CP snapshot fixture builder -- through the real S3 projection seams, same
# discipline `tests/test_op0b_s7_readiness_v2.py::cp_member`/`cp_snapshot`
# already use, trimmed to exactly the CP-HA-only shape this file needs.
# ---------------------------------------------------------------------------


def _identity_fact(*, run_id, at, member, unit, accepted=True):
    return PreflightFact(
        name="cp_identity_gate_accepted", category=FactCategory.PHYSICAL_IDENTITY, state=FactState.KNOWN, value=bool(accepted),
        provenance=Provenance(
            collected_at=at, preflight_run_id=run_id, source_vendor="checkpoint",
            source_plane=SourceOrigin.DEVICE_RUNTIME, transport=Transport.SSH_DIRECT,
            physical_device_identity=OpaqueToken(member), operational_entity_id=unit,
            context=FactContext.physical(), outcome=Outcome.SUCCESS if accepted else Outcome.IDENTITY_MISMATCH,
            source_command="gate",
        ),
    )


def cp_member(
    token, *, role="ACTIVE", mode="ha_new_mode", at=_T0, run_id="run-1", unit=_UNIT_ID,
    attention=False, pnote=False, link_down=False, sync="ok", version="R81.10",
    policy="policy-a", failover_count=0, peer_rows=("STANDBY",),
    failed=(), unknown=(),
):
    kw = dict(preflight_run_id=run_id, collected_at=at, physical_device_identity=token, operational_entity_id=unit)
    ev = project_cp_preflight_facts(
        {"local_role": role, "cluster_mode": mode, "peer_row_states": tuple(peer_rows), "local_attention": attention}, **kw,
    )
    own = list(ev.own_facts)
    own.append(_identity_fact(run_id=run_id, at=at, member=token, unit=unit))
    own.append(project_cp_software_version_fact(version, **kw))
    own.extend(project_cp_link_health_facts({"observed": True, "any_down": link_down, "interface_count": 3}, **kw))
    own.extend(project_cp_pnote_facts({"observed": True, "any_problem": pnote, "device_count": 5}, **kw))
    own.extend(project_cp_sync_facts({"observed": True, "status": sync}, dispatch_form="a6_syncstat", **kw))
    own.extend(project_cp_policy_facts({"observed": True, "policy_name": policy}, **kw))
    own.extend(project_cp_failover_history_facts(
        {"observed": True, "count": failover_count, "last_reason_class": "interface", "last_event_time": "t"},
        dispatch_form="a8_clish", **kw,
    ))
    out = []
    for fact in own:
        if fact.name in failed:
            fact = dataclasses.replace(fact, state=FactState.COLLECTION_FAILED, value=None)
        elif fact.name in unknown:
            fact = dataclasses.replace(fact, state=FactState.UNKNOWN, value=None)
        out.append(fact)
    return PreflightMemberEvidence(physical_device_identity=OpaqueToken(token), own_facts=tuple(out), peer_claim_facts=ev.peer_claim_facts)


def cp_snapshot(*members, unit=_UNIT_ID, run_id="run-1", unit_type="cluster"):
    return CollectedPreflightSnapshot(
        operational_unit_id=unit, vendor="checkpoint", unit_type=unit_type, preflight_run_id=run_id, members=tuple(members),
    )


def cp_rows(group=_UNIT_ID, devices=("m1", "m2")):
    return [
        {"device": d, "source": "cp", "cluster_topology": {"group_id": group, "display_name": "Core"},
         "inventory_status": {"data_state": "ok"}}
        for d in devices
    ]


def happy_cp_snapshot(*, run_id="run-1"):
    return cp_snapshot(
        cp_member("tok-m1", role="ACTIVE", run_id=run_id),
        cp_member("tok-m2", role="STANDBY", at=_T1, run_id=run_id),
        run_id=run_id,
    )


def unsafe_cp_snapshot(*, run_id="run-1"):
    # Two members both reporting ACTIVE -- explicit split-brain evidence.
    return cp_snapshot(
        cp_member("tok-m1", role="ACTIVE", run_id=run_id),
        cp_member("tok-m2", role="ACTIVE", at=_T1, run_id=run_id, peer_rows=("ACTIVE",)),
        run_id=run_id,
    )


def _members():
    return (
        CPPhysicalMemberTarget(physical_device_identity="tok-m1", expected_device_name="m1", management_ip="192.0.2.1"),
        CPPhysicalMemberTarget(physical_device_identity="tok-m2", expected_device_name="m2", management_ip="192.0.2.2"),
    )


def _make_provider(*, runner, unified_devices=None, cp_ha_runtime=None):
    return ClusterXLPreflightProvider(
        members=_members(),
        username="operator",
        secret="secret",
        unified_devices=lambda: unified_devices if unified_devices is not None else cp_rows(),
        cp_ha_runtime=(lambda: cp_ha_runtime) if cp_ha_runtime is not None else None,
        preflight_runner=runner,
    )


# ---------------------------------------------------------------------------
# ClusterXLPreflightProvider -- action/entity binding
# ---------------------------------------------------------------------------


def test_run_preflight_stamps_the_callers_own_action_id_and_entity_id():
    provider = _make_provider(runner=lambda **kwargs: happy_cp_snapshot())
    snapshot = provider.run_preflight(action_id="action-42", operational_entity_id=_UNIT_ID)
    assert snapshot.action_id == "action-42"
    assert snapshot.operational_entity_id == _UNIT_ID


def test_run_preflight_generates_a_fresh_preflight_run_id_every_call():
    calls = []

    def runner(**kwargs):
        run_id = f"run-{len(calls)}"
        calls.append(run_id)
        return happy_cp_snapshot(run_id=run_id)

    provider = _make_provider(runner=runner)
    first = provider.run_preflight(action_id="a1", operational_entity_id=_UNIT_ID)
    second = provider.run_preflight(action_id="a2", operational_entity_id=_UNIT_ID)
    assert first.preflight_run_id != second.preflight_run_id
    assert len(calls) == 2


def test_run_preflight_passes_the_callers_operational_entity_id_and_members_to_the_collector():
    seen = {}

    def runner(**kwargs):
        seen.update(kwargs)
        return happy_cp_snapshot()

    provider = _make_provider(runner=runner)
    provider.run_preflight(action_id="a1", operational_entity_id=_UNIT_ID)
    assert seen["operational_entity_id"] == _UNIT_ID
    assert tuple(m.physical_device_identity for m in seen["members"]) == ("tok-m1", "tok-m2")


# ---------------------------------------------------------------------------
# ClusterXLReadinessEligibilityEvaluator -- canonical verdict mapping
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "verdict, expected_eligible",
    [
        (VERDICT_SAFE, True),
        (VERDICT_UNSAFE, False),
        (VERDICT_INSUFFICIENT, False),
        (VERDICT_NOT_A_FAILOVER_UNIT, False),
        ("DEGRADED_PROCEED_WITH_RISK", False),
        ("some_future_unrecognized_verdict", False),
    ],
)
def test_evaluate_maps_canonical_verdict_to_eligibility(verdict, expected_eligible):
    snapshot = EligibilityPreflightSnapshot(
        preflight_run_id="pf1", action_id="a1", operational_entity_id=_UNIT_ID,
        coherent=True, readiness_verdict=verdict, check_statuses={"cluster_mode": "ha"},
    )
    result = ClusterXLReadinessEligibilityEvaluator().evaluate(snapshot=snapshot, capability=None)
    assert result.eligible is expected_eligible
    if not expected_eligible:
        assert result.reason_codes and verdict in result.reason_codes[0]


def test_evaluate_never_consults_capability_to_override_a_safe_verdict():
    """Correctness contract: eligibility never re-derives or overrides
    readiness -- a supported-but-oddly-shaped Capability must not flip a
    SAFE verdict to ineligible, nor vice versa."""
    from utils.operate.adapter import Capability

    snapshot = EligibilityPreflightSnapshot(
        preflight_run_id="pf1", action_id="a1", operational_entity_id=_UNIT_ID,
        coherent=True, readiness_verdict=VERDICT_SAFE, check_statuses={},
    )
    unsupported_capability = Capability(
        entity_kind=ENTITY_KIND_CP_CLUSTER, action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER,
        capability_id="cp_clusterxl_admin_state_v1", supported=False, reason="unsupported_cluster_mode",
    )
    result = ClusterXLReadinessEligibilityEvaluator().evaluate(snapshot=snapshot, capability=unsupported_capability)
    assert result.eligible is True


# ---------------------------------------------------------------------------
# ClusterXLPreflightProvider -- real compute_ha_readiness wiring
# ---------------------------------------------------------------------------


def test_run_preflight_reports_the_real_readiness_engines_safe_verdict_and_ha_cluster_mode():
    provider = _make_provider(runner=lambda **kwargs: happy_cp_snapshot())
    snapshot = provider.run_preflight(action_id="a1", operational_entity_id=_UNIT_ID)
    assert snapshot.readiness_verdict == VERDICT_SAFE
    assert snapshot.coherent is True
    # compute_ha_readiness reports CP's own vendor mode token
    # ("ha_new_mode"); this module translates it to the adapter's
    # canonical "ha" token -- never a second readiness/mode decision.
    assert snapshot.check_statuses["cluster_mode"] == "ha"


def test_run_preflight_resolves_subject_and_peer_tokens_from_the_active_members_role():
    provider = _make_provider(runner=lambda **kwargs: happy_cp_snapshot())
    snapshot = provider.run_preflight(action_id="a1", operational_entity_id=_UNIT_ID)
    assert snapshot.check_statuses["subject_member_token"] == "tok-m1"
    assert snapshot.check_statuses["peer_member_token"] == "tok-m2"


def test_run_preflight_reports_unsafe_verdict_on_real_split_brain_evidence():
    provider = _make_provider(runner=lambda **kwargs: unsafe_cp_snapshot())
    snapshot = provider.run_preflight(action_id="a1", operational_entity_id=_UNIT_ID)
    assert snapshot.readiness_verdict == VERDICT_UNSAFE
    # Split-brain -- two members observed ACTIVE -- is never a resolvable
    # subject/peer pair: fail closed rather than pick one arbitrarily.
    assert "subject_member_token" not in snapshot.check_statuses
    assert "peer_member_token" not in snapshot.check_statuses


def test_run_preflight_always_reports_recovery_mode_unknown():
    """D-V7b: the configured ClusterXL recovery method has no machine-
    readable read anywhere in the approved battery -- this module must
    never invent a resolution."""
    provider = _make_provider(runner=lambda **kwargs: happy_cp_snapshot())
    snapshot = provider.run_preflight(action_id="a1", operational_entity_id=_UNIT_ID)
    assert snapshot.check_statuses["recovery_mode"] == "unknown"


# ---------------------------------------------------------------------------
# Fail-closed missing/negative evidence
# ---------------------------------------------------------------------------


def test_run_preflight_fails_closed_when_a_members_role_read_failed():
    snap = cp_snapshot(
        cp_member("tok-m1", role="ACTIVE", failed=("ha_local_role",)),
        cp_member("tok-m2", role="STANDBY", at=_T1),
    )
    provider = _make_provider(runner=lambda **kwargs: snap)
    snapshot = provider.run_preflight(action_id="a1", operational_entity_id=_UNIT_ID)
    assert "subject_member_token" not in snapshot.check_statuses
    assert "peer_member_token" not in snapshot.check_statuses


def test_run_preflight_fails_closed_when_no_member_reports_active():
    snap = cp_snapshot(
        cp_member("tok-m1", role="STANDBY", peer_rows=("STANDBY",)),
        cp_member("tok-m2", role="STANDBY", at=_T1, peer_rows=("STANDBY",)),
    )
    provider = _make_provider(runner=lambda **kwargs: snap)
    snapshot = provider.run_preflight(action_id="a1", operational_entity_id=_UNIT_ID)
    assert "subject_member_token" not in snapshot.check_statuses
    assert "peer_member_token" not in snapshot.check_statuses


def test_run_preflight_fails_closed_when_cluster_mode_is_unknown():
    snap = cp_snapshot(
        cp_member("tok-m1", role="ACTIVE", unknown=("ha_cluster_mode",)),
        cp_member("tok-m2", role="STANDBY", at=_T1, unknown=("ha_cluster_mode",)),
    )
    provider = _make_provider(runner=lambda **kwargs: snap)
    snapshot = provider.run_preflight(action_id="a1", operational_entity_id=_UNIT_ID)
    assert "cluster_mode" not in snapshot.check_statuses


def test_run_preflight_reports_incoherent_when_a_runtime_fact_predates_this_run():
    member_a = cp_member("tok-m1", role="ACTIVE")
    # One runtime fact stamped with a stale preflight_run_id -- exactly what
    # utils.failover.preflight_model.evaluate_coherence's own contract makes
    # the whole snapshot incoherent over.
    stale = dataclasses.replace(
        member_a.own_facts[0],
        provenance=dataclasses.replace(member_a.own_facts[0].provenance, preflight_run_id="a-different-run"),
    )
    member_a = dataclasses.replace(member_a, own_facts=(stale,) + member_a.own_facts[1:])
    snap = cp_snapshot(member_a, cp_member("tok-m2", role="STANDBY", at=_T1))
    provider = _make_provider(runner=lambda **kwargs: snap)
    snapshot = provider.run_preflight(action_id="a1", operational_entity_id=_UNIT_ID)
    assert snapshot.coherent is False


def test_run_preflight_reports_insufficient_evidence_when_this_units_id_was_not_derived():
    """The provider's own operational_entity_id must actually be an
    inventory-derivable unit -- never a guessed verdict for a unit
    compute_ha_readiness itself never resolved."""
    provider = _make_provider(runner=lambda **kwargs: happy_cp_snapshot(), unified_devices=[])
    snapshot = provider.run_preflight(action_id="a1", operational_entity_id=_UNIT_ID)
    assert snapshot.readiness_verdict == VERDICT_INSUFFICIENT
    assert "cluster_mode" not in snapshot.check_statuses
    assert "subject_member_token" not in snapshot.check_statuses


# ---------------------------------------------------------------------------
# Adapter eligibility handoff -- real provider + real evaluator + real
# adapter, through the real ActionCoordinator. Still no device I/O: the
# member session transport stays a fake.
# ---------------------------------------------------------------------------


class FakeClusterXLMemberSession:
    def __init__(self, *, role="ACTIVE", admin_down_pnote_present=False):
        self.role = role
        self.admin_down_pnote_present = admin_down_pnote_present

    def read_role(self) -> MemberRoleReading:
        return MemberRoleReading(role=self.role, admin_down_pnote_present=self.admin_down_pnote_present, read_failed=False)

    def submit_admin_down(self) -> SubmissionConfirmation:
        return SubmissionConfirmation.SUBMITTED_OR_AMBIGUOUS

    def submit_admin_up(self) -> SubmissionConfirmation:
        return SubmissionConfirmation.SUBMITTED_OR_AMBIGUOUS


def _make_coordinator(tmp_path, *, runner, unified_devices=None):
    provider = _make_provider(runner=runner, unified_devices=unified_devices)
    sessions = {"tok-m1": FakeClusterXLMemberSession(role="ACTIVE"), "tok-m2": FakeClusterXLMemberSession(role="STANDBY")}
    adapter = CPClusterXLCapabilityAdapter(session_resolver=lambda token: sessions[token])
    return ActionCoordinator(
        data_root=tmp_path,
        authorizer=PermitAuthorizer(),
        preflight_provider=provider,
        eligibility_evaluator=ClusterXLReadinessEligibilityEvaluator(),
        adapter_resolver=lambda entity_kind, action_type: adapter,
    )


def test_real_wiring_reaches_awaiting_confirmation_on_safe_to_failover(tmp_path):
    coord = _make_coordinator(tmp_path, runner=lambda **kwargs: happy_cp_snapshot())
    record = coord.create_action(
        actor_ref="operator-1", action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER, operational_entity_id=_UNIT_ID,
        entity_kind=ENTITY_KIND_CP_CLUSTER, vendor="checkpoint", reason="controlled failover test",
    )
    result = coord.run_preflight(record.action_id, member_canonical_ids=["tok-m1", "tok-m2"])
    assert result.state == ActionState.AWAITING_CONFIRMATION
    assert result.readiness_verdict == VERDICT_SAFE
    assert result.check_statuses["subject_member_token"] == "tok-m1"
    assert result.check_statuses["peer_member_token"] == "tok-m2"


def test_real_wiring_reaches_not_eligible_on_split_brain_evidence(tmp_path):
    coord = _make_coordinator(tmp_path, runner=lambda **kwargs: unsafe_cp_snapshot())
    record = coord.create_action(
        actor_ref="operator-1", action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER, operational_entity_id=_UNIT_ID,
        entity_kind=ENTITY_KIND_CP_CLUSTER, vendor="checkpoint", reason="controlled failover test",
    )
    result = coord.run_preflight(record.action_id, member_canonical_ids=["tok-m1", "tok-m2"])
    assert result.state == ActionState.NOT_ELIGIBLE
    assert any("readiness_verdict_not_safe" in code for code in result.reason_codes)


def test_real_wiring_reaches_not_eligible_when_unit_not_in_inventory(tmp_path):
    coord = _make_coordinator(tmp_path, runner=lambda **kwargs: happy_cp_snapshot(), unified_devices=[])
    record = coord.create_action(
        actor_ref="operator-1", action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER, operational_entity_id=_UNIT_ID,
        entity_kind=ENTITY_KIND_CP_CLUSTER, vendor="checkpoint", reason="controlled failover test",
    )
    result = coord.run_preflight(record.action_id, member_canonical_ids=["tok-m1", "tok-m2"])
    assert result.state == ActionState.NOT_ELIGIBLE
    assert "no_adapter_capability" in result.reason_codes


# ---------------------------------------------------------------------------
# Re-asserted non-reachability invariants
# ---------------------------------------------------------------------------


def test_production_coordinator_default_stays_unreachable(tmp_path):
    coord = ActionCoordinator(data_root=tmp_path)
    with pytest.raises(Exception):
        coord.create_action(
            actor_ref="operator-1", action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER,
            operational_entity_id=_UNIT_ID, entity_kind=ENTITY_KIND_CP_CLUSTER,
            vendor="checkpoint", reason="must stay denied",
        )
    assert coord.store.list_all() == []


def test_preflight_provider_module_is_not_referenced_by_any_production_coordinator_construction():
    """Neither `ClusterXLPreflightProvider(` nor
    `ClusterXLReadinessEligibilityEvaluator(` appears anywhere outside
    `tests/` or the module that defines them -- this wiring ships typed,
    real, unit-tested implementations; it does not wire them into a live
    coordinator (see module docstring)."""
    markers = ("ClusterXLPreflightProvider(", "ClusterXLReadinessEligibilityEvaluator(")
    for py_file in REPO_ROOT.rglob("*.py"):
        relative = py_file.relative_to(REPO_ROOT)
        parts = relative.parts
        if parts[0] in ("tests", ".git", "node_modules"):
            continue
        if parts[0] == "checkpoint" and relative.name == "clusterxl_preflight_provider.py":
            continue
        text = py_file.read_text(encoding="utf-8", errors="ignore")
        for marker in markers:
            assert marker not in text, f"{relative} constructs {marker[:-1]} outside tests/"
