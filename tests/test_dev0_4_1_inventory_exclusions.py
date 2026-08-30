import json
from pathlib import Path

import pytest

from checkpoint.cp_runner import _remote_collection_command
from utils.inventory_exclusions import (
    InventoryExclusionPolicyError,
    add_exclusion,
    audit_path,
    checkpoint_transport_value,
    load_inventory_exclusions,
    load_inventory_exclusions_audit,
    policy_path,
    restore_exclusion,
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


# ---------------------------------------------------------------------------
# inventory_exclusions_management_ui: write-path backend logic only.
# Not wired into any UI or HTTP-reachable surface -- see the module docstring.
# ---------------------------------------------------------------------------

def test_add_exclusion_creates_a_new_enabled_entry(tmp_path):
    policy = add_exclusion(tmp_path, vendor="checkpoint", identity="CP-NEW-A", reason="not a firewall")
    assert policy.source == "runtime-policy"
    assert policy.identities_for("checkpoint") == ("CP-NEW-A",)

    raw = json.loads(policy_path(tmp_path).read_text(encoding="utf-8"))
    assert raw["exclusions"] == [
        {"vendor": "checkpoint", "identity": "CP-NEW-A", "reason": "not a firewall", "enabled": True}
    ]


def test_add_exclusion_requires_a_non_empty_reason(tmp_path):
    with pytest.raises(InventoryExclusionPolicyError, match="reason is required"):
        add_exclusion(tmp_path, vendor="checkpoint", identity="CP-NEW-A", reason="")
    with pytest.raises(InventoryExclusionPolicyError, match="reason is required"):
        add_exclusion(tmp_path, vendor="checkpoint", identity="CP-NEW-A", reason="   ")
    # No policy file created on validation failure.
    assert not policy_path(tmp_path).exists()


def test_add_exclusion_rejects_invalid_identity(tmp_path):
    with pytest.raises(InventoryExclusionPolicyError):
        add_exclusion(tmp_path, vendor="checkpoint", identity="", reason="manual")
    with pytest.raises(InventoryExclusionPolicyError):
        add_exclusion(tmp_path, vendor="checkpoint", identity="bad\nname", reason="manual")


def test_add_exclusion_on_existing_identity_is_idempotent_and_updates_reason(tmp_path):
    add_exclusion(tmp_path, vendor="checkpoint", identity="CP-DUP", reason="first reason")
    policy = add_exclusion(tmp_path, vendor="checkpoint", identity="CP-DUP", reason="second reason")
    assert policy.identities_for("checkpoint") == ("CP-DUP",)  # not duplicated
    entry = next(e for e in policy.entries if e.identity == "CP-DUP")
    assert entry.reason == "second reason"

    raw = json.loads(policy_path(tmp_path).read_text(encoding="utf-8"))
    assert len(raw["exclusions"]) == 1


def test_add_exclusion_re_enables_a_previously_restored_identity(tmp_path):
    add_exclusion(tmp_path, vendor="checkpoint", identity="CP-CYCLE", reason="first exclude")
    restore_exclusion(tmp_path, vendor="checkpoint", identity="CP-CYCLE", reason="was a mistake")
    assert load_inventory_exclusions(tmp_path).identities_for("checkpoint") == ()

    policy = add_exclusion(tmp_path, vendor="checkpoint", identity="CP-CYCLE", reason="excluding again")
    assert policy.identities_for("checkpoint") == ("CP-CYCLE",)
    raw = json.loads(policy_path(tmp_path).read_text(encoding="utf-8"))
    assert len(raw["exclusions"]) == 1  # re-enabled in place, not a second row


def test_restore_exclusion_soft_disables_not_deletes(tmp_path):
    add_exclusion(tmp_path, vendor="checkpoint", identity="CP-RESTORE-ME", reason="temporary")
    policy = restore_exclusion(tmp_path, vendor="checkpoint", identity="CP-RESTORE-ME", reason="back in scope")
    assert policy.identities_for("checkpoint") == ()  # no longer applied

    raw = json.loads(policy_path(tmp_path).read_text(encoding="utf-8"))
    assert len(raw["exclusions"]) == 1  # row preserved, not removed
    assert raw["exclusions"][0]["enabled"] is False
    assert raw["exclusions"][0]["reason"] == "back in scope"


def test_restore_exclusion_requires_a_non_empty_reason(tmp_path):
    add_exclusion(tmp_path, vendor="checkpoint", identity="CP-X", reason="manual")
    with pytest.raises(InventoryExclusionPolicyError, match="reason is required"):
        restore_exclusion(tmp_path, vendor="checkpoint", identity="CP-X", reason="")


def test_restore_exclusion_raises_when_nothing_to_restore(tmp_path):
    with pytest.raises(InventoryExclusionPolicyError, match="no active exclusion found"):
        restore_exclusion(tmp_path, vendor="checkpoint", identity="CP-NEVER-EXCLUDED", reason="n/a")
    # Restoring an already-restored (disabled) entry is also "nothing to restore".
    add_exclusion(tmp_path, vendor="checkpoint", identity="CP-Y", reason="manual")
    restore_exclusion(tmp_path, vendor="checkpoint", identity="CP-Y", reason="first restore")
    with pytest.raises(InventoryExclusionPolicyError, match="no active exclusion found"):
        restore_exclusion(tmp_path, vendor="checkpoint", identity="CP-Y", reason="second restore")


def test_add_and_restore_append_audit_entries(tmp_path):
    add_exclusion(tmp_path, vendor="checkpoint", identity="CP-AUDIT", reason="excluded for test")
    restore_exclusion(tmp_path, vendor="checkpoint", identity="CP-AUDIT", reason="restored for test")

    audit = load_inventory_exclusions_audit(tmp_path)
    assert len(audit) == 2
    assert audit[0].action == "added"
    assert audit[0].identity == "CP-AUDIT"
    assert audit[0].reason == "excluded for test"
    assert audit[0].actor is None
    assert audit[1].action == "restored"
    assert audit[1].reason == "restored for test"


def test_audit_entry_records_actor_when_provided(tmp_path):
    add_exclusion(tmp_path, vendor="checkpoint", identity="CP-A", reason="manual", actor="oidc:jdoe@example.test")
    audit = load_inventory_exclusions_audit(tmp_path)
    assert audit[0].actor == "oidc:jdoe@example.test"


def test_audit_log_read_is_fail_safe_on_corruption(tmp_path):
    path = audit_path(tmp_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("not valid json", encoding="utf-8")
    assert load_inventory_exclusions_audit(tmp_path) == []


def test_audit_log_caps_at_max_entries(tmp_path, monkeypatch):
    import utils.inventory_exclusions as ie
    monkeypatch.setattr(ie, "MAX_AUDIT_ENTRIES", 3)
    for i in range(5):
        add_exclusion(tmp_path, vendor="checkpoint", identity=f"CP-{i}", reason=f"reason {i}")
    audit = load_inventory_exclusions_audit(tmp_path)
    assert len(audit) == 3
    # Newest kept, oldest trimmed.
    assert [e.identity for e in audit] == ["CP-2", "CP-3", "CP-4"]


def test_failed_audit_write_leaves_the_policy_file_untouched(tmp_path, monkeypatch):
    """Fail-closed ordering: if the audit append raises, the policy file must
    not have been written at all for that call."""
    import utils.inventory_exclusions as ie

    def _boom(*_args, **_kwargs):
        raise OSError("simulated audit write failure")

    monkeypatch.setattr(ie, "_append_audit_entry", _boom)
    with pytest.raises(OSError):
        add_exclusion(tmp_path, vendor="checkpoint", identity="CP-NEVER-WRITTEN", reason="manual")
    assert not policy_path(tmp_path).exists()


def test_write_path_functions_are_not_wired_into_any_http_reachable_surface():
    """inventory_exclusions_management_ui is DEPLOY.1A-gated: these functions
    must exist without being callable from main.py, html_export.py or any
    other module that runs during a normal report render, until the
    OIDC/RBAC boundary exists.
    """
    root = Path(__file__).resolve().parents[1]
    for relative in ("main.py", "utils/html_export.py", "utils/inventory_exclusions_ui.py"):
        source = (root / relative).read_text(encoding="utf-8")
        assert "add_exclusion" not in source, f"{relative} must not call add_exclusion yet"
        assert "restore_exclusion" not in source, f"{relative} must not call restore_exclusion yet"
