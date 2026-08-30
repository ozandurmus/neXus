"""RB.4 — recovery artifact validation (V1-V3). Contract: §4."""
import gzip
import json

import pytest

import main
from utils import recovery_crypto, recovery_store
from utils.recovery_manifest import build_manifest
from utils.recovery_validation import (
    RecoveryValidationError,
    attach_restore_proof,
    validate_artifact,
)
from utils.runtime_paths import resolve_recovery_root, resolve_runtime_paths

pytestmark = pytest.mark.recovery


def _device(**overrides):
    device = {
        "vendor": "checkpoint", "entity_id": "fw-01", "physical_endpoint": "fw-01",
        "vsid": None, "hostname_fingerprint": "abc123", "platform": "gaia",
        "software_version": "R81.20", "ha_role": "standalone",
    }
    device.update(overrides)
    return device


def _manifest(plaintext: bytes, artifact_class="cp_gaia_backup", **device_overrides):
    dek = recovery_crypto.generate_key()
    vault_key = recovery_crypto.generate_key()
    sealed = recovery_crypto.encrypt_artifact(dek, plaintext)
    wrapped = recovery_crypto.wrap_data_key(vault_key, dek)
    manifest = build_manifest(
        artifact_id="x",
        device=_device(**device_overrides),
        artifact={
            "class": artifact_class, "vendor_native_filename": "backup.tgz",
            "plaintext_sha256": "a" * 64, "plaintext_bytes": len(plaintext),
            "ciphertext_sha256": __import__("hashlib").sha256(sealed).hexdigest(),
            "ciphertext_bytes": len(sealed), "compression": "gzip" if artifact_class != "pan_running_config" else "none",
            "collected_via": "cp_ssh_scp_fetch", "collection_duration_ms": 1,
        },
        crypto={"scheme": recovery_crypto.SCHEME_ID, "wrapped_data_key": wrapped, "vault_key_id": "abc"},
    )
    return sealed, manifest


def _gzip_tar(content: bytes) -> bytes:
    return gzip.compress(content)


# --- validate_artifact: V1 -----------------------------------------------

def test_v1_pass_when_hash_and_size_match():
    plaintext = _gzip_tar(b"real backup content")
    sealed, manifest = _manifest(plaintext)
    result = validate_artifact(sealed_bytes=sealed, plaintext=plaintext, manifest=manifest)
    v1 = [c for c in result["checks"] if c["level"] == "V1"]
    assert all(c["result"] == "PASS" for c in v1)


def test_v1_fails_on_sha_mismatch_and_caps_level_at_v1_failed():
    plaintext = _gzip_tar(b"real backup content")
    sealed, manifest = _manifest(plaintext)
    tampered_sealed = sealed + b"\x00"  # size + hash both now wrong
    result = validate_artifact(sealed_bytes=tampered_sealed, plaintext=plaintext, manifest=manifest)
    assert result["level"] == "V1"
    assert result["verdict"] == "FAILED"
    v1_results = {c["id"]: c["result"] for c in result["checks"] if c["level"] == "V1"}
    assert v1_results["sha256_match"] == "FAIL"


# --- validate_artifact: V2 -----------------------------------------------

def test_v2_pass_advances_level_to_v2():
    plaintext = _gzip_tar(b"real backup content")
    sealed, manifest = _manifest(plaintext)
    result = validate_artifact(sealed_bytes=sealed, plaintext=plaintext, manifest=manifest)
    assert result["level"] == "V2"  # no unified_devices given -> V3 is NOT_APPLICABLE, doesn't block
    assert result["verdict"] == "WELL_FORMED"


def test_v2_fails_on_corrupt_gzip_and_caps_at_v1():
    plaintext = b"this is not gzip data at all"
    sealed, manifest = _manifest(plaintext)
    result = validate_artifact(sealed_bytes=sealed, plaintext=plaintext, manifest=manifest)
    assert result["level"] == "V1"
    v2 = {c["id"]: c["result"] for c in result["checks"] if c["level"] == "V2"}
    assert v2["archive_openable"] == "FAIL"


def test_v2_xml_check_applies_only_to_pan_running_config():
    xml_plaintext = b"<config><entry name='x'/></config>"
    sealed, manifest = _manifest(xml_plaintext, artifact_class="pan_running_config",
                                  vendor="panorama", platform="pan-os")
    result = validate_artifact(sealed_bytes=sealed, plaintext=xml_plaintext, manifest=manifest)
    xml_check = next(c for c in result["checks"] if c["id"] == "xml_root_valid")
    assert xml_check["result"] == "PASS"


def test_v2_xml_check_not_applicable_for_non_xml_class():
    plaintext = _gzip_tar(b"content")
    sealed, manifest = _manifest(plaintext, artifact_class="cp_gaia_backup")
    result = validate_artifact(sealed_bytes=sealed, plaintext=plaintext, manifest=manifest)
    xml_check = next(c for c in result["checks"] if c["id"] == "xml_root_valid")
    assert xml_check["result"] == "NOT_APPLICABLE"


def test_v2_malformed_xml_fails():
    bad_xml = b"<config><entry>not closed"
    sealed, manifest = _manifest(bad_xml, artifact_class="pan_running_config",
                                  vendor="panorama", platform="pan-os")
    result = validate_artifact(sealed_bytes=sealed, plaintext=bad_xml, manifest=manifest)
    xml_check = next(c for c in result["checks"] if c["id"] == "xml_root_valid")
    assert xml_check["result"] == "FAIL"


# --- validate_artifact: V3 -------------------------------------------------

def _unified_row(entity_id="fw-01", version="R81.20", data_state="live"):
    return {
        "source": "cp", "device": entity_id,
        "software_version": version,
        "inventory_status": {"data_state": data_state},
    }


def test_v3_absent_inventory_is_not_applicable_never_pass():
    plaintext = _gzip_tar(b"content")
    sealed, manifest = _manifest(plaintext)
    result = validate_artifact(sealed_bytes=sealed, plaintext=plaintext, manifest=manifest, unified_devices=[])
    v3 = {c["id"]: c["result"] for c in result["checks"] if c["level"] == "V3"}
    assert v3["inventory_device_present"] == "NOT_APPLICABLE"
    assert v3["inventory_version_match"] == "NOT_APPLICABLE"
    assert result["level"] == "V2"  # never advances to V3 without real matched inventory


def test_v3_matching_version_advances_to_v3_consistent():
    plaintext = _gzip_tar(b"content")
    sealed, manifest = _manifest(plaintext)
    result = validate_artifact(
        sealed_bytes=sealed, plaintext=plaintext, manifest=manifest,
        unified_devices=[_unified_row(version="R81.20")],
    )
    assert result["level"] == "V3"
    assert result["verdict"] == "CONSISTENT"


def test_v3_version_mismatch_fails_and_caps_at_v2():
    plaintext = _gzip_tar(b"content")
    sealed, manifest = _manifest(plaintext)  # manifest software_version=R81.20
    result = validate_artifact(
        sealed_bytes=sealed, plaintext=plaintext, manifest=manifest,
        unified_devices=[_unified_row(version="R81.10")],  # different from artifact
    )
    assert result["level"] == "V2"
    v3 = {c["id"]: c["result"] for c in result["checks"] if c["level"] == "V3"}
    assert v3["inventory_version_match"] == "FAIL"


def test_v3_stale_no_data_inventory_fails():
    plaintext = _gzip_tar(b"content")
    sealed, manifest = _manifest(plaintext)
    result = validate_artifact(
        sealed_bytes=sealed, plaintext=plaintext, manifest=manifest,
        unified_devices=[_unified_row(data_state="no_data")],
    )
    v3 = {c["id"]: c["result"] for c in result["checks"] if c["level"] == "V3"}
    assert v3["inventory_data_state_fresh"] == "FAIL"


def test_v3_wrong_device_artifact_fails_v3_while_passing_v1_v2():
    """The differentiator case (contract §10): a truncated/wrong-device
    artifact that is internally intact and well-formed but doesn't match the
    device it claims to be for."""
    plaintext = _gzip_tar(b"content")
    sealed, manifest = _manifest(plaintext, entity_id="fw-99")  # claims fw-99
    result = validate_artifact(
        sealed_bytes=sealed, plaintext=plaintext, manifest=manifest,
        unified_devices=[_unified_row(entity_id="fw-01")],  # only fw-01 exists
    )
    v1 = {c["id"]: c["result"] for c in result["checks"] if c["level"] == "V1"}
    v2 = {c["id"]: c["result"] for c in result["checks"] if c["level"] == "V2"}
    v3 = {c["id"]: c["result"] for c in result["checks"] if c["level"] == "V3"}
    assert all(r == "PASS" for r in v1.values())
    assert all(r in ("PASS", "NOT_APPLICABLE") for r in v2.values())
    assert v3["inventory_device_present"] == "NOT_APPLICABLE"
    assert result["level"] == "V2"


def test_no_check_ever_sets_restore_proven_true():
    plaintext = _gzip_tar(b"content")
    sealed, manifest = _manifest(plaintext)
    result = validate_artifact(
        sealed_bytes=sealed, plaintext=plaintext, manifest=manifest,
        unified_devices=[_unified_row()],
    )
    assert result["restore_proven"] is False
    assert result["restore_proof"] is None


# --- attach_restore_proof: frozen rule 2 -----------------------------------

def test_attach_restore_proof_requires_all_fields():
    plaintext = _gzip_tar(b"content")
    sealed, manifest = _manifest(plaintext)
    result = validate_artifact(sealed_bytes=sealed, plaintext=plaintext, manifest=manifest)
    with pytest.raises(RecoveryValidationError, match="missing required fields"):
        attach_restore_proof(result, {"proven_at": "2026-08-30"})


def test_attach_restore_proof_requires_success_result():
    plaintext = _gzip_tar(b"content")
    sealed, manifest = _manifest(plaintext)
    result = validate_artifact(sealed_bytes=sealed, plaintext=plaintext, manifest=manifest)
    proof = {"proven_at": "now", "platform_class": "gaia-r81.20", "operator": "x",
              "procedure_ref": "y", "result": "failed"}
    with pytest.raises(RecoveryValidationError, match="result must be"):
        attach_restore_proof(result, proof)


def test_attach_restore_proof_sets_v4_restore_proven():
    plaintext = _gzip_tar(b"content")
    sealed, manifest = _manifest(plaintext)
    result = validate_artifact(sealed_bytes=sealed, plaintext=plaintext, manifest=manifest)
    proof = {"proven_at": "now", "platform_class": "gaia-r81.20", "operator": "x",
              "procedure_ref": "y", "result": "success"}
    updated = attach_restore_proof(result, proof)
    assert updated["level"] == "V4"
    assert updated["verdict"] == "RESTORE_PROVEN"
    assert updated["restore_proven"] is True
    assert updated["restore_proof"] == proof


# --- store integration: revalidate_artifact --------------------------------

def _paths(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("", encoding="utf-8")
    runtime = resolve_runtime_paths(str(tmp_path / "runtime"), environ={}, repository_root=repo)
    recovery = resolve_recovery_root(
        str(tmp_path / "recovery"), environ={}, repository_root=repo, runtime_root=runtime.runtime_root
    )
    return runtime, recovery


def test_revalidate_artifact_writes_validation_back_to_manifest(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    plaintext = _gzip_tar(b"real backup content for revalidation test")

    result = recovery_store.write_artifact(
        recovery, vault_key=vault_key, vault_key_id=vault_key_id,
        device=_device(), artifact_class="cp_gaia_backup", plaintext=plaintext,
        vendor_native_filename="backup.tgz", collected_via="cp_ssh_scp_fetch",
    )
    assert result.manifest["validation"] is None  # nothing validated at write time

    updated = recovery_store.revalidate_artifact(
        result.artifact_dir, result.manifest, vault_key=vault_key,
        unified_devices=[_unified_row()],
    )
    assert updated["validation"]["level"] == "V3"

    reread = recovery_store.read_manifest(result.artifact_dir)
    assert reread["validation"]["level"] == "V3"


# --- CLI integration --------------------------------------------------------

def test_recovery_validate_cli_empty_store_passes(tmp_path, capsys):
    with pytest.raises(SystemExit) as exc:
        main.main([
            "--runtime-root", str(tmp_path / "runtime"),
            "--recovery-root", str(tmp_path / "recovery"),
            "--recovery-validate",
        ])
    assert exc.value.code == 0
    out = capsys.readouterr().out
    assert "Artifacts validated:     0" in out
    assert "Gate:                    PASS" in out


def test_recovery_validate_cli_reports_failed_gate_and_exits_1(tmp_path, capsys):
    runtime_root = tmp_path / "runtime"
    recovery_root = tmp_path / "recovery"
    runtime = resolve_runtime_paths(str(runtime_root), environ={})
    recovery = resolve_recovery_root(str(recovery_root), environ={}, runtime_root=runtime.runtime_root)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    recovery_store.write_artifact(
        recovery, vault_key=vault_key, vault_key_id=vault_key_id,
        device=_device(), artifact_class="cp_gaia_backup", plaintext=b"not gzip data",
        vendor_native_filename="backup.tgz", collected_via="cp_ssh_scp_fetch",
        compression="gzip",  # claims gzip but isn't -- forces a V2 archive_openable FAIL
    )

    with pytest.raises(SystemExit) as exc:
        main.main([
            "--runtime-root", str(runtime_root),
            "--recovery-root", str(recovery_root),
            "--recovery-validate",
        ])
    assert exc.value.code == 1
    out = capsys.readouterr().out
    assert "Artifacts with a finding: 1" in out
    assert "Gate:                    FAIL" in out
