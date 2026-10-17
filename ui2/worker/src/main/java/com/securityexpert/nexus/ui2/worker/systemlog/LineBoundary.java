package com.securityexpert.nexus.ui2.worker.systemlog;

/**
 * Line-boundary trimming of one read (contract section 4.3, "Line trim"), as pure functions over the received bytes.
 * The cursor always sits just after a {@code \n}; bytes read past the last newline are carried to the next run
 * (they are simply read again, because the cursor does not move over them).
 */
public final class LineBoundary {
    private static final byte NEWLINE = '\n';

    private LineBoundary() {
    }

    /**
     * What to keep of a read's payload.
     *
     * @param storedBytes payload bytes to store; the cursor advances by exactly this much
     * @param carriedBytes payload bytes after the last newline, left for the next run
     * @param lineSplit true when a full-cap read held no newline at all and was stored as is (chunk flag
     *        {@code LINE_SPLIT})
     */
    public record Trim(int storedBytes, int carriedBytes, boolean lineSplit) {
    }

    /**
     * Trims the end of a payload.
     *
     * @param data buffer holding the payload
     * @param from index of the first payload byte (after any lead-in region)
     * @param length payload bytes received
     * @param fullCapRead true when the read returned its whole cap, so the file may continue and a line may cross
     *        the cap
     * @param finalFile true for a rotated sibling read to its end: it no longer grows, so a last line without a
     *        newline is complete and nothing is carried
     */
    public static Trim trailing(byte[] data, int from, int length, boolean fullCapRead, boolean finalFile) {
        checkRange(data, from, length);
        if (length == 0) {
            return new Trim(0, 0, false);
        }
        int lastNewline = -1;
        for (int i = from + length - 1; i >= from; i--) {
            if (data[i] == NEWLINE) {
                lastNewline = i;
                break;
            }
        }
        if (lastNewline >= 0) {
            int stored = lastNewline - from + 1;
            if (finalFile) {
                return new Trim(length, 0, false);
            }
            return new Trim(stored, length - stored, false);
        }
        if (fullCapRead) {
            return new Trim(length, 0, true); // no newline in a whole cap: store it, flag it
        }
        if (finalFile) {
            return new Trim(length, 0, false);
        }
        return new Trim(0, length, false);
    }

    /**
     * Where the stored bytes of a first-run read begin. The read started at an arbitrary byte, so it normally begins
     * inside a line; the partial first line is skipped (its bytes are part of the baseline gap).
     *
     * @param data buffer holding {@code leadIn} lead-in bytes followed by the payload
     * @param leadIn number of lead-in bytes that precede the payload (0 when the read started at byte 0)
     * @param payloadLength payload bytes received
     * @param fullCapRead true when the read returned its whole cap
     * @return how many leading payload bytes to skip, and the LINE_SPLIT flag when the payload holds no newline in
     *         a full-cap read (then nothing is skipped and the cap is stored as is)
     */
    public static Leading leading(byte[] data, int leadIn, int payloadLength, boolean fullCapRead) {
        checkRange(data, leadIn, payloadLength);
        if (leadIn == 0 || data[leadIn - 1] == NEWLINE) {
            return new Leading(0, false); // the first payload byte starts a line
        }
        for (int i = leadIn; i < leadIn + payloadLength; i++) {
            if (data[i] == NEWLINE) {
                return new Leading(i - leadIn + 1, false);
            }
        }
        if (fullCapRead) {
            return new Leading(0, true);
        }
        return new Leading(payloadLength, false); // one partial line only: nothing storable yet
    }

    /** Result of {@link #leading}: payload bytes to skip, and whether the read is flagged LINE_SPLIT. */
    public record Leading(int skippedBytes, boolean lineSplit) {
    }

    private static void checkRange(byte[] data, int from, int length) {
        if (data == null || from < 0 || length < 0 || from + length > data.length) {
            throw new IllegalArgumentException("range outside the buffer");
        }
    }
}
