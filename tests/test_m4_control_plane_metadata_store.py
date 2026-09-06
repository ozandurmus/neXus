"""M4 — local control-plane metadata store (approved Option A).

Proves the frozen storage contract:
`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §6.4/§6.5 and
`AC-ST-1`…`AC-ST-8`, plus the capability-projection persistence boundary in
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` §8.2.1 /
`AC-CS-70`…`AC-CS-73` / `AC-CS-97`.

Storage infrastructure only: there is no job behaviour, target resolution,
runner, scheduler, enrollment or device contact here, and none is asserted.

Every test builds its own temporary ``data_root`` (`tmp_path` is
function-scoped), so no test shares a database, a connection or a
RuntimeRoot — §6.5 "a temp ``data_root`` per test; no shared global
connection".
"""
from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path

import pytest

from utils.control_plane_store import (
    ACTIVE_JOB_STATES,
    JOB_LIFECYCLE_STATES,
    MIGRATIONS,
    STORE_FILENAME,
    SUPPORTED_SCHEMA_VERSION,
    ControlPlaneContentionError,
    ControlPlaneCorruptionError,
    ControlPlaneIntegrityError,
    ControlPlaneMigrationError,
    ControlPlaneSchemaVersionError,
    ControlPlaneStore,
    ControlPlaneStoreError,
    ControlPlaneStorePlacementError,
    iter_schema_columns,
    sidecar_paths,
    store_path,
)

pytestmark = pytest.mark.runtime_platform

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def data_root(tmp_path):
    """One temporary RuntimeRoot ``data_root`` per test (§6.5 test isolation)."""
    return tmp_path / "runtime" / "data"


@pytest.fixture
def store(data_root):
    with ControlPlaneStore(data_root) as opened:
        yield opened


def _seed_definition(store: ControlPlaneStore, definition_id: str = "def-1") -> str:
    store.execute(
        "INSERT INTO job_definitions (definition_id, job_type, created_at_utc) VALUES (?, ?, ?)",
        (definition_id, "cp_inventory_refresh", "2026-09-06T00:00:00+00:00"),
    )
    return definition_id


# ---------------------------------------------------------------------------
# AC-ST-6 — RuntimeRoot placement and test isolation
# ---------------------------------------------------------------------------

def test_store_lives_under_data_root_state(store, data_root):
    assert store.path == data_root / "state" / STORE_FILENAME
    assert store.path.exists()


def test_store_is_never_placed_in_the_repository():
    with pytest.raises(ControlPlaneStorePlacementError):
        ControlPlaneStore(ROOT / "data")


def test_store_is_never_placed_at_the_repository_root():
    with pytest.raises(ControlPlaneStorePlacementError):
        ControlPlaneStore(ROOT)


def test_store_is_never_placed_in_output_root(tmp_path):
    with pytest.raises(ControlPlaneStorePlacementError):
        ControlPlaneStore(tmp_path / "runtime" / "output")


def test_network_share_is_refused_because_wal_needs_shared_memory():
    with pytest.raises(ControlPlaneStorePlacementError) as excinfo:
        ControlPlaneStore(Path(r"\\fileserver\share\data"))
    assert "local filesystem" in str(excinfo.value)


def test_two_stores_from_two_temp_roots_share_no_state(tmp_path):
    first_root = tmp_path / "a" / "data"
    second_root = tmp_path / "b" / "data"
    with ControlPlaneStore(first_root) as first, ControlPlaneStore(second_root) as second:
        _seed_definition(first, "only-in-first")
        assert first.path != second.path
        rows = second.connection.execute("SELECT count(*) FROM job_definitions").fetchone()[0]
        assert rows == 0


def test_no_module_level_connection_is_shared():
    """§6.5 "no shared global connection" — the module holds no connection."""
    import utils.control_plane_store as module

    globals_holding_connections = [
        name
        for name, value in vars(module).items()
        if isinstance(value, sqlite3.Connection)
    ]
    assert globals_holding_connections == []


# ---------------------------------------------------------------------------
# §6.5 — SQLite runtime settings, read back from the engine
# ---------------------------------------------------------------------------

def test_wal_foreign_keys_synchronous_and_busy_timeout_are_in_force(store):
    settings = store.runtime_settings()
    assert settings.journal_mode == "wal"
    assert settings.foreign_keys == 1
    assert settings.busy_timeout_ms == 5000
    # synchronous=FULL (2), deliberately stronger than WAL's usual NORMAL (1):
    # CON.0 section 7.9 requires a job record to be durable before the runner
    # may start it, and NORMAL can lose a committed record on power loss.
    assert settings.synchronous == 2


def test_busy_timeout_is_bounded_and_configurable(data_root):
    with ControlPlaneStore(data_root, busy_timeout_ms=250) as store:
        assert store.runtime_settings().busy_timeout_ms == 250


def test_every_table_is_strict(store):
    rows = store.connection.execute(
        "SELECT name, sql FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    ).fetchall()
    assert rows, "expected the initial schema to create tables"
    for row in rows:
        assert "STRICT" in str(row["sql"]).upper(), f"table {row['name']} is not STRICT"


def test_strict_typing_is_actually_enforced(store):
    _seed_definition(store)
    with pytest.raises(ControlPlaneStoreError):
        store.execute(
            "INSERT INTO schedules (schedule_id, definition_id, interval_seconds, created_at_utc) "
            "VALUES (?, ?, ?, ?)",
            ("sch-1", "def-1", "not-an-integer", "2026-09-06T00:00:00+00:00"),
        )


def test_foreign_keys_are_enforced(store):
    with pytest.raises(ControlPlaneIntegrityError):
        store.execute(
            "INSERT INTO job_runs (run_id, definition_id, state, requested_at_utc) "
            "VALUES (?, ?, ?, ?)",
            ("run-1", "no-such-definition", "queued", "2026-09-06T00:00:00+00:00"),
        )


# ---------------------------------------------------------------------------
# §6.5 — creation, monotonic migrations, rollback, version refusal
# ---------------------------------------------------------------------------

def test_creation_applies_the_initial_schema_version(store):
    assert store.schema_version() == SUPPORTED_SCHEMA_VERSION
    assert store.applied_migrations() == [(1, "initial_control_plane_metadata")]


def test_migrations_are_strictly_ascending_and_unique():
    versions = [version for version, _, _ in MIGRATIONS]
    assert versions == sorted(set(versions))
    assert versions[-1] == SUPPORTED_SCHEMA_VERSION


def test_reopening_an_existing_store_is_idempotent(data_root):
    with ControlPlaneStore(data_root) as first:
        _seed_definition(first)
        first_applied = first.applied_migrations()
    with ControlPlaneStore(data_root) as second:
        assert second.applied_migrations() == first_applied
        assert second.connection.execute(
            "SELECT count(*) FROM job_definitions"
        ).fetchone()[0] == 1


def test_a_newer_schema_version_is_refused_and_both_versions_are_named(data_root):
    with ControlPlaneStore(data_root) as store:
        store.execute(
            "INSERT INTO schema_migrations (version, name, applied_at_utc) VALUES (?, ?, ?)",
            (SUPPORTED_SCHEMA_VERSION + 5, "from_a_newer_build", "2026-09-06T00:00:00+00:00"),
        )

    with pytest.raises(ControlPlaneSchemaVersionError) as excinfo:
        ControlPlaneStore(data_root)

    message = str(excinfo.value)
    assert str(SUPPORTED_SCHEMA_VERSION + 5) in message
    assert str(SUPPORTED_SCHEMA_VERSION) in message


def test_a_refused_newer_schema_is_never_downgraded_or_recreated(data_root):
    with ControlPlaneStore(data_root) as store:
        _seed_definition(store)
        store.execute(
            "INSERT INTO schema_migrations (version, name, applied_at_utc) VALUES (?, ?, ?)",
            (SUPPORTED_SCHEMA_VERSION + 5, "from_a_newer_build", "2026-09-06T00:00:00+00:00"),
        )

    with pytest.raises(ControlPlaneSchemaVersionError):
        ControlPlaneStore(data_root)

    # The refusal left the newer version and the existing row untouched.
    raw = sqlite3.connect(str(store_path(data_root)))
    try:
        assert raw.execute("SELECT max(version) FROM schema_migrations").fetchone()[0] == (
            SUPPORTED_SCHEMA_VERSION + 5
        )
        assert raw.execute("SELECT count(*) FROM job_definitions").fetchone()[0] == 1
    finally:
        raw.close()


def test_a_failing_migration_rolls_back_and_leaves_no_partial_schema(data_root, monkeypatch):
    import utils.control_plane_store as module

    broken = MIGRATIONS + (
        (
            SUPPORTED_SCHEMA_VERSION + 1,
            "deliberately_broken",
            (
                "CREATE TABLE later_table (id TEXT NOT NULL PRIMARY KEY) STRICT",
                "CREATE TABLE later_table (id TEXT NOT NULL PRIMARY KEY) STRICT",  # duplicate
            ),
        ),
    )
    monkeypatch.setattr(module, "MIGRATIONS", broken)
    monkeypatch.setattr(module, "SUPPORTED_SCHEMA_VERSION", SUPPORTED_SCHEMA_VERSION + 1)

    with pytest.raises(ControlPlaneMigrationError):
        ControlPlaneStore(data_root)

    raw = sqlite3.connect(str(store_path(data_root)))
    try:
        # The failed migration's version was never recorded and its first
        # statement was rolled back with it.
        assert raw.execute("SELECT max(version) FROM schema_migrations").fetchone()[0] == (
            SUPPORTED_SCHEMA_VERSION
        )
        partial = raw.execute(
            "SELECT count(*) FROM sqlite_schema WHERE type='table' AND name='later_table'"
        ).fetchone()[0]
        assert partial == 0
    finally:
        raw.close()


# ---------------------------------------------------------------------------
# §6.5 — the ledger must be an exact known prefix of MIGRATIONS
#
# max(version) alone is not enough: a ledger can report exactly the supported
# maximum while carrying a different migration identity or sequence.
# ---------------------------------------------------------------------------

def _write_ledger(
    data_root: Path,
    entries,
    *,
    journal_mode: str = "delete",
    keyed: bool = True,
) -> Path:
    """Build a database carrying an arbitrary ledger, without the store.

    ``keyed=True`` reproduces this build's own ledger shape
    (``version INTEGER PRIMARY KEY``). ``keyed=False`` reproduces a *foreign*
    ledger table written by some other build -- the only shape in which a
    duplicate or an out-of-order application history can physically exist,
    because an INTEGER PRIMARY KEY makes rowid the version and so
    canonicalises both away.
    """
    path = store_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = sqlite3.connect(str(path), isolation_level=None)
    try:
        raw.execute(f"PRAGMA journal_mode = {journal_mode}")
        version_column = (
            "version INTEGER NOT NULL PRIMARY KEY" if keyed else "version INTEGER NOT NULL"
        )
        raw.execute(
            f"CREATE TABLE schema_migrations ({version_column}, "
            "name TEXT NOT NULL, applied_at_utc TEXT NOT NULL) STRICT"
        )
        for version, name in entries:
            raw.execute(
                "INSERT INTO schema_migrations (version, name, applied_at_utc) VALUES (?, ?, ?)",
                (version, name, "2026-09-06T00:00:00+00:00"),
            )
    finally:
        raw.close()
    return path


def _read_raw_ledger(data_root: Path) -> list[tuple[int, str]]:
    raw = sqlite3.connect(str(store_path(data_root)))
    try:
        return [
            (int(v), str(n))
            for v, n in raw.execute(
                "SELECT version, name FROM schema_migrations ORDER BY rowid"
            ).fetchall()
        ]
    finally:
        raw.close()


def _read_raw_journal_mode(data_root: Path) -> str:
    raw = sqlite3.connect(str(store_path(data_root)))
    try:
        return str(raw.execute("PRAGMA journal_mode").fetchone()[0]).lower()
    finally:
        raw.close()


def test_supported_version_with_the_wrong_migration_name_is_refused(data_root):
    """The core gap: max(version) == SUPPORTED, but the identity is foreign."""
    _write_ledger(data_root, [(SUPPORTED_SCHEMA_VERSION, "some_other_builds_migration")])

    with pytest.raises(ControlPlaneSchemaVersionError) as excinfo:
        ControlPlaneStore(data_root)

    message = str(excinfo.value)
    assert "some_other_builds_migration" in message
    assert "initial_control_plane_metadata" in message


def test_an_unknown_extra_version_is_refused(data_root):
    _write_ledger(
        data_root,
        [(1, "initial_control_plane_metadata"), (2, "an_unknown_later_migration")],
    )
    with pytest.raises(ControlPlaneSchemaVersionError) as excinfo:
        ControlPlaneStore(data_root)
    assert "an_unknown_later_migration" in str(excinfo.value)


def test_an_unknown_lower_version_is_refused(data_root):
    _write_ledger(data_root, [(0, "a_version_this_build_never_defined")])
    with pytest.raises(ControlPlaneSchemaVersionError):
        ControlPlaneStore(data_root)


def test_a_non_prefix_sequence_is_refused(data_root):
    """A ledger starting at 2 skips migration 1 -- not a prefix of MIGRATIONS."""
    _write_ledger(data_root, [(2, "initial_control_plane_metadata")])
    with pytest.raises(ControlPlaneSchemaVersionError):
        ControlPlaneStore(data_root)


def test_a_gapped_sequence_is_refused(data_root, monkeypatch):
    import utils.control_plane_store as module

    monkeypatch.setattr(
        module,
        "MIGRATIONS",
        MIGRATIONS + ((2, "second", ("CREATE TABLE t2 (id TEXT NOT NULL PRIMARY KEY) STRICT",)),),
    )
    # Ledger records 1 and 3 -- version 2 was never applied.
    _write_ledger(data_root, [(1, "initial_control_plane_metadata"), (3, "second")])
    with pytest.raises(ControlPlaneSchemaVersionError):
        ControlPlaneStore(data_root)


def test_an_out_of_order_foreign_ledger_is_refused(data_root, monkeypatch):
    """Out-of-order is only representable in a foreign, unkeyed ledger table.

    In this build's own ledger, ``version`` is an INTEGER PRIMARY KEY, so
    rowid *is* the version and SQLite stores the rows in version order --
    an out-of-order application history cannot be recorded at all. It can be
    recorded by another build whose ledger table has no such key, and the
    prefix check refuses that.
    """
    import utils.control_plane_store as module

    monkeypatch.setattr(
        module,
        "MIGRATIONS",
        MIGRATIONS + ((2, "second", ("CREATE TABLE t2 (id TEXT NOT NULL PRIMARY KEY) STRICT",)),),
    )
    _write_ledger(
        data_root,
        [(2, "second"), (1, "initial_control_plane_metadata")],
        keyed=False,
    )
    with pytest.raises(ControlPlaneSchemaVersionError):
        ControlPlaneStore(data_root)


def test_a_duplicated_foreign_ledger_entry_is_refused(data_root):
    _write_ledger(
        data_root,
        [(1, "initial_control_plane_metadata"), (1, "initial_control_plane_metadata")],
        keyed=False,
    )
    with pytest.raises(ControlPlaneSchemaVersionError):
        ControlPlaneStore(data_root)


def test_this_builds_own_ledger_cannot_record_a_duplicate_version(store):
    """The duplicate case is structurally impossible in our own ledger."""
    with pytest.raises(ControlPlaneIntegrityError):
        store.execute(
            "INSERT INTO schema_migrations (version, name, applied_at_utc) VALUES (?, ?, ?)",
            (SUPPORTED_SCHEMA_VERSION, "a_second_row_for_the_same_version", "2026-09-06T00:00:00+00:00"),
        )


def test_a_valid_shorter_prefix_is_accepted_and_completed(data_root, monkeypatch):
    """The prefix rule must not refuse a legitimately half-migrated store."""
    import utils.control_plane_store as module

    monkeypatch.setattr(
        module,
        "MIGRATIONS",
        MIGRATIONS + ((2, "second", ("CREATE TABLE t2 (id TEXT NOT NULL PRIMARY KEY) STRICT",)),),
    )
    with ControlPlaneStore(data_root) as store:
        assert store.applied_migrations() == [
            (1, "initial_control_plane_metadata"),
            (2, "second"),
        ]


def test_ledger_refusal_names_encountered_and_supported_ledgers(data_root):
    _write_ledger(data_root, [(SUPPORTED_SCHEMA_VERSION, "wrong_name")])
    with pytest.raises(ControlPlaneSchemaVersionError) as excinfo:
        ControlPlaneStore(data_root)
    message = str(excinfo.value)
    assert "Encountered ledger:" in message
    assert "Supported ledger:" in message
    assert f"1..{SUPPORTED_SCHEMA_VERSION}" in message


def test_ledger_refusal_applies_and_discards_nothing(data_root):
    original = [(SUPPORTED_SCHEMA_VERSION, "wrong_name")]
    _write_ledger(data_root, original)

    with pytest.raises(ControlPlaneSchemaVersionError):
        ControlPlaneStore(data_root)

    # The ledger is untouched and no table of ours was created.
    assert _read_raw_ledger(data_root) == original
    raw = sqlite3.connect(str(store_path(data_root)))
    try:
        tables = {
            str(row[0])
            for row in raw.execute(
                "SELECT name FROM sqlite_schema WHERE type='table'"
            ).fetchall()
        }
    finally:
        raw.close()
    assert tables == {"schema_migrations"}


# ---------------------------------------------------------------------------
# §6.5 — deterministic connection cleanup, and non-mutating refusal
# ---------------------------------------------------------------------------

def _is_closed(connection: sqlite3.Connection) -> bool:
    try:
        connection.execute("SELECT 1")
        return False
    except sqlite3.ProgrammingError:
        return True


def test_a_connection_configuration_failure_closes_the_connection(data_root, monkeypatch):
    import utils.control_plane_store as module

    class _FailingConnection:
        def __init__(self) -> None:
            self.closed = False
            self.row_factory = None

        def execute(self, *_args, **_kwargs):
            raise sqlite3.OperationalError("pragma refused by the engine")

        def close(self) -> None:
            self.closed = True

    created: list[_FailingConnection] = []

    def _fake_connect(*_args, **_kwargs):
        connection = _FailingConnection()
        created.append(connection)
        return connection

    monkeypatch.setattr(module.sqlite3, "connect", _fake_connect)

    with pytest.raises(ControlPlaneStoreError):
        ControlPlaneStore(data_root)

    assert created, "expected the store to have opened a connection"
    assert all(connection.closed for connection in created)


def test_a_corrupt_database_closes_every_connection_it_opened(data_root, monkeypatch):
    import utils.control_plane_store as module

    path = store_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"not a database")

    real_connect = module.sqlite3.connect
    created: list[sqlite3.Connection] = []

    def _tracking_connect(*args, **kwargs):
        connection = real_connect(*args, **kwargs)
        created.append(connection)
        return connection

    monkeypatch.setattr(module.sqlite3, "connect", _tracking_connect)

    with pytest.raises(ControlPlaneCorruptionError):
        ControlPlaneStore(data_root)

    assert created, "expected the store to have opened a connection"
    assert all(_is_closed(connection) for connection in created)


def test_a_ledger_refusal_closes_every_connection_it_opened(data_root, monkeypatch):
    import utils.control_plane_store as module

    _write_ledger(data_root, [(SUPPORTED_SCHEMA_VERSION, "wrong_name")])

    real_connect = module.sqlite3.connect
    created: list[sqlite3.Connection] = []

    def _tracking_connect(*args, **kwargs):
        connection = real_connect(*args, **kwargs)
        created.append(connection)
        return connection

    monkeypatch.setattr(module.sqlite3, "connect", _tracking_connect)

    with pytest.raises(ControlPlaneSchemaVersionError):
        ControlPlaneStore(data_root)

    assert created
    assert all(_is_closed(connection) for connection in created)


def test_unsupported_schema_refusal_does_not_change_the_journal_mode(data_root):
    """Refusal must precede every persistent mutation, WAL activation included."""
    _write_ledger(
        data_root,
        [(SUPPORTED_SCHEMA_VERSION, "wrong_name")],
        journal_mode="delete",
    )
    assert _read_raw_journal_mode(data_root) == "delete"

    with pytest.raises(ControlPlaneSchemaVersionError):
        ControlPlaneStore(data_root)

    assert _read_raw_journal_mode(data_root) == "delete"


def test_a_newer_schema_refusal_does_not_change_the_journal_mode(data_root):
    _write_ledger(
        data_root,
        [(1, "initial_control_plane_metadata"), (99, "from_a_much_newer_build")],
        journal_mode="delete",
    )
    with pytest.raises(ControlPlaneSchemaVersionError):
        ControlPlaneStore(data_root)
    assert _read_raw_journal_mode(data_root) == "delete"
    assert _read_raw_ledger(data_root) == [
        (1, "initial_control_plane_metadata"),
        (99, "from_a_much_newer_build"),
    ]


def test_corruption_refusal_creates_no_wal_sidecars(data_root):
    path = store_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"not a database")

    with pytest.raises(ControlPlaneCorruptionError):
        ControlPlaneStore(data_root)

    wal, shm = sidecar_paths(data_root)
    assert not wal.exists()
    assert not shm.exists()


# ---------------------------------------------------------------------------
# §6.5 — corruption fails closed, never auto-repaired or recreated
# ---------------------------------------------------------------------------

def test_non_database_content_is_refused(data_root):
    path = store_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"this is definitely not a SQLite database")

    with pytest.raises(ControlPlaneCorruptionError):
        ControlPlaneStore(data_root)


def test_corrupt_content_is_never_repaired_or_recreated(data_root):
    path = store_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    original = b"this is definitely not a SQLite database"
    path.write_bytes(original)

    with pytest.raises(ControlPlaneCorruptionError):
        ControlPlaneStore(data_root)

    # Byte-for-byte untouched: no repair, no truncation, no silent recreate.
    assert path.read_bytes() == original


def test_corruption_message_refuses_rather_than_offering_recovery(data_root):
    path = store_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"not a database at all")

    with pytest.raises(ControlPlaneCorruptionError) as excinfo:
        ControlPlaneStore(data_root)
    assert "refusing to repair" in str(excinfo.value).lower()


def test_a_failed_open_leaves_no_usable_connection(data_root):
    path = store_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"not a database")

    with pytest.raises(ControlPlaneCorruptionError):
        ControlPlaneStore(data_root)
    # Nothing to leak: construction raised, so no instance exists to hold a
    # half-open connection.


# ---------------------------------------------------------------------------
# §6.5 — uniqueness: idempotency key, one active run per definition
# ---------------------------------------------------------------------------

def test_idempotency_key_is_unique_per_job_submission(store):
    _seed_definition(store)
    store.execute(
        "INSERT INTO job_submissions (idempotency_key, definition_id, provenance, submitted_at_utc) "
        "VALUES (?, ?, ?, ?)",
        ("key-1", "def-1", "console", "2026-09-06T00:00:00+00:00"),
    )
    with pytest.raises(ControlPlaneIntegrityError):
        store.execute(
            "INSERT INTO job_submissions (idempotency_key, definition_id, provenance, submitted_at_utc) "
            "VALUES (?, ?, ?, ?)",
            ("key-1", "def-1", "manual", "2026-09-06T00:01:00+00:00"),
        )


def test_at_most_one_active_run_per_durable_definition(store):
    _seed_definition(store)
    store.execute(
        "INSERT INTO job_runs (run_id, definition_id, state, requested_at_utc) VALUES (?, ?, ?, ?)",
        ("run-1", "def-1", "queued", "2026-09-06T00:00:00+00:00"),
    )
    with pytest.raises(ControlPlaneIntegrityError):
        store.execute(
            "INSERT INTO job_runs (run_id, definition_id, state, requested_at_utc) "
            "VALUES (?, ?, ?, ?)",
            ("run-2", "def-1", "running", "2026-09-06T00:01:00+00:00"),
        )


def test_a_terminal_run_frees_the_definition_for_a_new_run(store):
    _seed_definition(store)
    store.execute(
        "INSERT INTO job_runs (run_id, definition_id, state, requested_at_utc) VALUES (?, ?, ?, ?)",
        ("run-1", "def-1", "running", "2026-09-06T00:00:00+00:00"),
    )
    store.execute("UPDATE job_runs SET state = 'succeeded' WHERE run_id = ?", ("run-1",))
    store.execute(
        "INSERT INTO job_runs (run_id, definition_id, state, requested_at_utc) VALUES (?, ?, ?, ?)",
        ("run-2", "def-1", "queued", "2026-09-06T00:01:00+00:00"),
    )
    active = store.connection.execute(
        "SELECT count(*) FROM job_runs WHERE state IN ('queued', 'running')"
    ).fetchone()[0]
    assert active == 1


def test_two_definitions_may_each_have_their_own_active_run(store):
    _seed_definition(store, "def-1")
    _seed_definition(store, "def-2")
    store.execute(
        "INSERT INTO job_runs (run_id, definition_id, state, requested_at_utc) VALUES (?, ?, ?, ?)",
        ("run-1", "def-1", "running", "2026-09-06T00:00:00+00:00"),
    )
    store.execute(
        "INSERT INTO job_runs (run_id, definition_id, state, requested_at_utc) VALUES (?, ?, ?, ?)",
        ("run-2", "def-2", "running", "2026-09-06T00:00:00+00:00"),
    )
    assert store.connection.execute("SELECT count(*) FROM job_runs").fetchone()[0] == 2


# ---------------------------------------------------------------------------
# §6.5 — transactional rollback
# ---------------------------------------------------------------------------

def test_a_failed_transaction_rolls_back_every_statement_in_it(store):
    _seed_definition(store)
    with pytest.raises(ControlPlaneIntegrityError):
        with store.transaction() as cursor:
            cursor.execute(
                "INSERT INTO job_runs (run_id, definition_id, state, requested_at_utc) "
                "VALUES (?, ?, ?, ?)",
                ("run-ok", "def-1", "queued", "2026-09-06T00:00:00+00:00"),
            )
            cursor.execute(
                "INSERT INTO job_runs (run_id, definition_id, state, requested_at_utc) "
                "VALUES (?, ?, ?, ?)",
                ("run-bad", "missing-definition", "queued", "2026-09-06T00:00:00+00:00"),
            )
    # The first insert did not survive the failure of the second.
    assert store.connection.execute("SELECT count(*) FROM job_runs").fetchone()[0] == 0


def test_a_committed_transaction_persists_across_reopen(data_root):
    with ControlPlaneStore(data_root) as store:
        with store.transaction() as cursor:
            cursor.execute(
                "INSERT INTO job_definitions (definition_id, job_type, created_at_utc) "
                "VALUES (?, ?, ?)",
                ("def-1", "cp_inventory_refresh", "2026-09-06T00:00:00+00:00"),
            )
    with ControlPlaneStore(data_root) as reopened:
        assert reopened.connection.execute(
            "SELECT count(*) FROM job_definitions"
        ).fetchone()[0] == 1


# ---------------------------------------------------------------------------
# §6.5 — one writer, concurrent readers, bounded contention
# ---------------------------------------------------------------------------

def test_readers_run_concurrently_with_the_writer(store):
    _seed_definition(store)
    reader = store.open_reader()
    try:
        assert reader.execute("SELECT count(*) FROM job_definitions").fetchone()[0] == 1
    finally:
        reader.close()


def test_a_reader_connection_is_read_only(store):
    reader = store.open_reader()
    try:
        with pytest.raises(sqlite3.OperationalError):
            reader.execute(
                "INSERT INTO job_definitions (definition_id, job_type, created_at_utc) "
                "VALUES (?, ?, ?)",
                ("def-x", "t", "2026-09-06T00:00:00+00:00"),
            )
    finally:
        reader.close()


def test_writer_contention_fails_closed_within_the_bounded_timeout(data_root):
    """Contention waits, then fails closed -- never an unbounded block."""
    holder = ControlPlaneStore(data_root, busy_timeout_ms=200)
    contender = ControlPlaneStore(data_root, busy_timeout_ms=200)
    try:
        holder.connection.execute("BEGIN IMMEDIATE")
        started = time.monotonic()
        with pytest.raises(ControlPlaneContentionError):
            with contender.transaction():
                pass
        elapsed = time.monotonic() - started
        # Bounded: it gave up near the configured timeout rather than hanging.
        assert elapsed < 10
        holder.connection.execute("ROLLBACK")
    finally:
        holder.close()
        contender.close()


def test_concurrent_readers_do_not_block_each_other(store):
    _seed_definition(store)
    errors: list[BaseException] = []

    def read_once() -> None:
        try:
            reader = store.open_reader()
            try:
                reader.execute("SELECT count(*) FROM job_definitions").fetchone()
            finally:
                reader.close()
        except BaseException as exc:  # pragma: no cover - failure path
            errors.append(exc)

    threads = [threading.Thread(target=read_once) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=15)
    assert errors == []


def test_connection_ownership_is_deterministic(data_root):
    store = ControlPlaneStore(data_root)
    assert store.connection is not None
    store.close()
    with pytest.raises(ControlPlaneStoreError):
        _ = store.connection


def test_close_is_idempotent(data_root):
    store = ControlPlaneStore(data_root)
    store.close()
    store.close()


# ---------------------------------------------------------------------------
# AC-ST-1 / AC-ST-3 / AC-ST-5 — the Device Registry and forbidden data stay out
# ---------------------------------------------------------------------------

def test_no_registry_table_exists(store):
    tables = {
        str(row[0])
        for row in store.connection.execute(
            "SELECT name FROM sqlite_schema WHERE type='table'"
        ).fetchall()
    }
    for forbidden in ("device_registry", "devices", "registry"):
        assert forbidden not in tables


def test_schema_owns_no_forbidden_concept(store):
    """AC-ST-3/AC-ST-5: proven against the real schema, not a maintained list."""
    forbidden_fragments = (
        "endpoint",
        "address",
        "hostname",
        "host_name",
        "ip_addr",
        "management_ip",
        "credential",
        "password",
        "secret",
        "token",
        "passphrase",
        "private_key",
        "trust",
        "raw_config",
        "configuration_blob",
        "backup_bytes",
        "cas_",
        "evidence_object",
    )
    offenders = [
        f"{table}.{column}"
        for table, column in iter_schema_columns(store.connection)
        for fragment in forbidden_fragments
        if fragment in column.lower()
    ]
    assert offenders == [], f"forbidden concept persisted in the control-plane store: {offenders}"


def test_targets_are_stored_only_as_opaque_references(store):
    columns = {
        column for table, column in iter_schema_columns(store.connection) if table == "job_definitions"
    }
    assert "target_ref" in columns
    # The reference is opaque and its kind is constrained to the two allowed
    # identifier families -- never a copied endpoint (AC-TGT-1, AC-ST-5).
    kinds = store.connection.execute(
        "SELECT sql FROM sqlite_schema WHERE name='job_definitions'"
    ).fetchone()[0]
    assert "device_id" in kinds and "logical_entity_id" in kinds


def test_an_unknown_target_ref_kind_is_refused(store):
    with pytest.raises(ControlPlaneStoreError):
        store.execute(
            "INSERT INTO job_definitions (definition_id, job_type, target_ref, target_ref_kind, "
            "created_at_utc) VALUES (?, ?, ?, ?, ?)",
            ("def-bad", "t", "10.0.0.1", "endpoint", "2026-09-06T00:00:00+00:00"),
        )


def test_m4_performs_no_device_registry_migration(data_root):
    """AC-ST-1: opening the store neither reads, writes nor creates registry state."""
    from utils import device_registry

    registry_file = data_root / "state" / device_registry.REGISTRY_FILENAME
    with ControlPlaneStore(data_root):
        pass
    assert not registry_file.exists()


def test_device_registry_behaviour_is_unchanged_alongside_the_store(data_root):
    from utils.device_registry import DeviceRegistry

    registry = DeviceRegistry(data_root)
    record = registry.enroll(endpoint="192.0.2.10")
    with ControlPlaneStore(data_root):
        pass
    # The registry is still its own filesystem JSON authority, unaffected.
    listed = registry.list()
    assert [row.device_id for row in listed] == [record.device_id]
    assert (data_root / "state" / "device_registry.json").exists()


# ---------------------------------------------------------------------------
# X1 — the frozen job lifecycle vocabulary gains no competing member
# ---------------------------------------------------------------------------

def test_lifecycle_vocabulary_matches_the_frozen_console_vocabulary():
    from console.jobs import TERMINAL_STATES

    assert set(JOB_LIFECYCLE_STATES) - set(ACTIVE_JOB_STATES) == set(TERMINAL_STATES)
    assert set(ACTIVE_JOB_STATES) == {"queued", "running"}
    assert len(JOB_LIFECYCLE_STATES) == 6


def test_the_schema_accepts_only_the_frozen_lifecycle_states(store):
    _seed_definition(store)
    with pytest.raises(ControlPlaneStoreError):
        store.execute(
            "INSERT INTO job_runs (run_id, definition_id, state, requested_at_utc) "
            "VALUES (?, ?, ?, ?)",
            ("run-x", "def-1", "policy_disabled", "2026-09-06T00:00:00+00:00"),
        )


def test_every_frozen_lifecycle_state_is_accepted(store):
    for index, state in enumerate(JOB_LIFECYCLE_STATES):
        definition_id = f"def-{index}"
        _seed_definition(store, definition_id)
        store.execute(
            "INSERT INTO job_runs (run_id, definition_id, state, requested_at_utc) "
            "VALUES (?, ?, ?, ?)",
            (f"run-{index}", definition_id, state, "2026-09-06T00:00:00+00:00"),
        )
    assert store.connection.execute("SELECT count(*) FROM job_runs").fetchone()[0] == 6


# ---------------------------------------------------------------------------
# AC-CS-71 / AC-CS-72 / AC-CS-97 — capability-projection persistence boundary
# ---------------------------------------------------------------------------

def test_projection_persists_no_presentation_state_as_truth(store):
    """AC-CS-72/AC-CS-97: resolved presentation is never a persisted field."""
    columns = {
        column
        for table, column in iter_schema_columns(store.connection)
        if table == "capability_projections"
    }
    forbidden = {
        "primary_status",
        "capability_qualifiers",
        "qualifiers",
        "evidence_presentation",
        "shell_composition",
        "action_affordance",
        "affordances",
        "action_declarations",
        "taxonomy_class",
        "authorization",
        "authz_state",
    }
    assert columns & forbidden == set()


def test_projection_retains_authority_generations_and_rule_versions(store):
    """AC-CS-71: reuse can only be validated if all of them are retained."""
    columns = {
        column
        for table, column in iter_schema_columns(store.connection)
        if table == "capability_projections"
    }
    assert {"authority_generations", "producer_version", "support_rule_version"} <= columns


def test_projection_records_semantic_identity_and_basis(store):
    columns = {
        column
        for table, column in iter_schema_columns(store.connection)
        if table == "capability_projections"
    }
    assert {
        "subject_ref",
        "capability",
        "vendor",
        "platform",
        "entity_kind",
        "basis_run_ref",
    } <= columns


def test_projection_records_whether_the_identity_mapping_is_proven(store):
    """AC-CS-97: a projection may not be reused across an unproven mapping."""
    store.execute(
        "INSERT INTO capability_projections (projection_id, subject_ref, subject_ref_kind, "
        "capability, vendor, platform, entity_kind, dimension_values, producer_version, "
        "support_rule_version, authority_generations, identity_mapping_proven, computed_at_utc) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            "proj-1", "dev-1", "device_id", "inventory", "checkpoint", "gaia", "gateway",
            "{}", "p1", "s1", "[]", 0, "2026-09-06T00:00:00+00:00",
        ),
    )
    proven = store.connection.execute(
        "SELECT identity_mapping_proven FROM capability_projections WHERE projection_id='proj-1'"
    ).fetchone()[0]
    assert proven == 0


def test_a_projection_is_bound_to_one_exact_semantic_identity(store):
    """§8.2.1: reusable only for the exact binding it was computed under."""
    values = (
        "dev-1", "device_id", "inventory", "checkpoint", "gaia", "gateway",
        "{}", "p1", "s1", "[]", 1, "2026-09-06T00:00:00+00:00",
    )
    store.execute(
        "INSERT INTO capability_projections (projection_id, subject_ref, subject_ref_kind, "
        "capability, vendor, platform, entity_kind, dimension_values, producer_version, "
        "support_rule_version, authority_generations, identity_mapping_proven, computed_at_utc) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ("proj-1",) + values,
    )
    with pytest.raises(ControlPlaneIntegrityError):
        store.execute(
            "INSERT INTO capability_projections (projection_id, subject_ref, subject_ref_kind, "
            "capability, vendor, platform, entity_kind, dimension_values, producer_version, "
            "support_rule_version, authority_generations, identity_mapping_proven, computed_at_utc) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ("proj-2",) + values,
        )


# ---------------------------------------------------------------------------
# AC-ST-6 — support-bundle exclusion and LOCAL-SENSITIVE handling
# ---------------------------------------------------------------------------

def test_store_and_wal_files_are_never_enumerated_by_the_support_bundle(store, data_root):
    pytest.importorskip("paramiko")
    from utils import support_bundle

    _seed_definition(store)
    wal, shm = sidecar_paths(data_root)
    # Force the WAL sidecar to exist so it would be enumerated if anything
    # ever walked data/state/*.
    assert store.path.exists()
    wal.touch(exist_ok=True)
    shm.touch(exist_ok=True)

    run_dir = data_root / "runs" / "20260101T000000Z"
    (run_dir / "stage").mkdir(parents=True, exist_ok=True)
    (run_dir / "raw").mkdir(parents=True, exist_ok=True)

    bundle_path = support_bundle.run_support_bundle(
        run_dir, data_root=data_root, output_root=data_root.parent / "output"
    )

    import zipfile

    with zipfile.ZipFile(bundle_path) as zf:
        names = zf.namelist()
    assert not any("control_plane" in name for name in names)
    assert not any(name.endswith((".db", ".db-wal", ".db-shm")) for name in names)


def test_store_lives_outside_the_only_subtree_the_bundle_enumerates(data_root):
    """Exclusion is structural: the bundle walks data/runs/*, never data/state/*."""
    path = store_path(data_root)
    assert "state" in path.parts
    assert "runs" not in path.parts


def test_store_and_sidecars_are_database_artifacts_to_the_privacy_gate():
    """The DLP gate already refuses these in the repository -- extend, not duplicate."""
    from utils.repository_privacy import FORBIDDEN_SUFFIXES

    assert FORBIDDEN_SUFFIXES.get(".db") == "DATABASE_ARTIFACT"
    assert STORE_FILENAME.endswith(".db")


def test_store_and_sidecars_are_gitignored():
    ignored = (ROOT / ".gitignore").read_text(encoding="utf-8").splitlines()
    for pattern in ("*.db", "*.db-wal", "*.db-shm"):
        assert pattern in ignored


def test_store_is_registered_as_local_sensitive_class_2():
    privacy = (ROOT / "PRIVACY_AND_DATA_HANDLING.md").read_text(encoding="utf-8")
    assert STORE_FILENAME in privacy


# ---------------------------------------------------------------------------
# AC-ST-7 — SQLite is not claimed as the production engine
# ---------------------------------------------------------------------------

def test_postgres_selection_is_refused_rather_than_silently_downgraded(data_root, monkeypatch):
    from utils.evidence_backend import (
        ENV_BACKEND,
        EvidenceBackendError,
        select_control_plane_metadata_backend,
    )

    monkeypatch.setenv(ENV_BACKEND, "postgres")
    with pytest.raises(EvidenceBackendError) as excinfo:
        select_control_plane_metadata_backend(data_root=data_root)
    assert "pcp_storage_engine" in str(excinfo.value)


def test_an_unsupported_backend_kind_fails_closed(data_root, monkeypatch):
    from utils.evidence_backend import (
        ENV_BACKEND,
        EvidenceBackendError,
        select_control_plane_metadata_backend,
    )

    monkeypatch.setenv(ENV_BACKEND, "mysql")
    with pytest.raises(EvidenceBackendError):
        select_control_plane_metadata_backend(data_root=data_root)


def test_the_filesystem_default_returns_the_local_store(data_root, monkeypatch):
    from utils.evidence_backend import ENV_BACKEND, select_control_plane_metadata_backend

    monkeypatch.delenv(ENV_BACKEND, raising=False)
    backend = select_control_plane_metadata_backend(data_root=data_root)
    try:
        assert isinstance(backend, ControlPlaneStore)
    finally:
        backend.close()


def test_module_never_claims_sqlite_is_the_production_engine():
    source = (ROOT / "utils" / "control_plane_store.py").read_text(encoding="utf-8")
    assert "not the production engine" in source
    assert "pcp_storage_engine" in source
