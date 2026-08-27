from __future__ import annotations

import hashlib
import json
import shutil
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from utils.logger import info
from utils.runtime_paths import default_output_root

BASE_DIR = Path(__file__).resolve().parent.parent
OUTPUT_DIR = default_output_root(repository_root=BASE_DIR)
RUNS_DIR = BASE_DIR / "data" / "runs"

CORE_STAGES = (
    "cp",
    "vsx_collect",
    "vsx_parse",
    "cp_config",
    "panorama",
    "pan_config",
    "snapshot",
    "merge",
    "verify",
    "html",
)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _artifact_metadata(path: Path) -> dict[str, Any]:
    row: dict[str, Any] = {
        "size_bytes": path.stat().st_size,
        "sha256": _sha256_file(path),
    }
    if path.suffix.lower() == ".json":
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            row["json_valid"] = True
            if isinstance(data, list):
                row["objects"] = len(data)
            elif isinstance(data, dict):
                row["root_type"] = "object"
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            row["json_valid"] = False
            row["json_error"] = str(exc)
    return row


@dataclass
class RunContext:
    run_id: str
    root: Path
    raw_dir: Path
    parsed_dir: Path
    unified_dir: Path
    stage_dir: Path
    manifest_path: Path
    artifacts: dict[str, str] = field(default_factory=dict)
    artifact_integrity: dict[str, dict[str, Any]] = field(default_factory=dict)
    stages: dict[str, dict[str, Any]] = field(default_factory=dict)
    status: str = "running"
    created_at: str = field(default_factory=_utc_now)
    completed_at: str | None = None
    extra: dict[str, Any] = field(default_factory=dict)
    output_dir: Path = field(default=OUTPUT_DIR, repr=False)
    data_root: Path = field(default=BASE_DIR / "data", repr=False)
    _stage_started_monotonic: dict[str, float] = field(default_factory=dict, repr=False)
    # 0.6.1C: coordinator job metadata — all fields are safe for manifests (no secrets).
    job_id: str | None = None
    provenance: str | None = None          # "manual" | "scheduled"
    effective_scope: str | None = None
    coordinator_decision: str | None = None
    coalesced_to: str | None = None        # job_id this run was merged into

    @classmethod
    def create(cls, *, data_root=None, output_root=None) -> "RunContext":
        data_root = Path(data_root) if data_root is not None else RUNS_DIR.parent
        output_root = Path(output_root) if output_root is not None else OUTPUT_DIR
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        run_id = f"{stamp}_{uuid.uuid4().hex[:8]}"
        root = data_root / "runs" / run_id
        ctx = cls(
            run_id=run_id,
            root=root,
            raw_dir=root / "raw",
            parsed_dir=root / "parsed",
            unified_dir=root / "unified",
            stage_dir=root / "stage",
            manifest_path=root / "manifest.json",
            stages={name: {"status": "pending"} for name in CORE_STAGES},
            output_dir=output_root,
            data_root=data_root,
        )
        for path in (ctx.raw_dir, ctx.parsed_dir, ctx.unified_dir, ctx.stage_dir):
            path.mkdir(parents=True, exist_ok=True)
        ctx.write_manifest(status="running")
        info(f">>> RUN CONTEXT CREATED ({ctx.run_id})")
        return ctx

    def write_manifest(self, status: str | None = None, **extra: Any) -> None:
        if status is not None:
            self.status = status
            if status in {"completed", "degraded", "failed", "quarantined"}:
                self.completed_at = self.completed_at or _utc_now()
        self.extra.update(extra)

        payload = {
            "schema_version": "0.6",
            "run_id": self.run_id,
            "status": self.status,
            "created_at": self.created_at,
            "updated_at": _utc_now(),
            "completed_at": self.completed_at,
            "stages": self.stages,
            # Keep the legacy artifact name->path mapping for compatibility.
            "artifacts": self.artifacts,
            # New formal integrity contract for each captured run artifact.
            "artifact_integrity": self.artifact_integrity,
            **self.extra,
        }
        # 0.6.1C: include coordinator job metadata only when set.
        if self.job_id is not None:
            payload["job_id"] = self.job_id
        if self.provenance is not None:
            payload["provenance"] = self.provenance
        if self.effective_scope is not None:
            payload["effective_scope"] = self.effective_scope
        if self.coordinator_decision is not None:
            payload["coordinator_decision"] = self.coordinator_decision
        if self.coalesced_to is not None:
            payload["coalesced_to"] = self.coalesced_to
        tmp = self.manifest_path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(self.manifest_path)

    def start_stage(self, name: str) -> None:
        stage = self.stages.setdefault(name, {})
        stage.update({"status": "running", "started_at": _utc_now()})
        stage.pop("completed_at", None)
        stage.pop("duration_ms", None)
        stage.pop("error", None)
        self._stage_started_monotonic[name] = time.monotonic()
        self.write_manifest()

    def finish_stage(
        self,
        name: str,
        details: dict[str, Any] | None = None,
        status: str = "success",
    ) -> None:
        if status not in {"success", "degraded"}:
            raise ValueError(f"Unsupported completed stage status: {status}")
        stage = self.stages.setdefault(name, {})
        started = self._stage_started_monotonic.pop(name, None)
        stage.update({
            "status": status,
            "completed_at": _utc_now(),
        })
        if started is not None:
            stage["duration_ms"] = int((time.monotonic() - started) * 1000)
        if details:
            stage["details"] = details
        self.write_manifest()

    def fail_stage(self, name: str, exc: Exception | str) -> None:
        stage = self.stages.setdefault(name, {})
        started = self._stage_started_monotonic.pop(name, None)
        stage.update({
            "status": "failed",
            "completed_at": _utc_now(),
            "error": str(exc),
        })
        if started is not None:
            stage["duration_ms"] = int((time.monotonic() - started) * 1000)
        self.write_manifest(status="failed")

    def set_job_metadata(
        self,
        job_id: str,
        provenance: str,
        coordinator_decision: str | None = None,
        coalesced_to: str | None = None,
        effective_scope: str | None = None,
    ) -> None:
        """Record coordinator job metadata in the manifest.

        All values must be safe for persistence (no secrets, no raw addresses).
        """
        self.job_id = job_id
        self.provenance = provenance
        if effective_scope is not None:
            self.effective_scope = effective_scope
        if coordinator_decision is not None:
            self.coordinator_decision = coordinator_decision
        if coalesced_to is not None:
            self.coalesced_to = coalesced_to
        self.write_manifest()

    def clear_legacy_targets(self, names: Iterable[str]) -> None:
        self.output_dir.mkdir(parents=True, exist_ok=True)
        for name in names:
            path = self.output_dir / name
            if path.exists():
                path.unlink()
                info(f">>> STALE OUTPUT REMOVED BEFORE COLLECTION: {name}")

    def _record_artifact(self, name: str, path: Path) -> None:
        rel = path.relative_to(self.root).as_posix()
        self.artifacts[name] = rel
        self.artifact_integrity[name] = {
            "path": rel,
            **_artifact_metadata(path),
        }
        self.write_manifest()

    def capture(self, name: str, category: str) -> Path:
        source = self.output_dir / name
        if not source.exists():
            raise RuntimeError(f"Expected fresh artifact was not produced: {source}")

        if category == "raw":
            dest_dir = self.raw_dir
        elif category == "unified":
            dest_dir = self.unified_dir
        else:
            dest_dir = self.parsed_dir

        dest = dest_dir / name
        shutil.copy2(source, dest)
        shutil.copy2(source, self.stage_dir / name)
        self._record_artifact(name, dest)
        info(f">>> RUN ARTIFACT CAPTURED: {name}")
        return dest

    def archive_from_stage(self, name: str, category: str) -> Path:
        source = self.stage_dir / name
        if not source.exists():
            raise RuntimeError(f"Run stage artifact missing: {source}")

        if category == "unified":
            dest = self.unified_dir / name
        elif category == "raw":
            dest = self.raw_dir / name
        elif category == "parsed":
            dest = self.parsed_dir / name
        elif category == "root":
            dest = self.root / name
        else:
            raise ValueError(f"Unknown artifact category: {category}")

        shutil.copy2(source, dest)
        self._record_artifact(name, dest)
        info(f">>> RUN STAGE ARTIFACT ARCHIVED: {name}")
        return dest

    def publish_from_stage(self, name: str) -> Path:
        source = self.stage_dir / name
        if not source.exists():
            raise RuntimeError(f"Run artifact missing: {source}")
        self.output_dir.mkdir(parents=True, exist_ok=True)
        dest = self.output_dir / name
        tmp = dest.with_suffix(dest.suffix + ".tmp")
        shutil.copy2(source, tmp)
        tmp.replace(dest)
        return dest
