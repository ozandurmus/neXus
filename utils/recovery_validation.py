"""SecurityExpert — RB.4 recovery artifact validation (V1–V3).

docs/design/BACKUP_RECOVERY_CONTRACTS.md §4. Pure with respect to the
filesystem: callers supply the already-read ciphertext/plaintext bytes and
the unified inventory rows; this module never opens a file or a network
connection itself.

V4 (`RESTORE_PROVEN`) is never computed automatically — it requires an
actual lab restore, entered manually via `attach_restore_proof`
(architecture §6, open decision D7). No code path here may set
`restore_proven: true` without a caller-supplied `restore_proof` (frozen
rule 2).
"""
from __future__ import annotations

import gzip
from datetime import datetime, timezone
from typing import Any, Mapping, Sequence

from lxml import etree

from utils.config_evidence import sha256_bytes

_LEVEL_ORDER = ("V1", "V2", "V3")
_VERDICT_BY_LEVEL = {
    "NONE": "FAILED",
    "V1": "INTACT",
    "V2": "WELL_FORMED",
    "V3": "CONSISTENT",
}
_REQUIRED_RESTORE_PROOF_FIELDS = {"proven_at", "platform_class", "operator", "procedure_ref", "result"}


class RecoveryValidationError(Exception):
    """Raised when a V4 restore-proof record does not satisfy frozen rule 2."""


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _check(check_id: str, level: str, result: str, detail: str | None = None) -> dict[str, Any]:
    return {"id": check_id, "level": level, "result": result, "detail": detail}


def _v1_checks(sealed_bytes: bytes, manifest: Mapping[str, Any]) -> list[dict[str, Any]]:
    artifact = manifest.get("artifact") or {}
    checks = []

    actual_sha = sha256_bytes(sealed_bytes)
    expected_sha = artifact.get("ciphertext_sha256")
    checks.append(_check(
        "sha256_match", "V1", "PASS" if actual_sha == expected_sha else "FAIL",
        None if actual_sha == expected_sha else "ciphertext digest does not match the manifest",
    ))

    actual_size = len(sealed_bytes)
    expected_size = artifact.get("ciphertext_bytes")
    checks.append(_check(
        "size_band", "V1", "PASS" if actual_size == expected_size else "FAIL",
        None if actual_size == expected_size else f"{actual_size} != {expected_size}",
    ))
    return checks


def _v2_checks(plaintext: bytes, manifest: Mapping[str, Any]) -> list[dict[str, Any]]:
    artifact = manifest.get("artifact") or {}
    compression = artifact.get("compression")
    artifact_class = artifact.get("class")
    checks = []

    if compression == "gzip":
        try:
            gzip.decompress(plaintext)
            checks.append(_check("archive_openable", "V2", "PASS"))
        except OSError:
            checks.append(_check("archive_openable", "V2", "FAIL", "gzip decompress failed"))
    else:
        checks.append(_check("archive_openable", "V2", "NOT_APPLICABLE", f"compression={compression!r}"))

    checks.append(_check(
        "expected_members", "V2", "PASS" if plaintext else "FAIL",
        None if plaintext else "decrypted artifact is empty",
    ))

    if artifact_class == "pan_running_config":
        try:
            etree.fromstring(plaintext, parser=etree.XMLParser(resolve_entities=False, no_network=True))
            checks.append(_check("xml_root_valid", "V2", "PASS"))
        except etree.XMLSyntaxError:
            checks.append(_check("xml_root_valid", "V2", "FAIL", "not well-formed XML"))
    else:
        checks.append(_check("xml_root_valid", "V2", "NOT_APPLICABLE", f"class={artifact_class!r}"))

    return checks


def _find_unified_device(unified_devices: Sequence[Mapping[str, Any]], entity_id: str | None) -> dict | None:
    if not entity_id:
        return None
    from utils.restore_readiness import resolve_entity_id  # the one identity convention, not a copy of it

    for row in unified_devices:
        if resolve_entity_id(row) == entity_id:
            return row
    return None


def _v3_checks(manifest: Mapping[str, Any], unified_devices: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """Cross-checks against `unified.json` for the same device. Frozen rule 3
    (§4): when inventory for that device is absent or stale, every check is
    `NOT_APPLICABLE` with a reason — never `PASS`."""
    device = manifest.get("device") or {}
    entity_id = device.get("entity_id")
    matched = _find_unified_device(unified_devices, entity_id)
    checks: list[dict[str, Any]] = []

    if matched is None:
        no_inventory = "no matching device in unified.json"
        checks.append(_check("inventory_device_present", "V3", "NOT_APPLICABLE", no_inventory))
        checks.append(_check("inventory_version_match", "V3", "NOT_APPLICABLE", no_inventory))
        return checks

    checks.append(_check("inventory_device_present", "V3", "PASS"))

    data_state = str((matched.get("inventory_status") or {}).get("data_state") or "").strip().lower()
    if data_state == "no_data":
        checks.append(_check("inventory_data_state_fresh", "V3", "FAIL", "inventory data_state=no_data"))
    else:
        checks.append(_check("inventory_data_state_fresh", "V3", "PASS"))

    inventory_version = matched.get("software_version")
    manifest_version = device.get("software_version")
    if not inventory_version:
        checks.append(_check(
            "inventory_version_match", "V3", "NOT_APPLICABLE",
            "unified.json does not carry a software_version for this device",
        ))
    elif not manifest_version or manifest_version == "unknown":
        # e.g. the PAN device-state collector cannot derive a version from
        # today's unified.json fields (no gate-documented version command
        # yet) and records the honest sentinel "unknown" -- comparing it
        # would report a FAIL that isn't really a mismatch, just an
        # unresolved fact. NOT_APPLICABLE is the correct read (§4 rule 3).
        checks.append(_check(
            "inventory_version_match", "V3", "NOT_APPLICABLE",
            "artifact does not carry a resolved software_version",
        ))
    elif inventory_version == manifest_version:
        checks.append(_check("inventory_version_match", "V3", "PASS"))
    else:
        checks.append(_check(
            "inventory_version_match", "V3", "FAIL",
            f"artifact {manifest_version} != inventory {inventory_version}",
        ))

    return checks


def _highest_fully_passed_level(checks: Sequence[Mapping[str, Any]]) -> str:
    """Frozen rule 1 (§4): the highest level fully passed; any FAIL caps it
    at the level below. `NOT_APPLICABLE` does not block advancement -- only
    `FAIL` does. But a level where *every* check came back `NOT_APPLICABLE`
    verified nothing at all -- it does not count as passed either (e.g. no
    unified inventory to cross-check against at all yields two
    `NOT_APPLICABLE` V3 checks and no `FAIL`; that must cap at V2, not
    silently claim V3)."""
    by_level: dict[str, list[str]] = {}
    for c in checks:
        by_level.setdefault(c["level"], []).append(c["result"])

    achieved = "NONE"
    for level in _LEVEL_ORDER:
        results = by_level.get(level, [])
        if not results or any(r == "FAIL" for r in results) or not any(r == "PASS" for r in results):
            break
        achieved = level
    return achieved


def validate_artifact(
    *,
    sealed_bytes: bytes,
    plaintext: bytes,
    manifest: Mapping[str, Any],
    unified_devices: Sequence[Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    """Run the V1–V3 battery against one recovery artifact.

    Never computes V4: `restore_proven` stays `False` and `restore_proof`
    stays `None` regardless of how well V1–V3 pass -- only
    `attach_restore_proof` may set those, and only given a real record.
    """
    checks: list[dict[str, Any]] = []
    checks.extend(_v1_checks(sealed_bytes, manifest))
    checks.extend(_v2_checks(plaintext, manifest))
    checks.extend(_v3_checks(manifest, unified_devices or ()))

    level = _highest_fully_passed_level(checks)
    # A V1 failure still reports level="V1" (the level the check ran at,
    # not an omitted/invalid enum value) with verdict FAILED -- the schema
    # has no "NONE" level.
    reported_level = level if level != "NONE" else "V1"

    return {
        "level": reported_level,
        "verdict": _VERDICT_BY_LEVEL[level],
        "restore_proven": False,
        "checked_at": _utc_now(),
        "checks": checks,
        "restore_proof": None,
    }


def attach_restore_proof(validation: Mapping[str, Any], restore_proof: Mapping[str, Any]) -> dict[str, Any]:
    """Manually record a V4 `RESTORE_PROVEN` result (frozen rule 2 of §4).

    Never set automatically — always requires an explicit, complete
    `restore_proof` record from a real lab restore (architecture §6, open
    decision D7): `{proven_at, platform_class, operator, procedure_ref,
    result}`, with `result == "success"`.
    """
    missing = _REQUIRED_RESTORE_PROOF_FIELDS - set(restore_proof or {})
    if missing:
        raise RecoveryValidationError(f"restore_proof missing required fields: {sorted(missing)}")
    if restore_proof.get("result") != "success":
        raise RecoveryValidationError("restore_proof.result must be 'success' to set RESTORE_PROVEN")

    updated = dict(validation)
    updated["level"] = "V4"
    updated["verdict"] = "RESTORE_PROVEN"
    updated["restore_proven"] = True
    updated["restore_proof"] = dict(restore_proof)
    return updated
