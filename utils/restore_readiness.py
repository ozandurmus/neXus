"""SecurityExpert — restore-readiness assessment (RB.0).

Contract: docs/design/BACKUP_RECOVERY_CONTRACTS.md §5. Architecture:
docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md §7.

Answers "if this device died right now, what do we actually have?" using only
the inventory plane (`unified.json`) plus, once RB.1/RB.2/RB.3 exist, recovery
manifests and device-reported attestations. No network access, no
credentials, no new device command — this module never talks to a device.

Entity identity follows the same convention the configuration-evidence
collectors already use (`configuration/checkpoint_config_collector.py
_entity_id`): a VSX virtual system is `<physical_device>__vsid_<vs_id>`, never
merged with its physical host.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Mapping, Sequence

SCHEMA = "securityexpert-restore-readiness-v1"

STATE_READY = "READY"
STATE_STALE = "STALE"
STATE_PARTIAL = "PARTIAL"
STATE_UNPROTECTED = "UNPROTECTED"
STATE_UNKNOWN = "UNKNOWN"
_ALL_STATES = (STATE_READY, STATE_STALE, STATE_PARTIAL, STATE_UNPROTECTED, STATE_UNKNOWN)

_VALIDATED_LEVELS = {"V3", "V4"}
_INSUFFICIENT_DATA_STATES = {"no_data"}

_VENDOR_BY_SOURCE = {
    "cp": "checkpoint",
    "vsx": "checkpoint",
    "panorama": "panorama",
}


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def resolve_entity_id(row: Mapping[str, Any]) -> str:
    """Mirror configuration/checkpoint_config_collector.py's `_entity_id`
    convention so recovery, configuration and inventory reference the same
    identity for a CP virtual system: physical device alone, or
    `<device>__vsid_<vs_id>` — never the VS name/vsys label, which is not
    stable identity per AGENTS.md ("VSX actual identity = physical endpoint +
    VSID"). The one shared identity resolver -- utils.recovery_validation and
    utils.recovery_collect both import this rather than re-deriving it."""
    device = str(row.get("device") or "").strip()
    vs_id = str(row.get("vs_id") or "").strip()
    if row.get("source") == "vsx" and vs_id:
        return f"{device}__vsid_{vs_id}"
    return device


def resolve_vendor(row: Mapping[str, Any]) -> str | None:
    return _VENDOR_BY_SOURCE.get(str(row.get("source") or "").strip().lower())


@dataclass
class DeviceReadiness:
    entity_id: str
    vendor: str | None
    state: str
    reason: str
    held_artifacts: list = field(default_factory=list)
    attested_not_held: list = field(default_factory=list)
    missing_required: list = field(default_factory=list)
    evidence_basis: str = "none"

    def to_dict(self) -> dict[str, Any]:
        return {
            "entity_id": self.entity_id,
            "vendor": self.vendor,
            "state": self.state,
            "reason": self.reason,
            "held_artifacts": self.held_artifacts,
            "attested_not_held": self.attested_not_held,
            "missing_required": self.missing_required,
            "evidence_basis": self.evidence_basis,
        }


def _is_ready_artifact(artifact: Mapping[str, Any]) -> bool:
    level = str(artifact.get("validation_level") or "").strip().upper()
    return level in _VALIDATED_LEVELS and bool(artifact.get("version_matches_running"))


def _classify_device(
    entity_id: str,
    vendor: str | None,
    *,
    held_artifacts: Sequence[Mapping[str, Any]],
    attested_not_held: Sequence[Mapping[str, Any]],
    required_classes: Sequence[str] | None,
) -> DeviceReadiness:
    held = list(held_artifacts)
    attested = list(attested_not_held)

    if held:
        held_classes = {str(a.get("class") or "") for a in held}
        missing = sorted(set(required_classes or ()) - held_classes)
        # Missing a required class caps the state at PARTIAL even when the
        # artifacts that ARE held are validated/current -- "some artifact
        # classes present, required ones missing" (contract §5) is PARTIAL by
        # definition, not READY-with-a-footnote.
        if missing:
            return DeviceReadiness(
                entity_id, vendor, STATE_PARTIAL, "required_artifact_classes_missing",
                held_artifacts=held, attested_not_held=attested,
                missing_required=missing, evidence_basis="recovery_manifest",
            )
        if any(_is_ready_artifact(a) for a in held):
            return DeviceReadiness(
                entity_id, vendor, STATE_READY, "validated_current_artifact_held",
                held_artifacts=held, attested_not_held=attested,
                missing_required=missing, evidence_basis="recovery_manifest",
            )
        return DeviceReadiness(
            entity_id, vendor, STATE_STALE, "held_artifact_unvalidated_or_version_mismatch",
            held_artifacts=held, attested_not_held=attested,
            missing_required=missing, evidence_basis="recovery_manifest",
        )

    if attested:
        # A device-reported artifact (e.g. a Gaia snapshot the gateway claims to
        # hold) is weaker evidence than a manifest we actually hold — never
        # promoted to READY — but it is not "nothing" either (architecture §7's
        # readiness-input ordering). Surfaced as PARTIAL with the attestation
        # visible, rather than collapsed into UNPROTECTED.
        return DeviceReadiness(
            entity_id, vendor, STATE_PARTIAL, "only_device_attested_artifact_no_held_copy",
            held_artifacts=[], attested_not_held=attested,
            missing_required=sorted(required_classes or ()), evidence_basis="device_attestation",
        )

    return DeviceReadiness(
        entity_id, vendor, STATE_UNPROTECTED, "no_recovery_artifact_of_any_class",
        held_artifacts=[], attested_not_held=[],
        missing_required=sorted(required_classes or ()), evidence_basis="none",
    )


def compute_restore_readiness(
    unified_devices: Sequence[Mapping[str, Any]],
    *,
    recovery_manifests: Mapping[str, Sequence[Mapping[str, Any]]] | None = None,
    attestations: Mapping[str, Sequence[Mapping[str, Any]]] | None = None,
    required_classes: Mapping[str, Sequence[str]] | None = None,
    generated_at: str | None = None,
) -> dict[str, Any]:
    """Compute a `securityexpert-restore-readiness-v1` record.

    Parameters
    ----------
    unified_devices:
        Rows from `unified.json` (the merged CP/VSX/Panorama inventory).
    recovery_manifests:
        Optional `entity_id -> [held artifact summary, ...]` map. Each summary
        is `{class, age_days, validation_level, version_matches_running}`
        (contract §5). Empty/omitted until RB.1 exists — every device is then
        judged on inventory + attestations only, which is the correct RB.0
        behavior, not a degraded mode.
    attestations:
        Optional `entity_id -> [device-reported artifact, ...]` map, each
        `{class, age_days, source}` (contract §5 `attested_not_held`). Empty
        until the RB.3 §7.5 attestation command (`show backups`/`show
        snapshots`) exists.
    required_classes:
        Optional `vendor -> [artifact class, ...]` map used only to compute
        `missing_required`/`PARTIAL`. RB.0 deliberately does not hardcode a
        default requirement policy — that is RB.2/RB.3/RB.4 scope; omitting
        this parameter means "no artifact-class requirement is enforced yet".
    """
    manifests_by_entity = recovery_manifests or {}
    attest_by_entity = attestations or {}
    required_by_vendor = required_classes or {}

    devices: list[DeviceReadiness] = []
    seen_entities: set[str] = set()

    for index, row in enumerate(unified_devices):
        inventory_status = row.get("inventory_status") or {}
        data_state = str(inventory_status.get("data_state") or "").strip().lower()
        entity_id = resolve_entity_id(row)
        vendor = resolve_vendor(row)

        if not entity_id or vendor is None or data_state in _INSUFFICIENT_DATA_STATES:
            reason = (
                "inventory_data_state_no_data" if data_state in _INSUFFICIENT_DATA_STATES
                else "device_identity_or_vendor_unresolvable"
            )
            devices.append(DeviceReadiness(
                entity_id or f"unknown_{index}", vendor, STATE_UNKNOWN, reason,
                evidence_basis="none",
            ))
            continue

        if entity_id in seen_entities:
            continue
        seen_entities.add(entity_id)

        devices.append(_classify_device(
            entity_id, vendor,
            held_artifacts=manifests_by_entity.get(entity_id, ()),
            attested_not_held=attest_by_entity.get(entity_id, ()),
            required_classes=required_by_vendor.get(vendor),
        ))

    summary = {state: 0 for state in _ALL_STATES}
    for device in devices:
        summary[device.state] += 1

    return {
        "schema": SCHEMA,
        "generated_at": generated_at or _utc_now(),
        "devices": [d.to_dict() for d in devices],
        "summary": summary,
    }
