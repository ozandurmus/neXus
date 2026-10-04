"""SecurityExpert — OP.0b S7, preflight evidence → canonical readiness checks.

Contract: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(status: FROZEN WITH REAL-ENV VALIDATION GATES) — Implementation slices, S7,
read together with `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`
(APPROVED, "Approval record") for which facts actually exist, and
`docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md` P4 for the verdict
contract this module feeds but never owns.

This module is the **one explicit typed mapping** from S1 `PreflightFact`s
(as produced by the S5 Check Point / S6 Palo Alto dedicated preflight
collectors) into the seven canonical readiness stop-conditions
(`utils.failover.assessment.STOP_CONDITIONS`). It evaluates *check* status
(PASS / FAIL / INSUFFICIENT_EVIDENCE) from a `PreflightSnapshot`; it never
computes a unit verdict. The verdict roll-up stays exactly where OP.0a put it
— `utils.failover.assessment._verdict_for` — so there is one readiness
authority, not two (`tests/test_op0b_s7_readiness_v2.py` proves this
structurally: no verdict vocabulary appears in this file).

Evidence laws this module encodes and the tests enforce:

- `UNKNOWN` / `COLLECTION_FAILED` / `UNSUPPORTED` / an absent fact never
  satisfy a check — a check may PASS only when its frozen minimum predicate
  is positively established from `FactState.KNOWN` facts of the unit's own
  members, read in one coherent preflight run.
- `COLLECTION_FAILED` and `UNSUPPORTED` are never KNOWN_BAD; only a fact the
  device itself reported, whose value is in a frozen explicit-failure set,
  can produce `FAIL`.
- Incoherence (mixed `preflight_run_id`, invalid member attribution, an
  identity gate that did not pass, an unestablished HA mode) blocks every
  positive result — it is never itself turned into a device failure.
- A member's claim about its peer (`peer_claim_facts`) is never counted as
  an observation of that peer: the only peer-claim facts consulted are the
  Palo Alto `conn-*` link-status leaves, which are this member's *own*
  observation of its *link* to the peer (category F), never of the peer's
  state.
- No numeric threshold is chosen here, ever: `D-F1` (configuration-intent max
  age) is a still-open product-owner decision; `D-F2` (member-skew
  tolerance) and `D-F3` (flap/failover frequency) were DECIDED (CP pilot
  readiness-policy amendment) to permanently carry no threshold at all. Every
  counter is exposed as an observed value only; a check that would need a
  threshold to PASS stays fail-closed (`INSUFFICIENT_EVIDENCE`), never
  silently permissive, and never fabricated to a numeric predicate this
  module was never given.
- `D-V7b` (Check Point configured recovery) stays unreadable — `CP-A9` was
  not authorized by the command gate — so `preemption_known` stays
  `INSUFFICIENT_EVIDENCE` for Check Point, exactly as before; this module
  does not reinterpret that check. What changed (CP pilot readiness-policy
  amendment, `utils.failover.assessment.ADVISORY_EXEMPT_CHECKS`) is only
  whether the canonical roll-up lets this one check's documented,
  permanently-`INSUFFICIENT_EVIDENCE` status block an otherwise-positive
  verdict — a roll-up-only decision, entirely outside this module.
- PAN `B2` (bidirectional pair-identity corroboration) is NOT ESTABLISHED and
  is not established here: `peer_serial_claim` / `local_serial_claim` are
  never read; pair identity remains whatever `assessment._derive_pan_units`
  resolved from the frozen hostname-keyed fallback.

Pure, zero-I/O: no transport, no command, no credential, no file, no sleep.
Collection is S5/S6; interpretation is here; the verdict is `assessment`.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Sequence

from utils.failover.assessment import (
    CHECK_FAIL,
    CHECK_INSUFFICIENT,
    CHECK_PASS,
    EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT,
    POLICY_D_F1,
    POLICY_D_F2,
    POLICY_D_F3,
    STOP_CONDITIONS,
    _CP_ACTIVE_ROLES,
    _CP_LOAD_SHARING_MODES,
    _CP_STANDBY_CAPABLE_ROLES,
    _PAN_ACTIVE_STATES,
    _PAN_STANDBY_CAPABLE_STATES,
)
from utils.failover.preflight_model import (
    ContextKind,
    FactCategory,
    FactState,
    Outcome,
    PreflightFact,
    PreflightMemberEvidence,
    PreflightSnapshot,
    evaluate_coherence,
)

__all__ = [
    "FactRule",
    "CheckEvidenceSpec",
    "FACT_CHECK_MAP",
    "SnapshotEvaluation",
    "evaluate_snapshot_checks",
    "CP_SUPPORTED_FAILOVER_MODES",
    "PAN_SUPPORTED_FAILOVER_MODES",
    "PAN_NON_FUNCTIONAL_STATES",
]

# --- Frozen vendor vocabularies used by the mapping -------------------------

#: Check Point HA modes this contract supports as a failover unit (contract
#: domain invariant 7; sk112712 for the VSX string). Load-sharing modes are
#: not here: they are handled by the canonical roll-up as NOT_A_FAILOVER_UNIT.
#: `vsx_vsls` (real-env finding, S8-B'): Virtual System Load Sharing is a
#: supported CP failover mode -- each VSID fails over independently, in
#: contrast to `vsx_single_vs_failover` (whole VSX gateway together). Both
#: are VSX HA modes in the vendor sense of "supported and evaluable", never
#: `_CP_LOAD_SHARING_MODES` (that set means "no standby exists", which is
#: false for VSLS).
CP_SUPPORTED_FAILOVER_MODES = frozenset({"ha_new_mode", "vsx_single_vs_failover", "vsx_vsls"})

#: Palo Alto: Active/Passive only (contract "Scope in"; A/A, HA4 → UNSUPPORTED).
PAN_SUPPORTED_FAILOVER_MODES = frozenset({"active-passive"})

#: PAN-OS non-functional HA state set — ESTABLISHED by official docs (contract
#: §"Palo Alto evidence surface / Vendor semantics established": "non-
#: functional = initial, non-functional, tentative, suspended").
PAN_NON_FUNCTIONAL_STATES = frozenset({"initial", "non-functional", "tentative", "suspended"})

#: Check Point sync status enum as `cp_preflight_extraction.parse_cp_sync_status`
#: emits it (the parser already fails closed to `None` on unknown tokens).
_CP_SYNC_OK = ("ok",)
_CP_SYNC_NOT_OK = ("not_ok",)

#: PAN `state-sync` frozen predicate (D-V2): "Complete" → healthy; anything
#: else → not sufficient for PASS. No KNOWN_BAD vocabulary is frozen for it.
_PAN_STATE_SYNC_COMPLETE = ("complete",)

#: PAN `conn-*` frozen predicate (D-V1): "up" → healthy; "down" is the one
#: vendor-documented explicit failure token (KB "HA Peer Connection Status":
#: "Connection status: up/down"); any other value → UNKNOWN, never PASS.
_PAN_CONN_UP = ("up",)
_PAN_CONN_DOWN = ("down",)

#: PAN `*-compat` frozen predicate (D-V2): "Match" → healthy; "Mismatch" → an
#: explicit, vendor-documented parity failure; else UNKNOWN.
_PAN_COMPAT_MATCH = ("match",)
_PAN_COMPAT_MISMATCH = ("mismatch",)

#: PAN `running-sync` (D-V4, closed by docs at group scope). Official KB
#: vocabulary: "synchronized / not synchronized". Only the positive token is
#: frozen as healthy; nothing is frozen as KNOWN_BAD.
_PAN_RUNNING_SYNC_OK = ("synchronized",)

_CP_IDENTITY_GATE_FACT = "cp_identity_gate_accepted"
_PAN_IDENTITY_GATE_FACT = "pan_identity_gate_accepted"
_IDENTITY_GATE_FACTS = frozenset({_CP_IDENTITY_GATE_FACT, _PAN_IDENTITY_GATE_FACT})

#: Facts whose KNOWN_BAD reading is not trusted in a non-VS0 (VSID) context
#: (frozen D-V9a / sk165432: "a `Down` read in a non-VS0 context is UNKNOWN
#: ... never KNOWN_BAD"). A VSID-context read of these may support PASS when
#: healthy, but a failure-looking value collapses to UNKNOWN, never FAIL.
_VS_CONTEXT_UNTRUSTED_KNOWN_BAD = frozenset({"ha_local_role", "local_member_attention"})

#: Category-K / counter facts surfaced as informational observed values
#: (no threshold applied — D-F3 / D-F2 open). Values are S1 `FactValue`s
#: (bounded ints / bools / short tokens), never raw output.
_OBSERVED_DISCLOSURE_FACTS = (
    "cp_failover_count", "cp_failover_last_reason", "cp_failover_last_event_time",
    "cp_pnote_device_count", "cp_link_interface_count",
    "local_max_flaps", "local_nonfunc_flap_cnt", "local_preempt_flap_cnt", "local_state_duration",
    "local_priority", "local_preemptive",
    "pan_path_monitoring_enabled", "pan_path_monitoring_path_count",
)


# --- Typed mapping ---------------------------------------------------------

@dataclass(frozen=True)
class FactRule:
    """One fact's role in one check.

    `acceptable`: KNOWN values that satisfy the positive predicate (`None` =
    any KNOWN value is acceptable — e.g. "preemption configuration is
    *known*"). `failure`: KNOWN values that are an explicit, frozen,
    vendor-reported dangerous state → the check FAILs with `failure_reason`.
    Values are compared case-insensitively after `str()` for strings and
    directly for bools. `source`: `"own"` (the member's own facts) or
    `"link_observation"` (PAN `conn-*` leaves, carried in `peer_claim_facts`
    by the S2 projection because they are read from `peer-info`, but which
    are this member's own observation of its link — never a peer *state*).
    """

    fact: str
    category: FactCategory
    acceptable: tuple[Any, ...] | None = None
    failure: tuple[Any, ...] = ()
    failure_reason: str = ""
    source: str = "own"


@dataclass(frozen=True)
class CheckEvidenceSpec:
    """Everything the readiness layer needs to interpret one check for one
    vendor: which facts, which values pass, which values fail, what a
    missing value means, and which open decision (if any) makes a PASS
    unreachable regardless of the evidence."""

    check_id: str
    vendor: str
    #: Per-member facts that must all be KNOWN-and-acceptable on every
    #: attributable member for the check to PASS.
    positive_facts: tuple[FactRule, ...] = ()
    #: Cross-member predicate applied on top of `positive_facts`:
    #: "standby_capable_member" / "exactly_one_active" / "member_equality" / None.
    predicate: str | None = None
    #: Fact names the predicate compares (member_equality) — must be KNOWN on
    #: every member.
    predicate_facts: tuple[str, ...] = ()
    #: Open product-owner decision whose absence makes PASS unreachable.
    policy_gate: str | None = None
    #: Fixed, identity-free label naming what would close the gap.
    missing_evidence: str = ""
    #: Set when the check cannot be evaluated for this vendor at all (no
    #: authorized read exists) — reason emitted with INSUFFICIENT_EVIDENCE.
    not_evaluable_reason: str | None = None


def _rule(fact: str, category: FactCategory, *, acceptable=None, failure=(), failure_reason="", source="own") -> FactRule:
    return FactRule(fact=fact, category=category, acceptable=acceptable, failure=tuple(failure),
                    failure_reason=failure_reason, source=source)


#: Role/state vocabularies the cross-member predicates may reason over. A
#: KNOWN value outside these sets is an unrecognised vendor token: it is
#: `value_not_established` (INSUFFICIENT), never counted as "no standby" or
#: "exactly one active" -- an unknown value is not evidence of absence.
_CP_RECOGNISED_ROLES = tuple(_CP_ACTIVE_ROLES | _CP_STANDBY_CAPABLE_ROLES | {"DOWN"})
_PAN_RECOGNISED_STATES = tuple(_PAN_ACTIVE_STATES | _PAN_STANDBY_CAPABLE_STATES | PAN_NON_FUNCTIONAL_STATES)

_CP_ROLE = _rule("ha_local_role", FactCategory.RUNTIME_HA_STATE, acceptable=_CP_RECOGNISED_ROLES)
_CP_ATTENTION = _rule(
    "local_member_attention", FactCategory.FAILURE_HEALTH_STATE,
    acceptable=(False,), failure=(True,), failure_reason="member_failure_state_observed",
)
_CP_PNOTE = _rule(
    "cp_pnote_any_problem", FactCategory.FAILURE_HEALTH_STATE,
    acceptable=(False,), failure=(True,), failure_reason="critical_device_problem_observed",
)
_PAN_STATE = _rule(
    "local_state", FactCategory.RUNTIME_HA_STATE, acceptable=_PAN_RECOGNISED_STATES,
    failure=tuple(PAN_NON_FUNCTIONAL_STATES), failure_reason="member_non_functional_state_observed",
)

#: The mapping. One entry per (vendor, canonical check id). Every one of the
#: seven `STOP_CONDITIONS` appears for both vendors — a check with no
#: authorized evidence carries `not_evaluable_reason`, never silence.
FACT_CHECK_MAP: Mapping[tuple[str, str], CheckEvidenceSpec] = {
    # -- 1. viable_target -------------------------------------------------
    # Design §4 check 1: "a standby/passive peer that is up ... with no
    # critical device/pnote down". The frozen contract's proposed separate
    # check 8 (member failure state) is folded here per the S7 task's
    # seven-check preservation; the distinct reason codes keep "no standby"
    # and "member in failure state" separately visible.
    ("checkpoint", "viable_target"): CheckEvidenceSpec(
        check_id="viable_target", vendor="checkpoint",
        positive_facts=(_CP_ROLE, _CP_ATTENTION, _CP_PNOTE),
        predicate="standby_capable_member",
        missing_evidence="A3 cphaprob stat + A5 cphaprob -ia list on both members, one preflight run",
    ),
    ("panorama", "viable_target"): CheckEvidenceSpec(
        check_id="viable_target", vendor="panorama",
        positive_facts=(_PAN_STATE,),
        predicate="standby_capable_member",
        missing_evidence="P2 show high-availability state (local-info/state) on both members, one preflight run",
    ),
    # -- 2. state_sync_current -------------------------------------------
    ("checkpoint", "state_sync_current"): CheckEvidenceSpec(
        check_id="state_sync_current", vendor="checkpoint",
        positive_facts=(
            _rule("cp_sync_status", FactCategory.STATE_SESSION_SYNCHRONIZATION,
                  acceptable=_CP_SYNC_OK, failure=_CP_SYNC_NOT_OK, failure_reason="state_sync_not_ok_observed"),
        ),
        missing_evidence="A6 cphaprob syncstat / fw ctl pstat on both members, one preflight run",
    ),
    ("panorama", "state_sync_current"): CheckEvidenceSpec(
        check_id="state_sync_current", vendor="panorama",
        positive_facts=(
            _rule("local_state_sync", FactCategory.STATE_SESSION_SYNCHRONIZATION, acceptable=_PAN_STATE_SYNC_COMPLETE),
            _rule("peer_conn_ha2_status", FactCategory.LINK_HEALTH, acceptable=_PAN_CONN_UP,
                  failure=_PAN_CONN_DOWN, failure_reason="ha2_link_down_observed", source="link_observation"),
        ),
        missing_evidence="P2 show high-availability state (local-info/state-sync, peer-info/conn-ha2) on both members, one preflight run",
    ),
    # -- 3. parity --------------------------------------------------------
    ("checkpoint", "parity"): CheckEvidenceSpec(
        check_id="parity", vendor="checkpoint",
        positive_facts=(
            _rule("cp_software_version", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY),
            _rule("cp_installed_policy_token", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY),
        ),
        predicate="member_equality",
        predicate_facts=("cp_software_version", "cp_installed_policy_token"),
        missing_evidence="A2 show version all + A7 fw stat on both members, one preflight run",
    ),
    ("panorama", "parity"): CheckEvidenceSpec(
        check_id="parity", vendor="panorama",
        positive_facts=(
            _rule("group_running_sync", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, acceptable=_PAN_RUNNING_SYNC_OK),
            _rule("local_build_rel", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY),
            _rule("local_app_compat", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, acceptable=_PAN_COMPAT_MATCH,
                  failure=_PAN_COMPAT_MISMATCH, failure_reason="content_version_mismatch_observed"),
            _rule("local_av_compat", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, acceptable=_PAN_COMPAT_MATCH,
                  failure=_PAN_COMPAT_MISMATCH, failure_reason="content_version_mismatch_observed"),
            _rule("local_threat_compat", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, acceptable=_PAN_COMPAT_MATCH,
                  failure=_PAN_COMPAT_MISMATCH, failure_reason="content_version_mismatch_observed"),
            _rule("local_url_compat", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, acceptable=_PAN_COMPAT_MATCH,
                  failure=_PAN_COMPAT_MISMATCH, failure_reason="content_version_mismatch_observed"),
        ),
        predicate="member_equality",
        predicate_facts=("local_build_rel",),
        missing_evidence="P2 show high-availability state (running-sync, build-rel, *-compat) on both members, one preflight run",
    ),
    # -- 4. no_split_brain -----------------------------------------------
    ("checkpoint", "no_split_brain"): CheckEvidenceSpec(
        check_id="no_split_brain", vendor="checkpoint",
        positive_facts=(_CP_ROLE,),
        predicate="exactly_one_active",
        missing_evidence="A3 cphaprob stat on both members, one preflight run",
    ),
    ("panorama", "no_split_brain"): CheckEvidenceSpec(
        check_id="no_split_brain", vendor="panorama",
        positive_facts=(_rule("local_state", FactCategory.RUNTIME_HA_STATE, acceptable=_PAN_RECOGNISED_STATES),),
        predicate="exactly_one_active",
        missing_evidence="P2 show high-availability state (local-info/state) on both members, one preflight run",
    ),
    # -- 5. control_sync_link_health -------------------------------------
    # The frozen contract's proposed 5a/5b split is kept inside one check
    # (task §4); the reason codes distinguish control (HA1/CCP) from sync
    # (HA2) link failures.
    ("checkpoint", "control_sync_link_health"): CheckEvidenceSpec(
        check_id="control_sync_link_health", vendor="checkpoint",
        positive_facts=(
            _rule("cp_link_any_down", FactCategory.LINK_HEALTH, acceptable=(False,),
                  failure=(True,), failure_reason="cluster_interface_down_observed"),
        ),
        missing_evidence="A4 cphaprob -a if on both members, one preflight run",
    ),
    ("panorama", "control_sync_link_health"): CheckEvidenceSpec(
        check_id="control_sync_link_health", vendor="panorama",
        positive_facts=(
            _rule("peer_conn_ha1_status", FactCategory.LINK_HEALTH, acceptable=_PAN_CONN_UP,
                  failure=_PAN_CONN_DOWN, failure_reason="ha1_link_down_observed", source="link_observation"),
            _rule("peer_conn_ha2_status", FactCategory.LINK_HEALTH, acceptable=_PAN_CONN_UP,
                  failure=_PAN_CONN_DOWN, failure_reason="ha2_link_down_observed", source="link_observation"),
            _rule("pan_path_monitoring_any_down", FactCategory.LINK_HEALTH, acceptable=(False,),
                  failure=(True,), failure_reason="monitored_path_down_observed"),
        ),
        missing_evidence="P2 peer-info/conn-ha1, conn-ha2 + P4 show high-availability path-monitoring on both members, one preflight run",
    ),
    # -- 6. preemption_known ---------------------------------------------
    ("checkpoint", "preemption_known"): CheckEvidenceSpec(
        check_id="preemption_known", vendor="checkpoint",
        not_evaluable_reason="configured_recovery_not_readable_d_v7b",
        missing_evidence="A9 management-plane cluster recovery setting (not authorized; D-V7b unresolved)",
    ),
    ("panorama", "preemption_known"): CheckEvidenceSpec(
        check_id="preemption_known", vendor="panorama",
        positive_facts=(_rule("local_preemptive", FactCategory.ELECTION_PREEMPTION_BEHAVIOR),),
        missing_evidence="P2 show high-availability state (local-info/preemptive) on both members, one preflight run",
    ),
    # -- 7. flap_history --------------------------------------------------
    ("checkpoint", "flap_history"): CheckEvidenceSpec(
        check_id="flap_history", vendor="checkpoint",
        positive_facts=(_rule("cp_failover_count", FactCategory.TRANSITION_FLAP_HISTORY),),
        policy_gate=POLICY_D_F3,
        missing_evidence="A8 cluster failover statistics on both members + D-F3 flap threshold decision",
    ),
    ("panorama", "flap_history"): CheckEvidenceSpec(
        check_id="flap_history", vendor="panorama",
        positive_facts=(
            _rule("local_nonfunc_flap_cnt", FactCategory.TRANSITION_FLAP_HISTORY),
            _rule("local_preempt_flap_cnt", FactCategory.TRANSITION_FLAP_HISTORY),
        ),
        policy_gate=POLICY_D_F3,
        missing_evidence="P2 show high-availability state (flap counters) on both members + D-F3 flap threshold decision",
    ),
}

assert {check_id for _vendor, check_id in FACT_CHECK_MAP} == {check_id for check_id, _label in STOP_CONDITIONS}
assert len(FACT_CHECK_MAP) == 2 * len(STOP_CONDITIONS)


# --- Evaluation ------------------------------------------------------------

@dataclass
class _MemberView:
    identity: str
    own: dict[str, PreflightFact]
    link: dict[str, PreflightFact]  # PAN conn-* leaves from peer_claim_facts
    #: OP.0b S8-C real-env correction: EVERY peer_claim_facts entry, keyed by
    #: name -- `link` above is filtered to `FactCategory.LINK_HEALTH` only,
    #: which excludes `peer_serial_claim`/`peer_mgmt_ip_claim`
    #: (`PEER_IDENTITY_RELATIONSHIP`). `_pan_reciprocal_correspondence` needs
    #: those, so this carries the unfiltered set instead of widening `link`'s
    #: existing, narrower meaning.
    peer_claims: dict[str, PreflightFact]
    identity_gate: str  # "ok" | "failed" | "not_recorded"
    context_kind: ContextKind | None


@dataclass
class SnapshotEvaluation:
    """What `evaluate_snapshot_checks` hands back to the canonical
    evaluator: the seven check dicts (same shape `assessment._check` emits,
    plus an additive `facts` list), the safe provenance/coherence disclosure
    for the unit's `evidence` block, and the fresh HA mode if the snapshot
    established one (the roll-up decides what a load-sharing mode means)."""

    checks: list[dict[str, Any]] = field(default_factory=list)
    evidence: dict[str, Any] = field(default_factory=dict)
    effective_mode: str | None = None


def _norm(value: Any) -> Any:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower()
    return value


def _fact_matches(fact: PreflightFact | None, values: tuple[Any, ...] | None) -> bool | None:
    """`True` when the fact is KNOWN and its value is in `values` (or `values`
    is `None` = any KNOWN value); `False` when KNOWN and not in `values`;
    `None` when the fact is absent or not KNOWN — never coerced."""
    if fact is None or fact.state is not FactState.KNOWN:
        return None
    if values is None:
        return True
    return _norm(fact.value) in {_norm(v) for v in values}


def _member_views(snapshot: PreflightSnapshot) -> list[_MemberView]:
    views: list[_MemberView] = []
    for member in snapshot.members:
        own = {f.name: f for f in member.own_facts}
        peer_claims = {f.name: f for f in member.peer_claim_facts}
        link = {
            name: f for name, f in peer_claims.items()
            if f.category is FactCategory.LINK_HEALTH
        }
        gate_fact = next((own[n] for n in _IDENTITY_GATE_FACTS if n in own), None)
        if gate_fact is None:
            gate = "not_recorded"
        elif gate_fact.provenance.outcome is Outcome.IDENTITY_MISMATCH or _fact_matches(gate_fact, (True,)) is not True:
            gate = "failed"
        else:
            gate = "ok"
        contexts = {f.provenance.context.kind for f in member.own_facts}
        views.append(_MemberView(
            identity=str(member.physical_device_identity), own=own, link=link, peer_claims=peer_claims,
            identity_gate=gate, context_kind=next(iter(contexts)) if len(contexts) == 1 else None,
        ))
    return views


def _attribution_problems(unit_id: str, member_count: int, snapshot: PreflightSnapshot) -> list[str]:
    problems: list[str] = []
    if snapshot.operational_unit_id != unit_id:
        problems.append("snapshot_unit_mismatch")
    if len(snapshot.members) > member_count:
        problems.append("more_members_than_unit")
    identities = [str(m.physical_device_identity) for m in snapshot.members]
    if len(set(identities)) != len(identities):
        problems.append("duplicate_member_identity")
    for member in snapshot.members:
        for fact in member.own_facts + member.peer_claim_facts:
            if fact.provenance.operational_entity_id != snapshot.operational_unit_id:
                problems.append("fact_entity_mismatch")
                break
            if str(fact.provenance.physical_device_identity) != str(member.physical_device_identity):
                problems.append("fact_member_mismatch")
                break
    return sorted(set(problems))


def _effective_mode(vendor: str, members: Sequence[_MemberView]) -> tuple[str | None, str]:
    """(`mode`, gate) — gate ∈ ok | not_established | unsupported |
    not_a_failover_unit. Mode is established only when every attributable
    member reports the same KNOWN mode (contract invariant 7)."""
    fact_name = "ha_cluster_mode" if vendor == "checkpoint" else "local_mode"
    modes: set[str] = set()
    for view in members:
        fact = view.own.get(fact_name)
        if fact is None or fact.state is not FactState.KNOWN:
            return None, "not_established"
        modes.add(str(_norm(fact.value)))
    if len(modes) != 1:
        return None, "not_established"
    mode = modes.pop()
    if vendor == "checkpoint":
        if mode in _CP_LOAD_SHARING_MODES:
            return mode, "not_a_failover_unit"
        return mode, "ok" if mode in CP_SUPPORTED_FAILOVER_MODES else "unsupported"
    return mode, "ok" if mode in PAN_SUPPORTED_FAILOVER_MODES else "unsupported"


def _state_reason(fact: PreflightFact | None, name: str) -> str:
    if fact is None:
        return f"not_collected:{name}"
    return f"{fact.state.value}:{name}"


def _lookup(view: _MemberView, rule: FactRule) -> PreflightFact | None:
    if rule.source == "link_observation":
        return view.link.get(rule.fact)
    return view.own.get(rule.fact)


def _evaluate_check(
    spec: CheckEvidenceSpec,
    *,
    vendor: str,
    label: str,
    members: Sequence[_MemberView],
    expected_members: int,
    prerequisites_ok: bool,
    prerequisite_reason: str | None,
    is_vs_unit: bool,
) -> dict[str, Any]:
    facts_consulted: set[str] = set()

    def _result(status: str, reason: str, missing: str = "") -> dict[str, Any]:
        return {
            "id": spec.check_id, "label": label, "status": status, "reason": reason,
            "missing_evidence": missing, "facts": sorted(facts_consulted),
        }

    if spec.not_evaluable_reason:
        return _result(CHECK_INSUFFICIENT, spec.not_evaluable_reason, spec.missing_evidence)

    # 1. Explicit, single-member known-bad facts. A device's own report of a
    #    frozen dangerous state fails the check even when the snapshot as a
    #    whole is incoherent -- the fact stands on its own. Never from an
    #    absent / failed / unsupported read.
    for view in members:
        for rule in spec.positive_facts:
            if not rule.failure:
                continue
            fact = _lookup(view, rule)
            if fact is None:
                continue
            facts_consulted.add(rule.fact)
            if _fact_matches(fact, rule.failure) is True:
                if is_vs_unit and rule.fact in _VS_CONTEXT_UNTRUSTED_KNOWN_BAD and view.context_kind is not ContextKind.PHYSICAL:
                    # D-V9a: contradictory non-VS0 read -> UNKNOWN, never KNOWN_BAD.
                    continue
                return _result(CHECK_FAIL, rule.failure_reason)

    # 2. Cross-member conclusions need a coherent, fully-attributed snapshot
    #    covering every member of the unit -- both for a positive result and
    #    for a cross-member failure (two actives from different runs are not
    #    split-brain evidence).
    if not prerequisites_ok:
        return _result(CHECK_INSUFFICIENT, prerequisite_reason or "preflight_prerequisites_not_met", spec.missing_evidence)
    if expected_members < 2 or len(members) < expected_members:
        return _result(CHECK_INSUFFICIENT, "peer_not_independently_observed", spec.missing_evidence)

    # 3. Every positive fact KNOWN and acceptable on every member.
    for view in members:
        for rule in spec.positive_facts:
            fact = _lookup(view, rule)
            facts_consulted.add(rule.fact)
            matched = _fact_matches(fact, rule.acceptable)
            if matched is None:
                return _result(CHECK_INSUFFICIENT, _state_reason(fact, rule.fact), spec.missing_evidence)
            if matched is False:
                if is_vs_unit and rule.fact in _VS_CONTEXT_UNTRUSTED_KNOWN_BAD and view.context_kind is not ContextKind.PHYSICAL:
                    return _result(CHECK_INSUFFICIENT, f"non_vs0_context_read_not_trusted:{rule.fact}", spec.missing_evidence)
                return _result(CHECK_INSUFFICIENT, f"value_not_established:{rule.fact}", spec.missing_evidence)

    # 4. Cross-member predicate.
    if spec.predicate == "standby_capable_member":
        role_fact = "ha_local_role" if vendor == "checkpoint" else "local_state"
        standby = _CP_STANDBY_CAPABLE_ROLES if vendor == "checkpoint" else _PAN_STANDBY_CAPABLE_STATES
        roles = [str(_norm(v.own[role_fact].value)) for v in members]
        if not any(r in {_norm(s) for s in standby} for r in roles):
            return _result(CHECK_FAIL, "no_viable_target")
    elif spec.predicate == "exactly_one_active":
        role_fact = "ha_local_role" if vendor == "checkpoint" else "local_state"
        active = _CP_ACTIVE_ROLES if vendor == "checkpoint" else _PAN_ACTIVE_STATES
        roles = [str(_norm(v.own[role_fact].value)) for v in members]
        actives = [r for r in roles if r in {_norm(a) for a in active}]
        if len(actives) > 1:
            return _result(CHECK_FAIL, "split_brain_observed")
        if not actives:
            return _result(CHECK_INSUFFICIENT, "no_active_member_observed", spec.missing_evidence)
    elif spec.predicate == "member_equality":
        for name in spec.predicate_facts:
            values = {_norm(v.own[name].value) for v in members}
            if len(values) != 1:
                return _result(CHECK_FAIL, f"{name}_mismatch_observed")

    # 5. An open numeric policy makes PASS unreachable regardless of evidence.
    if spec.policy_gate:
        return _result(CHECK_INSUFFICIENT, f"threshold_policy_unresolved:{spec.policy_gate}", spec.missing_evidence)

    return _result(CHECK_PASS, "positively_established_in_run")


# --- OP.0b S8-C real-env correction: fresh PAN pair correspondence --------

_CORRESPONDENCE_MATCH = "MATCH"
_CORRESPONDENCE_MISMATCH = "MISMATCH"
_CORRESPONDENCE_MISSING = "MISSING"
_CORRESPONDENCE_NOT_EVALUABLE = "NOT_EVALUABLE"
_CORRESPONDENCE_AMBIGUOUS = "AMBIGUOUS"


def _pan_relationship(a: PreflightFact | None, b: PreflightFact | None) -> str:
    """AGENTS.md "Sensitive identity reporting law" vocabulary: compare two
    already-opaque fact values locally, report only the relationship, never
    the values (both are already `OpaqueToken`s by construction for every
    S8-C address/identity field this is called on)."""
    if a is None or b is None or a.state is not FactState.KNOWN or b.state is not FactState.KNOWN:
        return _CORRESPONDENCE_MISSING
    return _CORRESPONDENCE_MATCH if _norm(a.value) == _norm(b.value) else _CORRESPONDENCE_MISMATCH


def _pan_reciprocal_correspondence(members: Sequence[_MemberView]) -> dict[str, Any]:
    """OP.0b S8-C real-env correction: fresh, post-contact PAN pair
    correspondence from this run's own already-collected `P1` (dialed
    endpoint) / `P2` (`mgmt-ip` self-report and peer-claim, `mode`,
    best-effort `group-id`) evidence only -- no new field, no new read, and
    deliberately NOT the config-intent (`peer-ip` == `management_ip`)
    heuristic `_derive_pan_units` uses (that heuristic is REAL_ENV_DISPROVEN
    as a universal invariant; see that function's docstring).

    Purely descriptive. Never gates any of the seven canonical checks (that
    stays `_pair_identity_state`'s job in `utils.failover.assessment`) and
    never establishes PAN `B2` -- `B2` is the frozen, stronger, bidirectional
    identity-corroboration requirement (AGENTS.md "one-sided peer claim is
    not bidirectional corroboration"). This answers a narrower, read-only
    question instead: do these two independently P1-gated, explicitly
    bounded devices mutually report one another as their HA management
    peers, in the same mode? Reported honestly for a human/PO to weigh
    toward `B2`, never auto-promoted (task §12/§20: "no false B2 promotion";
    `group_id_correspondence` never participates in `state` at all -- its
    XML path is unconfirmed, corroborating only, per
    `configuration.panorama_config_collector._PAN_HA_GROUP_ID_PATH`).
    """
    if len(members) != 2:
        return {"state": _CORRESPONDENCE_NOT_EVALUABLE, "reason": "peer_not_independently_observed"}

    by_identity = {v.identity: v for v in members}
    if len(by_identity) != len(members):
        return {"state": _CORRESPONDENCE_AMBIGUOUS, "reason": "duplicate_member_identity"}
    ids = sorted(by_identity)
    a, b = by_identity[ids[0]], by_identity[ids[1]]

    self_a = _pan_relationship(a.own.get("local_mgmt_ip_claim"), a.own.get("local_management_endpoint"))
    self_b = _pan_relationship(b.own.get("local_mgmt_ip_claim"), b.own.get("local_management_endpoint"))
    a_claims_b = _pan_relationship(a.peer_claims.get("peer_mgmt_ip_claim"), b.own.get("local_management_endpoint"))
    b_claims_a = _pan_relationship(b.peer_claims.get("peer_mgmt_ip_claim"), a.own.get("local_management_endpoint"))
    mode_state = _pan_relationship(a.own.get("local_mode"), b.own.get("local_mode"))
    group_state = _pan_relationship(a.own.get("ha_group_id"), b.own.get("ha_group_id"))
    if group_state == _CORRESPONDENCE_MISSING:
        group_state = _CORRESPONDENCE_NOT_EVALUABLE  # best-effort field, unconfirmed path -- never "missing"

    signals = (self_a, self_b, a_claims_b, b_claims_a, mode_state)
    if any(s == _CORRESPONDENCE_MISMATCH for s in signals):
        overall = _CORRESPONDENCE_MISMATCH
    elif any(s == _CORRESPONDENCE_MISSING for s in signals):
        overall = _CORRESPONDENCE_MISSING
    else:
        overall = _CORRESPONDENCE_MATCH

    return {
        "state": overall,
        "self_management_correspondence": {ids[0]: self_a, ids[1]: self_b},
        "reciprocal_peer_management_correspondence": {
            f"{ids[0]}_claims_{ids[1]}": a_claims_b,
            f"{ids[1]}_claims_{ids[0]}": b_claims_a,
        },
        "mode_correspondence": mode_state,
        "group_id_correspondence": group_state,
    }


def evaluate_snapshot_checks(
    snapshot: PreflightSnapshot,
    *,
    unit_id: str,
    vendor: str,
    unit_member_count: int,
    is_vs_unit: bool,
    pair_identity: str,
) -> SnapshotEvaluation:
    """Interpret one unit's `PreflightSnapshot` into the seven canonical
    checks. Called only by `utils.failover.assessment._evaluate_checks`;
    returns check statuses and disclosure, never a verdict.

    `pair_identity` is the unit-derivation layer's own statement of how the
    unit's membership was established (`assessment` decides it; this
    function only refuses cross-member PASS when it is not established).
    """
    coherence = evaluate_coherence(snapshot)
    attribution = _attribution_problems(unit_id, unit_member_count, snapshot)
    views = _member_views(snapshot)
    gates = {v.identity_gate for v in views}
    attributable = [v for v in views if v.identity_gate == "ok"]
    mode, mode_gate = _effective_mode(vendor, attributable) if attributable else (None, "not_established")

    prerequisites: dict[str, Any] = {
        "attribution": "ok" if not attribution else "invalid",
        "attribution_problems": attribution,
        "identity_gate": "ok" if gates == {"ok"} else ("failed" if "failed" in gates else "not_recorded"),
        "coherence": "ok" if coherence.coherent else "incoherent",
        "mode": mode_gate,
        "pair_identity": pair_identity,
        "members_observed": len(attributable),
        "members_expected": unit_member_count,
    }
    reason: str | None = None
    if attribution:
        reason = "preflight_member_attribution_invalid"
    elif prerequisites["identity_gate"] != "ok":
        reason = "identity_gate_failed" if prerequisites["identity_gate"] == "failed" else "identity_gate_not_recorded"
    elif not coherence.coherent:
        reason = "preflight_snapshot_incoherent"
    elif mode_gate == "not_established":
        reason = "ha_mode_not_established"
    elif mode_gate == "unsupported":
        reason = "ha_mode_unsupported"
    elif mode_gate == "not_a_failover_unit":
        reason = "not_a_failover_unit_mode"
    elif pair_identity == "not_established":
        reason = "peer_not_independently_observed"
    prerequisites_ok = reason is None

    # Members whose identity gate failed contribute nothing -- not even a
    # known-bad fact -- because their evidence cannot be attributed.
    usable = attributable if prerequisites["identity_gate"] == "ok" else []
    if attribution:
        usable = []

    checks: list[dict[str, Any]] = []
    for check_id, label in STOP_CONDITIONS:
        spec = FACT_CHECK_MAP[(vendor, check_id)]
        checks.append(_evaluate_check(
            spec, vendor=vendor, label=label, members=usable, expected_members=unit_member_count,
            prerequisites_ok=prerequisites_ok, prerequisite_reason=reason, is_vs_unit=is_vs_unit,
        ))

    observed: dict[str, Any] = {}
    for view in usable:
        for name in _OBSERVED_DISCLOSURE_FACTS:
            fact = view.own.get(name)
            if fact is not None and fact.state is FactState.KNOWN and isinstance(fact.value, (bool, int)):
                observed.setdefault(name, []).append(fact.value)
            elif fact is not None and fact.state is FactState.KNOWN and isinstance(fact.value, str) and len(fact.value) <= 24:
                observed.setdefault(name, []).append(fact.value)

    # Disclosure only, unchanged shape: which open-policy questions this
    # snapshot's evidence touches. CP pilot readiness-policy amendment: the
    # roll-up (`assessment.UNRESOLVED_POLICY_DECISIONS`) no longer treats
    # `D-F2`/`D-F3` membership here as blocking -- both were DECIDED to carry
    # no threshold, permanently -- so listing them below stays honest ("no
    # bound was ever set") without gating anything. Only `D-F1` still blocks.
    policy_gates = [POLICY_D_F3]
    if coherence.member_skew_ms is not None:
        policy_gates.append(POLICY_D_F2)
    if snapshot.configuration_facts:
        policy_gates.append(POLICY_D_F1)

    # OP.0b S8-C real-env correction: fresh, post-contact PAN pair
    # correspondence -- descriptive disclosure only, computed over exactly
    # the same `usable` (attribution-valid, identity-gate-passed) members the
    # seven checks above already use; never gates a check, never PAN B2.
    pan_pair_correspondence = _pan_reciprocal_correspondence(usable) if vendor == "panorama" else None

    evidence = {
        "basis": EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT,
        "preflight_run_id": snapshot.preflight_run_id,
        "coherent": coherence.coherent,
        "coherence_reasons": list(coherence.reasons[:8]),
        "member_skew_ms": coherence.member_skew_ms,
        "member_skew_policy": f"recorded_not_bounded:{POLICY_D_F2}",
        "stale_intent_present": coherence.stale_intent_present,
        "configuration_intent_freshness": (
            f"not_evaluable:{POLICY_D_F1}" if snapshot.configuration_facts else "no_configuration_intent_facts"
        ),
        "prerequisites": prerequisites,
        "unresolved_policy_gates": sorted(set(policy_gates)),
        "observed": observed,
    }
    if pan_pair_correspondence is not None:
        evidence["pan_pair_correspondence"] = pan_pair_correspondence
    return SnapshotEvaluation(checks=checks, evidence=evidence, effective_mode=mode)
