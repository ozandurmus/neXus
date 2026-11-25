"""PO decision record 2026-11-24 D2/D3 in the versioned runners (tools/delivery/ops): the P1 planner condition, the
harness gate's exit-code mapping (stub lane_harness.py), the repair turn and its second chance, the event waiter's IDLE
rule, and the ship pretest reusing a harness PASS for the exact shipped tree."""
import json
import os
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from delivery_ops_support import OPS, OpsEnv  # noqa: E402

LANE = "20261125-0900-G600-D20-p1-sample"

STUB_HARNESS = r'''#!/usr/bin/env python3
"""Stub lane_harness.py: records its argv, pops its exit code from rc_seq, writes a report naming failed checks."""
import json, os, sys
state = os.environ["STUB_DIR"]
args = sys.argv[1:]
with open(os.path.join(state, "calls"), "a") as f:
    f.write(" ".join(args) + "\n")
seq_file = os.path.join(state, "rc_seq")
codes = open(seq_file).read().split()
rc = int(codes.pop(0)) if codes else 0
open(seq_file, "w").write(" ".join(codes))
report = args[args.index("--report") + 1]
checks = [{"id": "java_scoped", "status": "fail", "detail": "FooTest.bar failed"}] if rc == 10 else []
json.dump({"exit_code": rc, "checks": checks}, open(report, "w"))
print("stub exit", rc)
sys.exit(rc)
'''


@pytest.fixture
def ops(tmp_path):
    e = OpsEnv(tmp_path)
    e.git_repo(e.repo)
    (e.state / "lane-common-rules.md").write_text("## Common rules\n")
    for sub in ("hold",):
        (e.lq / sub).mkdir(exist_ok=True)
    stub_dir = tmp_path / "stub"
    stub_dir.mkdir()
    (stub_dir / "rc_seq").write_text("0")
    stub = stub_dir / "lane_harness.py"
    stub.write_text(STUB_HARNESS)
    e.env.update({"NEXUS_LANE_HARNESS": str(stub), "STUB_DIR": str(stub_dir), "NEXUS_HARNESS_LOCK_WAIT_S": "0.2"})
    e.stub_dir = stub_dir
    return e


def set_codes(ops, *codes):
    (ops.stub_dir / "rc_seq").write_text(" ".join(str(c) for c in codes))


def harness_calls(ops):
    f = ops.stub_dir / "calls"
    return f.read_text().splitlines() if f.exists() else []


def done_lane(ops, name=LANE, meta="base=origin/main\n", checks_yaml=None):
    """A lane that finished exit 0: brief and meta in done/, its worktree under the Codo dir."""
    (ops.lq / "done" / f"{name}.brief").write_text("# Task: sample\n")
    (ops.lq / "done" / f"{name}.meta").write_text(meta)
    wt = ops.codo / f"nexus-q-{name}" / "tools" / "delivery"
    wt.mkdir(parents=True, exist_ok=True)
    if checks_yaml is not None:
        (wt / "checks.yaml").write_text(checks_yaml)


def gate(ops, name=LANE):
    r = ops.bash(f'bash "{OPS / "harness-gate.sh"}" "{name}"')
    assert r.returncode == 0, r.stderr
    return (ops.logs / "lane-runner.log").read_text() if (ops.logs / "lane-runner.log").exists() else ""


def fields(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line)


# ---- harness gate: typed exit code -> ship entry / repair / held ---------------------------------------------------

def test_pass_writes_a_ship_entry_bound_to_the_report(ops):
    done_lane(ops, meta="base=origin/main\nallow_paths=1\ntitle=T\nreview_returned=1\n")
    log = gate(ops)
    entry = fields(ops.sq / f"{LANE}.ship")
    assert entry["branch"] == f"lane/q-{LANE}" and entry["name"] == LANE and entry["harness"] == "PASS"
    assert entry["harness_report"] == str(ops.logs / f"harness-{LANE}.json")
    # The repo's own fixes stay: the person's path approval and a returned review travel with the entry.
    assert entry["allow_paths"] == "1" and entry["title"] == "T" and entry["review_returned"] == "1"
    assert f"HARNESS PASS {LANE}" in log and "ship-queued" in log
    call = harness_calls(ops)[0]
    assert "--keep-going" in call and "--allow-paths" in call and "--fetch" in call and f"--lane-name {LANE}" in call
    assert "--integration auto" in call   # no checks.yaml naming hosta_test_run.py
    assert not (ops.state / ".harness-lock").exists()


def test_integration_goes_to_hosta_when_the_lanes_manifest_uses_the_hosta_runner(ops):
    done_lane(ops, checks_yaml="steps:\n  - id: integration\n    cmd: python3 tools/delivery/hosta_test_run.py\n")
    gate(ops)
    assert "--integration hosta" in harness_calls(ops)[0] and "--allow-paths" not in harness_calls(ops)[0]


def test_the_whole_test_load_goes_to_hosta_when_the_lanes_manifest_knows_the_carrier(ops):
    done_lane(ops, checks_yaml='hosta_carrier_cmd: ["{python}", "tools/delivery/hosta_test_run.py"]\n'
                               "steps:\n  - id: integration\n    cmd: python3 tools/delivery/hosta_test_run.py\n")
    gate(ops)
    assert "--carrier hosta" in harness_calls(ops)[0] and "--integration hosta" in harness_calls(ops)[0]


def test_an_older_base_without_the_carrier_keeps_the_mac_run(ops):
    done_lane(ops)
    gate(ops)
    assert "--carrier" not in harness_calls(ops)[0]


@pytest.mark.parametrize("code,tag", [(12, "CONFLICT"), (7, "ERROR")])
def test_conflict_and_unexpected_exits_are_queued_so_the_ship_path_still_gates_them(ops, code, tag):
    done_lane(ops)
    set_codes(ops, code)
    gate(ops)
    assert fields(ops.sq / f"{LANE}.ship")["harness"] == tag


def test_harness_error_50_is_retried_once_then_queued_as_error(ops):
    done_lane(ops)
    set_codes(ops, 50, 50)
    log = gate(ops)
    assert len(harness_calls(ops)) == 2 and "retrying once" in log
    assert fields(ops.sq / f"{LANE}.ship")["harness"] == "ERROR"


def test_harness_error_50_then_pass_ships_as_pass(ops):
    done_lane(ops)
    set_codes(ops, 50, 0)
    gate(ops)
    assert fields(ops.sq / f"{LANE}.ship")["harness"] == "PASS"


def test_held_for_po_goes_to_held(ops):
    done_lane(ops)
    set_codes(ops, 20)
    log = gate(ops)
    assert fields(ops.sq / "held" / f"{LANE}.ship")["harness"] == "HELD" and not (ops.sq / f"{LANE}.ship").exists()
    assert f"HARNESS HELD {LANE}" in log


def test_no_change_ships_nothing(ops):
    done_lane(ops)
    set_codes(ops, 30)
    log = gate(ops)
    assert not list(ops.sq.rglob("*.ship")) and "HARNESS NO_CHANGE" in log


def repair_briefs(ops):
    return sorted(ops.lq.glob("*-fix-harness-repair-*.brief"))


def test_first_check_failure_queues_one_repair_turn_under_a_standard_name(ops):
    done_lane(ops, meta="base=origin/main\nallow_paths=1\nengine=claude\n")
    set_codes(ops, 10)
    log = gate(ops)
    briefs = repair_briefs(ops)
    assert len(briefs) == 1 and not list(ops.sq.rglob("*.ship"))
    name = briefs[0].stem
    assert name.endswith("-fix-harness-repair-G600")
    assert name.split("-")[0].isdigit() and "-G" in name and "-D" in name   # standard lane name (lanename.sh)
    text = briefs[0].read_text()
    assert text.startswith("# Task: sample") and "## Repair turn (lane harness" in text and "java_scoped: FooTest.bar failed" in text
    meta = fields(ops.lq / f"{name}.meta")
    assert meta["base"] == f"lane/q-{LANE}" and meta["repair_of"] == LANE and meta["allow_paths"] == "1"
    assert f"repair turn queued ({name})" in log


def test_a_failure_after_a_repair_turn_is_held_for_the_po_session(ops):
    done_lane(ops, meta=f"base=lane/q-x\nrepair_of=x\n")
    set_codes(ops, 10)
    log = gate(ops)
    assert fields(ops.sq / "held" / f"{LANE}.ship")["harness"] == "FAIL" and repair_briefs(ops) == []
    assert "after repair turn" in log and "held for PO session" in log


def test_gate_runs_are_serialized_by_a_lock(ops):
    done_lane(ops)
    lock = ops.state / ".harness-lock"
    lock.mkdir()
    import subprocess
    env = dict(ops.env)
    proc = subprocess.Popen(["bash", str(OPS / "harness-gate.sh"), LANE], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1.0)
    assert harness_calls(ops) == [] and proc.poll() is None   # waits behind the running gate
    lock.rmdir()
    proc.wait(timeout=30)
    assert len(harness_calls(ops)) == 1 and not lock.exists()


def test_a_lock_older_than_40_minutes_is_taken_over(ops):
    done_lane(ops)
    lock = ops.state / ".harness-lock"
    lock.mkdir()
    old = time.time() - 2500
    os.utime(lock, (old, old))
    gate(ops)
    assert len(harness_calls(ops)) == 1


# ---- lane-runner: exit 0 -> gate; a repair turn that changed nothing -> second chance on the original ---------------

def run_finish(ops, name):
    # The gate runs synchronously here so the whole flow can be checked in one go.
    r = ops.source("lane-runner.sh", f'start_harness_gate() {{ bash "$OPS_DIR/harness-gate.sh" "$1"; }}\n'
                                     f'finish_lane "$Q/started/{name}.brief"')
    assert r.returncode == 0, r.stderr
    return r.stdout


def start_repair_lane(ops, exit_line):
    """Move the queued repair brief to started/ (as start_next_lane does) with an engine log ending in `exit_line`."""
    brief = repair_briefs(ops)[0]
    name = brief.stem
    meta = ops.lq / f"{name}.meta"
    brief.rename(ops.lq / "started" / brief.name)
    meta.rename(ops.lq / "started" / meta.name)
    (ops.logs / f"q-{name}.nohup").write_text(f"work\n{exit_line}\n")
    past = time.time() - 600
    os.utime(ops.lq / "started" / brief.name, (past, past))
    (ops.codo / f"nexus-q-{name}" / "tools" / "delivery").mkdir(parents=True, exist_ok=True)
    return name


def test_repair_turn_that_changes_nothing_reruns_the_gate_once_on_the_original_then_holds(ops):
    ops.write_lane(LANE, "[argon] exit=0 head=abc x")
    (ops.codo / f"nexus-q-{LANE}" / "tools" / "delivery").mkdir(parents=True)
    set_codes(ops, 10, 10)
    run_finish(ops, LANE)
    assert len(repair_briefs(ops)) == 1                 # first failure: one repair turn
    rname = start_repair_lane(ops, "[lane] no commit made\n[argon] exit=3 head=abc x")
    out = run_finish(ops, rname)
    assert "second chance" in out
    assert fields(ops.lq / "done" / f"{LANE}.meta")["repair_of"] == LANE
    assert len(harness_calls(ops)) == 2 and all(f"--lane-name {LANE}" in c for c in harness_calls(ops))
    # Second failure on the original: held for the PO session, no further repair turn.
    assert fields(ops.sq / "held" / f"{LANE}.ship")["harness"] == "FAIL"
    assert repair_briefs(ops) == []


def test_second_chance_pass_ships_the_original(ops):
    ops.write_lane(LANE, "[argon] exit=0 head=abc x")
    (ops.codo / f"nexus-q-{LANE}" / "tools" / "delivery").mkdir(parents=True)
    set_codes(ops, 10, 0)
    run_finish(ops, LANE)
    rname = start_repair_lane(ops, "[argon] exit=3 head=abc x")
    run_finish(ops, rname)
    assert fields(ops.sq / f"{LANE}.ship")["harness"] == "PASS"


def test_repair_turn_that_commits_is_gated_itself_and_held_on_a_second_failure(ops):
    ops.write_lane(LANE, "[argon] exit=0 head=abc x")
    (ops.codo / f"nexus-q-{LANE}" / "tools" / "delivery").mkdir(parents=True)
    set_codes(ops, 10, 10)
    run_finish(ops, LANE)
    rname = start_repair_lane(ops, "[argon] exit=0 head=def x")
    run_finish(ops, rname)
    assert fields(ops.sq / "held" / f"{rname}.ship")["branch"] == f"lane/q-{rname}"
    assert repair_briefs(ops) == [] and not (ops.sq / "held" / f"{LANE}.ship").exists()


def test_a_plain_no_change_lane_triggers_no_gate(ops):
    ops.write_lane(LANE, "[argon] exit=3 head=abc x")
    out = run_finish(ops, LANE)
    assert harness_calls(ops) == [] and "second chance" not in out


# ---- planner (D2) ---------------------------------------------------------------------------------------------------

def planner_env(ops, template="Group A.\nWrite 3 to 6 lane briefs for group A into the queue.\n"):
    for kind in ("p1", "debt", "backlog", "review"):
        (ops.state / f"refill-{kind}.template").write_text(template if kind == "p1" else f"{kind} template\n")


def refill(ops, hour="10", no_refill=True, queued=0, running=0, last_age=None, prev_kind=None):
    if no_refill:
        (ops.lq / "NO_REFILL").touch()
    for i in range(queued):
        (ops.lq / f"20261125-0800-G{i}-D{i}-x{i}.brief").write_text("b\n")
    for i in range(running):
        (ops.lq / "started" / f"20261125-0700-G9{i}-D9{i}-r{i}.brief").write_text("b\n")
    if last_age is not None:
        f = ops.lq / ".last-refill"
        f.touch()
        os.utime(f, (time.time() - last_age, time.time() - last_age))
    if prev_kind:
        (ops.lq / ".last-refill-kind").write_text(prev_kind + "\n")
    r = ops.source("lane-runner.sh", f'lane_hour() {{ echo {hour}; }}\nmaybe_refill')
    assert r.returncode == 0, r.stderr
    return sorted(ops.lq.glob("*-refill-*.brief"))


def test_planner_fires_when_no_brief_waits_and_a_slot_is_free(ops):
    planner_env(ops)
    briefs = refill(ops, running=2)
    assert len(briefs) == 1 and briefs[0].stem.endswith("-refill-p1")
    text = briefs[0].read_text()
    assert "Write exactly 3 lane briefs (lane cap 3, PO 2026-11-24)" in text and "3 to 6" not in text
    meta = fields(briefs[0].with_suffix(".meta"))
    assert meta["ship"] == "no" and meta["claude_model"] == "sonnet" and meta["effort"] == "high"
    assert meta["adddir"] == str(ops.state)


def test_planner_does_not_fire_when_every_slot_is_busy(ops):
    planner_env(ops)
    assert refill(ops, running=3) == []


def test_planner_does_not_fire_off_hours_while_a_brief_waits(ops):
    planner_env(ops)
    assert refill(ops, hour="10", queued=1) == []


@pytest.mark.parametrize("hour", ["08", "16"])
def test_planner_fires_at_08_and_16_when_fewer_than_2_wait(ops, hour):
    planner_env(ops)
    briefs = refill(ops, hour=hour, queued=1, running=3, last_age=3 * 3600, prev_kind="p1")
    assert len(briefs) == 1 and briefs[0].stem.endswith("-refill-p1")   # planner forces p1 over the rotation


def test_planner_at_08_waits_2_hours_after_the_last_refill(ops):
    planner_env(ops)
    assert refill(ops, hour="08", queued=1, last_age=3600) == []


def test_planner_at_08_does_not_fire_with_2_briefs_waiting(ops):
    planner_env(ops)
    assert refill(ops, hour="08", queued=2, last_age=3 * 3600) == []


def test_planner_never_stacks_on_a_queued_or_running_refill(ops):
    planner_env(ops)
    (ops.lq / "started" / "20261125-0700-G1-D1-refill-p1.brief").write_text("b\n")
    assert refill(ops, running=0) == []


def test_planner_respects_the_5_minute_spacing_and_the_ship_backlog(ops):
    planner_env(ops)
    assert refill(ops, last_age=60) == []
    for i in range(10):
        (ops.sq / f"x{i}.ship").write_text("b\n")
    assert refill(ops, last_age=3600) == []


def test_planner_appends_the_cap_when_the_template_wording_changed(ops):
    planner_env(ops, template="Write some lane briefs.\n")
    text = refill(ops)[0].read_text()
    assert "Write exactly 3 lane briefs (lane cap 3, PO 2026-11-24)" in text


def test_without_no_refill_the_rotation_runs_and_the_brief_is_unchanged(ops):
    planner_env(ops)
    briefs = refill(ops, no_refill=False, queued=2, prev_kind="p1")
    assert len(briefs) == 1 and briefs[0].stem.endswith("-refill-debt")
    assert briefs[0].read_text() == "debt template\n"


def test_planner_due_is_false_without_no_refill(ops):
    r = ops.source("lane-runner.sh", 'planner_due 0 0 && echo DUE || echo NOT')
    assert r.stdout.strip() == "NOT"


# ---- event waiter IDLE rule ------------------------------------------------------------------------------------------

def waiter(ops, body, **env):
    return ops.source("ayyas-waiter.sh", body, extra_env={k: str(v) for k, v in env.items()})


def test_idle_counter_grows_only_with_no_lane_and_no_brief(ops):
    r = waiter(ops, 'lanes_running() { echo 0; }\nidle_next 570; idle_next 0', NEXUS_WAITER_TICK=30)
    assert r.stdout.split() == ["600", "30"]
    (ops.lq / "x.brief").write_text("b\n")
    assert waiter(ops, 'lanes_running() { echo 0; }\nidle_next 570').stdout.strip() == "0"
    (ops.lq / "x.brief").unlink()
    assert waiter(ops, 'lanes_running() { echo 1; }\nidle_next 570').stdout.strip() == "0"


def test_idle_event_after_10_minutes(ops):
    assert waiter(ops, 'idle_event 570').stdout.strip() == ""
    assert waiter(ops, 'idle_event 600').stdout.startswith("IDLE: no lane running and no brief waiting for 10 min")


def test_waiter_returns_an_idle_event(ops):
    wc = ops.fake("watch-check-fake", "echo WATCH-OK")
    r = waiter(ops, 'lanes_running() { echo 0; }\nwaiter_main 30', NEXUS_WAITER_TICK=1, NEXUS_WAITER_IDLE_S=2,
               NEXUS_WATCH_CHECK=wc)
    assert "EVENT:" in r.stdout and "IDLE:" in r.stdout and "WATCH-OK" in r.stdout


def test_waiter_without_idle_capacity_times_out_quietly(ops):
    (ops.lq / "x.brief").write_text("b\n")
    wc = ops.fake("watch-check-fake", "echo WATCH-OK")
    r = waiter(ops, 'lanes_running() { echo 0; }\nwaiter_main 2', NEXUS_WAITER_TICK=1, NEXUS_WAITER_IDLE_S=1,
               NEXUS_WATCH_CHECK=wc)
    assert "EVENT:" not in r.stdout and "WATCH-OK" in r.stdout


def test_waiter_wakes_on_a_new_ship_failure_not_an_old_one(ops):
    (ops.logs / "ship-runner.log").write_text("2026-11-25 old FAILED batch/X\n")
    wc = ops.fake("watch-check-fake", "echo WATCH-OK")
    script = ('lanes_running() { echo 1; }\n( sleep 1; echo "2026-11-25 12:00 HELD lane-y" >> "$L/ship-runner.log" ) &\n'
              'waiter_main 10')
    r = waiter(ops, script, NEXUS_WAITER_TICK=1, NEXUS_WATCH_CHECK=wc)
    assert "HELD lane-y" in r.stdout and "old FAILED" not in r.stdout


def test_lanes_running_counts_the_engine_wrappers_the_runner_starts(ops):
    ops.fake("ps", "\n".join([
        'echo "COMMAND"',
        'echo "/Library/Frameworks/Python.framework/Versions/3.12/Resources/Python.app/Contents/MacOS/Python /x/argon_task_neon.py --worktree a"',
        'echo "/bin/bash /x/claude_task.sh /wt brief sonnet"',
        'echo "/bin/bash /x/qwen_task.sh /wt brief"',
        'echo "grep -E argon_task"',
        'echo "/usr/bin/vim /x/claude_task.sh"',
    ]))
    assert waiter(ops, "lanes_running").stdout.strip() == "3"


# ---- ship pretest reuse (PO 2026-11-25 option A) --------------------------------------------------------------------

REPORT_SCHEMA = "lane-harness-report/1"


@pytest.fixture
def shipped(ops, tmp_path):
    """A pretest worktree (the tree being shipped) with a check manifest, and a batch items dir."""
    wt = ops.git_repo(tmp_path / "pretest-wt", {"tools/delivery/checks.yaml": "version: 1\nsteps: []\n", "a.txt": "a\n"})
    items = tmp_path / "inflight" / "B1"
    items.mkdir(parents=True)
    return wt, items


def tree_of(ops, wt):
    return ops.git(wt, "rev-parse", "HEAD^{tree}")


def manifest_sha(wt):
    import hashlib
    return hashlib.sha256((wt / "tools/delivery/checks.yaml").read_bytes()).hexdigest()


def add_item(ops, items, name, wt, harness="PASS", tree=None, **report_overrides):
    report = items.parent / f"harness-{name}.json"
    data = {"schema": REPORT_SCHEMA, "exit_code": 0, "result": "PASS", "tree_id": tree or tree_of(ops, wt),
            "manifest_sha256": manifest_sha(wt), "complete": True, "profile": "lane", "checks": []}
    data.update(report_overrides)
    report.write_text(json.dumps(data))
    (items / f"{name}.ship").write_text(f"branch=lane/q-{name}\nname={name}\nharness={harness}\nharness_report={report}\n")
    return report


def reuse(ops, wt, items):
    r = ops.bash(f'python3 "{OPS / "pretest_reuse.py"}" --worktree "{wt}" --items "{items}"')
    return r.returncode, r.stdout.strip()


def test_a_single_item_pass_for_the_exact_tree_is_reused(ops, shipped):
    wt, items = shipped
    add_item(ops, items, "lane-a", wt)
    rc, why = reuse(ops, wt, items)
    assert rc == 0 and "lane-a" in why and tree_of(ops, wt)[:12] in why


@pytest.mark.parametrize("change", [
    {"harness": "ERROR"}, {"harness": "CONFLICT"}, {"tree": "0" * 40}, {"exit_code": 10}, {"complete": False},
    {"profile": "smoke"}, {"schema": "other/1"}, {"manifest_sha256": "f" * 64},
])
def test_anything_short_of_a_complete_pass_for_this_tree_runs_the_pretest(ops, shipped, change):
    wt, items = shipped
    add_item(ops, items, "lane-a", wt, **change)
    rc, why = reuse(ops, wt, items)
    assert rc == 1 and why


def test_a_multi_item_batch_needs_every_report_to_cover_the_shipped_tree(ops, shipped):
    wt, items = shipped
    add_item(ops, items, "lane-a", wt)
    add_item(ops, items, "lane-b", wt, tree="1" * 40)   # lane-b's report is for its own lane tree, not the batch tree
    rc, why = reuse(ops, wt, items)
    assert rc == 1 and "lane-b" in why


def test_missing_report_entry_or_items_runs_the_pretest(ops, shipped, tmp_path):
    wt, items = shipped
    assert reuse(ops, wt, items)[0] == 1                       # no items
    assert reuse(ops, wt, tmp_path / "nope")[0] == 1           # no items dir
    (items / "lane-a.ship").write_text("branch=lane/q-a\nname=lane-a\n")   # no harness fields (revert, older lane)
    assert reuse(ops, wt, items)[0] == 1
    (items / "lane-a.ship").write_text(f"name=lane-a\nharness=PASS\nharness_report={tmp_path / 'missing.json'}\n")
    assert reuse(ops, wt, items)[0] == 1


def test_shipq3_skips_jt_when_the_harness_pass_covers_the_tree(ops, shipped):
    wt, items = shipped
    add_item(ops, items, "lane-a", wt)
    jt = ops.fake("jt-fake", 'echo called >> "$NEXUS_JT_CALLED"; echo "java tests 1 failures 0"')
    called = ops.tmp / "jt-called"
    out = ops.tmp / "pretest.out"
    r = ops.source("shipq3.sh", f'pretest_decide "{wt}" "{items}"\npretest_execute "{wt}" "" "" "{out}" && echo GREEN',
                   extra_env={"NEXUS_JT": str(jt), "NEXUS_JT_CALLED": str(called)})
    assert r.returncode == 0, r.stderr
    assert r.stdout.startswith("PRETEST reused harness report:") and "GREEN" in r.stdout
    assert not called.exists() and out.read_text().startswith("PRETEST reused harness report:")


def test_shipq3_runs_jt_when_the_reports_do_not_cover_the_tree(ops, shipped):
    wt, items = shipped
    add_item(ops, items, "lane-a", wt, tree="0" * 40)
    called = ops.tmp / "jt-called"
    out = ops.tmp / "pretest.out"
    for body, green in (('echo "java tests 3 failures 0"; echo tools-ok', True), ('echo "java tests 3 failures 1"', False),
                        ('echo "java tests 3 failures 0"; echo vitest-FAIL', False), ('echo crashed', False)):
        jt = ops.fake("jt-fake", 'echo called >> "$NEXUS_JT_CALLED"\n' + body)
        r = ops.source("shipq3.sh", f'pretest_decide "{wt}" "{items}"\npretest_execute "{wt}" "" "" "{out}" && echo GREEN',
                       extra_env={"NEXUS_JT": str(jt), "NEXUS_JT_CALLED": str(called)})
        assert r.stdout.startswith("PRETEST ran:") and ("GREEN" in r.stdout) is green
    assert called.read_text().count("called") == 4


def test_shipq3_without_batch_items_runs_the_pretest(ops, shipped):
    wt, _ = shipped
    r = ops.source("shipq3.sh", f'pretest_decide "{wt}" ""; echo "$PRETEST_PATH"')
    assert r.stdout.splitlines() == ["PRETEST ran: no batch items given", "ran"]


def test_ship_runner_hands_the_batch_items_to_shipq3():
    text = (OPS / "ship-runner.sh").read_text()
    assert 'NEXUS_SHIP_ITEMS="$SQ/inflight/$ID"' in text
    shipq3 = (OPS / "shipq3.sh").read_text()
    assert "pretest_decide $W \"$ITEMS\" >> $OUT" in shipq3 and "NEXUS_PRETEST_FULL" in shipq3
