"""CON.2 C2-2/C2-5/C2-7 — the single-worker console job executor.

Runs at most one job at a time, FIFO (C2-7) — a ceiling, not the admission
mechanism: every execution still goes through ``main.main()`` ->
``execute_admitted_collection`` -> ``CollectionCoordinator``, so a job that
collides with a concurrent CLI or scheduled run is coalesced or refused by
the existing, unchanged admission logic. This module never calls a
collector, a vendor module, or ``run_recovery_collection`` directly (AC-2) —
every device interaction happens inside ``main.main()``, exactly as the
scheduler's ``_evaluate_and_dispatch_due_workflows`` already does.

**One narrow, named exception (M9):** the single ``device_enrollment_identity_probe``
job type (`console/registry.py`) has no CLI flag and no argv to construct —
`docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md` §4.1 requires it to run
"through the existing runner and admission coordinator" literally, not
through ``main.main()``. ``_execute_enrollment_probe`` below calls
``utils.collection_executor.execute_admitted_collection`` directly, with a
**lazy, function-local** import of ``utils.pre_enrollment_identity_probe``
(the same lazy-import pattern this module already uses for ``import main``),
so this module's own static import graph stays vendor-free —
``tests/test_con1_operator_console_read_only.py::test_ac8_console_app_imports_no_vendor_module``
still passes unmodified. This is the only job type this module ever executes
without going through ``main.main()``.
"""
from __future__ import annotations

import queue
import threading

from console.jobs import ConsoleJobStore
from console.registry import JobType, get_job_type
from console.registry_targets import DEVICE_ID_TARGET_MODE, resolve_registry_targets
from utils.action_taxonomy import console_refusal

#: M9 — the one job type `_execute` dispatches outside `main.main()` (see
#: module docstring). Kept as a named constant rather than a literal
#: comparison so the one branch that skips `_build_argv` is easy to find.
_ENROLLMENT_PROBE_JOB_TYPE_ID = "device_enrollment_identity_probe"


def _build_argv(job_type: JobType, runtime_root, targets: list[str]) -> list[str]:
    """C2-1's two explicit read modes (``recovery-attest``, ``render-only``)
    are not scheduler workflows and never go through ``workflow_argv`` —
    everything else shares that one path (C2-2)."""
    from utils.collection_executor import workflow_argv

    workflow = job_type.workflow
    if workflow == "recovery-attest":
        argv = ["--runtime-root", str(runtime_root), "--recovery-attest"]
        if targets:
            argv += ["--recovery-gateways", ",".join(targets)]
        return argv
    if workflow == "render-only":
        return ["--runtime-root", str(runtime_root), "--render-only"]
    return workflow_argv(workflow, runtime_root, targets=targets)


class ConsoleJobRunner:
    def __init__(self, *, job_store: ConsoleJobStore, runtime_paths, services) -> None:
        self._job_store = job_store
        self._runtime_paths = runtime_paths
        self._services = services
        self._queue: "queue.Queue[str]" = queue.Queue()
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        #: M9 — the raw (normalized) endpoint/port for a queued enrollment
        #: probe, keyed by job_id. Deliberately in-memory only, never
        #: persisted: `console/jobs.py::JobRecord` has no field for an
        #: endpoint by construction (its own forbidden-field list), so the
        #: only durable trace of "what was probed" is the opaque intent-
        #: binding token `console/app.py` stores in the job's own `targets`.
        #: A console restart between probe submission and execution loses a
        #: still-queued probe's pending intent — the same fate an ordinary
        #: `queued` record already has today (only `running` jobs are swept
        #: at startup); the operator simply resubmits.
        self._pending_probe_endpoints: dict[str, tuple[str, int | None]] = {}

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run_loop, name="console-job-runner", daemon=True)
        self._thread.start()

    def enqueue(self, job_id: str) -> None:
        self._queue.put(job_id)

    def enqueue_probe(self, job_id: str, *, endpoint: str, port: int | None) -> None:
        """M9 — the one enrollment-probe-specific enqueue path. Registers the
        pending intent before the job becomes visible to the worker thread,
        so `_execute_enrollment_probe` can never observe a queued job with no
        registered intent from a race (the caller enqueues only after this
        returns)."""
        self._pending_probe_endpoints[job_id] = (endpoint, port)
        self.enqueue(job_id)

    def _run_loop(self) -> None:
        while not self._stop.is_set():
            try:
                job_id = self._queue.get(timeout=0.5)
            except queue.Empty:
                continue
            try:
                self._execute(job_id)
            except Exception as exc:  # runner-level defense: a job must never crash the loop
                self._job_store.mark_terminal(
                    job_id, state="failed", error_code="runner_exception", error_summary=str(exc)
                )

    def _execute(self, job_id: str) -> None:
        record = self._job_store.get(job_id)
        if record is None or record.state != "queued":
            return  # already handled (e.g. a second enqueue of the same idempotent job)

        job_type = get_job_type(record.job_type)
        if job_type is None:
            self._job_store.mark_terminal(job_id, state="failed", error_code="unknown_job_type")
            return
        refusal = console_refusal(job_type.action_class)
        if refusal is not None:
            # Defense in depth: the route already refuses this at POST time (C2-6).
            self._job_store.mark_terminal(job_id, state="blocked", error_code=refusal)
            return

        if job_type.id == _ENROLLMENT_PROBE_JOB_TYPE_ID:
            self._execute_enrollment_probe(job_id)
            return

        if job_type.target_mode == DEVICE_ID_TARGET_MODE and record.targets:
            # M6 (registry_keyed_job_targets, Option D, AC-ST-4-equivalent):
            # re-read registry eligibility immediately before execution, not
            # just at admission -- a target eligible when the job was
            # queued may have been disabled/retired since. This call is the
            # only path from a queued job to workflow argv construction, so
            # a refusal here provably happens before `_build_argv`/
            # `main.main()` is ever reached.
            target_refusal = resolve_registry_targets(record.targets, data_root=self._runtime_paths.data_root)
            if target_refusal is not None:
                self._job_store.mark_terminal(
                    job_id, state="blocked",
                    error_code=target_refusal.reason, error_summary=target_refusal.detail,
                )
                return

        self._job_store.mark_running(job_id)

        import main  # entry-ward re-invocation; kept patchable as main.main, same pattern the scheduler uses
        from utils.coordinator_backend import CollectionAdmissionError, Provenance
        from utils.run_context import RunContext

        argv = _build_argv(job_type, self._runtime_paths.runtime_root, record.targets)
        ctx = RunContext.create(
            data_root=self._runtime_paths.data_root,
            output_root=self._runtime_paths.output_root,
        )
        try:
            main.main(
                argv,
                runtime_services=self._services,
                provenance=Provenance.CONSOLE.value,
                admission_run_context=ctx,
            )
        except CollectionAdmissionError as exc:
            self._handle_admission_error(job_id, ctx, exc)
            return
        except BaseException as exc:
            ctx.write_manifest(status="failed", console_result=f"failed_{type(exc).__name__.lower()}")
            self._job_store.mark_terminal(
                job_id, state="failed", run_id=ctx.run_id,
                error_code=type(exc).__name__, error_summary=str(exc),
            )
            return
        ctx.write_manifest(status="completed", console_result="completed")
        self._job_store.mark_terminal(
            job_id, state="succeeded", run_id=ctx.run_id, coordinator_decision="admitted"
        )

    def _execute_enrollment_probe(self, job_id: str) -> None:
        """M9 — see the module docstring's "one narrow, named exception."
        Never touches `_build_argv`/`main.main()`; goes directly through
        `utils.collection_executor.execute_admitted_collection` so the
        existing runner/admission-coordinator invariants still apply."""
        intent = self._pending_probe_endpoints.pop(job_id, None)
        if intent is None:
            self._job_store.mark_terminal(
                job_id, state="failed", error_code="probe_intent_unavailable",
                error_summary="the pending probe intent was lost (e.g. a console restart) -- resubmit the probe",
            )
            return
        endpoint, port = intent

        self._job_store.mark_running(job_id)

        from utils.collection_executor import execute_admitted_collection
        from utils.coordinator_backend import CollectionAdmissionError, Provenance
        from utils.pre_enrollment_identity_probe import (
            POSITIVE_IDENTITY,
            run_pre_enrollment_identity_probe,
        )
        from utils.run_context import RunContext

        ctx = RunContext.create(
            data_root=self._runtime_paths.data_root,
            output_root=self._runtime_paths.output_root,
        )

        def _operation():
            return run_pre_enrollment_identity_probe(
                endpoint=endpoint,
                port=port,
                resolve_credentials=self._resolve_probe_credentials,
            )

        try:
            outcome = execute_admitted_collection(
                self._services,
                vendor="checkpoint",
                workflow_scope="cp_pre_enrollment_identity_probe",
                canonical_ids=[endpoint],
                provenance=Provenance.CONSOLE.value,
                operation=_operation,
                run_context=ctx,
            )
        except CollectionAdmissionError as exc:
            self._handle_admission_error(job_id, ctx, exc)
            return
        except BaseException as exc:
            ctx.write_manifest(status="failed", console_result=f"failed_{type(exc).__name__.lower()}")
            self._job_store.mark_terminal(
                job_id, state="failed", run_id=ctx.run_id,
                error_code=type(exc).__name__, error_summary=str(exc),
            )
            return

        ctx.write_manifest(status="completed", console_result="completed")
        # C2-6-equivalent posture for this job type: a negative-evidence
        # outcome (untrusted endpoint, rejected identity gate, no serial, a
        # collector error) is still a *successful*, read-only job run —
        # exactly how `utils/first_contact_producer.py`'s own CLI caller
        # treats its refusal tokens. `outcome_counts` carries the sanitized
        # status; `preview` is populated only for a positive result.
        self._job_store.mark_terminal(
            job_id, state="succeeded", run_id=ctx.run_id, coordinator_decision="admitted",
            outcome_counts={"probe_status": outcome.status, "probe_reason": outcome.reason},
            preview=outcome.preview.to_dict() if outcome.status == POSITIVE_IDENTITY else None,
        )

    def _resolve_probe_credentials(self) -> tuple[str, str]:
        """The console-side equivalent of
        `application/workflows/first_contact.py::_resolve_credentials` —
        same DEV.2.1/DEV.2.2 runtime source, same "called only after trust
        succeeds" contract (enforced by
        `utils.pre_enrollment_identity_probe.run_pre_enrollment_identity_probe`,
        not here). Deliberately does not use
        `application.services.make_runtime_config`: that helper calls
        `ctx.parser.error(...)` (an argparse CLI exit) on a misconfiguration,
        which has no meaning inside a background job thread — this calls the
        lower-level builder directly and lets a real failure propagate as an
        ordinary exception, caught the same way `_execute_enrollment_probe`
        already catches every other collector failure.
        """
        import os

        from application.services import _build_runtime_config
        from utils.logger import register_sensitive_value, user_fingerprint

        cfg = _build_runtime_config(
            require_cp=True, require_panorama=False, runtime_paths=self._runtime_paths
        )
        try:
            username = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_USERNAME") or cfg.auth.principal
            secret = os.getenv("SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD") or cfg.auth.secret
            if not username or not secret:
                raise RuntimeError("CP configuration credentials are unavailable")
            register_sensitive_value(username, f"[USER:{user_fingerprint(username)}]")
            register_sensitive_value(secret, "[AUTH_SECRET:REDACTED]")
            return username, secret
        finally:
            cfg.clear_credentials()

    def _handle_admission_error(self, job_id: str, ctx, exc) -> None:
        decision = exc.decision.value
        if decision == "coalesced":
            active = self._services.coordinator.wait_for_terminal(exc.job.coalesced_to or "", timeout=300)
            if active is not None and active.status == "completed":
                ctx.write_manifest(status="completed", console_result="coalesced_completed")
                self._job_store.mark_terminal(
                    job_id, state="succeeded", run_id=ctx.run_id, coordinator_decision="coalesced"
                )
                return
            terminal = active.status if active is not None else "unavailable"
            ctx.write_manifest(status="failed", console_result=f"coalesced_{terminal}")
            self._job_store.mark_terminal(
                job_id, state="failed", run_id=ctx.run_id, coordinator_decision="coalesced",
                error_code="coalesced_incomplete", error_summary=f"coalesced job ended in {terminal}",
            )
            return
        ctx.write_manifest(status="failed", console_result=decision)
        self._job_store.mark_terminal(
            job_id, state="failed", run_id=ctx.run_id, coordinator_decision=decision,
            error_code=decision, error_summary=str(exc),
        )
