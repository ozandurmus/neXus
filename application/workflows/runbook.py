"""PCP.8 — ``--runbook-execute`` (docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md §15).

Opt-in read-only diagnostic runbook execution, modelled directly on
``application.workflows.compliance.compliance_probe``: resolves one named
``configuration.runbook_catalog.RUNBOOK_CATALOG`` entry, runs its ordered
``configuration.command_primitives.PRIMITIVE_REGISTRY`` steps against one
already-discovered device of the runbook's own vendor, through the same
admission coordinator every other collection path uses, writes a new
local-only, redacted evidence artifact, and prints a safe summary. Reuses the
compliance workflow's own target-selection helpers verbatim (diagnostic-path
law: no second physical-endpoint selection policy for the same evidence
question). Never wired into a normal collection run.
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from application.services import _require_bootstrap, make_admitted, make_runtime_config
from application.workflows.compliance import _env_bool, _env_int, _pick_cp_target, _pick_pan_target


def runbook_execute(ctx) -> dict[str, Any]:
    runtime_paths = ctx.runtime_paths
    output_root = Path(runtime_paths.output_root)
    _admitted = make_admitted(ctx)
    _runtime_config = make_runtime_config(ctx)

    from configuration.runbook_catalog import RUNBOOK_CATALOG

    runbook = RUNBOOK_CATALOG[ctx.args.runbook_id]

    print(f"=== SECURITYEXPERT PCP.8 DIAGNOSTIC RUNBOOK: {runbook.runbook_id} ===\n")
    _require_bootstrap("runbook-execute", output_root)

    # Re-validated against the live registry at execution time, not just at
    # catalog-load time (validator law, AC-3) -- never trust an earlier check.
    steps = runbook.resolve_steps()

    cfg = _runtime_config(
        require_cp=(runbook.vendor == "check_point"),
        require_panorama=(runbook.vendor == "palo_alto"),
    )

    from configuration.command_primitives import PrimitiveExecutionTracker

    tracker = PrimitiveExecutionTracker()
    results: list[Any] = []
    selection_gaps: list[str] = []

    if runbook.vendor == "check_point":
        cp_target, gaps = _pick_cp_target(output_root)
        selection_gaps.extend(gaps)
        if cp_target is not None:
            from configuration.command_primitives import run_check_point_primitives

            username = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_USERNAME") or cfg.auth.principal
            secret = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD") or cfg.auth.secret
            strict_host_key = _env_bool("SECURITYEXPERT_CP_CONFIG_SSH_STRICT_HOST_KEY", False)
            connect_timeout = _env_int("SECURITYEXPERT_CP_CONFIG_SSH_CONNECT_TIMEOUT_SECONDS", 8, 2, 60)

            results = _admitted(
                "checkpoint",
                f"runbook-execute:{runbook.runbook_id}",
                cfg.mds_ip,
                lambda: run_check_point_primitives(
                    cp_target,
                    username=username,
                    secret=secret,
                    strict_host_key=strict_host_key,
                    connect_timeout=connect_timeout,
                    tracker=tracker,
                    primitives=steps,
                ),
            )
        else:
            selection_gaps.append("check_point_target_unavailable")
    else:  # palo_alto
        pan_serial = _pick_pan_target(output_root)
        if pan_serial:
            from panorama.panorama_runtime_runner import _tls_verify_setting
            from configuration.command_primitives import run_palo_alto_primitives

            verify = _tls_verify_setting()
            results = _admitted(
                "paloalto",
                f"runbook-execute:{runbook.runbook_id}",
                cfg.panorama_ip,
                lambda: run_palo_alto_primitives(
                    cfg=cfg,
                    target_serial=pan_serial,
                    verify=verify,
                    tracker=tracker,
                    primitives=steps,
                ),
            )
        else:
            selection_gaps.append("palo_alto_target_unavailable")

    try:
        payload = {
            "phase": "PCP.8",
            "title": "Diagnostic Runbooks — read-only catalog execution",
            "runbook_id": runbook.runbook_id,
            "runbook_title": runbook.title,
            "vendor": runbook.vendor,
            "step_order": list(runbook.step_ids),
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "mode": "opt_in_runbook",
            "read_only": True,
            "raw_command_output_persisted": False,
            "selection_gaps": selection_gaps,
            "results": [r.to_safe_dict() for r in results],
            "sensitivity": "LOCAL_OPERATOR_SENSITIVE_NOT_SHAREABLE",
        }
        output_root.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
        report_path = output_root / f"runbook_execution_{runbook.runbook_id}_{stamp}.json"
        tmp = report_path.with_suffix(report_path.suffix + ".tmp")
        tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(report_path)
        payload["report_path"] = os.fspath(report_path)

        print("\n=== PCP.8 SAFE RUNBOOK SUMMARY ===")
        print(f"Runbook:                  {runbook.runbook_id} ({runbook.vendor})")
        print("Mode:                     opt-in / read-only")
        print(f"Steps executed:           {len(results)}")
        print(f"Successful:               {sum(1 for r in results if r.success)}")
        if selection_gaps:
            print(f"Selection gaps:           {', '.join(selection_gaps)}")
        print("Raw command output persisted: False")
        print(f"Local-only report:        {report_path}")
        print("Do not share the local-only report; it is not the shareable support bundle.")
        return payload
    finally:
        cfg.clear_credentials()
