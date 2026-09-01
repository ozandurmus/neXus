"""CON.2 C2-2/C2-5/C2-7 — the single-worker console job executor.

Runs at most one job at a time, FIFO (C2-7) — a ceiling, not the admission
mechanism: every execution still goes through ``main.main()`` ->
``execute_admitted_collection`` -> ``CollectionCoordinator``, so a job that
collides with a concurrent CLI or scheduled run is coalesced or refused by
the existing, unchanged admission logic. This module never calls a
collector, a vendor module, or ``run_recovery_collection`` directly (AC-2) —
every device interaction happens inside ``main.main()``, exactly as the
scheduler's ``_evaluate_and_dispatch_due_workflows`` already does.
"""
from __future__ import annotations

import queue
import threading

from console.jobs import ConsoleJobStore
from console.registry import JobType, get_job_type
from utils.action_taxonomy import console_refusal


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

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run_loop, name="console-job-runner", daemon=True)
        self._thread.start()

    def enqueue(self, job_id: str) -> None:
        self._queue.put(job_id)

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
