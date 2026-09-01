"""SecurityExpert — HA/failover readiness UI payload (OP.0c).

Contract: docs/design/FAILOVER_ENGINE_ARCHITECTURE.md §9 (dashboard scope),
§10.1 (OP.0c is CLASS 0, no gate). Domain source of truth: `utils.failover`
(OP.0a) — this module performs no verdict/check computation of its own, only
a sanitized projection over `compute_ha_readiness`'s output plus fixed UI
labels, so the readiness semantics cannot fork between the CLI and the
console (the same C1-4 guarantee the other *_ui.py builders already hold).

`extract_cp_ha_runtime` / `extract_pan_ha_runtime` are pure functions over an
already-parsed `cp_config_telemetry.json` / `pan_config_telemetry.json` dict
(no file I/O here) — `application/workflows/failover.py`'s
`--ha-readiness-check` CLI mode reads the file and calls these same two
functions, so the CLI snapshot and this live console projection can never
disagree about what a telemetry file means.

This module never talks to a device, issues no command and holds no
credential — same posture as `utils.failover.assessment`.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Mapping, Sequence

from utils.failover import compute_ha_readiness

UI_SCHEMA_VERSION = "op0c-failover-readiness-v1"

# Contract P4 / risks_forward: OP.0a can never emit SAFE_TO_FAILOVER by
# construction. Without this line surfaced in the UI itself, an
# all-INSUFFICIENT_EVIDENCE fleet reads as a broken feature rather than the
# honest state of the evidence -- the exact framing application/workflows/
# failover.py already prints for the CLI. One string, reused by both.
FRAMING_NOTE = (
    "This assessment covers only the stop-conditions answerable from evidence "
    "already collected. It CANNOT report a cluster safe to fail over -- "
    "SAFE_TO_FAILOVER is unreachable by design until the OP.0b preflight "
    "battery is gated and built. INSUFFICIENT_EVIDENCE means 'not asked yet', "
    "not 'unhealthy'."
)

# CLASS 2 (failover execution) does not exist yet -- OP.2 hard prerequisites
# (FAILOVER_ENGINE_ARCHITECTURE.md §10) are unmet. Fixed label, not a control:
# the UI renders this as inert text, never a button.
EXECUTION_UNAVAILABLE_NOTE = (
    "Failover execution is not available in this build. Controlled execution "
    "(OP.2) requires the OIDC/RBAC OPERATE boundary, the network-device "
    "command gate for the write primitives, and a signed-off change-management "
    "review -- none of which exist yet."
)

VERDICT_LABELS: Mapping[str, str] = {
    "SAFE_TO_FAILOVER": "Safe to fail over",
    "DEGRADED_PROCEED_WITH_RISK": "Degraded — proceed with risk",
    "UNSAFE_DO_NOT_FAILOVER": "Unsafe — do not fail over",
    "INSUFFICIENT_EVIDENCE": "Insufficient evidence",
    "NOT_A_FAILOVER_UNIT": "Not a failover unit",
}

# success/warning/danger/muted/info/neutral -- the same tone vocabulary
# static/app_core.js's statusPill() already renders everywhere else.
VERDICT_TONES: Mapping[str, str] = {
    "SAFE_TO_FAILOVER": "success",
    "DEGRADED_PROCEED_WITH_RISK": "warning",
    "UNSAFE_DO_NOT_FAILOVER": "danger",
    "INSUFFICIENT_EVIDENCE": "muted",
    "NOT_A_FAILOVER_UNIT": "neutral",
}

CHECK_STATUS_LABELS: Mapping[str, str] = {
    "PASS": "Pass",
    "FAIL": "Fail",
    "INSUFFICIENT_EVIDENCE": "Insufficient evidence",
}

CHECK_STATUS_TONES: Mapping[str, str] = {
    "PASS": "success",
    "FAIL": "danger",
    "INSUFFICIENT_EVIDENCE": "muted",
}

UNIT_TYPE_LABELS: Mapping[str, str] = {
    "cp_clusterxl_cluster": "Check Point ClusterXL",
    "cp_vsx_host": "Check Point VSX host",
    "cp_vsx_cluster": "Check Point VSX Cluster",
    "cp_vsx_virtual_system": "Check Point VSX virtual system",
    "pan_ha_pair": "Palo Alto HA pair",
}


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def extract_cp_ha_runtime(doc: Mapping[str, Any] | None) -> dict[str, dict[str, Any]]:
    """`entity_id -> {"ha_role", "ha_cluster_mode"}` from a parsed
    `cp_config_telemetry.json` document. `None`/malformed -> `{}` (every CP
    unit then reports INSUFFICIENT_EVIDENCE, the correct answer, never an
    error)."""
    if not isinstance(doc, Mapping):
        return {}
    runtime: dict[str, dict[str, Any]] = {}
    for device in doc.get("devices") or []:
        if not isinstance(device, Mapping):
            continue
        entity_id = str(device.get("entity_id") or "").strip()
        if not entity_id:
            continue
        runtime[entity_id] = {
            "ha_role": device.get("ha_role"),
            "ha_cluster_mode": device.get("ha_cluster_mode") or "unknown",
        }
    return runtime


def extract_pan_ha_runtime(
    doc: Mapping[str, Any] | None,
) -> tuple[dict[str, dict[str, Any]], dict[str, str]]:
    """`(runtime, peers)` from a parsed `pan_config_telemetry.json` document,
    same fail-safe posture as `extract_cp_ha_runtime`."""
    if not isinstance(doc, Mapping):
        return {}, {}
    runtime: dict[str, dict[str, Any]] = {}
    peers: dict[str, str] = {}
    for device in doc.get("devices") or []:
        if not isinstance(device, Mapping):
            continue
        entity_id = str(device.get("entity_id") or device.get("device") or "").strip()
        if not entity_id:
            continue
        ha = device.get("ha_runtime")
        if isinstance(ha, Mapping):
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


def build_failover_readiness_payload(
    unified_devices: Sequence[Mapping[str, Any]] | None,
    *,
    checkpoint_config_result: Mapping[str, Any] | None = None,
    config_result: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """The `failoverReadinessData` payload the console/report embed.

    `unified_devices` is `rawData` (already loaded by `build_report_payloads`
    from `unified.json`). `checkpoint_config_result`/`config_result` are the
    same already-loaded `cp_config_telemetry.json`/`pan_config_telemetry.json`
    dicts passed to the configuration/crypto builders -- no extra file read.
    Missing/empty inputs degrade to an explicit empty fleet, never an error
    (the same posture `build_discovery_capability_payload` established).
    """
    rows = unified_devices if isinstance(unified_devices, Sequence) else []
    cp_ha_runtime = extract_cp_ha_runtime(checkpoint_config_result)
    pan_ha_runtime, pan_ha_peers = extract_pan_ha_runtime(config_result)

    report = compute_ha_readiness(
        rows,
        cp_ha_runtime=cp_ha_runtime,
        pan_ha_runtime=pan_ha_runtime,
        pan_ha_peers=pan_ha_peers,
        generated_at=_utc_now(),
    )

    return {
        "schema_version": UI_SCHEMA_VERSION,
        "source_schema": report["schema"],
        "generated_at": report["generated_at"],
        "framing_note": FRAMING_NOTE,
        "execution_unavailable_note": EXECUTION_UNAVAILABLE_NOTE,
        "summary": report["summary"],
        "units": report["units"],
        "verdict_labels": dict(VERDICT_LABELS),
        "verdict_tones": dict(VERDICT_TONES),
        "check_status_labels": dict(CHECK_STATUS_LABELS),
        "check_status_tones": dict(CHECK_STATUS_TONES),
        "unit_type_labels": dict(UNIT_TYPE_LABELS),
    }
