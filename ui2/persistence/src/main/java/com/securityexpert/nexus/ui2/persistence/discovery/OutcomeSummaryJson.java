package com.securityexpert.nexus.ui2.persistence.discovery;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal, dependency-free JSON codec for {@code discovery_run.outcome_summary}
 * -- a flat {@code Map<String, Integer>} of counts only (14F DR-2, PR-3: "how
 * many candidates classified to each import outcome... never an address,
 * hostname, serial"). No library dependency is added to {@code persistence}
 * for what is, by contract, always this one shape.
 */
final class OutcomeSummaryJson {

    private static final Pattern ENTRY = Pattern.compile("\"([^\"]*)\"\\s*:\\s*(-?\\d+)");

    private OutcomeSummaryJson() {
    }

    static String toJson(Map<String, Integer> counts) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
        }
        return json.append('}').toString();
    }

    static Map<String, Integer> fromJson(String json) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return counts;
        }
        Matcher matcher = ENTRY.matcher(json);
        while (matcher.find()) {
            counts.put(unescape(matcher.group(1)), Integer.valueOf(matcher.group(2)));
        }
        return counts;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
