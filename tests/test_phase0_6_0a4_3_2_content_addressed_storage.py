from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from utils.config_evidence import ConfigEvidenceStore, sha256_bytes
from utils.config_storage import analyze_configuration_storage, deduplicate_legacy_storage

pytestmark = pytest.mark.configuration


XML_A = b'<?xml version="1.0"?><config><devices><entry name="a"/></devices></config>'
XML_B = b'<?xml version="1.0"?><config><devices><entry name="b"/></devices></config>'
CP_TEXT = "set hostname FW-CP-1\nset dns primary 192.0.2.53\n"


def test_same_snapshot_reuses_single_content_addressed_object(tmp_path):
    store = ConfigEvidenceStore(tmp_path / "configs")
    first = store.write_xml_snapshot(
        source="panos-direct",
        entity_id="SER1",
        artifact_type="effective",
        artifact_name="effective.xml",
        content=XML_A,
        method="test",
    )
    second = store.write_xml_snapshot(
        source="panos-direct",
        entity_id="SER1",
        artifact_type="effective",
        artifact_name="effective.xml",
        content=XML_A,
        method="test",
    )

    assert first.change_state == "first"
    assert second.change_state == "same"
    assert first.artifact_path == second.artifact_path
    assert first.blob_created is True
    assert second.blob_created is False
    assert first.stored_bytes_delta == len(XML_A)
    assert second.stored_bytes_delta == 0
    assert first.artifact_path.read_bytes() == XML_A
    assert not (first.directory / "effective.xml").exists()
    assert not (second.directory / "effective.xml").exists()
    assert (first.directory / "effective.xml.ref.json").exists()
    assert (second.directory / "effective.xml.ref.json").exists()

    objects = [p for p in store.artifact_root.rglob("*") if p.is_file()]
    assert len(objects) == 1


def test_snapshot_publish_self_heals_transient_permission_error(tmp_path, monkeypatch):
    """A transient lock on the final directory rename must retry, not fail the run.

    Regression for the intermittent local immutable evidence-store
    PermissionError: os.replace(tmp_dir, final_dir) previously had no retry,
    unlike the content-addressed blob write beside it.
    """
    store = ConfigEvidenceStore(tmp_path / "configs")
    real_replace = os.replace
    dir_rename_attempts = {"count": 0}

    def flaky_replace(src, dst):
        # Only the final metadata-directory publish (a directory rename) is
        # the target of this regression; leave the blob write untouched.
        if Path(src).is_dir():
            dir_rename_attempts["count"] += 1
            if dir_rename_attempts["count"] == 1:
                raise PermissionError("simulated transient AV/indexer lock")
        return real_replace(src, dst)

    monkeypatch.setattr("utils.config_evidence.os.replace", flaky_replace)

    result = store.write_xml_snapshot(
        source="panos-direct", entity_id="SER1", artifact_type="effective",
        artifact_name="effective.xml", content=XML_A, method="test",
    )

    assert dir_rename_attempts["count"] == 2
    assert result.change_state == "first"
    assert result.directory.exists()
    assert (result.directory / "metadata.json").exists()


def test_snapshot_publish_raises_after_exhausting_retries(tmp_path, monkeypatch):
    store = ConfigEvidenceStore(tmp_path / "configs")

    def always_locked(src, dst):
        raise PermissionError("simulated persistent lock")

    monkeypatch.setattr("utils.config_evidence.os.replace", always_locked)
    monkeypatch.setattr("utils.config_evidence.time.sleep", lambda *_: None)

    try:
        store.write_xml_snapshot(
            source="panos-direct", entity_id="SER1", artifact_type="effective",
            artifact_name="effective.xml", content=XML_A, method="test",
        )
        assert False, "expected PermissionError to propagate after retries are exhausted"
    except PermissionError:
        pass

    entity_dir = store._entity_dir("panos-direct", "SER1")
    leftovers = [p for p in entity_dir.iterdir()] if entity_dir.exists() else []
    assert not any(p.name.startswith(".tmp-") for p in leftovers)


def test_changed_snapshot_preserves_both_unique_versions(tmp_path):
    store = ConfigEvidenceStore(tmp_path / "configs")
    first = store.write_xml_snapshot(
        source="panos-direct", entity_id="SER1", artifact_type="effective",
        artifact_name="effective.xml", content=XML_A, method="test",
    )
    second = store.write_xml_snapshot(
        source="panos-direct", entity_id="SER1", artifact_type="effective",
        artifact_name="effective.xml", content=XML_B, method="test",
    )
    assert second.change_state == "changed"
    assert second.previous_sha256 == first.sha256
    assert first.artifact_path != second.artifact_path
    assert first.artifact_path.read_bytes() == XML_A
    assert second.artifact_path.read_bytes() == XML_B
    assert len([p for p in store.artifact_root.rglob("*") if p.is_file()]) == 2


def test_vendor_neutral_store_accepts_future_checkpoint_text_evidence(tmp_path):
    store = ConfigEvidenceStore(tmp_path / "configs")
    first = store.write_text_snapshot(
        source="checkpoint-gaia",
        entity_id="GW1",
        artifact_type="gaia_show_configuration",
        artifact_name="show-configuration.txt",
        content=CP_TEXT,
        method="direct_ssh_clish_show_configuration",
    )
    second = store.write_text_snapshot(
        source="checkpoint-gaia",
        entity_id="GW1",
        artifact_type="gaia_show_configuration",
        artifact_name="show-configuration.txt",
        content=CP_TEXT,
        method="direct_ssh_clish_show_configuration",
    )
    assert first.change_state == "first"
    assert second.change_state == "same"
    assert first.artifact_path == second.artifact_path
    metadata = json.loads(second.metadata_path.read_text(encoding="utf-8"))
    assert metadata["media_type"] == "text/plain"
    assert metadata["storage"]["mode"] == "content_addressed_v1"
    assert metadata["validation"]["text_valid"] is True


def _write_legacy_snapshot(root: Path, snap: str, content: bytes, state: str) -> Path:
    directory = root / "panorama-control" / "panorama-management" / snap
    directory.mkdir(parents=True)
    artifact = directory / "panorama-active-management-config.xml"
    artifact.write_bytes(content)
    digest = sha256_bytes(content)
    (directory / "metadata.json").write_text(json.dumps({
        "schema_version": "0.6",
        "source": "panorama-control",
        "entity_id": "panorama-management",
        "artifact_type": "panorama_active_management_config",
        "artifact_file": artifact.name,
        "status": "success",
        "sha256": digest,
        "size_bytes": len(content),
        "change_state": state,
    }), encoding="utf-8")
    (directory / "sha256.txt").write_text(f"{digest}  {artifact.name}\n", encoding="ascii")
    return artifact


def test_storage_analyzer_reports_duplicate_legacy_payload_savings(tmp_path):
    config_root = tmp_path / "configs"
    artifact_root = tmp_path / "artifacts" / "config" / "sha256"
    _write_legacy_snapshot(config_root, "20260101T000000Z_a", XML_A, "first")
    _write_legacy_snapshot(config_root, "20260102T000000Z_b", XML_A, "same")
    _write_legacy_snapshot(config_root, "20260103T000000Z_c", XML_B, "changed")

    report = analyze_configuration_storage(config_root=config_root, artifact_root=artifact_root)
    assert report["history_snapshots"] == 3
    assert report["same_history_events"] == 1
    assert report["legacy_payload_files"] == 3
    assert report["legacy_unique_payloads"] == 2
    assert report["legacy_payload_bytes"] == len(XML_A) * 2 + len(XML_B)
    assert report["new_unique_bytes_needed_for_migration"] == len(XML_A) + len(XML_B)
    assert report["projected_net_reclaim_bytes"] == len(XML_A)


def test_storage_migration_is_dry_run_by_default_then_applies_safely(tmp_path):
    config_root = tmp_path / "configs"
    artifact_root = tmp_path / "artifacts" / "config" / "sha256"
    output_dir = tmp_path / "output"
    first = _write_legacy_snapshot(config_root, "20260101T000000Z_a", XML_A, "first")
    second = _write_legacy_snapshot(config_root, "20260102T000000Z_b", XML_A, "same")

    dry = deduplicate_legacy_storage(
        apply=False, config_root=config_root, artifact_root=artifact_root, output_dir=output_dir
    )
    assert dry["legacy_payload_files"] == 2
    assert dry["unique_payloads"] == 1
    assert dry["projected_net_reclaim_bytes"] == len(XML_A)
    assert first.exists() and second.exists()
    assert not artifact_root.exists()
    assert Path(dry["manifest_path"]).exists()

    applied = deduplicate_legacy_storage(
        apply=True, config_root=config_root, artifact_root=artifact_root, output_dir=output_dir
    )
    assert applied["migrated_payload_files"] == 2
    assert not first.exists() and not second.exists()
    objects = [p for p in artifact_root.rglob("*") if p.is_file()]
    assert len(objects) == 1
    assert objects[0].read_bytes() == XML_A
    for metadata_path in config_root.rglob("metadata.json"):
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        assert metadata["storage"]["mode"] == "content_addressed_v1"
        assert metadata["storage"]["snapshot_contains_payload_copy"] is False
        assert (metadata_path.parent / f"{metadata['artifact_file']}.ref.json").exists()


def _write_metadata_with_artifact_name(root: Path, snap: str, artifact_name: str, content: bytes = XML_A):
    directory = root / "panorama-control" / "panorama-management" / snap
    directory.mkdir(parents=True)
    # Keep a benign payload present; metadata alone carries the malicious path.
    benign = directory / "benign.xml"
    benign.write_bytes(content)
    (directory / "metadata.json").write_text(json.dumps({
        "schema_version": "0.6",
        "source": "panorama-control",
        "entity_id": "panorama-management",
        "artifact_type": "panorama_active_management_config",
        "artifact_file": artifact_name,
        "status": "success",
        "sha256": sha256_bytes(content),
        "size_bytes": len(content),
        "change_state": "first",
    }), encoding="utf-8")
    return directory


def test_migration_rejects_path_traversal_and_absolute_names(tmp_path):
    import pytest

    config_root = tmp_path / "configs"
    artifact_root = tmp_path / "artifacts" / "config" / "sha256"
    output_dir = tmp_path / "output"
    _write_metadata_with_artifact_name(config_root, "bad1", "../victim.xml")
    _write_metadata_with_artifact_name(config_root, "bad2", r"C:\\temp\\victim.xml")

    report = analyze_configuration_storage(config_root=config_root, artifact_root=artifact_root)
    assert report["safety_error_count"] == 2
    assert report["legacy_payload_files"] == 0

    with pytest.raises(RuntimeError, match="Migration refused"):
        deduplicate_legacy_storage(
            apply=False, config_root=config_root, artifact_root=artifact_root, output_dir=output_dir
        )
    assert not output_dir.exists()


def test_migration_rejects_symlink_legacy_artifact(tmp_path):
    import os
    import pytest

    config_root = tmp_path / "configs"
    artifact_root = tmp_path / "artifacts" / "config" / "sha256"
    output_dir = tmp_path / "output"
    directory = config_root / "panorama-control" / "panorama-management" / "badlink"
    directory.mkdir(parents=True)
    victim = tmp_path / "victim.xml"
    victim.write_bytes(XML_A)
    link = directory / "legacy.xml"
    try:
        os.symlink(victim, link)
    except (OSError, NotImplementedError):
        pytest.skip("symlink creation unavailable")
    (directory / "metadata.json").write_text(json.dumps({
        "source": "panorama-control", "entity_id": "panorama-management",
        "artifact_type": "panorama_active_management_config", "artifact_file": "legacy.xml",
        "sha256": sha256_bytes(XML_A), "size_bytes": len(XML_A), "change_state": "first",
    }), encoding="utf-8")

    with pytest.raises(RuntimeError, match="Migration refused"):
        deduplicate_legacy_storage(
            apply=True, config_root=config_root, artifact_root=artifact_root, output_dir=output_dir
        )
    assert victim.exists()


def test_analyzer_hashes_payload_and_surfaces_metadata_hash_mismatch(tmp_path):
    config_root = tmp_path / "configs"
    artifact_root = tmp_path / "artifacts" / "config" / "sha256"
    artifact = _write_legacy_snapshot(config_root, "mismatch", XML_A, "first")
    metadata_path = artifact.parent / "metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    metadata["sha256"] = "0" * 64
    metadata_path.write_text(json.dumps(metadata), encoding="utf-8")

    report = analyze_configuration_storage(config_root=config_root, artifact_root=artifact_root)
    assert report["payload_hashes_verified"] == 1
    assert report["legacy_unique_payloads"] == 1
    assert report["legacy_unique_payload_bytes"] == len(XML_A)

    import pytest
    with pytest.raises(RuntimeError, match="Legacy artifact hash mismatch"):
        deduplicate_legacy_storage(
            apply=False, config_root=config_root, artifact_root=artifact_root, output_dir=tmp_path / "output"
        )


def test_manifest_contains_exact_pre_migration_rollback_state(tmp_path):
    import base64

    config_root = tmp_path / "configs"
    artifact_root = tmp_path / "artifacts" / "config" / "sha256"
    output_dir = tmp_path / "output"
    artifact = _write_legacy_snapshot(config_root, "rollback", XML_A, "first")
    metadata_path = artifact.parent / "metadata.json"
    sha_path = artifact.parent / "sha256.txt"
    metadata_before = metadata_path.read_bytes()
    sha_before = sha_path.read_bytes()

    dry = deduplicate_legacy_storage(
        apply=False, config_root=config_root, artifact_root=artifact_root, output_dir=output_dir
    )
    op = dry["operations"][0]
    state = op["rollback_state"]
    assert dry["manifest_sensitivity"] == "LOCAL_OPERATOR_SENSITIVE_NOT_SHAREABLE"
    assert base64.b64decode(state["metadata_json_b64"]) == metadata_before
    assert state["sha256_txt_existed"] is True
    assert base64.b64decode(state["sha256_txt_b64"]) == sha_before
    assert state["ref_file_existed"] is False
    assert state["ref_file_b64"] is None


def test_interrupted_apply_is_rerunnable_without_losing_remaining_payloads(tmp_path, monkeypatch):
    import pytest

    config_root = tmp_path / "configs"
    artifact_root = tmp_path / "artifacts" / "config" / "sha256"
    output_dir = tmp_path / "output"
    first = _write_legacy_snapshot(config_root, "20260101T000000Z_a", XML_A, "first")
    second = _write_legacy_snapshot(config_root, "20260102T000000Z_b", XML_B, "changed")

    original_unlink = Path.unlink
    calls = {"legacy": 0}
    def flaky_unlink(self, *args, **kwargs):
        if self in {first, second}:
            calls["legacy"] += 1
            if calls["legacy"] == 2:
                raise OSError("simulated interruption")
        return original_unlink(self, *args, **kwargs)

    monkeypatch.setattr(Path, "unlink", flaky_unlink)
    with pytest.raises(OSError, match="simulated interruption"):
        deduplicate_legacy_storage(
            apply=True, config_root=config_root, artifact_root=artifact_root, output_dir=output_dir
        )
    monkeypatch.setattr(Path, "unlink", original_unlink)

    assert (first.exists(), second.exists()).count(True) == 1
    rerun = deduplicate_legacy_storage(
        apply=True, config_root=config_root, artifact_root=artifact_root, output_dir=output_dir
    )
    assert rerun["migrated_payload_files"] == 1
    assert not first.exists() and not second.exists()
    assert len([p for p in artifact_root.rglob("*") if p.is_file()]) == 2
