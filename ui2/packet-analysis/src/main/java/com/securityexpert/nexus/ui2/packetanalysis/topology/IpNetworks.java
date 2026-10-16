package com.securityexpert.nexus.ui2.packetanalysis.topology;

/**
 * Syntax checks of the reference {@code ipaddress.ip_address(text)} and {@code ip_network(text, strict=False)}
 * (validation only; no DNS lookup ever happens, unlike {@link java.net.InetAddress}).
 */
final class IpNetworks {

    private IpNetworks() {
    }

    /** {@code ipaddress.ip_address(text)} succeeds. */
    static boolean isAddress(String text) {
        return isV4(text) || isV6(text, true);
    }

    static void address(String text) {
        if (!isAddress(text)) {
            throw new IllegalArgumentException(Py.repr(text) + " does not appear to be an IPv4 or IPv6 address");
        }
    }

    /** {@code ipaddress.ip_network(text, strict=False)}; throws when invalid. */
    static void network(String text) {
        String[] parts = text.split("/", -1);
        boolean ok;
        if (parts.length > 2) {
            ok = false;
        } else if (isV4(parts[0])) {
            ok = parts.length == 1 || validV4Prefix(parts[1]);
        } else if (isV6(parts[0], true)) {
            ok = parts.length == 1 || validPrefix(parts[1], 128);
        } else {
            ok = false;
        }
        if (!ok) {
            throw new IllegalArgumentException(Py.repr(text) + " does not appear to be an IPv4 or IPv6 network");
        }
    }

    private static boolean validPrefix(String s, int max) {
        if (s.isEmpty() || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        if (s.length() > 3) {
            return false;
        }
        return Integer.parseInt(s) <= max;
    }

    private static boolean validV4Prefix(String s) {
        if (validPrefix(s, 32)) {
            return true;
        }
        if (s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        if (!isV4(s)) {
            return false;
        }
        long v = 0;
        for (String o : s.split("\\.")) {
            v = (v << 8) | Integer.parseInt(o);
        }
        long inv = ~v & 0xffffffffL;
        // a netmask (1s then 0s) or a host mask (0s then 1s)
        return ((inv + 1) & inv) == 0 || ((v + 1) & v) == 0;
    }

    static boolean isV4(String s) {
        String[] octets = s.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String o : octets) {
            if (o.isEmpty() || o.length() > 3 || !o.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return false;
            }
            if (o.length() > 1 && o.charAt(0) == '0') {
                return false;
            }
            if (Integer.parseInt(o) > 255) {
                return false;
            }
        }
        return true;
    }

    static boolean isV6(String s, boolean allowScope) {
        int pct = s.indexOf('%');
        if (pct >= 0) {
            if (!allowScope || pct == s.length() - 1 || s.indexOf('/') >= 0) {
                return false;
            }
            s = s.substring(0, pct);
        }
        if (s.isEmpty()) {
            return false;
        }
        String[] parts = s.split(":", -1);
        if (parts.length < 3) {
            return false;
        }
        int maxParts = 8;
        if (parts[parts.length - 1].contains(".")) {
            if (!isV4(parts[parts.length - 1])) {
                return false;
            }
            parts[parts.length - 1] = "0";
            String[] extended = new String[parts.length + 1];
            System.arraycopy(parts, 0, extended, 0, parts.length);
            extended[parts.length] = "0";
            parts = extended;
        }
        if (parts.length > maxParts + 1) {
            return false;
        }
        int skip = -1;
        for (int i = 1; i < parts.length - 1; i++) {
            if (parts[i].isEmpty()) {
                if (skip >= 0) {
                    return false;
                }
                skip = i;
            }
        }
        int hi;
        int lo;
        if (skip >= 0) {
            hi = skip;
            lo = parts.length - skip - 1;
            if (parts[0].isEmpty()) {
                hi--;
                if (hi != 0) {
                    return false;
                }
            }
            if (parts[parts.length - 1].isEmpty()) {
                lo--;
                if (lo != 0) {
                    return false;
                }
            }
            if (hi + lo >= maxParts) {
                return false;
            }
        } else {
            if (parts.length != maxParts) {
                return false;
            }
            if (parts[0].isEmpty() || parts[parts.length - 1].isEmpty()) {
                return false;
            }
            hi = parts.length;
            lo = 0;
        }
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > 4 || !p.chars().allMatch(c -> Character.digit(c, 16) >= 0 && c < 128)) {
                return false;
            }
        }
        return true;
    }
}
