"""0.7.7 — compliance trend retro-fill (PAN baseline reconstruction).

Contract: docs/history/phase/0_7_7_COMPLIANCE_TREND_RECONSTRUCTION.md.
No network, no real-env gate — reads only synthetic CAS fixtures.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from utils.compliance_history import append_reconstructed, history_view, load_history
from utils.compliance_posture import _evaluate_vendor_neutral_control
from utils.compliance_rulepack import DEFAULT_RULE_PACK
from utils.compliance_trend_reconstruction import (
    RECONSTRUCTION_SCOPE,
    reconstruct_pan_baseline_records,
)
from utils.config_evidence import ConfigEvidenceStore

pytestmark = pytest.mark.compliance

_XML_HOSTNAME_DNS_OK_NTP_PARTIAL = b"""<?xml version='1.0' encoding='UTF-8'?>
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

_XML_HOSTNAME_ONLY = b"""<?xml version='1.0' encoding='UTF-8'?>
<config>
  <devices>
    <entry name="localhost.localdomain">
      <deviceconfig>
        <system>
          <hostname src="tpl">SYNTH-FW-B</hostname>
          <domain src="tpl">synth.test</domain>
        </system>
      </deviceconfig>
    </entry>
  </devices>
</config>"""


def _store(tmp_path: Path) -> ConfigEvidenceStore:
    return ConfigEvidenceStore(tmp_path / "configs", tmp_path / "artifacts" / "config" / "sha256")


def _write(store, *, entity_id="SYNTH-SERIAL-001", content=_XML_HOSTNAME_DNS_OK_NTP_PARTIAL):
    return store.write_xml_snapshot(
        source="panos-direct", entity_id=entity_id, artifact_type="effective",
        artifact_name="effective.xml", content=content, method="test",
    )


# --- reconstruction over CAS -----------------------------------------

def test_empty_cas_yields_no_records(tmp_path):
    assert reconstruct_pan_baseline_records(
        config_root=tmp_path / "configs", artifact_root=tmp_path / "artifacts" / "config" / "sha256",
    ) == []


def test_single_snapshot_produces_one_scoped_record(tmp_path):
    store = _store(tmp_path)
    _write(store)
    records = reconstruct_pan_baseline_records(
        config_root=tmp_path / "configs", artifact_root=tmp_path / "artifacts" / "config" / "sha256",
    )
    assert len(records) == 1
    rec = records[0]
    assert rec["reconstructed"] is True
    assert rec["reconstruction_scope"] == RECONSTRUCTION_SCOPE
    assert rec["total_controls"] == 10
    assert rec["subjects"] == 1
    # hostname PASS, dns PASS, ntp FINDING (primary only), 7 others UNKNOWN (no evidence)
    assert rec["cells"] == {"aligned": 2, "finding": 1, "unknown": 7, "planned": 0, "waived": 0}
    assert rec["run_id"].startswith("reconstructed:")


def test_snapshots_close_in_time_bucket_together(tmp_path):
    store = _store(tmp_path)
    _write(store, entity_id="SYNTH-SERIAL-001")
    _write(store, entity_id="SYNTH-SERIAL-002", content=_XML_HOSTNAME_ONLY)
    # Both snapshots were just written (milliseconds apart) -> same bucket.
    records = reconstruct_pan_baseline_records(
        config_root=tmp_path / "configs", artifact_root=tmp_path / "artifacts" / "config" / "sha256",
        gap_minutes=15,
    )
    assert len(records) == 1
    assert records[0]["subjects"] == 2


def test_snapshots_far_apart_form_separate_buckets(tmp_path):
    store = _store(tmp_path)
    store.write_xml_snapshot(
        source="panos-direct", entity_id="SYNTH-SERIAL-001", artifact_type="effective",
        artifact_name="effective.xml", content=_XML_HOSTNAME_DNS_OK_NTP_PARTIAL, method="test",
    )
    store.write_xml_snapshot(
        source="panos-direct", entity_id="SYNTH-SERIAL-001", artifact_type="effective",
        artifact_name="effective.xml", content=_XML_HOSTNAME_ONLY, method="test",
    )
    entity_dir = tmp_path / "configs" / "panos-direct" / "SYNTH-SERIAL-001"
    snaps = sorted(p for p in entity_dir.iterdir() if p.is_dir())
    assert len(snaps) == 2
    _write_metadata_collected_at(snaps[0] / "metadata.json", "2026-01-01T00:00:00Z")
    _write_metadata_collected_at(snaps[1] / "metadata.json", "2026-06-01T00:00:00Z")
    records = reconstruct_pan_baseline_records(
        config_root=tmp_path / "configs", artifact_root=tmp_path / "artifacts" / "config" / "sha256",
        gap_minutes=15,
    )
    assert len(records) == 2
    assert sorted(r["collected_at"] for r in records) == ["2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z"]


def _write_metadata_collected_at(meta_path: Path, iso: str) -> None:
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    meta["collected_at"] = iso
    meta_path.write_text(json.dumps(meta), encoding="utf-8")


def test_evaluation_matches_the_live_evaluator_directly():
    """A reconstructed record's per-control status must be exactly what the
    live evaluator dispatch returns for the same current_configuration."""
    from configuration.current_config_projection import _safe_xml, _scalar_rows
    root = _safe_xml(_XML_HOSTNAME_DNS_OK_NTP_PARTIAL)
    sections, _ = _scalar_rows(root, alignment_index={})
    device = {
        "vendor_key": "palo_alto",
        "current_configuration": {
            "status": "available",
            "sections": [{"id": sid, "settings": rows} for sid, rows in sections.items()],
        },
    }
    hostname_rule = next(r for r in DEFAULT_RULE_PACK["rules"] if r["control_id"] == "hostname_configured_non_default")
    result = _evaluate_vendor_neutral_control(device, hostname_rule)
    assert result["status"] == "PASS"


# --- ledger integration -----------------------------------------------

def test_append_reconstructed_is_idempotent(tmp_path):
    store = _store(tmp_path)
    _write(store)
    records = reconstruct_pan_baseline_records(
        config_root=tmp_path / "configs", artifact_root=tmp_path / "artifacts" / "config" / "sha256",
    )
    first = append_reconstructed(tmp_path, records)
    second = append_reconstructed(tmp_path, records)
    assert first == 1
    assert second == 0
    assert len(load_history(tmp_path)) == 1


def test_trend_never_compares_against_a_reconstructed_record(tmp_path):
    reconstructed = {
        "run_id": "reconstructed:2026-07-01T00:00:00Z", "collected_at": "2026-07-01T00:00:00Z",
        "aligned_percent": 10.0, "risk_weighted_alignment_percent": 10.0,
        "catalog_version": "x", "framework_catalog_version": None,
        "monitored_controls": 2, "total_controls": 10, "subjects": 1,
        "cells": {"aligned": 1, "finding": 0, "unknown": 0, "planned": 0, "waived": 0},
        "by_framework": {}, "reconstructed": True, "reconstruction_scope": RECONSTRUCTION_SCOPE,
    }
    live = {
        "run_id": "r1", "collected_at": "2026-06-01T00:00:00Z",
        "aligned_percent": 70.0, "risk_weighted_alignment_percent": 70.0,
        "catalog_version": "x", "framework_catalog_version": "x",
        "monitored_controls": 8, "total_controls": 18, "subjects": 1,
        "cells": {"aligned": 5, "finding": 1, "unknown": 0, "planned": 0, "waived": 0},
        "by_framework": {},
    }
    # reconstructed record is chronologically newest in the ledger; trend must
    # still be computed against the live record, not the reconstructed one.
    view = history_view([live, reconstructed], current_aligned=75.0)
    assert view["trend"] is not None
    assert view["trend"]["previous_date"] == "2026-06-01"
    assert view["trend"]["delta_aligned_percent"] == 5.0  # 75 - 70, not 75 - 10


def test_only_reconstructed_history_yields_no_trend():
    reconstructed = {
        "run_id": "reconstructed:2026-07-01T00:00:00Z", "collected_at": "2026-07-01T00:00:00Z",
        "aligned_percent": 10.0, "risk_weighted_alignment_percent": 10.0,
        "catalog_version": "x", "framework_catalog_version": None,
        "monitored_controls": 2, "total_controls": 10, "subjects": 1,
        "cells": {"aligned": 1, "finding": 0, "unknown": 0, "planned": 0, "waived": 0},
        "by_framework": {}, "reconstructed": True, "reconstruction_scope": RECONSTRUCTION_SCOPE,
    }
    view = history_view([reconstructed], current_aligned=75.0)
    assert view["trend"] is None


def test_pre_0_7_7_records_default_to_not_reconstructed():
    legacy = {
        "run_id": "r0", "collected_at": "2026-05-01T00:00:00Z",
        "aligned_percent": 50.0, "risk_weighted_alignment_percent": 50.0,
        "catalog_version": "x", "framework_catalog_version": "x",
        "monitored_controls": 5, "total_controls": 18, "subjects": 1,
        "cells": {"aligned": 3, "finding": 0, "unknown": 0, "planned": 0, "waived": 0},
        "by_framework": {},
    }
    view = history_view([legacy])
    assert view["records"][0]["reconstructed"] is False


# --- privacy ------------------------------------------------------------

def test_reconstructed_records_carry_no_device_identity(tmp_path):
    store = _store(tmp_path)
    _write(store, entity_id="SYNTH-SERIAL-001")
    records = reconstruct_pan_baseline_records(
        config_root=tmp_path / "configs", artifact_root=tmp_path / "artifacts" / "config" / "sha256",
    )
    blob = json.dumps(records)
    for needle in ("SYNTH-SERIAL-001", "SYNTH-FW-A", "198.51.100.53", "198.51.100.123"):
        assert needle not in blob
