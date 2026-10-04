"""SecurityExpert — RB.1 recovery manifest builder + validator.

Pure, no I/O. docs/design/BACKUP_RECOVERY_CONTRACTS.md §3. Deliberately
narrow: this enforces the rules §3 explicitly freezes (the `restore`
reservation, RMA-grade derivation, mandatory `known_gaps`, mandatory
`software_version`) — not full JSON-schema completeness of every field.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

SCHEMA = "securityexpert-recovery-manifest-v1"

# Frozen rule 3: is_rma_grade is derived, not asserted.
RMA_GRADE_BY_CLASS = {
    "pan_device_state": True,
    "pan_running_config": False,
    "cp_gaia_backup": True,
    "cp_mgmt_export": True,
    "cp_mds_backup": True,
}

# Frozen rule 4: known_gaps is mandatory and non-empty where the vendor
# documents an exclusion (architecture §3.1/§3.2).
KNOWN_GAPS_BY_CLASS = {
    "cp_gaia_backup": [
        "OS not included", "product binaries not included", "hotfixes not included",
    ],
    "pan_running_config": [
        "certificates not included", "LSVPN satellite authentication not included",
    ],
}


class RecoveryManifestError(Exception):
    """Raised when a manifest violates a frozen §3 rule."""


def build_manifest(
    *,
    artifact_id: str,
    device: dict[str, Any],
    artifact: dict[str, Any],
    crypto: dict[str, Any],
    restore_constraints: dict[str, Any] | None = None,
    consistency_group: str | None = None,
    retention: dict[str, Any] | None = None,
    created_at: str | None = None,
) -> dict[str, Any]:
    """Construct a manifest dict enforcing the frozen §3 invariants.

    Callers supply already-populated `device`/`artifact`/`crypto` sub-blocks
    (the store computes digests, sizes and the wrapped DEK); this function
    derives `is_rma_grade`, fills `known_gaps` where the vendor documents an
    exclusion and the caller did not already supply one, and freezes
    `restore: null`.
    """
    artifact_class = artifact.get("class")
    if artifact_class not in RMA_GRADE_BY_CLASS:
        raise RecoveryManifestError(f"unknown artifact class: {artifact_class!r}")
    if not device.get("software_version"):
        raise RecoveryManifestError("device.software_version is mandatory (frozen rule 5)")

    artifact = dict(artifact)
    artifact["is_rma_grade"] = RMA_GRADE_BY_CLASS[artifact_class]

    constraints = dict(restore_constraints or {})
    known_gaps = list(constraints.get("known_gaps") or [])
    if not known_gaps:
        known_gaps = list(KNOWN_GAPS_BY_CLASS.get(artifact_class, []))
    constraints["known_gaps"] = known_gaps

    manifest = {
        "schema": SCHEMA,
        "artifact_id": artifact_id,
        "created_at": created_at or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "device": device,
        "artifact": artifact,
        "crypto": crypto,
        "validation": None,
        "restore_constraints": constraints,
        "consistency_group": consistency_group,
        "retention": retention,
        "restore": None,
    }
    validate_manifest(manifest)
    return manifest


def validate_manifest(manifest: dict[str, Any]) -> None:
    """Raise `RecoveryManifestError` if `manifest` violates a frozen §3 rule."""
    if manifest.get("restore") is not None:
        raise RecoveryManifestError(
            "manifest.restore must be null -- reserved for RB.6 (frozen rule 1)"
        )

    device = manifest.get("device") or {}
    if not device.get("software_version"):
        raise RecoveryManifestError("device.software_version is mandatory (frozen rule 5)")

    artifact = manifest.get("artifact") or {}
    artifact_class = artifact.get("class")
    if artifact_class not in RMA_GRADE_BY_CLASS:
        raise RecoveryManifestError(f"unknown artifact class: {artifact_class!r}")
    expected_rma_grade = RMA_GRADE_BY_CLASS[artifact_class]
    if artifact.get("is_rma_grade") != expected_rma_grade:
        raise RecoveryManifestError(
            f"artifact.is_rma_grade for class {artifact_class!r} must be derived as "
            f"{expected_rma_grade} (frozen rule 3), not asserted"
        )

    required_gaps = KNOWN_GAPS_BY_CLASS.get(artifact_class)
    if required_gaps:
        gaps = (manifest.get("restore_constraints") or {}).get("known_gaps") or []
        if not gaps:
            raise RecoveryManifestError(
                f"restore_constraints.known_gaps is mandatory and non-empty for class "
                f"{artifact_class!r} (frozen rule 4)"
            )
