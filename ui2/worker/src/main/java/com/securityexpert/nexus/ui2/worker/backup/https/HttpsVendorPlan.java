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

    // Radware Cyber Controller (REST reference 10.3.0; RADWARE_CYBER_CONTROLLER_BACKUP_API_GATE_ENTRIES.md, V67)
    public static final String CC_LOGIN = "/mgmt/system/user/login";
    public static final String CC_ALLDEVICES = "/mgmt/system/config/itemlist/alldevices";
    public static final String CC_GETCFG = "/mgmt/device/byip/%s/config/getcfg?saveToDb=false&includePrivateKeys=true&passphrase=%s";
    public static final String CC_LOGOUT = "/mgmt/system/user/logout";
    public static final List<String> CC_GATE_KEYS = List.of("POST /mgmt/system/user/login", "GET /mgmt/system/config/itemlist/alldevices",
            "GET /mgmt/device/byip/<deviceIp>/config/getcfg", "POST /mgmt/system/user/logout");

    /** Gate keys: {@code METHOD path}, the version placeholder kept as {@code <ver>}. */
    public static final List<String> INFOBLOX_GATE_KEYS = List.of("GET /wapidoc/", "GET /wapi/v<ver>/grid",
            "POST /wapi/v<ver>/fileop?_function=getgriddata", "GET <getgriddata url, same host>",
            "POST /wapi/v<ver>/fileop?_function=downloadcomplete");
    public static final List<String> RADWARE_GATE_KEYS = List.of("GET /", "POST /dynamic/File/Configuration/ReceivefromDevice");

    private static final Pattern WAPI_VERSION = Pattern.compile("VERSION\\s*:\\s*'(\\d+(?:\\.\\d+){1,3})'");
    private static final Pattern VERSION_SAFE = Pattern.compile("\\d+(?:\\.\\d+){1,3}");

    private HttpsVendorPlan() {
    }

    /** The getcfg path; the address and the passphrase URL-encoded. The result carries the passphrase: never log it. */
    public static String ccGetcfg(String deviceIp, String passphrase) {
        return String.format(CC_GETCFG, java.net.URLEncoder.encode(deviceIp, java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(passphrase, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Whether the Cyber Controller's device list names {@code address}: any text value in the answer equal to it. The
     * list's shape on 10.13 is MEASURE FIRST, so no field name is assumed; equality on a whole value, never a prefix.
     */
    public static boolean listsAddress(com.fasterxml.jackson.databind.JsonNode node, String address) {
        if (node == null || address == null) {
            return false;
        }
        if (node.isTextual()) {
            return node.asText().strip().equalsIgnoreCase(address.strip());
        }
        for (com.fasterxml.jackson.databind.JsonNode child : node) {
            if (listsAddress(child, address)) {
                return true;
            }
        }
        return false;
    }

    /** The field names of the list's first object -- names only, for the first measurement record; never values. */
    public static java.util.Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        java.util.ArrayDeque<com.fasterxml.jackson.databind.JsonNode> queue = new java.util.ArrayDeque<>();
        queue.add(node);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 200) {
            com.fasterxml.jackson.databind.JsonNode n = queue.poll();
            if (n.isObject()) {
                n.fieldNames().forEachRemaining(names::add);
            }
            n.forEach(queue::add);
        }
        return names;
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
