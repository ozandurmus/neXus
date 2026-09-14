package com.securityexpert.nexus.ui2.worker.configuration.pan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A several-megabyte {@code effective-running}-shaped fixture, generated on
 * the fly, one small chunk at a time -- never held whole, in this test or in
 * the processor under test (14G CG-5, AC-2's own "not checked in" fixture
 * requirement). {@code localEntryCount} of the {@code address} category's
 * entries carry {@code src="local"}; the rest are split across {@code tpl}/
 * {@code dg}/{@code shared} so the category index exercises every source.
 */
final class GeneratedPanConfigInputStream extends InputStream {

    private final Iterator<String> chunks;
    private byte[] buffer = new byte[0];
    private int bufferPos;

    GeneratedPanConfigInputStream(int localEntryCount, int otherSourceEntryCount) {
        this.chunks = new ChunkIterator(localEntryCount, otherSourceEntryCount);
    }

    @Override
    public int read() throws IOException {
        if (!fillIfNeeded()) {
            return -1;
        }
        return buffer[bufferPos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (!fillIfNeeded()) {
            return -1;
        }
        int available = buffer.length - bufferPos;
        int n = Math.min(len, available);
        System.arraycopy(buffer, bufferPos, b, off, n);
        bufferPos += n;
        return n;
    }

    private boolean fillIfNeeded() {
        while (bufferPos >= buffer.length) {
            if (!chunks.hasNext()) {
                return false;
            }
            buffer = chunks.next().getBytes(StandardCharsets.UTF_8);
            bufferPos = 0;
        }
        return true;
    }

    private static final class ChunkIterator implements Iterator<String> {
        private static final String[] OTHER_SOURCES = { "tpl", "dg", "shared" };

        private final int localEntryCount;
        private final int otherSourceEntryCount;
        private int stage;
        private int index;

        ChunkIterator(int localEntryCount, int otherSourceEntryCount) {
            this.localEntryCount = localEntryCount;
            this.otherSourceEntryCount = otherSourceEntryCount;
        }

        @Override
        public boolean hasNext() {
            return stage < 5;
        }

        @Override
        public String next() {
            switch (stage) {
                case 0 -> {
                    stage = 1;
                    return "<response status=\"success\"><result><config version=\"11.0\"><devices><entry name=\"localhost.localdomain\"><vsys><entry name=\"vsys1\"><address>";
                }
                case 1 -> {
                    if (index < localEntryCount) {
                        int i = index++;
                        return "<entry name=\"addr-local-" + i + "\" src=\"local\"/>";
                    }
                    stage = 2;
                    index = 0;
                    return "";
                }
                case 2 -> {
                    if (index < otherSourceEntryCount) {
                        int i = index++;
                        String src = OTHER_SOURCES[i % OTHER_SOURCES.length];
                        return "<entry name=\"addr-other-" + i + "\" src=\"" + src + "\"/>";
                    }
                    stage = 3;
                    return "</address></entry></vsys></entry></devices>";
                }
                case 3 -> {
                    stage = 4;
                    return "<shared><address><entry name=\"shared-addr-0\"/></address></shared>";
                }
                case 4 -> {
                    stage = 5;
                    return "</config></result></response>";
                }
                default -> throw new NoSuchElementException();
            }
        }
    }
}
