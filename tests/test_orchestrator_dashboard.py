"""project/backlog.json id orchestrator_interactive_dashboard_app -- AC-7
targeted tests for the data/API layer only (no browser test harness, same
testing philosophy relay/NXS-LOCAL-0013's own watch-loop tests used):
stage-derivation, the action-id registry/staleness check, and the config
read/write "endpoint" (its underlying functions, exercised directly).
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import orchestrator as orch  # noqa: E402
import orchestrator_dashboard as dash  # noqa: E402
import local_relay as lr  # noqa: E402


# ---------------------------------------------------------------------------
# derive_process_status
# ---------------------------------------------------------------------------

def test_process_status_running_when_pid_alive():
    assert dash.derive_process_status({"pid_alive": True, "pid": 123}) == "running"


def test_process_status_exited_when_pid_present_but_dead():
    assert dash.derive_process_status({"pid_alive": False, "pid": 123}) == "exited"


def test_process_status_disconnected_when_no_pid():
    assert dash.derive_process_status({"pid_alive": False, "pid": None}) == "disconnected"


# ---------------------------------------------------------------------------
# derive_work_stage -- never inferred from git dirtiness alone
# ---------------------------------------------------------------------------

def _stage(**kwargs):
    defaults = dict(
        relay_status="AWAITING_ENGINEER", next_actor="engineer", phase=orch.PHASE_RUNNING,
        porcelain_lines=[], last_activity=None, has_open_pr=False,
    )
    defaults.update(kwargs)
    return dash.derive_work_stage(**defaults)


def test_stage_merged_when_relay_closed():
    assert _stage(relay_status="CLOSED") == dash.STAGE_MERGED


def test_stage_merged_when_phase_done_even_if_relay_status_missing():
    assert _stage(relay_status=None, phase=orch.PHASE_DONE) == dash.STAGE_MERGED


def test_stage_awaiting_po_takes_priority_over_dirty_worktree():
    assert _stage(next_actor="po", porcelain_lines=["M foo.py"]) == dash.STAGE_AWAITING_PO


def test_stage_resolving_merge_conflict_from_unmerged_porcelain_codes():
    assert _stage(porcelain_lines=["UU foo.py"]) == dash.STAGE_RESOLVING_CONFLICT


def test_stage_integration_when_open_pr_present():
    assert _stage(has_open_pr=True) == dash.STAGE_INTEGRATION


def test_stage_testing_from_real_last_activity_signal():
    assert _stage(last_activity="tool_use: Bash py -m pytest -q -n auto") == dash.STAGE_TESTING


def test_stage_coding_when_dirty_and_no_stronger_signal():
    assert _stage(porcelain_lines=["M foo.py"]) == dash.STAGE_CODING


def test_stage_not_started_when_clean_and_relay_open():
    assert _stage(relay_status="OPEN", porcelain_lines=[]) == dash.STAGE_NOT_STARTED


def test_stage_clean_worktree_is_not_assumed_done_stays_unknown():
    # Invariant: a clean worktree does not mean "done" -- with no relay/
    # phase/PR/activity signal at all, the honest answer is unknown, never
    # a fabricated "merged"/"done".
    assert _stage(relay_status="AWAITING_ENGINEER", porcelain_lines=[]) == dash.STAGE_UNKNOWN


# ---------------------------------------------------------------------------
# parse_blocker_tag
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("subject,expected_source,expected_clean", [
    ("[AUTHORIZED] run the regression suite", dash.BLOCKER_ALREADY_AUTHORIZED, "run the regression suite"),
    ("[BLOCKED] needs org policy change", dash.BLOCKER_PLATFORM_POLICY, "needs org policy change"),
    ("[DECISION] pick option A or B", dash.BLOCKER_NEW_DECISION, "pick option A or B"),
    ("plain untagged question", dash.BLOCKER_NEW_DECISION, "plain untagged question"),
])
def test_parse_blocker_tag(subject, expected_source, expected_clean):
    assert dash.parse_blocker_tag(subject) == (expected_source, expected_clean)


# ---------------------------------------------------------------------------
# action-id registry: compute / register / check / consume
# ---------------------------------------------------------------------------

def _action_kwargs(**overrides):
    kwargs = dict(
        movement_id="NXS-LOCAL-9999", relay_file="relay/NXS-LOCAL-9999-x.json",
        operation="record_decision", args={"marker": "RELAY_DECISION", "subject": "s", "text": "t"},
        working_directory="/tmp/wt", source_relay_seq=3,
    )
    kwargs.update(overrides)
    return kwargs


def test_compute_action_id_is_deterministic():
    a = dash.compute_action_id(**_action_kwargs())
    b = dash.compute_action_id(**_action_kwargs())
    assert a == b
    assert len(a) == 16


def test_compute_action_id_changes_with_source_relay_seq():
    a = dash.compute_action_id(**_action_kwargs(source_relay_seq=3))
    b = dash.compute_action_id(**_action_kwargs(source_relay_seq=4))
    assert a != b


def test_register_action_is_idempotent(tmp_path):
    state_dir = tmp_path / "state"
    kwargs = _action_kwargs()
    id1 = dash.register_action(state_dir, kwargs["movement_id"], relay_file=kwargs["relay_file"],
                                operation=kwargs["operation"], args=kwargs["args"],
                                working_directory=kwargs["working_directory"], source_relay_seq=kwargs["source_relay_seq"])
    id2 = dash.register_action(state_dir, kwargs["movement_id"], relay_file=kwargs["relay_file"],
                                operation=kwargs["operation"], args=kwargs["args"],
                                working_directory=kwargs["working_directory"], source_relay_seq=kwargs["source_relay_seq"])
    assert id1 == id2
    registry = dash.load_actions(state_dir, kwargs["movement_id"])
    assert list(registry.keys()) == [id1]


def test_check_action_unknown_id_rejected(tmp_path):
    ok, reason = dash.check_action(tmp_path / "state", "NXS-LOCAL-9999", "deadbeefdeadbeef", 1)
    assert ok is False
    assert "unknown" in reason


def test_check_action_stale_when_relay_seq_advanced(tmp_path):
    state_dir = tmp_path / "state"
    kwargs = _action_kwargs(source_relay_seq=3)
    action_id = dash.register_action(state_dir, kwargs["movement_id"], relay_file=kwargs["relay_file"],
                                      operation=kwargs["operation"], args=kwargs["args"],
                                      working_directory=kwargs["working_directory"], source_relay_seq=3)
    ok, reason = dash.check_action(state_dir, kwargs["movement_id"], action_id, current_relay_seq=4)
    assert ok is False
    assert "stale" in reason


def test_check_action_ok_when_seq_matches(tmp_path):
    state_dir = tmp_path / "state"
    kwargs = _action_kwargs(source_relay_seq=3)
    action_id = dash.register_action(state_dir, kwargs["movement_id"], relay_file=kwargs["relay_file"],
                                      operation=kwargs["operation"], args=kwargs["args"],
                                      working_directory=kwargs["working_directory"], source_relay_seq=3)
    ok, record = dash.check_action(state_dir, kwargs["movement_id"], action_id, current_relay_seq=3)
    assert ok is True
    assert record["operation"] == "record_decision"


def test_mark_consumed_then_check_action_rejects_duplicate_click(tmp_path):
    state_dir = tmp_path / "state"
    kwargs = _action_kwargs(source_relay_seq=3)
    action_id = dash.register_action(state_dir, kwargs["movement_id"], relay_file=kwargs["relay_file"],
                                      operation=kwargs["operation"], args=kwargs["args"],
                                      working_directory=kwargs["working_directory"], source_relay_seq=3)
    dash.mark_consumed(state_dir, kwargs["movement_id"], action_id, consumed_relay_seq=4)
    ok, reason = dash.check_action(state_dir, kwargs["movement_id"], action_id, current_relay_seq=4)
    assert ok is False
    assert "consumed" in reason


# ---------------------------------------------------------------------------
# config read/write
# ---------------------------------------------------------------------------

def test_read_config_defaults_when_no_file(tmp_path):
    cfg = dash.read_config(tmp_path)
    assert cfg == {"poll_interval_seconds": orch.WATCH_INTERVAL_DEFAULT}


def test_write_then_read_config_round_trips(tmp_path):
    ok, result = dash.write_config(tmp_path, 42)
    assert ok is True
    assert result == {"poll_interval_seconds": 42}
    assert dash.read_config(tmp_path) == {"poll_interval_seconds": 42}


def test_write_config_rejects_non_integer(tmp_path):
    ok, reason = dash.write_config(tmp_path, "soon")
    assert ok is False
    assert "integer" in reason


def test_write_config_rejects_bool_masquerading_as_int(tmp_path):
    ok, reason = dash.write_config(tmp_path, True)
    assert ok is False


def test_write_config_rejects_out_of_range(tmp_path):
    ok, reason = dash.write_config(tmp_path, 0)
    assert ok is False
    assert "between" in reason
    ok2, _ = dash.write_config(tmp_path, 999999)
    assert ok2 is False


def test_read_config_falls_back_on_corrupt_file(tmp_path):
    (tmp_path / ".nexus").mkdir()
    (tmp_path / ".nexus" / "dashboard_config.json").write_text("not json", encoding="utf-8")
    assert dash.read_config(tmp_path) == {"poll_interval_seconds": orch.WATCH_INTERVAL_DEFAULT}


# ---------------------------------------------------------------------------
# end-to-end: pending-action card -> execute_action -> relay actually changes
# ---------------------------------------------------------------------------

def _start_report(**overrides):
    report = {
        "baseline": {"origin_main": "deadbeef"}, "objective": "Exercise the dashboard.",
        "scope": {"in": ["do the thing"], "out": ["not that thing"]}, "movement_type": "VALIDATION",
        "requirements": ["exactly one schema"], "acceptance_criteria": ["round-trips cleanly"],
        "validation_plan": ["run the focused suite"], "invariants": ["repository stays authoritative"],
        "risks": [], "context_not_loaded": [],
        "recommended_reasoning": {"tier": "Normal", "reason": "deterministic implementation"},
        "git": {"lane": "feature/x", "base": "origin/main"}, "merge_gate": "n/a",
        "deployment_direction": "local validation only", "output_contract": ["exactly one relay file"],
    }
    report.update(overrides)
    return report


def _make_relay(tmp_path: Path, movement: str = "TEST_MOVEMENT") -> Path:
    """Returns the created relay file's own path -- `create` assigns the
    NXS-LOCAL-<NNNN> id itself (always 0001 for a fresh, per-test relay_dir);
    it is not derived from `movement`, which only feeds the filename slug."""
    relay_dir = tmp_path / "relay"
    start = tmp_path / "start.json"
    start.write_text(json.dumps({
        "protocol_version": 2, "message_type": "SESSION_START", "movement": movement,
        "refs": [], "report": _start_report(),
    }), encoding="utf-8")
    rc = lr.main(["create", "--role", "po", "--start", str(start), "--dir", str(relay_dir)])
    assert rc == lr.EXIT_OK
    return next(relay_dir.glob("NXS-LOCAL-*.json"))


def _post_engineer_question(relay_file: Path, subject: str, text: str = "please decide") -> None:
    rc = lr.main([
        "append", "--file", str(relay_file), "--role", "engineer", "--marker", "RELAY_QUESTION",
        "--subject", subject, "--text", text, "--next", "po",
    ])
    assert rc == lr.EXIT_OK


def test_build_pending_action_card_new_decision_registers_two_actions(tmp_path):
    relay_file = _make_relay(tmp_path, "NXS-LOCAL-0001")
    _post_engineer_question(relay_file, "[DECISION] pick an option")
    relay_obj = json.loads(relay_file.read_text(encoding="utf-8"))
    state_dir = tmp_path / "state"

    card = dash.build_pending_action_card(
        movement_id="NXS-LOCAL-0001", relay_file=relay_file, relay_obj=relay_obj,
        worktree_path=str(tmp_path), state_dir=state_dir,
    )
    assert card["blocker_source"] == dash.BLOCKER_NEW_DECISION
    assert card["requested_action"] == "pick an option"
    assert card["primary_operation"] == dash.OPERATION_RECORD_DECISION
    assert card["primary_action_id"] and card["reject_action_id"]
    assert card["primary_action_id"] != card["reject_action_id"]


def test_build_pending_action_card_platform_policy_has_no_action_ids(tmp_path):
    relay_file = _make_relay(tmp_path, "NXS-LOCAL-0001")
    _post_engineer_question(relay_file, "[BLOCKED] needs org approval")
    relay_obj = json.loads(relay_file.read_text(encoding="utf-8"))

    card = dash.build_pending_action_card(
        movement_id="NXS-LOCAL-0001", relay_file=relay_file, relay_obj=relay_obj,
        worktree_path=str(tmp_path), state_dir=tmp_path / "state",
    )
    assert card["blocker_source"] == dash.BLOCKER_PLATFORM_POLICY
    assert card["primary_action_id"] is None


def test_build_pending_action_card_none_when_not_po_turn(tmp_path):
    relay_file = _make_relay(tmp_path, "NXS-LOCAL-0001")
    relay_obj = json.loads(relay_file.read_text(encoding="utf-8"))  # next_actor is "engineer" fresh from create
    card = dash.build_pending_action_card(
        movement_id="NXS-LOCAL-0001", relay_file=relay_file, relay_obj=relay_obj,
        worktree_path=str(tmp_path), state_dir=tmp_path / "state",
    )
    assert card is None


def test_execute_action_appends_relay_decision_and_flips_turn_back_to_engineer(tmp_path):
    relay_file = _make_relay(tmp_path, "NXS-LOCAL-0001")
    _post_engineer_question(relay_file, "[DECISION] approve the plan")
    relay_obj = json.loads(relay_file.read_text(encoding="utf-8"))
    state_dir = tmp_path / "state"

    card = dash.build_pending_action_card(
        movement_id="NXS-LOCAL-0001", relay_file=relay_file, relay_obj=relay_obj,
        worktree_path=str(tmp_path), state_dir=state_dir,
    )
    result = dash.execute_action(state_dir, "NXS-LOCAL-0001", relay_file, card["primary_action_id"])
    assert result["next_actor"] == "engineer"

    updated = json.loads(relay_file.read_text(encoding="utf-8"))
    assert updated["entries"][-1]["marker"] == "RELAY_DECISION"
    assert updated["next_actor"] == "engineer"

    # Duplicate click on the same (now-consumed) action id is rejected.
    with pytest.raises(dash.DashboardError, match="consumed"):
        dash.execute_action(state_dir, "NXS-LOCAL-0001", relay_file, card["primary_action_id"])


def test_execute_action_rejects_stale_id_after_relay_changed_underneath_it(tmp_path):
    relay_file = _make_relay(tmp_path, "NXS-LOCAL-0001")
    _post_engineer_question(relay_file, "[DECISION] approve the plan")
    relay_obj = json.loads(relay_file.read_text(encoding="utf-8"))
    state_dir = tmp_path / "state"

    card = dash.build_pending_action_card(
        movement_id="NXS-LOCAL-0001", relay_file=relay_file, relay_obj=relay_obj,
        worktree_path=str(tmp_path), state_dir=state_dir,
    )
    # Something else appends to the relay before the button click lands
    # (e.g. a RELAY_CORRECTION posted after dispatch) -- the previously
    # issued id must no longer apply.
    lr.main([
        "append", "--file", str(relay_file), "--role", "po", "--marker", "RELAY_CORRECTION",
        "--subject", "actually wait", "--text", "hold on", "--next", "engineer",
    ])
    with pytest.raises(dash.DashboardError, match="stale"):
        dash.execute_action(state_dir, "NXS-LOCAL-0001", relay_file, card["primary_action_id"])


def test_send_message_appends_immediately_when_it_is_the_po_turn(tmp_path):
    relay_file = _make_relay(tmp_path, "NXS-LOCAL-0001")
    _post_engineer_question(relay_file, "[DECISION] approve?")
    state_dir = tmp_path / "state"

    result = dash.send_message(state_dir, "NXS-LOCAL-0001", relay_file, "go ahead")
    assert result["delivery_state"] == "saved_queued_for_next_resume"
    assert result["appended"] is True
    updated = json.loads(relay_file.read_text(encoding="utf-8"))
    assert updated["entries"][-1]["marker"] == "RELAY_NOTE"
    assert updated["next_actor"] == "engineer"


# ---------------------------------------------------------------------------
# GOV.ORCH.4 section 3.3: derive_health -- AC-5
# ---------------------------------------------------------------------------

def _health(**kwargs):
    defaults = dict(
        pid_alive=True, relay_status="AWAITING_ENGINEER", next_actor="engineer",
        phase=orch.PHASE_RUNNING, idle_seconds=0.0, stuck_after_seconds=900,
    )
    defaults.update(kwargs)
    return dash.derive_health(**defaults)


def test_health_done_when_relay_closed():
    assert _health(relay_status="CLOSED") == dash.HEALTH_DONE


def test_health_done_when_phase_done_even_if_relay_status_missing():
    assert _health(relay_status=None, phase=orch.PHASE_DONE) == dash.HEALTH_DONE


def test_health_failed_when_phase_failed():
    assert _health(phase=orch.PHASE_FAILED) == dash.HEALTH_FAILED


def test_health_awaiting_po_even_when_pid_is_dead():
    # A dead process with next_actor == "po" is the ordinary pause after an
    # engineer session posts a question -- not exited_without_close.
    assert _health(next_actor="po", pid_alive=False) == dash.HEALTH_AWAITING_PO


def test_health_exited_without_close_when_pid_dead_and_not_terminal():
    assert _health(pid_alive=False, next_actor="engineer") == dash.HEALTH_EXITED_WITHOUT_CLOSE


def test_health_silent_when_alive_but_idle_past_the_threshold():
    assert _health(idle_seconds=901, stuck_after_seconds=900) == dash.HEALTH_SILENT


def test_health_healthy_when_alive_and_recently_active():
    assert _health(idle_seconds=10, stuck_after_seconds=900) == dash.HEALTH_HEALTHY


def test_health_healthy_when_idle_seconds_unknown():
    # No log yet observed -- honestly "healthy" (pid alive, nothing says
    # otherwise), never silent from a guess.
    assert _health(idle_seconds=None) == dash.HEALTH_HEALTHY


@pytest.mark.parametrize("kwargs,expected", [
    ({"relay_status": "CLOSED"}, dash.HEALTH_DONE),
    ({"phase": orch.PHASE_FAILED}, dash.HEALTH_FAILED),
    ({"next_actor": "po"}, dash.HEALTH_AWAITING_PO),
    ({"pid_alive": False}, dash.HEALTH_EXITED_WITHOUT_CLOSE),
    ({"idle_seconds": 5000}, dash.HEALTH_SILENT),
    ({}, dash.HEALTH_HEALTHY),
])
def test_health_never_returns_anything_outside_the_six_values(kwargs, expected):
    result = _health(**kwargs)
    assert result == expected
    assert result in dash.ALL_HEALTH_VALUES


# ---------------------------------------------------------------------------
# GOV.ORCH.4 section 3.3/3.4: stuck_after_seconds round-trips -- AC-10
# ---------------------------------------------------------------------------

def test_config_for_api_fills_in_the_stuck_after_seconds_default(tmp_path):
    cfg = dash.config_for_api(tmp_path)
    assert cfg["stuck_after_seconds"] == dash.DEFAULT_STUCK_AFTER_SECONDS


def test_stuck_after_seconds_round_trips_through_write_and_read_config(tmp_path):
    ok, result = dash.write_config(tmp_path, stuck_after_seconds=1200)
    assert ok is True
    assert result["stuck_after_seconds"] == 1200
    assert dash.read_config(tmp_path)["stuck_after_seconds"] == 1200
    assert dash.config_for_api(tmp_path)["stuck_after_seconds"] == 1200


def test_writing_stuck_after_seconds_alone_preserves_a_previously_set_poll_interval(tmp_path):
    dash.write_config(tmp_path, poll_interval_seconds=42)
    dash.write_config(tmp_path, stuck_after_seconds=1200)
    cfg = dash.read_config(tmp_path)
    assert cfg == {"poll_interval_seconds": 42, "stuck_after_seconds": 1200}


def test_write_config_rejects_invalid_stuck_after_seconds(tmp_path):
    ok, reason = dash.write_config(tmp_path, stuck_after_seconds="soon")
    assert ok is False
    assert "integer" in reason
    ok2, reason2 = dash.write_config(tmp_path, stuck_after_seconds=1)
    assert ok2 is False
    assert "between" in reason2


# ---------------------------------------------------------------------------
# GOV.ORCH.4 section 3.4: /api/board -- AC-6
# ---------------------------------------------------------------------------

def _save_minimal_record(state_dir: Path, movement_id: str, **overrides) -> None:
    record = {
        "movement_id": movement_id, "phase": orch.PHASE_RUNNING, "pid": os.getpid(),
        "worktree_path": None, "retry_count": 0, "branch": "feature/x",
        "provider": "claude", "started_at": "2026-09-11T10:00:00Z",
    }
    record.update(overrides)
    orch._save_state(state_dir, movement_id, record)


def _relay_id_of(relay_file: Path) -> str:
    return json.loads(relay_file.read_text(encoding="utf-8"))["id"]


def _make_relay_named(tmp_path: Path, movement: str) -> Path:
    """`_make_relay`'s own return value (`next(relay_dir.glob(...))`) is
    only reliable when it is the sole relay file in the directory --
    `glob` order is not creation order. Once a test needs several relay
    files in one shared `relay_dir`, resolve each call's own file by its
    own movement slug instead."""
    _make_relay(tmp_path, movement)
    relay_dir = tmp_path / "relay"
    slug = movement.strip().lower()
    candidates = list(relay_dir.glob(f"NXS-LOCAL-*-{slug}.json"))
    assert len(candidates) == 1, candidates
    return candidates[0]


def test_build_board_groups_one_movement_per_health_into_the_right_columns(tmp_path):
    state_dir, relay_dir, repo_root = tmp_path / "state", tmp_path / "relay", tmp_path

    relay_file_open = _make_relay_named(tmp_path, "open-one")
    id_open = _relay_id_of(relay_file_open)
    _save_minimal_record(state_dir, id_open, phase=orch.PHASE_RUNNING)  # healthy, relay open

    relay_file_awaiting = _make_relay_named(tmp_path, "awaiting-one")
    id_awaiting = _relay_id_of(relay_file_awaiting)
    _post_engineer_question(relay_file_awaiting, "[DECISION] pick")
    _save_minimal_record(state_dir, id_awaiting, phase=orch.PHASE_RUNNING, pid=None)

    relay_file_closed = _make_relay_named(tmp_path, "closed-one")
    id_closed = _relay_id_of(relay_file_closed)
    lr.main(["append", "--file", str(relay_file_closed), "--role", "engineer", "--marker", "SESSION_CLOSE",
             "--report", str(_write_close_report(tmp_path)), "--outcome", "DONE"])
    _save_minimal_record(state_dir, id_closed, phase=orch.PHASE_DONE, pid=None)

    board = dash.build_board(state_dir, relay_dir, repo_root, retry_limit=2, stuck_after_seconds=900)
    ids = {row["movement_id"] for row in board["open"]}
    assert id_open in ids
    assert {row["movement_id"] for row in board["awaiting_po"]} == {id_awaiting}
    assert {row["movement_id"] for row in board["closed"]} == {id_closed}
    assert board["totals"]["open"] == len(board["open"])
    assert board["totals"]["awaiting_po"] == 1
    assert board["totals"]["closed"] == 1


def test_build_board_rows_carry_every_field_the_kanban_card_needs(tmp_path):
    # AC-12: the JS render function is pure DOM string-building with no
    # jsdom-free harness available in this suite -- per the contract's own
    # fallback, this asserts the server-side board row carries every field
    # section 3.5's card content lists (movement id/objective, provider ·
    # model · effort requested-and-observed, process status, work stage,
    # duration, idle time, tokens in/cache-read/out + cache-hit% + cost,
    # PR number/state).
    state_dir, relay_dir, repo_root = tmp_path / "state", tmp_path / "relay", tmp_path
    _save_minimal_record(state_dir, "NXS-LOCAL-0060")

    board = dash.build_board(state_dir, relay_dir, repo_root, retry_limit=2, stuck_after_seconds=900)
    row = (board["open"] + board["awaiting_po"] + board["closed"])[0]
    for field in (
        "movement_id", "objective", "provider", "model_requested", "effort_requested",
        "model_observed", "effort_observed", "health", "stage", "process_status",
        "idle_seconds", "started_at", "ended_at", "duration_s", "usage",
        "pr_number", "pr_state", "verify_passed", "branch",
    ):
        assert field in row, field
    for field in (
        "input_tokens", "cache_read_input_tokens", "output_tokens", "cache_hit_ratio",
        "cost_usd", "cost_source",
    ):
        assert field in row["usage"], field


def _write_close_report(tmp_path) -> Path:
    report = {
        "completed": ["x"], "changed": ["a.py"], "preserved": ["x"],
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
    path = tmp_path / f"close_report_{len(list(tmp_path.glob('close_report_*.json')))}.json"
    path.write_text(json.dumps(report), encoding="utf-8")
    return path


# ---------------------------------------------------------------------------
# GOV.ORCH.4 section 3.4: traffic view -- AC-7
# ---------------------------------------------------------------------------

def test_build_traffic_labels_sent_and_received_and_carries_session_close_fields(tmp_path):
    relay_file = _make_relay(tmp_path, "NXS-LOCAL-0020")
    _post_engineer_question(relay_file, "[DECISION] approve?")
    lr.main([
        "append", "--file", str(relay_file), "--role", "po", "--marker", "RELAY_DECISION",
        "--subject", "approved", "--text", "go", "--authorized-by", "PO", "--scope", "x",
        "--supersedes", "none", "--next", "engineer",
    ])
    lr.main(["append", "--file", str(relay_file), "--role", "engineer", "--marker", "SESSION_CLOSE",
             "--report", str(_write_close_report(tmp_path)), "--outcome", "DONE"])
    relay_obj = json.loads(relay_file.read_text(encoding="utf-8"))

    traffic = dash.build_traffic(relay_obj["entries"])
    directions = {(item["marker"], item["direction"]) for item in traffic}
    assert ("SESSION_START", "sent") in directions
    assert ("RELAY_QUESTION", "received") in directions
    assert ("RELAY_DECISION", "sent") in directions
    assert ("SESSION_CLOSE", "received") in directions

    close_item = next(item for item in traffic if item["marker"] == "SESSION_CLOSE")
    assert close_item["outcome"] == "DONE"
    assert close_item["changed"] == ["a.py"]
    assert close_item["validation"]["targeted"] == "1 passed"


def test_build_traffic_for_movement_interleaves_dispatch_and_verify_rows(tmp_path):
    relay_file = _make_relay(tmp_path, "NXS-LOCAL-0021")
    relay_obj = json.loads(relay_file.read_text(encoding="utf-8"))
    record = {
        "movement_id": "NXS-LOCAL-0021", "started_at": "2026-09-11T09:00:00Z", "provider": "claude",
        "verify": {"passed": True, "steps": []},
    }
    items = dash.build_traffic_for_movement(record, relay_obj)
    markers = [item["marker"] for item in items]
    assert "DISPATCH" in markers
    assert "VERIFY" in markers
    assert markers.index("DISPATCH") < markers.index("VERIFY")


# ---------------------------------------------------------------------------
# GOV.ORCH.4 section 3.4: /api/movements backward compatibility -- AC-8
# ---------------------------------------------------------------------------

def test_gather_movements_adds_health_and_usage_without_removing_existing_keys(tmp_path):
    state_dir, relay_dir = tmp_path / "state", tmp_path / "relay"
    _make_relay(tmp_path, "NXS-LOCAL-0030")
    _save_minimal_record(state_dir, "NXS-LOCAL-0030")

    before_keys = {
        "movement_id", "phase", "pid", "pid_alive", "revision", "branch", "worktree_path", "started_at",
        "retry_count", "relay_status", "last_marker", "last_timestamp", "last_activity",
        "heartbeat_age_seconds", "failure_reason", "exit_code", "provider", "model_requested",
        "effort_requested", "merge_mode", "legacy_state_dir", "process_status", "work_stage",
        "next_actor", "open_pr", "pending_action", "outbox_pending",
    }
    result = dash.gather_movements(state_dir, relay_dir, retry_limit=2)
    row = result["movements"][0]
    assert before_keys <= set(row.keys())
    assert "health" in row and row["health"] in dash.ALL_HEALTH_VALUES
    assert "usage" in row and row["usage"]["provider"] == "claude"


# ---------------------------------------------------------------------------
# GOV.ORCH.4 section 3.4/3.6: /api/usage -- exercised via build_usage_report
# ---------------------------------------------------------------------------

def test_build_usage_report_totals_across_movements(tmp_path):
    state_dir, relay_dir, repo_root = tmp_path / "state", tmp_path / "relay", tmp_path
    _save_minimal_record(state_dir, "NXS-LOCAL-0040", started_at="2026-09-11T09:00:00Z")
    _save_minimal_record(state_dir, "NXS-LOCAL-0041", started_at="2026-09-11T09:00:00Z")

    report = dash.build_usage_report(state_dir, relay_dir, repo_root)
    assert {m["movement_id"] for m in report["movements"]} == {"NXS-LOCAL-0040", "NXS-LOCAL-0041"}
    assert report["totals"]["movements"] == 2


def test_build_usage_report_since_filters_by_started_at(tmp_path):
    state_dir, relay_dir, repo_root = tmp_path / "state", tmp_path / "relay", tmp_path
    _save_minimal_record(state_dir, "NXS-LOCAL-0050", started_at="2026-09-01T00:00:00Z")
    _save_minimal_record(state_dir, "NXS-LOCAL-0051", started_at="2026-09-11T00:00:00Z")

    report = dash.build_usage_report(state_dir, relay_dir, repo_root, since="2026-09-10T00:00:00Z")
    assert {m["movement_id"] for m in report["movements"]} == {"NXS-LOCAL-0051"}


def test_send_message_queues_to_outbox_when_not_po_turn(tmp_path):
    relay_file = _make_relay(tmp_path, "NXS-LOCAL-0001")  # fresh create -> next_actor is "engineer"
    state_dir = tmp_path / "state"

    result = dash.send_message(state_dir, "NXS-LOCAL-0001", relay_file, "hang tight")
    assert result["delivery_state"] == "saved_not_yet_your_turn"
    assert result["appended"] is False
    outbox = dash.load_outbox(state_dir, "NXS-LOCAL-0001")
    assert len(outbox) == 1 and outbox[0]["text"] == "hang tight"
    # Never appended -- the relay is unchanged.
    updated = json.loads(relay_file.read_text(encoding="utf-8"))
    assert len(updated["entries"]) == 1
