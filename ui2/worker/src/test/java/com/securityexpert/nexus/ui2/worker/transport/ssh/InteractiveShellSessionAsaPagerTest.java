package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The Cisco ASA pager ("<--- More --->") is answered in the shell (PO 2026-11-15: no "terminal pager 0", Cisco warns it
 * can raise the CPU). Synthetic masked transcripts: the reassembled answer must equal the unpaged one byte for byte.
 */
class InteractiveShellSessionAsaPagerTest {

    private static final String PROMPT = "fw-synth#";
    private static final String MORE = "<--- More --->";
    private static final String CR_ERASE = "\r" + " ".repeat(14) + "\r";
    private static final String BS_ERASE = "\b".repeat(14) + " ".repeat(14) + "\b".repeat(14);
    private static final String BS_SPACE_ERASE = "\b \b".repeat(14);

    private static List<String> body(int lines) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            out.add(i % 5 == 0 ? "interface Vlan" + i : "  description synthetic line " + i + " 192.0.2." + (i % 250));
        }
        return out;
    }

    /** The device's pages: the echo first, then up to {@code pageLines} lines and the prompt; erase between pages. */
    private static List<String> pages(List<String> lines, int pageLines, String erase) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder("showrun\r\n");
        for (int i = 0; i < lines.size(); i++) {
            page.append(lines.get(i)).append("\r\n");
            boolean last = i == lines.size() - 1;
            if (!last && (i + 1) % pageLines == 0) {
                pages.add(page + MORE);
                page = new StringBuilder(erase);
            }
        }
        pages.add(page + PROMPT);
        return pages;
    }

    private static String unpaged(List<String> lines) {
        return run(List.of(pages(lines, Integer.MAX_VALUE, "").get(0)), 1).text();
    }

    private record Out(InteractiveShellSession.Result result, int spaces) {
        String text() {
            return result.text();
        }
    }

    /** Runs the pages through the shell, each page cut into {@code split}-byte reads (0: one read). */
    private static Out run(List<String> pages, int split) {
        Script script = new Script(pages, split);
        InteractiveShellSession.Result[] result = new InteractiveShellSession.Result[1];
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                result[0] = new InteractiveShellSession(script.input(), script.output(), PROMPT).runForResult("showrun", 10_000));
        return new Out(result[0], script.spaces);
    }

    @Test
    void pagedAnswerEqualsTheUnpagedAnswerForEveryEraseVariantAndReadBoundary() {
        List<String> lines = body(130);
        String expected = unpaged(lines);
        assertEquals(String.join("\n", lines), expected);
        int pagesExpected = 130 / 24; // 5 full pages end in a marker, the sixth in the prompt
        for (String erase : List.of(CR_ERASE, BS_ERASE, BS_SPACE_ERASE, "")) {
            for (int split : new int[] {0, 1, 2, 3, 5, 7, 13, 16, 100}) {
                Out out = run(pages(lines, 24, erase), split);
                assertEquals(InteractiveShellSession.Result.Kind.OUTPUT, out.result().kind(), "erase " + erase.length() + " split " + split);
                assertEquals(expected, out.text(), "erase " + erase.length() + " split " + split);
                assertEquals(pagesExpected, out.spaces(), "erase " + erase.length() + " split " + split);
            }
        }
    }

    @Test
    void aSinglePageAnswerSendsNoSpace() {
        List<String> lines = body(10);
        Out out = run(pages(lines, 24, CR_ERASE), 0);
        assertEquals(String.join("\n", lines), out.text());
        assertEquals(0, out.spaces());
    }

    @Test
    void aPageThatEndsExactlyAtTheLastLineStillReassembles() {
        // 48 lines: the second page ends with the last line and is followed by the prompt, not by another marker.
        List<String> lines = body(48);
        Out out = run(pages(lines, 24, CR_ERASE), 4);
        assertEquals(String.join("\n", lines), out.text());
        assertEquals(1, out.spaces());
    }

    @Test
    void theMarkerInsideDataIsNotAPrompt() {
        List<String> lines = new ArrayList<>(body(60));
        lines.set(7, "  description keep <--- More ---> inside text");       // mid-line
        lines.set(11, "  description ends with <--- More --->");            // ends a line, then a newline follows
        String expected = String.join("\n", lines);
        for (int split : new int[] {0, 1, 3, 14, 15}) {
            Out out = run(pages(lines, 24, CR_ERASE), split);
            assertEquals(expected, out.text(), "split " + split);
            assertEquals(2, out.spaces(), "split " + split);
        }
        // A data line that starts with the marker and goes on in the same read is not a prompt either. (If a read ended
        // exactly after such a line start it would be taken for the pager: documented limit, as a real pager looks the same.)
        lines.set(9, "<--- More ---> as the start of a data line");
        Out out = run(pages(lines, 24, CR_ERASE), 0);
        assertEquals(String.join("\n", lines), out.text());
        assertEquals(2, out.spaces());
    }

    @Test
    void anEraseLookalikeInTheNextPageIsKept() {
        // After the erase, a page that starts with indentation keeps it; with no erase at all it stays too.
        List<String> lines = body(60);
        lines.set(24, "    indented first line of page two");
        Out out = run(pages(lines, 24, CR_ERASE), 1);
        assertEquals(String.join("\n", lines), out.text());
        Out bare = run(pages(lines, 24, ""), 1);
        assertEquals(String.join("\n", lines), bare.text());
    }

    @Test
    void aRunawayPagerStopsAtTheBound() {
        List<String> pages = new ArrayList<>();
        pages.add("showrun\r\nx\r\n" + MORE);
        for (int i = 0; i < InteractiveShellSession.MAX_PAGES + 2; i++) {
            pages.add(CR_ERASE + "x\r\n" + MORE);
        }
        Out out = run(pages, 0);
        assertEquals(InteractiveShellSession.Result.Kind.TIMED_OUT, out.result().kind());
    }

    /** Pages released one at a time: the next only after the shell answers (a space, or the command's newline). */
    private static final class Script {
        private final List<List<byte[]>> pages = new ArrayList<>();
        private int page = -1;
        private int chunk;
        private int offset;
        int spaces;

        Script(List<String> source, int split) {
            for (String p : source) {
                byte[] bytes = p.getBytes(StandardCharsets.UTF_8);
                List<byte[]> chunks = new ArrayList<>();
                int size = split <= 0 ? bytes.length : split;
                for (int i = 0; i < bytes.length; i += size) {
                    chunks.add(java.util.Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + size)));
                }
                pages.add(chunks);
            }
        }

        private void advance() {
            page++;
            chunk = 0;
            offset = 0;
        }

        private int remaining() {
            if (page < 0 || page >= pages.size()) {
                return 0;
            }
            List<byte[]> chunks = pages.get(page);
            while (chunk < chunks.size() && offset >= chunks.get(chunk).length) {
                chunk++;
                offset = 0;
                // a read ends at the chunk boundary: report this chunk only
                break;
            }
            return chunk < chunks.size() ? chunks.get(chunk).length - offset : 0;
        }

        InputStream input() {
            return new InputStream() {
                @Override public int available() {
                    return remaining();
                }

                @Override public int read() {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                }

                @Override public int read(byte[] buffer, int start, int length) {
                    int n = Math.min(length, remaining());
                    if (n == 0) {
                        return -1;
                    }
                    System.arraycopy(pages.get(page).get(chunk), offset, buffer, start, n);
                    offset += n;
                    return n;
                }
            };
        }

        OutputStream output() {
            return new OutputStream() {
                @Override public void write(int value) {
                    if (value == '\n') {
                        advance();
                    } else if (value == ' ') {
                        spaces++;
                        advance();
                    }
                }
            };
        }
    }
}
