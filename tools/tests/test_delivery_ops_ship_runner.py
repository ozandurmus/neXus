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


def test_security_pregate_blocking_with_zero_scan_errors_is_not_infra_and_queues_fix_brief(ops):
    summary = 'SECURITY PREGATE SUMMARY: {"passed": false, "scan_errors": 0, "blocking": 2, "counts": {"gitleaks": {"CRITICAL": {"new": 2}}}}'
    for batch, suffix in (("B7", "a"), ("B8", "b")):
        (ops.logs / f"ship-{batch}.log").write_text(summary + "\n")
        inflight(ops, batch, [f"{LANE}-{suffix}"], [f"FAILED batch/{batch}: security gate FAIL"])
    out = ops.source("ship-runner.sh", 'infra=1; finish_batch B7; echo "INFRA_B7=$infra"; finish_batch B8; echo "INFRA_B8=$infra"').stdout
    assert "INFRA_B7=0" in out and "INFRA_B8=0" in out
    assert not (ops.sq / "PAUSED").exists() and "PAUSED:" not in out
    for suffix in ("a", "b"):
        assert (ops.sq / "failed" / f"{LANE}-{suffix}.ship").exists()
    briefs = sorted(ops.lq.glob("*-fix-*.brief"))
    assert len(briefs) == 2
    for brief in briefs:
        assert summary in brief.read_text()


@pytest.mark.parametrize("out_line,log_text", [
    ("FAILED batch/{batch}: security gate FAIL",
     'SECURITY PREGATE SUMMARY: {"passed": false, "scan_errors": 1, "blocking": 0}\n'),
    ("FAILED batch/{batch}: security gate FAIL",
     'SECURITY PREGATE SUMMARY: {"passed": false, "scan_errors": 1, "blocking": 2}\n'),
    ("FAILED batch/{batch}: FAIL (pre-merge security gate timed out)", ""),
])
def test_security_pregate_scan_errors_or_timeout_still_trigger_infra_pause(ops, out_line, log_text):
    for batch, suffix in (("B9", "a"), ("B10", "b")):
        if log_text:
            (ops.logs / f"ship-{batch}.log").write_text(log_text)
        inflight(ops, batch, [f"{LANE}-{suffix}"], [out_line.format(batch=batch)])
    out = ops.source("ship-runner.sh", 'finish_batch B9; finish_batch B10; echo "INFRA=$infra"').stdout
    assert (ops.sq / "PAUSED").exists() and "PAUSED: two infrastructure failures" in out and "INFRA=2" in out
    assert (ops.sq / f"{LANE}-a.ship").exists() and (ops.sq / f"{LANE}-b.ship").exists()
    assert list(ops.lq.glob("*-fix-*.brief")) == []


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


def test_a_named_preview_e2e_failure_is_the_lanes_fault_not_infrastructure():
    # G778 (2026-11-26): a preview e2e that names a failing test cancels the security pregate; that must not count as an
    # infrastructure failure, or two lane failures in a row pause the whole runner.
    src = (OPS / "ship-runner.sh").read_text()
    i = src.index("infra=$((infra + 1))")
    guard = src[src.rindex("if ", 0, i):i]
    assert "PREVIEW E2E FAILED TEST:" in guard and "! grep -q" in guard


# ---- AGY_ONLY mode (PO decision record 2026-11-26) ---------------------------------------------------------------------

def guarded_branch(ops, files):
    """A branch of ops.repo with `files` added on top of main; origin/main points at main."""
    ops.git(ops.repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    ops.git(ops.repo, "checkout", "-q", "-b", "lane/q-guard")
    for rel, text in files.items():
        (ops.repo / rel).parent.mkdir(parents=True, exist_ok=True)
        (ops.repo / rel).write_text(text)
    ops.git(ops.repo, "add", "-A"); ops.git(ops.repo, "commit", "-q", "-m", "change")
    ops.git(ops.repo, "checkout", "-q", "main")
    mb = ops.git(ops.repo, "merge-base", "lane/q-guard", "origin/main")
    return mb, ops.git(ops.repo, "diff", "--name-only", mb, "lane/q-guard")


def fake_consult(ops, verdict):
    f = ops.tmp / "consult.py"
    f.write_text("import argparse\np=argparse.ArgumentParser()\nfor a in ('--brief','--output','--model','--effort'): p.add_argument(a)\n"
                 "a=p.parse_args()\nopen('/dev/null').close()\nopen(a.output,'w').write('review text\\n'+%r+'\\n')\n"
                 "open(a.output+'.args','w').write(a.model+' '+a.effort+'\\n'+open(a.brief).read())\n" % verdict)
    return f


def guard_review(ops, verdict, files):
    (ops.lq).mkdir(exist_ok=True)
    (ops.lq / "AGY_ONLY").touch()
    mb, changed = guarded_branch(ops, files)
    entry = ops.sq / f"{LANE}.ship"
    entry.write_text(f"branch=lane/q-guard\nname={LANE}\n")
    consult = fake_consult(ops, verdict)
    (ops.repo / "docs").mkdir(exist_ok=True)
    r = ops.source("ship-runner.sh", f'agy_only_guard_review {LANE} "{entry}" lane/q-guard {mb} "{changed}"; echo "RC=$?"',
                   cwd=ops.repo, extra_env={"NEXUS_CONSULT_ARGON": str(consult)})
    assert r.returncode == 0, r.stderr
    return entry.read_text(), r.stdout


def test_agy_only_neon_pass_releases_a_guarded_path_item(ops):
    text, out = guard_review(ops, "VERDICT: PASS", {"scripts/tool.py": "x = 1\n"})
    assert "RC=0" in out and "allow_paths=1" in text and "approved=agy-neon-xhigh AGY_ONLY exception PO 2026-11-26" in text
    assert not (ops.repo / "docs" / ".agy-only-review").exists()


def test_agy_only_neon_block_or_no_verdict_keeps_the_item_held(ops):
    for verdict in ("VERDICT: BLOCK", "no verdict here"):
        text, out = guard_review(ops, verdict, {"scripts/tool.py": "x = 1\n"})
        assert "RC=1" in out and "allow_paths" not in text
        ops.git(ops.repo, "branch", "-D", "lane/q-guard")


@pytest.mark.parametrize("files", [
    {"ui2/service/src/main/resources/db/migration/V999__x.sql": "select 1;\n"},
    {"deploy/run.sh": "echo\n"},
    {".github/workflows/ci.yml": "a: b\n"},
    {"tools/delivery/ops/lane-runner.sh": "agy_only() { :; }  # AGY_ONLY\n"},
])
def test_agy_only_exceptions_stay_held_even_when_neon_would_pass(ops, files):
    text, out = guard_review(ops, "VERDICT: PASS", files)
    assert "RC=1" in out and "allow_paths" not in text and "stays held for a person" in out
    assert not (ops.repo / "docs" / ".agy-only-review").exists() and not list(ops.repo.glob("**/*.args"))


def test_a_ship_runner_change_without_agy_only_lines_goes_through_the_review(ops):
    text, out = guard_review(ops, "VERDICT: PASS", {"tools/delivery/ops/status.sh": "# comment\n"})
    assert "RC=0" in out and "allow_paths=1" in text


def test_the_ship_loop_asks_for_the_review_only_under_agy_only(ops):
    body = ops.source("ship-runner.sh", 'declare -f agy_only').stdout
    assert 'AGY_ONLY' in body
    src = (OPS / "ship-runner.sh").read_text()
    assert src.count("agy_only_guard_review \"$n\"") == 1 and "&& agy_only" in src


def shipped_batch(ops, batch, files):
    ops.git(ops.repo, "checkout", "-q", "-b", f"batch/{batch}")
    for rel, text in files.items():
        (ops.repo / rel).parent.mkdir(parents=True, exist_ok=True)
        (ops.repo / rel).write_text(text)
    ops.git(ops.repo, "add", "-A"); ops.git(ops.repo, "commit", "-q", "-m", "ship")
    ops.git(ops.repo, "checkout", "-q", "main")
    inflight(ops, batch, [LANE], [f"SHIPPED batch/{batch} 12:00"])


def test_a_shipped_batch_touching_the_ops_scripts_marks_a_self_update(ops):
    shipped_batch(ops, "B1", {"tools/delivery/ops/status.sh": "# new\n"})
    r = ops.source("ship-runner.sh", 'finish_batch B1; echo "SU=$SELF_UPDATE"')
    assert "SU=1" in r.stdout
    shipped_batch(ops, "B2", {"docs/x.md": "x\n"})
    r = ops.source("ship-runner.sh", 'finish_batch B2; echo "SU=$SELF_UPDATE"')
    assert "SU=0" in r.stdout


def self_update(ops, inflight_dirs):
    co = ops.tmp / "runner-checkout"
    ops.git_repo(co, {"tools/delivery/ops/x.sh": "a\n"})
    ops.git(co, "update-ref", "refs/remotes/origin/main", "HEAD")
    for d in inflight_dirs:
        (ops.sq / "inflight" / d).mkdir(parents=True)
    r = ops.source("ship-runner.sh", f'runner_checkout() {{ echo "{co}"; }}; runner_reexec() {{ echo REEXEC; }}\n'
                                     'SELF_UPDATE=1; self_update_if_idle; echo "SU=$SELF_UPDATE"')
    assert r.returncode == 0, r.stderr
    return co, r.stdout


def test_the_self_update_waits_while_a_batch_is_in_flight(ops):
    co, out = self_update(ops, ["B9"])
    assert "REEXEC" not in out and "SU=1" in out
    assert os.access(co / "tools/delivery/ops/x.sh", os.W_OK)


def test_the_self_update_checks_out_origin_main_read_only_and_re_executes_when_idle(ops):
    co, out = self_update(ops, [])
    assert "REEXEC" in out and "SU=0" in out
    assert not os.access(co / "tools/delivery/ops/x.sh", os.W_OK)
    os.chmod(co / "tools/delivery/ops", 0o755); os.chmod(co / "tools/delivery/ops/x.sh", 0o644)   # let pytest clean up


# ---- b-review.sh under AGY_ONLY ----------------------------------------------------------------------------------------

def review_run(ops, agy_only, agy_reply):
    ops.git(ops.repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    ops.git(ops.repo, "checkout", "-q", "-b", f"lane/q-{LANE}")
    (ops.repo / "a.txt").write_text("a\n")
    ops.git(ops.repo, "add", "-A"); ops.git(ops.repo, "commit", "-q", "-m", "change")
    ops.git(ops.repo, "checkout", "-q", "main")
    ship_entry(ops)
    if agy_only:
        (ops.lq / "AGY_ONLY").touch()
    ops.fake("claude", f'echo CLAUDE-CALLED >> "{ops.tmp}/calls"; echo "VERDICT: PASS"')
    ops.fake("agy", f'echo "AGY $*" >> "{ops.tmp}/calls"; ls .review-* >> "{ops.tmp}/calls" 2>&1; echo "{agy_reply}"')
    r = ops.source("b-review.sh", f'review_main {LANE}', extra_env={"NEXUS_REVIEW_RETRY_S": "0"})
    assert r.returncode == 0, r.stderr
    calls = (ops.tmp / "calls").read_text() if (ops.tmp / "calls").exists() else ""
    return calls


def test_b_review_under_agy_only_uses_neon_xhigh_and_never_claude(ops):
    calls = review_run(ops, True, "VERDICT: PASS")
    assert "CLAUDE-CALLED" not in calls and "--model gemini-4-neon-xhigh --mode plan --print-timeout 1500s" in calls
    assert ".review-" in calls and "reviewed=neon-agy-only" in (ops.sq / f"{LANE}.ship").read_text()
    assert not list(ops.repo.glob(".review-*")) and not list(ops.codo.glob("**/.review-*.diff"))


def test_b_review_under_copilot_only_leaves_entry_pending_without_invoking_reviewers(ops):
    f = ship_entry(ops)
    (ops.lq / "COPILOT_ONLY").touch()
    ops.fake("claude", f'echo claude >> "{ops.tmp}/reviewer-calls"; exit 99')
    ops.fake("agy", f'echo agy >> "{ops.tmp}/reviewer-calls"; exit 99')

    r = ops.source("b-review.sh", f"review_main {LANE}")

    assert r.returncode == 0, r.stderr
    assert f.exists() and "review=pending" in f.read_text()
    assert not (ops.tmp / "reviewer-calls").exists()
    assert "B-REVIEW DEFERRED" in (ops.logs / "ship-runner.log").read_text()


@pytest.mark.parametrize("agy_only,expected_model", [
    (True, "gemini-4-neon-xhigh"),
    (False, "gemini-4-neon-high"),
])
def test_b_review_print_timeout_uses_valid_go_duration(ops, agy_only, expected_model):
    calls = review_run(ops, agy_only, "VERDICT: PASS")
    assert f"--model {expected_model}" in calls
    timeouts = re.findall(r"--print-timeout(?:=|\s+)(\S+)", calls)
    assert timeouts, f"expected --print-timeout in agy invocation: {calls!r}"
    for val in timeouts:
        assert re.fullmatch(r"([0-9]+[smh]|0)", val), f"invalid Go duration for --print-timeout: {val!r}"
        assert val == "1500s"


def test_b_review_without_agy_only_still_uses_claude(ops):
    calls = review_run(ops, False, "VERDICT: PASS")
    assert "CLAUDE-CALLED" in calls and "reviewed=sonnet" in (ops.sq / f"{LANE}.ship").read_text()


def test_b_review_under_agy_only_retries_three_times_then_holds(ops):
    calls = review_run(ops, True, "no verdict")
    assert calls.count("AGY ") == 3 and "CLAUDE-CALLED" not in calls
    assert (ops.sq / "held" / f"{LANE}.ship").exists()


def test_b_review_block_under_agy_only_queues_a_fix_lane_without_pin_claude(ops):
    review_run(ops, True, "VERDICT: BLOCK")
    meta = next(ops.lq.glob("*fix-blocked*.meta")).read_text()
    assert "review_returned=1" in meta and "pin_claude" not in meta


def test_status_prints_agy_only_on(ops):
    assert "AGY_ONLY" not in ops.bash(f'bash "{OPS}/status.sh"').stdout
    (ops.lq / "AGY_ONLY").touch()
    assert "AGY_ONLY on" in ops.bash(f'bash "{OPS}/status.sh"').stdout


# ---- relative-path guards and inline logs in fix briefs ----------------------------------------------------------------

REL_PATH_GUARD = "Always read/edit files using repo-relative paths inside your current worktree; never call read_file on paths outside it."


def test_review_decide_strips_worktree_paths_and_adds_relative_path_guard(ops):
    ship_entry(ops)
    review_text = (
        "Defects:\n"
        "- [FooService.java](file:///Users/dev/Codo/nexus-q-20261126-1200-G7-D3-p1-sample/ui2/service/src/FooService.java#L12) swallows error\n"
        "- /Users/dev/Codo/nexus-q-20261126-1200-G7-D3-p1-sample/ui2/frontend/src/App.tsx:45 missing check\n"
        '- See ("/tmp/other/nexus-q-999-bar/scripts/helper.py")\n'
        "VERDICT: BLOCK\n"
    )
    decide(ops, review_text)
    brief = next(ops.lq.glob("*-fix-blocked-p1-sample.brief")).read_text()
    assert REL_PATH_GUARD in brief
    assert "the inline review below" in brief
    assert str(ops.logs) not in brief and f"review-{LANE}.md" not in brief
    assert "[FooService.java](ui2/service/src/FooService.java#L12)" in brief
    assert "ui2/frontend/src/App.tsx:45 missing check" in brief
    assert 'See ("scripts/helper.py")' in brief
    assert "nexus-q-" not in brief and "file://" not in brief and "/Users/dev" not in brief


def test_ship_fix_brief_inlines_bounded_logs_strips_worktree_paths_and_adds_guard(ops):
    inflight(
        ops,
        "B7",
        [LANE],
        ["FAILED batch/B7: unit tests did not pass (FAIL file:///Users/dev/Codo/nexus-q-123-foo/ui2/BarTest.java)"],
    )
    pretest_lines = ["FAIL /Users/dev/Codo/nexus-q-123-foo/ui2/service/BarTest.java:10 assertion failed"]
    pretest_lines.extend(f"PASS benign-line-{i:02d}" for i in range(1, 46))
    pretest_lines.append("ERROR: build failed in /Users/dev/Codo/nexus-shiprun/ui2/frontend/src/App.tsx")
    (ops.logs / "pretest-ship-B7.out").write_text("\n".join(pretest_lines) + "\n")

    ship_lines = [f"INFO step-{i:02d}" for i in range(1, 41)]
    ship_lines.append("PREVIEW E2E FAILED TEST: file:///tmp/wt/nexus-q-123-foo/ui2/e2e/login.spec.ts")
    (ops.logs / "ship-B7.log").write_text("\n".join(ship_lines) + "\n")

    run_finish(ops, "B7")
    brief = next(ops.lq.glob("*-fix-*.brief")).read_text()
    assert REL_PATH_GUARD in brief
    assert str(ops.logs) not in brief and "Read the logs" not in brief
    assert "Shipping it failed: FAILED batch/B7: unit tests did not pass (FAIL ui2/BarTest.java)" in brief
    assert "### pretest-ship-B7.out (bounded inline excerpt)" in brief
    assert "FAIL ui2/service/BarTest.java:10 assertion failed" in brief
    assert "ERROR: build failed in ui2/frontend/src/App.tsx" in brief
    assert "benign-line-02" not in brief
    assert "### ship-B7.log (bounded inline excerpt)" in brief
    assert "PREVIEW E2E FAILED TEST: ui2/e2e/login.spec.ts" in brief
    assert "step-02" not in brief
    assert "nexus-q-" not in brief and "nexus-shiprun" not in brief and "file://" not in brief and "/Users/dev" not in brief
