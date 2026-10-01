"""scripts/standalone_orchestrate.py -- the relay-free parallel Codex path (PO 2026-10-01)."""
import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import standalone_orchestrate as sa  # noqa: E402


def _args(**kw):
    import argparse
    base = dict(task="small-fix", model="gpt-6-luna", effort="medium", brief="-", base=None, network=False)
    base.update(kw)
    return argparse.Namespace(**base)


@pytest.mark.parametrize("limit,standalone,relay", [
    (4, ["alpha", "beta", "gamma", "delta"], []),
    (4, ["alpha", "beta", "gamma"], ["relay-task"]),
    (1, [], ["relay-task"]),
    (6, [f"lane-{n}" for n in range(6)], []),
])
def test_refuses_at_parallel_limit(tmp_path, monkeypatch, limit, standalone, relay):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path / ".state")
    monkeypatch.setattr(sa, "_live_standalone", lambda: standalone)
    monkeypatch.setattr(sa, "_live_orchestrator_movements", lambda: relay)
    with pytest.raises(SystemExit, match="parallel limit") as error:
        sa.cmd_start(_args(max_parallel=limit))
    assert ", ".join(standalone + relay) in str(error.value)


@pytest.mark.parametrize("value", ["0", "7", "invalid"])
def test_cli_rejects_invalid_parallel_limit(value):
    with pytest.raises(SystemExit) as error:
        sa.main(["start", "--task", "small-fix", "--model", "gpt-6-luna", "--brief", "-",
                 "--max-parallel", value])
    assert error.value.code == 2


def test_cli_parallel_default(monkeypatch):
    monkeypatch.setattr(sa, "cmd_start", lambda args: args.max_parallel)
    assert sa.main(["start", "--task", "small-fix", "--model", "gpt-6-luna", "--brief", "-"]) == 4


@pytest.mark.parametrize("kw,msg", [({"task": "Bad Slug"}, "slug"), ({"model": "gpt-5-unknown"}, "model")])
def test_rejects_bad_task_or_model(tmp_path, monkeypatch, kw, msg):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path / ".state")
    with pytest.raises(SystemExit, match=msg):
        sa.cmd_start(_args(**kw))


@pytest.mark.parametrize("limit,busy", [(4, 0), (4, 3), (6, 5), (1, 0)])
def test_start_spawns_codex_with_commit_roots_and_prompt_on_stdin(tmp_path, monkeypatch, limit, busy):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path / ".state")
    monkeypatch.setattr(sa, "HOME", tmp_path)
    monkeypatch.setattr(sa, "_live_standalone", lambda: [f"lane-{n}" for n in range(busy)])
    monkeypatch.setattr(sa, "_live_orchestrator_movements", lambda: [])
    info_exclude = tmp_path / "common" / "info" / "exclude"

    def fake_git(*args, cwd=None, check=True):
        if args[:1] == ("rev-parse",) and "--git-path" in args:
            return str(info_exclude)
        if args[:1] == ("rev-parse",):
            return "abc123"
        if args[:2] == ("worktree", "add"):
            Path(args[3]).mkdir(parents=True)
        return ""

    monkeypatch.setattr(sa, "_git", fake_git)
    monkeypatch.setattr(sa.orch, "_link_frontend_dependencies", lambda w, c: None)
    monkeypatch.setattr(sa.orch, "_worktree_git_dir", lambda w: tmp_path / "common" / "worktrees" / "small-fix")
    monkeypatch.setattr(sa.orch, "_git_object_dir", lambda w: tmp_path / "common")
    spawned = {}

    class P:
        pid = 4242

    def fake_popen(argv, **kw):
        spawned["argv"], spawned["env"] = argv, kw["env"]
        return P()

    monkeypatch.setattr(sa.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(sa.sys, "stdin", type("S", (), {"read": staticmethod(lambda: "Fix the label.")})())
    assert sa.cmd_start(_args(max_parallel=limit)) == 0
    argv = spawned["argv"]
    assert argv[:2] == ["/bin/sh", "-c"] and "$@" in argv[2]
    codex = argv[4:]
    assert codex[:2] == ["codex", "exec"] and "gpt-6-luna" in codex
    assert "sandbox_workspace_write.network_access=false" in codex
    assert "sandbox_workspace_write.network_access=true" not in codex
    assert str(tmp_path / "common") in [codex[i + 1] for i, v in enumerate(codex) if v == "--add-dir"]
    prompt = Path(spawned["env"]["SA_PROMPT"]).read_text()
    assert "Do not push" in prompt and prompt.endswith("Fix the label.")
    assert ".standalone/" in info_exclude.read_text()
    record = json.loads((tmp_path / ".state" / "small-fix.json").read_text())
    assert record["branch"] == "sa/small-fix" and record["pid"] == 4242


@pytest.mark.parametrize("full,integration_pass", [(False, True), (True, True), (True, False)])
def test_ship_test_modes_and_pr_results(tmp_path, monkeypatch, full, integration_pass):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path / ".state")
    import subprocess
    calls = []

    def git(*args, **kwargs):
        if args[0] == "status":
            return ""
        if args[0] == "rev-list":
            return "1"
        return "synthetic-commit"

    def run(args, **kwargs):
        calls.append(args)
        output = ""
        code = 0
        if "repository_privacy_check.py" in " ".join(args):
            output = "Gate:                 PASS"
        elif args[:3] == ["gh", "pr", "create"]:
            output = "https://example.invalid/pr/1"
        elif "scripts/hosta_e2e.sh" in args:
            output = "23 passed\nE2E: PASS\n"
        elif "scripts/hosta_integration.sh" in args:
            code = 0 if integration_pass else 1
            output = "INTEGRATION: PASS" if integration_pass else "INTEGRATION: FAIL"
        return subprocess.CompletedProcess(args, code, output, "")

    monkeypatch.setattr(sa, "_git", git)
    monkeypatch.setattr(sa, "_deploy", lambda *_: None)
    monkeypatch.setattr(sa.subprocess, "run", run)
    result = sa.cmd_ship(_args(task=None, branch="feature/synthetic", notes=None, title=None, full=full))
    assert result == (0 if integration_pass else 1)
    e2e = next(args for args in calls if "scripts/hosta_e2e.sh" in args)
    assert e2e[-1] == ("--full" if full else "--quick")
    assert any("scripts/hosta_integration.sh" in args for args in calls) == full
    comments = [args[-1] for args in calls if args[:3] == ["gh", "pr", "comment"]]
    assert any("PostgreSQL 16" in text and ("PASS" if integration_pass else "FAIL") in text for text in comments) == full


@pytest.mark.parametrize("fact", [
    "AllowTcpForwarding no", "never a jump server", "only inside container images",
    "kaniko build Jobs in ui2-build", "integration Job", "Workers cannot run Gradle in the sandbox",
    "export KUBECONFIG=$HOME/.kube/config", "ConfigMap `corp-ca`", "masked aiview persona",
    "after every deploy", "any 4xx on page/API requests fails it", "gateway reads run via `bash -lc`",
    "never on management servers", "R82 login-profile fork loop",
])
def test_preamble_standing_facts(fact):
    assert fact in sa.PREAMBLE.format(model="gpt-6-astra")


@pytest.mark.parametrize("timeout", [False, True])
def test_ship_waits_for_other_process(tmp_path, monkeypatch, capsys, timeout):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path)
    lock_path = tmp_path / "ship.lock"
    holder = subprocess.Popen([
        sys.executable, "-c",
        "import fcntl, sys; "
        "lock = open(sys.argv[1], 'a+'); fcntl.flock(lock, fcntl.LOCK_EX); "
        "lock.write('first-task'); lock.flush(); print('ready', flush=True); sys.stdin.readline()",
        str(lock_path),
    ], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    clock = [0]
    sleeps = []
    shipped = []

    def sleep(seconds):
        sleeps.append(seconds)
        clock[0] += seconds
        if not timeout:
            holder.communicate("release\n", timeout=5)

    def ship(args):
        # A separate process must still be excluded while the ship body executes.
        check = subprocess.run([
            sys.executable, "-c",
            "import fcntl, sys; lock = open(sys.argv[1], 'a+'); "
            "fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)", str(lock_path),
        ], capture_output=True, text=True, timeout=5)
        assert check.returncode != 0 and "BlockingIOError" in check.stderr
        shipped.append(args.task)
        return 0

    monkeypatch.setattr(sa.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(sa.time, "sleep", sleep)
    monkeypatch.setattr(sa, "_ship", ship)
    try:
        assert holder.stdout.readline().strip() == "ready"
        assert sa.cmd_ship(_args(task="second-task")) == (3 if timeout else 0)
        assert sleeps == [60] * (45 if timeout else 1)
        assert capsys.readouterr().out.splitlines() == ["waiting for first-task ship"] * len(sleeps)
        assert shipped == ([] if timeout else ["second-task"])
    finally:
        if holder.poll() is None:
            holder.communicate("release\n", timeout=5)
    with lock_path.open("a+") as lock:
        sa.fcntl.flock(lock, sa.fcntl.LOCK_EX | sa.fcntl.LOCK_NB)


def test_ship_releases_lock_on_failure(tmp_path, monkeypatch):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path)

    def fail(args):
        raise SystemExit("synthetic failure")

    monkeypatch.setattr(sa, "_ship", fail)
    with pytest.raises(SystemExit, match="synthetic failure"):
        sa.cmd_ship(_args())
    with (tmp_path / "ship.lock").open("a+") as lock:
        sa.fcntl.flock(lock, sa.fcntl.LOCK_EX | sa.fcntl.LOCK_NB)


def test_status_compact_and_task_json(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path)
    monkeypatch.setattr(sa, "_phase", lambda record: record["phase"])
    monkeypatch.setattr(sa, "_last_activity", lambda record: "activity\n" + "x" * 100)
    started = (sa.dt.datetime.now(sa.dt.timezone.utc) - sa.dt.timedelta(minutes=7)).isoformat()
    for task, phase in [("alpha", "running"), ("beta", "done"), ("gamma", "failed"),
                        ("stopped-task", "stopped"), ("cleaned-task", "cleaned")]:
        sa._save(task, dict(phase=phase, started_at=started, model="gpt-6-luna", branch=f"sa/{task}"))
    assert sa.cmd_status(_args(task=None)) == 0
    lines = capsys.readouterr().out.splitlines()
    assert len(lines) == 4
    for line, phase in zip(lines, ["running", "done", "failed", "stopped"]):
        _, actual_phase, minutes, activity = line.split(" ", 3)
        assert actual_phase == phase and minutes == "7m"
        assert activity == ("activity " + "x" * 100)[:80]
    assert sa.cmd_status(_args(task="cleaned-task")) == 0
    assert json.loads(capsys.readouterr().out)[0]["phase"] == "cleaned"


def test_start_holds_admission_lock_and_releases_on_failure(tmp_path, monkeypatch):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path)

    def start(args):
        with (tmp_path / "start.lock").open("a+") as second:
            with pytest.raises(BlockingIOError):
                sa.fcntl.flock(second, sa.fcntl.LOCK_EX | sa.fcntl.LOCK_NB)
        raise SystemExit("synthetic failure")

    monkeypatch.setattr(sa, "_start", start)
    with pytest.raises(SystemExit, match="synthetic failure"):
        sa.cmd_start(_args())
    with (tmp_path / "start.lock").open("a+") as lock:
        sa.fcntl.flock(lock, sa.fcntl.LOCK_EX | sa.fcntl.LOCK_NB)
