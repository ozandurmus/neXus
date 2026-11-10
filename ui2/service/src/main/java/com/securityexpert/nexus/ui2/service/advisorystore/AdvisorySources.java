package com.securityexpert.nexus.ui2.service.advisorystore;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one place that knows which host and path prefix each advisory source may be fetched from (contract section 2) and
 * which hosts an advisory link may point at (contract rule 7). Nothing else in the service hard-codes a host.
 */
public final class AdvisorySources {

    private AdvisorySources() {
    }

    /**
     * One source. {@code documentPaths}: a path ending in {@code /} is a prefix, any other path must match exactly.
     * {@code linkHosts}: hosts an advisory page link of this source may have (the document host is always one of them).
     */
    public record Spec(String key, String host, List<String> documentPaths, Set<String> linkHosts) { }

    /** The four source keys in display order (contract section 2). */
    public static final List<String> KEYS = List.of("paloalto", "fortinet", "cisco", "cisa_kev");

    private static final Map<String, Spec> SPECS = Map.of(
            "paloalto", new Spec("paloalto", "security.paloaltonetworks.com",
                    List.of("/api/v1/products/PAN-OS/advisories"), Set.of("security.paloaltonetworks.com")),
            "fortinet", new Spec("fortinet", "filestore.fortinet.com",
                    List.of("/fortiguard/rss/ir.xml", "/fortiguard/psirt/"),
                    Set.of("filestore.fortinet.com", "www.fortiguard.com", "fortiguard.fortinet.com")),
            "cisco", new Spec("cisco", "www.cisco.com", List.of("/.well-known/csaf/"),
                    Set.of("www.cisco.com", "sec.cloudapps.cisco.com", "tools.cisco.com")),
            "cisa_kev", new Spec("cisa_kev", "www.cisa.gov",
                    List.of("/sites/default/files/feeds/known_exploited_vulnerabilities.json"), Set.of("www.cisa.gov")));

    /** The document host of a source, or null for an unknown source. */
    public static String hostFor(String source) {
        Spec spec = source == null ? null : SPECS.get(source);
        return spec == null ? null : spec.host();
    }

    public static boolean known(String source) {
        return source != null && SPECS.containsKey(source);
    }

    /** True when {@code url} is an {@code https} address on the source's host and one of its document paths. */
    public static boolean documentUrlAllowed(String source, String url) {
        Spec spec = source == null ? null : SPECS.get(source);
        URI uri = parse(url);
        if (spec == null || uri == null || !spec.host().equals(uri.getHost().toLowerCase(Locale.ROOT))) return false;
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
        String path = uri.getRawPath();
        if (path == null || path.contains("%") || path.contains("..") || path.contains("//") || path.contains("\\")) return false;
        for (String allowed : spec.documentPaths()) {
            if (allowed.endsWith("/") ? path.startsWith(allowed) && path.length() > allowed.length() : path.equals(allowed)) return true;
        }
        return false;
    }

    /** True when an advisory link may be shown: {@code https}, no credentials, on a host of the source's link list. */
    public static boolean linkAllowed(String source, String url) {
        Spec spec = source == null ? null : SPECS.get(source);
        URI uri = parse(url);
        return spec != null && uri != null && spec.linkHosts().contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    /** The URL as a URI when it is a plain {@code https} address with a host, no credentials and the default port; else null. */
    private static URI parse(String url) {
        if (url == null || url.length() > 2000 || !url.startsWith("https://")) return null;
        try {
            URI uri = new URI(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
