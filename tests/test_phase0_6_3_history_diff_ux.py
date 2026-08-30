"""Tests for 0.6.3 — Unified Configuration History + Diff UX.

Acceptance criteria covered:
  AC-1  Timeline chronological order and change_state labels.
  AC-2  Scope isolation — no cross-entity mixing.
  AC-3  PAN compatible pair produces deterministic normalized diff rows.
  AC-4  SAME content produces no fabricated diff result.
  AC-5  CP history returns INSUFFICIENT_EVIDENCE for diff.
  AC-6  Missing/malformed metadata produces safe explicit state.
  AC-7  UI payload contains history_v1 without browser raw-content access.
  AC-8  Payload and diff rows contain no raw config, hash, path or credential.
  AC-9  Existing config-evidence immutability contract is not broken.

All test fixtures use synthetic values only. No real device identities,
management addresses, credentials or raw configuration.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from utils.config_evidence import ConfigEvidenceStore
from utils.config_history import (
    MAX_DIFF_ROWS,
    MAX_TIMELINE_EVENTS,
    ConfigHistoryService,
    build_history_payload,
)
from utils.config_ui import build_configuration_ui_payload

pytestmark = pytest.mark.configuration

# ---------------------------------------------------------------------------
# Synthetic fixtures
# ---------------------------------------------------------------------------

_XML_BASE = b"""<?xml version='1.0' encoding='UTF-8'?>
<config>
  <devices>
    <entry name="localhost.localdomain">
      <deviceconfig>
        <system>
          <hostname src="tpl">SYNTH-FW-A</hostname>
          <domain src="tpl">synth.test</domain>
          <dns-setting src="tpl">
            <servers>
              <primary>198.51.100.53</primary>
              <secondary>198.51.100.54</secondary>
            </servers>
          </dns-setting>
          <ntp-servers>
            <primary-ntp-server><ntp-server-address>198.51.100.123</ntp-server-address></primary-ntp-server>
          </ntp-servers>
        </system>
      </deviceconfig>
    </entry>
  </devices>
</config>"""

_XML_CHANGED = b"""<?xml version='1.0' encoding='UTF-8'?>
<config>
  <devices>
    <entry name="localhost.localdomain">
      <deviceconfig>
        <system>
          <hostname src="tpl">SYNTH-FW-A</hostname>
          <domain src="tpl">synth.test</domain>
          <dns-setting src="tpl">
            <servers>
              <primary>198.51.100.99</primary>
              <secondary>198.51.100.54</secondary>
            </servers>
          </dns-setting>
          <ntp-servers>
            <primary-ntp-server><ntp-server-address>198.51.100.200</ntp-server-address></primary-ntp-server>
          </ntp-servers>
        </system>
      </deviceconfig>
    </entry>
  </devices>
</config>"""

_CP_REDACTED = (
    "set hostname SYNTH-CP-GW\n"
    "set dns primary 198.51.100.53\n"
    "# [SECURITYEXPERT SECRET-BEARING CONFIGURATION LINE WITHHELD]\n"
    "# raw-canonical-sha256=unavailable\n"
    "# secret-bearing-lines-withheld=1\n"
)

FORBIDDEN_PATTERNS = [
    "sha256",
    "object_path",
    "management_ip",
    "SUPER_SECRET",
    "raw-canonical",
    "SYNTH-CP-GW-REAL-IP",
    "private-key",
    "198.51.100.10",  # synthetic management IP must not appear in history payload
]


def _store(tmp_path: Path) -> ConfigEvidenceStore:
    return ConfigEvidenceStore(tmp_path / "configs", tmp_path / "artifacts" / "config" / "sha256")


def _service(tmp_path: Path) -> ConfigHistoryService:
    return ConfigHistoryService(tmp_path / "configs", tmp_path / "artifacts" / "config" / "sha256")


def _write_pan_snapshots(store: ConfigEvidenceStore, count: int = 3) -> list:
    results = []
    content = _XML_BASE
    for i in range(count):
        if i == count - 1:
            content = _XML_CHANGED
        r = store.write_xml_snapshot(
            source="panos-direct",
            entity_id="SYNTH-SERIAL-001",
            artifact_type="effective",
            artifact_name="effective.xml",
            content=content,
            method="test",
        )
        results.append(r)
    return results


def _write_cp_snapshots(store: ConfigEvidenceStore, count: int = 2) -> list:
    results = []
    for _ in range(count):
        r = store.write_text_snapshot(
            source="checkpoint-gaia",
            entity_id="SYNTH-CP-GW",
            artifact_type="gaia_show_configuration_redacted",
            artifact_name="show-configuration.txt",
            content=_CP_REDACTED,
            method="direct_ssh_clish",
        )
        results.append(r)
    return results


# ---------------------------------------------------------------------------
# AC-1: Timeline chronological order and change_state labels
# ---------------------------------------------------------------------------

def test_timeline_events_descending_chronological_with_correct_states(tmp_path):
    store = _store(tmp_path)
    snap_results = _write_pan_snapshots(store, count=3)

    svc = _service(tmp_path)
    hist = svc.get_device_history(
        source="panos-direct",
        entity_id="SYNTH-SERIAL-001",
        artifact_type="effective",
    )
    assert hist.status == "available"
    assert len(hist.artifacts) == 1
    art = hist.artifacts[0]
    assert len(art.events) == 3

    # Newest first (descending).
    timestamps = [e.collected_at for e in art.events]
    assert timestamps == sorted(timestamps, reverse=True)

    states = [e.change_state for e in art.events]
    assert states[0] == "changed"  # newest
    assert states[-1] == "first"   # oldest

    assert all(e.artifact_type == "effective" for e in art.events)
    assert all(e.status == "available" for e in art.events)


# ---------------------------------------------------------------------------
# AC-2: Scope isolation
# ---------------------------------------------------------------------------

def test_timeline_scoped_to_single_entity_no_cross_entity_mixing(tmp_path):
    store = _store(tmp_path)
    store.write_xml_snapshot(
        source="panos-direct", entity_id="SYNTH-SERIAL-001",
        artifact_type="effective", artifact_name="effective.xml",
        content=_XML_BASE, method="test",
    )
    store.write_xml_snapshot(
        source="panos-direct", entity_id="SYNTH-SERIAL-002",
        artifact_type="effective", artifact_name="effective.xml",
        content=_XML_CHANGED, method="test",
    )

    svc = _service(tmp_path)
    hist1 = svc.get_device_history(source="panos-direct", entity_id="SYNTH-SERIAL-001", artifact_type="effective")
    hist2 = svc.get_device_history(source="panos-direct", entity_id="SYNTH-SERIAL-002", artifact_type="effective")

    ids1 = {e.id for art in hist1.artifacts for e in art.events}
    ids2 = {e.id for art in hist2.artifacts for e in art.events}
    assert not ids1 & ids2, "Timelines must not share snapshot identifiers across entities"
    assert len(ids1) == 1
    assert len(ids2) == 1


# ---------------------------------------------------------------------------
# AC-3: PAN compatible pair produces deterministic diff rows
# ---------------------------------------------------------------------------

def test_pan_changed_pair_produces_normalized_diff_rows(tmp_path):
    store = _store(tmp_path)
    _write_pan_snapshots(store, count=3)

    svc = _service(tmp_path)
    hist = svc.get_device_history(
        source="panos-direct",
        entity_id="SYNTH-SERIAL-001",
        artifact_type="effective",
    )
    assert hist.pair_results, "Expected at least one pair result for a CHANGED snapshot"
    pair = hist.pair_results[0]
    assert pair.status == "available"
    assert pair.diff_rows, "Expected at least one diff row"

    diff_settings = [r.setting for r in pair.diff_rows]
    assert any("DNS" in s or "NTP" in s or "primary" in s.lower() for s in diff_settings), (
        f"Expected a DNS or NTP diff row; got: {diff_settings}"
    )

    for row in pair.diff_rows:
        assert row.change in ("added", "removed", "modified")
        assert row.section in ("system", "dns", "ntp", "management", "high_availability", "network_summary", "telemetry")
        assert isinstance(row.setting, str) and row.setting
        assert row.scope in ("local", "central", "member_specific", "unknown", "effective")


# ---------------------------------------------------------------------------
# AC-4: SAME content produces no fabricated diff
# ---------------------------------------------------------------------------

def test_same_content_pair_produces_no_diff_rows(tmp_path):
    store = _store(tmp_path)
    # Write base then same again — two snapshots, no content change.
    store.write_xml_snapshot(
        source="panos-direct", entity_id="SYNTH-SERIAL-001",
        artifact_type="effective", artifact_name="effective.xml",
        content=_XML_BASE, method="test",
    )
    store.write_xml_snapshot(
        source="panos-direct", entity_id="SYNTH-SERIAL-001",
        artifact_type="effective", artifact_name="effective.xml",
        content=_XML_BASE, method="test",
    )
    svc = _service(tmp_path)
    hist = svc.get_device_history(
        source="panos-direct", entity_id="SYNTH-SERIAL-001", artifact_type="effective"
    )
    # SAME events are not comparison_eligible.
    for art in hist.artifacts:
        for ev in art.events:
            if ev.change_state == "same":
                assert not ev.comparison_eligible
    # No pair result because latest eligible event is "first", not "changed".
    assert all(
        p.status != "available" or len(p.diff_rows) == 0
        for p in hist.pair_results
    )


# ---------------------------------------------------------------------------
# AC-5: CP history → INSUFFICIENT_EVIDENCE for diff
# ---------------------------------------------------------------------------

def test_cp_timeline_available_but_diff_is_insufficient_evidence(tmp_path):
    store = _store(tmp_path)
    _write_cp_snapshots(store, count=2)

    svc = _service(tmp_path)
    hist = svc.get_device_history(
        source="checkpoint-gaia",
        entity_id="SYNTH-CP-GW",
        artifact_type="gaia_show_configuration_redacted",
    )
    assert hist.status == "available"
    assert hist.artifacts[0].events  # Timeline present

    for pair in hist.pair_results:
        assert pair.status == "insufficient_evidence"
        assert "cp_raw_text_diff_not_supported" in (pair.reason or "")


# ---------------------------------------------------------------------------
# AC-6: Missing/malformed metadata produces safe explicit state
# ---------------------------------------------------------------------------

def test_empty_entity_directory_returns_insufficient_evidence(tmp_path):
    svc = _service(tmp_path)
    hist = svc.get_device_history(
        source="panos-direct", entity_id="NONEXISTENT", artifact_type="effective"
    )
    assert hist.status == "insufficient_evidence"
    payload = build_history_payload(hist)
    assert payload["status"] == "insufficient_evidence"


def test_malformed_metadata_is_counted_not_raised(tmp_path):
    config_root = tmp_path / "configs"
    bad_snap = config_root / "panos-direct" / "SYNTH-SERIAL-001" / "20260101T000000Z_badf00d"
    bad_snap.mkdir(parents=True)
    (bad_snap / "metadata.json").write_text("{not valid json", encoding="utf-8")

    svc = ConfigHistoryService(config_root, tmp_path / "artifacts" / "config" / "sha256")
    hist = svc.get_device_history(
        source="panos-direct", entity_id="SYNTH-SERIAL-001", artifact_type="effective"
    )
    if hist.artifacts:
        assert hist.artifacts[0].skipped_malformed >= 1


# ---------------------------------------------------------------------------
# AC-7: UI payload contains history_v1 without filesystem paths
# ---------------------------------------------------------------------------

def test_build_configuration_ui_payload_attaches_history_v1_for_pan(tmp_path):
    store = _store(tmp_path)
    results = _write_pan_snapshots(store, count=2)
    newest = results[-1]

    effective_artifact = {
        "status": "success",
        "method": "DIRECT_EFFECTIVE",
        "change_state": newest.change_state,
        "sha256": newest.sha256,
        "snapshot": str(newest.directory),
        "artifact_file": "effective.xml",
        "structural_validation": {
            "schema_status": "pass",
            "counts": {"vsys_entries": 1, "virtual_router_entries": 1,
                       "zone_entries": 2, "interface_definitions_total": 2},
        },
    }
    config_result = {
        "summary": {
            "run_id": "synth-run",
            "selected": 1,
            "success": 1,
            "primary_evidence_success": 1,
            "alignment_evidence_complete": 1,
            "first": 1,
            "same": 0,
            "changed": 1,
            "setting_alignment_classifications": {},
        },
        "transport": {"direct_firewall": {"tls_verify": False, "ca_bundle_configured": False}},
        "devices": [{
            "device": "SYNTH-FW-A",
            "serial": "SYNTH-SERIAL-001",
            "management_ip": "198.51.100.10",
            "model": "PA-SYNTH",
            "sw_version": "11.1.0",
            "ha_state": "active",
            "connected": "yes",
            "status": "success",
            "primary_evidence_status": "success",
            "alignment_evidence_status": "complete",
            "completed_at": "2026-08-27T10:00:00+03:00",
            "expected_configuration": {"primary_template_stack": None},
            "configuration_alignment": {"panorama_reports_out_of_sync": False},
            "setting_alignment": {
                "status": "success",
                "device_status": "ALIGNED",
                "summary": {
                    "expected_settings": 5, "alignment_ready_settings": 5,
                    "classification_counts": {"ALIGNED": 5},
                    "category_counts": {},
                },
            },
            "direct": {
                "effective": effective_artifact,
                "active": {"status": "success", "change_state": "same"},
                "merged": {"status": "success", "change_state": "same"},
            },
            "panorama_control": {"status": "success"},
        }],
    }

    svc = _service(tmp_path)
    payload = build_configuration_ui_payload(config_result, history_service=svc)
    assert payload["available"] is True
    device = payload["devices"][0]
    assert "history_v1" in device
    hv1 = device["history_v1"]
    assert isinstance(hv1, dict)
    assert hv1.get("status") in ("available", "insufficient_evidence")


def test_history_v1_absent_when_no_history_service(tmp_path):
    config_result = {
        "summary": {"run_id": "r", "selected": 0, "success": 0, "primary_evidence_success": 0},
        "transport": {"direct_firewall": {"tls_verify": False, "ca_bundle_configured": False}},
        "devices": [],
    }
    payload = build_configuration_ui_payload(config_result)
    # No devices, but payload must not fail without history_service.
    assert payload["available"] is True


# ---------------------------------------------------------------------------
# AC-8: Privacy – no raw config, secret, hash or path in payload
# ---------------------------------------------------------------------------

def test_history_payload_contains_no_forbidden_patterns(tmp_path):
    store = _store(tmp_path)
    _write_pan_snapshots(store, count=3)

    svc = _service(tmp_path)
    hist = svc.get_device_history(
        source="panos-direct", entity_id="SYNTH-SERIAL-001", artifact_type="effective"
    )
    payload = build_history_payload(hist)
    encoded = json.dumps(payload)

    for pattern in FORBIDDEN_PATTERNS:
        assert pattern not in encoded, (
            f"Forbidden pattern '{pattern}' found in history_v1 payload"
        )

    # No filesystem absolute paths.
    assert ":\\" not in encoded and "C:/" not in encoded and "/data/artifacts" not in encoded
    assert "object_path" not in encoded


def test_diff_rows_do_not_contain_secrets_or_hashes(tmp_path):
    store = _store(tmp_path)
    _write_pan_snapshots(store, count=3)

    svc = _service(tmp_path)
    hist = svc.get_device_history(
        source="panos-direct", entity_id="SYNTH-SERIAL-001", artifact_type="effective"
    )
    for pair in hist.pair_results:
        for row in pair.diff_rows:
            for forbidden in ("sha256", "password", "secret", "private-key", "credential", "token"):
                setting_l = (row.setting or "").lower()
                before_l = (row.before or "").lower()
                after_l = (row.after or "").lower()
                assert forbidden not in before_l, f"Secret pattern in diff 'before': {row}"
                assert forbidden not in after_l, f"Secret pattern in diff 'after': {row}"


# ---------------------------------------------------------------------------
# AC-9: CAS immutability contract not broken by history service
# ---------------------------------------------------------------------------

def test_history_service_does_not_mutate_cas_objects(tmp_path):
    store = _store(tmp_path)
    results = _write_pan_snapshots(store, count=3)
    # Collect blob paths before history service touches anything.
    blob_paths = {r.artifact_path for r in results}
    blob_digests = {p: p.read_bytes() for p in blob_paths}

    svc = _service(tmp_path)
    hist = svc.get_device_history(
        source="panos-direct", entity_id="SYNTH-SERIAL-001", artifact_type="effective"
    )
    _ = build_history_payload(hist)

    for path, original_bytes in blob_digests.items():
        assert path.read_bytes() == original_bytes, (
            f"CAS blob was mutated by history service: {path}"
        )


# ---------------------------------------------------------------------------
# Timeline truncation contract
# ---------------------------------------------------------------------------

def test_timeline_truncation_flag_when_exceeding_limit(tmp_path):
    store = _store(tmp_path)
    limit = MAX_TIMELINE_EVENTS
    for _ in range(limit + 2):
        store.write_xml_snapshot(
            source="panos-direct", entity_id="SYNTH-SERIAL-001",
            artifact_type="effective", artifact_name="effective.xml",
            content=_XML_BASE, method="test",
        )

    svc = _service(tmp_path)
    hist = svc.get_device_history(
        source="panos-direct", entity_id="SYNTH-SERIAL-001", artifact_type="effective"
    )
    art = hist.artifacts[0]
    assert art.truncated is True
    assert len(art.events) == limit


# ---------------------------------------------------------------------------
# Privacy contract: build_history_payload never emits sha256 or paths
# ---------------------------------------------------------------------------

def test_build_history_payload_excludes_sha256_and_artifact_paths(tmp_path):
    store = _store(tmp_path)
    _write_pan_snapshots(store, count=2)

    svc = _service(tmp_path)
    hist = svc.get_device_history(
        source="panos-direct", entity_id="SYNTH-SERIAL-001", artifact_type="effective"
    )
    payload = build_history_payload(hist)
    encoded = json.dumps(payload)

    assert "sha256" not in encoded
    assert "artifact_paths_included" in encoded  # privacy contract key present
    assert payload["privacy"]["artifact_paths_included"] is False
    assert payload["privacy"]["raw_configuration_included"] is False
    assert payload["privacy"]["value_hashes_included"] is False
    assert payload["privacy"]["credentials_included"] is False
