package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.StreamedInteractiveResult.Kind;

/** The streaming read of one command on the interactive shell (support bundle session captures): scripted bytes, no device. */
class InteractiveShellStreamingTest {
    private static final String ASA = "asa-tango-04#";
    private static final String ASA_MORE = "<--- More --->";

    @Test
    void answersTheAsaPagerWithASpaceRemovesItAndItsEraseSequenceAndKeepsLinesWhole() {
        var in = new Scripted(false, "show tech-support\r\nline one\r\n" + ASA_MORE,
                "\r              \rline two\r\n " + "indented three\r\n" + ASA_MORE,
                "\b\b\b\b\b\b\b\b\b\b\b\b\b\b              \b\b\b\b\b\b\b\b\b\b\b\b\b\blast line\r\n" + ASA);
        var out = new Out(in);
        List<String> lines = new ArrayList<>();
        var result = new InteractiveShellSession(in, out, ASA).runStreaming("show tech-support", 5000, ASA_MORE, lines::add);
        assertEquals(Kind.COMPLETED, result.kind());
        assertEquals(List.of("line one", "line two", " indented three", "last line"), lines);
        assertEquals(2, result.pagesAnswered());
        assertEquals(2, out.spaces);
        assertEquals(4, result.lines());
    }

    @Test
    void theCommandIsSentOnceAndNoPagerSettingIsEverSent() {
        var in = new Scripted(false, "show crashinfo\r\ndata\r\n" + ASA);
        var out = new Out(in);
        new InteractiveShellSession(in, out, ASA).runStreaming("show crashinfo", 5000, ASA_MORE, l -> { });
        assertEquals("show crashinfo\n", out.written.toString());
        assertTrue(!out.written.toString().contains("pager"));
    }

    @Test
    void theFortiGatePagerMarkerIsAnsweredToo() {
        var in = new Scripted(false, "execute tac report\nfirst\n --More-- ", "second\n --More-- ", "third\nFGT-TANGO-04 # ");
        var out = new Out(in);
        List<String> lines = new ArrayList<>();
        var result = new InteractiveShellSession(in, out, "FGT-TANGO-04 #").runStreaming("execute tac report", 5000, "--More--", lines::add);
        assertEquals(Kind.COMPLETED, result.kind());
        assertEquals(List.of("first", "second", "third"), lines);
        assertEquals(2, out.spaces);
    }

    @Test
    void aShortRefusalIsACliErrorNotACapture() {
        var in = new Scripted(false, "show crashinfox\r\n% Invalid input detected at '^' marker.\r\n" + ASA);
        var result = new InteractiveShellSession(in, new Out(in), ASA).runStreaming("show crashinfox", 5000, ASA_MORE, l -> { });
        assertEquals(Kind.CLI_ERROR, result.kind());
    }

    @Test
    void aSinkThatThrowsInterruptsTheCommandWithCtrlC() {
        var in = new Scripted(false, "show tech-support\r\none\r\ntwo\r\nthree\r\n" + ASA);
        var out = new Out(in);
        int[] seen = {0};
        var result = new InteractiveShellSession(in, out, ASA).runStreaming("show tech-support", 5000, ASA_MORE, l -> {
            if (++seen[0] == 2) throw new IOException("TOO_LARGE");
        });
        assertEquals(Kind.SINK_ABORTED, result.kind());
        assertEquals(1, result.lines());
        assertTrue(out.ctrlC, "the foreground command is interrupted");
    }

    @Test
    void aCommandThatNeverReturnsItsPromptTimesOutAndIsInterrupted() {
        var in = new Scripted(false, "show tech-support\r\nsome output\r\n");
        var out = new Out(in);
        long started = System.currentTimeMillis();
        var result = new InteractiveShellSession(in, out, ASA).runStreaming("show tech-support", 1000, ASA_MORE, l -> { });
        assertEquals(Kind.TIMED_OUT, result.kind());
        assertTrue(System.currentTimeMillis() - started < 5000);
        assertTrue(out.ctrlC);
    }

    @Test
    void aBrokenChannelIsASessionLostWithWhatArrived() {
        var in = new Scripted(false, "show tech-support\r\nfirst\r\n") {
            @Override public int available() throws IOException {
                if (consumed()) throw new IOException("closed");
                return super.available();
            }
        };
        List<String> lines = new ArrayList<>();
        var result = new InteractiveShellSession(in, new Out(in), ASA).runStreaming("show tech-support", 5000, ASA_MORE, lines::add);
        assertEquals(Kind.SESSION_LOST, result.kind());
        assertEquals(List.of("first"), lines);
    }

    @Test
    void aMultiByteCharacterSplitAcrossReadsIsDecodedWhole() {
        byte[] full = "show crashinfo\r\ncafé ü\r\n".getBytes(StandardCharsets.UTF_8);
        int cut = indexOfByte(full, (byte) 0xc3) + 1; // between the two bytes of "é"
        byte[] second = new byte[full.length - cut];
        System.arraycopy(full, cut, second, 0, second.length);
        byte[] first = java.util.Arrays.copyOf(full, cut);
        var in = new Scripted(true, first, second, (ASA).getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        var result = new InteractiveShellSession(in, new Out(in), ASA).runStreaming("show crashinfo", 5000, ASA_MORE, lines::add);
        assertEquals(Kind.COMPLETED, result.kind());
        assertEquals(List.of("café ü"), lines);
    }

    @Test
    void anIncompleteUtf8TailIsRecognised() {
        assertEquals(0, InteractiveShellSession.incompleteUtf8Tail("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, InteractiveShellSession.incompleteUtf8Tail("café".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, InteractiveShellSession.incompleteUtf8Tail(new byte[] {'a', (byte) 0xc3}));
        assertEquals(2, InteractiveShellSession.incompleteUtf8Tail(new byte[] {(byte) 0xe2, (byte) 0x82}));
        assertEquals(3, InteractiveShellSession.incompleteUtf8Tail(new byte[] {(byte) 0xf0, (byte) 0x9f, (byte) 0x98}));
    }

    @Test
    void aCommandWithALineBreakIsNeverSent() {
        var in = new Scripted(false, "x");
        var out = new Out(in);
        var result = new InteractiveShellSession(in, out, ASA).runStreaming("show\nreload", 1000, ASA_MORE, l -> { });
        assertEquals(Kind.NOT_SENT, result.kind());
        assertEquals("", out.written.toString());
    }

    private static int indexOfByte(byte[] data, byte value) {
        for (int i = 0; i < data.length; i++) if (data[i] == value) return i;
        throw new AssertionError();
    }

    /** Pages of input that become readable one after another, each after the shell wrote a newline or a space. */
    private static class Scripted extends InputStream {
        private final byte[][] pages;
        private final boolean autoAdvance;
        private int page = -1, offset;

        Scripted(boolean autoAdvance, String... pages) {
            this.autoAdvance = autoAdvance;
            this.pages = new byte[pages.length][];
            for (int i = 0; i < pages.length; i++) this.pages[i] = pages[i].getBytes(StandardCharsets.UTF_8);
        }

        Scripted(boolean autoAdvance, byte[]... pages) {
            this.autoAdvance = autoAdvance;
            this.pages = pages;
        }

        void advance() { page++; offset = 0; }

        boolean consumed() { return page >= 0 && page >= pages.length - 1 && offset >= pages[page].length; }

        @Override public int available() throws IOException {
            if (page < 0 || page >= pages.length) return 0;
            int remaining = pages[page].length - offset;
            if (remaining == 0 && autoAdvance && page + 1 < pages.length) advance();
            return remaining;
        }

        @Override public int read(byte[] buffer, int start, int length) throws IOException {
            int count = Math.min(length, available());
            if (count == 0) return -1;
            System.arraycopy(pages[page], offset, buffer, start, count);
            offset += count;
            return count;
        }

        @Override public int read() throws IOException {
            if (available() == 0) return -1;
            return pages[page][offset++] & 0xff;
        }
    }

    private static final class Out extends OutputStream {
        private final Scripted in;
        final StringBuilder written = new StringBuilder();
        int spaces;
        boolean ctrlC;

        Out(Scripted in) { this.in = in; }

        /** The command line: one bulk write ending in a newline makes the first page readable. */
        @Override public void write(byte[] b, int off, int len) {
            written.append(new String(b, off, len, StandardCharsets.UTF_8));
            if (len > 0 && b[off + len - 1] == '\n') in.advance();
        }

        /** Single-byte writes: Ctrl-C, or the space that answers a pager prompt (it makes the next page readable). */
        @Override public void write(int value) {
            if (value == 3) { ctrlC = true; return; }
            written.append((char) value);
            if (value == ' ') { spaces++; in.advance(); }
            else if (value == '\n') in.advance();
        }
    }
}
