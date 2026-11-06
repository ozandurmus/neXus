package com.securityexpert.nexus.ui2.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/** 2026-11-06 M9: a rule's extras iterate in one fixed (sorted) order in every JVM run, and still refuse nulls. */
class PolicyRuleExtrasOrderTest {
    private static Rule rule(Map<String, List<String>> extras) {
        var cell = new Cell(List.of(), false);
        return new Rule("r1", "r1", 1, "RULE-TEST-1", true, cell, cell, cell, cell, "allow", "Log", "", extras);
    }

    @Test void extrasIterateInSortedKeyOrderWhateverTheInputOrder() {
        Map<String, List<String>> input = new LinkedHashMap<>();
        for (String key : List.of("target-selectors", "schedule", "source-user", "category", "time", "install-on")) input.put(key, List.of("x"));
        var r = rule(input);
        assertEquals(new ArrayList<>(new TreeSet<>(input.keySet())), new ArrayList<>(r.extras().keySet()));
        assertEquals(input, r.extras());
        assertThrows(UnsupportedOperationException.class, () -> r.extras().put("k", List.of()));
    }

    @Test void nullKeysOrValuesAreStillRefused() {
        Map<String, List<String>> nullValue = new HashMap<>(); nullValue.put("schedule", null);
        assertThrows(NullPointerException.class, () -> rule(nullValue));
    }
}
