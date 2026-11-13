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
    public record Spec(String key, String host, List<String> documentPaths, Set<String> linkHosts, Map<String, List<String>> moreHosts) {
        public Spec(String key, String host, List<String> documentPaths, Set<String> linkHosts) {
            this(key, host, documentPaths, linkHosts, Map.of());
        }

        List<String> pathsFor(String requestHost) {
            if (host.equals(requestHost)) return documentPaths;
            return requestHost == null ? List.of() : moreHosts.getOrDefault(requestHost, List.of());
        }
    }

    /** The source keys in display order (contract section 2 and amendment A-2026-11-13). */
    public static final List<String> KEYS = List.of("paloalto", "fortinet", "cisco", "cisa_kev", "checkpoint");

    private static final Map<String, Spec> SPECS = Map.of(
            "paloalto", new Spec("paloalto", "security.paloaltonetworks.com",
                    List.of("/api/v1/products/PAN-OS/advisories"), Set.of("security.paloaltonetworks.com")),
            "fortinet", new Spec("fortinet", "filestore.fortinet.com",
                    List.of("/fortiguard/rss/ir.xml", "/fortiguard/psirt/"),
                    Set.of("filestore.fortinet.com", "www.fortiguard.com", "fortiguard.fortinet.com")),
            "cisco", new Spec("cisco", "www.cisco.com", List.of("/.well-known/csaf/"),
                    Set.of("www.cisco.com", "sec.cloudapps.cisco.com", "tools.cisco.com")),
            "cisa_kev", new Spec("cisa_kev", "www.cisa.gov",
                    List.of("/sites/default/files/feeds/known_exploited_vulnerabilities.json"), Set.of("www.cisa.gov")),
            // Amendment A-2026-11-13: the Jumbo resolved-issues pages, then the CVE records they name.
            "checkpoint", new Spec("checkpoint", "sc1.checkpoint.com",
                    List.of("/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html", "/documents/Jumbo_HFA/R82/All_Resolved_Issues.html",
                            "/documents/Jumbo_HFA/R82.10/All_Resolved_Issues.html", "/documents/Jumbo_HFA/R82.20/All_Resolved_Issues.html"),
                    Set.of("sc1.checkpoint.com", "support.checkpoint.com", "raw.githubusercontent.com"),
                    Map.of("raw.githubusercontent.com", List.of("/CVEProject/cvelistV5/main/cves/"))));

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
        if (spec == null || uri == null) return false;
        List<String> paths = spec.pathsFor(uri.getHost().toLowerCase(Locale.ROOT));
        if (paths.isEmpty()) return false;
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
        String path = uri.getRawPath();
        if (path == null || path.contains("%") || path.contains("..") || path.contains("//") || path.contains("\\")) return false;
        for (String allowed : paths) {
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
