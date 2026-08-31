"""RB.3b step 2 — durable ``operational-write`` ledger.

Contract: ``docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`` §10 /
``BACKUP_RECOVERY_CONTRACTS.md`` §9.13, decision B4 in
``docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md``.

Two tiers, mirroring the DEV.3.3 precedent:

* Backend-agnostic tests (filesystem default) — selection, the fail-closed
  unreadable/absent split, window arithmetic, append-only behaviour.
* Live PostgreSQL integration (``SECURITYEXPERT_TEST_POSTGRES_DSN``, skipped
  when absent) — schema, insert-only, and filesystem/Postgres decision parity.

§10 obligations (a) [zero device contact on a second in-window run], (f)
[read+write inside ``run_under_admission``] and (g) [``record_execution`` fires
iff ``add backup local`` was sent] are collector-integration properties and are
exercised with RB.3b step 5, against the fixture SSH/SCP transport.
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from utils.evidence_backend import (
    EvidenceBackendError,
    FilesystemOperationalWriteLedgerBackend,
    PostgresOperationalWriteLedgerBackend,
    select_operational_write_ledger_backend,
    verify_evidence_backend_ready,
)
from utils.recovery_operational_ledger import (
    LEDGER_RELATIVE_PATH,
    LedgerEntry,
    OperationalLedgerUnreadableError,
    RecoveryOperationalLedger,
    ledger_path,
)

pytestmark = pytest.mark.runtime_platform

REPO_ROOT = Path(__file__).resolve().parent.parent
CLASS = "cp_gaia_backup"


def _now() -> datetime:
    return datetime(2026, 8, 31, 12, 0, 0, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# Selection
# ---------------------------------------------------------------------------

def test_default_selection_is_filesystem(monkeypatch, tmp_path):
    monkeypatch.delenv("SECURITYEXPERT_EVIDENCE_BACKEND", raising=False)
    backend = select_operational_write_ledger_backend(state_file=tmp_path / "l.json")
    assert isinstance(backend, FilesystemOperationalWriteLedgerBackend)


def test_postgres_without_dsn_fails_closed(monkeypatch, tmp_path):
    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_BACKEND", "postgres")
    monkeypatch.delenv("SECURITYEXPERT_EVIDENCE_POSTGRES_DSN", raising=False)
    with pytest.raises(EvidenceBackendError):
        select_operational_write_ledger_backend(state_file=tmp_path / "l.json")


def test_unsupported_backend_kind_fails_closed(monkeypatch, tmp_path):
    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_BACKEND", "sqlite")
    with pytest.raises(EvidenceBackendError):
        select_operational_write_ledger_backend(state_file=tmp_path / "l.json")


def test_ledger_path_is_runtime_state():
    assert ledger_path("/x/data") == Path("/x/data") / LEDGER_RELATIVE_PATH
    assert LEDGER_RELATIVE_PATH == "state/recovery_operational_ledger.json"


# ---------------------------------------------------------------------------
# §10 (c) — absent ledger proceeds
# ---------------------------------------------------------------------------

def test_absent_ledger_returns_none_and_proceeds(tmp_path):
    ledger = RecoveryOperationalLedger(
        FilesystemOperationalWriteLedgerBackend(tmp_path / "state" / "recovery_operational_ledger.json")
    )
    assert ledger.last_execution(entity_id="gw-1", command_class=CLASS) is None
    assert ledger.within_window(entity_id="gw-1", command_class=CLASS, now=_now()) is False


def test_empty_entries_list_is_absent_not_unreadable(tmp_path):
    p = tmp_path / "l.json"
    p.write_text(json.dumps({"schema": "x", "entries": []}), encoding="utf-8")
    ledger = RecoveryOperationalLedger(FilesystemOperationalWriteLedgerBackend(p))
    assert ledger.last_execution(entity_id="gw-1", command_class=CLASS) is None


# ---------------------------------------------------------------------------
# §10 (b) — unreadable ledger blocks the run
# ---------------------------------------------------------------------------

def test_corrupt_json_raises_unreadable(tmp_path):
    p = tmp_path / "l.json"
    p.write_text("{not json", encoding="utf-8")
    ledger = RecoveryOperationalLedger(FilesystemOperationalWriteLedgerBackend(p))
    with pytest.raises(OperationalLedgerUnreadableError):
        ledger.last_execution(entity_id="gw-1", command_class=CLASS)
    with pytest.raises(OperationalLedgerUnreadableError):
        ledger.within_window(entity_id="gw-1", command_class=CLASS, now=_now())


def test_malformed_structure_raises_unreadable(tmp_path):
    p = tmp_path / "l.json"
    p.write_text(json.dumps({"schema": "x", "entries": {"not": "a list"}}), encoding="utf-8")
    ledger = RecoveryOperationalLedger(FilesystemOperationalWriteLedgerBackend(p))
    with pytest.raises(OperationalLedgerUnreadableError):
        ledger.last_execution(entity_id="gw-1", command_class=CLASS)


def test_unparseable_timestamp_raises_unreadable(tmp_path):
    p = tmp_path / "l.json"
    p.write_text(
        json.dumps(
            {
                "schema": "x",
                "entries": [
                    {"entity_id": "gw-1", "command_class": CLASS, "executed_at": "not-a-date", "outcome": "completed"}
                ],
            }
        ),
        encoding="utf-8",
    )
    ledger = RecoveryOperationalLedger(FilesystemOperationalWriteLedgerBackend(p))
    with pytest.raises(OperationalLedgerUnreadableError):
        ledger.last_execution(entity_id="gw-1", command_class=CLASS)


def test_backend_error_is_wrapped_as_unreadable():
    class Boom(FilesystemOperationalWriteLedgerBackend):
        def entries_for(self, *, entity_id, command_class):
            raise EvidenceBackendError("disk gone")

    ledger = RecoveryOperationalLedger(Boom(Path("/nope/l.json")))
    with pytest.raises(OperationalLedgerUnreadableError):
        ledger.last_execution(entity_id="gw-1", command_class=CLASS)


# ---------------------------------------------------------------------------
# Window arithmetic + record/read round trip
# ---------------------------------------------------------------------------

def _fs_ledger(tmp_path) -> RecoveryOperationalLedger:
    return RecoveryOperationalLedger(
        FilesystemOperationalWriteLedgerBackend(tmp_path / "state" / "recovery_operational_ledger.json")
    )


def test_record_then_within_window(tmp_path):
    ledger = _fs_ledger(tmp_path)
    ledger.record_execution(
        entity_id="gw-1", command_class=CLASS,
        executed_at=_now() - timedelta(hours=1), outcome="completed", run_id="run-9",
    )
    assert ledger.within_window(entity_id="gw-1", command_class=CLASS, now=_now()) is True
    last = ledger.last_execution(entity_id="gw-1", command_class=CLASS)
    assert last is not None and last.outcome == "completed" and last.run_id == "run-9"


def test_stale_entry_is_outside_window(tmp_path):
    ledger = _fs_ledger(tmp_path)
    ledger.record_execution(
        entity_id="gw-1", command_class=CLASS,
        executed_at=_now() - timedelta(hours=25), outcome="completed", run_id=None,
    )
    assert ledger.within_window(entity_id="gw-1", command_class=CLASS, now=_now()) is False


def test_exactly_at_window_boundary_is_outside(tmp_path):
    ledger = _fs_ledger(tmp_path)
    ledger.record_execution(
        entity_id="gw-1", command_class=CLASS,
        executed_at=_now() - timedelta(hours=24), outcome="completed", run_id=None,
    )
    assert ledger.within_window(entity_id="gw-1", command_class=CLASS, now=_now()) is False


def test_failed_and_cleanup_failed_outcomes_recorded(tmp_path):
    ledger = _fs_ledger(tmp_path)
    ledger.record_execution(entity_id="gw-1", command_class=CLASS, executed_at=_now() - timedelta(hours=2), outcome="failed", run_id=None)
    ledger.record_execution(entity_id="gw-1", command_class=CLASS, executed_at=_now() - timedelta(hours=1), outcome="cleanup_failed", run_id=None)
    last = ledger.last_execution(entity_id="gw-1", command_class=CLASS)
    assert last is not None and last.outcome == "cleanup_failed"
    assert ledger.within_window(entity_id="gw-1", command_class=CLASS, now=_now()) is True


def test_invalid_outcome_rejected(tmp_path):
    ledger = _fs_ledger(tmp_path)
    with pytest.raises(ValueError):
        ledger.record_execution(entity_id="gw-1", command_class=CLASS, executed_at=_now(), outcome="ok", run_id=None)


def test_other_entity_and_class_are_isolated(tmp_path):
    ledger = _fs_ledger(tmp_path)
    ledger.record_execution(entity_id="gw-1", command_class=CLASS, executed_at=_now() - timedelta(hours=1), outcome="completed", run_id=None)
    assert ledger.within_window(entity_id="gw-2", command_class=CLASS, now=_now()) is False
    assert ledger.within_window(entity_id="gw-1", command_class="cp_mgmt_export", now=_now()) is False


def test_naive_now_is_treated_as_utc(tmp_path):
    ledger = _fs_ledger(tmp_path)
    ledger.record_execution(entity_id="gw-1", command_class=CLASS, executed_at=_now() - timedelta(hours=1), outcome="completed", run_id=None)
    naive_now = datetime(2026, 8, 31, 12, 0, 0)
    assert ledger.within_window(entity_id="gw-1", command_class=CLASS, now=naive_now) is True


# ---------------------------------------------------------------------------
# §10 (e) — append-only
# ---------------------------------------------------------------------------

def test_filesystem_append_never_rewrites_existing_entry(tmp_path):
    p = tmp_path / "state" / "recovery_operational_ledger.json"
    backend = FilesystemOperationalWriteLedgerBackend(p)
    first = LedgerEntry("gw-1", CLASS, _now() - timedelta(hours=3), "completed", "r1").to_dict()
    backend.append(first)
    backend.append(LedgerEntry("gw-1", CLASS, _now() - timedelta(hours=1), "failed", "r2").to_dict())
    stored = json.loads(p.read_text(encoding="utf-8"))["entries"]
    assert stored[0] == first  # untouched, still first
    assert len(stored) == 2
    assert stored[1]["run_id"] == "r2"


def _strip_comments_and_docstrings(text: str) -> str:
    """Crude: drop ``#`` comment lines and triple-quoted blocks so the check
    sees executable statements only, not prose that mentions UPDATE/DELETE."""
    out, in_doc = [], False
    for line in text.splitlines():
        if line.count('"""') == 1:
            in_doc = not in_doc
            continue
        if in_doc or line.lstrip().startswith("#"):
            continue
        out.append(line.split("#", 1)[0])
    return "\n".join(out)


def test_module_source_has_no_update_or_delete_sql():
    src = _strip_comments_and_docstrings(
        (REPO_ROOT / "utils" / "recovery_operational_ledger.py").read_text(encoding="utf-8")
    )
    backend_src = (REPO_ROOT / "utils" / "evidence_backend.py").read_text(encoding="utf-8")
    ledger_section = _strip_comments_and_docstrings(
        backend_src.split("5. Operational-write ledger backend", 1)[1].split("Backend selection", 1)[0]
    )
    for needle in ("UPDATE ", "DELETE ", "DROP TABLE", "TRUNCATE"):
        assert needle not in src
        assert needle not in ledger_section


def test_schema_key_and_shape(tmp_path):
    p = tmp_path / "state" / "recovery_operational_ledger.json"
    FilesystemOperationalWriteLedgerBackend(p).append(
        LedgerEntry("gw-1", CLASS, _now(), "completed", None).to_dict()
    )
    doc = json.loads(p.read_text(encoding="utf-8"))
    assert doc["schema"] == "securityexpert-recovery-operational-ledger-v1"
    assert "updated_at" in doc
    entry = doc["entries"][0]
    assert set(entry) == {"entity_id", "command_class", "executed_at", "outcome", "run_id"}
    assert entry["executed_at"].endswith("+00:00")


# ---------------------------------------------------------------------------
# Live PostgreSQL integration
# ---------------------------------------------------------------------------

def _postgres_dsn() -> str:
    return os.environ.get(
        "SECURITYEXPERT_TEST_POSTGRES_DSN",
        "postgresql://securityexpert:securityexpert@127.0.0.1:5432/securityexpert_test",
    )


def _postgres_available() -> bool:
    try:
        import psycopg
    except ImportError:
        return False
    try:
        with psycopg.connect(_postgres_dsn(), connect_timeout=2, autocommit=True) as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT 1")
        return True
    except Exception:
        return False


requires_postgres = pytest.mark.skipif(
    not _postgres_available(),
    reason="live PostgreSQL test database not available (set SECURITYEXPERT_TEST_POSTGRES_DSN)",
)


def _reset_table() -> None:
    import psycopg

    with psycopg.connect(_postgres_dsn(), autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute("DROP TABLE IF EXISTS recovery_operational_write_ledger CASCADE")


@pytest.fixture()
def pg_env(monkeypatch):
    _reset_table()
    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_BACKEND", "postgres")
    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_POSTGRES_DSN", _postgres_dsn())
    yield _postgres_dsn()


@requires_postgres
def test_preflight_creates_ledger_table(pg_env):
    import psycopg

    verify_evidence_backend_ready()
    with psycopg.connect(pg_env, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
            )
            tables = {row[0] for row in cur.fetchall()}
    assert "recovery_operational_write_ledger" in tables


@requires_postgres
def test_postgres_record_read_round_trip(pg_env):
    ledger = RecoveryOperationalLedger(PostgresOperationalWriteLedgerBackend(pg_env))
    ledger.record_execution(
        entity_id="gw-1", command_class=CLASS,
        executed_at=_now() - timedelta(hours=1), outcome="completed", run_id="run-9",
    )
    assert ledger.within_window(entity_id="gw-1", command_class=CLASS, now=_now()) is True
    last = ledger.last_execution(entity_id="gw-1", command_class=CLASS)
    assert last is not None and last.run_id == "run-9"


@requires_postgres
def test_postgres_newest_first_ordering(pg_env):
    backend = PostgresOperationalWriteLedgerBackend(pg_env)
    backend.append(LedgerEntry("gw-1", CLASS, _now() - timedelta(hours=5), "failed", "old").to_dict())
    backend.append(LedgerEntry("gw-1", CLASS, _now() - timedelta(hours=1), "completed", "new").to_dict())
    rows = backend.entries_for(entity_id="gw-1", command_class=CLASS)
    assert [r["run_id"] for r in rows] == ["new", "old"]


@requires_postgres
def test_unreachable_postgres_read_is_unreadable(monkeypatch):
    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_BACKEND", "postgres")
    monkeypatch.setenv(
        "SECURITYEXPERT_EVIDENCE_POSTGRES_DSN",
        "postgresql://nobody@127.0.0.1:1/nodb?connect_timeout=2",
    )
    with pytest.raises(EvidenceBackendError):
        PostgresOperationalWriteLedgerBackend(
            "postgresql://nobody@127.0.0.1:1/nodb?connect_timeout=2"
        )


@requires_postgres
def test_filesystem_and_postgres_agree_on_decision(pg_env, tmp_path):
    """§10 (d) — same synthetic history → same skip/proceed decision."""
    history = [
        LedgerEntry("gw-1", CLASS, _now() - timedelta(hours=1), "completed", "r1"),
        LedgerEntry("gw-2", CLASS, _now() - timedelta(hours=30), "completed", "r2"),
    ]

    pg = RecoveryOperationalLedger(PostgresOperationalWriteLedgerBackend(pg_env))
    fs = RecoveryOperationalLedger(
        FilesystemOperationalWriteLedgerBackend(tmp_path / "state" / "recovery_operational_ledger.json")
    )
    for entry in history:
        for lg in (pg, fs):
            lg.record_execution(
                entity_id=entry.entity_id, command_class=entry.command_class,
                executed_at=entry.executed_at, outcome=entry.outcome, run_id=entry.run_id,
            )
    for entity_id in ("gw-1", "gw-2", "gw-3"):
        assert pg.within_window(entity_id=entity_id, command_class=CLASS, now=_now()) == \
               fs.within_window(entity_id=entity_id, command_class=CLASS, now=_now())
