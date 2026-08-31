"""SecurityExpert 0.6.3 — Configuration History Service.

Read-only timeline query and safe normalized diff for immutable
ConfigEvidenceStore metadata.  Nothing in this module writes to storage,
contacts a device, increases polling/concurrency, or emits secrets.

Key contracts (from PHASE0_6_3_UNIFIED_CONFIGURATION_HISTORY_DIFF_UX.md):
- Scope: single source / entity_id / artifact_type.
- Timeline rows never contain sha256, object paths, management_ip, credentials,
  raw configuration, or Gaia lines.
- PAN: safe diff reuses the existing allowlisted structured projection only.
- CP: INSUFFICIENT_EVIDENCE — no raw/redacted Gaia text diff in this increment.
- SAME events appear in timeline but never create a fabricated diff result.
- Any failure is an explicit safe unavailable state; no raw-content fallback.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from lxml import etree

from configuration.current_config_projection import (
    SECTION_ORDER,
    _as_dict,
    _safe_xml,
    _scalar_rows,
)
from utils.config_evidence import CONFIG_ROOT, ARTIFACT_ROOT
from utils.evidence_backend import ConfigSnapshotBackend, select_config_snapshot_backend

HISTORY_SCHEMA_VERSION = "0.6.3"
MAX_TIMELINE_EVENTS = 50
MAX_DIFF_ROWS = 100
PAN_EFFECTIVE_ARTIFACT_TYPES = frozenset({"effective", "pan_effective_running"})
CP_ARTIFACT_TYPES = frozenset({
    "gaia_show_configuration_redacted",
    "gaia_vsx_context_show_configuration_redacted",
})


# ---------------------------------------------------------------------------
# Data shapes
# ---------------------------------------------------------------------------

@dataclass
class TimelineEvent:
    id: str
    collected_at: str
    change_state: str
    artifact_type: str
    status: str
    comparison_eligible: bool


@dataclass
class ArtifactTimeline:
    artifact_type: str
    artifact_label: str
    events: list[TimelineEvent] = field(default_factory=list)
    truncated: bool = False
    skipped_malformed: int = 0


@dataclass
class DiffRow:
    section: str
    setting: str
    change: str          # "added" | "removed" | "modified"
    before: str | None
    after: str | None
    scope: str           # "local" | "central" | "member_specific" | "unknown"


@dataclass
class PairResult:
    older_event_id: str
    newer_event_id: str
    older_collected_at: str
    newer_collected_at: str
    status: str          # "available" | "insufficient_evidence" | "unavailable"
    reason: str | None
    diff_rows: list[DiffRow] = field(default_factory=list)
    withheld_count: int = 0
    truncated: bool = False


@dataclass
class DeviceHistory:
    status: str          # "available" | "insufficient_evidence" | "unavailable"
    scope: str
    artifacts: list[ArtifactTimeline] = field(default_factory=list)
    pair_results: list[PairResult] = field(default_factory=list)
    privacy: dict[str, bool] = field(default_factory=lambda: {
        "raw_configuration_included": False,
        "value_hashes_included": False,
        "artifact_paths_included": False,
        "credentials_included": False,
    })


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _artifact_label(artifact_type: str) -> str:
    labels = {
        "effective": "Effective-running",
        "pan_effective_running": "Effective-running",
        "active": "Local active",
        "merged": "Merged",
        "panorama_active_management_config": "Panorama control",
        "gaia_show_configuration_redacted": "Gaia redacted actual",
        "gaia_vsx_context_show_configuration_redacted": "Gaia VS context (redacted)",
    }
    return labels.get(artifact_type, artifact_type.replace("_", " ").title())


def _parse_collected_at(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None


def _snapshot_sort_key(meta: dict[str, Any], snap_name: str) -> tuple[str, str]:
    collected_at = str(meta.get("collected_at") or "")
    # descending: negate by sorting ascending on reversed ISO string
    return (collected_at, snap_name)


def _blob_path_for_metadata(
    meta: dict[str, Any],
    artifact_root: Path,
) -> Path | None:
    storage = _as_dict(meta.get("storage"))
    obj_path_str = storage.get("object_path")
    if not obj_path_str:
        return None
    obj_path = Path(str(obj_path_str))
    # object_path is relative to the store's data root (configs/../ = project root).
    # artifact_root is  <data_root>/artifacts/config/sha256  → 3 parents up.
    if not obj_path.is_absolute():
        data_root = artifact_root.parent.parent.parent
        obj_path = data_root / obj_path
    if not obj_path.exists():
        return None
    return obj_path


def _valid_metadata(payload: dict[str, Any] | None) -> dict[str, Any] | None:
    """Apply the stored-record validity rules, independent of storage backend.

    Kept here rather than in the backend so both backends run identical logic
    (DEV.3.3 contract, amendment A2).
    """
    if not isinstance(payload, dict):
        return None
    if payload.get("status") != "success" or not payload.get("sha256"):
        return None
    if not payload.get("collected_at") or not payload.get("artifact_type"):
        return None
    return payload


def _build_timeline(
    backend: ConfigSnapshotBackend, source: str, entity_id: str, artifact_type: str
) -> ArtifactTimeline:
    timeline = ArtifactTimeline(
        artifact_type=artifact_type,
        artifact_label=_artifact_label(artifact_type),
    )

    candidates: list[tuple[datetime, str, dict[str, Any]]] = []
    for snapshot_id, raw in backend.list_snapshots(source=source, entity_id=entity_id):
        meta = _valid_metadata(raw)
        if meta is None:
            timeline.skipped_malformed += 1
            continue
        if meta.get("artifact_type") != artifact_type:
            continue
        dt = _parse_collected_at(meta.get("collected_at"))
        if dt is None:
            timeline.skipped_malformed += 1
            continue
        candidates.append((dt, snapshot_id, meta))

    # Descending chronological; snapshot directory name as tie-breaker.
    candidates.sort(key=lambda c: (c[0], c[1]), reverse=True)

    if len(candidates) > MAX_TIMELINE_EVENTS:
        timeline.truncated = True
        candidates = candidates[:MAX_TIMELINE_EVENTS]

    is_pan = artifact_type in PAN_EFFECTIVE_ARTIFACT_TYPES
    for dt, snap_name, meta in candidates:
        change_state = str(meta.get("change_state") or "unknown").lower()
        eligible = is_pan and change_state in ("first", "changed")
        timeline.events.append(TimelineEvent(
            id=snap_name,
            collected_at=str(meta.get("collected_at") or ""),
            change_state=change_state,
            artifact_type=artifact_type,
            status="available",
            comparison_eligible=eligible,
        ))

    return timeline


# ---------------------------------------------------------------------------
# PAN safe normalized diff
# ---------------------------------------------------------------------------

def _read_pan_object(meta: dict[str, Any], artifact_root: Path) -> etree._Element | None:
    """Resolve and parse a PAN XML object from the CAS store."""
    blob_path = _blob_path_for_metadata(meta, artifact_root)
    if blob_path is None:
        return None
    try:
        content = blob_path.read_bytes()
    except OSError:
        return None
    try:
        return _safe_xml(content)
    except (etree.XMLSyntaxError, ValueError):
        return None


def _project_rows(root: etree._Element) -> dict[str, list[dict[str, Any]]]:
    """Return safe scalar rows without alignment context (history diff only)."""
    sections, _ = _scalar_rows(root, alignment_index={})
    return sections


def _rows_to_index(sections: dict[str, list[dict[str, Any]]]) -> dict[tuple[str, str, str | None], dict[str, Any]]:
    """Build a lookup: (section, setting, context) → row."""
    index: dict[tuple[str, str, str | None], dict[str, Any]] = {}
    for section_id, rows in sections.items():
        for row in rows:
            key = (section_id, str(row.get("setting") or ""), row.get("context"))
            index[key] = row
    return index


def _compare_sections(
    older_sections: dict[str, list[dict[str, Any]]],
    newer_sections: dict[str, list[dict[str, Any]]],
) -> tuple[list[DiffRow], int]:
    """Return (diff_rows, withheld_count) for safe allowlisted section rows.

    Rules:
    - A field is compared by (section, setting, context) composite key.
    - SAME content produces no diff entry.
    - network_summary structural counts are compared without row context.
    - At most MAX_DIFF_ROWS safe rows are emitted; truncation is a boolean flag.
    """
    diff_rows: list[DiffRow] = []
    withheld = 0

    older_idx = _rows_to_index(older_sections)
    newer_idx = _rows_to_index(newer_sections)
    all_keys = set(older_idx) | set(newer_idx)

    section_priority = {s: i for i, s in enumerate(SECTION_ORDER)}

    for key in sorted(all_keys, key=lambda k: (section_priority.get(k[0], 99), k[1], k[2] or "")):
        if len(diff_rows) >= MAX_DIFF_ROWS:
            break
        older_row = older_idx.get(key)
        newer_row = newer_idx.get(key)
        section, setting, context = key

        older_val = str(older_row["value"]) if older_row else None
        newer_val = str(newer_row["value"]) if newer_row else None

        if older_val == newer_val:
            continue

        scope = str(newer_row.get("origin") or older_row.get("origin") or "unknown")
        if scope in ("central", "local_override", "member_specific", "local", "effective"):
            scope = scope
        else:
            scope = "unknown"

        if older_row is None:
            change = "added"
        elif newer_row is None:
            change = "removed"
        else:
            change = "modified"

        diff_rows.append(DiffRow(
            section=section,
            setting=setting if not context else f"{setting} ({context})",
            change=change,
            before=older_val,
            after=newer_val,
            scope=scope,
        ))

    return diff_rows, withheld


def _compute_pan_pair(
    *,
    older_snap_name: str,
    newer_snap_name: str,
    older_meta: dict[str, Any],
    newer_meta: dict[str, Any],
    artifact_root: Path,
) -> PairResult:
    older_at = str(older_meta.get("collected_at") or "")
    newer_at = str(newer_meta.get("collected_at") or "")

    older_root = _read_pan_object(older_meta, artifact_root)
    newer_root = _read_pan_object(newer_meta, artifact_root)

    if older_root is None or newer_root is None:
        return PairResult(
            older_event_id=older_snap_name,
            newer_event_id=newer_snap_name,
            older_collected_at=older_at,
            newer_collected_at=newer_at,
            status="unavailable",
            reason="historical_object_not_readable",
        )

    older_sections = _project_rows(older_root)
    newer_sections = _project_rows(newer_root)
    diff_rows, withheld = _compare_sections(older_sections, newer_sections)

    all_keys_count = len(set(_rows_to_index(older_sections)) | set(_rows_to_index(newer_sections)))
    truncated = len(diff_rows) >= MAX_DIFF_ROWS and all_keys_count > MAX_DIFF_ROWS

    return PairResult(
        older_event_id=older_snap_name,
        newer_event_id=newer_snap_name,
        older_collected_at=older_at,
        newer_collected_at=newer_at,
        status="available",
        reason=None,
        diff_rows=diff_rows,
        withheld_count=withheld,
        truncated=truncated,
    )


# ---------------------------------------------------------------------------
# Public service interface
# ---------------------------------------------------------------------------

class ConfigHistoryService:
    """Read-only history query and diff service.

    Operates on existing ConfigEvidenceStore metadata; never writes, migrates,
    deletes, changes retention, or contacts devices.
    """

    def __init__(
        self,
        config_root: Path | None = None,
        artifact_root: Path | None = None,
        backend: ConfigSnapshotBackend | None = None,
    ) -> None:
        self.config_root = Path(config_root) if config_root else CONFIG_ROOT
        self.artifact_root = Path(artifact_root) if artifact_root else ARTIFACT_ROOT
        self.backend = (
            backend if backend is not None else select_config_snapshot_backend(root=self.config_root)
        )

    def _read_snap_metadata(
        self, source: str, entity_id: str, snap_name: str, artifact_type: str
    ) -> dict[str, Any] | None:
        raw = self.backend.get_snapshot(source=source, entity_id=entity_id, snapshot_id=snap_name)
        meta = _valid_metadata(raw)
        if meta is None or meta.get("artifact_type") != artifact_type:
            return None
        return meta

    def get_device_history(
        self,
        *,
        source: str,
        entity_id: str,
        artifact_type: str,
    ) -> DeviceHistory:
        """Return a safe, bounded history timeline and the latest CHANGED pair.

        Caller supplies the exact stored source/entity/artifact_type scope.
        Never reads raw configuration, emits raw XML or Gaia lines.
        """
        # CP: timeline only; diff not supported yet.
        if artifact_type in CP_ARTIFACT_TYPES:
            timeline = _build_timeline(self.backend, source, entity_id, artifact_type)
            status = "available" if timeline.events else "insufficient_evidence"
            return DeviceHistory(
                status=status,
                scope="single_entity_single_artifact",
                artifacts=[timeline],
                pair_results=[
                    PairResult(
                        older_event_id="",
                        newer_event_id="",
                        older_collected_at="",
                        newer_collected_at="",
                        status="insufficient_evidence",
                        reason="cp_raw_text_diff_not_supported_in_0_6_3",
                    )
                ] if timeline.events else [],
            )

        # PAN effective-running: timeline + latest CHANGED pair.
        if artifact_type not in PAN_EFFECTIVE_ARTIFACT_TYPES:
            return DeviceHistory(
                status="insufficient_evidence",
                scope="single_entity_single_artifact",
            )

        timeline = _build_timeline(self.backend, source, entity_id, artifact_type)

        if not timeline.events:
            return DeviceHistory(
                status="insufficient_evidence",
                scope="single_entity_single_artifact",
                artifacts=[timeline],
            )

        pair_results: list[PairResult] = []
        # Find latest CHANGED event and compute diff against its previous.
        eligible = [e for e in timeline.events if e.comparison_eligible]
        if eligible:
            newer_event = eligible[0]
            newer_meta = self._read_snap_metadata(source, entity_id, newer_event.id, artifact_type)
            if newer_meta is not None:
                previous_snap = str(newer_meta.get("previous_snapshot") or "")
                if previous_snap:
                    older_meta = self._read_snap_metadata(source, entity_id, previous_snap, artifact_type)
                    if older_meta is not None:
                        pair_results.append(_compute_pan_pair(
                            older_snap_name=previous_snap,
                            newer_snap_name=newer_event.id,
                            older_meta=older_meta,
                            newer_meta=newer_meta,
                            artifact_root=self.artifact_root,
                        ))
                    else:
                        pair_results.append(PairResult(
                            older_event_id=previous_snap,
                            newer_event_id=newer_event.id,
                            older_collected_at="",
                            newer_collected_at=newer_event.collected_at,
                            status="unavailable",
                            reason="previous_snapshot_metadata_not_readable",
                        ))

        return DeviceHistory(
            status="available",
            scope="single_entity_single_artifact",
            artifacts=[timeline],
            pair_results=pair_results,
        )


# ---------------------------------------------------------------------------
# Safe serialization for UI payload
# ---------------------------------------------------------------------------

def _serialise_diff_row(row: DiffRow) -> dict[str, Any]:
    return {
        "section": row.section,
        "setting": row.setting,
        "change": row.change,
        "before": row.before,
        "after": row.after,
        "scope": row.scope,
    }


def _serialise_pair(pair: PairResult) -> dict[str, Any]:
    return {
        "older_event_id": pair.older_event_id,
        "newer_event_id": pair.newer_event_id,
        "older_collected_at": pair.older_collected_at,
        "newer_collected_at": pair.newer_collected_at,
        "status": pair.status,
        "reason": pair.reason,
        "diff_rows": [_serialise_diff_row(r) for r in pair.diff_rows],
        "diff_row_count": len(pair.diff_rows),
        "withheld_count": pair.withheld_count,
        "truncated": pair.truncated,
    }


def _serialise_event(event: TimelineEvent) -> dict[str, Any]:
    return {
        "id": event.id,
        "collected_at": event.collected_at,
        "change_state": event.change_state,
        "artifact_type": event.artifact_type,
        "status": event.status,
        "comparison_eligible": event.comparison_eligible,
    }


def build_history_payload(history: DeviceHistory) -> dict[str, Any]:
    """Convert a DeviceHistory into a safe UI-embeddable dict.

    The output never contains sha256, object paths, management_ip, credentials,
    raw configuration bytes or Gaia lines.
    """
    return {
        "schema_version": HISTORY_SCHEMA_VERSION,
        "status": history.status,
        "scope": history.scope,
        "artifacts": [
            {
                "artifact_type": art.artifact_type,
                "artifact_label": art.artifact_label,
                "event_count": len(art.events),
                "truncated": art.truncated,
                "skipped_malformed": art.skipped_malformed,
                "events": [_serialise_event(e) for e in art.events],
            }
            for art in history.artifacts
        ],
        "pair_results": [_serialise_pair(p) for p in history.pair_results],
        "privacy": dict(history.privacy),
    }
