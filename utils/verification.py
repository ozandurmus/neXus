import ipaddress
import json
import os
from datetime import datetime, timezone
from pathlib import Path

from utils.completeness import build_vsx_completeness
from utils.logger import info, warn


OUTPUT_DIR = Path("output")
VERIFY_FILE = OUTPUT_DIR / "verification.json"
DEFAULT_CP_RAW_MAX_AGE_SECONDS = 3600


def _load_json(path, expected_type=list):
    path = Path(path)
    if not path.exists():
        return None, {"code": "FILE_MISSING", "path": str(path)}

    try:
        with path.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, json.JSONDecodeError) as exc:
        return None, {
            "code": "FILE_INVALID",
            "path": str(path),
            "message": str(exc),
        }

    if expected_type is not None and not isinstance(data, expected_type):
        return None, {
            "code": "UNEXPECTED_ROOT_TYPE",
            "path": str(path),
            "actual": type(data).__name__,
        }

    return data, None


def _load_optional_json(path, expected_type=None):
    path = Path(path)
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None
    if expected_type is not None and not isinstance(data, expected_type):
        return None
    return data


def _routes(item):
    routes = item.get("routes")
    if isinstance(routes, list):
        return routes
    routing = item.get("routing")
    if isinstance(routing, list):
        return routing
    return []


def _interfaces(item):
    interfaces = item.get("interfaces")
    return interfaces if isinstance(interfaces, list) else []


def _interface_names(item):
    return {
        str(iface.get("name")).strip()
        for iface in _interfaces(item)
        if iface.get("name") not in (None, "")
    }


def _route_interface_reference_count(items):
    missing = 0
    examples = []

    for item in items:
        names = _interface_names(item)
        for route in _routes(item):
            iface = str(route.get("interface") or "").strip()
            if not iface or iface in {"-", "None", "none"}:
                continue
            if iface not in names:
                missing += 1
                if len(examples) < 10:
                    examples.append({
                        "device": item.get("device"),
                        "context": item.get("vsys"),
                        "interface": iface,
                        "network": route.get("network"),
                    })

    return missing, examples


def _invalid_network_count(items):
    invalid = 0
    examples = []

    for item in items:
        for route in _routes(item):
            network = route.get("network")
            if not network:
                continue
            try:
                ipaddress.ip_network(str(network), strict=False)
            except ValueError:
                invalid += 1
                if len(examples) < 10:
                    examples.append({
                        "device": item.get("device"),
                        "context": item.get("vsys"),
                        "network": network,
                    })

    return invalid, examples


def _duplicate_identity_count(source, items):
    seen = set()
    duplicates = []

    for item in items:
        if source == "vsx":
            key = (item.get("device"), item.get("vsys"), item.get("vs_id"))
        else:
            key = (item.get("device"),)

        if key in seen:
            duplicates.append(key)
        else:
            seen.add(key)

    return len(duplicates), [list(x) for x in duplicates[:10]]


def _summarize_source(source, items):
    interface_count = sum(len(_interfaces(item)) for item in items)
    route_count = sum(len(_routes(item)) for item in items)
    empty_items = [
        {
            "device": item.get("device"),
            "context": item.get("vsys"),
        }
        for item in items
        if not _interfaces(item) and not _routes(item)
    ]

    route_iface_missing, route_iface_examples = _route_interface_reference_count(items)
    invalid_networks, invalid_network_examples = _invalid_network_count(items)
    duplicate_count, duplicate_examples = _duplicate_identity_count(source, items)

    warnings = []
    if empty_items:
        warnings.append({
            "code": "EMPTY_INVENTORY_OBJECT",
            "count": len(empty_items),
            "examples": empty_items[:10],
        })
    if route_iface_missing:
        warnings.append({
            "code": "ROUTE_INTERFACE_REFERENCE_UNRESOLVED",
            "count": route_iface_missing,
            "examples": route_iface_examples,
            "note": "Observation only; vendor interface inventory scope can differ from route interface scope.",
        })
    if invalid_networks:
        warnings.append({
            "code": "INVALID_ROUTE_NETWORK",
            "count": invalid_networks,
            "examples": invalid_network_examples,
        })
    if duplicate_count:
        warnings.append({
            "code": "DUPLICATE_SOURCE_IDENTITY",
            "count": duplicate_count,
            "examples": duplicate_examples,
        })

    status = "warning" if warnings else "success"
    return {
        "status": status,
        "objects": len(items),
        "interfaces": interface_count,
        "routes": route_count,
        "empty_objects": len(empty_items),
        "warnings": warnings,
    }


def _cp_collection_integrity(cp_items, telemetry):
    warnings = []
    if not telemetry:
        return {
            "status": "warning",
            "telemetry_available": False,
            "warnings": [{
                "code": "CP_COLLECTION_TELEMETRY_UNAVAILABLE",
                "note": "Run CP collection with the current build; it uploads and executes the bundled cp_inventory.sh automatically.",
            }],
        }

    marker = telemetry.get("remote_collection_marker") or {}
    collector = telemetry.get("collector_script") or {}
    summary = telemetry.get("summary") or {}
    raw_files = telemetry.get("remote_files") or []
    command_status = telemetry.get("remote_command_status") or []

    if not collector:
        warnings.append({
            "code": "CP_AUTOMATED_COLLECTOR_EVIDENCE_UNAVAILABLE",
            "note": "This artifact does not prove that the bundled collector was uploaded/executed by the current Python run.",
        })
    else:
        if collector.get("upload_verified") is not True:
            warnings.append({"code": "CP_COLLECTOR_UPLOAD_NOT_VERIFIED"})
        if collector.get("local_sha256") != collector.get("remote_sha256"):
            warnings.append({"code": "CP_COLLECTOR_HASH_MISMATCH"})
        if collector.get("remote_exit_status") != 0:
            warnings.append({
                "code": "CP_COLLECTOR_REMOTE_EXIT_NONZERO",
                "exit_status": collector.get("remote_exit_status"),
            })
        if collector.get("done_marker_seen") is not True:
            warnings.append({"code": "CP_COLLECTOR_DONE_MARKER_NOT_SEEN"})

    if not marker.get("available"):
        warnings.append({
            "code": "CP_COLLECTION_MARKER_UNAVAILABLE",
            "note": "CP data may still be valid, but this run cannot prove when the remote raw set was generated.",
        })

    discovered = marker.get("discovered")

    if isinstance(discovered, int) and len(command_status) != discovered:
        warnings.append({
            "code": "CP_COMMAND_STATUS_COUNT_MISMATCH",
            "expected": discovered,
            "actual": len(command_status),
        })

    def cp_row_management_down(row):
        return (
            row.get("collection_outcome") == "management_down"
            or row.get("interface_error") == "management_down"
        )

    def cp_row_failed(row):
        if cp_row_management_down(row):
            return False
        if row.get("interface_error") not in (None, "", "none"):
            return True
        if row.get("route_error") not in (None, "", "none"):
            return True
        return row.get("interface_rc") not in (0, "0") or row.get("route_rc") not in (0, "0")

    management_down_rows = [row for row in command_status if cp_row_management_down(row)]
    failed_status_rows = [row for row in command_status if cp_row_failed(row)]
    partial_status_rows = [row for row in command_status if row.get("collection_outcome") == "partial"]
    successful_status_rows = [row for row in command_status if row.get("collection_outcome") == "success"]
    collected_expected = len(successful_status_rows) + len(partial_status_rows)

    # Legacy status rows do not have collection_outcome. Preserve their old
    # verification semantics when processing older artifacts.
    if command_status and not any(row.get("collection_outcome") for row in command_status):
        collected_expected = len(command_status) - len(failed_status_rows)

    if command_status and collected_expected != len(cp_items):
        warnings.append({
            "code": "CP_COLLECTED_PARSED_COUNT_MISMATCH",
            "expected_collected": collected_expected,
            "parsed": len(cp_items),
            "discovered": discovered,
            "management_down": len(management_down_rows),
            "collection_failed": len(failed_status_rows),
        })

    # Failed command output is isolated from normal RAW in 0.4.3. Therefore
    # only successful command captures are expected in the parser RAW set.
    expected_raw_files = 0
    for row in command_status:
        if cp_row_management_down(row):
            continue
        if row.get("interface_error") in (None, "", "none") and row.get("interface_rc") in (0, "0"):
            expected_raw_files += 1
        if row.get("route_error") in (None, "", "none") and row.get("route_rc") in (0, "0"):
            expected_raw_files += 1
        if row.get("cluster_probe_error") in ("none",) and row.get("cluster_probe_rc") in (0, "0"):
            expected_raw_files += 1
    if not command_status:
        expected_raw_files = len(cp_items) * 2

    raw_txt_files = summary.get("raw_txt_files")
    if isinstance(raw_txt_files, int) and raw_txt_files != expected_raw_files:
        warnings.append({
            "code": "CP_SUCCESS_RAW_FILE_COUNT_MISMATCH",
            "expected": expected_raw_files,
            "actual": raw_txt_files,
        })

    command_failures = len(failed_status_rows) if command_status else summary.get("command_failures")
    direct_ssh_capable = summary.get("direct_ssh_inventory_cli_capable")
    if isinstance(direct_ssh_capable, int) and direct_ssh_capable > 0:
        warnings.append({
            "code": "CP_DIRECT_SSH_FALLBACK_CAPABLE",
            "count": direct_ssh_capable,
            "note": "Observe-only Phase 0.5.1 evidence: CPRID failed, but read-only direct SSH operational CLI commands succeeded. The SSH output is not promoted to inventory yet.",
        })

    if isinstance(command_failures, int) and command_failures:
        warnings.append({
            "code": "CP_REMOTE_COMMAND_FAILURE",
            "count": command_failures,
            "examples": [
                {
                    "device": row.get("device"),
                    "management_state": row.get("management_state"),
                    "collection_outcome": row.get("collection_outcome"),
                    "interface_rc": row.get("interface_rc"),
                    "route_rc": row.get("route_rc"),
                    "interface_attempts": row.get("interface_attempts"),
                    "route_attempts": row.get("route_attempts"),
                    "interface_error": row.get("interface_error"),
                    "route_error": row.get("route_error"),
                }
                for row in failed_status_rows[:10]
            ],
        })

    if management_down_rows:
        warnings.append({
            "code": "CP_MANAGEMENT_DEVICE_DOWN",
            "count": len(management_down_rows),
            "note": "Operational availability observation, not a collector failure. Remote runtime commands are intentionally skipped for devices explicitly non-communicating in management.",
            "examples": [
                {
                    "device": row.get("device"),
                    "management_state": row.get("management_state"),
                }
                for row in management_down_rows[:10]
            ],
        })

    completed_epoch = marker.get("completed_epoch")
    collection_age_seconds = None
    if isinstance(completed_epoch, int):
        collection_age_seconds = max(0, int(datetime.now(timezone.utc).timestamp() - completed_epoch))
        max_age = int(os.environ.get("FBUDDY_CP_RAW_MAX_AGE_SECONDS", DEFAULT_CP_RAW_MAX_AGE_SECONDS))
        if max_age > 0 and collection_age_seconds > max_age:
            warnings.append({
                "code": "CP_REMOTE_RAW_STALE",
                "age_seconds": collection_age_seconds,
                "max_age_seconds": max_age,
            })

    zero_size_files = [row for row in raw_files if row.get("size_bytes") == 0]
    if zero_size_files:
        warnings.append({"code": "CP_EMPTY_SUCCESS_RAW_FILE", "count": len(zero_size_files)})

    retried_status_rows = [
        row for row in command_status
        if not cp_row_management_down(row)
        and ((row.get("interface_attempts") or 0) > 1 or (row.get("route_attempts") or 0) > 1)
    ]
    recovered_status_rows = [row for row in retried_status_rows if not cp_row_failed(row)]

    return {
        "status": "warning" if warnings else "success",
        "telemetry_available": True,
        "automated_collector": bool(collector),
        "collector_upload_verified": collector.get("upload_verified") if collector else None,
        "collection_marker_available": bool(marker.get("available")),
        "collection_age_seconds": collection_age_seconds,
        "discovered": discovered,
        "management_up_devices": summary.get("management_up_devices") or marker.get("management_up"),
        "management_down_devices": len(management_down_rows) if command_status else summary.get("management_down_devices"),
        "management_unknown_devices": summary.get("management_unknown_devices") or marker.get("management_unknown"),
        "attempted_devices": summary.get("attempted_devices") or marker.get("attempted"),
        "parsed_devices": len(cp_items),
        "raw_txt_files": raw_txt_files,
        "expected_raw_txt_files": expected_raw_files,
        "oldest_file_age_seconds": summary.get("oldest_file_age_seconds"),
        "newest_file_age_seconds": summary.get("newest_file_age_seconds"),
        "command_status": "known" if command_status else "unknown",
        "command_failures": command_failures,
        "successful_devices": len(successful_status_rows) if successful_status_rows else summary.get("successful_devices"),
        "partial_devices": len(partial_status_rows) if command_status else summary.get("partial_devices"),
        "failed_devices": len(failed_status_rows) if command_status else summary.get("failed_devices"),
        "retried_devices": len(retried_status_rows) if command_status else None,
        "recovered_after_retry": len(recovered_status_rows) if command_status else None,
        "parallelism": summary.get("parallelism") or marker.get("parallelism"),
        "collection_mode": summary.get("collection_mode") or marker.get("collection_mode"),
        "direct_ssh_probe_candidates": summary.get("direct_ssh_probe_candidates"),
        "direct_ssh_reachable": summary.get("direct_ssh_reachable"),
        "direct_ssh_authenticated": summary.get("direct_ssh_authenticated"),
        "direct_ssh_inventory_cli_capable": summary.get("direct_ssh_inventory_cli_capable"),
        "direct_ssh_spark_hints": summary.get("direct_ssh_spark_hints"),
        "clusterxl_groups": summary.get("clusterxl_groups"),
        "clusterxl_members": summary.get("clusterxl_members"),
        "clusterxl_virtual_interfaces": summary.get("clusterxl_virtual_interfaces"),
        "warnings": warnings,
    }


def _panorama_collection_integrity(pan_items, telemetry):
    if not telemetry:
        return {
            "status": "warning",
            "telemetry_available": False,
            "warnings": [{
                "code": "PANORAMA_COLLECTION_TELEMETRY_UNAVAILABLE",
                "note": "Operational API collection can still be parsed, but target response success/failure evidence is unavailable.",
            }],
        }

    discovered = telemetry.get("discovered")
    successful = telemetry.get("successful")
    failed = telemetry.get("failed")
    warnings = []

    if isinstance(successful, int) and successful != len(pan_items):
        warnings.append({
            "code": "PANORAMA_SUCCESS_PARSED_COUNT_MISMATCH",
            "successful": successful,
            "parsed": len(pan_items),
        })

    if isinstance(failed, int) and failed:
        failed_examples = []
        for row in telemetry.get("devices") or []:
            if (row.get("interfaces") or {}).get("status") == "failed" or (row.get("routes") or {}).get("status") == "failed":
                if len(failed_examples) < 10:
                    failed_examples.append({
                        "device": row.get("device"),
                        "serial": row.get("serial"),
                        "connected": row.get("connected"),
                        "interface_status": (row.get("interfaces") or {}).get("status"),
                        "route_status": (row.get("routes") or {}).get("status"),
                    })
        warnings.append({
            "code": "PANORAMA_TARGET_COLLECTION_FAILED",
            "count": failed,
            "examples": failed_examples,
        })

    return {
        "status": "warning" if warnings else "success",
        "telemetry_available": True,
        "discovered": discovered,
        "connected_yes": telemetry.get("connected_yes"),
        "connected_no": telemetry.get("connected_no"),
        "successful": successful,
        "failed": failed,
        "parsed_devices": len(pan_items),
        "warnings": warnings,
    }

def run_verification(output_dir=OUTPUT_DIR):
    """Phase 0.4 read-only verification.

    Collection methods and publish behavior are unchanged. The verifier adds
    explicit evidence for CP raw freshness and VSX command/read completeness.
    It remains observe-only and does not block the existing HTML pipeline.
    """
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    info(">>> BASELINE VERIFICATION START")

    files = {
        "cp": output_dir / "cp.json",
        "vsx": output_dir / "vsx.json",
        "panorama": output_dir / "panorama_runtime.json",
        "unified": output_dir / "unified.json",
    }

    loaded = {}
    file_errors = []
    for source, path in files.items():
        data, error = _load_json(path)
        if error:
            file_errors.append({"source": source, **error})
        else:
            loaded[source] = data

    sources = {}
    for source in ("cp", "vsx", "panorama"):
        if source not in loaded:
            sources[source] = {
                "status": "unavailable",
                "objects": 0,
                "interfaces": 0,
                "routes": 0,
                "empty_objects": 0,
                "warnings": [],
            }
        else:
            sources[source] = _summarize_source(source, loaded[source])

    effective_files = [
        output_dir / "cp_effective.json",
        output_dir / "vsx_effective.json",
        output_dir / "panorama_effective.json",
    ]
    effective_counts = []
    for path in effective_files:
        data = _load_optional_json(path, list)
        if isinstance(data, list):
            effective_counts.append(len(data))

    expected_unified = (
        sum(effective_counts)
        if len(effective_counts) == 3
        else sum(sources[s]["objects"] for s in ("cp", "vsx", "panorama"))
    )
    actual_unified = len(loaded.get("unified", []))
    unified_count_match = "unified" in loaded and expected_unified == actual_unified

    merge_warnings = []
    if "unified" not in loaded:
        merge_warnings.append({"code": "UNIFIED_UNAVAILABLE"})
    elif not unified_count_match:
        merge_warnings.append({
            "code": "UNIFIED_OBJECT_COUNT_MISMATCH",
            "expected": expected_unified,
            "actual": actual_unified,
        })

    cp_telemetry = _load_optional_json(output_dir / "cp_telemetry.json", dict)
    cp_integrity = _cp_collection_integrity(loaded.get("cp", []), cp_telemetry)

    vsx_raw = _load_optional_json(output_dir / "vsx_raw.json", list) or []
    vsx_telemetry = _load_optional_json(output_dir / "vsx_telemetry.json", list) or []
    vsx_integrity = build_vsx_completeness(
        vsx_raw,
        loaded.get("vsx", []),
        vsx_telemetry,
    )
    # The full context diagnostics are useful in the support bundle, not in the
    # regular verification file. Keep verification compact and non-sensitive.
    vsx_integrity.pop("contexts", None)

    panorama_telemetry = _load_optional_json(output_dir / "panorama_telemetry.json", dict)
    panorama_integrity = _panorama_collection_integrity(
        loaded.get("panorama", []), panorama_telemetry
    )

    collection_integrity = {
        "cp": cp_integrity,
        "vsx": vsx_integrity,
        "panorama": panorama_integrity,
    }

    run_status = "success"
    if file_errors:
        run_status = "warning"
    if merge_warnings or any(s["status"] != "success" for s in sources.values()):
        run_status = "warning"
    if any(
        item["status"] != "success"
        for item in (cp_integrity, vsx_integrity, panorama_integrity)
    ):
        run_status = "warning"

    report = {
        "phase": "0.5",
        "build": "0.5-final-ui-closure",
        "mode": "observe_only",
        "run_status": run_status,
        "publish_blocking": False,
        "sources": sources,
        "collection_integrity": collection_integrity,
        "merge": {
            "status": "success" if not merge_warnings else "warning",
            "expected_objects": expected_unified,
            "actual_objects": actual_unified,
            "count_match": unified_count_match,
            "warnings": merge_warnings,
        },
        "file_errors": file_errors,
    }

    verify_file = output_dir / VERIFY_FILE.name
    temp_file = verify_file.with_suffix(verify_file.suffix + ".tmp")
    with temp_file.open("w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)
    temp_file.replace(verify_file)

    for source, summary in sources.items():
        info(
            f">>> VERIFY {source.upper()}: {summary['status']} | "
            f"objects={summary['objects']} | interfaces={summary['interfaces']} | "
            f"routes={summary['routes']} | empty={summary['empty_objects']}"
        )

    info(
        ">>> VERIFY CP FRESHNESS: "
        f"{cp_integrity['status']} | marker={cp_integrity.get('collection_marker_available')} | "
        f"age={cp_integrity.get('collection_age_seconds')}s | failures={cp_integrity.get('command_failures')} | "
        f"parallelism={cp_integrity.get('parallelism')} | retried={cp_integrity.get('retried_devices')} | "
        f"recovered={cp_integrity.get('recovered_after_retry')}"
    )
    info(
        ">>> VERIFY VSX COMPLETENESS: "
        f"{vsx_integrity['status']} | raw={vsx_integrity['raw_contexts']} | "
        f"parsed={vsx_integrity['parsed_contexts']} | "
        f"timeouts={vsx_integrity['telemetry']['timeout_count']} | "
        f"prompt_misses={vsx_integrity['telemetry']['prompt_miss_count']} | "
        f"if_delta_ctx={vsx_integrity['raw_to_parsed']['interface_delta_contexts']} | "
        f"route_delta_ctx={vsx_integrity['raw_to_parsed']['route_delta_contexts']}"
    )
    info(
        ">>> VERIFY PANORAMA COLLECTION: "
        f"{panorama_integrity['status']} | discovered={panorama_integrity.get('discovered')} | "
        f"success={panorama_integrity.get('successful')} | failed={panorama_integrity.get('failed')} | "
        f"connected_no={panorama_integrity.get('connected_no')}"
    )

    if report["run_status"] == "warning":
        warn(f">>> BASELINE VERIFICATION WARNING -> {verify_file}")
    else:
        info(f">>> BASELINE VERIFICATION OK -> {verify_file}")

    return report
