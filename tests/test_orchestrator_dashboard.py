"""project/backlog.json id orchestrator_interactive_dashboard_app -- AC-7
targeted tests for the data/API layer only (no browser test harness, same
testing philosophy relay/NXS-LOCAL-0013's own watch-loop tests used):
stage-derivation, the action-id registry/staleness check, and the config
read/write "endpoint" (its underlying functions, exercised directly).
"""
from __future__ import annotations

import json
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


def test_usage_metrics_reads_latest_provider_usage(tmp_path):
    nexus = tmp_path / ".nexus"
    nexus.mkdir()
    (nexus / "engineer.log").write_text(
        '{"type":"turn.completed","usage":{"input_tokens":600000,"cached_input_tokens":500000,"output_tokens":12}}\n',
        encoding="utf-8",
    )
    assert dash.read_usage_metrics(str(tmp_path)) == {
        "input_tokens": 600000, "cached_input_tokens": 500000, "output_tokens": 12,
    }


def test_attention_flags_high_context_and_large_contract():
    alerts = dash.derive_attention(
        usage={"input_tokens": 600000},
        approved_task={"refs": list(range(9)), "report": {"acceptance_criteria": list(range(9))}},
        row={"phase": orch.PHASE_DONE, "process_status": "exited"},
    )
    assert len(alerts) == 2
    assert "600,000" in alerts[0]


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
