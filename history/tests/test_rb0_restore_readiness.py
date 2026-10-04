import pytest

from utils.restore_readiness import (
    STATE_PARTIAL,
    STATE_READY,
    STATE_STALE,
    STATE_UNKNOWN,
    STATE_UNPROTECTED,
    compute_restore_readiness,
)

pytestmark = pytest.mark.recovery


def _row(source="cp", device="fw-01", vs_id=None, data_state="live"):
    row = {
        "source": source,
        "device": device,
        "inventory_status": {"data_state": data_state},
    }
    if vs_id is not None:
        row["vs_id"] = vs_id
    return row


def test_no_evidence_at_all_is_unprotected():
    report = compute_restore_readiness([_row()])
    assert report["schema"] == "securityexpert-restore-readiness-v1"
    device = report["devices"][0]
    assert device["state"] == STATE_UNPROTECTED
    assert device["evidence_basis"] == "none"
    assert device["held_artifacts"] == []
    assert device["attested_not_held"] == []
    assert report["summary"][STATE_UNPROTECTED] == 1


def test_validated_current_artifact_is_ready():
    manifests = {
        "fw-01": [
            {"class": "cp_gaia_backup", "age_days": 1, "validation_level": "V3",
             "version_matches_running": True}
        ]
    }
    report = compute_restore_readiness([_row()], recovery_manifests=manifests)
    device = report["devices"][0]
    assert device["state"] == STATE_READY
    assert device["evidence_basis"] == "recovery_manifest"
    assert device["held_artifacts"][0]["class"] == "cp_gaia_backup"


def test_version_mismatch_is_stale_not_ready():
    manifests = {
        "fw-01": [
            {"class": "cp_gaia_backup", "age_days": 30, "validation_level": "V3",
             "version_matches_running": False}
        ]
    }
    report = compute_restore_readiness([_row()], recovery_manifests=manifests)
    assert report["devices"][0]["state"] == STATE_STALE


def test_unvalidated_artifact_is_stale_not_ready():
    manifests = {
        "fw-01": [
            {"class": "cp_gaia_backup", "age_days": 1, "validation_level": "V1",
             "version_matches_running": True}
        ]
    }
    report = compute_restore_readiness([_row()], recovery_manifests=manifests)
    assert report["devices"][0]["state"] == STATE_STALE


def test_missing_required_class_is_partial():
    manifests = {"fw-01": [{"class": "pan_running_config", "age_days": 1,
                             "validation_level": "V3", "version_matches_running": True}]}
    required = {"checkpoint": ["cp_gaia_backup"]}
    report = compute_restore_readiness(
        [_row()], recovery_manifests=manifests, required_classes=required
    )
    device = report["devices"][0]
    assert device["state"] == STATE_PARTIAL
    assert device["missing_required"] == ["cp_gaia_backup"]


def test_attested_only_is_partial_never_ready():
    attestations = {"fw-01": [{"class": "cp_gaia_snapshot", "age_days": 41,
                                "source": "device_reported"}]}
    report = compute_restore_readiness([_row()], attestations=attestations)
    device = report["devices"][0]
    assert device["state"] == STATE_PARTIAL
    assert device["state"] != STATE_READY
    assert device["evidence_basis"] == "device_attestation"
    assert device["held_artifacts"] == []
    assert device["attested_not_held"][0]["class"] == "cp_gaia_snapshot"


def test_attested_and_held_are_never_merged():
    manifests = {"fw-01": [{"class": "cp_gaia_backup", "age_days": 1,
                             "validation_level": "V3", "version_matches_running": True}]}
    attestations = {"fw-01": [{"class": "cp_gaia_snapshot", "age_days": 90,
                                "source": "device_reported"}]}
    report = compute_restore_readiness(
        [_row()], recovery_manifests=manifests, attestations=attestations
    )
    device = report["devices"][0]
    assert device["state"] == STATE_READY
    assert device["evidence_basis"] == "recovery_manifest"
    assert len(device["held_artifacts"]) == 1
    assert len(device["attested_not_held"]) == 1


def test_no_data_inventory_is_unknown():
    report = compute_restore_readiness([_row(data_state="no_data")])
    device = report["devices"][0]
    assert device["state"] == STATE_UNKNOWN
    assert device["reason"] == "inventory_data_state_no_data"
    assert device["evidence_basis"] == "none"


def test_unresolvable_vendor_is_unknown_not_unprotected():
    report = compute_restore_readiness([_row(source="mystery-vendor")])
    device = report["devices"][0]
    assert device["state"] == STATE_UNKNOWN
    assert device["reason"] == "device_identity_or_vendor_unresolvable"


def test_missing_device_identity_is_unknown():
    report = compute_restore_readiness([{"source": "cp", "inventory_status": {"data_state": "live"}}])
    device = report["devices"][0]
    assert device["state"] == STATE_UNKNOWN


def test_vsx_virtual_system_entity_id_is_physical_plus_vsid():
    physical = _row(source="vsx", device="vsx-gw-01")
    vs = _row(source="vsx", device="vsx-gw-01", vs_id="10")
    report = compute_restore_readiness([physical, vs])
    ids = {d["entity_id"] for d in report["devices"]}
    assert ids == {"vsx-gw-01", "vsx-gw-01__vsid_10"}


def test_panorama_vendor_maps_to_panorama_not_checkpoint():
    report = compute_restore_readiness([_row(source="panorama", device="pan-fw-01")])
    assert report["devices"][0]["vendor"] == "panorama"


def test_cp_and_vsx_both_map_to_checkpoint_vendor():
    report = compute_restore_readiness([_row(source="cp"), _row(source="vsx", device="vsx-01")])
    vendors = {d["vendor"] for d in report["devices"]}
    assert vendors == {"checkpoint"}


def test_duplicate_rows_for_same_entity_are_deduplicated():
    report = compute_restore_readiness([_row(), _row()])
    assert len(report["devices"]) == 1


def test_summary_counts_all_five_states():
    rows = [
        _row(device="ready-fw"),
        _row(device="stale-fw"),
        _row(device="partial-fw"),
        _row(device="unprotected-fw"),
        _row(device="unknown-fw", data_state="no_data"),
    ]
    manifests = {
        "ready-fw": [{"class": "cp_gaia_backup", "age_days": 1, "validation_level": "V3",
                       "version_matches_running": True}],
        "stale-fw": [{"class": "cp_gaia_backup", "age_days": 1, "validation_level": "V1",
                       "version_matches_running": True}],
    }
    attestations = {"partial-fw": [{"class": "cp_gaia_snapshot", "age_days": 1,
                                     "source": "device_reported"}]}
    report = compute_restore_readiness(rows, recovery_manifests=manifests, attestations=attestations)
    assert report["summary"] == {
        STATE_READY: 1, STATE_STALE: 1, STATE_PARTIAL: 1,
        STATE_UNPROTECTED: 1, STATE_UNKNOWN: 1,
    }


def test_zero_network_zero_credentials_pure_function():
    # RB.0 must be computable from data already in memory; no I/O in this module.
    import inspect
    import utils.restore_readiness as mod

    source = inspect.getsource(mod)
    for banned in ("socket", "requests", "paramiko", "getpass", "input("):
        assert banned not in source
