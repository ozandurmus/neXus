"""RB.3a — CP Gaia backup/snapshot attestation.

Contract: docs/history/phase/RB_3A_CP_GAIA_BACKUP_ATTESTATION.md
(frozen 2026-08-31); command gate docs/design/BACKUP_RECOVERY_CONTRACTS.md §7.5.

Covers acceptance criteria AC-1 … AC-10. No live Check Point device is
reachable here: the SSH-facing `CheckpointRecoveryAttester.attest` path is
exercised only through its pure listing parser and its local platform gate;
the orchestration, VSX/platform exclusion, batch semantics and the readiness
consumer are exercised with a fake attester.
"""
from datetime import date

import pytest

import main
from utils.collection_executor import ALLOWLISTED_WORKFLOWS, SchedulerPolicyError, load_scheduler_policy
from utils.recovery_collect import (
    RecoveryAttestationError,
    RecoveryCollectionError,
    RecoveryCollectionRequest,
    run_recovery_attestation,
)
from utils.restore_readiness import compute_restore_readiness
from checkpoint.checkpoint_recovery_attestation import (
    _ATTESTATION_COMMANDS,
    CheckpointRecoveryAttester,
    _wire_forms,
    parse_gaia_listing,
)

pytestmark = pytest.mark.recovery


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------

def _unified():
    return [
        {"source": "cp", "device": "fw-01", "management_ip": "192.0.2.1",
         "inventory_status": {"data_state": "live"}},
        {"source": "vsx", "device": "vsx-01", "vsys": "", "management_ip": "192.0.2.2",
         "inventory_status": {"data_state": "live"}},
        {"source": "vsx", "device": "vsx-01", "vsys": "VS-A", "vs_id": "10",
         "inventory_status": {"data_state": "live"}},
        {"source": "vsx", "device": "vsx-01", "vsys": "VS-B", "vs_id": "20",
         "inventory_status": {"data_state": "live"}},
    ]


class _FakeAttester:
    """Implements the `RecoveryAttester` protocol without SSH."""

    def __init__(self, *, records_by_entity=None, unsupported=(), fail=()):
        self.records_by_entity = dict(records_by_entity or {})
        self.unsupported = set(unsupported)
        self.fail = set(fail)
        self.classify_calls: list[str] = []
        self.attest_calls: list[str] = []

    def classify_target(self, target):
        self.classify_calls.append(target.entity_id)
        return "unsupported" if target.entity_id in self.unsupported else "supported"

    def attest(self, target):
        self.attest_calls.append(target.entity_id)
        if target.entity_id in self.fail:
            raise RecoveryAttestationError(f"{target.entity_id}: simulated session failure")
        return list(self.records_by_entity.get(target.entity_id, []))


class _Cfg:
    class auth:
        principal = "svc-user"
        secret = "svc-secret"


@pytest.fixture(autouse=True)
def _no_cp_ssh_env(monkeypatch):
    for var in (
        "SECURITYEXPERT_CP_CONFIG_SSH_USERNAME",
        "SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD",
        "SECURITYEXPERT_CP_CONFIG_SSH_STRICT_HOST_KEY",
    ):
        monkeypatch.delenv(var, raising=False)


# --------------------------------------------------------------------------
# AC-1 — the bounded, fail-closed listing parser
# --------------------------------------------------------------------------

_NOW = date(2024, 3, 1)


def test_ac1_parser_ctime_format():
    stdout = (
        "Backups:\n"
        "backup_SECRETHOST_10_2_2024.tgz  Sat Feb 10 14:30:00 2024   1024 KB\n"
    )
    parsed = parse_gaia_listing(stdout, artifact_class="cp_gaia_backup", now=_NOW)
    assert parsed.status == "parsed"
    assert parsed.records == [
        {"class": "cp_gaia_backup", "age_days": 20, "source": "device_reported"}
    ]


def test_ac1_parser_iso_date_format():
    stdout = (
        "The following snapshots are available:\n"
        "  FirstImage        Created on: 2024-01-05 08:14:22\n"
        "  SecondImage       2024-02-20\n"
    )
    parsed = parse_gaia_listing(stdout, artifact_class="cp_gaia_snapshot", now=_NOW)
    assert parsed.status == "parsed"
    assert [r["age_days"] for r in parsed.records] == [56, 10]
    assert {r["class"] for r in parsed.records} == {"cp_gaia_snapshot"}


def test_ac1_parser_empty_listing_yields_no_records():
    parsed = parse_gaia_listing("Snapshots:\n", artifact_class="cp_gaia_snapshot", now=_NOW)
    assert parsed.records == []
    assert parsed.status == "empty_listing"

    parsed2 = parse_gaia_listing(
        "There are no snapshots on this machine.\n",
        artifact_class="cp_gaia_snapshot", now=_NOW,
    )
    assert parsed2.records == []
    assert parsed2.status == "empty_listing"


def test_ac1_parser_error_response_yields_no_records():
    for stdout in (
        "show backups\nPermission denied.\n",
        "CLISH:  Invalid command:'show backups'\n",
    ):
        parsed = parse_gaia_listing(stdout, artifact_class="cp_gaia_backup", now=_NOW)
        assert parsed.records == []
        assert parsed.status == "cli_error"


def test_ac1_parser_undatable_entry_is_kept_with_null_age():
    stdout = "Snapshots:\npreUpgradeBaseline\nBLUE_snap Fri Feb 23 09:00:00 2024\n"
    parsed = parse_gaia_listing(stdout, artifact_class="cp_gaia_snapshot", now=_NOW)
    assert parsed.status == "parsed"
    ages = [r["age_days"] for r in parsed.records]
    assert ages == [None, 7]
    assert all(r["source"] == "device_reported" for r in parsed.records)


# --------------------------------------------------------------------------
# AC-2 — VSX: physical endpoint once, VS entities untouched
# --------------------------------------------------------------------------

def test_ac2_vsx_host_contacted_once_vs_entities_stay_unprotected():
    attester = _FakeAttester(records_by_entity={
        "fw-01": [{"class": "cp_gaia_backup", "age_days": 3, "source": "device_reported"}],
        "vsx-01": [{"class": "cp_gaia_snapshot", "age_days": 41, "source": "device_reported"}],
    })
    request = RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "all"})
    result = run_recovery_attestation(request, unified_devices=_unified(), attester=attester)

    # host entity contacted exactly once; neither VS entity contacted
    assert attester.attest_calls == ["fw-01", "vsx-01"]
    assert {o.entity_id for o in result.outcomes} == {"fw-01", "vsx-01"}

    readiness = compute_restore_readiness(_unified(), attestations=result.as_attestation_map())
    by_id = {d["entity_id"]: d for d in readiness["devices"]}
    assert by_id["vsx-01__vsid_10"]["state"] == "UNPROTECTED"
    assert by_id["vsx-01__vsid_20"]["state"] == "UNPROTECTED"
    assert by_id["vsx-01"]["state"] == "PARTIAL"


def test_ac2_explicit_vs_target_is_reported_skipped_not_silently_dropped():
    attester = _FakeAttester()
    request = RecoveryCollectionRequest(
        vendor="checkpoint",
        selector={"mode": "targets", "entity_ids": ["fw-01", "vsx-01__vsid_10"]},
    )
    result = run_recovery_attestation(request, unified_devices=_unified(), attester=attester)
    statuses = {o.entity_id: o.status for o in result.outcomes}
    assert statuses["vsx-01__vsid_10"] == "skipped_virtual_system"
    assert attester.attest_calls == ["fw-01"]


# --------------------------------------------------------------------------
# AC-3 — store isolation
# --------------------------------------------------------------------------

def test_ac3_attestation_run_never_touches_the_recovery_store(tmp_path):
    from utils.runtime_paths import resolve_recovery_root, resolve_runtime_paths
    from utils import recovery_store

    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("", encoding="utf-8")
    runtime = resolve_runtime_paths(str(tmp_path / "runtime"), environ={}, repository_root=repo)
    recovery = resolve_recovery_root(
        str(tmp_path / "recovery"), environ={}, repository_root=repo, runtime_root=runtime.runtime_root
    )
    recovery_store.get_or_create_vault_key(runtime.data_root, recovery.recovery_root)
    before = sorted(p.name for p in recovery.recovery_root.rglob("*"))

    attester = _FakeAttester(records_by_entity={
        "fw-01": [{"class": "cp_gaia_backup", "age_days": 1, "source": "device_reported"}],
    })
    run_recovery_attestation(
        RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "all"}),
        unified_devices=_unified(), attester=attester,
    )

    assert recovery_store.list_artifact_dirs(recovery) == []
    assert sorted(p.name for p in recovery.recovery_root.rglob("*")) == before


# --------------------------------------------------------------------------
# AC-4 — readiness: PARTIAL, never READY; a held artifact still outranks
# --------------------------------------------------------------------------

def test_ac4_attestation_only_is_partial_never_ready():
    attestations = {"fw-01": [{"class": "cp_gaia_backup", "age_days": 5, "source": "device_reported"}]}
    report = compute_restore_readiness(_unified(), attestations=attestations)
    dev = {d["entity_id"]: d for d in report["devices"]}["fw-01"]
    assert dev["state"] == "PARTIAL"
    assert dev["reason"] == "only_device_attested_artifact_no_held_copy"
    assert dev["evidence_basis"] == "device_attestation"


def test_ac4_held_validated_artifact_outranks_an_attestation_for_same_entity():
    manifests = {"fw-01": [{"class": "cp_gaia_backup", "age_days": 1,
                            "validation_level": "V3", "version_matches_running": True}]}
    attestations = {"fw-01": [{"class": "cp_gaia_snapshot", "age_days": 41, "source": "device_reported"}]}
    report = compute_restore_readiness(_unified(), recovery_manifests=manifests, attestations=attestations)
    dev = {d["entity_id"]: d for d in report["devices"]}["fw-01"]
    assert dev["state"] == "READY"
    assert dev["evidence_basis"] == "recovery_manifest"
    assert len(dev["attested_not_held"]) == 1


def test_ac4_null_age_days_attestation_still_classifies_partial():
    attestations = {"fw-01": [{"class": "cp_gaia_snapshot", "age_days": None, "source": "device_reported"}]}
    report = compute_restore_readiness(_unified(), attestations=attestations)
    dev = {d["entity_id"]: d for d in report["devices"]}["fw-01"]
    assert dev["state"] == "PARTIAL"
    assert dev["attested_not_held"][0]["age_days"] is None


# --------------------------------------------------------------------------
# AC-5 — no parsed name reaches a record or the state file
# --------------------------------------------------------------------------

def test_ac5_parsed_name_never_appears_in_records():
    stdout = (
        "Backups:\n"
        "backup_prod-fw-fqdn-01_2_10_2024.tgz  Sat Feb 10 14:30:00 2024\n"
    )
    parsed = parse_gaia_listing(stdout, artifact_class="cp_gaia_backup", now=_NOW)
    blob = repr(parsed.records)
    assert "prod-fw-fqdn-01" not in blob
    assert ".tgz" not in blob
    assert set(parsed.records[0]) == {"class", "age_days", "source"}


def test_ac5_state_file_written_by_main_carries_no_name(tmp_path, monkeypatch):
    # The map that main.py persists is built only from RecoveryAttestationResult
    # records, which are nameless by construction.
    attester = _FakeAttester(records_by_entity={
        "fw-01": [{"class": "cp_gaia_backup", "age_days": 9, "source": "device_reported"}],
    })
    result = run_recovery_attestation(
        RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "all"}),
        unified_devices=_unified(), attester=attester,
    )
    import json
    text = json.dumps(result.as_attestation_map())
    assert "backup" not in text.replace("cp_gaia_backup", "")  # only the class token
    assert result.as_attestation_map() == {
        "fw-01": [{"class": "cp_gaia_backup", "age_days": 9, "source": "device_reported"}]
    }


# --------------------------------------------------------------------------
# AC-6 — the frozen command guard
# --------------------------------------------------------------------------

def test_ac6_command_tuple_is_frozen_and_guarded():
    assert _ATTESTATION_COMMANDS == ("show backups", "show snapshots")


@pytest.mark.parametrize("bad", ["show version", "show configuration", "add backup local", "show backup"])
def test_ac6_non_attestation_command_raises_before_the_wire(bad):
    with pytest.raises(ValueError, match="frozen attestation set"):
        _wire_forms(bad)


def test_ac6_frozen_commands_produce_direct_and_wrapped_forms():
    for command in _ATTESTATION_COMMANDS:
        direct, wrapped = _wire_forms(command)
        assert direct == command
        assert wrapped == f"clish -c '{command}'"


# --------------------------------------------------------------------------
# AC-7 — platform gate: Spark / Gaia Embedded, zero commands
# --------------------------------------------------------------------------

def test_ac7_spark_endpoint_classified_unsupported():
    attester = CheckpointRecoveryAttester(_Cfg(), platform_by_entity={"fw-01": "gaia_embedded"})
    from utils.recovery_collect import RecoveryCollectionTarget
    target = RecoveryCollectionTarget(entity_id="fw-01", vendor="checkpoint", row={})
    assert attester.classify_target(target) == "unsupported"


def test_ac7_unsupported_endpoint_gets_no_attest_call():
    attester = _FakeAttester(unsupported={"fw-01"})
    result = run_recovery_attestation(
        RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "all"}),
        unified_devices=_unified(), attester=attester,
    )
    statuses = {o.entity_id: o.status for o in result.outcomes}
    assert statuses["fw-01"] == "unsupported"
    assert "fw-01" not in attester.attest_calls
    assert result.as_attestation_map() == {}


def test_ac7_unknown_platform_is_attested_normally():
    attester = CheckpointRecoveryAttester(_Cfg(), platform_by_entity={"fw-01": "unknown"})
    from utils.recovery_collect import RecoveryCollectionTarget
    target = RecoveryCollectionTarget(entity_id="fw-01", vendor="checkpoint", row={})
    assert attester.classify_target(target) == "supported"


# --------------------------------------------------------------------------
# AC-8 — batch: one endpoint's failure does not abort the rest
# --------------------------------------------------------------------------

def _unified_three():
    return [
        {"source": "cp", "device": f"fw-0{n}", "management_ip": f"192.0.2.{n}",
         "inventory_status": {"data_state": "live"}}
        for n in (1, 2, 3)
    ]


def test_ac8_middle_endpoint_admission_failure_is_isolated():
    attester = _FakeAttester(records_by_entity={
        "fw-01": [{"class": "cp_gaia_backup", "age_days": 2, "source": "device_reported"}],
        "fw-03": [{"class": "cp_gaia_snapshot", "age_days": 8, "source": "device_reported"}],
    })

    def run_under_admission(entity_id, operation):
        if entity_id == "fw-02":
            raise RuntimeError("endpoint_lock_conflict")
        return operation()

    result = run_recovery_attestation(
        RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "all"}),
        unified_devices=_unified_three(), attester=attester,
        run_under_admission=run_under_admission,
    )
    statuses = {o.entity_id: o.status for o in result.outcomes}
    assert statuses == {"fw-01": "attested", "fw-02": "failed", "fw-03": "attested"}
    assert result.attested_count == 2
    assert result.failed_count == 1
    assert set(result.as_attestation_map()) == {"fw-01", "fw-03"}
    assert attester.attest_calls == ["fw-01", "fw-03"]  # fw-02 never reached the attester


def test_ac8_session_failure_is_recorded_and_batch_continues():
    attester = _FakeAttester(
        records_by_entity={"fw-03": [{"class": "cp_gaia_backup", "age_days": 1, "source": "device_reported"}]},
        fail={"fw-01"},
    )
    result = run_recovery_attestation(
        RecoveryCollectionRequest(vendor="checkpoint", selector={"mode": "all"}),
        unified_devices=_unified_three(), attester=attester,
    )
    statuses = {o.entity_id: o.status for o in result.outcomes}
    assert statuses["fw-01"] == "failed"
    assert statuses["fw-03"] == "attested"


def test_unresolvable_explicit_target_raises_before_any_contact():
    attester = _FakeAttester()
    request = RecoveryCollectionRequest(
        vendor="checkpoint",
        selector={"mode": "targets", "entity_ids": ["fw-01", "does-not-exist"]},
    )
    with pytest.raises(RecoveryCollectionError, match="unresolvable"):
        run_recovery_attestation(request, unified_devices=_unified(), attester=attester)
    assert attester.attest_calls == []


# --------------------------------------------------------------------------
# AC-9 — corrupt attestation file degrades --restore-readiness-check to
#        "no attestations", exit 0
# --------------------------------------------------------------------------

def test_ac9_loader_degrades_on_missing_or_corrupt_file(tmp_path):
    assert main._load_recovery_attestations(tmp_path) == {}
    state = tmp_path / "state"
    state.mkdir()
    (state / "recovery_attestations.json").write_text("{ not valid json", encoding="utf-8")
    assert main._load_recovery_attestations(tmp_path) == {}
    (state / "recovery_attestations.json").write_text('{"attestations": "not-a-dict"}', encoding="utf-8")
    assert main._load_recovery_attestations(tmp_path) == {}


def test_ac9_restore_readiness_check_exit_0_with_corrupt_attestation_file(tmp_path):
    import json
    runtime_root = tmp_path / "runtime"
    output_dir = runtime_root / "output"
    output_dir.mkdir(parents=True)
    (output_dir / "unified.json").write_text(json.dumps(_unified()), encoding="utf-8")
    state_dir = runtime_root / "data" / "state"
    state_dir.mkdir(parents=True)
    (state_dir / "recovery_attestations.json").write_text("CORRUPT{", encoding="utf-8")

    main.main(["--runtime-root", str(runtime_root), "--restore-readiness-check"])

    report = json.loads((state_dir / "restore_readiness.json").read_text(encoding="utf-8"))
    assert report["schema"] == "securityexpert-restore-readiness-v1"
    # degraded to "no attestations" -> every device UNPROTECTED/UNKNOWN, none PARTIAL
    assert report["summary"]["PARTIAL"] == 0


def test_ac9_restore_readiness_check_consumes_a_valid_attestation_file(tmp_path):
    import json
    runtime_root = tmp_path / "runtime"
    output_dir = runtime_root / "output"
    output_dir.mkdir(parents=True)
    (output_dir / "unified.json").write_text(json.dumps(_unified()), encoding="utf-8")
    state_dir = runtime_root / "data" / "state"
    state_dir.mkdir(parents=True)
    (state_dir / "recovery_attestations.json").write_text(json.dumps({
        "schema": "securityexpert-recovery-attestations-v1",
        "generated_at": "2026-08-31T00:00:00Z",
        "attestations": {"fw-01": [{"class": "cp_gaia_backup", "age_days": 12, "source": "device_reported"}]},
    }), encoding="utf-8")

    main.main(["--runtime-root", str(runtime_root), "--restore-readiness-check"])

    report = json.loads((state_dir / "restore_readiness.json").read_text(encoding="utf-8"))
    dev = {d["entity_id"]: d for d in report["devices"]}["fw-01"]
    assert dev["state"] == "PARTIAL"
    assert dev["evidence_basis"] == "device_attestation"


# --------------------------------------------------------------------------
# AC-10 — "recovery-attest-cp" is not allowlisted for scheduling
# --------------------------------------------------------------------------

def test_ac10_recovery_attest_cp_is_not_allowlisted():
    assert "recovery-attest-cp" not in ALLOWLISTED_WORKFLOWS


def test_ac10_scheduler_policy_naming_recovery_attest_cp_is_refused(tmp_path):
    import json
    policy_dir = tmp_path / "state"
    policy_dir.mkdir()
    (policy_dir / "scheduler_policy.json").write_text(json.dumps({
        "version": 1, "enabled": True,
        "schedule": [{"workflow": "recovery-attest-cp", "interval_minutes": 1440}],
    }), encoding="utf-8")
    with pytest.raises(SchedulerPolicyError, match="non-allowlisted"):
        load_scheduler_policy(tmp_path)


# --------------------------------------------------------------------------
# CLI surface
# --------------------------------------------------------------------------

def test_cli_recovery_attest_and_recovery_collect_are_mutually_exclusive(tmp_path):
    with pytest.raises(SystemExit) as exc:
        main.main([
            "--runtime-root", str(tmp_path / "runtime"),
            "--recovery-collect", "--recovery-vendor", "checkpoint", "--recovery-attest",
        ])
    assert exc.value.code == 2


def test_cli_recovery_attest_rejects_panorama_vendor(tmp_path):
    with pytest.raises(SystemExit) as exc:
        main.main([
            "--runtime-root", str(tmp_path / "runtime"),
            "--recovery-attest", "--recovery-vendor", "panorama",
        ])
    assert exc.value.code == 2


def test_cli_recovery_gateways_allowed_with_recovery_attest(tmp_path):
    # reaches bootstrap check (exit 2, missing unified.json) rather than the
    # argparse "only valid with --recovery-collect" rejection.
    with pytest.raises(SystemExit) as exc:
        main.main([
            "--runtime-root", str(tmp_path / "runtime"),
            "--recovery-attest", "--recovery-gateways", "fw-01",
        ])
    assert exc.value.code == 2
