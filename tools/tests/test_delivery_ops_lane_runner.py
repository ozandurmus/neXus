"""lane-runner.sh (tools/delivery/ops): distinct lane outcomes, exit 3 never silent, FAILED_WORKTREE re-queued once."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from delivery_ops_support import OpsEnv  # noqa: E402

LANE = "20261124-1200-G1-D1-p1-sample"


@pytest.fixture
def ops(tmp_path):
    e = OpsEnv(tmp_path)
    e.git_repo(e.repo)
    return e


def outcome(ops, text):
    log = ops.logs / "probe.nohup"
    log.write_text(text)
    return ops.source("lane-runner.sh", f'lane_outcome "{log}"').stdout.strip()


@pytest.mark.parametrize("text,expected", [
    ("", "RUNNING"),
    ("report only\n", "RUNNING"),
    ("[argon] exit=0 head=abc x\n", "OK"),
    ("[lane] no commit made\n[argon] exit=3 head=abc x\n", "NO_CHANGE"),
    ("[argon] FAILED: a permission was auto-denied; extend settings\n[argon] exit=3 head=abc x\n", "PERMISSION_DENIED"),
    ('the "run_command" permission was auto-denied\n[argon] exit=3 head=abc x\n', "PERMISSION_DENIED"),
    ("backend returned an empty conversation id\n[argon] exit=1 head=abc x\n", "INFRA_ERROR"),
    ("read tcp: connection reset by peer\n[argon] exit=3 head=abc x\n", "INFRA_ERROR"),
    ("RESOURCE_EXHAUSTED: quota\n[argon] exit=1 head=abc x\n", "QUOTA"),
    ("some failure\n[argon] exit=1 head=abc x\n", "FAILED"),
    ("some failure\n[argon] exit=2 head=abc x\n", "FAILED"),
])
def test_lane_outcome_classification(ops, text, expected):
    assert outcome(ops, text) == expected


def test_infra_text_early_in_the_log_does_not_make_a_clean_no_change_an_infra_error(ops):
    text = "connection reset (retrying)\n" + "work line\n" * 60 + "[lane] no commit made\n[argon] exit=3 head=abc x\n"
    assert outcome(ops, text) == "NO_CHANGE"


def finish(ops, name=LANE):
    r = ops.source("lane-runner.sh", f'finish_lane "$Q/started/{name}.brief"')
    assert r.returncode == 0, r.stderr
    return r.stdout


def test_exit_0_goes_to_the_ship_queue_and_done(ops):
    ops.write_lane(LANE, "[argon] exit=0 head=abc x", meta="base=origin/main\nallow_paths=1\ntitle=T\n")
    out = finish(ops)
    entry = (ops.sq / f"{LANE}.ship").read_text()
    assert f"branch=lane/q-{LANE}" in entry and f"name={LANE}" in entry and "allow_paths=1" in entry and "title=T" in entry
    assert (ops.lq / "done" / f"{LANE}.brief").exists() and (ops.lq / "done" / f"{LANE}.outcome").read_text().strip() == "OK"
    assert not (ops.lq / "started" / f"{LANE}.brief").exists() and "ship-queued" in out


def test_a_returned_review_flag_stays_on_the_ship_entry(ops):
    ops.write_lane(LANE, "[argon] exit=0 head=abc x", meta="base=lane/q-x\nreview_returned=1\n")
    finish(ops)
    assert "review_returned=1" in (ops.sq / f"{LANE}.ship").read_text()


def test_ship_no_lane_is_not_queued(ops):
    ops.write_lane(LANE, "[argon] exit=0 head=abc x", meta="base=origin/main\nship=no\n")
    finish(ops)
    assert not (ops.sq / f"{LANE}.ship").exists() and (ops.lq / "done" / f"{LANE}.brief").exists()


def test_exit_3_without_a_marker_is_an_explicit_no_change_not_a_silent_done(ops):
    ops.write_lane(LANE, "[argon] exit=3 head=abc x", report="[lane] no commit made")
    out = finish(ops)
    assert "NO_CHANGE" in out
    assert (ops.lq / "done" / f"{LANE}.outcome").read_text().strip() == "NO_CHANGE"
    assert not (ops.sq / f"{LANE}.ship").exists()


def test_a_permission_denial_is_held_with_an_alert_never_done(ops):
    ops.write_lane(LANE, "[argon] exit=3 head=abc x", report='[argon] FAILED: a permission was auto-denied; the "run_command" permission')
    out = finish(ops)
    assert (ops.lq / "hold" / f"{LANE}.brief").exists() and (ops.lq / "hold" / f"{LANE}.meta").exists()
    assert not (ops.lq / "done" / f"{LANE}.brief").exists()
    assert "ALERT HELD" in out and "permission" in out


def test_an_infra_error_is_requeued_once_then_held_with_an_alert(ops):
    ops.write_lane(LANE, "[argon] exit=3 head=abc x", report="backend returned an empty conversation id", age_s=600)
    out = finish(ops)
    assert (ops.lq / f"{LANE}.brief").exists() and (ops.lq / f".transient-{LANE}").exists()
    assert "INFRA error" in out and not (ops.lq / "hold" / f"{LANE}.brief").exists()
    assert not (ops.lq / "PAUSED").exists()
    # second one in a row: the re-queued lane started again and failed the same way
    (ops.lq / f"{LANE}.brief").rename(ops.lq / "started" / f"{LANE}.brief")
    (ops.logs / f"q-{LANE}.nohup").write_text("backend returned an empty conversation id\n[argon] exit=3 head=abc x\n")
    out = finish(ops)
    assert (ops.lq / "hold" / f"{LANE}.brief").exists() and "ALERT HELD" in out
    assert not (ops.lq / f".transient-{LANE}").exists()


def test_a_fast_failing_lane_pauses_the_runner_and_is_requeued(ops):
    ops.write_lane(LANE, "[argon] exit=1 head=abc x", report="boom", age_s=30)
    out = finish(ops)
    assert (ops.lq / "PAUSED").exists() and (ops.lq / f"{LANE}.brief").exists() and "FAST FAIL" in out


def test_a_slow_failing_lane_is_done_failed_with_an_alert(ops):
    ops.write_lane(LANE, "[argon] exit=1 head=abc x", report="boom", age_s=900)
    out = finish(ops)
    assert not (ops.lq / "PAUSED").exists() and "ALERT FAILED" in out
    assert (ops.lq / "done" / f"{LANE}.outcome").read_text().strip() == "FAILED"


def test_a_quota_hit_switches_to_claude_and_requeues(ops):
    ops.write_lane(LANE, "[argon] exit=1 head=abc x", report="RESOURCE_EXHAUSTED: usage limit", age_s=900)
    out = finish(ops)
    assert (ops.lq / "ENGINE_CLAUDE").exists() and (ops.lq / f"{LANE}.brief").exists() and "quota hit" in out


def test_a_running_lane_is_left_alone(ops):
    ops.write_lane(LANE, None, report="working")
    assert finish(ops) == ""
    assert (ops.lq / "started" / f"{LANE}.brief").exists()


def test_worktree_failure_requeues_once_then_alerts(ops):
    brief = ops.lq / "17-sample.brief"
    brief.write_text("b\n")
    (ops.lq / "17-sample.meta").write_text("base=origin/main\n")
    r = ops.source("lane-runner.sh", f'wt_failed "{brief}" 17-sample')
    assert "brief stays queued for one retry" in r.stdout and brief.exists()
    assert (ops.lq / ".wtretry-17-sample").exists()
    r = ops.source("lane-runner.sh", f'wt_failed "{brief}" 17-sample')
    assert "ALERT worktree failed twice" in r.stdout
    assert not brief.exists() and (ops.lq / "done" / "17-sample.brief.FAILED_WORKTREE").exists()
    assert (ops.lq / "done" / "17-sample.meta").exists() and not (ops.lq / ".wtretry-17-sample").exists()


def test_start_next_lane_requeues_a_brief_whose_worktree_cannot_be_created(ops):
    """End to end through start_next_lane: a base ref that does not exist makes `git worktree add` fail."""
    brief = ops.lq / "20261124-1201-G2-D2-p1-bad.brief"
    brief.write_text("b\n")
    (ops.lq / "20261124-1201-G2-D2-p1-bad.meta").write_text("base=refs/heads/does-not-exist\nengine=claude\n")
    ops.fake("ps", "exit 0")   # no engine process running
    for _ in range(2):
        r = ops.source("lane-runner.sh", 'MAX=3; start_next_lane')
        assert r.returncode == 0, r.stderr
    assert (ops.lq / "done" / "20261124-1201-G2-D2-p1-bad.brief.FAILED_WORKTREE").exists()
    assert "ALERT worktree failed twice" in r.stdout
