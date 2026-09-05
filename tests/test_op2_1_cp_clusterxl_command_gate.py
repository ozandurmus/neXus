"""OP.2.1 -- CP ClusterXL mutation command-gate invariants.

Deterministic, text-level checks on `docs/history/phase/OP_2_1_CP_CLUSTERXL_
MUTATION_COMMAND_GATE.md` -- the network-device command gate the frozen
`OP.2.0` contract names as the `OP.2.1` prerequisite for `OP.2.C` (the first
vendor adapter). This is a docs-only build: no device is contacted, no
taxonomy member is added, no adapter exists. These tests only prove the
gate document's own internal consistency and that documenting the two
mutation primitives here has not, anywhere, made CLASS 2 reachable:

- the gate doc exists and both candidate rows use only the fixed decision
  vocabulary,
- both approved rows declare CLASS_2_OPERATIONAL_STATE_CHANGE (never
  CLASS_0_READ -- the inverse of OP.0b.1's own check, since this gate is
  mutations, not reads),
- every already-known mutating alternative stays listed as rejected,
- the deferred `-p` variant approves nothing,
- CLASS_2_OPERATIONAL_STATE_CHANGE still has no member and DenyAllAuthorizer
  is still the only production authorizer -- source-scanned, the same
  technique tests/test_op2_a_b_execution_foundation.py already uses.
"""
from __future__ import annotations

import re
from pathlib import Path

from utils import action_taxonomy
from utils.operate import authorization as operate_authorization

ROOT = Path(__file__).resolve().parents[1]
GATE_DOC = ROOT / "docs" / "history" / "phase" / "OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md"

_DECISION_VOCABULARY = {
    "APPROVED_FOR_OP2C",
    "DEFERRED_NOT_IN_INITIAL_BATTERY",
    "REJECTED",
}

_APPROVED_ROW_IDS = ("CP-M1", "CP-M1-R")

_KNOWN_MUTATING_ALTERNATIVES = (
    "cphastop",
    "cpstop",
    "g_clusterXL_admin",
    "set cluster member admin {down\\|up} [permanent]",
)


def _text() -> str:
    return GATE_DOC.read_text(encoding="utf-8")


def _record(text: str, record_id: str, next_marker: str) -> str:
    start = text.index(f"**ID:** {record_id}")
    end = text.index(next_marker, start)
    return text[start:end]


def test_gate_document_exists():
    assert GATE_DOC.exists(), "OP.2.1 CP ClusterXL command-gate doc is missing"


def test_every_per_command_decision_uses_the_fixed_vocabulary():
    text = _text()
    decisions = re.findall(r"\*\*Decision:\*\* \*\*([A-Z0-9_]+)", text)
    assert decisions, "no per-command Decision lines found"
    for decision in decisions:
        assert decision in _DECISION_VOCABULARY, (
            f"gate record used a decision token outside the fixed vocabulary: {decision!r}"
        )
    # Both real candidates must actually be approved, not merely deferred.
    for row_id in _APPROVED_ROW_IDS:
        assert row_id in text


def test_approved_rows_declare_class_2_never_class_0():
    text = _text()
    action_classes = re.findall(r"\*\*Action class:\*\* `?(CLASS_[A-Z0-9_]+)", text)
    assert action_classes, "no 'Action class:' fields found"
    for action_class in action_classes:
        assert action_class == "CLASS_2_OPERATIONAL_STATE_CHANGE", (
            f"a per-command gate record declared {action_class!r} -- OP.2.1 gates "
            "mutation primitives, which must be CLASS_2, never CLASS_0_READ"
        )


def test_cp_m1_and_reversal_are_approved_for_op2c():
    text = _text()
    m1 = _record(text, "CP-M1", "**ID:** CP-M1-R")
    m1r = text[text.index("**ID:** CP-M1-R"):text.index("## Persistence")]
    assert "**Decision:** **APPROVED_FOR_OP2C" in m1
    assert "**Decision:** **APPROVED_FOR_OP2C" in m1r
    # The reversal is explicitly a separate typed action, never automatic.
    assert "separate typed class 2 action" in m1r
    assert "never an automatic rollback" in m1r


def test_deferred_persistence_variant_approves_nothing():
    text = _text()
    section = text[text.index("## Persistence (`-p`)"):text.index("## Rejected mutating alternatives")]
    assert "DEFERRED_NOT_IN_INITIAL_BATTERY" in section
    matrix_row = text[text.index("| CP-M1 `-p`"):text.index("| CP-M1 `-p`") + 200]
    assert "DEFERRED_NOT_IN_INITIAL_BATTERY" in matrix_row


def test_known_mutating_alternatives_are_listed_as_rejected():
    text = _text()
    rejected_section_start = text.index("## Rejected mutating alternatives")
    rejected_section = text[rejected_section_start:text.index("## Command → fact matrix")]
    for command in _KNOWN_MUTATING_ALTERNATIVES:
        assert command in rejected_section, (
            f"known mutating alternative missing from the rejected table: {command!r}"
        )
    # The management-plane preemption write must be explicitly disclaimed.
    assert "Management-plane write of the recovery-method" in rejected_section


def test_reversal_preemption_disclosure_and_d_v7b_are_discussed():
    text = _text()
    assert "D-V7b / D-F3" in text
    assert "not operator-overridable" in text
    assert "_verdict_for" in text


def test_class_2_still_has_no_member_and_deny_all_is_the_only_authorizer():
    """Documenting two mutation primitives must not, by itself, make CLASS 2
    reachable -- the taxonomy class stays memberless and the only production
    authorizer stays unconditional DENY, unaffected by this gate."""
    assert action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE.permitted is False
    assert action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE.console_submittable is False

    source = Path(operate_authorization.__file__).read_text(encoding="utf-8")
    assert "DenyAllAuthorizer" in source
    assert "class PermitAllAuthorizer" not in source, (
        "a PERMIT-returning authorizer must not exist outside tests/"
    )


def test_gate_declares_docs_only_scope():
    text = _text()
    header = text.split("## Status", 1)[1][:1200]
    assert "APPROVED" in header
    assert "no code" in header.lower() or "docs only" in header.lower()
    assert "no taxonomy member" in header.lower() or "no member" in header.lower()
