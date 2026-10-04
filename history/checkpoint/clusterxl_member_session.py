"""OP.2.C follow-up -- the real `ClusterXLMemberSession` implementation.

`checkpoint.clusterxl_capability_adapter` defines the `ClusterXLMemberSession`
Protocol and deliberately ships no implementation of it (see that module's
own docstring: "a concrete implementation resolves `clusterXL_admin down`/
`up` internally... this module provides no such implementation"). This
module is that later, separate piece -- it does not touch the adapter, the
Protocol, `OP.2` lifecycle/authorization/readiness policy, or anything else
`checkpoint.clusterxl_capability_adapter` already owns.

**Transport reuse.** This wraps one already-open
`checkpoint.preflight_collector.MemberSession` -- the exact per-member
persistent Expert-shell session the CP.0b preflight collector already uses
and this repository has real-environment validated (`CURRENT_STATE.md`).
No second SSH client, no second `InteractiveSshSession`, no reconnect
per command: every call here runs over the same open shell, using the
same `_run_command` callable and the same `INTER_COMMAND_DELAY_SECONDS`
inter-command pacing the collector's own `MemberSession.run`/`run_vsenv`
already apply, so command bookkeeping (`command_invocations`) and pacing
stay coherent across ordinary battery reads and the two approved mutation
submissions on one member.

**Command resolution.** The two approved primitives
(`docs/history/phase/OP_2_1_CP_CLUSTERXL_MUTATION_COMMAND_GATE.md`, CP-M1 /
CP-M1-R) are Expert-shell script invocations, never wrapped in
`clish -c '...'` -- the gate document is explicit that `clusterXL_admin`
runs "on a fresh Expert-shell execute channel", the same execution context
the member's persistent shell already logs into. `clish -c` is reserved for
genuinely Gaia-CLI-only reads (e.g. this repository's own `A8` enterprise
form) and is not used here because neither approved command is one.
Command text is two hardcoded literals (`_ADMIN_DOWN_COMMAND_TEXT`,
`_ADMIN_UP_COMMAND_TEXT`) -- never built from caller input, and never
returned to a caller (`OP.2.0` P18 stays the adapter's problem, not this
module's, but this module does not undermine it either).

**Verification reads.** `read_role()` reuses exactly the two already-
approved class 0 reads the gate names as CP-M1/CP-M1-R's own postcondition
evidence -- `CPPreflightRead.A3_CPHAPROB_STAT` (local role) and
`CPPreflightRead.A5_PNOTE_LIST` (`admin_down` pnote) -- through
`MemberSession.run`, so framing, exit-status handling, and pacing are
exactly what the collector already validated. No new read command is
introduced.

**Exact `admin_down` pnote identification (`OP.2.C1` safety correction).**
`admin_down_pnote_present` answers one narrow question: is the Critical
Device *literally named* `admin_down` -- the exact device
`clusterXL_admin down` registers, per the gate's "Supported semantics" --
present and reporting a problem state? This is never the same fact as
`checkpoint.cp_preflight_extraction.parse_cphaprob_ia_list`'s own
`any_problem` (an aggregate over every registered Critical Device, scoped
to the general CP-A5 readiness battery and its D-V6 retained-field limit --
a `problem` state on some unrelated device, e.g. `Synchronization`, must
never be read as `admin_down`'s appearance). This module reads the pnote
*name* to make that distinction -- exactly the narrower disclosure the
gate's own "Sensitive output" row already sanctions ("member role token,
pnote name") -- and pairs each parsed device name with its state locally,
without widening `parse_cphaprob_ia_list`'s own contract or retained-field
set. A problem-state row with no associated device name is never assumed
to be `admin_down` -- but it is also never assumed to be *not* `admin_down`
(a later safety correction: absence of the name among a read's other,
named rows does not prove `admin_down` itself is absent, since the unnamed
row could be it). `admin_down_pnote_present` resolves to `False` only when
A5 positively proves `admin_down` is not in problem state -- every parsed
problem row is provably some other, named device, or the device's own
explicit "no pnotes in problem state" sentence. Any unnamed problem row,
absent a positive `admin_down` identification elsewhere in the same read,
fails closed to `None`.

**Submission outcome.** `SubmissionConfirmation` is the same two-way split
`OP.2.0` P6/P7 already fix: `CONFIRMED_NOT_SENT` only when this module can
positively prove the command was never even attempted -- the one provable
case is that no persistent Expert shell was ever established for this
member (`session._shell is None`). Once a send is attempted on an
established session, the outcome is `SUBMITTED_OR_AMBIGUOUS` no matter what
happens next -- a non-zero exit status, a CLI-rejected/empty response, an
ordinary success, `InteractiveSshSession.run`'s own `"execution_error"`
classification, or an exception raised out of the run call itself. None of
those positively proves the bytes never left this session's already-open
channel (`OP.2.C1` safety correction: an attempted send that fails is
attempted-send *uncertainty*, not proof of non-delivery, and must never be
treated as the safer outcome). This module never distinguishes further
among the `SUBMITTED_OR_AMBIGUOUS` cases, exactly as `SubmissionConfirmation`'s
own docstring requires. Exactly one call to the underlying transport per
submission -- no retry, no resend, under any circumstance.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from checkpoint.clusterxl_capability_adapter import (
    MemberRoleReading,
    SubmissionConfirmation,
)
from checkpoint.cp_preflight_battery import CPPreflightRead
from checkpoint.preflight_collector import INTER_COMMAND_DELAY_SECONDS, MemberSession
from configuration.checkpoint_config_collector import _parse_clusterxl_runtime_role

__all__ = [
    "ADMIN_DOWN_COMMAND_TEXT",
    "ADMIN_UP_COMMAND_TEXT",
    "RealClusterXLMemberSession",
]

#: The exact Critical Device name `clusterXL_admin down` registers
#: (`OP.2.1` CP-M1 "Supported semantics"). Matched case-insensitively,
#: exact name only -- never a substring/prefix match against any other
#: registered device.
_ADMIN_DOWN_DEVICE_NAME = "admin_down"

#: Local mirrors of `checkpoint.cp_preflight_extraction`'s own CP-A5 shape
#: knowledge (three real output shapes: per-device block, fixed-width
#: table, healthy-member sentence). Duplicated rather than imported so this
#: module's narrower, OP.2.1-sanctioned pnote-*name* read never widens that
#: module's own public contract (`parse_cphaprob_ia_list`'s return shape,
#: D-V6's retained-field limit) by so much as a shared private symbol.
_PNOTE_DEVICE_NAME_RE = re.compile(r"(?im)^\s*device\s+name\s*:\s*(\S[^\r\n]*)$")
_PNOTE_STATE_RE = re.compile(r"(?im)^\s*current\s+state\s*:\s*(\S[^\r\n]*)$")
_PNOTE_TABLE_HEADER_RE = re.compile(r"(?i)current\s+state\s*:?")
_PNOTE_DEVICE_HEADER_RE = re.compile(r"(?i)device\s+name\s*:?")
_PNOTE_NONE_IN_PROBLEM_RE = re.compile(r"(?im)^\s*there\s+are\s+no\s+pnotes?\s+in\s+(?:a\s+)?problem\s+state\b")
_PNOTE_PROBLEM_PREFIXES = ("problem", "error", "failed")


def _parse_pnote_block_pairs(text: str) -> list[tuple[str | None, str]]:
    """Per-device block form: pair each `Device Name:` line with the
    `Current state:` line that follows it. A state line with no preceding
    (unconsumed) device name line still yields a pair with `name=None` --
    never dropped, so callers can positively distinguish "an unnamed
    problem line" from "no problem line at all"."""
    pairs: list[tuple[str | None, str]] = []
    current_name: str | None = None
    for line in text.splitlines():
        name_match = _PNOTE_DEVICE_NAME_RE.match(line)
        if name_match:
            current_name = name_match.group(1).strip()
            continue
        state_match = _PNOTE_STATE_RE.match(line)
        if state_match:
            pairs.append((current_name, state_match.group(1).strip()))
            current_name = None
    return pairs


#: A column boundary is the start of a run of non-space text that either
#: opens the line or follows at least two spaces -- the same fixed-width
#: convention the real header row uses to separate multi-word labels
#: ("Device Name:", "Time since last report:") from one another.
_COLUMN_START_RE = re.compile(r"(?:^|(?<=\s{2}))\S")


def _parse_pnote_table_pairs(text: str) -> list[tuple[str | None, str]]:
    """Fixed-width column table form: locate the `Device Name`/`Current
    state` header columns by their fixed-width column boundaries and slice
    each following row at both."""
    lines = [line for line in text.splitlines() if line.strip()]
    for index, line in enumerate(lines):
        state_match = _PNOTE_TABLE_HEADER_RE.search(line)
        device_match = _PNOTE_DEVICE_HEADER_RE.search(line)
        if not state_match or not device_match:
            continue

        column_starts = sorted(m.start() for m in _COLUMN_START_RE.finditer(line))

        def _cell_end(start: int) -> int:
            following = [c for c in column_starts if c > start]
            return min(following) if following else len(line)

        state_start, name_start = state_match.start(), device_match.start()
        state_end, name_end = _cell_end(state_start), _cell_end(name_start)
        pairs: list[tuple[str | None, str]] = []
        for row in lines[index + 1:]:
            if len(row) <= state_start:
                continue
            state_cell = row[state_start:state_end].strip()
            name_cell = row[name_start:name_end].strip() if len(row) > name_start else ""
            if state_cell:
                pairs.append((name_cell or None, state_cell))
        return pairs
    return []


def _admin_down_pnote_state(stdout: str) -> bool | None:
    """Whether the exact `admin_down` Critical Device is present and
    reporting a problem state -- never any other pnote, and never the
    aggregate "any problem device" fact. `None` when the read is not
    observed in any known shape (fail closed, same discipline as
    `parse_cphaprob_ia_list`).

    `False` is returned only when A5 *positively proves* `admin_down` is
    not in problem state -- either the sentence form's explicit "no pnotes
    in problem state" statement, or device/state pairs that are each
    provably not `admin_down` (a real name that isn't `admin_down`, in any
    state). If any parsed problem-state row carries no usable device name,
    the absence of the `admin_down` name among the *named* rows is not
    proof that `admin_down` itself is absent -- that row could be it. Such
    a row makes the whole read `None` (fail closed) unless `admin_down` was
    already positively identified by name elsewhere in the same output, in
    which case that positive identification stands regardless of what else
    is ambiguous."""
    text = str(stdout or "")
    pairs = _parse_pnote_block_pairs(text)
    if not pairs:
        pairs = _parse_pnote_table_pairs(text)
    if not pairs:
        if _PNOTE_NONE_IN_PROBLEM_RE.search(text):
            return False
        return None

    admin_down_problem = False
    unnamed_problem = False
    for name, state in pairs:
        if not state.strip().lower().startswith(_PNOTE_PROBLEM_PREFIXES):
            continue
        if name is not None and name.strip().lower() == _ADMIN_DOWN_DEVICE_NAME:
            admin_down_problem = True
        elif name is None:
            unnamed_problem = True
    if admin_down_problem:
        return True
    if unnamed_problem:
        return None
    return False


#: The two `OP.2.1`-approved CP-M1 / CP-M1-R primitives, verbatim, no `-p`
#: (deferred -- see the gate doc's "Persistence" section). Literal only,
#: never interpolated -- mirrors the discipline
#: `checkpoint.cp_preflight_battery.COMMAND_TEXT` already applies to every
#: class 0 read.
ADMIN_DOWN_COMMAND_TEXT = "clusterXL_admin down"
ADMIN_UP_COMMAND_TEXT = "clusterXL_admin up"


@dataclass
class RealClusterXLMemberSession:
    """`ClusterXLMemberSession` backed by one already-open `MemberSession`.

    `member_session` is owned by the caller (typically the same collection
    call site that already built it for the CP.0b read battery) -- this
    class never opens, closes, or replaces it.
    """

    member_session: MemberSession

    def read_role(self) -> MemberRoleReading:
        a3 = self.member_session.run(CPPreflightRead.A3_CPHAPROB_STAT)
        role = (
            _parse_clusterxl_runtime_role(str(a3.get("stdout") or ""), None)
            if a3.get("success")
            else None
        )

        a5 = self.member_session.run(CPPreflightRead.A5_PNOTE_LIST)
        admin_down_pnote_present: bool | None = None
        if a5.get("success"):
            admin_down_pnote_present = _admin_down_pnote_state(str(a5.get("stdout") or ""))

        return MemberRoleReading(
            role=role,
            admin_down_pnote_present=admin_down_pnote_present,
            read_failed=role is None,
        )

    def submit_admin_down(self) -> SubmissionConfirmation:
        return self._submit_once(ADMIN_DOWN_COMMAND_TEXT)

    def submit_admin_up(self) -> SubmissionConfirmation:
        return self._submit_once(ADMIN_UP_COMMAND_TEXT)

    def _submit_once(self, command_text: str) -> SubmissionConfirmation:
        session = self.member_session
        if session._shell is None:
            # No persistent Expert shell was ever established for this
            # member -- the one provable "never even attempted" case: no
            # send was ever issued because there was no channel to send it
            # on. This is the *only* CONFIRMED_NOT_SENT escape (OP.2.C1
            # safety correction) -- everything past this point is an
            # attempted send, whose outcome this module can never prove
            # negative.
            return SubmissionConfirmation.CONFIRMED_NOT_SENT

        # Same pacing discipline as MemberSession.run/run_vsenv: wait before
        # every command on this session except its first, never after.
        if session.command_invocations and session._sleep is not None:
            session._sleep(INTER_COMMAND_DELAY_SECONDS)
        session.command_invocations += 1

        try:
            session._run_command(command_text)
        except Exception:
            # A send was attempted on an already-established session and
            # something went wrong before/while/after it -- this is
            # attempted-send uncertainty, never proof the command didn't
            # reach the device (OP.2.C1 safety correction: an exception out
            # of an opened channel, including InteractiveSshSession.run's
            # own "execution_error" classification, no longer maps to
            # CONFIRMED_NOT_SENT).
            pass
        return SubmissionConfirmation.SUBMITTED_OR_AMBIGUOUS
