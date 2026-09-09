"""Failover-plane modes (OP.0a, OP.1).

Contracts: docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md,
docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md.

``--ha-readiness-check`` and ``--failover-plan-dry-run``. Both are **offline
maintenance-class modes**: they open no network connection, hold no
credential and issue no device command -- each derives its report from
evidence a previous collection run already stored. Same posture as
``--restore-readiness-check``.

Nothing vendor-bound is imported at module scope (the AC-3 lazy-import
boundary the ``application`` package establishes).
"""
from __future__ import annotations

import json
from pathlib import Path

from application.services import _require_bootstrap


def _load_cp_ha_runtime(output_root) -> dict[str, dict]:
    """Read `cp_config_telemetry.json` and extract `ha_role` /
    `ha_cluster_mode` per entity via the pure, shared extractor (OP.0c:
    `utils.failover_readiness_ui` -- the console's live projection calls the
    same function over the same file's already-parsed contents, so the CLI
    snapshot and the console can never disagree about what the file means).

    Missing, corrupt or malformed -> `{}` ("no HA runtime evidence"), never an
    error. Every CP unit then reports INSUFFICIENT_EVIDENCE, which is the
    correct answer rather than a degraded mode (contract correctness rule 6).
    """
    from utils.failover_readiness_ui import extract_cp_ha_runtime

    path = Path(output_root) / "cp_config_telemetry.json"
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        doc = None
    return extract_cp_ha_runtime(doc)


def _load_pan_ha_runtime(output_root) -> tuple[dict[str, dict], dict[str, str]]:
    """Read `pan_config_telemetry.json` and extract PAN HA runtime state plus
    the configured peer address per entity via the shared extractor (see
    `_load_cp_ha_runtime`).

    Returns `(runtime, peers)`. Same fail-safe posture as the CP loader: a
    missing or corrupt file degrades to empty maps, never to an error. `peers`
    feeds the contract-P7 pair assembly, which is fail-closed on its own.
    """
    from utils.failover_readiness_ui import extract_pan_ha_runtime

    path = Path(output_root) / "pan_config_telemetry.json"
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        doc = None
    return extract_pan_ha_runtime(doc)


def ha_readiness_check(ctx):
    runtime_paths = ctx.runtime_paths
    _require_bootstrap("ha-readiness-check", runtime_paths.output_root)
    from utils.failover import compute_ha_readiness

    print("=== SECURITYEXPERT HA READINESS — OP.0a ===\n")
    unified_path = runtime_paths.output_root / "unified.json"
    unified_devices = json.loads(unified_path.read_text(encoding="utf-8"))

    cp_ha_runtime = _load_cp_ha_runtime(runtime_paths.output_root)
    pan_ha_runtime, pan_ha_peers = _load_pan_ha_runtime(runtime_paths.output_root)

    report = compute_ha_readiness(
        unified_devices,
        cp_ha_runtime=cp_ha_runtime,
        pan_ha_runtime=pan_ha_runtime,
        pan_ha_peers=pan_ha_peers,
    )

    state_dir = runtime_paths.data_root / "state"
    state_dir.mkdir(parents=True, exist_ok=True)
    state_path = state_dir / "ha_readiness.json"
    state_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    summary = report["summary"]
    total = sum(summary.values())
    print(f"HA units assessed:     {total}")
    for verdict in (
        "SAFE_TO_FAILOVER", "DEGRADED_PROCEED_WITH_RISK", "UNSAFE_DO_NOT_FAILOVER",
        "INSUFFICIENT_EVIDENCE", "NOT_A_FAILOVER_UNIT",
    ):
        print(f"  {verdict:<28} {summary.get(verdict, 0)}")

    # The framing this build must always carry with it (contract P4 / risks).
    # Without it, an all-INSUFFICIENT result reads as a broken feature rather
    # than as the honest state of the evidence.
    print(
        "\nNote: OP.0a assesses only the stop-conditions answerable from evidence "
        "already collected. It CANNOT report a cluster safe to fail over -- "
        "SAFE_TO_FAILOVER is unreachable by design until the OP.0b preflight "
        "battery is gated and built. INSUFFICIENT_EVIDENCE here means "
        "'not asked yet', not 'unhealthy'."
    )
    print(f"\nWrote {state_path}")
    return 0


def failover_plan_dry_run(ctx):
    """OP.1: compile a `FailoverPlan` + `DryRunReport` for every HA unit this
    run's readiness assessment derives (or exactly one, with
    `--failover-plan-unit`) -- no network access, no credential, no device
    command, no `ClusterXLMemberSession` is ever resolved (`compile_
    failover_plan`'s own AC-1 poison-resolver proof)."""
    runtime_paths = ctx.runtime_paths
    _require_bootstrap("failover-plan-dry-run", runtime_paths.output_root)
    from utils.failover import compute_ha_readiness
    from utils.failover_plan import compile_failover_plan, evaluate_dry_run

    print("=== SECURITYEXPERT FAILOVER PLAN DRY-RUN — OP.1 ===\n")
    unified_path = runtime_paths.output_root / "unified.json"
    unified_devices = json.loads(unified_path.read_text(encoding="utf-8"))

    cp_ha_runtime = _load_cp_ha_runtime(runtime_paths.output_root)
    pan_ha_runtime, pan_ha_peers = _load_pan_ha_runtime(runtime_paths.output_root)

    readiness_record = compute_ha_readiness(
        unified_devices,
        cp_ha_runtime=cp_ha_runtime,
        pan_ha_runtime=pan_ha_runtime,
        pan_ha_peers=pan_ha_peers,
    )

    all_unit_ids = [unit["unit_id"] for unit in readiness_record["units"]]
    requested_unit_id = getattr(ctx.args, "failover_plan_unit", None)
    if requested_unit_id is not None:
        if requested_unit_id not in all_unit_ids:
            print(f"Unknown --failover-plan-unit {requested_unit_id!r}: no such unit in this run's HA readiness assessment.")
            return 2
        unit_ids = [requested_unit_id]
    else:
        unit_ids = all_unit_ids

    reports = []
    for unit_id in unit_ids:
        plan = compile_failover_plan(unit_id, readiness_record=readiness_record, cp_ha_runtime=cp_ha_runtime)
        reports.append(evaluate_dry_run(plan).to_dict())

    document = {
        "schema": "securityexpert-failover-plan-dry-run-v1",
        "generated_at": readiness_record["generated_at"],
        "reports": reports,
    }

    state_dir = runtime_paths.data_root / "state" / "failover_plan"
    state_dir.mkdir(parents=True, exist_ok=True)
    state_path = state_dir / "dry_run.json"
    state_path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"Units assessed:         {len(reports)}")
    for report in reports:
        plan = report["plan"]
        print(
            f"  {plan['unit_id']:<40} compilable={plan['plan_compilable']!s:<5} "
            f"would_proceed={report['would_proceed']!s:<5} verdict={report['readiness_verdict']}"
        )

    print(
        "\nNote: this dry-run grants no authorization. It issues no command and "
        "constructs no ActionCoordinator. Executing a compiled plan requires a "
        "separate, independently authorized OP.2 action once that track's own "
        "prerequisites are met."
    )
    print(f"\nWrote {state_path}")
    return 0
