"""GOV.PO.3 push-hook baseline scoping (AC-1..AC-7, relay/NXS-LOCAL-0012 seq 3-4).

Covers scripts/nexus_engineer_tool_gate.py's baseline-aware repository
privacy check: a finding already present at the movement's own base commit
is pre-existing debt and never blocks push; only a finding genuinely new
relative to that baseline blocks, exactly as before.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import nexus_engineer_tool_gate as gate  # noqa: E402

pytestmark = pytest.mark.runtime_platform


def _write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _git(repo: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True, check=True)


def _init_repo(repo: Path) -> None:
    _git(repo, "init", "-q", "-b", "main")
    _git(repo, "config", "user.email", "test@example.com")
    _git(repo, "config", "user.name", "Test")


def _commit_all(repo: Path, message: str) -> None:
    _git(repo, "add", "-A")
    _git(repo, "commit", "-q", "-m", message)


def _write_approved_task(repo: Path, base_ref: str) -> None:
    _write(repo, ".nexus/approved_task.json", json.dumps({"report": {"git": {"base": base_ref}}}))


CREDENTIAL_LINE = 'api_key = "hunter2-synthetic"\n'


def test_pre_existing_finding_untouched_push_allowed(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    _commit_all(tmp_path, "base")
    _write_approved_task(tmp_path, "main")

    ok, reason = gate._privacy_check(str(tmp_path))
    assert ok, reason
    assert "pre-existing" in reason


def test_pre_existing_finding_file_touched_elsewhere_still_allowed(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    _commit_all(tmp_path, "base")
    _write_approved_task(tmp_path, "main")

    # Diff touches the same file, but appends after the flagged line -- the
    # flagged span itself (and its line number) is unchanged (AC-6 b).
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE + "unrelated trailer\n")

    ok, reason = gate._privacy_check(str(tmp_path))
    assert ok, reason


def test_pre_existing_finding_survives_line_number_drift(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    _commit_all(tmp_path, "base")
    _write_approved_task(tmp_path, "main")

    # Unrelated earlier lines shift the flagged line's number without
    # changing the flagged content itself (AC-3 / AC-6 d).
    _write(tmp_path, "docs/notes.md", "intro\nunrelated one\nunrelated two\nunrelated three\n" + CREDENTIAL_LINE)

    ok, reason = gate._privacy_check(str(tmp_path))
    assert ok, reason


def test_genuinely_new_finding_denies_and_names_only_the_new_one(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    _commit_all(tmp_path, "base")
    _write_approved_task(tmp_path, "main")

    # A genuinely new finding in a different file; the pre-existing one is
    # untouched and must not be named in the denial (AC-4).
    _write(tmp_path, "docs/other.md", 'api_key = "brand-new-synthetic-leak"\n')

    ok, reason = gate._privacy_check(str(tmp_path))
    assert not ok
    assert "1 new finding" in reason
    assert "docs/other.md" in reason
    assert "docs/notes.md" not in reason


def test_zero_findings_skips_baseline_scan_entirely(tmp_path, monkeypatch):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "nothing sensitive here\n")
    _commit_all(tmp_path, "base")
    _write_approved_task(tmp_path, "main")

    def _explode(cwd):  # pragma: no cover - must never be called
        raise AssertionError("baseline scan must be skipped when there are zero findings (AC-5)")

    monkeypatch.setattr(gate, "_baseline_finding_keys", _explode)
    ok, reason = gate._privacy_check(str(tmp_path))
    assert ok, reason


def test_handle_pre_wires_privacy_check_into_git_push_decision(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    _commit_all(tmp_path, "base")
    _write_approved_task(tmp_path, "main")
    _write(tmp_path, "docs/other.md", 'api_key = "brand-new-synthetic-leak"\n')

    payload = {"tool_name": "Bash", "tool_input": {"command": "git push origin feature"}, "cwd": str(tmp_path)}
    assert gate.handle_pre(payload) == 2

    _write(tmp_path, "docs/other.md", "")
    assert gate.handle_pre(payload) == 0


def test_ac1_live_bug_regression_against_real_repository_state():
    # AC-1: two pre-existing prose false positives (project/build_history.json:47
    # and relay/NXS-LOCAL-0003-local-relay-watch-command.json:155, both
    # build-history-immutable) blocked an unrelated push before this fix.
    # Reproduce against this repository's real current state and assert they
    # are specifically recognized as pre-existing (present in the baseline),
    # rather than asserting the overall gate passes -- an unrelated local
    # runtime artifact (e.g. an untracked data/ or logs/ directory left by an
    # earlier test run) can legitimately still block, and correctly so: it
    # was never committed anywhere, so no git-based baseline can ever call it
    # pre-existing. That is a separate, out-of-scope gap, not this bug.
    ac1_locations = {
        ("project/build_history.json", "CREDENTIAL_LITERAL"),
        ("relay/NXS-LOCAL-0003-local-relay-watch-command.json", "CREDENTIAL_LITERAL"),
    }
    current = gate.scan_repository(ROOT)
    ac1_findings = [f for f in current.findings if (f.path, f.rule) in ac1_locations]
    assert ac1_findings, "expected the AC-1 regression fixtures to still be present in the live repository"

    baseline_keys, baseline_note = gate._baseline_finding_keys(str(ROOT))
    for finding in ac1_findings:
        assert gate._finding_key(ROOT, finding) in baseline_keys, (finding, baseline_note)
