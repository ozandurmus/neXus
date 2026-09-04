"""SecurityExpert — HA readiness assessment (OP.0a).

Contract: docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md.
Architecture: docs/design/FAILOVER_ENGINE_ARCHITECTURE.md §4 (ordered
stop-conditions), §3.1/§3.2 (per-vendor), §7 (engine layout).

Answers "what do we actually know about this cluster's failover readiness, and
what would we still have to ask a device?" — using only evidence the platform
already holds. **This module never talks to a device**, issues no command, and
holds no credential; it is offline derivation, the same posture as
`utils/restore_readiness.py`.

It deliberately **cannot** say a cluster is safe to fail over. See P4 below.

Entity identity follows the same convention the configuration-evidence
collectors use (`configuration/checkpoint_config_collector.py _entity_id`), via
`utils.restore_readiness.resolve_entity_id` — one shared resolver, not a
re-derivation.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import TYPE_CHECKING, Any, Mapping, Sequence

from utils.restore_readiness import resolve_entity_id, resolve_vendor

if TYPE_CHECKING:  # pragma: no cover - typing only; no runtime import cycle
    from utils.failover.preflight_model import PreflightSnapshot

SCHEMA = "securityexpert-ha-readiness-v1"

# --- Verdict vocabulary (contract P4, frozen) --------------------------------
VERDICT_SAFE = "SAFE_TO_FAILOVER"
VERDICT_DEGRADED = "DEGRADED_PROCEED_WITH_RISK"
VERDICT_UNSAFE = "UNSAFE_DO_NOT_FAILOVER"
VERDICT_INSUFFICIENT = "INSUFFICIENT_EVIDENCE"
VERDICT_NOT_A_FAILOVER_UNIT = "NOT_A_FAILOVER_UNIT"

CHECK_PASS = "PASS"
CHECK_FAIL = "FAIL"
CHECK_INSUFFICIENT = "INSUFFICIENT_EVIDENCE"

#: The seven ordered stop-conditions of architecture §4, in order. Every unit's
#: check list carries all seven, always — a condition we cannot evaluate is
#: reported as INSUFFICIENT_EVIDENCE against a named missing command, never
#: silently omitted (contract correctness rule 6).
STOP_CONDITIONS: tuple[tuple[str, str], ...] = (
    ("viable_target", "A standby/passive peer exists that could carry the traffic"),
    ("state_sync_current", "State/session sync is complete and current"),
    ("parity", "Version / policy / content parity between the peers"),
    ("no_split_brain", "No existing split-brain or election instability"),
    ("control_sync_link_health", "Control and sync link health (CP CCP / PAN HA1+HA2)"),
    ("preemption_known", "Preemption configuration is known (determines rollback impact)"),
    ("flap_history", "No recent failover/monitored-path flapping"),
)

#: The ONLY stop-conditions OP.0a has evidence for. Everything else is forced
#: to INSUFFICIENT_EVIDENCE by `_evaluate_checks`, regardless of what any future
#: edit computes — this is contract decision P4 expressed as a guard rather than
#: a convention, and `AC-6` proves SAFE_TO_FAILOVER stays unreachable because of
#: it. Widening this set is an OP.0b change and must come with its command gate.
OP0A_EVALUABLE_CHECKS = frozenset({"viable_target", "no_split_brain"})

# --- Evidence basis + open-policy vocabulary (OP.0b S7) ---------------------
#: Which evidence a unit's checks were interpreted from. `op0a_stored_telemetry`
#: is the OP.0a path (already-collected `cphaprob stat` / `show
#: high-availability state` rows read off disk, no run coherence); `op0b_
#: preflight_snapshot` is a fresh S5/S6 `PreflightSnapshot` interpreted by
#: `utils.failover.preflight_readiness`. One unit is evaluated from exactly
#: one basis -- stored telemetry and a fresh snapshot are never blended.
EVIDENCE_BASIS_STORED_TELEMETRY = "op0a_stored_telemetry"
EVIDENCE_BASIS_PREFLIGHT_SNAPSHOT = "op0b_preflight_snapshot"

#: A VSX Virtual System's own HA state is out of scope for the approved S8-B
#: physical-parent battery -- B1 (`vsx stat -v`) is VS enumeration/count only
#: (`project_cp_vsx_enumeration_facts`), never a per-VS HA-state read. When
#: this run's fresh preflight snapshot covers the VS's physical parent, the
#: generic OP.0a "no preflight battery exists" reasons below are actively
#: misleading -- a battery just ran, for the parent. This reason states the
#: true, narrower fact instead (real-env finding, S8-B VSX operator review).
#: Never a verdict change: the check still reports INSUFFICIENT_EVIDENCE.
REASON_VS_STATE_OUT_OF_PHYSICAL_SCOPE_BATTERY = "vs_state_out_of_physical_scope_preflight_battery"
_VS_OUT_OF_SCOPE_MISSING_EVIDENCE = (
    "OP.0b VSX battery (B1 vsx stat -v) is VS enumeration/count only, not a per-VS HA-state read"
)

#: Open product-owner numeric decisions (frozen OP.0b.0 contract, §"Open
#: decisions"). No TTL, skew tolerance or flap threshold is chosen anywhere
#: in this package; while any of these applies to a unit's evidence, a
#: positive verdict for that unit stays unreachable (`_verdict_for`).
POLICY_D_F1 = "D-F1"  # configuration-intent max age
POLICY_D_F2 = "D-F2"  # member-skew tolerance
POLICY_D_F3 = "D-F3"  # flap / failover-frequency threshold
UNRESOLVED_POLICY_DECISIONS = frozenset({POLICY_D_F1, POLICY_D_F2, POLICY_D_F3})

#: What OP.0b would have to ask to close each gap. These strings are the only
#: command text this module emits; they are fixed labels, never device output.
_MISSING_EVIDENCE: Mapping[str, Mapping[str, str]] = {
    "checkpoint": {
        "state_sync_current": "cphaprob syncstat, fw ctl pstat (OP.0b)",
        "parity": "fw stat, cpinfo -y all (OP.0b)",
        "control_sync_link_health": "cphaprob -a if, cphaprob -l list (OP.0b)",
        "preemption_known": "cluster object preemption setting (OP.0b)",
        "flap_history": "cphaprob stat uptime / cluster event log (OP.0b)",
        "viable_target": "cphaprob stat (not collected for this entity)",
        "no_split_brain": "cphaprob stat (not collected for this entity)",
    },
    "panorama": {
        "state_sync_current": "show high-availability state-synchronization (OP.0b)",
        "parity": "show system info per peer (OP.0b)",
        "control_sync_link_health": "show high-availability all, path/link-monitoring (OP.0b)",
        "preemption_known": "show high-availability all (preemptive) (OP.0b)",
        "flap_history": "show high-availability all (flap counters) (OP.0b)",
        "viable_target": "show high-availability state (not collected for this entity)",
        "no_split_brain": "show high-availability state (not collected for this entity)",
    },
}

# CP ClusterXL runtime role vocabulary, split by what it means for a failover.
_CP_ACTIVE_ROLES = {"ACTIVE", "ACTIVE ATTENTION"}
_CP_STANDBY_CAPABLE_ROLES = {"STANDBY", "STANDBY READY", "READY", "BACKUP"}

_CP_LOAD_SHARING_MODES = {"load_sharing_unicast", "load_sharing_multicast"}

_PAN_ACTIVE_STATES = {"active", "active-primary"}
_PAN_STANDBY_CAPABLE_STATES = {"passive", "active-secondary"}

_UNIT_CP_CLUSTER = "cp_clusterxl_cluster"
_UNIT_CP_VSX_HOST = "cp_vsx_host"
_UNIT_CP_VSX_CLUSTER = "cp_vsx_cluster"
_UNIT_CP_VSX_VS = "cp_vsx_virtual_system"
_UNIT_PAN_PAIR = "pan_ha_pair"

_INSUFFICIENT_DATA_STATES = {"no_data"}


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


@dataclass
class HaUnit:
    """One failover unit. A VSX host and each of its virtual systems are
    distinct units; a VS never inherits its physical host's verdict (contract
    correctness rule 7, the RB.3a decision-A3 principle)."""

    unit_id: str
    unit_type: str
    vendor: str
    members: list[str] = field(default_factory=list)
    cluster_mode: str = "unknown"
    display_name: str | None = None
    parent_id: str | None = None
    #: Set only by `_derive_pan_units` for a single-member PAN fallback unit,
    #: to distinguish *why* it stayed unresolved (contract OP.0a.P7) --
    #: never read by anything CP-side. `None` keeps the existing generic
    #: `pan_ha_peer_unresolved` reason at the `compute_ha_readiness` call
    #: site.
    unresolved_reason: str | None = None
    #: OP.0b S8-C real-env correction. `True` only for a unit
    #: `_apply_pan_explicit_candidate` built from an operator's explicit,
    #: bounded `--pan-preflight-targets` selection -- never set by
    #: `_derive_pan_units`'s normal stored-telemetry derivation. Distinguishes
    #: an operator-bounded CANDIDATE pair (identity independently P1-gated
    #: per member, pair correspondence not yet Grade-A proven) from a
    #: `_derive_pan_units`-established Grade-A configuration-intent pair, so
    #: `_pair_identity_state` never reports the stronger grade for evidence
    #: that does not support it (task §10/§20: "no false Grade-A promotion").
    explicit_candidate: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "unit_id": self.unit_id,
            "unit_type": self.unit_type,
            "vendor": self.vendor,
            "members": list(self.members),
            "cluster_mode": self.cluster_mode,
            "display_name": self.display_name,
            "parent_id": self.parent_id,
            # OP.0b.0 §26 X-4: serialised additively (S7) so the pair-identity
            # axis is visible separately from the verdict `reason`.
            "unresolved_reason": self.unresolved_reason,
            "explicit_candidate": self.explicit_candidate,
        }


@dataclass
class UnitAssessment:
    unit: HaUnit
    verdict: str
    reason: str
    checks: list[dict[str, Any]] = field(default_factory=list)
    #: Safe provenance/coherence disclosure (S7): which evidence basis the
    #: checks were read from, which preflight run, coherence state, open
    #: policy gates -- never raw evidence, never an identity.
    evidence: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        record = self.unit.to_dict()
        record["verdict"] = self.verdict
        record["reason"] = self.reason
        record["checks"] = list(self.checks)
        record["evidence"] = dict(self.evidence)
        return record


def _check(check_id: str, label: str, status: str, reason: str, missing: str = "") -> dict[str, Any]:
    return {
        "id": check_id,
        "label": label,
        "status": status,
        "reason": reason,
        "missing_evidence": missing,
        "facts": [],
    }


def _cp_roles(members: Sequence[str], cp_ha_runtime: Mapping[str, Mapping[str, Any]]) -> list[str]:
    roles = []
    for entity_id in members:
        role = str((cp_ha_runtime.get(_normalize_cp_entity_key(entity_id)) or {}).get("ha_role") or "").strip().upper()
        if role:
            roles.append(role)
    return roles


def _pan_states(members: Sequence[str], pan_ha_runtime: Mapping[str, Mapping[str, Any]]) -> list[str]:
    """Observed HA states for a PAN unit, counted once per physical peer.

    Each PAN device reports both its own `state` and its view of the peer's
    (`peer_state`). Naively collecting both from every member double-counts a
    resolved pair -- a perfectly healthy active/passive pair (01: active,
    peer=passive; 02: passive, peer=active) yields two "active" observations
    and is misreported as split-brain. Found by the OP.0a smoke run, not by
    the unit tests, which had paired only same-shaped records.

    So: trust each member's own `state` only. `peer_state` is never counted
    -- not even as a fallback for a single-member unit. That fallback (the
    "phantom member" uplift, OP.0b.0 §26 PAN-4 / AC-5) was removed by OP.0b
    S7: `peer_state` is relationship evidence about a claimed peer, never an
    independently observed physical member, and a single-member unit now
    reports `peer_not_independently_observed` for the two cross-member
    checks instead of a PASS synthesised from one side's claim.
    """
    states: list[str] = []
    for entity_id in members:
        record = pan_ha_runtime.get(entity_id) or {}
        state = str(record.get("state") or "").strip().lower()
        if state:
            states.append(state)
    return states


def _pair_identity_state(unit: HaUnit) -> str:
    """How the unit-derivation layer established this unit's membership --
    the `pair_identity` prerequisite the snapshot evaluator refuses to
    PASS cross-member checks without. CP: `cluster_topology.group_id`
    (mutual VIP set). PAN: mutual configured `peer-ip` agreement -- Grade A
    configuration intent under the frozen hostname-keyed fallback, never
    runtime-proven; `B2` stays NOT ESTABLISHED and is not asserted here.

    OP.0b S8-C real-env correction: an explicit, operator-bounded preflight
    candidate pair (`HaUnit.explicit_candidate`) is a THIRD, distinct state
    -- not "not_established" (both members were independently selected and,
    by the time this unit is evaluated, both have independently passed P1;
    cross-member checks over their fresh evidence are meaningful and must
    run) and not "established_configuration_intent" (no Grade-A config-intent
    match was ever proven for this pair; claiming that grade would be a
    false promotion, task §10/§20). Its own literal string never appears in
    `evaluate_snapshot_checks`'s gate (only the literal `"not_established"`
    is special-cased there), so the distinction is legible in `evidence`
    disclosure without changing which checks are reachable.
    """
    if len(unit.members) < 2:
        return "not_established"
    if unit.explicit_candidate:
        return "explicit_bounded_candidate_pending_correspondence"
    if unit.vendor == "panorama":
        return "established_configuration_intent"
    return "established_topology_group"


def _evaluate_checks(
    unit: HaUnit,
    *,
    cp_ha_runtime: Mapping[str, Mapping[str, Any]],
    pan_ha_runtime: Mapping[str, Mapping[str, Any]],
    snapshot: "PreflightSnapshot | None" = None,
    parent_preflight_applied: bool = False,
) -> tuple[list[dict[str, Any]], dict[str, Any], str | None]:
    """Evaluate all seven §4 stop-conditions from exactly one evidence basis.

    With a fresh S5/S6 `snapshot` for this unit, every check is interpreted
    by the one typed fact→check mapping in
    `utils.failover.preflight_readiness` (OP.0b S7) -- the stored-telemetry
    rows are not consulted for that unit at all, so old and fresh evidence
    never blend into one result (frozen contract, "Provenance contract").

    Without one, the OP.0a stored-telemetry path applies: only the members
    of `OP0A_EVALUABLE_CHECKS` can return PASS/FAIL; every other condition is
    forced to INSUFFICIENT_EVIDENCE here, at one place, so that no future
    edit elsewhere can make a green light reachable without changing that
    frozenset and its gate (contract P4).

    `parent_preflight_applied` (VSX VS units only): true when THIS run's
    fresh preflight snapshot was applied to the VS's physical parent (the
    only unit the approved S8-B battery evaluates). The VS itself still gets
    no snapshot of its own -- unchanged -- but its INSUFFICIENT_EVIDENCE
    reason names the real, narrower cause (`REASON_VS_STATE_OUT_OF_
    PHYSICAL_SCOPE_BATTERY`) instead of the generic OP.0a "no preflight
    battery exists" wording, which is false in this case. Never a verdict or
    evidence-basis change.

    Returns `(checks, evidence, effective_mode)`; `effective_mode` is the
    fresh HA mode a snapshot established (or `None`).
    """
    if snapshot is not None:
        from utils.failover.preflight_readiness import evaluate_snapshot_checks

        evaluation = evaluate_snapshot_checks(
            snapshot,
            unit_id=unit.unit_id,
            vendor=unit.vendor,
            unit_member_count=len(unit.members),
            is_vs_unit=unit.unit_type == _UNIT_CP_VSX_VS,
            pair_identity=_pair_identity_state(unit),
        )
        return evaluation.checks, evaluation.evidence, evaluation.effective_mode

    missing_for_vendor = _MISSING_EVIDENCE.get(unit.vendor, {})
    vs_out_of_scope = unit.unit_type == _UNIT_CP_VSX_VS and parent_preflight_applied

    if unit.vendor == "checkpoint":
        observed = _cp_roles(unit.members, cp_ha_runtime)
        active = [r for r in observed if r in _CP_ACTIVE_ROLES]
        standby = [r for r in observed if r in _CP_STANDBY_CAPABLE_ROLES]
    else:
        observed = _pan_states(unit.members, pan_ha_runtime)
        active = [s for s in observed if s in _PAN_ACTIVE_STATES]
        standby = [s for s in observed if s in _PAN_STANDBY_CAPABLE_STATES]

    # Absence of evidence != evidence of absence (S7): "no standby observed"
    # is a cross-member conclusion and needs every member's own state. A
    # one-sided read (fewer own observations than members, or a single-
    # member unit) can still expose an explicit split-brain (two actives
    # are decisive on their own) but never a `no_viable_target`.
    all_members_observed = len(observed) >= len(unit.members) and len(unit.members) >= 2

    checks: list[dict[str, Any]] = []
    for check_id, label in STOP_CONDITIONS:
        if check_id not in OP0A_EVALUABLE_CHECKS:
            if vs_out_of_scope:
                checks.append(_check(
                    check_id, label, CHECK_INSUFFICIENT,
                    REASON_VS_STATE_OUT_OF_PHYSICAL_SCOPE_BATTERY, _VS_OUT_OF_SCOPE_MISSING_EVIDENCE,
                ))
            else:
                checks.append(_check(
                    check_id, label, CHECK_INSUFFICIENT,
                    "not_evaluable_without_preflight_battery",
                    missing_for_vendor.get(check_id, "OP.0b preflight battery"),
                ))
            continue

        if not observed:
            if vs_out_of_scope:
                checks.append(_check(
                    check_id, label, CHECK_INSUFFICIENT,
                    REASON_VS_STATE_OUT_OF_PHYSICAL_SCOPE_BATTERY, _VS_OUT_OF_SCOPE_MISSING_EVIDENCE,
                ))
            else:
                checks.append(_check(
                    check_id, label, CHECK_INSUFFICIENT, "no_ha_runtime_evidence_for_unit",
                    missing_for_vendor.get(check_id, "OP.0b preflight battery"),
                ))
            continue

        if check_id == "viable_target":
            if not all_members_observed:
                checks.append(_check(
                    check_id, label, CHECK_INSUFFICIENT, "peer_not_independently_observed",
                    missing_for_vendor.get(check_id, "OP.0b preflight battery"),
                ))
            elif standby:
                checks.append(_check(check_id, label, CHECK_PASS, "standby_capable_member_observed"))
            else:
                checks.append(_check(check_id, label, CHECK_FAIL, "no_viable_target"))
        elif check_id == "no_split_brain":
            if len(active) > 1:
                checks.append(_check(check_id, label, CHECK_FAIL, "split_brain_observed"))
            elif not all_members_observed:
                checks.append(_check(
                    check_id, label, CHECK_INSUFFICIENT, "peer_not_independently_observed",
                    missing_for_vendor.get(check_id, "OP.0b preflight battery"),
                ))
            elif active:
                checks.append(_check(check_id, label, CHECK_PASS, "exactly_one_active_member"))
            else:
                checks.append(_check(
                    check_id, label, CHECK_INSUFFICIENT, "no_active_member_observed",
                    missing_for_vendor.get(check_id, "OP.0b preflight battery"),
                ))
    evidence = {
        "basis": EVIDENCE_BASIS_STORED_TELEMETRY,
        "preflight_run_id": None,
        "coherent": None,
        "member_skew_ms": None,
        "members_observed": len(observed),
        "members_expected": len(unit.members),
        "unresolved_policy_gates": [],
    }
    return checks, evidence, None


def _verdict_for(
    unit: HaUnit,
    checks: Sequence[Mapping[str, Any]],
    evidence: Mapping[str, Any] | None = None,
) -> tuple[str, str]:
    """Fail-closed verdict (contract P3/P4). **The one readiness roll-up.**

    `SAFE_TO_FAILOVER` requires **every** §4 stop-condition to have positively
    passed *and* no open numeric policy decision (`UNRESOLVED_POLICY_DECISIONS`)
    to apply to the evidence the checks were read from. On the OP.0a
    stored-telemetry basis `OP0A_EVALUABLE_CHECKS` covers only two of the
    seven, so SAFE is structurally unreachable — asserted by AC-6. On the
    OP.0b S7 preflight basis `flap_history` can never PASS while `D-F3` is
    open, and this gate additionally refuses SAFE while `D-F2`/`D-F1` apply,
    so it stays unreachable there too — asserted by the S7 suite over a
    generated snapshot matrix. `DEGRADED_PROCEED_WITH_RISK` is reserved in the
    vocabulary and never emitted here (open decision `op_degraded_verdict`,
    owed before OP.1).
    """
    # P3: a load-sharing cluster has no standby; "fail it over" is not a
    # coherent request, so it gets neither a safe nor an unsafe verdict.
    if unit.vendor == "checkpoint" and unit.cluster_mode in _CP_LOAD_SHARING_MODES:
        return VERDICT_NOT_A_FAILOVER_UNIT, "load_sharing_member_evacuation_not_failover"

    failures = [c for c in checks if c.get("status") == CHECK_FAIL]
    if failures:
        # Architecture §4 says "the first failure sets the verdict", and the
        # ordering there puts viable_target ahead of split-brain. Implementation
        # deviation, deliberate: a split-brained cluster ALSO has no standby, so
        # the §4 order would diagnose it as `no_viable_target` -- true, but the
        # symptom rather than the cause, and it points the operator at the wrong
        # remedy. Split-brain is reported as the reason whenever it is observed;
        # every other failure keeps §4's order.
        for candidate in failures:
            if candidate.get("reason") == "split_brain_observed":
                return VERDICT_UNSAFE, "split_brain_observed"
        return VERDICT_UNSAFE, str(failures[0].get("reason") or "stop_condition_failed")

    if all(c.get("status") == CHECK_PASS for c in checks) and len(checks) == len(STOP_CONDITIONS):
        open_gates = sorted(
            str(g) for g in ((evidence or {}).get("unresolved_policy_gates") or [])
            if str(g) in UNRESOLVED_POLICY_DECISIONS
        )
        if open_gates:
            return VERDICT_INSUFFICIENT, "positive_verdict_blocked_by_unresolved_policy:" + ",".join(open_gates)
        return VERDICT_SAFE, "all_stop_conditions_passed"

    return VERDICT_INSUFFICIENT, "stop_conditions_not_fully_evaluable"


_TRAILING_SEP_RE = re.compile(r"^(.*[1-5])[-_.]$")


def _join_device_key(name: str) -> str:
    """Normalize a physical device name for cross-collector identity joins
    only (never for display or for the entity/evidence identity itself).

    Historically, `checkpoint/scripts/cp_inventory.sh`'s `SAFE_GW=$(echo "$GW"
    | tr -c '[:alnum:]_-' '_')` line converted `echo`'s own trailing newline
    into a literal "_" (an echo/tr pipeline bug, not a real naming
    convention -- fixed at the source), so `cp.json`'s device key carried a
    spurious trailing separator that `checkpoint/vsx_runner.py`'s device
    names, read straight from `cpmiquerybin`, never had. Left unreconciled,
    the two collectors' `device` values fail an exact-string join -- a VSX
    physical member is then classified as plain ClusterXL (VSX-hosting-device
    match fails) and each of its Virtual Systems falls back to a standalone,
    member-scoped unit instead of merging under the shared physical parent
    (real-env finding, OP.VSX retry). Kept defensively for evidence collected
    before the source fix, or any other stray separator."""
    text = str(name or "").strip()
    match = _TRAILING_SEP_RE.match(text)
    return match.group(1) if match else text


def _normalize_cp_entity_key(entity_id: str) -> str:
    """Apply `_join_device_key` to just the device portion of a CP entity id,
    so a VSID suffix (`__vsid_<N>`) never hides the same cosmetic separator
    mismatch from a lookup.

    `configuration/checkpoint_config_collector.py` derives its own
    `cp_config_telemetry.json` entity id from its independently-resolved
    `PhysicalTarget.device` (real-env finding: this can inherit the
    trailing-separator-suffixed name, e.g. `FW-CKP-EXTRA-LL-1___vsid_2`),
    while this module's own entity ids come from `resolve_entity_id` on raw
    `vsx.json`/`cp.json` rows (e.g. `FW-CKP-EXTRA-LL-1__vsid_2`). Normalizing
    both `cp_ha_runtime`'s stored keys and every lookup key here the same way
    reconciles that without touching evidence identity."""
    text = str(entity_id or "").strip()
    if "__vsid_" in text:
        device, _, suffix = text.partition("__vsid_")
        return f"{_join_device_key(device)}__vsid_{suffix}"
    return _join_device_key(text)


def _normalize_cp_runtime(
    cp_ha_runtime: Mapping[str, Mapping[str, Any]],
) -> dict[str, Mapping[str, Any]]:
    return {_normalize_cp_entity_key(k): v for k, v in cp_ha_runtime.items()}


def _row_is_vsx(row: Mapping[str, Any], source: str) -> bool:
    """True when this physical-host row's OWN fields mark it as VSX -- either
    it came from `vsx.json` directly, or a `cp.json` row carries the CP
    inventory's own VSX flag (`checkpoint/cp_runner.py`'s
    `vsx_cluster_member`/legacy `vs_cluster_member`).

    This flag is never actually present on a real `source:"cp"` row in
    practice: `checkpoint/cp_runner.py::run_cp()` writes `cp.json` from
    `results` (interfaces/routes/cluster-interface parsing only), while
    `vsx_cluster_member` is set on a completely different list
    (`collection_status`) that becomes `cp_telemetry.json`'s
    `remote_command_status` -- it never merges into `cp.json`/`unified.json`.
    Kept as a forward-compatible check in case that ever changes; the
    evidence-based `vsx_hosting_devices` check in `_derive_cp_units` is what
    actually classifies real VSX physical hosts today."""
    if source == "vsx":
        return True
    for key in ("vsx_cluster_member", "vs_cluster_member"):
        if str(row.get(key) or "").strip().lower() in {"true", "yes", "1"}:
            return True
    return False


def _derive_cp_units(
    rows: Sequence[Mapping[str, Any]],
    cp_ha_runtime: Mapping[str, Mapping[str, Any]],
) -> list[HaUnit]:
    """Assemble CP failover units, cluster-centric (real-env finding, OP.0a
    identity/topology defect) and VSX-operational-identity-aware (real-env
    finding, OP.VSX).

    Canonical grouping identity is `cluster_topology.group_id` -- the stable,
    role-independent digest `checkpoint/cp_runner.py::enrich_cluster_topology`
    already attaches to both plain ClusterXL members and VSX physical hosts
    that run classic ClusterXL underneath (the standard VSX topology; no VSLS
    assumption is made or needed). The legacy flat `cluster` field is a
    fallback only, for fixtures/history predating that nested shape.
    `cluster_topology.display_name` is presentation only and never decides
    grouping.

    A physical device is classified VSX by evidence, not by a flag that never
    actually reaches `unified.json` for real CP rows (see `_row_is_vsx`):
    "this device has at least one associated Virtual System" (a
    `source:"vsx"` row naming it) is itself sufficient, already-collected
    proof.

    A VSX Virtual System's OPERATIONAL identity is `<physical_unit_id>__vsid_
    <N>` -- the same VSID observed through every physical member that reports
    it collapses into ONE logical unit, never one per (device, vsid) pair.
    The pre-existing `<device>__vsid_<N>` EVIDENCE-ENTITY identity is
    preserved unchanged inside that unit's `members` list; only the
    OPERATIONAL unit id and the checks computed over the merged member list
    change. A VS still never inherits its physical parent's verdict
    (correctness rule 7): its own seven checks are computed from its own
    aggregated evidence only. Fail-closed by construction, not by special
    case: grouping key is `(physical_unit_id, vsid)`, so a VSID can never
    merge across an unresolved or different physical parent (cases F/G), and
    the existing, unmodified check logic already returns a decisive or
    INSUFFICIENT_EVIDENCE verdict -- never a fabricated SAFE one -- for
    one-sided, conflicting-role, or conflicting-mode evidence (cases B/D/E),
    because it operates generically over however many member observations a
    unit actually has.
    """
    units: list[HaUnit] = []
    clusters: dict[str, list[str]] = {}
    cluster_display_names: dict[str, str] = {}
    cluster_is_vsx: dict[str, bool] = {}
    physical_unit_by_device: dict[str, str] = {}
    vs_rows: list[tuple[str, str, str, str]] = []  # (entity_id, physical device, vs_id, vsys name)

    vsx_hosting_devices = {
        _join_device_key(row.get("device"))
        for row in rows
        if str(row.get("source") or "").strip().lower() == "vsx" and str(row.get("vs_id") or "").strip()
    }

    for row in rows:
        source = str(row.get("source") or "").strip().lower()
        if source not in {"cp", "vsx"}:
            continue
        entity_id = resolve_entity_id(row)
        if not entity_id:
            continue
        vs_id = str(row.get("vs_id") or "").strip()
        device = str(row.get("device") or "").strip()

        # A VSX virtual system is always its own operational unit and never
        # joins its host's cluster grouping (correctness rule 7) -- deferred
        # until the physical-grouping pass below resolves a parent for it.
        if source == "vsx" and vs_id:
            vsys = str(row.get("vsys") or "").strip()
            vs_rows.append((entity_id, device, vs_id, vsys))
            continue

        is_vsx = _row_is_vsx(row, source) or _join_device_key(device) in vsx_hosting_devices
        topology = row.get("cluster_topology")
        topology = topology if isinstance(topology, Mapping) else {}
        group_id = str(topology.get("group_id") or "").strip()
        display_name = str(topology.get("display_name") or "").strip()
        legacy_cluster = str(row.get("cluster") or "").strip()
        cluster_key = group_id or legacy_cluster

        if cluster_key:
            clusters.setdefault(cluster_key, [])
            if entity_id not in clusters[cluster_key]:
                clusters[cluster_key].append(entity_id)
            cluster_is_vsx[cluster_key] = cluster_is_vsx.get(cluster_key, False) or is_vsx
            if display_name and cluster_key not in cluster_display_names:
                cluster_display_names[cluster_key] = display_name
        elif is_vsx:
            units.append(HaUnit(
                unit_id=entity_id, unit_type=_UNIT_CP_VSX_HOST, vendor="checkpoint",
                members=[entity_id],
                cluster_mode=str((cp_ha_runtime.get(_normalize_cp_entity_key(entity_id)) or {}).get("ha_cluster_mode") or "unknown"),
            ))
            physical_unit_by_device[_join_device_key(device)] = entity_id
        # A standalone CP gateway with no cluster identity is not an HA unit
        # at all and is omitted rather than reported as a broken one.

    for cluster_key, members in clusters.items():
        modes = {
            str((cp_ha_runtime.get(_normalize_cp_entity_key(m)) or {}).get("ha_cluster_mode") or "").strip()
            for m in members
        }
        modes.discard("")
        modes.discard("unknown")
        if cluster_is_vsx.get(cluster_key):
            # A grouped VSX physical pair is a VSX CLUSTER, distinct from a
            # single ungrouped VSX host (_UNIT_CP_VSX_HOST, unchanged above)
            # and from an ordinary non-VSX ClusterXL cluster. Additive type,
            # not a rename: existing single-host VSX fixtures/tests are
            # unaffected.
            unit_type = _UNIT_CP_VSX_CLUSTER if len(members) > 1 else _UNIT_CP_VSX_HOST
        else:
            unit_type = _UNIT_CP_CLUSTER
        units.append(HaUnit(
            unit_id=cluster_key,
            unit_type=unit_type,
            vendor="checkpoint",
            members=sorted(members),
            display_name=cluster_display_names.get(cluster_key),
            # Members disagreeing on the mode is itself unresolved evidence.
            cluster_mode=modes.pop() if len(modes) == 1 else "unknown",
        ))
        for member_device in members:
            physical_unit_by_device.setdefault(_join_device_key(member_device), cluster_key)

    # Aggregate VS evidence entities into one logical VS operational unit per
    # (resolved physical parent, vsid). A VSID observed only by a device whose
    # own physical parent never resolved stays a standalone singleton, keyed
    # by its evidence-entity id exactly as before -- never guessed into a
    # cluster, and never merged with a same-numbered VSID under a different
    # or absent parent (cases F/G).
    resolved_groups: dict[tuple[str, str], list[str]] = {}
    resolved_vsys: dict[tuple[str, str], str] = {}
    orphans: list[tuple[str, str, str]] = []  # (entity_id, vs_id, vsys) with no resolved parent
    for entity_id, device, vs_id, vsys in vs_rows:
        parent_unit_id = physical_unit_by_device.get(_join_device_key(device))
        if parent_unit_id is None:
            orphans.append((entity_id, vs_id, vsys))
            continue
        key = (parent_unit_id, vs_id)
        resolved_groups.setdefault(key, []).append(entity_id)
        if vsys and key not in resolved_vsys:
            resolved_vsys[key] = vsys

    for (parent_unit_id, vs_id), member_entity_ids in resolved_groups.items():
        modes = {
            str((cp_ha_runtime.get(_normalize_cp_entity_key(m)) or {}).get("ha_cluster_mode") or "").strip()
            for m in member_entity_ids
        }
        modes.discard("")
        modes.discard("unknown")
        vsys = resolved_vsys.get((parent_unit_id, vs_id), "")
        parent_display_name = cluster_display_names.get(parent_unit_id, parent_unit_id)
        units.append(HaUnit(
            unit_id=f"{parent_unit_id}__vsid_{vs_id}",
            unit_type=_UNIT_CP_VSX_VS,
            vendor="checkpoint",
            members=sorted(member_entity_ids),
            parent_id=parent_unit_id,
            display_name=f"{vsys} | {parent_display_name}" if vsys else None,
            cluster_mode=modes.pop() if len(modes) == 1 else "unknown",
        ))

    for entity_id, _vs_id, vsys in orphans:
        units.append(HaUnit(
            unit_id=entity_id, unit_type=_UNIT_CP_VSX_VS, vendor="checkpoint",
            members=[entity_id],
            parent_id=None,
            display_name=vsys or None,
            cluster_mode=str((cp_ha_runtime.get(_normalize_cp_entity_key(entity_id)) or {}).get("ha_cluster_mode") or "unknown"),
        ))

    return units


def _pan_vsys_names(*rows: Mapping[str, Any]) -> list[str]:
    """VSYS names observed on a PAN device's own interfaces (`row["interfaces"][].vsys`,
    `panorama/panorama_runtime_runner.py::parse_interfaces`) -- informational
    context only, never identity. "0"/empty is the system/default context and
    is not a real VSYS name. Accepts multiple rows (a resolved pair's two
    members) and returns the union, sorted, so a pair's label reflects both
    sides."""
    names: set[str] = set()
    for row in rows:
        for iface in row.get("interfaces") or []:
            if not isinstance(iface, Mapping):
                continue
            vsys = str(iface.get("vsys") or "").strip()
            if vsys and vsys != "0":
                names.add(vsys)
    return sorted(names, key=lambda v: (len(v), v))


def _pan_display_name(vsys_names: list[str], fallback: str) -> str | None:
    if not vsys_names:
        return None
    label = "VSYS " + ", ".join(vsys_names) if len(vsys_names) <= 3 else (
        "VSYS " + ", ".join(vsys_names[:3]) + f" +{len(vsys_names) - 3}"
    )
    return f"{label} | {fallback}"


def _derive_pan_units(
    rows: Sequence[Mapping[str, Any]],
    pan_ha_runtime: Mapping[str, Mapping[str, Any]],
    pan_ha_peers: Mapping[str, str],
) -> list[HaUnit]:
    """Assemble PAN HA pairs (contract OP.0a.P7, revised at the PAN HA
    peer-pairing identity closure).

    `unified.json` carries no PAN peer relationship today — PAN rows have no
    `cluster` and no peer reference — so the pair is inferred from each
    device's configured `peer-ip` (`pan_ha_peers`, sourced from the running
    configuration this collector already fetches) matching another device's
    `management_ip`. This is CONFIGURATION INTENT, never a runtime-observed
    or runtime-proven relationship (Grade A only, per the frozen contract) —
    it must never be read elsewhere as sufficient corroboration for any
    future CLASS 2 operational decision.

    REAL_ENV_DISPROVEN (OP.0b S8-C, real-env correction): "configured HA1
    peer-ip == peer's management_ip" is NOT a universal PAN HA invariant. It
    is the correct test only for the specific (valid, supported) topology
    where the management interface is itself configured as HA1. The
    approved real S8-C pair uses dedicated HA1 control-link addressing (a
    distinct, equally valid, and arguably more common topology): both
    members report HA enabled and a configured HA1 peer address, self
    identity is internally consistent, yet the configured peer address
    matches neither member's management address — symmetrically, on both
    sides. That is expected dedicated-HA1 behavior, never a device defect
    and never evidence of misconfiguration. Management-plane addressing and
    HA1 control-link addressing are independent planes unless the operator
    has explicitly made them the same interface.

    This function is UNCHANGED by that correction — deliberately (task
    §12/§13: conservative stored/legacy topology derivation stays as-is;
    weakening it globally would let an unrelated device's management_ip
    coincidentally satisfy this predicate for the wrong reason). It still
    only recognizes the management-as-HA1 topology as Grade A. A device
    whose peer-ip does not resolve to any known management_ip (the
    dedicated-HA1 case) stays a conservative single-member unit here, same
    as before — correctly INSUFFICIENT_EVIDENCE, never fabricated into a
    pair from this evidence alone. The corrected model is applied instead in
    the explicit, narrow, invocation-scoped preflight candidate-resolution
    path (`application.workflows.preflight._resolve_pan_operational_entity`
    -> `derive_ha_units(..., pan_explicit_candidate_members=...)` ->
    `_apply_pan_explicit_candidate` below), which never touches this
    function's own fleet-wide, stored-telemetry behavior, and post-contact
    fresh runtime correspondence (`preflight_readiness._pan_reciprocal_correspondence`),
    which reasons over independently-observed P1/P2 evidence instead of this
    config-intent heuristic.

    Fail-closed, in order:
    - `peer-ip` missing, resolving to zero, or resolving to more than one
      entity → single-member unit, generic `pan_ha_peer_unresolved` (via
      `compute_ha_readiness`'s reason override).
    - A resolves to exactly one candidate B, but B's own configured
      `peer-ip` does not resolve back to A's `management_ip` (asymmetric or
      contradictory configuration, e.g. A→B but B→C, or A→B but B has no
      peer configured at all) → single-member unit for A,
      `unresolved_reason="pan_ha_peer_asymmetric"`. Never guessed, never a
      one-sided pair. Self-reference is already excluded from `candidates`.
    - Only a MUTUAL configuration agreement (A→B and B→A) forms a pair. It
      is never guessed and never silently merged.
    """
    pan_rows: dict[str, Mapping[str, Any]] = {}
    by_management_ip: dict[str, list[str]] = {}

    for row in rows:
        if str(row.get("source") or "").strip().lower() != "panorama":
            continue
        entity_id = resolve_entity_id(row)
        if not entity_id:
            continue
        pan_rows[entity_id] = row
        management_ip = str(row.get("management_ip") or "").strip()
        if management_ip:
            by_management_ip.setdefault(management_ip, []).append(entity_id)

    units: list[HaUnit] = []
    paired: set[str] = set()

    for entity_id in sorted(pan_rows):
        if entity_id in paired:
            continue
        runtime = pan_ha_runtime.get(entity_id) or {}
        enabled = runtime.get("enabled")
        # HA not enabled (or no HA evidence at all) is not a broken unit — the
        # device simply is not part of an HA pair. Omit it. Configuration
        # intent alone, with runtime HA disabled, never forms an eligible
        # pair (contract fail-closed case 7).
        if str(enabled or "").strip().lower() not in {"yes", "true", "1"}:
            continue

        peer_ip = str(pan_ha_peers.get(entity_id) or "").strip()
        candidates = [e for e in by_management_ip.get(peer_ip, []) if e != entity_id] if peer_ip else []
        mode = str(runtime.get("mode") or "unknown").strip() or "unknown"

        if len(candidates) == 1:
            peer = candidates[0]
            entity_management_ip = str(pan_rows[entity_id].get("management_ip") or "").strip()
            peer_declared_peer_ip = str(pan_ha_peers.get(peer) or "").strip()
            mutual = bool(entity_management_ip) and peer_declared_peer_ip == entity_management_ip

            if mutual:
                paired.add(entity_id)
                paired.add(peer)
                pair_id = f"{entity_id}+{peer}"
                vsys_names = _pan_vsys_names(pan_rows[entity_id], pan_rows[peer])
                units.append(HaUnit(
                    unit_id=pair_id, unit_type=_UNIT_PAN_PAIR, vendor="panorama",
                    members=sorted([entity_id, peer]), cluster_mode=mode,
                    display_name=_pan_display_name(vsys_names, pair_id),
                ))
                continue

            paired.add(entity_id)
            vsys_names = _pan_vsys_names(pan_rows[entity_id])
            units.append(HaUnit(
                unit_id=entity_id, unit_type=_UNIT_PAN_PAIR, vendor="panorama",
                members=[entity_id], cluster_mode=mode,
                display_name=_pan_display_name(vsys_names, entity_id),
                unresolved_reason="pan_ha_peer_asymmetric",
            ))
        else:
            paired.add(entity_id)
            vsys_names = _pan_vsys_names(pan_rows[entity_id])
            units.append(HaUnit(
                unit_id=entity_id, unit_type=_UNIT_PAN_PAIR, vendor="panorama",
                members=[entity_id], cluster_mode=mode,
                display_name=_pan_display_name(vsys_names, entity_id),
            ))
    return units


def pan_explicit_candidate_unit_id(entity_ids: Sequence[str]) -> str:
    """The deterministic `unit_id` an explicit, operator-bounded PAN preflight
    candidate pair gets -- same `"A+B"` (sorted) convention
    `_derive_pan_units` already uses for a Grade-A configuration-intent pair,
    so the two are visually/textually consistent, and so
    `_apply_pan_explicit_candidate` can recognize when `_derive_pan_units`
    already independently formed the exact same pair (management-as-HA1
    topology) and defer to that stronger-graded unit instead of duplicating
    it. Exported so the S7.5 application entrypoint
    (`application.workflows.preflight._resolve_pan_operational_entity`) can
    compute the identical id for its fresh `PreflightSnapshot.operational_unit_id`
    *before* calling `compute_ha_readiness`, matching the same convention
    `derive_ha_units` docstring already establishes for CP/legacy PAN units."""
    return "+".join(sorted(str(e) for e in entity_ids))


def _apply_pan_explicit_candidate(
    units: list[HaUnit],
    usable_rows: Sequence[Mapping[str, Any]],
    member_ids: Sequence[str],
) -> list[HaUnit]:
    """OP.0b S8-C real-env correction: replace the two orphan single-member
    `HaUnit`s `_derive_pan_units` produced for these SAME two entity ids with
    ONE explicit, bounded `--pan-preflight-targets A,B` candidate `HaUnit`.
    Every other unit `_derive_pan_units`/`_derive_cp_units` produced --
    including any single-member PAN unit for a DIFFERENT, not-selected
    entity -- passes through untouched.

    Real-env finding (operator report review, same session): appending the
    2-member candidate PURELY additively left the operator's generated
    report showing THREE rows for one explicit, bounded, two-device preflight
    invocation -- the pair plus its two now-redundant single-member halves,
    all labelled "Palo Alto HA pair" with the same VSYS prefix, unreadable
    next to Check Point's one row per cluster. The two single-member units
    carry no evidence the 2-member candidate doesn't already subsume (same
    physical rows, same-or-fresher facts once a snapshot applies); keeping
    them was redundant, not additional evidence, so removing them for THIS
    invocation's own report is not a weakening.

    This is deliberately separate from `_derive_pan_units`'s own fleet-wide,
    stored-telemetry pairing (task §13's "A. conservative stored/legacy
    topology derivation" vs. "B. explicit bounded candidate resolution"):
    normal, non-preflight callers (the console, `--ha-readiness-check`, any
    report render) never pass `member_ids`, so this function is never called
    for them, `_derive_pan_units`'s own output is never touched by it, and
    every other unit (any other PAN device, every CP unit) is unaffected.

    A no-op when:
    - `member_ids` is not exactly two distinct entity ids (defensive; the
      S7.5 application layer already enforces this before calling in), or
    - both entity ids do not resolve to known PAN rows in `usable_rows`, or
    - `_derive_pan_units` already independently formed a unit with EXACTLY
      these two members (the management-as-HA1 topology, Grade A already
      proven) -- that stronger-graded unit is used as-is, never shadowed by
      a second, weaker-graded unit for the same two members.
    """
    ids = sorted(dict.fromkeys(str(e) for e in member_ids if str(e).strip()))
    if len(ids) != 2:
        return units
    if any(u.vendor == "panorama" and set(u.members) == set(ids) for u in units):
        return units

    pan_rows = {
        resolve_entity_id(row): row
        for row in usable_rows
        if resolve_vendor(row) == "panorama"
    }
    if not all(entity_id in pan_rows for entity_id in ids):
        return units

    remaining = [
        u for u in units
        if not (u.vendor == "panorama" and u.unit_type == _UNIT_PAN_PAIR
                and len(u.members) == 1 and u.members[0] in ids)
    ]

    unit_id = pan_explicit_candidate_unit_id(ids)
    vsys_names = _pan_vsys_names(*(pan_rows[e] for e in ids))
    return [*remaining, HaUnit(
        unit_id=unit_id, unit_type=_UNIT_PAN_PAIR, vendor="panorama",
        members=ids, cluster_mode="unknown",
        display_name=_pan_display_name(vsys_names, unit_id),
        explicit_candidate=True,
    )]


def derive_ha_units(
    unified_devices: Sequence[Mapping[str, Any]],
    *,
    cp_ha_runtime: Mapping[str, Mapping[str, Any]] | None = None,
    pan_ha_runtime: Mapping[str, Mapping[str, Any]] | None = None,
    pan_ha_peers: Mapping[str, str] | None = None,
    pan_explicit_candidate_members: Sequence[str] | None = None,
) -> list[HaUnit]:
    """The canonical operational-HA-unit derivation, exported unchanged.

    `compute_ha_readiness` uses this internally to decide which units exist
    and what their `unit_id`/`members` are; OP.0b S7.5's application
    entrypoint calls it too, to resolve one operational entity from caller-
    supplied physical targets *before* any device contact, so a fresh
    `PreflightSnapshot.operational_unit_id` is guaranteed to match what this
    function -- and therefore `compute_ha_readiness` -- will independently
    derive for the same inventory. No readiness/verdict logic lives here;
    this performs no collection and computes no check or verdict.

    `pan_explicit_candidate_members` (OP.0b S8-C real-env correction):
    optional, exactly-two entity ids an operator explicitly bounded via
    `--pan-preflight-targets`. When given, the two now-redundant orphan
    single-member `HaUnit`s for those same two ids (if any) are replaced by
    ONE bounded-candidate `HaUnit` via `_apply_pan_explicit_candidate` -- see
    its docstring. `None`/omitted (every caller except the PAN preflight
    entrypoint) reproduces the exact prior behavior of this function.
    """
    cp_runtime = _normalize_cp_runtime(cp_ha_runtime or {})
    pan_runtime = pan_ha_runtime or {}
    peers = pan_ha_peers or {}

    usable_rows = [
        row for row in unified_devices
        if str((row.get("inventory_status") or {}).get("data_state") or "").strip().lower()
        not in _INSUFFICIENT_DATA_STATES
        and resolve_vendor(row) is not None
        and resolve_entity_id(row)
    ]

    units = _derive_cp_units(usable_rows, cp_runtime) + _derive_pan_units(usable_rows, pan_runtime, peers)
    if pan_explicit_candidate_members:
        units = _apply_pan_explicit_candidate(units, usable_rows, pan_explicit_candidate_members)
    return units


def compute_ha_readiness(
    unified_devices: Sequence[Mapping[str, Any]],
    *,
    cp_ha_runtime: Mapping[str, Mapping[str, Any]] | None = None,
    pan_ha_runtime: Mapping[str, Mapping[str, Any]] | None = None,
    pan_ha_peers: Mapping[str, str] | None = None,
    generated_at: str | None = None,
    preflight_snapshots: "Sequence[PreflightSnapshot] | None" = None,
    pan_explicit_candidate_members: Sequence[str] | None = None,
) -> dict[str, Any]:
    """Compute a `securityexpert-ha-readiness-v1` record.

    OP.0b S7: `preflight_snapshots` are fresh S5/S6 `PreflightSnapshot`s,
    keyed by their `operational_unit_id`. A unit whose id matches one is
    evaluated from that snapshot alone (evidence basis
    `op0b_preflight_snapshot`); every other unit keeps the OP.0a stored-
    telemetry basis. Snapshots never create units — unit derivation stays
    inventory-driven — and a snapshot naming no derived unit is reported
    under the top-level `preflight.unmatched` list, never silently dropped
    and never evaluated against a guessed unit. This function performs no
    collection: callers run `checkpoint.preflight_collector` /
    `panorama.preflight_collector` as a separate, explicit stage.

    Parameters
    ----------
    unified_devices:
        Rows from `unified.json` (the merged CP/VSX/Panorama inventory).
    cp_ha_runtime:
        Optional `entity_id -> {"ha_role": ..., "ha_cluster_mode": ...}` map,
        sourced from the configuration-evidence rows that
        `configuration/checkpoint_config_collector.py` already populates from
        the already-gated `cphaprob stat`. Omitted/empty means every CP unit
        reports INSUFFICIENT_EVIDENCE — the correct behaviour, not a degraded
        mode.
    pan_ha_runtime:
        Optional `entity_id -> {"enabled","state","mode","peer_state",
        "state_sync"}` map as returned by
        `panorama_config_collector.get_target_ha_runtime_state`.
    pan_ha_peers:
        Optional `entity_id -> configured peer-ip` map used only for P7 pair
        assembly.
    pan_explicit_candidate_members:
        OP.0b S8-C real-env correction. Optional, exactly-two entity ids an
        operator explicitly bounded via `--pan-preflight-targets`, threaded
        straight through to `derive_ha_units`. `None` for every caller
        except the PAN preflight entrypoint -- see `derive_ha_units`.

    The result contains no management address, no raw device output and no
    command string other than the fixed `missing_evidence` labels.
    """
    cp_runtime = _normalize_cp_runtime(cp_ha_runtime or {})
    pan_runtime = pan_ha_runtime or {}

    units = derive_ha_units(
        unified_devices, cp_ha_runtime=cp_ha_runtime, pan_ha_runtime=pan_runtime, pan_ha_peers=pan_ha_peers,
        pan_explicit_candidate_members=pan_explicit_candidate_members,
    )

    snapshots_by_unit: dict[str, Any] = {}
    duplicate_snapshot_units: list[str] = []
    for snapshot in preflight_snapshots or ():
        key = str(snapshot.operational_unit_id)
        if key in snapshots_by_unit:
            # Two snapshots for one unit is ambiguous evidence -- neither is
            # trusted (fail closed), and the unit falls back to stored telemetry.
            duplicate_snapshot_units.append(key)
            continue
        snapshots_by_unit[key] = snapshot
    for key in duplicate_snapshot_units:
        snapshots_by_unit.pop(key, None)
    applied: list[str] = []
    # A VSX VS's own physical parent, if this run applied a fresh snapshot to
    # it -- the only fact `_evaluate_checks` needs to give the VS an honest,
    # narrower INSUFFICIENT_EVIDENCE reason instead of the stale "no preflight
    # battery exists" one (S8-B VSX operator-review finding).
    snapshot_unit_ids = set(snapshots_by_unit.keys())

    assessments: list[UnitAssessment] = []
    for unit in sorted(units, key=lambda u: (u.vendor, u.unit_type, u.unit_id)):
        snapshot = snapshots_by_unit.get(unit.unit_id)
        parent_preflight_applied = bool(unit.parent_id and unit.parent_id in snapshot_unit_ids)
        checks, evidence, effective_mode = _evaluate_checks(
            unit, cp_ha_runtime=cp_runtime, pan_ha_runtime=pan_runtime, snapshot=snapshot,
            parent_preflight_applied=parent_preflight_applied,
        )
        if snapshot is not None:
            applied.append(unit.unit_id)
            if effective_mode:
                # Fresh in-run mode beats the stored-telemetry mode for both
                # the roll-up (P3 load-sharing gate) and the disclosed unit.
                unit.cluster_mode = effective_mode
        verdict, reason = _verdict_for(unit, checks, evidence)
        if (
            unit.vendor == "panorama"
            and len(unit.members) == 1
            and verdict == VERDICT_INSUFFICIENT
        ):
            reason = unit.unresolved_reason or "pan_ha_peer_unresolved"
        assessments.append(UnitAssessment(unit, verdict, reason, checks, evidence))

    summary = {
        VERDICT_SAFE: 0,
        VERDICT_DEGRADED: 0,
        VERDICT_UNSAFE: 0,
        VERDICT_INSUFFICIENT: 0,
        VERDICT_NOT_A_FAILOVER_UNIT: 0,
    }
    for assessment in assessments:
        summary[assessment.verdict] += 1

    derived_unit_ids = {u.unit_id for u in units}
    return {
        "schema": SCHEMA,
        "generated_at": generated_at or _utc_now(),
        "units": [a.to_dict() for a in assessments],
        "summary": summary,
        # Additive (S7): which fresh preflight evidence reached this record.
        "preflight": {
            "snapshots_supplied": len(preflight_snapshots or ()),
            "applied": sorted(applied),
            "unmatched": sorted(k for k in snapshots_by_unit if k not in derived_unit_ids),
            "ambiguous": sorted(set(duplicate_snapshot_units)),
        },
    }
