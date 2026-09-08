"""GOV.PO.3 CI privacy-gate baseline scoping (relay/NXS-LOCAL-0020).

AC-1's live bug: the GitHub Actions 'validation' workflow's repository
privacy gate (`python main.py --repository-privacy-check`) scanned the full
working tree with no baseline, so the two pre-existing, already-accepted
findings relay/NXS-LOCAL-0014 already taught the local pre-push hook
(scripts/nexus_engineer_tool_gate.py) to ignore permanently failed CI on
every PR instead. This extends that same baseline-aware comparison (now
shared in utils.repository_privacy, AC-2) to the CI-facing entry point
(application/workflows/maintenance.py::repository_privacy_check, reached via
`python main.py --repository-privacy-check --privacy-baseline-ref <ref>`).
"""
from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from application.cli import build_parser
from application.context import ApplicationContext
from application.workflows import maintenance as maintenance_wf
from utils.repository_privacy import (
    PrivacyFinding,
    baseline_finding_keys,
    finding_fingerprint,
    finding_key,
    scan_repository,
)

pytestmark = pytest.mark.runtime_platform

ROOT = Path(__file__).resolve().parent.parent


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


CREDENTIAL_LINE = 'api_key = "hunter2-synthetic"\n'


# --- AC-2: shared finding_fingerprint/finding_key/baseline_finding_keys -----


def test_finding_fingerprint_is_stable_across_line_number_drift(tmp_path):
    _write(tmp_path, "docs/notes.md", "intro\nunrelated one\n" + CREDENTIAL_LINE)
    report = scan_repository(tmp_path)
    finding = next(f for f in report.findings if f.rule == "CREDENTIAL_LITERAL")
    moved = PrivacyFinding(finding.path, finding.line + 5, finding.rule)
    # A finding whose recorded line number no longer matches any line in the
    # file has no fingerprint -- distinct from the real finding's fingerprint.
    assert finding_fingerprint(tmp_path, finding) != finding_fingerprint(tmp_path, moved)


def test_finding_key_matches_same_content_different_line(tmp_path):
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    before = scan_repository(tmp_path).findings[0]
    before_key = finding_key(tmp_path, before)  # fingerprint the original line before it moves
    _write(tmp_path, "docs/notes.md", "intro\nunrelated one\nunrelated two\n" + CREDENTIAL_LINE)
    after = scan_repository(tmp_path).findings[0]
    assert before.line != after.line
    assert before_key == finding_key(tmp_path, after)


def test_baseline_finding_keys_none_ref_is_fail_closed(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    _commit_all(tmp_path, "base")
    keys, note = baseline_finding_keys(tmp_path, None)
    assert keys == frozenset()
    assert "unavailable" in note


def test_baseline_finding_keys_resolves_merge_base_with_given_ref(tmp_path):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    _commit_all(tmp_path, "base")
    _git(tmp_path, "branch", "feature")
    _write(tmp_path, "docs/other.md", 'api_key = "brand-new-synthetic-leak"\n')
    _commit_all(tmp_path, "add new leak")

    keys, note = baseline_finding_keys(tmp_path, "feature")
    assert note.startswith("baseline=")
    baseline_root_report = scan_repository(tmp_path)
    pre_existing = [f for f in baseline_root_report.findings if f.path == "docs/notes.md"]
    assert pre_existing
    assert finding_key(tmp_path, pre_existing[0]) in keys
    new_only = [f for f in baseline_root_report.findings if f.path == "docs/other.md"]
    assert new_only
    assert finding_key(tmp_path, new_only[0]) not in keys


# --- AC-3/AC-4: the CI-facing entry point (application.workflows.maintenance) -


def test_ci_privacy_check_rejects_baseline_ref_without_the_check():
    import main

    with pytest.raises(SystemExit) as exc:
        main.main(["--privacy-baseline-ref", "origin/main"])
    assert exc.value.code != 0


def test_ci_privacy_check_pre_existing_finding_passes_with_baseline(tmp_path, monkeypatch):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    _commit_all(tmp_path, "base")
    monkeypatch.setattr(maintenance_wf, "_REPO_ROOT", tmp_path)

    parser = build_parser()
    args = parser.parse_args(["--repository-privacy-check", "--privacy-baseline-ref", "main"])
    ctx = ApplicationContext(args=args, parser=parser, provenance="manual")

    with pytest.raises(SystemExit) as exc:
        maintenance_wf.repository_privacy_check(ctx)
    assert exc.value.code == 0


def test_ci_privacy_check_new_finding_still_fails_with_baseline(tmp_path, monkeypatch):
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    _commit_all(tmp_path, "base")
    _write(tmp_path, "docs/other.md", 'api_key = "brand-new-synthetic-leak"\n')
    monkeypatch.setattr(maintenance_wf, "_REPO_ROOT", tmp_path)

    parser = build_parser()
    args = parser.parse_args(["--repository-privacy-check", "--privacy-baseline-ref", "main"])
    ctx = ApplicationContext(args=args, parser=parser, provenance="manual")

    with pytest.raises(SystemExit) as exc:
        maintenance_wf.repository_privacy_check(ctx)
    assert exc.value.code == 1


def test_ci_privacy_check_without_baseline_ref_fails_exactly_as_before(tmp_path, monkeypatch):
    # AC-3: omitting --privacy-baseline-ref must reproduce the pre-fix
    # behavior exactly -- every finding fails the gate, no silent narrowing.
    _init_repo(tmp_path)
    _write(tmp_path, "docs/notes.md", "intro\n" + CREDENTIAL_LINE)
    _commit_all(tmp_path, "base")
    monkeypatch.setattr(maintenance_wf, "_REPO_ROOT", tmp_path)

    parser = build_parser()
    args = parser.parse_args(["--repository-privacy-check"])
    ctx = ApplicationContext(args=args, parser=parser, provenance="manual")

    with pytest.raises(SystemExit) as exc:
        maintenance_wf.repository_privacy_check(ctx)
    assert exc.value.code == 1


# --- AC-6: real reproduction against this repository's actual current state -


def test_ac6_live_repository_findings_are_pre_existing_against_origin_main():
    ac1_locations = {
        ("project/build_history.json", "CREDENTIAL_LITERAL"),
        ("relay/NXS-LOCAL-0003-local-relay-watch-command.json", "CREDENTIAL_LITERAL"),
    }
    current = scan_repository(ROOT)
    ac1_findings = [f for f in current.findings if (f.path, f.rule) in ac1_locations]
    assert ac1_findings, "expected the AC-1 regression fixtures to still be present in the live repository"

    keys, note = baseline_finding_keys(ROOT, "origin/main")
    for finding in ac1_findings:
        assert finding_key(ROOT, finding) in keys, (finding, note)


def test_ac6_synthetic_new_finding_still_fails_against_origin_main():
    # AC-6: one clearly-fake, untracked finding-shaped scratch file, dropped
    # directly into this repository's own working tree (not committed) and
    # removed again immediately after -- the exact reproduction the
    # acceptance criterion asks for, not a copy.
    scratch = ROOT / "scratch_ac6_synthetic_leak.md"
    assert not scratch.exists(), "scratch fixture path unexpectedly already present"
    scratch.write_text('api_key = "brand-new-synthetic-leak-ac6"\n', encoding="utf-8")
    try:
        keys, _note = baseline_finding_keys(ROOT, "origin/main")
        current = scan_repository(ROOT)
        new_only = [f for f in current.findings if f.path == "scratch_ac6_synthetic_leak.md"]
        assert new_only, "expected the synthetic finding to be detected at all"
        assert finding_key(ROOT, new_only[0]) not in keys
    finally:
        scratch.unlink()
