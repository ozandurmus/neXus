"""OP.1 — `compile_failover_plan`, the write-free `FailoverPlan` compiler.

Contract: `docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md` §3,
§4, §7. Consumes only a `compute_ha_readiness()` record and the same raw
`cp_ha_runtime` map its caller already loaded for that call (`application.
workflows.failover._load_cp_ha_runtime`) — no collection, no device
contact, no new evidence field.

**Implementation-detail decision (§9, "reuse vs duplicate the two
`clusterxl_preflight_provider` vendor-mode/member-token helpers"):**
duplicate both, not import either. Checked empirically, not assumed:
`checkpoint.clusterxl_preflight_provider` imports `checkpoint.
preflight_collector`, which imports `checkpoint.cp_preflight_battery`,
which imports `paramiko` at module scope — importing either helper would
make this offline, zero-I/O package transitively depend on the live SSH
transport stack, exactly the "nothing vendor-bound is imported at module
scope" discipline `application/workflows/failover.py` already states for
this same evidence surface. `_map_cluster_mode` is reproduced verbatim
below (four lines, one fixed vocabulary translation). The member-token
resolution is also reproduced rather than imported for a second, structural
reason: `clusterxl_preflight_provider._resolve_member_tokens` reads
`ha_local_role` off a freshly-collected `PreflightSnapshot`'s per-member
`Fact` objects, a materially different shape from the raw `cp_ha_runtime`
dict this compiler actually has (the same one `utils.failover.assessment.
_cp_roles` already reads) — reusing it would mean constructing throwaway
`Fact`/member objects purely to satisfy an unrelated type, which is
indirection, not reuse. The fail-closed rule itself (exactly one
`"ACTIVE"` among exactly two members, or no tokens) is reproduced exactly,
per §3.1, and both copies are independently covered by the AC-2/AC-3 test
matrix.

Purity (AC-1): this module never resolves a `ClusterXLMemberSession`. The
`CPClusterXLCapabilityAdapter` it constructs is always given
`_POISON_SESSION_RESOLVER`, a callable that raises `AssertionError` if ever
invoked — `capability()` and `build_plan()` never call it (verified by
reading their implementation, `checkpoint/clusterxl_capability_adapter.py`
lines 175–278), so this is a structural proof, not a documented intention.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Mapping, Sequence

from checkpoint.clusterxl_capability_adapter import (
    ACTION_TYPE_HA_GRACEFUL_FAILBACK,
    ACTION_TYPE_HA_GRACEFUL_FAILOVER,
    ENTITY_KIND_CP_CLUSTER,
    CPClusterXLCapabilityAdapter,
)
from utils.failover.assessment import _normalize_cp_entity_key
from utils.operate.eligibility import PreflightSnapshot

from .model import FailoverPlan, PlanStep, PreconditionBinding, ReversalStep

__all__ = ["compile_failover_plan"]

_CAPABILITY_ID = "cp_clusterxl_admin_state_v1"
_UNIT_CP_CLUSTER = "cp_clusterxl_cluster"
_VSX_UNIT_TYPES = frozenset({"cp_vsx_host", "cp_vsx_cluster", "cp_vsx_virtual_system"})

#: `checkpoint.clusterxl_capability_adapter._CLUSTER_MODE_HA` -- duplicated
#: as a literal (module docstring: not imported, so this module's one narrow
#: vocabulary translation never silently widens if that private constant
#: changes shape) -- the same discipline `clusterxl_preflight_provider`
#: already applies to itself for the identical constant.
_ADAPTER_CLUSTER_MODE_HA = "ha"

#: `configuration.checkpoint_config_collector.CLUSTERXL_CLUSTER_MODES`'s own
#: HA (New) mode token -- the only vendor mode this module ever translates.
_CP_VENDOR_MODE_HA_NEW = "ha_new_mode"

#: `utils.failover.assessment.HaUnit.cluster_mode`'s own "never resolved"
#: sentinel -- reported the same as a missing mode, never as a positive but
#: unrecognized vendor mode.
_UNIT_CLUSTER_MODE_UNKNOWN_SENTINEL = "unknown"

#: Verbatim, unconditional (§3.3) — this action is itself CLASS 2 and
#: separately, independently confirmed; compiling this disclosure schedules,
#: queues or implies nothing.
_REVERSAL_AUTHORIZATION_NOTE = (
    "This is a separate, independently confirmed CLASS 2 action "
    "(op_reversal_model, decided 2026-09-04). Compiling this disclosure "
    "does not schedule, queue or imply it will run."
)

#: The named facts `observe_postcondition()` would read (both members' own
#: `ha_local_role`, the `admin_down` pnote) -- never a command string (P18,
#: reused unchanged from `utils.operate.adapter`'s own read-only postcondition
#: shape, `checkpoint/clusterxl_capability_adapter.py::observe_postcondition`).
_VERIFICATION_READS: tuple[str, ...] = (
    "subject_member_ha_local_role",
    "peer_member_ha_local_role",
    "subject_member_admin_down_pnote",
)


def _poison_session_resolver(token: str) -> Any:
    raise AssertionError(
        f"compile_failover_plan must never resolve a member session (token={token!r}) "
        "-- it performs no I/O by construction"
    )


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _map_cluster_mode(vendor_mode: str | None) -> str | None:
    """`"ha_new_mode" -> "ha"`, the one fixed vocabulary translation --
    reproduced verbatim from `checkpoint.clusterxl_preflight_provider.
    _map_cluster_mode` (see module docstring for why this is duplicated
    rather than imported)."""
    if vendor_mode is None or vendor_mode == _UNIT_CLUSTER_MODE_UNKNOWN_SENTINEL:
        return None
    if vendor_mode == _CP_VENDOR_MODE_HA_NEW:
        return _ADAPTER_CLUSTER_MODE_HA
    return vendor_mode


def _find_unit(readiness_record: Mapping[str, Any], unit_id: str) -> Mapping[str, Any]:
    for unit in readiness_record.get("units") or []:
        if unit.get("unit_id") == unit_id:
            return unit
    raise ValueError(f"unit_id {unit_id!r} not found in readiness_record")


def _resolve_member_tokens(
    members: Sequence[str], cp_ha_runtime: Mapping[str, Mapping[str, Any]],
) -> tuple[str | None, str | None]:
    """The active/peer opaque-token pair, or `(None, None)` on anything short
    of exactly one unambiguous `"ACTIVE"` member among exactly two (§3.1) --
    same fail-closed rule as `checkpoint.clusterxl_preflight_provider.
    _resolve_member_tokens`, reproduced here over the raw `cp_ha_runtime` shape
    (see module docstring)."""
    if len(members) != 2:
        return None, None
    roles: list[str | None] = []
    for member_id in members:
        entry = cp_ha_runtime.get(_normalize_cp_entity_key(member_id)) or {}
        role = str(entry.get("ha_role") or "").strip().upper()
        roles.append(role or None)
    if any(role is None for role in roles):
        return None, None
    active_indices = [index for index, role in enumerate(roles) if role == "ACTIVE"]
    if len(active_indices) != 1:
        return None, None
    active_index = active_indices[0]
    peer_index = 1 - active_index
    return members[active_index], members[peer_index]


def compile_failover_plan(
    unit_id: str,
    *,
    readiness_record: Mapping[str, Any],
    cp_ha_runtime: Mapping[str, Mapping[str, Any]] | None = None,
    generated_at: str | None = None,
) -> FailoverPlan:
    """Compile one unit's `FailoverPlan`. Never performs I/O (AC-1);
    `plan_compilable` is `False` with a named `compilation_blocked_reason`
    whenever §3.1's two structural facts do not resolve, or the unit is not
    classic ClusterXL (§7) -- never a fabricated plan (AC-2)."""
    cp_ha_runtime = cp_ha_runtime or {}
    unit = _find_unit(readiness_record, unit_id)
    vendor = str(unit.get("vendor") or "unknown")
    unit_type = str(unit.get("unit_type") or "")
    readiness_verdict = str(unit.get("verdict") or "")
    evidence_basis = str((unit.get("evidence") or {}).get("basis") or "")
    generated = generated_at or _utc_now()

    def _blocked(reason: str) -> FailoverPlan:
        return FailoverPlan(
            unit_id=unit_id,
            vendor=vendor,
            capability_id=_CAPABILITY_ID,
            evidence_basis=evidence_basis,
            readiness_verdict=readiness_verdict,
            plan_compilable=False,
            compilation_blocked_reason=reason,
            step=None,
            reversal=None,
            generated_at=generated,
        )

    if vendor != "checkpoint" or unit_type != _UNIT_CP_CLUSTER:
        if unit_type in _VSX_UNIT_TYPES:
            return _blocked("vendor_scope_vsx_vsls_deferred")
        return _blocked("unsupported_unit_type")

    members = list(unit.get("members") or [])
    adapter_cluster_mode = _map_cluster_mode(unit.get("cluster_mode"))
    subject_token, peer_token = _resolve_member_tokens(members, cp_ha_runtime)

    check_statuses: dict[str, Any] = {"recovery_mode": "unknown"}
    if adapter_cluster_mode is not None:
        check_statuses["cluster_mode"] = adapter_cluster_mode
    if subject_token is not None and peer_token is not None:
        check_statuses["subject_member_token"] = subject_token
        check_statuses["peer_member_token"] = peer_token

    evidence = PreflightSnapshot(
        preflight_run_id="",
        action_id="",
        operational_entity_id=unit_id,
        coherent=True,
        readiness_verdict=readiness_verdict,
        check_statuses=check_statuses,
    )

    adapter = CPClusterXLCapabilityAdapter(session_resolver=_poison_session_resolver)
    capability = adapter.capability(
        entity_kind=ENTITY_KIND_CP_CLUSTER, action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER, evidence=evidence,
    )
    if not capability.supported:
        return _blocked(str(capability.reason or "unsupported"))

    forward_plan = adapter.build_plan(
        entity=evidence, action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER, evidence=evidence,
    )
    reversal_plan = adapter.build_plan(
        entity=evidence, action_type=ACTION_TYPE_HA_GRACEFUL_FAILBACK, evidence=evidence,
    )

    preconditions = tuple(
        PreconditionBinding(
            id=str(check.get("id")),
            label=str(check.get("label")),
            status=str(check.get("status")),
            reason=str(check.get("reason")),
            missing_evidence=str(check.get("missing_evidence") or ""),
        )
        for check in unit.get("checks") or []
    )

    step = PlanStep(
        primitive_id="CP-M1",
        action_type=ACTION_TYPE_HA_GRACEFUL_FAILOVER,
        subject_member_token=str(subject_token),
        preconditions=preconditions,
        intended_postcondition=forward_plan.intended_postcondition,
        impact_disclosure=forward_plan.impact_disclosure,
        verification_reads=_VERIFICATION_READS,
        settle_observation=forward_plan.settle_observation,
    )
    reversal = ReversalStep(
        primitive_id="CP-M1-R",
        action_type=ACTION_TYPE_HA_GRACEFUL_FAILBACK,
        reverses="the compiled step above",
        intended_postcondition=reversal_plan.intended_postcondition,
        impact_disclosure=reversal_plan.impact_disclosure,
        authorization_note=_REVERSAL_AUTHORIZATION_NOTE,
    )

    return FailoverPlan(
        unit_id=unit_id,
        vendor="checkpoint",
        capability_id=_CAPABILITY_ID,
        evidence_basis=evidence_basis,
        readiness_verdict=readiness_verdict,
        plan_compilable=True,
        compilation_blocked_reason=None,
        step=step,
        reversal=reversal,
        generated_at=generated,
    )
