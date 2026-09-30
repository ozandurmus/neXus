package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.PromptAnswer;

class InteractiveShellSessionReadTest {

    private static final String PROMPT = "FGT-TANGO-04 #";

    @Test
    void readsThreeMegabytesWithoutChangingOutput() {
        String body = "    set x \"value 192.0.2.1\"\n".repeat(110_000);
        ScriptedInput in = new ScriptedInput(false, body + PROMPT);
        ScriptedOutput out = new ScriptedOutput(in);
        InteractiveShellSession shell = new InteractiveShellSession(in, out, PROMPT);

        assertTimeout(Duration.ofSeconds(2), () -> {
            InteractiveShellSession.Result result = shell.runForResult("show", 5000);
            assertEquals(InteractiveShellSession.Result.Kind.OUTPUT, result.kind());
            assertEquals(body.strip(), result.text());
        });
    }

    @Test
    void promptTextInsideJsonDoesNotEndTheRead() {
        ScriptedInput in = new ScriptedInput(true, "{\"description\":\"FGT-TANGO-04 #",
                "\"}\n" + PROMPT);
        ScriptedOutput out = new ScriptedOutput(in);

        InteractiveShellSession.Result result = new InteractiveShellSession(in, out, PROMPT)
                .runForResult("show", 5000);

        assertEquals(InteractiveShellSession.Result.Kind.OUTPUT, result.kind());
        assertEquals("{\"description\":\"FGT-TANGO-04 #\"}", result.text());
    }

    @Test
    void answersEachPagerOnceAndRemovesItsMarker() {
        ScriptedInput in = new ScriptedInput(false,
                "first\n --More-- ", "second\n --More-- ", "third\n" + PROMPT);
        ScriptedOutput out = new ScriptedOutput(in);
        InteractiveShellSession.Result result = new InteractiveShellSession(in, out, PROMPT)
                .runForResult("show", 5000);

        assertEquals(InteractiveShellSession.Result.Kind.OUTPUT, result.kind());
        assertEquals("first\n second\n third", result.text());
        assertEquals(2, out.spaces);
    }

    @Test
    void completesWithPromptAndAnsiEscapeSplitAcrossReads() {
        ScriptedInput in = new ScriptedInput(true, "body\n\u001b[3", "1mFGT-TAN", "GO-04 #\u001b[0m");
        ScriptedOutput out = new ScriptedOutput(in);
        InteractiveShellSession.Result result = new InteractiveShellSession(in, out, PROMPT)
                .runForResult("show", 5000);

        assertEquals(InteractiveShellSession.Result.Kind.OUTPUT, result.kind());
        assertEquals("body", result.text());
    }

    @Test
    void answeringPathUsesTailAndSendsReplyOnce() {
        ScriptedInput in = new ScriptedInput(false, "Continue?", "body\n" + PROMPT);
        ScriptedOutput out = new ScriptedOutput(in);
        InteractiveShellSession.Result result = new InteractiveShellSession(in, out, PROMPT)
                .runAnswering("show", List.of(new PromptAnswer("Continue?", new char[] {'y'})), 5000);

        assertEquals(InteractiveShellSession.Result.Kind.OUTPUT, result.kind());
        assertEquals("Continue?\nbody", result.text());
        assertEquals(2, out.newlines);
    }

    @Test
    void aLatePreviousPromptDoesNotEndTheNextCommand() {
        // PAN-OS 2026-09-27: a second repaint of the previous prompt arrives after the command is sent.
        ScriptedInput in = new ScriptedInput(true, PROMPT + " ", "show\nbody line\n" + PROMPT);
        ScriptedOutput out = new ScriptedOutput(in);
        InteractiveShellSession.Result result = new InteractiveShellSession(in, out, PROMPT).runForResult("show", 5000);

        assertEquals(InteractiveShellSession.Result.Kind.OUTPUT, result.kind());
        assertEquals("body line", result.text());
    }

    private static final class ScriptedInput extends InputStream {
        private final byte[][] pages;
        private final boolean autoAdvance;
        private int page = -1;
        private int offset;

        ScriptedInput(boolean autoAdvance, String... pages) {
            this.autoAdvance = autoAdvance;
            this.pages = new byte[pages.length][];
            for (int i = 0; i < pages.length; i++) {
                this.pages[i] = pages[i].getBytes(StandardCharsets.UTF_8);
            }
        }

        void advance() {
            page++;
            offset = 0;
        }

        @Override
        public int available() {
            if (page < 0 || page >= pages.length) {
                return 0;
            }
            int remaining = pages[page].length - offset;
            if (remaining == 0 && autoAdvance && page + 1 < pages.length) {
                advance();
            }
            return remaining;
        }

        @Override
        public int read(byte[] buffer, int start, int length) {
            int count = Math.min(length, available());
            if (count == 0) {
                return -1;
            }
            System.arraycopy(pages[page], offset, buffer, start, count);
            offset += count;
            return count;
        }

        @Override
        public int read() {
            if (available() == 0) {
                return -1;
            }
            return pages[page][offset++] & 0xff;
        }
    }

    private static final class ScriptedOutput extends OutputStream {
        private final ScriptedInput in;
        private int newlines;
        private int spaces;

        ScriptedOutput(ScriptedInput in) {
            this.in = in;
        }

        @Override
        public void write(int value) {
            if (value == '\n') {
                newlines++;
                in.advance();
            } else if (value == ' ') {
                spaces++;
                in.advance();
            }
        }
    }

    @org.junit.jupiter.api.Test
    void relearnPromptPicksUpTheOtherUnitsPrompt() throws Exception {
        // The new unit repaints its prompt only after the empty line is sent.
        byte[] repaint = "\nFGT-B # ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.concurrent.atomic.AtomicBoolean asked = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger pos = new java.util.concurrent.atomic.AtomicInteger();
        var in = new java.io.InputStream() {
            @Override public int available() { return asked.get() ? repaint.length - pos.get() : 0; }
            @Override public int read() { return asked.get() && pos.get() < repaint.length ? repaint[pos.getAndIncrement()] & 0xff : -1; }
            @Override public int read(byte[] b, int off, int len) {
                int n = Math.min(len, available());
                if (n <= 0) return -1;
                System.arraycopy(repaint, pos.getAndAdd(n), b, off, n);
                return n;
            }
        };
        var out = new java.io.OutputStream() {
            @Override public void write(int b) { if (b == '\n') asked.set(true); }
        };
        var shell = new InteractiveShellSession(in, out, "FGT-A # ");
        org.junit.jupiter.api.Assertions.assertTrue(shell.relearnPrompt(100));
    }

    @org.junit.jupiter.api.Test
    void aPromptRepaintedTwiceOnOneLineIsLearnedOnce() {
        org.junit.jupiter.api.Assertions.assertEquals("admin@fw-invented(active)>",
                InteractiveShellSession.singlePrompt("admin@fw-invented(active)> admin@fw-invented(active)>"));
        org.junit.jupiter.api.Assertions.assertEquals("FGT-INVENTED #", InteractiveShellSession.singlePrompt("FGT-INVENTED #"));
        org.junit.jupiter.api.Assertions.assertEquals("FGT-INVENTED # FGT-INVENTED (global) #",
                InteractiveShellSession.singlePrompt("FGT-INVENTED # FGT-INVENTED (global) #"));
        org.junit.jupiter.api.Assertions.assertEquals("[Expert@cp-invented:0]#",
                InteractiveShellSession.singlePrompt("[Expert@cp-invented:0]#"));
    }
}
