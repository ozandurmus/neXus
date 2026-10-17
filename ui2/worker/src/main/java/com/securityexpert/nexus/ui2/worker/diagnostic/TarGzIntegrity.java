package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * The neXus-side integrity check of a fetched HCP archive (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 8.1: "the gzip
 * and tar structure must be readable. The device does no {@code tar -t}"). It walks the ustar/GNU header chain inside
 * the gzip stream: every header's checksum must match and every member's data must be present, up to the end-of-archive
 * marker (or the end of the stream after at least one member). No member content is kept or interpreted; the walk stops
 * after {@link #MAX_EXPANDED_BYTES} of expanded data and then counts the archive as readable so far.
 */
final class TarGzIntegrity {
    static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;
    private static final int BLOCK = 512;

    private TarGzIntegrity() {}

    /** True when the gzip stream and the tar header chain inside it are readable. */
    static boolean readable(byte[] data, int length) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data, 0, length), 64 * 1024)) {
            byte[] header = new byte[BLOCK];
            long expanded = 0;
            int members = 0;
            while (true) {
                int got = readFully(in, header);
                if (got == 0 && members > 0) return true; // end of stream without the zero blocks: tolerated
                if (got < BLOCK) return false;
                expanded += BLOCK;
                if (allZero(header)) return members > 0 || expanded == BLOCK;
                if (!checksumMatches(header)) return false;
                long size = octal(header, 124, 12);
                if (size < 0) return false;
                long padded = (size + BLOCK - 1) / BLOCK * BLOCK;
                if (skipFully(in, padded) != padded) return false;
                expanded += padded;
                members++;
                if (expanded >= MAX_EXPANDED_BYTES) return true;
            }
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int n = in.read(buffer, total, buffer.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static long skipFully(InputStream in, long bytes) throws IOException {
        byte[] scratch = new byte[8192];
        long left = bytes;
        while (left > 0) {
            int n = in.read(scratch, 0, (int) Math.min(scratch.length, left));
            if (n < 0) break;
            left -= n;
        }
        return bytes - left;
    }

    private static boolean allZero(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static boolean checksumMatches(byte[] header) {
        long stored = octal(header, 148, 8);
        if (stored < 0) return false;
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) sum += (i >= 148 && i < 156) ? ' ' : (header[i] & 0xff);
        return sum == stored;
    }

    /** An octal field (NUL/space terminated); -1 when it is not octal. GNU base-256 sizes count as unreadable here. */
    private static long octal(byte[] header, int offset, int length) {
        long value = 0;
        boolean digits = false;
        for (int i = offset; i < offset + length; i++) {
            int c = header[i] & 0xff;
            if (c == 0 || c == ' ') {
                if (digits) break;
                continue;
            }
            if (c < '0' || c > '7') return -1;
            value = value * 8 + (c - '0');
            digits = true;
        }
        return digits ? value : -1;
    }
}
