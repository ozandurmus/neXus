"""0.7.7 -- offline compliance-trend retro-fill (PAN baseline reconstruction).

Mines the existing content-addressed config store (CAS) for past PAN
effective-running snapshots and produces ledger-record-shaped dicts a caller
can hand to ``utils.compliance_history.append_reconstructed``.

Deliberately narrow (see docs/history/phase/0_7_7_COMPLIANCE_TREND_RECONSTRUCTION.md
for the full feasibility finding): PAN devices only, the ten deterministic
``DEFAULT_RULE_PACK`` baseline controls only, today's rule pack applied
retroactively. No alignment, no CP, no control-assignment/waiver replay, no
CE.1 user checks, no per-framework breakdown -- none of that is versioned
per historical snapshot in CAS. Every record this module produces carries
``reconstructed: True`` and ``reconstruction_scope`` so it can never be
mistaken for a live checkpoint's full-catalog roll-up.

Read-only: never writes to CAS, contacts a device, or requires credentials.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from lxml import etree

from configuration.current_config_projection import _safe_xml, _scalar_rows
from utils.compliance_catalog import CATALOG_VERSION, severity_weight
from utils.compliance_posture import _evaluate_vendor_neutral_control
from utils.compliance_rulepack import BASELINE_CONTROLS, DEFAULT_RULE_PACK
from utils.config_evidence import ARTIFACT_ROOT, CONFIG_ROOT
from utils.config_history import PAN_EFFECTIVE_ARTIFACT_TYPES, _blob_path_for_metadata, _read_metadata

RECONSTRUCTION_SCOPE = "pan_baseline_rule_pack_only"
RECONSTRUCTION_GAP_MINUTES = 15
_BASELINE_CONTROL_IDS = tuple(str(c["control_id"]) for c in BASELINE_CONTROLS)
_ALIGNED_STATUSES = frozenset({"PASS"})
_DENOMINATOR_STATUSES = frozenset({"PASS", "FINDING", "UNKNOWN", "PLANNED"})


def _parse_iso(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        return datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
    except ValueError:
        return None


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class _Snapshot:
    __slots__ = ("entity_dir", "meta", "collected_at")

    def __init__(self, entity_dir: Path, meta: dict[str, Any], collected_at: datetime) -> None:
        self.entity_dir = entity_dir
        self.meta = meta
        self.collected_at = collected_at


def _iter_pan_snapshots(config_root: Path) -> list[_Snapshot]:
    """Every PAN effective-running snapshot across every source/entity, unsorted."""
    out: list[_Snapshot] = []
    if not config_root.exists():
        return out
    for source_dir in config_root.iterdir():
        if not source_dir.is_dir():
            continue
        for entity_dir in source_dir.iterdir():
            if not entity_dir.is_dir():
                continue
            for snap_dir in entity_dir.iterdir():
                if not snap_dir.is_dir() or snap_dir.name.startswith(".tmp-"):
                    continue
                metadata_path = snap_dir / "metadata.json"
                if not metadata_path.exists():
                    continue
                meta = _read_metadata(metadata_path)
                if meta is None or meta.get("artifact_type") not in PAN_EFFECTIVE_ARTIFACT_TYPES:
                    continue
                dt = _parse_iso(meta.get("collected_at"))
                if dt is None:
                    continue
                out.append(_Snapshot(entity_dir, meta, dt))
    return out


def _bucket_snapshots(
    snapshots: list[_Snapshot], *, gap_minutes: int,
) -> list[list[_Snapshot]]:
    """Single-linkage time clustering: a new bucket starts whenever the gap to
    the previous (by collected_at) snapshot exceeds ``gap_minutes``."""
    if not snapshots:
        return []
    ordered = sorted(snapshots, key=lambda s: s.collected_at)
    gap = timedelta(minutes=gap_minutes)
    buckets: list[list[_Snapshot]] = [[ordered[0]]]
    for snap in ordered[1:]:
        if snap.collected_at - buckets[-1][-1].collected_at <= gap:
            buckets[-1].append(snap)
        else:
            buckets.append([snap])
    return buckets


def _read_pan_sections(meta: dict[str, Any], artifact_root: Path) -> dict[str, list[dict[str, Any]]] | None:
    blob_path = _blob_path_for_metadata(meta, artifact_root)
    if blob_path is None:
        return None
    try:
        content = blob_path.read_bytes()
    except OSError:
        return None
    try:
        root = _safe_xml(content)
    except (etree.XMLSyntaxError, ValueError):
        return None
    sections, _redacted = _scalar_rows(root, alignment_index={})
    return sections


def _evaluate_bucket(bucket: list[_Snapshot], artifact_root: Path) -> dict[str, Any] | None:
    """One reconstructed ledger record from one time-clustered bucket, or
    ``None`` if not a single entity's blob was readable."""
    cells = {"aligned": 0, "finding": 0, "unknown": 0, "planned": 0, "waived": 0}
    weight_num = 0
    weight_den = 0
    has_evidence: dict[str, bool] = {}
    entities_evaluated = 0

    for snap in bucket:
        sections = _read_pan_sections(snap.meta, artifact_root)
        if sections is None:
            continue
        entities_evaluated += 1
        device = {
            "vendor_key": "palo_alto",
            "current_configuration": {
                "status": "available",
                "sections": [
                    {"id": section_id, "settings": rows}
                    for section_id, rows in sections.items()
                ],
            },
        }
        for rule in DEFAULT_RULE_PACK["rules"]:
            control_id = str(rule["control_id"])
            if control_id not in _BASELINE_CONTROL_IDS:
                continue
            result = _evaluate_vendor_neutral_control(device, rule)
            status = str(result.get("status") or "UNKNOWN")
            bucket_key = {
                "PASS": "aligned", "FINDING": "finding", "UNKNOWN": "unknown", "PLANNED": "planned",
            }.get(status)
            if bucket_key is None:
                continue
            cells[bucket_key] += 1
            if bucket_key in ("aligned", "finding"):
                has_evidence[control_id] = True
            else:
                has_evidence.setdefault(control_id, False)
            if status in _DENOMINATOR_STATUSES:
                w = severity_weight(str((result.get("severity") or "informational")))
                weight_den += w
                if status in _ALIGNED_STATUSES:
                    weight_num += w

    if entities_evaluated == 0:
        return None

    denom = sum(cells[k] for k in ("aligned", "finding", "unknown", "planned"))
    aligned_percent = round(cells["aligned"] / denom * 100, 1) if denom else 0.0
    risk_weighted = round(weight_num / weight_den * 100, 1) if weight_den else 0.0
    monitored = sum(1 for v in has_evidence.values() if v)
    bucket_start = min(s.collected_at for s in bucket)

    return {
        "run_id": f"reconstructed:{_iso(bucket_start)}",
        "collected_at": _iso(bucket_start),
        "compliance_schema_version": None,
        "catalog_version": CATALOG_VERSION,
        "framework_catalog_version": None,
        "cells": cells,
        "aligned_percent": aligned_percent,
        "risk_weighted_alignment_percent": risk_weighted,
        "monitored_controls": monitored,
        "total_controls": len(_BASELINE_CONTROL_IDS),
        "subjects": entities_evaluated,
        "by_framework": {},
        "reconstructed": True,
        "reconstruction_scope": RECONSTRUCTION_SCOPE,
    }


def reconstruct_pan_baseline_records(
    config_root: Path | None = None,
    artifact_root: Path | None = None,
    *,
    gap_minutes: int = RECONSTRUCTION_GAP_MINUTES,
) -> list[dict[str, Any]]:
    """Every reconstructable PAN-baseline ledger record, oldest first.

    Read-only over CAS; missing/empty CAS -> ``[]``. No network, no
    credentials, no device identity in the output.
    """
    config_root = Path(config_root) if config_root else CONFIG_ROOT
    artifact_root = Path(artifact_root) if artifact_root else ARTIFACT_ROOT
    snapshots = _iter_pan_snapshots(config_root)
    buckets = _bucket_snapshots(snapshots, gap_minutes=gap_minutes)
    records = [_evaluate_bucket(bucket, artifact_root) for bucket in buckets]
    return [r for r in records if r is not None]
