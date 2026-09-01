"""SecurityExpert CLI entrypoint.

Thin CLI/bootstrap layer. ``main()`` delegates to ``application.cli.run``; the
argument surface, mutually-exclusive-mode validation, the runtime/logging/
evidence-backend bootstrap and every CLI mode body live in the ``application/``
package (docs/history/phase/CODEBASE_MODULARIZATION_BACKEND.md).

The names re-exported below are the stable surface the existing test suite
imports from ``main`` (audit finding F4, plus ``_cp_stage_cooldown`` /
``_run_scheduler_once``). ``getpass`` / ``sys`` / ``time`` stay imported here so
tests can patch ``main.<module>.<attr>`` — those module objects are shared with
the ``application`` package.
"""
import getpass  # noqa: F401  re-exported: tests patch main.getpass.getpass
import sys  # noqa: F401  re-exported: tests patch main.sys.stdin
import time  # noqa: F401  re-exported: tests patch main.time.sleep

from config import Config  # noqa: F401  re-exported: tests assert main.Config is config.Config
from utils.logger import info, register_sensitive_value  # noqa: F401  re-exported

from application.cli import run as _run
from application.services import (  # noqa: F401
    _bootstrap_gaps,
    _build_runtime_config,
    _prompt_management_endpoint,
    _require_bootstrap,
)
from application.workflows.checkpoint import _cp_stage_cooldown  # noqa: F401
from application.workflows.maintenance import (  # noqa: F401
    _run_scheduler_once,
    _scheduler_workflow_argv,
)
from application.workflows.recovery import _load_recovery_attestations  # noqa: F401


###############################################
# MAIN
###############################################
def main(argv=None, *, runtime_services=None, provenance="manual", admission_run_context=None):
    return _run(
        argv,
        runtime_services=runtime_services,
        provenance=provenance,
        admission_run_context=admission_run_context,
    )


###############################################
# ENTRY
###############################################
if __name__ == "__main__":
    main()
