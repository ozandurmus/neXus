package com.securityexpert.nexus.ui2.packetanalysis.export;

/**
 * The privacy projection of an expert report. The report is computed from the engine's stored values; every
 * identity-bearing string (device and capture-point names, capture file names, engine text) passes through one of
 * these methods before it enters the report model, so a masked workbook never holds the raw value anywhere.
 *
 * <p>The packet-analysis module has no privacy dependency: the service supplies an implementation backed by its
 * pseudonymizer and subnet-preserving address masker (the same ones that mask the JSON responses). {@link #NONE}
 * is the raw projection for raw-view roles. {@code name} is always called for every name before any {@code text}
 * call, because the service pseudonymizer learns names from {@code name} and rewrites them inside {@code text}.</p>
 */
public interface ReportMasker {
    /** A device, capture-point or capture-file name. */
    String name(String raw);

    /** Free text that may embed addresses or already-registered names. */
    String text(String raw);

    /** True when this is a masking projection (flows are then shown by ordinal, never by their identifier). */
    boolean masking();

    ReportMasker NONE = new ReportMasker() {
        @Override
        public String name(String raw) {
            return raw;
        }

        @Override
        public String text(String raw) {
            return raw;
        }

        @Override
        public boolean masking() {
            return false;
        }
    };
}
