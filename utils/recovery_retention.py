"""SecurityExpert — RB.1 recovery-plane retention (GFS + floor + ledger).

docs/design/BACKUP_RECOVERY_CONTRACTS.md §8. Dry-run by default; deletion
requires an explicit `apply=True`, mirroring
`utils/config_storage.deduplicate_legacy_storage`'s `--apply` convention.

`plan_deletions` is a pure function — no filesystem access — so the frozen
floor invariant (§9.9: retention may never drive a device to zero held
artifacts, and may never delete the last `is_rma_grade` artifact) is a
property directly testable over arbitrary policies and fleets.
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from utils.runtime_paths import RecoveryPaths

SCHEMA = "securityexpert-recovery-retention-v1"

DEFAULT_POLICY: dict[str, Any] = {
    "schema": SCHEMA,
    "policy": "gfs-default",
    "daily": 7,
    "weekly": 4,
    "monthly": 6,
    "floor": {"never_reduce_device_below": 1, "never_delete_only_rma_grade": True},
    "deletion_requires_apply_flag": True,
}


def plan_deletions(
    held_by_entity: dict[str, list[dict[str, Any]]],
    policy: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    """Compute what retention WOULD delete, honoring the floor (frozen rule 1
    of §8): never drive a device to zero held artifacts, and never delete the
    last `is_rma_grade: true` artifact even if newer non-RMA-grade ones
    exist. Performs no filesystem access and deletes nothing.

    `held_by_entity` maps `entity_id -> [{"artifact_id", "is_rma_grade",
    "tier", "age_rank"}, ...]`, where `tier` is `daily|weekly|monthly` and
    `age_rank` is 0 for the newest artifact in that tier, increasing with age.
    """
    policy = policy or DEFAULT_POLICY
    limits = {tier: int(policy.get(tier, 0)) for tier in ("daily", "weekly", "monthly")}
    floor = policy.get("floor") or {}
    min_held = int(floor.get("never_reduce_device_below", 1))
    protect_only_rma = bool(floor.get("never_delete_only_rma_grade", True))

    candidates: list[dict[str, Any]] = []
    for entity_id, artifacts in held_by_entity.items():
        by_tier: dict[str, list[dict[str, Any]]] = {}
        for a in artifacts:
            by_tier.setdefault(a["tier"], []).append(a)

        over_limit: list[dict[str, Any]] = []
        for tier, items in by_tier.items():
            limit = limits.get(tier, 0)
            items_sorted = sorted(items, key=lambda a: a["age_rank"])
            over_limit.extend(items_sorted[limit:])

        remaining = len(artifacts)
        remaining_rma = sum(1 for a in artifacts if a.get("is_rma_grade"))

        # Oldest-first so a partial tier overflow deletes the least useful
        # artifacts first when the floor stops it from deleting everything
        # that is nominally over-limit.
        for a in sorted(over_limit, key=lambda a: -a["age_rank"]):
            if remaining - 1 < min_held:
                continue
            if protect_only_rma and a.get("is_rma_grade") and remaining_rma <= 1:
                continue
            candidates.append({
                "entity_id": entity_id,
                "artifact_id": a["artifact_id"],
                "is_rma_grade": bool(a.get("is_rma_grade")),
                "tier": a["tier"],
            })
            remaining -= 1
            if a.get("is_rma_grade"):
                remaining_rma -= 1

    return candidates


def apply_deletions(
    recovery_paths: RecoveryPaths,
    candidates: list[dict[str, Any]],
    *,
    artifact_dirs: dict[str, Path],
    policy_name: str,
    operator: str,
    apply: bool = False,
) -> list[dict[str, Any]]:
    """Return the tombstones `plan_deletions`'s candidates would produce.

    `apply=False` (the default) is dry-run only: the vault and the ledger are
    untouched. `apply=True` deletes each candidate's artifact directory
    (`artifact_dirs[artifact_id]`) and appends an append-only tombstone per
    deletion to `retention/ledger.json` (frozen rule 3 of §8) — a missing
    backup must always be distinguishable from one that was never taken.
    """
    from utils.recovery_store import delete_artifact_dir  # local import: avoid a store<->retention cycle

    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    tombstones = [
        {
            "artifact_id": c["artifact_id"],
            "entity_id": c["entity_id"],
            "deleted_at": now,
            "policy": policy_name,
            "operator": operator,
        }
        for c in candidates
    ]
    if not apply:
        return tombstones

    ledger_path = recovery_paths.retention_root / "ledger.json"
    existing = _read_ledger(ledger_path)
    existing.extend(tombstones)
    ledger_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = ledger_path.with_name(ledger_path.name + ".tmp")
    tmp_path.write_text(json.dumps(existing, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    os.replace(tmp_path, ledger_path)

    for c in candidates:
        directory = artifact_dirs.get(c["artifact_id"])
        if directory is not None:
            delete_artifact_dir(directory)

    return tombstones


def _read_ledger(ledger_path: Path) -> list[dict[str, Any]]:
    if not ledger_path.is_file():
        return []
    try:
        data = json.loads(ledger_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    return data if isinstance(data, list) else []


def read_ledger(recovery_paths: RecoveryPaths) -> list[dict[str, Any]]:
    return _read_ledger(recovery_paths.retention_root / "ledger.json")
