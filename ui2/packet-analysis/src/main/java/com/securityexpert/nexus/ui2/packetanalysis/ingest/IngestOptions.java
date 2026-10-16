package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import java.time.Duration;

/**
 * Per-file ingest settings (the keyword arguments of the reference {@code ingest}).
 *
 * @param tshark tshark path override, or {@code null} to search
 * @param prefixBytes payload prefix kept per packet, 8..4096
 * @param batchSize rows per committed batch
 * @param checkpointUuid snoop only: decode FW-1 UUIDs
 * @param sourceMetadata converter metadata (text imports), or {@code null}
 * @param f5Trailer decode the F5 Ethernet trailer; {@code null} keeps the previous setting of the capture
 * @param readFilter tshark display filter (pre-filter), or {@code null}
 * @param prefilterTotal frames of the unfiltered capture, when known
 * @param prefilterWarning warning recorded when a pre-filter was skipped
 * @param timeout wall-clock limit for the tshark run, or {@code null} for none
 */
public record IngestOptions(String tshark, int prefixBytes, int batchSize, boolean checkpointUuid,
        Object sourceMetadata, Boolean f5Trailer, String readFilter, Long prefilterTotal, String prefilterWarning,
        Duration timeout) {

    /** The reference defaults: 64-byte prefix, 50,000-row batches, everything else off. */
    public static IngestOptions defaults() {
        return new IngestOptions(null, 64, 50000, false, null, null, null, null, null, null);
    }

    public IngestOptions withTshark(String path) {
        return new IngestOptions(path, prefixBytes, batchSize, checkpointUuid, sourceMetadata, f5Trailer,
                readFilter, prefilterTotal, prefilterWarning, timeout);
    }

    public IngestOptions withBatchSize(int rows) {
        return new IngestOptions(tshark, prefixBytes, rows, checkpointUuid, sourceMetadata, f5Trailer, readFilter,
                prefilterTotal, prefilterWarning, timeout);
    }

    public IngestOptions withTimeout(Duration limit) {
        return new IngestOptions(tshark, prefixBytes, batchSize, checkpointUuid, sourceMetadata, f5Trailer,
                readFilter, prefilterTotal, prefilterWarning, limit);
    }

    /** The pre-filter fields of a second-phase ingest. */
    public IngestOptions withPrefilter(String filter, Long total, String warning) {
        return new IngestOptions(tshark, prefixBytes, batchSize, checkpointUuid, sourceMetadata, f5Trailer, filter,
                total, warning, timeout);
    }
}
