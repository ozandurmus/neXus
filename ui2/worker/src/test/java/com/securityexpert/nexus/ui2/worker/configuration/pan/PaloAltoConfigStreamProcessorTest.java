package com.securityexpert.nexus.ui2.worker.configuration.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;

/** AC-2/AC-2a: streaming category index, per-source counts, and src=local override detection over a multi-megabyte fixture. */
class PaloAltoConfigStreamProcessorTest {

    /** Wraps a source stream and records the largest {@code len} ever requested of {@link #read(byte[], int, int)}. */
    private static final class BoundedReadTrackingInputStream extends FilterInputStream {
        private int maxRequestedLen;

        BoundedReadTrackingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            maxRequestedLen = Math.max(maxRequestedLen, len);
            return super.read(b, off, len);
        }
    }

    @Test
    void processesAMultiMegabyteFixtureWithoutEverRequestingALargeSingleRead() throws Exception {
        int localCount = 150_000; // ~40 bytes/entry -> several MB of generated XML.
        int otherCount = 300;
        var tracking = new BoundedReadTrackingInputStream(new GeneratedPanConfigInputStream(localCount, otherCount));

        var processed = PaloAltoConfigStreamProcessor.process(tracking);

        // A materializing read (readAllBytes()/ofString()) requests a buffer that grows toward the
        // full document size; a genuinely streaming StAX pass never requests more than its own small
        // internal read-ahead buffer. 64 KiB is comfortably above any real StAX implementation's
        // default buffer and comfortably below "the whole multi-megabyte document in one call."
        assertTrue(tracking.maxRequestedLen > 0, "the stream must actually have been read");
        assertTrue(tracking.maxRequestedLen <= 65536,
                "a single read() requested " + tracking.maxRequestedLen + " bytes -- looks like a materializing read, not a stream");

        assertTrue(processed.hasLocalOverride());
        assertEquals(localCount, processed.overrides().size());

        int localAddressCount = countFor(processed.index(), "vsys1", "address", "local");
        assertEquals(localCount, localAddressCount);
        int tplCount = countFor(processed.index(), "vsys1", "address", "tpl");
        assertTrue(tplCount > 0, "tpl-sourced entries must be counted separately from local ones");

        // shared context, no src attribute -> defaults to "shared" (never confused with vsys1's own local entries).
        int sharedCount = countFor(processed.index(), "shared", "address", "shared");
        assertEquals(1, sharedCount);
    }

    @Test
    void aFixtureWithNoLocalElementRecordsNoOverride() throws Exception {
        var stream = new GeneratedPanConfigInputStream(0, 30);
        var processed = PaloAltoConfigStreamProcessor.process(stream);

        assertEquals(false, processed.hasLocalOverride());
        assertEquals(List.of(), processed.overrides());
    }

    private static int countFor(List<ConfigurationIndexEntry> index, String context, String category, String source) {
        return index.stream()
                .filter(e -> e.context().equals(context) && e.section().equals(category)
                        && e.source().equals(Optional.of(source)))
                .mapToInt(ConfigurationIndexEntry::entryCount)
                .findFirst()
                .orElse(0);
    }
}
