package com.securityexpert.nexus.ui2.platform.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;

/**
 * Registry-level guard for the metric label rule: no label carries a hostname, an IP address, a serial, a user name or
 * a device name; only enums, opaque ids and fixed technical names. It is a deny-by-default filter applied to every
 * meter any code registers:
 *
 * <ul>
 *   <li>a label key outside {@link #ALLOWED_KEYS} is dropped, so a future caller cannot add a {@code host} or
 *       {@code user} label by accident;</li>
 *   <li>a label value that is not a short token of letters, digits and {@code _ - . : / { } * ( )} and spaces, or that
 *       looks like an IP address, is replaced by {@link #REDACTED}.</li>
 * </ul>
 */
public final class MetricLabelGuard {

    public static final String REDACTED = "REDACTED";

    /** Every label key this product may emit (JVM, HTTP, DB pool, domain meters). Anything else is dropped. */
    public static final Set<String> ALLOWED_KEYS = Set.of(
            "role", "area", "id", "cause", "action", "state", "pool", "method", "status", "outcome", "uri",
            "exception", "result", "name", "type", "gc", "reason");

    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9 _.:/{}*()\\-]{1,64}");
    private static final Pattern IPV4 = Pattern.compile(".*\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b.*");
    private static final Pattern IPV6 = Pattern.compile(".*[0-9A-Fa-f]{0,4}:[0-9A-Fa-f]{0,4}:[0-9A-Fa-f:]*.*");
    private static final Pattern DEVICE_PSEUDONYM = Pattern.compile("(?i).*\\b(fw|cls|vsys)-[a-z]+-\\d+\\b.*");

    private MetricLabelGuard() {
    }

    /** True when {@code value} may appear as a label value. */
    public static boolean safeValue(String value) {
        return value != null && SAFE_VALUE.matcher(value).matches() && !IPV4.matcher(value).matches()
                && !IPV6.matcher(value).matches() && !DEVICE_PSEUDONYM.matcher(value).matches();
    }

    public static MeterFilter filter() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                boolean clean = true;
                for (Tag tag : id.getTagsAsIterable()) {
                    if (!ALLOWED_KEYS.contains(tag.getKey()) || !safeValue(tag.getValue())) {
                        clean = false;
                        break;
                    }
                }
                if (clean) return id;
                List<Tag> kept = new ArrayList<>();
                for (Tag tag : id.getTagsAsIterable()) {
                    if (!ALLOWED_KEYS.contains(tag.getKey())) continue;
                    kept.add(safeValue(tag.getValue()) ? tag : Tag.of(tag.getKey(), REDACTED));
                }
                return id.replaceTags(kept);
            }
        };
    }
}
