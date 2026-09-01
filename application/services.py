"""Runtime foundation, bootstrap-prerequisite gate and shared I/O helpers.

Everything ``main()`` did between "arguments are valid" and "hand off to a mode"
(audit Phase C), plus the F6 top-level helpers whose natural owner is this
shared layer. Loaded on every invocation, so it imports no vendor / transport /
heavy-parser module at load time (AC-3); the coordinator and evidence-backend
imports stay lazy and in-function exactly as they were in ``main.py``.
"""
from __future__ import annotations

import getpass
import json
import sys
from pathlib import Path

from config import Config
from utils.logger import (
    configure_log_root,
    info,
    principal_fingerprint,
    register_sensitive_value,
)
from utils.runtime_config_source import RuntimeConfigError, resolve_value
from utils.runtime_paths import RuntimePathError, resolve_runtime_paths


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


# ---------------------------------------------------------------------------
# Clean-baseline bootstrap: partial/dev modes reuse artifacts produced by a
# previous run. On a fresh runtime they must fail fast here with actionable
# guidance, before credential prompts or a collector, instead of a deep
# traceback.
# ---------------------------------------------------------------------------
_ARTIFACT_PRODUCERS = {
    "cp.json": "py -B main.py   (or --only cp)",
    "cp_telemetry.json": "py -B main.py   (or --only cp)",
    "vsx.json": "py -B main.py   (or --only vsx)",
    "panorama_runtime.json": "py -B main.py   (or --only panorama)",
    "unified.json": "py -B main.py   (full checkpoint; also written by --only cp / --only vsx / --only pan-config)",
    "pan_config_telemetry.json": "py -B main.py   (or --only pan-config)",
    "cp_config_telemetry.json": "py -B main.py   (or --cp-config-collect --cp-config-stage all)",
}

# Artifacts each mode consumes from a *previous* run — not the ones it produces
# itself. A full `--only all` checkpoint has no prerequisites.
_MODE_PREREQUISITES = {
    "render-only": ("unified.json",),
    "restore-readiness-check": ("unified.json",),
    "ha-readiness-check": ("unified.json",),
    "recovery-collect": ("unified.json",),
    "recovery-attest": ("unified.json",),
    "cp-config-probe": ("cp_telemetry.json", "cp.json", "vsx.json"),
    "cp-config-collect": ("cp.json", "vsx.json"),
    "cp": ("vsx.json", "panorama_runtime.json"),
    "vsx": ("cp.json", "panorama_runtime.json"),
    "pan-config": ("unified.json",),
}

_BOOTSTRAP_SEQUENCE = (
    "Establish a baseline, then re-run this mode:",
    "  py -B main.py                     full checkpoint - produces every artifact",
    "or build the inventory planes individually:",
    "  py -B main.py --only cp",
    "  py -B main.py --only vsx",
    "  py -B main.py --only panorama",
)


def _bootstrap_gaps(mode, output_root):
    """Return [(artifact, producer_hint)] for a mode's missing prior artifacts."""
    output_root = Path(output_root)
    return [
        (name, _ARTIFACT_PRODUCERS.get(name, "a previous run"))
        for name in _MODE_PREREQUISITES.get(mode, ())
        if not (output_root / name).exists()
    ]


def _require_bootstrap(mode, output_root):
    """Exit 2 with actionable guidance if `mode` is missing prior artifacts."""
    gaps = _bootstrap_gaps(mode, output_root)
    if not gaps:
        return
    lines = [
        "",
        f">>> BOOTSTRAP REQUIRED: '{mode}' reuses artifacts from a previous run; "
        "this runtime has none yet.",
        "",
        "    Missing:",
        *(f"      {name:<24} produced by:  {producer}" for name, producer in gaps),
        "",
        *(f"    {line}" for line in _BOOTSTRAP_SEQUENCE),
        "",
    ]
    print("\n".join(lines), file=sys.stderr)
    raise SystemExit(2)


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


def build_runtime_foundation(args, parser):
    """Audit Phase C: resolve the external runtime root, point logging at it,
    and run the DEV.3.3 evidence-backend preflight. Runs for every mode that
    clears the offline Phase-B group. Returns the resolved RuntimePaths.
    """
    try:
        runtime_paths = resolve_runtime_paths(args.runtime_root)
    except RuntimePathError as exc:
        parser.error(str(exc))
    configure_log_root(runtime_paths.logs_root)
    print(
        ">>> RUNTIME PATH FOUNDATION READY "
        f"(runtime_root={runtime_paths.runtime_root} normal_runtime=external history_cas=legacy_pending)"
    )

    # SECURITYEXPERT_EVIDENCE_BACKEND=postgres opts into the DEV.3.3 shared
    # evidence store; default 'filesystem' is the unchanged per-container path
    # and never touches the Postgres driver.
    from utils.evidence_backend import (
        EvidenceBackendError,
        active_evidence_backend_kind,
        verify_evidence_backend_ready,
    )
    try:
        verify_evidence_backend_ready()
    except EvidenceBackendError as exc:
        parser.error(str(exc))
    if active_evidence_backend_kind() != "filesystem":
        print(f">>> EVIDENCE BACKEND: {active_evidence_backend_kind()} (DEV.3.3)")
    return runtime_paths


def build_collection_services(args, runtime_paths, runtime_services, parser):
    """Audit lines 1056-1080: coordinator-backend selection and scheduler-policy
    load. Called only by the workflows that touch a collector (checkpoint,
    recovery, scheduler-once), so ``utils.collection_executor`` stays off the
    maintenance-only path exactly as before.
    """
    from utils.collection_executor import (
        CollectionCoordinator,
        RuntimeCollectionServices,
        SchedulerPolicyError,
        load_scheduler_policy,
        select_coordinator_backend,
    )
    from utils.coordinator_backend import CoordinatorBackendError

    if runtime_services is not None:
        services = runtime_services
    else:
        # SECURITYEXPERT_COORDINATOR_BACKEND=postgres opts into the DEV.3.2
        # cross-process backend; default 'memory' is the unchanged 0.6.1C path.
        try:
            backend = select_coordinator_backend(data_root=runtime_paths.data_root)
        except CoordinatorBackendError as exc:
            parser.error(str(exc))
        services = RuntimeCollectionServices(coordinator=CollectionCoordinator(backend))
    try:
        services.scheduler_policy = load_scheduler_policy(runtime_paths.data_root)
    except SchedulerPolicyError as exc:
        parser.error(str(exc))
    return services


def make_admitted(ctx):
    """The audit's ``_admitted`` closure (main.py 1087-1098), rebound onto an
    ApplicationContext instead of ``main()`` locals."""
    def _admitted(vendor, workflow_scope, endpoint, operation, *, run_context=None):
        from utils.collection_executor import execute_admitted_collection

        if not endpoint:
            raise RuntimeError(f"{workflow_scope} management endpoint is unavailable")
        return execute_admitted_collection(
            ctx.services,
            vendor=vendor,
            workflow_scope=workflow_scope,
            canonical_ids=[str(endpoint)],
            provenance=ctx.provenance,
            operation=operation,
            run_context=run_context or ctx.admission_run_context,
        )

    return _admitted


def make_runtime_config(ctx):
    """The audit's ``_runtime_config`` closure (main.py 1100-1110): build the
    process Config with a clean CLI exit for a non-interactive misconfiguration
    instead of an uncaught traceback."""
    def _runtime_config(*, require_cp, require_panorama):
        try:
            return _build_runtime_config(
                require_cp=require_cp,
                require_panorama=require_panorama,
                runtime_paths=ctx.runtime_paths,
            )
        except RuntimeConfigError as exc:
            ctx.parser.error(str(exc))

    return _runtime_config
