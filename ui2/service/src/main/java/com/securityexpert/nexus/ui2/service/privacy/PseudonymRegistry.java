package com.securityexpert.nexus.ui2.service.privacy;

import java.util.List;

/**
 * Makes pseudonyms unique per kind: a raw name (by its keyed hash) claims the first free candidate once, and
 * keeps it. {@link #inMemory()} is the process-local fallback for tests and for a service without a database.
 */
public interface PseudonymRegistry {

    String claim(String kind, String rawKey, List<String> candidates);

    static PseudonymRegistry inMemory() {
        return new PseudonymRegistry() {
        private final java.util.Map<String, String> byRaw = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Set<String> taken = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public synchronized String claim(String kind, String rawKey, List<String> candidates) {
            String existing = byRaw.get(kind + "|" + rawKey);
            if (existing != null) {
                return existing;
            }
            for (String candidate : candidates) {
                if (taken.add(kind + "|" + candidate)) {
                    byRaw.put(kind + "|" + rawKey, candidate);
                    return candidate;
                }
            }
            return candidates.get(0);
        }
        };
    }
}
