"""OP.0b S7.5 -- controlled preflight application entrypoint.

Contract chain: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(FROZEN WITH REAL-ENV VALIDATION GATES) -> `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`
(APPROVED) -> the S5/S6/S7 implementation slices this build wires together.

S8 proved S5/S6 (`checkpoint.preflight_collector.run_cp_preflight` /
`panorama.preflight_collector.run_pan_preflight`) were unreachable through
the controlled application boundary. This module is the bounded seam: one
explicitly selected HA operational entity, its exact bounded physical
members, resolved fail-closed from already-collected local inventory before
any device is contacted -> exactly one S5 or S6 collection call -> the
resulting `PreflightSnapshot` handed, unmodified and unpersisted, straight
into the canonical S7 evaluator (`utils.failover.compute_ha_readiness`).

READ-ONLY HA PREFLIGHT. Contacts only the explicitly selected HA members.
Performs fresh device reads. No failover action is ever executed here --
`utils/failover/` remains read-only assessment/evidence only. Readiness may
still come back INSUFFICIENT_EVIDENCE; that is the honest answer for
whatever the fresh reads did not establish, never a broken feature.

No new device command, no new API operation, no retry, no fallback
collector, no persisted preflight snapshot/evidence artifact. This module
computes no check and no verdict of its own -- `compute_ha_readiness` /
`utils.failover.preflight_readiness` remain the one readiness authority.

Nothing vendor-bound is imported at module scope (the AC-3 lazy-import
boundary the ``application`` package establishes).
"""
from __future__ import annotations

import json
from pathlib import Path

from application.services import _require_bootstrap, make_admitted, make_runtime_config
from application.workflows.failover import _load_cp_ha_runtime, _load_pan_ha_runtime

#: Bounded, caller-selected physical members only -- matches the S5/S6
#: collectors' own `MAX_PHYSICAL_MEMBERS`. Enforced here too, before any
#: local resolution work, so an over-wide request never reaches a selector.
_MAX_PHYSICAL_MEMBERS = 2


class PreflightTargetResolutionError(RuntimeError):
    """Raised when the caller-supplied targets do not resolve to exactly one
    known operational HA entity -- always before any device is contacted."""


def _parse_requested_targets(raw, *, label: str) -> list[str]:
    if not raw or not str(raw).strip():
        raise PreflightTargetResolutionError(f"{label}: no target supplied")
    values = list(dict.fromkeys(t.strip() for t in str(raw).split(",") if t.strip()))
    if not values:
        raise PreflightTargetResolutionError(f"{label}: no valid target supplied")
    if len(values) > _MAX_PHYSICAL_MEMBERS:
        raise PreflightTargetResolutionError(
            f"{label}: at most {_MAX_PHYSICAL_MEMBERS} physical members may be selected for one HA "
            f"preflight entity, refusing to contact any device (received {len(values)})"
        )
    return values


def _load_unified_devices(output_root) -> list[dict]:
    path = Path(output_root) / "unified.json"
    return json.loads(path.read_text(encoding="utf-8"))


def _print_safe_result(report: dict, *, operational_unit_id: str, vendor: str, member_count: int) -> None:
    """Operator-visible, privacy-safe summary (contract S7.5 §13): never a
    raw serial, management/HA IP, raw command output, raw XML or credential
    -- only the fields `compute_ha_readiness` already treats as safe to
    disclose (verdict, reason, per-check status/reason, opaque run id)."""
    unit = next((u for u in report.get("units") or [] if u.get("unit_id") == operational_unit_id), None)
    print(f"Vendor:                  {vendor}")
    print(f"Operational unit:        {operational_unit_id}")
    print(f"Bounded members:         {member_count}")
    preflight = report.get("preflight") or {}
    print(f"Snapshot applied:        {operational_unit_id in (preflight.get('applied') or [])}")
    if unit is None:
        print("Canonical readiness:     unit not found by the readiness evaluator (see preflight.unmatched)")
        return
    evidence = unit.get("evidence") or {}
    print(f"Preflight run id:        {evidence.get('preflight_run_id', 'n/a')}")
    print(f"Coherent:                {evidence.get('coherent', 'n/a')}")
    print(f"Readiness verdict:       {unit.get('verdict')}")
    print(f"Reason:                  {unit.get('reason')}")
    print("Checks:")
    for check in unit.get("checks") or []:
        missing = f" ({check['missing_evidence']})" if check.get("missing_evidence") else ""
        print(f"  {check.get('id', ''):<24} {check.get('status', ''):<22} {check.get('reason', '')}{missing}")


# --- Check Point (S5) -------------------------------------------------------

def _resolve_cp_operational_entity(output_root, requested: list[str]):
    """Fail-closed CP target resolution, reusing the exact same physical-host
    entity_id selector `--cp-config-targets` already uses
    (`configuration.checkpoint_config_collector._apply_cp_target_selector`)
    against the same already-collected local candidates
    (`_resolve_targets()`, sourced from `cp_telemetry.json`/`cp.json`/
    `vsx.json` -- no device is contacted to build this candidate set).

    Establishes `(operational_entity_id, unit_type, selected physical
    targets)` before returning; every requested id must resolve exactly, and
    every resolved target must belong to the same intended operational HA
    entity (same `cluster_group_id`), or this raises before any SSH
    connection opens.
    """
    import configuration.checkpoint_config_collector as cp_collector

    cp_collector.OUTPUT_DIR = Path(output_root)
    try:
        targets, _skipped = cp_collector._resolve_targets()
    except RuntimeError as exc:
        raise PreflightTargetResolutionError(str(exc)) from exc

    try:
        selected = cp_collector._apply_cp_target_selector(targets, requested)
    except ValueError as exc:
        raise PreflightTargetResolutionError(str(exc)) from exc

    entity_types = {t.entity_type for t in selected}
    if not entity_types <= {"clusterxl_member", "vsx_host"}:
        raise PreflightTargetResolutionError(
            "cp_preflight_targets: every selected target must be a ClusterXL member or VSX host "
            "(not a standalone gateway or another management object type), refusing to contact any device"
        )
    if len(entity_types) > 1:
        raise PreflightTargetResolutionError(
            "cp_preflight_targets: selected targets mix ClusterXL and VSX host entity types, "
            "refusing to guess one operational HA entity"
        )

    cluster_ids = {t.cluster_group_id for t in selected if t.cluster_group_id}
    if len(cluster_ids) > 1:
        raise PreflightTargetResolutionError(
            "cp_preflight_targets: selected targets belong to more than one cluster, "
            "refusing to contact any device"
        )
    if cluster_ids:
        if any(not t.cluster_group_id for t in selected):
            raise PreflightTargetResolutionError(
                "cp_preflight_targets: selected targets are only partially grouped into one cluster, "
                "refusing to contact any device"
            )
        operational_entity_id = next(iter(cluster_ids))
    elif len(selected) == 1:
        operational_entity_id = selected[0].device
    else:
        raise PreflightTargetResolutionError(
            "cp_preflight_targets: selected targets are ungrouped and do not resolve to one "
            "operational HA entity, refusing to contact any device"
        )

    unit_type = "vsx" if entity_types == {"vsx_host"} else "clusterxl"
    return operational_entity_id, unit_type, selected


def cp_ha_preflight_check(ctx):
    runtime_paths = ctx.runtime_paths
    args = ctx.args
    _admitted = make_admitted(ctx)
    _runtime_config = make_runtime_config(ctx)

    print("=== SECURITYEXPERT CHECK POINT HA PREFLIGHT — OP.0b S7.5 ===\n")
    print(
        "READ-ONLY HA PREFLIGHT: contacts only the explicitly selected HA members, performs\n"
        "fresh device reads, executes no failover action. Current readiness may remain\n"
        "INSUFFICIENT_EVIDENCE -- that is the honest answer for what fresh reads did not\n"
        "establish, not a broken feature.\n"
    )
    _require_bootstrap("cp-ha-preflight-check", runtime_paths.output_root)

    requested = _parse_requested_targets(args.cp_preflight_targets, label="cp_preflight_targets")
    operational_entity_id, unit_type, selected = _resolve_cp_operational_entity(
        runtime_paths.output_root, requested,
    )

    from checkpoint.preflight_collector import CPPhysicalMemberTarget, run_cp_preflight

    members = [
        CPPhysicalMemberTarget(
            physical_device_identity=target.device,
            expected_device_name=target.device,
            management_ip=target.management_ip,
        )
        for target in selected
    ]

    cfg = _runtime_config(require_cp=True, require_panorama=False)
    try:
        snapshot = _admitted(
            "checkpoint",
            "cp-ha-preflight-check",
            cfg.mds_ip,
            lambda: run_cp_preflight(
                operational_entity_id=operational_entity_id,
                unit_type=unit_type,
                members=members,
                username=cfg.auth.principal,
                secret=cfg.auth.secret,
            ),
        )
    finally:
        cfg.clear_credentials()

    from utils.failover import compute_ha_readiness

    unified_devices = _load_unified_devices(runtime_paths.output_root)
    cp_ha_runtime = _load_cp_ha_runtime(runtime_paths.output_root)
    report = compute_ha_readiness(
        unified_devices,
        cp_ha_runtime=cp_ha_runtime,
        preflight_snapshots=[snapshot],
    )
    print("\n=== SAFE READINESS SUMMARY ===")
    _print_safe_result(
        report, operational_unit_id=operational_entity_id, vendor="checkpoint", member_count=len(members),
    )
    return 0


# --- Palo Alto (S6) ----------------------------------------------------------

def _resolve_pan_operational_entity(runtime_paths, requested: list[str], pan_ha_runtime, pan_ha_peers):
    """Fail-closed PAN target resolution, reusing the exact same serial
    selector `--pan-config-targets` already uses
    (`configuration.panorama_config_collector._apply_pan_target_selector`)
    against already-collected local candidates (`unified.json`'s Panorama-
    sourced rows -- no live Panorama "show devices" call is made to build
    this candidate set; that would be a new API operation beyond S6's
    approved direct-firewall-only battery). `unified.json` carries no live
    connectivity fact for a Panorama-discovered device, so the selector's
    "currently connected" bound is evaluated against the same local
    candidate set -- S6's own per-member identity gate (`P1`) is what
    actually proves reachability, at contact time, exactly as it already
    does for every other caller of this collector.

    The resolved operational_unit_id must equal what
    `utils.failover.derive_ha_units` -- the same derivation
    `compute_ha_readiness` uses internally -- independently derives for the
    same selected members. Anything else (an unresolved/asymmetric peer
    relationship for a two-member request, or no matching unit at all) is
    the open `B2` bidirectional pair-identity-corroboration boundary this
    slice does not attempt to redesign, and fails closed before any device
    is contacted.
    """
    import configuration.panorama_config_collector as pan_collector
    from utils.failover import derive_ha_units
    from utils.restore_readiness import resolve_entity_id

    unified_devices = _load_unified_devices(runtime_paths.output_root)
    devices = [
        row for row in unified_devices
        if isinstance(row, dict) and str(row.get("source") or "").strip().lower() == "panorama"
        and str(row.get("serial") or "").strip()
    ]

    try:
        selected_rows = pan_collector._apply_pan_target_selector(devices, devices, requested)
    except ValueError as exc:
        raise PreflightTargetResolutionError(str(exc)) from exc

    selected_entity_ids = {resolve_entity_id(row) for row in selected_rows}

    units = derive_ha_units(unified_devices, pan_ha_runtime=pan_ha_runtime, pan_ha_peers=pan_ha_peers)
    matching = [u for u in units if u.vendor == "panorama" and set(u.members) == selected_entity_ids]
    if len(matching) != 1:
        raise PreflightTargetResolutionError(
            "pan_preflight_targets: selected targets do not resolve to exactly one known operational "
            "HA entity (pair identity B2 remains unresolved for this selection), refusing to contact any device"
        )
    unit = matching[0]

    by_entity_id = {resolve_entity_id(row): row for row in selected_rows}
    return unit.unit_id, [by_entity_id[entity_id] for entity_id in sorted(selected_entity_ids)]


def pan_ha_preflight_check(ctx):
    runtime_paths = ctx.runtime_paths
    args = ctx.args
    _admitted = make_admitted(ctx)
    _runtime_config = make_runtime_config(ctx)

    print("=== SECURITYEXPERT PALO ALTO HA PREFLIGHT — OP.0b S7.5 ===\n")
    print(
        "READ-ONLY HA PREFLIGHT: contacts only the explicitly selected HA members, performs\n"
        "fresh device reads, executes no failover action. Current readiness may remain\n"
        "INSUFFICIENT_EVIDENCE -- that is the honest answer for what fresh reads did not\n"
        "establish, not a broken feature.\n"
    )
    _require_bootstrap("pan-ha-preflight-check", runtime_paths.output_root)

    requested = _parse_requested_targets(args.pan_preflight_targets, label="pan_preflight_targets")
    pan_ha_runtime, pan_ha_peers = _load_pan_ha_runtime(runtime_paths.output_root)
    operational_entity_id, selected_rows = _resolve_pan_operational_entity(
        runtime_paths, requested, pan_ha_runtime, pan_ha_peers,
    )

    from panorama.preflight_collector import PANPhysicalMemberTarget, run_pan_preflight
    from utils.restore_readiness import resolve_entity_id
    from configuration.panorama_config_collector import _direct_tls_verify_setting, _direct_timeout_seconds

    members = [
        PANPhysicalMemberTarget(
            physical_device_identity=resolve_entity_id(row),
            expected_serial=str(row.get("serial") or ""),
            management_ip=str(row.get("management_ip") or ""),
        )
        for row in selected_rows
    ]

    cfg = _runtime_config(require_cp=False, require_panorama=True)
    try:
        snapshot = _admitted(
            "panorama",
            "pan-ha-preflight-check",
            cfg.panorama_ip,
            lambda: run_pan_preflight(
                operational_entity_id=operational_entity_id,
                members=members,
                username=cfg.auth.principal,
                secret=cfg.auth.secret,
                verify=_direct_tls_verify_setting(),
                timeout=_direct_timeout_seconds(),
            ),
        )
    finally:
        cfg.clear_credentials()

    from utils.failover import compute_ha_readiness

    unified_devices = _load_unified_devices(runtime_paths.output_root)
    report = compute_ha_readiness(
        unified_devices,
        pan_ha_runtime=pan_ha_runtime,
        pan_ha_peers=pan_ha_peers,
        preflight_snapshots=[snapshot],
    )
    print("\n=== SAFE READINESS SUMMARY ===")
    _print_safe_result(
        report, operational_unit_id=operational_entity_id, vendor="panorama", member_count=len(members),
    )
    return 0
