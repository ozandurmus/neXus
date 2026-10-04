"""SecurityExpert -- OP.0b S8-A, execution-context capability gap.

Real-environment root cause, from the device's own `clish`/`xpand` audit
trail for one 8-read member battery:

    clish[...]: cmd by <user>: Start executing : ver
    clish[...]: cmd by <user>: Start executing : show hostname       <- A1
    clish[...]: cmd by <user>: Start executing : ver
    clish[...]: cmd by <user>: Start executing : show version ...    <- A2
    clish[...]: cmd by <user>: Start executing : ver                 <- A3 (only)
    clish[...]: cmd by <user>: Start executing : ver                 <- A4 (only)
    clish[...]: cmd by <user>: Start executing : ver                 <- A5 (only)
    clish[...]: cmd by <user>: Start executing : ver                 <- A6 (only)
    clish[...]: cmd by <user>: Start executing : ver                 <- A7 (only)
    clish[...]: cmd by <user>: Start executing : show cluster ...    <- A8

Eight exec channels, eight device-side `clish -c ver` initializations (one
per channel, from the account's Gaia CLI login-shell wrapper), and *only*
the three `clish -c '...'` forms ever reaching a command. The five bare
Expert reads never appear in the device's command log at all: Clish rejects
them before any Check Point binary runs.

This is a capability gap of the SSH *account*, not a transport, `$PATH` or
parser defect, and it is deliberately not repairable from here -- reaching
Expert from Clish needs the expert password (a new credential path) and
changing the account's shell is a device mutation. Both are PO hard-stop
conditions. These tests pin that the product *reports* the gap truthfully
and never tries to route around it.
"""
from __future__ import annotations

import inspect
import time

import checkpoint.preflight_collector as pc
from checkpoint.cp_preflight_battery import COMMAND_TEXT, CPPreflightRead
from checkpoint.preflight_collector import classify_execution_context_gap

#: The five reads the device rejected, and the three it executed.
EXPERT_READS = (
    CPPreflightRead.A3_CPHAPROB_STAT,
    CPPreflightRead.A4_LINK_IF,
    CPPreflightRead.A5_PNOTE_LIST,
    CPPreflightRead.A6_SYNCSTAT,
    CPPreflightRead.A7_FW_STAT,
)
CLISH_READS = (
    CPPreflightRead.A1_HOSTNAME,
    CPPreflightRead.A2_VERSION,
    CPPreflightRead.A8_CLISH_FAILOVER,
)

#: Gaia Clish's real rejection shape for a command outside its grammar.
CLISH_REJECTION = {
    "success": False,
    "stdout": "CLINFR0329  Invalid command:'cphaprob stat'.\n",
    "stderr": "",
}


class TestGapDetection:

    def test_every_expert_read_is_recognised_as_a_gap(self):
        for read in EXPERT_READS:
            assert classify_execution_context_gap(COMMAND_TEXT[read], CLISH_REJECTION), read

    def test_a_successful_read_is_never_a_gap(self):
        ok = {"success": True, "stdout": "anything", "stderr": ""}
        for read in EXPERT_READS:
            assert not classify_execution_context_gap(COMMAND_TEXT[read], ok)

    def test_a_clish_form_read_is_never_a_gap(self):
        """`clish -c '...'` reads execute under either login shell, so a
        failure there is a real failure and must keep saying so."""
        for read in CLISH_READS:
            assert not classify_execution_context_gap(COMMAND_TEXT[read], CLISH_REJECTION)

    def test_a_genuine_device_error_stays_a_failure(self):
        """Fail-closed: only Clish's own rejection vocabulary reclassifies a
        read. A timeout, a transport error or an unparseable answer is still
        a failure, never softened into a capability gap."""
        for result in (
            {"success": False, "stdout": "", "stderr": ""},
            {"success": False, "error_class": "timeout", "stdout": "", "stderr": ""},
            {"success": False, "stdout": "cphaprob: cluster module not loaded", "stderr": ""},
        ):
            for read in EXPERT_READS:
                assert not classify_execution_context_gap(COMMAND_TEXT[read], result)

    def test_stderr_is_examined_too(self):
        result = {"success": False, "stdout": "", "stderr": "Unknown command\n"}
        assert classify_execution_context_gap(COMMAND_TEXT[CPPreflightRead.A7_FW_STAT], result)


class TestNoRoutingAroundTheGap:
    """The gap is reported, never worked around. Detecting it must not
    become authority to retry, escalate privilege, or change the device."""

    def test_detection_performs_no_io(self):
        """Checked against the executable body only -- the docstring
        legitimately *names* the boundaries this must not cross."""
        called = set(classify_execution_context_gap.__code__.co_names)
        for forbidden in ("_run_exec", "exec_command", "connect", "sleep", "Popen"):
            assert forbidden not in called, f"gap detection must only classify: {forbidden!r}"

    def test_no_expert_escalation_anywhere_in_the_collector(self):
        """No credential path or shell escape may appear in the collector,
        whatever the gap tempts a future change toward."""
        src = inspect.getsource(pc)
        for forbidden in ("expert_password", "send('expert", 'send("expert', "set user "):
            assert forbidden not in src, f"hard-stop boundary crossed: {forbidden!r}"


class TestInterCommandPacing:
    """Deterministic pacing between approved reads.

    Real-environment evidence: the battery executes correctly inside one
    persistent Expert shell, but issued back to back it destabilizes the SSH
    session. Pacing is therefore intentional production courtesy -- and it is
    strictly *between* reads, after deterministic completion of the previous
    one. It is never retry, backoff, reconnect, or adaptive."""

    def _paced(self, calls, invocations):
        session = pc.MemberSession(
            physical_device_identity="member-a",
            _run_command=lambda _text: {"success": True, "stdout": "", "stderr": "",
                                        "error_class": "none", "timeout": False,
                                        "exit_status": 0},
            _sleep=calls.append,
        )
        for read in invocations:
            session.run(read)
        return session

    def test_n_commands_produce_n_minus_one_waits(self):
        reads = [CPPreflightRead.A1_HOSTNAME, CPPreflightRead.A2_VERSION,
                 CPPreflightRead.A3_CPHAPROB_STAT, CPPreflightRead.A7_FW_STAT]
        calls: list[float] = []
        self._paced(calls, reads)
        assert len(calls) == len(reads) - 1

    def test_first_read_has_no_pre_delay(self):
        calls: list[float] = []
        self._paced(calls, [CPPreflightRead.A1_HOSTNAME])
        assert calls == [], "no delay before the first read"

    def test_no_delay_after_the_final_read(self):
        """Structural, not incidental: the wait happens before a *next* send,
        so a battery can never end on a sleep."""
        src = inspect.getsource(pc.MemberSession.run)
        assert src.index("_sleep(") < src.index("_run_command("), src

    def test_pacing_uses_the_single_named_constant(self):
        assert pc.INTER_COMMAND_DELAY_SECONDS == 0.3
        assert "INTER_COMMAND_DELAY_SECONDS" in inspect.getsource(pc.MemberSession.run)

    def test_delay_value_is_exactly_the_constant(self):
        calls: list[float] = []
        self._paced(calls, [CPPreflightRead.A1_HOSTNAME, CPPreflightRead.A2_VERSION])
        assert calls == [pc.INTER_COMMAND_DELAY_SECONDS]

    def test_pacing_follows_completion_never_precedes_it(self):
        """`_run_command` returns only on deterministic nonce/exit-status
        completion, so a wait can only ever follow a completed read."""
        order: list[str] = []
        session = pc.MemberSession(
            physical_device_identity="member-a",
            _run_command=lambda _t: order.append("complete") or {
                "success": True, "stdout": "", "stderr": "",
                "error_class": "none", "timeout": False, "exit_status": 0},
            _sleep=lambda _s: order.append("wait"),
        )
        for read in (CPPreflightRead.A1_HOSTNAME, CPPreflightRead.A2_VERSION,
                     CPPreflightRead.A3_CPHAPROB_STAT):
            session.run(read)
        assert order == ["complete", "wait", "complete", "wait", "complete"]

    def test_a_failed_read_is_never_retried(self):
        """Pacing must never become retry authority."""
        issued: list[str] = []
        session = pc.MemberSession(
            physical_device_identity="member-a",
            _run_command=lambda text: issued.append(text) or {
                "success": False, "stdout": "", "stderr": "",
                "error_class": "command_error", "timeout": False, "exit_status": 1},
            _sleep=lambda _s: None,
        )
        session.run(CPPreflightRead.A3_CPHAPROB_STAT)
        assert len(issued) == 1, "a failed read is recorded, never re-issued"

    def test_no_adaptive_or_configurable_pacing(self):
        """Executable body only -- the constant's own comment legitimately
        names the mechanisms it is not."""
        called = set(pc.MemberSession.run.__code__.co_names)
        for forbidden in ("getenv", "environ", "uniform", "monotonic"):
            assert forbidden not in called, f"pacing must stay one plain constant: {forbidden!r}"
        src = inspect.getsource(pc)
        for forbidden in ("retry_delay", "max_delay", "backoff_factor"):
            assert forbidden not in src, f"pacing must stay one plain constant: {forbidden!r}"

    def test_real_session_binds_a_sleeper_resolved_at_call_time(self):
        """Not a frozen default: production gets `time.sleep`, tests inject."""
        sig = inspect.signature(pc.make_real_member_session)
        assert sig.parameters["sleeper"].default is None
        assert "time.sleep" in inspect.getsource(pc.make_real_member_session)
