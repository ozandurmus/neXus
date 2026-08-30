"""SecurityExpert — RB.1 recovery-plane store.

Writes/reads the encrypted vault under `RecoveryPaths.recovery_root`
(docs/design/BACKUP_RECOVERY_CONTRACTS.md §2). `utils.recovery_collect`
(RB.2/RB.3) is the only caller of `write_artifact`; this module owns the
store, envelope encryption, manifest, validation-rewrite and retention
primitives, never a vendor protocol/shell call.
"""
from __future__ import annotations

import json
import os
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

from utils import recovery_crypto
from utils.config_evidence import safe_component, sha256_bytes, utc_stamp
from utils.recovery_manifest import build_manifest, validate_manifest
from utils.recovery_validation import validate_artifact
from utils.runtime_paths import RecoveryPaths

VAULT_KEY_FILE_NAME = ".recovery_vault.key"
VAULT_KEY_ENV = "SECURITYEXPERT_RECOVERY_VAULT_KEY"


class RecoveryStoreError(Exception):
    """Raised on a recovery-store I/O or invariant failure."""


def _validate_vault_key_location(data_root: Path, recovery_root: Path) -> None:
    """Frozen invariant §9.2: the wrapping key is never under `recovery_root`."""
    data_root = Path(data_root).expanduser().resolve(strict=False)
    recovery_root = Path(recovery_root).expanduser().resolve(strict=False)
    try:
        data_root.relative_to(recovery_root)
        nested = True
    except ValueError:
        nested = False
    if data_root == recovery_root or nested:
        raise RecoveryStoreError(
            "the recovery vault key location must not be under recovery_root (contract §9.2)"
        )


def get_or_create_vault_key(
    data_root: Path, recovery_root: Path, *, environ: dict[str, str] | None = None
) -> tuple[bytes, str]:
    """Resolve the vault master key, generating and persisting one on first
    use. Lives on `data_root` (the evidence/runtime volume), never on
    `recovery_root` — mirrors DEV.2.2's `.support_hmac.key` precedent for the
    same reason: a compromise of one volume must not hand over the key to
    the other. Returns `(key_bytes, key_id)`.
    """
    _validate_vault_key_location(data_root, recovery_root)
    env = os.environ if environ is None else environ

    env_key = env.get(VAULT_KEY_ENV, "").strip()
    if env_key:
        try:
            key = bytes.fromhex(env_key)
        except ValueError as exc:
            raise RecoveryStoreError(f"{VAULT_KEY_ENV} must be a hex-encoded key") from exc
        if len(key) != recovery_crypto.KEY_BYTES:
            raise RecoveryStoreError(f"{VAULT_KEY_ENV} must decode to {recovery_crypto.KEY_BYTES} bytes")
        return key, recovery_crypto.key_id(key)

    key_path = Path(data_root) / VAULT_KEY_FILE_NAME
    key_path.parent.mkdir(parents=True, exist_ok=True)
    if key_path.exists():
        key = key_path.read_bytes()
        if len(key) != recovery_crypto.KEY_BYTES:
            raise RecoveryStoreError(f"{key_path} does not hold a {recovery_crypto.KEY_BYTES}-byte key")
        return key, recovery_crypto.key_id(key)

    key = recovery_crypto.generate_key()
    key_path.write_bytes(key)
    try:
        os.chmod(key_path, 0o600)
    except OSError:
        pass
    return key, recovery_crypto.key_id(key)


def _atomic_write_bytes(path: Path, data: bytes) -> None:
    tmp_path = path.with_name(path.name + ".tmp")
    tmp_path.write_bytes(data)
    os.replace(tmp_path, path)


@dataclass(frozen=True)
class RecoveryWriteResult:
    artifact_dir: Path
    manifest: dict[str, Any]


def write_artifact(
    recovery_paths: RecoveryPaths,
    *,
    vault_key: bytes,
    vault_key_id: str,
    device: dict[str, Any],
    artifact_class: str,
    plaintext: bytes,
    vendor_native_filename: str,
    collected_via: str,
    collection_duration_ms: int | None = None,
    compression: str = "none",
    restore_constraints: dict[str, Any] | None = None,
    consistency_group: str | None = None,
    retention: dict[str, Any] | None = None,
) -> RecoveryWriteResult:
    """Encrypt `plaintext` and write `artifact.enc` + `manifest.json` under
    `vault/<vendor>/<entity_id>/<utc_stamp>/`.

    Frozen invariant §9.1: `plaintext` is never written to disk in any form,
    including a temp file — it is sealed to ciphertext in memory first, and
    only the ciphertext (and the manifest, which holds no artifact bytes)
    ever touches the filesystem.
    """
    vendor = device.get("vendor")
    entity_id = device.get("entity_id")
    if not vendor or not entity_id:
        raise RecoveryStoreError("device.vendor and device.entity_id are required")

    dek = recovery_crypto.generate_key()
    sealed = recovery_crypto.encrypt_artifact(dek, plaintext)
    wrapped_dek = recovery_crypto.wrap_data_key(vault_key, dek)

    stamp = utc_stamp()
    artifact_dir = recovery_paths.vault_root / safe_component(vendor) / safe_component(entity_id) / stamp
    artifact_dir.mkdir(parents=True, exist_ok=True)

    manifest = build_manifest(
        artifact_id=sha256_bytes(sealed),
        device=device,
        artifact={
            "class": artifact_class,
            "vendor_native_filename": vendor_native_filename,
            "plaintext_sha256": sha256_bytes(plaintext),
            "plaintext_bytes": len(plaintext),
            "ciphertext_sha256": sha256_bytes(sealed),
            "ciphertext_bytes": len(sealed),
            "compression": compression,
            "collected_via": collected_via,
            "collection_duration_ms": collection_duration_ms,
        },
        crypto={
            "scheme": recovery_crypto.SCHEME_ID,
            "wrapped_data_key": wrapped_dek,
            "vault_key_id": vault_key_id,
        },
        restore_constraints=restore_constraints,
        consistency_group=consistency_group,
        retention=retention,
    )

    _atomic_write_bytes(artifact_dir / "artifact.enc", sealed)
    _atomic_write_bytes(
        artifact_dir / "manifest.json",
        (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8"),
    )
    return RecoveryWriteResult(artifact_dir=artifact_dir, manifest=manifest)


def read_manifest(artifact_dir: Path) -> dict[str, Any]:
    path = Path(artifact_dir) / "manifest.json"
    if not path.is_file():
        raise RecoveryStoreError(f"no manifest.json under {artifact_dir}")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    return manifest


def list_artifact_dirs(
    recovery_paths: RecoveryPaths, *, vendor: str | None = None, entity_id: str | None = None
) -> list[Path]:
    """Return every `vault/<vendor>/<entity_id>/<stamp>/` directory holding a
    manifest, optionally filtered by vendor and/or entity."""
    vault_root = recovery_paths.vault_root
    if not vault_root.is_dir():
        return []

    vendor_dirs = (
        [vault_root / safe_component(vendor)] if vendor
        else sorted(p for p in vault_root.iterdir() if p.is_dir())
    )
    found: list[Path] = []
    for vendor_dir in vendor_dirs:
        if not vendor_dir.is_dir():
            continue
        entity_dirs = (
            [vendor_dir / safe_component(entity_id)] if entity_id
            else sorted(p for p in vendor_dir.iterdir() if p.is_dir())
        )
        for entity_dir in entity_dirs:
            if not entity_dir.is_dir():
                continue
            for stamp_dir in sorted(p for p in entity_dir.iterdir() if p.is_dir()):
                if (stamp_dir / "manifest.json").is_file():
                    found.append(stamp_dir)
    return found


def decrypt_artifact(artifact_dir: Path, manifest: dict[str, Any], *, vault_key: bytes) -> bytes:
    """Decrypt an artifact fully in memory. Callers (RB.4 validation) must
    never write the result to disk — §9.1 has no carve-out for validation."""
    sealed = (Path(artifact_dir) / "artifact.enc").read_bytes()
    crypto = manifest.get("crypto") or {}
    dek = recovery_crypto.unwrap_data_key(vault_key, crypto.get("wrapped_data_key", ""))
    return recovery_crypto.decrypt_artifact(dek, sealed)


def write_consistency_group(
    recovery_paths: RecoveryPaths, group_id: str, *, members: list[str], status: str = "PENDING"
) -> dict[str, Any]:
    """Storage primitive only — the INCONSISTENT-propagation policy (a group
    with any failed member is not counted as readiness evidence) is RB.3
    scope per contract §7.6, not implemented here."""
    group_manifest = {
        "schema": "securityexpert-recovery-consistency-group-v1",
        "group_id": group_id,
        "members": list(members),
        "status": status,
    }
    group_dir = recovery_paths.groups_root / safe_component(group_id)
    group_dir.mkdir(parents=True, exist_ok=True)
    _atomic_write_bytes(
        group_dir / "manifest.json",
        (json.dumps(group_manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8"),
    )
    return group_manifest


def read_consistency_group(recovery_paths: RecoveryPaths, group_id: str) -> dict[str, Any]:
    path = recovery_paths.groups_root / safe_component(group_id) / "manifest.json"
    if not path.is_file():
        raise RecoveryStoreError(f"no consistency group manifest for {group_id!r}")
    return json.loads(path.read_text(encoding="utf-8"))


def delete_artifact_dir(artifact_dir: Path) -> None:
    """Used only by retention.apply_deletions (--apply). Removes the whole
    `<stamp>/` directory (ciphertext + manifest) for one artifact."""
    directory = Path(artifact_dir)
    if directory.is_dir():
        shutil.rmtree(directory)


def revalidate_artifact(
    artifact_dir: Path,
    manifest: dict[str, Any],
    *,
    vault_key: bytes,
    unified_devices: Sequence[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """RB.4: decrypt in memory (never to disk — §9.1 has no validation
    carve-out), run the V1–V3 battery, write `manifest.validation` back and
    return the updated manifest.

    `unified_devices` is optional: an empty/omitted inventory makes every V3
    check `NOT_APPLICABLE` (frozen rule 3 of §4), not an error — RB.4 must be
    runnable offline against whatever local `unified.json` happens to exist.
    """
    sealed_bytes = (Path(artifact_dir) / "artifact.enc").read_bytes()
    plaintext = decrypt_artifact(artifact_dir, manifest, vault_key=vault_key)

    validation = validate_artifact(
        sealed_bytes=sealed_bytes, plaintext=plaintext, manifest=manifest,
        unified_devices=unified_devices,
    )

    updated = dict(manifest)
    updated["validation"] = validation
    validate_manifest(updated)  # still honors every §3 frozen rule after the rewrite

    _atomic_write_bytes(
        Path(artifact_dir) / "manifest.json",
        (json.dumps(updated, indent=2, ensure_ascii=False) + "\n").encode("utf-8"),
    )
    return updated
