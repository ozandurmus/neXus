"""scripts/standalone_orchestrate.py -- the relay-free, one-task-at-a-time Codex path (PO 2026-09-26)."""
import json
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


def test_refuses_while_another_task_runs(tmp_path, monkeypatch):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path / ".state")
    monkeypatch.setattr(sa, "_live_standalone", lambda: ["other-task"])
    monkeypatch.setattr(sa, "_live_orchestrator_movements", lambda: [])
    with pytest.raises(SystemExit, match="one task at a time"):
        sa.cmd_start(_args())


def test_refuses_while_an_orchestrator_movement_runs(tmp_path, monkeypatch):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path / ".state")
    monkeypatch.setattr(sa, "_live_standalone", lambda: [])
    monkeypatch.setattr(sa, "_live_orchestrator_movements", lambda: ["NXS-LOCAL-0371"])
    with pytest.raises(SystemExit, match="NXS-LOCAL-0371"):
        sa.cmd_start(_args())


@pytest.mark.parametrize("kw,msg", [({"task": "Bad Slug"}, "slug"), ({"model": "gpt-5-unknown"}, "model")])
def test_rejects_bad_task_or_model(tmp_path, monkeypatch, kw, msg):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path / ".state")
    with pytest.raises(SystemExit, match=msg):
        sa.cmd_start(_args(**kw))


def test_start_spawns_codex_with_commit_roots_and_prompt_on_stdin(tmp_path, monkeypatch):
    monkeypatch.setattr(sa, "STATE_DIR", tmp_path / ".state")
    monkeypatch.setattr(sa, "HOME", tmp_path)
    monkeypatch.setattr(sa, "_live_standalone", lambda: [])
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
    assert sa.cmd_start(_args()) == 0
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
def test_ship_test_modes_and_pr_results(monkeypatch, full, integration_pass):
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
