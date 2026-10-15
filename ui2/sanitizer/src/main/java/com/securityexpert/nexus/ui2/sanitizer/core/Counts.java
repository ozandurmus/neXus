package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Replacement counts per category -- the only content-derived figures a job summary carries. */
public final class Counts {

    private final Map<String, Long> values = new TreeMap<>();

    public void add(String category) {
        add(category, 1);
    }

    public void add(String category, long n) {
        if (n != 0) values.merge(category, n, Long::sum);
    }

    public long get(String category) {
        return values.getOrDefault(category, 0L);
    }

    public void addAll(Counts other) {
        other.values.forEach(this::add);
    }

    public Map<String, Long> asMap() {
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
