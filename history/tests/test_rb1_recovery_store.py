"""RB.1 — recovery-plane store: layout, encryption, manifest, retention.

Contract: docs/design/BACKUP_RECOVERY_CONTRACTS.md §2/§3/§8/§9.
"""
import json
import random
import zipfile

import pytest

import main
from utils import recovery_crypto, recovery_retention, recovery_store, support_bundle
from utils.recovery_manifest import RecoveryManifestError, build_manifest, validate_manifest
from utils.repository_privacy import scan_repository
from utils.runtime_paths import RuntimePathError, resolve_recovery_root, resolve_runtime_paths

pytestmark = pytest.mark.recovery


# --- resolve_recovery_root ------------------------------------------------

def test_recovery_root_env_var_is_mandatory_no_default(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("", encoding="utf-8")
    with pytest.raises(RuntimePathError, match="required"):
        resolve_recovery_root(environ={}, repository_root=repo)


def test_recovery_root_cli_override_works(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("", encoding="utf-8")
    recovery = tmp_path / "recovery"
    paths = resolve_recovery_root(str(recovery), environ={}, repository_root=repo)
    assert paths.recovery_root == recovery.resolve()
    assert paths.vault_root == recovery.resolve() / "vault"
    assert paths.vault_root.is_dir()
    assert paths.groups_root.is_dir()
    assert paths.retention_root.is_dir()


def test_recovery_root_must_be_absolute(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("", encoding="utf-8")
    with pytest.raises(RuntimePathError, match="absolute"):
        resolve_recovery_root("relative/path", environ={}, repository_root=repo)


def test_recovery_root_rejects_nesting_inside_repository(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("", encoding="utf-8")
    nested = repo / "recovery"
    with pytest.raises(RuntimePathError, match="repository root"):
        resolve_recovery_root(str(nested), environ={}, repository_root=repo)


def test_recovery_root_rejects_nesting_inside_runtime_root(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("", encoding="utf-8")
    runtime_root = tmp_path / "runtime"
    nested = runtime_root / "recovery"
    with pytest.raises(RuntimePathError, match="runtime root"):
        resolve_recovery_root(str(nested), environ={}, repository_root=repo, runtime_root=runtime_root)


def test_recovery_root_disjoint_from_both_repo_and_runtime_succeeds(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("", encoding="utf-8")
    runtime_root = tmp_path / "runtime"
    recovery_root = tmp_path / "recovery"
    paths = resolve_recovery_root(
        str(recovery_root), environ={}, repository_root=repo, runtime_root=runtime_root
    )
    assert paths.recovery_root == recovery_root.resolve()


# --- recovery_crypto -------------------------------------------------------

def test_artifact_encrypt_decrypt_round_trip():
    dek = recovery_crypto.generate_key()
    plaintext = b"vendor-native backup payload, byte-exact" * 100
    sealed = recovery_crypto.encrypt_artifact(dek, plaintext)
    assert plaintext not in sealed
    assert recovery_crypto.decrypt_artifact(dek, sealed) == plaintext


def test_wrap_unwrap_data_key_round_trip():
    vault_key = recovery_crypto.generate_key()
    dek = recovery_crypto.generate_key()
    wrapped = recovery_crypto.wrap_data_key(vault_key, dek)
    assert dek.hex() not in wrapped
    assert recovery_crypto.unwrap_data_key(vault_key, wrapped) == dek


def test_decrypt_with_wrong_key_raises():
    dek = recovery_crypto.generate_key()
    wrong_dek = recovery_crypto.generate_key()
    sealed = recovery_crypto.encrypt_artifact(dek, b"secret bytes")
    with pytest.raises(recovery_crypto.RecoveryCryptoError):
        recovery_crypto.decrypt_artifact(wrong_dek, sealed)


def test_decrypt_tampered_ciphertext_raises():
    dek = recovery_crypto.generate_key()
    sealed = bytearray(recovery_crypto.encrypt_artifact(dek, b"secret bytes"))
    sealed[-1] ^= 0xFF
    with pytest.raises(recovery_crypto.RecoveryCryptoError):
        recovery_crypto.decrypt_artifact(dek, bytes(sealed))


def test_key_id_is_deterministic_and_key_material_free():
    key = recovery_crypto.generate_key()
    fingerprint = recovery_crypto.key_id(key)
    assert recovery_crypto.key_id(key) == fingerprint
    assert key.hex() not in fingerprint


# --- recovery_manifest ------------------------------------------------------

def _device(**overrides):
    device = {
        "vendor": "checkpoint", "entity_id": "fw-01", "physical_endpoint": "fw-01",
        "vsid": None, "hostname_fingerprint": "abc123", "platform": "gaia",
        "software_version": "R81.20", "ha_role": "standalone",
    }
    device.update(overrides)
    return device


def _artifact(artifact_class="cp_gaia_backup", **overrides):
    artifact = {
        "class": artifact_class, "vendor_native_filename": "backup.tgz",
        "plaintext_sha256": "a" * 64, "plaintext_bytes": 100,
        "ciphertext_sha256": "b" * 64, "ciphertext_bytes": 112,
        "compression": "gzip", "collected_via": "cp_ssh_scp_fetch",
        "collection_duration_ms": 100,
    }
    artifact.update(overrides)
    return artifact


def _crypto():
    return {"scheme": recovery_crypto.SCHEME_ID, "wrapped_data_key": "deadbeef==", "vault_key_id": "abc"}


def test_build_manifest_derives_is_rma_grade_true_for_cp_gaia_backup():
    manifest = build_manifest(artifact_id="x", device=_device(), artifact=_artifact(), crypto=_crypto())
    assert manifest["artifact"]["is_rma_grade"] is True
    assert manifest["restore"] is None


def test_build_manifest_pan_running_config_is_never_rma_grade():
    manifest = build_manifest(
        artifact_id="x", device=_device(vendor="panorama", platform="pan-os"),
        artifact=_artifact("pan_running_config"), crypto=_crypto(),
    )
    assert manifest["artifact"]["is_rma_grade"] is False


def test_build_manifest_pan_device_state_is_rma_grade():
    manifest = build_manifest(
        artifact_id="x", device=_device(vendor="panorama", platform="pan-os"),
        artifact=_artifact("pan_device_state"), crypto=_crypto(),
    )
    assert manifest["artifact"]["is_rma_grade"] is True


def test_build_manifest_auto_fills_known_gaps_for_cp_gaia_backup():
    manifest = build_manifest(artifact_id="x", device=_device(), artifact=_artifact(), crypto=_crypto())
    gaps = manifest["restore_constraints"]["known_gaps"]
    assert gaps
    assert any("hotfixes" in g for g in gaps)


def test_build_manifest_rejects_missing_software_version():
    with pytest.raises(RecoveryManifestError, match="software_version"):
        build_manifest(
            artifact_id="x", device=_device(software_version=""), artifact=_artifact(), crypto=_crypto()
        )


def test_build_manifest_rejects_unknown_artifact_class():
    with pytest.raises(RecoveryManifestError, match="unknown artifact class"):
        build_manifest(artifact_id="x", device=_device(), artifact=_artifact("made_up_class"), crypto=_crypto())


def test_validate_manifest_rejects_non_null_restore():
    manifest = build_manifest(artifact_id="x", device=_device(), artifact=_artifact(), crypto=_crypto())
    manifest["restore"] = {"requested_at": "now"}
    with pytest.raises(RecoveryManifestError, match="restore"):
        validate_manifest(manifest)


def test_validate_manifest_rejects_asserted_is_rma_grade_mismatch():
    manifest = build_manifest(
        artifact_id="x", device=_device(vendor="panorama", platform="pan-os"),
        artifact=_artifact("pan_running_config"), crypto=_crypto(),
    )
    manifest["artifact"]["is_rma_grade"] = True  # tampered
    with pytest.raises(RecoveryManifestError, match="is_rma_grade"):
        validate_manifest(manifest)


def test_validate_manifest_rejects_missing_known_gaps_when_required():
    manifest = build_manifest(artifact_id="x", device=_device(), artifact=_artifact(), crypto=_crypto())
    manifest["restore_constraints"]["known_gaps"] = []
    with pytest.raises(RecoveryManifestError, match="known_gaps"):
        validate_manifest(manifest)


# --- recovery_store ----------------------------------------------------------

def _paths(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("", encoding="utf-8")
    runtime = resolve_runtime_paths(str(tmp_path / "runtime"), environ={}, repository_root=repo)
    recovery = resolve_recovery_root(
        str(tmp_path / "recovery"), environ={}, repository_root=repo, runtime_root=runtime.runtime_root
    )
    return runtime, recovery


def test_vault_key_persists_across_a_simulated_restart(tmp_path):
    runtime, recovery = _paths(tmp_path)
    key1, id1 = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    # Simulate a restart: nothing but the two paths on disk carries over.
    key2, id2 = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    assert key1 == key2
    assert id1 == id2


def test_vault_key_env_override(tmp_path):
    runtime, recovery = _paths(tmp_path)
    raw_key = recovery_crypto.generate_key()
    key, key_id = recovery_store.get_or_create_vault_key(
        runtime.data_root, recovery.recovery_root, environ={"SECURITYEXPERT_RECOVERY_VAULT_KEY": raw_key.hex()}
    )
    assert key == raw_key
    assert key_id == recovery_crypto.key_id(raw_key)


def test_vault_key_env_override_rejects_wrong_length():
    with pytest.raises(recovery_store.RecoveryStoreError, match="32 bytes"):
        recovery_store.get_or_create_vault_key(
            "/tmp/does-not-matter-data", "/tmp/does-not-matter-recovery",
            environ={"SECURITYEXPERT_RECOVERY_VAULT_KEY": "ab"},
        )


def test_vault_key_location_rejected_when_nested_under_recovery_root(tmp_path):
    # Frozen invariant §9.2.
    recovery_root = tmp_path / "recovery"
    data_root = recovery_root / "data"  # nested -- must be rejected
    with pytest.raises(recovery_store.RecoveryStoreError, match="§9.2|recovery_root"):
        recovery_store.get_or_create_vault_key(data_root, recovery_root, environ={})


def test_write_read_decrypt_round_trip(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    plaintext = b"REAL-BACKUP-BYTES-fw-01-secret-material" * 50

    result = recovery_store.write_artifact(
        recovery,
        vault_key=vault_key, vault_key_id=vault_key_id,
        device=_device(), artifact_class="cp_gaia_backup", plaintext=plaintext,
        vendor_native_filename="backup.tgz", collected_via="cp_ssh_scp_fetch",
    )
    assert result.manifest["artifact"]["is_rma_grade"] is True

    read_back = recovery_store.read_manifest(result.artifact_dir)
    assert read_back == result.manifest

    decrypted = recovery_store.decrypt_artifact(result.artifact_dir, read_back, vault_key=vault_key)
    assert decrypted == plaintext


def test_no_plaintext_bytes_anywhere_under_recovery_root(tmp_path):
    """Frozen invariant §9.1: no plaintext artifact is ever written to disk,
    including a temp path."""
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    marker = b"UNMISTAKABLE-PLAINTEXT-MARKER-0xDEADBEEF" * 20

    recovery_store.write_artifact(
        recovery, vault_key=vault_key, vault_key_id=vault_key_id,
        device=_device(), artifact_class="cp_gaia_backup", plaintext=marker,
        vendor_native_filename="backup.tgz", collected_via="cp_ssh_scp_fetch",
    )

    for path in recovery.recovery_root.rglob("*"):
        if path.is_file():
            assert marker not in path.read_bytes(), f"plaintext leaked into {path}"


def test_no_plaintext_key_material_in_manifest(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    result = recovery_store.write_artifact(
        recovery, vault_key=vault_key, vault_key_id=vault_key_id,
        device=_device(), artifact_class="cp_gaia_backup", plaintext=b"x" * 10,
        vendor_native_filename="backup.tgz", collected_via="cp_ssh_scp_fetch",
    )
    manifest_text = json.dumps(result.manifest)
    assert vault_key.hex() not in manifest_text


def test_list_artifact_dirs_filters_by_vendor_and_entity(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    for entity in ("fw-01", "fw-02"):
        recovery_store.write_artifact(
            recovery, vault_key=vault_key, vault_key_id=vault_key_id,
            device=_device(entity_id=entity), artifact_class="cp_gaia_backup", plaintext=b"x",
            vendor_native_filename="backup.tgz", collected_via="cp_ssh_scp_fetch",
        )
    all_dirs = recovery_store.list_artifact_dirs(recovery)
    assert len(all_dirs) == 2
    only_fw01 = recovery_store.list_artifact_dirs(recovery, entity_id="fw-01")
    assert len(only_fw01) == 1
    assert "fw-01" in str(only_fw01[0])


def test_consistency_group_round_trip(tmp_path):
    _, recovery = _paths(tmp_path)
    written = recovery_store.write_consistency_group(
        recovery, "mgmt-ha-01", members=["mgmt-a", "mgmt-b"], status="PENDING"
    )
    read_back = recovery_store.read_consistency_group(recovery, "mgmt-ha-01")
    assert read_back == written
    assert read_back["members"] == ["mgmt-a", "mgmt-b"]


# --- recovery_retention: §9.9 floor invariant --------------------------------

def test_plan_deletions_respects_daily_limit():
    held = {
        "fw-01": [
            {"artifact_id": f"a{i}", "is_rma_grade": True, "tier": "daily", "age_rank": i}
            for i in range(10)
        ]
    }
    policy = dict(recovery_retention.DEFAULT_POLICY, daily=7, weekly=0, monthly=0)
    policy["floor"] = {"never_reduce_device_below": 0, "never_delete_only_rma_grade": False}
    candidates = recovery_retention.plan_deletions(held, policy)
    assert len(candidates) == 3
    assert all(c["entity_id"] == "fw-01" for c in candidates)


def test_plan_deletions_floor_never_reduces_below_minimum():
    held = {"fw-01": [
        {"artifact_id": "a0", "is_rma_grade": False, "tier": "daily", "age_rank": 0},
        {"artifact_id": "a1", "is_rma_grade": False, "tier": "daily", "age_rank": 1},
    ]}
    policy = dict(recovery_retention.DEFAULT_POLICY, daily=0, weekly=0, monthly=0)
    policy["floor"] = {"never_reduce_device_below": 1, "never_delete_only_rma_grade": False}
    candidates = recovery_retention.plan_deletions(held, policy)
    assert len(candidates) == 1  # not both, even though both are "over limit"


def test_plan_deletions_never_deletes_the_sole_rma_grade_artifact():
    held = {"fw-01": [
        {"artifact_id": "rma", "is_rma_grade": True, "tier": "daily", "age_rank": 0},
        {"artifact_id": "non-rma-1", "is_rma_grade": False, "tier": "daily", "age_rank": 1},
        {"artifact_id": "non-rma-2", "is_rma_grade": False, "tier": "daily", "age_rank": 2},
    ]}
    policy = dict(recovery_retention.DEFAULT_POLICY, daily=0, weekly=0, monthly=0)
    policy["floor"] = {"never_reduce_device_below": 0, "never_delete_only_rma_grade": True}
    candidates = recovery_retention.plan_deletions(held, policy)
    deleted_ids = {c["artifact_id"] for c in candidates}
    assert "rma" not in deleted_ids
    assert deleted_ids == {"non-rma-1", "non-rma-2"}


def test_plan_deletions_floor_property_randomized():
    """§9.9 property test: for many random fleets/policies, retention must
    never drive an entity below its floor or delete the sole RMA-grade
    artifact."""
    rng = random.Random(20260830)
    for _ in range(200):
        min_held = rng.randint(0, 3)
        protect_rma = rng.choice([True, False])
        n_artifacts = rng.randint(0, 8)
        artifacts = []
        for i in range(n_artifacts):
            artifacts.append({
                "artifact_id": f"a{i}",
                "is_rma_grade": rng.choice([True, False]),
                "tier": rng.choice(["daily", "weekly", "monthly"]),
                "age_rank": i,
            })
        held = {"fw-01": artifacts}
        policy = {
            "daily": rng.randint(0, 3), "weekly": rng.randint(0, 3), "monthly": rng.randint(0, 3),
            "floor": {"never_reduce_device_below": min_held, "never_delete_only_rma_grade": protect_rma},
        }
        candidates = recovery_retention.plan_deletions(held, policy)

        remaining = n_artifacts - len(candidates)
        # A fleet that already holds fewer artifacts than the floor demands
        # can never be brought UP to the floor by retention -- the invariant
        # is "never REDUCE below it", so the achievable bound is the floor
        # capped by what actually exists.
        assert remaining >= min(min_held, n_artifacts), (policy, artifacts, candidates)

        rma_before = sum(1 for a in artifacts if a["is_rma_grade"])
        rma_deleted = sum(1 for c in candidates if c["is_rma_grade"])
        if protect_rma and rma_before >= 1:
            assert rma_before - rma_deleted >= 1, (policy, artifacts, candidates)


def test_apply_deletions_dry_run_touches_nothing(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    result = recovery_store.write_artifact(
        recovery, vault_key=vault_key, vault_key_id=vault_key_id,
        device=_device(), artifact_class="cp_gaia_backup", plaintext=b"x",
        vendor_native_filename="backup.tgz", collected_via="cp_ssh_scp_fetch",
    )
    candidate = [{"entity_id": "fw-01", "artifact_id": result.manifest["artifact_id"],
                  "is_rma_grade": True, "tier": "daily"}]
    tombstones = recovery_retention.apply_deletions(
        recovery, candidate, artifact_dirs={result.manifest["artifact_id"]: result.artifact_dir},
        policy_name="gfs-default", operator="test", apply=False,
    )
    assert len(tombstones) == 1
    assert result.artifact_dir.is_dir()  # untouched
    assert recovery_retention.read_ledger(recovery) == []  # untouched


def test_apply_deletions_apply_removes_artifact_and_appends_ledger(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    result = recovery_store.write_artifact(
        recovery, vault_key=vault_key, vault_key_id=vault_key_id,
        device=_device(), artifact_class="cp_gaia_backup", plaintext=b"x",
        vendor_native_filename="backup.tgz", collected_via="cp_ssh_scp_fetch",
    )
    artifact_id = result.manifest["artifact_id"]
    candidate = [{"entity_id": "fw-01", "artifact_id": artifact_id, "is_rma_grade": True, "tier": "daily"}]
    tombstones = recovery_retention.apply_deletions(
        recovery, candidate, artifact_dirs={artifact_id: result.artifact_dir},
        policy_name="gfs-default", operator="test", apply=True,
    )
    assert len(tombstones) == 1
    assert not result.artifact_dir.exists()
    ledger = recovery_retention.read_ledger(recovery)
    assert len(ledger) == 1
    assert ledger[0]["artifact_id"] == artifact_id
    assert ledger[0]["entity_id"] == "fw-01"


# --- §9.4: recovery payload never reaches the support bundle -----------------

def test_support_bundle_never_includes_recovery_payload(tmp_path):
    runtime, recovery = _paths(tmp_path)
    vault_key, vault_key_id = recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    marker = b"RECOVERY-ARTIFACT-MUST-NEVER-LEAK-INTO-BUNDLE"
    recovery_store.write_artifact(
        recovery, vault_key=vault_key, vault_key_id=vault_key_id,
        device=_device(), artifact_class="cp_gaia_backup", plaintext=marker,
        vendor_native_filename="backup.tgz", collected_via="cp_ssh_scp_fetch",
    )

    # A minimal, isolated evidence-plane run to build a real bundle from.
    data_root = tmp_path / "bundle_data"
    run_dir = data_root / "runs" / "20260101_000000_deadbeef"
    stage = run_dir / "stage"
    stage.mkdir(parents=True)
    for name in ("cp.json", "vsx.json", "panorama_runtime.json", "unified.json"):
        (stage / name).write_text("[]", encoding="utf-8")
    (run_dir / "verification.json").write_text(json.dumps({"run_status": "success", "sources": {}}), encoding="utf-8")

    bundle_path = support_bundle.run_support_bundle(
        run_dir, data_root=data_root, output_root=tmp_path / "bundle_output"
    )

    with zipfile.ZipFile(bundle_path) as zf:
        for name in zf.namelist():
            content = zf.read(name)
            assert marker not in content
            assert str(recovery.recovery_root) not in content.decode("utf-8", errors="replace")


# --- §9.5: privacy gate extension --------------------------------------------

def test_privacy_gate_flags_enc_files(tmp_path):
    (tmp_path / "leaked.enc").write_bytes(b"not really encrypted, just a probe")
    report = scan_repository(tmp_path)
    rules = {f.rule for f in report.findings}
    assert "PRIVATE_OR_TRUST_MATERIAL" in rules


def test_privacy_gate_flags_vault_root_dir(tmp_path):
    (tmp_path / "vault").mkdir()
    (tmp_path / "vault" / "manifest.json").write_text("{}", encoding="utf-8")
    report = scan_repository(tmp_path)
    rules = {f.rule for f in report.findings}
    assert "RUNTIME_DIRECTORY_PRESENT" in rules


# --- §9.11: nginx never mounts the recovery volume ---------------------------

def _yaml_service_block(text, service_name):
    """Extract a top-level (2-space-indented) Compose service's own lines,
    stopping at the next 2-space-indented key. No PyYAML dependency; this
    repo's compose files are simple enough for indentation-based slicing."""
    lines = text.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line == f"  {service_name}:":
            start = i + 1
            break
    if start is None:
        return None
    block = []
    for line in lines[start:]:
        if line and not line.startswith("   ") and not line.startswith("\t"):
            break  # next top-level service/section
        block.append(line)
    return "\n".join(block)


def test_nginx_service_never_mounts_recovery_volume():
    import pathlib
    repo_root = pathlib.Path(__file__).resolve().parent.parent
    for compose_file in ("docker-compose.yml", "docker-compose.prod.yml"):
        text = (repo_root / compose_file).read_text(encoding="utf-8")
        nginx_block = _yaml_service_block(text, "nginx")
        if nginx_block is None:
            continue
        # Only actual mapping/list content matters -- strip comment lines so
        # this doesn't false-positive on prose explaining the invariant.
        code_lines = [
            line for line in nginx_block.splitlines() if line.strip() and not line.strip().startswith("#")
        ]
        nginx_code = "\n".join(code_lines)
        assert "recovery" not in nginx_code.lower(), (
            f"{compose_file}: nginx service must never mount the recovery volume (contract §9.11)\n{nginx_code}"
        )


# --- CLI integration ---------------------------------------------------------

def test_recovery_store_check_cli_reports_empty_store(tmp_path, capsys):
    runtime_root = tmp_path / "runtime"
    recovery_root = tmp_path / "recovery"
    main.main([
        "--runtime-root", str(runtime_root),
        "--recovery-root", str(recovery_root),
        "--recovery-store-check",
    ])  # no SystemExit
    out = capsys.readouterr().out
    assert "Artifacts held:          0" in out
    assert "Gate:                    PASS" in out


def test_recovery_store_check_cli_requires_recovery_root(tmp_path, capsys):
    runtime_root = tmp_path / "runtime"
    with pytest.raises(SystemExit) as exc:
        main.main(["--runtime-root", str(runtime_root), "--recovery-store-check"])
    assert exc.value.code == 2
    assert "SECURITYEXPERT_RECOVERY_ROOT" in capsys.readouterr().err
