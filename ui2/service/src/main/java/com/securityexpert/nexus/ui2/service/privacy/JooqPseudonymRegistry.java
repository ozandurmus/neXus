package com.securityexpert.nexus.ui2.service.privacy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** V53 {@code pseudonym_registry}: the first claim of a candidate wins, and a raw key keeps its claim. */
public final class JooqPseudonymRegistry implements PseudonymRegistry {

    private final TransactionBoundary transactionBoundary;
    private final Map<String, String> byRaw = new ConcurrentHashMap<>();
    private final Set<String> taken = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean loaded = new AtomicBoolean();

    public JooqPseudonymRegistry(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    private void ensureLoaded() {
        if (loaded.get()) return;
        synchronized (this) {
            if (loaded.get()) return;
            transactionBoundary.inTransaction(dsl -> {
                for (var record : dsl.fetch("select kind, raw_key, pseudonym from pseudonym_registry")) {
                    String k = record.get(0, String.class);
                    String r = record.get(1, String.class);
                    String p = record.get(2, String.class);
                    if (k != null && r != null && p != null) {
                        byRaw.put(k + "|" + r, p);
                        taken.add(k + "|" + p);
                    }
                }
                return null;
            });
            loaded.set(true);
        }
    }

    @Override
    public String claim(String kind, String rawKey, List<String> candidates) {
        String rawCacheKey = kind + "|" + rawKey;
        String cached = byRaw.get(rawCacheKey);
        if (cached != null) return cached;
        ensureLoaded();
        cached = byRaw.get(rawCacheKey);
        if (cached != null) return cached;
        return transactionBoundary.inTransaction(dsl -> {
            var existing = dsl.fetchOptional("select pseudonym from pseudonym_registry where kind = {0} and raw_key = {1}", kind, rawKey);
            if (existing.isPresent()) {
                String p = existing.get().get(0, String.class);
                byRaw.put(rawCacheKey, p);
                taken.add(kind + "|" + p);
                return p;
            }
            for (String candidate : candidates) {
                String candidateKey = kind + "|" + candidate;
                if (taken.contains(candidateKey)) continue;
                int inserted = dsl.execute("insert into pseudonym_registry(kind, raw_key, pseudonym) values ({0}, {1}, {2}) "
                        + "on conflict do nothing", kind, rawKey, candidate);
                if (inserted == 1) {
                    taken.add(candidateKey);
                    byRaw.put(rawCacheKey, candidate);
                    return candidate;
                }
                taken.add(candidateKey);
                var raced = dsl.fetchOptional("select pseudonym from pseudonym_registry where kind = {0} and raw_key = {1}", kind, rawKey);
                if (raced.isPresent()) {
                    String p = raced.get().get(0, String.class);
                    byRaw.put(rawCacheKey, p);
                    taken.add(kind + "|" + p);
                    return p;
                }
            }
            return candidates.get(0);
        });
    }
}
