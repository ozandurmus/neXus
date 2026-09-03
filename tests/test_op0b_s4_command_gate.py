"""OP.0b S4 -- command-gate package invariants.

Deterministic, text-level checks on `docs/history/phase/OP_0B_1_COMMAND_GATE_
PACKAGE.md` -- the network-device command gate `OP.0b.0` names as the
prerequisite for any `S5`/`S6` implementation. This is a docs-only build
(`AGENTS.md` "Mandatory build lifecycle": a frozen contract is required
before implementation when new network-device commands are introduced; this
gate is that step, not the implementation). No device is contacted by these
tests; they only prove the package's own internal consistency:

- it is not silently self-approved (status stays DRAFT until a human records
  approval),
- every command's decision uses only the fixed §13 vocabulary,
- every already-known mutating command is still listed as REJECTED, never as
  an approved decision,
- no approved/optional row claims anything other than CLASS_0_READ.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GATE_DOC = ROOT / "docs" / "history" / "phase" / "OP_0B_1_COMMAND_GATE_PACKAGE.md"

_DECISION_VOCABULARY = {
    "APPROVED_FOR_S5",
    "APPROVED_FOR_S6",
    "OPTIONAL_APPROVED",
    "REJECTED",
    "DEFERRED_UNKNOWN",
}

_KNOWN_MUTATING_COMMANDS = (
    "cphaprob -d <name> -t <sec> -s <state> [-p] register",
    "cphaprob -d <name> [-p] unregister",
    "show cluster failover reset history",
    "fw ctl set int vsid <N>",
    "clusterXL_admin down/up",
    "request high-availability state suspend/functional",
    "sync-to-remote",
)


def _text() -> str:
    return GATE_DOC.read_text(encoding="utf-8")


def test_gate_document_exists():
    assert GATE_DOC.exists(), "OP.0b.1 command-gate package doc is missing"


def test_gate_stays_draft_pending_explicit_po_approval():
    text = _text()
    assert "DRAFT" in text.split("## Status", 1)[1][:400]
    assert "## Approval record" in text
    approval_section = text.split("## Approval record", 1)[1]
    # The approval record must still be empty (no date/name filled in) --
    # this test fails on purpose the day someone fills it in without also
    # flipping the status line, catching a silent self-approval.
    assert "PO approval date: —" in approval_section
    assert "Approved by: —" in approval_section


def test_every_per_command_decision_uses_the_fixed_vocabulary():
    text = _text()
    decisions = re.findall(r"\*\*Decision:\*\* \*\*([A-Z0-9_]+)", text)
    assert decisions, "no per-command Decision lines found"
    for decision in decisions:
        assert decision in _DECISION_VOCABULARY, (
            f"gate record used a decision token outside the fixed vocabulary: {decision!r}"
        )


def test_every_recommendation_in_the_po_package_tables_is_valid():
    text = _text()
    po_section = text.split("## PO approval package", 1)[1]
    po_section = po_section.split("## Approval record", 1)[0]
    recommendations = re.findall(r"\*\*([A-Z_]+)\*\*", po_section)
    # every bolded token in the PO tables must be a decision from the fixed
    # vocabulary (the tables carry no other bolded content)
    assert recommendations
    for token in recommendations:
        assert token in _DECISION_VOCABULARY, (
            f"PO approval package used an unrecognised recommendation token: {token!r}"
        )


def test_no_approved_or_optional_row_claims_anything_but_class_0_read():
    text = _text()
    action_classes = re.findall(r"\*\*Action class:\*\* `?(CLASS_[A-Z0-9_]+)", text)
    assert action_classes, "no 'Action class:' fields found"
    for action_class in action_classes:
        assert action_class == "CLASS_0_READ", (
            f"a per-command gate record declared {action_class!r} -- CLASS 1+ "
            "is structurally out of scope for OP.0b.1 (P4 invariant)"
        )


def test_known_mutating_commands_are_still_listed_as_rejected():
    text = _text()
    rejected_section_start = text.index("## Rejected mutating operations")
    rejected_section = text[rejected_section_start:text.index("## Per-command gate records — Palo Alto")]
    for command in _KNOWN_MUTATING_COMMANDS:
        assert command in rejected_section, (
            f"known mutating command missing from the rejected-operations table: {command!r}"
        )


def test_deferred_rows_approve_no_command():
    """CP-A9 and PAN-P5 are DEFERRED_UNKNOWN -- neither may claim an
    approved command string or a non-zero call count."""
    text = _text()
    a9 = text[text.index("**ID:** CP-A9"):text.index("**ID:** CP-B1")]
    p5 = text[text.index("**ID:** PAN-P5"):text.index("## Command → fact matrix")]
    assert "**Decision:** **DEFERRED_UNKNOWN" in a9
    assert "**Expected calls per member:** 0" in a9
    assert "**Decision:** **DEFERRED_UNKNOWN" in p5
    assert "**Expected calls per member:** 0" in p5
