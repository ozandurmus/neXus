"""M4 — local control-plane metadata store (approved Option A).

The frozen contract is `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md`
§6.4 (ownership boundary) and §6.5 (engine contract), acceptance criteria
`AC-ST-1`…`AC-ST-8`; the capability-projection persistence boundary is
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` §8.2.1 and
`AC-CS-70`…`AC-CS-73` / `AC-CS-97`.

**Storage infrastructure only.** This module creates and validates the store
and owns its schema, migrations and fail-closed outcomes. It implements no
job behaviour, no target resolution, no runner, no scheduling, no enrollment
and no HTTP/UI surface — those are `M5`…`M12` and are deliberately absent.

What this store may own (§6.4, `AC-ST-2`): durable job definitions, job/run
lifecycle records, schedules, capability projections, idempotency/submission
metadata, control-plane runtime metadata.

What it must never own (§6.4, `AC-ST-3`): Device Registry rows, copied
endpoints or fallback endpoint authority, credential payloads, trust secrets,
raw configuration, backup bytes, CAS evidence objects, `OP.2` action
authority, or resolved presentation state as durable truth. The `PCP.1`
Device Registry stays on its frozen filesystem JSON backend
(`utils/device_registry.py`) and is not migrated, mirrored or read here.

A target is stored only as an **opaque** registry/logical-entity reference
(`target_ref`). The endpoint is never copied: the registry stays authoritative
at admission and again immediately before execution (`AC-ST-4`/`AC-ST-5`), so
a reference that no longer resolves is a refusal, never a cache hit.

**SQLite is not the production engine.** `pcp_storage_engine` remains an open,
production-scoped decision (`AC-ST-7`). Runtime auto-creation here is a
pre-production local convenience only; a production deployment applies
migrations through a deployment-controlled step (§6.5, `DEV.4.6`).

Privacy: the database and its `-wal`/`-shm` sidecars are LOCAL-SENSITIVE
(CLASS 2, `PRIVACY_AND_DATA_HANDLING.md`). They live under
``<data_root>/state/`` — outside ``data_root/runs/<run_id>``, the only subtree
`utils/support_bundle.run_support_bundle` enumerates — so they are excluded
from the support bundle by construction, exactly like the Device Registry and
its lock file. `.db`/`.db-wal`/`.db-shm` are already `DATABASE_ARTIFACT` in
`utils/repository_privacy.py` and already ignored by `.gitignore`.
"""
from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from utils.runtime_paths import discover_repository_root

#: The database file, under ``<data_root>/state/``. The ``.db`` suffix is
#: deliberate: `utils/repository_privacy.py` already classifies ``.db``,
#: ``.db-wal`` and ``.db-shm`` as ``DATABASE_ARTIFACT``, and `.gitignore`
#: already carries all three — so the existing privacy mechanisms cover this
#: store without a parallel system.
STORE_FILENAME = "control_plane.db"

#: Highest schema version this build understands. A database carrying a
#: higher version is refused (§6.5 "never auto-upgrade downward").
SUPPORTED_SCHEMA_VERSION = 1

#: The frozen console job lifecycle (`console/jobs.py`, `CON.2`; bound as `X1`
#: by the `M3` contract, which may add no member). Declared here as a literal
#: rather than imported so that `utils/` keeps no dependency on `console/`;
#: `tests/test_m4_control_plane_metadata_store.py` asserts the two agree, so a
#: competing lifecycle cannot drift in unnoticed.
JOB_LIFECYCLE_STATES = ("queued", "running", "succeeded", "failed", "blocked", "skipped")

#: Non-terminal states. "At most one active run per durable job definition"
#: (§6.5 uniqueness) is enforced over exactly these.
ACTIVE_JOB_STATES = ("queued", "running")

#: WAL needs shared memory, which is unsafe on SMB/NFS (§6.5 "local filesystem
#: only"). A UNC path is refused rather than silently opened.
_UNC_PREFIXES = ("\\\\", "//")


class ControlPlaneStoreError(RuntimeError):
    """Base fail-closed error for the local control-plane metadata store.

    Every failure below is a refusal. The store never auto-repairs, silently
    recreates, downgrades or discards state (§6.5).
    """


class ControlPlaneStorePlacementError(ControlPlaneStoreError):
    """Invalid storage placement: inside the repository, inside ``output_root``,
    or on a non-local filesystem."""


class ControlPlaneSchemaVersionError(ControlPlaneStoreError):
    """The database carries a schema version this build does not support.

    Names both the encountered and the supported version, and refuses. Never
    an auto-upgrade, never a downgrade, never a recreate.
    """


class ControlPlaneCorruptionError(ControlPlaneStoreError):
    """The file is corrupt or is not a database at all.

    Mirrors `utils/device_registry.py`'s whole-document posture: the store
    fails closed as a whole and is never repaired or recreated in place.
    """


class ControlPlaneMigrationError(ControlPlaneStoreError):
    """A migration failed. Its transaction is rolled back, so the database is
    left at the last version that applied cleanly."""


class ControlPlaneContentionError(ControlPlaneStoreError):
    """The bounded ``busy_timeout`` elapsed while waiting for the writer.

    Contention waits, then fails closed — never an unbounded block (§6.5).
    """


class ControlPlaneIntegrityError(ControlPlaneStoreError):
    """A uniqueness or foreign-key constraint refused the write.

    Covers the two named invariants: one idempotency key per job submission,
    and at most one active run per durable job definition.
    """


@dataclass(frozen=True)
class SqliteRuntimeSettings:
    """The settings actually in force on an open connection, read back from
    the engine rather than assumed."""

    journal_mode: str
    synchronous: int
    foreign_keys: int
    busy_timeout_ms: int


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


# ---------------------------------------------------------------------------
# Migrations — ordered, monotonic, each applied in its own transaction
# ---------------------------------------------------------------------------
#
# Every table is STRICT (SQLite 3.37+; 3.45.3 is the recorded local version).
# Column vocabulary is deliberately narrow: there is no endpoint, address,
# host, credential, secret, token, trust, raw-configuration, backup, CAS or
# authorization column anywhere, and none may be added — the schema-shape test
# fails the build if one appears.

_MIGRATION_1_STATEMENTS = (
    # Control-plane runtime metadata (§6.4). Opaque key/value; no secrets.
    """
    CREATE TABLE control_plane_metadata (
        key             TEXT NOT NULL PRIMARY KEY,
        value           TEXT NOT NULL,
        updated_at_utc  TEXT NOT NULL
    ) STRICT
    """,
    # Durable job definitions (§6.4 "where they become durable data rather
    # than source constants"). `target_ref` is an OPAQUE registry device_id or
    # canonical logical-entity id — never an endpoint, address or command
    # (AC-TGT-1, AC-ST-5).
    """
    CREATE TABLE job_definitions (
        definition_id   TEXT NOT NULL PRIMARY KEY,
        job_type        TEXT NOT NULL,
        target_ref      TEXT,
        target_ref_kind TEXT,
        enabled         INTEGER NOT NULL DEFAULT 1,
        created_at_utc  TEXT NOT NULL,
        CHECK (enabled IN (0, 1)),
        CHECK (target_ref_kind IS NULL
               OR target_ref_kind IN ('device_id', 'logical_entity_id'))
    ) STRICT
    """,
    # Idempotency / submission metadata (§6.4). The idempotency key IS the
    # primary key, so "unique idempotency key per job submission" is a
    # structural property rather than a checked convention. No third id is
    # invented: `console/jobs.py` already has job_id + idempotency_key.
    """
    CREATE TABLE job_submissions (
        idempotency_key TEXT NOT NULL PRIMARY KEY,
        definition_id   TEXT NOT NULL REFERENCES job_definitions(definition_id),
        provenance      TEXT NOT NULL,
        submitted_at_utc TEXT NOT NULL,
        CHECK (provenance IN ('manual', 'scheduled', 'console', 'system'))
    ) STRICT
    """,
    # Job / run lifecycle records (§6.4), using the frozen console vocabulary
    # (X1) verbatim — this store adds no member to it.
    """
    CREATE TABLE job_runs (
        run_id          TEXT NOT NULL PRIMARY KEY,
        definition_id   TEXT NOT NULL REFERENCES job_definitions(definition_id),
        idempotency_key TEXT REFERENCES job_submissions(idempotency_key),
        state           TEXT NOT NULL,
        requested_at_utc TEXT NOT NULL,
        started_at_utc  TEXT,
        finished_at_utc TEXT,
        error_code      TEXT,
        CHECK (state IN ('queued', 'running', 'succeeded', 'failed', 'blocked', 'skipped'))
    ) STRICT
    """,
    # "No more than one active run per durable job definition" (§6.5). A
    # partial unique index makes it an engine invariant, not a race-prone
    # read-then-write check.
    """
    CREATE UNIQUE INDEX ux_job_runs_one_active_per_definition
        ON job_runs (definition_id)
        WHERE state IN ('queued', 'running')
    """,
    # Schedules (§6.4). A schedule is durable product state, never a UI
    # preference. Cadence policy (the >= 10 min floor, default-disabled
    # posture) belongs to M12 and is deliberately not decided here.
    """
    CREATE TABLE schedules (
        schedule_id     TEXT NOT NULL PRIMARY KEY,
        definition_id   TEXT NOT NULL REFERENCES job_definitions(definition_id),
        interval_seconds INTEGER,
        enabled         INTEGER NOT NULL DEFAULT 0,
        created_at_utc  TEXT NOT NULL,
        CHECK (enabled IN (0, 1)),
        CHECK (interval_seconds IS NULL OR interval_seconds > 0)
    ) STRICT
    """,
    # Capability projections (§6.4; M3 §8.2.1 / AC-CS-71 / AC-CS-97).
    #
    # Holds ONLY: capability-domain dimension values D2..D6, basis/run
    # information, semantic identity (canonical subject, exact capability,
    # vendor/platform/entity kind) and producer/support-rule context.
    #
    # It deliberately has NO column for primary_status, capability_qualifiers,
    # evidence_presentation, shell composition, action_affordance, action
    # declarations, taxonomy class or authorization state — a projection
    # carrying those would silently become an authorization or admission
    # record (§8.2.1).
    #
    # `authority_generations` retains EVERY consumed authority generation and
    # `producer_version` / `support_rule_version` the producer and
    # support-rule versions, so a reuse attempt can be affirmatively validated
    # (AC-CS-71). `identity_mapping_proven` is 0 wherever the canonical join
    # would need an unproven translation, which forbids reuse across that
    # mapping (AC-CS-97, D6e).
    """
    CREATE TABLE capability_projections (
        projection_id        TEXT NOT NULL PRIMARY KEY,
        subject_ref          TEXT NOT NULL,
        subject_ref_kind     TEXT NOT NULL,
        capability           TEXT NOT NULL,
        vendor               TEXT NOT NULL,
        platform             TEXT NOT NULL,
        entity_kind          TEXT NOT NULL,
        dimension_values     TEXT NOT NULL,
        basis_run_ref        TEXT,
        producer_version     TEXT NOT NULL,
        support_rule_version TEXT NOT NULL,
        authority_generations TEXT NOT NULL,
        identity_mapping_proven INTEGER NOT NULL,
        computed_at_utc      TEXT NOT NULL,
        CHECK (identity_mapping_proven IN (0, 1)),
        CHECK (subject_ref_kind IN ('device_id', 'logical_entity_id')),
        UNIQUE (subject_ref, capability, vendor, platform, entity_kind)
    ) STRICT
    """,
)

#: ``(version, name, statements)``, strictly ascending and applied in order.
MIGRATIONS: tuple[tuple[int, str, tuple[str, ...]], ...] = (
    (1, "initial_control_plane_metadata", _MIGRATION_1_STATEMENTS),
)


def _assert_monotonic(migrations: tuple[tuple[int, str, tuple[str, ...]], ...]) -> None:
    versions = [version for version, _, _ in migrations]
    if versions != sorted(set(versions)):
        raise ControlPlaneMigrationError(
            f"migrations must be strictly ascending and unique, got {versions}"
        )
    if versions and versions[-1] != SUPPORTED_SCHEMA_VERSION:
        raise ControlPlaneMigrationError(
            f"highest migration {versions[-1]} does not match "
            f"SUPPORTED_SCHEMA_VERSION {SUPPORTED_SCHEMA_VERSION}"
        )


_assert_monotonic(MIGRATIONS)


# ---------------------------------------------------------------------------
# Placement
# ---------------------------------------------------------------------------

def _assert_valid_placement(data_root: Path) -> Path:
    """RuntimeRoot only: never the repository, never inside it, never
    ``output_root``, never a network share (§6.5).

    Mirrors `utils/device_registry._assert_outside_repository`; `runtime_paths`
    exposes no shared public helper, so each consumer states its own rule.
    """
    raw = str(data_root)
    if raw.startswith(_UNC_PREFIXES):
        raise ControlPlaneStorePlacementError(
            "control-plane store data_root must be on a local filesystem: WAL requires "
            f"shared memory and is unsafe on SMB/NFS -- refusing network path {raw!r}"
        )

    resolved = Path(data_root).expanduser().resolve()
    repo_root = discover_repository_root().resolve()

    if resolved == repo_root:
        raise ControlPlaneStorePlacementError(
            "control-plane store data_root must not be the repository root"
        )
    try:
        resolved.relative_to(repo_root)
    except ValueError:
        pass
    else:
        raise ControlPlaneStorePlacementError(
            "control-plane store data_root must not be inside the repository root"
        )

    # RuntimePaths lays out `<runtime_root>/data` beside `<runtime_root>/output`.
    # Pointing the store at the sibling output tree is a placement error.
    output_root = (resolved.parent / "output").resolve()
    if resolved == output_root:
        raise ControlPlaneStorePlacementError(
            "control-plane store data_root must not be output_root"
        )
    try:
        resolved.relative_to(output_root)
    except ValueError:
        return resolved
    raise ControlPlaneStorePlacementError(
        "control-plane store data_root must not be inside output_root"
    )


def store_path(data_root: Path) -> Path:
    """The database path for a ``data_root``, without opening anything."""
    return Path(data_root) / "state" / STORE_FILENAME


def sidecar_paths(data_root: Path) -> tuple[Path, Path]:
    """The WAL and shared-memory sidecars, which share the store's
    LOCAL-SENSITIVE classification and bundle exclusion."""
    base = store_path(data_root)
    return (
        base.with_name(base.name + "-wal"),
        base.with_name(base.name + "-shm"),
    )


# ---------------------------------------------------------------------------
# The store
# ---------------------------------------------------------------------------

class ControlPlaneStore:
    """One owning process, one writer connection, concurrent readers.

    The connection is owned by the instance and closed deterministically by
    :meth:`close` or the context manager. There is no module-level or
    process-global connection, and the class holds no class-level state, so a
    test gets a fully isolated store from a temporary ``data_root``.
    """

    def __init__(
        self,
        data_root: Path,
        *,
        create: bool = True,
        busy_timeout_ms: int = 5000,
    ) -> None:
        self._data_root = _assert_valid_placement(data_root)
        self._path = store_path(self._data_root)
        self._busy_timeout_ms = int(busy_timeout_ms)
        self._connection: sqlite3.Connection | None = None
        self._closed = False

        if not self._path.exists() and not create:
            raise ControlPlaneStoreError(
                f"control-plane store does not exist at {self._path} and create=False"
            )
        if create:
            self._path.parent.mkdir(parents=True, exist_ok=True)

        self._connection = self._open_writer()
        try:
            self._probe_readable(self._connection)
            self._apply_migrations(self._connection)
        except Exception:
            # A store that failed to open safely never stays half-open.
            self._connection.close()
            self._connection = None
            self._closed = True
            raise

    # -- connection -------------------------------------------------------

    def _open_writer(self) -> sqlite3.Connection:
        # `isolation_level=None` hands transaction control to this module, so
        # every migration and every write is an explicit, auditable
        # transaction rather than an implicit one.
        connection = sqlite3.connect(
            str(self._path),
            timeout=self._busy_timeout_ms / 1000.0,
            isolation_level=None,
        )
        connection.row_factory = sqlite3.Row
        self._apply_pragmas(connection, read_only=False)
        return connection

    def _apply_pragmas(self, connection: sqlite3.Connection, *, read_only: bool) -> None:
        connection.execute(f"PRAGMA busy_timeout = {self._busy_timeout_ms}")
        connection.execute("PRAGMA foreign_keys = ON")
        if read_only:
            return

        try:
            mode = connection.execute("PRAGMA journal_mode = WAL").fetchone()[0]
        except sqlite3.DatabaseError as exc:
            raise self._classify(exc) from exc
        if str(mode).lower() != "wal":
            # WAL is refused on filesystems without shared memory. Fail closed
            # rather than silently running in a weaker journal mode.
            raise ControlPlaneStorePlacementError(
                f"WAL journal mode was refused (engine reported {mode!r}) for {self._path}: "
                "the control-plane store requires a local filesystem (SMB/NFS is unsupported)"
            )

        # synchronous=FULL, chosen deliberately over the usual WAL default of
        # NORMAL. Under WAL, NORMAL does not fsync at commit, so recently
        # committed records can be lost on power loss or a kernel panic --
        # database integrity survives, but a *committed* record may not.
        # `CON.0` §7.9 requires a job record to be durable BEFORE the runner
        # may start it, so trading that guarantee away is not available to us.
        # The cost is one fsync per commit on a local, low-write control plane,
        # which is negligible here.
        connection.execute("PRAGMA synchronous = FULL")

    def _probe_readable(self, connection: sqlite3.Connection) -> None:
        """Force the engine to touch the file so corruption surfaces at open.

        ``sqlite3.connect`` is lazy: a garbage file opens without error and
        fails on first use. The probe makes "is this a database?" an
        open-time question with a typed answer.
        """
        try:
            connection.execute("SELECT count(*) FROM sqlite_schema").fetchone()
        except sqlite3.DatabaseError as exc:
            raise self._classify(exc) from exc

    @staticmethod
    def _classify(exc: sqlite3.Error) -> ControlPlaneStoreError:
        """Map an engine error onto this module's typed, fail-closed outcomes."""
        text = str(exc).lower()
        if "not a database" in text or "malformed" in text or "corrupt" in text:
            return ControlPlaneCorruptionError(
                f"control-plane store is corrupt or is not a database: {exc}. "
                "Refusing to repair, recreate or discard it -- a human must "
                "inspect and remove or restore the file."
            )
        if "locked" in text or "busy" in text:
            return ControlPlaneContentionError(
                f"control-plane store is busy and the bounded busy_timeout elapsed: {exc}"
            )
        if isinstance(exc, sqlite3.IntegrityError):
            return ControlPlaneIntegrityError(str(exc))
        return ControlPlaneStoreError(str(exc))

    @property
    def connection(self) -> sqlite3.Connection:
        if self._connection is None or self._closed:
            raise ControlPlaneStoreError("control-plane store connection is closed")
        return self._connection

    @property
    def path(self) -> Path:
        return self._path

    def close(self) -> None:
        if self._connection is not None:
            self._connection.close()
            self._connection = None
        self._closed = True

    def __enter__(self) -> "ControlPlaneStore":
        return self

    def __exit__(self, *_exc: Any) -> None:
        self.close()

    # -- migrations -------------------------------------------------------

    def _current_version(self, connection: sqlite3.Connection) -> int:
        row = connection.execute(
            "SELECT name FROM sqlite_schema WHERE type='table' AND name='schema_migrations'"
        ).fetchone()
        if row is None:
            return 0
        applied = connection.execute("SELECT max(version) FROM schema_migrations").fetchone()[0]
        return int(applied or 0)

    def _apply_migrations(self, connection: sqlite3.Connection) -> None:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version        INTEGER NOT NULL PRIMARY KEY,
                name           TEXT NOT NULL,
                applied_at_utc TEXT NOT NULL
            ) STRICT
            """
        )

        current = self._current_version(connection)
        if current > SUPPORTED_SCHEMA_VERSION:
            raise ControlPlaneSchemaVersionError(
                f"control-plane store at {self._path} is at schema version {current}, but this "
                f"build supports at most {SUPPORTED_SCHEMA_VERSION}. Refusing to open: a newer "
                "schema is never auto-upgraded downward, migrated backwards or recreated. "
                f"Encountered version: {current}. Supported versions: "
                f"1..{SUPPORTED_SCHEMA_VERSION}."
            )

        for version, name, statements in MIGRATIONS:
            if version <= current:
                continue
            # One transaction per migration: a failure rolls its own version
            # back completely and leaves every earlier version applied.
            connection.execute("BEGIN IMMEDIATE")
            try:
                for statement in statements:
                    connection.execute(statement)
                connection.execute(
                    "INSERT INTO schema_migrations (version, name, applied_at_utc) "
                    "VALUES (?, ?, ?)",
                    (version, name, _utc_now()),
                )
            except sqlite3.Error as exc:
                connection.execute("ROLLBACK")
                raise ControlPlaneMigrationError(
                    f"migration {version} ({name}) failed and was rolled back: {exc}"
                ) from exc
            connection.execute("COMMIT")

    # -- introspection ----------------------------------------------------

    def schema_version(self) -> int:
        return self._current_version(self.connection)

    def applied_migrations(self) -> list[tuple[int, str]]:
        rows = self.connection.execute(
            "SELECT version, name FROM schema_migrations ORDER BY version"
        ).fetchall()
        return [(int(row["version"]), str(row["name"])) for row in rows]

    def runtime_settings(self) -> SqliteRuntimeSettings:
        """Read the settings back from the engine rather than restating them."""
        connection = self.connection
        return SqliteRuntimeSettings(
            journal_mode=str(connection.execute("PRAGMA journal_mode").fetchone()[0]).lower(),
            synchronous=int(connection.execute("PRAGMA synchronous").fetchone()[0]),
            foreign_keys=int(connection.execute("PRAGMA foreign_keys").fetchone()[0]),
            busy_timeout_ms=int(connection.execute("PRAGMA busy_timeout").fetchone()[0]),
        )

    def open_reader(self) -> sqlite3.Connection:
        """A separate read-only connection.

        WAL allows readers to run concurrently with the single writer. The
        caller owns and closes what it opens; the store keeps no registry of
        reader connections and shares none between callers.
        """
        connection = sqlite3.connect(
            f"file:{self._path.as_posix()}?mode=ro",
            uri=True,
            timeout=self._busy_timeout_ms / 1000.0,
            isolation_level=None,
        )
        connection.row_factory = sqlite3.Row
        self._apply_pragmas(connection, read_only=True)
        return connection

    # -- writes -----------------------------------------------------------

    def transaction(self) -> "_Transaction":
        """An explicit write transaction that rolls back on any exception."""
        return _Transaction(self)

    def execute(self, statement: str, parameters: tuple = ()) -> sqlite3.Cursor:
        """A single statement in its own transaction, with typed failures."""
        with self.transaction() as cursor:
            return cursor.execute(statement, parameters)


class _Transaction:
    def __init__(self, store: ControlPlaneStore) -> None:
        self._store = store

    def __enter__(self) -> sqlite3.Cursor:
        connection = self._store.connection
        try:
            connection.execute("BEGIN IMMEDIATE")
        except sqlite3.Error as exc:
            raise ControlPlaneStore._classify(exc) from exc
        return connection.cursor()

    def __exit__(self, exc_type, exc, _tb) -> bool:
        connection = self._store.connection
        if exc_type is not None:
            connection.execute("ROLLBACK")
            if isinstance(exc, sqlite3.Error):
                raise ControlPlaneStore._classify(exc) from exc
            return False
        try:
            connection.execute("COMMIT")
        except sqlite3.Error as commit_exc:
            connection.execute("ROLLBACK")
            raise ControlPlaneStore._classify(commit_exc) from commit_exc
        return False


def iter_schema_columns(connection: sqlite3.Connection) -> Iterator[tuple[str, str]]:
    """``(table, column)`` for every table in the store.

    Exists so the forbidden-ownership boundary (§6.4, `AC-ST-3`) can be proven
    against the real schema instead of a hand-maintained list.
    """
    tables = connection.execute(
        "SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    ).fetchall()
    for table_row in tables:
        table = str(table_row[0])
        for column_row in connection.execute(f"PRAGMA table_info({table})").fetchall():
            yield table, str(column_row[1])
