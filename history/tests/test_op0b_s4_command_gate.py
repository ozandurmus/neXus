"""OP.0b S4 -- command-gate package invariants.

Deterministic, text-level checks on `docs/history/phase/OP_0B_1_COMMAND_GATE_
PACKAGE.md` -- the network-device command gate `OP.0b.0` names as the
prerequisite for any `S5`/`S6` implementation. This is a docs-only build
(`AGENTS.md` "Mandatory build lifecycle": a frozen contract is required
before implementation when new network-device commands are introduced; this
gate is that step, not the implementation). No device is contacted by these
tests; they only prove the package's own internal consistency:

- the PO approval record is actually filled in (not a silent self-approval
  with the record still empty),
- every command's decision uses only the fixed §13 vocabulary,
- every already-known mutating command is still listed as REJECTED, never as
  an approved decision,
- no approved/optional row claims anything other than CLASS_0_READ,
- the PO's binding overrides (no new command-level retry; B1 reuses the
  existing session; A10/A11/P3 withheld from the current implementation
  battery) are actually recorded on the rows they apply to, not just in a
  summary paragraph that could drift from the per-row records.
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

# The "PO decision" column in the PO approval package tables uses this one
# extra bolded token (never bolded alone as "NOT AUTHORIZED" -- that phrase
# always carries the space, so it never collides with the decision
# vocabulary's all-caps single-token matching below).
_PO_DECISION_TOKENS = _DECISION_VOCABULARY | {"AUTHORIZED"}

_KNOWN_MUTATING_COMMANDS = (
    "cphaprob -d <name> -t <sec> -s <state> [-p] register",
    "cphaprob -d <name> [-p] unregister",
    "show cluster failover reset history",
    "fw ctl set int vsid <N>",
    "clusterXL_admin down/up",
    "request high-availability state suspend/functional",
    "sync-to-remote",
)

# Rows the PO explicitly authorized for the current S5/S6 implementation
# battery -- every one of these must carry a NO_RETRY override on its own
# record, not merely in the summary "Retry -- PO override" section.
_PO_AUTHORIZED_ROW_IDS = ("CP-A4", "CP-A5", "CP-A6", "CP-A7", "CP-A8", "CP-B1", "PAN-P4")

# Rows technically OPTIONAL_APPROVED but withheld by the PO for this slice.
_PO_WITHHELD_ROW_IDS = ("CP-A10", "CP-A11", "PAN-P3")


def _text() -> str:
    return GATE_DOC.read_text(encoding="utf-8")


def _record(text: str, record_id: str, next_id: str) -> str:
    start = text.index(f"**ID:** {record_id}")
    end = text.index(f"**ID:** {next_id}", start)
    return text[start:end]


def test_gate_document_exists():
    assert GATE_DOC.exists(), "OP.0b.1 command-gate package doc is missing"


def test_gate_records_explicit_po_approval():
    text = _text()
    assert "APPROVED" in text.split("## Status", 1)[1][:200]
    assert "## Approval record" in text
    approval_section = text.split("## Approval record", 1)[1]
    # The approval must actually be recorded -- not left as the empty
    # placeholder this test would have caught before sign-off.
    assert "PO approval: YES" in approval_section
    assert "PO approval date: —" not in approval_section
    assert "Approved by: —" not in approval_section
    assert "2026-09-03" in approval_section


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
    # every bolded all-caps token in the PO tables must be either a fixed
    # decision or the PO-decision column's "AUTHORIZED" marker (its
    # withheld counterpart is always the two-word, unbolded-as-one-token
    # "NOT AUTHORIZED", which this regex cannot and should not match)
    assert recommendations
    for token in recommendations:
        assert token in _PO_DECISION_TOKENS, (
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
    a9 = _record(text, "CP-A9", "CP-B1")
    p5 = text[text.index("**ID:** PAN-P5"):text.index("## Command → fact matrix")]
    assert "**Decision:** **DEFERRED_UNKNOWN" in a9
    assert "**Expected calls per member:** 0" in a9
    assert "**Decision:** **DEFERRED_UNKNOWN" in p5
    assert "**Expected calls per member:** 0" in p5


def test_po_authorized_rows_carry_the_no_retry_override_on_the_record_itself():
    """Every row the PO actually authorized for implementation must show
    NO_RETRY on its own per-command record -- a summary paragraph saying so
    elsewhere in the doc is not enough; the record an implementer reads for
    that specific command must be unambiguous on its own."""
    text = _text()
    ids_in_order = ("CP-A4", "CP-A5", "CP-A6", "CP-A7", "CP-A8", "CP-A9", "CP-B1", "CP-A10")
    for i, row_id in enumerate(_PO_AUTHORIZED_ROW_IDS):
        if row_id.startswith("CP-"):
            idx = ids_in_order.index(row_id)
            record = _record(text, row_id, ids_in_order[idx + 1])
        else:
            record = text[text.index(f"**ID:** {row_id}"):text.index("**ID:** PAN-P5")]
        assert "NO_RETRY" in record, f"{row_id} is PO-authorized but its record has no NO_RETRY override"
        assert "PO override" in record, f"{row_id} record does not cite the PO override"


def test_po_withheld_rows_say_not_authorized_on_the_record_itself():
    text = _text()
    a10 = _record(text, "CP-A10", "CP-A11")
    a11 = text[text.index("**ID:** CP-A11"):text.index("## VSX safety summary")]
    p3 = text[text.index("**ID:** PAN-P3"):text.index("**ID:** PAN-P4")]
    for row_id, record in (("CP-A10", a10), ("CP-A11", a11), ("PAN-P3", p3)):
        assert "NOT AUTHORIZED" in record, f"{row_id} record does not say NOT AUTHORIZED"
        # the technical assessment must survive -- OPTIONAL_APPROVED stays on the record
        assert "OPTIONAL_APPROVED" in record, f"{row_id} lost its preserved technical assessment"


def test_b1_forbids_a_new_ssh_session():
    text = _text()
    b1 = _record(text, "CP-B1", "CP-A10")
    assert "NOT approved" in b1 and "new SSH transport session" in b1
    assert "stop and return" in b1.lower() or "STOP" in b1


def test_network_bound_excludes_optionals_and_matches_po_ceiling():
    text = _text()
    bound_section = text[text.index("**NETWORK BEHAVIOR IF APPROVED"):text.index("## Approval record")]
    normalized = " ".join(bound_section.split())
    assert "18 required device commands maximum" in normalized
    assert "16 required device commands maximum" in normalized
    assert "6 required API calls maximum" in normalized
    # no retry-inflated ceiling figure now that command-level retry is not
    # authorized (a bare mention explaining *why* there is none, e.g. "no
    # retry ceiling multiplier", is fine -- a leftover "Ceiling ...: 48" or
    # "Ceiling ...: 16" style figure is not)
    assert not re.search(r"ceiling[^.]*:\s*\*\*\d+\*\*", normalized.lower())
