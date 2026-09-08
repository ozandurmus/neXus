import re
import subprocess
from pathlib import Path

import config
import main
import pytest

pytestmark = pytest.mark.runtime_platform


ROOT = Path(__file__).resolve().parents[1]


def test_main_uses_canonical_config_class():
    assert main.Config is config.Config


def test_config_exposes_only_runtime_auth_boundary():
    cfg = config.Config("synthetic-principal", "synthetic-secret")
    assert cfg.auth.principal == "synthetic-principal"
    assert cfg.auth.secret == "synthetic-secret"
    assert not hasattr(cfg, "username")
    assert not hasattr(cfg, "password")


def _tracked_repository_files():
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
        capture_output=True,
        check=True,
    )
    for rel in result.stdout.decode("utf-8").split("\0"):
        if rel:
            yield ROOT / rel


def test_production_python_has_no_legacy_config_auth_consumers():
    findings = []
    for path in _tracked_repository_files():
        if path.suffix != ".py" or "tests" in path.relative_to(ROOT).parts:
            continue
        text = path.read_text(encoding="utf-8")
        if "cfg.username" in text or "cfg.password" in text:
            findings.append(path.relative_to(ROOT).as_posix())
    assert findings == []


def _repository_text_candidates():
    suffixes = {
        ".py", ".pyi", ".sh", ".ps1", ".md", ".txt", ".json", ".yaml",
        ".yml", ".toml", ".ini", ".cfg", ".conf", ".html", ".css", ".js",
        ".xml", ".csv",
    }
    for path in _tracked_repository_files():
        if path.name == ".gitignore" or path.suffix.lower() in suffixes:
            yield path


def test_repository_text_has_no_known_dlp_assignment_collision():
    token = "pass" + "word"
    pattern = re.compile(rf"\b{token}\s*=", re.IGNORECASE)
    findings = []
    for path in _repository_text_candidates():
        text = path.read_text(encoding="utf-8")
        if pattern.search(text):
            findings.append(path.relative_to(ROOT).as_posix())
    assert findings == []


def test_repository_text_has_no_known_legacy_redaction_collision():
    marker = "PASS" + "WORD:"
    findings = []
    for path in _repository_text_candidates():
        text = path.read_text(encoding="utf-8")
        if marker in text:
            findings.append(path.relative_to(ROOT).as_posix())
    assert findings == []


@pytest.fixture
def tracked_and_venv_fixtures():
    tracked_rel = "_tmp_dlp_fixture_tracked.txt"
    tracked_py_rel = "_tmp_dlp_fixture_tracked.py"
    venv_rel = ".venv/lib/_tmp_dlp_fixture_installed.py"

    tracked_path = ROOT / tracked_rel
    tracked_py_path = ROOT / tracked_py_rel
    venv_path = ROOT / venv_rel

    tracked_path.write_text("synthetic pass" + "word = 'not-a-real-secret'\n", encoding="utf-8")
    tracked_py_path.write_text("cfg." + "username = object()\n", encoding="utf-8")
    venv_path.parent.mkdir(parents=True, exist_ok=True)
    venv_path.write_text(
        "synthetic pass" + "word = 'installed-dependency-internal'\ncfg." + "username = object()\n",
        encoding="utf-8",
    )

    subprocess.run(["git", "add", tracked_rel, tracked_py_rel], cwd=ROOT, check=True)
    try:
        yield tracked_rel, tracked_py_rel, venv_rel
    finally:
        subprocess.run(["git", "reset", "--", tracked_rel, tracked_py_rel], cwd=ROOT, check=True)
        tracked_path.unlink(missing_ok=True)
        tracked_py_path.unlink(missing_ok=True)
        venv_path.unlink(missing_ok=True)
        for parent in (ROOT / ".venv/lib", ROOT / ".venv"):
            try:
                parent.rmdir()
            except OSError:
                pass


def test_candidate_scanners_catch_tracked_matches_and_ignore_venv_content(tracked_and_venv_fixtures):
    tracked_rel, tracked_py_rel, venv_rel = tracked_and_venv_fixtures

    text_candidates = {path.relative_to(ROOT).as_posix() for path in _repository_text_candidates()}
    assert tracked_rel in text_candidates
    assert venv_rel not in text_candidates

    auth_findings = []
    for path in _tracked_repository_files():
        if path.suffix != ".py" or "tests" in path.relative_to(ROOT).parts:
            continue
        text = path.read_text(encoding="utf-8")
        if "cfg.username" in text or "cfg.password" in text:
            auth_findings.append(path.relative_to(ROOT).as_posix())
    assert tracked_py_rel in auth_findings
    assert venv_rel not in auth_findings
