"""0.7.5 — compliance trend layer (append-only ledger).

Contract: docs/history/phase/0_7_5_COMPLIANCE_TREND.md.
No network, no real-env gate — aggregates over an already-built roll-up.
"""
import json
from datetime import datetime, timedelta

from utils.compliance_history import (
    HISTORY_SCHEMA_VERSION,
    LEDGER_RELATIVE_PATH,
    MAX_RECORDS,
    append_run,
    history_view,
    load_history,
    summarise_overview,
)
from utils.compliance_posture import build_compliance_posture
from utils.html_export import run_html_export


def _cfg(available=True):
    if not available:
        return {"available": False}
    return {
        "available": True,
        "fleet": {"tls_verify": True},
        "privacy": {"raw_configuration_blob_included": False,
                    "credentials_included": False, "secret_values_redacted": True},
        "devices": [{
            "vendor_key": "check_point", "name": "corp-gw-01",
            "platform_family": "gaia", "entity_type": "gateway", "connected": True,
            "current_configuration": {"status": "available", "sections": []},
            "alignment": {"findings": []},
        }],
    }


def _record(at, aligned):
    return {
        "run_id": at, "collected_at": at, "aligned_percent": aligned,
        "risk_weighted_alignment_percent": aligned, "catalog_version": "x",
        "framework_catalog_version": "x", "monitored_controls": 3, "total_controls": 10,
        "subjects": 1, "cells": {"aligned": 1, "finding": 0, "unknown": 0, "planned": 0, "waived": 0},
        "by_framework": {},
    }


# --- ledger I/O ------------------------------------------------------

def test_append_load_roundtrip_and_order(tmp_path):
    append_run(tmp_path, _record("2026-06-01T00:00:00Z", 70.0))
    append_run(tmp_path, _record("2026-07-01T00:00:00Z", 75.0))
    rows = load_history(tmp_path)
    assert [r["aligned_percent"] for r in rows] == [70.0, 75.0]        # oldest first
    stored = json.loads((tmp_path / LEDGER_RELATIVE_PATH).read_text(encoding="utf-8"))
    assert stored["schema_version"] == HISTORY_SCHEMA_VERSION


def test_ledger_caps_to_max_records_keeping_newest(tmp_path):
    base = datetime(2026, 1, 1)
    for i in range(MAX_RECORDS + 25):
        at = (base + timedelta(minutes=i)).strftime("%Y-%m-%dT%H:%M:%SZ")
        append_run(tmp_path, _record(at, float(i)))
    rows = load_history(tmp_path)
    assert len(rows) == MAX_RECORDS
    assert rows[-1]["aligned_percent"] == float(MAX_RECORDS + 24)      # newest survived
    assert rows[0]["aligned_percent"] == float(25)                     # first 25 trimmed


def test_load_history_is_fail_safe(tmp_path):
    assert load_history(tmp_path) == []                                # missing
    p = tmp_path / LEDGER_RELATIVE_PATH
    p.parent.mkdir(parents=True)
    p.write_text("{ not json at all", encoding="utf-8")
    assert load_history(tmp_path) == []                                # corrupt
    p.write_text(json.dumps({"records": "nope"}), encoding="utf-8")
    assert load_history(tmp_path) == []                                # wrong shape


def test_append_run_is_best_effort_on_unwritable_root(tmp_path):
    target = tmp_path / "file-not-dir"
    target.write_text("x", encoding="utf-8")
    append_run(target, _record("2026-06-01T00:00:00Z", 70.0))          # must not raise


# --- history_view -------------------------------------------------

def test_history_view_trend_needs_a_prior_record():
    assert history_view([], current_aligned=80.0) == {"records": [], "trend": None}
    v = history_view([_record("2026-06-30T09:00:00Z", 78.0)], current_aligned=83.0,
                     current_risk_weighted=80.0)
    assert v["trend"]["previous_date"] == "2026-06-30"
    assert v["trend"]["delta_aligned_percent"] == 5.0
    assert v["trend"]["direction"] == "up"


def test_history_view_direction_down_and_flat():
    down = history_view([_record("2026-06-30T00:00:00Z", 90.0)], current_aligned=88.0)
    assert down["trend"]["direction"] == "down" and down["trend"]["delta_aligned_percent"] == -2.0
    flat = history_view([_record("2026-06-30T00:00:00Z", 88.0)], current_aligned=88.0)
    assert flat["trend"]["direction"] == "flat"


def test_history_view_projects_and_limits(tmp_path):
    rows = [_record(f"2026-01-{d:02d}T00:00:00Z", float(d)) for d in range(1, 40)]
    v = history_view(rows, current_aligned=99.0, limit=30)
    assert len(v["records"]) == 30
    assert v["records"][0]["date"] == "2026-01-10"                     # oldest 9 dropped
    assert set(v["records"][0]) == {
        "date", "at", "aligned_percent", "risk_weighted_alignment_percent",
        "cells", "monitored_controls", "total_controls",
        "catalog_version", "framework_catalog_version",
    }


# --- build_compliance_posture wiring ----------------------------

def test_overview_history_keys_absent_kwarg_is_additive_only(tmp_path):
    a = build_compliance_posture(_cfg(), None, data_root=tmp_path)
    ov = a["compliance_overview"]
    assert ov["history"] == [] and ov["trend"] is None
    # not-available path carries the same additive shape
    empty = build_compliance_posture(_cfg(available=False), None, data_root=tmp_path)["compliance_overview"]
    assert empty["history"] == [] and empty["trend"] is None


def test_overview_trend_reflects_prior_ledger_record(tmp_path):
    append_run(tmp_path, {**_record("2026-06-30T09:00:00Z", 40.0),
                          "compliance_schema_version": "0.6.6B"})
    ov = build_compliance_posture(
        _cfg(), None, data_root=tmp_path, history=load_history(tmp_path),
    )["compliance_overview"]
    assert len(ov["history"]) == 1
    assert ov["trend"] is not None
    assert ov["trend"]["previous_date"] == "2026-06-30"


def test_summarise_overview_carries_no_identity(tmp_path):
    ov = build_compliance_posture(_cfg(), None, data_root=tmp_path)["compliance_overview"]
    rec = summarise_overview(ov, run_id="run-xyz", collected_at="2026-08-30T00:00:00Z",
                             schema_version="0.6.6B")
    blob = json.dumps(rec)
    for needle in ("corp-gw-01", "cp-001", "192.", "gaia"):
        assert needle not in blob
    assert set(rec) >= {
        "run_id", "collected_at", "compliance_schema_version", "catalog_version",
        "framework_catalog_version", "cells", "aligned_percent",
        "risk_weighted_alignment_percent", "monitored_controls", "total_controls",
        "subjects", "by_framework",
    }


# --- render wiring: write only on a checkpoint -----------------

def _render(tmp_path, **kw):
    unified = tmp_path / "unified.json"
    unified.write_text("[]", encoding="utf-8")
    out = tmp_path / "index.html"
    run_html_export(unified_json=unified, output_html=out, data_root=tmp_path, **kw)
    return out


def test_plain_render_does_not_write_the_ledger(tmp_path):
    _render(tmp_path)
    assert not (tmp_path / LEDGER_RELATIVE_PATH).exists()


def test_checkpoint_render_appends_one_record(tmp_path):
    # unified.json is [] so compliance_ui is not "available" -> still no write
    _render(tmp_path, record_checkpoint=True, run_id="r1")
    assert not (tmp_path / LEDGER_RELATIVE_PATH).exists()


def test_corrupt_ledger_does_not_break_a_render(tmp_path):
    p = tmp_path / LEDGER_RELATIVE_PATH
    p.parent.mkdir(parents=True)
    p.write_text("broken", encoding="utf-8")
    html = _render(tmp_path).read_text(encoding="utf-8")
    assert '"trend":null' in html.replace(" ", "")
