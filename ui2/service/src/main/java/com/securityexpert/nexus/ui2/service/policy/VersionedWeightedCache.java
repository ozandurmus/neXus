package com.securityexpert.nexus.ui2.service.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Bounded least-recently-used cache for immutable parsed values, one entry per key.
 *
 * <p>An entry is valid for exactly one caller-supplied {@code version} (for a stored policy: the row's collected_at and
 * size, which change whenever the row is replaced). A lookup with another version drops the stale entry and misses, so a
 * replaced row can never be answered from the old parse. The bound is a total {@code weight} (callers pass roughly the
 * number of rules and objects), not an entry count: a value heavier than the whole bound is not cached, and the least
 * recently used entries are evicted until the rest fits. An optional time to live bounds the staleness of anything the
 * version does not cover. Every operation is O(1) under one short lock; no computation ever runs under it.
 */
final class VersionedWeightedCache<V> {
    private record Entry<V>(String version, V value, long weight, long expiresAtNanos) {}
    private final LinkedHashMap<String, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final long maxWeight;
    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private long weight;
    private long hits, misses, evictions;

    /** @param ttlNanos zero or negative means entries never expire by age */
    VersionedWeightedCache(long maxWeight, long ttlNanos, LongSupplier nanoClock) {
        if (maxWeight < 1) throw new IllegalArgumentException("maxWeight");
        this.maxWeight = maxWeight; this.ttlNanos = ttlNanos; this.nanoClock = nanoClock;
    }
    VersionedWeightedCache(long maxWeight) { this(maxWeight, 0, System::nanoTime); }

    /** The cached value for exactly this version, or null; a stale or expired entry is removed. */
    synchronized V get(String key, String version) {
        var entry = entries.get(key);
        if (entry == null) { misses++; return null; }
        if (!entry.version().equals(version) || ttlNanos > 0 && nanoClock.getAsLong() - entry.expiresAtNanos() >= 0) {
            remove(key); misses++; return null;
        }
        hits++;
        return entry.value();
    }

    /** Stores the value; returns false (and keeps nothing for the key) when it is heavier than the whole bound. */
    synchronized boolean put(String key, String version, V value, long valueWeight) {
        remove(key);
        long w = Math.max(1, valueWeight);
        if (w > maxWeight) return false;
        entries.put(key, new Entry<>(version, value, w, ttlNanos > 0 ? nanoClock.getAsLong() + ttlNanos : 0));
        weight += w;
        var eldest = entries.entrySet().iterator();
        while (weight > maxWeight && eldest.hasNext()) {
            var victim = eldest.next();
            weight -= victim.getValue().weight(); evictions++;
            eldest.remove();
        }
        return true;
    }

    synchronized void invalidate(String key) { remove(key); }
    synchronized void clear() { entries.clear(); weight = 0; }
    /** Drops every entry whose key is not in {@code keep} (a source that no longer lists the item). */
    synchronized void retainOnly(Set<String> keep) {
        for (var it = entries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry<V>> e = it.next();
            if (!keep.contains(e.getKey())) { weight -= e.getValue().weight(); it.remove(); }
        }
    }
    private void remove(String key) {
        var old = entries.remove(key);
        if (old != null) weight -= old.weight();
    }

    synchronized long weight() { return weight; }
    synchronized int size() { return entries.size(); }
    synchronized long hits() { return hits; }
    synchronized long misses() { return misses; }
    synchronized long evictions() { return evictions; }
    long maxWeight() { return maxWeight; }
}
