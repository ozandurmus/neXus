package com.securityexpert.nexus.ui2.service.systemlog;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactRetrievalRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;

/**
 * Synthetic wiring for the system-log read tests: a real encrypted artefact store in a temp directory (so a page really is
 * decrypted), a repository double, a device double and a ledger that records its rows. Documentation addresses and
 * example names only.
 */
public final class SystemLogReadFixture {
    public static final byte[] PRIVACY_KEY = "0123456789abcdef0123456789abcdef-test-key".getBytes(StandardCharsets.UTF_8);
    public static final String DEVICE = "dev-1";
    public static final String CHUNK = "chunk-1";

    /** One ledger row of an unmasked view. */
    public record LedgerRow(String retrievalId, String artefactId, String reason, String destination, String actor, String action) {
    }

    /** Counts how many times a page was decrypted. */
    public static final class CountingStore implements ArtefactStore {
        private final ArtefactStore delegate;
        public final AtomicInteger retrievals = new AtomicInteger();

        CountingStore(ArtefactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public ArtefactHandle open(String deviceId, String jobId, String vendor, boolean gzip) throws IOException {
            return delegate.open(deviceId, jobId, vendor, gzip);
        }

        @Override
        public InputStream retrieve(ArtefactRef ref, byte[] wrappedDataKey, boolean gzip) throws IOException {
            retrievals.incrementAndGet();
            return delegate.retrieve(ref, wrappedDataKey, gzip);
        }
    }

    public final CountingStore store;
    public final SystemLogReadRepository repository = mock(SystemLogReadRepository.class);
    public final SystemLogCollectionRepository settings = mock(SystemLogCollectionRepository.class);
    public final DeviceRepository devices = mock(DeviceRepository.class);
    public final List<LedgerRow> ledgerRows = new ArrayList<>();
    public boolean ledgerFails;
    public final LogMasking masking = new LogMasking(PRIVACY_KEY, () -> KnownNames.of(Map.of("edge-fw-01", "FW-TANGO-04")));
    public final BackupArtefactRetrievalRepository ledger = (id, artefact, reason, destination, actor, action) -> {
        if (ledgerFails) throw new IllegalStateException("ledger unavailable");
        ledgerRows.add(new LedgerRow(id, artefact, reason, destination, actor, action));
    };

    public SystemLogReadFixture(Path root) {
        store = new CountingStore(new FileArtefactStore(root,
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]))));
        when(settings.settings()).thenReturn(SystemLogCollectionRepository.Settings.DEFAULTS);
        device(DEVICE, "gateway", "check_point");
    }

    public SystemLogReadFixture device(String id, String role, String vendor) {
        when(devices.find(id)).thenReturn(Optional.of(new DeviceRecord(id, role, vendor, "manual", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "cred-1")));
        return this;
    }

    public SystemLogReadService service() {
        return new SystemLogReadService(repository, settings, devices, store, masking, ledger);
    }

    public SystemLogReadService serviceWithoutStore() {
        return new SystemLogReadService(repository, settings, devices, null, masking, ledger);
    }

    /** Stores {@code text} as a gzip-compressed, encrypted chunk of {@link #DEVICE} and registers it with the repository double. */
    public SystemLogReadRepository.ChunkRef chunk(String chunkId, byte[] text) throws IOException {
        try (ArtefactStore.ArtefactHandle handle = store.open(DEVICE, "job-1", "check_point", true)) {
            handle.sink().write(text);
            var meta = handle.finish();
            var ref = new SystemLogReadRepository.ChunkRef(chunkId, DEVICE, "cp_messages", text.length, 0, null,
                    "artefact-" + chunkId, meta.ref().value(), meta.wrappedDataKey());
            when(repository.findChunk(chunkId)).thenReturn(Optional.of(ref));
            return ref;
        }
    }

    public static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code count} lines of exactly 100 bytes each (99 characters and the terminator), numbered. */
    public static byte[] hundredByteLines(int count) {
        StringBuilder sb = new StringBuilder(count * 100);
        for (int i = 0; i < count; i++) {
            String head = String.format("line-%06d src 192.0.2.17 ", i);
            sb.append(head).append("x".repeat(99 - head.length())).append('\n');
        }
        return bytes(sb.toString());
    }
}
