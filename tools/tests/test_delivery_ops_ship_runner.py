"""ship-runner.sh, b-review.sh, status.sh, watch-check.sh, counters (tools/delivery/ops)."""
import os
import re
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from delivery_ops_support import OPS, OpsEnv  # noqa: E402

LANE = "20261124-1200-G7-D3-p1-sample"


@pytest.fixture
def ops(tmp_path):
    e = OpsEnv(tmp_path)
    e.git_repo(e.repo)
    (e.state / "lane-common-rules.md").write_text("## Common rules\n")
    for sub in ("done", "failed", "held", "inflight"):   # ship_main creates these when the loop starts
        (e.sq / sub).mkdir(exist_ok=True)
    return e


def inflight(ops, batch, names, out_lines, extra=""):
    d = ops.sq / "inflight" / batch
    d.mkdir(parents=True)
    for n in names:
        (d / f"{n}.ship").write_text(f"branch=lane/q-{n}\nname={n}\n{extra}")
    (ops.logs / f"ship-{batch}.out").write_text("\n".join(out_lines) + "\nDONE\n")


def run_finish(ops, batch, pre=""):
    r = ops.source("ship-runner.sh", f'{pre}\nfinish_batch {batch}')
    assert r.returncode == 0, r.stderr
    return r.stdout


# ---- state-sync trigger replaced by a no-op hook -------------------------------------------------------------------

def test_shipped_batches_never_queue_a_state_sync_lane(ops):
    for i in range(5):
        inflight(ops, f"B{i}", [f"{LANE}-{i}"], [f"SHIPPED batch/B{i} 12:00"])
        out = run_finish(ops, f"B{i}")
        assert f"SHIPPED batch/B{i}" in out
    assert list(ops.lq.glob("*state-sync*")) == [] and not (ops.sq / ".since-state-sync").exists()
    assert len(list((ops.sq / "done").glob("*.ship"))) == 5


def test_the_record_fold_hook_is_called_once_per_shipped_batch_and_is_a_no_op_by_default(ops):
    inflight(ops, "B1", [LANE], ["SHIPPED batch/B1 12:00"])
    out = run_finish(ops, "B1", pre='record_fold_hook() { echo "HOOK $1"; }')
    assert f"HOOK {LANE}" in out
    body = ops.source("ship-runner.sh", 'declare -f record_fold_hook').stdout
    assert re.fullmatch(r"record_fold_hook \(\) \{ :;? \}", re.sub(r"\s+", " ", body).strip())


# ---- failure handling stays as before, and gives up loudly ---------------------------------------------------------

def test_a_failed_single_lane_goes_to_failed_with_one_fix_lane(ops):
    inflight(ops, "B2", [LANE], ["FAILED batch/B2: unit tests did not pass (FAIL FooTest.bar)"], extra="allow_paths=1\n")
    run_finish(ops, "B2")
    assert (ops.sq / "failed" / f"{LANE}.ship").exists()
    briefs = list(ops.lq.glob("*-fix-*.brief"))
    assert len(briefs) == 1 and "make lane" in briefs[0].read_text()
    meta = briefs[0].with_suffix(".meta").read_text()
    assert f"base=lane/q-{LANE}" in meta and "allow_paths=1" in meta and "claude_model=sonnet" in meta


def test_a_failed_fix_lane_gets_no_second_fix_and_an_alert(ops):
    name = "20261124-1300-G8-D4-fix-20261124-1200-G7-D3-p1-sample"
    inflight(ops, "B3", [name], ["FAILED batch/B3: unit tests did not pass (FAIL X)"])
    out = run_finish(ops, "B3")
    assert "ALERT no second fix" in out and (ops.sq / "failed" / f"{name}.ship").exists()
    assert list(ops.lq.glob("*.brief")) == []


def test_two_infrastructure_failures_in_a_row_pause_the_runner(ops):
    inflight(ops, "B4", [LANE + "-a"], ["FAILED batch/B4: preview e2e failed"])
    inflight(ops, "B5", [LANE + "-b"], ["FAILED batch/B5: pull request not merged (x)"])
    out = ops.source("ship-runner.sh", 'finish_batch B4; finish_batch B5').stdout
    assert (ops.sq / "PAUSED").exists() and "PAUSED: two infrastructure failures" in out
    assert (ops.sq / f"{LANE}-a.ship").exists() and (ops.sq / f"{LANE}-b.ship").exists()   # stay queued


@pytest.mark.parametrize("line", [
    "could not open the pull request: pull request create failed: HTTP 502: 502 Bad Gateway (https://api.example/graphql)",
    "Could not GET 'https://repo.example/x.pom'. Received status code 429 from server: Too Many Requests",
])
def test_github_5xx_and_registry_429_are_infrastructure_not_the_lanes_fault(ops, line):
    # 2026-11-25: the PR-create 502 line was not in the failure reason, so the runner blamed the lane whose file path
    # the ship_path line named and queued a useless fix lane.
    inflight(ops, "B6", [LANE], ['{"ship_path": "full", "why": "backend change: tools/x.py"}', line])
    run_finish(ops, "B6")
    assert (ops.sq / f"{LANE}.ship").exists() and list(ops.lq.glob("*-fix-*.brief")) == []


# ---- b-review: a BLOCK goes back to the author once ----------------------------------------------------------------

def ship_entry(ops, extra=""):
    f = ops.sq / f"{LANE}.ship"
    f.write_text(f"branch=lane/q-{LANE}\nname={LANE}\nreview=pending\n{extra}")
    return f


def decide(ops, verdict_text):
    out = ops.logs / f"review-{LANE}.md"
    out.write_text(verdict_text)
    r = ops.source("b-review.sh", f'review_decide {LANE} "{ops.sq}/{LANE}.ship" lane/q-{LANE} "{out}"')
    assert r.returncode == 0, r.stderr
    return (ops.logs / "ship-runner.log").read_text() if (ops.logs / "ship-runner.log").exists() else ""


def test_review_pass_releases_the_entry(ops):
    f = ship_entry(ops)
    log = decide(ops, "looks fine\nVERDICT: PASS\n")
    text = f.read_text()
    assert "review=pending" not in text and "reviewed=sonnet" in text and "B-REVIEW PASS" in log


def test_first_block_returns_the_review_to_the_author_on_the_same_branch(ops):
    ship_entry(ops, "allow_paths=1\n")
    log = decide(ops, "FooService.java:12 swallows the failure\nVERDICT: BLOCK\n")
    assert not (ops.sq / f"{LANE}.ship").exists() and not list((ops.sq / "held").glob("*"))
    returned = (ops.sq / "returned" / f"{LANE}.ship").read_text()
    assert "review_returned=1" in returned and "review=pending" not in returned
    briefs = list(ops.lq.glob("*-fix-blocked-p1-sample.brief"))
    assert len(briefs) == 1
    text = briefs[0].read_text()
    assert "FooService.java:12 swallows the failure" in text and f"lane/q-{LANE}" in text
    meta = briefs[0].with_suffix(".meta").read_text()
    assert f"base=lane/q-{LANE}" in meta and "review_returned=1" in meta and "pin_claude=1" in meta and "allow_paths=1" in meta
    assert "B-REVIEW RETURNED" in log and "B-REVIEW BLOCK" not in log


def test_second_block_goes_to_held(ops):
    ship_entry(ops, "review_returned=1\n")
    log = decide(ops, "still wrong\nVERDICT: BLOCK\n")
    assert (ops.sq / "held" / f"{LANE}.ship").exists() and not list((ops.sq / "returned").glob("*"))
    assert list(ops.lq.glob("*.brief")) == [] and "B-REVIEW BLOCK" in log


def test_a_neon_block_after_a_sonnet_pass_counts_as_a_block(ops):
    ship_entry(ops)
    decide(ops, "ok\nVERDICT: PASS (Sonnet); BLOCK (Neon)\nmore\n")
    assert (ops.sq / "returned" / f"{LANE}.ship").exists()


def test_a_diff_too_large_to_review_is_held_not_returned(ops):
    ship_entry(ops)
    decide(ops, "VERDICT: BLOCK (diff of 9000 lines is too large for a pre-merge review)\n")
    assert (ops.sq / "held" / f"{LANE}.ship").exists() and list(ops.lq.glob("*.brief")) == []


# ---- status.sh and watch-check.sh show failed/held with ages ---------------------------------------------------------

def age(path, seconds):
    t = time.time() - seconds
    os.utime(path, (t, t))


def test_status_lists_failed_and_held_with_ages(ops):
    for sub, name, secs in (("failed", "f-one", 3 * 3600), ("held", "h-one", 45 * 60), ("held", "h-two", 20)):
        d = ops.sq / sub
        d.mkdir(exist_ok=True)
        (d / f"{name}.ship").write_text("x")
        age(d / f"{name}.ship", secs)
    (ops.lq / "hold").mkdir()
    (ops.lq / "hold" / "lane-h.brief").write_text("x"); age(ops.lq / "hold" / "lane-h.brief", 2 * 86400 + 60)
    (ops.lq / "done" / "w1.brief.FAILED_WORKTREE").write_text("x")
    out = ops.bash(f'bash "{OPS}/status.sh"').stdout
    assert re.search(r"^ship failed 1: f-one \(3h\)$", out, re.M)
    assert re.search(r"^ship held 2: h-one \(45m\), h-two \(\d+s\)$", out, re.M), out
    assert re.search(r"^lane hold 1: lane-h \(2d\)$", out, re.M)
    assert "lane worktree failed 1: w1.brief.FAILED_WORKTREE" in out
    assert "failed 1" in out and "held 2" in out


def test_status_is_quiet_on_an_empty_pipeline(ops):
    out = ops.bash(f'bash "{OPS}/status.sh"').stdout
    assert "ship failed 0" in out and "ship held 0" in out and "today: shipped 0 deploys, failed 0" in out


def test_watch_check_flags_work_that_left_the_pipeline(ops):
    ops.fake("launchctl", "exit 0")
    (ops.sq / "failed" / "f-one.ship").write_text("x")
    (ops.sq / "held" / "h-one.ship").write_text("x")
    (ops.lq / "hold").mkdir(); (ops.lq / "hold" / "l-one.brief").write_text("x")
    (ops.lq / "done" / "w1.brief.FAILED_WORKTREE").write_text("x")
    (ops.logs / "ship-runner.log").write_text("")
    (ops.logs / "lane-runner.log").write_text("")
    out = ops.bash(f'bash "{OPS}/watch-check.sh"', extra_env={"NEXUS_LAUNCHCTL": str(ops.bin / "launchctl")}).stdout
    for needle in ("ACTION failed/f-one.ship", "ACTION held/h-one.ship", "ACTION hold/l-one.brief", "ACTION done/w1.brief.FAILED_WORKTREE"):
        assert needle in out, out


# ---- counters / names / migration numbers --------------------------------------------------------------------------

def test_lane_names_and_counters(ops):
    a = ops.bash(f'bash "{OPS}/lanename.sh" 17-some-thing').stdout.strip()
    b = ops.bash(f'bash "{OPS}/lanename.sh" {a}').stdout.strip()
    pat = r"^\d{8}-\d{4}-G(\d+)-D(\d+)-some-thing$"
    ma, mb = re.match(pat, a), re.match(pat, b)
    assert ma and mb and int(mb.group(1)) == int(ma.group(1)) + 1 and int(mb.group(2)) == int(ma.group(2)) + 1
    assert len((ops.state / "counters" / "registry.tsv").read_text().splitlines()) == 2


def test_reserve_migration_numbers_are_above_main_and_stable(ops):
    mig = "ui2/service/src/main/resources/db/migration"
    origin = ops.tmp / "origin.git"
    ops.git(ops.tmp, "init", "-q", "--bare", "-b", "main", str(origin))
    work = ops.git_repo(ops.tmp / "work", {f"{mig}/V7__a.sql": "x\n", f"{mig}/V9__b.sql": "y\n"})
    ops.git(work, "remote", "add", "origin", str(origin)); ops.git(work, "push", "-q", "origin", "main")
    ops.git(ops.codo, "clone", "-q", str(origin), str(ops.codo / "clone"))
    env = {"NEXUS_OPS_REPO": str(ops.codo / "clone")}
    first = ops.bash(f'bash "{OPS}/reserve-migration.sh" lane-a', extra_env=env).stdout.strip()
    again = ops.bash(f'bash "{OPS}/reserve-migration.sh" lane-a', extra_env=env).stdout.strip()
    other = ops.bash(f'bash "{OPS}/reserve-migration.sh" lane-b', extra_env=env).stdout.strip()
    assert (first, again, other) == ("10", "10", "11")


def test_daily_metrics_counts_returned_reviews_and_lane_alerts(ops):
    day = time.strftime("%Y-%m-%d")
    (ops.logs / "ship-runner.log").write_text(f"{day} 10:00:00 B-REVIEW RETURNED x: first block\n{day} 10:01:00 B-REVIEW BLOCK y: held\n")
    (ops.logs / "lane-runner.log").write_text(f"{day} 10:00:00 ALERT HELD z: permission\n")
    out = ops.bash(f'python3 -I "{OPS}/daily-metrics.py" {day}').stdout
    assert "| review_returned | 1 |" in out and "| review_block | 1 |" in out and "| lane_alerts | 1 |" in out


def test_daily_metrics_survives_missing_logs(ops):
    r = ops.bash(f'python3 -I "{OPS}/daily-metrics.py" 2026-01-01')
    assert r.returncode == 0 and "| lane_starts | 0 |" in r.stdout


def test_a_reply_without_a_verdict_is_unavailable_not_a_block(ops):
    # 2026-11-26: "You've hit your 5-hour limit" was read as BLOCK and sent two good lanes back to their authors.
    ship_entry(ops)
    log = decide(ops, "You've hit your 5-hour limit \u00b7 resets 2:20am\n")
    assert (ops.sq / "held" / f"{LANE}.ship").exists() and "B-REVIEW UNAVAILABLE" in log
    assert list(ops.lq.glob("*fix-blocked*.brief")) == [] and not (ops.sq / "returned" / f"{LANE}.ship").exists()


def test_queue_md_is_a_rendered_file_re_rendered_after_a_conflict():
    # project/QUEUE.md is rendered from project/*.json (GOV.ORCH.5); a conflict on it alone must not need a port lane.
    src = (OPS / "ship-runner.sh").read_text()
    gen = re.search(r"^GEN='([^']+)'", src, re.M).group(1)
    assert re.search(gen, "project/QUEUE.md")
    assert not re.search(gen, "project/backlog.json")
    block = src[src.index('if [ "$G" = 1 ]; then'):]
    assert "project_queue.py render" in block[:block.index("\n    fi\n")]
