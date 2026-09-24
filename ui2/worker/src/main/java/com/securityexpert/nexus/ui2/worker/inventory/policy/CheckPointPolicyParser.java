package com.securityexpert.nexus.ui2.worker.inventory.policy;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code cpstat -f policy fw}: the policy name and install time lines. EXPECTED SHAPE, UNVERIFIED until the Product
 * Owner's sample is recorded (POLICY_INSTALL_TIME_COMMAND_GATE_ENTRIES.md "MEASURE FIRST"): {@code Policy name:} and
 * {@code Install time:} (or {@code Policy install time:}), time as {@code Tue Sep 22 23:05:11 2026}, the gateway's
 * local time. Anything else leaves the field empty -- UNKNOWN, never a guess. Only these two lines are kept.
 */
public final class CheckPointPolicyParser {

    // [ \t] not \s around the colon: measured 2026-09-24, an empty "Install time:" line let \s cross the newline and
    // read the next line ("Num. connections: N") as the time. An empty value stays empty.
    private static final Pattern NAME = Pattern.compile("(?im)^[ \\t]*policy[ \\t]+name[ \\t]*:[ \\t]*(\\S[^\\r\\n]*?)[ \\t]*$");
    private static final Pattern TIME = Pattern.compile("(?im)^[ \\t]*(?:policy[ \\t]+)?install[ \\t]+time[ \\t]*:[ \\t]*(\\S[^\\r\\n]*?)[ \\t]*$");
    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE MMM  d HH:mm:ss yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH));
    /** The estate's gateways run Europe/Istanbul (measured in configuration: timezone setting); UNVERIFIED per device. */
    static final ZoneId GATEWAY_ZONE = ZoneId.of("Europe/Istanbul");

    private CheckPointPolicyParser() {
    }

    public static PolicyInstallRead parse(String output) {
        if (output == null || output.isBlank()) {
            return PolicyInstallRead.NONE;
        }
        Optional<String> name = first(NAME, output);
        Optional<String> text = first(TIME, output);
        return new PolicyInstallRead(name, text, text.flatMap(CheckPointPolicyParser::toInstant), "cp_cpstat_policy");
    }

    static Optional<java.time.Instant> toInstant(String text) {
        for (DateTimeFormatter f : FORMATS) {
            try {
                return Optional.of(LocalDateTime.parse(text.strip(), f).atZone(GATEWAY_ZONE).toInstant());
            } catch (DateTimeParseException ignored) {
                // next form
            }
        }
        return Optional.empty();
    }

    private static Optional<String> first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
