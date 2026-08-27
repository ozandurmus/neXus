from __future__ import annotations

import copy
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from utils.logger import info, warn

BASE_DIR = Path(__file__).resolve().parent.parent
STATE_DIR = BASE_DIR / "data" / "state"
LKG_FILE = STATE_DIR / "last_known_good.json"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _load_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return default


def _write_json_atomic(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


def _cp_key(item: dict[str, Any]) -> str:
    return str(item.get("device") or "")


def _vsx_key(item: dict[str, Any]) -> str:
    return "|".join(str(item.get(k) or "") for k in ("device", "vsys", "vs_id"))


def _pan_key(item: dict[str, Any]) -> str:
    return str(item.get("serial") or item.get("device") or "")


def _status(
    *,
    run_id: str,
    collected_at: str | None,
    availability_state: str,
    data_state: str,
    fresh: bool,
    last_successful_collection: str | None,
    stale_reason: str | None = None,
    current_run_observed: bool = True,
) -> dict[str, Any]:
    return {
        "fresh": fresh,
        "data_state": data_state,
        "availability_state": availability_state,
        "current_run": run_id,
        "current_run_observed": current_run_observed,
        "collected_at": collected_at,
        "last_successful_collection": last_successful_collection,
        "stale_reason": stale_reason,
    }


def _fresh_item(item: dict[str, Any], run_id: str, collected_at: str | None, availability: str) -> dict[str, Any]:
    result = copy.deepcopy(item)
    result["inventory_status"] = _status(
        run_id=run_id,
        collected_at=collected_at,
        availability_state=availability or "available",
        data_state="live",
        fresh=True,
        last_successful_collection=collected_at,
    )
    return result


def _stale_or_placeholder(
    *,
    source: str,
    identity: str,
    display_device: str,
    run_id: str,
    availability: str,
    reason: str,
    previous: dict[str, Any] | None,
    serial: str | None = None,
) -> dict[str, Any]:
    if previous and isinstance(previous.get("item"), dict):
        result = copy.deepcopy(previous["item"])
        last_success = previous.get("last_successful_collection")
        result["inventory_status"] = _status(
            run_id=run_id,
            collected_at=None,
            availability_state=availability,
            data_state="last_known_good",
            fresh=False,
            last_successful_collection=last_success,
            stale_reason=reason,
        )
        return result

    result: dict[str, Any] = {
        "source": source,
        "device": display_device or identity,
        "interfaces": [],
        "routes": [],
    }
    if serial:
        result["serial"] = serial
    result["inventory_status"] = _status(
        run_id=run_id,
        collected_at=None,
        availability_state=availability,
        data_state="no_data",
        fresh=False,
        last_successful_collection=None,
        stale_reason=reason,
    )
    return result


def _save_lkg(state: dict[str, Any], state_file: Path = LKG_FILE) -> None:
    state["schema_version"] = "0.5"
    state["updated_at"] = _utc_now()
    _write_json_atomic(state_file, state)


def build_failure_aware_snapshot(run_ctx) -> dict[str, Any]:
    state_file = (Path(run_ctx.data_root) / "state" / "last_known_good.json") if hasattr(run_ctx, "data_root") else LKG_FILE
    """Build effective per-source inventory without changing collector methods.

    Fresh entities update local last-known-good state. Devices explicitly observed as
    unavailable in the current run are represented using their last successful data
    when available, otherwise as a zero-data placeholder. This prevents collection
    failure from being confused with device removal.
    """
    stage = Path(run_ctx.stage_dir)
    cp = _load_json(stage / "cp.json", []) or []
    vsx = _load_json(stage / "vsx.json", []) or []
    pan = _load_json(stage / "panorama_runtime.json", []) or []
    cp_tel = _load_json(run_ctx.raw_dir / "cp_telemetry.json", {}) or {}
    pan_tel = _load_json(run_ctx.raw_dir / "panorama_telemetry.json", {}) or {}

    state = _load_json(state_file, {}) or {}
    entities = state.setdefault("entities", {})
    cp_lkg = entities.setdefault("cp", {})
    vsx_lkg = entities.setdefault("vsx", {})
    pan_lkg = entities.setdefault("panorama", {})

    cp_at = (run_ctx.stages.get("cp") or {}).get("completed_at") or run_ctx.created_at
    vsx_at = (run_ctx.stages.get("vsx_parse") or {}).get("completed_at") or run_ctx.created_at
    pan_at = (run_ctx.stages.get("panorama") or {}).get("completed_at") or run_ctx.created_at

    cp_status_rows = {
        str(row.get("device") or ""): row
        for row in (cp_tel.get("remote_command_status") or [])
        if row.get("device")
    }
    current_cp = {_cp_key(item): item for item in cp if _cp_key(item)}
    effective_cp: list[dict[str, Any]] = []

    for key, item in current_cp.items():
        row = cp_status_rows.get(key, {})
        availability = str(row.get("management_state") or "communicating")
        outcome = str(row.get("collection_outcome") or "success")
        if outcome == "partial":
            previous = cp_lkg.get(key)
            if previous and isinstance(previous.get("item"), dict):
                effective_cp.append(_stale_or_placeholder(
                    source="cp", identity=key, display_device=key,
                    run_id=run_ctx.run_id, availability=availability,
                    reason="collection_partial", previous=previous,
                ))
            else:
                partial = copy.deepcopy(item)
                partial["inventory_status"] = _status(
                    run_id=run_ctx.run_id,
                    collected_at=cp_at,
                    availability_state=availability,
                    data_state="partial",
                    fresh=False,
                    last_successful_collection=None,
                    stale_reason="collection_partial",
                )
                effective_cp.append(partial)
            continue

        fresh = _fresh_item(item, run_ctx.run_id, cp_at, availability)
        effective_cp.append(fresh)
        cp_lkg[key] = {"item": copy.deepcopy(item), "last_successful_collection": cp_at}

    for key, row in cp_status_rows.items():
        if key in current_cp:
            continue
        outcome = str(row.get("collection_outcome") or "unknown")
        management_state = str(row.get("management_state") or "unknown")
        if outcome == "management_down":
            reason = "management_unavailable"
            availability = management_state
        else:
            reason = "collection_failed"
            availability = management_state or "unknown"
        effective_cp.append(_stale_or_placeholder(
            source="cp",
            identity=key,
            display_device=key,
            run_id=run_ctx.run_id,
            availability=availability,
            reason=reason,
            previous=cp_lkg.get(key),
        ))

    effective_vsx: list[dict[str, Any]] = []
    for item in vsx:
        key = _vsx_key(item)
        fresh = _fresh_item(item, run_ctx.run_id, vsx_at, "communicating")
        effective_vsx.append(fresh)
        if key:
            vsx_lkg[key] = {"item": copy.deepcopy(item), "last_successful_collection": vsx_at}

    pan_tel_by_serial = {
        str(row.get("serial") or ""): row
        for row in (pan_tel.get("devices") or [])
        if row.get("serial")
    }
    current_pan = {_pan_key(item): item for item in pan if _pan_key(item)}
    effective_pan: list[dict[str, Any]] = []

    for key, item in current_pan.items():
        row = pan_tel_by_serial.get(str(item.get("serial") or ""), {})
        connected = str(row.get("connected") or "yes").lower()
        availability = "communicating" if connected == "yes" else connected or "unknown"
        fresh = _fresh_item(item, run_ctx.run_id, pan_at, availability)
        effective_pan.append(fresh)
        pan_lkg[key] = {"item": copy.deepcopy(item), "last_successful_collection": pan_at}

    for serial, row in pan_tel_by_serial.items():
        key = serial or str(row.get("device") or "")
        if key in current_pan:
            continue
        connected = str(row.get("connected") or "unknown").lower()
        interfaces_status = str((row.get("interfaces") or {}).get("status") or "")
        routes_status = str((row.get("routes") or {}).get("status") or "")
        if connected == "no":
            reason = "management_disconnected"
            availability = "disconnected"
        elif "failed" in {interfaces_status, routes_status}:
            reason = "collection_failed"
            availability = "communicating" if connected == "yes" else connected
        else:
            reason = "collection_unavailable"
            availability = connected or "unknown"
        effective_pan.append(_stale_or_placeholder(
            source="panorama",
            identity=key,
            display_device=str(row.get("device") or serial),
            serial=serial,
            run_id=run_ctx.run_id,
            availability=availability,
            reason=reason,
            previous=pan_lkg.get(key),
        ))

    _save_lkg(state, state_file)

    outputs = {
        "cp_effective.json": effective_cp,
        "vsx_effective.json": effective_vsx,
        "panorama_effective.json": effective_pan,
    }
    for name, payload in outputs.items():
        _write_json_atomic(stage / name, payload)

    status_counts = {
        "live": 0,
        "last_known_good": 0,
        "no_data": 0,
        "partial": 0,
    }
    for item in effective_cp + effective_vsx + effective_pan:
        data_state = (item.get("inventory_status") or {}).get("data_state")
        if data_state in status_counts:
            status_counts[data_state] += 1

    summary = {
        "run_id": run_ctx.run_id,
        "cp": {"effective": len(effective_cp), "fresh": len(current_cp), "unavailable": max(0, len(effective_cp) - len(current_cp))},
        "vsx": {"effective": len(effective_vsx), "fresh": len(effective_vsx), "unavailable": 0},
        "panorama": {"effective": len(effective_pan), "fresh": len(current_pan), "unavailable": max(0, len(effective_pan) - len(current_pan))},
        "data_states": status_counts,
        "last_known_good_file": (
            str(state_file)
        ),
    }
    _write_json_atomic(stage / "snapshot_status.json", summary)
    info(
        ">>> FAILURE-AWARE SNAPSHOT READY "
        f"(live={status_counts['live']} lkg={status_counts['last_known_good']} no_data={status_counts['no_data']})"
    )
    if status_counts["last_known_good"] or status_counts["no_data"]:
        warn(
            ">>> INVENTORY CONTAINS NON-LIVE ENTITIES "
            f"(lkg={status_counts['last_known_good']} no_data={status_counts['no_data']})"
        )
    return summary
