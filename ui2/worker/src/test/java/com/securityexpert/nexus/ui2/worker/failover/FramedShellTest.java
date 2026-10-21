package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;

/** Sentinel framing on a scripted PTY shell (CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md §3.2). Synthetic text only. */
class FramedShellTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int BOUND = 262144;
    private final AtomicLong clock = new AtomicLong();

    private FramedShell shell(ScriptedShell scripted) {
        return new FramedShell(scripted, new SecureRandom(), clock::get);
    }

    private ScriptedShell answering(String output, int status) {
        return new ScriptedShell(clock, (inner, begin, end) -> ScriptedShell.framed(begin, end, output, status));
    }

    @Test
    void outputAndExitStatusComeOnlyFromBetweenTheSentinels() {
        var scripted = answering("Cluster Mode: High Availability (Active Up)\n1 (local) 192.0.2.11 100% Active\n", 0);
        var shell = shell(scripted);
        var result = shell.run("cphaprob stat", TIMEOUT, BOUND);
        var completed = assertInstanceOf(ExecResult.Completed.class, result);
        assertEquals("Cluster Mode: High Availability (Active Up)\n1 (local) 192.0.2.11 100% Active\n", completed.output());
        assertEquals(0, completed.exitStatus());
        assertEquals(java.util.List.of("cphaprob stat"), scripted.inner);
        assertFalse(shell.broken());
        assertTrue(scripted.lines.get(0).matches(
                "echo __NX_[0-9a-f]{32}_BEGIN__; cphaprob stat; echo __NX_[0-9a-f]{32}_END_\\$\\?__"));
    }

    @Test
    void nonZeroExitStatusIsReportedNotGuessed() {
        var result = shell(answering("synthetic: command not found\n", 127)).run("cphaprob stat", TIMEOUT, BOUND);
        var completed = assertInstanceOf(ExecResult.Completed.class, result);
        assertEquals(127, completed.exitStatus());
        assertEquals("synthetic: command not found\n", completed.output());
    }

    @Test
    void echoBannerPromptEscapesAndCarriageReturnsAreStripped() {
        String colored = "\u001B[1mbond1\u001B[0m\t| UP\r\n\u001B(Bline two\u0007\n";
        var scripted = new ScriptedShell(clock, (inner, begin, end) -> begin + "\r\n" + colored + end + "0__\r\n"
                + ScriptedShell.PROMPT);
        scripted.chunk = 3;
        var completed = assertInstanceOf(ExecResult.Completed.class, shell(scripted).run("cphaprob show_bond", TIMEOUT, BOUND));
        assertEquals("bond1\t| UP\nline two\n", completed.output());
    }

    @Test
    void lastLineWithoutNewlineKeepsItsText() {
        var scripted = new ScriptedShell(clock, (inner, begin, end) -> begin + "\r\nfirst\r\nno newline" + end + "0__\r\n");
        var completed = assertInstanceOf(ExecResult.Completed.class, shell(scripted).run("fw stat", TIMEOUT, BOUND));
        assertEquals("first\nno newline", completed.output());
    }

    @Test
    void commandsRunInOrderOnOneShellWithAFreshNoncePerCommand() {
        var scripted = new ScriptedShell(clock, (inner, begin, end) -> ScriptedShell.framed(begin, end, inner + "-out\n", 0));
        var shell = shell(scripted);
        assertEquals("a-out\n", ((ExecResult.Completed) shell.run("a", TIMEOUT, BOUND)).output());
        assertEquals("b-out\n", ((ExecResult.Completed) shell.run("b", TIMEOUT, BOUND)).output());
        assertEquals(2, shell.sent());
        String first = scripted.lines.get(0).substring(5, 5 + 32 + 5);
        String second = scripted.lines.get(1).substring(5, 5 + 32 + 5);
        assertNotEquals(first, second);
        assertEquals(0, scripted.closes);
    }

    @Test
    void deadlineIsTheCommandTimeoutAndClosesTheShell() {
        var scripted = new ScriptedShell(clock, (inner, begin, end) -> begin + "\r\npartial output\r\n");
        var shell = shell(scripted);
        assertInstanceOf(ExecResult.TimedOut.class, shell.run("cphaprob stat", Duration.ofSeconds(5), BOUND));
        assertTrue(clock.get() >= Duration.ofSeconds(5).toNanos());
        assertTrue(clock.get() < Duration.ofSeconds(6).toNanos());
        assertTrue(shell.broken());
        assertEquals(1, scripted.closes);
    }

    @Test
    void oversizedOutputFailsAndClosesTheShell() {
        String big = "x".repeat(100) + "\n";
        var scripted = new ScriptedShell(clock, (inner, begin, end) -> ScriptedShell.framed(begin, end, big.repeat(20), 0));
        var shell = shell(scripted);
        var failed = assertInstanceOf(ExecResult.ChannelFailed.class, shell.run("arp -an", TIMEOUT, 1000));
        assertEquals("OUTPUT_LIMIT_EXCEEDED", failed.reason());
        assertTrue(shell.broken());
        var noNewline = new ScriptedShell(clock, (inner, begin, end) -> begin + "\r\n" + "y".repeat(20_000));
        var other = shell(noNewline);
        assertEquals("OUTPUT_LIMIT_EXCEEDED",
                ((ExecResult.ChannelFailed) other.run("arp -an", TIMEOUT, 1000)).reason());
    }

    @Test
    void oversizedPreambleFails() {
        var scripted = new ScriptedShell(clock, (inner, begin, end) -> "noise\r\n".repeat(20_000));
        var shell = shell(scripted);
        assertEquals("SHELL_PREAMBLE_LIMIT_EXCEEDED",
                ((ExecResult.ChannelFailed) shell.run("arp -an", TIMEOUT, BOUND)).reason());
        assertTrue(shell.broken());
    }

    @Test
    void desyncIsAFailureAndTheShellIsNeverReused() {
        for (String fault : java.util.List.of("end-before-begin", "second-begin", "status-out-of-range")) {
            var scripted = new ScriptedShell(clock, (inner, begin, end) -> switch (fault) {
                case "end-before-begin" -> end + "0__\r\n" + begin + "\r\nok\r\n" + end + "0__\r\n";
                case "second-begin" -> begin + "\r\nok\r\n" + begin + "\r\n" + end + "0__\r\n";
                default -> begin + "\r\nok\r\n" + end + "999__\r\n";
            });
            var shell = shell(scripted);
            var failed = assertInstanceOf(ExecResult.ChannelFailed.class, shell.run("cphaprob stat", TIMEOUT, BOUND), fault);
            assertEquals("SHELL_FRAMING_DESYNC", failed.reason(), fault);
            assertTrue(shell.broken(), fault);
            assertEquals(1, scripted.closes, fault);
            var again = assertInstanceOf(ExecResult.ChannelFailed.class, shell.run("cphaprob stat", TIMEOUT, BOUND));
            assertEquals("SHELL_CLOSED", again.reason());
            assertEquals(1, scripted.lines.size(), "Nothing may be sent on a shell after a desync: " + fault);
        }
    }

    @Test
    void anEndMarkerFromAnotherNonceIsOutputNotAnEnd() {
        var scripted = new ScriptedShell(clock, (inner, begin, end) -> begin + "\r\n__NX_" + "0".repeat(32) + "_END_0__\r\n"
                + end + "0__\r\n");
        var completed = assertInstanceOf(ExecResult.Completed.class, shell(scripted).run("cphaprob stat", TIMEOUT, BOUND));
        assertEquals("__NX_" + "0".repeat(32) + "_END_0__\n", completed.output());
    }

    @Test
    void closedChannelFailsAndMultiLineCommandsAreNeverSent() {
        var scripted = answering("ok\n", 0);
        var shell = shell(scripted);
        assertEquals("COMMAND_NOT_SENDABLE",
                ((ExecResult.ChannelFailed) shell.run("cphaprob stat\nreboot", TIMEOUT, BOUND)).reason());
        assertTrue(scripted.lines.isEmpty());
        assertFalse(shell.broken());
        scripted.closed = true;
        assertEquals("SHELL_SEND_FAILED", ((ExecResult.ChannelFailed) shell.run("cphaprob stat", TIMEOUT, BOUND)).reason());
        assertTrue(shell.broken());
    }

    @Test
    void refusedSendClosesTheShellAndPropagates() {
        var refusing = new ScriptedShell(clock, (inner, begin, end) -> null) {
            @Override
            public void sendLine(String line) {
                throw new IllegalStateException("SYNTHETIC_ADMISSION_REFUSED");
            }
        };
        var shell = shell(refusing);
        assertThrows(IllegalStateException.class, () -> shell.run("cphaprob stat", TIMEOUT, BOUND));
        assertTrue(shell.broken());
        assertEquals(1, refusing.closes);
    }
}
