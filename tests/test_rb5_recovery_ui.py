"""RB.5 — recovery_ui payload builder.

Contract: docs/design/BACKUP_RECOVERY_CONTRACTS.md §6 (frozen rules 1-4).
Architecture: docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md §11.
"""
import json

from utils.recovery_ui import (
    SCHEMA,
    build_recovery_ui_payload,
    load_persisted_readiness_record,
    readiness_by_entity,
)


def _device(entity_id, state, *, held_artifacts=None, attested_not_held=None):
    return {
        "entity_id": entity_id,
        "vendor": "checkpoint",
        "state": state,
        "reason": "test",
        "held_artifacts": held_artifacts or [],
        "attested_not_held": attested_not_held or [],
        "missing_required": [],
        "evidence_basis": "recovery_manifest" if held_artifacts else "none",
    }


def _readiness_record(devices, *, generated_at="2026-09-01T00:00:00Z"):
    summary = {state: 0 for state in ("READY", "STALE", "PARTIAL", "UNPROTECTED", "UNKNOWN")}
    for device in devices:
        summary[device["state"]] += 1
    return {"schema": "securityexpert-restore-readiness-v1", "generated_at": generated_at,
            "devices": devices, "summary": summary}


# --------------------------------------------------------------------------
# AC-4: frozen rule 3 -- available: false is an explicit empty state
# --------------------------------------------------------------------------

def test_missing_readiness_record_is_available_false():
    payload = build_recovery_ui_payload(None)
    assert payload["available"] is False
    assert payload["schema"] == SCHEMA
    assert payload["devices"] == []
    assert payload["readiness_summary"] == {
        "READY": 0, "STALE": 0, "PARTIAL": 0, "UNPROTECTED": 0, "UNKNOWN": 0,
    }
    assert payload["coverage"] == {
        "devices_in_inventory": 0, "devices_with_any_artifact": 0, "coverage_percent": 0,
    }


def test_malformed_readiness_record_is_available_false():
    assert build_recovery_ui_payload({"devices": {"not": "a-list"}})["available"] is False
    assert build_recovery_ui_payload("not-a-mapping")["available"] is False
    assert build_recovery_ui_payload({})["available"] is False


# --------------------------------------------------------------------------
# AC-2: every readiness state
# --------------------------------------------------------------------------

def test_every_readiness_state_is_projected():
    devices = [
        _device("ready-01", "READY", held_artifacts=[
            {"class": "full_config_snapshot", "age_days": 1, "validation_level": "V3",
             "version_matches_running": True, "restore_proven": True},
        ]),
        _device("stale-01", "STALE", held_artifacts=[
            {"class": "full_config_snapshot", "age_days": 90, "validation_level": "V1",
             "version_matches_running": False},
        ]),
        _device("partial-01", "PARTIAL", attested_not_held=[
            {"class": "local_snapshot", "age_days": None, "source": "device_reported"},
        ]),
        _device("unprotected-01", "UNPROTECTED"),
        _device("unknown-01", "UNKNOWN"),
    ]
    payload = build_recovery_ui_payload(_readiness_record(devices))

    assert payload["available"] is True
    assert payload["readiness_summary"] == {
        "READY": 1, "STALE": 1, "PARTIAL": 1, "UNPROTECTED": 1, "UNKNOWN": 1,
    }
    by_entity = {row["entity_id"]: row for row in payload["devices"]}
    assert by_entity["ready-01"]["state"] == "READY"
    assert by_entity["ready-01"]["artifacts"][0]["restore_proven"] is True
    assert by_entity["stale-01"]["artifacts"][0]["restore_proven"] is False
    # attested_not_held is never merged into the rendered `artifacts` list
    # (frozen rule §5-1) -- PARTIAL-by-attestation has zero held artifacts.
    assert by_entity["partial-01"]["artifacts"] == []
    assert by_entity["unprotected-01"]["artifacts"] == []
    assert by_entity["unknown-01"]["artifacts"] == []


# --------------------------------------------------------------------------
# AC-2: coverage arithmetic
# --------------------------------------------------------------------------

def test_coverage_arithmetic():
    devices = [
        _device("a", "READY", held_artifacts=[{"class": "full_config_snapshot"}]),
        _device("b", "STALE", held_artifacts=[{"class": "full_config_snapshot"}]),
        _device("c", "UNPROTECTED"),
        _device("d", "UNKNOWN"),
    ]
    coverage = build_recovery_ui_payload(_readiness_record(devices))["coverage"]
    assert coverage == {
        "devices_in_inventory": 4, "devices_with_any_artifact": 2, "coverage_percent": 50,
    }


def test_coverage_percent_zero_devices_does_not_divide_by_zero():
    coverage = build_recovery_ui_payload(_readiness_record([]))["coverage"]
    assert coverage == {"devices_in_inventory": 0, "devices_with_any_artifact": 0, "coverage_percent": 0}


# --------------------------------------------------------------------------
# AC-4: frozen rule 1 -- no payload bytes ever, only the frozen fields
# --------------------------------------------------------------------------

def test_artifact_row_never_carries_extra_manifest_fields():
    devices = [_device("a", "READY", held_artifacts=[{
        "class": "full_config_snapshot", "age_days": 3, "validation_level": "V3",
        "version_matches_running": True,
        "manifest_digest": "sha256:" + "a" * 64,
        "artifact_bytes": "forbidden",
    }])]
    row = build_recovery_ui_payload(_readiness_record(devices))["devices"][0]["artifacts"][0]
    assert set(row) == {"class", "age_days", "validation_level", "restore_proven"}
    dumped = json.dumps(row)
    assert "manifest_digest" not in dumped
    assert "forbidden" not in dumped


# --------------------------------------------------------------------------
# AC-4: frozen rule 2 -- restore_proven is always an explicit boolean
# --------------------------------------------------------------------------

def test_restore_proven_defaults_false_and_is_never_invented_true():
    devices = [_device("a", "READY", held_artifacts=[
        {"class": "full_config_snapshot"},  # no restore_proven key at all
    ])]
    row = build_recovery_ui_payload(_readiness_record(devices))["devices"][0]["artifacts"][0]
    assert row["restore_proven"] is False


# --------------------------------------------------------------------------
# AC-5: additive compliance_posture evidence source
# --------------------------------------------------------------------------

def test_readiness_by_entity_projects_a_lookup_map():
    devices = [_device("cp-edge-01", "READY"), _device("cp-edge-02", "UNPROTECTED")]
    lookup = readiness_by_entity(_readiness_record(devices))
    assert set(lookup) == {"cp-edge-01", "cp-edge-02"}
    assert lookup["cp-edge-01"]["state"] == "READY"
    assert "entity_id" not in lookup["cp-edge-01"]


def test_readiness_by_entity_degrades_to_empty_dict():
    assert readiness_by_entity(None) == {}
    assert readiness_by_entity({"devices": "nope"}) == {}


# --------------------------------------------------------------------------
# retention_pending_deletion pass-through
# --------------------------------------------------------------------------

def test_retention_rows_are_projected_verbatim():
    payload = build_recovery_ui_payload(
        _readiness_record([]),
        retention_pending_deletion=[
            {"entity_id": "cp-edge-01", "artifact_id": "art-1", "expires_at": "2026-01-01T00:00:00Z"},
            "not-a-mapping",
        ],
    )
    assert payload["retention_pending_deletion"] == [
        {"entity_id": "cp-edge-01", "artifact_id": "art-1", "expires_at": "2026-01-01T00:00:00Z"},
    ]


# --------------------------------------------------------------------------
# load_persisted_readiness_record -- fail-safe I/O
# --------------------------------------------------------------------------

def test_load_persisted_readiness_record_missing_file_is_none(tmp_path):
    assert load_persisted_readiness_record(tmp_path) is None


def test_load_persisted_readiness_record_corrupt_file_is_none(tmp_path):
    state_dir = tmp_path / "state"
    state_dir.mkdir()
    (state_dir / "restore_readiness.json").write_text("{not json", encoding="utf-8")
    assert load_persisted_readiness_record(tmp_path) is None


def test_load_persisted_readiness_record_reads_real_file(tmp_path):
    state_dir = tmp_path / "state"
    state_dir.mkdir()
    record = _readiness_record([_device("a", "READY")])
    (state_dir / "restore_readiness.json").write_text(json.dumps(record), encoding="utf-8")
    loaded = load_persisted_readiness_record(tmp_path)
    assert loaded == record
