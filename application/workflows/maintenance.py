"""Privacy / storage / render / diagnostic modes and the RuntimeRoot scheduler.

§5 "privacy, storage, render and diagnostic modes". Each entrypoint is a
verbatim move of its ``main()`` mode block; the only change is reading shared
state off the passed :class:`~application.context.ApplicationContext` instead of
``main()`` locals, and (in the scheduler) resolving the top-level entry through
``main.main`` so it stays monkeypatchable.
"""
from __future__ import annotations

from pathlib import Path

from application.services import _load_output_json, _require_bootstrap, _workflow_context
from utils.collection_executor import workflow_argv

# main.py sits at the repository root; this module is application/workflows/.
_REPO_ROOT = Path(__file__).resolve().parents[2]


# ---------------------------------------------------------------------------
# Scheduler (--scheduler-once)
# ---------------------------------------------------------------------------
def _scheduler_workflow_argv(row, runtime_root):
    """Thin wrapper: CON.2 C2-2 promoted the argv construction itself to
    ``utils.collection_executor.workflow_argv`` so the scheduler and the
    console job runner share one path."""
    return workflow_argv(row.workflow, runtime_root, targets=row.targets)


def _run_scheduler_once(runtime_paths, services):
    """Evaluate the RuntimeRoot scheduler policy once; never create a loop.

    On the PostgreSQL coordinator backend, the whole read-evaluate-write
    cycle is gated behind a non-blocking scheduler-wide advisory lock
    (correctness contract item 6, DEV.3.2) so two scheduler processes never
    both see the same workflow as due and both dispatch it. This is
    independent of, and does not substitute for, the per-endpoint/per-vendor
    admission a dispatched workflow still goes through — it only stops
    redundant dispatch attempts. The in-memory backend has no such
    cross-process race to gate.
    """
    from utils.coordinator_backend import PostgresCoordinatorBackend, SchedulerLockUnavailable

    backend = services.coordinator.backend
    if isinstance(backend, PostgresCoordinatorBackend):
        try:
            with backend.scheduler_lock():
                return _evaluate_and_dispatch_due_workflows(runtime_paths, services)
        except SchedulerLockUnavailable:
            print("Scheduler: another process is already evaluating the schedule; skipping this cycle.")
            return []
    return _evaluate_and_dispatch_due_workflows(runtime_paths, services)


def _evaluate_and_dispatch_due_workflows(runtime_paths, services):
    """The actual read-evaluate-write cycle; see ``_run_scheduler_once``."""
    import main  # entry-ward re-invocation; kept patchable as main.main
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
            main.main(
                _scheduler_workflow_argv(row, runtime_paths.runtime_root),
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


def scheduler_once(ctx):
    results = _run_scheduler_once(ctx.runtime_paths, ctx.services)
    print(f"Scheduler one-shot complete: terminal_jobs={len(results)}")
    return None


# ---------------------------------------------------------------------------
# Phase-B offline modes (run before the runtime foundation exists)
# ---------------------------------------------------------------------------
def repository_privacy_check(ctx):
    # Repository privacy validation is deliberately local/offline and returns
    # before RuntimeRoot creation, credential prompts, or any collector import.
    from utils.repository_privacy import RepositoryPrivacyError, scan_repository
    print("=== SECURITYEXPERT LOCAL REPOSITORY PRIVACY GATE — DEV.0.4 ===\n")
    try:
        report = scan_repository(_REPO_ROOT)
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


def storage_analyze(ctx):
    # Storage maintenance is deliberately credential-free and independent from
    # network collection. This branch returns before interactive authentication prompts.
    # NOTE (AC-3/AC-5): utils.config_storage pulls in lxml transitively via
    # utils.config_evidence; kept lazy here, at first use, so this offline mode
    # never loads it (the audit found this import was eager at main.py's top
    # level pre-split -- a pre-existing gap, not a behavior change).
    from utils.config_storage import analyze_configuration_storage, human_bytes

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
    return None


def storage_deduplicate(ctx):
    from utils.config_storage import deduplicate_legacy_storage, human_bytes

    args = ctx.args
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
    return None


# ---------------------------------------------------------------------------
# Phase-D single-purpose modes (need the runtime foundation)
# ---------------------------------------------------------------------------
def persistent_secret_material_check(ctx):
    runtime_paths = ctx.runtime_paths
    from utils.persistent_secret_material import check_persistent_secret_material
    print("=== SECURITYEXPERT PERSISTENT SECRET MATERIAL CHECK — DEV.2.2 ===\n")
    report = check_persistent_secret_material(runtime_paths)
    print(f"HMAC identity key present:    {report.hmac_key_present}")
    print(f"HMAC identity key on volume:  {report.hmac_key_on_persistent_root}")
    print(f"CP strict host-key enabled:   {report.cp_strict_host_key_enabled}")
    print(f"CP trust preflight:           {report.cp_trust_status}")
    print(f"PAN CA bundle configured:     {report.pan_ca_bundle_configured}")
    print(f"PAN trust preflight:          {report.pan_trust_status}")
    if not report.hmac_key_present:
        print(
            "\nNote: no HMAC identity key on the persistent data root yet -- one will "
            "be generated on the first support-bundle write and then persists across "
            "restarts as long as the runtime volume is retained."
        )
    if not report.cp_strict_host_key_enabled or not report.pan_ca_bundle_configured:
        print(
            "\nAdvisory: production hardening (SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY=1 "
            "with a mounted known_hosts, SECURITYEXPERT_PAN_CA_BUNDLE with a mounted CA "
            "bundle) is not fully enabled. Not a gate failure by itself -- compatibility "
            "mode is the accepted default off the production server."
        )
    if report.findings:
        print("\nFindings:")
        for finding in report.findings:
            print(f"  {finding}")
    print(f"\nGate:                         {report.gate}")
    print("No network access performed. No key material, path or credential was printed.")
    raise SystemExit(0 if report.gate == "PASS" else 1)


def compliance_trend_reconstruct(ctx):
    runtime_paths = ctx.runtime_paths
    from utils.compliance_history import append_reconstructed
    from utils.compliance_trend_reconstruction import RECONSTRUCTION_SCOPE, reconstruct_pan_baseline_records
    print(f"=== SECURITYEXPERT COMPLIANCE TREND RETRO-FILL ({RECONSTRUCTION_SCOPE}) — PHASE 0.7.7 ===\n")
    records = reconstruct_pan_baseline_records()
    appended = append_reconstructed(runtime_paths.data_root, records)
    print(f"Reconstructed buckets found: {len(records)}")
    print(f"New records appended:        {appended}")
    print(f"Already present (skipped):   {len(records) - appended}")
    print(
        "\nScope: PAN devices only, the ten deterministic baseline rule-pack controls only. "
        "No alignment, no CP, no assignment/waiver or CE.1 check replay. Records are stamped "
        "reconstructed=true and never affect the live trend delta."
    )
    return None


def render_only(ctx):
    runtime_paths = ctx.runtime_paths
    services = ctx.services
    from utils.html_export import run_html_export

    _require_bootstrap("render-only", runtime_paths.output_root)
    unified = runtime_paths.output_root / "unified.json"
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
        data_root=runtime_paths.data_root,
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
    return None
