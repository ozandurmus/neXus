from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

BASE_DIR = Path(__file__).resolve().parent.parent
PROJECT_DIR = BASE_DIR / "project"

STATUS_VALUES = {
    "done",
    "in_progress",
    "planned",
    "blocked",
    "deferred",
    "complete",
    "complete_with_followup",
    "automated_validated",
    "real_env_validated",
}
CRITERION_STATES = {"done", "pending", "blocked", "deferred"}


def _load(name: str, default: Any) -> Any:
    path = PROJECT_DIR / name
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return default


def _criterion_progress(feature: dict[str, Any]) -> float:
    criteria = feature.get("criteria") or []
    if not criteria:
        return 100.0 if feature.get("status") == "done" else 0.0
    total = 0.0
    done = 0.0
    for row in criteria:
        try:
            weight = max(0.0, float(row.get("weight", 1)))
        except (TypeError, ValueError):
            weight = 1.0
        total += weight
        if str(row.get("state") or "").lower() == "done":
            done += weight
    return round((done / total * 100.0) if total else 0.0, 1)


def _weighted_progress(items: list[dict[str, Any]]) -> float:
    total = 0.0
    completed = 0.0
    for item in items:
        try:
            weight = max(0.0, float(item.get("weight", 1)))
        except (TypeError, ValueError):
            weight = 1.0
        total += weight
        completed += weight * float(item.get("progress_percent", 0.0)) / 100.0
    return round((completed / total * 100.0) if total else 0.0, 1)




def _metadata_warnings(roadmap: dict[str, Any], features: list[dict[str, Any]], backlog_items: list[dict[str, Any]], builds: list[dict[str, Any]]) -> list[str]:
    warnings: list[str] = []
    ids = [str(row.get("id") or "") for row in features]
    nonempty_ids = [item for item in ids if item]
    duplicates = sorted({item for item in nonempty_ids if nonempty_ids.count(item) > 1})
    if duplicates:
        warnings.append("Duplicate feature IDs: " + ", ".join(duplicates))
    feature_ids = set(nonempty_ids)
    track_ids = set()
    for track in roadmap.get("tracks") or []:
        track_id = str(track.get("id") or "")
        if track_id:
            track_ids.add(track_id)
        missing = [str(fid) for fid in (track.get("feature_ids") or []) if str(fid) not in feature_ids]
        if missing:
            warnings.append(f"Track {track_id or '<unnamed>'} references missing features: " + ", ".join(missing))
    current_track = str(roadmap.get("current_track") or "")
    if current_track and current_track not in track_ids:
        warnings.append(f"Current track is not declared: {current_track}")
    for feature in features:
        status = str(feature.get("status") or "")
        if status and status not in STATUS_VALUES:
            warnings.append(f"Feature {feature.get('id') or '<unnamed>'} has unsupported status: {status}")
        for criterion in feature.get("criteria") or []:
            state = str(criterion.get("state") or "")
            if state and state not in CRITERION_STATES:
                warnings.append(f"Feature {feature.get('id') or '<unnamed>'} criterion {criterion.get('id') or '<unnamed>'} has unsupported state: {state}")
    for item in backlog_items:
        status = str(item.get("status") or "planned")
        if status not in STATUS_VALUES:
            warnings.append(f"Backlog {item.get('id') or '<unnamed>'} has unsupported status: {status}")
    for item in builds:
        status = str(item.get("status") or "")
        if status and status not in STATUS_VALUES:
            warnings.append(f"Build {item.get('build') or '<unnamed>'} has unsupported status: {status}")
    current_build = str(roadmap.get("current_build") or "")
    if current_build and not any(str(item.get("build") or "") == current_build for item in builds):
        warnings.append(f"Current build is absent from build history: {current_build}")
    warnings.extend(_cross_authority_warnings(roadmap, features, builds, backlog_items))
    return warnings


# --- Cross-authority consistency (architecture_convergence) -----------------
#
# The rules above check each file against itself: vocabulary, duplicate ids,
# dangling references. They were all green while roadmap.json claimed the
# current build was `0.7.4` (completed 2026-08-29), build_history.json's newest
# record was OP.0a (2026-09-01), and feature_registry.json still called that
# same OP.0a work `planned`. Three files each claiming a different "now" is the
# exact drift a governance gate exists to catch, so the rules below compare the
# files *against each other*.
#
# Deliberately JSON-only: this function runs inside the report render path, so
# it must not read Markdown. The CURRENT_STATE.md ↔ roadmap agreement check
# lives in tests/test_architecture_convergence.py instead, where it costs
# nothing at runtime.

#: build_history statuses that mean "finished"; a `next` build may not hold one.
_TERMINAL_BUILD_STATUSES = frozenset({
    "done", "complete", "automated_validated", "real_env_validated",
})

#: feature_registry and build_history use slightly different words for the same
#: delivery state. Compare canonical forms, not raw strings.
_STATUS_EQUIVALENCE = {
    "complete": "done",
    "complete_with_followup": "done",
}


def _canonical_status(status: str) -> str:
    return _STATUS_EQUIVALENCE.get(status, status)


#: Delivery states that mean work has actually begun.
_STARTED_STATUSES = frozenset({
    "in_progress", "automated_validated", "real_env_validated", "done", "complete",
    "complete_with_followup",
})


def _cross_authority_warnings(
    roadmap: dict[str, Any],
    features: list[dict[str, Any]],
    builds: list[dict[str, Any]],
    backlog_items: list[dict[str, Any]] | None = None,
) -> list[str]:
    warnings: list[str] = []
    now_next = roadmap.get("now_next") or {}
    now = now_next.get("now") or {}
    nxt = now_next.get("next") or {}

    build_status = {}
    for item in builds:
        key = str(item.get("build") or "")
        if key and key not in build_status:      # newest-first: first wins
            build_status[key] = str(item.get("status") or "")

    newest = str(builds[0].get("build") or "") if builds else ""
    now_build = str(now.get("build") or "")

    # R1 -- build_history.json is newest-first by its own record_contract, so
    # its head record IS the current build. roadmap must agree with it.
    if newest and now_build and now_build != newest:
        warnings.append(
            f"roadmap now_next.now.build ({now_build}) is not the newest build "
            f"history record ({newest}); one of the two is stale"
        )

    # R2 -- current_build and now_next.now.build are two fields claiming the
    # same fact in the same file.
    current_build = str(roadmap.get("current_build") or "")
    if current_build and now_build and current_build != now_build:
        warnings.append(
            f"roadmap current_build ({current_build}) disagrees with "
            f"now_next.now.build ({now_build})"
        )

    # R3 -- an id that is both a feature and a build must carry one status.
    feature_status = {
        str(f.get("id") or ""): str(f.get("status") or "") for f in features
    }
    for fid, fstatus in feature_status.items():
        if not fid or fid not in build_status:
            continue
        bstatus = build_status[fid]
        if not fstatus or not bstatus:
            continue
        if _canonical_status(fstatus) != _canonical_status(bstatus):
            warnings.append(
                f"status conflict for {fid!r}: feature_registry says {fstatus!r}, "
                f"build_history says {bstatus!r}"
            )

    # R4 -- "next" cannot already be finished.
    next_build = str(nxt.get("build") or "")
    if next_build and _canonical_status(build_status.get(next_build, "")) in _TERMINAL_BUILD_STATUSES:
        warnings.append(
            f"roadmap now_next.next.build ({next_build}) is already "
            f"{build_status[next_build]!r} in build history"
        )

    # R5 -- every open decision must name the gate that forces it (the existing
    # `decide_by` field), so a decision cannot sit open indefinitely with nobody
    # able to say which build it is holding up.
    for decision in roadmap.get("open_decisions") or []:
        if str(decision.get("status") or "") != "open":
            continue
        if not str(decision.get("decide_by") or "").strip():
            warnings.append(
                f"open decision {decision.get('id') or '<unnamed>'!r} has no "
                f"decide_by gate"
            )

    # R6 -- backlog and feature_registry describe different things (a debt item
    # can span several features), so their statuses are NOT required to match.
    # But the two impossible directions are still worth catching: a backlog item
    # cannot still be `planned` once its feature has started, and a feature
    # cannot still be `planned` once its backlog item is done.
    backlog_status = {
        str(i.get("id") or ""): str(i.get("status") or "") for i in (backlog_items or [])
    }
    for fid, fstatus in feature_status.items():
        bstatus = backlog_status.get(fid)
        if bstatus is None or not fid:
            continue
        if bstatus == "planned" and fstatus in _STARTED_STATUSES:
            warnings.append(
                f"backlog {fid!r} is still 'planned' while feature_registry says {fstatus!r}"
            )
        if fstatus == "planned" and _canonical_status(bstatus) == "done":
            warnings.append(
                f"feature_registry {fid!r} is still 'planned' while backlog says {bstatus!r}"
            )
    return warnings

def build_project_plan_payload() -> dict[str, Any]:
    roadmap = _load("roadmap.json", {})
    registry = _load("feature_registry.json", {"features": []})
    backlog = _load("backlog.json", {"items": []})
    history = _load("build_history.json", {"builds": []})

    features = [dict(row) for row in (registry.get("features") or []) if isinstance(row, dict)]
    feature_by_id: dict[str, dict[str, Any]] = {}
    for feature in features:
        feature["progress_percent"] = _criterion_progress(feature)
        feature_by_id[str(feature.get("id") or "")] = feature

    tracks = []
    for raw_track in roadmap.get("tracks") or []:
        track = dict(raw_track)
        track_features = [feature_by_id[fid] for fid in track.get("feature_ids") or [] if fid in feature_by_id]
        track["features"] = track_features
        track["progress_percent"] = _weighted_progress(track_features)
        track["done_features"] = sum(1 for feature in track_features if feature.get("status") == "done")
        track["feature_count"] = len(track_features)
        tracks.append(track)

    overall_items = []
    for track in tracks:
        overall_items.append({"weight": track.get("weight", 1), "progress_percent": track.get("progress_percent", 0)})
    overall_progress = _weighted_progress(overall_items)
    current_track_id = roadmap.get("current_track")
    current_track = next((track for track in tracks if track.get("id") == current_track_id), None)

    completed_features = [feature for feature in features if feature.get("status") == "done"]
    completed_features.sort(key=lambda row: str(row.get("introduced") or ""), reverse=True)

    backlog_items = [dict(row) for row in (backlog.get("items") or []) if isinstance(row, dict)]
    builds = [dict(row) for row in (history.get("builds") or []) if isinstance(row, dict)]
    metadata_warnings = _metadata_warnings(roadmap, features, backlog_items, builds)
    backlog_counts: dict[str, int] = {}
    for row in backlog_items:
        status = str(row.get("status") or "planned")
        backlog_counts[status] = backlog_counts.get(status, 0) + 1

    return {
        "schema_version": "1.0",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "current_build": roadmap.get("current_build"),
        "current_track": current_track_id,
        "progress_contract": roadmap.get("progress_contract"),
        "overall_progress_percent": overall_progress,
        "current_track_progress_percent": (current_track or {}).get("progress_percent", 0.0),
        "tracks": tracks,
        "now_next": roadmap.get("now_next") or {},
        "roadmap_notes": roadmap.get("roadmap_notes") or [],
        "backlog": backlog_items,
        "backlog_counts": backlog_counts,
        "completed_features": completed_features,
        "build_history": builds,
        "metadata_warnings": metadata_warnings,
    }
