"""Legacy architecture_convergence tests: action taxonomy + failover package.

Moved from tests/test_architecture_convergence.py. They exercise the archived
Python product (`utils/action_taxonomy.py`, `console/registry.py`,
`utils/failover/`), which live under history/. Opt-in: run from history/.
The live taxonomy is the Java ActionClass; no device command is authorized by
retaining this file.
"""
from __future__ import annotations

from pathlib import Path

import pytest

pytestmark = pytest.mark.runtime_platform

ROOT = Path(__file__).resolve().parents[1]  # history/ (holds utils/failover)


# --- Action taxonomy -------------------------------------------------------

def test_the_action_classes_exist_and_are_ordered():
    from utils import action_taxonomy as tax

    assert [c.level for c in tax.ACTION_CLASSES] == [0, 1, 1.5, 2, 3, 4]
    assert len({c.id for c in tax.ACTION_CLASSES}) == 6


def test_recovery_write_and_operational_state_change_are_distinct_classes():
    """The whole point of the taxonomy.

    Taking a Gaia backup and failing a cluster over are not the same risk and
    must never resolve to the same class, the same permission or the same
    refusal code — the old `operational-write` vocabulary made them identical.
    """
    from utils import action_taxonomy as tax

    backup = tax.CLASS_1_RECOVERY_WRITE
    failover = tax.CLASS_2_OPERATIONAL_STATE_CHANGE

    assert backup is not failover
    assert backup.level < failover.level
    assert backup.refusal_code != failover.refusal_code
    # A recovery write is permitted product-wide (the CLI runs it under the RB.x
    # ledger contracts); an operational state change is not permitted anywhere.
    assert backup.permitted is True
    assert failover.permitted is False


def test_controlled_restore_write_is_narrow_and_between_recovery_and_state_change():
    from utils import action_taxonomy as tax

    restore = tax.CLASS_1B_CONTROLLED_RESTORE_WRITE

    assert restore.level == 1.5
    assert tax.CLASS_1_RECOVERY_WRITE.level < restore.level < tax.CLASS_2_OPERATIONAL_STATE_CHANGE.level
    assert restore.permitted is True
    assert restore.console_submittable is False
    assert restore.refusal_code == "controlled_restore_write_not_console_submittable"
    assert "provenance-bound" in restore.why
    assert "physical" in restore.why


def test_configuration_write_and_policy_deployment_stay_prohibited():
    from utils import action_taxonomy as tax

    for cls in (tax.CLASS_3_CONFIGURATION_WRITE, tax.CLASS_4_POLICY_DEPLOYMENT):
        assert cls.permitted is False
        assert cls.console_submittable is False


def test_only_class_0_is_console_submittable():
    """The console's standing boundary, asserted on the taxonomy itself rather
    than on any one surface, so a new surface inherits it."""
    from utils import action_taxonomy as tax

    submittable = [c for c in tax.ACTION_CLASSES if c.console_submittable]
    assert submittable == [tax.CLASS_0_READ]


def test_every_console_job_type_maps_to_a_taxonomy_class():
    from console.registry import JOB_REGISTRY

    for job_id, job_type in JOB_REGISTRY.items():
        assert job_type.action_class is not None, job_id


def test_no_console_job_type_is_class_2_or_above():
    """CLASS 2 has no member yet and must not gain one here.

    A failover job type may only appear once every OP.2 prerequisite in
    docs/design/FAILOVER_ENGINE_ARCHITECTURE.md section 10 is met. If this test
    fails, that gate — not this assertion — is what needs revisiting.
    """
    from console.registry import JOB_REGISTRY

    offenders = {
        job_id: jt.action_class.id
        for job_id, jt in JOB_REGISTRY.items()
        if jt.action_class.level >= 2
    }
    assert offenders == {}


def test_legacy_command_class_values_still_map():
    """`command_class` is on the wire and inside every durable job record, so
    the legacy vocabulary must keep resolving without a data migration."""
    from console.registry import JOB_REGISTRY
    from utils.action_taxonomy import LEGACY_COMMAND_CLASS_TO_ACTION_CLASS

    for job_type in JOB_REGISTRY.values():
        assert job_type.command_class in LEGACY_COMMAND_CLASS_TO_ACTION_CLASS


def test_the_failover_package_still_contains_no_executor():
    """OP.0a decision P5, restated here because it is a safety property and not
    only that build's business: dormant write-capable code is a standing
    liability (the `remove_dormant_remote_cleanup` precedent). `preflight_model`
    (OP.0b S1, frozen contract) is the one deliberate addition -- a pure,
    zero-I/O evidence/provenance model, not a plan/executor/adapter; allowed
    here explicitly rather than by widening the check's intent."""
    failover_dir = ROOT / "utils" / "failover"
    modules = {p.stem for p in failover_dir.glob("*.py")}
    allowed = {"__init__", "assessment", "preflight_model", "preflight_readiness"}
    assert modules == allowed, (
        f"utils/failover/ gained {modules - allowed}; a plan, "
        f"executor or vendor adapter may not exist before its own gate is cleared"
    )
