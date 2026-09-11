"""GOV.PO.3 -- scripts/orchestrator.py (docs/design/
GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md, FROZEN).

Covers the pure decision logic (dispatch/resume/refuse, merge-lock
acquire, phase reconciliation, task hashing, git-ref validation) without
spawning a real `claude -p` process or a real git worktree -- those are
exercised by the AC-5 demonstrations, not by this suite. A handful of
CLI-level tests wire the pure functions together with monkeypatched
git/spawn side effects to prove `start`/`status`/`merge-lock` plumbing.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import orchestrator as orch  # noqa: E402
import local_relay as lr  # noqa: E402


def _start_report(**overrides) -> dict:
    report = {
        "baseline": {"origin_main": "deadbeef"},
        "objective": "Exercise the orchestrator.",
        "scope": {"in": ["do the thing"], "out": ["not that thing"]},
        "movement_type": "VALIDATION",
        "requirements": ["exactly one schema"],
        "acceptance_criteria": ["round-trips cleanly"],
        "validation_plan": ["run the focused suite"],
        "invariants": ["repository stays authoritative"],
        "risks": [],
        "context_not_loaded": [],
        "recommended_reasoning": {"tier": "Normal", "reason": "deterministic implementation"},
        "git": {"lane": "feature/x", "base": "origin/main"},
        "merge_gate": "n/a",
        "deployment_direction": "local validation only",
        "output_contract": ["exactly one relay file"],
    }
    report.update(overrides)
    return report


def _make_relay(tmp_path: Path, movement: str = "TEST_MOVEMENT", **report_overrides) -> Path:
    """A real, schema-valid relay dir with one movement, next_actor=engineer
    (create()'s own default when the creating role is "po") -- built through
    local_relay.py itself so it is guaranteed valid, never hand-rolled JSON."""
    relay_dir = tmp_path / "relay"
    start = tmp_path / "start.json"
    start.write_text(json.dumps({
        "protocol_version": 2, "message_type": "SESSION_START", "movement": movement,
        "refs": [], "report": _start_report(**report_overrides),
    }), encoding="utf-8")
    rc = lr.main(["create", "--role", "po", "--start", str(start), "--dir", str(relay_dir)])
    assert rc == lr.EXIT_OK
    return relay_dir


class _FakeProc:
    def __init__(self, pid: int) -> None:
        self.pid = pid


def _dead_pid() -> int:
    proc = subprocess.Popen([sys.executable, "-c", "pass"])
    proc.wait()
    return proc.pid


# --- canonical hashing (section 3.5) ---------------------------------------

def test_task_hash_is_reproducible_for_identical_content():
    entry = {"seq": 1, "marker": "SESSION_START", "actor": "po", "timestamp": "t", "report": {"a": 1}}
    assert orch.task_hash(entry) == orch.task_hash(dict(entry))


def test_task_hash_changes_when_content_changes():
    entry = {"seq": 1, "marker": "SESSION_START", "actor": "po", "timestamp": "t", "report": {"a": 1}}
    other = {**entry, "report": {"a": 2}}
    assert orch.task_hash(entry) != orch.task_hash(other)


def test_task_hash_is_insensitive_to_key_order():
    a = {"seq": 1, "marker": "SESSION_START", "actor": "po", "timestamp": "t", "report": {"a": 1, "b": 2}}
    b = {"marker": "SESSION_START", "report": {"b": 2, "a": 1}, "seq": 1, "actor": "po", "timestamp": "t"}
    assert orch.task_hash(a) == orch.task_hash(b)


def test_canonical_json_matches_what_gets_written_to_disk(tmp_path):
    entry = {"seq": 1, "marker": "SESSION_START", "actor": "po", "timestamp": "t", "report": {"a": 1}}
    path = tmp_path / "approved_task.json"
    path.write_text(orch.canonical_json(entry), encoding="utf-8")
    rehashed = __import__("hashlib").sha256(path.read_bytes()).hexdigest()
    assert rehashed == orch.task_hash(entry)


# --- git-ref validation (mirrors GOV_PO_1_GATE_5's own restrictions) -------

@pytest.mark.parametrize("base,lane,ok", [
    ("origin/main", "feature/x", True),
    ("origin/main", "gov/po-sneaky", False),
    ("main", "feature/x", False),
    (None, "feature/x", False),
    ("origin/main", None, False),
    ("origin/main", "", False),
])
def test_validate_git_refs(base, lane, ok):
    allowed, _ = orch.validate_git_refs(base, lane)
    assert allowed is ok


# --- pid liveness -----------------------------------------------------------

def test_pid_alive_true_for_the_current_process():
    assert orch.pid_alive(os.getpid()) is True


def test_pid_alive_false_for_a_reaped_process():
    assert orch.pid_alive(_dead_pid()) is False


@pytest.mark.parametrize("pid", [0, None])
def test_pid_alive_false_for_falsy_pid(pid):
    assert orch.pid_alive(pid) is False


# --- decide_start (AC-1/AC-5: duplicate-start prevention, resume, refusal) -

def _relay_obj(next_actor="engineer"):
    return {"next_actor": next_actor}


def test_decide_start_refuses_when_not_engineers_turn():
    action, _ = orch.decide_start(relay_obj=_relay_obj("po"), existing_record=None,
                                   is_pid_alive=False, active_count=0, max_workers=3)
    assert action == "refuse"


def test_decide_start_dispatches_fresh_when_slot_free():
    action, _ = orch.decide_start(relay_obj=_relay_obj(), existing_record=None,
                                   is_pid_alive=False, active_count=0, max_workers=3)
    assert action == "dispatch"


def test_decide_start_refuses_fresh_dispatch_when_no_free_slot():
    action, reason = orch.decide_start(relay_obj=_relay_obj(), existing_record=None,
                                        is_pid_alive=False, active_count=3, max_workers=3)
    assert action == "refuse"
    assert "max_workers" in reason


def test_decide_start_refuses_duplicate_when_existing_run_is_alive():
    existing = {"pid": 123, "phase": orch.PHASE_RUNNING}
    action, reason = orch.decide_start(relay_obj=_relay_obj(), existing_record=existing,
                                        is_pid_alive=True, active_count=1, max_workers=3)
    assert action == "refuse"
    assert "already running" in reason


def test_decide_start_resumes_when_existing_run_is_dead_but_not_terminal():
    existing = {"pid": 123, "phase": orch.PHASE_RUNNING}
    action, _ = orch.decide_start(relay_obj=_relay_obj(), existing_record=existing,
                                   is_pid_alive=False, active_count=1, max_workers=3)
    assert action == "resume"


@pytest.mark.parametrize("phase", [orch.PHASE_DONE, orch.PHASE_FAILED, orch.PHASE_CANCELLED])
def test_decide_start_treats_a_terminal_prior_run_as_a_fresh_dispatch(phase):
    existing = {"pid": 123, "phase": phase}
    action, _ = orch.decide_start(relay_obj=_relay_obj(), existing_record=existing,
                                   is_pid_alive=False, active_count=0, max_workers=3)
    assert action == "dispatch"


def test_decide_start_a_terminal_prior_run_still_needs_a_free_slot():
    existing = {"pid": 123, "phase": orch.PHASE_FAILED}
    action, reason = orch.decide_start(relay_obj=_relay_obj(), existing_record=existing,
                                        is_pid_alive=False, active_count=3, max_workers=3)
    assert action == "refuse"
    assert "max_workers" in reason


# --- AC-4 (orchestrator_background_task_exit_race): resume recovery-note ---
# --- detection -- the exact live-observed pattern from relay/NXS-LOCAL-0018:
# --- staged-but-uncommitted changes, relay still at its own SESSION_START,
# --- pid dead. All pure -- git status is parsed/monkeypatched, never a real
# --- subprocess spawn.

def _relay_obj_with_entries(n: int) -> dict:
    return {"next_actor": "engineer", "entries": [{"marker": "SESSION_START"}] * n}


def test_relay_never_advanced_past_session_start_true_for_just_the_start_entry():
    assert orch.relay_never_advanced_past_session_start(_relay_obj_with_entries(1)) is True


def test_relay_never_advanced_past_session_start_false_once_anything_is_appended():
    assert orch.relay_never_advanced_past_session_start(_relay_obj_with_entries(2)) is False


def test_relay_never_advanced_past_session_start_true_when_entries_is_missing():
    assert orch.relay_never_advanced_past_session_start({}) is True


@pytest.mark.parametrize("status,expected", [
    ("", False),
    (" M unstaged.py\n", False),
    ("?? untracked.py\n", False),
    (" M unstaged.py\n?? untracked.py\n", False),
    ("M  staged.py\n", True),
    ("A  added.py\n", True),
    ("MM partially_staged.py\n", True),
    (" M unstaged.py\nA  staged.py\n", True),
])
def test_has_staged_uncommitted_changes(status, expected):
    assert orch.has_staged_uncommitted_changes(status) is expected


def test_needs_resume_recovery_note_fires_for_the_exact_pattern():
    assert orch.needs_resume_recovery_note(
        relay_obj=_relay_obj_with_entries(1), has_staged_uncommitted_changes=True,
        is_pid_alive=False,
    ) is True


def test_needs_resume_recovery_note_false_when_pid_is_alive():
    assert orch.needs_resume_recovery_note(
        relay_obj=_relay_obj_with_entries(1), has_staged_uncommitted_changes=True,
        is_pid_alive=True,
    ) is False


def test_needs_resume_recovery_note_false_when_nothing_is_staged():
    assert orch.needs_resume_recovery_note(
        relay_obj=_relay_obj_with_entries(1), has_staged_uncommitted_changes=False,
        is_pid_alive=False,
    ) is False


def test_needs_resume_recovery_note_false_once_relay_advanced_past_session_start():
    assert orch.needs_resume_recovery_note(
        relay_obj=_relay_obj_with_entries(2), has_staged_uncommitted_changes=True,
        is_pid_alive=False,
    ) is False


# --- merge-lock decision (section 3.8) --------------------------------------

def test_merge_lock_acquire_grants_when_free():
    granted, record, _ = orch.decide_merge_lock_acquire(
        lock_record=None, requester_movement="M1", requester_pid=1, now=100.0, ttl=600)
    assert granted
    assert record["movement_id"] == "M1"


def test_merge_lock_acquire_is_reentrant_for_the_same_movement():
    lock = {"movement_id": "M1", "pid": 1, "acquired_at": 100.0}
    granted, record, reason = orch.decide_merge_lock_acquire(
        lock_record=lock, requester_movement="M1", requester_pid=1, now=150.0, ttl=600)
    assert granted
    assert "re-entrant" in reason


def test_merge_lock_acquire_denies_a_live_other_holder():
    lock = {"movement_id": "M1", "pid": 1, "acquired_at": 100.0}
    granted, record, reason = orch.decide_merge_lock_acquire(
        lock_record=lock, requester_movement="M2", requester_pid=2, now=150.0, ttl=600,
        is_pid_alive=lambda pid: True)
    assert not granted
    assert record == lock
    assert "M1" in reason


def test_merge_lock_acquire_reclaims_a_lock_whose_holder_is_dead():
    lock = {"movement_id": "M1", "pid": 1, "acquired_at": 100.0}
    granted, record, reason = orch.decide_merge_lock_acquire(
        lock_record=lock, requester_movement="M2", requester_pid=2, now=150.0, ttl=600,
        is_pid_alive=lambda pid: False)
    assert granted
    assert record["movement_id"] == "M2"
    assert "reclaimed" in reason


def test_merge_lock_acquire_reclaims_a_stale_lock_even_if_holder_alive():
    lock = {"movement_id": "M1", "pid": 1, "acquired_at": 100.0}
    granted, record, reason = orch.decide_merge_lock_acquire(
        lock_record=lock, requester_movement="M2", requester_pid=2, now=100.0 + 601, ttl=600,
        is_pid_alive=lambda pid: True)
    assert granted
    assert "reclaimed" in reason


# --- phase reconciliation ----------------------------------------------------

@pytest.mark.parametrize("phase", [orch.PHASE_DONE, orch.PHASE_FAILED, orch.PHASE_CANCELLED])
def test_reconcile_phase_terminal_phases_never_change(phase):
    record = {"phase": phase, "retry_count": 0}
    assert orch.reconcile_phase(record, is_pid_alive_now=True, relay_status="OPEN", retry_limit=2) == phase


def test_reconcile_phase_relay_closed_becomes_done():
    record = {"phase": orch.PHASE_RUNNING, "retry_count": 0}
    assert orch.reconcile_phase(record, is_pid_alive_now=False, relay_status="CLOSED", retry_limit=2) == orch.PHASE_DONE


def test_reconcile_phase_dead_pid_past_retry_limit_becomes_failed():
    record = {"phase": orch.PHASE_RUNNING, "retry_count": 2}
    assert orch.reconcile_phase(record, is_pid_alive_now=False, relay_status="AWAITING_ENGINEER", retry_limit=2) == orch.PHASE_FAILED


def test_reconcile_phase_dead_pid_within_retry_budget_stays_running():
    record = {"phase": orch.PHASE_RUNNING, "retry_count": 1}
    assert orch.reconcile_phase(record, is_pid_alive_now=False, relay_status="AWAITING_ENGINEER", retry_limit=2) == orch.PHASE_RUNNING


def test_reconcile_phase_alive_pid_stays_running():
    record = {"phase": orch.PHASE_RUNNING, "retry_count": 0}
    assert orch.reconcile_phase(record, is_pid_alive_now=True, relay_status="AWAITING_ENGINEER", retry_limit=2) == orch.PHASE_RUNNING


# --- state-file IO ------------------------------------------------------------

def test_state_round_trips(tmp_path):
    state_dir = tmp_path / "state"
    record = {"movement_id": "NXS-LOCAL-0001", "phase": orch.PHASE_RUNNING, "pid": 1}
    orch._save_state(state_dir, "NXS-LOCAL-0001", record)
    assert orch._load_state(state_dir, "NXS-LOCAL-0001") == record


def test_load_state_returns_none_when_absent(tmp_path):
    assert orch._load_state(tmp_path / "state", "NXS-LOCAL-9999") is None


def test_active_count_only_counts_non_terminal_and_alive(tmp_path):
    state_dir = tmp_path / "state"
    orch._save_state(state_dir, "NXS-LOCAL-0001", {"movement_id": "NXS-LOCAL-0001", "phase": orch.PHASE_RUNNING, "pid": os.getpid()})
    orch._save_state(state_dir, "NXS-LOCAL-0002", {"movement_id": "NXS-LOCAL-0002", "phase": orch.PHASE_DONE, "pid": os.getpid()})
    orch._save_state(state_dir, "NXS-LOCAL-0003", {"movement_id": "NXS-LOCAL-0003", "phase": orch.PHASE_RUNNING, "pid": _dead_pid()})
    assert orch._active_count(state_dir) == 1


def test_active_count_excludes_the_named_movement(tmp_path):
    state_dir = tmp_path / "state"
    orch._save_state(state_dir, "NXS-LOCAL-0001", {"movement_id": "NXS-LOCAL-0001", "phase": orch.PHASE_RUNNING, "pid": os.getpid()})
    assert orch._active_count(state_dir, exclude_movement="NXS-LOCAL-0001") == 0


# --- CLI: merge-lock ----------------------------------------------------------

def test_merge_lock_cli_acquire_release_round_trip(tmp_path):
    state_dir = tmp_path / "state"
    rc = orch.main(["merge-lock", "acquire", "--movement", "NXS-LOCAL-0001",
                     "--pid", str(os.getpid()), "--state-dir", str(state_dir)])
    assert rc == orch.EXIT_OK

    rc2 = orch.main(["merge-lock", "acquire", "--movement", "NXS-LOCAL-0002",
                      "--pid", str(os.getpid()), "--state-dir", str(state_dir), "--wait", "0.2"])
    assert rc2 == orch.EXIT_REFUSED

    rc3 = orch.main(["merge-lock", "release", "--movement", "NXS-LOCAL-0002", "--state-dir", str(state_dir)])
    assert rc3 == orch.EXIT_REFUSED  # not the holder

    rc4 = orch.main(["merge-lock", "release", "--movement", "NXS-LOCAL-0001", "--state-dir", str(state_dir)])
    assert rc4 == orch.EXIT_OK

    rc5 = orch.main(["merge-lock", "acquire", "--movement", "NXS-LOCAL-0002",
                      "--pid", str(os.getpid()), "--state-dir", str(state_dir)])
    assert rc5 == orch.EXIT_OK


# --- CLI: status --------------------------------------------------------------

def test_status_cli_reports_no_records_as_empty_list(tmp_path, capsys):
    rc = orch.main(["status", "--state-dir", str(tmp_path / "state"), "--relay-dir", str(tmp_path / "relay")])
    assert rc == orch.EXIT_OK
    assert json.loads(capsys.readouterr().out) == []


def test_status_cli_usage_error_for_unknown_movement(tmp_path, capsys):
    rc = orch.main(["status", "--movement", "NXS-LOCAL-0001",
                     "--state-dir", str(tmp_path / "state"), "--relay-dir", str(tmp_path / "relay")])
    assert rc == orch.EXIT_USAGE


def test_status_cli_reconciles_phase_against_a_closed_relay(tmp_path, capsys):
    relay_dir = _make_relay(tmp_path, movement="M")
    relay_file = next(relay_dir.glob("*.json"))
    relay_id = json.loads(relay_file.read_text())["id"]
    report_file = tmp_path / "close.json"
    report_file.write_text(json.dumps({
        "completed": ["x"], "changed": ["x"], "preserved": ["x"],
        "validation": {"targeted": "1 passed", "affected": "1 passed", "full_regression": "n/a",
                       "privacy": "n/a", "state_consistency": "n/a", "diff_check": "n/a", "real_environment": "NOT_RUN"},
        "unresolved_risks": [], "state_updates": [],
        "next": {"movement": "n/a", "movement_type": "VALIDATION", "status": "done", "objective": "n/a"},
        "recommended_reasoning": {"tier": "Normal", "reason": "x"},
        "continuation": "SAME_SESSION",
        "integration": {"branch": "n/a", "head_sha": "n/a", "pr": None, "pr_url": None, "ci": "n/a",
                         "merge_state": "NOT_OPENED", "merge_commit": None, "merge_decision": "NOT_APPLICABLE"},
        "effects": {"main_py": "none", "ui": "none"},
    }), encoding="utf-8")
    assert lr.main(["append", "--file", str(relay_file), "--role", "engineer", "--marker", "SESSION_CLOSE",
                     "--report", str(report_file), "--outcome", "DONE"]) == lr.EXIT_OK

    state_dir = tmp_path / "state"
    orch._save_state(state_dir, relay_id, {"movement_id": relay_id, "phase": orch.PHASE_RUNNING,
                                            "pid": _dead_pid(), "retry_count": 0, "dispatch_seq": 1})
    capsys.readouterr()  # discard create/append's own stdout lines above
    rc = orch.main(["status", "--movement", relay_id, "--state-dir", str(state_dir), "--relay-dir", str(relay_dir)])
    assert rc == orch.EXIT_OK
    row = json.loads(capsys.readouterr().out)
    assert row["phase"] == orch.PHASE_DONE
    assert orch._load_state(state_dir, relay_id)["phase"] == orch.PHASE_DONE


# --- CLI: start (git/spawn side effects monkeypatched) ------------------------

def test_start_cli_dispatches_and_writes_a_state_record(tmp_path, monkeypatch):
    relay_dir = _make_relay(tmp_path)
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    state_dir = tmp_path / "state"
    worktrees_dir = tmp_path / "worktrees"

    monkeypatch.setattr(orch, "_git_rev_parse", lambda ref, cwd: "deadbeefcafe")
    monkeypatch.setattr(orch, "_git_worktree_add", lambda *a, **k: None)
    monkeypatch.setattr(orch, "install_prepush_hook", lambda *a, **k: None)
    monkeypatch.setattr(orch, "_spawn_engineer", lambda **kwargs: _FakeProc(os.getpid()))

    rc = orch.main(["start", "--movement", relay_id, "--relay-dir", str(relay_dir),
                     "--state-dir", str(state_dir), "--worktrees-dir", str(worktrees_dir),
                     "--profile", str(tmp_path / "profile.json")])
    assert rc == orch.EXIT_OK
    record = orch._load_state(state_dir, relay_id)
    assert record["phase"] == orch.PHASE_RUNNING
    assert record["revision"] == 1
    assert record["base_sha"] == "deadbeefcafe"
    assert record["branch"] == "feature/x"
    assert (worktrees_dir / relay_id / ".nexus" / "approved_task.json").is_file()
    assert (worktrees_dir / relay_id / ".nexus" / "movement_id.txt").read_text().strip() == relay_id


def test_start_cli_refuses_a_duplicate_launch(tmp_path, monkeypatch):
    relay_dir = _make_relay(tmp_path)
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    state_dir = tmp_path / "state"
    monkeypatch.setattr(orch, "_git_rev_parse", lambda ref, cwd: "sha")
    monkeypatch.setattr(orch, "_git_worktree_add", lambda *a, **k: None)
    monkeypatch.setattr(orch, "install_prepush_hook", lambda *a, **k: None)
    monkeypatch.setattr(orch, "_spawn_engineer", lambda **kwargs: _FakeProc(os.getpid()))

    args = ["start", "--movement", relay_id, "--relay-dir", str(relay_dir), "--state-dir", str(state_dir),
            "--worktrees-dir", str(tmp_path / "worktrees"), "--profile", str(tmp_path / "profile.json")]
    assert orch.main(args) == orch.EXIT_OK
    assert orch.main(args) == orch.EXIT_REFUSED


def test_start_cli_resumes_after_an_interrupted_run_without_recreating_the_worktree(tmp_path, monkeypatch):
    relay_dir = _make_relay(tmp_path)
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    state_dir = tmp_path / "state"
    worktrees_dir = tmp_path / "worktrees"
    calls = {"worktree_add": 0}

    def fake_worktree_add(*a, **k):
        calls["worktree_add"] += 1

    monkeypatch.setattr(orch, "_git_rev_parse", lambda ref, cwd: "sha")
    monkeypatch.setattr(orch, "_git_worktree_add", fake_worktree_add)
    monkeypatch.setattr(orch, "install_prepush_hook", lambda *a, **k: None)
    monkeypatch.setattr(orch, "_spawn_engineer", lambda **kwargs: _FakeProc(os.getpid()))

    args = ["start", "--movement", relay_id, "--relay-dir", str(relay_dir), "--state-dir", str(state_dir),
            "--worktrees-dir", str(worktrees_dir), "--profile", str(tmp_path / "profile.json")]
    assert orch.main(args) == orch.EXIT_OK
    assert calls["worktree_add"] == 1

    # Simulate a crash: the recorded pid is now dead, phase is still non-terminal.
    record = orch._load_state(state_dir, relay_id)
    orch._save_state(state_dir, relay_id, {**record, "pid": _dead_pid()})

    assert orch.main(args) == orch.EXIT_OK
    assert calls["worktree_add"] == 1  # not recreated
    resumed = orch._load_state(state_dir, relay_id)
    assert resumed["revision"] == 1  # unchanged -- a resume, not a fresh redispatch


def test_start_cli_resume_injects_recovery_note_for_the_background_task_exit_race(tmp_path, monkeypatch):
    """AC-4: relay/NXS-LOCAL-0018's exact live pattern -- resuming a dead pid
    whose relay never advanced past its own SESSION_START and whose worktree
    still has staged-but-uncommitted changes."""
    relay_dir = _make_relay(tmp_path)
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    state_dir = tmp_path / "state"
    worktrees_dir = tmp_path / "worktrees"
    spawn_calls = []

    def fake_spawn_engineer(**kwargs):
        spawn_calls.append(kwargs)
        return _FakeProc(os.getpid())

    monkeypatch.setattr(orch, "_git_rev_parse", lambda ref, cwd: "sha")
    monkeypatch.setattr(orch, "_git_worktree_add", lambda *a, **k: None)
    monkeypatch.setattr(orch, "install_prepush_hook", lambda *a, **k: None)
    monkeypatch.setattr(orch, "_spawn_engineer", fake_spawn_engineer)

    args = ["start", "--movement", relay_id, "--relay-dir", str(relay_dir), "--state-dir", str(state_dir),
            "--worktrees-dir", str(worktrees_dir), "--profile", str(tmp_path / "profile.json")]
    assert orch.main(args) == orch.EXIT_OK  # fresh dispatch -- relay is still just SESSION_START
    assert spawn_calls[-1].get("extra_prompt_note") is None

    record = orch._load_state(state_dir, relay_id)
    orch._save_state(state_dir, relay_id, {**record, "pid": _dead_pid()})
    monkeypatch.setattr(orch, "_git_status_porcelain", lambda worktree_path: "M  some_staged_change.py\n")

    assert orch.main(args) == orch.EXIT_OK  # resume
    assert spawn_calls[-1].get("extra_prompt_note") == orch.RESUME_RECOVERY_NOTE


def test_start_cli_resume_injects_no_recovery_note_when_nothing_is_staged(tmp_path, monkeypatch):
    relay_dir = _make_relay(tmp_path)
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    state_dir = tmp_path / "state"
    worktrees_dir = tmp_path / "worktrees"
    spawn_calls = []

    def fake_spawn_engineer(**kwargs):
        spawn_calls.append(kwargs)
        return _FakeProc(os.getpid())

    monkeypatch.setattr(orch, "_git_rev_parse", lambda ref, cwd: "sha")
    monkeypatch.setattr(orch, "_git_worktree_add", lambda *a, **k: None)
    monkeypatch.setattr(orch, "install_prepush_hook", lambda *a, **k: None)
    monkeypatch.setattr(orch, "_spawn_engineer", fake_spawn_engineer)

    args = ["start", "--movement", relay_id, "--relay-dir", str(relay_dir), "--state-dir", str(state_dir),
            "--worktrees-dir", str(worktrees_dir), "--profile", str(tmp_path / "profile.json")]
    assert orch.main(args) == orch.EXIT_OK  # fresh dispatch

    record = orch._load_state(state_dir, relay_id)
    orch._save_state(state_dir, relay_id, {**record, "pid": _dead_pid()})
    monkeypatch.setattr(orch, "_git_status_porcelain", lambda worktree_path: "")

    assert orch.main(args) == orch.EXIT_OK  # resume, nothing staged -- no note
    assert spawn_calls[-1].get("extra_prompt_note") is None


def test_start_cli_refuses_when_relay_says_it_is_not_engineers_turn(tmp_path):
    relay_dir = _make_relay(tmp_path)
    relay_file = next(relay_dir.glob("*.json"))
    obj = json.loads(relay_file.read_text())
    relay_id = obj["id"]
    assert lr.main(["append", "--file", str(relay_file), "--role", "engineer", "--marker", "RELAY_QUESTION",
                     "--subject", "q", "--text", "t", "--next", "po"]) == lr.EXIT_OK

    rc = orch.main(["start", "--movement", relay_id, "--relay-dir", str(relay_dir),
                     "--state-dir", str(tmp_path / "state"), "--worktrees-dir", str(tmp_path / "worktrees"),
                     "--profile", str(tmp_path / "profile.json")])
    assert rc == orch.EXIT_REFUSED


def test_start_cli_rejects_a_base_ref_outside_origin(tmp_path):
    relay_dir = _make_relay(tmp_path, git={"base": "some-other-remote/main", "lane": "feature/x"})
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    rc = orch.main(["start", "--movement", relay_id, "--relay-dir", str(relay_dir),
                     "--state-dir", str(tmp_path / "state"), "--worktrees-dir", str(tmp_path / "worktrees"),
                     "--profile", str(tmp_path / "profile.json")])
    assert rc == orch.EXIT_USAGE


# --- engineer spawn argv (relay/NXS-LOCAL-0015 AC-1/AC-2/AC-3: a resumed --
# --- session must reach the canonical relay directory as reliably as a ---
# --- fresh dispatch already does) --------------------------------------------

def test_spawn_engineer_grants_add_dir_for_the_canonical_relay_directory_on_fresh_dispatch(tmp_path, monkeypatch):
    calls = []

    def fake_popen(argv, **kwargs):
        calls.append(argv)
        return _FakeProc(12345)

    monkeypatch.setattr(orch.subprocess, "Popen", fake_popen)
    canonical_relay_dir = tmp_path / "canonical" / "relay"
    orch._spawn_engineer(
        worktree_path=tmp_path / "wt", profile_path=tmp_path / "profile.json",
        canonical_relay_dir=canonical_relay_dir, relay_file=canonical_relay_dir / "NXS-LOCAL-0001-x.json",
    )
    argv = calls[0]  # the engineer process's own argv, not the summary-tailer companion's
    assert "--add-dir" in argv
    assert argv[argv.index("--add-dir") + 1] == str(canonical_relay_dir)
    assert "--resume" not in argv


def test_spawn_engineer_grants_add_dir_for_the_canonical_relay_directory_on_resume_too(tmp_path, monkeypatch):
    calls = []

    def fake_popen(argv, **kwargs):
        calls.append(argv)
        return _FakeProc(12345)

    monkeypatch.setattr(orch.subprocess, "Popen", fake_popen)
    canonical_relay_dir = tmp_path / "canonical" / "relay"
    orch._spawn_engineer(
        worktree_path=tmp_path / "wt", profile_path=tmp_path / "profile.json",
        canonical_relay_dir=canonical_relay_dir, relay_file=canonical_relay_dir / "NXS-LOCAL-0001-x.json",
        resume_session_id="11111111-1111-1111-1111-111111111111",
    )
    argv = calls[0]  # the engineer process's own argv, not the summary-tailer companion's
    # The exact same grant a fresh dispatch gets (AC-3/AC-5: no regression,
    # no divergence between the two paths) -- resume only adds --resume.
    assert "--add-dir" in argv
    assert argv[argv.index("--add-dir") + 1] == str(canonical_relay_dir)
    assert argv[-2:] == ["--resume", "11111111-1111-1111-1111-111111111111"]


# --- AC-3/AC-4: extra_prompt_note gets appended onto the prompt argv element
# --- for exactly the one dispatch it is passed for, never by default --------

def test_spawn_engineer_prompt_is_unmodified_without_an_extra_note(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(orch.subprocess, "Popen", lambda argv, **k: (calls.append(argv), _FakeProc(1))[1])
    canonical_relay_dir = tmp_path / "canonical" / "relay"
    orch._spawn_engineer(
        worktree_path=tmp_path / "wt", profile_path=tmp_path / "profile.json",
        canonical_relay_dir=canonical_relay_dir, relay_file=canonical_relay_dir / "NXS-LOCAL-0001-x.json",
    )
    argv = calls[0]
    assert argv[argv.index("-p") + 1] == orch.ENGINEER_PROMPT


def test_spawn_engineer_appends_the_extra_note_onto_the_prompt_when_given(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(orch.subprocess, "Popen", lambda argv, **k: (calls.append(argv), _FakeProc(1))[1])
    canonical_relay_dir = tmp_path / "canonical" / "relay"
    orch._spawn_engineer(
        worktree_path=tmp_path / "wt", profile_path=tmp_path / "profile.json",
        canonical_relay_dir=canonical_relay_dir, relay_file=canonical_relay_dir / "NXS-LOCAL-0001-x.json",
        extra_prompt_note=orch.RESUME_RECOVERY_NOTE,
    )
    argv = calls[0]
    prompt = argv[argv.index("-p") + 1]
    assert prompt.startswith(orch.ENGINEER_PROMPT)
    assert orch.RESUME_RECOVERY_NOTE in prompt


def test_spawn_engineer_sets_a_raised_bash_default_timeout_without_clobbering_the_operators_own(tmp_path, monkeypatch):
    captured_env = {}

    def fake_popen(argv, env=None, **k):
        captured_env.update(env or {})
        return _FakeProc(1)

    monkeypatch.setattr(orch.subprocess, "Popen", fake_popen)
    canonical_relay_dir = tmp_path / "canonical" / "relay"

    monkeypatch.delenv("BASH_DEFAULT_TIMEOUT_MS", raising=False)
    orch._spawn_engineer(
        worktree_path=tmp_path / "wt", profile_path=tmp_path / "profile.json",
        canonical_relay_dir=canonical_relay_dir, relay_file=canonical_relay_dir / "NXS-LOCAL-0001-x.json",
    )
    assert captured_env["BASH_DEFAULT_TIMEOUT_MS"] == str(orch.DEFAULT_ENGINEER_BASH_TIMEOUT_MS)

    monkeypatch.setenv("BASH_DEFAULT_TIMEOUT_MS", "999")
    orch._spawn_engineer(
        worktree_path=tmp_path / "wt", profile_path=tmp_path / "profile.json",
        canonical_relay_dir=canonical_relay_dir, relay_file=canonical_relay_dir / "NXS-LOCAL-0001-x.json",
    )
    assert captured_env["BASH_DEFAULT_TIMEOUT_MS"] == "999"


# --- AC-2: stream-json line -> summary parser (representative real shapes) --
# Captured from a real `claude -p --output-format stream-json --verbose`
# run against the installed claude version, trimmed to the fields the
# parser actually looks at.

REAL_LINE_SUMMARY_TABLE = [
    (
        '{"type":"assistant","message":{"model":"claude-sonnet-5","id":"msg_1","type":"message",'
        '"role":"assistant","content":[{"type":"text","text":"4"}],"stop_reason":null}}',
        "assistant: 4",
    ),
    (
        '{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_1","name":"Bash",'
        '"input":{"command":"echo hi","description":"Print hi to stdout"}}]}}',
        "tool_use: Bash echo hi",
    ),
    (
        '{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"","signature":"x"}]}}',
        "assistant: (thinking)",
    ),
    (
        '{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_1","type":"tool_result",'
        '"content":"hi","is_error":false}]}}',
        "tool_result (ok): hi",
    ),
    (
        '{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_1","type":"tool_result",'
        '"content":"boom","is_error":true}]}}',
        "tool_result (error): boom",
    ),
    (
        '{"duration_api_ms":5361,"stop_reason":"end_turn","session_id":"s","total_cost_usd":0.039,'
        '"type":"result","subtype":"success","result":"Done."}',
        "result (success): Done.",
    ),
    (
        '{"type":"system","subtype":"init","cwd":"/repo","session_id":"s","tools":[],"model":"claude-sonnet-5"}',
        "system: init",
    ),
    (
        '{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"},"session_id":"s"}',
        "rate_limit: allowed",
    ),
]


@pytest.mark.parametrize("raw,expected", REAL_LINE_SUMMARY_TABLE)
def test_summarize_stream_json_line_real_shapes(raw, expected):
    assert orch.summarize_stream_json_line(raw) == expected


def test_summarize_stream_json_line_skips_blank_lines():
    assert orch.summarize_stream_json_line("") is None
    assert orch.summarize_stream_json_line("   \n") is None


def test_summarize_stream_json_line_skips_a_truncated_partial_line():
    # A line the writer has not finished appending yet -- not a parse
    # error to report, just not ready; the tailer re-reads it next poll.
    partial = '{"type":"assistant","message":{"content":[{"type":"text","text":"unfinished'
    assert orch.summarize_stream_json_line(partial) is None


def test_summarize_stream_json_line_falls_back_to_raw_for_an_unrecognized_shape():
    # Valid JSON, but a shape this parser does not know -- AC-2's own risk
    # note: never silently drop a real event, fall back to the raw line.
    raw = '{"type":"some_future_event_type","payload":{"a":1}}'
    assert orch.summarize_stream_json_line(raw) == raw


def test_summarize_stream_json_line_truncates_long_text():
    long_text = "x" * 500
    raw = json.dumps({"type": "assistant", "message": {"content": [{"type": "text", "text": long_text}]}})
    result = orch.summarize_stream_json_line(raw)
    assert result.startswith("assistant: xxxxxxxxxx")
    assert len(result) < 120


def test_summarize_stream_json_line_prefers_command_over_other_tool_fields():
    raw = json.dumps({"type": "assistant", "message": {"content": [
        {"type": "tool_use", "name": "Bash", "input": {"command": "ls -la", "description": "list files"}}
    ]}})
    assert orch.summarize_stream_json_line(raw) == "tool_use: Bash ls -la"


# --- AC-2: the tailer's partial-line-safe draining ---------------------------

def test_drain_log_once_leaves_a_truncated_last_line_for_next_time(tmp_path):
    log_path = tmp_path / "engineer.log"
    summary_path = tmp_path / "engineer.summary.log"
    complete = json.dumps({"type": "assistant", "message": {"content": [{"type": "text", "text": "hi"}]}})
    full_second = json.dumps({"type": "assistant", "message": {"content": [{"type": "text", "text": "more"}]}})
    partial_second = full_second[:20]
    log_path.write_bytes((complete + "\n").encode("utf-8") + partial_second.encode("utf-8"))

    with open(summary_path, "a", encoding="utf-8") as fh:
        offset = orch._drain_log_once(log_path, fh, 0)
    assert summary_path.read_text() == "assistant: hi\n"
    assert offset == len(complete) + 1  # the partial second line was not consumed

    with open(log_path, "ab") as f:
        f.write(full_second[20:].encode("utf-8") + b"\n")
    with open(summary_path, "a", encoding="utf-8") as fh:
        offset = orch._drain_log_once(log_path, fh, offset)
    assert summary_path.read_text() == "assistant: hi\nassistant: more\n"


def test_drain_log_once_is_a_noop_when_log_file_does_not_exist_yet(tmp_path):
    log_path = tmp_path / "engineer.log"
    summary_path = tmp_path / "engineer.summary.log"
    with open(summary_path, "a", encoding="utf-8") as fh:
        offset = orch._drain_log_once(log_path, fh, 0)
    assert offset == 0
    assert summary_path.read_text() == ""


# --- AC-1: _spawn_engineer's argv gains the streaming flags ------------------

class _FakePopen:
    def __init__(self, pid):
        self.pid = pid


def test_spawn_engineer_argv_includes_stream_json_and_verbose(tmp_path, monkeypatch):
    calls = []

    def fake_popen(argv, **kwargs):
        calls.append(argv)
        return _FakePopen(pid=1000 + len(calls))

    monkeypatch.setattr(orch.subprocess, "Popen", fake_popen)
    worktree = tmp_path / "wt"

    proc = orch._spawn_engineer(
        worktree_path=worktree, profile_path=tmp_path / "profile.json",
        canonical_relay_dir=tmp_path / "relay", relay_file=tmp_path / "relay" / "X.json",
    )

    assert len(calls) == 2  # the engineer process, then the summary-tailer companion
    engineer_argv = calls[0]
    assert engineer_argv[engineer_argv.index("--output-format") + 1] == "stream-json"
    assert "--verbose" in engineer_argv

    tailer_argv = calls[1]
    assert "_tail-summary" in tailer_argv
    assert str(proc.pid) in tailer_argv


# --- AC-4/AC-5: last_activity -------------------------------------------------

def test_read_last_activity_returns_none_for_no_worktree_path():
    assert orch._read_last_activity(None) is None


def test_read_last_activity_returns_none_when_summary_log_is_absent(tmp_path):
    # A movement dispatched before AC-1/AC-2 landed: old-format engineer.log,
    # no summary.log at all -- must not raise (AC-5).
    assert orch._read_last_activity(str(tmp_path)) is None


def test_read_last_activity_returns_the_last_nonblank_line(tmp_path):
    nexus_dir = tmp_path / ".nexus"
    nexus_dir.mkdir()
    (nexus_dir / "engineer.summary.log").write_text("assistant: hi\ntool_use: Bash ls\n\n", encoding="utf-8")
    assert orch._read_last_activity(str(tmp_path)) == "tool_use: Bash ls"


def test_status_row_carries_last_activity(tmp_path):
    nexus_dir = tmp_path / "wt" / ".nexus"
    nexus_dir.mkdir(parents=True)
    (nexus_dir / "engineer.summary.log").write_text("assistant: working\n", encoding="utf-8")
    record = {
        "movement_id": "NXS-LOCAL-0001", "phase": orch.PHASE_RUNNING, "pid": os.getpid(),
        "worktree_path": str(tmp_path / "wt"), "retry_count": 0, "branch": "feature/x",
    }
    row = orch._status_row(record, tmp_path / "relay", retry_limit=2)
    assert row["last_activity"] == "assistant: working"


def test_status_row_last_activity_is_none_for_a_pre_change_movement(tmp_path):
    # AC-5 backward compatibility: no .nexus dir at all under the recorded
    # worktree path (a movement dispatched before this change) -- no crash.
    record = {
        "movement_id": "NXS-LOCAL-0001", "phase": orch.PHASE_DONE, "pid": 1,
        "worktree_path": str(tmp_path / "nonexistent-wt"), "retry_count": 0, "branch": "feature/x",
    }
    row = orch._status_row(record, tmp_path / "relay", retry_limit=2)
    assert row["last_activity"] is None


def test_status_cli_reports_last_activity_field(tmp_path, monkeypatch, capsys):
    relay_dir = _make_relay(tmp_path, movement="M")
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    worktree = tmp_path / "wt"
    nexus_dir = worktree / ".nexus"
    nexus_dir.mkdir(parents=True)
    (nexus_dir / "engineer.summary.log").write_text("assistant: on it\n", encoding="utf-8")
    state_dir = tmp_path / "state"
    orch._save_state(state_dir, relay_id, {
        "movement_id": relay_id, "phase": orch.PHASE_RUNNING, "pid": os.getpid(),
        "worktree_path": str(worktree), "retry_count": 0, "branch": "feature/x", "dispatch_seq": 1,
    })
    capsys.readouterr()
    rc = orch.main(["status", "--movement", relay_id, "--state-dir", str(state_dir), "--relay-dir", str(relay_dir)])
    assert rc == orch.EXIT_OK
    row = json.loads(capsys.readouterr().out)
    assert row["last_activity"] == "assistant: on it"


# --- AC-3/AC-6: watch's data-gathering function, tested in isolation --------

def test_gather_watch_rows_is_read_only(tmp_path):
    state_dir = tmp_path / "state"
    relay_dir = tmp_path / "relay"
    orch._save_state(state_dir, "NXS-LOCAL-0001", {
        "movement_id": "NXS-LOCAL-0001", "phase": orch.PHASE_DONE, "pid": 1,
        "branch": "feature/old", "worktree_path": str(tmp_path / "old-wt"), "retry_count": 0,
    })
    before = (state_dir / "NXS-LOCAL-0001.json").read_text()

    rows = orch._gather_watch_rows(state_dir, relay_dir, retry_limit=2)

    after = (state_dir / "NXS-LOCAL-0001.json").read_text()
    assert after == before  # watch never writes state, unlike status's own reconciliation


def test_gather_watch_rows_backward_compatible_with_a_pre_change_movement(tmp_path):
    state_dir = tmp_path / "state"
    relay_dir = tmp_path / "relay"
    orch._save_state(state_dir, "NXS-LOCAL-0001", {
        "movement_id": "NXS-LOCAL-0001", "phase": orch.PHASE_DONE, "pid": 1,
        "branch": "feature/old", "worktree_path": str(tmp_path / "old-wt-no-summary-log"), "retry_count": 0,
    })

    rows = orch._gather_watch_rows(state_dir, relay_dir, retry_limit=2)

    assert len(rows) == 1
    assert rows[0]["movement_id"] == "NXS-LOCAL-0001"
    assert rows[0]["last_activity"] is None


def test_gather_watch_rows_sorted_and_multiple(tmp_path):
    state_dir = tmp_path / "state"
    relay_dir = tmp_path / "relay"
    orch._save_state(state_dir, "NXS-LOCAL-0002", {"movement_id": "NXS-LOCAL-0002", "phase": orch.PHASE_DONE, "pid": 1})
    orch._save_state(state_dir, "NXS-LOCAL-0001", {"movement_id": "NXS-LOCAL-0001", "phase": orch.PHASE_DONE, "pid": 1})
    rows = orch._gather_watch_rows(state_dir, relay_dir, retry_limit=2)
    assert [r["movement_id"] for r in rows] == ["NXS-LOCAL-0001", "NXS-LOCAL-0002"]


def test_render_watch_screen_no_rows_known():
    assert "no movements known" in orch._render_watch_screen([], 80)


def test_render_watch_screen_shows_na_for_missing_last_activity():
    row = {
        "movement_id": "NXS-LOCAL-0001", "phase": "running", "pid_alive": True,
        "heartbeat_age_seconds": 5, "branch": "feature/x", "worktree_path": "/tmp/x",
        "last_marker": None, "last_timestamp": None, "last_activity": None,
    }
    screen = orch._render_watch_screen([row], 80)
    assert "n/a" in screen
    assert "NXS-LOCAL-0001" in screen


def test_format_age_buckets():
    assert orch._format_age(None) == "-"
    assert orch._format_age(5) == "5s"
    assert orch._format_age(65) == "1m05s"
    assert orch._format_age(3700) == "1h01m"


# ---------------------------------------------------------------------------
# GOV.ORCH.1: `run`, `verify`, retry accounting, state-dir default
# ---------------------------------------------------------------------------

def _init_bare_and_clone(tmp_path: Path) -> Path:
    """A real, minimal git project with an `origin` remote whose `main`
    branch is fetched -- real enough for `git worktree add origin/main -b
    feature/x` and `git diff --check`/`git status --porcelain` to behave
    exactly as they would against the real repository, without touching it."""
    bare = tmp_path / "origin.git"
    subprocess.run(["git", "init", "-q", "--bare", str(bare)], check=True)
    work = tmp_path / "work"
    subprocess.run(["git", "clone", "-q", str(bare), str(work)], check=True)
    subprocess.run(["git", "config", "user.email", "t@example.com"], cwd=work, check=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=work, check=True)
    (work / "README.md").write_text("hello\n", encoding="utf-8")
    # Mirrors this repository's own .gitignore entry for `.nexus/` -- the
    # real neXus repo already ignores it, so a real movement worktree never
    # shows an untracked `.nexus/` in `git status --porcelain`; this
    # synthetic repo needs the same entry for `verify`'s uncommitted-changes
    # step to behave realistically.
    (work / ".gitignore").write_text(".nexus/\n", encoding="utf-8")
    subprocess.run(["git", "add", "-A"], cwd=work, check=True)
    subprocess.run(["git", "commit", "-q", "-m", "init"], cwd=work, check=True)
    subprocess.run(["git", "push", "-q", "origin", "HEAD:main"], cwd=work, check=True)
    subprocess.run(["git", "fetch", "-q", "origin"], cwd=work, check=True)
    return work


_STUB_CLAUDE = '''#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import time

mode = os.environ.get("NEXUS_TEST_STUB_MODE", "close")

if mode == "close":
    import tempfile
    relay_file = os.environ["NEXUS_RELAY_FILE"]
    report = {
        "completed": ["x"], "changed": ["x"], "preserved": ["x"],
        "validation": {"targeted": "1 passed", "affected": "1 passed", "full_regression": "n/a",
                       "privacy": "n/a", "state_consistency": "n/a", "diff_check": "n/a", "real_environment": "NOT_RUN"},
        "unresolved_risks": [], "state_updates": [],
        "next": {"movement": "n/a", "movement_type": "VALIDATION", "status": "done", "objective": "n/a"},
        "recommended_reasoning": {"tier": "Normal", "reason": "x"},
        "continuation": "SAME_SESSION",
        "integration": {"branch": "n/a", "head_sha": "n/a", "pr": None, "pr_url": None, "ci": "n/a",
                         "merge_state": "NOT_OPENED", "merge_commit": None, "merge_decision": "NOT_APPLICABLE"},
        "effects": {"main_py": "none", "ui": "none"},
    }
    fd, report_path = tempfile.mkstemp(suffix=".json")
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(report, fh)
    python = os.environ["NEXUS_STUB_PYTHON"]
    local_relay = os.environ["NEXUS_STUB_LOCAL_RELAY"]
    subprocess.run([python, local_relay, "append", "--file", relay_file, "--role", "engineer",
                     "--marker", "SESSION_CLOSE", "--report", report_path, "--outcome", "DONE"], check=True)
    print("engineer: done", flush=True)
    sys.exit(0)
elif mode == "exit_nonzero":
    print("engineer: about to fail", flush=True)
    sys.exit(1)
elif mode == "sleep":
    time.sleep(30)
    sys.exit(0)
elif mode == "heartbeat_stall":
    print("engineer: one line then silence", flush=True)
    time.sleep(30)
    sys.exit(0)
'''


_STUB_CODEX = '''#!/usr/bin/env python3
import json
import os
import subprocess
import sys

mode = os.environ.get("NEXUS_TEST_STUB_MODE", "close")
sys.stdin.read()  # AC-2: the prompt arrives on stdin, exactly as real codex exec would consume it.

if mode == "close":
    import tempfile
    relay_file = os.environ["NEXUS_RELAY_FILE"]
    report = {
        "completed": ["x"], "changed": ["x"], "preserved": ["x"],
        "validation": {"targeted": "1 passed", "affected": "1 passed", "full_regression": "n/a",
                       "privacy": "n/a", "state_consistency": "n/a", "diff_check": "n/a", "real_environment": "NOT_RUN"},
        "unresolved_risks": [], "state_updates": [],
        "next": {"movement": "n/a", "movement_type": "VALIDATION", "status": "done", "objective": "n/a"},
        "recommended_reasoning": {"tier": "Normal", "reason": "x"},
        "continuation": "SAME_SESSION",
        "integration": {"branch": "n/a", "head_sha": "n/a", "pr": None, "pr_url": None, "ci": "n/a",
                         "merge_state": "NOT_OPENED", "merge_commit": None, "merge_decision": "NOT_APPLICABLE"},
        "effects": {"main_py": "none", "ui": "none"},
    }
    fd, report_path = tempfile.mkstemp(suffix=".json")
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(report, fh)
    python = os.environ["NEXUS_STUB_PYTHON"]
    local_relay = os.environ["NEXUS_STUB_LOCAL_RELAY"]
    subprocess.run([python, local_relay, "append", "--file", relay_file, "--role", "engineer",
                     "--marker", "SESSION_CLOSE", "--report", report_path, "--outcome", "DONE"], check=True)
    print(json.dumps({"type": "item.completed", "item": {"type": "agent_message", "text": "done"}}), flush=True)
    sys.exit(0)
elif mode == "exit_nonzero":
    sys.exit(1)
'''


def _install_stub_codex(tmp_path: Path, monkeypatch) -> None:
    bin_dir = tmp_path / "stub_bin"
    bin_dir.mkdir(exist_ok=True)
    stub = bin_dir / "codex"
    stub.write_text(_STUB_CODEX, encoding="utf-8")
    stub.chmod(0o755)
    monkeypatch.setenv("PATH", f"{bin_dir}:{os.environ['PATH']}")
    monkeypatch.setenv("NEXUS_STUB_PYTHON", sys.executable)
    monkeypatch.setenv("NEXUS_STUB_LOCAL_RELAY", str(ROOT / "scripts" / "local_relay.py"))


def _install_stub_claude(tmp_path: Path, monkeypatch) -> None:
    bin_dir = tmp_path / "stub_bin"
    bin_dir.mkdir(exist_ok=True)
    stub = bin_dir / "claude"
    stub.write_text(_STUB_CLAUDE, encoding="utf-8")
    stub.chmod(0o755)
    monkeypatch.setenv("PATH", f"{bin_dir}:{os.environ['PATH']}")
    monkeypatch.setenv("NEXUS_STUB_PYTHON", sys.executable)
    monkeypatch.setenv("NEXUS_STUB_LOCAL_RELAY", str(ROOT / "scripts" / "local_relay.py"))


def _run_args(tmp_path, relay_dir, relay_id, **extra):
    args = ["run", "--movement", relay_id, "--relay-dir", str(relay_dir),
            "--state-dir", str(tmp_path / "state"), "--worktrees-dir", str(tmp_path / "worktrees"),
            "--profile", str(tmp_path / "profile.json")]
    for k, v in extra.items():
        args += [f"--{k.replace('_', '-')}", str(v)]
    return args


def test_run_cli_success_returns_zero_and_prints_report_object(tmp_path, monkeypatch, capsys):
    """AC-1: a stub engineer that exits 0 and closes the relay -- `run`
    returns 0, prints the section 2.4 report object, and leaves no child
    process."""
    work = _init_bare_and_clone(tmp_path)
    relay_dir = _make_relay(work, movement="M", git={"base": "origin/main", "lane": "feature/x"},
                             validation_plan=[{"argv": [sys.executable, "-c", "pass"]}])
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    _install_stub_claude(tmp_path, monkeypatch)
    monkeypatch.setenv("NEXUS_TEST_STUB_MODE", "close")

    capsys.readouterr()
    rc = orch.main(_run_args(tmp_path, relay_dir, relay_id, timeout=15, heartbeat_timeout=10))
    out = capsys.readouterr().out
    report = json.loads(out.strip().splitlines()[-1])
    assert rc == orch.EXIT_OK, report
    assert report["movement"] == relay_id
    assert report["phase"] == orch.PHASE_DONE
    assert report["exit_code"] == 0
    assert report["relay_status"] == "CLOSED"
    assert report["verify"]["passed"] is True
    assert report["provider"] == "claude"

    record = orch._load_state(tmp_path / "state", relay_id)
    assert not orch.pid_alive(record["pid"])


def test_run_cli_timeout_kills_stub_and_records_failed_timeout(tmp_path, monkeypatch, capsys):
    """AC-2."""
    work = _init_bare_and_clone(tmp_path)
    relay_dir = _make_relay(work, movement="M", git={"base": "origin/main", "lane": "feature/x"})
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    _install_stub_claude(tmp_path, monkeypatch)
    monkeypatch.setenv("NEXUS_TEST_STUB_MODE", "sleep")

    capsys.readouterr()
    rc = orch.main(_run_args(tmp_path, relay_dir, relay_id, timeout=1, heartbeat_timeout=30))
    report = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert rc == orch.EXIT_REFUSED
    assert report["phase"] == orch.PHASE_FAILED
    assert report["failure_reason"] == "timeout"
    assert report["exit_code"] is None

    record = orch._load_state(tmp_path / "state", relay_id)
    assert not orch.pid_alive(record["pid"])
    assert record["phase"] == orch.PHASE_FAILED
    assert record["failure_reason"] == "timeout"


def test_run_cli_heartbeat_timeout_kills_stub_and_records_heartbeat_timeout(tmp_path, monkeypatch, capsys):
    """AC-3."""
    work = _init_bare_and_clone(tmp_path)
    relay_dir = _make_relay(work, movement="M", git={"base": "origin/main", "lane": "feature/x"})
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    _install_stub_claude(tmp_path, monkeypatch)
    monkeypatch.setenv("NEXUS_TEST_STUB_MODE", "heartbeat_stall")

    capsys.readouterr()
    rc = orch.main(_run_args(tmp_path, relay_dir, relay_id, timeout=30, heartbeat_timeout=1))
    report = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert rc == orch.EXIT_REFUSED
    assert report["phase"] == orch.PHASE_FAILED
    assert report["failure_reason"] == "heartbeat_timeout"

    record = orch._load_state(tmp_path / "state", relay_id)
    assert not orch.pid_alive(record["pid"])


def test_run_cli_engineer_exit_nonzero_fails_without_verify_gate(tmp_path, monkeypatch, capsys):
    work = _init_bare_and_clone(tmp_path)
    relay_dir = _make_relay(work, movement="M", git={"base": "origin/main", "lane": "feature/x"})
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    _install_stub_claude(tmp_path, monkeypatch)
    monkeypatch.setenv("NEXUS_TEST_STUB_MODE", "exit_nonzero")

    capsys.readouterr()
    rc = orch.main(_run_args(tmp_path, relay_dir, relay_id, timeout=15, heartbeat_timeout=10))
    report = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert rc == orch.EXIT_REFUSED
    assert report["phase"] == orch.PHASE_FAILED
    assert report["exit_code"] == 1
    assert report["failure_reason"] == "engineer_exit_nonzero"


def test_run_cli_success_reports_provider_default_used_when_no_model_observed(tmp_path, monkeypatch, capsys):
    """AC-7: the stub engineer's log never carries a recognizable
    system/init event, so `model_observed` stays None and the report
    carries `audit_exception: provider_default_used`."""
    work = _init_bare_and_clone(tmp_path)
    relay_dir = _make_relay(work, movement="M", git={"base": "origin/main", "lane": "feature/x"},
                             validation_plan=[{"argv": [sys.executable, "-c", "pass"]}])
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    _install_stub_claude(tmp_path, monkeypatch)
    monkeypatch.setenv("NEXUS_TEST_STUB_MODE", "close")

    capsys.readouterr()
    rc = orch.main(_run_args(tmp_path, relay_dir, relay_id, timeout=15, heartbeat_timeout=10))
    report = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert rc == orch.EXIT_OK, report
    assert report["model_observed"] is None
    assert report["audit_exception"] == "provider_default_used"


# --- GOV.ORCH.2: --provider, --merge-mode, per-provider effort allowlists ---

def test_start_cli_invalid_effort_for_the_chosen_provider_is_a_usage_error_with_no_state_record(tmp_path):
    """AC-6: 'max' is Claude-only (section 2.4) -- requesting it for codex
    is a usage error, and no state record is created."""
    relay_dir = _make_relay(tmp_path, git={"base": "origin/main", "lane": "feature/x"})
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    state_dir = tmp_path / "state"
    rc = orch.main(["start", "--movement", relay_id, "--relay-dir", str(relay_dir),
                     "--state-dir", str(state_dir), "--worktrees-dir", str(tmp_path / "worktrees"),
                     "--profile", str(tmp_path / "profile.json"), "--provider", "codex", "--effort", "max"])
    assert rc == orch.EXIT_USAGE
    assert orch._load_state(state_dir, relay_id) is None


def test_run_cli_provider_codex_forces_orchestrator_merge_mode_and_calls_integrate_once_after_verify(
    tmp_path, monkeypatch, capsys,
):
    """AC-5: --provider codex forces merge-mode orchestrator; the prompt
    file carries the no-merge instruction; `run` calls the shared
    `orchestrator_verify.integrate` exactly once, only after verify passed."""
    work = _init_bare_and_clone(tmp_path)
    relay_dir = _make_relay(work, movement="M", git={"base": "origin/main", "lane": "feature/x"},
                             validation_plan=[{"argv": [sys.executable, "-c", "pass"]}])
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    _install_stub_codex(tmp_path, monkeypatch)
    monkeypatch.setenv("NEXUS_TEST_STUB_MODE", "close")

    integrate_calls = []

    def fake_integrate(cwd):
        integrate_calls.append(cwd)
        return True, "mocked integrate: ok"

    monkeypatch.setattr(orch.ov, "integrate", fake_integrate)

    capsys.readouterr()
    rc = orch.main(_run_args(tmp_path, relay_dir, relay_id, timeout=15, heartbeat_timeout=10, provider="codex"))
    report = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert rc == orch.EXIT_OK, report
    assert report["provider"] == "codex"
    assert report["merge_mode"] == "orchestrator"
    assert report["verify"]["passed"] is True
    assert len(integrate_calls) == 1
    assert report["integrate"] == {"passed": True, "reason": "mocked integrate: ok"}

    worktree = Path(report["worktree_path"])
    prompt_text = (worktree / ".nexus" / "engineer_prompt.txt").read_text(encoding="utf-8")
    assert "gh pr merge" in prompt_text


def test_run_cli_provider_codex_skips_integrate_when_verify_fails(tmp_path, monkeypatch, capsys):
    """AC-5's other half: integrate must never run when verify did not
    pass."""
    work = _init_bare_and_clone(tmp_path)
    relay_dir = _make_relay(work, movement="M", git={"base": "origin/main", "lane": "feature/x"},
                             validation_plan=[{"argv": [sys.executable, "-c", "import sys; sys.exit(1)"]}])
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    _install_stub_codex(tmp_path, monkeypatch)
    monkeypatch.setenv("NEXUS_TEST_STUB_MODE", "close")

    integrate_calls = []
    monkeypatch.setattr(orch.ov, "integrate", lambda cwd: integrate_calls.append(cwd) or (True, "ok"))

    capsys.readouterr()
    rc = orch.main(_run_args(tmp_path, relay_dir, relay_id, timeout=15, heartbeat_timeout=10, provider="codex"))
    report = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert rc == orch.EXIT_REFUSED
    assert report["verify"]["passed"] is False
    assert integrate_calls == []
    assert "integrate" not in report


# --- verify (standalone CLI subcommand) -------------------------------------

def test_verify_cli_passes_on_a_clean_worktree_with_an_object_validation_plan(tmp_path):
    work = _init_bare_and_clone(tmp_path)
    relay_dir = tmp_path / "relay"
    start = tmp_path / "start.json"
    start.write_text(json.dumps({
        "protocol_version": 2, "message_type": "SESSION_START", "movement": "M",
        "refs": [], "report": _start_report(git={"base": "origin/main", "lane": "feature/x"},
                                              validation_plan=[{"argv": [sys.executable, "-c", "pass"]}, "prose"]),
    }), encoding="utf-8")
    assert lr.main(["create", "--role", "po", "--start", str(start), "--dir", str(relay_dir)]) == lr.EXIT_OK
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]

    worktree = tmp_path / "worktrees" / relay_id
    subprocess.run(["git", "worktree", "add", str(worktree), "origin/main", "-b", "feature/x"],
                    cwd=work, check=True)
    state_dir = tmp_path / "state"
    orch._save_state(state_dir, relay_id, {"movement_id": relay_id, "worktree_path": str(worktree),
                                            "phase": orch.PHASE_RUNNING, "pid": os.getpid(), "retry_count": 0})

    rc = orch.main(["verify", "--movement", relay_id, "--relay-dir", str(relay_dir), "--state-dir", str(state_dir)])
    assert rc == orch.EXIT_OK


def test_verify_cli_fails_on_uncommitted_changes(tmp_path):
    """AC-5."""
    work = _init_bare_and_clone(tmp_path)
    relay_dir = _make_relay(tmp_path, movement="M", git={"base": "origin/main", "lane": "feature/x"})
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]

    worktree = tmp_path / "worktrees" / relay_id
    subprocess.run(["git", "worktree", "add", str(worktree), "origin/main", "-b", "feature/x"],
                    cwd=work, check=True)
    (worktree / "README.md").write_text("dirty\n", encoding="utf-8")

    state_dir = tmp_path / "state"
    orch._save_state(state_dir, relay_id, {"movement_id": relay_id, "worktree_path": str(worktree),
                                            "phase": orch.PHASE_RUNNING, "pid": os.getpid(), "retry_count": 0})

    rc = orch.main(["verify", "--movement", relay_id, "--relay-dir", str(relay_dir), "--state-dir", str(state_dir)])
    assert rc == orch.EXIT_REFUSED


# --- retry_count accounting on resume (AC-6) --------------------------------

def test_retry_count_increments_on_each_resume_and_status_reports_failed_past_the_limit(tmp_path, monkeypatch):
    relay_dir = _make_relay(tmp_path)
    relay_id = json.loads(next(relay_dir.glob("*.json")).read_text())["id"]
    state_dir = tmp_path / "state"
    worktrees_dir = tmp_path / "worktrees"

    monkeypatch.setattr(orch, "_git_rev_parse", lambda ref, cwd: "sha")
    monkeypatch.setattr(orch, "_git_worktree_add", lambda *a, **k: None)
    monkeypatch.setattr(orch, "install_prepush_hook", lambda *a, **k: None)
    monkeypatch.setattr(orch, "_spawn_engineer", lambda **kwargs: _FakeProc(os.getpid()))

    args = ["start", "--movement", relay_id, "--relay-dir", str(relay_dir), "--state-dir", str(state_dir),
            "--worktrees-dir", str(worktrees_dir), "--profile", str(tmp_path / "profile.json")]
    assert orch.main(args) == orch.EXIT_OK
    record = orch._load_state(state_dir, relay_id)
    assert record["retry_count"] == 0

    retry_limit = 2
    for expected_retry_count in (1, 2):
        orch._save_state(state_dir, relay_id, {**orch._load_state(state_dir, relay_id), "pid": _dead_pid()})
        assert orch.main(args) == orch.EXIT_OK  # resume
        record = orch._load_state(state_dir, relay_id)
        assert record["retry_count"] == expected_retry_count

    # A final dead pid, retry_count already at the limit: status must now
    # report (and persist) `failed`, never keep resuming forever.
    orch._save_state(state_dir, relay_id, {**orch._load_state(state_dir, relay_id), "pid": _dead_pid()})
    rc = orch.main(["status", "--movement", relay_id, "--state-dir", str(state_dir), "--relay-dir", str(relay_dir),
                     "--retry-limit", str(retry_limit)])
    assert rc == orch.EXIT_OK
    record = orch._load_state(state_dir, relay_id)
    assert record["phase"] == orch.PHASE_FAILED
    assert record["failure_reason"] == "retry_limit_exceeded"


# --- state-dir default under <worktrees-dir>/.state/ (AC-9) -----------------

def test_default_state_dir_is_a_state_subdir_of_default_worktrees_dir():
    assert orch.DEFAULT_STATE_DIR == orch.DEFAULT_WORKTREES_DIR / ".state"


def test_status_lists_a_record_found_only_in_the_legacy_state_dir(tmp_path, monkeypatch):
    legacy_dir = tmp_path / "legacy_state"
    monkeypatch.setattr(orch, "LEGACY_STATE_DIR", legacy_dir)
    orch._save_state(legacy_dir, "NXS-LOCAL-0099", {
        "movement_id": "NXS-LOCAL-0099", "phase": orch.PHASE_DONE, "pid": 1, "retry_count": 0,
    })
    state_dir = tmp_path / "state"
    relay_dir = tmp_path / "relay"

    rc = orch.main(["status", "--state-dir", str(state_dir), "--relay-dir", str(relay_dir)])
    assert rc == orch.EXIT_OK


def test_status_single_movement_found_only_in_legacy_dir_is_flagged(tmp_path, monkeypatch, capsys):
    legacy_dir = tmp_path / "legacy_state"
    monkeypatch.setattr(orch, "LEGACY_STATE_DIR", legacy_dir)
    orch._save_state(legacy_dir, "NXS-LOCAL-0099", {
        "movement_id": "NXS-LOCAL-0099", "phase": orch.PHASE_DONE, "pid": 1, "retry_count": 0,
    })
    state_dir = tmp_path / "state"
    relay_dir = tmp_path / "relay"

    capsys.readouterr()
    rc = orch.main(["status", "--movement", "NXS-LOCAL-0099", "--state-dir", str(state_dir),
                     "--relay-dir", str(relay_dir)])
    row = json.loads(capsys.readouterr().out)
    assert rc == orch.EXIT_OK
    assert row["legacy_state_dir"] is True


# ---------------------------------------------------------------------------
# GOV.ORCH.4 section 3.6: `orchestrator.py usage` -- AC-9
# ---------------------------------------------------------------------------

def test_usage_cli_json_output_equals_build_usage_report(tmp_path, capsys):
    import orchestrator_dashboard as dash

    state_dir = tmp_path / "state"
    relay_dir = tmp_path / "relay"
    orch._save_state(state_dir, "NXS-LOCAL-0201", {
        "movement_id": "NXS-LOCAL-0201", "phase": orch.PHASE_RUNNING, "pid": os.getpid(),
        "provider": "claude", "started_at": "2026-09-11T09:00:00Z", "worktree_path": None,
    })
    orch._save_state(state_dir, "NXS-LOCAL-0202", {
        "movement_id": "NXS-LOCAL-0202", "phase": orch.PHASE_DONE, "pid": None,
        "provider": "codex", "started_at": "2026-09-11T10:00:00Z", "worktree_path": None,
    })

    expected = dash.build_usage_report(state_dir, relay_dir, ROOT)

    capsys.readouterr()
    rc = orch.main(["usage", "--state-dir", str(state_dir), "--relay-dir", str(relay_dir),
                     "--repo-root", str(ROOT), "--json"])
    out = capsys.readouterr().out
    assert rc == orch.EXIT_OK
    assert json.loads(out) == expected


def test_usage_cli_table_output_does_not_raise(tmp_path, capsys):
    state_dir = tmp_path / "state"
    relay_dir = tmp_path / "relay"
    orch._save_state(state_dir, "NXS-LOCAL-0203", {
        "movement_id": "NXS-LOCAL-0203", "phase": orch.PHASE_RUNNING, "pid": os.getpid(),
        "provider": "claude", "started_at": "2026-09-11T09:00:00Z", "worktree_path": None,
    })
    capsys.readouterr()
    rc = orch.main(["usage", "--state-dir", str(state_dir), "--relay-dir", str(relay_dir),
                     "--repo-root", str(ROOT)])
    out = capsys.readouterr().out
    assert rc == orch.EXIT_OK
    assert "NXS-LOCAL-0203" in out
    assert "TOTAL" in out
