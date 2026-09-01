"""architecture_convergence — action taxonomy + repository-state consistency.

Two guarantees this build introduced, both of which existed only as prose
before and both of which had already drifted:

1. **The action taxonomy** (`utils/action_taxonomy.py`). The product is not
   "read-only" — `RB.x` ships controlled recovery writes — and the console's
   old two-value `read | operational-write` vocabulary could not tell a Gaia
   backup (CLASS 1) apart from a failover (CLASS 2). These tests pin the class
   boundaries and the console's "CLASS 0 only" guarantee so `OP.x` cannot blur
   them by adding a job type.

2. **One current state.** `roadmap.json`, `feature_registry.json`,
   `build_history.json`, `backlog.json`, `CURRENT_STATE.md` and
   `docs/history/INDEX.md` each used to claim the current build independently,
   and they disagreed: roadmap said `0.7.4` (completed 2026-08-29) while the
   newest build record was `OP.0a` (2026-09-01) and the feature registry still
   called that same work `planned`. The JSON↔JSON rules live in
   `utils.project_plan._cross_authority_warnings` (they run in the render path);
   the Markdown↔JSON rules live here, where they cost nothing at runtime.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.runtime_platform

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "project"


def _load(name: str) -> dict:
    return json.loads((PROJECT / name).read_text(encoding="utf-8"))


# --- Action taxonomy -------------------------------------------------------

def test_the_five_classes_exist_and_are_ordered():
    from utils import action_taxonomy as tax

    assert [c.level for c in tax.ACTION_CLASSES] == [0, 1, 2, 3, 4]
    assert len({c.id for c in tax.ACTION_CLASSES}) == 5


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


# --- Repository-state consistency ------------------------------------------

def test_project_metadata_has_no_cross_authority_contradictions():
    """The JSON↔JSON gate. Extends the pre-existing `metadata_warnings`
    assertion with the cross-file rules; it was green while three files each
    named a different current build."""
    from utils.project_plan import build_project_plan_payload

    assert build_project_plan_payload()["metadata_warnings"] == []


def test_current_state_names_the_same_active_build_as_the_roadmap():
    """The Markdown↔JSON gate.

    CURRENT_STATE.md is prose and cannot be derived, so the one machine-checkable
    thing is that it names the build the metadata says is current. That single
    token is exactly what went stale before.
    """
    now_build = _load("roadmap.json")["now_next"]["now"]["build"]
    current_state = (ROOT / "CURRENT_STATE.md").read_text(encoding="utf-8")
    assert now_build in current_state, (
        f"CURRENT_STATE.md does not mention the current build {now_build!r} "
        f"declared by project/roadmap.json"
    )


def test_current_state_stays_a_checkpoint_not_a_history():
    """AGENTS.md "Handover economy": CURRENT_STATE.md is a hot-path checkpoint
    a cold chat reads in one pass. It had grown to 764 lines by absorbing a
    narrative for eleven predecessor builds that all already had their own
    build_history record and phase doc."""
    lines = (ROOT / "CURRENT_STATE.md").read_text(encoding="utf-8").splitlines()
    assert len(lines) <= 200, (
        f"CURRENT_STATE.md is {len(lines)} lines; predecessor build detail belongs "
        f"in project/build_history.json and its linked phase doc"
    )


def test_build_history_index_is_derived_not_hand_maintained():
    """docs/history/INDEX.md claimed to be generated from build_history.json
    while actually being hand-edited, and drifted to a newest row of `0.7.4`.
    It is now really generated; this proves the checked-in copy is current."""
    result = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "build_history_index.py"), "--check"],
        capture_output=True, text=True, cwd=str(ROOT),
    )
    assert result.returncode == 0, result.stdout + result.stderr


def test_every_build_history_doc_link_resolves():
    """build_history.json is the designated route into archived detail
    (AGENTS.md). A dead link there silently removes a build's evidence."""
    broken = [
        path
        for build in _load("build_history.json")["builds"]
        for path in (build.get("docs") or {}).values()
        if not (ROOT / path).exists()
    ]
    assert broken == []


def test_the_failover_package_still_contains_no_executor():
    """OP.0a decision P5, restated here because it is a safety property and not
    only that build's business: dormant write-capable code is a standing
    liability (the `remove_dormant_remote_cleanup` precedent)."""
    failover_dir = ROOT / "utils" / "failover"
    modules = {p.stem for p in failover_dir.glob("*.py")}
    assert modules == {"__init__", "assessment"}, (
        f"utils/failover/ gained {modules - {'__init__', 'assessment'}}; a plan, "
        f"executor or vendor adapter may not exist before its own gate is cleared"
    )
