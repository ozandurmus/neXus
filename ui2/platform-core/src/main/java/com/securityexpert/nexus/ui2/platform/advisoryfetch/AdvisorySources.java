package com.securityexpert.nexus.ui2.platform.advisoryfetch;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * The closed list of sources, hosts and path prefixes the fetcher may request (contract section 2). Every outbound
 * address is checked against it immediately before the request is sent; nothing else is ever requested.
 */
public final class AdvisorySources {

    /** One source: its key, its one host, the path prefixes documents may have, and its fixed start document. */
    public record Source(String key, String host, List<String> pathPrefixes, String startPath) {

        public URI startUri() {
            return URI.create("https://" + host + startPath);
        }
    }

    public static final Source PALOALTO = new Source("paloalto", "security.paloaltonetworks.com",
            List.of("/api/v1/products/PAN-OS/advisories"), "/api/v1/products/PAN-OS/advisories");
    public static final Source FORTINET = new Source("fortinet", "filestore.fortinet.com",
            List.of("/fortiguard/rss/ir.xml", "/fortiguard/psirt/"), "/fortiguard/rss/ir.xml");
    public static final Source CISCO = new Source("cisco", "www.cisco.com",
            List.of("/.well-known/csaf/"), "/.well-known/csaf/changes.csv");
    public static final Source CISA_KEV = new Source("cisa_kev", "www.cisa.gov",
            List.of("/sites/default/files/feeds/known_exploited_vulnerabilities.json"),
            "/sites/default/files/feeds/known_exploited_vulnerabilities.json");

    public static final List<Source> ALL = List.of(PALOALTO, FORTINET, CISCO, CISA_KEV);

    private static final int MAX_ADDRESS_LENGTH = 300;

    private AdvisorySources() {
    }

    public static Optional<Source> byKey(String key) {
        return ALL.stream().filter(source -> source.key().equals(key)).findFirst();
    }

    /**
     * True when {@code uri} is a plain {@code https} address on the source's host with one of its path prefixes: no user
     * info, no port, no query, no fragment, no escape or dot segment, no doubled slash, bounded length. A prefix that
     * ends in {@code /} is a directory prefix; any other prefix must match the whole path.
     */
    public static boolean allowed(Source source, URI uri) {
        if (source == null || uri == null) {
            return false;
        }
        String text = uri.toString();
        if (text.length() > MAX_ADDRESS_LENGTH || !"https".equals(uri.getScheme()) || !source.host().equals(uri.getHost())
                || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return false;
        }
        String path = uri.getRawPath();
        if (path == null || path.contains("%") || path.contains("..") || path.contains("//") || path.contains("\\")
                || !path.matches("[A-Za-z0-9/._~-]+")) {
            return false;
        }
        for (String prefix : source.pathPrefixes()) {
            if (prefix.endsWith("/") ? path.startsWith(prefix) && path.length() > prefix.length() : path.equals(prefix)) {
                return true;
            }
        }
        return false;
    }
}
