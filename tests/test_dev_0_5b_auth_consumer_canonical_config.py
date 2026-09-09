import hashlib
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


# backlog: dlp_scanner_prose_collision_in_frozen_records -- PO decision (b), 2026-09-09:
# a narrow, byte-identical exception for the two already-catalogued frozen-history prose
# matches below, never a path- or pattern-based blanket exclusion. Each entry is the
# sha256 of the EXACT line of text that trips the scanner; a line is only exempt if its
# hash matches one already catalogued here for that exact file. A new match -- whether in
# a different file, or a newly added/edited line in one of these same two files -- will not
# have a catalogued hash and still fails the gate. Do not add entries here for anything
# other than these two specific, already-known findings.
_KNOWN_PROSE_COLLISION_LINE_HASHES = {
    "project/build_history.json": {
        # M9/local-relay-watch-command evidence prose describing the .venv-scan DLP
        # collision defect itself (RELAY_DECISION NXS-LOCAL-0012 precedent).
        "a2bb9193d211a694b8c2200c326309df0327285242a82477a7d93d813b6ad2fc",
    },
    "relay/NXS-LOCAL-0003-local-relay-watch-command.json": {
        # Same historical evidence prose, recorded in the movement's own relay file.
        "b8acd99d1c4bf36cbe3b9ef8b323746bd261a9da58fdf3152b95be36774546e1",
    },
}


def _is_catalogued_prose_collision(path, line):
    rel = path.relative_to(ROOT).as_posix()
    catalogued = _KNOWN_PROSE_COLLISION_LINE_HASHES.get(rel)
    if not catalogued:
        return False
    return hashlib.sha256(line.encode("utf-8")).hexdigest() in catalogued


def _uncatalogued_prose_matches(matcher):
    findings = []
    for path in _repository_text_candidates():
        for line in path.read_text(encoding="utf-8").splitlines():
            if matcher(line) and not _is_catalogued_prose_collision(path, line):
                findings.append(path.relative_to(ROOT).as_posix())
                break
    return findings


def test_repository_text_has_no_known_dlp_assignment_collision():
    token = "pass" + "word"
    pattern = re.compile(rf"\b{token}\s*=", re.IGNORECASE)
    assert _uncatalogued_prose_matches(pattern.search) == []


def test_repository_text_has_no_known_legacy_redaction_collision():
    marker = "PASS" + "WORD:"
    assert _uncatalogued_prose_matches(lambda line: marker in line) == []


def test_catalogued_prose_collision_exception_is_keyed_to_the_exact_line_not_the_file():
    # A different line in one of the two catalogued files -- even a brand-new one -- must
    # not be silently exempted just because the file path is catalogued (AC-3 / invariant:
    # a new line added to either named file must still be caught).
    build_history_path = ROOT / "project/build_history.json"
    novel_line_same_file = "a brand new evidence line with a synthetic pass" + "word = 'zzz'"
    assert not _is_catalogued_prose_collision(build_history_path, novel_line_same_file)

    relay_path = ROOT / "relay/NXS-LOCAL-0003-local-relay-watch-command.json"
    novel_marker_line_same_file = "a brand new redaction example PASS" + "WORD: zzz"
    assert not _is_catalogued_prose_collision(relay_path, novel_marker_line_same_file)


@pytest.fixture
def synthetic_prose_collision_fixture():
    rel = "_tmp_dlp_fixture_prose_collision.md"
    path = ROOT / rel
    path.write_text("Not one of the catalogued findings.\n", encoding="utf-8")
    subprocess.run(["git", "add", rel], cwd=ROOT, check=True)
    try:
        yield rel, path
    finally:
        subprocess.run(["git", "reset", "--", rel], cwd=ROOT, check=True)
        path.unlink(missing_ok=True)


def test_catalogued_prose_collision_exception_does_not_generalize_to_a_new_finding(
    synthetic_prose_collision_fixture,
):
    rel, path = synthetic_prose_collision_fixture

    token = "pass" + "word"
    pattern = re.compile(rf"\b{token}\s*=", re.IGNORECASE)
    path.write_text("synthetic new pass" + "word = 'not-catalogued'\n", encoding="utf-8")
    assert rel in _uncatalogued_prose_matches(pattern.search)

    marker = "PASS" + "WORD:"
    path.write_text("synthetic new PASS" + "WORD: not-catalogued\n", encoding="utf-8")
    assert rel in _uncatalogued_prose_matches(lambda line: marker in line)


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
