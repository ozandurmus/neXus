"""Tests for scripts/argon_task.py model-family trailer derivation and refusal guard."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import argon_task  # noqa: E402


@pytest.mark.parametrize(
    ("model", "expected_author", "expected_trailer"),
    [
        ("gemini-4-argon-high", "Gemini 4 Argon", "Co-Authored-By: Gemini 4 Argon"),
        ("gemini-4-argon-low", "Gemini 4 Argon", "Co-Authored-By: Gemini 4 Argon"),
        ("gemini-4-carbon-high", "Gemini 4 Carbon", "Co-Authored-By: Gemini 4 Carbon"),
        ("gemini-4-carbon-medium", "Gemini 4 Carbon", "Co-Authored-By: Gemini 4 Carbon"),
        ("gemini-4-hydrogen-high", "Gemini 4 Hydrogen", "Co-Authored-By: Gemini 4 Hydrogen"),
        ("claude-sonnet-5-5-high", "Claude Sonnet 5.5", "Co-Authored-By: Claude Sonnet 5.5"),
        ("claude-opus-5-5-high", "Claude Opus 5.5", "Co-Authored-By: Claude Opus 5.5"),
    ],
)
def test_trailer_and_worker_rules_follow_model_family(model: str, expected_author: str, expected_trailer: str) -> None:
    assert argon_task.co_author_for_model(model) == expected_author
    assert argon_task.trailer_for_model(model) == expected_trailer
    rules = argon_task.worker_rules(model)
    assert f"`{expected_trailer}` (no e-mail address: the repository privacy gate forbids one)." in rules
    other = "Gemini 4 Carbon" if expected_author == "Gemini 4 Argon" else "Gemini 4 Argon"
    assert f"Co-Authored-By: {other}" not in rules
    assert "@" not in rules


@pytest.mark.parametrize(
    "bad_model",
    [
        "claude-sonnet-5",
        "claude-opus-5-5",
        "gemini-3-pro",
        "gemini-4-other-high",
        "gemini-4-argon",
        "gemini-4-argon-",
        "gemini-4-carbon",
        "gemini-4-carbon-",
        "",
    ],
)
def test_unsupported_model_is_refused_with_allowed_families(
    bad_model: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    with pytest.raises(ValueError) as exc_info:
        argon_task.co_author_for_model(bad_model)
    assert "gemini-4-argon-*" in str(exc_info.value)
    assert "gemini-4-carbon-*" in str(exc_info.value)

    called: list[list[str]] = []
    monkeypatch.setattr(argon_task.subprocess, "run", lambda cmd, **kw: called.append(list(cmd)))
    rc = argon_task.main(["--worktree", str(tmp_path / "nexus-lane"), "--brief", str(tmp_path / "brief.md"), "--model", bad_model])
    err = capsys.readouterr().err
    assert rc == 2
    assert "refused:" in err
    assert "gemini-4-argon-*" in err
    assert "gemini-4-carbon-*" in err
    assert called == []


@pytest.mark.parametrize(
    ("extra_args", "expected_model", "expected_trailer"),
    [
        ([], "gemini-4-argon-high", "Co-Authored-By: Gemini 4 Argon"),
        (["--model", "gemini-4-argon-high"], "gemini-4-argon-high", "Co-Authored-By: Gemini 4 Argon"),
        (["--model", "gemini-4-carbon-high"], "gemini-4-carbon-high", "Co-Authored-By: Gemini 4 Carbon"),
    ],
)
def test_main_builds_prompt_with_model_derived_trailer(
    extra_args: list[str],
    expected_model: str,
    expected_trailer: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    codo = tmp_path / "Codo"
    wt = codo / "nexus-lane"
    wt.mkdir(parents=True)
    brief = tmp_path / "brief.md"
    brief.write_text("# Task brief\nDo the work.", encoding="utf-8")
    log = tmp_path / "argon.log"

    monkeypatch.setattr(argon_task.Path, "home", staticmethod(lambda: tmp_path))
    agy_calls: list[list[str]] = []

    def fake_run(cmd: list[str], **kwargs):
        if cmd[:4] == ["git", "-C", str(wt.resolve()), "rev-parse"]:
            return subprocess.CompletedProcess(cmd, 0, stdout="/repo/.git/worktrees/nexus-lane\n", stderr="")
        if cmd[:4] == ["git", "-C", str(wt.resolve()), "branch"]:
            return subprocess.CompletedProcess(cmd, 0, stdout="feature/test-lane\n", stderr="")
        if cmd[:4] == ["git", "-C", str(wt.resolve()), "log"]:
            return subprocess.CompletedProcess(cmd, 0, stdout="abc1234 synthetic commit\n", stderr="")
        if cmd[0] == "agy":
            agy_calls.append(list(cmd))
            kwargs["stdout"].write("completed\n")
            kwargs["stdout"].flush()
            return subprocess.CompletedProcess(cmd, 0)
        raise AssertionError(f"unexpected command: {cmd}")

    monkeypatch.setattr(argon_task.subprocess, "run", fake_run)
    rc = argon_task.main(["--worktree", str(wt), "--brief", str(brief), "--log", str(log), *extra_args])
    assert rc == 0
    assert len(agy_calls) == 1
    cmd = agy_calls[0]
    assert cmd[cmd.index("--model") + 1] == expected_model
    prompt = cmd[cmd.index("-p") + 1]
    assert f"`{expected_trailer}` (no e-mail address: the repository privacy gate forbids one)." in prompt


def test_module_docstring_documents_both_model_families() -> None:
    doc = argon_task.__doc__ or ""
    assert "gemini-4-argon-*" in doc
    assert "gemini-4-carbon-*" in doc
    assert "Gemini 4 Argon" in doc
    assert "Gemini 4 Carbon" in doc
    assert "gemini-4-hydrogen-*" in doc and "Gemini 4 Hydrogen" in doc
