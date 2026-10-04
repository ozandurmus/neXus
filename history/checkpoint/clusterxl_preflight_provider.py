"""OP.2.C follow-up -- the real `PreflightProvider`/`EligibilityEvaluator`
for CP ClusterXL HA.

`utils.operate.eligibility.PreflightProvider`/`EligibilityEvaluator` are
typed seams `OP.2.A`/`OP.2.B` ship with no implementation (that module's own
docstring: "a future movement (`OP.2.C`) wires [them] to the real, already-
approved class 0 battery and the canonical
`utils.failover.compute_ha_readiness` projection"). This module is that
wiring, and nothing more:

- `run_preflight()` calls `checkpoint.preflight_collector.run_cp_preflight`
  (the approved S5 collector) for exactly the caller-selected physical
  members, then `utils.failover.preflight_model.evaluate_coherence` and
  `utils.failover.assessment.compute_ha_readiness` -- the one canonical
  readiness authority -- over the resulting snapshot(s). It adds no second
  readiness engine, no second check set, and reinterprets no check: the
  returned `PreflightSnapshot.readiness_verdict` is copied verbatim from
  whatever `compute_ha_readiness` reports for this operational unit.
- `ClusterXLReadinessEligibilityEvaluator.evaluate()` maps that canonical
  verdict onto `EligibilityResult` by simple equality against
  `utils.failover.assessment.VERDICT_SAFE` -- it never re-derives, re-weighs,
  or second-guesses the verdict (`utils.operate.eligibility`'s own
  "Correctness contract").

Building this module does not, by itself, wire anything live: nothing here
constructs an `ActionCoordinator`, nothing here is a production entry point,
and nothing here selects or resolves a vendor adapter -- `CURRENT_STATE.md`
"Open blockers" (`DEPLOY.1A`/`OPERATE`, SSH trust hardening, the signed
change-management review, a protected entry point) all remain unresolved by
this file, exactly as `checkpoint.clusterxl_capability_adapter` and
`checkpoint.clusterxl_member_session` already state for themselves.

**Opaque member tokens (`subject_member_token`/`peer_member_token`).** These
are the SAME caller-supplied `CPPhysicalMemberTarget.physical_device_identity`
opaque tokens `run_cp_preflight` already attributes evidence to -- there is
no separate token-minting step. Which of the two caller-selected members is
"subject" (the currently-active member CP-M1 would act on) is decided from
this run's own fresh `ha_local_role` fact (category `RUNTIME_HA_STATE`,
`checkpoint.cp_preflight_projection`), never from member order or from any
stored/prior-run role. Exactly one member must resolve to `ACTIVE` and the
member pairing must be exactly two members with both roles known -- anything
else (a missing/failed role read, a tie, two actives, more or fewer than two
members) fails closed to no tokens at all, which `CPClusterXLCapabilityAdapter.
capability()` already turns into `insufficient_member_identity_evidence`.

**Cluster mode.** `compute_ha_readiness` reports a unit's `cluster_mode` in
CP's own vendor vocabulary (`checkpoint_config_collector.CLUSTERXL_CLUSTER_
MODES`, e.g. `"ha_new_mode"`) -- not the adapter's canonical `"ha"` token
(`clusterxl_capability_adapter._CLUSTER_MODE_HA`). This module performs that
one, narrow, fixed vocabulary translation (`"ha_new_mode" -> "ha"`) and
otherwise passes the vendor mode through unchanged, so a non-HA mode (VSX
Load Sharing, VSLS, VRRP) still fails the adapter's own `unsupported_
cluster_mode` gate exactly as `OP.2.1`'s scope requires -- this is not a
second readiness/mode decision, only a label the adapter already expects.

**Recovery mode (`D-V7b`).** Check Point's configured ClusterXL recovery
method (maintain-current-active vs. switch-to-higher-priority) has no
machine-readable read in the approved class 0 battery
(`utils.failover.assessment._verdict_for`'s own advisory-exempt reason,
`"configured_recovery_not_readable_d_v7b"`). This module therefore always
reports `recovery_mode="unknown"` -- an honest disclosure, not a TODO --
which `CPClusterXLCapabilityAdapter.build_plan()` already turns into a
disclosed-`UNKNOWN` failback impact for the operator to decide (`OP.2.0`
P12).
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Mapping, Sequence

from checkpoint.preflight_collector import CPPhysicalMemberTarget, run_cp_preflight
from utils.failover.assessment import VERDICT_INSUFFICIENT, VERDICT_SAFE, compute_ha_readiness
from utils.failover.preflight_model import FactState, PreflightSnapshot as CollectedPreflightSnapshot
from utils.failover.preflight_model import evaluate_coherence
from utils.operate.adapter import Capability
from utils.operate.eligibility import EligibilityResult, PreflightSnapshot

__all__ = [
    "ClusterXLPreflightProvider",
    "ClusterXLReadinessEligibilityEvaluator",
]

#: `checkpoint.clusterxl_capability_adapter._CLUSTER_MODE_HA` -- the
#: adapter's own canonical HA-mode token. Duplicated as a literal, not
#: imported, so this module's one narrow vocabulary translation never
#: silently widens if that module's private constant ever changes shape.
_ADAPTER_CLUSTER_MODE_HA = "ha"

#: `configuration.checkpoint_config_collector.CLUSTERXL_CLUSTER_MODES`'s own
#: HA (New) mode token -- the only vendor mode this module ever translates.
_CP_VENDOR_MODE_HA_NEW = "ha_new_mode"

#: `checkpoint.clusterxl_capability_adapter._ROLE_ACTIVE` -- duplicated as a
#: literal for the same reason as `_ADAPTER_CLUSTER_MODE_HA` above.
_ROLE_ACTIVE = "ACTIVE"

#: `checkpoint.clusterxl_capability_adapter`'s honest, permanent fallback
#: (`D-V7b`) -- never resolved to a guess by this module.
_RECOVERY_MODE_UNKNOWN = "unknown"


def _fact_value(facts: Sequence[Any], name: str) -> Any | None:
    for fact in facts:
        if fact.name == name and fact.state is FactState.KNOWN:
            return fact.value
    return None


#: `utils.failover.assessment.HaUnit.cluster_mode`'s own "never resolved"
#: sentinel -- reported the same as a missing mode (`None`), never as a
#: positive-but-unrecognized vendor mode.
_UNIT_CLUSTER_MODE_UNKNOWN_SENTINEL = "unknown"


def _map_cluster_mode(vendor_mode: str | None) -> str | None:
    if vendor_mode is None or vendor_mode == _UNIT_CLUSTER_MODE_UNKNOWN_SENTINEL:
        return None
    if vendor_mode == _CP_VENDOR_MODE_HA_NEW:
        return _ADAPTER_CLUSTER_MODE_HA
    return vendor_mode


def _resolve_member_tokens(snapshot: CollectedPreflightSnapshot) -> tuple[str | None, str | None]:
    """The active/peer opaque-token pair, or `(None, None)` on anything
    short of exactly one unambiguous `ACTIVE` member among exactly two.
    Fail-closed by construction -- see module docstring."""
    if len(snapshot.members) != 2:
        return None, None
    roles = [_fact_value(member.own_facts, "ha_local_role") for member in snapshot.members]
    if any(role is None for role in roles):
        return None, None
    active_indices = [index for index, role in enumerate(roles) if role == _ROLE_ACTIVE]
    if len(active_indices) != 1:
        # Either no member observed ACTIVE, or more than one did
        # (split-brain evidence) -- neither is a case this module resolves
        # a subject member from.
        return None, None
    active_index = active_indices[0]
    peer_index = 1 - active_index
    subject_token = str(snapshot.members[active_index].physical_device_identity)
    peer_token = str(snapshot.members[peer_index].physical_device_identity)
    return subject_token, peer_token


class ClusterXLPreflightProvider:
    """Real `utils.operate.eligibility.PreflightProvider` for one bounded CP
    ClusterXL HA operational entity.

    `members`/`unit_type`/credentials are fixed at construction (this
    provider is scoped to one operational entity, matching `run_cp_preflight`'s
    own bounded-target contract, not a fleet-wide resolver). `unified_devices`
    and, optionally, `cp_ha_runtime` are read fresh on every call via the
    supplied callables -- never cached across calls -- so each `run_preflight`
    invocation reflects this action's own current inventory, not a captured
    snapshot from construction time.

    `preflight_runner` is an injection seam defaulting to the real
    `checkpoint.preflight_collector.run_cp_preflight`; tests substitute a
    fake that performs no device I/O, exactly the discipline
    `checkpoint.clusterxl_capability_adapter`'s own `session_resolver` seam
    already uses.
    """

    def __init__(
        self,
        *,
        members: Sequence[CPPhysicalMemberTarget],
        username: str,
        secret: str,
        unified_devices: Callable[[], Sequence[Mapping[str, Any]]],
        cp_ha_runtime: Callable[[], Mapping[str, Mapping[str, Any]]] | None = None,
        unit_type: str = "cluster",
        strict_host_key: bool = False,
        connect_timeout: int = 8,
        command_timeout: int = 20,
        preflight_runner: Callable[..., CollectedPreflightSnapshot] = run_cp_preflight,
    ) -> None:
        self._members = tuple(members)
        self._username = username
        self._secret = secret
        self._unified_devices = unified_devices
        self._cp_ha_runtime = cp_ha_runtime
        self._unit_type = unit_type
        self._strict_host_key = strict_host_key
        self._connect_timeout = connect_timeout
        self._command_timeout = command_timeout
        self._preflight_runner = preflight_runner

    def run_preflight(self, *, action_id: str, operational_entity_id: str) -> PreflightSnapshot:
        collected = self._preflight_runner(
            operational_entity_id=operational_entity_id,
            unit_type=self._unit_type,
            members=self._members,
            username=self._username,
            secret=self._secret,
            strict_host_key=self._strict_host_key,
            connect_timeout=self._connect_timeout,
            command_timeout=self._command_timeout,
        )
        coherence = evaluate_coherence(collected)

        readiness = compute_ha_readiness(
            self._unified_devices(),
            cp_ha_runtime=self._cp_ha_runtime() if self._cp_ha_runtime is not None else None,
            preflight_snapshots=[collected, *collected.subordinate_snapshots],
        )
        unit = next(
            (u for u in readiness["units"] if u.get("unit_id") == operational_entity_id), None,
        )
        if unit is None:
            # This action's own operational entity was not among the units
            # `compute_ha_readiness` derived from current inventory -- never
            # guess a verdict or member identity for a unit it did not
            # itself resolve (fail closed).
            readiness_verdict = VERDICT_INSUFFICIENT
            check_statuses: dict[str, Any] = {"recovery_mode": _RECOVERY_MODE_UNKNOWN}
        else:
            readiness_verdict = str(unit.get("verdict") or VERDICT_INSUFFICIENT)
            check_statuses = {"recovery_mode": _RECOVERY_MODE_UNKNOWN}
            cluster_mode = _map_cluster_mode(unit.get("cluster_mode"))
            if cluster_mode is not None:
                check_statuses["cluster_mode"] = cluster_mode
            subject_token, peer_token = _resolve_member_tokens(collected)
            if subject_token is not None and peer_token is not None:
                check_statuses["subject_member_token"] = subject_token
                check_statuses["peer_member_token"] = peer_token

        return PreflightSnapshot(
            preflight_run_id=collected.preflight_run_id,
            action_id=action_id,
            operational_entity_id=operational_entity_id,
            coherent=coherence.coherent,
            readiness_verdict=readiness_verdict,
            check_statuses=check_statuses,
        )


@dataclass(frozen=True)
class ClusterXLReadinessEligibilityEvaluator:
    """Real `utils.operate.eligibility.EligibilityEvaluator` for CP
    ClusterXL HA. Maps `PreflightSnapshot.readiness_verdict` onto
    `EligibilityResult` by equality against `utils.failover.assessment.
    VERDICT_SAFE` -- the one canonical positive verdict -- and never
    consults `capability` to override that mapping (`utils.operate.
    eligibility`'s own "Correctness contract": a disagreement between
    readiness and eligibility is a defect in this layer, not a tie to
    break). Every non-`SAFE_TO_FAILOVER` verdict (`DEGRADED_PROCEED_WITH_
    RISK`, `UNSAFE_DO_NOT_FAILOVER`, `INSUFFICIENT_EVIDENCE`, `NOT_A_
    FAILOVER_UNIT`, or any future/unrecognized string) is not eligible."""

    def evaluate(self, *, snapshot: PreflightSnapshot, capability: Capability | None) -> EligibilityResult:
        if snapshot.readiness_verdict == VERDICT_SAFE:
            return EligibilityResult(eligible=True, reason_codes=("readiness_safe_to_failover",))
        return EligibilityResult(
            eligible=False,
            reason_codes=(f"readiness_verdict_not_safe:{snapshot.readiness_verdict}",),
        )
