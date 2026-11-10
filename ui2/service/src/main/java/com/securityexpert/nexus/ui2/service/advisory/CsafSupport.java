package com.securityexpert.nexus.ui2.service.advisory;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Shared, strict reading helpers of the CSAF 2.0 based parsers (contract sections 1 rule 5 and 7, section 6). Document
 * text is untrusted: it is stored as plain text, truncated, and never interpreted.
 */
final class CsafSupport {

    /** The longest stored title (contract section 5). */
    static final int MAX_TITLE = 300;

    /** The longest stored fix statement (contract section 5). */
    static final int MAX_FIXED_TEXT = 200;

    private static final Pattern CVE_ID = Pattern.compile("CVE-\\d{4}-\\d{4,}");
    private static final Pattern TRACKING_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** Strict JSON: no default typing, duplicate keys and trailing tokens refused. */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private CsafSupport() {
    }

    static JsonNode readTree(byte[] document) throws IOException {
        return MAPPER.readTree(document);
    }

    /** True when the first non-blank byte is {@code first}. */
    static boolean startsWith(byte[] document, char first) {
        if (document == null) {
            return false;
        }
        for (byte b : document) {
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == (byte) 0xEF || b == (byte) 0xBB || b == (byte) 0xBF) {
                continue;
            }
            return b == first;
        }
        return false;
    }

    /** The text of a string node, or null for anything else. */
    static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /** Plain text: control characters become spaces, whitespace is collapsed, the result is cut to {@code max}. */
    static String plain(String raw, int max) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), max + 1));
        boolean space = true;
        for (int i = 0; i < raw.length() && sb.length() < max; i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                if (!space) {
                    sb.append(' ');
                    space = true;
                }
            } else {
                sb.append(c);
                space = false;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') {
            end--;
        }
        return sb.substring(0, end);
    }

    /** The calendar date of an ISO date or date-time text (the first ten characters), or null. */
    static LocalDate date(String raw) {
        if (raw == null || raw.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code document.tracking.id} when it is a plain identifier, else null. */
    static String trackingId(JsonNode root) {
        String id = text(root.path("document").path("tracking").path("id"));
        return id != null && TRACKING_ID.matcher(id).matches() ? id : null;
    }

    /** The highest {@code cvss_v3.baseScore} of all vulnerabilities and scores, or null when there is none. */
    static BigDecimal highestScore(JsonNode vulnerabilities) {
        BigDecimal best = null;
        if (vulnerabilities == null || !vulnerabilities.isArray()) {
            return null;
        }
        for (JsonNode vuln : vulnerabilities) {
            for (JsonNode score : vuln.path("scores")) {
                JsonNode base = score.path("cvss_v3").path("baseScore");
                if (!base.isNumber()) {
                    continue;
                }
                BigDecimal value = base.decimalValue();
                if (value.signum() < 0 || value.compareTo(BigDecimal.TEN) > 0) {
                    continue;
                }
                if (best == null || value.compareTo(best) > 0) {
                    best = value;
                }
            }
        }
        return best;
    }

    /** The CVSS v3 qualitative rating of a base score. */
    static AdvisoryModel.Severity severity(BigDecimal score) {
        if (score == null) {
            return AdvisoryModel.Severity.UNKNOWN;
        }
        if (score.signum() == 0) {
            return AdvisoryModel.Severity.NONE;
        }
        if (score.compareTo(new BigDecimal("4.0")) < 0) {
            return AdvisoryModel.Severity.LOW;
        }
        if (score.compareTo(new BigDecimal("7.0")) < 0) {
            return AdvisoryModel.Severity.MEDIUM;
        }
        if (score.compareTo(new BigDecimal("9.0")) < 0) {
            return AdvisoryModel.Severity.HIGH;
        }
        return AdvisoryModel.Severity.CRITICAL;
    }

    /** The distinct CVE ids of {@code vulnerabilities[].cve} that match the CVE pattern, in document order. */
    static List<String> cveIds(JsonNode vulnerabilities) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (vulnerabilities != null && vulnerabilities.isArray()) {
            for (JsonNode vuln : vulnerabilities) {
                String cve = text(vuln.path("cve"));
                if (cve != null && isCveId(cve.trim())) {
                    ids.add(cve.trim());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    static boolean isCveId(String candidate) {
        return candidate != null && CVE_ID.matcher(candidate).matches();
    }

    /** The https address when {@code url} is one, else null (contract rule 7: links only on https). */
    static String httpsUrl(String url) {
        if (url == null || url.length() > 500 || !url.startsWith("https://")) {
            return null;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= ' ' || c == '"' || c == '<' || c == '>') {
                return null;
            }
        }
        return url;
    }
}
