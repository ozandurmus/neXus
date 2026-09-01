"""Full staged integration checkpoint, ``--only`` partials, and the Check Point
configuration modes.

§5 "full-stage orchestration and degraded-status policy". Holds audit Phase E in
full — the staged pipeline, the ``RunContext`` lifecycle, the degraded-vs-success
status policy and the enclosing ``try / except / finally`` whose ``finally`` runs
``cfg.clear_credentials()`` — plus the ``--only`` partial-render paths,
``--cp-config-probe`` / ``--cp-config-collect`` (same vendor, shared lazy
``configuration.checkpoint_config_collector`` import), ``_env_int`` and
``_cp_stage_cooldown``.

The heavy collector import cluster stays lazy **inside** ``integration_checkpoint``
exactly as it sat at ``main.py:1520-1530``; nothing vendor-bound is imported at
module scope (AC-3).
"""
from __future__ import annotations

import os
import time

from utils.logger import info

from application.services import (
    _load_output_json,
    _remove_output_files,
    _require_bootstrap,
    _workflow_context,
    make_admitted,
    make_runtime_config,
)


def _env_int(name, default, minimum, maximum):
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(maximum, value))


def _cp_stage_cooldown(next_stage):
    """Optional safety gap between CP-touching stages in full runs.

    0.6.1B.1.3 safety audit identified back-to-back CP, VSX and CP-config
    access as a pressure risk on some estates. Default stays 0 to preserve
    validated behavior; operators can enforce a bounded cooldown when needed.
    """
    seconds = _env_int("SECURITYEXPERT_CP_STAGE_COOLDOWN_SECONDS", 0, 0, 30)
    if seconds <= 0:
        info(
            ">>> CP SAFETY GUARDRAIL: no stage cooldown configured before "
            f"{next_stage} (set SECURITYEXPERT_CP_STAGE_COOLDOWN_SECONDS=1..30 if needed)"
        )
        return
    info(
        f">>> CP SAFETY GUARDRAIL: waiting {seconds}s before {next_stage} "
        "to reduce back-to-back device interaction pressure"
    )
    time.sleep(seconds)


def cp_config_probe(ctx):
    runtime_paths = ctx.runtime_paths
    _admitted = make_admitted(ctx)
    _runtime_config = make_runtime_config(ctx)

    print("=== SECURITYEXPERT CHECK POINT CONFIGURATION IDENTITY + VSX PROBE — PHASE 0.6.1A.1 ===\n")
    _require_bootstrap("cp-config-probe", runtime_paths.output_root)
    cfg = _runtime_config(require_cp=True, require_panorama=False)
    try:
        from configuration.checkpoint_config_probe import run_checkpoint_config_probe
        result = _admitted(
            "checkpoint",
            "cp-config-probe",
            cfg.mds_ip,
            lambda: run_checkpoint_config_probe(cfg),
        )
        summary = result.get("summary") or {}
        print("\n=== 0.6.1A.1 SAFE PROBE SUMMARY ===")
        print("Mode:                     observe-only / read-only")
        print("Login shell contract:     Expert -> explicit Gaia Clish")
        print(f"Selected targets:         {summary.get('selected_targets')}")
        print(f"Successful probes:        {summary.get('successful_count')}")
        print(f"SSH reachable:            {summary.get('ssh_reachable_count')}")
        print(f"Authenticated:            {summary.get('authenticated_count')}")
        print(f"Identity gate accepted:    {summary.get('identity_gate_accepted_count')}")
        print(f"Hostname differences:     {summary.get('identity_hostname_difference_count')}")
        print(f"Identity high confidence: {summary.get('identity_high_confidence_count')}")
        print(f"VSX context success:      {summary.get('vsx_context_probe_success')}")
        print(f"VSX context distinct:     {summary.get('vsx_context_distinct_from_host')}")
        print(f"Secret-bearing lines:     {summary.get('secret_bearing_lines_detected_in_memory')} (count only; raw not saved)")
        print(f"Raw config persisted:     {summary.get('raw_configuration_persisted')}")
        print(f"Host-key policy:          {summary.get('host_key_policy')}")
        print(f"Probe gate:               {summary.get('probe_gate')}")
        gaps = summary.get("selection_gaps") or []
        if gaps:
            print(f"Selection gaps:           {', '.join(gaps)}")
        print(f"Local-only report:        {result.get('report_path')}")
        print("Do not share the local-only report; console summary is designed to be shareable.")
    finally:
        cfg.clear_credentials()
    return None


def cp_config_collect(ctx):
    runtime_paths = ctx.runtime_paths
    services = ctx.services
    args = ctx.args
    _admitted = make_admitted(ctx)
    _runtime_config = make_runtime_config(ctx)

    print("=== SECURITYEXPERT CHECK POINT CONFIGURATION COLLECTION — PHASE 0.6.1B.1.2 ===\n")
    _require_bootstrap("cp-config-collect", runtime_paths.output_root)
    cfg = _runtime_config(require_cp=True, require_panorama=False)
    try:
        from configuration.checkpoint_config_collector import run_checkpoint_config_collection
        from utils.html_export import run_html_export
        result = _admitted(
            "checkpoint",
            "cp-config",
            cfg.mds_ip,
            lambda: run_checkpoint_config_collection(
                cfg,
                stage=args.cp_config_stage,
                max_workers=args.cp_config_workers,
            ),
        )
        summary = result.get("summary") or {}
        # Legacy contract marker retained for source-level B.1 regression checks:
        # 0.6.1B.1 SAFE COLLECTION SUMMARY
        print("\n=== 0.6.1B.1.2 SAFE COLLECTION SUMMARY ===")
        print(f"Stage:                    {summary.get('stage')}")
        print(f"Physical hosts:           {summary.get('physical_hosts')}")
        print(f"Configuration entities:  {summary.get('selected')} observed / {summary.get('planned_entities', summary.get('selected'))} planned")
        print(f"Successful:               {summary.get('success')}")
        print(f"Unmaterialized entities:  {summary.get('unmaterialized_entities', 0)}")
        print(f"Unavailable:              {summary.get('unavailable')}")
        print(f"Operational failures:     {summary.get('operational_failures')}")
        print(f"Capability gaps:          {summary.get('capability_gaps')}")
        print(f"Identity accepted:        {summary.get('identity_gate_accepted')}")
        print(f"Standalone gateways:      {summary.get('standalone_gateways')}")
        print(f"ClusterXL members:        {summary.get('clusterxl_members')}")
        print(f"VSX hosts:                {summary.get('vsx_hosts')}")
        print(f"VSX virtual systems:      {summary.get('vsx_virtual_systems')}")
        print(f"Quantum Spark/Embedded:   {summary.get('gaia_embedded_success')}/{summary.get('gaia_embedded_entities')} current")
        platform_counts = summary.get("platform_counts") or {}
        if platform_counts:
            platform_parts = []
            for key in ("gaia", "gaia_embedded", "unknown"):
                row = platform_counts.get(key) or {}
                if row:
                    platform_parts.append(f"{key}:{row.get('success', 0)}/{row.get('selected', 0)}")
            if platform_parts:
                print("Platform coverage:        " + ", ".join(platform_parts))
        print(f"Management-down hosts:    {summary.get('management_reported_down_hosts', 0)}")
        print(f"Unknown-platform entities:{summary.get('platform_unknown_entities', 0)}")
        print(f"Unknown but current:      {summary.get('successful_unknown_platform_entities', 0)}")
        print(f"Model coverage:           {summary.get('model_covered')}")
        print(f"Serial coverage:          {summary.get('serial_covered')}")
        print(f"HA role coverage:         {summary.get('ha_role_covered')}")
        shell_counts = summary.get("shell_mode_counts") or {}
        if shell_counts:
            print("SSH shell profiles:       " + ", ".join(f"{key}={value}" for key, value in sorted(shell_counts.items())))
        print(f"Secret lines withheld:    {summary.get('secret_bearing_lines_withheld')}")
        print(f"Projected safe settings:  {summary.get('safe_projected_settings')}")
        print(f"History first/same/change:{summary.get('first')}/{summary.get('same')}/{summary.get('changed')}")
        print(f"Collector gate:           {summary.get('collector_gate')}")
        print(f"Coverage complete:        {summary.get('coverage_complete')}")
        print(f"Workers:                  {summary.get('workers')}")
        print(f"Collection duration:      {summary.get('duration_seconds')} seconds")
        family_counts = summary.get("failure_family_counts") or {}
        if family_counts:
            print("Failure families:         " + ", ".join(f"{key}={value}" for key, value in sorted(family_counts.items())))
        reason_counts = summary.get("failure_reason_counts") or {}
        if reason_counts:
            print("Failure reasons:          " + ", ".join(f"{key}={value}" for key, value in sorted(reason_counts.items())))
        entity_counts = summary.get("entity_type_counts") or {}
        if entity_counts:
            parts = []
            for key in ("standalone_gateway", "clusterxl_member", "vsx_host", "virtual_system"):
                row = entity_counts.get(key) or {}
                if row:
                    parts.append(f"{key}:{row.get('success', 0)}/{row.get('selected', 0)}")
            if parts:
                print("Entity coverage:          " + ", ".join(parts))
        print(f"Host-key policy:          {summary.get('host_key_policy')}")
        print("Raw configuration saved:  False")
        unified = runtime_paths.output_root / "unified.json"
        if unified.exists():
            pan_result = _load_output_json("pan_config_telemetry.json", runtime_paths.output_root)
            run_html_export(
                unified_json=unified,
                output_html=runtime_paths.output_root / "index.html",
                config_result=pan_result,
                checkpoint_config_result=result,
                workflow_context=_workflow_context("cp-config"),
                repository_root=runtime_paths.repository_root,
                data_root=runtime_paths.data_root,
                lifecycle_store=services.lifecycle_store,
                capability_store=services.capability_store,
                coordinator=services.coordinator,
                scheduler_policy=services.scheduler_policy,
            )
            print(f"HTML:                     {runtime_paths.output_root / 'index.html'}")
        else:
            print("HTML:                     not rendered (output/unified.json unavailable)")
        print(r"Local-only telemetry:     output\cp_config_telemetry.json")
    finally:
        cfg.clear_credentials()
    return None


def integration_checkpoint(ctx):
    """Audit Phase E in full: ``main.py:1518-2082`` moved verbatim.

    ``_pan_config_limit_for_mode`` / ``_require_partial_inputs`` /
    ``_render_partial_inventory`` (audit finding F5) stay nested here: they no
    longer close over a 1,690-line ``main()`` scope, only over this entrypoint's
    parameters, which keeps the Phase-E lazy-import cluster in exactly one place.
    """
    args = ctx.args
    parser = ctx.parser
    runtime_paths = ctx.runtime_paths
    services = ctx.services
    support_bundle_output_root = ctx.support_bundle_output_root
    _admitted = make_admitted(ctx)
    _runtime_config = make_runtime_config(ctx)

    # Heavy/vendor-specific dependencies are loaded only for collection or
    # post-processing runs, not for storage analysis/migration.
    from checkpoint.cp_runner import run_cp
    from checkpoint.vsx_runner import run_vsx
    from panorama.panorama_runtime_runner import run_panorama_runtime
    from configuration.panorama_config_collector import run_panorama_config_evidence
    from configuration.checkpoint_config_collector import run_checkpoint_config_collection
    from utils.merge import run_merge
    from utils.html_export import run_html_export
    from utils.verification import run_verification
    from utils.run_context import RunContext
    from utils.support_bundle import run_support_bundle
    from utils.snapshot import build_failure_aware_snapshot

    if args.only == "pan-config":
        print("=== SECURITYEXPERT PAN CONFIGURATION DEVELOPMENT MODE — PHASE 0.6.0A4.3.3.2 ===\n")
    else:
        print("=== SECURITYEXPERT ===\n")

    collection_requested = args.only in ["cp", "vsx", "panorama", "pan-config", "all"]
    cfg = None

    # Fail fast — before any credential prompt or collector — when a partial
    # mode has no baseline artifacts to reuse.
    _require_bootstrap(args.only, runtime_paths.output_root)

    ###############################################
    # USER INPUT - ONLY WHEN COLLECTION NEEDS IT
    ###############################################
    if collection_requested:
        require_cp_endpoint = args.only in ["cp", "vsx", "all"]
        require_panorama_endpoint = args.only in ["panorama", "pan-config", "all"]
        cfg = _runtime_config(
            require_cp=require_cp_endpoint,
            require_panorama=require_panorama_endpoint,
        )

    run_ctx = RunContext.create(data_root=runtime_paths.data_root, output_root=runtime_paths.output_root) if args.only == "all" else None
    current_stage = None
    report = None
    config_result = None
    checkpoint_config_result = None
    inventory_support_path = None

    def _pan_config_limit_for_mode():
        if args.pan_config_limit is not None:
            return args.pan_config_limit
        if args.pan_config_stage is not None:
            return None if args.pan_config_stage == "all" else int(args.pan_config_stage)
        # Safe POC default when explicitly running the collector; a normal
        # full run intentionally covers the whole connected PAN fleet.
        return 5 if args.only == "pan-config" else None

    def _require_partial_inputs(mode, names):
        missing = [name for name in names if not (runtime_paths.output_root / name).exists()]
        if missing:
            raise RuntimeError(
                f"--only {mode} needs baseline artifacts from a previous checkpoint; missing: {', '.join(missing)}. "
                "Run py.exe -B .\\main.py once to establish a full checkpoint."
            )

    def _render_partial_inventory(mode):
        if mode == "cp":
            _require_partial_inputs(mode, ["cp.json", "vsx.json", "panorama_runtime.json"])
        elif mode == "vsx":
            _require_partial_inputs(mode, ["vsx.json"])
        run_merge(
            cp_file=runtime_paths.output_root / "cp.json",
            vsx_file=runtime_paths.output_root / "vsx.json",
            pan_file=runtime_paths.output_root / "panorama_runtime.json",
            unified_file=runtime_paths.output_root / "unified.json",
        )
        latest_config = _load_output_json("pan_config_telemetry.json", runtime_paths.output_root)
        latest_cp_config = _load_output_json("cp_config_telemetry.json", runtime_paths.output_root)
        run_html_export(
            config_result=latest_config,
            checkpoint_config_result=latest_cp_config,
            workflow_context=_workflow_context(mode),
            unified_json=runtime_paths.output_root / "unified.json",
            output_html=runtime_paths.output_root / "index.html",
            repository_root=runtime_paths.repository_root,
            data_root=runtime_paths.data_root,
            lifecycle_store=services.lifecycle_store,
            capability_store=services.capability_store,
            coordinator=services.coordinator,
            scheduler_policy=services.scheduler_policy,
        )
        print("\n=== PARTIAL DEVELOPMENT OUTPUT ===")
        print(f"Mode: {mode} (MIXED-CYCLE DEVELOPMENT VIEW / NOT A CHECKPOINT)")
        print(f"HTML: {runtime_paths.output_root / 'index.html'}")
        if latest_config is None:
            print("Configuration: not attached (no previous PAN configuration telemetry)")
        else:
            print("Configuration: reused from latest local PAN configuration telemetry")

    try:
        ###############################################
        # EXECUTION FLOW
        ###############################################

        if args.only in ["cp", "all"]:
            current_stage = "cp" if run_ctx else None
            if run_ctx:
                run_ctx.start_stage(current_stage)
            info(">>> RUNNING CHECK POINT (CP)")
            if args.only == "cp":
                info(">>> CP DEVELOPMENT SCOPE: PHYSICAL NON-VSX ONLY (VSX HOSTS/MEMBERS/VS EXCLUDED)")
            if run_ctx:
                run_ctx.clear_legacy_targets(["cp.json", "cp_telemetry.json", "cp_direct_ssh_probe.json"])
            else:
                _remove_output_files("cp.json", "cp_telemetry.json", "cp_direct_ssh_probe.json", output_dir=runtime_paths.output_root)
            cp_telemetry = _admitted(
                "checkpoint",
                "cp",
                cfg.mds_ip,
                lambda: run_cp(cfg, exclude_vsx=(args.only == "cp")),
                run_context=run_ctx,
            )
            if run_ctx:
                run_ctx.capture("cp.json", "parsed")
                run_ctx.capture("cp_telemetry.json", "raw")
                run_ctx.capture("cp_direct_ssh_probe.json", "raw")
                cp_summary = (cp_telemetry or {}).get("summary") or {}
                cp_failed = cp_summary.get("failed_devices")
                cp_partial = cp_summary.get("partial_devices")
                cp_down = cp_summary.get("management_down_devices")
                cp_stage_status = (
                    "degraded"
                    if any(isinstance(v, int) and v > 0 for v in (cp_failed, cp_partial, cp_down))
                    else "success"
                )
                run_ctx.finish_stage(
                    current_stage,
                    {
                        "attempted_devices": cp_summary.get("attempted_devices"),
                        "successful_devices": cp_summary.get("successful_devices"),
                        "partial_devices": cp_partial,
                        "failed_devices": cp_failed,
                        "management_down_devices": cp_down,
                        "retried_devices": cp_summary.get("retried_devices"),
                        "recovered_after_retry": cp_summary.get("recovered_after_retry"),
                        "parallelism": cp_summary.get("parallelism"),
                    },
                    status=cp_stage_status,
                )
            current_stage = None

            if args.only == "cp":
                _render_partial_inventory("cp")

            if args.only == "all":
                _cp_stage_cooldown("vsx_collect")

        if args.only in ["vsx", "all"]:
            current_stage = "vsx_collect" if run_ctx else None
            if run_ctx:
                run_ctx.start_stage(current_stage)
            info(">>> RUNNING VSX (RAW)")
            if run_ctx:
                run_ctx.clear_legacy_targets(["vsx_raw.json", "vsx_telemetry.json", "vsx.json"])
            else:
                _remove_output_files("vsx_raw.json", "vsx_telemetry.json", "vsx.json", output_dir=runtime_paths.output_root)
            _admitted(
                "checkpoint",
                "vsx",
                cfg.mds_ip,
                lambda: run_vsx(cfg),
                run_context=run_ctx,
            )
            if run_ctx:
                run_ctx.capture("vsx_raw.json", "raw")
                run_ctx.capture("vsx_telemetry.json", "raw")
                run_ctx.finish_stage(current_stage)
            current_stage = None

            current_stage = "vsx_parse" if run_ctx else None
            if run_ctx:
                run_ctx.start_stage(current_stage)
            info(">>> RUNNING VSX PARSER")
            from checkpoint.vsx_parser import run_vsx_parse  # lazy import
            run_vsx_parse(runtime_paths.output_root)
            if run_ctx:
                run_ctx.capture("vsx.json", "parsed")
                run_ctx.finish_stage(current_stage)
            current_stage = None

            if args.only == "vsx":
                _render_partial_inventory("vsx")

        if args.only == "all" and not args.skip_config:
            _cp_stage_cooldown("cp_config")
            current_stage = "cp_config"
            run_ctx.start_stage(current_stage)
            info(">>> RUNNING CHECK POINT CONFIGURATION COLLECTION (STANDALONE + CLUSTERXL + VSX)")
            try:
                checkpoint_config_result = _admitted(
                    "checkpoint",
                    "cp-config",
                    cfg.mds_ip,
                    lambda: run_checkpoint_config_collection(
                        cfg,
                        stage="all",
                        max_workers=args.cp_config_workers,
                        orchestration_run_id=run_ctx.run_id,
                    ),
                    run_context=run_ctx,
                )
                cp_config_summary = checkpoint_config_result.get("summary") or {}
                run_ctx.capture("cp_config_telemetry.json", "parsed")
                cp_config_status = "success" if cp_config_summary.get("collector_gate") is True else "degraded"
                run_ctx.finish_stage(
                    current_stage,
                    {
                        "selected": cp_config_summary.get("selected"),
                        "success": cp_config_summary.get("success"),
                        "failed": cp_config_summary.get("failed"),
                        "standalone_gateways": cp_config_summary.get("standalone_gateways"),
                        "clusterxl_members": cp_config_summary.get("clusterxl_members"),
                        "vsx_hosts": cp_config_summary.get("vsx_hosts"),
                        "vsx_virtual_systems": cp_config_summary.get("vsx_virtual_systems"),
                        "secret_bearing_lines_withheld": cp_config_summary.get("secret_bearing_lines_withheld"),
                        "raw_configuration_persisted": False,
                        "host_key_policy": cp_config_summary.get("host_key_policy"),
                    },
                    status=cp_config_status,
                )
            except Exception as cp_config_exc:
                run_ctx.finish_stage(
                    current_stage,
                    {
                        "error_type": type(cp_config_exc).__name__,
                        "error": str(cp_config_exc),
                        "raw_configuration_persisted": False,
                    },
                    status="degraded",
                )
                info(
                    ">>> CHECK POINT CONFIGURATION STAGE DEGRADED; INVENTORY PIPELINE WILL CONTINUE "
                    f"({type(cp_config_exc).__name__})"
                )
            current_stage = None

        if args.only == "pan-config":
            info(">>> RUNNING PHASE 0.6.0A4.2.2 PAN SEMANTIC POLICY & PROVENANCE HARDENING")
            config_result = _admitted(
                "paloalto",
                "pan-config",
                cfg.panorama_ip,
                lambda: run_panorama_config_evidence(
                    cfg,
                    limit=_pan_config_limit_for_mode(),
                    max_workers=args.pan_config_workers,
                    probe_pushed_template=args.pan_probe_pushed_template,
                ),
            )
            summary = config_result.get("summary") or {}
            print(
                "PAN A4.2.2 semantic policy/provenance: "
                f"stage={summary.get('stage')} "
                f"selected={summary.get('selected')} "
                f"primary={summary.get('primary_evidence_success')} "
                f"expected_mapped={summary.get('expected_compiler_selected_mapped')} "
                f"expected_ready={summary.get('expected_compiler_selected_alignment_ready_settings')} "
                f"compiler_gate={summary.get('expected_compiler_gate')} "
                f"alignment_complete={summary.get('alignment_evidence_complete')} "
                f"identity={summary.get('direct_identity_verified')} "
                f"effective={summary.get('direct_effective_success')} "
                f"merged={summary.get('direct_merged_success')} "
                f"active={summary.get('direct_active_success')} "
                f"ha_roles={summary.get('ha_runtime_role_available')} "
                f"ha_queries={summary.get('ha_runtime_target_queries')} "
                f"ha_query_failures={summary.get('ha_runtime_target_failed')} "
                f"out_of_sync={summary.get('panorama_any_out_of_sync')} "
                f"method_failures={summary.get('method_failures_total')} "
                f"aligned={(summary.get('setting_alignment_classifications') or {}).get('ALIGNED', 0)} "
                f"local_override={(summary.get('setting_alignment_classifications') or {}).get('LOCAL_OVERRIDE', 0)} "
                f"drift={(summary.get('setting_alignment_classifications') or {}).get('EFFECTIVE_DRIFT', 0)} "
                f"engine_gate={summary.get('setting_alignment_engine_gate')} "
                f"a4_2_pass={summary.get('a4_2_stage_pass')} "
                f"semantic_candidates={summary.get('semantic_validation_possible_schema_equivalents')} "
                f"semantic_samples={summary.get('semantic_validation_manual_samples')} "
                f"semantic_engine={summary.get('semantic_validation_engine_gate')} "
                f"manual={summary.get('semantic_validation_manual_confirmation_status')} "
                f"a4_2_1_engine={summary.get('a4_2_1_engine_pass')}"
            )
            print(f"Config support: {config_result.get('support_path')}")
            print(f"Local expected compiler report: {config_result.get('expected_compiler_report_path')}")
            print(f"Local setting alignment report: {config_result.get('setting_alignment_report_path')}")
            print(f"Local semantic validation report: {config_result.get('semantic_validation_report_path')}")
            print(f"Local semantic validation samples: {config_result.get('semantic_validation_samples_csv_path')}")
            print(f"Local method diagnostics: {config_result.get('failures_path')}")
            _require_partial_inputs("pan-config", ["unified.json"])
            run_html_export(
                unified_json=runtime_paths.output_root / "unified.json",
                output_html=runtime_paths.output_root / "index.html",
                repository_root=runtime_paths.repository_root,
                data_root=runtime_paths.data_root,
                config_result=config_result,
                checkpoint_config_result=_load_output_json("cp_config_telemetry.json", runtime_paths.output_root),
                workflow_context=_workflow_context("pan-config"),
                lifecycle_store=services.lifecycle_store,
                capability_store=services.capability_store,
                coordinator=services.coordinator,
                scheduler_policy=services.scheduler_policy,
            )
            print("\n=== PARTIAL DEVELOPMENT OUTPUT ===")
            print("Mode: pan-config (FRESH PAN CONFIG + REUSED INVENTORY / NOT A CHECKPOINT)")
            print(f"HTML: {runtime_paths.output_root / 'index.html'}")

        if args.only in ["panorama", "all"]:
            current_stage = "panorama" if run_ctx else None
            if run_ctx:
                run_ctx.start_stage(current_stage)
            info(">>> RUNNING PANORAMA")
            if run_ctx:
                run_ctx.clear_legacy_targets(["panorama_runtime.json", "panorama_telemetry.json"])
            _admitted(
                "paloalto",
                "panorama",
                cfg.panorama_ip,
                lambda: run_panorama_runtime(cfg),
                run_context=run_ctx,
            )
            if run_ctx:
                pan_telemetry = _load_output_json("panorama_telemetry.json", runtime_paths.output_root) or {}
                run_ctx.capture("panorama_runtime.json", "parsed")
                run_ctx.capture("panorama_telemetry.json", "raw")
                pan_failed = pan_telemetry.get("failed")
                pan_stage_status = "degraded" if isinstance(pan_failed, int) and pan_failed > 0 else "success"
                run_ctx.finish_stage(
                    current_stage,
                    {
                        "discovered": pan_telemetry.get("discovered"),
                        "successful": pan_telemetry.get("successful"),
                        "failed": pan_failed,
                        "connected_no": pan_telemetry.get("connected_no"),
                    },
                    status=pan_stage_status,
                )
            current_stage = None

        if args.only == "all" and not args.skip_config:
            current_stage = "pan_config"
            run_ctx.start_stage(current_stage)
            info(">>> RUNNING PAN CONFIGURATION EVIDENCE + EXPECTED COMPILER + SETTING ALIGNMENT + SEMANTIC POLICY/PROVENANCE HARDENING + VALIDATION (FULL FLEET)")
            try:
                config_result = _admitted(
                    "paloalto",
                    "pan-config",
                    cfg.panorama_ip,
                    lambda: run_panorama_config_evidence(
                        cfg,
                        limit=_pan_config_limit_for_mode(),
                        max_workers=args.pan_config_workers,
                        orchestration_run_id=run_ctx.run_id,
                        probe_pushed_template=args.pan_probe_pushed_template,
                    ),
                    run_context=run_ctx,
                )
                config_summary = config_result.get("summary") or {}
                config_status = "success" if config_summary.get("a4_2_1_engine_pass") is True else "degraded"
                run_ctx.finish_stage(
                    current_stage,
                    {
                        "selected": config_summary.get("selected"),
                        "primary_evidence_success": config_summary.get("primary_evidence_success"),
                        "alignment_evidence_complete": config_summary.get("alignment_evidence_complete"),
                        "expected_compiler_selected_mapped": config_summary.get("expected_compiler_selected_mapped"),
                        "expected_compiler_gate": config_summary.get("expected_compiler_gate"),
                        "a4_1_stage_pass": config_summary.get("a4_1_stage_pass"),
                        "setting_alignment_engine_gate": config_summary.get("setting_alignment_engine_gate"),
                        "a4_2_stage_pass": config_summary.get("a4_2_stage_pass"),
                        "semantic_validation_engine_gate": config_summary.get("semantic_validation_engine_gate"),
                        "a4_2_1_engine_pass": config_summary.get("a4_2_1_engine_pass"),
                        "semantic_validation_manual_confirmation_status": config_summary.get("semantic_validation_manual_confirmation_status"),
                        "semantic_validation_possible_schema_equivalents": config_summary.get("semantic_validation_possible_schema_equivalents"),
                        "semantic_validation_manual_samples": config_summary.get("semantic_validation_manual_samples"),
                        "setting_local_override": (config_summary.get("setting_alignment_classifications") or {}).get("LOCAL_OVERRIDE", 0),
                        "setting_effective_drift": (config_summary.get("setting_alignment_classifications") or {}).get("EFFECTIVE_DRIFT", 0),
                        "config_support": config_result.get("support_path"),
                    },
                    status=config_status,
                )
            except Exception as config_exc:
                # Configuration collection is an independently reportable plane.
                # A failure must be visible, but must not prevent the already
                # collected inventory from being snapshotted/published.
                run_ctx.finish_stage(
                    current_stage,
                    {
                        "error_type": type(config_exc).__name__,
                        "error": str(config_exc),
                        "a4_1_stage_pass": False,
                        "a4_2_stage_pass": False,
                        "a4_2_1_engine_pass": False,
                    },
                    status="degraded",
                )
                info(
                    ">>> PAN CONFIGURATION STAGE DEGRADED; INVENTORY PIPELINE WILL CONTINUE "
                    f"({type(config_exc).__name__})"
                )
            current_stage = None

        if args.only == "all":
            current_stage = "snapshot"
            run_ctx.start_stage(current_stage)
            info(">>> BUILDING FAILURE-AWARE SNAPSHOT")
            snapshot_summary = build_failure_aware_snapshot(run_ctx)
            run_ctx.archive_from_stage("snapshot_status.json", "root")
            run_ctx.finish_stage(current_stage, snapshot_summary)
            current_stage = None

        if args.only in ["merge", "all"]:
            current_stage = "merge" if run_ctx else None
            if run_ctx:
                run_ctx.start_stage(current_stage)
            info(">>> MERGING DATA")
            if run_ctx:
                run_merge(
                    cp_file=run_ctx.stage_dir / "cp_effective.json",
                    vsx_file=run_ctx.stage_dir / "vsx_effective.json",
                    pan_file=run_ctx.stage_dir / "panorama_effective.json",
                    unified_file=run_ctx.stage_dir / "unified.json",
                )
                run_ctx.archive_from_stage("unified.json", "unified")
                run_ctx.publish_from_stage("unified.json")
                run_ctx.finish_stage(current_stage)
            else:
                run_merge(
                    cp_file=runtime_paths.output_root / "cp.json",
                    vsx_file=runtime_paths.output_root / "vsx.json",
                    pan_file=runtime_paths.output_root / "panorama_runtime.json",
                    unified_file=runtime_paths.output_root / "unified.json",
                )
            current_stage = None

        if args.only in ["verify", "all"]:
            current_stage = "verify" if run_ctx else None
            if run_ctx:
                run_ctx.start_stage(current_stage)
            info(">>> VERIFYING BASELINE DATA")
            if run_ctx:
                report = run_verification(run_ctx.stage_dir)
                run_ctx.archive_from_stage("verification.json", "root")
                run_ctx.publish_from_stage("verification.json")
                run_ctx.finish_stage(current_stage, {"run_status": report.get("run_status")})
            else:
                report = run_verification(runtime_paths.output_root)
            current_stage = None

        if args.only in ["html", "all"]:
            current_stage = "html" if run_ctx else None
            if run_ctx:
                run_ctx.start_stage(current_stage)
            info(">>> GENERATING HTML")
            if run_ctx:
                run_html_export(
                    unified_json=run_ctx.stage_dir / "unified.json",
                    output_html=run_ctx.stage_dir / "index.html",
                    config_result=config_result,
                    checkpoint_config_result=checkpoint_config_result,
                    workflow_context=_workflow_context("checkpoint", run_id=run_ctx.run_id),
                    repository_root=runtime_paths.repository_root,
                    data_root=runtime_paths.data_root,
                    lifecycle_store=services.lifecycle_store,
                    capability_store=services.capability_store,
                    coordinator=services.coordinator,
                    scheduler_policy=services.scheduler_policy,
                    # 0.7.5 — only the full checkpoint appends a compliance-trend
                    # ledger record.
                    record_checkpoint=True,
                    run_id=run_ctx.run_id,
                )
                run_ctx.archive_from_stage("index.html", "root")
                run_ctx.publish_from_stage("index.html")
                run_ctx.finish_stage(current_stage)
            else:
                run_html_export(
                    unified_json=runtime_paths.output_root / "unified.json",
                    output_html=runtime_paths.output_root / "index.html",
                    repository_root=runtime_paths.repository_root,
                    data_root=runtime_paths.data_root,
                    workflow_context={"mode": "diagnostic-html", "label": "HTML diagnostic", "checkpoint": False, "mixed_cycle": True},
                    lifecycle_store=services.lifecycle_store,
                    capability_store=services.capability_store,
                    coordinator=services.coordinator,
                    scheduler_policy=services.scheduler_policy,
                )
            current_stage = None

        if run_ctx:
            final_status = (
                "degraded"
                if any(stage.get("status") == "degraded" for stage in run_ctx.stages.values())
                else "completed"
            )
            run_ctx.write_manifest(
                status=final_status,
                verification_status=(report or {}).get("run_status"),
            )
            info(">>> GENERATING SHAREABLE INVENTORY SUPPORT BUNDLE")
            inventory_support_path = run_support_bundle(
                run_ctx.root,
                data_root=runtime_paths.data_root,
                output_root=support_bundle_output_root or runtime_paths.output_root,
            )
        elif args.only == "support":
            inventory_support_path = run_support_bundle(
                data_root=runtime_paths.data_root,
                output_root=support_bundle_output_root or runtime_paths.output_root,
            )

        if args.only == "all":
            print("\n=== FULL RUN OUTPUTS ===")
            print("Mode: full integration checkpoint")
            print(f"Run ID: {run_ctx.run_id}")
            print(f"HTML: {runtime_paths.output_root / 'index.html'}")
            print(f"Inventory support: {inventory_support_path}")
            if args.skip_config:
                print("Configuration: SKIPPED (--skip-config)")
            else:
                if checkpoint_config_result is not None:
                    cp_summary = checkpoint_config_result.get("summary") or {}
                    print(
                        "Check Point config: "
                        f"{cp_summary.get('success')}/{cp_summary.get('selected')} current | "
                        f"standalone={cp_summary.get('standalone_gateways')} "
                        f"cluster_members={cp_summary.get('clusterxl_members')} "
                        f"vsx_hosts={cp_summary.get('vsx_hosts')} "
                        f"vs={cp_summary.get('vsx_virtual_systems')} "
                        f"operational_failures={cp_summary.get('operational_failures')} "
                        f"capability_gaps={cp_summary.get('capability_gaps')}"
                    )
                else:
                    print("Check Point config: NOT PRODUCED (configuration stage degraded)")
            if not args.skip_config and config_result is not None:
                print(f"Config support: {config_result.get('support_path')}")
                print(f"Expected compiler report (LOCAL ONLY): {config_result.get('expected_compiler_report_path')}")
                print(f"Setting alignment report (LOCAL ONLY): {config_result.get('setting_alignment_report_path')}")
                print(f"Semantic validation report (LOCAL ONLY): {config_result.get('semantic_validation_report_path')}")
                print(f"Semantic validation samples (LOCAL ONLY): {config_result.get('semantic_validation_samples_csv_path')}")
                print(f"Method diagnostics (LOCAL ONLY): {config_result.get('failures_path')}")
            elif not args.skip_config:
                print("PAN Config support: NOT PRODUCED (configuration stage degraded before bundle creation)")

        print("\n=== DONE ===")
    except KeyboardInterrupt:
        if run_ctx:
            if current_stage:
                run_ctx.fail_stage(current_stage, "interrupted by operator")
            else:
                run_ctx.write_manifest(status="failed", error="interrupted by operator")
        info(">>> RUN INTERRUPTED BY OPERATOR (KeyboardInterrupt)")
        raise SystemExit(130)
    except Exception as exc:
        if run_ctx:
            if current_stage:
                run_ctx.fail_stage(current_stage, exc)
            else:
                run_ctx.write_manifest(status="failed", error=str(exc))
        raise
    finally:
        if cfg is not None:
            cfg.clear_credentials()
