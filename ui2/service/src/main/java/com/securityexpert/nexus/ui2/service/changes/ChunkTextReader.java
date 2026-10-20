package com.securityexpert.nexus.ui2.service.changes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.changes.ChangeFeedRepository.ChunkRow;

/**
 * The lines of one stored system-log chunk, decrypted through the artefact store and kept in a small in-memory
 * cache keyed by chunk id. A chunk is immutable once written (its row is the ledger; retention prunes the artefact,
 * never rewrites it), so the cache never goes stale; it is bounded so a wide range cannot grow the heap. The text is
 * already secret-redacted on write (Phase B section 12); masking for the aiview persona is applied by the caller
 * per line and per request, never cached, so the cache holds the same bytes the artefact holds and nothing more.
 *
 * <p>A chunk larger than {@link #MAX_CHUNK_BYTES} is not read (reported as skipped): the two sources this feed parses
 * ({@code pan_config}, {@code cp_cpwd_elg}) are small (findings 2026-10-17 section 3.1 and 5), so a larger chunk is
 * an anomaly, not a case to page through here.</p>
 */
public final class ChunkTextReader {
    /** Per chunk: anything bigger is skipped and named in the coverage notes. */
    public static final int MAX_CHUNK_BYTES = 4 * 1024 * 1024;
    /** Chunks kept decrypted in memory (least recently used first out). */
    static final int CACHE_ENTRIES = 1024;

    private final ArtefactStore store; // null when the service has no store mounted
    private final Map<String, List<String>> cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > CACHE_ENTRIES;
        }
    });

    public ChunkTextReader(ArtefactStore store) {
        this.store = store;
    }

    /** True when a store is mounted; without one no stored source can be read and the feed says so. */
    public boolean available() {
        return store != null;
    }

    /**
     * The chunk's lines without terminators, from the cache or the store.
     *
     * @throws IOException when the artefact cannot be read or decrypted
     * @throws IllegalStateException when the chunk is too large to read here
     */
    public List<String> lines(ChunkRow chunk) throws IOException {
        Objects.requireNonNull(chunk, "chunk");
        if (store == null) throw new IOException("no artefact store mounted");
        List<String> cached = cache.get(chunk.chunkId());
        if (cached != null) return cached;
        if (chunk.bytes() > MAX_CHUNK_BYTES) throw new IllegalStateException("chunk larger than " + MAX_CHUNK_BYTES + " bytes");
        if (chunk.artefactRef() == null || chunk.wrappedDataKey() == null) throw new IOException("artefact not recorded");
        List<String> lines = new ArrayList<>();
        try (InputStream in = store.retrieve(new ArtefactRef(chunk.artefactRef()), chunk.wrappedDataKey(), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 64 * 1024)) {
            String line;
            long read = 0;
            while ((line = reader.readLine()) != null) {
                read += line.length() + 1;
                if (read > MAX_CHUNK_BYTES) throw new IllegalStateException("chunk larger than " + MAX_CHUNK_BYTES + " bytes");
                lines.add(line);
            }
        }
        List<String> frozen = List.copyOf(lines);
        cache.put(chunk.chunkId(), frozen);
        return frozen;
    }

    /** Pre-seeds the cache (tests, or a warm-up); the lines are kept exactly as given. */
    void preload(String chunkId, List<String> lines) {
        cache.put(chunkId, List.copyOf(lines));
    }
}
