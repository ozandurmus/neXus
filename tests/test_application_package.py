"""codebase_modularization (backend) — AC-3 / AC-5 / AC-6.

docs/history/phase/CODEBASE_MODULARIZATION_BACKEND.md. Proves the lazy-import
boundary that the pre-split main.py only ever documented (AI_START_HERE.md:
"Vendor/config imports are lazy") is now a tested invariant, and that the
main.main() entry surface the existing suite depends on is unchanged.

Each import-boundary check runs in a fresh subprocess: this repo's test suite
imports vendor modules widely elsewhere, so an in-process `sys.modules` check
would give a false pass once another test file has already loaded them.
"""
from __future__ import annotations

import inspect
import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]

# checkpoint/panorama/configuration are this repo's vendor-collector packages;
# paramiko/lxml/requests are the underlying transport/parser libraries they
# and utils/config_evidence.py depend on.
_FORBIDDEN_PREFIXES = ("checkpoint", "panorama", "configuration", "paramiko", "lxml", "requests")


def _forbidden(modules):
    return sorted(m for m in modules if m.split(".")[0] in _FORBIDDEN_PREFIXES)


def _run(code: str) -> str:
    result = subprocess.run(
        [sys.executable, "-c", code],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr
    return result.stdout


def _new_modules_after_import(*module_names: str):
    code = (
        "import sys, json\n"
        "before = set(sys.modules)\n"
        + "\n".join(f"import {name}" for name in module_names) + "\n"
        "after = set(sys.modules)\n"
        "print(json.dumps(sorted(after - before)))\n"
    )
    return json.loads(_run(code))


# --- AC-3: static import boundary -------------------------------------------

def test_application_top_level_imports_no_vendor_module():
    new = _new_modules_after_import("application.cli", "application.services", "application.context")
    assert _forbidden(new) == []


@pytest.mark.parametrize("module", [
    "application.workflows.maintenance",
    "application.workflows.recovery",
    "application.workflows.checkpoint",
])
def test_workflow_module_imports_no_vendor_module_at_module_scope(module):
    new = _new_modules_after_import(module)
    assert _forbidden(new) == []


def test_no_workflow_module_imports_another_workflow_module():
    for name in ("maintenance", "recovery", "checkpoint"):
        source = (ROOT / "application" / "workflows" / f"{name}.py").read_text(encoding="utf-8")
        for other in ("maintenance", "recovery", "checkpoint"):
            if other == name:
                continue
            assert f"application.workflows.{other}" not in source, (name, other)


def test_main_reexports_every_f4_name():
    import main

    for name in (
        "_require_bootstrap",
        "_build_runtime_config",
        "_bootstrap_gaps",
        "_load_recovery_attestations",
        "_prompt_management_endpoint",
        "_scheduler_workflow_argv",
        "Config",
        # Beyond the contract's original 7: also patched/called directly by
        # existing tests (test_phase0_6_0a4_3_3_2_workflow_and_ha.py,
        # test_phase0_6_1c_1_runtime_scheduler_wiring.py).
        "_cp_stage_cooldown",
        "_run_scheduler_once",
    ):
        assert hasattr(main, name), name


def test_main_py_is_a_thin_entry():
    source = (ROOT / "main.py").read_text(encoding="utf-8")
    lines = [line for line in source.splitlines() if line.strip()]
    assert len(lines) <= 60, f"main.py grew to {len(lines)} non-blank lines"


# --- AC-5: the lazy-import boundary holds at runtime, not just structurally -

def test_repository_privacy_check_run_imports_no_vendor_module():
    code = (
        "import contextlib, io, sys, json\n"
        "import main\n"
        "with contextlib.redirect_stdout(io.StringIO()):\n"
        "    try:\n"
        "        main.main(['--repository-privacy-check'])\n"
        "    except SystemExit:\n"
        "        pass\n"
        "print(json.dumps(sorted(sys.modules)))\n"
    )
    modules = json.loads(_run(code))
    assert _forbidden(modules) == []


# --- AC-6: main() entry signature is frozen ---------------------------------

_FROZEN_SIGNATURE = "(argv=None, *, runtime_services=None, provenance='manual', admission_run_context=None)"


def test_main_entry_signature_is_frozen():
    import main

    assert str(inspect.signature(main.main)) == _FROZEN_SIGNATURE
    assert callable(main.main)
