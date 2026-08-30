"""0.7.5 -- append-only compliance trend ledger.

One compact aggregate record per full-integration checkpoint, written to
``<data_root>/state/compliance_history.json`` (RuntimeRoot state, gitignored).
Fleet + per-framework aggregates only -- no device identity, no per-subject rows.

Reads are FAIL-SAFE: a missing or corrupt ledger yields an empty history and
never raises (a trend line is a convenience, it must not break a render). This is
the opposite of the fail-closed compliance check pack.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

HISTORY_SCHEMA_VERSION = "0.7.5"
LEDGER_RELATIVE_PATH = "state/compliance_history.json"
MAX_RECORDS = 200          # ledger cap; oldest trimmed on append
PAYLOAD_RECORD_LIMIT = 30  # most-recent N surfaced in the payload

_CELL_KEYS = ("aligned", "finding", "unknown", "planned", "waived")


def _ledger_path(data_root: Any) -> Path:
    return Path(data_root) / LEDGER_RELATIVE_PATH


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _int(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _num(value: Any) -> float:
    try:
        return round(float(value), 1)
    except (TypeError, ValueError):
        return 0.0


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_history(data_root: Any, *, limit: int | None = None) -> list[dict[str, Any]]:
    """Every stored record, oldest first. Missing / unreadable / malformed -> []."""
    try:
        raw = json.loads(_ledger_path(data_root).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    records = raw.get("records") if isinstance(raw, dict) else raw
    if not isinstance(records, list):
        return []
    clean = [r for r in records if isinstance(r, dict) and r.get("collected_at")]
    clean.sort(key=lambda r: str(r.get("collected_at")))
    return clean[-limit:] if limit else clean


def summarise_overview(
    overview: dict[str, Any],
    *,
    run_id: str | None,
    collected_at: str | None = None,
    schema_version: str | None = None,
) -> dict[str, Any]:
    """Build one ledger record from a ``compliance_overview`` payload."""
    ov = _as_dict(overview)
    cells = _as_dict(ov.get("cells"))
    by_fw = _as_dict(ov.get("by_framework"))
    return {
        "run_id": str(run_id) if run_id else None,
        "collected_at": collected_at or _utc_now_iso(),
        "compliance_schema_version": schema_version,
        "catalog_version": ov.get("catalog_version"),
        "framework_catalog_version": ov.get("framework_catalog_version"),
        "cells": {k: _int(cells.get(k)) for k in _CELL_KEYS},
        "aligned_percent": _num(ov.get("aligned_percent")),
        "risk_weighted_alignment_percent": _num(ov.get("risk_weighted_alignment_percent")),
        "monitored_controls": _int(ov.get("monitored_controls")),
        "total_controls": _int(ov.get("total_controls")),
        "subjects": _int(ov.get("subjects")),
        "by_framework": {
            str(name): {
                "aligned": _int(_as_dict(fw).get("aligned")),
                "finding": _int(_as_dict(fw).get("finding")),
                "coverage": _as_dict(fw).get("coverage"),
            }
            for name, fw in by_fw.items()
        },
    }


def append_run(data_root: Any, record: dict[str, Any]) -> None:
    """Append one record, cap to ``MAX_RECORDS`` (newest kept), atomic write.

    Best-effort: an ``OSError`` while writing is swallowed -- the render that
    produced this record has already succeeded and the trend is non-essential.
    """
    records = load_history(data_root)
    records.append(dict(record))
    records = records[-MAX_RECORDS:]
    payload = {
        "schema_version": HISTORY_SCHEMA_VERSION,
        "updated_at": _utc_now_iso(),
        "records": records,
    }
    path = _ledger_path(data_root)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(path)
    except OSError:
        pass


def _project_record(record: Any) -> dict[str, Any]:
    r = _as_dict(record)
    at = str(r.get("collected_at") or "")
    cells = _as_dict(r.get("cells"))
    return {
        "date": at[:10],
        "at": at,
        "aligned_percent": _num(r.get("aligned_percent")),
        "risk_weighted_alignment_percent": _num(r.get("risk_weighted_alignment_percent")),
        "cells": {k: _int(cells.get(k)) for k in _CELL_KEYS},
        "monitored_controls": _int(r.get("monitored_controls")),
        "total_controls": _int(r.get("total_controls")),
        "catalog_version": r.get("catalog_version"),
        "framework_catalog_version": r.get("framework_catalog_version"),
    }


def history_view(
    history: list[dict[str, Any]] | None,
    *,
    current_aligned: float | None = None,
    current_risk_weighted: float | None = None,
    limit: int = PAYLOAD_RECORD_LIMIT,
) -> dict[str, Any]:
    """``{"records": [...oldest->newest...], "trend": {...}|None}`` for the payload.

    ``trend`` compares the current run's roll-up (``current_aligned`` /
    ``current_risk_weighted``) to the newest stored record. During a render the
    ledger's newest record is the *previous* run -- this run is appended only
    afterwards, and only on a full checkpoint.
    """
    records = [_project_record(r) for r in (history or [])][-limit:]
    trend: dict[str, Any] | None = None
    if records and current_aligned is not None:
        prev = records[-1]
        delta_aligned = round(float(current_aligned) - prev["aligned_percent"], 1)
        delta_risk = round(float(current_risk_weighted or 0.0) - prev["risk_weighted_alignment_percent"], 1)
        trend = {
            "previous_date": prev["date"],
            "previous_at": prev["at"],
            "delta_aligned_percent": delta_aligned,
            "delta_risk_weighted_percent": delta_risk,
            "direction": "up" if delta_aligned > 0 else ("down" if delta_aligned < 0 else "flat"),
        }
    return {"records": records, "trend": trend}
