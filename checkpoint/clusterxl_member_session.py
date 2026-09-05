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

**Submission outcome.** `SubmissionConfirmation` is the same two-way split
`OP.2.0` P6/P7 already fix: `CONFIRMED_NOT_SENT` only when this module can
positively prove the command never reached the device (no established
shell, or the underlying send itself failed before any device interaction
-- `InteractiveSshSession.run`'s own `"execution_error"` classification, or
an exception raised out of the run call itself); every other outcome
(timeout, non-zero exit status, a CLI-rejected/empty response, or an
ordinary success) is `SUBMITTED_OR_AMBIGUOUS` -- this module never
distinguishes further, exactly as `SubmissionConfirmation`'s own docstring
requires. Exactly one call to the underlying transport per submission --
no retry, no resend, under any circumstance.
"""
from __future__ import annotations

from dataclasses import dataclass

from checkpoint.clusterxl_capability_adapter import (
    MemberRoleReading,
    SubmissionConfirmation,
)
from checkpoint.cp_preflight_battery import CPPreflightRead
from checkpoint.cp_preflight_extraction import parse_cphaprob_ia_list
from checkpoint.preflight_collector import INTER_COMMAND_DELAY_SECONDS, MemberSession
from configuration.checkpoint_config_collector import _parse_clusterxl_runtime_role

__all__ = [
    "ADMIN_DOWN_COMMAND_TEXT",
    "ADMIN_UP_COMMAND_TEXT",
    "RealClusterXLMemberSession",
]

#: The two `OP.2.1`-approved CP-M1 / CP-M1-R primitives, verbatim, no `-p`
#: (deferred -- see the gate doc's "Persistence" section). Literal only,
#: never interpolated -- mirrors the discipline
#: `checkpoint.cp_preflight_battery.COMMAND_TEXT` already applies to every
#: class 0 read.
ADMIN_DOWN_COMMAND_TEXT = "clusterXL_admin down"
ADMIN_UP_COMMAND_TEXT = "clusterXL_admin up"

#: `InteractiveSshSession.run`'s own classification for "the send itself
#: failed" -- the one case that positively proves the command never reached
#: the device (`configuration.checkpoint_config_collector.InteractiveSshSession.run`).
_SEND_FAILED_ERROR_CLASS = "execution_error"


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
            pnotes = parse_cphaprob_ia_list(str(a5.get("stdout") or ""))
            if pnotes.get("observed"):
                admin_down_pnote_present = bool(pnotes.get("any_problem"))

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
            # member -- the one positive "never reached the device" case
            # OP.2.0 P7 carves out, same as an unopened transport.
            return SubmissionConfirmation.CONFIRMED_NOT_SENT

        # Same pacing discipline as MemberSession.run/run_vsenv: wait before
        # every command on this session except its first, never after.
        if session.command_invocations and session._sleep is not None:
            session._sleep(INTER_COMMAND_DELAY_SECONDS)
        session.command_invocations += 1

        try:
            result = session._run_command(command_text)
        except Exception:
            return SubmissionConfirmation.CONFIRMED_NOT_SENT

        if isinstance(result, dict) and result.get("error_class") == _SEND_FAILED_ERROR_CLASS:
            return SubmissionConfirmation.CONFIRMED_NOT_SENT
        return SubmissionConfirmation.SUBMITTED_OR_AMBIGUOUS
