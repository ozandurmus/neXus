"""The argument surface and dispatch order ``main()`` used to inline.

``build_parser`` and ``validate_modes`` are audit Phase A moved verbatim;
``dispatch`` is the same linear ``if <mode>:`` sequence in the same precedence
order, each body now one call to a workflow entrypoint (D-MOD-B1/B4). Loaded on
every invocation — imports no vendor / transport / heavy-parser module at load
time (AC-3).
"""
from __future__ import annotations

import argparse
from pathlib import Path

from application import services
from application.context import ApplicationContext
from application.workflows import checkpoint as checkpoint_wf
from application.workflows import maintenance as maintenance_wf
from application.workflows import recovery as recovery_wf


def build_parser() -> argparse.ArgumentParser:
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
        "--recovery-root",
        default=None,
        help=(
            "RB.1 recovery-plane store root. Precedence: this CLI option, then "
            "SECURITYEXPERT_RECOVERY_ROOT. No default -- unlike --runtime-root, this is "
            "mandatory and must be a separate volume (docs/design/BACKUP_RECOVERY_CONTRACTS.md §2). "
            "Only used by --recovery-store-check."
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
        "--cp-config-targets",
        default=None,
        help=(
            "OP.0d: exact, comma-separated entity_id allowlist for --cp-config-collect "
            "(the physical-host entity_id already used throughout the repository -- "
            "the 'device' value in cp.json/cp_config_telemetry.json; never a display "
            "label). Fail-closed: every id must resolve to exactly one already-discovered "
            "candidate before any SSH connection opens -- an unknown or ambiguous id aborts "
            "before contact. Takes precedence over --cp-config-stage when set."
        ),
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
        "--pan-config-targets",
        default=None,
        help=(
            "OP.0d: exact, comma-separated serial allowlist for the PAN configuration "
            "collector (the identity-gated serial the collector already cross-checks "
            "against each firewall's own 'show system info'; never a hostname/label). "
            "Fail-closed: every serial must resolve to exactly one currently-connected "
            "discovered firewall before any direct API call -- an unknown, ambiguous, or "
            "not-currently-connected serial aborts before contact. Takes precedence over "
            "--pan-config-limit/--pan-config-stage when set."
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
        "--pan-ha-peer-diagnostic",
        action="store_true",
        help=(
            "OP.0a real-env peer-identity audit: CLASS 0 read-only, opt-in, disabled by "
            "default. Adds no new PAN command/API call -- it only enumerates the field "
            "NAMES already present in the same 'show high-availability state' response "
            "and reports whether the configured peer_ip's one-way token matches the "
            "management_ip's or any runtime field's token. Never reports a raw address."
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
        "--persistent-secret-material-check",
        action="store_true",
        help=(
            "DEV.2.2 local/offline check of the persistent runtime volume contract: "
            "resolves the runtime root, reports whether the support-bundle HMAC "
            "identity key already exists on the persistent data root, and preflights "
            "CP strict host-key trust / PAN CA bundle trust when enabled. No network "
            "access; no key material, path or credential is printed."
        ),
    )
    parser.add_argument(
        "--restore-readiness-check",
        action="store_true",
        help=(
            "RB.0 local/offline restore-readiness assessment: 'if this device died "
            "right now, what do we actually have?' Reads the latest local unified.json "
            "only -- no network access, no credentials, no new device command, no "
            "recovery artifact is collected. Writes data/state/restore_readiness.json. "
            "Every device is UNPROTECTED until RB.1+ ship a recovery store to report "
            "against; that count is the point of running this before then."
        ),
    )
    parser.add_argument(
        "--ha-readiness-check",
        action="store_true",
        help=(
            "OP.0a local/offline HA readiness assessment: 'what do we actually know "
            "about this cluster's failover readiness, and what would we still have to "
            "ask a device?' Reads the latest local unified.json plus already-collected "
            "HA runtime evidence -- no network access, no credentials, no device "
            "command. Writes data/state/ha_readiness.json. It CANNOT report a cluster "
            "safe to fail over: SAFE_TO_FAILOVER is unreachable by design until the "
            "OP.0b preflight battery is gated and built, so INSUFFICIENT_EVIDENCE "
            "means 'not asked yet', not 'unhealthy'."
        ),
    )
    parser.add_argument(
        "--recovery-store-check",
        action="store_true",
        help=(
            "RB.1 local/offline recovery-plane store check: resolves --recovery-root / "
            "SECURITYEXPERT_RECOVERY_ROOT (mandatory, must be a separate volume from "
            "the runtime root), initializes the vault/groups/retention layout and the "
            "vault master key if not already present, and reports what is already held. "
            "No network access, no device collection. Never prints artifact bytes, key "
            "material or the vault key file's path."
        ),
    )
    parser.add_argument(
        "--recovery-validate",
        action="store_true",
        help=(
            "RB.4 local/offline validation (V1-V3) of every artifact already held in "
            "the recovery store: transport (hash/size), structural (archive/XML "
            "well-formed), and semantic (cross-checked against the local unified.json, "
            "when present). Rewrites each artifact's manifest.validation in place. "
            "Never computes V4 (RESTORE_PROVEN) -- that requires a real lab restore, "
            "entered manually. Exit 1 if any artifact's validation verdict is FAILED."
        ),
    )
    parser.add_argument(
        "--recovery-collect",
        action="store_true",
        help=(
            "RB.2/RB.3 recovery artifact collection, via utils.recovery_collect -- this "
            "flag only parses arguments and dispatches; it contains no collection logic "
            "itself. Requires --recovery-vendor. Admission-coordinated per target "
            "(collection_executor), same per-endpoint lock/budget as other collectors."
        ),
    )
    parser.add_argument(
        "--recovery-attest",
        action="store_true",
        help=(
            "RB.3a CP Gaia backup/snapshot *attestation*, via "
            "utils.recovery_collect.run_recovery_attestation -- asks each physical "
            "Check Point endpoint what recovery artifacts it believes it holds "
            "('show backups' / 'show snapshots', frozen tuple, contract §7.5) and "
            "records that as attested-but-unheld evidence in "
            "data/state/recovery_attestations.json, which --restore-readiness-check "
            "then reads. Collects no artifact; writes nothing to the recovery store; "
            "no backup/snapshot name is ever recorded. Check Point only. Reuses "
            "--recovery-gateways for selective targeting. Admission-coordinated per "
            "endpoint (same per-endpoint lock/budget as other collectors)."
        ),
    )
    parser.add_argument(
        "--recovery-vendor",
        choices=["panorama", "checkpoint"],
        default=None,
        help=(
            "Vendor for --recovery-collect. 'panorama' (PAN device-state export) is "
            "implemented. 'checkpoint' (CP Gaia backup *collection*) is a blocked "
            "stub pending open decision D3. CP Gaia backup/snapshot *attestation* "
            "(read-only, no artifact) is available now via --recovery-attest."
        ),
    )
    parser.add_argument(
        "--recovery-gateways",
        default=None,
        help=(
            "Comma-separated entity_id list to selectively target specific gateways/"
            "firewalls for --recovery-collect (e.g. fw-01,fw-02, or fw-01__vsid_10 for "
            "a CP virtual system). Omit for every admitted device of --recovery-vendor. "
            "An entity_id absent from unified.json is a request-time error -- no device "
            "is contacted."
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
        "--compliance-trend-reconstruct",
        action="store_true",
        help=(
            "Offline retro-fill of compliance_overview.history from PAN configuration snapshots "
            "already in the content-addressed store. PAN baseline rule-pack controls only "
            "(no alignment, no CP, no assignment/waiver or CE.1 check replay); records are "
            "stamped reconstructed=true and never affect the live trend delta. "
            "No network, no credentials. Safe to re-run (idempotent)."
        ),
    )
    parser.add_argument(
        "--console",
        action="store_true",
        help=(
            "CON.1 read-only operator console: an authenticated loopback HTTP service "
            "that serves the existing UI modules and the latest local artifacts, live. "
            "Zero action capability -- no vendor import, no credential, no device contact. "
            "Requires the optional console dependencies: pip install -r requirements-console.txt"
        ),
    )
    parser.add_argument(
        "--console-port",
        type=int,
        default=8765,
        help="Loopback port for --console (default: 8765). The listener always binds 127.0.0.1 only.",
    )
    parser.add_argument(
        "--scheduler-once",
        action="store_true",
        help=(
            "Evaluate the default-disabled RuntimeRoot scheduler policy once, "
            "run due allowlisted read-only workflows, then exit. No polling loop is created."
        ),
    )
    return parser


def validate_modes(args, parser):
    maintenance_modes = sum(bool(value) for value in (
        args.repository_privacy_check,
        args.storage_analyze,
        args.storage_deduplicate,
        args.persistent_secret_material_check,
        args.restore_readiness_check,
        args.ha_readiness_check,
        args.recovery_store_check,
        args.recovery_validate,
        args.compliance_trend_reconstruct,
    ))
    if maintenance_modes > 1:
        parser.error("Choose only one repository/storage maintenance mode")
    if args.repository_privacy_check and args.apply:
        parser.error("--apply is not valid with --repository-privacy-check")
    if args.repository_privacy_check and (
        args.cp_config_probe or args.cp_config_collect or args.render_only or args.only != "all"
        or args.recovery_collect or args.recovery_attest
    ):
        parser.error("--repository-privacy-check cannot be combined with collection/render modes")
    if args.persistent_secret_material_check and args.apply:
        parser.error("--apply is not valid with --persistent-secret-material-check")
    if args.persistent_secret_material_check and (
        args.cp_config_probe or args.cp_config_collect or args.render_only or args.only != "all"
        or args.recovery_collect or args.recovery_attest
    ):
        parser.error("--persistent-secret-material-check cannot be combined with collection/render modes")
    if args.restore_readiness_check and args.apply:
        parser.error("--apply is not valid with --restore-readiness-check")
    if args.restore_readiness_check and (
        args.cp_config_probe or args.cp_config_collect or args.render_only or args.only != "all"
        or args.recovery_collect or args.recovery_attest
    ):
        parser.error("--restore-readiness-check cannot be combined with collection/render modes")
    if args.ha_readiness_check and args.apply:
        parser.error("--apply is not valid with --ha-readiness-check")
    if args.ha_readiness_check and (
        args.cp_config_probe or args.cp_config_collect or args.render_only or args.only != "all"
        or args.recovery_collect or args.recovery_attest
    ):
        parser.error("--ha-readiness-check cannot be combined with collection/render modes")
    if args.recovery_store_check and args.apply:
        parser.error("--apply is not valid with --recovery-store-check")
    if args.recovery_store_check and (
        args.cp_config_probe or args.cp_config_collect or args.render_only or args.only != "all"
        or args.recovery_collect or args.recovery_attest
    ):
        parser.error("--recovery-store-check cannot be combined with collection/render modes")
    if args.recovery_validate and args.apply:
        parser.error("--apply is not valid with --recovery-validate")
    if args.recovery_validate and (
        args.cp_config_probe or args.cp_config_collect or args.render_only or args.only != "all"
        or args.recovery_collect or args.recovery_attest
    ):
        parser.error("--recovery-validate cannot be combined with collection/render modes")

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
    if args.recovery_collect and not args.recovery_vendor:
        parser.error("--recovery-collect requires --recovery-vendor")
    if args.recovery_collect and args.recovery_attest:
        parser.error("--recovery-collect and --recovery-attest cannot be combined")
    if args.recovery_gateways and not (args.recovery_collect or args.recovery_attest):
        parser.error("--recovery-gateways is only valid with --recovery-collect or --recovery-attest")
    if args.recovery_attest and args.recovery_vendor and args.recovery_vendor != "checkpoint":
        parser.error("--recovery-attest is Check Point only (omit --recovery-vendor, or set it to 'checkpoint')")
    if (args.recovery_collect or args.recovery_attest) and (
        args.cp_config_probe or args.cp_config_collect or args.render_only
        or args.only != "all" or args.storage_analyze or args.storage_deduplicate or args.apply
        or args.compliance_trend_reconstruct
    ):
        parser.error("--recovery-collect / --recovery-attest cannot be combined with other collection/render/storage modes")
    if args.scheduler_once and (
        args.repository_privacy_check
        or args.storage_analyze
        or args.storage_deduplicate
        or args.persistent_secret_material_check
        or args.restore_readiness_check
        or args.ha_readiness_check
        or args.recovery_store_check
        or args.recovery_validate
        or args.recovery_collect
        or args.recovery_attest
        or args.apply
        or args.cp_config_probe
        or args.cp_config_collect
        or args.render_only
        or args.compliance_trend_reconstruct
        or args.only != "all"
    ):
        parser.error("--scheduler-once cannot be combined with collection, render, or maintenance modes")
    if args.compliance_trend_reconstruct and (
        args.cp_config_probe or args.cp_config_collect or args.render_only or args.apply or args.only != "all"
        or args.recovery_collect or args.recovery_attest
    ):
        parser.error("--compliance-trend-reconstruct cannot be combined with collection/render modes")
    if args.console_port != 8765 and not args.console:
        parser.error("--console-port is only valid with --console")
    if args.console and (
        args.repository_privacy_check
        or args.storage_analyze
        or args.storage_deduplicate
        or args.apply
        or args.persistent_secret_material_check
        or args.restore_readiness_check
        or args.ha_readiness_check
        or args.recovery_store_check
        or args.recovery_validate
        or args.recovery_collect
        or args.recovery_attest
        or args.cp_config_probe
        or args.cp_config_collect
        or args.render_only
        or args.compliance_trend_reconstruct
        or args.scheduler_once
        or args.only != "all"
    ):
        parser.error("--console cannot be combined with collection, render, or maintenance modes")


def dispatch(args, parser, *, runtime_services=None, provenance="manual", admission_run_context=None):
    """The linear mode sequence, same precedence order as the pre-split
    ``main()`` (AC-2). Each ``if`` body is one call to a workflow entrypoint.
    """
    ctx = ApplicationContext(
        args=args,
        parser=parser,
        provenance=provenance,
        admission_run_context=admission_run_context,
    )

    # --- Phase A: CON.1 C1-8 fail-closed preflight, before anything else ----
    if args.console:
        from console.server import ConsoleDependencyError, console_dependency_preflight
        try:
            console_dependency_preflight()
        except ConsoleDependencyError as exc:
            parser.error(str(exc))

    # --- Phase B: pre-runtime maintenance (offline, no RuntimeRoot) ---------
    if args.repository_privacy_check:
        return maintenance_wf.repository_privacy_check(ctx)
    if args.storage_analyze:
        return maintenance_wf.storage_analyze(ctx)
    if args.storage_deduplicate:
        return maintenance_wf.storage_deduplicate(ctx)

    # --- Phase C: shared runtime foundation --------------------------------
    ctx.support_bundle_output_root = (
        Path(args.support_bundle_output_dir).expanduser() if args.support_bundle_output_dir else None
    )
    ctx.runtime_paths = services.build_runtime_foundation(args, parser)

    if args.console:
        from console.server import run_console
        # CON.2: the console needs the same RuntimeCollectionServices a
        # collection-mode invocation builds, held for the process lifetime
        # so job admission state is consistent across every console job.
        ctx.services = services.build_collection_services(
            args, ctx.runtime_paths, runtime_services, parser
        )
        run_console(runtime_paths=ctx.runtime_paths, port=args.console_port, services=ctx.services)
        return None

    # --- Phase D: single-purpose modes -----------------------------------
    if args.persistent_secret_material_check:
        return maintenance_wf.persistent_secret_material_check(ctx)
    if args.restore_readiness_check:
        return recovery_wf.restore_readiness_check(ctx)
    if args.ha_readiness_check:
        from application.workflows import failover as failover_wf
        return failover_wf.ha_readiness_check(ctx)
    if args.recovery_store_check:
        return recovery_wf.recovery_store_check(ctx)
    if args.recovery_validate:
        return recovery_wf.recovery_validate(ctx)
    if args.compliance_trend_reconstruct:
        return maintenance_wf.compliance_trend_reconstruct(ctx)

    ctx.services = services.build_collection_services(
        args, ctx.runtime_paths, runtime_services, parser
    )

    if args.scheduler_once:
        return maintenance_wf.scheduler_once(ctx)
    if args.recovery_collect:
        return recovery_wf.recovery_collect(ctx)
    if args.recovery_attest:
        return recovery_wf.recovery_attest(ctx)
    if args.cp_config_probe:
        return checkpoint_wf.cp_config_probe(ctx)
    if args.cp_config_collect:
        return checkpoint_wf.cp_config_collect(ctx)
    if args.render_only:
        return maintenance_wf.render_only(ctx)

    # --- Phase E: full staged integration checkpoint / --only partials ----
    return checkpoint_wf.integration_checkpoint(ctx)


def run(argv=None, *, runtime_services=None, provenance="manual", admission_run_context=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    validate_modes(args, parser)
    return dispatch(
        args,
        parser,
        runtime_services=runtime_services,
        provenance=provenance,
        admission_run_context=admission_run_context,
    )
