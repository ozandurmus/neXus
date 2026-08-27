from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import zipfile
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from utils.completeness import build_vsx_completeness
from checkpoint.direct_ssh_probe import support_safe_probe
from utils.logger import info, warn
from utils.runtime_paths import default_output_root

BASE_DIR = Path(__file__).resolve().parent.parent
RUNS_DIR = BASE_DIR / "data" / "runs"
SUPPORT_KEY_FILE = BASE_DIR / "data" / ".support_hmac.key"
OUTPUT_DIR = default_output_root(repository_root=BASE_DIR)


def _load_json(path: Path, default=None):
    try:
        with path.open("r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return default


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _get_support_key(support_key_file=SUPPORT_KEY_FILE) -> bytes:
    support_key_file = Path(support_key_file)
    env_key = os.getenv("FBUDDY_SUPPORT_HASH_KEY")
    if env_key:
        return env_key.encode("utf-8")

    support_key_file.parent.mkdir(parents=True, exist_ok=True)
    if support_key_file.exists():
        key = support_key_file.read_bytes().strip()
        if key:
            return key

    key = secrets.token_hex(32).encode("ascii")
    support_key_file.write_bytes(key)
    try:
        os.chmod(support_key_file, 0o600)
    except OSError:
        pass
    return key


class Tokenizer:
    def __init__(self, key: bytes):
        self.key = key

    def token(self, kind: str, value: Any, length: int = 14) -> str | None:
        if value in (None, ""):
            return None
        text = str(value)
        digest = hmac.new(self.key, f"{kind}:{text}".encode("utf-8"), hashlib.sha256).hexdigest()
        return f"{kind.upper()}_{digest[:length]}"

    def network_token(self, value: Any) -> dict[str, Any] | None:
        if value in (None, ""):
            return None
        text = str(value)
        prefix = None
        if "/" in text:
            try:
                prefix = int(text.rsplit("/", 1)[1])
            except ValueError:
                prefix = None
        return {"id": self.token("net", text), "prefix": prefix}


SENSITIVE_KEYS = {
    "device": "dev",
    "device_ip": "ip",
    "management_ip": "ip",
    "ip": "ip",
    "next_hop": "ip",
    "serial": "serial",
    "vsys": "ctx",
    "context": "ctx",
    "zone": "zone",
    "vr": "vr",
    "interface": "if",
    "name": "name",
    "network": "net",
    "username": "user",
    "hostname": "dev",
    "path": "path",
    "error": "err",
    "message": "msg",
}


def _sanitize_value(key: str, value: Any, tok: Tokenizer):
    if key == "network":
        return tok.network_token(value)
    kind = SENSITIVE_KEYS.get(key)
    if kind:
        return tok.token(kind, value)
    if isinstance(value, dict):
        return _sanitize_dict(value, tok)
    if isinstance(value, list):
        return [_sanitize_value("", item, tok) for item in value]
    return value


def _sanitize_dict(data: dict[str, Any], tok: Tokenizer) -> dict[str, Any]:
    return {key: _sanitize_value(key, value, tok) for key, value in data.items()}


def _routes(item):
    routes = item.get("routes")
    if isinstance(routes, list):
        return routes
    routing = item.get("routing")
    return routing if isinstance(routing, list) else []


def _interfaces(item):
    value = item.get("interfaces")
    return value if isinstance(value, list) else []


def _entity_key(source: str, item: dict[str, Any]) -> str:
    if source == "vsx":
        return "|".join(str(item.get(k) or "") for k in ("device", "vsys", "vs_id"))
    if source == "panorama":
        return "|".join(str(item.get(k) or "") for k in ("device", "serial"))
    return str(item.get("device") or "")


def _canonical_entity(item: dict[str, Any]) -> dict[str, Any]:
    interfaces = []
    for iface in _interfaces(item):
        entry = {
            "name": iface.get("name"),
            "type": iface.get("type"),
            "parent": iface.get("parent"),
            "state": iface.get("state"),
            "vr": iface.get("vr"),
            "vsys": iface.get("vsys"),
            "zone": iface.get("zone"),
        }
        if isinstance(iface.get("ips"), list):
            entry["addresses"] = sorted(
                (
                    str(ip.get("ip") or ""),
                    ip.get("prefix"),
                    str(ip.get("network") or ""),
                )
                for ip in iface["ips"]
            )
        else:
            entry["addresses"] = [
                (
                    str(iface.get("ip") or ""),
                    iface.get("prefix"),
                    str(iface.get("network") or ""),
                )
            ]
        interfaces.append(entry)

    routes = [
        {
            "network": route.get("network"),
            "next_hop": route.get("next_hop"),
            "interface": route.get("interface"),
            "type": route.get("type"),
            "vr": route.get("vr"),
            "protocol": route.get("protocol"),
        }
        for route in _routes(item)
    ]

    interfaces.sort(key=lambda x: json.dumps(x, sort_keys=True, ensure_ascii=False))
    routes.sort(key=lambda x: json.dumps(x, sort_keys=True, ensure_ascii=False))
    return {"interfaces": interfaces, "routes": routes}


def _fingerprint(value: Any) -> str:
    blob = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()


def _source_stats(items: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "objects": len(items),
        "interfaces": sum(len(_interfaces(item)) for item in items),
        "routes": sum(len(_routes(item)) for item in items),
        "empty_objects": sum(1 for item in items if not _interfaces(item) and not _routes(item)),
    }


def _anonymize_verification(verification: dict[str, Any], tok: Tokenizer) -> dict[str, Any]:
    return _sanitize_dict(verification, tok)


def _build_entity_fingerprints(source: str, items: list[dict[str, Any]], tok: Tokenizer) -> list[dict[str, Any]]:
    rows = []
    for item in items:
        identity = _entity_key(source, item)
        rows.append({
            "entity": tok.token("entity", f"{source}:{identity}"),
            "source": source,
            "interfaces": len(_interfaces(item)),
            "routes": len(_routes(item)),
            "fingerprint": _fingerprint(_canonical_entity(item)),
        })
    return rows


ROUTE_LINE_RE = re.compile(r"^(default\b|\S+(?:/\d+)?\s+(?:via\s+\S+\s+)?dev\s+\S+)")


def _vsx_raw_diagnostics(raw_items: list[dict[str, Any]], parsed_items: list[dict[str, Any]], tok: Tokenizer):
    parsed_map = {
        (str(x.get("device")), str(x.get("vsys")), str(x.get("vs_id"))): x
        for x in parsed_items
    }
    rows = []
    for item in raw_items:
        key = (str(item.get("device")), str(item.get("vsys")), str(item.get("vs_id")))
        parsed = parsed_map.get(key, {})
        raw_if = str(item.get("interfaces_raw") or "")
        raw_rt = str(item.get("routes_raw") or "")
        interface_candidates = sum(
            1 for line in raw_if.splitlines()
            if "Link encap" in line and line.strip().split()[0] != "lo"
        )
        route_candidates = sum(1 for line in raw_rt.splitlines() if ROUTE_LINE_RE.match(line.strip()))
        rows.append({
            "entity": tok.token("entity", "vsx:" + "|".join(key)),
            "device": tok.token("dev", item.get("device")),
            "context": tok.token("ctx", item.get("vsys")),
            "vs_id": item.get("vs_id"),
            "raw": {
                "interface_bytes": len(raw_if.encode("utf-8", errors="ignore")),
                "interface_lines": len(raw_if.splitlines()),
                "interface_prompt_seen": bool(re.search(r"\[Expert@[^\]]+\]#\s*$", raw_if, re.MULTILINE)),
                "interface_candidates": interface_candidates,
                "route_bytes": len(raw_rt.encode("utf-8", errors="ignore")),
                "route_lines": len(raw_rt.splitlines()),
                "route_prompt_seen": bool(re.search(r"\[Expert@[^\]]+\]#\s*$", raw_rt, re.MULTILINE)),
                "route_candidates": route_candidates,
            },
            "parsed": {
                "interfaces": len(_interfaces(parsed)),
                "routes": len(_routes(parsed)),
            },
            "delta": {
                "interfaces": interface_candidates - len(_interfaces(parsed)),
                "routes": route_candidates - len(_routes(parsed)),
            },
        })
    return rows


def _anomaly_rollup(verification: dict[str, Any], tok: Tokenizer) -> dict[str, Any]:
    findings = []
    for source, summary in (verification.get("sources") or {}).items():
        for warning_item in summary.get("warnings") or []:
            finding = {
                "scope": "source",
                "source": source,
                "code": warning_item.get("code"),
                "count": warning_item.get("count"),
                "examples": [],
            }
            for example in warning_item.get("examples") or []:
                finding["examples"].append(_sanitize_dict(example, tok))
            findings.append(finding)

    for source, integrity in (verification.get("collection_integrity") or {}).items():
        for warning_item in integrity.get("warnings") or []:
            findings.append({
                "scope": "collection_integrity",
                "source": source,
                "code": warning_item.get("code"),
                "count": warning_item.get("count"),
                "details": _sanitize_dict(warning_item, tok),
            })
    return {"findings": findings}


def _integrity_rows(run_dir: Path):
    rows = []
    for path in sorted(run_dir.rglob("*")):
        if not path.is_file():
            continue
        if "support" in path.parts or path.name == SUPPORT_KEY_FILE.name:
            continue
        rel = path.relative_to(run_dir).as_posix()
        row = {
            "path": rel,
            "size_bytes": path.stat().st_size,
            "sha256": _sha256_file(path),
        }
        if path.suffix.lower() == ".json":
            data = _load_json(path, default=None)
            row["json_valid"] = data is not None
            if isinstance(data, list):
                row["objects"] = len(data)
        rows.append(row)
    return rows


def _latest_run_dir() -> Path:
    if not runs_dir.exists():
        raise RuntimeError("No run directory exists yet. Run a full inventory first.")
    candidates = [p for p in runs_dir.iterdir() if p.is_dir()]
    if not candidates:
        raise RuntimeError("No run directory exists yet. Run a full inventory first.")
    return max(candidates, key=lambda p: p.stat().st_mtime)


def run_support_bundle(run_dir: Path | None = None, *, data_root=None, output_root=None) -> Path:
    data_root = Path(data_root) if data_root is not None else BASE_DIR / "data"
    output_root = Path(output_root) if output_root is not None else OUTPUT_DIR
    runs_dir = data_root / "runs"
    support_key_file = data_root / ".support_hmac.key"
    run_dir = Path(run_dir) if run_dir else _latest_run_dir()
    if not run_dir.exists():
        raise RuntimeError(f"Run directory not found: {run_dir}")

    info(f">>> SUPPORT BUNDLE START ({run_dir.name})")
    key = _get_support_key(support_key_file)
    tok = Tokenizer(key)

    stage = run_dir / "stage"
    cp = _load_json(stage / "cp.json", []) or []
    vsx = _load_json(stage / "vsx.json", []) or []
    pan = _load_json(stage / "panorama_runtime.json", []) or []
    unified = _load_json(stage / "unified.json", []) or []
    verification = _load_json(run_dir / "verification.json", {}) or _load_json(stage / "verification.json", {}) or {}
    manifest = _load_json(run_dir / "manifest.json", {}) or {}
    cp_telemetry = _load_json(run_dir / "raw" / "cp_telemetry.json", {}) or {}
    cp_direct_ssh_probe = _load_json(run_dir / "raw" / "cp_direct_ssh_probe.json", {}) or {}
    vsx_raw = _load_json(run_dir / "raw" / "vsx_raw.json", []) or []
    vsx_telemetry = _load_json(run_dir / "raw" / "vsx_telemetry.json", []) or []
    panorama_telemetry = _load_json(run_dir / "raw" / "panorama_telemetry.json", {}) or {}

    support_dir = run_dir / "support"
    support_dir.mkdir(parents=True, exist_ok=True)

    summary = {
        "format": "f-buddy-support-v2",
        "build": "phase-0.5-final-ui-closure",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "run": tok.token("run", run_dir.name),
        "privacy": {
            "scheme": "HMAC-SHA256",
            "key_in_bundle": False,
            "deterministic_across_runs": True,
            "note": "Real identifiers, IPs, networks, serials, contexts, zones and VR names are not included.",
        },
        "sources": {
            "cp": _source_stats(cp),
            "vsx": _source_stats(vsx),
            "panorama": _source_stats(pan),
        },
        "unified": {
            "objects": len(unified),
            "data_states": dict(Counter(
                str((item.get("inventory_status") or {}).get("data_state") or "unknown")
                for item in unified
            )),
            "fresh_objects": sum(
                1 for item in unified
                if (item.get("inventory_status") or {}).get("fresh") is True
            ),
        },
        "verification_status": verification.get("run_status"),
    }

    fingerprints = {
        "cp": _build_entity_fingerprints("cp", cp, tok),
        "vsx": _build_entity_fingerprints("vsx", vsx, tok),
        "panorama": _build_entity_fingerprints("panorama", pan, tok),
    }

    vsx_completeness = build_vsx_completeness(vsx_raw, vsx, vsx_telemetry)
    timeout_count = vsx_completeness["telemetry"]["timeout_count"]
    prompt_miss_count = vsx_completeness["telemetry"]["prompt_miss_count"]

    vsx_contexts_safe = []
    for row in vsx_completeness.pop("contexts", []):
        vsx_contexts_safe.append({
            "entity": tok.token("entity", f"vsx:{row.get('device')}|{row.get('context')}|{row.get('vs_id')}"),
            "device": tok.token("dev", row.get("device")),
            "context": tok.token("ctx", row.get("context")),
            "vs_id": row.get("vs_id"),
            "raw": row.get("raw"),
            "telemetry": row.get("telemetry"),
            "parsed": row.get("parsed"),
            "delta": row.get("delta"),
        })

    cp_summary = cp_telemetry.get("summary") or {}
    cp_marker = cp_telemetry.get("remote_collection_marker") or {}
    cp_collector = cp_telemetry.get("collector_script") or {}
    cp_direct_ssh_safe = _sanitize_dict(support_safe_probe(cp_direct_ssh_probe), tok) if cp_direct_ssh_probe else {}
    cp_command_status_safe = [
        {
            "device": tok.token("dev", row.get("device")),
            "interface_rc": row.get("interface_rc"),
            "route_rc": row.get("route_rc"),
            "interface_attempts": row.get("interface_attempts"),
            "route_attempts": row.get("route_attempts"),
            "interface_error": row.get("interface_error"),
            "route_error": row.get("route_error"),
            "interface_first_error": row.get("interface_first_error"),
            "route_first_error": row.get("route_first_error"),
            "management_state": row.get("management_state"),
            "collection_outcome": row.get("collection_outcome"),
            "object_type": row.get("object_type"),
            "cluster_probe_rc": row.get("cluster_probe_rc"),
            "cluster_probe_attempts": row.get("cluster_probe_attempts"),
            "cluster_probe_error": row.get("cluster_probe_error"),
        }
        for row in (cp_telemetry.get("remote_command_status") or [])
    ]
    cp_raw_files = cp_telemetry.get("remote_files") or []

    diagnostics = {
        "cp": {
            "telemetry_available": bool(cp_telemetry),
            "collector": {
                "automated": bool(cp_collector),
                "upload_verified": cp_collector.get("upload_verified"),
                "local_sha256": cp_collector.get("local_sha256"),
                "remote_sha256": cp_collector.get("remote_sha256"),
                "remote_exit_status": cp_collector.get("remote_exit_status"),
                "done_marker_seen": cp_collector.get("done_marker_seen"),
                "reported_total_gw": cp_collector.get("reported_total_gw"),
                "processed_gw": cp_collector.get("processed_gw"),
                "stderr_bytes": cp_collector.get("stderr_bytes"),
            },
            "collection_marker": {
                "available": bool(cp_marker.get("available")),
                "started_epoch": cp_marker.get("started_epoch"),
                "completed_epoch": cp_marker.get("completed_epoch"),
                "discovered": cp_marker.get("discovered"),
            },
            "raw_files": {
                "count": len(cp_raw_files),
                "zero_size_count": sum(1 for row in cp_raw_files if row.get("size_bytes") == 0),
                "oldest_age_seconds": cp_summary.get("oldest_file_age_seconds"),
                "newest_age_seconds": cp_summary.get("newest_file_age_seconds"),
            },
            "command_status_state": cp_summary.get("command_status"),
            "command_failures": cp_summary.get("command_failures"),
            "attempted_devices": cp_summary.get("attempted_devices"),
            "successful_devices": cp_summary.get("successful_devices"),
            "partial_devices": cp_summary.get("partial_devices"),
            "failed_devices": cp_summary.get("failed_devices"),
            "management_up_devices": cp_summary.get("management_up_devices"),
            "management_down_devices": cp_summary.get("management_down_devices"),
            "management_unknown_devices": cp_summary.get("management_unknown_devices"),
            "retried_devices": cp_summary.get("retried_devices"),
            "recovered_after_retry": cp_summary.get("recovered_after_retry"),
            "parallelism": cp_summary.get("parallelism"),
            "collection_mode": cp_summary.get("collection_mode"),
            "first_timeout_seconds": cp_summary.get("first_timeout_seconds"),
            "retry_timeout_seconds": cp_summary.get("retry_timeout_seconds"),
            "max_retries": cp_summary.get("max_retries"),
            "clusterxl_groups": cp_summary.get("clusterxl_groups"),
            "clusterxl_members": cp_summary.get("clusterxl_members"),
            "clusterxl_virtual_interfaces": cp_summary.get("clusterxl_virtual_interfaces"),
            "direct_ssh_probe": cp_direct_ssh_safe,
            "command_status": cp_command_status_safe,
        },
        "vsx": {
            **vsx_completeness,
            "contexts": vsx_contexts_safe,
        },
        "panorama": {
            "telemetry_available": bool(panorama_telemetry),
            "discovered": panorama_telemetry.get("discovered"),
            "connected_yes": panorama_telemetry.get("connected_yes"),
            "connected_no": panorama_telemetry.get("connected_no"),
            "successful": panorama_telemetry.get("successful"),
            "failed": panorama_telemetry.get("failed"),
            "devices": [
                {
                    "device": tok.token("dev", row.get("device")),
                    "serial": tok.token("serial", row.get("serial")),
                    "connected": row.get("connected"),
                    "interfaces": {
                        "status": (row.get("interfaces") or {}).get("status"),
                        "duration_ms": (row.get("interfaces") or {}).get("duration_ms"),
                        "parsed": (row.get("interfaces") or {}).get("parsed"),
                    },
                    "routes": {
                        "status": (row.get("routes") or {}).get("status"),
                        "duration_ms": (row.get("routes") or {}).get("duration_ms"),
                        "parsed": (row.get("routes") or {}).get("parsed"),
                    },
                }
                for row in (panorama_telemetry.get("devices") or [])
            ],
        },
    }

    def cp_row_management_down(row):
        return row.get("collection_outcome") == "management_down" or row.get("interface_error") == "management_down"

    def cp_row_failed(row):
        if cp_row_management_down(row):
            return False
        return (
            row.get("interface_error") not in (None, "", "none")
            or row.get("route_error") not in (None, "", "none")
            or row.get("interface_rc") not in (0, "0")
            or row.get("route_rc") not in (0, "0")
        )

    cp_errors = [row for row in cp_command_status_safe if cp_row_failed(row)]
    cp_management_down = [row for row in cp_command_status_safe if cp_row_management_down(row)]
    vsx_errors = []
    for row in vsx_contexts_safe:
        tele = row.get("telemetry") or {}
        delta = row.get("delta") or {}
        issues = []
        if tele.get("interface_timeout") is True:
            issues.append("interface_timeout")
        if tele.get("route_timeout") is True:
            issues.append("route_timeout")
        if tele.get("interface_prompt_seen") is False:
            issues.append("interface_prompt_not_seen")
        if tele.get("route_prompt_seen") is False:
            issues.append("route_prompt_not_seen")
        if delta.get("interfaces") not in (None, 0):
            issues.append("interface_raw_parsed_delta")
        if delta.get("routes") not in (None, 0):
            issues.append("route_raw_parsed_delta")
        if issues:
            vsx_errors.append({
                "entity": row.get("entity"),
                "device": row.get("device"),
                "context": row.get("context"),
                "vs_id": row.get("vs_id"),
                "issues": issues,
                "telemetry": tele,
                "delta": delta,
            })

    panorama_errors = []
    for row in panorama_telemetry.get("devices") or []:
        int_status = (row.get("interfaces") or {}).get("status")
        route_status = (row.get("routes") or {}).get("status")
        connected = row.get("connected")
        if connected == "no" or int_status == "failed" or route_status == "failed":
            panorama_errors.append({
                "device": tok.token("dev", row.get("device")),
                "serial": tok.token("serial", row.get("serial")),
                "connected": connected,
                "interface_status": int_status,
                "route_status": route_status,
            })

    errors_payload = {
        "cp": {
            "count": len(cp_errors),
            "devices": cp_errors,
            "management_down_count": len(cp_management_down),
            "management_down_devices": cp_management_down,
            "direct_ssh_probe": cp_direct_ssh_safe,
            "note": "management_down devices are operational availability observations and are not counted as collector errors. Direct SSH is an observe-only capability probe in 0.5.1 and does not make an entity LIVE yet.",
        },
        "vsx": {
            "count": len(vsx_errors),
            "contexts": vsx_errors,
        },
        "panorama": {
            "count": len(panorama_errors),
            "devices": panorama_errors,
        },
    }

    payloads = {
        "summary.json": summary,
        "verification_anonymized.json": _anonymize_verification(verification, tok),
        "manifest_anonymized.json": _sanitize_dict(manifest, tok),
        "anomalies.json": _anomaly_rollup(verification, tok),
        "integrity.json": {"files": _integrity_rows(run_dir)},
        "inventory_fingerprints.json": fingerprints,
        "diagnostics.json": diagnostics,
        "errors.json": errors_payload,
        "cp_direct_ssh_probe_anonymized.json": cp_direct_ssh_safe,
    }

    for name, payload in payloads.items():
        (support_dir / name).write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")

    log_lines = [
        "F-BUDDY SHAREABLE SUPPORT REPORT",
        f"run={summary['run']}",
        f"verification={summary['verification_status']}",
        f"cp objects={summary['sources']['cp']['objects']} interfaces={summary['sources']['cp']['interfaces']} routes={summary['sources']['cp']['routes']}",
        f"vsx objects={summary['sources']['vsx']['objects']} interfaces={summary['sources']['vsx']['interfaces']} routes={summary['sources']['vsx']['routes']}",
        f"panorama objects={summary['sources']['panorama']['objects']} interfaces={summary['sources']['panorama']['interfaces']} routes={summary['sources']['panorama']['routes']}",
        f"unified objects={summary['unified']['objects']}",
        f"cp automated={diagnostics['cp']['collector']['automated']} upload_verified={diagnostics['cp']['collector']['upload_verified']} exit={diagnostics['cp']['collector']['remote_exit_status']} done={diagnostics['cp']['collector']['done_marker_seen']} marker={diagnostics['cp']['collection_marker']['available']} parallelism={diagnostics['cp']['parallelism']} mgmt_down={diagnostics['cp']['management_down_devices']} command_failures={diagnostics['cp']['command_failures']} retried={diagnostics['cp']['retried_devices']} recovered={diagnostics['cp']['recovered_after_retry']}",
        f"cp direct_ssh candidates={(cp_direct_ssh_probe.get('summary') or {}).get('candidates')} reachable={(cp_direct_ssh_probe.get('summary') or {}).get('ssh_reachable')} authenticated={(cp_direct_ssh_probe.get('summary') or {}).get('authenticated')} cli_capable={(cp_direct_ssh_probe.get('summary') or {}).get('inventory_cli_capable')} spark_hints={(cp_direct_ssh_probe.get('summary') or {}).get('spark_hints')}",
        f"cp clusterxl groups={diagnostics['cp']['clusterxl_groups']} members={diagnostics['cp']['clusterxl_members']} virtual_interfaces={diagnostics['cp']['clusterxl_virtual_interfaces']}",
        f"errors cp={errors_payload['cp']['count']} vsx={errors_payload['vsx']['count']} panorama={errors_payload['panorama']['count']}",
        f"vsx telemetry timeouts={timeout_count} prompt_misses={prompt_miss_count} if_delta_ctx={diagnostics['vsx']['raw_to_parsed']['interface_delta_contexts']} route_delta_ctx={diagnostics['vsx']['raw_to_parsed']['route_delta_contexts']}",
        f"panorama discovered={diagnostics['panorama']['discovered']} success={diagnostics['panorama']['successful']} failed={diagnostics['panorama']['failed']} connected_no={diagnostics['panorama']['connected_no']}",
        "Identifiers are HMAC pseudonyms; the HMAC key is not included in this bundle.",
    ]
    (support_dir / "support.log").write_text("\n".join(log_lines) + "\n", encoding="utf-8")

    zip_path = output_root / f"support_bundle_{run_dir.name}.zip"
    output_root.mkdir(parents=True, exist_ok=True)
    tmp_zip = zip_path.with_suffix(".zip.tmp")
    with zipfile.ZipFile(tmp_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(support_dir.iterdir()):
            if path.is_file():
                zf.write(path, arcname=path.name)
    tmp_zip.replace(zip_path)

    info(f">>> SUPPORT BUNDLE READY -> {zip_path}")
    warn(">>> DO NOT SHARE data/.support_hmac.key; it is intentionally excluded from the bundle")
    return zip_path
