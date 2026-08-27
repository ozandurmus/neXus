import json
from pathlib import Path

import pytest

from checkpoint.cp_runner import _remote_collection_command
from utils.inventory_exclusions import (
    InventoryExclusionPolicyError,
    checkpoint_transport_value,
    load_inventory_exclusions,
    policy_path,
)
from utils.repository_privacy import scan_repository


def _write_policy(data_root: Path, exclusions):
    path = policy_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"version": 1, "exclusions": exclusions}), encoding="utf-8")
    return path


def test_missing_policy_is_empty_and_does_not_create_runtime_state(tmp_path):
    data_root = tmp_path / "data"
    policy = load_inventory_exclusions(data_root)
    assert policy.source == "missing"
    assert policy.identities_for("checkpoint") == ()
    assert not policy_path(data_root).exists()


def test_vendor_neutral_policy_filters_enabled_checkpoint_entries(tmp_path):
    _write_policy(tmp_path, [
        {"vendor": "checkpoint", "identity": "CP-NONDEVICE-A", "enabled": True, "reason": "not_device"},
        {"vendor": "checkpoint", "identity": "CP-NONDEVICE-B", "enabled": False, "reason": "review"},
        {"vendor": "paloalto", "identity": "PAN-NONDEVICE-A", "enabled": True, "reason": "not_device"},
        {"vendor": "checkpoint", "identity": "CP-NONDEVICE-A", "enabled": True, "reason": "duplicate"},
    ])
    policy = load_inventory_exclusions(tmp_path)
    assert policy.source == "runtime-policy"
    assert policy.identities_for("checkpoint") == ("CP-NONDEVICE-A",)
    assert policy.identities_for("paloalto") == ("PAN-NONDEVICE-A",)


def test_malformed_policy_fails_closed_without_identity_echo(tmp_path):
    path = policy_path(tmp_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('{"version": 99, "exclusions": []}', encoding="utf-8")
    with pytest.raises(InventoryExclusionPolicyError) as exc:
        load_inventory_exclusions(tmp_path)
    assert "unsupported schema" in str(exc.value)


def test_checkpoint_transport_rejects_ambiguous_or_multiline_identity():
    with pytest.raises(InventoryExclusionPolicyError):
        checkpoint_transport_value(["CP,OBJECT"])
    with pytest.raises(InventoryExclusionPolicyError):
        checkpoint_transport_value(["CP\nOBJECT"])


def test_remote_command_quotes_runtime_identity_and_preserves_exact_match_transport():
    command = _remote_collection_command(
        exclude_vsx=True,
        excluded_device_names=("CP OBJECT A", "CP-OBJECT-B"),
    )
    assert "SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES=" in command
    assert "SECURITYEXPERT_CP_EXCLUDE_VSX=1" in command
    assert "CP OBJECT A,CP-OBJECT-B" in command
    assert command.endswith("bash -l /home/admin/cp_inventory.sh")


def test_repository_candidate_has_no_environment_specific_exclusion_default():
    root = Path(__file__).resolve().parents[1]
    script = (root / "checkpoint" / "scripts" / "cp_inventory.sh").read_text(encoding="utf-8")
    assert 'SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES="${SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES:-}"' in script
    report = scan_repository(root)
    assert not any(f.rule == "ENVIRONMENT_IDENTITY_LITERAL" for f in report.findings)
