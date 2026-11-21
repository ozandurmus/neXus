"""2026-11-17 ship-speed changes: unit tests in parallel with the preview (--await-pretest), module e2e cadence
(--module-e2e edges), and the pre-ship test scope. Local doubles only."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "delivery"))
import module_deploy as deploy  # noqa: E402
import pretest_scope as scope  # noqa: E402
import standalone_orchestrate as sa  # noqa: E402
from test_ship_speed import ship_doubles, ship_args, names, text  # noqa: E402


# ---------------------------------------------------------------------------------------------- await pretest

def clock_sleep():
    t = [0.0]
    return (lambda: t[0]), (lambda s: t.__setitem__(0, t[0] + s))


def test_await_pretest_passes_on_pass(tmp_path, capsys):
    f = tmp_path / "v"; f.write_text("PASS\n")
    clock, sleep = clock_sleep()
    sa._await_pretest(str(f), clock=clock, sleep=sleep)
    assert '"pretest": "pass"' in capsys.readouterr().out


@pytest.mark.parametrize("content", ["FAIL unit tests", "garbage", "pass"])
def test_await_pretest_fails_closed(tmp_path, content):
    f = tmp_path / "v"; f.write_text(content + "\n")
    clock, sleep = clock_sleep()
    with pytest.raises(SystemExit, match="did not pass"):
        sa._await_pretest(str(f), clock=clock, sleep=sleep)


def test_await_pretest_waits_then_times_out_without_verdict(tmp_path):
    clock, sleep = clock_sleep()
    with pytest.raises(SystemExit, match="still missing"):
        sa._await_pretest(str(tmp_path / "none"), timeout=60, poll=5, clock=clock, sleep=sleep)


def test_await_pretest_sees_a_verdict_written_later(tmp_path):
    f = tmp_path / "v"
    t = [0.0]

    def sleep(s):
        t[0] += s
        if t[0] >= 20:
            f.write_text("PASS\n")
    sa._await_pretest(str(f), timeout=600, poll=5, clock=lambda: t[0], sleep=sleep)


def test_ship_merges_only_after_the_pretest_verdict(monkeypatch, tmp_path):
    events = []
    ship_doubles(monkeypatch, text("ui2/service/A.java"), events)
    monkeypatch.setattr(sa, "_await_pretest", lambda p: events.append(("pretest", p)))
    assert sa._ship(ship_args(await_pretest="V")) == 0
    order = names(events)
    assert order.index("preview") < order.index("pretest") < order.index("gh pr merge")


def test_failed_pretest_closes_the_pr_and_never_merges(monkeypatch):
    events = []
    ship_doubles(monkeypatch, text("ui2/service/A.java"), events)

    def red(p):
        raise SystemExit("unit tests did not pass (FAIL); not merged")
    monkeypatch.setattr(sa, "_await_pretest", red)
    with pytest.raises(SystemExit, match="unit tests did not pass"):
        sa._ship(ship_args(await_pretest="V"))
    assert any(e[:3] == ("gh", "pr", "close") for e in events)
    assert not any(e[:3] == ("gh", "pr", "merge") for e in events) and ("deploy",) not in events


def test_cli_flag(monkeypatch):
    monkeypatch.setattr(sa, "cmd_ship", lambda a: a.await_pretest)
    assert sa.main(["ship", "--branch", "x", "--await-pretest", "/tmp/v"]) == "/tmp/v"
    assert sa.main(["ship", "--branch", "x"]) is None


# ---------------------------------------------------------------------------------------------- module e2e cadence

def test_e2e_cadence():
    assert all(deploy.e2e_after_step(i, 5, "each") for i in range(5))
    assert [deploy.e2e_after_step(i, 5, "edges") for i in range(5)] == [True, False, False, False, True]
    assert deploy.e2e_after_step(0, 1, "edges")
    assert deploy.e2e_after_step(1, 5, "bogus")


def run_main(monkeypatch, capsys, mode, extra=()):
    calls = []
    snap = ROOT / "tools"
    monkeypatch.setattr(deploy, "validate_targets", lambda v: v.split())
    monkeypatch.setattr("release_snapshot.load_snapshot", lambda d: {"runtime": {}}, raising=False)
    monkeypatch.setattr(deploy, "prepare_policy_public_trust", lambda: None)

    def replace(target, image, snapshot, limit=0, commit=None, authorization=None, rollback=False, defer_e2e=False):
        calls.append((target, defer_e2e))
        return {"module": target, "e2e": "PASS"}
    monkeypatch.setattr(deploy, "replace", replace)
    monkeypatch.setattr(deploy, "module_e2e", lambda c=None: calls.append(("group-e2e",)))
    img = "registry.example.invalid/x@sha256:" + "a" * 64
    monkeypatch.setattr(sys, "argv", ["m", "--targets", "service worker configuration compliance policy",
                                      "--image", img, "--snapshot", str(snap), "--module-e2e", mode, *extra])
    deploy.main()
    return calls


def test_edges_skips_the_middle_e2e(monkeypatch, capsys):
    calls = run_main(monkeypatch, capsys, "edges")
    by = {c[0]: c for c in calls}
    assert by["service"] == ("service", False) and by["policy"] == ("policy", False)
    assert by["worker"] == ("worker", True)           # e2e deferred = skipped
    assert ("group-e2e",) not in calls                # the config+compliance group skipped its e2e
    out = capsys.readouterr().out
    assert '"e2e": "BATCHED"' in out


def test_each_keeps_every_e2e(monkeypatch, capsys):
    calls = run_main(monkeypatch, capsys, "each")
    assert ("worker", False) in calls and ("group-e2e",) in calls


# ---------------------------------------------------------------------------------------------- pretest scope

def test_scope_leaf_module_and_dependents():
    v = scope.scope_for(["ui2/worker/src/main/java/A.java"])
    assert v["scope"] == "scoped" and ":worker:test" in v["gradle_tasks"]
    assert ":architecture-tests:test" in v["gradle_tasks"] and ":platform-core:test" not in v["gradle_tasks"]
    assert ":service:test" in v["gradle_tasks"]          # service has runtimeOnly(worker)
    assert v["frontend"] is False


def test_scope_core_module_pulls_in_everything_above_it():
    v = scope.scope_for(["ui2/platform-core/src/main/java/A.java"])
    assert {":persistence:test", ":job-engine:test", ":service:test", ":worker:test"} <= set(v["gradle_tasks"])
    assert ":integration-tests:test" not in v["gradle_tasks"]


def test_scope_frontend_only():
    v = scope.scope_for(["ui2/frontend/src/App.tsx", "docs/x.md"])
    assert v["scope"] == "scoped" and v["frontend"] is True


def test_scope_frontend_only_and_docs_only_run_no_java_module_tests():
    """Pinned by the 2026-11-20 ship-speed measurement: only the structural architecture task is selected."""
    front = scope.scope_for(["ui2/frontend/src/App.tsx", "ui2/frontend/tests/a.test.tsx"])
    assert front["gradle_tasks"] == [":architecture-tests:test"] and front["frontend"] is True
    docs = scope.scope_for(["docs/design/X.md", "CURRENT_STATE.md"])
    assert docs["scope"] == "scoped" and docs["gradle_tasks"] == [":architecture-tests:test"]
    assert docs["frontend"] is False


@pytest.mark.parametrize("path", [
    "ui2/build.gradle.kts", "ui2/settings-gradle.lockfile", "ui2/gradle/libs.versions.toml", "ui2/frontend/package.json",
    "tools/delivery/x.py", "deploy/ui2/50-service-deployment.yaml", "scripts/x.py", "unknown.txt", "../etc/passwd",
    "ui2/newmodule/src/A.java",
])
def test_scope_fails_closed_to_full(path):
    assert scope.scope_for(["ui2/worker/src/main/java/A.java", path])["scope"] == "full"


def test_scope_empty_is_full():
    assert scope.scope_for([])["scope"] == "full"


def test_scope_cli_stdin(monkeypatch, capsys):
    monkeypatch.setattr(sys, "stdin", __import__("io").StringIO("ui2/cli/src/A.java\n"))
    assert scope.main(["--paths-from-stdin"]) == 0
    assert json.loads(capsys.readouterr().out)["scope"] == "scoped"
