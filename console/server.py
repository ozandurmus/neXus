"""CON.1 C1-8 — fail-closed startup preflight, then the loopback listener.

Same pattern ``DEV.3.3`` established for the PostgreSQL backends
(``utils.coordinator_backend._psycopg``): the optional dependency is imported
inside a function, at first use, never at module load time, so ``console/``
stays unreachable from every other mode and the absence of
``requirements-console.txt`` fails clean with an actionable message instead
of a traceback.
"""
from __future__ import annotations

from console.auth import generate_launch_token

CONSOLE_MISSING_DEPENDENCY_MESSAGE = (
    "--console requires the optional console dependencies: "
    "pip install -r requirements-console.txt"
)


class ConsoleDependencyError(Exception):
    """Raised when ``requirements-console.txt`` is not installed (C1-8)."""


def console_dependency_preflight():
    """Import ``uvicorn`` (and transitively ``fastapi``, via ``console.app``)
    or raise :class:`ConsoleDependencyError`. Called by ``application.cli``
    before anything else runs for ``--console`` (C1-8), and again here at the
    top of :func:`run_console` so this module is safe to call directly too.
    """
    try:
        import uvicorn
    except ImportError as exc:  # pragma: no cover - exercised only without the driver
        raise ConsoleDependencyError(CONSOLE_MISSING_DEPENDENCY_MESSAGE) from exc
    return uvicorn


def run_console(*, runtime_paths, port: int, services) -> None:
    """Start the loopback console listener and block until interrupted.

    Privacy invariant 3: the bind address is ``127.0.0.1`` only, and is not
    configurable by flag or environment in this phase.

    CON.2: ``services`` (a ``RuntimeCollectionServices``, built once by
    ``application.cli.dispatch`` exactly as it is for the scheduler) is held
    for this process's lifetime and passed to every job the runner executes,
    so the in-process coordinator's admission state (active jobs, budgets)
    is consistent across every console-triggered job, the same continuity
    the scheduler already relies on across its own dispatch loop.
    """
    uvicorn = console_dependency_preflight()
    from console.app import create_app  # deferred: pulls in fastapi, same reason as uvicorn above
    from console.jobs import ConsoleJobStore
    from console.runner import ConsoleJobRunner

    host = "127.0.0.1"
    bound_origin = f"http://{host}:{port}"
    launch_token = generate_launch_token()

    job_store = ConsoleJobStore(runtime_paths.data_root)
    # C2-5: a job left ``running`` by a prior process is a crash, not a
    # zombie -- sweep before the runner starts picking up new work.
    job_store.sweep_orphaned_running()
    runner = ConsoleJobRunner(job_store=job_store, runtime_paths=runtime_paths, services=services)
    runner.start()

    app = create_app(
        runtime_paths=runtime_paths,
        launch_token=launch_token,
        bound_origin=bound_origin,
        job_store=job_store,
        runner=runner,
    )

    # C1-5: exactly one line, to stdout, containing the fragment token. Never
    # logged through utils.logger (which would also be redacted, but the
    # point is this line is the *intended* one-time disclosure channel).
    print(f"Operator console: {bound_origin}/#t={launch_token}")

    uvicorn.run(app, host=host, port=port, log_level="warning")
