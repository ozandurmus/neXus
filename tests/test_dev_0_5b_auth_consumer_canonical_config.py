import re
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


def test_production_python_has_no_legacy_config_auth_consumers():
    findings = []
    for path in ROOT.rglob("*.py"):
        if "tests" in path.parts:
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
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        if any(part in {".git", "__pycache__", ".pytest_cache", "node_modules"} for part in path.parts):
            continue
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
