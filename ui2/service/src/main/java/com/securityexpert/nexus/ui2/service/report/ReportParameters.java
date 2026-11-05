package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/**
 * R1 report parameters (contract "UI": a date range; a framework for RPT-02). Dates are calendar days in the product
 * time zone (Europe/Istanbul); {@code to} is inclusive. Nothing here carries an identity.
 */
public record ReportParameters(LocalDate from, LocalDate to, String framework) {

    /** Default range: the last seven days, today included. */
    public static final int DEFAULT_DAYS = 7;
    /** Longest range a report covers. */
    public static final int MAX_DAYS = 92;
    public static final String ALL_FRAMEWORKS = "ALL";
    /** The framework keys, primary first (PO decision 2026-11-05: CIS and NIST headline). */
    public static final List<String> FRAMEWORKS = List.of("CIS", "NIST_800_53", "PCI_DSS", "FINANCIAL_BASELINE");

    public static final class Invalid extends RuntimeException {
        private final String code;

        public Invalid(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Parses and validates; blank values take their defaults. */
    public static ReportParameters parse(ReportTemplate template, String from, String to, String framework, Instant now) {
        LocalDate today = now.atZone(JobWindowPolicy.ZONE_ID).toLocalDate();
        LocalDate end = date(to).orElse(today);
        LocalDate start = date(from).orElse(end.minusDays(DEFAULT_DAYS - 1L));
        if (end.isAfter(today)) throw new Invalid("INVALID_DATE_RANGE", "The range may not end after today");
        if (start.isAfter(end)) throw new Invalid("INVALID_DATE_RANGE", "The range starts after it ends");
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new Invalid("INVALID_DATE_RANGE", "A report covers at most " + MAX_DAYS + " days");
        }
        String fw = null;
        if (template.frameworkParameter()) {
            fw = framework == null || framework.isBlank() ? ALL_FRAMEWORKS : framework.strip();
            if (!ALL_FRAMEWORKS.equals(fw) && !FRAMEWORKS.contains(fw)) {
                throw new Invalid("INVALID_FRAMEWORK", "Unknown framework");
            }
        } else if (framework != null && !framework.isBlank()) {
            throw new Invalid("INVALID_FRAMEWORK", "This report takes no framework");
        }
        return new ReportParameters(start, end, fw);
    }

    private static Optional<LocalDate> date(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(value.strip()));
        } catch (DateTimeParseException e) {
            throw new Invalid("INVALID_DATE_RANGE", "Dates are yyyy-MM-dd");
        }
    }

    /** Window start (inclusive), the start of {@code from} in the product time zone. */
    public Instant windowStart() {
        return from.atStartOfDay(JobWindowPolicy.ZONE_ID).toInstant();
    }

    /** Window end (exclusive), the start of the day after {@code to}. */
    public Instant windowEnd() {
        return to.plusDays(1).atStartOfDay(JobWindowPolicy.ZONE_ID).toInstant();
    }

    /** Stable key order for {@code parameters_json} and every rendered header. */
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("from", from.toString());
        m.put("to", to.toString());
        if (framework != null) m.put("framework", framework);
        return m;
    }
}
