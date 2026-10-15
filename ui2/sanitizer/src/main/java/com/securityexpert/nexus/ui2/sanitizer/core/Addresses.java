package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.Locale;

/**
 * Strict textual address parsing and rendering. IPv4 is dotted decimal without leading zeros (so {@code 010.1.1.1},
 * octal-looking or out-of-range forms are not addresses); IPv6 follows RFC 4291 §2.2 including an embedded dotted
 * IPv4 tail, rendered either in RFC 5952 canonical form or fully expanded, in either letter case.
 */
public final class Addresses {

    private Addresses() {
    }

    /** How an IPv6 address was written, so its pseudonym (and its revert) is written the same way. */
    public record V6Style(boolean upper, boolean expanded, boolean dottedTail) {
        public static final V6Style CANONICAL = new V6Style(false, false, false);

        public static V6Style of(String text) {
            boolean hasUpper = false;
            boolean hasLower = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 'A' && c <= 'F') hasUpper = true;
                if (c >= 'a' && c <= 'f') hasLower = true;
            }
            boolean dotted = text.indexOf('.') >= 0;
            boolean expanded = !text.contains("::") && text.split(":", -1).length == (dotted ? 7 : 8)
                    && java.util.Arrays.stream(text.split(":", -1)).limit(dotted ? 6 : 8).allMatch(g -> g.length() == 4);
            return new V6Style(hasUpper && !hasLower, expanded, dotted);
        }
    }

    /** Parses IPv4 or IPv6 text (no zone, no prefix) or throws {@link IllegalArgumentException}. */
    public static byte[] parse(String text) {
        byte[] v4 = parseV4(text);
        if (v4 != null) return v4;
        byte[] v6 = parseV6(text);
        if (v6 != null) return v6;
        throw new IllegalArgumentException("not an address");
    }

    /** Strict dotted-decimal IPv4, or {@code null}. */
    public static byte[] parseV4(String text) {
        int length = text.length();
        if (length < 7 || length > 15) return null;
        byte[] out = new byte[4];
        int part = 0;
        int value = -1;
        int digits = 0;
        for (int i = 0; i <= length; i++) {
            char c = i < length ? text.charAt(i) : '.';
            if (c == '.') {
                if (digits == 0 || part > 3) return null;
                out[part++] = (byte) value;
                value = -1;
                digits = 0;
            } else if (c >= '0' && c <= '9') {
                if (digits > 0 && value == 0) return null; // leading zero
                value = (value < 0 ? 0 : value * 10) + (c - '0');
                if (++digits > 3 || value > 255) return null;
            } else {
                return null;
            }
        }
        return part == 4 ? out : null;
    }

    /** RFC 4291 IPv6 text (compressed, expanded, or with a dotted IPv4 tail), or {@code null}. */
    public static byte[] parseV6(String text) {
        if (text.length() < 2 || text.length() > 45 || text.indexOf(':') < 0) return null;
        int doubleColon = text.indexOf("::");
        if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) return null;
        String head = doubleColon >= 0 ? text.substring(0, doubleColon) : text;
        String tail = doubleColon >= 0 ? text.substring(doubleColon + 2) : "";
        int[] groups = new int[8];
        int[] headGroups = groups(head, true, doubleColon < 0);
        if (headGroups == null) return null;
        int[] tailGroups = doubleColon >= 0 ? groups(tail, true, true) : new int[0];
        if (tailGroups == null) return null;
        if (doubleColon >= 0 && headGroups.length + tailGroups.length > 7) return null;
        if (doubleColon < 0 && headGroups.length != 8) return null;
        // An embedded IPv4 tail may only end the address.
        if (doubleColon >= 0 && tail.isEmpty() && head.indexOf('.') >= 0) return null;
        if (doubleColon >= 0 && !tail.isEmpty() && head.indexOf('.') >= 0) return null;
        System.arraycopy(headGroups, 0, groups, 0, headGroups.length);
        System.arraycopy(tailGroups, 0, groups, 8 - tailGroups.length, tailGroups.length);
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[2 * i] = (byte) (groups[i] >> 8);
            out[2 * i + 1] = (byte) groups[i];
        }
        return out;
    }

    /** Hex groups of one side of {@code ::}; a dotted IPv4 tail counts as two groups when {@code last}. */
    private static int[] groups(String side, boolean allowEmpty, boolean last) {
        if (side.isEmpty()) return allowEmpty ? new int[0] : null;
        String[] parts = side.split(":", -1);
        int count = parts.length;
        String lastPart = parts[count - 1];
        boolean dotted = lastPart.indexOf('.') >= 0;
        if (dotted && !last) return null;
        int[] out = new int[dotted ? count + 1 : count];
        for (int i = 0; i < count; i++) {
            String p = parts[i];
            if (dotted && i == count - 1) {
                byte[] v4 = parseV4(p);
                if (v4 == null) return null;
                out[i] = ((v4[0] & 0xff) << 8) | (v4[1] & 0xff);
                out[i + 1] = ((v4[2] & 0xff) << 8) | (v4[3] & 0xff);
                continue;
            }
            if (p.isEmpty() || p.length() > 4) return null;
            int value = 0;
            for (int j = 0; j < p.length(); j++) {
                int d = Character.digit(p.charAt(j), 16);
                if (d < 0) return null;
                value = (value << 4) | d;
            }
            out[i] = value;
        }
        return out;
    }

    public static String formatV4(byte[] a) {
        return (a[0] & 0xff) + "." + (a[1] & 0xff) + "." + (a[2] & 0xff) + "." + (a[3] & 0xff);
    }

    public static String format(byte[] a) {
        return a.length == 4 ? formatV4(a) : formatV6(a, V6Style.CANONICAL);
    }

    /** Renders 16 bytes in a style: RFC 5952 (longest zero run of two or more groups compressed) or expanded. */
    public static String formatV6(byte[] a, V6Style style) {
        int groupCount = style.dottedTail() ? 6 : 8;
        String[] parts = new String[groupCount];
        int[] g = new int[groupCount];
        for (int i = 0; i < groupCount; i++) {
            g[i] = ((a[2 * i] & 0xff) << 8) | (a[2 * i + 1] & 0xff);
            parts[i] = style.expanded() ? String.format("%04x", g[i]) : Integer.toHexString(g[i]);
        }
        String text;
        int bestStart = -1;
        int bestLength = 1;
        if (!style.expanded()) {
            for (int i = 0; i < groupCount; ) {
                if (g[i] != 0) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < groupCount && g[j] == 0) j++;
                if (j - i > bestLength) {
                    bestStart = i;
                    bestLength = j - i;
                }
                i = j;
            }
        }
        if (bestStart < 0) {
            text = String.join(":", parts);
        } else {
            text = String.join(":", java.util.Arrays.copyOfRange(parts, 0, bestStart)) + "::"
                    + String.join(":", java.util.Arrays.copyOfRange(parts, bestStart + bestLength, groupCount));
        }
        if (style.dottedTail()) {
            text += (text.endsWith(":") ? "" : ":") + (a[12] & 0xff) + "." + (a[13] & 0xff) + "." + (a[14] & 0xff) + "."
                    + (a[15] & 0xff);
        }
        return style.upper() ? text.toUpperCase(Locale.ROOT) : text;
    }
}
