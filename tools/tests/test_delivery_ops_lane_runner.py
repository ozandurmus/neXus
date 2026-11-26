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
    ("error: interrupted\n\n[argon] exit=1 head=abc x\n", "INFRA_ERROR"),
    ("the run was interrupted by a test\n[argon] exit=1 head=abc x\n", "FAILED"),
    ("RESOURCE_EXHAUSTED: quota\n[argon] exit=1 head=abc x\n", "QUOTA"),
    ("some failure\n[argon] exit=1 head=abc x\n", "FAILED"),
    ("some failure\n[argon] exit=2 head=abc x\n", "FAILED"),
])
def test_lane_outcome_classification(ops, text, expected):
    assert outcome(ops, text) == expected


def test_infra_text_early_in_the_log_does_not_make_a_clean_no_change_an_infra_error(ops):
    text = "connection reset (retrying)\n" + "work line\n" * 60 + "[lane] no commit made\n[argon] exit=3 head=abc x\n"
    assert outcome(ops, text) == "NO_CHANGE"


def finish(ops, name=LANE, pre=""):
    # The harness gate is a background job in production; here it only records that it was started.
    r = ops.source("lane-runner.sh", f'start_harness_gate() {{ echo "GATE $1" >> "$L/gate-calls"; }}\n{pre}\n'
                                     f'finish_lane "$Q/started/{name}.brief"')
    assert r.returncode == 0, r.stderr
    return r.stdout


def gate_calls(ops):
    f = ops.logs / "gate-calls"
    return f.read_text().split("\n")[:-1] if f.exists() else []


def test_exit_0_goes_through_the_harness_gate_never_straight_to_the_ship_queue(ops):
    ops.write_lane(LANE, "[argon] exit=0 head=abc x", meta="base=origin/main\nallow_paths=1\ntitle=T\n")
    out = finish(ops)
    assert gate_calls(ops) == [f"GATE {LANE}"] and "harness gate started" in out
    assert not (ops.sq / f"{LANE}.ship").exists()   # the gate writes the entry from a typed harness exit
    # The gate reads the brief and meta from done/, so they are there before it starts.
    assert (ops.lq / "done" / f"{LANE}.brief").exists() and (ops.lq / "done" / f"{LANE}.meta").exists()
    assert (ops.lq / "done" / f"{LANE}.outcome").read_text().strip() == "OK"
    assert not (ops.lq / "started" / f"{LANE}.brief").exists()


def test_ship_no_lane_is_not_gated_or_queued(ops):
    ops.write_lane(LANE, "[argon] exit=0 head=abc x", meta="base=origin/main\nship=no\n")
    finish(ops)
    assert gate_calls(ops) == [] and not (ops.sq / f"{LANE}.ship").exists()
    assert (ops.lq / "done" / f"{LANE}.brief").exists()


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


def test_a_second_lane_hitting_the_same_quota_is_requeued_too_but_only_once(ops):
    (ops.lq / "ENGINE_CLAUDE").touch()        # the first lane of this quota window already switched
    ops.write_lane(LANE, "[argon] exit=3 head=abc x", report="RESOURCE_EXHAUSTED (code 429): Individual quota reached", age_s=900)
    finish(ops)
    assert (ops.lq / f"{LANE}.brief").exists()
    (ops.lq / f"{LANE}.brief").unlink()
    ops.write_lane(LANE, "[argon] exit=3 head=abc x", report="RESOURCE_EXHAUSTED (code 429): Individual quota reached", age_s=900)
    finish(ops)
    assert not (ops.lq / f"{LANE}.brief").exists()
    assert (ops.lq / "done" / f"{LANE}.outcome").read_text().strip() == "FAILED"


def test_an_agy_429_marks_the_model_and_does_not_switch_to_claude(ops):
    ops.write_lane(LANE, "[argon] exit=3 head=abc x", age_s=900, meta="base=origin/main\nagy_model=gemini-4-carbon-high\n",
                   report="RESOURCE_EXHAUSTED (code 429): Individual quota reached. Resets in 10m32s.")
    out = finish(ops)
    assert (ops.lq / ".agy-limited-gemini-4-carbon-high").exists() and not (ops.lq / "ENGINE_CLAUDE").exists()
    assert (ops.lq / f"{LANE}.brief").exists() and "rate-limited" in out


def test_routing_skips_a_rate_limited_model(ops):
    (ops.lq / ".agy-limited-gemini-4-carbon-high").touch()
    r = ops.source("lane-runner.sh", 'agy_limited gemini-4-carbon-high && echo LIMITED; agy_limited gemini-4-neon-high || echo FREE')
    assert "LIMITED" in r.stdout and "FREE" in r.stdout


def test_a_report_that_mentions_quotas_is_not_a_quota_hit(ops):
    # 2026-11-26: the planner's own prose ("... rate limit ... quota ... 429 ...") switched lanes to Claude and paused.
    ops.write_lane(LANE, "[argon] exit=3 head=abc x", age_s=60,
                   report="Findings: the agy quota note, a rate limit of 429 per window and usage limit wording.\n[lane] no commit made")
    out = finish(ops)
    assert not (ops.lq / "ENGINE_CLAUDE").exists() and not (ops.lq / "PAUSED").exists()
    assert (ops.lq / "done" / f"{LANE}.outcome").read_text().strip() == "NO_CHANGE" and "NO_CHANGE" in out


def test_the_real_agy_429_lines_are_a_quota_hit(ops):
    ops.write_lane(LANE, "[argon] exit=3 head=abc x", age_s=900, meta="base=origin/main\nagy_model=gemini-4-carbon-high\n",
                   report="error: Individual quota reached. Please upgrade your subscription to increase your limits. Resets in 10m32s.\n"
                          'AGY_ERROR: {"short_error":"RESOURCE_EXHAUSTED (code 429): Individual quota reached."}')
    out = finish(ops)
    assert (ops.lq / ".agy-limited-gemini-4-carbon-high").exists() and (ops.lq / f"{LANE}.brief").exists()


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


# ---- AGY_ONLY mode (PO decision record 2026-11-26) ---------------------------------------------------------------------

def agy_env(ops, agy_running=0):
    """Stub engines: argon_task_neon.py and claude_task.sh record their arguments; `ps` lists `agy_running` agy lanes."""
    (ops.state / "argon_task_neon.py").write_text("import sys\nopen(sys.argv[sys.argv.index('--log')+1] + '.args','w').write(' '.join(sys.argv))\n"
                                                  "print('[argon] exit=0 head=abc x')\n")
    (ops.state / "claude_task.sh").write_text('echo "CLAUDE $*"\n')
    ops.fake("ps", "".join('echo "/usr/bin/Python /s/argon_task_neon.py --worktree w"\n' for _ in range(agy_running)) + "exit 0")


def queue_brief(ops, name, meta):
    (ops.lq / f"{name}.brief").write_text("b\n")
    (ops.lq / f"{name}.meta").write_text(meta)


def start(ops, name, meta, agy_only=True, agy_running=0, extra=""):
    agy_env(ops, agy_running)
    (ops.lq / "started").mkdir(exist_ok=True)
    if agy_only:
        (ops.lq / "AGY_ONLY").touch()
    queue_brief(ops, name, meta)
    r = ops.source("lane-runner.sh", f"MAX=3; {extra}\nstart_next_lane")
    assert r.returncode == 0, r.stderr
    return r.stdout


def test_agy_only_routes_a_pin_claude_lane_to_agy(ops):
    out = start(ops, "20261126-1300-G5-D5-p1-pinned", "base=main\nengine=claude\npin_claude=1\nclaude_model=sonnet\n")
    assert "gemini-4-neon-medium" in out and "claude" not in out.replace("pin_claude", "")


def test_without_agy_only_pin_claude_still_runs_on_claude(ops):
    out = start(ops, "20261126-1300-G5-D5-p1-pinned", "base=main\nengine=claude\npin_claude=1\nclaude_model=sonnet\n", agy_only=False)
    assert "(claude sonnet" in out


def test_agy_only_ignores_engine_claude_and_agy_off(ops):
    (ops.lq / "ENGINE_CLAUDE").touch(); (ops.lq / "AGY_OFF").touch()
    out = start(ops, "20261126-1300-G5-D5-fix-x", "base=main\n")
    assert "gemini-4-neon-medium" in out


def test_agy_only_sends_a_state_sync_lane_to_agy_not_claude(ops):
    out = start(ops, "20261126-1300-G5-D5-state-sync", "base=main\nengine=claude\nclaude_model=sonnet\n")
    assert "gemini-4-neon-high" in out


def test_agy_only_lifts_the_half_and_half_slot_cap(ops):
    # Two agy lanes already run (MAX 3): normally the third spills to Claude, under AGY_ONLY it stays on agy.
    out = start(ops, "20261126-1300-G5-D5-p1-third", "base=main\n", agy_running=2)
    assert "gemini-4-neon-medium" in out
    ops.lq.joinpath("AGY_ONLY").unlink()
    for f in ops.lq.glob("20261126*"):
        f.unlink()
    out = start(ops, "20261126-1301-G6-D6-p1-fourth", "base=main\n", agy_only=False, agy_running=2)
    assert "(claude" in out


def test_explicit_gemini_model_in_the_meta_is_honored_by_an_agy_lane(ops):
    out = start(ops, "20261126-1300-G5-D5-p1-explicit", "base=main\nengine=agy\nmodel=gemini-4-neon-xhigh\n", agy_only=False)
    assert "gemini-4-neon-xhigh" in out and "carbon" not in out
    args = next(ops.logs.glob("q-*.log.args")).read_text()
    assert "--model gemini-4-neon-xhigh --effort xhigh" in args


def test_without_a_model_line_the_lane_name_still_routes(ops):
    out = start(ops, "20261126-1300-G5-D5-p1-named", "base=main\nengine=agy\n", agy_only=False)
    assert "gemini-4-neon-medium" in out


def test_agy_only_with_every_model_rate_limited_keeps_the_brief_queued(ops):
    for m in ("gemini-4-carbon-high", "gemini-4-neon-high", "gemini-4-neon-medium", "gemini-4-hydrogen-high"):
        (ops.lq / f".agy-limited-{m}").touch()
    out = start(ops, "20261126-1300-G5-D5-p1-limited", "base=main\nengine=claude\npin_claude=1\n")
    assert "stays queued" in out and (ops.lq / "20261126-1300-G5-D5-p1-limited.brief").exists()
    assert not (ops.lq / "ENGINE_CLAUDE").exists()


def test_agy_only_quota_hit_never_creates_engine_claude(ops):
    (ops.lq / "AGY_ONLY").touch()
    ops.write_lane(LANE, "[argon] exit=3 head=abc x", age_s=900, meta="base=origin/main\nagy_model=gemini-4-carbon-high\n",
                   report="error: Individual quota reached. Resets in 10m32s.")
    finish(ops)
    assert not (ops.lq / "ENGINE_CLAUDE").exists() and (ops.lq / f"{LANE}.brief").exists()
    assert (ops.lq / ".agy-limited-gemini-4-carbon-high").exists()


def planner_setup(ops, agy_only=True):
    (ops.state / "refill-p1.template").write_text("# Planner\nWrite 3 to 6 lane briefs into the lane queue.\n")
    (ops.lq / "NO_REFILL").touch()
    if agy_only:
        (ops.lq / "AGY_ONLY").touch()
    ops.git(ops.repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    (ops.lq / "hold").mkdir(exist_ok=True)
    (ops.lq / "hold" / "20261125-0900-G1-D1-held-one.brief").write_text("x\n")
    # QUEUE.md lives on origin/main
    (ops.repo / "project").mkdir(exist_ok=True)
    (ops.repo / "project" / "QUEUE.md").write_text("QUEUE-MARKER-LINE\n")
    ops.git(ops.repo, "add", "-A"); ops.git(ops.repo, "commit", "-q", "-m", "q")
    ops.git(ops.repo, "update-ref", "refs/remotes/origin/main", "HEAD")


def test_agy_only_planner_brief_carries_the_inlined_state_and_runs_on_agy(ops):
    planner_setup(ops)
    r = ops.source("lane-runner.sh", "maybe_refill")
    assert r.returncode == 0, r.stderr
    brief = next(ops.lq.glob("*refill-p1.brief")).read_text()
    meta = next(ops.lq.glob("*refill-p1.meta")).read_text()
    assert "QUEUE-MARKER-LINE" in brief and "held-one" in brief and "### status.sh" in brief and "planner-out/" in brief
    assert "All required ops state is already provided inline below; do NOT call read_file on paths outside your current worktree." in brief
    assert "exactly 3 lane briefs" in brief and len(brief) < 45000
    assert "engine=agy" in meta and "planner_out=1" in meta and "pin_claude" not in meta and "adddir" not in meta
    assert "claude_model" not in meta


def test_without_agy_only_the_planner_stays_on_claude(ops):
    planner_setup(ops, agy_only=False)
    ops.source("lane-runner.sh", "maybe_refill")
    brief = next(ops.lq.glob("*refill-p1.brief")).read_text()
    meta = next(ops.lq.glob("*refill-p1.meta")).read_text()
    assert "QUEUE-MARKER-LINE" not in brief and "engine=claude" in meta and "pin_claude=1" in meta and "adddir=" in meta


def test_a_finished_planner_lane_moves_planner_out_into_the_lane_queue(ops):
    (ops.lq / "AGY_ONLY").touch()
    wt = ops.codo / f"nexus-q-{LANE}"
    (wt / "planner-out").mkdir(parents=True)
    for i in (1, 2):
        (wt / "planner-out" / f"job{i}.brief").write_text(f"brief {i}\n")
        (wt / "planner-out" / f"job{i}.meta").write_text("base=origin/main\nallow_paths=1\npin_claude=1\nadddir=/x\nship=no\n")
    ops.write_lane(LANE, "[argon] exit=0 head=abc x", meta="base=origin/main\nship=no\nplanner_out=1\n")
    finish(ops)
    briefs = sorted(ops.lq.glob("2*-job*.brief"))
    assert len(briefs) == 2 and briefs[0].read_text().startswith("brief")
    meta = briefs[0].with_suffix(".meta").read_text()
    assert "base=origin/main" in meta and "engine=agy" in meta and "ship=no" in meta
    assert "allow_paths" not in meta and "pin_claude" not in meta and "adddir" not in meta


def test_the_lane_runner_re_executes_itself_only_when_its_script_changed(ops, tmp_path):
    script = tmp_path / "lane-runner.sh"
    script.write_text("echo one\n")
    body = (f'LANE_SELF="{script}"; LANE_SELF_SUM=$(script_sum "$LANE_SELF"); lane_reexec() {{ echo REEXEC; }}\n'
            'lane_self_check; echo "after-unchanged"\n'
            f'echo two >> "{script}"; lane_self_check; echo "after-changed"')
    r = ops.source("lane-runner.sh", body)
    assert r.stdout.split() .count("REEXEC") == 1 and "after-unchanged" in r.stdout
    assert r.stdout.index("after-unchanged") < r.stdout.index("REEXEC")


@pytest.mark.parametrize("lane_name,meta,expected", [
    ("20261126-1300-G5-D5-docs-update", "base=main\n", "gemini-4-hydrogen-high"),
    ("20261126-1300-G5-D5-checklist-audit", "base=main\n", "gemini-4-hydrogen-high"),
    ("20261126-1300-G5-D5-p1-feature", "base=main\n", "gemini-4-neon-medium"),
    ("20261126-1300-G5-D5-refill-p1", "base=main\n", "gemini-4-neon-medium"),
    ("20261126-1300-G5-D5-misc-job", "base=main\n", "gemini-4-neon-medium"),
    ("20261126-1300-G5-D5-p1-deep", "base=main\nmodel=gemini-4-carbon-high\n", "gemini-4-carbon-high"),
])
def test_minimum_required_model_routing_by_lane_name_and_explicit_meta(ops, lane_name, meta, expected):
    out = start(ops, lane_name, meta)
    assert f"({expected}," in out
    args = next(ops.logs.glob("q-*.log.args")).read_text()
    assert f"--model {expected}" in args


@pytest.mark.parametrize("lane_name", [
    "20261126-1300-G5-D5-p1-fallback",
    "20261126-1300-G5-D5-docs-fallback",
])
def test_rate_limit_fallback_from_neon_or_hydrogen_never_escalates_to_carbon(ops, lane_name):
    for m in ("gemini-4-hydrogen-high", "gemini-4-neon-medium", "gemini-4-neon-high"):
        (ops.lq / f".agy-limited-{m}").touch()
    out = start(ops, lane_name, "base=main\n")
    assert "stays queued" in out and "gemini-4-carbon-high" not in out
    assert (ops.lq / f"{lane_name}.brief").exists()


def test_rate_limit_fallback_order_moves_down_and_sideways_never_up_to_carbon(ops):
    (ops.lq / ".agy-limited-gemini-4-neon-medium").touch()
    out = start(ops, "20261126-1300-G5-D5-p1-down", "base=main\n")
    assert "-> 20261126-1300-G5-D5-p1-down on gemini-4-hydrogen-high" in out
    assert "(gemini-4-hydrogen-high," in out


def test_agy_only_planner_brief_emits_explicit_minimum_model_tier_table(ops):
    planner_setup(ops)
    r = ops.source("lane-runner.sh", "maybe_refill")
    assert r.returncode == 0, r.stderr
    brief = next(ops.lq.glob("*refill-p1.brief")).read_text()
    for tier in ("gemini-4-hydrogen-high", "gemini-4-neon-medium", "gemini-4-neon-high", "gemini-4-carbon-high"):
        assert tier in brief
    assert "Always write an explicit model=" in brief


def test_start_next_lane_removes_zero_commit_leftover_branch_and_creates_worktree(ops):
    name = "20261126-1300-G5-D5-p1-stale"
    wt = ops.codo / f"nexus-q-{name}"
    ops.git(ops.repo, "worktree", "add", "-q", "-b", f"lane/q-{name}", str(wt), "main")
    out = start(ops, name, "base=main\n")
    assert "removed zero-commit leftover branch" in out
    assert f"started L20261126-5 #5 {name}" in out
    assert (ops.lq / "started" / f"{name}.brief").exists()
    assert wt.exists()


def test_start_next_lane_refuses_to_delete_leftover_branch_with_commits(ops):
    name = "20261126-1300-G5-D5-p1-precious"
    wt = ops.codo / f"nexus-q-{name}"
    ops.git(ops.repo, "worktree", "add", "-q", "-b", f"lane/q-{name}", str(wt), "main")
    (wt / "work.txt").write_text("keep me\n")
    ops.git(wt, "add", "work.txt")
    ops.git(wt, "commit", "-q", "-m", "valuable work")
    out = start(ops, name, "base=main\n")
    assert "removed zero-commit leftover branch" not in out
    assert "brief stays queued for one retry" in out
    assert "valuable work" in ops.git(ops.repo, "log", "-1", "--pretty=%s", f"lane/q-{name}")
