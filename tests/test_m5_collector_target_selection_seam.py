"""M5 -- collector target-selection seam, `cp-config` only.

Promotes OP.0d's already-validated Check Point `--cp-config-targets`
selector into the shared `utils.collection_executor.workflow_argv()` argv
seam (CON.2 C2-2) used by both the scheduler
(`application.workflows.maintenance._scheduler_workflow_argv`) and the
console job runner (`console/runner.py._build_argv`). No second selector, no
registry target resolution (M6), no device contact -- all boundaries here are
argv construction and mocked/synthetic collector calls.

Frozen parents: `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §9,
`AC-TGT-3`/`AC-TGT-4`/`AC-TGT-5`;
`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §12/§12.1 (`M5`).
"""
from __future__ import annotations

from pathlib import Path

import pytest

from application.cli import build_parser
from utils.collection_executor import (
    UnsupportedTargetSelectionError,
    workflow_argv,
)

pytestmark = pytest.mark.configuration


# --- target-free cp-config stays explicit and plane-wide (AC-TGT-5) ---------

def test_cp_config_argv_target_free_is_unchanged_plane_wide():
    argv = workflow_argv("cp-config", Path("R"), targets=())
    assert argv == ["--runtime-root", "R", "--cp-config-collect", "--cp-config-stage", "all"]
    assert "--cp-config-targets" not in argv


# --- targeted cp-config argv carries only the requested exact entity ids ---

def test_cp_config_argv_targeted_carries_exact_entity_ids_in_order():
    argv = workflow_argv("cp-config", Path("R"), targets=("CL2", "CL1"))
    assert argv == [
        "--runtime-root", "R",
        "--cp-config-collect", "--cp-config-stage", "all",
        "--cp-config-targets", "CL2,CL1",
    ]


def test_cp_config_argv_preserves_opaque_identifier_spelling():
    # Identity law: never cast, strip, pad or otherwise normalize an opaque
    # entity_id.  A leading-zero id must reach argv byte-identical.
    argv = workflow_argv("cp-config", Path("R"), targets=("0026109000729",))
    assert argv[-1] == "0026109000729"


def test_cp_config_argv_round_trips_through_cli_parser():
    # Closes the loop between workflow_argv()'s output and the existing
    # OP.0d CLI surface (application/cli.py) it targets.
    argv = workflow_argv("cp-config", Path("R"), targets=("CL1", "CL2"))
    parser = build_parser()
    args = parser.parse_args(argv[2:])  # drop --runtime-root R (a top-level flag)
    assert args.cp_config_collect is True
    assert args.cp_config_targets == "CL1,CL2"


# --- shared console/scheduler path uses the same argv builder --------------

def test_scheduler_wrapper_matches_workflow_argv_for_cp_config_targets():
    from application.workflows.maintenance import _scheduler_workflow_argv

    class _Row:
        pass

    row = _Row()
    row.workflow = "cp-config"
    row.targets = ("CL1",)
    assert _scheduler_workflow_argv(row, "R") == workflow_argv("cp-config", "R", targets=("CL1",))


def test_console_runner_build_argv_matches_workflow_argv_for_cp_config_targets():
    from console.runner import _build_argv
    from console.registry import get_job_type

    job_type = get_job_type("config_refresh_cp")
    assert job_type.workflow == "cp-config"
    argv = _build_argv(job_type, Path("R"), ["CL1", "CL2"])
    assert argv == workflow_argv("cp-config", Path("R"), targets=["CL1", "CL2"])


# --- unsupported targeted workflows fail closed before contact -------------

@pytest.mark.parametrize("workflow", ["cp", "checkpoint", "vsx", "pan-config"])
def test_unsupported_workflow_with_targets_refused_before_main(workflow):
    with pytest.raises(UnsupportedTargetSelectionError, match="no target-selection seam"):
        workflow_argv(workflow, Path("R"), targets=("SOMETHING",))


@pytest.mark.parametrize("workflow", ["cp", "checkpoint", "vsx", "pan-config"])
def test_unsupported_workflow_without_targets_is_unaffected(workflow):
    # Target-free invocation of every other workflow is untouched by M5.
    normalized = "cp" if workflow == "checkpoint" else workflow
    argv = workflow_argv(workflow, Path("R"), targets=())
    assert argv == ["--runtime-root", "R", "--only", normalized]


def test_scheduler_refuses_before_main_invoked_for_unsupported_target(monkeypatch):
    """A scheduler row naming targets against a non-seamed workflow must never
    reach main.main() -- workflow_argv() raises during argv construction,
    which happens before any main.main(...) call."""
    from application.workflows.maintenance import _scheduler_workflow_argv

    class _Row:
        pass

    row = _Row()
    row.workflow = "vsx"
    row.targets = ("SOMETHING",)

    with pytest.raises(UnsupportedTargetSelectionError):
        _scheduler_workflow_argv(row, "R")


def test_console_runner_refuses_before_main_invoked_for_unsupported_target():
    from console.runner import _build_argv
    from console.registry import get_job_type

    job_type = get_job_type("inventory_refresh_vsx")
    assert job_type.workflow == "vsx"
    with pytest.raises(UnsupportedTargetSelectionError):
        _build_argv(job_type, Path("R"), ["SOMETHING"])


# --- empty/invalid targeted requests never silently become plane-wide ------

def test_empty_tuple_targets_is_the_only_plane_wide_spelling():
    # An empty collection is the sole spelling of "no targeting requested".
    # Anything else must be an explicit, non-empty, exact list.
    assert workflow_argv("cp-config", Path("R"), targets=[]) == workflow_argv(
        "cp-config", Path("R"), targets=()
    )


def test_recovery_pan_targets_still_pass_through_unaffected_by_m5():
    # Pre-existing M5-adjacent seam (recovery-pan) must be byte-identical.
    argv = workflow_argv("recovery-pan", Path("R"), targets=("fw-01", "fw-02"))
    assert argv[-2:] == ["--recovery-gateways", "fw-01,fw-02"]


def test_recovery_cp_targets_still_pass_through_unaffected_by_m5():
    argv = workflow_argv("recovery-cp", Path("R"), targets=("fw-01",))
    assert argv[-2:] == ["--recovery-gateways", "fw-01"]


# --- JOB_REGISTRY.target_mode is explicitly out of scope for M5 ------------

def test_job_registry_cp_config_target_mode_unchanged():
    from console.registry import get_job_type

    job_type = get_job_type("config_refresh_cp")
    assert job_type.target_mode == "none"


# --- safety invariants: no new command, no admission/concurrency change ----

def test_no_new_device_command_introduced_by_workflow_argv_change():
    source = (Path(__file__).resolve().parents[1] / "utils" / "collection_executor.py").read_text(encoding="utf-8")
    start = source.index("def workflow_argv")
    end = source.index("# ---", start)
    body = source[start:end]
    assert "paramiko" not in body.lower()
    assert "_run_exec" not in body
    assert "ThreadPoolExecutor" not in body
    assert "subprocess" not in body


def test_admission_coordinator_and_budgets_module_untouched_by_m5():
    from utils.coordinator_backend import DEFAULT_CONCURRENCY_BUDGETS

    # Vendor concurrency budget stays at 1 pending its own real-env evidence
    # (CURRENT_STATE.md); M5 touches argv construction only.
    assert all(v == 1 for v in DEFAULT_CONCURRENCY_BUDGETS.values())
