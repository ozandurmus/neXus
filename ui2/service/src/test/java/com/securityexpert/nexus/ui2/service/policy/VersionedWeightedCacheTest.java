package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

class VersionedWeightedCacheTest {
    @Test void hitsOnlyForTheSameVersionAndDropsStaleEntries() {
        var cache = new VersionedWeightedCache<String>(100);
        cache.put("a", "v1", "one", 10);
        assertEquals("one", cache.get("a", "v1"));
        assertNull(cache.get("a", "v2"), "a replaced row must never be answered from the old parse");
        assertEquals(0, cache.size());
        assertEquals(0, cache.weight());
        assertNull(cache.get("a", "v1"));
    }

    @Test void evictsLeastRecentlyUsedUntilTheWeightFits() {
        var cache = new VersionedWeightedCache<String>(100);
        cache.put("a", "v", "A", 40); cache.put("b", "v", "B", 40);
        assertEquals("A", cache.get("a", "v"));               // a is now more recent than b
        cache.put("c", "v", "C", 40);                           // 120 > 100: b goes
        assertNull(cache.get("b", "v"));
        assertEquals("A", cache.get("a", "v")); assertEquals("C", cache.get("c", "v"));
        assertEquals(80, cache.weight()); assertEquals(1, cache.evictions());
    }

    @Test void valueHeavierThanTheBoundIsNotKeptAndDisplacesTheOldEntry() {
        var cache = new VersionedWeightedCache<String>(100);
        cache.put("a", "v1", "old", 10);
        assertFalse(cache.put("a", "v2", "huge", 101));
        assertNull(cache.get("a", "v1"));
        assertEquals(0, cache.size());
    }

    @Test void replacingAKeyAdjustsTheWeightAndRetainOnlyDropsUnlistedKeys() {
        var cache = new VersionedWeightedCache<String>(100);
        cache.put("a", "v1", "A", 30); cache.put("a", "v2", "A2", 50); cache.put("b", "v", "B", 20);
        assertEquals(70, cache.weight());
        cache.retainOnly(Set.of("b"));
        assertEquals(20, cache.weight()); assertEquals(1, cache.size());
        cache.invalidate("b");
        assertEquals(0, cache.weight());
    }

    @Test void entriesExpireAfterTheTimeToLive() {
        var now = new AtomicLong(1_000);
        var cache = new VersionedWeightedCache<String>(100, 500, now::get);
        cache.put("a", "v", "A", 1);
        now.addAndGet(499);
        assertEquals("A", cache.get("a", "v"));
        now.addAndGet(1);
        assertNull(cache.get("a", "v"));
        assertEquals(0, cache.size());
    }
}
