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
        return header != null && header.length() <= 40 && Patterns.HOST_HEADER.matcher(header).matches();
    }
}
