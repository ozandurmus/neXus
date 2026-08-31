"""RB.3b step 5 — the device-touching core of the CP Gaia backup collector.

Contract: ``docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md`` (correctness
contract, AC-1…AC-6, AC-12, AC-14), ``docs/design/BACKUP_RECOVERY_CONTRACTS.md``
§7.3 / §7.4 / §7.7 / §7.8 / §9.12 / §9.13,
``docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`` §10 (a) / (f) / (g), and
amendment **C6** (§9.12 for a workflow with no scheduler entry).

Every device interaction runs against an in-process fake SSH/SCP session —
**never a live device** (contract §11 / RB.2's own rule). The offline gate
layer (allowlist, VSX, platform, ``software_version``, the §7.7 parser) is
covered by ``tests/test_rb3b_cp_backup_collector.py``; the durable ledger's own
unit battery is ``tests/test_rb3b_operational_ledger.py``.
"""
from __future__ import annotations

import gzip
import json
from datetime import datetime, timedelta, timezone

import pytest

import checkpoint.checkpoint_recovery_collector as cc
from checkpoint.checkpoint_recovery_collector import CheckpointGaiaBackupCollector
from utils import recovery_store
from utils.collection_executor import ALLOWLISTED_WORKFLOWS
from utils.evidence_backend import FilesystemOperationalWriteLedgerBackend
from utils.recovery_collect import (
    RecoveryCollectionError,
    RecoveryCollectionRequest,
    RecoveryCollectionSkipped,
    RecoveryCollectionTarget,
    run_recovery_collection,
)
from utils.recovery_operational_ledger import RecoveryOperationalLedger
from utils.runtime_paths import resolve_recovery_root, resolve_runtime_paths

pytestmark = pytest.mark.recovery

_NOW = datetime(2026, 8, 31, 12, 0, 0, tzinfo=timezone.utc)
_CREDS = {
    "SECURITYEXPERT_CP_BACKUP_SSH_USERNAME": "svc-backup",
    "SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD": "s3cr3t",
    "SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES": "gw-1",
}
_ARCHIVE_PLAINTEXT = gzip.compress(b"fake gaia system backup tar payload " * 64)
_ARCHIVE_NAME = "backup_gw-1_31_8_2026_11_59.tgz"
_ADD_BACKUP_OK_STDOUT = (
    "Creating backup package. Use 'show backup status' to monitor progress.\n"
    f"Backup file was successfully created: /var/log/CPbackup/backups/{_ARCHIVE_NAME}\n"
)
_DF_OK = (
    "Filesystem          1024-blocks     Used Available Capacity Mounted on\n"
    "/dev/mapper/vg-lv_current  20961280  3512096  16384512      18% /\n"
    "/dev/mapper/vg-lv_log      52403200  1048576  48708000       3% /var/log\n"
)
_DF_FULL = (
    "Filesystem          1024-blocks     Used Available Capacity Mounted on\n"
    "/dev/mapper/vg-lv_log      52403200 51890000    512000      99% /var/log\n"
)


def _ok(stdout: str = "") -> dict:
    return {"success": True, "error_class": "none", "stdout": stdout, "stderr": "",
            "timeout": False, "exit_status": 0}


def _timeout() -> dict:
    return {"success": False, "error_class": "timeout", "stdout": "", "stderr": "",
            "timeout": True, "exit_status": None}


def _cli_rejected() -> dict:
    return {"success": False, "error_class": "cli_rejected",
            "stdout": "clish: Invalid command: 'add backup local'", "stderr": "",
            "timeout": False, "exit_status": 1}


def _is_free(cmd: str) -> bool:
    return "show diskspace" in cmd or cmd.startswith("df -P")


def _is_add(cmd: str) -> bool:
    return cmd.endswith("add backup local'") or cmd == "add backup local"


def _is_delete(cmd: str) -> bool:
    return cmd.startswith("rm -f -- ") or "delete backup" in cmd


class _FakeSession:
    """Mirrors ``checkpoint_recovery_collector.BackupSshSession``'s surface."""

    def __init__(self, *, free_stdout: str = _DF_OK, add_results=None,
                 add_stdout: str = _ADD_BACKUP_OK_STDOUT, archive_bytes: bytes = _ARCHIVE_PLAINTEXT,
                 present: bool = True, delete_succeeds: bool = True, fetch_raises: bool = False,
                 device_size: int | None = None):
        self.commands: list[str] = []
        self.closed = False
        self._free_stdout = free_stdout
        self._add_results = list(add_results or [])
        self._add_stdout = add_stdout
        self._archive_bytes = archive_bytes
        self._present = present
        self._delete_succeeds = delete_succeeds
        self._fetch_raises = fetch_raises
        self._device_size = device_size if device_size is not None else len(archive_bytes)

    def run(self, command: str, timeout: int) -> dict:
        self.commands.append(command)
        if _is_free(command):
            return _ok(self._free_stdout) if self._free_stdout else _timeout()
        if _is_add(command):
            if self._add_results:
                return self._add_results.pop(0)
            return _ok(self._add_stdout)
        if _is_delete(command):
            if self._delete_succeeds:
                self._present = False
            return _ok("")
        return _ok("")

    def remote_size(self, remote_path: str, timeout: int) -> int | None:
        return self._device_size if self._present else None

    def fetch(self, remote_path: str, timeout: int) -> bytes:
        if self._fetch_raises:
            raise OSError("simulated SFTP transport failure")
        return self._archive_bytes

    def close(self) -> None:
        self.closed = True


def _paths(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir(exist_ok=True)
    (repo / "main.py").write_text("", encoding="utf-8")
    runtime = resolve_runtime_paths(str(tmp_path / "runtime"), environ={}, repository_root=repo)
    recovery = resolve_recovery_root(
        str(tmp_path / "recovery"), environ={}, repository_root=repo, runtime_root=runtime.runtime_root
    )
    return runtime, recovery


def _ledger(tmp_path) -> RecoveryOperationalLedger:
    return RecoveryOperationalLedger(
        FilesystemOperationalWriteLedgerBackend(tmp_path / "state" / "recovery_operational_ledger.json")
    )


def _target(entity_id="gw-1", **row):
    row.setdefault("management_ip", "192.0.2.1")
    row.setdefault("device", entity_id)
    return RecoveryCollectionTarget(entity_id=entity_id, vendor="checkpoint", row=row)


def _collector(tmp_path, *, session=None, ledger=None, clock=None, session_factory=None,
               prior_sizes=None, run_id="run-1"):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    if session_factory is None:
        the_session = session if session is not None else _FakeSession()
        session_factory = lambda _pt: the_session  # noqa: E731
    return CheckpointGaiaBackupCollector(
        env=dict(_CREDS),
        ledger=ledger if ledger is not None else _ledger(tmp_path),
        recovery_paths=recovery, vault_key=vault_key, vault_key_id=vault_key_id, run_id=run_id,
        platform_by_entity={"gw-1": "gaia"},
        prior_backup_sizes_by_entity=prior_sizes or {},
        session_factory=session_factory,
        clock=clock or (lambda: _NOW),
    ), recovery


# ---------------------------------------------------------------------------
# AC-2 — full success path: fetch, verify, store, delete, ledger completed
# ---------------------------------------------------------------------------

def test_ac2_success_stores_then_deletes_and_records_completed(tmp_path):
    session = _FakeSession()
    collector, recovery = _collector(tmp_path, session=session)
    ledger = collector._ledger

    plaintext, meta = collector.collect(_target(sw_version="R81.10"))

    assert plaintext == _ARCHIVE_PLAINTEXT
    assert meta["class"] == "cp_gaia_backup"
    assert meta["software_version"] == "R81.10"
    assert meta["stored_artifact_id"]
    # order: free-space read -> add backup local -> delete
    kinds = [("free" if _is_free(c) else "add" if _is_add(c) else "del" if _is_delete(c) else "?") for c in session.commands]
    assert kinds[0] == "free"
    assert "add" in kinds and "del" in kinds
    assert kinds.index("add") < kinds.index("del")
    # artifact really landed in the store, before the delete
    dirs = recovery_store.list_artifact_dirs(recovery)
    assert len(dirs) == 1
    manifest = recovery_store.read_manifest(dirs[0])
    assert manifest["device"]["software_version"] == "R81.10"
    assert manifest["artifact"]["vendor_native_filename"] == "cp_gaia_backup.tgz"
    # ledger recorded exactly one 'completed' entry
    last = ledger.last_execution(entity_id="gw-1", command_class="cp_gaia_backup")
    assert last is not None and last.outcome == "completed" and last.run_id == "run-1"
    assert session.closed is True


def test_ac2_archive_name_never_reaches_manifest_or_meta(tmp_path):
    collector, recovery = _collector(tmp_path)
    plaintext, meta = collector.collect(_target(sw_version="R81.10"))
    blob = json.dumps(meta) + json.dumps(recovery_store.read_manifest(recovery_store.list_artifact_dirs(recovery)[0]))
    assert _ARCHIVE_NAME not in blob
    assert ".tgz" in blob  # only the generic vendor_native_filename


# ---------------------------------------------------------------------------
# AC-1 / §9.10 — precondition abort: no `add backup local` is ever sent
# ---------------------------------------------------------------------------

def test_ac1_insufficient_free_space_aborts_before_any_write(tmp_path):
    session = _FakeSession(free_stdout=_DF_FULL)
    collector, _ = _collector(tmp_path, session=session)
    with pytest.raises(RecoveryCollectionError, match="free .* < required"):
        collector.collect(_target(sw_version="R81.10"))
    assert not any(_is_add(c) for c in session.commands)
    assert collector._ledger.last_execution(entity_id="gw-1", command_class="cp_gaia_backup") is None


def test_ac1_unparseable_free_space_aborts_before_any_write(tmp_path):
    session = _FakeSession(free_stdout="garbage; device said no")
    collector, _ = _collector(tmp_path, session=session)
    with pytest.raises(RecoveryCollectionError, match="UNKNOWN"):
        collector.collect(_target(sw_version="R81.10"))
    assert not any(_is_add(c) for c in session.commands)


def test_ac1_three_times_prior_backup_size_is_enforced(tmp_path):
    # largest prior backup 20 GiB -> needs 60 GiB free; /var/log has ~47 GiB
    session = _FakeSession()
    collector, _ = _collector(tmp_path, session=session, prior_sizes={"gw-1": [20 * 1024 * 1024 * 1024]})
    with pytest.raises(RecoveryCollectionError, match="< required"):
        collector.collect(_target(sw_version="R81.10"))
    assert not any(_is_add(c) for c in session.commands)


# ---------------------------------------------------------------------------
# AC-3 — cleanup on failure; CLEANUP_FAILED -> endpoint ineligible
# ---------------------------------------------------------------------------

def test_ac3_fetch_failure_still_deletes_and_records_failed(tmp_path):
    session = _FakeSession(fetch_raises=True)
    collector, recovery = _collector(tmp_path, session=session)
    with pytest.raises(RecoveryCollectionError, match="fetch failed"):
        collector.collect(_target(sw_version="R81.10"))
    assert any(_is_delete(c) for c in session.commands)          # §7.3 point 13
    assert recovery_store.list_artifact_dirs(recovery) == []     # no store write
    last = collector._ledger.last_execution(entity_id="gw-1", command_class="cp_gaia_backup")
    assert last is not None and last.outcome == "failed"


def test_ac3_fetch_and_delete_both_fail_marks_endpoint_ineligible(tmp_path):
    session = _FakeSession(fetch_raises=True, delete_succeeds=False)
    collector, _ = _collector(tmp_path, session=session)
    with pytest.raises(RecoveryCollectionError, match="CLEANUP_FAILED"):
        collector.collect(_target(sw_version="R81.10"))
    last = collector._ledger.last_execution(entity_id="gw-1", command_class="cp_gaia_backup")
    assert last is not None and last.outcome == "cleanup_failed"

    # a later run — even outside the 24 h window — is refused as INELIGIBLE
    later = _NOW + timedelta(days=3)
    session2 = _FakeSession()
    collector2, _ = _collector(tmp_path, session=session2, ledger=collector._ledger, clock=lambda: later)
    with pytest.raises(RecoveryCollectionError, match="INELIGIBLE"):
        collector2.collect(_target(sw_version="R81.10"))
    assert session2.commands == []  # zero device contact


def test_ac3_unparseable_archive_name_is_cleanup_failed_not_a_guess(tmp_path):
    session = _FakeSession(add_stdout="Backup created successfully.\n")  # no parseable name
    collector, _ = _collector(tmp_path, session=session)
    with pytest.raises(RecoveryCollectionError, match="archive name could not be parsed"):
        collector.collect(_target(sw_version="R81.10"))
    assert not any(_is_delete(c) for c in session.commands)  # never guesses a name to delete
    last = collector._ledger.last_execution(entity_id="gw-1", command_class="cp_gaia_backup")
    assert last is not None and last.outcome == "cleanup_failed"


# ---------------------------------------------------------------------------
# AC-4 — deletion targets ONLY the name this run created
# ---------------------------------------------------------------------------

def test_ac4_delete_uses_exactly_the_created_name(tmp_path):
    session = _FakeSession()
    collector, _ = _collector(tmp_path, session=session)
    collector.collect(_target(sw_version="R81.10"))
    delete_cmds = [c for c in session.commands if _is_delete(c)]
    assert delete_cmds
    assert all(_ARCHIVE_NAME in c for c in delete_cmds)
    assert all("*" not in c and "?" not in c for c in delete_cmds)  # never a glob


def test_ac4_delete_archive_refuses_an_unsafe_token(tmp_path):
    collector, _ = _collector(tmp_path)
    session = _FakeSession()
    for bad in ("../etc/passwd", "backup_x.tgz; rm -rf /", "backup_*.tgz", "", "backup_x.tar"):
        assert collector._delete_archive(session, bad) is False
    assert session.commands == []  # nothing was ever sent for a bad token


# ---------------------------------------------------------------------------
# AC-5 — no retry of `add backup local`
# ---------------------------------------------------------------------------

def test_ac5_ambiguous_add_backup_failure_is_not_retried(tmp_path):
    session = _FakeSession(add_results=[_timeout()])
    collector, _ = _collector(tmp_path, session=session)
    with pytest.raises(RecoveryCollectionError):
        collector.collect(_target(sw_version="R81.10"))
    assert sum(1 for c in session.commands if _is_add(c)) == 1  # sent exactly once


def test_ac5_clean_cli_rejection_tries_the_other_wire_form_not_a_retry(tmp_path):
    # first (clish) form cleanly rejected -> the bare form is tried once; that
    # is a shell-wrapper fallthrough, not a retry (the rejection created nothing)
    session = _FakeSession(add_results=[_cli_rejected(), _ok(_ADD_BACKUP_OK_STDOUT)])
    collector, recovery = _collector(tmp_path, session=session)
    collector.collect(_target(sw_version="R81.10"))
    add_forms = [c for c in session.commands if _is_add(c)]
    assert add_forms == ["clish -c 'add backup local'", "add backup local"]
    assert len(recovery_store.list_artifact_dirs(recovery)) == 1


def test_ac5_both_wire_forms_cleanly_rejected_creates_nothing(tmp_path):
    session = _FakeSession(add_results=[_cli_rejected(), _cli_rejected()])
    collector, _ = _collector(tmp_path, session=session)
    with pytest.raises(RecoveryCollectionError, match="rejected by the device on every frozen"):
        collector.collect(_target(sw_version="R81.10"))
    # nothing created -> no cleanup, no ledger entry (§10 g)
    assert not any(_is_delete(c) for c in session.commands)
    assert collector._ledger.last_execution(entity_id="gw-1", command_class="cp_gaia_backup") is None


# ---------------------------------------------------------------------------
# AC-6 / §9.13 (a)(b)(c) — the durable ledger gates device contact
# ---------------------------------------------------------------------------

def test_ac6_second_run_inside_window_skips_with_zero_device_contact(tmp_path):
    ledger = _ledger(tmp_path)
    ledger.record_execution(entity_id="gw-1", command_class="cp_gaia_backup",
                            executed_at=_NOW - timedelta(hours=2), outcome="completed", run_id="prev")

    def _boom(_pt):
        raise AssertionError("device must not be contacted")

    collector, _ = _collector(tmp_path, ledger=ledger, session_factory=_boom)
    with pytest.raises(RecoveryCollectionSkipped, match="skipped_recent_backup"):
        collector.collect(_target(sw_version="R81.10"))


def test_ac6_unreadable_ledger_blocks_the_run_with_no_device_contact(tmp_path):
    p = tmp_path / "state" / "recovery_operational_ledger.json"
    p.parent.mkdir(parents=True)
    p.write_text("{ not valid json", encoding="utf-8")
    ledger = RecoveryOperationalLedger(FilesystemOperationalWriteLedgerBackend(p))

    def _boom(_pt):
        raise AssertionError("device must not be contacted")

    collector, _ = _collector(tmp_path, ledger=ledger, session_factory=_boom)
    with pytest.raises(RecoveryCollectionError, match="operational_ledger_unreadable"):
        collector.collect(_target(sw_version="R81.10"))


def test_ac6_absent_ledger_proceeds(tmp_path):
    # a fresh ledger (no file) -> the happy path runs
    session = _FakeSession()
    collector, recovery = _collector(tmp_path, session=session)
    collector.collect(_target(sw_version="R81.10"))
    assert len(recovery_store.list_artifact_dirs(recovery)) == 1


def test_ac6_stale_entry_outside_window_proceeds(tmp_path):
    ledger = _ledger(tmp_path)
    ledger.record_execution(entity_id="gw-1", command_class="cp_gaia_backup",
                            executed_at=_NOW - timedelta(hours=30), outcome="completed", run_id="old")
    session = _FakeSession()
    collector, recovery = _collector(tmp_path, session=session, ledger=ledger)
    collector.collect(_target(sw_version="R81.10"))
    assert len(recovery_store.list_artifact_dirs(recovery)) == 1


# ---------------------------------------------------------------------------
# §9.13 (f)(g) — ledger read + write happen inside run_under_admission,
#                and record_execution fires iff `add backup local` was sent
# ---------------------------------------------------------------------------

class _SpyLedger:
    def __init__(self, inner, log):
        self._inner, self._log = inner, log

    def last_execution(self, **kw):
        self._log.append("ledger.read")
        return self._inner.last_execution(**kw)

    def within_window(self, **kw):
        self._log.append("ledger.read")
        return self._inner.within_window(**kw)

    def record_execution(self, **kw):
        self._log.append("ledger.write")
        return self._inner.record_execution(**kw)


def _unified():
    return [{"source": "cp", "device": "gw-1", "management_ip": "192.0.2.1",
             "software_version": "R81.10", "inventory_status": {"data_state": "live"}}]


def test_9_13_f_ledger_read_and_write_are_inside_the_admission_section(tmp_path):
    events: list[str] = []
    spy = _SpyLedger(_ledger(tmp_path), events)
    session = _FakeSession()
    collector, _ = _collector(tmp_path, session=session, ledger=spy)

    def run_under_admission(entity_id, operation):
        events.append("admission.enter")
        try:
            return operation()
        finally:
            events.append("admission.exit")

    result = run_recovery_collection(
        RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "targets", "entity_ids": ["gw-1"]}),
        unified_devices=_unified(), collector=collector,
        recovery_paths=collector._recovery_paths, vault_key=collector._vault_key,
        vault_key_id=collector._vault_key_id, run_under_admission=run_under_admission,
    )
    assert result.collected_count == 1
    assert events[0] == "admission.enter" and events[-1] == "admission.exit"
    assert "ledger.read" in events[1:-1] and "ledger.write" in events[1:-1]
    assert events.index("ledger.read") < events.index("ledger.write")


def test_9_13_g_record_execution_not_called_when_aborted_before_add_backup(tmp_path):
    events: list[str] = []
    spy = _SpyLedger(_ledger(tmp_path), events)
    session = _FakeSession(free_stdout=_DF_FULL)  # free-space precondition fails
    collector, _ = _collector(tmp_path, session=session, ledger=spy)
    with pytest.raises(RecoveryCollectionError):
        collector.collect(_target(sw_version="R81.10"))
    assert "ledger.read" in events
    assert "ledger.write" not in events


# ---------------------------------------------------------------------------
# AC-12 — a collected artifact validates V1 + V2 and reaches V3 on version match
# ---------------------------------------------------------------------------

def test_ac12_collected_artifact_validates_to_v3_on_version_match(tmp_path):
    session = _FakeSession()
    collector, recovery = _collector(tmp_path, session=session)
    collector.collect(_target(sw_version="R81.10"))

    art_dir = recovery_store.list_artifact_dirs(recovery)[0]
    manifest = recovery_store.read_manifest(art_dir)
    updated = recovery_store.revalidate_artifact(
        art_dir, manifest, vault_key=collector._vault_key, unified_devices=_unified(),
    )
    validation = updated["validation"]
    assert validation["level"] == "V3"
    by_id = {c["id"]: c["result"] for c in validation["checks"]}
    assert by_id["sha256_match"] == "PASS" and by_id["size_band"] == "PASS"
    assert by_id["archive_openable"] == "PASS"           # real gzip payload
    assert by_id["inventory_version_match"] == "PASS"    # R81.10 == R81.10


# ---------------------------------------------------------------------------
# AC-14 — no plaintext temp file anywhere in a full run
# ---------------------------------------------------------------------------

def test_ac14_no_plaintext_artifact_touches_disk(tmp_path):
    session = _FakeSession()
    collector, recovery = _collector(tmp_path, session=session)
    collector.collect(_target(sw_version="R81.10"))

    hits = []
    for path in tmp_path.rglob("*"):
        if not path.is_file():
            continue
        try:
            blob = path.read_bytes()
        except OSError:
            continue
        if _ARCHIVE_PLAINTEXT in blob or gzip.decompress(_ARCHIVE_PLAINTEXT) in blob:
            hits.append(path.name)
    assert hits == []
    # the only artifact bytes on disk are the sealed ciphertext
    enc = (recovery_store.list_artifact_dirs(recovery)[0] / "artifact.enc").read_bytes()
    assert enc != _ARCHIVE_PLAINTEXT and _ARCHIVE_PLAINTEXT not in enc


# ---------------------------------------------------------------------------
# C6 / §9.12 — non-scheduled workflow: admission-coordinated, not allowlisted
# ---------------------------------------------------------------------------

def test_c6_recovery_cp_is_not_allowlisted_but_still_admission_coordinated(tmp_path):
    assert "recovery-cp" not in ALLOWLISTED_WORKFLOWS

    session = _FakeSession()
    collector, _ = _collector(tmp_path, session=session)
    admitted: list[str] = []

    def run_under_admission(entity_id, operation):
        admitted.append(entity_id)
        return operation()

    result = run_recovery_collection(
        RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "targets", "entity_ids": ["gw-1"]}),
        unified_devices=_unified(), collector=collector,
        recovery_paths=collector._recovery_paths, vault_key=collector._vault_key,
        vault_key_id=collector._vault_key_id, run_under_admission=run_under_admission,
    )
    assert admitted == ["gw-1"]                 # endpoint lock acquired for the CP path
    assert result.collected_count == 1


# ---------------------------------------------------------------------------
# orchestration: a skip is a first-class non-failure outcome
# ---------------------------------------------------------------------------

def test_skip_is_reported_as_status_skipped_not_failed(tmp_path):
    ledger = _ledger(tmp_path)
    ledger.record_execution(entity_id="gw-1", command_class="cp_gaia_backup",
                            executed_at=_NOW - timedelta(hours=1), outcome="completed", run_id="prev")
    collector, _ = _collector(tmp_path, ledger=ledger, session_factory=lambda _pt: _FakeSession())

    result = run_recovery_collection(
        RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "targets", "entity_ids": ["gw-1"]}),
        unified_devices=_unified(), collector=collector,
        recovery_paths=collector._recovery_paths, vault_key=collector._vault_key,
        vault_key_id=collector._vault_key_id, run_under_admission=lambda e, op: op(),
    )
    assert [o.status for o in result.outcomes] == ["skipped"]
    assert result.failed_count == 0
    assert result.skipped_count == 1
    assert "skipped_recent_backup" in result.outcomes[0].error


# ---------------------------------------------------------------------------
# fail-closed: no ledger / no store bound -> refuse before device contact
# ---------------------------------------------------------------------------

def test_fail_closed_without_store_binding(tmp_path):
    ledger = _ledger(tmp_path)
    collector = CheckpointGaiaBackupCollector(
        env=dict(_CREDS), ledger=ledger, platform_by_entity={"gw-1": "gaia"},
        session_factory=lambda _pt: (_ for _ in ()).throw(AssertionError("no device contact")),
        clock=lambda: _NOW,
    )
    with pytest.raises(RecoveryCollectionError, match="recovery store is not bound"):
        collector.collect(_target(sw_version="R81.10"))
