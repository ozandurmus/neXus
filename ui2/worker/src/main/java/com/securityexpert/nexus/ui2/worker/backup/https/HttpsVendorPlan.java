package com.securityexpert.nexus.ui2.worker.backup.https;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The closed HTTPS request set for vendors backed up over HTTPS (V64 gate rows keyed {@code METHOD path}).
 * Measured from the Backbox trails (VENDOR_BACKUP_MEASUREMENTS_2026_09_22.md, addendum 2026-09-24).
 */
public final class HttpsVendorPlan {

    // Infoblox Grid Manager (trail 34411065)
    public static final String INFOBLOX_WAPIDOC = "/wapidoc/";
    public static final String INFOBLOX_GRID = "/wapi/v%s/grid?_return_fields=name";
    public static final String INFOBLOX_GETGRIDDATA = "/wapi/v%s/fileop?_function=getgriddata";
    public static final String INFOBLOX_GETGRIDDATA_BODY = "{\"type\": \"BACKUP\"}";
    public static final String INFOBLOX_DOWNLOADCOMPLETE = "/wapi/v%s/fileop?_function=downloadcomplete";

    // Radware DefensePro (trail 34095224)
    public static final String RADWARE_ROOT = "/";
    public static final String RADWARE_RECEIVE_CONFIGURATION = "/dynamic/File/Configuration/ReceivefromDevice";

    /** Gate keys: {@code METHOD path}, the version placeholder kept as {@code <ver>}. */
    public static final List<String> INFOBLOX_GATE_KEYS = List.of("GET /wapidoc/", "GET /wapi/v<ver>/grid",
            "POST /wapi/v<ver>/fileop?_function=getgriddata", "GET <getgriddata url, same host>",
            "POST /wapi/v<ver>/fileop?_function=downloadcomplete");
    public static final List<String> RADWARE_GATE_KEYS = List.of("GET /", "POST /dynamic/File/Configuration/ReceivefromDevice");

    private static final Pattern WAPI_VERSION = Pattern.compile("VERSION\\s*:\\s*'(\\d+(?:\\.\\d+){1,3})'");
    private static final Pattern VERSION_SAFE = Pattern.compile("\\d+(?:\\.\\d+){1,3}");

    private HttpsVendorPlan() {
    }

    /** {@code /wapidoc/} (Sphinx): the single-quoted value on the VERSION line, as Backbox reads it. */
    public static Optional<String> parseWapiVersion(String html) {
        if (html == null) {
            return Optional.empty();
        }
        Matcher m = WAPI_VERSION.matcher(html);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    public static String withVersion(String template, String version) {
        if (!VERSION_SAFE.matcher(version).matches()) {
            throw new IllegalArgumentException("not a WAPI version");
        }
        return String.format(template, version);
    }
}
