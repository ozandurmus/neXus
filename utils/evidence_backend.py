"""Evidence store backend implementations — DEV.3.3 distributed evidence store migration.

Five independent storage concerns move from per-container local files to an
opt-in PostgreSQL backend, byte-compatible with today's filesystem behavior
(a sixth, the CON.2 console job store, and a seventh, the OP.2.A/B class 2
action record, were added later; an eighth, the PCP.1 Device Registry, is
filesystem-only for now — see their own sections below):

* **Config snapshot** metadata index (``utils/config_evidence.py``,
  ``utils/config_history.py``) — content-addressed payload blobs
  (``data/artifacts/config/sha256/**``) are explicitly out of scope and never
  touched here.
* **Run manifest** (``utils/run_context.py``).
* **Last-known-good** entity state (``utils/snapshot.py``).
* **Scheduler state** (``utils/collection_executor.py``).
* **Operational-write ledger** — the durable 24 h per-endpoint ceiling on
  ``operational-write`` device commands (``utils/recovery_operational_ledger.py``,
  RB.3b decision B4).

See ``docs/history/phase/DEV3_3_DISTRIBUTED_EVIDENCE_STORE_MIGRATION.md`` for
the full contract, design decisions and the resolved identity-fidelity
decision (E1: full fidelity — this module stores device/management_ip/
entity_id in Postgres exactly as the filesystem backend does today).

This is an independent, opt-in sibling of ``utils/coordinator_backend.py``
(DEV.3.2) — a different env var (``SECURITYEXPERT_EVIDENCE_BACKEND``, not
``SECURITYEXPERT_COORDINATOR_BACKEND``) selects it, and it may be enabled
independently of, together with, or without the coordinator's Postgres
backend. Unlike the coordinator backend, nothing here holds a lock across a
connection's idle time — every operation is a single independent statement in
its own short transaction, so this backend has no equivalent of the
coordinator's transaction-pooling-proxy hazard (see the phase doc, D6).
"""
from __future__ import annotations

import abc
import json
import os
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


class EvidenceBackendError(RuntimeError):
    """Raised when an evidence backend cannot safely read or write.

    Fail-closed: a caller must never fall back to unsynchronized local state
    on this error.
    """


_SAFE_COMPONENT_RE = re.compile(r"[^A-Za-z0-9._-]+")


def _safe_component(value: Any) -> str:
    text = str(value or "unknown").strip()
    text = _SAFE_COMPONENT_RE.sub("_", text).strip("._")
    return text or "unknown"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _parse_dt(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=timezone.utc)


def _write_json_atomic(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + f".tmp-{os.getpid()}")
    tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


def _replace_dir_with_retry(tmp_path: Path, final_path: Path, *, max_retries: int = 3, retry_delay_seconds: float = 0.1) -> None:
    for attempt in range(max_retries):
        try:
            os.replace(tmp_path, final_path)
            return
        except (OSError, PermissionError):
            if attempt < max_retries - 1:
                time.sleep(retry_delay_seconds * (2 ** attempt))
                continue
            raise


# PostgreSQL's CREATE TABLE IF NOT EXISTS is *not* safe against a concurrent
# identical CREATE: two processes racing it can fail with a duplicate-key error
# on pg_type. Two worker containers starting together against a fresh database
# hit exactly that, so all schema creation is serialized behind one
# transaction-level advisory lock (released at commit — it never outlives the
# transaction, so it is safe behind a transaction-pooling proxy, unlike the
# coordinator's session-level locks).
_SCHEMA_LOCK_KEY = 0x5EC0DE33  # arbitrary fixed constant, DEV.3.3 schema only


def _ensure_schema(psycopg_module, dsn: str, statements: tuple[str, ...]) -> None:
    with psycopg_module.connect(dsn, autocommit=True) as conn:
        with conn.transaction():
            with conn.cursor() as cur:
                cur.execute("SELECT pg_advisory_xact_lock(%s)", (_SCHEMA_LOCK_KEY,))
                for statement in statements:
                    cur.execute(statement)


def _psycopg():
    try:
        import psycopg
    except ImportError as exc:  # pragma: no cover - exercised only without the driver
        raise EvidenceBackendError(
            "SECURITYEXPERT_EVIDENCE_BACKEND=postgres requires the 'psycopg' package "
            "(psycopg[binary]>=3.1); it is not installed."
        ) from exc
    return psycopg


# ---------------------------------------------------------------------------
# 1. Config snapshot backend (CAS metadata index)
# ---------------------------------------------------------------------------

class ConfigSnapshotBackend(abc.ABC):
    """Storage primitive for content-addressed configuration snapshot metadata.

    Deliberately dumb: no business validation, no artifact-type filtering, no
    "latest"/"success" semantics — those stay in ``utils/config_evidence.py``
    and ``utils/config_history.py`` so both keep behaving identically
    regardless of which backend is active (DEV.3.3 contract, D4).
    """

    @abc.abstractmethod
    def write(self, *, source: str, entity_id: str, snapshot_id: str, metadata: dict[str, Any]) -> None:
        ...

    @abc.abstractmethod
    def list_snapshots(self, *, source: str, entity_id: str) -> list[tuple[str, dict[str, Any] | None]]:
        """All snapshot ids for this entity with their raw metadata dict.

        Newest-first by ``snapshot_id`` (today's ``<utc_stamp>_<uuid8>`` format
        sorts chronologically as a string, matching the filesystem backend's
        existing directory-name sort). ``None`` in place of a metadata dict
        means the stored record could not be read/parsed (a storage-layer
        failure) — callers count these as malformed, exactly as today.
        """

    @abc.abstractmethod
    def get_snapshot(self, *, source: str, entity_id: str, snapshot_id: str) -> dict[str, Any] | None:
        ...

    @abc.abstractmethod
    def snapshot_location(self, *, source: str, entity_id: str, snapshot_id: str) -> Path:
        """Where this snapshot's metadata lives, for display in collector results.

        A real directory on the filesystem backend; a synthetic, deliberately
        non-existent ``postgres/config_snapshot/<id>`` pointer on the Postgres
        backend, which has no per-snapshot directory (contract amendment A6).
        """


class FilesystemConfigSnapshotBackend(ConfigSnapshotBackend):
    """Today's exact ``data/configs/<source>/<entity>/<snapshot>/`` layout."""

    def __init__(self, root: Path) -> None:
        self.root = Path(root)

    def _entity_dir(self, source: str, entity_id: str) -> Path:
        return self.root / _safe_component(source) / _safe_component(entity_id)

    def write(self, *, source: str, entity_id: str, snapshot_id: str, metadata: dict[str, Any]) -> None:
        entity_dir = self._entity_dir(source, entity_id)
        entity_dir.mkdir(parents=True, exist_ok=True)
        final_dir = entity_dir / snapshot_id
        tmp_dir = entity_dir / f".tmp-{snapshot_id}"
        tmp_dir.mkdir(parents=False, exist_ok=False)

        storage = metadata.get("storage") if isinstance(metadata.get("storage"), dict) else {}
        artifact_name = _safe_component(metadata.get("artifact_file") or "artifact.bin")
        digest = str(metadata.get("sha256") or "")
        object_relpath = str(storage.get("object_path") or "")

        try:
            (tmp_dir / "metadata.json").write_text(
                json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8"
            )
            (tmp_dir / "sha256.txt").write_text(
                f"{digest}  @{object_relpath}  logical={artifact_name}\n", encoding="ascii"
            )
            (tmp_dir / f"{artifact_name}.ref.json").write_text(
                json.dumps(
                    {
                        "schema_version": "content-addressed-reference-v1",
                        "logical_artifact_name": artifact_name,
                        "sha256": digest,
                        "object_path": object_relpath,
                        "size_bytes": metadata.get("size_bytes"),
                    },
                    indent=2,
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            _replace_dir_with_retry(tmp_dir, final_dir)
        except Exception:
            import shutil
            shutil.rmtree(tmp_dir, ignore_errors=True)
            raise

    def list_snapshots(self, *, source: str, entity_id: str) -> list[tuple[str, dict[str, Any] | None]]:
        entity_dir = self._entity_dir(source, entity_id)
        if not entity_dir.exists():
            return []
        rows: list[tuple[str, dict[str, Any] | None]] = []
        for child in entity_dir.iterdir():
            if not child.is_dir() or child.name.startswith(".tmp-"):
                continue
            metadata_path = child / "metadata.json"
            if not metadata_path.exists():
                continue
            try:
                meta = json.loads(metadata_path.read_text(encoding="utf-8"))
                if not isinstance(meta, dict):
                    meta = None
            except (OSError, UnicodeDecodeError, json.JSONDecodeError):
                meta = None
            rows.append((child.name, meta))
        rows.sort(key=lambda row: row[0], reverse=True)
        return rows

    def get_snapshot(self, *, source: str, entity_id: str, snapshot_id: str) -> dict[str, Any] | None:
        metadata_path = self._entity_dir(source, entity_id) / snapshot_id / "metadata.json"
        if not metadata_path.exists():
            return None
        try:
            meta = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            return None
        return meta if isinstance(meta, dict) else None

    def snapshot_location(self, *, source: str, entity_id: str, snapshot_id: str) -> Path:
        return self._entity_dir(source, entity_id) / snapshot_id


_CONFIG_SNAPSHOT_SCHEMA = (
    """
    CREATE TABLE IF NOT EXISTS config_snapshot (
        snapshot_id       TEXT PRIMARY KEY,
        source            TEXT NOT NULL,
        entity_id         TEXT NOT NULL,
        artifact_type     TEXT,
        device            TEXT,
        management_ip     TEXT,
        collected_at      TIMESTAMPTZ,
        method            TEXT,
        status            TEXT,
        sha256            TEXT,
        size_bytes        BIGINT,
        collector_version TEXT,
        change_state      TEXT,
        previous_sha256   TEXT,
        previous_snapshot TEXT,
        object_path       TEXT,
        metadata_json     JSONB NOT NULL
    )
    """,
    "CREATE INDEX IF NOT EXISTS config_snapshot_latest_idx "
    "ON config_snapshot (source, entity_id, artifact_type, snapshot_id DESC)",
    "CREATE INDEX IF NOT EXISTS config_snapshot_sha256_idx ON config_snapshot (sha256)",
)


class PostgresConfigSnapshotBackend(ConfigSnapshotBackend):
    """CAS metadata index on PostgreSQL — identity fields stored in full (E1, Option 1)."""

    def __init__(self, dsn: str) -> None:
        self._psycopg = _psycopg()
        self._dsn = dsn
        self._ensure_schema()

    def _connect(self):
        return self._psycopg.connect(self._dsn, autocommit=True)

    def _ensure_schema(self) -> None:
        _ensure_schema(self._psycopg, self._dsn, _CONFIG_SNAPSHOT_SCHEMA)

    def write(self, *, source: str, entity_id: str, snapshot_id: str, metadata: dict[str, Any]) -> None:
        from psycopg.types.json import Jsonb

        storage = metadata.get("storage") if isinstance(metadata.get("storage"), dict) else {}
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        INSERT INTO config_snapshot (
                            snapshot_id, source, entity_id, artifact_type, device,
                            management_ip, collected_at, method, status, sha256,
                            size_bytes, collector_version, change_state,
                            previous_sha256, previous_snapshot, object_path, metadata_json
                        ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                        ON CONFLICT (snapshot_id) DO NOTHING
                        """,
                        (
                            snapshot_id, source, entity_id, metadata.get("artifact_type"),
                            metadata.get("device"), metadata.get("management_ip"),
                            _parse_dt(metadata.get("collected_at")), metadata.get("method"),
                            metadata.get("status"), metadata.get("sha256"), metadata.get("size_bytes"),
                            metadata.get("collector_version"), metadata.get("change_state"),
                            metadata.get("previous_sha256"), metadata.get("previous_snapshot"),
                            storage.get("object_path"), Jsonb(metadata),
                        ),
                    )
        except Exception as exc:
            raise EvidenceBackendError(f"postgres config snapshot write failed: {exc}") from exc

    def list_snapshots(self, *, source: str, entity_id: str) -> list[tuple[str, dict[str, Any] | None]]:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        "SELECT snapshot_id, metadata_json FROM config_snapshot "
                        "WHERE source = %s AND entity_id = %s ORDER BY snapshot_id DESC",
                        (source, entity_id),
                    )
                    rows = cur.fetchall()
        except Exception as exc:
            raise EvidenceBackendError(f"postgres config snapshot list failed: {exc}") from exc
        return [(row[0], row[1]) for row in rows]

    def get_snapshot(self, *, source: str, entity_id: str, snapshot_id: str) -> dict[str, Any] | None:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        "SELECT metadata_json FROM config_snapshot "
                        "WHERE source = %s AND entity_id = %s AND snapshot_id = %s",
                        (source, entity_id, snapshot_id),
                    )
                    row = cur.fetchone()
        except Exception as exc:
            raise EvidenceBackendError(f"postgres config snapshot get failed: {exc}") from exc
        return row[0] if row else None

    def snapshot_location(self, *, source: str, entity_id: str, snapshot_id: str) -> Path:
        return Path("postgres") / "config_snapshot" / snapshot_id


# ---------------------------------------------------------------------------
# 2. Run manifest backend
# ---------------------------------------------------------------------------

class RunManifestBackend(abc.ABC):
    @abc.abstractmethod
    def write_manifest(self, *, manifest_path: Path, manifest: dict[str, Any]) -> None:
        ...


class FilesystemRunManifestBackend(RunManifestBackend):
    def write_manifest(self, *, manifest_path: Path, manifest: dict[str, Any]) -> None:
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = manifest_path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(manifest_path)


_RUN_MANIFEST_SCHEMA = (
    """
    CREATE TABLE IF NOT EXISTS run_manifest (
        run_id        TEXT PRIMARY KEY,
        status        TEXT NOT NULL,
        job_id        TEXT,
        created_at    TIMESTAMPTZ,
        updated_at    TIMESTAMPTZ NOT NULL,
        manifest_json JSONB NOT NULL
    )
    """,
    "CREATE INDEX IF NOT EXISTS run_manifest_status_idx ON run_manifest (status, updated_at DESC)",
)


class PostgresRunManifestBackend(RunManifestBackend):
    def __init__(self, dsn: str) -> None:
        self._psycopg = _psycopg()
        self._dsn = dsn
        self._ensure_schema()

    def _connect(self):
        return self._psycopg.connect(self._dsn, autocommit=True)

    def _ensure_schema(self) -> None:
        _ensure_schema(self._psycopg, self._dsn, _RUN_MANIFEST_SCHEMA)

    def write_manifest(self, *, manifest_path: Path, manifest: dict[str, Any]) -> None:
        from psycopg.types.json import Jsonb

        run_id = str(manifest.get("run_id") or "")
        if not run_id:
            raise EvidenceBackendError("run manifest is missing run_id; cannot persist to Postgres")
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        INSERT INTO run_manifest (run_id, status, job_id, created_at, updated_at, manifest_json)
                        VALUES (%s,%s,%s,%s,%s,%s)
                        ON CONFLICT (run_id) DO UPDATE SET
                            status = EXCLUDED.status,
                            job_id = EXCLUDED.job_id,
                            updated_at = EXCLUDED.updated_at,
                            manifest_json = EXCLUDED.manifest_json
                        """,
                        (
                            run_id, manifest.get("status") or "running", manifest.get("job_id"),
                            _parse_dt(manifest.get("created_at")), _parse_dt(manifest.get("updated_at")) or datetime.now(timezone.utc),
                            Jsonb(manifest),
                        ),
                    )
        except Exception as exc:
            raise EvidenceBackendError(f"postgres run manifest write failed: {exc}") from exc


# ---------------------------------------------------------------------------
# 3. Last-known-good backend
# ---------------------------------------------------------------------------

class LastKnownGoodBackend(abc.ABC):
    """Per-entity last-known-good state.

    ``commit()`` exists so the filesystem backend can batch every
    ``put_entity`` call from one run into the single atomic whole-file write
    ``utils/snapshot.py`` has always done (zero behavior change, DEV.3.3
    contract D7). The Postgres backend writes each entity immediately and
    independently — this is what actually closes the D2 hazard (one
    container's whole-file rewrite silently discarding another container's
    concurrently-written entities); ``commit()`` is a no-op there.
    """

    @abc.abstractmethod
    def get_entity(self, *, source: str, entity_key: str) -> dict[str, Any] | None:
        ...

    @abc.abstractmethod
    def put_entity(self, *, source: str, entity_key: str, item: dict[str, Any], last_successful_collection: str | None) -> None:
        ...

    @abc.abstractmethod
    def commit(self) -> None:
        ...


class FilesystemLastKnownGoodBackend(LastKnownGoodBackend):
    """Today's exact single ``last_known_good.json`` whole-file read/write."""

    SCHEMA_VERSION = "0.5"

    def __init__(self, state_file: Path) -> None:
        self.state_file = Path(state_file)
        try:
            self._state = json.loads(self.state_file.read_text(encoding="utf-8"))
            if not isinstance(self._state, dict):
                self._state = {}
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            self._state = {}
        self._entities = self._state.setdefault("entities", {})

    def get_entity(self, *, source: str, entity_key: str) -> dict[str, Any] | None:
        return self._entities.get(source, {}).get(entity_key)

    def put_entity(self, *, source: str, entity_key: str, item: dict[str, Any], last_successful_collection: str | None) -> None:
        self._entities.setdefault(source, {})[entity_key] = {
            "item": item,
            "last_successful_collection": last_successful_collection,
        }

    def commit(self) -> None:
        self._state["schema_version"] = self.SCHEMA_VERSION
        self._state["updated_at"] = _utc_now()
        _write_json_atomic(self.state_file, self._state)


_LAST_KNOWN_GOOD_SCHEMA = (
    """
    CREATE TABLE IF NOT EXISTS last_known_good_entity (
        source                     TEXT NOT NULL,
        entity_key                 TEXT NOT NULL,
        item_json                  JSONB NOT NULL,
        last_successful_collection TIMESTAMPTZ,
        updated_at                 TIMESTAMPTZ NOT NULL,
        PRIMARY KEY (source, entity_key)
    )
    """,
)


class PostgresLastKnownGoodBackend(LastKnownGoodBackend):
    def __init__(self, dsn: str) -> None:
        self._psycopg = _psycopg()
        self._dsn = dsn
        self._ensure_schema()

    def _connect(self):
        return self._psycopg.connect(self._dsn, autocommit=True)

    def _ensure_schema(self) -> None:
        _ensure_schema(self._psycopg, self._dsn, _LAST_KNOWN_GOOD_SCHEMA)

    def get_entity(self, *, source: str, entity_key: str) -> dict[str, Any] | None:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        "SELECT item_json, last_successful_collection FROM last_known_good_entity "
                        "WHERE source = %s AND entity_key = %s",
                        (source, entity_key),
                    )
                    row = cur.fetchone()
        except Exception as exc:
            raise EvidenceBackendError(f"postgres last-known-good read failed: {exc}") from exc
        if row is None:
            return None
        item_json, last_successful_collection = row
        return {
            "item": item_json,
            "last_successful_collection": (
                last_successful_collection.isoformat() if last_successful_collection else None
            ),
        }

    def put_entity(self, *, source: str, entity_key: str, item: dict[str, Any], last_successful_collection: str | None) -> None:
        from psycopg.types.json import Jsonb

        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        INSERT INTO last_known_good_entity (source, entity_key, item_json, last_successful_collection, updated_at)
                        VALUES (%s,%s,%s,%s,%s)
                        ON CONFLICT (source, entity_key) DO UPDATE SET
                            item_json = EXCLUDED.item_json,
                            last_successful_collection = EXCLUDED.last_successful_collection,
                            updated_at = EXCLUDED.updated_at
                        """,
                        (source, entity_key, Jsonb(item), _parse_dt(last_successful_collection), datetime.now(timezone.utc)),
                    )
        except Exception as exc:
            raise EvidenceBackendError(f"postgres last-known-good write failed: {exc}") from exc

    def commit(self) -> None:
        pass


# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# 6. Console job backend (CON.2 C2-4)
# ---------------------------------------------------------------------------
#
# One durable record per operator-triggered console job. Lives under
# ``data_root/state/console_jobs``, never under ``output_root`` and never in
# the support bundle (C2-4 / privacy invariant 4). Forbidden in a job record
# (asserted by test): credentials, tokens, management addresses, hostnames
# beyond an ``entity_id``, raw device output, backup bytes, file paths
# outside the runtime root, stack traces -- ``console/jobs.py`` enforces the
# shape; this backend is deliberately dumb storage, same split as the other
# five concerns.

_CONSOLE_JOB_SCHEMA = (
    """
    CREATE TABLE IF NOT EXISTS console_job (
        job_id             TEXT PRIMARY KEY,
        idempotency_key    TEXT NOT NULL,
        job_type           TEXT NOT NULL,
        command_class      TEXT NOT NULL,
        targets_json       JSONB NOT NULL,
        state              TEXT NOT NULL,
        requested_at       TIMESTAMPTZ NOT NULL,
        started_at         TIMESTAMPTZ,
        finished_at        TIMESTAMPTZ,
        run_id             TEXT,
        coordinator_decision TEXT,
        outcome_counts_json JSONB,
        error_code         TEXT,
        error_summary      TEXT
    )
    """,
    "CREATE UNIQUE INDEX IF NOT EXISTS console_job_idempotency_idx "
    "ON console_job (idempotency_key)",
    "CREATE INDEX IF NOT EXISTS console_job_requested_at_idx "
    "ON console_job (requested_at DESC)",
)

class ConsoleJobBackend(abc.ABC):
    """Storage primitive for CON.2 durable job records.

    Deliberately dumb: no registry validation, no admission logic, no
    idempotency *policy* -- ``console/jobs.py`` owns all of that so both
    backends behave identically.
    """

    @abc.abstractmethod
    def create(self, job: dict[str, Any]) -> None:
        """Insert a new job record. ``job['job_id']`` and
        ``job['idempotency_key']`` must be unique; a duplicate `job_id`` is
        a programming error (caller generates a fresh uuid per job), but a
        duplicate ``idempotency_key`` is an expected race the caller must
        handle by re-reading via ``get_by_idempotency_key`` -- this raises
        ``EvidenceBackendError`` rather than silently overwriting."""

    @abc.abstractmethod
    def update(self, job_id: str, **fields: Any) -> None:
        """Merge the given fields into the existing record."""

    @abc.abstractmethod
    def get(self, job_id: str) -> dict[str, Any] | None:
        ...

    @abc.abstractmethod
    def get_by_idempotency_key(self, idempotency_key: str) -> dict[str, Any] | None:
        ...

    @abc.abstractmethod
    def list_all(self) -> list[dict[str, Any]]:
        """Every job record, newest-``requested_at``-first."""

    @abc.abstractmethod
    def mark_orphaned_running_as_failed(self, *, error_code: str) -> list[str]:
        """C2-5: on console startup, any record still ``running`` from a
        prior process is a crash, not a zombie -- mark it ``failed`` with
        ``error_code`` and return the affected job ids."""


class FilesystemConsoleJobBackend(ConsoleJobBackend):
    """One JSON file per job under ``<data_root>/state/console_jobs/``."""

    def __init__(self, root: Path) -> None:
        self.root = Path(root)

    def _job_path(self, job_id: str) -> Path:
        return self.root / f"{_safe_component(job_id)}.json"

    def create(self, job: dict[str, Any]) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        if self.get_by_idempotency_key(job["idempotency_key"]) is not None:
            raise EvidenceBackendError(
                f"console job idempotency_key already recorded: {job['idempotency_key']!r}"
            )
        path = self._job_path(job["job_id"])
        if path.exists():
            raise EvidenceBackendError(f"console job_id already recorded: {job['job_id']!r}")
        _write_json_atomic(path, job)

    def update(self, job_id: str, **fields: Any) -> None:
        path = self._job_path(job_id)
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise EvidenceBackendError(f"console job {job_id!r} is unreadable: {exc}") from exc
        record.update(fields)
        _write_json_atomic(path, record)

    def get(self, job_id: str) -> dict[str, Any] | None:
        path = self._job_path(job_id)
        if not path.exists():
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            return None

    def get_by_idempotency_key(self, idempotency_key: str) -> dict[str, Any] | None:
        for record in self.list_all():
            if record.get("idempotency_key") == idempotency_key:
                return record
        return None

    def list_all(self) -> list[dict[str, Any]]:
        if not self.root.exists():
            return []
        records: list[dict[str, Any]] = []
        for child in self.root.iterdir():
            if not child.is_file() or child.suffix != ".json":
                continue
            try:
                record = json.loads(child.read_text(encoding="utf-8"))
            except (OSError, UnicodeDecodeError, json.JSONDecodeError):
                continue
            if isinstance(record, dict):
                records.append(record)
        records.sort(key=lambda r: str(r.get("requested_at") or ""), reverse=True)
        return records

    def mark_orphaned_running_as_failed(self, *, error_code: str) -> list[str]:
        affected: list[str] = []
        for record in self.list_all():
            if record.get("state") == "running":
                self.update(
                    record["job_id"],
                    state="failed",
                    error_code=error_code,
                    finished_at=_utc_now(),
                )
                affected.append(record["job_id"])
        return affected


class PostgresConsoleJobBackend(ConsoleJobBackend):
    def __init__(self, dsn: str) -> None:
        self._psycopg = _psycopg()
        self._dsn = dsn
        self._ensure_schema()

    def _connect(self):
        return self._psycopg.connect(self._dsn, autocommit=True)

    def _ensure_schema(self) -> None:
        _ensure_schema(self._psycopg, self._dsn, _CONSOLE_JOB_SCHEMA)

    def _row_to_record(self, row) -> dict[str, Any]:
        (job_id, idempotency_key, job_type, command_class, targets, state,
         requested_at, started_at, finished_at, run_id, coordinator_decision,
         outcome_counts, error_code, error_summary) = row
        return {
            "job_id": job_id,
            "idempotency_key": idempotency_key,
            "job_type": job_type,
            "command_class": command_class,
            "targets": targets,
            "state": state,
            "requested_at": requested_at.isoformat() if requested_at else None,
            "started_at": started_at.isoformat() if started_at else None,
            "finished_at": finished_at.isoformat() if finished_at else None,
            "run_id": run_id,
            "coordinator_decision": coordinator_decision,
            "outcome_counts": outcome_counts,
            "error_code": error_code,
            "error_summary": error_summary,
        }

    def create(self, job: dict[str, Any]) -> None:
        from psycopg.errors import UniqueViolation
        from psycopg.types.json import Jsonb

        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        INSERT INTO console_job (
                            job_id, idempotency_key, job_type, command_class, targets_json,
                            state, requested_at, started_at, finished_at, run_id,
                            coordinator_decision, outcome_counts_json, error_code, error_summary
                        ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                        """,
                        (
                            job["job_id"], job["idempotency_key"], job["job_type"], job["command_class"],
                            Jsonb(job.get("targets") or []), job["state"], _parse_dt(job.get("requested_at")),
                            _parse_dt(job.get("started_at")), _parse_dt(job.get("finished_at")), job.get("run_id"),
                            job.get("coordinator_decision"),
                            Jsonb(job["outcome_counts"]) if job.get("outcome_counts") is not None else None,
                            job.get("error_code"), job.get("error_summary"),
                        ),
                    )
        except UniqueViolation as exc:
            raise EvidenceBackendError(
                f"console job already recorded (job_id or idempotency_key collision): {exc}"
            ) from exc
        except Exception as exc:
            raise EvidenceBackendError(f"postgres console job create failed: {exc}") from exc

    def update(self, job_id: str, **fields: Any) -> None:
        from psycopg.types.json import Jsonb

        column_map = {
            "job_type": "job_type", "command_class": "command_class", "targets": "targets_json",
            "state": "state", "requested_at": "requested_at", "started_at": "started_at",
            "finished_at": "finished_at", "run_id": "run_id",
            "coordinator_decision": "coordinator_decision", "outcome_counts": "outcome_counts_json",
            "error_code": "error_code", "error_summary": "error_summary",
        }
        sets, values = [], []
        for key, value in fields.items():
            column = column_map.get(key)
            if column is None:
                continue
            sets.append(f"{column} = %s")
            if key in ("requested_at", "started_at", "finished_at"):
                values.append(_parse_dt(value))
            elif key in ("targets", "outcome_counts"):
                values.append(Jsonb(value) if value is not None else None)
            else:
                values.append(value)
        if not sets:
            return
        values.append(job_id)
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        f"UPDATE console_job SET {', '.join(sets)} WHERE job_id = %s",
                        values,
                    )
        except Exception as exc:
            raise EvidenceBackendError(f"postgres console job update failed: {exc}") from exc

    def get(self, job_id: str) -> dict[str, Any] | None:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(f"SELECT {', '.join(_CONSOLE_JOB_FIELDS_SQL)} FROM console_job WHERE job_id = %s", (job_id,))
                    row = cur.fetchone()
        except Exception as exc:
            raise EvidenceBackendError(f"postgres console job get failed: {exc}") from exc
        return self._row_to_record(row) if row else None

    def get_by_idempotency_key(self, idempotency_key: str) -> dict[str, Any] | None:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        f"SELECT {', '.join(_CONSOLE_JOB_FIELDS_SQL)} FROM console_job WHERE idempotency_key = %s",
                        (idempotency_key,),
                    )
                    row = cur.fetchone()
        except Exception as exc:
            raise EvidenceBackendError(f"postgres console job get-by-idempotency-key failed: {exc}") from exc
        return self._row_to_record(row) if row else None

    def list_all(self) -> list[dict[str, Any]]:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        f"SELECT {', '.join(_CONSOLE_JOB_FIELDS_SQL)} FROM console_job ORDER BY requested_at DESC"
                    )
                    rows = cur.fetchall()
        except Exception as exc:
            raise EvidenceBackendError(f"postgres console job list failed: {exc}") from exc
        return [self._row_to_record(row) for row in rows]

    def mark_orphaned_running_as_failed(self, *, error_code: str) -> list[str]:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        "UPDATE console_job SET state = 'failed', error_code = %s, finished_at = %s "
                        "WHERE state = 'running' RETURNING job_id",
                        (error_code, datetime.now(timezone.utc)),
                    )
                    rows = cur.fetchall()
        except Exception as exc:
            raise EvidenceBackendError(f"postgres console job orphan-sweep failed: {exc}") from exc
        return [row[0] for row in rows]


_CONSOLE_JOB_FIELDS_SQL = (
    "job_id", "idempotency_key", "job_type", "command_class", "targets_json", "state",
    "requested_at", "started_at", "finished_at", "run_id", "coordinator_decision",
    "outcome_counts_json", "error_code", "error_summary",
)

# 4. Scheduler state backend
# ---------------------------------------------------------------------------

class SchedulerStateBackend(abc.ABC):
    """Raw document storage only — workflow-allowlist validation stays in
    ``utils/collection_executor.py`` so it applies identically to both
    backends."""

    @abc.abstractmethod
    def load_raw(self) -> dict[str, Any] | None:
        ...

    @abc.abstractmethod
    def save_raw(self, payload: dict[str, Any]) -> None:
        ...


class FilesystemSchedulerStateBackend(SchedulerStateBackend):
    def __init__(self, path: Path) -> None:
        self.path = Path(path)

    def load_raw(self) -> dict[str, Any] | None:
        if not self.path.exists():
            return None
        raw = self.path.read_text(encoding="utf-8")
        return json.loads(raw)

    def save_raw(self, payload: dict[str, Any]) -> None:
        _write_json_atomic(self.path, payload)


_SCHEDULER_STATE_SCHEMA = (
    """
    CREATE TABLE IF NOT EXISTS scheduler_state (
        workflow          TEXT PRIMARY KEY,
        last_completed_at TIMESTAMPTZ NOT NULL
    )
    """,
)


class PostgresSchedulerStateBackend(SchedulerStateBackend):
    def __init__(self, dsn: str) -> None:
        self._psycopg = _psycopg()
        self._dsn = dsn
        self._ensure_schema()

    def _connect(self):
        return self._psycopg.connect(self._dsn, autocommit=True)

    def _ensure_schema(self) -> None:
        _ensure_schema(self._psycopg, self._dsn, _SCHEDULER_STATE_SCHEMA)

    def load_raw(self) -> dict[str, Any] | None:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute("SELECT workflow, last_completed_at FROM scheduler_state")
                    rows = cur.fetchall()
        except Exception as exc:
            raise EvidenceBackendError(f"postgres scheduler state read failed: {exc}") from exc
        if not rows:
            return None
        return {
            "version": 1,
            "last_completed_at": {workflow: ts.isoformat() for workflow, ts in rows},
        }

    def save_raw(self, payload: dict[str, Any]) -> None:
        rows = payload.get("last_completed_at") or {}
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.executemany(
                        """
                        INSERT INTO scheduler_state (workflow, last_completed_at)
                        VALUES (%s, %s)
                        ON CONFLICT (workflow) DO UPDATE SET last_completed_at = EXCLUDED.last_completed_at
                        """,
                        [(workflow, _parse_dt(value)) for workflow, value in rows.items()],
                    )
        except Exception as exc:
            raise EvidenceBackendError(f"postgres scheduler state write failed: {exc}") from exc


# ---------------------------------------------------------------------------
# 5. Operational-write ledger backend (RB.3b, decision B4)
# ---------------------------------------------------------------------------
#
# A durable per-endpoint record of every ``operational-write`` device command
# (today only ``add backup local``). The 24 h ceiling in
# ``BACKUP_RECOVERY_CONTRACTS.md`` §7.3 point 6 is a disk-safety control on a
# production firewall, so it needs state that survives a process/container
# restart and is shared across containers when Postgres is configured — exactly
# what this abstraction already gives the other four concerns.
#
# INSERT-ONLY: no code path issues UPDATE or DELETE against the row store, and
# the filesystem impl never rewrites an existing entry (§9.13 test (e)).
#
# Fail-closed read semantics (``RecoveryOperationalLedger`` in
# ``utils/recovery_operational_ledger.py`` enforces this): an *absent* store
# (missing file / empty table) is "no prior backup" and returns ``[]``; an
# *unreadable* store (corrupt JSON, I/O error, Postgres unreachable, query
# error) raises ``EvidenceBackendError`` and callers MUST NOT treat that as
# "no entries" — see the ledger module's §5 table.

_OPERATIONAL_LEDGER_SCHEMA_KEY = "securityexpert-recovery-operational-ledger-v1"


class OperationalWriteLedgerBackend(abc.ABC):
    @abc.abstractmethod
    def append(self, entry: dict[str, Any]) -> None:
        """Append one entry dict (``entity_id``, ``command_class``,
        ``executed_at`` ISO-8601, ``outcome``, ``run_id``). Never rewrites or
        removes an existing entry."""

    @abc.abstractmethod
    def entries_for(self, *, entity_id: str, command_class: str) -> list[dict[str, Any]]:
        """Every entry for the pair, newest-first by ``executed_at``. Raises
        ``EvidenceBackendError`` if the store exists but cannot be read/parsed
        — callers MUST NOT treat that as an empty list."""


class FilesystemOperationalWriteLedgerBackend(OperationalWriteLedgerBackend):
    """``<data_root>/state/recovery_operational_ledger.json`` — the same
    whole-file read → append → atomic-write pattern
    ``data/state/compliance_history.json`` uses. Entries are tiny and accrue at
    ≤ 1/endpoint/day, so whole-file writes are fine."""

    def __init__(self, state_file: Path) -> None:
        self.state_file = Path(state_file)

    def _load_all(self) -> list[dict[str, Any]]:
        if not self.state_file.exists():
            return []
        try:
            raw = json.loads(self.state_file.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise EvidenceBackendError(
                f"operational-write ledger at {self.state_file} is unreadable: {exc}"
            ) from exc
        if not isinstance(raw, dict) or not isinstance(raw.get("entries"), list):
            raise EvidenceBackendError(
                f"operational-write ledger at {self.state_file} is malformed: "
                "expected an object with an 'entries' list"
            )
        entries: list[dict[str, Any]] = []
        for item in raw["entries"]:
            if not isinstance(item, dict):
                raise EvidenceBackendError(
                    f"operational-write ledger at {self.state_file} has a non-object entry"
                )
            entries.append(item)
        return entries

    def append(self, entry: dict[str, Any]) -> None:
        entries = self._load_all()
        entries.append(dict(entry))
        _write_json_atomic(
            self.state_file,
            {
                "schema": _OPERATIONAL_LEDGER_SCHEMA_KEY,
                "updated_at": _utc_now(),
                "entries": entries,
            },
        )

    def entries_for(self, *, entity_id: str, command_class: str) -> list[dict[str, Any]]:
        matched = [
            e
            for e in self._load_all()
            if e.get("entity_id") == entity_id and e.get("command_class") == command_class
        ]
        matched.sort(key=lambda e: str(e.get("executed_at") or ""), reverse=True)
        return matched


_OPERATIONAL_LEDGER_SQL_SCHEMA = (
    """
    CREATE TABLE IF NOT EXISTS recovery_operational_write_ledger (
        id            BIGSERIAL PRIMARY KEY,
        entity_id     TEXT        NOT NULL,
        command_class TEXT        NOT NULL,
        executed_at   TIMESTAMPTZ NOT NULL,
        outcome       TEXT        NOT NULL,
        run_id        TEXT,
        created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
    )
    """,
    "CREATE INDEX IF NOT EXISTS recovery_opwrite_ledger_lookup_idx "
    "ON recovery_operational_write_ledger (entity_id, command_class, executed_at DESC)",
)


class PostgresOperationalWriteLedgerBackend(OperationalWriteLedgerBackend):
    """Insert-only ledger table. ``append`` is one INSERT; ``entries_for`` is
    one indexed ``SELECT ... ORDER BY executed_at DESC``."""

    def __init__(self, dsn: str) -> None:
        self._psycopg = _psycopg()
        self._dsn = dsn
        self._ensure_schema()

    def _connect(self):
        return self._psycopg.connect(self._dsn, autocommit=True)

    def _ensure_schema(self) -> None:
        _ensure_schema(self._psycopg, self._dsn, _OPERATIONAL_LEDGER_SQL_SCHEMA)

    def append(self, entry: dict[str, Any]) -> None:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        INSERT INTO recovery_operational_write_ledger
                            (entity_id, command_class, executed_at, outcome, run_id)
                        VALUES (%s,%s,%s,%s,%s)
                        """,
                        (
                            entry.get("entity_id"),
                            entry.get("command_class"),
                            _parse_dt(entry.get("executed_at")),
                            entry.get("outcome"),
                            entry.get("run_id"),
                        ),
                    )
        except Exception as exc:
            raise EvidenceBackendError(
                f"postgres operational-write ledger append failed: {exc}"
            ) from exc

    def entries_for(self, *, entity_id: str, command_class: str) -> list[dict[str, Any]]:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        "SELECT entity_id, command_class, executed_at, outcome, run_id "
                        "FROM recovery_operational_write_ledger "
                        "WHERE entity_id = %s AND command_class = %s "
                        "ORDER BY executed_at DESC",
                        (entity_id, command_class),
                    )
                    rows = cur.fetchall()
        except Exception as exc:
            raise EvidenceBackendError(
                f"postgres operational-write ledger read failed: {exc}"
            ) from exc
        return [
            {
                "entity_id": row[0],
                "command_class": row[1],
                "executed_at": row[2].isoformat() if row[2] else None,
                "outcome": row[3],
                "run_id": row[4],
            }
            for row in rows
        ]




# ---------------------------------------------------------------------------
# 7. Class 2 action record backend (OP.2.A/B)
# ---------------------------------------------------------------------------
#
# One durable record per ``action_id`` under ``<data_root>/state/
# ha_action_records/`` -- the same shape ``ConsoleJobBackend`` uses, minus
# its orphan sweep (an ``EXECUTING`` record left by a dead process resolves
# to ``OUTCOME_UNKNOWN``, never ``failed`` -- see the OP.2.0 contract,
# "Why this contract exists now" #3). This backend is deliberately dumb
# storage: it has no opinion about legal states, transitions, the
# operational-entity-lock uniqueness rule, or the quarantine predicate --
# those all live in ``utils/operate/store.py`` so both backends behave
# identically.

_ACTION_RECORD_SCHEMA = (
    """
    CREATE TABLE IF NOT EXISTS ha_action_record (
        action_id             TEXT PRIMARY KEY,
        operational_entity_id TEXT NOT NULL,
        state                 TEXT NOT NULL,
        created_at            TIMESTAMPTZ,
        finished_at           TIMESTAMPTZ,
        acknowledged_at       TIMESTAMPTZ,
        record_json           JSONB NOT NULL
    )
    """,
    "CREATE INDEX IF NOT EXISTS ha_action_record_entity_idx "
    "ON ha_action_record (operational_entity_id)",
)


class ActionRecordBackend(abc.ABC):
    @abc.abstractmethod
    def create(self, record: dict[str, Any]) -> None:
        """Insert a new record. A duplicate ``action_id`` raises
        ``EvidenceBackendError`` -- the caller (``ActionRecordStore``) treats
        that as a create-race against a concurrent identical request and
        re-reads rather than overwriting."""

    @abc.abstractmethod
    def update(self, action_id: str, **fields: Any) -> None:
        ...

    @abc.abstractmethod
    def get(self, action_id: str) -> dict[str, Any] | None:
        ...

    @abc.abstractmethod
    def list_by_entity(self, operational_entity_id: str) -> list[dict[str, Any]]:
        ...

    @abc.abstractmethod
    def list_all(self) -> list[dict[str, Any]]:
        ...


class FilesystemActionRecordBackend(ActionRecordBackend):
    """One JSON file per ``action_id`` under ``<root>/``."""

    def __init__(self, root: Path) -> None:
        self.root = Path(root)

    def _record_path(self, action_id: str) -> Path:
        return self.root / f"{_safe_component(action_id)}.json"

    def create(self, record: dict[str, Any]) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        path = self._record_path(record["action_id"])
        if path.exists():
            raise EvidenceBackendError(f"action record already recorded: {record['action_id']!r}")
        _write_json_atomic(path, record)

    def update(self, action_id: str, **fields: Any) -> None:
        path = self._record_path(action_id)
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise EvidenceBackendError(f"action record {action_id!r} is unreadable: {exc}") from exc
        record.update(fields)
        _write_json_atomic(path, record)

    def get(self, action_id: str) -> dict[str, Any] | None:
        path = self._record_path(action_id)
        if not path.exists():
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            return None

    def list_all(self) -> list[dict[str, Any]]:
        if not self.root.exists():
            return []
        records: list[dict[str, Any]] = []
        for child in self.root.iterdir():
            if not child.is_file() or child.suffix != ".json":
                continue
            try:
                record = json.loads(child.read_text(encoding="utf-8"))
            except (OSError, UnicodeDecodeError, json.JSONDecodeError):
                continue
            if isinstance(record, dict):
                records.append(record)
        return records

    def list_by_entity(self, operational_entity_id: str) -> list[dict[str, Any]]:
        return [r for r in self.list_all() if r.get("operational_entity_id") == operational_entity_id]


class PostgresActionRecordBackend(ActionRecordBackend):
    def __init__(self, dsn: str) -> None:
        self._psycopg = _psycopg()
        self._dsn = dsn
        self._ensure_schema()

    def _connect(self):
        return self._psycopg.connect(self._dsn, autocommit=True)

    def _ensure_schema(self) -> None:
        _ensure_schema(self._psycopg, self._dsn, _ACTION_RECORD_SCHEMA)

    def create(self, record: dict[str, Any]) -> None:
        from psycopg.errors import UniqueViolation
        from psycopg.types.json import Jsonb

        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        INSERT INTO ha_action_record (
                            action_id, operational_entity_id, state, created_at,
                            finished_at, acknowledged_at, record_json
                        ) VALUES (%s,%s,%s,%s,%s,%s,%s)
                        """,
                        (
                            record["action_id"], record["operational_entity_id"], record["state"],
                            _parse_dt(record.get("created_at")), _parse_dt(record.get("finished_at")),
                            _parse_dt(record.get("acknowledged_at")), Jsonb(record),
                        ),
                    )
        except UniqueViolation as exc:
            raise EvidenceBackendError(f"action record already recorded: {exc}") from exc
        except Exception as exc:
            raise EvidenceBackendError(f"postgres action record create failed: {exc}") from exc

    def update(self, action_id: str, **fields: Any) -> None:
        current = self.get(action_id)
        if current is None:
            raise EvidenceBackendError(f"action record {action_id!r} does not exist")
        current.update(fields)
        from psycopg.types.json import Jsonb

        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        UPDATE ha_action_record SET
                            state = %s, finished_at = %s, acknowledged_at = %s, record_json = %s
                        WHERE action_id = %s
                        """,
                        (
                            current["state"], _parse_dt(current.get("finished_at")),
                            _parse_dt(current.get("acknowledged_at")), Jsonb(current), action_id,
                        ),
                    )
        except Exception as exc:
            raise EvidenceBackendError(f"postgres action record update failed: {exc}") from exc

    def get(self, action_id: str) -> dict[str, Any] | None:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute("SELECT record_json FROM ha_action_record WHERE action_id = %s", (action_id,))
                    row = cur.fetchone()
        except Exception as exc:
            raise EvidenceBackendError(f"postgres action record get failed: {exc}") from exc
        return row[0] if row else None

    def list_by_entity(self, operational_entity_id: str) -> list[dict[str, Any]]:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        "SELECT record_json FROM ha_action_record WHERE operational_entity_id = %s",
                        (operational_entity_id,),
                    )
                    rows = cur.fetchall()
        except Exception as exc:
            raise EvidenceBackendError(f"postgres action record list-by-entity failed: {exc}") from exc
        return [row[0] for row in rows]

    def list_all(self) -> list[dict[str, Any]]:
        try:
            with self._connect() as conn:
                with conn.cursor() as cur:
                    cur.execute("SELECT record_json FROM ha_action_record")
                    rows = cur.fetchall()
        except Exception as exc:
            raise EvidenceBackendError(f"postgres action record list failed: {exc}") from exc
        return [row[0] for row in rows]


# ---------------------------------------------------------------------------
# 8. Device registry backend (PCP.1)
# ---------------------------------------------------------------------------
#
# Dumb storage only, same split as the other seven concerns: no duplicate
# detection, no lifecycle validation, no schema enforcement, no locking --
# all of that lives in utils/device_registry.py so both backends would
# behave identically. Filesystem implementation only in PCP.1
# (docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md section 21); the
# abstract interface is shaped for a later PostgreSQL implementation but none
# is written here (pcp_storage_engine open decision) -- selecting
# ``postgres`` for this concern raises rather than silently falling back.

class DeviceRegistryBackend(abc.ABC):
    @abc.abstractmethod
    def load_raw(self) -> dict[str, Any] | None:
        """The raw registry document, or ``None`` if it does not exist yet
        (the empty registry -- never an error by itself)."""

    @abc.abstractmethod
    def save_raw(self, payload: dict[str, Any]) -> None:
        ...


class FilesystemDeviceRegistryBackend(DeviceRegistryBackend):
    """``<data_root>/state/device_registry.json`` -- atomic tmp-then-replace,
    the same posture as every other ``data/state/*`` file."""

    def __init__(self, path: Path) -> None:
        self.path = Path(path)

    def load_raw(self) -> dict[str, Any] | None:
        if not self.path.exists():
            return None
        try:
            raw = self.path.read_text(encoding="utf-8")
        except OSError as exc:
            raise EvidenceBackendError(f"device registry at {self.path} is unreadable: {exc}") from exc
        try:
            return json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise EvidenceBackendError(f"device registry at {self.path} is not valid JSON: {exc}") from exc

    def save_raw(self, payload: dict[str, Any]) -> None:
        _write_json_atomic(self.path, payload)


# ---------------------------------------------------------------------------
# Backend selection
# ---------------------------------------------------------------------------

ENV_BACKEND = "SECURITYEXPERT_EVIDENCE_BACKEND"
ENV_DSN = "SECURITYEXPERT_EVIDENCE_POSTGRES_DSN"


def active_evidence_backend_kind() -> str:
    """Return the configured backend kind without connecting to it.

    Used by ``utils/config_storage.py`` to decide whether its filesystem-only
    legacy-payload dedup/analysis tools apply at all (DEV.3.3 contract,
    "Explicitly out of scope").
    """
    return (os.environ.get(ENV_BACKEND) or "filesystem").strip().lower()


def _require_dsn() -> str:
    dsn = os.environ.get(ENV_DSN)
    if not dsn:
        raise EvidenceBackendError(
            f"{ENV_BACKEND}=postgres requires {ENV_DSN} to be set."
        )
    return dsn


def verify_evidence_backend_ready() -> None:
    """Fail-closed startup preflight for the evidence backend.

    No-op on the filesystem default (and never imports the Postgres driver
    there). On ``postgres`` it proves the DSN is set, the instance is
    reachable, and every table this build needs exists — so a misconfigured
    deployment fails at startup instead of part-way through a collection run
    with evidence already half-written.

    Unlike the coordinator backend's preflight (DEV.3.2, D6) this does not
    need to detect a transaction-pooling proxy: nothing here holds a lock
    across a connection's idle time, so pooled connections are safe.
    """
    if active_evidence_backend_kind() != "postgres":
        return
    dsn = _require_dsn()
    try:
        PostgresConfigSnapshotBackend(dsn)
        PostgresRunManifestBackend(dsn)
        PostgresLastKnownGoodBackend(dsn)
        PostgresSchedulerStateBackend(dsn)
        PostgresOperationalWriteLedgerBackend(dsn)
        PostgresConsoleJobBackend(dsn)
        PostgresActionRecordBackend(dsn)
    except EvidenceBackendError:
        raise
    except Exception as exc:
        raise EvidenceBackendError(f"evidence backend preflight could not run: {exc}") from exc


def select_config_snapshot_backend(*, root: Path) -> ConfigSnapshotBackend:
    kind = active_evidence_backend_kind()
    if kind == "postgres":
        return PostgresConfigSnapshotBackend(_require_dsn())
    if kind not in ("filesystem", ""):
        raise EvidenceBackendError(f"Unsupported {ENV_BACKEND}: {kind!r}")
    return FilesystemConfigSnapshotBackend(root)


def select_run_manifest_backend() -> RunManifestBackend:
    kind = active_evidence_backend_kind()
    if kind == "postgres":
        return PostgresRunManifestBackend(_require_dsn())
    if kind not in ("filesystem", ""):
        raise EvidenceBackendError(f"Unsupported {ENV_BACKEND}: {kind!r}")
    return FilesystemRunManifestBackend()


def select_last_known_good_backend(*, state_file: Path) -> LastKnownGoodBackend:
    kind = active_evidence_backend_kind()
    if kind == "postgres":
        return PostgresLastKnownGoodBackend(_require_dsn())
    if kind not in ("filesystem", ""):
        raise EvidenceBackendError(f"Unsupported {ENV_BACKEND}: {kind!r}")
    return FilesystemLastKnownGoodBackend(state_file)


def select_scheduler_state_backend(*, path: Path) -> SchedulerStateBackend:
    kind = active_evidence_backend_kind()
    if kind == "postgres":
        return PostgresSchedulerStateBackend(_require_dsn())
    if kind not in ("filesystem", ""):
        raise EvidenceBackendError(f"Unsupported {ENV_BACKEND}: {kind!r}")
    return FilesystemSchedulerStateBackend(path)


def select_operational_write_ledger_backend(*, state_file: Path) -> OperationalWriteLedgerBackend:
    kind = active_evidence_backend_kind()
    if kind == "postgres":
        return PostgresOperationalWriteLedgerBackend(_require_dsn())
    if kind not in ("filesystem", ""):
        raise EvidenceBackendError(f"Unsupported {ENV_BACKEND}: {kind!r}")
    return FilesystemOperationalWriteLedgerBackend(state_file)


def select_console_job_backend(*, root: Path) -> ConsoleJobBackend:
    kind = active_evidence_backend_kind()
    if kind == "postgres":
        return PostgresConsoleJobBackend(_require_dsn())
    if kind not in ("filesystem", ""):
        raise EvidenceBackendError(f"Unsupported {ENV_BACKEND}: {kind!r}")
    return FilesystemConsoleJobBackend(root)


def select_action_record_backend(*, root: Path) -> ActionRecordBackend:
    kind = active_evidence_backend_kind()
    if kind == "postgres":
        return PostgresActionRecordBackend(_require_dsn())
    if kind not in ("filesystem", ""):
        raise EvidenceBackendError(f"Unsupported {ENV_BACKEND}: {kind!r}")
    return FilesystemActionRecordBackend(root)


def select_device_registry_backend(*, path: Path) -> DeviceRegistryBackend:
    kind = active_evidence_backend_kind()
    if kind == "postgres":
        raise EvidenceBackendError(
            f"{ENV_BACKEND}=postgres has no DeviceRegistryBackend implementation in PCP.1 "
            "(pcp_storage_engine is still an open decision) -- use the filesystem default"
        )
    if kind not in ("filesystem", ""):
        raise EvidenceBackendError(f"Unsupported {ENV_BACKEND}: {kind!r}")
    return FilesystemDeviceRegistryBackend(path)
