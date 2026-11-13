package com.securityexpert.nexus.ui2.platform.advisoryfetch;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The closed list of sources, hosts and path prefixes the fetcher may request (contract section 2 and amendment
 * A-2026-11-13). Every outbound
 * address is checked against it immediately before the request is sent; nothing else is ever requested.
 */
public final class AdvisorySources {

    /**
     * One source: its key, its main host, the path prefixes documents may have there, its fixed start document, and any
     * further hosts with their own path prefixes (Check Point: the CVE records the resolved-issues pages name).
     */
    public record Source(String key, String host, List<String> pathPrefixes, String startPath, Map<String, List<String>> moreHosts) {

        public Source(String key, String host, List<String> pathPrefixes, String startPath) {
            this(key, host, pathPrefixes, startPath, Map.of());
        }

        public Source {
            pathPrefixes = List.copyOf(pathPrefixes);
            moreHosts = Map.copyOf(moreHosts);
        }

        public URI startUri() {
            return URI.create("https://" + host + startPath);
        }

        /** The path prefixes of {@code requestHost} for this source, or empty when the host is not one of its hosts. */
        List<String> prefixesFor(String requestHost) {
            if (requestHost == null) {
                return List.of();
            }
            if (host.equals(requestHost)) {
                return pathPrefixes;
            }
            return moreHosts.getOrDefault(requestHost, List.of());
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

    /**
     * The Jumbo Hotfix Accumulator "All Resolved Issues" pages of the Gaia trains this source covers (amendment
     * A-2026-11-13), in fetch order. Each is a fixed document; the first one is the source's start document.
     */
    public static final List<String> CHECKPOINT_JUMBO_PAGES = List.of(
            "/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html",
            "/documents/Jumbo_HFA/R82/All_Resolved_Issues.html",
            "/documents/Jumbo_HFA/R82.10/All_Resolved_Issues.html",
            "/documents/Jumbo_HFA/R82.20/All_Resolved_Issues.html");
    /** The CVE records of the CVE Program's public list, by the fixed layout of that list. */
    public static final String CVELIST_HOST = "raw.githubusercontent.com";
    public static final String CVELIST_PREFIX = "/CVEProject/cvelistV5/main/cves/";
    public static final Source CHECKPOINT = new Source("checkpoint", "sc1.checkpoint.com", CHECKPOINT_JUMBO_PAGES,
            CHECKPOINT_JUMBO_PAGES.getFirst(), Map.of(CVELIST_HOST, List.of(CVELIST_PREFIX)));

    

    /**
     * The Cisco End-of-Sale and End-of-Life bulletins for the lifecycle catalog (LIFECYCLE_CATALOG_SOURCES_CONTRACT.md
     * section 3.1): the two product listing pages, then the English bulletin pages they link under the security collateral
     * path. Same approved host as {@link #CISCO}; its documents go to the lifecycle ingest endpoint, not the advisory one.
     */
    public static final String CISCO_EOL_ASA_LISTING = "/c/en/us/products/security/asa-firepower-services/eos-eol-notice-listing.html";
    public static final String CISCO_EOL_FIREPOWER_LISTING = "/c/en/us/products/security/firepower-ngfw/eos-eol-notice-listing.html";
    public static final String CISCO_EOL_BULLETINS = "/c/en/us/products/collateral/security/";
    public static final Source CISCO_EOL = new Source("cisco_eol", "www.cisco.com",
            List.of(CISCO_EOL_ASA_LISTING, CISCO_EOL_FIREPOWER_LISTING, CISCO_EOL_BULLETINS), CISCO_EOL_ASA_LISTING);

    public static final List<Source> ALL = List.of(PALOALTO, FORTINET, CISCO, CISA_KEV, CHECKPOINT, CISCO_EOL);

    /** The service's internal ingest path for a source: lifecycle sources and advisory sources have their own endpoints. */
    public static String ingestPath(String sourceKey) {
        return (CISCO_EOL.key().equals(sourceKey) ? "/internal/lifecycle-sources/" : "/internal/advisories/") + sourceKey;
    }

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
        List<String> prefixes = source.prefixesFor(uri.getHost());
        if (text.length() > MAX_ADDRESS_LENGTH || !"https".equals(uri.getScheme()) || prefixes.isEmpty()
                || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return false;
        }
        String path = uri.getRawPath();
        if (path == null || path.contains("%") || path.contains("..") || path.contains("//") || path.contains("\\")
                || !path.matches("[A-Za-z0-9/._~-]+")) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix.endsWith("/") ? path.startsWith(prefix) && path.length() > prefix.length() : path.equals(prefix)) {
                return true;
            }
        }
        return false;
    }
}
