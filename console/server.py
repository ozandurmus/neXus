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


def run_console(*, runtime_paths, port: int) -> None:
    """Start the loopback console listener and block until interrupted.

    Privacy invariant 3: the bind address is ``127.0.0.1`` only, and is not
    configurable by flag or environment in this phase.
    """
    uvicorn = console_dependency_preflight()
    from console.app import create_app  # deferred: pulls in fastapi, same reason as uvicorn above

    host = "127.0.0.1"
    bound_origin = f"http://{host}:{port}"
    launch_token = generate_launch_token()

    app = create_app(runtime_paths=runtime_paths, launch_token=launch_token, bound_origin=bound_origin)

    # C1-5: exactly one line, to stdout, containing the fragment token. Never
    # logged through utils.logger (which would also be redacted, but the
    # point is this line is the *intended* one-time disclosure channel).
    print(f"Operator console: {bound_origin}/#t={launch_token}")

    uvicorn.run(app, host=host, port=port, log_level="warning")
