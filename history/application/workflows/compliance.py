"""CE.2 — ``--compliance-probe`` (docs/design/COMPLIANCE_CHECK_ENGINE.md §5, D8).

Opt-in read-only probe, modelled directly on ``checkpoint.cp_config_probe``:
runs the curated ``configuration.command_primitives.PRIMITIVE_REGISTRY``
against one already-discovered device per vendor, through the same admission
coordinator every other collection path uses (1-per-vendor budget), writes a
new local-only, redacted evidence artifact, and prints a safe summary. Never
wired into a normal collection run — that promotion needs its own
real-environment validation gate (design section 5).
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from utils.logger import info

from application.services import (
    _require_bootstrap,
    make_admitted,
    make_runtime_config,
)


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off", "disabled"}


def _env_int(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(maximum, value))


def _load_json(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return default


def _pick_cp_target(output_root: Path):
    """Reuse the 0.6.1A probe's own target selection (diagnostic-path law:
    no second physical-endpoint selection policy for the same evidence
    question). Returns the first physical (non-VSX-context) target, or None."""
    from configuration import checkpoint_config_probe as cp_probe_mod

    cp_probe_mod.OUTPUT_DIR = output_root
    targets, gaps = cp_probe_mod._pick_targets()
    physical = [t for t in targets if t.role != "vsx_virtual_system"]
    return (physical[0] if physical else None), gaps


def _pick_pan_target(output_root: Path) -> str | None:
    """First already-discovered Panorama-managed serial, or None (PAN is
    best-effort — CE.2 needs 'CP and/or PAN' wired, not both mandatory)."""
    rows = _load_json(output_root / "panorama_runtime.json", []) or []
    for row in rows:
        serial = str((row or {}).get("serial") or "").strip()
        if serial:
            return serial
    return None


def compliance_probe(ctx) -> dict[str, Any]:
    runtime_paths = ctx.runtime_paths
    output_root = Path(runtime_paths.output_root)
    _admitted = make_admitted(ctx)
    _runtime_config = make_runtime_config(ctx)

    print("=== SECURITYEXPERT CE.2 COMPLIANCE COMMAND-PRIMITIVE PROBE ===\n")
    _require_bootstrap("compliance-probe", output_root)
    cfg = _runtime_config(require_cp=True, require_panorama=False)

    from configuration.command_primitives import PrimitiveExecutionTracker

    tracker = PrimitiveExecutionTracker()
    all_results: list[dict[str, Any]] = []
    selection_gaps: list[str] = []

    # --- Check Point ---------------------------------------------------
    cp_target, gaps = _pick_cp_target(output_root)
    selection_gaps.extend(gaps)
    if cp_target is not None:
        from configuration.command_primitives import run_check_point_primitives

        username = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_USERNAME") or cfg.auth.principal
        secret = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD") or cfg.auth.secret
        strict_host_key = _env_bool("SECURITYEXPERT_CP_CONFIG_SSH_STRICT_HOST_KEY", False)
        connect_timeout = _env_int("SECURITYEXPERT_CP_CONFIG_SSH_CONNECT_TIMEOUT_SECONDS", 8, 2, 60)

        cp_results = _admitted(
            "checkpoint",
            "compliance-probe",
            cfg.mds_ip,
            lambda: run_check_point_primitives(
                cp_target,
                username=username,
                secret=secret,
                strict_host_key=strict_host_key,
                connect_timeout=connect_timeout,
                tracker=tracker,
            ),
        )
        all_results.extend(cp_results)
    else:
        selection_gaps.append("check_point_target_unavailable")

    # --- Palo Alto (best-effort: CE.2 requires "CP and/or PAN") --------
    if cfg.panorama_ip:
        pan_serial = _pick_pan_target(output_root)
        if pan_serial:
            from panorama.panorama_runtime_runner import _tls_verify_setting
            from configuration.command_primitives import run_palo_alto_primitives

            verify = _tls_verify_setting()
            pan_results = _admitted(
                "paloalto",
                "compliance-probe",
                cfg.panorama_ip,
                lambda: run_palo_alto_primitives(
                    cfg=cfg,
                    target_serial=pan_serial,
                    verify=verify,
                    tracker=tracker,
                ),
            )
            all_results.extend(pan_results)
        else:
            selection_gaps.append("palo_alto_target_unavailable")
    else:
        info(">>> COMPLIANCE PROBE: no Panorama endpoint configured, Check Point only this run")

    try:
        payload = {
            "phase": "CE.2",
            "title": "Compliance Check Engine — command-primitive probe",
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "mode": "opt_in_probe",
            "read_only": True,
            "raw_command_output_persisted": False,
            "selection_gaps": selection_gaps,
            "results": [r.to_safe_dict() for r in all_results],
            "sensitivity": "LOCAL_OPERATOR_SENSITIVE_NOT_SHAREABLE",
        }
        output_root.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
        report_path = output_root / f"compliance_probe_{stamp}.json"
        tmp = report_path.with_suffix(report_path.suffix + ".tmp")
        tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(report_path)
        payload["report_path"] = os.fspath(report_path)

        print("\n=== CE.2 SAFE PROBE SUMMARY ===")
        print("Mode:                     opt-in / read-only")
        print(f"Primitives executed:      {len(all_results)}")
        print(f"Successful:               {sum(1 for r in all_results if r.success)}")
        if selection_gaps:
            print(f"Selection gaps:           {', '.join(selection_gaps)}")
        print("Raw command output persisted: False")
        print(f"Local-only report:        {report_path}")
        print("Do not share the local-only report; it is not the shareable support bundle.")
        return payload
    finally:
        cfg.clear_credentials()
