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
import re
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


# --- DEV.4 governance authority reconciliation ------------------------------
#
# DEV.4 collapsed the AI bootstrap surface to three authoritative files
# (AGENTS.md constitution, AI_START_HERE.md operating protocol, CURRENT_STATE.md
# hot checkpoint) after an audit found duplicated and contradictory law spread
# across AI_HANDOVER.md, docs/AI_DEVELOPMENT_PROTOCOL.md and the .github
# instructions/prompts surface. These tests pin the invariants that made that
# audit necessary so they cannot silently regress.

def test_ai_handover_if_present_declares_itself_non_authoritative():
    """AI_HANDOVER.md used to compete with CURRENT_STATE.md/roadmap.json as a
    project-state authority. DEV.4 kept it only as an explicitly-labeled
    convenience summary; if it is ever removed entirely that is also fine —
    this test only forbids it silently becoming authoritative again."""
    handover = ROOT / "AI_HANDOVER.md"
    if not handover.exists():
        return
    text = handover.read_text(encoding="utf-8")
    assert "NON-AUTHORITATIVE DERIVED SUMMARY" in text
    assert "DO NOT USE AS PROJECT-STATE AUTHORITY" in text


def test_no_device_write_automation_claim_does_not_reappear():
    """.github/copilot-instructions.md carried a stale absolute claim ("No
    device write/change automation is permitted at the current product
    maturity") that predated the CLASS 1 recovery-write contracts (`RB.x`) and
    contradicted the action taxonomy. DEV.4 removed it; it must not resurface
    verbatim in any canonical governance doc."""
    stale = "No device write/change automation is permitted"
    for doc in (
        "AGENTS.md",
        "AI_START_HERE.md",
        "CURRENT_STATE.md",
        "CLAUDE.md",
        "docs/AI_DEVELOPMENT_PROTOCOL.md",
        ".github/copilot-instructions.md",
    ):
        text = (ROOT / doc).read_text(encoding="utf-8")
        assert stale not in text, f"{doc} reintroduced the stale absolute claim"


def test_agents_md_encodes_the_opaque_identifier_law():
    """The identity law that came directly out of the PAN HA serial-matching
    incident (no int() cast, no leading-zero strip, no digit-only
    normalization, no guessed equality) must live in the constitution, not
    only in a chat transcript or a single build's phase doc."""
    text = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    assert "opaque" in text.lower()
    for token in ("MATCH", "MISMATCH", "NOT_EVALUABLE"):
        assert token in text, f"AGENTS.md is missing the {token!r} vocabulary"


def test_command_gate_and_validation_tiers_stay_documented():
    """The network-device command gate and the automated-vs-real-environment
    distinction are the two governance mechanisms every device-facing build
    depends on; they must keep a canonical home."""
    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    protocol = (ROOT / "docs" / "AI_DEVELOPMENT_PROTOCOL.md").read_text(encoding="utf-8")
    assert "network-device command gate" in agents
    assert "network-device command gate" in protocol
    assert "real-environment validation" in agents or "real-environment evidence" in agents
    assert "AUTOMATED_VALIDATED" in agents and "REAL_ENV_VALIDATED" in agents


def test_agents_md_encodes_evidence_identity_and_readiness_distinctions():
    """Two of the eleven evidence-law pairs are load-bearing enough to check
    directly: an evidence-plane identity is not an operational identity, and a
    green readiness assessment is not itself an authorization to act."""
    text = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    assert "Evidence identity != operational identity" in text
    assert "Readiness != authorization" in text


# --- Draft-authority machine gate (OP.0b.0 STATE_UPDATE, DEV.4 follow-up) ---
#
# AGENTS.md "Authority hierarchy" item 2 and "Contract-status law" both say, in
# prose, that a DRAFT / DO NOT FREEZE contract must never be treated as the
# current FROZEN implementation authority. Auditing DEV.4's five governance
# tests found none of them machine-check that specific invariant -- they pin
# AI_HANDOVER.md's banner, the stale device-write claim, the opaque-identifier
# vocabulary, the command-gate/validation-tier wording, and the evidence-
# identity/readiness wording, but nothing walks an actual contract doc's own
# declared status against what project state claims about it. This closes
# that gap generically: it reads each contract doc's own "## Status" line
# (the existing, already-followed convention -- see e.g. OP_0B_0_..., which is
# "DRAFT -- DO NOT FREEZE", vs. DEV3_3_..., which is "CONTRACT_FROZEN") and
# compares the *token*, never fixed prose, against the status of any
# build_history.json record that cites the doc via its `docs` mapping.

_STATUS_HEADING_RE = re.compile(r"^## Status\s*$", re.MULTILINE)
_DRAFT_STATUS_MARKERS = ("DRAFT", "DO NOT FREEZE")
_FROZEN_STATUS_MARKERS = ("FROZEN",)


def _contract_doc_status_line(path: Path) -> str:
    """The first non-blank line following a doc's '## Status' heading -- the
    convention every phase/design doc in this repository already follows for
    declaring DRAFT / DO NOT FREEZE / CONTRACT_FROZEN / SUPERSEDED /
    DEPRECATED (AGENTS.md Contract-status law)."""
    match = _STATUS_HEADING_RE.search(path.read_text(encoding="utf-8"))
    if not match:
        return ""
    for line in path.read_text(encoding="utf-8")[match.end():].splitlines():
        if line.strip():
            return line.strip()
    return ""


def test_a_draft_contract_never_backs_a_terminal_build_history_record():
    """The machine check for: 'A DRAFT / DO NOT FREEZE contract cannot be
    treated as the current FROZEN implementation authority.' Walks every doc
    a build_history.json record cites, and where that doc's own status line
    says DRAFT/DO NOT FREEZE (and not FROZEN), asserts the citing record's
    own status is not one that claims a finished, authoritative outcome. This
    is authority-semantics, not prose-matching: it keys off the doc's self-
    declared status token, so it holds for any future DRAFT contract, not
    only OP.0b.0."""
    from utils.project_plan import _TERMINAL_BUILD_STATUSES

    for build in _load("build_history.json")["builds"]:
        for doc_path in (build.get("docs") or {}).values():
            full = ROOT / doc_path
            if full.suffix != ".md" or not full.exists():
                continue
            status_line = _contract_doc_status_line(full)
            if not status_line:
                continue
            is_draft = any(marker in status_line for marker in _DRAFT_STATUS_MARKERS)
            is_frozen = any(marker in status_line for marker in _FROZEN_STATUS_MARKERS)
            if not is_draft or is_frozen:
                continue
            build_status = str(build.get("status") or "")
            assert build_status not in _TERMINAL_BUILD_STATUSES, (
                f"{doc_path} status line ({status_line!r}) is DRAFT/DO NOT FREEZE, "
                f"but build_history record {build.get('build')!r} claims terminal "
                f"status {build_status!r} -- a draft cannot back frozen authority"
            )
