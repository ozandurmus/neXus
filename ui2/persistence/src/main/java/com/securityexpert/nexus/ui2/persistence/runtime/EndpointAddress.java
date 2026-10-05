package com.securityexpert.nexus.ui2.persistence.runtime;

import java.net.URI;
import java.util.Locale;

/** Representation-only normalization for admission. Transport/trust callers retain the original host case. */
public final class EndpointAddress {
    private EndpointAddress() {}
    private static URI uri(String address) {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("ENDPOINT_MISSING");
        String value = address.strip();
        if (!value.contains("://")) {
            long colons = value.chars().filter(c -> c == ':').count();
            if (colons > 1 && !value.startsWith("[")) value = "[" + value + "]";
            value = "https://" + value;
        }
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("ENDPOINT_INVALID"); }
        if (uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("ENDPOINT_INVALID");
        return uri;
    }
    public static String host(String address) {
        String host = uri(address).getHost();
        return host.startsWith("[") ? host.substring(1, host.length()-1) : host;
    }
    public static int port(String address, int defaultPort) {
        int explicit = uri(address).getPort();
        int port = explicit < 0 ? defaultPort : explicit;
        if (port < 1 || port > 65535) throw new IllegalArgumentException("ENDPOINT_INVALID");
        return port;
    }
    public static String key(String address, int defaultPort) { return keyHost(host(address), port(address, defaultPort)); }
    public static String keyHost(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException("ENDPOINT_INVALID");
        host = host.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length()-1);
        return "addr:" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }
}
