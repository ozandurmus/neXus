package com.securityexpert.nexus.ui2.service.sanitizer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenSink;
import com.securityexpert.nexus.ui2.sanitizer.core.VaultKey;

/**
 * One sanitize job's write side of the vault: every token the engine emits is sealed and appended in batches of
 * {@link #BATCH} rows (one statement each); a bounded set of recently written lookup keys keeps repeated tokens from
 * being re-sealed. Originals longer than {@link #MAX_ORIGINAL} bytes are not recorded (and so are not revertible).
 * Not thread-safe: one job, one thread.
 */
final class JobVault implements TokenSink {

    static final int BATCH = 1000;
    static final int MAX_ORIGINAL = 4096;
    private static final int RECENT = 200_000;

    private final SanitizerRepository repository;
    private final VaultCrypto crypto;
    private final VaultCrypto.JobKey key;
    private final String jobId;
    private final String actor;
    private final List<SanitizerRepository.Entry> pending = new ArrayList<>();
    private final Map<String, Boolean> recent = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > RECENT;
        }
    };
    private long written;

    JobVault(SanitizerRepository repository, VaultCrypto crypto, VaultCrypto.JobKey key, String jobId, String actor) {
        this.repository = repository;
        this.crypto = crypto;
        this.key = key;
        this.jobId = jobId;
        this.actor = actor;
    }

    @Override
    public void record(VaultKey token, String original) {
        if (original == null || original.isEmpty() || original.getBytes(StandardCharsets.UTF_8).length > MAX_ORIGINAL) return;
        byte[] lookupKey = crypto.lookupKey(token);
        if (recent.put(HexFormat.of().formatHex(lookupKey), Boolean.TRUE) != null) return;
        pending.add(new SanitizerRepository.Entry(lookupKey, token.kind().id(), crypto.seal(key.dataKey(), jobId, lookupKey, original)));
        if (pending.size() >= BATCH) flush();
    }

    void flush() {
        if (pending.isEmpty()) return;
        written += repository.insertEntries(jobId, actor, List.copyOf(pending));
        pending.clear();
    }

    long written() {
        return written;
    }
}
