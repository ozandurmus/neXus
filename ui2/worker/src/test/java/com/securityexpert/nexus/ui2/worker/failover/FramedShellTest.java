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
                "echo __NX_[0-9a-f]{32}_BEGIN__; \\( cphaprob stat \\) 2>/dev/null; echo __NX_[0-9a-f]{32}_END_\\$\\?__"),
                scripted.lines.get(0));
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
        assertEquals(1, scripted.interrupts, "Ctrl-C before the close (review P2-1)");
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
        assertEquals("SHELL_CLOSED", ((ExecResult.ChannelFailed) shell.run("cphaprob stat", TIMEOUT, BOUND)).reason());
        assertTrue(shell.broken());
        // Closed after the shell was ready: the send fails.
        var later = answering("ok\n", 0);
        var ready = shell(later);
        ready.run("fw stat", TIMEOUT, BOUND);
        later.closed = true;
        assertEquals("SHELL_SEND_FAILED", ((ExecResult.ChannelFailed) ready.run("fw stat", TIMEOUT, BOUND)).reason());
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

    // ---- Review response (ARGON_REVIEW_2026_10_22_CP_ONE_SHELL.md) ----

    @Test
    void carriageReturnAndBackspaceOverwriteAsATerminalShows() {
        assertEquals("table-row", FramedShell.clean("spinner\rtable-row\r"));
        assertEquals("XYcdef", FramedShell.clean("abcdef\rXY"));
        assertEquals("XY", FramedShell.clean("abcdef\rXY\u001B[K"));
        assertEquals("new", FramedShell.clean("old text\u001B[2Knew"));
        assertEquals("abd", FramedShell.clean("abc\bd"));
        assertEquals("a\tb", FramedShell.clean("a\tb\u0007\r"));
        assertEquals("__NX_x_BEGIN__", FramedShell.clean("[Expert@FW-TEST-01:0]# \r\u001B[K__NX_x_BEGIN__\r"));
    }

    @Test
    void overwrittenPromptAndUnNewlinedPromptBeforeBeginStillFrame() {
        for (String before : java.util.List.of("prompt-redraw-text-longer-than-nothing\r\u001B[K", ScriptedShell.PROMPT)) {
            var scripted = new ScriptedShell(clock, (inner, begin, end) -> before + begin + "\r\nok\r\n" + end + "0__\r\n");
            var completed = assertInstanceOf(ExecResult.Completed.class, shell(scripted).run("fw stat", TIMEOUT, BOUND), before);
            assertEquals("ok\n", completed.output());
        }
        assertTrue(FramedShell.isBegin("[Expert@FW-TEST-01:0]# __NX_x_BEGIN__", "__NX_x_BEGIN__"));
        assertFalse(FramedShell.isBegin("# echo __NX_x_BEGIN__", "__NX_x_BEGIN__"), "a cut-off echo is not BEGIN");
        assertFalse(FramedShell.isBegin("echo __NX_x_BEGIN__; ( fw stat ) 2>/dev/null; echo __NX_x_END_$?__", "__NX_x_BEGIN__"));
    }

    @Test
    void bannerAndFirstPromptAreDrainedBeforeTheFirstCommand() {
        var scripted = answering("ok\n", 0);
        var shell = shell(scripted);
        shell.run("fw stat", TIMEOUT, BOUND);
        assertTrue(clock.get() >= FramedShell.READY_QUIET.toNanos(), "waits for quiet after the banner and prompt");
        long afterFirst = clock.get();
        shell.run("fw stat", TIMEOUT, BOUND);
        assertEquals(afterFirst, clock.get(), "only the first command waits for the shell to be ready");
        var silent = new ScriptedShell(clock, (inner, begin, end) -> ScriptedShell.framed(begin, end, "ok\n", 0)) {
            { while (read(new byte[8192], Duration.ZERO) > 0) { } }
        };
        long start = clock.get();
        assertInstanceOf(ExecResult.Completed.class, shell(silent).run("fw stat", TIMEOUT, BOUND));
        assertTrue(clock.get() - start >= FramedShell.READY_MAX.toNanos(), "a silent shell is tried after READY_MAX");
    }

    @Test
    void stderrIsDroppedExceptForTheReadThatHadAPtyOnExec() {
        var scripted = answering("ok\n", 0);
        var shell = shell(scripted);
        shell.run("cphaprob stat", false, TIMEOUT, BOUND);
        shell.run("cphaprob -a if", true, TIMEOUT, BOUND);
        assertEquals(java.util.List.of("2>/dev/null", "2>&1"), scripted.stderrRules);
        assertEquals(java.util.List.of("cphaprob stat", "cphaprob -a if"), scripted.inner);
    }

    @Test
    void interruptedReadRestoresTheInterruptFlag() {
        var interrupting = new ScriptedShell(clock, (inner, begin, end) -> null) {
            boolean sentOnce;
            @Override public void sendLine(String line) throws java.io.IOException { super.sendLine(line); sentOnce = true; }
            @Override public int read(byte[] buffer, Duration wait) {
                if (sentOnce) throw new java.io.UncheckedIOException(new java.io.InterruptedIOException("synthetic"));
                return super.read(buffer, wait);
            }
        };
        var shell = new FramedShell(new InterruptOnRead(interrupting), new SecureRandom(), clock::get);
        var failed = assertInstanceOf(ExecResult.ChannelFailed.class, shell.run("fw stat", TIMEOUT, BOUND));
        assertEquals("INTERRUPTED", failed.reason());
        assertTrue(Thread.interrupted(), "interrupt flag restored (and cleared here)");
        assertTrue(shell.broken());
    }

    /** Turns the scripted shell's unchecked marker into the InterruptedIOException a real channel throws. */
    private record InterruptOnRead(ScriptedShell delegate) implements com.securityexpert.nexus.ui2.jobs.transport.ShellChannel {
        @Override public void sendLine(String line) throws java.io.IOException { delegate.sendLine(line); }
        @Override public int read(byte[] buffer, Duration wait) throws java.io.IOException {
            try { return delegate.read(buffer, wait); }
            catch (java.io.UncheckedIOException wrapped) { throw wrapped.getCause(); }
        }
        @Override public void close() { delegate.close(); }
    }
}
