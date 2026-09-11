from pathlib import Path

from utils.repository_privacy import scan_repository
import pytest

pytestmark = pytest.mark.runtime_platform


def _write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def test_clean_synthetic_repository_passes(tmp_path):
    _write(tmp_path, "main.py", "ENDPOINT = '192.0.2.10'\n")
    _write(tmp_path, "tests/test_sample.py", "api_key = 'synthetic-token'\nIP='10.0.0.1'\n")
    report = scan_repository(tmp_path)
    assert report.gate == "PASS"
    assert report.findings == ()


def test_private_endpoint_and_local_user_path_fail_without_echoing_values(tmp_path):
    _write(tmp_path, "config.md", "endpoint: 10.23.45.67\npath: C:\\Users\\operator123\\work\\x\n")
    report = scan_repository(tmp_path)
    assert report.gate == "FAIL"
    rules = {finding.rule for finding in report.findings}
    assert "PRIVATE_ENDPOINT_LITERAL" in rules
    assert "LOCAL_USER_PATH" in rules
    assert "10.23.45.67" not in repr(report.findings)
    assert "operator123" not in repr(report.findings)


def test_forbidden_runtime_and_binary_artifacts_fail(tmp_path):
    (tmp_path / "output").mkdir()
    (tmp_path / "capture.pcap").write_bytes(b"synthetic")
    report = scan_repository(tmp_path)
    rules = {finding.rule for finding in report.findings}
    assert "RUNTIME_DIRECTORY_PRESENT" in rules
    assert "PACKET_CAPTURE" in rules


def test_private_key_marker_is_reported_without_key_body(tmp_path):
    _write(tmp_path, "bad.txt", "-----BEGIN PRIVATE KEY-----\nSYNTHETIC-KEY-BODY\n")
    report = scan_repository(tmp_path)
    assert any(f.rule == "PRIVATE_KEY_MATERIAL" for f in report.findings)
    assert "SYNTHETIC-KEY-BODY" not in repr(report.findings)


def test_environment_specific_cp_exclusion_default_is_detected(tmp_path):
    _write(
        tmp_path,
        "checkpoint/scripts/cp_inventory.sh",
        'SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES="${SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES:-DEVICE_A_,DEVICE_B_}"\n',
    )
    report = scan_repository(tmp_path)
    assert any(f.rule == "ENVIRONMENT_IDENTITY_LITERAL" for f in report.findings)


def test_credential_rule_ignores_prose_that_merely_quotes_the_keyword(tmp_path):
    """Documentation sentences such as ``'password='/'PASSWORD:' substrings``
    are not credential literals: the captured value is either punctuation
    only or running prose. A real single-token secret still trips the rule."""
    from utils.repository_privacy import scan_repository

    repo = tmp_path / "repo"
    (repo / "docs").mkdir(parents=True)
    (repo / "docs" / "note.md").write_text(
        "third-party packages contain literal 'password='/'PASSWORD:' substrings; "
        "confirmed unrelated by inspection\n"
        "a note: 'secret= this is a sentence about the scanner not a value'\n",
        encoding="utf-8",
    )
    (repo / "docs" / "leak.md").write_text("password = 'hunter2-real'\n", encoding="utf-8")
    report = scan_repository(repo)
    rules = {(f.path, f.rule) for f in report.findings}
    assert ("docs/note.md", "CREDENTIAL_LITERAL") not in rules
    assert ("docs/leak.md", "CREDENTIAL_LITERAL") in rules
