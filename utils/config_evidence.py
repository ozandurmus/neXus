from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from lxml import etree


BASE_DIR = Path(__file__).resolve().parent.parent
CONFIG_ROOT = BASE_DIR / "data" / "configs"
ARTIFACT_ROOT = BASE_DIR / "data" / "artifacts" / "config" / "sha256"


_SAFE_COMPONENT_RE = re.compile(r"[^A-Za-z0-9._-]+")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def utc_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_component(value: Any) -> str:
    text = str(value or "unknown").strip()
    text = _SAFE_COMPONENT_RE.sub("_", text).strip("._")
    return text or "unknown"


def validate_xml_config(content: bytes) -> dict[str, Any]:
    if not content or len(content) < 32:
        raise ValueError("Configuration artifact is empty or unexpectedly small")
    parser = etree.XMLParser(resolve_entities=False, no_network=True, recover=False)
    root = etree.fromstring(content, parser=parser)
    tag = root.tag.split("}")[-1] if isinstance(root.tag, str) else ""
    if tag != "config":
        raise ValueError(f"Expected <config> root, got <{tag or 'unknown'}>")
    return {
        "xml_valid": True,
        "root_tag": tag,
        "top_level_children": len(root),
    }


def validate_text_config(content: bytes) -> dict[str, Any]:
    """Minimal validator for future vendor-native text configuration evidence.

    This intentionally does not parse vendor syntax. The purpose is to let the
    content-addressed evidence layer support Check Point Gaia/Clish (and other
    text evidence) without coupling storage to a vendor collector.
    """
    if not content or len(content.strip()) < 4:
        raise ValueError("Text configuration artifact is empty or unexpectedly small")
    try:
        text = content.decode("utf-8")
        encoding = "utf-8"
    except UnicodeDecodeError:
        text = content.decode("utf-8", errors="replace")
        encoding = "utf-8-replacement"
    return {
        "text_valid": True,
        "encoding": encoding,
        "line_count": len(text.splitlines()),
    }


@dataclass(frozen=True)
class SnapshotResult:
    directory: Path
    artifact_path: Path
    metadata_path: Path
    sha256: str
    size_bytes: int
    change_state: str
    previous_sha256: str | None
    previous_snapshot: str | None
    logical_artifact_name: str
    blob_created: bool
    stored_bytes_delta: int


class ConfigEvidenceStore:
    """Immutable metadata history + content-addressed configuration objects.

    History remains under ``data/configs/<source>/<entity>/<snapshot>/`` but
    large payloads are stored once under ``data/artifacts/config/sha256``.
    A SAME collection therefore writes only small metadata/reference files.

    The storage contract is vendor-neutral. PAN XML uses ``write_xml_snapshot``;
    future Check Point Gaia/Clish evidence can use ``write_text_snapshot`` with
    the exact same de-duplication/history behavior.
    """

    STORAGE_SCHEMA = "content_addressed_v1"

    def __init__(self, root: Path | None = None, artifact_root: Path | None = None):
        self.root = Path(root) if root else CONFIG_ROOT
        if artifact_root is not None:
            self.artifact_root = Path(artifact_root)
        elif root is not None:
            # Keep tests/migrations isolated beside a custom config root.
            self.artifact_root = self.root.parent / "artifacts" / "config" / "sha256"
        else:
            self.artifact_root = ARTIFACT_ROOT
        self.data_root = self.root.parent

    def _entity_dir(self, source: str, entity_id: str) -> Path:
        return self.root / safe_component(source) / safe_component(entity_id)

    def _blob_path(self, digest: str) -> Path:
        return self.artifact_root / digest[:2] / digest

    def _relative_to_data_root(self, path: Path) -> str:
        try:
            return str(path.relative_to(self.data_root))
        except ValueError:
            return os.path.relpath(path, start=self.data_root)

    def _latest_metadata(
        self, entity_dir: Path, *, artifact_type: str | None = None
    ) -> tuple[Path, dict[str, Any]] | None:
        if not entity_dir.exists():
            return None
        candidates = []
        for child in entity_dir.iterdir():
            if not child.is_dir() or child.name.startswith(".tmp-"):
                continue
            metadata_path = child / "metadata.json"
            if metadata_path.exists():
                candidates.append(metadata_path)
        for metadata_path in sorted(candidates, key=lambda p: p.parent.name, reverse=True):
            try:
                payload = json.loads(metadata_path.read_text(encoding="utf-8"))
            except (OSError, UnicodeDecodeError, json.JSONDecodeError):
                continue
            if artifact_type is not None and payload.get("artifact_type") != artifact_type:
                continue
            if payload.get("status") == "success" and payload.get("sha256"):
                return metadata_path, payload
        return None

    def _ensure_blob(self, *, content: bytes, digest: str) -> tuple[Path, bool]:
        """Write content-addressed blob with concurrent-safe fallback handling.
        
        Multiple workers may attempt to write the same digest (identical content).
        This method is safe under concurrency:
        - If blob_path exists with matching digest, return it (another worker won).
        - If write fails due to lock, wait briefly and retry the existence check.
        - Only the first writer records blob_created=True in metadata.
        """
        blob_path = self._blob_path(digest)
        blob_path.parent.mkdir(parents=True, exist_ok=True)
        
        # Check if blob already exists (common case for concurrent de-duplication).
        if blob_path.exists():
            if sha256_file(blob_path) != digest:
                raise RuntimeError(f"Content-addressed artifact hash mismatch: {blob_path}")
            return blob_path, False

        # Attempt to write new blob with retry on concurrent lock failures.
        max_retries = 3
        retry_delay_seconds = 0.1
        
        for attempt in range(max_retries):
            tmp_blob = blob_path.parent / f".tmp-{digest}-{uuid.uuid4().hex[:8]}"
            try:
                tmp_blob.write_bytes(content)
                if sha256_file(tmp_blob) != digest:
                    raise RuntimeError("Configuration object hash changed while writing")
                
                # Attempt atomic replace. On Windows, concurrent writers may race here.
                os.replace(tmp_blob, blob_path)
                return blob_path, True
                
            except (OSError, PermissionError) as exc:
                # Cleanup temporary blob if it exists.
                try:
                    if tmp_blob.exists():
                        tmp_blob.unlink()
                except OSError:
                    pass
                
                # Check if another concurrent writer already succeeded.
                if blob_path.exists():
                    try:
                        if sha256_file(blob_path) == digest:
                            # Another worker succeeded; reuse their blob.
                            return blob_path, False
                    except (OSError, RuntimeError):
                        pass
                
                # Retry on transient lock if not the final attempt.
                if attempt < max_retries - 1:
                    time.sleep(retry_delay_seconds * (2 ** attempt))
                    continue
                
                # Final attempt failed; raise the original exception.
                raise
        
        # Should not reach here, but fallback.
        raise RuntimeError(f"Failed to write configuration blob after {max_retries} attempts")

    def _write_snapshot(
        self,
        *,
        source: str,
        entity_id: str,
        artifact_type: str,
        content: bytes,
        method: str,
        validation: dict[str, Any],
        device_name: str | None = None,
        management_ip: str | None = None,
        collector_version: str = "0.6.0A4.3.2",
        extra_metadata: dict[str, Any] | None = None,
        additional_validation: dict[str, Any] | None = None,
        artifact_name: str = "configuration.bin",
        media_type: str = "application/octet-stream",
    ) -> SnapshotResult:
        validation = dict(validation)
        if additional_validation:
            validation.update(additional_validation)
        digest = sha256_bytes(content)
        size_bytes = len(content)
        entity_dir = self._entity_dir(source, entity_id)
        entity_dir.mkdir(parents=True, exist_ok=True)

        previous = self._latest_metadata(entity_dir, artifact_type=artifact_type)
        previous_sha = previous[1].get("sha256") if previous else None
        previous_snapshot = previous[0].parent.name if previous else None
        if previous_sha is None:
            change_state = "first"
        elif previous_sha == digest:
            change_state = "same"
        else:
            change_state = "changed"

        # Publish/reuse the immutable object before publishing the history event.
        blob_path, blob_created = self._ensure_blob(content=content, digest=digest)

        snapshot_name = f"{utc_stamp()}_{uuid.uuid4().hex[:8]}"
        final_dir = entity_dir / snapshot_name
        tmp_dir = entity_dir / f".tmp-{snapshot_name}"
        tmp_dir.mkdir(parents=False, exist_ok=False)

        artifact_name = safe_component(artifact_name)
        metadata_path = tmp_dir / "metadata.json"
        sha_path = tmp_dir / "sha256.txt"
        ref_path = tmp_dir / f"{artifact_name}.ref.json"
        object_relpath = self._relative_to_data_root(blob_path)

        try:
            metadata = {
                "schema_version": "0.6.0A4.3.2",
                "source": source,
                "entity_id": entity_id,
                "device": device_name,
                "management_ip": management_ip,
                "artifact_type": artifact_type,
                "artifact_file": artifact_name,
                "media_type": media_type,
                "collected_at": utc_now(),
                "method": method,
                "status": "success",
                "sha256": digest,
                "size_bytes": size_bytes,
                "collector_version": collector_version,
                "change_state": change_state,
                "previous_sha256": previous_sha,
                "previous_snapshot": previous_snapshot,
                "storage": {
                    "mode": self.STORAGE_SCHEMA,
                    "object_sha256": digest,
                    "object_path": object_relpath,
                    "object_created_this_snapshot": blob_created,
                    "stored_bytes_delta": size_bytes if blob_created else 0,
                    "snapshot_contains_payload_copy": False,
                },
                "validation": validation,
            }
            if extra_metadata:
                metadata.update(extra_metadata)

            metadata_path.write_text(
                json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8"
            )
            sha_path.write_text(
                f"{digest}  @{object_relpath}  logical={artifact_name}\n", encoding="ascii"
            )
            ref_path.write_text(
                json.dumps(
                    {
                        "schema_version": "content-addressed-reference-v1",
                        "logical_artifact_name": artifact_name,
                        "sha256": digest,
                        "object_path": object_relpath,
                        "size_bytes": size_bytes,
                    },
                    indent=2,
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )

            os.replace(tmp_dir, final_dir)
        except Exception:
            shutil.rmtree(tmp_dir, ignore_errors=True)
            # Never delete blob_path here. Another already-published snapshot may
            # reference it, or a concurrent writer may have created the same one.
            raise

        return SnapshotResult(
            directory=final_dir,
            artifact_path=blob_path,
            metadata_path=final_dir / "metadata.json",
            sha256=digest,
            size_bytes=size_bytes,
            change_state=change_state,
            previous_sha256=previous_sha,
            previous_snapshot=previous_snapshot,
            logical_artifact_name=artifact_name,
            blob_created=blob_created,
            stored_bytes_delta=size_bytes if blob_created else 0,
        )

    def write_xml_snapshot(
        self,
        *,
        source: str,
        entity_id: str,
        artifact_type: str,
        content: bytes,
        method: str,
        device_name: str | None = None,
        management_ip: str | None = None,
        collector_version: str = "0.6.0A4.3.2",
        extra_metadata: dict[str, Any] | None = None,
        additional_validation: dict[str, Any] | None = None,
        artifact_name: str = "running-config.xml",
    ) -> SnapshotResult:
        return self._write_snapshot(
            source=source,
            entity_id=entity_id,
            artifact_type=artifact_type,
            content=content,
            method=method,
            validation=validate_xml_config(content),
            device_name=device_name,
            management_ip=management_ip,
            collector_version=collector_version,
            extra_metadata=extra_metadata,
            additional_validation=additional_validation,
            artifact_name=artifact_name,
            media_type="application/xml",
        )

    def write_text_snapshot(
        self,
        *,
        source: str,
        entity_id: str,
        artifact_type: str,
        content: bytes | str,
        method: str,
        device_name: str | None = None,
        management_ip: str | None = None,
        collector_version: str = "0.6.0A4.3.2",
        extra_metadata: dict[str, Any] | None = None,
        additional_validation: dict[str, Any] | None = None,
        artifact_name: str = "show-configuration.txt",
    ) -> SnapshotResult:
        encoded = content.encode("utf-8") if isinstance(content, str) else content
        return self._write_snapshot(
            source=source,
            entity_id=entity_id,
            artifact_type=artifact_type,
            content=encoded,
            method=method,
            validation=validate_text_config(encoded),
            device_name=device_name,
            management_ip=management_ip,
            collector_version=collector_version,
            extra_metadata=extra_metadata,
            additional_validation=additional_validation,
            artifact_name=artifact_name,
            media_type="text/plain",
        )

    def write_binary_snapshot(
        self,
        *,
        source: str,
        entity_id: str,
        artifact_type: str,
        content: bytes,
        method: str,
        device_name: str | None = None,
        management_ip: str | None = None,
        collector_version: str = "0.6.0A4.3.2",
        extra_metadata: dict[str, Any] | None = None,
        additional_validation: dict[str, Any] | None = None,
        artifact_name: str = "artifact.bin",
        media_type: str = "application/octet-stream",
        validator: Callable[[bytes], dict[str, Any]] | None = None,
    ) -> SnapshotResult:
        if not content:
            raise ValueError("Binary artifact is empty")
        validation = validator(content) if validator else {"non_empty": True}
        return self._write_snapshot(
            source=source,
            entity_id=entity_id,
            artifact_type=artifact_type,
            content=content,
            method=method,
            validation=validation,
            device_name=device_name,
            management_ip=management_ip,
            collector_version=collector_version,
            extra_metadata=extra_metadata,
            additional_validation=additional_validation,
            artifact_name=artifact_name,
            media_type=media_type,
        )
