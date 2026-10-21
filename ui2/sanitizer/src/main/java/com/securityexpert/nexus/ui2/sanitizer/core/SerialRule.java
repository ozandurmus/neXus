package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The serial rule (2026-10-18 privacy fix, moved here 2026-10-19 so the Data Sanitizer and the masked system-log view
 * share one implementation). A serial is opaque (AGENTS.md identity law) and becomes the inventory's {@code SN-}
 * token ({@link IdentityTokens#serial}) whatever its form:
 *
 * <ul>
 *   <li>in text: the value of a JSON key {@code serial}, {@code serial_number}, {@code serialno}, {@code sn} (any case,
 *       {@code -}/{@code _}/space spelling, an {@code @} attribute prefix, quoted or a bare number -- a bare number
 *       becomes a quoted token so the line stays valid JSON), and of a {@code serial=V} / {@code serial: V} /
 *       {@code Serial Number = "V"} pair whose value carries a digit. Palo Alto JSON Lines entries carry a
 *       {@code "serial"} key on every entry (findings record 2026-10-17);</li>
 *   <li>in a structured file: every value under a serial-like JSON key or CSV column header
 *       ({@link #isSerialKey}), fail closed.</li>
 * </ul>
 * <p>An empty value, {@code <redacted>} and a value that already is an {@code SN-} token are kept.</p>
 */
public final class SerialRule {

    /** Count category of a serial replacement. */
    public static final String SERIAL = "serial";

    private static final String SERIAL_NAMES = "(?:serial(?:[-_ ]?(?:number|num|no))?|sn)";
    /** {@code "serial":"V"} or {@code "serial": 1234} (any case, an {@code @} attribute prefix allowed). */
    private static final Pattern JSON = Pattern.compile(
            "(?i)\"@?" + SERIAL_NAMES + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(-?[0-9][0-9.eE+-]*))");
    /** {@code serial=V}, {@code serial: V}, {@code Serial Number = "V"}: the value must carry a digit. */
    private static final Pattern TEXT = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])" + SERIAL_NAMES + "\\s*[=:]\\s*([\"']?)([A-Za-z0-9][A-Za-z0-9._/:-]*)");
    /**
     * A JSON key or column header naming a serial, any case and spacing: {@code serial}, {@code Serial Number},
     * {@code serial_no.}, {@code serialNo}, {@code Serial #}, {@code SN}, {@code S/N}, {@code S.N.}, the Turkish
     * {@code seri no} / {@code Seri Numarası}; one trailing {@code . : #} is allowed.
     */
    private static final Pattern HEADER = Pattern.compile(
            "(?iu)@?(?:serial(?:[-_\\s]*(?:number|num|no|nr|#))?|s\\s*/\\s*n|s\\.?\\s*n|seri(?:[-_\\s]*(?:no|nr|numaras[ıi]))?)"
                    + "\\s*[.:#]?");
    private static final Pattern TOKEN = Pattern.compile("SN-[0-9A-F]{10}");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*[0-9].*");

    private SerialRule() {
    }

    /** True when a JSON key or a column header names a serial: every value under it is one. */
    public static boolean isSerialKey(String key) {
        if (key == null) return false;
        String k = (key.startsWith("﻿") ? key.substring(1) : key).strip();
        return k.length() <= 24 && HEADER.matcher(k).matches();
    }

    /** A whole value under a serial key or column: its {@code SN-} token, fail closed. */
    public static String value(String value, IdentityTokens tokens, Counts counts) {
        if (value == null) return null;
        String trimmed = value.strip();
        if (trimmed.isEmpty() || isMasked(trimmed)) return value;
        counts.add(SERIAL);
        return tokens.serial(trimmed);
    }

    /** Every serial pair in a piece of text. */
    public static String mask(String text, IdentityTokens tokens, Counts counts) {
        if (text == null || text.length() < 4) return text;
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("serial") && !lower.contains("sn")) return text;
        return text(json(text, tokens, counts), tokens, counts);
    }

    private static String json(String text, IdentityTokens tokens, Counts counts) {
        Matcher m = JSON.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        while (m.find()) {
            boolean quoted = m.start(1) >= 0;
            int start = quoted ? m.start(1) : m.start(2);
            int end = quoted ? m.end(1) : m.end(2);
            String value = text.substring(start, end).strip();
            // Fail closed: a JSON serial is masked whatever its form; only an empty or already masked value stays.
            if (value.isEmpty() || isMasked(value)) continue;
            if (out == null) out = new StringBuilder(text.length() + 16);
            String token = tokens.serial(value);
            out.append(text, copied, start).append(quoted ? token : "\"" + token + "\"");
            copied = end;
            counts.add(SERIAL);
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    private static String text(String text, IdentityTokens tokens, Counts counts) {
        Matcher m = TEXT.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        while (m.find()) {
            int start = m.start(2);
            int end = m.end(2);
            while (end > start && ".:/-".indexOf(text.charAt(end - 1)) >= 0) end--;
            String value = text.substring(start, end);
            if (start < copied || value.isEmpty() || isMasked(value) || !HAS_DIGIT.matcher(value).matches()) continue;
            if (out == null) out = new StringBuilder(text.length() + 16);
            out.append(text, copied, start).append(tokens.serial(value));
            copied = end;
            counts.add(SERIAL);
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    private static boolean isMasked(String value) {
        return TOKEN.matcher(value).matches() || value.contains("<redacted>");
    }
}
