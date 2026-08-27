from __future__ import annotations

import base64
import json
import os
import shutil
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any

from utils.config_evidence import ARTIFACT_ROOT, CONFIG_ROOT, sha256_file
from utils.runtime_paths import default_output_root


BASE_DIR = Path(__file__).resolve().parent.parent
OUTPUT_DIR = default_output_root(repository_root=BASE_DIR)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")


def _is_sha256(value: Any) -> bool:
    text = str(value or "")
    return len(text) == 64 and all(ch in "0123456789abcdefABCDEF" for ch in text)


def _dir_size(root: Path) -> int:
    if not root.exists():
        return 0
    total = 0
    for path in root.rglob("*"):
        if path.is_file():
            try:
                total += path.stat().st_size
            except OSError:
                pass
    return total


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _validate_artifact_name(value: Any) -> str:
    name = str(value or "").strip()
    if not name:
        raise ValueError("artifact_file is missing")
    if name in {".", ".."} or "/" in name or "\\" in name:
        raise ValueError(f"artifact_file must be a single filename: {name!r}")
    if PurePosixPath(name).is_absolute() or PureWindowsPath(name).is_absolute():
        raise ValueError(f"artifact_file must be relative: {name!r}")
    if PureWindowsPath(name).drive or PureWindowsPath(name).anchor:
        raise ValueError(f"artifact_file must not contain a drive/anchor: {name!r}")
    return name


def _safe_legacy_path(metadata_path: Path, artifact_name: str, config_root: Path) -> Path:
    config_resolved = config_root.resolve(strict=True)
    metadata_resolved = metadata_path.resolve(strict=True)
    snapshot_resolved = metadata_path.parent.resolve(strict=True)
    if not _is_within(metadata_resolved, config_resolved):
        raise ValueError("metadata path escapes config root")
    if not _is_within(snapshot_resolved, config_resolved):
        raise ValueError("snapshot path escapes config root")

    candidate = metadata_path.parent / artifact_name
    if candidate.is_symlink():
        raise ValueError(f"legacy artifact must not be a symlink: {artifact_name}")
    if not candidate.exists():
        return candidate
    resolved = candidate.resolve(strict=True)
    if not _is_within(resolved, snapshot_resolved):
        raise ValueError(f"legacy artifact escapes snapshot directory: {artifact_name}")
    if not resolved.is_file():
        raise ValueError(f"legacy artifact is not a regular file: {artifact_name}")
    return candidate


def _metadata_rows(config_root: Path) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    rows: list[dict[str, Any]] = []
    errors: list[dict[str, str]] = []
    if not config_root.exists():
        return rows, errors
    try:
        config_root.resolve(strict=True)
    except OSError as exc:
        return rows, [{"metadata_path": str(config_root), "error": f"config root unavailable: {exc}"}]
    for metadata_path in config_root.rglob("metadata.json"):
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            artifact_name = _validate_artifact_name(metadata.get("artifact_file"))
            legacy_path = _safe_legacy_path(metadata_path, artifact_name, config_root)
        except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
            errors.append({"metadata_path": str(metadata_path), "error": str(exc)})
            continue
        storage = metadata.get("storage") if isinstance(metadata.get("storage"), dict) else {}
        rows.append({
            "metadata_path": metadata_path,
            "metadata": metadata,
            "source": str(metadata.get("source") or "unknown"),
            "entity_id": str(metadata.get("entity_id") or "unknown"),
            "artifact_type": str(metadata.get("artifact_type") or "unknown"),
            "change_state": str(metadata.get("change_state") or "unknown"),
            "sha256": str(metadata.get("sha256") or ""),
            "size_bytes": int(metadata.get("size_bytes") or 0),
            "artifact_name": artifact_name,
            "legacy_path": legacy_path,
            "legacy_exists": legacy_path.is_file(),
            "storage_mode": storage.get("mode"),
            "object_path": storage.get("object_path"),
        })
    return rows, errors


def analyze_configuration_storage(
    *,
    config_root: Path | None = None,
    artifact_root: Path | None = None,
) -> dict[str, Any]:
    config_root = Path(config_root) if config_root else CONFIG_ROOT
    artifact_root = Path(artifact_root) if artifact_root else ARTIFACT_ROOT
    rows, safety_errors = _metadata_rows(config_root)

    legacy_rows = [row for row in rows if row["legacy_exists"]]
    digest_sizes: dict[str, int] = {}
    metadata_hash_mismatches = 0
    metadata_hash_untrusted = 0
    by_source: dict[str, dict[str, int]] = defaultdict(lambda: {
        "snapshots": 0,
        "legacy_payload_files": 0,
        "legacy_payload_bytes": 0,
        "same_snapshots": 0,
        "changed_snapshots": 0,
        "first_snapshots": 0,
    })
    for row in rows:
        src = by_source[row["source"]]
        src["snapshots"] += 1
        if row["change_state"] == "same":
            src["same_snapshots"] += 1
        elif row["change_state"] == "changed":
            src["changed_snapshots"] += 1
        elif row["change_state"] == "first":
            src["first_snapshots"] += 1
        if row["legacy_exists"]:
            try:
                size = row["legacy_path"].stat().st_size
            except OSError:
                size = row["size_bytes"]
            src["legacy_payload_files"] += 1
            src["legacy_payload_bytes"] += size
            # A4.3.2.1: analysis must verify payload bytes instead of trusting metadata SHA.
            digest = sha256_file(row["legacy_path"])
            declared = row["sha256"].lower()
            if not _is_sha256(declared):
                metadata_hash_untrusted += 1
            elif declared != digest:
                metadata_hash_mismatches += 1
            digest_sizes.setdefault(digest.lower(), size)

    existing_objects: dict[str, int] = {}
    corrupt_existing_objects: list[str] = []
    if artifact_root.exists():
        artifact_resolved = artifact_root.resolve(strict=True)
        for path in artifact_root.rglob("*"):
            if not path.is_file() or path.name.startswith(".tmp-"):
                continue
            if _is_sha256(path.name):
                try:
                    if path.is_symlink() or not _is_within(path.resolve(strict=True), artifact_resolved):
                        corrupt_existing_objects.append(str(path))
                        continue
                    actual = sha256_file(path)
                    if actual.lower() != path.name.lower():
                        corrupt_existing_objects.append(str(path))
                        continue
                    existing_objects[path.name.lower()] = path.stat().st_size
                except OSError:
                    corrupt_existing_objects.append(str(path))

    legacy_payload_bytes = sum(item["legacy_payload_bytes"] for item in by_source.values())
    unique_legacy_bytes = sum(digest_sizes.values())
    missing_unique_bytes = sum(
        size for digest, size in digest_sizes.items() if digest not in existing_objects
    )
    projected_net_reclaim = max(0, legacy_payload_bytes - missing_unique_bytes)
    same_snapshots = sum(1 for row in rows if row["change_state"] == "same")

    return {
        "schema_version": "0.6.0A4.3.2.1",
        "mode": "analysis",
        "generated_at": _utc_now(),
        "config_root": str(config_root),
        "artifact_root": str(artifact_root),
        "actual_disk_bytes": _dir_size(config_root) + _dir_size(artifact_root),
        "history_snapshots": len(rows),
        "same_history_events": same_snapshots,
        "safety_error_count": len(safety_errors),
        "safety_errors": safety_errors,
        "payload_hashes_verified": len(legacy_rows),
        "metadata_hash_mismatch_count": metadata_hash_mismatches,
        "metadata_hash_untrusted_count": metadata_hash_untrusted,
        "corrupt_existing_cas_object_count": len(corrupt_existing_objects),
        "corrupt_existing_cas_objects": corrupt_existing_objects,
        "legacy_payload_files": len(legacy_rows),
        "legacy_payload_bytes": legacy_payload_bytes,
        "legacy_unique_payloads": len(digest_sizes),
        "legacy_unique_payload_bytes": unique_legacy_bytes,
        "content_addressed_objects": len(existing_objects),
        "content_addressed_bytes": sum(existing_objects.values()),
        "new_unique_bytes_needed_for_migration": missing_unique_bytes,
        "projected_net_reclaim_bytes": projected_net_reclaim,
        "projected_reclaim_percent_of_legacy_payload": (
            round((projected_net_reclaim / legacy_payload_bytes) * 100.0, 2)
            if legacy_payload_bytes else 0.0
        ),
        "by_source": dict(sorted(by_source.items())),
        "notes": [
            "SAME snapshots remain as small history metadata events but do not require a second payload object.",
            "CHANGED versions remain preserved as distinct content-addressed objects for future diff/history.",
            "The storage contract is vendor-neutral; Check Point text evidence can use the same object store when its configuration collector is introduced.",
        ],
    }


def _object_path(artifact_root: Path, digest: str) -> Path:
    return artifact_root / digest[:2] / digest


def _atomic_copy_verified(source: Path, target: Path, digest: str) -> bool:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        if sha256_file(target) != digest:
            raise RuntimeError(f"Existing content-addressed object is corrupt: {target}")
        return False
    tmp = target.parent / f".tmp-{digest}-{uuid.uuid4().hex[:8]}"
    try:
        shutil.copyfile(source, tmp)
        if sha256_file(tmp) != digest:
            raise RuntimeError(f"Migration copy hash mismatch for {source}")
        os.replace(tmp, target)
    finally:
        try:
            if tmp.exists():
                tmp.unlink()
        except OSError:
            pass
    return True


def deduplicate_legacy_storage(
    *,
    apply: bool = False,
    config_root: Path | None = None,
    artifact_root: Path | None = None,
    output_dir: Path | None = None,
) -> dict[str, Any]:
    """Plan or apply migration of legacy per-snapshot payload copies.

    Dry-run is the default. On apply, each legacy payload is first copied into
    the CAS and hash-verified; metadata is atomically updated; only then is the
    legacy payload unlinked. The generated manifest is sufficient to recreate
    the removed legacy files from their content-addressed object if needed.
    """
    config_root = Path(config_root) if config_root else CONFIG_ROOT
    artifact_root = Path(artifact_root) if artifact_root else ARTIFACT_ROOT
    output_dir = Path(output_dir) if output_dir else OUTPUT_DIR
    all_rows, safety_errors = _metadata_rows(config_root)
    if safety_errors:
        preview = "; ".join(item["error"] for item in safety_errors[:3])
        raise RuntimeError(
            f"Migration refused: {len(safety_errors)} unsafe or malformed metadata record(s): {preview}"
        )
    rows = [row for row in all_rows if row["legacy_exists"]]

    operations: list[dict[str, Any]] = []
    object_first_source: dict[str, Path] = {}
    for row in rows:
        legacy_path: Path = row["legacy_path"]
        digest = row["sha256"].lower()
        if not _is_sha256(digest):
            raise RuntimeError(f"Legacy artifact metadata SHA-256 is missing or invalid: {legacy_path}")
        # Verify metadata before any destructive action.
        actual_digest = sha256_file(legacy_path)
        if actual_digest != digest:
            raise RuntimeError(f"Legacy artifact hash mismatch: {legacy_path}")
        object_path = _object_path(artifact_root, digest)
        object_first_source.setdefault(digest, legacy_path)
        metadata_path: Path = row["metadata_path"]
        sha_path = metadata_path.parent / "sha256.txt"
        ref_path = metadata_path.parent / f"{row['artifact_name']}.ref.json"
        operations.append({
            "metadata_path": str(metadata_path),
            "legacy_path": str(legacy_path),
            "source": row["source"],
            "entity_id": row["entity_id"],
            "artifact_type": row["artifact_type"],
            "artifact_name": row["artifact_name"],
            "sha256": digest,
            "size_bytes": legacy_path.stat().st_size,
            "object_path": str(object_path),
            "object_exists_before": object_path.exists(),
            "rollback_state": {
                "metadata_json_b64": base64.b64encode(metadata_path.read_bytes()).decode("ascii"),
                "sha256_txt_existed": sha_path.exists(),
                "sha256_txt_b64": (
                    base64.b64encode(sha_path.read_bytes()).decode("ascii") if sha_path.exists() else None
                ),
                "ref_file_existed": ref_path.exists(),
                "ref_file_b64": (
                    base64.b64encode(ref_path.read_bytes()).decode("ascii") if ref_path.exists() else None
                ),
            },
        })

    unique_digests = {op["sha256"] for op in operations}
    bytes_to_remove = sum(int(op["size_bytes"]) for op in operations)
    bytes_to_create = 0
    for digest in unique_digests:
        target = _object_path(artifact_root, digest)
        if not target.exists():
            bytes_to_create += object_first_source[digest].stat().st_size
    projected_reclaim = max(0, bytes_to_remove - bytes_to_create)

    manifest = {
        "schema_version": "0.6.0A4.3.2.1",
        "mode": "apply" if apply else "dry_run",
        "generated_at": _utc_now(),
        "config_root": str(config_root),
        "artifact_root": str(artifact_root),
        "legacy_payload_files": len(operations),
        "unique_payloads": len(unique_digests),
        "legacy_bytes_to_remove": bytes_to_remove,
        "new_object_bytes_to_create": bytes_to_create,
        "projected_net_reclaim_bytes": projected_reclaim,
        "manifest_sensitivity": "LOCAL_OPERATOR_SENSITIVE_NOT_SHAREABLE",
        "operations": operations,
        "rollback_contract": (
            "Each removed legacy_path can be recreated by copying object_path back to legacy_path. "
            "Each operation also records exact pre-migration metadata.json, sha256.txt and any pre-existing ref file bytes. "
            "No content-addressed objects are deleted by this migration."
        ),
    }

    output_dir.mkdir(parents=True, exist_ok=True)
    suffix = "apply" if apply else "dryrun"
    manifest_path = output_dir / f"storage_migration_{_stamp()}_{suffix}.json"

    if not apply:
        manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
        manifest["manifest_path"] = str(manifest_path)
        return manifest

    # Publish the rollback/operation plan before the first destructive step so
    # an interrupted migration is still auditable/recoverable.
    manifest["apply_status"] = "applying"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")

    created_objects: Counter[str] = Counter()
    migrated = 0
    for op in operations:
        metadata_path = Path(op["metadata_path"])
        artifact_name = _validate_artifact_name(op["artifact_name"])
        legacy_path = _safe_legacy_path(metadata_path, artifact_name, config_root)
        digest = op["sha256"]
        # Re-verify immediately before mutation to reduce TOCTOU exposure.
        if sha256_file(legacy_path) != digest:
            raise RuntimeError(f"Legacy artifact changed after migration plan was built: {legacy_path}")
        target = Path(op["object_path"])
        created = _atomic_copy_verified(legacy_path, target, digest)
        if created:
            created_objects[op["source"]] += 1

        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        try:
            object_relpath = str(target.relative_to(config_root.parent))
        except ValueError:
            object_relpath = os.path.relpath(target, start=config_root.parent)
        metadata["storage"] = {
            "mode": "content_addressed_v1",
            "object_sha256": digest,
            "object_path": object_relpath,
            "object_created_this_snapshot": created,
            "stored_bytes_delta": int(op["size_bytes"]) if created else 0,
            "snapshot_contains_payload_copy": False,
            "migrated_from_legacy_snapshot": True,
            "migrated_at": _utc_now(),
        }
        metadata["schema_version"] = "0.6.0A4.3.2.1"
        tmp_metadata = metadata_path.with_name(f".metadata-{uuid.uuid4().hex[:8]}.tmp")
        tmp_metadata.write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp_metadata, metadata_path)

        ref_path = metadata_path.parent / f"{artifact_name}.ref.json"
        ref_tmp = ref_path.with_name(f".{ref_path.name}.{uuid.uuid4().hex[:8]}.tmp")
        ref_tmp.write_text(
            json.dumps({
                "schema_version": "content-addressed-reference-v1",
                "logical_artifact_name": op["artifact_name"],
                "sha256": digest,
                "object_path": object_relpath,
                "size_bytes": int(op["size_bytes"]),
            }, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
        os.replace(ref_tmp, ref_path)

        sha_path = metadata_path.parent / "sha256.txt"
        sha_tmp = sha_path.with_name(f".sha256-{uuid.uuid4().hex[:8]}.tmp")
        sha_tmp.write_text(
            f"{digest}  @{object_relpath}  logical={op['artifact_name']}\n", encoding="ascii"
        )
        os.replace(sha_tmp, sha_path)

        legacy_path.unlink()
        migrated += 1

    manifest["applied"] = True
    manifest["apply_status"] = "completed"
    manifest["migrated_payload_files"] = migrated
    manifest["created_objects_by_source"] = dict(sorted(created_objects.items()))
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    manifest["manifest_path"] = str(manifest_path)
    return manifest


def human_bytes(value: int) -> str:
    size = float(max(0, int(value)))
    units = ["B", "KiB", "MiB", "GiB", "TiB"]
    for unit in units:
        if size < 1024 or unit == units[-1]:
            return f"{size:.2f} {unit}" if unit != "B" else f"{int(size)} B"
        size /= 1024
    return f"{size:.2f} TiB"
