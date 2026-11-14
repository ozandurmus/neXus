package com.securityexpert.nexus.ui2.service.lifecyclesource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one place that knows the lifecycle catalog's sources (contract section 3): the online source the advisory-fetch
 * workload hands documents for, and the page forms an operator may upload. Nothing else in the service names a vendor
 * host for the lifecycle catalog.
 */
public final class LifecycleSources {

    /** The online sources; each needs its host approved for the proxy (contract section 3; D-L1 and D-L2, 2026-11-14). */
    public static final List<String> FEED_KEYS = List.of("cisco_eol", "checkpoint_lifecycle", "paloalto_eol");

    /** The Cisco bulletins: plain English pages under the security collateral path of the approved host. */
    static final String CISCO_HOST = "www.cisco.com";
    static final String CISCO_BULLETIN_PREFIX = "/c/en/us/products/collateral/security/";
    static final String CISCO_LISTING = "https://www.cisco.com/c/en/us/products/security/asa-firepower-services/eos-eol-notice-listing.html";

    /** The page form of a fetched document of an online source, or null. */
    public static String feedPageForm(String source, String url) {
        if ("cisco_eol".equals(source)) return CiscoEolBulletinParser.PAGE_FORM;
        if ("checkpoint_lifecycle".equals(source)) return CheckPointLifecycleParser.PAGE_FORM;
        if ("paloalto_eol".equals(source)) {
            return url != null && url.equals(PAGE_FORMS.get(PaloAltoEolParser.HARDWARE_FORM).canonicalUrl())
                    ? PaloAltoEolParser.HARDWARE_FORM : PaloAltoEolParser.SOFTWARE_FORM;
        }
        return null;
    }

    /** An uploadable page form: the vendor, the source key its entries are stored under, and the page it is a copy of. */
    public record PageForm(String key, String vendor, String source, String canonicalUrl, String label) { }

    public static final Map<String, PageForm> PAGE_FORMS = Map.of(
            CiscoEolBulletinParser.PAGE_FORM, new PageForm(CiscoEolBulletinParser.PAGE_FORM, "CISCO_ASA", "cisco_eol", CISCO_LISTING,
                    "Cisco End-of-Sale and End-of-Life bulletin (one bulletin page)"),
            PaloAltoEolParser.SOFTWARE_FORM, new PageForm(PaloAltoEolParser.SOFTWARE_FORM, "PALOALTO", "paloalto_eol",
                    "https://www.paloaltonetworks.com/services/support/end-of-life-announcements/end-of-life-summary",
                    "Palo Alto Networks End-of-Life Summary (PAN-OS & Panorama)"),
            PaloAltoEolParser.HARDWARE_FORM, new PageForm(PaloAltoEolParser.HARDWARE_FORM, "PALOALTO", "paloalto_eol",
                    "https://www.paloaltonetworks.com/services/support/end-of-life-announcements/hardware-end-of-life-dates",
                    "Palo Alto Networks Hardware End-of-Life Dates"),
            CheckPointLifecycleParser.PAGE_FORM, new PageForm(CheckPointLifecycleParser.PAGE_FORM, "CHECKPOINT", "checkpoint_lifecycle",
                    "https://www.checkpoint.com/support-services/support-life-cycle-policy/",
                    "Check Point Support Life Cycle Policy (appliances and versions)"));

    /** The display order of the page forms. */
    public static final List<String> PAGE_FORM_ORDER = List.of(CheckPointLifecycleParser.PAGE_FORM, PaloAltoEolParser.SOFTWARE_FORM,
            PaloAltoEolParser.HARDWARE_FORM, CiscoEolBulletinParser.PAGE_FORM);

    private LifecycleSources() {
    }

    public static boolean feedKnown(String source) {
        return source != null && FEED_KEYS.contains(source);
    }

    /**
     * True when {@code url} is a document the fetcher may have read for {@code source}: a Cisco bulletin (https, approved host,
     * bulletin path), or exactly the Check Point or Palo Alto page of the source.
     */
    public static boolean feedDocumentAllowed(String source, String url) {
        if (url == null) return false;
        if ("checkpoint_lifecycle".equals(source)) return url.equals(PAGE_FORMS.get(CheckPointLifecycleParser.PAGE_FORM).canonicalUrl());
        if ("paloalto_eol".equals(source)) {
            return url.equals(PAGE_FORMS.get(PaloAltoEolParser.SOFTWARE_FORM).canonicalUrl())
                    || url.equals(PAGE_FORMS.get(PaloAltoEolParser.HARDWARE_FORM).canonicalUrl());
        }
        if (!"cisco_eol".equals(source) || url.length() > 300 || !url.startsWith("https://")) return false;
        try {
            URI uri = new URI(url);
            String path = uri.getRawPath();
            return "https".equals(uri.getScheme()) && uri.getHost() != null && CISCO_HOST.equals(uri.getHost().toLowerCase(Locale.ROOT))
                    && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && path != null && path.startsWith(CISCO_BULLETIN_PREFIX) && path.endsWith(".html")
                    && !path.contains("..") && !path.contains("//") && !path.contains("%") && path.matches("[A-Za-z0-9/._~-]+");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** A code word for a page path, used as the publication reference when a bulletin carries no id. */
    static String pathRef(String url) {
        String name = url == null ? "" : url.substring(url.lastIndexOf('/') + 1).replaceFirst("\\.html$", "");
        name = name.replaceAll("[^A-Za-z0-9_.-]", "-");
        if (name.isEmpty()) name = "page";
        return name.length() > 80 ? name.substring(0, 80) : name;
    }
}
