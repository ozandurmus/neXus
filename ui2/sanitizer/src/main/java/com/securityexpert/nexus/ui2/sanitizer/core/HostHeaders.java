package com.securityexpert.nexus.ui2.sanitizer.core;

/**
 * Column headers and JSON keys that say "the values here are host names" ({@code hostname}, {@code device},
 * {@code server}, {@code fqdn}, {@code cihaz adı}, ...). Under such a header a bare single-word value is replaced as
 * a host name even though free text would leave a lone word alone.
 */
public final class HostHeaders {

    private HostHeaders() {
    }

    public static boolean isHostHeader(String header) {
        if (header == null) return false;
        String h = header.startsWith("\uFEFF") ? header.substring(1) : header;
        return h.length() <= 40 && Patterns.HOST_HEADER.matcher(h).matches();
    }
}
