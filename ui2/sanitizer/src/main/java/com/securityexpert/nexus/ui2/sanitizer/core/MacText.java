package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.HexFormat;
import java.util.Locale;

/** MAC text forms: {@code 00:11:22:33:44:55}, {@code 00-11-22-33-44-55}, {@code 0011.2233.4455}, either case. */
public final class MacText {

    private MacText() {
    }

    public record Style(char separator, boolean upper) {
        public static Style of(String text) {
            char sep = text.indexOf('.') >= 0 ? '.' : (text.indexOf('-') >= 0 ? '-' : ':');
            boolean hasUpper = text.chars().anyMatch(c -> c >= 'A' && c <= 'F');
            boolean hasLower = text.chars().anyMatch(c -> c >= 'a' && c <= 'f');
            return new Style(sep, hasUpper && !hasLower);
        }
    }

    public static byte[] parse(String text) {
        if (text.indexOf('.') >= 0) {
            String hex = text.replace(".", "");
            if (hex.length() != 12) throw new IllegalArgumentException("not a MAC");
            return HexFormat.of().parseHex(hex);
        }
        String[] parts = text.split("[:-]", -1);
        if (parts.length != 6) throw new IllegalArgumentException("not a MAC");
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 2) throw new IllegalArgumentException("not a MAC");
            int val = 0;
            for (int j = 0; j < p.length(); j++) {
                int d = Character.digit(p.charAt(j), 16);
                if (d < 0) throw new IllegalArgumentException("not a MAC");
                val = (val << 4) | d;
            }
            out[i] = (byte) val;
        }
        return out;
    }

    public static String format(byte[] mac, Style style) {
        String hex = HexFormat.of().formatHex(mac);
        StringBuilder out = new StringBuilder(17);
        if (style.separator() == '.') {
            out.append(hex, 0, 4).append('.').append(hex, 4, 8).append('.').append(hex, 8, 12);
        } else {
            for (int i = 0; i < 6; i++) {
                if (i > 0) out.append(style.separator());
                out.append(hex, 2 * i, 2 * i + 2);
            }
        }
        return style.upper() ? out.toString().toUpperCase(Locale.ROOT) : out.toString();
    }

    public static String canonical(byte[] mac) {
        return format(mac, new Style(':', false));
    }
}
