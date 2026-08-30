from pathlib import Path

import pytest

from utils.persistent_secret_material import check_persistent_secret_material
from utils.runtime_paths import resolve_runtime_paths

pytestmark = pytest.mark.security


def _runtime_paths(tmp_path):
    repo_root = tmp_path / "repo"
    runtime_root = tmp_path / "runtime"
    (repo_root).mkdir()
    (repo_root / "main.py").write_text("", encoding="utf-8")
    return resolve_runtime_paths(
        str(runtime_root),
        environ={},
        repository_root=repo_root,
    )


def test_no_hardening_enabled_is_pass_with_advisory_findings(tmp_path):
    rp = _runtime_paths(tmp_path)
    report = check_persistent_secret_material(rp, environ={})
    assert report.hmac_key_present is False
    assert report.hmac_key_on_persistent_root is True
    assert report.cp_strict_host_key_enabled is False
    assert report.cp_trust_status == "NOT_ENABLED"
    assert report.pan_ca_bundle_configured is False
    assert report.pan_trust_status == "NOT_CONFIGURED"
    assert report.gate == "PASS"
    assert report.findings == []


def test_hmac_key_on_persistent_data_root_survives_restart(tmp_path):
    rp = _runtime_paths(tmp_path)
    key_file = rp.data_root / ".support_hmac.key"
    key_file.parent.mkdir(parents=True, exist_ok=True)
    key_file.write_bytes(b"deadbeef" * 8)

    # Simulate a container restart: resolve the runtime paths again from the
    # same runtime root, as a fresh process would.
    rp_again = resolve_runtime_paths(
        str(rp.runtime_root), environ={}, repository_root=rp.repository_root
    )
    report = check_persistent_secret_material(rp_again, environ={})
    assert report.hmac_key_present is True
    assert report.hmac_key_on_persistent_root is True
    assert report.gate == "PASS"


def test_pan_ca_bundle_configured_but_missing_file_fails_gate(tmp_path):
    rp = _runtime_paths(tmp_path)
    missing = tmp_path / "does-not-exist-ca.pem"
    report = check_persistent_secret_material(
        rp, environ={"SECURITYEXPERT_PAN_CA_BUNDLE": str(missing)}
    )
    assert report.pan_ca_bundle_configured is True
    assert report.pan_trust_status == "FAIL"
    assert report.gate == "FAIL"
    assert "pan_ca_bundle_configured_but_not_readable" in report.findings


def test_pan_ca_bundle_configured_and_readable_passes(tmp_path):
    rp = _runtime_paths(tmp_path)
    ca_bundle = tmp_path / "ca.pem"
    ca_bundle.write_text("synthetic-cert-material", encoding="utf-8")
    report = check_persistent_secret_material(
        rp, environ={"SECURITYEXPERT_PAN_CA_BUNDLE": str(ca_bundle)}
    )
    assert report.pan_trust_status == "PASS"
    assert report.gate == "PASS"


def test_cp_strict_host_key_enabled_without_trusted_material_fails_gate(tmp_path, monkeypatch):
    rp = _runtime_paths(tmp_path)
    # Point paramiko's system host-key lookup at an empty, isolated HOME so
    # this is deterministic regardless of the machine running the test.
    empty_home = tmp_path / "empty_home"
    empty_home.mkdir()
    monkeypatch.setenv("HOME", str(empty_home))
    report = check_persistent_secret_material(
        rp, environ={"SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY": "1"}
    )
    assert report.cp_strict_host_key_enabled is True
    assert report.cp_trust_status == "FAIL"
    assert report.gate == "FAIL"
    assert "cp_strict_host_key_enabled_but_no_trusted_material_mounted" in report.findings


def test_disabled_strict_host_key_values_are_not_enabled(tmp_path):
    rp = _runtime_paths(tmp_path)
    for value in ("0", "false", "no", "off", "disabled", ""):
        report = check_persistent_secret_material(
            rp, environ={"SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY": value}
        )
        assert report.cp_strict_host_key_enabled is False
        assert report.cp_trust_status == "NOT_ENABLED"
