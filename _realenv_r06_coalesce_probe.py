"""
0.6.1C R06 — Same-Process Coalescing Probe  (LOCAL USE)
========================================================
Bounded in-process coalescing validation with two explicit modes.

Purpose
-------
The default synthetic mode is an automated preflight only.  ``--real-env``
executes the existing read-only CP collector as the first admitted operation
and submits the overlapping second request through the same process-lifetime
coordinator.  Only the real-environment mode can satisfy the human R06 gate.

How it works
------------
1. A single shared RuntimeCollectionServices instance is created (same object
   that main.py owns in a normal run).
2. Thread-1 submits a request and is ADMITTED.  Its operation blocks on an
    Event until thread-2 has submitted its request, then invokes the supplied
    operation exactly once.
3. Thread-2 submits a second request to the same PROBE_ENDPOINT while thread-1
   is still active.  The coordinator must return COALESCED.  Thread-2's
   operation lambda is guarded; any call to it is recorded as an error.
4. Thread-2 signals thread-1 to complete.
5. Both threads rejoin deterministically within the bounded timeout.
6. A value-free SAFE SUMMARY is printed; the probe exits 0 on PASS, 1 on FAIL.

Safety invariants
-----------------
- Synthetic mode uses no network or credentials and cannot close R06.
- Real mode accepts endpoint/credentials interactively and never prints them
    in the final SAFE SUMMARY.
- No daemon threads or background loops are created.
- The coordinator budget and cooldown settings are unchanged (this is a
  checkpoint/cp scope probe; budget capacity == 1).

Usage
-----
    py -B _realenv_r06_coalesce_probe.py
    py -B _realenv_r06_coalesce_probe.py --real-env --runtime-root <approved-path>

Only the value-free SAFE SUMMARY output is suitable for sharing or committing
to the real-environment report.  Do not share terminal logs that may contain
runtime paths.
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
from pathlib import Path
from typing import Any, Callable

from utils.collection_executor import (
    CollectionAdmissionError,
    CoordinatorDecision,
    Provenance,
    RuntimeCollectionServices,
    execute_admitted_collection,
)

# Synthetic probe endpoint — never reaches the network.
_PROBE_ENDPOINT = "CP-MGMT-PROBE-R06"
_PROBE_TIMEOUT = 10.0   # seconds; bounded overlap handshake
_COLLECT_TIMEOUT = 900.0  # seconds; max time for a real collector run


def _run_probe(
    operation: Callable[[], Any] | None = None,
    *,
    canonical_id: str = _PROBE_ENDPOINT,
    real_environment: bool = False,
) -> dict[str, Any]:
    """Execute one bounded overlap and return a value-free result dict."""
    services = RuntimeCollectionServices()
    supplied_operation = operation or (lambda: {"status": "synthetic_probe_ok"})

    # Synchronisation events
    first_admitted_event = threading.Event()
    second_done_event = threading.Event()

    results: dict[str, Any] = {
        "first_decision": None,
        "first_operation_started": False,
        "first_completed": False,
        "second_decision": None,
        "second_coalesced_to": None,
        "second_operation_called": False,
        "failure_family": None,
    }

    # ------------------------------------------------------------------
    # Thread-1 — first request; blocks until second request is submitted.
    # ------------------------------------------------------------------
    def _first_operation() -> dict[str, Any]:
        """Signal admission, wait for overlap, then run the operation once."""
        # Record admission here — before the long operation — so evaluation
        # is not blocked by the collector's runtime duration.
        results["first_decision"] = CoordinatorDecision.ADMITTED.value
        first_admitted_event.set()
        if not second_done_event.wait(timeout=_PROBE_TIMEOUT):
            raise TimeoutError("bounded_overlap_handshake_timeout")
        results["first_operation_started"] = True
        return supplied_operation()

    def _run_first() -> None:
        try:
            execute_admitted_collection(
                services,
                vendor="checkpoint",
                workflow_scope="cp",
                canonical_ids=[canonical_id],
                provenance=Provenance.SCHEDULED.value,
                operation=_first_operation,
            )
            results["first_decision"] = CoordinatorDecision.ADMITTED.value
            results["first_completed"] = True
        except Exception as exc:  # noqa: BLE001
            results["failure_family"] = f"first_{type(exc).__name__.lower()}"

    # ------------------------------------------------------------------
    # Thread-2 — second request; must see COALESCED, must not call its
    #            operation.
    # ------------------------------------------------------------------
    def _second_operation() -> None:
        """This must never be reached; any invocation is a probe failure."""
        results["second_operation_called"] = True

    def _run_second() -> None:
        # Wait until the first request is genuinely inside its operation so the
        # coordinator holds an active lock on PROBE_ENDPOINT.
        if not first_admitted_event.wait(timeout=_PROBE_TIMEOUT):
            results["failure_family"] = "second_admission_wait_timeout"
            second_done_event.set()
            return
        try:
            execute_admitted_collection(
                services,
                vendor="checkpoint",
                workflow_scope="cp",
                canonical_ids=[canonical_id],
                provenance=Provenance.MANUAL.value,
                operation=_second_operation,
            )
            # Reaching here means the second request was ADMITTED instead of
            # COALESCED — this is a probe failure.
            results["second_decision"] = CoordinatorDecision.ADMITTED.value
        except CollectionAdmissionError as exc:
            results["second_decision"] = exc.decision.value
            results["second_coalesced_to"] = exc.job.coalesced_to
        except Exception as exc:  # noqa: BLE001
            results["failure_family"] = f"second_{type(exc).__name__.lower()}"
        finally:
            second_done_event.set()

    t1 = threading.Thread(target=_run_first, daemon=False, name="r06-first")
    t2 = threading.Thread(target=_run_second, daemon=False, name="r06-second")
    t1.start()
    t2.start()
    # t2 (overlap handshake) completes quickly; t1 waits for the full collector.
    t2.join(timeout=_PROBE_TIMEOUT + 5)
    t1.join(timeout=_COLLECT_TIMEOUT)

    # ------------------------------------------------------------------
    # Evaluate
    # ------------------------------------------------------------------
    first_admitted = results["first_decision"] == CoordinatorDecision.ADMITTED.value
    first_completed = results["first_completed"]
    second_coalesced = results["second_decision"] == CoordinatorDecision.COALESCED.value
    no_second_operation = not results["second_operation_called"]
    no_thread_error = results["failure_family"] is None
    active_after = len(services.coordinator.active_jobs())

    r06_pass = (
        first_admitted
        and results["first_operation_started"]
        and first_completed
        and second_coalesced
        and no_second_operation
        and no_thread_error
        and active_after == 0
    )

    return {
        "build": "0.6.1C-R06-coalesce-probe",
        "evidence_origin": "real_environment" if real_environment else "synthetic_preflight",
        "network_access": real_environment,
        "credentials_used": real_environment,
        "first_request_admitted": first_admitted,
        "first_operation_started": results["first_operation_started"],
        "first_request_completed": first_completed,
        "second_request_decision": results["second_decision"],
        "second_operation_called": results["second_operation_called"],
        "coalesced_to_set": results["second_coalesced_to"] is not None,
        "failure_family": results["failure_family"],
        "active_jobs_after_probe": active_after,
        "total_jobs_recorded": len(services.coordinator.all_jobs()),
        "r06_mechanism_pass": r06_pass,
        "r06_real_env_pass": r06_pass and real_environment,
        "r06_same_process_coordinator": True,
        "r06_no_second_session": no_second_operation,
        "result_summary": "PASS" if r06_pass else "FAIL",
    }


def _run_real_environment(runtime_root: Path) -> dict[str, Any]:
    """Run the existing CP collector once behind the bounded overlap probe."""
    from checkpoint.cp_runner import run_cp
    from main import _build_runtime_config
    from utils.logger import configure_log_root
    from utils.runtime_paths import resolve_runtime_paths

    runtime_paths = resolve_runtime_paths(str(runtime_root))
    configure_log_root(runtime_paths.logs_root)
    cfg = _build_runtime_config(
        require_cp=True,
        require_panorama=False,
        runtime_paths=runtime_paths,
    )
    try:
        return _run_probe(
            lambda: run_cp(cfg, exclude_vsx=True),
            canonical_id=str(cfg.mds_ip),
            real_environment=True,
        )
    finally:
        cfg.clear_credentials()


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="0.6.1C R06 bounded coalescing probe")
    parser.add_argument("--real-env", action="store_true")
    parser.add_argument("--runtime-root", type=Path)
    args = parser.parse_args(argv)
    if args.real_env and args.runtime_root is None:
        parser.error("--real-env requires --runtime-root")
    if args.runtime_root is not None and not args.real_env:
        parser.error("--runtime-root is valid only with --real-env")

    summary = (
        _run_real_environment(args.runtime_root)
        if args.real_env
        else _run_probe()
    )
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    expected_pass = summary["r06_real_env_pass"] if args.real_env else summary["r06_mechanism_pass"]
    if not expected_pass:
        print(
            "\n[R06 FAIL] Same-process coalescing did not behave as required.",
            file=sys.stderr,
        )
        sys.exit(1)
    label = "REAL-ENV PASS" if args.real_env else "SYNTHETIC PREFLIGHT PASS"
    print(f"\n[R06 {label}] Same-process coalescing confirmed; no second session.", file=sys.stderr)
    sys.exit(0)


if __name__ == "__main__":
    main()
