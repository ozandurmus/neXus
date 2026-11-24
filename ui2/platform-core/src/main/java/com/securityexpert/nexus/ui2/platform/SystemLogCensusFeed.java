package com.securityexpert.nexus.ui2.platform;

import java.io.PrintStream;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The seam of the system-log template census (docs/design/LOG_TEMPLATE_CENSUS_TOOL_2026_11_24.md). The census itself
 * (mining, classification, report, output guard) lives in the worker module; what decrypts a chunk and applies the
 * aiview masking (the privacy key, {@code LogMasking}, the device pseudonyms) lives in the service module. The service
 * may not depend on the worker and the worker may not depend on the service (Ui2ArchitectureTest dir2), so the two meet
 * here, in JDK-only types: the worker's entry point loads a {@link Provider} by class name, as the launcher loads a
 * role's main class.
 *
 * <p><b>Privacy contract.</b> Raw text exists only inside {@link #read}: it decrypts one chunk, masks every line,
 * passes (raw line, masked line) to the caller's leak check, drops and counts a line the check flags, and returns
 * masked lines only. No method returns a raw line, a host name or a device identifier other than the opaque device id
 * of the index row; {@link #devicePseudonym} returns the aiview pseudonym.</p>
 */
public interface SystemLogCensusFeed extends AutoCloseable {

    /** One chunk row of the index: opaque ids, source key, log day and the artefact reference needed to decrypt it. */
    record Chunk(String chunkId, String deviceId, String sourceKey, String logDay, String artefactRef, byte[] wrappedKey,
            long bytes) {
    }

    /** The masked lines of one chunk; {@code guardRejected} lines were dropped because an identity literal survived. */
    record Masked(List<String> lines, long guardRejected, boolean truncated) {
    }

    /** Receives the chunks of the window in order; return false to stop. */
    interface Sink {
        boolean accept(Chunk chunk) throws Exception;
    }

    /** Opens a feed. {@code leakCheck} takes (raw line, masked line) and returns a class label, or null when no identity survived. */
    interface Provider {
        SystemLogCensusFeed open(BiFunction<String, String, String> leakCheck, PrintStream progress) throws Exception;
    }

    /** Live (not pruned) chunks of the sources with a log day in [from, to], ordered by (source, device, day). */
    void chunks(LocalDate from, LocalDate to, List<String> sources, Sink sink) throws Exception;

    /** One chunk, decrypted and masked line by line (at most {@code maxBytes} bytes of it). */
    Masked read(Chunk chunk, int maxBytes) throws Exception;

    /** The aiview pseudonym of a device (never a host name). */
    String devicePseudonym(String deviceId);

    /** The masking rule set applied (for the report header). */
    String maskRuleSet();

    @Override
    void close() throws Exception;
}
