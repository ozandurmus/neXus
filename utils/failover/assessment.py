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

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Mapping, Sequence

from utils.restore_readiness import resolve_entity_id, resolve_vendor

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

    def to_dict(self) -> dict[str, Any]:
        return {
            "unit_id": self.unit_id,
            "unit_type": self.unit_type,
            "vendor": self.vendor,
            "members": list(self.members),
            "cluster_mode": self.cluster_mode,
            "display_name": self.display_name,
            "parent_id": self.parent_id,
        }


@dataclass
class UnitAssessment:
    unit: HaUnit
    verdict: str
    reason: str
    checks: list[dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        record = self.unit.to_dict()
        record["verdict"] = self.verdict
        record["reason"] = self.reason
        record["checks"] = list(self.checks)
        return record


def _check(check_id: str, label: str, status: str, reason: str, missing: str = "") -> dict[str, Any]:
    return {
        "id": check_id,
        "label": label,
        "status": status,
        "reason": reason,
        "missing_evidence": missing,
    }


def _cp_roles(members: Sequence[str], cp_ha_runtime: Mapping[str, Mapping[str, Any]]) -> list[str]:
    roles = []
    for entity_id in members:
        role = str((cp_ha_runtime.get(entity_id) or {}).get("ha_role") or "").strip().upper()
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

    So: when direct evidence exists for more than one member, trust each
    member's own `state` and ignore `peer_state`. `peer_state` is used only as
    a fallback when the unit has direct evidence for a single member (the P7
    unresolved-peer case), where it is the only peer evidence available.
    """
    with_evidence = [m for m in members if pan_ha_runtime.get(m)]
    use_peer_view = len(with_evidence) < 2

    states: list[str] = []
    for entity_id in members:
        record = pan_ha_runtime.get(entity_id) or {}
        state = str(record.get("state") or "").strip().lower()
        if state:
            states.append(state)
        if use_peer_view:
            peer = str(record.get("peer_state") or "").strip().lower()
            if peer:
                states.append(peer)
    return states


def _evaluate_checks(
    unit: HaUnit,
    *,
    cp_ha_runtime: Mapping[str, Mapping[str, Any]],
    pan_ha_runtime: Mapping[str, Mapping[str, Any]],
) -> list[dict[str, Any]]:
    """Evaluate all seven §4 stop-conditions.

    Only the members of `OP0A_EVALUABLE_CHECKS` can return PASS/FAIL; every
    other condition is forced to INSUFFICIENT_EVIDENCE here, at one place, so
    that no future edit elsewhere can make a green light reachable without
    changing that frozenset and its gate (contract P4).
    """
    missing_for_vendor = _MISSING_EVIDENCE.get(unit.vendor, {})

    if unit.vendor == "checkpoint":
        observed = _cp_roles(unit.members, cp_ha_runtime)
        active = [r for r in observed if r in _CP_ACTIVE_ROLES]
        standby = [r for r in observed if r in _CP_STANDBY_CAPABLE_ROLES]
    else:
        observed = _pan_states(unit.members, pan_ha_runtime)
        active = [s for s in observed if s in _PAN_ACTIVE_STATES]
        standby = [s for s in observed if s in _PAN_STANDBY_CAPABLE_STATES]

    checks: list[dict[str, Any]] = []
    for check_id, label in STOP_CONDITIONS:
        if check_id not in OP0A_EVALUABLE_CHECKS:
            checks.append(_check(
                check_id, label, CHECK_INSUFFICIENT,
                "not_evaluable_without_preflight_battery",
                missing_for_vendor.get(check_id, "OP.0b preflight battery"),
            ))
            continue

        if not observed:
            checks.append(_check(
                check_id, label, CHECK_INSUFFICIENT, "no_ha_runtime_evidence_for_unit",
                missing_for_vendor.get(check_id, "OP.0b preflight battery"),
            ))
            continue

        if check_id == "viable_target":
            if standby:
                checks.append(_check(check_id, label, CHECK_PASS, "standby_capable_member_observed"))
            else:
                checks.append(_check(check_id, label, CHECK_FAIL, "no_viable_target"))
        elif check_id == "no_split_brain":
            if len(active) > 1:
                checks.append(_check(check_id, label, CHECK_FAIL, "split_brain_observed"))
            elif active:
                checks.append(_check(check_id, label, CHECK_PASS, "exactly_one_active_member"))
            else:
                checks.append(_check(
                    check_id, label, CHECK_INSUFFICIENT, "no_active_member_observed",
                    missing_for_vendor.get(check_id, "OP.0b preflight battery"),
                ))
    return checks


def _verdict_for(unit: HaUnit, checks: Sequence[Mapping[str, Any]]) -> tuple[str, str]:
    """Fail-closed verdict (contract P3/P4).

    `SAFE_TO_FAILOVER` requires **every** §4 stop-condition to have positively
    passed. Because `OP0A_EVALUABLE_CHECKS` covers only two of the seven, that
    is structurally unreachable in OP.0a — asserted by AC-6 rather than left to
    reading. `DEGRADED_PROCEED_WITH_RISK` is reserved in the vocabulary and
    never emitted here (open decision `op_degraded_verdict`, owed before OP.1).
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
        return VERDICT_SAFE, "all_stop_conditions_passed"

    return VERDICT_INSUFFICIENT, "stop_conditions_not_fully_evaluable"


def _row_is_vsx(row: Mapping[str, Any], source: str) -> bool:
    """True when this physical-host row participates in VSX -- either it came
    from `vsx.json` directly, or a `cp.json` row carries the CP inventory's own
    VSX flag (`checkpoint/cp_runner.py`'s `vsx_cluster_member`/legacy
    `vs_cluster_member`). Used only to pick the presentation `unit_type`
    (`cp_vsx_host` vs `cp_clusterxl_cluster`) -- it never affects grouping
    identity, which is `cluster_topology.group_id` either way."""
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
    identity/topology defect).

    Canonical grouping identity is `cluster_topology.group_id` -- the stable,
    role-independent digest `checkpoint/cp_runner.py::enrich_cluster_topology`
    already attaches to both plain ClusterXL members and VSX physical hosts
    that run classic ClusterXL underneath (the standard VSX topology; no VSLS
    assumption is made or needed). The legacy flat `cluster` field is a
    fallback only, for fixtures/history predating that nested shape -- it is
    never primary once nested topology exists. `cluster_topology.display_name`
    is presentation only and never decides grouping.

    A VSX Virtual System is always its own unit and never inherits its
    physical host's verdict (correctness rule 7) -- but it carries `parent_id`
    pointing at its resolved physical cluster/host unit, so the UI can present
    it as subordinate to that physical context without changing how its own
    verdict is computed.
    """
    units: list[HaUnit] = []
    clusters: dict[str, list[str]] = {}
    cluster_display_names: dict[str, str] = {}
    cluster_is_vsx: dict[str, bool] = {}
    physical_unit_by_device: dict[str, str] = {}
    vs_rows: list[tuple[str, str]] = []  # (entity_id, physical device)

    for row in rows:
        source = str(row.get("source") or "").strip().lower()
        if source not in {"cp", "vsx"}:
            continue
        entity_id = resolve_entity_id(row)
        if not entity_id:
            continue
        vs_id = str(row.get("vs_id") or "").strip()
        device = str(row.get("device") or "").strip()

        # A VSX virtual system is always its own unit and never joins its
        # host's cluster grouping (correctness rule 7) -- deferred until the
        # physical-grouping pass below resolves a parent_id for it.
        if source == "vsx" and vs_id:
            vs_rows.append((entity_id, device))
            continue

        is_vsx = _row_is_vsx(row, source)
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
                cluster_mode=str((cp_ha_runtime.get(entity_id) or {}).get("ha_cluster_mode") or "unknown"),
            ))
            physical_unit_by_device[device] = entity_id
        # A standalone CP gateway with no cluster identity is not an HA unit
        # at all and is omitted rather than reported as a broken one.

    for cluster_key, members in clusters.items():
        modes = {
            str((cp_ha_runtime.get(m) or {}).get("ha_cluster_mode") or "").strip()
            for m in members
        }
        modes.discard("")
        modes.discard("unknown")
        unit_type = _UNIT_CP_VSX_HOST if cluster_is_vsx.get(cluster_key) else _UNIT_CP_CLUSTER
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
            physical_unit_by_device.setdefault(member_device, cluster_key)

    for entity_id, device in vs_rows:
        units.append(HaUnit(
            unit_id=entity_id, unit_type=_UNIT_CP_VSX_VS, vendor="checkpoint",
            members=[entity_id],
            parent_id=physical_unit_by_device.get(device),
            cluster_mode=str((cp_ha_runtime.get(entity_id) or {}).get("ha_cluster_mode") or "unknown"),
        ))

    return units


def _derive_pan_units(
    rows: Sequence[Mapping[str, Any]],
    pan_ha_runtime: Mapping[str, Mapping[str, Any]],
    pan_ha_peers: Mapping[str, str],
) -> list[HaUnit]:
    """Assemble PAN HA pairs (contract P7).

    `unified.json` carries no PAN peer relationship today — PAN rows have no
    `cluster` and no peer reference — so the pair is inferred by matching a
    device's configured `peer-ip` against another PAN entity's management
    address. Fail-closed: a `peer-ip` resolving to zero or to more than one
    entity yields a single-member unit with `pan_ha_peer_unresolved`. It is
    never guessed and never silently merged.
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
        # device simply is not part of an HA pair. Omit it.
        if str(enabled or "").strip().lower() not in {"yes", "true", "1"}:
            continue

        peer_ip = str(pan_ha_peers.get(entity_id) or "").strip()
        candidates = [e for e in by_management_ip.get(peer_ip, []) if e != entity_id] if peer_ip else []
        mode = str(runtime.get("mode") or "unknown").strip() or "unknown"

        if len(candidates) == 1:
            peer = candidates[0]
            paired.add(entity_id)
            paired.add(peer)
            units.append(HaUnit(
                unit_id=f"{entity_id}+{peer}", unit_type=_UNIT_PAN_PAIR, vendor="panorama",
                members=sorted([entity_id, peer]), cluster_mode=mode,
            ))
        else:
            paired.add(entity_id)
            units.append(HaUnit(
                unit_id=entity_id, unit_type=_UNIT_PAN_PAIR, vendor="panorama",
                members=[entity_id], cluster_mode=mode,
            ))
    return units


def compute_ha_readiness(
    unified_devices: Sequence[Mapping[str, Any]],
    *,
    cp_ha_runtime: Mapping[str, Mapping[str, Any]] | None = None,
    pan_ha_runtime: Mapping[str, Mapping[str, Any]] | None = None,
    pan_ha_peers: Mapping[str, str] | None = None,
    generated_at: str | None = None,
) -> dict[str, Any]:
    """Compute a `securityexpert-ha-readiness-v1` record.

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

    The result contains no management address, no raw device output and no
    command string other than the fixed `missing_evidence` labels.
    """
    cp_runtime = cp_ha_runtime or {}
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

    assessments: list[UnitAssessment] = []
    for unit in sorted(units, key=lambda u: (u.vendor, u.unit_type, u.unit_id)):
        checks = _evaluate_checks(unit, cp_ha_runtime=cp_runtime, pan_ha_runtime=pan_runtime)
        verdict, reason = _verdict_for(unit, checks)
        if (
            unit.vendor == "panorama"
            and len(unit.members) == 1
            and verdict == VERDICT_INSUFFICIENT
        ):
            reason = "pan_ha_peer_unresolved"
        assessments.append(UnitAssessment(unit, verdict, reason, checks))

    summary = {
        VERDICT_SAFE: 0,
        VERDICT_DEGRADED: 0,
        VERDICT_UNSAFE: 0,
        VERDICT_INSUFFICIENT: 0,
        VERDICT_NOT_A_FAILOVER_UNIT: 0,
    }
    for assessment in assessments:
        summary[assessment.verdict] += 1

    return {
        "schema": SCHEMA,
        "generated_at": generated_at or _utc_now(),
        "units": [a.to_dict() for a in assessments],
        "summary": summary,
    }
