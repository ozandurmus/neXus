"""DEV.3.3 — distributed evidence store migration.

Contract: ``docs/history/phase/DEV3_3_DISTRIBUTED_EVIDENCE_STORE_MIGRATION.md``.

Two tiers, mirroring the DEV.3.2 precedent:

* Backend-agnostic tests that run everywhere (selection, fail-closed
  behavior, the filesystem backend's unchanged on-disk layout, and the
  config_storage/compliance-reconstruction not-applicable gates).
* Live PostgreSQL integration tests (``SECURITYEXPERT_TEST_POSTGRES_DSN``,
  ``skipif`` when absent), including the AC-3 concurrency proof, which uses
  real OS subprocesses — threads would not prove the property under test.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from utils.config_evidence import ConfigEvidenceStore
from utils.config_history import ConfigHistoryService
from utils.evidence_backend import (
    EvidenceBackendError,
    FilesystemConfigSnapshotBackend,
    FilesystemLastKnownGoodBackend,
    FilesystemRunManifestBackend,
    FilesystemSchedulerStateBackend,
    active_evidence_backend_kind,
    select_config_snapshot_backend,
    select_last_known_good_backend,
    select_run_manifest_backend,
    select_scheduler_state_backend,
    verify_evidence_backend_ready,
)

pytestmark = pytest.mark.runtime_platform

REPO_ROOT = Path(__file__).resolve().parent.parent

XML_A = b"<config><devices><entry name='a'/></devices></config>"
XML_B = b"<config><devices><entry name='b'/></devices></config>"


# ---------------------------------------------------------------------------
# Backend selection and fail-closed behavior
# ---------------------------------------------------------------------------

def test_default_selection_is_filesystem(monkeypatch, tmp_path):
    monkeypatch.delenv("SECURITYEXPERT_EVIDENCE_BACKEND", raising=False)
    assert active_evidence_backend_kind() == "filesystem"
    assert isinstance(select_config_snapshot_backend(root=tmp_path), FilesystemConfigSnapshotBackend)
    assert isinstance(select_run_manifest_backend(), FilesystemRunManifestBackend)
    assert isinstance(
        select_last_known_good_backend(state_file=tmp_path / "lkg.json"), FilesystemLastKnownGoodBackend
    )
    assert isinstance(
        select_scheduler_state_backend(path=tmp_path / "sched.json"), FilesystemSchedulerStateBackend
    )


def test_postgres_without_dsn_fails_closed(monkeypatch, tmp_path):
    """AC-6: never silently degrade to filesystem when misconfigured."""
    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_BACKEND", "postgres")
    monkeypatch.delenv("SECURITYEXPERT_EVIDENCE_POSTGRES_DSN", raising=False)
    for factory in (
        lambda: select_config_snapshot_backend(root=tmp_path),
        select_run_manifest_backend,
        lambda: select_last_known_good_backend(state_file=tmp_path / "lkg.json"),
        lambda: select_scheduler_state_backend(path=tmp_path / "sched.json"),
        verify_evidence_backend_ready,
    ):
        with pytest.raises(EvidenceBackendError):
            factory()


def test_unreachable_postgres_fails_closed(monkeypatch):
    """AC-6: an unreachable instance raises rather than falling back."""
    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_BACKEND", "postgres")
    # Port 1 is reserved and never serves PostgreSQL.
    monkeypatch.setenv(
        "SECURITYEXPERT_EVIDENCE_POSTGRES_DSN", "postgresql://nobody@127.0.0.1:1/nodb?connect_timeout=2"
    )
    with pytest.raises(EvidenceBackendError):
        verify_evidence_backend_ready()


def test_unsupported_backend_name_is_rejected(monkeypatch, tmp_path):
    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_BACKEND", "sqlite")
    with pytest.raises(EvidenceBackendError):
        select_config_snapshot_backend(root=tmp_path)


def test_filesystem_preflight_never_imports_the_driver(monkeypatch):
    """The default path must not require psycopg to be installed at all."""
    monkeypatch.delenv("SECURITYEXPERT_EVIDENCE_BACKEND", raising=False)
    monkeypatch.setitem(sys.modules, "psycopg", None)
    verify_evidence_backend_ready()  # must not raise


# ---------------------------------------------------------------------------
# Filesystem backend keeps today's exact on-disk contract (D7)
# ---------------------------------------------------------------------------

def test_filesystem_snapshot_layout_is_unchanged(tmp_path, monkeypatch):
    monkeypatch.delenv("SECURITYEXPERT_EVIDENCE_BACKEND", raising=False)
    store = ConfigEvidenceStore(tmp_path / "configs")
    result = store.write_xml_snapshot(
        source="panos-direct", entity_id="SER1", artifact_type="effective",
        artifact_name="effective.xml", content=XML_A, method="test",
    )
    assert (result.directory / "metadata.json").exists()
    assert (result.directory / "sha256.txt").exists()
    assert (result.directory / "effective.xml.ref.json").exists()
    # The payload blob is never inside the snapshot directory.
    assert not (result.directory / "effective.xml").exists()
    assert result.artifact_path.is_file()


def test_last_known_good_filesystem_still_writes_one_file_per_run(tmp_path):
    """D7: buffered puts, exactly one whole-file write on commit."""
    state_file = tmp_path / "state" / "last_known_good.json"
    backend = FilesystemLastKnownGoodBackend(state_file)
    backend.put_entity(source="cp", entity_key="gw1", item={"device": "gw1"}, last_successful_collection="2026-01-01T00:00:00+00:00")
    backend.put_entity(source="cp", entity_key="gw2", item={"device": "gw2"}, last_successful_collection=None)
    assert not state_file.exists(), "nothing may be written before commit()"

    backend.commit()
    stored = json.loads(state_file.read_text(encoding="utf-8"))
    assert stored["schema_version"] == "0.5"
    assert set(stored["entities"]["cp"]) == {"gw1", "gw2"}
    assert stored["entities"]["cp"]["gw1"]["item"]["device"] == "gw1"

    # A fresh backend over the same file sees the committed state.
    assert FilesystemLastKnownGoodBackend(state_file).get_entity(source="cp", entity_key="gw2") is not None


# ---------------------------------------------------------------------------
# Not-applicable gates (AC-7)
# ---------------------------------------------------------------------------

def test_storage_tools_report_not_applicable_on_postgres(monkeypatch):
    from utils import config_storage

    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_BACKEND", "postgres")
    analysis = config_storage.analyze_configuration_storage()
    dedup = config_storage.deduplicate_legacy_storage()
    for report in (analysis, dedup):
        assert report["status"] == "not_applicable_on_postgres_backend"
        assert report["evidence_backend"] == "postgres"
        # A misleading zero-work result must not be reported instead.
        assert "legacy_payload_files" not in report


def test_compliance_reconstruction_refuses_non_filesystem_backend(monkeypatch):
    """Amendment A8: a silent zero-bucket result would read as 'no history'."""
    from utils.compliance_trend_reconstruction import reconstruct_pan_baseline_records

    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_BACKEND", "postgres")
    with pytest.raises(EvidenceBackendError):
        reconstruct_pan_baseline_records()


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


def _reset_tables() -> None:
    import psycopg

    with psycopg.connect(_postgres_dsn(), autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "DROP TABLE IF EXISTS config_snapshot, run_manifest, "
                "last_known_good_entity, scheduler_state CASCADE"
            )


@pytest.fixture()
def pg_env(monkeypatch):
    _reset_tables()
    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_BACKEND", "postgres")
    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_POSTGRES_DSN", _postgres_dsn())
    yield _postgres_dsn()


@requires_postgres
def test_preflight_creates_every_table(pg_env):
    import psycopg

    verify_evidence_backend_ready()
    with psycopg.connect(pg_env, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
            )
            tables = {row[0] for row in cur.fetchall()}
    assert {"config_snapshot", "run_manifest", "last_known_good_entity", "scheduler_state"} <= tables


@requires_postgres
def test_snapshot_metadata_round_trip_matches_filesystem(pg_env, tmp_path, monkeypatch):
    """AC-2: identical reader-visible shapes from both backends."""
    monkeypatch.delenv("SECURITYEXPERT_EVIDENCE_BACKEND", raising=False)
    # Separate artifact roots so both writes create their own blob; a shared
    # root would make the second a de-duplicated reuse and the storage
    # sub-dict would legitimately differ.
    fs_store = ConfigEvidenceStore(tmp_path / "configs", tmp_path / "blobs-fs")
    fs_store.write_xml_snapshot(
        source="panos-direct", entity_id="SER1", artifact_type="effective",
        artifact_name="effective.xml", content=XML_A, method="test",
    )
    fs_rows = fs_store.backend.list_snapshots(source="panos-direct", entity_id="SER1")

    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_BACKEND", "postgres")
    monkeypatch.setenv("SECURITYEXPERT_EVIDENCE_POSTGRES_DSN", pg_env)
    pg_store = ConfigEvidenceStore(tmp_path / "configs-pg", tmp_path / "blobs-pg")
    pg_store.write_xml_snapshot(
        source="panos-direct", entity_id="SER1", artifact_type="effective",
        artifact_name="effective.xml", content=XML_A, method="test",
    )
    pg_rows = pg_store.backend.list_snapshots(source="panos-direct", entity_id="SER1")

    assert len(fs_rows) == len(pg_rows) == 1
    fs_meta, pg_meta = fs_rows[0][1], pg_rows[0][1]
    # Everything except the per-write timestamp/id must be identical.
    volatile = {"collected_at", "storage"}
    assert {k: v for k, v in fs_meta.items() if k not in volatile} == {
        k: v for k, v in pg_meta.items() if k not in volatile
    }
    # The storage sub-dict matches too, apart from the per-root object path.
    assert {k: v for k, v in fs_meta["storage"].items() if k != "object_path"} == {
        k: v for k, v in pg_meta["storage"].items() if k != "object_path"
    }


@requires_postgres
def test_change_state_and_history_work_on_postgres(pg_env, tmp_path):
    """first → same → changed, and the history timeline, over the shared store."""
    store = ConfigEvidenceStore(tmp_path / "configs")
    first = store.write_xml_snapshot(
        source="panos-direct", entity_id="SER1", artifact_type="effective",
        artifact_name="effective.xml", content=XML_A, method="test",
    )
    same = store.write_xml_snapshot(
        source="panos-direct", entity_id="SER1", artifact_type="effective",
        artifact_name="effective.xml", content=XML_A, method="test",
    )
    changed = store.write_xml_snapshot(
        source="panos-direct", entity_id="SER1", artifact_type="effective",
        artifact_name="effective.xml", content=XML_B, method="test",
    )
    assert [first.change_state, same.change_state, changed.change_state] == ["first", "same", "changed"]
    assert changed.previous_sha256 == first.sha256

    history = ConfigHistoryService(
        config_root=tmp_path / "configs", artifact_root=store.artifact_root
    ).get_device_history(source="panos-direct", entity_id="SER1", artifact_type="effective")
    assert history.status == "available"
    assert len(history.artifacts[0].events) == 3
    # Newest first (amendment A3).
    assert history.artifacts[0].events[0].id == changed.directory.name


@requires_postgres
def test_blobs_stay_on_the_volume(pg_env, tmp_path):
    """AC-7: payload blobs are never moved into Postgres."""
    import psycopg

    store = ConfigEvidenceStore(tmp_path / "configs")
    result = store.write_xml_snapshot(
        source="panos-direct", entity_id="SER1", artifact_type="effective",
        artifact_name="effective.xml", content=XML_A, method="test",
    )
    assert result.artifact_path.is_file()
    assert result.artifact_path.read_bytes() == XML_A

    with psycopg.connect(pg_env, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT metadata_json::text FROM config_snapshot")
            stored = cur.fetchone()[0]
    assert XML_A.decode() not in stored, "payload bytes must never reach Postgres"


@requires_postgres
def test_run_manifest_round_trip(pg_env, tmp_path):
    """AC-4: the exact manifest dict RunContext builds, including job metadata."""
    import psycopg

    from utils.run_context import RunContext

    ctx = RunContext.create(data_root=tmp_path / "data", output_root=tmp_path / "out")
    ctx.set_job_metadata("job_abc123", "scheduled", coordinator_decision="admitted", effective_scope="cp")
    ctx.write_manifest(status="completed")

    with psycopg.connect(pg_env, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT run_id, status, job_id, manifest_json FROM run_manifest")
            run_id, status, job_id, manifest = cur.fetchone()

    assert (run_id, status, job_id) == (ctx.run_id, "completed", "job_abc123")
    assert manifest["provenance"] == "scheduled"
    assert manifest["coordinator_decision"] == "admitted"
    assert manifest["effective_scope"] == "cp"
    assert manifest["stages"]["cp"]["status"] == "pending"


@requires_postgres
def test_scheduler_state_round_trip(pg_env, tmp_path):
    """AC-5: dict[str, datetime] survives the round trip unchanged."""
    from datetime import datetime, timezone

    from utils.collection_executor import is_workflow_due, load_scheduler_state, write_scheduler_state

    data_root = tmp_path / "data"
    assert load_scheduler_state(data_root) == {}

    completed = datetime(2026, 1, 1, 12, 0, tzinfo=timezone.utc)
    write_scheduler_state(data_root, {"checkpoint": completed})
    reloaded = load_scheduler_state(data_root)
    assert reloaded == {"checkpoint": completed}

    # And it still drives the due-check identically.
    class _W:
        interval_minutes = 60

    assert not is_workflow_due(_W(), reloaded["checkpoint"], now=completed)
    assert is_workflow_due(
        _W(), reloaded["checkpoint"], now=datetime(2026, 1, 1, 14, 0, tzinfo=timezone.utc)
    )


@requires_postgres
def test_last_known_good_per_entity_writes(pg_env):
    backend = select_last_known_good_backend(state_file=Path("/nonexistent/unused.json"))
    backend.put_entity(source="cp", entity_key="gw1", item={"device": "gw1"}, last_successful_collection="2026-01-01T00:00:00+00:00")
    # Postgres writes immediately; commit() is a no-op, not the durability point.
    assert select_last_known_good_backend(
        state_file=Path("/nonexistent/unused.json")
    ).get_entity(source="cp", entity_key="gw1")["item"]["device"] == "gw1"
    backend.commit()


_CONCURRENT_WRITER = """
import os, sys
sys.path.insert(0, {repo!r})
os.environ["SECURITYEXPERT_EVIDENCE_BACKEND"] = "postgres"
os.environ["SECURITYEXPERT_EVIDENCE_POSTGRES_DSN"] = {dsn!r}
from pathlib import Path
from utils.evidence_backend import select_last_known_good_backend

backend = select_last_known_good_backend(state_file=Path("/nonexistent/unused.json"))
for i in range({count}):
    backend.put_entity(
        source="cp",
        entity_key="{prefix}-%d" % i,
        item={{"device": "{prefix}-%d" % i}},
        last_successful_collection="2026-01-01T00:00:00+00:00",
    )
backend.commit()
"""


@requires_postgres
def test_concurrent_processes_never_lose_each_others_entities(pg_env, tmp_path):
    """AC-3 / amendment A1 — the reason this build exists.

    Two real OS processes each write a disjoint set of entities at the same
    time, exactly as two worker containers collecting different slices of a
    fleet would. Every entity from both must survive. Threads would not prove
    this: the hazard is a lost update between separate processes, each with
    its own view of the store.
    """
    import psycopg

    procs = [
        subprocess.Popen(
            [sys.executable, "-c", _CONCURRENT_WRITER.format(
                repo=str(REPO_ROOT), dsn=pg_env, count=40, prefix=prefix,
            )],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        for prefix in ("alpha", "beta")
    ]
    for proc in procs:
        _, err = proc.communicate(timeout=120)
        assert proc.returncode == 0, err.decode()

    with psycopg.connect(pg_env, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT entity_key FROM last_known_good_entity WHERE source = 'cp'")
            keys = {row[0] for row in cur.fetchall()}

    expected = {f"alpha-{i}" for i in range(40)} | {f"beta-{i}" for i in range(40)}
    assert keys == expected, f"lost {len(expected - keys)} entities to a concurrent writer"
