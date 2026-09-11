"""GOV.ORCH.7 section 2.3 item 4 / AC-7 -- fail-closed preflight.

Deliberately a separate module from tests/test_orchestrator.py: that
suite's own autouse `_bypass_preflight` fixture monkeypatches
`orch.run_preflight` away for its dispatch/resume mechanics tests, and does
not reach this module.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import orchestrator as orch  # noqa: E402


def _session_start(**git_overrides) -> dict:
    return {
        "report": {
            "baseline": {"authority": "docs/does/not/exist.md"},
            "git": {"base": "origin/main", **git_overrides},
        }
    }


# --- decide_preflight: the five conditions, isolated ------------------------

def _base_kwargs(**overrides) -> dict:
    kwargs = dict(
        session_start=_session_start(), provider="claude", model="sonnet", effort="high",
        authority_status_ok=True, base_is_origin_main=True,
        packet_validation_errors=[], worker_md_exists=True,
    )
    kwargs.update(overrides)
    return kwargs


def test_decide_preflight_passes_when_every_condition_holds():
    assert orch.decide_preflight(**_base_kwargs()) == []


def test_decide_preflight_fails_on_non_frozen_authority():
    failures = orch.decide_preflight(**_base_kwargs(authority_status_ok=False))
    assert any("FROZEN/RATIFIED" in f for f in failures)


def test_decide_preflight_fails_when_base_is_not_origin_main():
    failures = orch.decide_preflight(**_base_kwargs(base_is_origin_main=False))
    assert any("origin/main" in f for f in failures)


@pytest.mark.parametrize("missing", ("provider", "model", "effort"))
def test_decide_preflight_fails_when_provider_model_or_effort_absent(missing):
    failures = orch.decide_preflight(**_base_kwargs(**{missing: None}))
    assert any("--provider/--model/--effort" in f for f in failures)


def test_decide_preflight_fails_on_packet_validation_errors():
    failures = orch.decide_preflight(**_base_kwargs(packet_validation_errors=["bad field"]))
    assert any("gov_session_transfer.py validate" in f for f in failures)


def test_decide_preflight_fails_when_worker_md_missing():
    failures = orch.decide_preflight(**_base_kwargs(worker_md_exists=False))
    assert any("roles/WORKER.md" in f for f in failures)


def test_decide_preflight_reports_every_failed_condition_at_once():
    failures = orch.decide_preflight(**_base_kwargs(
        authority_status_ok=False, base_is_origin_main=False, provider=None,
        packet_validation_errors=["x"], worker_md_exists=False,
    ))
    assert len(failures) == 5


def test_there_is_no_skip_preflight_flag_anywhere_in_the_cli():
    parser = orch.build_parser()
    help_text = parser.format_help()
    for sub in ("start", "run"):
        sub_parser = next(a for a in parser._subparsers._group_actions[0].choices.items() if a[0] == sub)[1]
        assert "--skip-preflight" not in sub_parser.format_help()
    assert "--skip-preflight" not in help_text


# --- resolve_authority_status_ok --------------------------------------------

def test_resolve_authority_status_ok_true_for_a_frozen_document(tmp_path):
    doc = tmp_path / "X.md"
    doc.write_text("# X\n\n## Status\n\nFROZEN -- PRODUCT OWNER APPROVED\n", encoding="utf-8")
    assert orch.resolve_authority_status_ok("X.md (FROZEN)", repo_root=tmp_path) is True


def test_resolve_authority_status_ok_false_for_a_draft_document(tmp_path):
    doc = tmp_path / "X.md"
    doc.write_text("# X\n\n## Status\n\nDRAFT\n", encoding="utf-8")
    assert orch.resolve_authority_status_ok("X.md (DRAFT)", repo_root=tmp_path) is False


def test_resolve_authority_status_ok_false_when_no_path_found():
    assert orch.resolve_authority_status_ok("nothing here", repo_root=Path("/tmp")) is False


def test_resolve_authority_status_ok_false_when_file_missing(tmp_path):
    assert orch.resolve_authority_status_ok("missing.md", repo_root=tmp_path) is False


# --- run_preflight end-to-end against a small fixture repo ------------------

def _git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True, check=True)


def _fixture_repo(tmp_path: Path, *, frozen: bool) -> Path:
    remote = tmp_path / "remote.git"
    remote.mkdir()
    _git(remote, "init", "-q", "--bare")

    seed = tmp_path / "seed"
    seed.mkdir()
    _git(seed, "init", "-q")
    _git(seed, "config", "user.email", "t@example.com")
    _git(seed, "config", "user.name", "T")
    (seed / "AGENTS.md").write_text("x\n", encoding="utf-8")
    (seed / "AUTHORITY.md").write_text(
        "# AUTHORITY\n\n## Status\n\n" + ("FROZEN" if frozen else "DRAFT") + "\n",
        encoding="utf-8",
    )
    roles = seed / "roles"
    roles.mkdir()
    (roles / "WORKER.md").write_text("# roles/WORKER.md\n", encoding="utf-8")
    _git(seed, "add", ".")
    _git(seed, "commit", "-q", "-m", "seed")
    _git(seed, "branch", "-m", "main")
    _git(seed, "push", str(remote), "main")

    repo = tmp_path / "repo"
    _git(tmp_path, "clone", "-q", str(remote), str(repo))
    _git(repo, "config", "user.email", "t@example.com")
    _git(repo, "config", "user.name", "T")
    _git(repo, "checkout", "-q", "main")
    return repo


def _full_report(**git_overrides) -> dict:
    return {
        "baseline": {"authority": "AUTHORITY.md"},
        "objective": "x", "movement_type": "VALIDATION", "requirements": ["x"],
        "acceptance_criteria": ["x"], "validation_plan": ["x"], "invariants": ["x"],
        "risks": [], "context_not_loaded": [], "scope": {"in": ["x"], "out": ["y"]},
        "recommended_reasoning": {"tier": "Normal", "reason": "x"},
        "git": {"base": "origin/main", "lane": "feature/x", **git_overrides},
        "merge_gate": "n/a", "deployment_direction": "local validation only",
        "output_contract": ["x"],
    }


def test_run_preflight_passes_against_a_fully_valid_fixture_repo(tmp_path):
    repo = _fixture_repo(tmp_path, frozen=True)
    session_start = {"report": _full_report()}
    failures = orch.run_preflight(
        session_start=session_start, movement_id="NXS-LOCAL-0001",
        provider="claude", model="sonnet", effort="high", repo_root=repo,
    )
    assert failures == []


def test_run_preflight_fails_closed_on_a_draft_authority(tmp_path):
    repo = _fixture_repo(tmp_path, frozen=False)
    session_start = {"report": _full_report()}
    failures = orch.run_preflight(
        session_start=session_start, movement_id="NXS-LOCAL-0001",
        provider="claude", model="sonnet", effort="high", repo_root=repo,
    )
    assert any("FROZEN/RATIFIED" in f for f in failures)


# --- run/start refuse to dispatch when preflight fails ----------------------

def test_do_start_refuses_before_dispatch_when_preflight_fails(tmp_path, monkeypatch):
    """`_do_start` (shared by `start` and `run`) must call `run_preflight`
    before `decide_start` and exit non-zero without spawning anything."""
    import argparse

    monkeypatch.setattr(orch, "run_preflight", lambda **kwargs: ["a fabricated failure"])
    spawned = []
    monkeypatch.setattr(orch, "_spawn_engineer", lambda **kwargs: spawned.append(1))

    relay_dir = tmp_path / "relay"
    relay_dir.mkdir()
    (relay_dir / "NXS-LOCAL-0009-x.json").write_text(
        __import__("json").dumps({
            "id": "NXS-LOCAL-0009", "next_actor": "engineer",
            "entries": [{"seq": 1, "marker": "SESSION_START", "actor": "po", "timestamp": "t",
                         "report": {"git": {"base": "origin/main", "lane": "feature/x"}}}],
        }), encoding="utf-8",
    )
    args = argparse.Namespace(
        movement="NXS-LOCAL-0009", relay_dir=str(relay_dir), state_dir=str(tmp_path / "state"),
        worktrees_dir=str(tmp_path / "worktrees"), max_workers=3, provider="claude",
        model="sonnet", effort="high", profile=str(tmp_path / "profile.json"),
        max_budget_usd=3.0, merge_mode=None,
    )
    rc, payload, proc = orch._do_start(args)
    assert rc == 2
    assert payload is None
    assert proc is None
    assert not spawned
