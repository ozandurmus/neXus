"""Failover-plane modes (OP.0a).

Contract: docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md.

``--ha-readiness-check`` only. This is an **offline maintenance-class mode**:
it opens no network connection, holds no credential and issues no device
command — it derives an assessment from evidence a previous collection run
already stored. Same posture as ``--restore-readiness-check``.

Nothing vendor-bound is imported at module scope (the AC-3 lazy-import
boundary the ``application`` package establishes).
"""
from __future__ import annotations

import json
from pathlib import Path

from application.services import _require_bootstrap


def _load_cp_ha_runtime(output_root) -> dict[str, dict]:
    """Read `ha_role` / `ha_cluster_mode` per entity from
    `cp_config_telemetry.json`, as populated by a prior `--cp-config-collect`
    run from the already-gated `cphaprob stat`.

    Missing, corrupt or malformed -> `{}` ("no HA runtime evidence"), never an
    error. Every CP unit then reports INSUFFICIENT_EVIDENCE, which is the
    correct answer rather than a degraded mode (contract correctness rule 6).
    """
    path = Path(output_root) / "cp_config_telemetry.json"
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    if not isinstance(doc, dict):
        return {}
    runtime: dict[str, dict] = {}
    for device in doc.get("devices") or []:
        if not isinstance(device, dict):
            continue
        entity_id = str(device.get("entity_id") or "").strip()
        if not entity_id:
            continue
        runtime[entity_id] = {
            "ha_role": device.get("ha_role"),
            "ha_cluster_mode": device.get("ha_cluster_mode") or "unknown",
        }
    return runtime


def _load_pan_ha_runtime(output_root) -> tuple[dict[str, dict], dict[str, str]]:
    """Read PAN HA runtime state and the configured peer address per entity
    from `pan_config_telemetry.json`.

    Returns `(runtime, peers)`. Same fail-safe posture as the CP loader: a
    missing or corrupt file degrades to empty maps, never to an error. `peers`
    feeds the contract-P7 pair assembly, which is fail-closed on its own.
    """
    path = Path(output_root) / "pan_config_telemetry.json"
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}, {}
    if not isinstance(doc, dict):
        return {}, {}
    runtime: dict[str, dict] = {}
    peers: dict[str, str] = {}
    for device in doc.get("devices") or []:
        if not isinstance(device, dict):
            continue
        entity_id = str(device.get("entity_id") or device.get("device") or "").strip()
        if not entity_id:
            continue
        ha = device.get("ha_runtime")
        if isinstance(ha, dict):
            runtime[entity_id] = {
                "enabled": ha.get("enabled"),
                "state": ha.get("state"),
                "mode": ha.get("mode"),
                "peer_state": ha.get("peer_state"),
                "state_sync": ha.get("state_sync"),
            }
            peer_ip = str(ha.get("peer_ip") or "").strip()
            if peer_ip:
                peers[entity_id] = peer_ip
    return runtime, peers


def ha_readiness_check(ctx):
    runtime_paths = ctx.runtime_paths
    _require_bootstrap("ha-readiness-check", runtime_paths.output_root)
    from utils.failover import compute_ha_readiness

    print("=== SECURITYEXPERT HA READINESS — OP.0a ===\n")
    unified_path = runtime_paths.output_root / "unified.json"
    unified_devices = json.loads(unified_path.read_text(encoding="utf-8"))

    cp_ha_runtime = _load_cp_ha_runtime(runtime_paths.output_root)
    pan_ha_runtime, pan_ha_peers = _load_pan_ha_runtime(runtime_paths.output_root)

    report = compute_ha_readiness(
        unified_devices,
        cp_ha_runtime=cp_ha_runtime,
        pan_ha_runtime=pan_ha_runtime,
        pan_ha_peers=pan_ha_peers,
    )

    state_dir = runtime_paths.data_root / "state"
    state_dir.mkdir(parents=True, exist_ok=True)
    state_path = state_dir / "ha_readiness.json"
    state_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    summary = report["summary"]
    total = sum(summary.values())
    print(f"HA units assessed:     {total}")
    for verdict in (
        "SAFE_TO_FAILOVER", "DEGRADED_PROCEED_WITH_RISK", "UNSAFE_DO_NOT_FAILOVER",
        "INSUFFICIENT_EVIDENCE", "NOT_A_FAILOVER_UNIT",
    ):
        print(f"  {verdict:<28} {summary.get(verdict, 0)}")

    # The framing this build must always carry with it (contract P4 / risks).
    # Without it, an all-INSUFFICIENT result reads as a broken feature rather
    # than as the honest state of the evidence.
    print(
        "\nNote: OP.0a assesses only the stop-conditions answerable from evidence "
        "already collected. It CANNOT report a cluster safe to fail over -- "
        "SAFE_TO_FAILOVER is unreachable by design until the OP.0b preflight "
        "battery is gated and built. INSUFFICIENT_EVIDENCE here means "
        "'not asked yet', not 'unhealthy'."
    )
    print(f"\nWrote {state_path}")
    return 0
