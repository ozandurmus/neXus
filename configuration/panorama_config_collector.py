from __future__ import annotations

import csv
import hashlib
import json
import os
import time
import uuid
import zipfile
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

import requests
from lxml import etree

from configuration.pan_config_structure import analyze_pan_config_structure
from configuration.pan_expected_compiler import compile_panorama_expected, expected_for_serial
from configuration.pan_setting_alignment import align_expected_to_effective
from configuration.pan_semantic_validation import build_semantic_validation
from configuration.pan_config_alignment import (
    alignment_profile,
    analyze_panorama_intent,
    analyze_provenance_markers,
    assignment_for_serial,
)
from utils.config_evidence import ConfigEvidenceStore
from utils.logger import err, info, register_sensitive_value, warn
from utils.pan_tls_trust import PanTlsStrictPreflightError, preflight_pan_tls_ca_bundle
from utils.runtime_paths import default_output_root
from utils.support_bundle import Tokenizer, _get_support_key
from panorama.pan_identity import normalize_pan_hostname


BASE_DIR = Path(__file__).resolve().parent.parent
OUTPUT_DIR = default_output_root(repository_root=BASE_DIR)
DERIVED_EXPECTED_DIR = BASE_DIR / "data" / "derived" / "panorama_expected"
DERIVED_ALIGNMENT_DIR = BASE_DIR / "data" / "derived" / "panorama_alignment"
DERIVED_SEMANTIC_DIR = BASE_DIR / "data" / "derived" / "panorama_semantic_validation"
COLLECTOR_VERSION = "0.6.0A4.3.3.2"
PANORAMA_METHOD = "panorama_xml_api_config_show"
PANORAMA_ARTIFACT_TYPE = "panos_active_config_via_panorama"
DIRECT_ACTIVE_METHOD = "direct_panos_xml_api_config_show"
DIRECT_ACTIVE_ARTIFACT_TYPE = "panos_direct_active_config"
DIRECT_EFFECTIVE_METHOD = "direct_panos_xml_api_op_effective_running"
DIRECT_EFFECTIVE_ARTIFACT_TYPE = "panos_direct_effective_running_config"
DIRECT_MERGED_METHOD = "direct_panos_xml_api_op_merged"
DIRECT_MERGED_ARTIFACT_TYPE = "panos_direct_merged_config"
DIRECT_PUSHED_TEMPLATE_METHOD = "direct_panos_xml_api_op_pushed_template"
DIRECT_PUSHED_TEMPLATE_ARTIFACT_TYPE = "panos_direct_pushed_template_config"
PANORAMA_INTENT_METHOD = "panorama_xml_api_active_management_config_show"
PANORAMA_INTENT_ARTIFACT_TYPE = "panorama_active_management_config"


class PanoramaConfigError(RuntimeError):
    pass


class DirectFirewallIdentityError(PanoramaConfigError):
    pass


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _run_id() -> str:
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    return f"{stamp}_{uuid.uuid4().hex[:8]}"


def _fingerprint(value: str | None) -> str:
    if not value:
        return "unknown"
    return hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:12]


def _env_bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _tls_verify_setting() -> bool | str:
    ca_bundle = os.getenv("SECURITYEXPERT_PAN_CA_BUNDLE")
    if ca_bundle:
        return ca_bundle
    return _env_bool("SECURITYEXPERT_PAN_TLS_VERIFY", default=False)


def _direct_tls_verify_setting() -> bool | str:
    direct_ca = os.getenv("SECURITYEXPERT_PAN_DIRECT_CA_BUNDLE")
    if direct_ca:
        return direct_ca
    shared_ca = os.getenv("SECURITYEXPERT_PAN_CA_BUNDLE")
    if shared_ca:
        return shared_ca
    direct_verify = os.getenv("SECURITYEXPERT_PAN_DIRECT_TLS_VERIFY")
    if direct_verify is not None:
        return direct_verify.strip().lower() in {"1", "true", "yes", "on"}
    return _env_bool("SECURITYEXPERT_PAN_TLS_VERIFY", default=False)


def _timeout_seconds() -> float:
    raw = os.getenv("SECURITYEXPERT_PAN_CONFIG_TIMEOUT", "90")
    try:
        return max(5.0, float(raw))
    except ValueError:
        return 90.0


def _direct_timeout_seconds() -> float:
    raw = os.getenv("SECURITYEXPERT_PAN_DIRECT_TIMEOUT", "20")
    try:
        return max(3.0, float(raw))
    except ValueError:
        return 20.0


def _worker_count(requested: int | None = None) -> int:
    if requested is None:
        raw = os.getenv("SECURITYEXPERT_PAN_CONFIG_WORKERS", "3")
        try:
            requested = int(raw)
        except ValueError:
            requested = 3
    return max(1, min(int(requested), 6))


def fix_host(host: str) -> str:
    host = str(host or "").rstrip("/")
    if not host.startswith("http"):
        host = f"https://{host}"
    return host


def _parse_xml_response(response: requests.Response, operation: str) -> etree._Element:
    response.raise_for_status()
    try:
        parser = etree.XMLParser(resolve_entities=False, no_network=True, recover=False)
        root = etree.fromstring(response.content, parser=parser)
    except etree.XMLSyntaxError as exc:
        raise PanoramaConfigError(f"{operation}: invalid XML response") from exc
    if root.get("status") != "success":
        message = " ".join(
            text.strip()
            for text in root.xpath("//msg//text()")
            if text and text.strip()
        )
        raise PanoramaConfigError(f"{operation}: API status=error ({message or 'no message'})")
    return root


def _keygen(cfg: Any, host: str, *, verify: bool | str, timeout: float, operation: str) -> str:
    # Credentials are POST body fields, never URL query parameters.
    response = requests.post(
        f"{host}/api/",
        data={
            "type": "keygen",
            "user": cfg.auth.principal,
            "password": cfg.auth.secret,
        },
        verify=verify,
        timeout=timeout,
    )
    root = _parse_xml_response(response, operation)
    key = root.findtext(".//key")
    if not key:
        raise PanoramaConfigError(f"{operation}: success without a key")
    return key


def get_api_key(cfg: Any, host: str, *, verify: bool | str, timeout: float) -> str:
    return _keygen(
        cfg,
        host,
        verify=verify,
        timeout=timeout,
        operation="Panorama key generation",
    )


def get_firewall_api_key(cfg: Any, host: str, *, verify: bool | str, timeout: float) -> str:
    return _keygen(
        cfg,
        host,
        verify=verify,
        timeout=timeout,
        operation="Direct firewall key generation",
    )


def api_post(
    host: str,
    key: str,
    data: dict[str, Any],
    *,
    verify: bool | str,
    timeout: float,
    operation: str,
) -> etree._Element:
    response = requests.post(
        f"{host}/api/",
        data=data,
        headers={"X-PAN-KEY": key},
        verify=verify,
        timeout=timeout,
    )
    return _parse_xml_response(response, operation)


def get_devices(host: str, key: str, *, verify: bool | str, timeout: float) -> list[dict[str, Any]]:
    root = api_post(
        host,
        key,
        {
            "type": "op",
            "cmd": "<show><devices><all></all></devices></show>",
        },
        verify=verify,
        timeout=timeout,
        operation="Panorama managed device discovery",
    )
    devices = []
    for entry in root.xpath("//devices/entry"):
        serial = (entry.findtext("serial") or entry.get("name") or "").strip()
        if not serial:
            continue
        devices.append({
            "serial": serial,
            "hostname": normalize_pan_hostname(entry.findtext("hostname"), serial=serial),
            "connected": (entry.findtext("connected") or "").strip().lower(),
            "management_ip": (entry.findtext("ip-address") or "").strip() or None,
            "model": (entry.findtext("model") or "").strip() or None,
            "sw_version": (entry.findtext("sw-version") or "").strip() or None,
            "shared_policy_status": (
                entry.findtext("shared-policy-status")
                or entry.findtext("shared-policy")
                or ""
            ).strip() or None,
            "template_status": (
                entry.findtext("template-status")
                or entry.findtext("template")
                or ""
            ).strip() or None,
            "ha_state": (entry.findtext("ha-state") or "").strip() or None,
        })
    return devices


def get_target_ha_runtime_state(
    host: str,
    key: str,
    serial: str,
    *,
    verify: bool | str,
    timeout: float,
) -> dict[str, Any]:
    """Read the managed firewall's actual HA runtime state through Panorama.

    This is a read-only operational query. PAN-OS exposes the local runtime
    role under ``result/group/local-info/state`` for ``show high-availability
    state``. Static HA configuration is deliberately not used to infer a role.
    """
    root = api_post(
        host,
        key,
        {
            "type": "op",
            "cmd": "<show><high-availability><state></state></high-availability></show>",
            "target": serial,
        },
        verify=verify,
        timeout=timeout,
        operation=f"Panorama target HA runtime state serial={_fingerprint(serial)}",
    )
    enabled = (root.findtext(".//result/enabled") or root.findtext(".//enabled") or "").strip() or None
    state = (root.findtext(".//result/group/local-info/state") or "").strip() or None
    mode = (root.findtext(".//result/group/local-info/mode") or "").strip() or None
    peer_state = (root.findtext(".//result/group/peer-info/state") or "").strip() or None
    state_sync = (root.findtext(".//result/group/local-info/state-sync") or "").strip() or None
    return {
        "enabled": enabled,
        "state": state,
        "mode": mode,
        "peer_state": peer_state,
        "state_sync": state_sync,
    }


def parse_ha_peer_ip_from_config(content: bytes) -> dict[str, str | None]:
    """Extract the configured HA peer address from a running-config XML
    document this collector already fetches (OP.0a.P7 contract). Read-only
    parse of already-retrieved evidence -- no new device command, no new
    API call: `content` is the same bytes `_store_artifact` writes as the
    `running-config.xml` / active-config artifact.

    `/deviceconfig/high-availability/group/peer-ip` (and `-ipv6`) is the
    exact XPath `configuration/pan_semantic_policy.py`'s
    `_MEMBER_SPECIFIC_EXACT_SUFFIXES` already treats as real and
    member-specific, manually validated against this environment. Searched
    as a descendant match (`.//deviceconfig/...`), not an absolute path,
    because the exact nesting depth under the API's `target=<serial>`-scoped
    `<config>` root is not asserted here -- the tag-path suffix is what was
    validated, not its absolute depth.

    Configuration intent, not a runtime observation and not proof of a live
    peer relationship: this is what the HA group is configured to point at,
    never confirmation that it currently does. Callers must not treat a
    resolved value as sufficient corroboration on its own (OP.0a.P7
    contract, Grade A vs Grade B/C) -- it is one half of the mutual
    configuration-agreement check `utils/failover/assessment.py::_derive_pan_units`
    requires before forming a pair, never used alone."""
    try:
        parser = etree.XMLParser(resolve_entities=False, no_network=True, recover=False)
        root = etree.fromstring(content, parser=parser)
    except etree.XMLSyntaxError:
        return {"peer_ip": None, "peer_ipv6": None}
    peer_ip = (root.findtext(".//deviceconfig/high-availability/group/peer-ip") or "").strip() or None
    peer_ipv6 = (root.findtext(".//deviceconfig/high-availability/group/peer-ipv6") or "").strip() or None
    return {"peer_ip": peer_ip, "peer_ipv6": peer_ipv6}


def _config_payload(root: etree._Element, operation: str) -> bytes:
    config = root.find(".//result/config")
    if config is None:
        raise PanoramaConfigError(f"{operation}: response has no <result><config> payload")
    return etree.tostring(
        config,
        encoding="UTF-8",
        xml_declaration=True,
        pretty_print=False,
    )


def get_active_running_config(
    host: str,
    key: str,
    serial: str,
    *,
    verify: bool | str,
    timeout: float,
) -> bytes:
    """Control artifact: active config queried through Panorama target=<serial>."""
    root = api_post(
        host,
        key,
        {
            "type": "config",
            "action": "show",
            "xpath": "/config",
            "target": serial,
        },
        verify=verify,
        timeout=timeout,
        operation=f"PAN-OS active configuration target={_fingerprint(serial)}",
    )
    return _config_payload(root, "Panorama active configuration")



def get_panorama_management_config(
    host: str,
    key: str,
    *,
    verify: bool | str,
    timeout: float,
) -> bytes:
    """Read Panorama's own active management configuration (no target)."""
    root = api_post(
        host,
        key,
        {"type": "config", "action": "show", "xpath": "/config"},
        verify=verify,
        timeout=timeout,
        operation="Panorama active management configuration",
    )
    return _config_payload(root, "Panorama active management configuration")

def get_direct_system_info(
    host: str,
    key: str,
    *,
    verify: bool | str,
    timeout: float,
) -> dict[str, str | None]:
    root = api_post(
        host,
        key,
        {"type": "op", "cmd": "<show><system><info></info></system></show>"},
        verify=verify,
        timeout=timeout,
        operation="Direct firewall system info",
    )
    system = root.find(".//result/system")
    if system is None:
        raise PanoramaConfigError("Direct firewall system info has no <system> payload")
    return {
        "serial": (system.findtext("serial") or "").strip() or None,
        "hostname": (system.findtext("hostname") or "").strip() or None,
        "sw_version": (system.findtext("sw-version") or "").strip() or None,
        "model": (system.findtext("model") or "").strip() or None,
    }


def get_direct_active_config(
    host: str,
    key: str,
    *,
    verify: bool | str,
    timeout: float,
) -> bytes:
    root = api_post(
        host,
        key,
        {"type": "config", "action": "show", "xpath": "/config"},
        verify=verify,
        timeout=timeout,
        operation="Direct firewall active configuration",
    )
    return _config_payload(root, "Direct firewall active configuration")


def get_direct_operational_config(
    host: str,
    key: str,
    mode: str,
    *,
    verify: bool | str,
    timeout: float,
) -> bytes:
    if mode not in {"effective-running", "merged", "pushed-template"}:
        raise ValueError(f"Unsupported PAN operational config mode: {mode}")
    cmd = f"<show><config><{mode}></{mode}></config></show>"
    root = api_post(
        host,
        key,
        {"type": "op", "cmd": cmd},
        verify=verify,
        timeout=timeout,
        operation=f"Direct firewall show config {mode}",
    )
    return _config_payload(root, f"Direct firewall show config {mode}")


def _write_local_telemetry(payload: dict[str, Any]) -> Path:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    path = OUTPUT_DIR / "pan_config_telemetry.json"
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)
    return path


def _write_expected_compiler_local(compiled: dict[str, Any], run_id: str) -> tuple[Path, Path]:
    """Persist the local-only compiled expected manifest atomically.

    The derived manifest contains real assignment names and hashed values/paths,
    but never raw configuration values. It is still treated as sensitive local
    evidence and is excluded from support bundles and source control.
    """

    DERIVED_EXPECTED_DIR.mkdir(parents=True, exist_ok=True)
    run_dir = DERIVED_EXPECTED_DIR / run_id
    tmp_dir = DERIVED_EXPECTED_DIR / f".{run_id}.tmp"
    if tmp_dir.exists():
        import shutil
        shutil.rmtree(tmp_dir)
    tmp_dir.mkdir(parents=True, exist_ok=False)
    manifest_path = tmp_dir / "expected-compiler.json"
    manifest_path.write_text(json.dumps(compiled, indent=2, ensure_ascii=False), encoding="utf-8")
    if run_dir.exists():
        import shutil
        shutil.rmtree(run_dir)
    tmp_dir.replace(run_dir)
    final_manifest = run_dir / "expected-compiler.json"

    # Compact operator report: real names are useful for troubleshooting, but
    # setting paths/hashes stay in the derived manifest only.
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    report_path = OUTPUT_DIR / f"pan_expected_compiler_{run_id}.json"
    report_tmp = report_path.with_suffix(".json.tmp")
    report = {
        "schema_version": compiled.get("schema_version"),
        "local_only": True,
        "contains_real_assignment_names": True,
        "contains_raw_configuration_values": False,
        "summary": compiled.get("summary") or {},
        "compiler_contract": compiled.get("compiler_contract") or {},
        "devices": {
            serial: {
                "status": row.get("status"),
                "template_stack_status": row.get("template_stack_status"),
                "template_stacks": row.get("template_stacks") or [],
                "template_expected": row.get("template_expected"),
                "device_group_assignments": row.get("device_group_assignments") or [],
                "policy_scopes": [
                    {
                        "device_group": scope.get("device_group"),
                        "vsys": scope.get("vsys") or [],
                        "scope": scope.get("scope"),
                        "lineage_high_to_low": ((scope.get("lineage") or {}).get("lineage_high_to_low") or []),
                        "lineage_status": ((scope.get("lineage") or {}).get("status")),
                    }
                    for scope in (row.get("policy_scopes") or [])
                ],
                "anomalies": row.get("anomalies") or [],
            }
            for serial, row in sorted((compiled.get("by_serial") or {}).items())
        },
        "derived_manifest": str(final_manifest),
    }
    report_tmp.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    report_tmp.replace(report_path)
    return final_manifest, report_path



def _artifact_bytes(artifact: dict[str, Any] | None) -> bytes | None:
    """Read published evidence from CAS, with legacy snapshot fallback."""
    artifact = artifact or {}
    if artifact.get("status") != "success":
        return None

    object_path = artifact.get("artifact_object")
    if object_path:
        path = Path(str(object_path))
        if not path.is_absolute():
            path = BASE_DIR / path
        try:
            return path.read_bytes()
        except OSError:
            return None

    # Backward compatibility for pre-A4.3.2 snapshot directories that still
    # contain the payload beside metadata.json.
    snapshot = artifact.get("snapshot")
    artifact_file = artifact.get("artifact_file")
    if not snapshot or not artifact_file:
        return None
    directory = Path(str(snapshot))
    if not directory.is_absolute():
        directory = BASE_DIR / directory
    try:
        return (directory / str(artifact_file)).read_bytes()
    except OSError:
        return None


def _safe_setting_alignment(alignment: dict[str, Any] | None) -> dict[str, Any] | None:
    if not alignment:
        return None
    summary = alignment.get("summary") or {}
    return {
        "schema_version": alignment.get("schema_version"),
        "status": alignment.get("status"),
        "device_status": alignment.get("device_status"),
        "reason": alignment.get("reason"),
        "error_type": alignment.get("error_type"),
        "summary": summary,
        "engine_contract": alignment.get("engine_contract") or {},
        "source_coverage": alignment.get("source_coverage") or {},
        "detail_in_support_bundle": False,
        "paths_in_support_bundle": False,
        "value_hashes_in_support_bundle": False,
        "raw_values_included": False,
    }


def _write_setting_alignment_local(rows: list[dict[str, Any]], run_id: str) -> tuple[Path, Path]:
    """Persist A4.2 setting-level results locally, never in the support ZIP."""
    DERIVED_ALIGNMENT_DIR.mkdir(parents=True, exist_ok=True)
    run_dir = DERIVED_ALIGNMENT_DIR / run_id
    tmp_dir = DERIVED_ALIGNMENT_DIR / f".{run_id}.tmp"
    if tmp_dir.exists():
        import shutil
        shutil.rmtree(tmp_dir)
    tmp_dir.mkdir(parents=True, exist_ok=False)

    devices = []
    operator_devices = []
    for row in rows:
        detail = row.get("_setting_alignment_detail") or row.get("setting_alignment") or {}
        devices.append({
            "device": row.get("device"),
            "serial": row.get("serial"),
            "management_ip": row.get("management_ip"),
            "status": detail.get("status"),
            "device_status": detail.get("device_status"),
            "summary": detail.get("summary") or {},
            "engine_contract": detail.get("engine_contract") or {},
            "source_coverage": detail.get("source_coverage") or {},
            "results": detail.get("results") or [],
        })
        operator_devices.append({
            "device": row.get("device"),
            "serial": row.get("serial"),
            "status": detail.get("status"),
            "device_status": detail.get("device_status"),
            "summary": detail.get("summary") or {},
        })

    manifest = {
        "schema_version": "0.6.0A4.2.2",
        "local_only": True,
        "contains_device_identity": True,
        "contains_setting_paths": True,
        "contains_value_hashes": True,
        "contains_raw_configuration_values": False,
        "classification_contract": {
            "ALIGNED": "compiled expected scalar hash equals effective-running scalar hash",
            "LOCAL_OVERRIDE": "expected differs and direct local-active scalar equals effective scalar",
            "EFFECTIVE_DRIFT": "expected differs, local-active does not explain it, merged equals effective, and Panorama sync is known",
            "PANORAMA_OUT_OF_SYNC": "difference observed while Panorama reports policy/template out of sync",
            "EXPECTED_ONLY": "compiled expected scalar was not observed in effective-running; not automatically drift",
            "LOCAL_ONLY": "local-active scalar has no compiled Template-Stack scalar counterpart; informational",
            "MEMBER_SPECIFIC": "member-relative setting is intentionally excluded from generic override/drift claims",
            "PROVENANCE_UNVERIFIED": "expected source semantics are not sufficiently verified for an override/drift claim",
            "IDENTITY_TRANSLATION_REQUIRED": "logical/internal identity mapping is unresolved; not an override finding",
            "UNKNOWN": "available evidence is insufficient for a stronger classification",
        },
        "devices": devices,
    }
    manifest_path = tmp_dir / "setting-alignment.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    if run_dir.exists():
        import shutil
        shutil.rmtree(run_dir)
    tmp_dir.replace(run_dir)
    final_manifest = run_dir / "setting-alignment.json"

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    report_path = OUTPUT_DIR / f"pan_setting_alignment_{run_id}.json"
    report_tmp = report_path.with_suffix(".json.tmp")
    report = {
        "schema_version": "0.6.0A4.2.2",
        "local_only": True,
        "contains_device_identity": True,
        "contains_setting_paths": False,
        "contains_raw_configuration_values": False,
        "devices": operator_devices,
        "derived_manifest": str(final_manifest),
    }
    report_tmp.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    report_tmp.replace(report_path)
    return final_manifest, report_path


def _write_semantic_validation_local(result: dict[str, Any], run_id: str) -> tuple[Path, Path, Path]:
    """Persist A4.2.1 semantic validation artifacts locally only.

    The derived manifest contains paths/hashes but no raw values. The operator
    report and CSV may contain selected non-sensitive configuration values and
    are therefore explicitly local-only and excluded from support bundles.
    """
    DERIVED_SEMANTIC_DIR.mkdir(parents=True, exist_ok=True)
    run_dir = DERIVED_SEMANTIC_DIR / run_id
    tmp_dir = DERIVED_SEMANTIC_DIR / f".{run_id}.tmp"
    if tmp_dir.exists():
        import shutil
        shutil.rmtree(tmp_dir)
    tmp_dir.mkdir(parents=True, exist_ok=False)

    manifest = result.get("manifest") or {}
    manifest_path = tmp_dir / "semantic-validation.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    if run_dir.exists():
        import shutil
        shutil.rmtree(run_dir)
    tmp_dir.replace(run_dir)
    final_manifest = run_dir / "semantic-validation.json"

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    report_path = OUTPUT_DIR / f"pan_semantic_validation_{run_id}.json"
    report_tmp = report_path.with_suffix(".json.tmp")
    operator_report = dict(result.get("operator_report") or {})
    operator_report["derived_manifest"] = str(final_manifest)
    report_tmp.write_text(json.dumps(operator_report, indent=2, ensure_ascii=False), encoding="utf-8")
    report_tmp.replace(report_path)

    csv_path = OUTPUT_DIR / f"pan_semantic_validation_samples_{run_id}.csv"
    csv_tmp = csv_path.with_suffix(".csv.tmp")
    fields = [
        "sample_id", "device", "serial", "management_ip", "sample_kind",
        "classification", "category", "setting", "possible_local_equivalent_setting",
        "expected_source_kind", "expected_source_name", "expected_value",
        "local_active_value", "merged_value", "effective_value",
        "path_shape_similarity", "manual_result", "operator_action",
    ]
    with csv_tmp.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for row in operator_report.get("samples") or []:
            writer.writerow(row)
    csv_tmp.replace(csv_path)
    return final_manifest, report_path, csv_path


def _safe_semantic_validation(result: dict[str, Any] | None) -> dict[str, Any]:
    result = result or {}
    summary = dict(result.get("summary") or {})
    return {
        "schema_version": result.get("schema_version") or "0.6.0A4.2.1",
        "status": result.get("status"),
        "summary": summary,
        "manual_confirmation_status": summary.get("manual_confirmation_status", "pending"),
        "local_manifest_in_support_bundle": False,
        "operator_report_in_support_bundle": False,
        "sample_setting_paths_in_support_bundle": False,
        "sample_values_in_support_bundle": False,
        "value_hashes_in_support_bundle": False,
        "raw_values_included": False,
    }


def _safe_expected(expected: dict[str, Any] | None, tok: Tokenizer) -> dict[str, Any]:
    expected = expected or {}
    template = expected.get("template_expected") or {}
    return {
        "status": expected.get("status"),
        "template_stack_status": expected.get("template_stack_status"),
        "template_stack_count": len(expected.get("template_stacks") or []),
        "primary_template_stack": tok.token("template_stack", expected.get("primary_template_stack")),
        "template_expected": {
            "compiled_setting_count": int(template.get("compiled_setting_count") or 0),
            "alignment_ready_setting_count": int(template.get("alignment_ready_setting_count") or 0),
            "unresolved_variable_setting_count": int(template.get("unresolved_variable_setting_count") or 0),
            "shadowed_setting_count": int(template.get("shadowed_setting_count") or 0),
            "coverage": template.get("coverage") or {},
        } if template else None,
        "device_group_assignment_count": len(expected.get("device_group_assignments") or []),
        "policy_scope_count": len(expected.get("policy_scopes") or []),
        "policy_lineage_complete": all(
            ((scope.get("lineage") or {}).get("status") == "compiled")
            for scope in (expected.get("policy_scopes") or [])
        ) if expected.get("policy_scopes") else False,
        "anomalies": list(expected.get("anomalies") or []),
        "raw_values_included": False,
        "real_assignment_names_included": False,
    }


def _safe_structure(structure: dict[str, Any] | None) -> dict[str, Any] | None:
    if not structure:
        return None
    inspection = structure.get("schema_inspection") or {}
    return {
        "schema_version": structure.get("schema_version"),
        "status": structure.get("status"),
        "schema_status": structure.get("schema_status"),
        "evidence_status": structure.get("evidence_status"),
        "evidence_reason": structure.get("evidence_reason"),
        "presence": structure.get("presence"),
        "counts": structure.get("counts"),
        "schema_inspection": {
            "privacy_safe": inspection.get("privacy_safe"),
            "distinct_path_count": inspection.get("distinct_path_count"),
            "max_depth": inspection.get("max_depth"),
            "paths_truncated": inspection.get("paths_truncated"),
            "path_values_in_support_bundle": False,
        },
    }


def _safe_artifact(row: dict[str, Any] | None) -> dict[str, Any] | None:
    if not row:
        return None
    return {
        "status": row.get("status"),
        "method": row.get("method"),
        "transport": row.get("transport"),
        "duration_ms": row.get("duration_ms"),
        "api_query_duration_ms": row.get("api_query_duration_ms"),
        "local_store_duration_ms": row.get("local_store_duration_ms"),
        "size_bytes": row.get("size_bytes"),
        "sha256": row.get("sha256"),
        "canonical_sha256": row.get("canonical_sha256"),
        "change_state": row.get("change_state"),
        "structural_validation": _safe_structure(row.get("structural_validation")),
        "provenance_markers": row.get("provenance_markers"),
        "error_type": row.get("error_type"),
        "failure_domain": row.get("failure_domain"),
        "failure_stage": row.get("failure_stage"),
        "error_hint": row.get("error_hint"),
        "required_for_primary": row.get("required_for_primary"),
        "required_for_alignment": row.get("required_for_alignment"),
    }


def _safe_direct(row: dict[str, Any] | None) -> dict[str, Any] | None:
    if not row:
        return None
    return {
        "attempted": row.get("attempted"),
        "status": row.get("status"),
        "api_auth": row.get("api_auth"),
        "identity_verified": row.get("identity_verified"),
        "identity_mismatch": row.get("identity_mismatch"),
        "duration_ms": row.get("duration_ms"),
        "error_type": row.get("error_type"),
        "failure_domain": row.get("failure_domain"),
        "failure_stage": row.get("failure_stage"),
        "failure_method": row.get("failure_method"),
        "error_hint": row.get("error_hint"),
        "active": _safe_artifact(row.get("active")),
        "effective": _safe_artifact(row.get("effective")),
        "merged": _safe_artifact(row.get("merged")),
        "pushed_template": _safe_artifact(row.get("pushed_template")),
        "ssh": {
            "attempted": False,
            "role": "fallback_not_enabled_in_a4",
        },
    }


def _safe_assignment(assignment: dict[str, Any] | None, tok: Tokenizer) -> dict[str, Any]:
    assignment = assignment or {}
    stacks = []
    for stack in assignment.get("template_stacks") or []:
        stacks.append({
            "name": tok.token("template_stack", stack.get("name")),
            "templates": [tok.token("template", value) for value in (stack.get("templates") or [])],
            "stack_level_config_present": bool(stack.get("stack_level_config_present")),
        })
    groups = []
    for group in assignment.get("device_groups") or []:
        groups.append({
            "name": tok.token("device_group", group.get("name")),
            "parent": tok.token("device_group", group.get("parent")),
            "vsys": [tok.token("ctx", value) for value in (group.get("vsys") or [])],
        })
    return {
        "assignment_status": assignment.get("assignment_status"),
        "template_stacks": stacks,
        "device_groups": groups,
    }


def _safe_failure(failure: dict[str, Any], tok: Tokenizer) -> dict[str, Any]:
    return {
        "device": tok.token("dev", failure.get("device")),
        "serial": tok.token("serial", failure.get("serial")),
        "management_ip": tok.token("ip", failure.get("management_ip")),
        "method": failure.get("method"),
        "transport": failure.get("transport"),
        "failure_domain": failure.get("failure_domain"),
        "failure_stage": failure.get("failure_stage"),
        "error_type": failure.get("error_type"),
        "error_hint": failure.get("error_hint"),
        "required_for_primary": failure.get("required_for_primary"),
        "required_for_alignment": failure.get("required_for_alignment"),
    }


def _write_shareable_support(payload: dict[str, Any], run_id: str) -> Path:
    key = _get_support_key()
    tok = Tokenizer(key)
    safe_devices = []
    for row in payload.get("devices") or []:
        control = row.get("panorama_control") or row
        alignment = dict(row.get("configuration_alignment") or {})
        alignment["panorama_assignment"] = _safe_assignment(row.get("panorama_assignment"), tok)
        safe_devices.append({
            "entity": tok.token("entity", f"pan-config:{row.get('serial')}"),
            "device": tok.token("dev", row.get("device")),
            "serial": tok.token("serial", row.get("serial")),
            "management_ip": tok.token("ip", row.get("management_ip")),
            "connected": row.get("connected"),
            "status": row.get("status"),
            "primary_evidence_status": row.get("primary_evidence_status"),
            "alignment_evidence_status": row.get("alignment_evidence_status"),
            "duration_ms": control.get("duration_ms"),
            "size_bytes": control.get("size_bytes"),
            "sha256": control.get("sha256"),
            "change_state": control.get("change_state"),
            "structural_validation": _safe_structure(control.get("structural_validation")),
            "panorama_control": _safe_artifact(row.get("panorama_control")),
            "direct": _safe_direct(row.get("direct")),
            "comparison": row.get("comparison"),
            "configuration_alignment": alignment,
            "setting_alignment": _safe_setting_alignment(row.get("setting_alignment")),
            "expected_configuration": _safe_expected(row.get("expected_configuration"), tok),
            "error_type": row.get("error_type"),
        })

    safe_summary = dict(payload.get("summary") or {})
    safe_summary.pop("run_id", None)
    failures = [_safe_failure(item, tok) for item in (payload.get("failures") or [])]
    intent = payload.get("panorama_intent") or {}
    intent_safe = {
        "status": intent.get("status"),
        "artifact": _safe_artifact(intent.get("artifact")),
        "summary": ((intent.get("analysis") or {}).get("summary") or {}),
        "compiled_expected_config": ((intent.get("analysis") or {}).get("compiled_expected_config")),
        "compiled_expected_reason": ((intent.get("analysis") or {}).get("compiled_expected_reason")),
    }
    compiler = payload.get("expected_compiler") or {}
    compiler_safe = {
        "status": compiler.get("status"),
        "summary": compiler.get("summary") or {},
        "compiler_contract": compiler.get("compiler_contract") or {},
        "raw_values_included": False,
        "real_assignment_names_included": False,
        "manifest_in_support_bundle": False,
    }

    safe = {
        "format": "securityexpert-config-support-v1",
        "build": "phase-0.6.0A4.3.2-content-addressed-configuration-history-storage",
        "generated_at": _utc_now(),
        "run": tok.token("run", run_id),
        "privacy": {
            "scheme": "HMAC-SHA256",
            "key_in_bundle": False,
            "raw_configuration_in_bundle": False,
            "raw_failure_message_in_bundle": False,
            "note": "Names, serials, management IPs and Panorama assignment names are pseudonymized. Raw configuration and credentials are never included.",
        },
        "summary": safe_summary,
        "transport": payload.get("transport") or {},
        "panorama_intent": intent_safe,
        "expected_compiler": compiler_safe,
        "setting_alignment": {
            "schema_version": "0.6.0A4.2.2",
            "local_manifest_in_support_bundle": False,
            "setting_paths_in_support_bundle": False,
            "value_hashes_in_support_bundle": False,
            "raw_values_included": False,
        },
        "semantic_validation": _safe_semantic_validation(payload.get("semantic_validation")),
        "failures": failures,
        "devices": safe_devices,
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    zip_path = OUTPUT_DIR / f"config_support_{run_id}.zip"
    tmp_zip = zip_path.with_suffix(".zip.tmp")
    with zipfile.ZipFile(tmp_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("summary.json", json.dumps(safe, indent=2, ensure_ascii=False))
        lines = [
            "SECURITYEXPERT PAN SEMANTIC POLICY / PROVENANCE SUPPORT — A4.2.2",
            f"run={safe['run']}",
            f"discovered={safe['summary'].get('discovered')}",
            f"selected={safe['summary'].get('selected')}",
            f"primary_evidence_success={safe['summary'].get('primary_evidence_success')}",
            f"alignment_evidence_complete={safe['summary'].get('alignment_evidence_complete')}",
            f"direct_api_auth_success={safe['summary'].get('direct_api_auth_success')}",
            f"direct_identity_verified={safe['summary'].get('direct_identity_verified')}",
            f"direct_active_success={safe['summary'].get('direct_active_success')}",
            f"direct_effective_success={safe['summary'].get('direct_effective_success')}",
            f"direct_merged_success={safe['summary'].get('direct_merged_success')}",
            f"panorama_any_out_of_sync={safe['summary'].get('panorama_any_out_of_sync')}",
            f"expected_compiler_status={safe['summary'].get('expected_compiler_status')}",
            f"expected_compiler_selected_mapped={safe['summary'].get('expected_compiler_selected_mapped')}",
            f"expected_compiler_selected_alignment_ready_settings={safe['summary'].get('expected_compiler_selected_alignment_ready_settings')}",
            f"expected_compiler_gate={safe['summary'].get('expected_compiler_gate')}",
            f"expected_policy_lineage_gate={safe['summary'].get('expected_policy_lineage_gate')}",
            f"a4_1_stage_pass={safe['summary'].get('a4_1_stage_pass')}",
            f"setting_alignment_engine_gate={safe['summary'].get('setting_alignment_engine_gate')}",
            f"a4_2_stage_pass={safe['summary'].get('a4_2_stage_pass')}",
            f"semantic_policy_engine_gate={safe['summary'].get('semantic_policy_engine_gate')}",
            f"a4_2_2_stage_pass={safe['summary'].get('a4_2_2_stage_pass')}",
            f"semantic_member_specific={safe['summary'].get('semantic_policy_member_specific')}",
            f"semantic_provenance_unverified={safe['summary'].get('semantic_policy_provenance_unverified')}",
            f"semantic_identity_translation_required={safe['summary'].get('semantic_policy_identity_translation_required')}",
            f"semantic_identity_path_normalized={safe['summary'].get('semantic_policy_identity_path_normalized_settings')}",
            f"semantic_identity_value_normalized={safe['summary'].get('semantic_policy_identity_value_normalized_settings')}",
            f"semantic_validation_engine_gate={safe['summary'].get('semantic_validation_engine_gate')}",
            f"a4_2_1_engine_pass={safe['summary'].get('a4_2_1_engine_pass')}",
            f"semantic_manual_status={safe['summary'].get('semantic_validation_manual_confirmation_status')}",
            f"semantic_schema_candidates={safe['summary'].get('semantic_validation_possible_schema_equivalents')}",
            f"semantic_manual_samples={safe['summary'].get('semantic_validation_manual_samples')}",
            f"setting_aligned={safe['summary'].get('setting_alignment_classifications', {}).get('ALIGNED', 0)}",
            f"setting_local_override={safe['summary'].get('setting_alignment_classifications', {}).get('LOCAL_OVERRIDE', 0)}",
            f"setting_effective_drift={safe['summary'].get('setting_alignment_classifications', {}).get('EFFECTIVE_DRIFT', 0)}",
            f"setting_expected_only={safe['summary'].get('setting_alignment_classifications', {}).get('EXPECTED_ONLY', 0)}",
            f"setting_unknown={safe['summary'].get('setting_alignment_classifications', {}).get('UNKNOWN', 0)}",
            f"storage_artifact_events={safe['summary'].get('storage_artifact_events')}",
            f"storage_new_objects={safe['summary'].get('storage_new_objects')}",
            f"storage_reused_objects={safe['summary'].get('storage_reused_objects')}",
            f"storage_logical_payload_bytes={safe['summary'].get('storage_logical_payload_bytes')}",
            f"storage_new_object_bytes={safe['summary'].get('storage_new_object_bytes')}",
            f"storage_dedup_bytes_avoided={safe['summary'].get('storage_dedup_bytes_avoided')}",
            f"failures={len(failures)}",
        ]
        for failure in failures:
            lines.append(
                "FAIL "
                f"device={failure.get('device')} method={failure.get('method')} "
                f"transport={failure.get('transport')} domain={failure.get('failure_domain')} "
                f"stage={failure.get('failure_stage')} error={failure.get('error_type')} "
                f"hint={failure.get('error_hint')}"
            )
        lines.extend([
            "A4.2.2 is read-only. SSH fallback is not attempted automatically.",
            "Expected/compiler, setting-alignment, and semantic-validation artifacts are local-only; support contains counts/contracts only.",
            "Raw configuration is intentionally excluded.",
        ])
        zf.writestr("support.log", "\n".join(lines) + "\n")
    tmp_zip.replace(zip_path)
    return zip_path

def _canonical_sha256(content: bytes) -> str:
    parser = etree.XMLParser(resolve_entities=False, no_network=True, recover=False, remove_blank_text=True)
    root = etree.fromstring(content, parser=parser)
    canonical = etree.tostring(root, method="c14n", exclusive=True, with_comments=False)
    return hashlib.sha256(canonical).hexdigest()


def _normalize_sync_status(value: str | None) -> str:
    text = " ".join(str(value or "").strip().lower().replace("_", " ").replace("-", " ").split())
    compact = text.replace(" ", "")
    if not compact:
        return "unknown"
    if compact in {"insync", "synchronized", "sync"}:
        return "in_sync"
    if "outofsync" in compact or compact in {"notsynchronized", "notsync"}:
        return "out_of_sync"
    return "other"


def _configuration_alignment(
    device: dict[str, Any],
    comparison: dict[str, Any],
    direct: dict[str, Any],
    assignment: dict[str, Any] | None = None,
) -> dict[str, Any]:
    shared = _normalize_sync_status(device.get("shared_policy_status"))
    template = _normalize_sync_status(device.get("template_status"))
    panorama_out = shared == "out_of_sync" or template == "out_of_sync"
    sync = {
        "panorama_shared_policy_sync": shared,
        "panorama_template_sync": template,
        "panorama_reports_out_of_sync": panorama_out,
    }
    profile = alignment_profile(
        panorama_sync=sync,
        assignment=assignment or {},
        direct=direct,
        comparison=comparison,
    )
    pushed = (direct or {}).get("pushed_template") or {}
    active_cmp = (comparison or {}).get("panorama_active_vs_direct_active") or {}
    if active_cmp.get("available"):
        active_alignment = "aligned" if active_cmp.get("exact_canonical_match") is True else "different"
    else:
        active_alignment = "unknown"
    return {
        **sync,
        **profile,
        "panorama_active_vs_direct_active": active_alignment,
        "pushed_template_evidence": "available" if pushed.get("status") == "success" else "unavailable",
        "override_analysis": "not_classified",
        "override_reason": "A4 maps Panorama assignment/provenance and observed differences; exact setting-level override requires compiled template-stack/device-group intent",
    }

def _artifact_metrics(structure: dict[str, Any] | None) -> dict[str, int]:
    counts = (structure or {}).get("counts") or {}
    return {
        "vsys": int(counts.get("vsys_entries") or 0),
        "virtual_routers": int(counts.get("virtual_router_entries") or 0),
        "zones": int(counts.get("zone_entries") or 0),
        "interfaces": int(counts.get("interface_definitions_total") or 0),
        "security_rules": int(counts.get("security_rule_entries_total") or 0),
    }


def _compare_artifacts(left: dict[str, Any] | None, right: dict[str, Any] | None) -> dict[str, Any]:
    if not left or not right or left.get("status") != "success" or right.get("status") != "success":
        return {"available": False}
    left_metrics = _artifact_metrics(left.get("structural_validation"))
    right_metrics = _artifact_metrics(right.get("structural_validation"))
    return {
        "available": True,
        "exact_sha256_match": left.get("sha256") == right.get("sha256"),
        "exact_canonical_match": left.get("canonical_sha256") == right.get("canonical_sha256"),
        "size_delta_bytes": int(right.get("size_bytes") or 0) - int(left.get("size_bytes") or 0),
        "metric_delta": {
            key: right_metrics[key] - left_metrics[key]
            for key in left_metrics
        },
        "right_richer_signal_count": sum(
            1 for key in left_metrics if right_metrics[key] > left_metrics[key]
        ),
    }


def _store_artifact(
    store: ConfigEvidenceStore,
    *,
    source: str,
    serial: str,
    artifact_type: str,
    artifact_name: str,
    content: bytes,
    method: str,
    device: dict[str, Any],
    run_id: str,
    retrieval_scope: str,
    duration_ms: int,
) -> dict[str, Any]:
    structure = analyze_pan_config_structure(content)
    provenance_markers = analyze_provenance_markers(content)
    snap = store.write_xml_snapshot(
        source=source,
        entity_id=serial,
        artifact_type=artifact_type,
        artifact_name=artifact_name,
        content=content,
        method=method,
        device_name=device.get("hostname"),
        management_ip=device.get("management_ip"),
        collector_version=COLLECTOR_VERSION,
        extra_metadata={
            "model": device.get("model"),
            "sw_version": device.get("sw_version"),
            "run_id": run_id,
            "retrieval_scope": retrieval_scope,
            "remote_artifact_created": False,
            "remote_configuration_changed": False,
        },
        additional_validation={
            "pan_structure": structure,
            "provenance_markers": provenance_markers,
        },
    )
    try:
        snapshot_display = str(snap.directory.relative_to(BASE_DIR))
    except ValueError:
        snapshot_display = str(snap.directory)
    try:
        artifact_object = str(snap.artifact_path.relative_to(BASE_DIR))
    except ValueError:
        artifact_object = str(snap.artifact_path)
    return {
        "status": "success",
        "duration_ms": duration_ms,
        "snapshot": snapshot_display,
        "artifact_file": snap.logical_artifact_name,
        "artifact_object": artifact_object,
        "storage_mode": ConfigEvidenceStore.STORAGE_SCHEMA,
        "blob_created": snap.blob_created,
        "stored_bytes_delta": snap.stored_bytes_delta,
        "size_bytes": snap.size_bytes,
        "sha256": snap.sha256,
        "canonical_sha256": _canonical_sha256(content),
        "change_state": snap.change_state,
        "previous_sha256": snap.previous_sha256,
        "structural_validation": structure,
        "provenance_markers": provenance_markers,
    }


def _failure_hint(exc: Exception, *, stage: str) -> str:
    name = type(exc).__name__
    if isinstance(exc, PermissionError):
        return "local_filesystem_permission_or_lock" if "store" in stage else "permission_denied"
    if name in {"ConnectTimeout", "ConnectionError"}:
        return "network_path_acl_or_management_service"
    if name in {"ReadTimeout", "Timeout"}:
        return "api_response_timeout"
    if name == "SSLError":
        return "tls_handshake_or_trust"
    if name == "HTTPError":
        return "http_status_error"
    if isinstance(exc, PanoramaConfigError):
        return "panos_api_rejected_unsupported_or_role_permission"
    return "inspect_local_failure_log"


def _method_failure(
    *,
    method: str,
    transport: str,
    stage: str,
    exc: Exception,
    duration_ms: int,
    required_for_primary: bool = False,
    required_for_alignment: bool = False,
) -> dict[str, Any]:
    return {
        "status": "failed",
        "method": method,
        "transport": transport,
        "duration_ms": duration_ms,
        "failure_domain": "local_store" if "store" in stage else "remote_transport_or_api",
        "failure_stage": stage,
        "error_type": type(exc).__name__,
        "error_hint": _failure_hint(exc, stage=stage),
        "required_for_primary": required_for_primary,
        "required_for_alignment": required_for_alignment,
    }


def _collect_direct_artifact(
    *,
    getter: Any,
    store: ConfigEvidenceStore,
    serial: str,
    device: dict[str, Any],
    run_id: str,
    name: str,
    artifact_type: str,
    evidence_method: str,
    method_id: str,
    artifact_name: str,
    retrieval_scope: str,
    required_for_primary: bool,
    required_for_alignment: bool,
) -> dict[str, Any]:
    query_started = time.monotonic()
    try:
        content = getter()
        query_ms = int((time.monotonic() - query_started) * 1000)
    except Exception as exc:
        return _method_failure(
            method=method_id,
            transport="DIRECT_HTTPS_XML_API",
            stage=f"direct_{name}_api_query",
            exc=exc,
            duration_ms=int((time.monotonic() - query_started) * 1000),
            required_for_primary=required_for_primary,
            required_for_alignment=required_for_alignment,
        )

    store_started = time.monotonic()
    try:
        artifact = _store_artifact(
            store,
            source="panos-direct",
            serial=serial,
            artifact_type=artifact_type,
            artifact_name=artifact_name,
            content=content,
            method=evidence_method,
            device=device,
            run_id=run_id,
            retrieval_scope=retrieval_scope,
            duration_ms=query_ms,
        )
    except Exception as exc:
        return _method_failure(
            method=method_id,
            transport="LOCAL_IMMUTABLE_EVIDENCE_STORE",
            stage=f"direct_{name}_local_store",
            exc=exc,
            duration_ms=int((time.monotonic() - store_started) * 1000),
            required_for_primary=required_for_primary,
            required_for_alignment=required_for_alignment,
        )

    artifact.update({
        "method": method_id,
        "transport": "DIRECT_HTTPS_XML_API",
        "api_query_duration_ms": query_ms,
        "local_store_duration_ms": int((time.monotonic() - store_started) * 1000),
        "required_for_primary": required_for_primary,
        "required_for_alignment": required_for_alignment,
    })
    return artifact


def _collect_direct_compare(
    cfg: Any,
    device: dict[str, Any],
    *,
    store: ConfigEvidenceStore,
    run_id: str,
    verify: bool | str,
    timeout: float,
    probe_pushed_template: bool = False,
) -> dict[str, Any]:
    serial = device["serial"]
    management_ip = device.get("management_ip")
    device_name = str(device.get("hostname") or serial)
    result: dict[str, Any] = {
        "attempted": bool(management_ip),
        "status": "pending" if management_ip else "missing_management_ip",
        "api_auth": "not_attempted",
        "identity_verified": False,
        "identity_mismatch": False,
        "ssh_attempted": False,
    }
    if not management_ip:
        result.update({
            "failure_method": "PANORAMA_DISCOVERY_MANAGEMENT_IP",
            "failure_stage": "missing_management_ip",
            "failure_domain": "discovery",
            "error_hint": "verify_managed_device_ip_address_in_panorama",
        })
        err(f">>> PAN A4.2 FAIL device={device_name} method=PANORAMA_DISCOVERY_MANAGEMENT_IP stage=missing_management_ip")
        return result

    host = fix_host(management_ip)
    started_all = time.monotonic()
    try:
        key = get_firewall_api_key(cfg, host, verify=verify, timeout=timeout)
        register_sensitive_value(key, "[DIRECT_API_KEY:REDACTED]")
        result["api_auth"] = "success"
    except Exception as exc:
        result.update({
            "status": "failed",
            "failure_method": "DIRECT_HTTPS_API_KEYGEN",
            "failure_domain": "remote_transport_or_api",
            "failure_stage": "direct_keygen",
            "error_type": type(exc).__name__,
            "error_hint": _failure_hint(exc, stage="direct_keygen"),
            "duration_ms": int((time.monotonic() - started_all) * 1000),
        })
        err(
            f">>> PAN A4.2 FAIL device={device_name} method=DIRECT_HTTPS_API_KEYGEN "
            f"stage=direct_keygen error={type(exc).__name__} hint={result['error_hint']}"
        )
        return result

    try:
        system = get_direct_system_info(host, key, verify=verify, timeout=timeout)
        observed_serial = system.get("serial")
        if not observed_serial or observed_serial != serial:
            result.update({
                "status": "identity_mismatch",
                "identity_mismatch": True,
                "failure_method": "DIRECT_HTTPS_API_SYSTEM_INFO",
                "failure_domain": "identity",
                "failure_stage": "identity_verification",
                "error_hint": "panorama_serial_does_not_match_direct_firewall_serial",
                "duration_ms": int((time.monotonic() - started_all) * 1000),
            })
            err(f">>> PAN A4.2 FAIL device={device_name} method=DIRECT_HTTPS_API_SYSTEM_INFO stage=identity_verification error=SERIAL_MISMATCH")
            return result
        result["identity_verified"] = True
    except Exception as exc:
        result.update({
            "status": "failed",
            "failure_method": "DIRECT_HTTPS_API_SYSTEM_INFO",
            "failure_domain": "remote_transport_or_api",
            "failure_stage": "identity_api_query",
            "error_type": type(exc).__name__,
            "error_hint": _failure_hint(exc, stage="identity_api_query"),
            "duration_ms": int((time.monotonic() - started_all) * 1000),
        })
        err(
            f">>> PAN A4.2 FAIL device={device_name} method=DIRECT_HTTPS_API_SYSTEM_INFO "
            f"stage=identity_api_query error={type(exc).__name__} hint={result['error_hint']}"
        )
        return result

    operations = [
        (
            "active",
            lambda: get_direct_active_config(host, key, verify=verify, timeout=timeout),
            DIRECT_ACTIVE_ARTIFACT_TYPE,
            DIRECT_ACTIVE_METHOD,
            "DIRECT_HTTPS_API_ACTIVE_CONFIG",
            "direct-active-config.xml",
            "direct_firewall_active_config",
            False,
            True,
        ),
        (
            "effective",
            lambda: get_direct_operational_config(host, key, "effective-running", verify=verify, timeout=timeout),
            DIRECT_EFFECTIVE_ARTIFACT_TYPE,
            DIRECT_EFFECTIVE_METHOD,
            "DIRECT_HTTPS_API_EFFECTIVE_RUNNING",
            "direct-effective-running.xml",
            "direct_firewall_effective_running",
            True,
            True,
        ),
        (
            "merged",
            lambda: get_direct_operational_config(host, key, "merged", verify=verify, timeout=timeout),
            DIRECT_MERGED_ARTIFACT_TYPE,
            DIRECT_MERGED_METHOD,
            "DIRECT_HTTPS_API_MERGED_CONFIG",
            "direct-merged-config.xml",
            "direct_firewall_merged",
            False,
            True,
        ),
    ]
    if probe_pushed_template:
        operations.append((
            "pushed_template",
            lambda: get_direct_operational_config(host, key, "pushed-template", verify=verify, timeout=timeout),
            DIRECT_PUSHED_TEMPLATE_ARTIFACT_TYPE,
            DIRECT_PUSHED_TEMPLATE_METHOD,
            "DIRECT_HTTPS_API_PUSHED_TEMPLATE",
            "direct-pushed-template.xml",
            "direct_firewall_pushed_template",
            False,
            False,
        ))
    else:
        result["pushed_template"] = {
            "status": "not_probed",
            "method": "DIRECT_HTTPS_API_PUSHED_TEMPLATE",
            "transport": "DIRECT_HTTPS_XML_API",
            "required_for_primary": False,
            "required_for_alignment": False,
            "reason": "disabled_by_default_in_a4_2_known_optional_probe",
        }

    for name, getter, artifact_type, evidence_method, method_id, artifact_name, scope, required_primary, required_alignment in operations:
        artifact = _collect_direct_artifact(
            getter=getter,
            store=store,
            serial=serial,
            device=device,
            run_id=run_id,
            name=name,
            artifact_type=artifact_type,
            evidence_method=evidence_method,
            method_id=method_id,
            artifact_name=artifact_name,
            retrieval_scope=scope,
            required_for_primary=required_primary,
            required_for_alignment=required_alignment,
        )
        result[name] = artifact
        if artifact.get("status") != "success":
            err(
                f">>> PAN A4.2 FAIL device={device_name} method={artifact.get('method')} "
                f"transport={artifact.get('transport')} stage={artifact.get('failure_stage')} "
                f"error={artifact.get('error_type')} hint={artifact.get('error_hint')}"
            )

    effective_ok = (result.get("effective") or {}).get("status") == "success"
    merged_ok = (result.get("merged") or {}).get("status") == "success"
    active_ok = (result.get("active") or {}).get("status") == "success"
    result["primary_evidence_status"] = "success" if effective_ok else "failed"
    result["alignment_evidence_status"] = "complete" if effective_ok and merged_ok and active_ok else "partial"
    result["status"] = "success" if effective_ok else "failed"
    result["duration_ms"] = int((time.monotonic() - started_all) * 1000)
    return result

def _collect_device_row(
    cfg: Any,
    device: dict[str, Any],
    *,
    index: int,
    total: int,
    panorama_host: str,
    panorama_key: str,
    panorama_verify: bool | str,
    timeout: float,
    direct_verify: bool | str,
    direct_timeout: float,
    store: ConfigEvidenceStore,
    run_id: str,
    direct_compare: bool,
    panorama_intent: dict[str, Any] | None,
    expected_compiler: dict[str, Any] | None,
    probe_pushed_template: bool = False,
) -> dict[str, Any]:
    serial = device["serial"]
    device_name = str(device.get("hostname") or serial)
    target_id = _fingerprint(serial)
    if device.get("management_ip"):
        register_sensitive_value(str(device["management_ip"]), "[PAN_MGMT_IP:REDACTED]")
    assignment = assignment_for_serial(panorama_intent, serial)
    expected = expected_for_serial(expected_compiler, serial)
    row: dict[str, Any] = {
        "device": device.get("hostname"),
        "serial": serial,
        "management_ip": device.get("management_ip"),
        "connected": device.get("connected"),
        "model": device.get("model"),
        "sw_version": device.get("sw_version"),
        "shared_policy_status": device.get("shared_policy_status"),
        "template_status": device.get("template_status"),
        "ha_state": device.get("ha_state"),
        "panorama_assignment": assignment,
        "expected_configuration": expected,
        "status": "pending",
        "started_at": _utc_now(),
        "selection_index": index,
    }

    # Prefer the HA role already exposed by Panorama managed-device discovery.
    # Some PAN-OS/Panorama combinations leave that field blank. In that case,
    # issue the vendor-native read-only operational command against the target
    # firewall and use only the returned runtime state. Failure is auxiliary:
    # it must never invalidate otherwise-good configuration evidence.
    if row.get("ha_state"):
        row["ha_runtime"] = {
            "status": "success",
            "source": "panorama_managed_device_discovery",
            "method": "PANORAMA_SHOW_DEVICES_ALL_HA_STATE",
            "state": row.get("ha_state"),
            "queried_target": False,
        }
    else:
        ha_started = time.monotonic()
        try:
            ha_runtime = get_target_ha_runtime_state(
                panorama_host,
                panorama_key,
                serial,
                verify=panorama_verify,
                # Auxiliary runtime-role lookup must not stretch the primary
                # configuration collection's much larger timeout budget.
                timeout=min(timeout, 10.0),
            )
            row["ha_runtime"] = {
                "status": "success",
                "source": "panorama_target_ha_state",
                "method": "PANORAMA_HTTPS_API_TARGET_SHOW_HIGH_AVAILABILITY_STATE",
                "transport": "PANORAMA_HTTPS_XML_API_TARGET",
                "queried_target": True,
                "duration_ms": int((time.monotonic() - ha_started) * 1000),
                **ha_runtime,
            }
            if ha_runtime.get("state"):
                row["ha_state"] = ha_runtime.get("state")
        except Exception as ha_exc:
            row["ha_runtime"] = {
                "status": "failed",
                "source": "panorama_target_ha_state",
                "method": "PANORAMA_HTTPS_API_TARGET_SHOW_HIGH_AVAILABILITY_STATE",
                "transport": "PANORAMA_HTTPS_XML_API_TARGET",
                "queried_target": True,
                "duration_ms": int((time.monotonic() - ha_started) * 1000),
                "error_type": type(ha_exc).__name__,
                "error_hint": "ha_runtime_role_unavailable",
            }
    info(
        f">>> PAN A4.2 [{index}/{total}] device={device_name} target={target_id} "
        f"expected_compile={expected.get('status')} assignment={assignment.get('assignment_status')}"
    )

    query_started = time.monotonic()
    try:
        control_content = get_active_running_config(
            panorama_host,
            panorama_key,
            serial,
            verify=panorama_verify,
            timeout=timeout,
        )
        query_ms = int((time.monotonic() - query_started) * 1000)
    except Exception as exc:
        row["panorama_control"] = _method_failure(
            method="PANORAMA_HTTPS_API_TARGET_ACTIVE_CONFIG",
            transport="PANORAMA_HTTPS_XML_API_TARGET",
            stage="panorama_target_active_api_query",
            exc=exc,
            duration_ms=int((time.monotonic() - query_started) * 1000),
            required_for_primary=False,
            required_for_alignment=True,
        )
        err(
            f">>> PAN A4.2 FAIL device={device_name} method=PANORAMA_HTTPS_API_TARGET_ACTIVE_CONFIG "
            f"stage=panorama_target_active_api_query error={type(exc).__name__} "
            f"hint={row['panorama_control'].get('error_hint')}"
        )
    else:
        # Additive parse of the same already-fetched bytes stored below --
        # no new command, no new API call (OP.0a.P7 contract). Fail-closed:
        # an unparseable document yields peer_ip=None, never a guess, and
        # never blocks storing the artifact itself.
        peer_addresses = parse_ha_peer_ip_from_config(control_content)
        if isinstance(row.get("ha_runtime"), dict):
            row["ha_runtime"]["peer_ip"] = peer_addresses["peer_ip"]
            row["ha_runtime"]["peer_ipv6"] = peer_addresses["peer_ipv6"]

        store_started = time.monotonic()
        try:
            artifact = _store_artifact(
                store,
                source="panorama",
                serial=serial,
                artifact_type=PANORAMA_ARTIFACT_TYPE,
                artifact_name="running-config.xml",
                content=control_content,
                method=PANORAMA_METHOD,
                device=device,
                run_id=run_id,
                retrieval_scope="active_config_via_panorama_control",
                duration_ms=query_ms,
            )
            artifact.update({
                "method": "PANORAMA_HTTPS_API_TARGET_ACTIVE_CONFIG",
                "transport": "PANORAMA_HTTPS_XML_API_TARGET",
                "api_query_duration_ms": query_ms,
                "local_store_duration_ms": int((time.monotonic() - store_started) * 1000),
                "required_for_primary": False,
                "required_for_alignment": True,
            })
            row["panorama_control"] = artifact
        except Exception as exc:
            row["panorama_control"] = _method_failure(
                method="PANORAMA_HTTPS_API_TARGET_ACTIVE_CONFIG",
                transport="LOCAL_IMMUTABLE_EVIDENCE_STORE",
                stage="panorama_target_active_local_store",
                exc=exc,
                duration_ms=int((time.monotonic() - store_started) * 1000),
                required_for_primary=False,
                required_for_alignment=True,
            )
            err(
                f">>> PAN A4.2 FAIL device={device_name} method=PANORAMA_HTTPS_API_TARGET_ACTIVE_CONFIG "
                f"transport=LOCAL_IMMUTABLE_EVIDENCE_STORE stage=panorama_target_active_local_store "
                f"error={type(exc).__name__} hint={row['panorama_control'].get('error_hint')}"
            )

    if direct_compare:
        row["direct"] = _collect_direct_compare(
            cfg,
            device,
            store=store,
            run_id=run_id,
            verify=direct_verify,
            timeout=direct_timeout,
            probe_pushed_template=probe_pushed_template,
        )
    else:
        row["direct"] = {"attempted": False, "status": "disabled"}

    row["comparison"] = {
        "panorama_active_vs_direct_active": _compare_artifacts(
            row.get("panorama_control"), (row.get("direct") or {}).get("active")
        ),
        "direct_active_vs_effective": _compare_artifacts(
            (row.get("direct") or {}).get("active"), (row.get("direct") or {}).get("effective")
        ),
        "direct_active_vs_merged": _compare_artifacts(
            (row.get("direct") or {}).get("active"), (row.get("direct") or {}).get("merged")
        ),
        "direct_merged_vs_effective": _compare_artifacts(
            (row.get("direct") or {}).get("merged"), (row.get("direct") or {}).get("effective")
        ),
        "pushed_template_vs_merged": _compare_artifacts(
            (row.get("direct") or {}).get("pushed_template"), (row.get("direct") or {}).get("merged")
        ),
    }
    row["configuration_alignment"] = _configuration_alignment(
        device, row["comparison"], row.get("direct") or {}, assignment
    )

    # A4.2: compare compiled Template-Stack scalar intent with the direct
    # firewall effective-running tree. Detailed setting paths/hashes remain
    # local-only and are removed from the shareable payload after persistence.
    direct_for_alignment = row.get("direct") or {}
    try:
        setting_detail = align_expected_to_effective(
            serial=serial,
            expected_compiler=expected_compiler,
            expected_row=expected,
            effective_content=_artifact_bytes(direct_for_alignment.get("effective")),
            merged_content=_artifact_bytes(direct_for_alignment.get("merged")),
            active_content=_artifact_bytes(direct_for_alignment.get("active")),
            panorama_sync=row.get("configuration_alignment") or {},
        )
    except Exception as align_exc:
        setting_detail = {
            "schema_version": "0.6.0A4.2",
            "status": "failed",
            "device_status": "INSUFFICIENT_EVIDENCE",
            "reason": "setting_alignment_engine_exception",
            "error_type": type(align_exc).__name__,
            "summary": {
                "expected_settings": int((((expected.get("template_expected") or {}).get("compiled_setting_count")) or 0)),
                "alignment_ready_settings": int((((expected.get("template_expected") or {}).get("alignment_ready_setting_count")) or 0)),
                "evaluated_settings": 0,
                "classification_counts": {"INSUFFICIENT_EVIDENCE": 1},
                "findings": {},
            },
            "results": [],
            "raw_values_included": False,
        }
        err(
            f">>> PAN A4.2 ALIGNMENT FAIL device={device_name} method=LOCAL_SETTING_ALIGNMENT_ENGINE "
            f"stage=expected_vs_effective_compare error={type(align_exc).__name__}"
        )
    row["_setting_alignment_detail"] = setting_detail
    row["setting_alignment"] = _safe_setting_alignment(setting_detail)
    row["configuration_alignment"]["setting_level_status"] = setting_detail.get("device_status")
    row["configuration_alignment"]["setting_level_engine_status"] = setting_detail.get("status")
    row["configuration_alignment"]["setting_level_counts"] = (setting_detail.get("summary") or {}).get("classification_counts") or {}
    row["configuration_alignment"]["compiled_expected_config"] = bool(
        expected.get("primary_template_stack") and setting_detail.get("status") in {"success", "partial"}
    )

    direct = row.get("direct") or {}
    identity_ok = direct.get("identity_verified") is True
    effective_ok = ((direct.get("effective") or {}).get("status") == "success")
    merged_ok = ((direct.get("merged") or {}).get("status") == "success")
    active_ok = ((direct.get("active") or {}).get("status") == "success")
    row["primary_evidence_status"] = "success" if identity_ok and effective_ok else "failed"
    row["alignment_evidence_status"] = (
        "complete" if identity_ok and effective_ok and merged_ok and active_ok else
        "partial" if identity_ok and effective_ok and merged_ok else
        "insufficient"
    )
    if not direct_compare:
        row["status"] = "success" if (row.get("panorama_control") or {}).get("status") == "success" else "failed"
    else:
        # A4 promotes effective-running to the primary evidence gate. Local active
        # is alignment/provenance evidence and no longer makes the whole job fail.
        row["status"] = "success" if row["primary_evidence_status"] == "success" else "failed"
    row["completed_at"] = _utc_now()
    info(
        f">>> PAN A4.2 [{index}/{total}] device={device_name} status={row['status']} "
        f"primary={row['primary_evidence_status']} alignment={row['alignment_evidence_status']} "
        f"expected={expected.get('status')} stack={expected.get('template_stack_status')} "
        f"identity={identity_ok} active={((direct.get('active') or {}).get('status'))} "
        f"effective={((direct.get('effective') or {}).get('status'))} "
        f"merged={((direct.get('merged') or {}).get('status'))} "
        f"classification={(row.get('configuration_alignment') or {}).get('status')} "
        f"setting_alignment={(row.get('setting_alignment') or {}).get('device_status')}"
    )
    return row

def _failure_records(
    rows: list[dict[str, Any]],
    panorama_intent: dict[str, Any] | None = None,
    expected_compiler: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    failures: list[dict[str, Any]] = []
    if panorama_intent and panorama_intent.get("status") == "failed":
        artifact = panorama_intent.get("artifact") or {}
        failures.append({
            "device": "PANORAMA_CONTROL_PLANE",
            "serial": None,
            "management_ip": None,
            "method": artifact.get("method") or "PANORAMA_HTTPS_API_ACTIVE_MANAGEMENT_CONFIG",
            "transport": artifact.get("transport") or "PANORAMA_HTTPS_XML_API",
            "failure_domain": artifact.get("failure_domain"),
            "failure_stage": artifact.get("failure_stage"),
            "error_type": artifact.get("error_type"),
            "error_hint": artifact.get("error_hint"),
            "required_for_primary": False,
            "required_for_alignment": True,
        })
    if expected_compiler:
        compiler_status = expected_compiler.get("status")
        if compiler_status in {"failed", "unavailable"}:
            failures.append({
                "device": "PANORAMA_CONTROL_PLANE",
                "serial": None,
                "management_ip": None,
                "method": "PANORAMA_EXPECTED_CONFIGURATION_COMPILER",
                "transport": "LOCAL_PANORAMA_INTENT_COMPILER",
                "failure_domain": "local_compiler",
                "failure_stage": "expected_compiler_compile",
                "error_type": expected_compiler.get("error_type") or "ExpectedCompilerUnavailable",
                "error_hint": expected_compiler.get("error_hint") or "panorama_expected_compiler_failed",
                "required_for_primary": False,
                "required_for_alignment": True,
            })
        elif expected_compiler.get("local_store_status") == "failed":
            failures.append({
                "device": "PANORAMA_CONTROL_PLANE",
                "serial": None,
                "management_ip": None,
                "method": "PANORAMA_EXPECTED_CONFIGURATION_COMPILER",
                "transport": "LOCAL_IMMUTABLE_DERIVED_STORE",
                "failure_domain": "local_store",
                "failure_stage": "expected_compiler_local_store",
                "error_type": expected_compiler.get("local_store_error_type") or "OSError",
                "error_hint": expected_compiler.get("local_store_error_hint") or "local_filesystem_permission_or_lock",
                "required_for_primary": False,
                "required_for_alignment": True,
            })
    for row in rows:
        base = {
            "device": row.get("device"),
            "serial": row.get("serial"),
            "management_ip": row.get("management_ip"),
        }
        control = row.get("panorama_control") or {}
        if control.get("status") == "failed":
            failures.append({
                **base,
                "method": control.get("method") or "PANORAMA_HTTPS_API_TARGET_ACTIVE_CONFIG",
                "transport": control.get("transport"),
                "failure_domain": control.get("failure_domain"),
                "failure_stage": control.get("failure_stage"),
                "error_type": control.get("error_type"),
                "error_hint": control.get("error_hint"),
                "required_for_primary": control.get("required_for_primary", False),
                "required_for_alignment": control.get("required_for_alignment", True),
            })
        direct = row.get("direct") or {}
        if direct.get("status") in {"failed", "identity_mismatch", "missing_management_ip"} and not direct.get("identity_verified"):
            failures.append({
                **base,
                "method": direct.get("failure_method") or "DIRECT_HTTPS_API",
                "transport": "DIRECT_HTTPS_XML_API",
                "failure_domain": direct.get("failure_domain"),
                "failure_stage": direct.get("failure_stage"),
                "error_type": direct.get("error_type"),
                "error_hint": direct.get("error_hint"),
                "required_for_primary": True,
                "required_for_alignment": True,
            })
            continue
        for name in ("active", "effective", "merged", "pushed_template"):
            artifact = direct.get(name) or {}
            if artifact.get("status") != "failed":
                continue
            failures.append({
                **base,
                "method": artifact.get("method"),
                "transport": artifact.get("transport"),
                "failure_domain": artifact.get("failure_domain"),
                "failure_stage": artifact.get("failure_stage"),
                "error_type": artifact.get("error_type"),
                "error_hint": artifact.get("error_hint"),
                "required_for_primary": bool(artifact.get("required_for_primary")),
                "required_for_alignment": bool(artifact.get("required_for_alignment")),
            })
        setting = row.get("setting_alignment") or {}
        if setting.get("status") == "failed":
            failures.append({
                **base,
                "method": "LOCAL_SETTING_ALIGNMENT_ENGINE",
                "transport": "LOCAL_DERIVED_ANALYSIS",
                "failure_domain": "local_alignment_engine",
                "failure_stage": "expected_vs_effective_compare",
                "error_type": setting.get("error_type") or "SettingAlignmentError",
                "error_hint": "setting_alignment_engine_failed",
                "required_for_primary": False,
                "required_for_alignment": True,
            })
    return failures


def _write_local_failures(failures: list[dict[str, Any]], run_id: str) -> Path:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    path = OUTPUT_DIR / f"pan_config_failures_{run_id}.json"
    tmp = path.with_suffix(".json.tmp")
    payload = {
        "schema_version": "0.6.0A4.2",
        "local_only": True,
        "contains_device_identity": True,
        "note": "This diagnostic file is local-only and is not copied raw into the shareable support bundle.",
        "failures": failures,
    }
    tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)
    return path


def _sum_artifact_metrics(rows: list[dict[str, Any]], artifact_name: str) -> dict[str, int]:
    total = {"vsys": 0, "virtual_routers": 0, "zones": 0, "interfaces": 0, "security_rules": 0}
    for row in rows:
        artifact = ((row.get("direct") or {}).get(artifact_name) or {})
        if artifact.get("status") != "success":
            continue
        metrics = _artifact_metrics(artifact.get("structural_validation"))
        for key in total:
            total[key] += int(metrics.get(key) or 0)
    return total


def _run_storage_stats(rows: list[dict[str, Any]], panorama_intent_result: dict[str, Any]) -> dict[str, int]:
    """Summarize logical vs newly-stored payload bytes for the current run."""
    artifacts: list[dict[str, Any]] = []
    intent_artifact = (panorama_intent_result or {}).get("artifact") or {}
    if intent_artifact.get("status") == "success":
        artifacts.append(intent_artifact)
    for row in rows:
        control = row.get("panorama_control") or {}
        if control.get("status") == "success":
            artifacts.append(control)
        direct = row.get("direct") or {}
        for name in ("active", "effective", "merged", "pushed_template"):
            artifact = direct.get(name) or {}
            if artifact.get("status") == "success":
                artifacts.append(artifact)

    logical = sum(int(a.get("size_bytes") or 0) for a in artifacts)
    stored = sum(int(a.get("stored_bytes_delta") or 0) for a in artifacts)
    return {
        "storage_artifact_events": len(artifacts),
        "storage_new_objects": sum(1 for a in artifacts if a.get("blob_created") is True),
        "storage_reused_objects": sum(1 for a in artifacts if a.get("blob_created") is False),
        "storage_logical_payload_bytes": logical,
        "storage_new_object_bytes": stored,
        "storage_dedup_bytes_avoided": max(0, logical - stored),
    }


def _apply_pan_target_selector(
    devices: list[dict[str, Any]],
    connected: list[dict[str, Any]],
    requested_serials: Sequence[str] | None,
) -> list[dict[str, Any]]:
    """OP.0d exact, fail-closed narrowing of already-discovered PAN candidates.

    Matches on `serial` -- the same identity-gated value the direct-firewall
    identity check already cross-verifies against `show system info`, never a
    hostname/label/substring/regex/wildcard. Every requested serial must
    resolve to exactly one currently-connected discovered device before this
    returns; an unknown, ambiguous, or not-currently-connected serial raises
    here, before `run_panorama_config_evidence` issues a single direct
    firewall API call. Narrows only -- never adds a target `limit` did not
    already make eligible.
    """
    requested = list(dict.fromkeys(str(s).strip() for s in requested_serials if str(s).strip()))
    if not requested:
        raise ValueError("pan_config_targets: no valid serial supplied")

    by_serial: dict[str, list[dict[str, Any]]] = {}
    for device in devices:
        by_serial.setdefault(str(device.get("serial") or ""), []).append(device)

    unknown = sorted(s for s in requested if s not in by_serial)
    if unknown:
        raise ValueError(
            "pan_config_targets: unknown serial(s), refusing to contact any device: "
            + ", ".join(unknown)
        )

    ambiguous = sorted(s for s in requested if len(by_serial[s]) > 1)
    if ambiguous:
        raise ValueError(
            "pan_config_targets: ambiguous serial(s) resolve to more than one discovered device, "
            "refusing to contact any device: " + ", ".join(ambiguous)
        )

    connected_serials = {str(d.get("serial") or "") for d in connected}
    not_connected = sorted(s for s in requested if s not in connected_serials)
    if not_connected:
        raise ValueError(
            "pan_config_targets: requested serial(s) not currently connected, refusing to contact: "
            + ", ".join(not_connected)
        )

    by_serial_connected = {str(d.get("serial") or ""): d for d in connected}
    return [by_serial_connected[s] for s in requested]


def run_panorama_config_evidence(
    cfg: Any,
    *,
    limit: int | None = 5,
    direct_compare: bool = True,
    max_workers: int | None = None,
    orchestration_run_id: str | None = None,
    probe_pushed_template: bool = True,
    target_serials: Sequence[str] | None = None,
) -> dict[str, Any]:
    """Phase 0.6.0A4.2.1: PAN Semantic Validation.

    Panorama remains discovery/intent and direct effective-running remains the
    primary actual-state evidence. A4.2 uses the A4.1 compiled Template Stack
    scalar model and compares alignment-ready settings to direct effective-
    running evidence. Override/drift claims require explicit multi-source proof.
    Device Group policy-value alignment remains deferred. Read-only.
    """

    global OUTPUT_DIR
    OUTPUT_DIR = Path(cfg.runtime_paths.output_root) if getattr(cfg, "runtime_paths", None) is not None else OUTPUT_DIR
    run_id = orchestration_run_id or _run_id()
    panorama_host = fix_host(cfg.panorama_ip)
    panorama_verify = _tls_verify_setting()
    direct_verify = _direct_tls_verify_setting()
    timeout = _timeout_seconds()
    direct_timeout = _direct_timeout_seconds()
    workers = _worker_count(max_workers)
    store = ConfigEvidenceStore()
    expected_compiler_result: dict[str, Any] = {"status": "pending", "summary": {}}
    intent_content: bytes | None = None

    # Preflight: if a CA bundle path is configured, verify it exists and is
    # readable before any network call.  This mirrors the 0.6.4 CP SSH
    # host-key strict-preflight pattern and prevents a confusing TLS failure
    # deep in the collection loop.
    try:
        preflight_pan_tls_ca_bundle(panorama_verify)
    except PanTlsStrictPreflightError as preflight_exc:
        raise PanTlsStrictPreflightError(
            "pan_config_panorama_tls_preflight_failed"
        ) from preflight_exc
    if direct_compare:
        try:
            preflight_pan_tls_ca_bundle(direct_verify)
        except PanTlsStrictPreflightError as preflight_exc:
            raise PanTlsStrictPreflightError(
                "pan_config_direct_tls_preflight_failed"
            ) from preflight_exc

    if panorama_verify is False:
        import urllib3
        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
        warn(">>> PANORAMA CONFIG A4.2.2 TLS verification is disabled; set SECURITYEXPERT_PAN_CA_BUNDLE for production trust")
    if direct_compare and direct_verify is False:
        import urllib3
        urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
        warn(">>> DIRECT PAN-OS API A4.2.2 TLS verification is disabled; set SECURITYEXPERT_PAN_DIRECT_CA_BUNDLE for production trust")

    info(">>> PHASE 0.6.0A4.2.2 PAN SEMANTIC POLICY & PROVENANCE HARDENING START")
    panorama_key = get_api_key(cfg, panorama_host, verify=panorama_verify, timeout=timeout)
    register_sensitive_value(panorama_key, "[API_KEY:REDACTED]")

    if not direct_compare:
        panorama_intent_result: dict[str, Any] = {
            "status": "disabled",
            "analysis": {},
            "artifact": None,
        }
        expected_compiler_result = {"status": "disabled", "summary": {}}
    else:
        # Read Panorama's own active configuration once. This is the management
        # source-of-truth inventory for Template/Stack/Device-Group assignment.
        panorama_intent_result: dict[str, Any] = {"status": "pending"}
        intent_started = time.monotonic()
        try:
            intent_content = get_panorama_management_config(
                panorama_host, panorama_key, verify=panorama_verify, timeout=timeout
            )
            intent_query_ms = int((time.monotonic() - intent_started) * 1000)
            intent_analysis = analyze_panorama_intent(intent_content)
            panorama_intent_result["analysis"] = intent_analysis

            compiler_started = time.monotonic()
            try:
                compiled_expected = compile_panorama_expected(intent_content)
                compile_ms = int((time.monotonic() - compiler_started) * 1000)
                try:
                    manifest_path, compiler_report_path = _write_expected_compiler_local(compiled_expected, run_id)
                    expected_compiler_result = {
                        **compiled_expected,
                        "status": "success",
                        "duration_ms": compile_ms,
                        "manifest_path": str(manifest_path),
                        "report_path": str(compiler_report_path),
                        "local_store_status": "success",
                    }
                except Exception as store_exc:
                    expected_compiler_result = {
                        **compiled_expected,
                        "status": "partial",
                        "duration_ms": compile_ms,
                        "manifest_path": None,
                        "report_path": None,
                        "local_store_status": "failed",
                        "local_store_error_type": type(store_exc).__name__,
                        "local_store_error_hint": "local_filesystem_permission_or_lock",
                    }
                    err(
                        f">>> PAN A4.2 EXPECTED COMPILER LOCAL STORE FAIL error={type(store_exc).__name__} "
                        "hint=local_filesystem_permission_or_lock"
                    )
            except Exception as compiler_exc:
                expected_compiler_result = {
                    "status": "failed",
                    "summary": {},
                    "duration_ms": int((time.monotonic() - compiler_started) * 1000),
                    "error_type": type(compiler_exc).__name__,
                    "error_hint": "panorama_expected_compiler_failed",
                }
                err(
                    f">>> PAN A4.2 EXPECTED COMPILER FAIL error={type(compiler_exc).__name__} "
                    "hint=panorama_expected_compiler_failed"
                )

            store_started = time.monotonic()
            try:
                artifact = _store_artifact(
                    store,
                    source="panorama-control",
                    serial="panorama-management",
                    artifact_type=PANORAMA_INTENT_ARTIFACT_TYPE,
                    artifact_name="panorama-active-management-config.xml",
                    content=intent_content,
                    method=PANORAMA_INTENT_METHOD,
                    device={"hostname": "Panorama", "management_ip": cfg.panorama_ip, "model": None, "sw_version": None},
                    run_id=run_id,
                    retrieval_scope="panorama_active_management_config_for_assignment_provenance",
                    duration_ms=intent_query_ms,
                )
                artifact.update({
                    "method": "PANORAMA_HTTPS_API_ACTIVE_MANAGEMENT_CONFIG",
                    "transport": "PANORAMA_HTTPS_XML_API",
                    "api_query_duration_ms": intent_query_ms,
                    "local_store_duration_ms": int((time.monotonic() - store_started) * 1000),
                    "required_for_primary": False,
                    "required_for_alignment": True,
                })
                panorama_intent_result.update({"status": "success", "artifact": artifact})
            except Exception as exc:
                # Analysis can still proceed from the in-memory config even if the
                # local immutable-store publish fails.
                panorama_intent_result.update({
                    "status": "partial",
                    "artifact": _method_failure(
                        method="PANORAMA_HTTPS_API_ACTIVE_MANAGEMENT_CONFIG",
                        transport="LOCAL_IMMUTABLE_EVIDENCE_STORE",
                        stage="panorama_intent_local_store",
                        exc=exc,
                        duration_ms=int((time.monotonic() - store_started) * 1000),
                        required_for_primary=False,
                        required_for_alignment=True,
                    ),
                })
                err(
                    f">>> PAN A4.2 FAIL device=PANORAMA method=PANORAMA_HTTPS_API_ACTIVE_MANAGEMENT_CONFIG "
                    f"transport=LOCAL_IMMUTABLE_EVIDENCE_STORE stage=panorama_intent_local_store "
                    f"error={type(exc).__name__} hint={panorama_intent_result['artifact'].get('error_hint')}"
                )
        except Exception as exc:
            panorama_intent_result.update({
                "status": "failed",
                "analysis": None,
                "artifact": _method_failure(
                    method="PANORAMA_HTTPS_API_ACTIVE_MANAGEMENT_CONFIG",
                    transport="PANORAMA_HTTPS_XML_API",
                    stage="panorama_intent_api_query",
                    exc=exc,
                    duration_ms=int((time.monotonic() - intent_started) * 1000),
                    required_for_primary=False,
                    required_for_alignment=True,
                ),
            })
            if expected_compiler_result.get("status") == "pending":
                expected_compiler_result = {
                    "status": "unavailable",
                    "summary": {},
                    "error_type": type(exc).__name__,
                    "error_hint": "panorama_intent_unavailable",
                }
            err(
                f">>> PAN A4.2 FAIL device=PANORAMA method=PANORAMA_HTTPS_API_ACTIVE_MANAGEMENT_CONFIG "
                f"stage=panorama_intent_api_query error={type(exc).__name__} "
                f"hint={panorama_intent_result['artifact'].get('error_hint')}"
            )

    intent_analysis = panorama_intent_result.get("analysis") or {}
    intent_summary = intent_analysis.get("summary") or {}
    info(
        f">>> PAN A4.2 PANORAMA INTENT status={panorama_intent_result.get('status')} "
        f"templates={intent_summary.get('templates')} stacks={intent_summary.get('template_stacks')} "
        f"device_groups={intent_summary.get('device_groups')} assigned_serials={intent_summary.get('assigned_serials')}"
    )
    compiler_summary_all = expected_compiler_result.get("summary") or {}
    info(
        f">>> PAN A4.2 EXPECTED COMPILER status={expected_compiler_result.get('status')} "
        f"serials={compiler_summary_all.get('assigned_serials')} "
        f"single_stack={compiler_summary_all.get('serials_with_exactly_one_stack')} "
        f"settings={compiler_summary_all.get('compiled_scalar_settings_across_unique_stacks')} "
        f"ready={compiler_summary_all.get('alignment_ready_scalar_settings_across_unique_stacks')} "
        f"vars={compiler_summary_all.get('unresolved_variable_settings_across_unique_stacks')}"
    )

    devices = get_devices(panorama_host, panorama_key, verify=panorama_verify, timeout=timeout)
    connected = [d for d in devices if d.get("connected") == "yes"]
    disconnected = [d for d in devices if d.get("connected") != "yes"]
    if target_serials:
        # OP.0d: an explicit selector is a narrower, operator-approved request
        # than limit -- it takes precedence and limit's own count-based
        # selection is not applied on top of it.
        selected = _apply_pan_target_selector(devices, connected, target_serials)
        stage = f"explicit-{len(selected)}-target(s)"
    elif limit is None:
        selected = connected
        stage = "all-connected"
    else:
        selected = connected[: max(0, int(limit))]
        stage = f"first-{max(0, int(limit))}-connected"
    actual_workers = min(workers, len(selected)) if selected else 0
    info(
        f">>> PAN A4.2 DISCOVERED={len(devices)} CONNECTED={len(connected)} "
        f"DISCONNECTED={len(disconnected)} SELECTED={len(selected)} STAGE={stage} WORKERS={actual_workers}"
    )

    if selected:
        def worker(item: tuple[int, dict[str, Any]]) -> dict[str, Any]:
            index, device = item
            return _collect_device_row(
                cfg,
                device,
                index=index,
                total=len(selected),
                panorama_host=panorama_host,
                panorama_key=panorama_key,
                panorama_verify=panorama_verify,
                timeout=timeout,
                direct_verify=direct_verify,
                direct_timeout=direct_timeout,
                store=store,
                run_id=run_id,
                direct_compare=direct_compare,
                panorama_intent=intent_analysis,
                expected_compiler=expected_compiler_result,
                probe_pushed_template=probe_pushed_template,
            )

        with ThreadPoolExecutor(max_workers=actual_workers) as executor:
            rows = list(executor.map(worker, enumerate(selected, 1)))
    else:
        rows = []

    rows.sort(key=lambda row: int(row.get("selection_index") or 0))

    setting_alignment_store: dict[str, Any] = {"status": "pending"}
    try:
        setting_manifest_path, setting_report_path = _write_setting_alignment_local(rows, run_id)
        setting_alignment_store = {
            "status": "success",
            "manifest_path": str(setting_manifest_path),
            "report_path": str(setting_report_path),
        }
    except Exception as setting_store_exc:
        setting_alignment_store = {
            "status": "failed",
            "manifest_path": None,
            "report_path": None,
            "error_type": type(setting_store_exc).__name__,
            "error_hint": "local_filesystem_permission_or_lock",
        }
        err(
            f">>> PAN A4.2.2 SETTING ALIGNMENT LOCAL STORE FAIL error={type(setting_store_exc).__name__} "
            "hint=local_filesystem_permission_or_lock"
        )

    semantic_validation_result: dict[str, Any] = {
        "schema_version": "0.6.0A4.2.1",
        "status": "unavailable",
        "summary": {"manual_confirmation_status": "not_ready"},
    }
    semantic_validation_store: dict[str, Any] = {"status": "unavailable"}
    if rows and direct_compare and intent_content is not None:
        try:
            semantic_validation_result = build_semantic_validation(
                rows=rows,
                panorama_content=intent_content,
                artifact_loader=lambda row, kind: _artifact_bytes(((row.get("direct") or {}).get(kind))),
            )
            semantic_manifest_path, semantic_report_path, semantic_csv_path = _write_semantic_validation_local(
                semantic_validation_result, run_id
            )
            semantic_validation_store = {
                "status": "success",
                "manifest_path": str(semantic_manifest_path),
                "report_path": str(semantic_report_path),
                "samples_csv_path": str(semantic_csv_path),
            }
        except Exception as semantic_exc:
            semantic_validation_result = {
                "schema_version": "0.6.0A4.2.1",
                "status": "failed",
                "error_type": type(semantic_exc).__name__,
                "summary": {"manual_confirmation_status": "not_ready"},
            }
            semantic_validation_store = {
                "status": "failed",
                "error_type": type(semantic_exc).__name__,
                "error_hint": "semantic_validation_engine_or_local_store_failed",
            }
            err(
                f">>> PAN A4.2.2 SEMANTIC VALIDATION FAIL method=LOCAL_SEMANTIC_VALIDATION_ENGINE "
                f"stage=semantic_validation error={type(semantic_exc).__name__}"
            )

    # Full setting paths and value hashes live only in local derived artifacts.
    # Semantic operator samples may contain selected non-sensitive raw values and
    # are also local-only. Remove detailed A4.2 rows before support generation.
    for row in rows:
        row.pop("_setting_alignment_detail", None)

    alignment_counts = Counter(
        str((row.get("configuration_alignment") or {}).get("status") or "UNKNOWN")
        for row in rows
    )
    setting_engine_status_counts: Counter[str] = Counter()
    setting_device_status_counts: Counter[str] = Counter()
    setting_classification_counts: Counter[str] = Counter()
    setting_category_counts: dict[str, Counter[str]] = {}
    for row in rows:
        setting = row.get("setting_alignment") or {}
        setting_engine_status_counts[str(setting.get("status") or "unavailable")] += 1
        setting_device_status_counts[str(setting.get("device_status") or "INSUFFICIENT_EVIDENCE")] += 1
        setting_summary = setting.get("summary") or {}
        setting_classification_counts.update(setting_summary.get("classification_counts") or {})
        for category, counts in (setting_summary.get("category_counts") or {}).items():
            setting_category_counts.setdefault(str(category), Counter()).update(counts or {})
    storage_stats = _run_storage_stats(rows, panorama_intent_result)
    summary = {
        "run_id": run_id,
        "stage": stage,
        "workers": actual_workers,
        "discovered": len(devices),
        "connected_discovered": len(connected),
        "disconnected_discovered": len(disconnected),
        "selected": len(selected),
        "success": sum(1 for row in rows if row.get("status") == "success"),
        "partial": sum(1 for row in rows if row.get("alignment_evidence_status") == "partial" and row.get("primary_evidence_status") == "success"),
        "failed": sum(1 for row in rows if row.get("status") == "failed"),
        "primary_evidence_success": sum(1 for row in rows if row.get("primary_evidence_status") == "success"),
        "alignment_evidence_complete": sum(1 for row in rows if row.get("alignment_evidence_status") == "complete"),
        "alignment_evidence_partial": sum(1 for row in rows if row.get("alignment_evidence_status") == "partial"),
        "panorama_intent_status": panorama_intent_result.get("status"),
        "panorama_intent_templates": int(intent_summary.get("templates") or 0),
        "panorama_intent_template_stacks": int(intent_summary.get("template_stacks") or 0),
        "panorama_intent_device_groups": int(intent_summary.get("device_groups") or 0),
        "panorama_assignment_mapped": sum(1 for row in rows if (row.get("panorama_assignment") or {}).get("assignment_status") == "mapped"),
        "expected_compiler_status": expected_compiler_result.get("status"),
        "expected_compiler_assigned_serials": int(compiler_summary_all.get("assigned_serials") or 0),
        "expected_compiler_single_stack_serials": int(compiler_summary_all.get("serials_with_exactly_one_stack") or 0),
        "expected_compiler_multiple_stack_serials": int(compiler_summary_all.get("serials_with_multiple_stacks") or 0),
        "expected_compiler_missing_template_references": int(compiler_summary_all.get("missing_template_references") or 0),
        "expected_compiler_unique_stack_scalar_settings": int(compiler_summary_all.get("compiled_scalar_settings_across_unique_stacks") or 0),
        "expected_compiler_unique_stack_alignment_ready_settings": int(compiler_summary_all.get("alignment_ready_scalar_settings_across_unique_stacks") or 0),
        "expected_compiler_unique_stack_unresolved_variables": int(compiler_summary_all.get("unresolved_variable_settings_across_unique_stacks") or 0),
        "expected_compiler_selected_mapped": sum(1 for row in rows if (row.get("expected_configuration") or {}).get("primary_template_stack")),
        "expected_compiler_selected_compiled": sum(1 for row in rows if (row.get("expected_configuration") or {}).get("status") == "compiled"),
        "expected_compiler_selected_partial": sum(1 for row in rows if (row.get("expected_configuration") or {}).get("status") == "partial"),
        "expected_compiler_selected_unmapped": sum(1 for row in rows if (row.get("expected_configuration") or {}).get("status") in {"unmapped", "unavailable"}),
        "expected_compiler_selected_alignment_ready_settings": sum(
            int((((row.get("expected_configuration") or {}).get("template_expected") or {}).get("alignment_ready_setting_count") or 0))
            for row in rows
        ),
        "expected_compiler_selected_unresolved_variable_settings": sum(
            int((((row.get("expected_configuration") or {}).get("template_expected") or {}).get("unresolved_variable_setting_count") or 0))
            for row in rows
        ),
        "expected_compiler_selected_policy_scopes": sum(len((row.get("expected_configuration") or {}).get("policy_scopes") or []) for row in rows),
        "expected_compiler_selected_policy_mapped_devices": sum(1 for row in rows if (row.get("expected_configuration") or {}).get("policy_scopes")),
        "expected_compiler_selected_policy_lineage_complete_devices": sum(
            1 for row in rows
            if (row.get("expected_configuration") or {}).get("policy_scopes")
            and all(((scope.get("lineage") or {}).get("status") == "compiled") for scope in ((row.get("expected_configuration") or {}).get("policy_scopes") or []))
        ),
        "expected_compiler_selected_anomalies": sum(len((row.get("expected_configuration") or {}).get("anomalies") or []) for row in rows),
        "panorama_control_success": sum(1 for row in rows if (row.get("panorama_control") or {}).get("status") == "success"),
        "ha_runtime_role_available": sum(1 for row in rows if row.get("ha_state")),
        "ha_runtime_target_queries": sum(1 for row in rows if (row.get("ha_runtime") or {}).get("queried_target") is True),
        "ha_runtime_target_success": sum(
            1 for row in rows
            if (row.get("ha_runtime") or {}).get("queried_target") is True
            and (row.get("ha_runtime") or {}).get("status") == "success"
        ),
        "ha_runtime_target_failed": sum(
            1 for row in rows
            if (row.get("ha_runtime") or {}).get("queried_target") is True
            and (row.get("ha_runtime") or {}).get("status") == "failed"
        ),
        "direct_candidates": sum(1 for row in rows if row.get("management_ip")),
        "direct_api_auth_success": sum(1 for row in rows if (row.get("direct") or {}).get("api_auth") == "success"),
        "direct_identity_verified": sum(1 for row in rows if (row.get("direct") or {}).get("identity_verified") is True),
        "direct_identity_mismatch": sum(1 for row in rows if (row.get("direct") or {}).get("identity_mismatch") is True),
        "direct_active_success": sum(1 for row in rows if ((row.get("direct") or {}).get("active") or {}).get("status") == "success"),
        "direct_effective_success": sum(1 for row in rows if ((row.get("direct") or {}).get("effective") or {}).get("status") == "success"),
        "direct_merged_success": sum(1 for row in rows if ((row.get("direct") or {}).get("merged") or {}).get("status") == "success"),
        "direct_pushed_template_success": sum(1 for row in rows if ((row.get("direct") or {}).get("pushed_template") or {}).get("status") == "success"),
        "direct_full_evidence_success": sum(
            1 for row in rows
            if (row.get("direct") or {}).get("identity_verified") is True
            and all(((row.get("direct") or {}).get(name) or {}).get("status") == "success" for name in ("active", "effective", "merged"))
        ),
        "panorama_direct_active_exact_match": sum(1 for row in rows if ((row.get("comparison") or {}).get("panorama_active_vs_direct_active") or {}).get("exact_sha256_match") is True),
        "panorama_direct_active_canonical_match": sum(1 for row in rows if ((row.get("comparison") or {}).get("panorama_active_vs_direct_active") or {}).get("exact_canonical_match") is True),
        "panorama_active_direct_semantic_mismatch": sum(
            1 for row in rows
            if ((row.get("comparison") or {}).get("panorama_active_vs_direct_active") or {}).get("available") is True
            and ((row.get("comparison") or {}).get("panorama_active_vs_direct_active") or {}).get("exact_canonical_match") is False
        ),
        "effective_richer_than_direct_active": sum(1 for row in rows if ((row.get("comparison") or {}).get("direct_active_vs_effective") or {}).get("right_richer_signal_count", 0) > 0),
        "merged_richer_than_direct_active": sum(1 for row in rows if ((row.get("comparison") or {}).get("direct_active_vs_merged") or {}).get("right_richer_signal_count", 0) > 0),
        "panorama_shared_policy_out_of_sync": sum(1 for row in rows if (row.get("configuration_alignment") or {}).get("panorama_shared_policy_sync") == "out_of_sync"),
        "panorama_template_out_of_sync": sum(1 for row in rows if (row.get("configuration_alignment") or {}).get("panorama_template_sync") == "out_of_sync"),
        "panorama_any_out_of_sync": sum(1 for row in rows if (row.get("configuration_alignment") or {}).get("panorama_reports_out_of_sync") is True),
        "alignment_classifications": dict(sorted(alignment_counts.items())),
        "setting_alignment_engine_statuses": dict(sorted(setting_engine_status_counts.items())),
        "setting_alignment_device_statuses": dict(sorted(setting_device_status_counts.items())),
        "setting_alignment_classifications": dict(sorted(setting_classification_counts.items())),
        "setting_alignment_category_counts": {
            category: dict(sorted(counter.items()))
            for category, counter in sorted(setting_category_counts.items())
        },
        "setting_alignment_expected_settings": sum(
            int((((row.get("setting_alignment") or {}).get("summary") or {}).get("expected_settings") or 0))
            for row in rows
        ),
        "setting_alignment_alignment_ready_settings": sum(
            int((((row.get("setting_alignment") or {}).get("summary") or {}).get("alignment_ready_settings") or 0))
            for row in rows
        ),
        "setting_alignment_evaluated_settings": sum(
            int((((row.get("setting_alignment") or {}).get("summary") or {}).get("evaluated_settings") or 0))
            for row in rows
        ),
        "setting_alignment_value_compared_settings": sum(
            int((((row.get("setting_alignment") or {}).get("summary") or {}).get("value_compared_settings") or 0))
            for row in rows
        ),
        "semantic_policy_member_specific": int(setting_classification_counts.get("MEMBER_SPECIFIC") or 0),
        "semantic_policy_provenance_unverified": int(setting_classification_counts.get("PROVENANCE_UNVERIFIED") or 0),
        "semantic_policy_identity_translation_required": int(setting_classification_counts.get("IDENTITY_TRANSLATION_REQUIRED") or 0),
        "semantic_policy_identity_path_normalized_settings": sum(
            int((((row.get("setting_alignment") or {}).get("summary") or {}).get("identity_path_normalized_settings") or 0))
            for row in rows
        ),
        "semantic_policy_identity_value_normalized_settings": sum(
            int((((row.get("setting_alignment") or {}).get("summary") or {}).get("identity_value_normalized_settings") or 0))
            for row in rows
        ),
        "semantic_policy_vsys_identity_map_entries": sum(
            int((((row.get("setting_alignment") or {}).get("summary") or {}).get("vsys_identity_map_entries") or 0))
            for row in rows
        ),
        "setting_alignment_devices_with_local_override": sum(
            1 for row in rows if int(((((row.get("setting_alignment") or {}).get("summary") or {}).get("classification_counts") or {}).get("LOCAL_OVERRIDE") or 0)) > 0
        ),
        "setting_alignment_devices_with_effective_drift": sum(
            1 for row in rows if int(((((row.get("setting_alignment") or {}).get("summary") or {}).get("classification_counts") or {}).get("EFFECTIVE_DRIFT") or 0)) > 0
        ),
        "setting_alignment_devices_out_of_sync": sum(
            1 for row in rows if int(((((row.get("setting_alignment") or {}).get("summary") or {}).get("classification_counts") or {}).get("PANORAMA_OUT_OF_SYNC") or 0)) > 0
        ),
        "setting_alignment_store_status": setting_alignment_store.get("status"),
        "artifact_metrics": {
            "active": _sum_artifact_metrics(rows, "active"),
            "merged": _sum_artifact_metrics(rows, "merged"),
            "effective": _sum_artifact_metrics(rows, "effective"),
        },
        "skipped_disconnected": len(disconnected),
    }

    semantic_summary = semantic_validation_result.get("summary") or {}
    summary.update({
        "semantic_validation_status": semantic_validation_result.get("status"),
        "semantic_validation_store_status": semantic_validation_store.get("status"),
        "semantic_validation_possible_schema_equivalents": int(semantic_summary.get("possible_schema_equivalent_candidates") or 0),
        "semantic_validation_expected_only_with_candidate": int(semantic_summary.get("expected_only_with_candidate") or 0),
        "semantic_validation_local_only_with_candidate": int(semantic_summary.get("local_only_with_candidate") or 0),
        "semantic_validation_unexplained_expected_only": int(semantic_summary.get("unexplained_expected_only") or 0),
        "semantic_validation_unexplained_local_only": int(semantic_summary.get("unexplained_local_only") or 0),
        "semantic_validation_manual_samples": int(semantic_summary.get("manual_samples_total") or 0),
        "semantic_validation_candidate_counts_by_category": semantic_summary.get("candidate_counts_by_category") or {},
        "semantic_validation_manual_sample_kind_counts": semantic_summary.get("manual_sample_kind_counts") or {},
        "semantic_validation_manual_sample_classification_counts": semantic_summary.get("manual_sample_classification_counts") or {},
        "semantic_validation_manual_confirmation_status": semantic_summary.get("manual_confirmation_status") or "not_ready",
    })

    control_success_rows = [row for row in rows if (row.get("panorama_control") or {}).get("status") == "success"]
    summary.update({
        "first": sum(1 for row in control_success_rows if (row.get("panorama_control") or {}).get("change_state") == "first"),
        "same": sum(1 for row in control_success_rows if (row.get("panorama_control") or {}).get("change_state") == "same"),
        "changed": sum(1 for row in control_success_rows if (row.get("panorama_control") or {}).get("change_state") == "changed"),
        "total_bytes": sum(int((row.get("panorama_control") or {}).get("size_bytes") or 0) for row in control_success_rows),
        "structural_pass": sum(1 for row in control_success_rows if ((row.get("panorama_control") or {}).get("structural_validation") or {}).get("status") == "pass"),
        "structural_warn": sum(1 for row in control_success_rows if ((row.get("panorama_control") or {}).get("structural_validation") or {}).get("status") == "warn"),
        "schema_pass": sum(1 for row in control_success_rows if ((row.get("panorama_control") or {}).get("structural_validation") or {}).get("schema_status") == "pass"),
        "schema_warn": sum(1 for row in control_success_rows if ((row.get("panorama_control") or {}).get("structural_validation") or {}).get("schema_status") == "warn"),
        "evidence_unknown": sum(1 for row in control_success_rows if ((row.get("panorama_control") or {}).get("structural_validation") or {}).get("evidence_status") == "unknown"),
        "observed_vsys_entries": sum(int((((row.get("panorama_control") or {}).get("structural_validation") or {}).get("counts") or {}).get("vsys_entries") or 0) for row in control_success_rows),
        "observed_virtual_router_entries": sum(int((((row.get("panorama_control") or {}).get("structural_validation") or {}).get("counts") or {}).get("virtual_router_entries") or 0) for row in control_success_rows),
        "observed_zone_entries": sum(int((((row.get("panorama_control") or {}).get("structural_validation") or {}).get("counts") or {}).get("zone_entries") or 0) for row in control_success_rows),
        "observed_interface_definitions": sum(int((((row.get("panorama_control") or {}).get("structural_validation") or {}).get("counts") or {}).get("interface_definitions_total") or 0) for row in control_success_rows),
    })

    summary.update(storage_stats)

    failures = _failure_records(rows, panorama_intent_result, expected_compiler_result)
    if setting_alignment_store.get("status") == "failed":
        failures.append({
            "device": "LOCAL_SETTING_ALIGNMENT_STORE",
            "serial": None,
            "management_ip": None,
            "method": "LOCAL_SETTING_ALIGNMENT_MANIFEST_STORE",
            "transport": "LOCAL_IMMUTABLE_DERIVED_STORE",
            "failure_domain": "local_store",
            "failure_stage": "setting_alignment_local_store",
            "error_type": setting_alignment_store.get("error_type") or "OSError",
            "error_hint": setting_alignment_store.get("error_hint") or "local_filesystem_permission_or_lock",
            "required_for_primary": False,
            "required_for_alignment": True,
        })
    if semantic_validation_result.get("status") == "failed" or semantic_validation_store.get("status") == "failed":
        failures.append({
            "device": "LOCAL_SEMANTIC_VALIDATION",
            "serial": None,
            "management_ip": None,
            "method": "LOCAL_SEMANTIC_VALIDATION_ENGINE",
            "transport": "LOCAL_DERIVED_ANALYSIS",
            "failure_domain": "local_semantic_validation",
            "failure_stage": "semantic_validation_or_local_store",
            "error_type": semantic_validation_result.get("error_type") or semantic_validation_store.get("error_type") or "SemanticValidationError",
            "error_hint": semantic_validation_store.get("error_hint") or "semantic_validation_engine_or_local_store_failed",
            "required_for_primary": False,
            "required_for_alignment": True,
        })
    summary["method_failures_total"] = len(failures)
    summary["method_failures_primary"] = sum(1 for item in failures if item.get("required_for_primary"))
    summary["method_failures_alignment"] = sum(1 for item in failures if item.get("required_for_alignment"))
    summary["method_failures_optional"] = sum(1 for item in failures if not item.get("required_for_primary") and not item.get("required_for_alignment"))
    # A4.1 keeps the direct effective-running primary evidence gate and adds a
    # compiler gate: every selected firewall should map to exactly one Template
    # Stack and the compiler must not have fatal reference/assignment anomalies.
    compiler_status_ok = expected_compiler_result.get("status") in {"success", "partial"}
    compiler_mapping_ok = summary["expected_compiler_selected_mapped"] == len(selected)
    compiler_fatal_ok = (
        summary["expected_compiler_multiple_stack_serials"] == 0
        and summary["expected_compiler_missing_template_references"] == 0
    )
    summary["expected_compiler_gate"] = bool(selected) and compiler_status_ok and compiler_mapping_ok and compiler_fatal_ok
    summary["expected_policy_lineage_gate"] = (
        bool(selected)
        and summary["expected_compiler_selected_policy_mapped_devices"] == len(selected)
        and summary["expected_compiler_selected_policy_lineage_complete_devices"] == len(selected)
    )
    # Preserve the A3/A4 primary-evidence stage_pass contract for regression
    # compatibility. A4.1 adds its own stricter compiler gate/result.
    summary["stage_pass"] = (
        bool(selected)
        and summary["primary_evidence_success"] == len(selected)
        and summary["direct_identity_mismatch"] == 0
    )
    summary["a4_1_stage_pass"] = bool(summary["stage_pass"] and summary["expected_compiler_gate"] and summary["expected_policy_lineage_gate"])
    # A4.2 findings are observations, not collection failures. The engine gate
    # checks only that every selected device was actually evaluated and that the
    # derived local manifest was durably published. LOCAL_OVERRIDE or DRIFT do
    # not make the phase gate fail.
    summary["setting_alignment_engine_gate"] = bool(selected) and all(
        ((row.get("setting_alignment") or {}).get("status") == "success")
        for row in rows
    ) and setting_alignment_store.get("status") == "success"
    summary["a4_2_stage_pass"] = bool(
        summary["a4_1_stage_pass"] and summary["setting_alignment_engine_gate"]
    )
    # A4.2.2 is a semantic-policy hardening gate. It verifies that every
    # selected device was evaluated by the A4.2.2 alignment schema. Findings
    # such as MEMBER_SPECIFIC or PROVENANCE_UNVERIFIED are safe classifications
    # and therefore do not fail the phase.
    summary["semantic_policy_engine_gate"] = bool(selected) and all(
        ((row.get("setting_alignment") or {}).get("schema_version") == "0.6.0A4.2.2")
        for row in rows
    )
    summary["a4_2_2_stage_pass"] = bool(
        summary["a4_2_stage_pass"] and summary["semantic_policy_engine_gate"]
    )

    summary["semantic_validation_engine_gate"] = bool(
        semantic_validation_result.get("status") == "success"
        and semantic_validation_store.get("status") == "success"
    )
    summary["a4_2_1_engine_pass"] = bool(
        summary["a4_2_stage_pass"] and summary["semantic_validation_engine_gate"]
    )
    # A4.2.1 is intentionally a human-confirmed semantic validation phase. The
    # automated engine can be ready/pass while the semantic verdict remains
    # pending until sampled settings are checked against Panorama/firewall UI.
    summary["a4_2_1_manual_validation_required"] = True
    summary["a4_2_1_stage_pass"] = None

    payload = {
        "schema_version": "0.6",
        "collector_version": COLLECTOR_VERSION,
        # Backward-compatible top-level aliases remain Panorama-active; the
        # promoted primary evidence is explicit in architecture/summary.
        "artifact_type": PANORAMA_ARTIFACT_TYPE,
        "method": PANORAMA_METHOD,
        "generated_at": _utc_now(),
        "architecture": {
            "discovery_plane": "Panorama",
            "intent_plane": "Panorama active management configuration",
            "expected_configuration_compiler": "Template Stack scalar precedence + Device Group policy lineage",
            "expected_compiler_limits": ["template variables unresolved", "non-scalar XML list merge omitted", "device-group object value precedence deferred"],
            "setting_alignment_engine": "compiled Template-Stack scalar intent vs direct firewall effective-running",
            "setting_alignment_classifications": ["ALIGNED", "LOCAL_OVERRIDE", "EFFECTIVE_DRIFT", "PANORAMA_OUT_OF_SYNC", "EXPECTED_ONLY", "LOCAL_ONLY", "MEMBER_SPECIFIC", "PROVENANCE_UNVERIFIED", "IDENTITY_TRANSLATION_REQUIRED", "UNKNOWN"],
            "setting_alignment_limits": ["template variables remain UNKNOWN", "member/list collections not value-compared", "Device Group policy values not yet setting-aligned", "expected-only is not automatically drift", "identity mismatch is never an override until VSYS ID/display-name resolution succeeds"],
            "semantic_policy_hardening": ["HA peer addressing is member-relative", "device telemetry mismatches are provenance-guarded", "VSYS display-name/internal-ID identity is normalized before comparison"],
            "semantic_validation": "deterministic manual samples + conservative EXPECTED_ONLY/LOCAL_ONLY schema-equivalence hypotheses",
            "semantic_validation_policy": "A4.2 classifications are never auto-promoted by schema-twin heuristics; human confirmation is required",
            "semantic_validation_sensitive_values": "selected values exist only in local operator report; secret-like paths are redacted",
            "primary_configuration_evidence": "direct_firewall_effective_running",
            "alignment_evidence": ["Panorama compiled expected manifest", "Panorama assignment/sync", "direct active", "direct merged", "direct effective"],
            "override_claim_policy": "LOCAL_OVERRIDE requires directly-comparable trusted expected provenance plus effective!=expected and local-active=effective after semantic identity normalization",
            "drift_claim_policy": "EFFECTIVE_DRIFT requires expected/effective mismatch, no local-active explanation, merged=effective, and known Panorama sync state",
            "ssh_fallback": "not_automatic_in_a4_2; reserved for diagnostics/unsupported API cases",
            "native_backup_future_direction": "direct PAN-OS export category=device-state before SSH fallback",
            "configuration_storage": "vendor-neutral content-addressed immutable objects + per-run metadata history",
            "storage_same_policy": "SAME writes a history reference only; duplicate payload bytes are not stored",
            "storage_changed_policy": "CHANGED stores a new immutable object and preserves previous versions for diff/history",
            "checkpoint_storage_readiness": "storage API accepts future Gaia/Clish text evidence; CP collection method is intentionally not changed in A4.3.2",
        },
        "transport": {
            # Backward-compatible Panorama control contract.
            "api": "PAN-OS XML API via Panorama target=<serial>",
            "request_type": "config",
            "action": "show",
            "xpath": "/config",
            "http_method": "POST",
            "api_key_transport": "X-PAN-KEY header",
            "tls_verify": panorama_verify is not False,
            "ca_bundle_configured": isinstance(panorama_verify, str),
            "timeout_seconds": timeout,
            "panorama_discovery": {
                "api": "PAN-OS XML API show devices all",
                "transport": "HTTPS",
            },
            "panorama_intent": {
                "api": "PAN-OS XML API action=show xpath=/config without target",
                "transport": "HTTPS",
                "purpose": "template-stack/device-group assignment provenance",
            },
            "panorama_target_control": {
                "api": "PAN-OS XML API action=show xpath=/config target=<serial>",
                "transport": "HTTPS",
            },
            "panorama_target_ha_runtime": {
                "api": "PAN-OS XML API type=op show high-availability state target=<serial>",
                "transport": "HTTPS",
                "purpose": "actual local HA runtime role when show devices all does not expose ha-state",
                "read_only": True,
            },
            "direct_firewall": {
                "discovered_address_source": "Panorama show devices all ip-address",
                "api": "PAN-OS XML API direct to firewall management IP",
                "authentication": "per-firewall keygen using runtime credentials; API key held in memory only",
                "identity_gate": "direct show system info serial must equal Panorama-discovered serial",
                "queries": ["active_config", "effective-running", "merged"] + (["pushed-template"] if probe_pushed_template else []),
                "pushed_template_probe": "enabled" if probe_pushed_template else "disabled_by_default",
                "transport": "HTTPS",
                "api_key_transport": "X-PAN-KEY header",
                "tls_verify": direct_verify is not False,
                "ca_bundle_configured": isinstance(direct_verify, str),
                "timeout_seconds": direct_timeout,
                "workers": actual_workers,
                "worker_cap": 6,
            },
            "ssh": {
                "attempted": False,
                "role": "fallback_not_enabled_in_a4",
            },
            "remote_artifact_created": False,
            "remote_configuration_changed": False,
            "local_artifact_created": True,
            "read_only": True,
        },
        "panorama_intent": panorama_intent_result,
        "expected_compiler": expected_compiler_result,
        "setting_alignment_store": setting_alignment_store,
        "semantic_validation": _safe_semantic_validation(semantic_validation_result),
        "semantic_validation_store": semantic_validation_store,
        "summary": summary,
        "failures": failures,
        "devices": rows,
    }
    telemetry_path = _write_local_telemetry(payload)
    failures_path = _write_local_failures(failures, run_id)
    support_path = _write_shareable_support(payload, run_id)

    info(
        f">>> PAN A4.2.2 DONE selected={summary['selected']} primary={summary['primary_evidence_success']} "
        f"expected_mapped={summary['expected_compiler_selected_mapped']} expected_gate={summary['expected_compiler_gate']} "
        f"alignment_complete={summary['alignment_evidence_complete']} effective={summary['direct_effective_success']} "
        f"merged={summary['direct_merged_success']} active={summary['direct_active_success']} "
        f"ha_roles={summary['ha_runtime_role_available']} ha_queries={summary['ha_runtime_target_queries']} "
        f"ha_query_failures={summary['ha_runtime_target_failed']} "
        f"failures={summary['method_failures_total']} primary_gate={summary['stage_pass']} a4_1_gate={summary['a4_1_stage_pass']} "
        f"a4_2_gate={summary['a4_2_stage_pass']} a4_2_2_gate={summary['a4_2_2_stage_pass']} semantic_engine={summary['semantic_validation_engine_gate']} "
        f"semantic_candidates={summary['semantic_validation_possible_schema_equivalents']} "
        f"semantic_samples={summary['semantic_validation_manual_samples']} manual={summary['semantic_validation_manual_confirmation_status']}"
    )
    if failures:
        warn(f">>> PAN A4.2.2 LOCAL FAILURE MATRIX -> {failures_path}")
        for item in failures:
            warn(
                f">>> PAN A4.2.2 METHOD STATUS device={item.get('device')} method={item.get('method')} "
                f"transport={item.get('transport')} stage={item.get('failure_stage')} "
                f"error={item.get('error_type')} hint={item.get('error_hint')} "
                f"primary={item.get('required_for_primary')} alignment={item.get('required_for_alignment')}"
            )
    info(f">>> LOCAL TELEMETRY -> {telemetry_path}")
    if setting_alignment_store.get("report_path"):
        info(f">>> LOCAL SETTING ALIGNMENT REPORT -> {setting_alignment_store.get('report_path')}")
    if semantic_validation_store.get("report_path"):
        info(f">>> LOCAL SEMANTIC VALIDATION REPORT -> {semantic_validation_store.get('report_path')}")
    if semantic_validation_store.get("samples_csv_path"):
        info(f">>> LOCAL SEMANTIC VALIDATION SAMPLES -> {semantic_validation_store.get('samples_csv_path')}")
    info(f">>> SHAREABLE CONFIG SUPPORT -> {support_path}")
    return {
        **payload,
        "telemetry_path": str(telemetry_path),
        "failures_path": str(failures_path),
        "support_path": str(support_path),
        "expected_compiler_manifest_path": expected_compiler_result.get("manifest_path"),
        "expected_compiler_report_path": expected_compiler_result.get("report_path"),
        "setting_alignment_manifest_path": setting_alignment_store.get("manifest_path"),
        "setting_alignment_report_path": setting_alignment_store.get("report_path"),
        "semantic_validation_manifest_path": semantic_validation_store.get("manifest_path"),
        "semantic_validation_report_path": semantic_validation_store.get("report_path"),
        "semantic_validation_samples_csv_path": semantic_validation_store.get("samples_csv_path"),
    }

