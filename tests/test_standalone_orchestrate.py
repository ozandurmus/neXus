"""scripts/standalone_orchestrate.py -- the relay-free, one-task-at-a-time Codex path (PO 2026-09-26)."""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import standalone_orchestrate as sa  # noqa: E402


def _args(**kw):
    import argparse
    base = dict(task="small-fix", model="gpt-6-luna", effort="medium", brief="-", base=None)
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


@pytest.mark.parametrize("kw,msg", [({"task": "Bad Slug"}, "slug"), ({"model": "gpt-6-astra"}, "model")])
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
    assert str(tmp_path / "common") in [codex[i + 1] for i, v in enumerate(codex) if v == "--add-dir"]
    prompt = Path(spawned["env"]["SA_PROMPT"]).read_text()
    assert "Do not push" in prompt and prompt.endswith("Fix the label.")
    assert ".standalone/" in info_exclude.read_text()
    record = json.loads((tmp_path / ".state" / "small-fix.json").read_text())
    assert record["branch"] == "sa/small-fix" and record["pid"] == 4242
