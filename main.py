import argparse
import getpass
import json
import os
import sys
import time
from pathlib import Path

from utils.logger import (
    info,
    configure_log_root,
    register_sensitive_value,
    principal_fingerprint,
)

# Storage maintenance stays dependency-light and credential-free. Collection
# runners are imported lazily inside main() after storage-only commands return.
from utils.config_storage import analyze_configuration_storage, deduplicate_legacy_storage, human_bytes
from utils.runtime_config_source import RuntimeConfigError, resolve_value
from utils.runtime_paths import RuntimePathError, resolve_runtime_paths
from config import Config


###############################################
# CONFIG OBJECT
###############################################


def _load_output_json(name, output_dir=Path("output")):
    path = Path(output_dir) / name
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def _remove_output_files(*names, output_dir=Path("output")):
    output_dir = Path(output_dir)
    for name in names:
        path = output_dir / name
        if path.exists():
            path.unlink()
            info(f">>> STALE OUTPUT REMOVED BEFORE PARTIAL COLLECTION: {name}")


def _workflow_context(mode, *, run_id=None):
    labels = {
        "checkpoint": "Full checkpoint",
        "render-only": "Render only",
        "pan-config": "PAN configuration only",
        "vsx": "VSX only",
        "cp": "Check Point physical only",
        "cp-config": "Check Point configuration only",
    }
    reused = {
        "checkpoint": [],
        "render-only": ["unified inventory", "PAN configuration/alignment", "Check Point configuration"],
        "pan-config": ["unified inventory"],
        "vsx": ["Check Point inventory", "PAN runtime", "PAN configuration/alignment"],
        "cp": ["VSX inventory", "PAN runtime", "PAN configuration/alignment", "Check Point configuration"],
        "cp-config": ["unified inventory", "PAN configuration/alignment"],
    }
    fresh = {
        "checkpoint": ["CP", "VSX", "Check Point configuration", "PAN runtime", "PAN configuration", "HTML"],
        "render-only": ["HTML"],
        "pan-config": ["PAN configuration", "HTML"],
        "vsx": ["VSX", "merge", "HTML"],
        "cp": ["CP non-VSX", "merge", "HTML"],
        "cp-config": ["Check Point configuration", "HTML"],
    }
    return {
        "mode": mode,
        "label": labels.get(mode, mode),
        "checkpoint": mode == "checkpoint",
        "mixed_cycle": mode != "checkpoint",
        "run_id": run_id,
        "fresh_planes": fresh.get(mode, []),
        "reused_planes": reused.get(mode, []),
    }


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


# DEV.2.1: public non-interactive configuration contract (compose files, secret
# mounts). Each also accepts a `<VAR>_FILE` variant pointing at a secret-mount
# file; see utils/runtime_config_source.resolve_value and docs/builds/.
_PRINCIPAL_VAR = "SECURITYEXPERT_PRINCIPAL"
_SECRET_VAR = "SECURITYEXPERT_SECRET"
_CP_MDS_ENDPOINT_VAR = "SECURITYEXPERT_CP_MDS_ENDPOINT"
_PANORAMA_ENDPOINT_VAR = "SECURITYEXPERT_PANORAMA_ENDPOINT"


def _prompt_management_endpoint(label):
    """Read an environment-specific management endpoint at runtime.

    Endpoints are intentionally not persisted by this helper.  A hostname or
    IP address is accepted; vendor collectors retain responsibility for their
    existing transport/scheme normalization.
    """
    while True:
        value = input(f"{label}: " ).strip()
        if value:
            return value
        print(f"{label} is required.")


def _resolve_or_prompt(name, label, *, kind, interactive, missing):
    """Resolve `name` non-interactively; otherwise prompt when stdin is a TTY;
    otherwise record `name` in `missing` and return None.

    `kind` selects the interactive fallback: "endpoint" (looped non-empty
    prompt), "secret" (getpass), or "line" (bare input).
    """
    value = resolve_value(name)
    if value is not None:
        return value
    if interactive:
        if kind == "secret":
            return getpass.getpass(f"{label}: ")
        if kind == "endpoint":
            return _prompt_management_endpoint(label)
        return input(f"{label}: ")
    missing.append(name)
    return None


def _build_runtime_config(*, require_cp, require_panorama, runtime_paths=None):
    """Build the process Config from env / secret files, falling back to
    interactive prompts only when stdin is a TTY.

    Precedence per value: `<VAR>_FILE` > `<VAR>` > prompt (TTY only). When stdin
    is not a TTY and a required value is unresolved, raise RuntimeConfigError
    naming every missing variable, before any collector import or network call.
    """
    interactive = sys.stdin.isatty()
    missing = []

    cp_endpoint = (
        _resolve_or_prompt(_CP_MDS_ENDPOINT_VAR, "Check Point Management",
                           kind="endpoint", interactive=interactive, missing=missing)
        if require_cp else None
    )
    panorama_endpoint = (
        _resolve_or_prompt(_PANORAMA_ENDPOINT_VAR, "Palo Alto Panorama",
                           kind="endpoint", interactive=interactive, missing=missing)
        if require_panorama else None
    )
    principal = _resolve_or_prompt(_PRINCIPAL_VAR, "Login",
                                   kind="line", interactive=interactive, missing=missing)
    secret = _resolve_or_prompt(_SECRET_VAR, "Authentication secret",
                                kind="secret", interactive=interactive, missing=missing)

    if missing:
        raise RuntimeConfigError(
            "non-interactive runtime configuration incomplete (stdin is not a TTY): set "
            + ", ".join(f"{name} (or {name}_FILE)" for name in missing)
        )

    principal_id = principal_fingerprint(principal)
    register_sensitive_value(principal, f"[AUTH_PRINCIPAL:{principal_id}]")
    register_sensitive_value(secret, "[AUTH_SECRET:REDACTED]")

    cfg = Config(principal, secret, cp_endpoint, panorama_endpoint, runtime_paths=runtime_paths)
    secret = None
    principal = None
    return cfg


def _scheduler_workflow_argv(workflow, runtime_root):
    normalized = "cp" if workflow == "checkpoint" else workflow
    base = ["--runtime-root", str(runtime_root)]
    if normalized == "cp-config":
        return [*base, "--cp-config-collect", "--cp-config-stage", "all"]
    return [*base, "--only", normalized]


def _run_scheduler_once(runtime_paths, services):
    """Evaluate the RuntimeRoot scheduler policy once; never create a loop."""
    from datetime import datetime, timezone

    from utils.collection_executor import (
        CollectionAdmissionError,
        Provenance,
        is_workflow_due,
        load_scheduler_state,
        write_scheduler_state,
    )
    from utils.run_context import RunContext

    policy = services.scheduler_policy
    if policy is None or not policy.enabled or not policy.workflows:
        print("Scheduler: disabled or unconfigured; no jobs produced and no network access performed.")
        return []

    state = load_scheduler_state(runtime_paths.data_root)
    due = [row for row in policy.workflows if is_workflow_due(row, state.get(row.workflow))]
    if not due:
        print("Scheduler: no workflows due; no jobs produced and no network access performed.")
        return []

    results = []
    for row in due:
        ctx = RunContext.create(
            data_root=runtime_paths.data_root,
            output_root=runtime_paths.output_root,
        )
        try:
            main(
                _scheduler_workflow_argv(row.workflow, runtime_paths.runtime_root),
                runtime_services=services,
                provenance=Provenance.SCHEDULED.value,
                admission_run_context=ctx,
            )
        except CollectionAdmissionError as exc:
            if exc.decision.value == "coalesced":
                active = services.coordinator.wait_for_terminal(
                    exc.job.coalesced_to or "",
                    timeout=300,
                )
                if active is not None and active.status == "completed":
                    completed_at = datetime.now(timezone.utc)
                    state[row.workflow] = completed_at
                    write_scheduler_state(runtime_paths.data_root, state)
                    ctx.write_manifest(status="completed", scheduler_result="coalesced_completed")
                    results.append({"workflow": row.workflow, "status": "coalesced_completed"})
                    continue
                terminal = active.status if active is not None else "unavailable"
                ctx.write_manifest(status="failed", scheduler_result=f"coalesced_{terminal}")
                raise RuntimeError(f"coalesced scheduled workflow did not complete successfully: {terminal}")
            ctx.write_manifest(status="failed", scheduler_result=exc.decision.value)
            raise
        except BaseException as exc:
            ctx.write_manifest(status="failed", scheduler_result=f"failed_{type(exc).__name__.lower()}")
            raise
        completed_at = datetime.now(timezone.utc)
        state[row.workflow] = completed_at
        write_scheduler_state(runtime_paths.data_root, state)
        ctx.write_manifest(status="completed", scheduler_result="completed")
        results.append({"workflow": row.workflow, "status": "completed"})
    return results


###############################################
# MAIN
###############################################
def main(argv=None, *, runtime_services=None, provenance="manual", admission_run_context=None):

    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--runtime-root",
        default=None,
        help=(
            "External SecurityExpert runtime root. Precedence: this CLI option, "
            "SECURITYEXPERT_RUNTIME_ROOT, then the Windows LOCALAPPDATA default. "
            "DEV.0.3A validates the foundation; artifact consumers migrate in DEV.0.3B/C."
        ),
    )
    parser.add_argument(
        "--only",
        help=(
            "Development/diagnostic scope. Recommended modes: cp / vsx / pan-config / all. "
            "Legacy diagnostics remain available: panorama / merge / verify / html / support."
        ),
        default="all"
    )
    parser.add_argument(
        "--cp-config-probe",
        action="store_true",
        help=(
            "Phase 0.6.1A read-only Check Point configuration evidence probe. "
            "Uses the latest checkpoint to select Standalone, ClusterXL and VSX samples; "
            "SSH login is treated as Expert shell and Gaia reads are invoked through clish. "
            "Raw show-configuration output is never persisted."
        ),
    )
    parser.add_argument(
        "--cp-config-collect",
        action="store_true",
        help=(
            "Phase 0.6.1B.1.2 Check Point configuration collection from existing CP/VSX inventory artifacts. "
            "Uses an interactive SSH capability handshake: direct Clish when proven, otherwise Expert with explicit clish; "
            "VSX context keeps validated vsenv. Raw show-configuration is never persisted."
        ),
    )
    parser.add_argument(
        "--cp-config-stage",
        choices=["sample", "all"],
        default="sample",
        help="Check Point configuration scope for --cp-config-collect. Full integration runs always use all.",
    )
    parser.add_argument(
        "--cp-config-workers",
        type=int,
        default=6,
        help="Check Point configuration physical-host parallelism (1-12, default: 6)",
    )
    parser.add_argument(
        "--render-only",
        action="store_true",
        help=(
            "Regenerate HTML from the latest local unified inventory plus PAN and Check Point configuration telemetry. "
            "No credentials and no network collectors are used."
        ),
    )
    parser.add_argument(
        "--pan-config-limit",
        type=int,
        default=None,
        help="Phase 0.6.0A4.3: explicit override for the number of connected PAN firewalls selected",
    )
    parser.add_argument(
        "--pan-config-stage",
        choices=["5", "10", "all"],
        default=None,
        help=(
            "PAN config scope override. Default: 5 connected firewalls for --only pan-config; "
            "all connected firewalls for a normal full run."
        ),
    )
    parser.add_argument(
        "--skip-config",
        action="store_true",
        help="Skip Check Point and PAN configuration collection during a normal full run.",
    )
    parser.add_argument(
        "--pan-config-workers",
        type=int,
        default=3,
        help="Phase 0.6.0A4.3 direct API parallelism (1-6, default: 3)",
    )
    parser.add_argument(
        "--pan-probe-pushed-template",
        action="store_true",
        help=(
            "Optional diagnostic probe for 'show config pushed-template'. "
            "Disabled by default in A4.2 because fleet validation showed it is not required for primary/alignment evidence."
        ),
    )
    parser.add_argument(
        "--repository-privacy-check",
        action="store_true",
        help=(
            "Run the local/offline Corporate Git candidate privacy gate. "
            "No credentials, runtime collection or network access are used; matched values are never printed."
        ),
    )
    parser.add_argument(
        "--storage-analyze",
        action="store_true",
        help="Analyze configuration history/object storage without collecting devices or changing files.",
    )
    parser.add_argument(
        "--storage-deduplicate",
        action="store_true",
        help=(
            "Plan migration of legacy per-snapshot config payload copies into the content-addressed store. "
            "Dry-run by default; add --apply to perform the migration."
        ),
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Apply --storage-deduplicate. Without this flag, migration is dry-run only.",
    )
    parser.add_argument(
        "--support-bundle-output-dir",
        default=None,
        help=(
            "Optional directory for writing shareable support bundle zip files. "
            "When not set, bundle zip files are written to runtime output. "
            "Use this to publish bundles to a separate non-runtime location."
        ),
    )
    parser.add_argument(
        "--scheduler-once",
        action="store_true",
        help=(
            "Evaluate the default-disabled RuntimeRoot scheduler policy once, "
            "run due allowlisted read-only workflows, then exit. No polling loop is created."
        ),
    )
    args = parser.parse_args(argv)

    maintenance_modes = sum(bool(value) for value in (
        args.repository_privacy_check, args.storage_analyze, args.storage_deduplicate
    ))
    if maintenance_modes > 1:
        parser.error("Choose only one repository/storage maintenance mode")
    if args.repository_privacy_check and args.apply:
        parser.error("--apply is not valid with --repository-privacy-check")
    if args.repository_privacy_check and (args.cp_config_probe or args.cp_config_collect or args.render_only or args.only != "all"):
        parser.error("--repository-privacy-check cannot be combined with collection/render modes")

    if args.storage_analyze and args.storage_deduplicate:
        parser.error("Choose only one of --storage-analyze or --storage-deduplicate")
    if args.apply and not args.storage_deduplicate:
        parser.error("--apply is valid only with --storage-deduplicate")
    if args.cp_config_probe and args.only != "all":
        parser.error("--cp-config-probe cannot be combined with --only")
    if args.cp_config_probe and args.render_only:
        parser.error("--cp-config-probe cannot be combined with --render-only")
    if args.cp_config_probe and (args.storage_analyze or args.storage_deduplicate or args.apply):
        parser.error("--cp-config-probe cannot be combined with storage maintenance options")
    if args.cp_config_collect and args.cp_config_probe:
        parser.error("--cp-config-collect cannot be combined with --cp-config-probe")
    if args.cp_config_collect and args.only != "all":
        parser.error("--cp-config-collect cannot be combined with --only")
    if args.cp_config_collect and args.render_only:
        parser.error("--cp-config-collect cannot be combined with --render-only")
    if args.cp_config_collect and (args.storage_analyze or args.storage_deduplicate or args.apply):
        parser.error("--cp-config-collect cannot be combined with storage maintenance options")
    if args.render_only and args.only != "all":
        parser.error("--render-only cannot be combined with --only")
    if args.render_only and (args.storage_analyze or args.storage_deduplicate or args.apply):
        parser.error("--render-only cannot be combined with storage maintenance options")
    if args.scheduler_once and (
        args.repository_privacy_check
        or args.storage_analyze
        or args.storage_deduplicate
        or args.apply
        or args.cp_config_probe
        or args.cp_config_collect
        or args.render_only
        or args.only != "all"
    ):
        parser.error("--scheduler-once cannot be combined with collection, render, or maintenance modes")

    # Repository privacy validation is deliberately local/offline and returns
    # before RuntimeRoot creation, credential prompts, or any collector import.
    if args.repository_privacy_check:
        from utils.repository_privacy import RepositoryPrivacyError, scan_repository
        print("=== SECURITYEXPERT LOCAL REPOSITORY PRIVACY GATE — DEV.0.4 ===\n")
        try:
            report = scan_repository(Path(__file__).resolve().parent)
        except RepositoryPrivacyError as exc:
            print("Gate:                 ERROR")
            print(f"Reason:               {exc}")
            print("No network access performed. No matched values were printed.")
            raise SystemExit(2)
        print(f"Files scanned:        {report.files_scanned}")
        print(f"Files skipped:        {report.files_skipped}")
        print(f"Findings:             {len(report.findings)}")
        if report.findings:
            print("\nFindings (matched values intentionally withheld):")
            for finding in report.findings:
                location = f"{finding.path}:{finding.line}" if finding.line else finding.path
                print(f"  {location}  {finding.rule}")
        print(f"\nGate:                 {report.gate}")
        print("No network access performed. No matched values were printed.")
        raise SystemExit(0 if report.gate == "PASS" else 1)

    # Storage maintenance is deliberately credential-free and independent from
    # network collection. This branch returns before interactive authentication prompts.
    if args.storage_analyze:
        print("=== SECURITYEXPERT CONFIGURATION STORAGE ANALYSIS — PHASE 0.6.0A4.3.2.1 ===\n")
        report = analyze_configuration_storage()
        print(f"History snapshots:       {report['history_snapshots']}")
        print(f"SAME history events:     {report['same_history_events']}")
        print(f"Safety errors:           {report.get('safety_error_count', 0)}")
        print(f"Payload hashes verified: {report.get('payload_hashes_verified', 0)}")
        print(f"Metadata SHA mismatches: {report.get('metadata_hash_mismatch_count', 0)}")
        print(f"Untrusted metadata SHA:  {report.get('metadata_hash_untrusted_count', 0)}")
        print(f"Corrupt CAS objects:     {report.get('corrupt_existing_cas_object_count', 0)}")
        print(f"Legacy payload files:    {report['legacy_payload_files']}")
        print(f"Legacy payload size:     {human_bytes(report['legacy_payload_bytes'])}")
        print(f"Unique legacy payload:   {human_bytes(report['legacy_unique_payload_bytes'])}")
        print(f"Existing CAS objects:    {report['content_addressed_objects']}")
        print(f"Existing CAS size:       {human_bytes(report['content_addressed_bytes'])}")
        print(f"New unique bytes needed: {human_bytes(report['new_unique_bytes_needed_for_migration'])}")
        print(f"Projected net reclaim:   {human_bytes(report['projected_net_reclaim_bytes'])} "
              f"({report['projected_reclaim_percent_of_legacy_payload']}%)")
        print("\nBy source:")
        for source, row in report.get("by_source", {}).items():
            print(
                f"  {source}: snapshots={row['snapshots']} same={row['same_snapshots']} "
                f"legacy_files={row['legacy_payload_files']} "
                f"legacy_size={human_bytes(row['legacy_payload_bytes'])}"
            )
        if (report.get("safety_error_count") or report.get("metadata_hash_mismatch_count")
                or report.get("metadata_hash_untrusted_count") or report.get("corrupt_existing_cas_object_count")):
            print("\nWARNING: integrity/safety issues detected; migration apply must not proceed until resolved.")
        print("\nNo configuration/history/artifact files changed.")
        return

    if args.storage_deduplicate:
        mode = "APPLY" if args.apply else "DRY RUN"
        print(f"=== SECURITYEXPERT CONFIGURATION STORAGE DEDUPLICATION {mode} — PHASE 0.6.0A4.3.2.1 ===\n")
        report = deduplicate_legacy_storage(apply=args.apply)
        print(f"Legacy payload files:    {report['legacy_payload_files']}")
        print(f"Unique payloads:         {report['unique_payloads']}")
        print(f"Legacy bytes:            {human_bytes(report['legacy_bytes_to_remove'])}")
        print(f"New object bytes:        {human_bytes(report['new_object_bytes_to_create'])}")
        print(f"Projected net reclaim:   {human_bytes(report['projected_net_reclaim_bytes'])}")
        print(f"Migration manifest:      {report.get('manifest_path')}")
        if args.apply:
            print(f"Migrated payload files:  {report.get('migrated_payload_files', 0)}")
            print("Result: legacy payload copies removed only after CAS copy + hash verification + metadata publish.")
        else:
            print("DRY RUN ONLY — legacy/history/CAS data unchanged; a LOCAL-ONLY sensitive manifest was written.")
        return

    support_bundle_output_root = Path(args.support_bundle_output_dir).expanduser() if args.support_bundle_output_dir else None

    try:
        runtime_paths = resolve_runtime_paths(args.runtime_root)
    except RuntimePathError as exc:
        parser.error(str(exc))
    configure_log_root(runtime_paths.logs_root)
    print(
        ">>> RUNTIME PATH FOUNDATION READY "
        f"(runtime_root={runtime_paths.runtime_root} normal_runtime=external history_cas=legacy_pending)"
    )

    from utils.collection_executor import (
        Provenance,
        RuntimeCollectionServices,
        SchedulerPolicyError,
        execute_admitted_collection,
        load_scheduler_policy,
    )

    services = runtime_services or RuntimeCollectionServices()
    try:
        services.scheduler_policy = load_scheduler_policy(runtime_paths.data_root)
    except SchedulerPolicyError as exc:
        parser.error(str(exc))

    if args.scheduler_once:
        results = _run_scheduler_once(runtime_paths, services)
        print(f"Scheduler one-shot complete: terminal_jobs={len(results)}")
        return

    def _admitted(vendor, workflow_scope, endpoint, operation, *, run_context=None):
        if not endpoint:
            raise RuntimeError(f"{workflow_scope} management endpoint is unavailable")
        return execute_admitted_collection(
            services,
            vendor=vendor,
            workflow_scope=workflow_scope,
            canonical_ids=[str(endpoint)],
            provenance=provenance,
            operation=operation,
            run_context=run_context or admission_run_context,
        )

    def _runtime_config(*, require_cp, require_panorama):
        """_build_runtime_config with a clean CLI exit for a non-interactive
        misconfiguration instead of an uncaught traceback."""
        try:
            return _build_runtime_config(
                require_cp=require_cp,
                require_panorama=require_panorama,
                runtime_paths=runtime_paths,
            )
        except RuntimeConfigError as exc:
            parser.error(str(exc))

    if args.cp_config_probe:
        print("=== SECURITYEXPERT CHECK POINT CONFIGURATION IDENTITY + VSX PROBE — PHASE 0.6.1A.1 ===\n")
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
        return

    if args.cp_config_collect:
        print("=== SECURITYEXPERT CHECK POINT CONFIGURATION COLLECTION — PHASE 0.6.1B.1.2 ===\n")
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
        return

    if args.render_only:
        from utils.html_export import run_html_export

        unified = runtime_paths.output_root / "unified.json"
        if not unified.exists():
            raise RuntimeError(
                "--render-only requires output/unified.json from a previous full/partial inventory run. "
                "Run py.exe -B .\\main.py once to establish a checkpoint."
            )
        config_result = _load_output_json("pan_config_telemetry.json", runtime_paths.output_root)
        checkpoint_config_result = _load_output_json("cp_config_telemetry.json", runtime_paths.output_root)
        print("=== SECURITYEXPERT RENDER-ONLY DEVELOPMENT MODE — PHASE 0.6.1B.1 ===\n")
        run_html_export(
            unified_json=unified,
            output_html=runtime_paths.output_root / "index.html",
            config_result=config_result,
            checkpoint_config_result=checkpoint_config_result,
            workflow_context=_workflow_context("render-only"),
            repository_root=runtime_paths.repository_root,
            lifecycle_store=services.lifecycle_store,
            capability_store=services.capability_store,
            coordinator=services.coordinator,
            scheduler_policy=services.scheduler_policy,
        )
        print("\n=== DEVELOPMENT OUTPUT ===")
        print("Mode: render-only (NO NETWORK / NO CREDENTIALS / NOT A CHECKPOINT)")
        print(f"HTML: {runtime_paths.output_root / 'index.html'}")
        if config_result is None:
            print("Configuration: not attached (output/pan_config_telemetry.json not available)")
        else:
            print("Configuration: reused from latest local PAN configuration telemetry")
        return

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
                    lifecycle_store=services.lifecycle_store,
                    capability_store=services.capability_store,
                    coordinator=services.coordinator,
                    scheduler_policy=services.scheduler_policy,
                )
                run_ctx.archive_from_stage("index.html", "root")
                run_ctx.publish_from_stage("index.html")
                run_ctx.finish_stage(current_stage)
            else:
                run_html_export(
                    unified_json=runtime_paths.output_root / "unified.json",
                    output_html=runtime_paths.output_root / "index.html",
                    repository_root=runtime_paths.repository_root,
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


###############################################
# ENTRY
###############################################
if __name__ == "__main__":
    main()
