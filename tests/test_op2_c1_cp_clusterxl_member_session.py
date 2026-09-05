"""OP.2.C follow-up -- `checkpoint.clusterxl_member_session.RealClusterXLMemberSession`.

Exercises the real `ClusterXLMemberSession` implementation
(`checkpoint.clusterxl_capability_adapter.ClusterXLMemberSession`) that backs
onto the existing, real-environment-validated per-member
`checkpoint.preflight_collector.MemberSession` transport. No test opens a
real socket or SSH client -- every `MemberSession` here is built directly
against an in-module fake `_run_command`/`_sleep`, the same discipline
`tests/test_op0b_s5_cp_preflight_collector.py` already uses for the
collector itself.

Covers: command resolution (the two literal CP-M1/CP-M1-R primitives, never
wrapped in `clish -c`), exact one-shot submission behavior (no retry, no
resend), read framing/exit-status handling for the A3/A5 verification reads,
transport-failure propagation to `SubmissionConfirmation.CONFIRMED_NOT_SENT`,
and session reuse (one `MemberSession`, no reconnect, no nested SSH client)
across both reads and submissions.
"""
from __future__ import annotations

import pytest

from checkpoint.clusterxl_capability_adapter import SubmissionConfirmation
from checkpoint.clusterxl_member_session import (
    ADMIN_DOWN_COMMAND_TEXT,
    ADMIN_UP_COMMAND_TEXT,
    RealClusterXLMemberSession,
)
from checkpoint.preflight_collector import INTER_COMMAND_DELAY_SECONDS, MemberSession

pytestmark = pytest.mark.operate

_A3_ACTIVE = "\n".join([
    "Cluster mode: High Availability (Active Up) with IGMP Membership",
    "ID  Unique Address  Assigned Load  State",
    "1 (local)  192.168.1.1  100%  ACTIVE",
    "2  192.168.1.2  0%  STANDBY",
])
_A3_DOWN = "\n".join([
    "Cluster mode: High Availability (Active Up) with IGMP Membership",
    "ID  Unique Address  Assigned Load  State",
    "1 (local)  192.168.1.1  0%  DOWN",
    "2  192.168.1.2  100%  ACTIVE",
])
_A5_NO_PROBLEM = "There are no pnotes in problem state"
_A5_ADMIN_DOWN = "Current state: problem"


class _FakeShell:
    """Stand-in for the persistent Expert shell -- only ever used as a
    truthy/None sentinel by the code under test, never invoked directly."""


def _ok(stdout: str) -> dict:
    return {"success": True, "error_class": "none", "stdout": stdout, "stderr": "", "exit_status": 0}


def _cli_rejected() -> dict:
    return {"success": False, "error_class": "cli_rejected", "stdout": "", "stderr": "", "exit_status": None}


def _send_failed() -> dict:
    """`InteractiveSshSession.run`'s own classification for a `channel.send`
    that raised before any device interaction -- the one positively-proven
    "never reached the device" transport failure."""
    return {
        "success": False, "error_class": "execution_error", "error_detail": "OSError",
        "stdout": "", "stderr": "", "exit_status": None,
    }


class _ScriptedRunCommand:
    """Records every command text it is called with, in order, and returns
    the next scripted result (or a default) -- lets a test assert exactly
    which commands were issued, in what order, and how many times."""

    def __init__(self, *, responses: dict[str, dict] | None = None, default: dict | None = None):
        self.calls: list[str] = []
        self._responses = dict(responses or {})
        self._default = default if default is not None else _ok("ok")

    def __call__(self, command_text: str) -> dict:
        self.calls.append(command_text)
        return self._responses.get(command_text, self._default)


def _make_session(run_command, *, with_shell: bool = True) -> tuple[MemberSession, list[float]]:
    sleeps: list[float] = []
    session = MemberSession(
        physical_device_identity="member-a",
        _run_command=run_command,
        _sleep=sleeps.append,
        _shell=_FakeShell() if with_shell else None,
    )
    return session, sleeps


# ---------------------------------------------------------------------------
# Command resolution
# ---------------------------------------------------------------------------


def test_admin_down_and_up_command_text_are_the_op21_approved_literals():
    assert ADMIN_DOWN_COMMAND_TEXT == "clusterXL_admin down"
    assert ADMIN_UP_COMMAND_TEXT == "clusterXL_admin up"
    # No `-p` (deferred per the gate doc), no clish wrapping -- Expert-shell
    # script form, verbatim.
    for text in (ADMIN_DOWN_COMMAND_TEXT, ADMIN_UP_COMMAND_TEXT):
        assert "-p" not in text.split()
        assert not text.startswith("clish")


def test_submit_admin_down_issues_exactly_the_admin_down_literal():
    run_command = _ScriptedRunCommand()
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    real.submit_admin_down()

    assert run_command.calls == [ADMIN_DOWN_COMMAND_TEXT]


def test_submit_admin_up_issues_exactly_the_admin_up_literal():
    run_command = _ScriptedRunCommand()
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    real.submit_admin_up()

    assert run_command.calls == [ADMIN_UP_COMMAND_TEXT]


# ---------------------------------------------------------------------------
# Exact one-shot submission behavior -- no retry, no resend
# ---------------------------------------------------------------------------


def test_submit_admin_down_calls_the_transport_exactly_once():
    run_command = _ScriptedRunCommand()
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    confirmation = real.submit_admin_down()

    assert len(run_command.calls) == 1
    assert confirmation == SubmissionConfirmation.SUBMITTED_OR_AMBIGUOUS
    assert session.command_invocations == 1


def test_submit_never_retries_on_a_cli_rejected_response():
    run_command = _ScriptedRunCommand(responses={ADMIN_DOWN_COMMAND_TEXT: _cli_rejected()})
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    confirmation = real.submit_admin_down()

    assert len(run_command.calls) == 1
    assert confirmation == SubmissionConfirmation.SUBMITTED_OR_AMBIGUOUS


def test_repeated_calls_never_resend_a_prior_submission_automatically():
    """This module offers no auto-retry primitive: calling submit_admin_down
    twice is two deliberate caller-issued submissions, not a hidden retry --
    proving the transport sees exactly one call per explicit invocation."""
    run_command = _ScriptedRunCommand()
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    real.submit_admin_down()
    assert run_command.calls == [ADMIN_DOWN_COMMAND_TEXT]
    real.submit_admin_down()
    assert run_command.calls == [ADMIN_DOWN_COMMAND_TEXT, ADMIN_DOWN_COMMAND_TEXT]


# ---------------------------------------------------------------------------
# Read framing / exit-status handling (A3 role, A5 pnote)
# ---------------------------------------------------------------------------


def test_read_role_active_with_no_pnote_problem():
    run_command = _ScriptedRunCommand(responses={
        "cphaprob stat": _ok(_A3_ACTIVE),
        "cphaprob -ia list": _ok(_A5_NO_PROBLEM),
    })
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    reading = real.read_role()

    assert reading.role == "ACTIVE"
    assert reading.admin_down_pnote_present is False
    assert reading.read_failed is False
    assert run_command.calls == ["cphaprob stat", "cphaprob -ia list"]


def test_read_role_down_with_admin_down_pnote_present():
    run_command = _ScriptedRunCommand(responses={
        "cphaprob stat": _ok(_A3_DOWN),
        "cphaprob -ia list": _ok(_A5_ADMIN_DOWN),
    })
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    reading = real.read_role()

    assert reading.role == "DOWN"
    assert reading.admin_down_pnote_present is True
    assert reading.read_failed is False


def test_read_role_marks_read_failed_when_a3_fails_regardless_of_a5():
    run_command = _ScriptedRunCommand(responses={
        "cphaprob stat": _cli_rejected(),
        "cphaprob -ia list": _ok(_A5_NO_PROBLEM),
    })
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    reading = real.read_role()

    assert reading.role is None
    assert reading.read_failed is True


def test_read_role_pnote_present_is_none_when_a5_fails():
    run_command = _ScriptedRunCommand(responses={
        "cphaprob stat": _ok(_A3_ACTIVE),
        "cphaprob -ia list": _cli_rejected(),
    })
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    reading = real.read_role()

    assert reading.role == "ACTIVE"
    assert reading.admin_down_pnote_present is None
    assert reading.read_failed is False


def test_read_role_paces_between_a3_and_a5_but_not_before_a3():
    run_command = _ScriptedRunCommand(responses={
        "cphaprob stat": _ok(_A3_ACTIVE),
        "cphaprob -ia list": _ok(_A5_NO_PROBLEM),
    })
    session, sleeps = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    real.read_role()

    assert sleeps == [INTER_COMMAND_DELAY_SECONDS]


# ---------------------------------------------------------------------------
# Transport failure propagation -- CONFIRMED_NOT_SENT is the narrow escape
# ---------------------------------------------------------------------------


def test_no_established_shell_is_confirmed_not_sent_without_touching_transport():
    run_command = _ScriptedRunCommand()
    session, _ = _make_session(run_command, with_shell=False)
    real = RealClusterXLMemberSession(member_session=session)

    confirmation = real.submit_admin_down()

    assert confirmation == SubmissionConfirmation.CONFIRMED_NOT_SENT
    assert run_command.calls == []


def test_send_failed_before_device_contact_is_confirmed_not_sent():
    run_command = _ScriptedRunCommand(responses={ADMIN_DOWN_COMMAND_TEXT: _send_failed()})
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    confirmation = real.submit_admin_down()

    assert confirmation == SubmissionConfirmation.CONFIRMED_NOT_SENT
    assert len(run_command.calls) == 1  # attempted once, still no retry


def test_transport_exception_is_confirmed_not_sent_not_propagated():
    def _raising(_command_text: str) -> dict:
        raise ConnectionError("channel closed")

    session, _ = _make_session(_raising)
    real = RealClusterXLMemberSession(member_session=session)

    confirmation = real.submit_admin_down()

    assert confirmation == SubmissionConfirmation.CONFIRMED_NOT_SENT


@pytest.mark.parametrize("result", [_cli_rejected(), _ok("done"), {
    "success": False, "error_class": "timeout", "stdout": "", "stderr": "", "exit_status": None,
}])
def test_every_other_transport_outcome_is_submitted_or_ambiguous_never_not_sent(result):
    run_command = _ScriptedRunCommand(responses={ADMIN_UP_COMMAND_TEXT: result})
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    confirmation = real.submit_admin_up()

    assert confirmation == SubmissionConfirmation.SUBMITTED_OR_AMBIGUOUS


# ---------------------------------------------------------------------------
# Session reuse -- one MemberSession, no reconnect, no nested SSH client
# ---------------------------------------------------------------------------


def test_read_and_submit_share_the_same_member_session_invocation_count():
    run_command = _ScriptedRunCommand(responses={
        "cphaprob stat": _ok(_A3_ACTIVE),
        "cphaprob -ia list": _ok(_A5_NO_PROBLEM),
    })
    session, _ = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    real.read_role()
    assert session.command_invocations == 2

    real.submit_admin_down()
    assert session.command_invocations == 3
    # Same session object throughout -- no second MemberSession/shell.
    assert real.member_session is session


def test_submission_paces_after_prior_reads_on_the_same_session():
    run_command = _ScriptedRunCommand(responses={
        "cphaprob stat": _ok(_A3_ACTIVE),
        "cphaprob -ia list": _ok(_A5_NO_PROBLEM),
    })
    session, sleeps = _make_session(run_command)
    real = RealClusterXLMemberSession(member_session=session)

    real.read_role()  # 2 invocations, 1 inter-read pace
    real.submit_admin_down()  # 3rd invocation on the same session -> paced too

    assert sleeps == [INTER_COMMAND_DELAY_SECONDS, INTER_COMMAND_DELAY_SECONDS]


def test_two_physical_members_never_share_a_session_or_transport():
    run_command_a = _ScriptedRunCommand()
    run_command_b = _ScriptedRunCommand()
    session_a, _ = _make_session(run_command_a)
    session_b, _ = _make_session(run_command_b)
    real_a = RealClusterXLMemberSession(member_session=session_a)
    real_b = RealClusterXLMemberSession(member_session=session_b)

    real_a.submit_admin_down()
    real_b.submit_admin_up()

    assert run_command_a.calls == [ADMIN_DOWN_COMMAND_TEXT]
    assert run_command_b.calls == [ADMIN_UP_COMMAND_TEXT]
