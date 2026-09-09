"""SecurityExpert — recovery_ui payload builder (RB.5).

Contract: docs/design/BACKUP_RECOVERY_CONTRACTS.md §6 (frozen rules 1-4).
Architecture: docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md §11.

``build_recovery_ui_payload`` is a pure function over already-computed
persisted state -- the RB.0 readiness record
(``utils.restore_readiness.compute_restore_readiness``'s own output shape,
persisted at ``data/state/restore_readiness.json``) plus an optional
retention pending-deletion list. It performs no I/O, talks to no device and
computes no readiness verdict of its own -- it only reshapes fields RB.0
(readiness), RB.1 manifests (``restore_proven``, retention metadata) and
RB.4 (validation level) already produced, so a future surface (UI 2.0) can
call the exact same function over the exact same persisted state and never
fork the posture math (invariant: the recovery_ui builder stays a pure
projection).

Frozen rules enforced by construction here (contract §6):

1. No payload bytes, no download URL, no decrypt affordance -- only
   class/age/validation-level/entity identity ever leave this module.
2. ``restore_proven`` is always an explicit boolean field; this module never
   emits the bare word "verified".
3. ``available: False`` is an explicit empty state, never a silently empty
   module.

The ``load_*`` functions below are the only I/O in this file -- they read
the persisted readiness record and scan the recovery store for retention
metadata, then hand plain dicts to the pure builder above. They degrade to
"no evidence" (``None`` / ``[]``) on any missing/corrupt input, exactly like
every other RB.x reader (``application/workflows/recovery.py``'s
``_load_recovery_attestations``) -- never an error, never a device contact.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence

SCHEMA = "securityexpert-recovery-ui-v1"

_ALL_STATES = ("READY", "STALE", "PARTIAL", "UNPROTECTED", "UNKNOWN")


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _empty_payload(generated_at: str) -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "available": False,
        "generated_at": generated_at,
        "readiness_summary": {state: 0 for state in _ALL_STATES},
        "coverage": {
            "devices_in_inventory": 0,
            "devices_with_any_artifact": 0,
            "coverage_percent": 0,
        },
        "devices": [],
        "retention_pending_deletion": [],
    }


def _artifact_row(artifact: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "class": artifact.get("class"),
        "age_days": artifact.get("age_days"),
        "validation_level": artifact.get("validation_level"),
        # Frozen rule 2 (§6): an explicit badge field, defaulting false --
        # never invented true, never the bare word "verified".
        "restore_proven": bool(artifact.get("restore_proven", False)),
    }


def _retention_row(entry: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "entity_id": entry.get("entity_id"),
        "artifact_id": entry.get("artifact_id"),
        "expires_at": entry.get("expires_at"),
    }


def build_recovery_ui_payload(
    readiness_record: Mapping[str, Any] | None,
    *,
    retention_pending_deletion: Sequence[Mapping[str, Any]] | None = None,
    generated_at: str | None = None,
) -> dict[str, Any]:
    """Project the contract §6 ``recovery_ui`` payload.

    ``readiness_record`` is an ``utils.restore_readiness.compute_restore_
    readiness`` output dict (or the persisted ``data/state/restore_
    readiness.json``); each ``devices[].held_artifacts[]`` entry may carry an
    additional ``restore_proven`` bool (RB.4 V4, absent -> False) alongside
    the §5-frozen ``class``/``age_days``/``validation_level``/
    ``version_matches_running`` fields -- an additive annotation, not a
    reclassification of RB.0's own verdict.

    ``retention_pending_deletion`` is an already-resolved
    ``[{entity_id, artifact_id, expires_at}, ...]`` list (RB.1 retention
    metadata) -- this function does not compute retention policy, only
    projects rows a caller already resolved.

    Missing/malformed ``readiness_record`` -> ``available: False`` (frozen
    rule 3): an explicit empty state, e.g. before the first
    ``--restore-readiness-check`` run.
    """
    now = generated_at or _utc_now()
    if not isinstance(readiness_record, Mapping):
        return _empty_payload(now)
    devices = readiness_record.get("devices")
    if not isinstance(devices, Sequence):
        return _empty_payload(now)

    readiness_summary = {state: 0 for state in _ALL_STATES}
    raw_summary = readiness_record.get("summary")
    if isinstance(raw_summary, Mapping):
        for state in _ALL_STATES:
            readiness_summary[state] = int(raw_summary.get(state) or 0)

    device_rows: list[dict[str, Any]] = []
    devices_with_any_artifact = 0
    for device in devices:
        if not isinstance(device, Mapping):
            continue
        held = device.get("held_artifacts")
        held = [a for a in held if isinstance(a, Mapping)] if isinstance(held, Sequence) else []
        if held:
            devices_with_any_artifact += 1
        device_rows.append({
            "entity_id": device.get("entity_id"),
            "state": device.get("state"),
            "artifacts": [_artifact_row(a) for a in held],
        })

    devices_in_inventory = len(device_rows)
    coverage_percent = (
        round(100 * devices_with_any_artifact / devices_in_inventory)
        if devices_in_inventory else 0
    )

    retention_rows = [
        _retention_row(entry)
        for entry in (retention_pending_deletion or ())
        if isinstance(entry, Mapping)
    ]

    return {
        "schema": SCHEMA,
        "available": True,
        "generated_at": readiness_record.get("generated_at") or now,
        "readiness_summary": readiness_summary,
        "coverage": {
            "devices_in_inventory": devices_in_inventory,
            "devices_with_any_artifact": devices_with_any_artifact,
            "coverage_percent": coverage_percent,
        },
        "devices": device_rows,
        "retention_pending_deletion": retention_rows,
    }


def readiness_by_entity(readiness_record: Mapping[str, Any] | None) -> dict[str, dict[str, Any]]:
    """``entity_id -> device readiness dict`` (minus ``entity_id`` itself),
    for ``compliance_posture``'s additive readiness evidence source
    (architecture §11: "for a future backup-coverage control", no new
    control created here). Malformed/absent input -> ``{}``, the same
    on-no-evidence posture every other evidence namespace degrades to."""
    if not isinstance(readiness_record, Mapping):
        return {}
    devices = readiness_record.get("devices")
    if not isinstance(devices, Sequence):
        return {}
    out: dict[str, dict[str, Any]] = {}
    for device in devices:
        if not isinstance(device, Mapping):
            continue
        entity_id = device.get("entity_id")
        if not entity_id:
            continue
        out[str(entity_id)] = {k: v for k, v in device.items() if k != "entity_id"}
    return out


def load_persisted_readiness_record(data_root: Any) -> dict[str, Any] | None:
    """Read ``<data_root>/state/restore_readiness.json`` (RB.0's own persisted
    output). Missing/corrupt/malformed -> ``None`` (available: False), never
    an error -- the same fail-safe posture as ``_load_recovery_attestations``."""
    path = Path(data_root) / "state" / "restore_readiness.json"
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return doc if isinstance(doc, dict) else None


def load_retention_pending_deletion(
    *, repository_root: Any = None, runtime_root: Any = None, as_of: str | None = None,
) -> list[dict[str, Any]]:
    """Scan the recovery store (``SECURITYEXPERT_RECOVERY_ROOT``, if resolvable)
    for artifacts whose ``manifest.retention.expires_at`` has already passed
    -- eligible for deletion, but not yet removed (``--recovery-store-check
    --apply`` is a separate, explicit operator action). No recovery volume
    configured yet -> ``[]``, matching architecture §11/§5's "the UI must be
    able to render it before any recovery volume exists". No device contact,
    no artifact bytes read -- manifests only."""
    from utils.recovery_manifest import RecoveryManifestError
    from utils.recovery_store import RecoveryStoreError, list_artifact_dirs, read_manifest
    from utils.runtime_paths import RuntimePathError, resolve_recovery_root

    try:
        recovery_paths = resolve_recovery_root(
            None, repository_root=repository_root, runtime_root=runtime_root,
        )
    except RuntimePathError:
        return []

    now = as_of or _utc_now()
    pending: list[dict[str, Any]] = []
    for artifact_dir in list_artifact_dirs(recovery_paths):
        try:
            manifest = read_manifest(artifact_dir)
        except (OSError, ValueError, RecoveryStoreError, RecoveryManifestError):
            continue
        retention = manifest.get("retention") or {}
        expires_at = retention.get("expires_at")
        if expires_at and expires_at <= now:
            pending.append({
                "entity_id": (manifest.get("device") or {}).get("entity_id"),
                "artifact_id": manifest.get("artifact_id"),
                "expires_at": expires_at,
            })
    pending.sort(key=lambda row: row["expires_at"])
    return pending
