package com.securityexpert.nexus.ui2.service.report;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * A file renderer over a projected {@link ReportDocument}. Renderers never see an unprojected document (they refuse
 * one) and never truncate: output beyond {@link #MAX_BYTES} (Reports R1 contract "Storage": 25 MiB) fails the report
 * with {@link TooLarge}, and nothing is stored.
 */
public interface ReportRenderer {

    long MAX_BYTES = 25L * 1024 * 1024;

    ReportFormat format();

    byte[] render(ReportDocument document);

    /** The report is larger than the cap; the run fails with {@code REPORT_TOO_LARGE}. */
    final class TooLarge extends RuntimeException {
        public TooLarge(long limit) {
            super("the report is larger than " + limit + " bytes; nothing was stored or truncated");
        }
    }

    static void requireProjected(ReportDocument document) {
        Objects.requireNonNull(document, "document");
        if (!document.projected()) throw new IllegalStateException("renderers accept only a projected report document");
    }

    static String show(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    static String projectionLabel(ReportDocument d) {
        return "RAW".equals(d.projection()) ? "RAW - RESTRICTED (unmasked names)" : "MASKED (AIView pseudonyms)";
    }

    /** An in-memory sink that throws {@link TooLarge} as soon as it would exceed {@code limit}. */
    final class CappedBuffer extends OutputStream {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final long limit;
        private long count;

        public CappedBuffer(long limit) {
            this.limit = limit;
        }

        @Override
        public void write(int b) {
            check(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            check(len);
            out.write(b, off, len);
        }

        private void check(int len) {
            if (count + len > limit) throw new TooLarge(limit);
            count += len;
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
