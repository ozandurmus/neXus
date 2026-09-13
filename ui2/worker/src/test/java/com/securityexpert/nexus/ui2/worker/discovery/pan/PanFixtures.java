package com.securityexpert.nexus.ui2.worker.discovery.pan;

/**
 * Synthetic {@code response} XML for the adapter's fixture tests, shaped to
 * the UNVERIFIED candidate paths recorded in {@code PanoramaApiFieldBinding}
 * (a live Panorama is the only thing that can confirm these are correct).
 * No real serial, hostname, address or estate value appears here -- every
 * value is invented for this test source, following cp's {@code
 * WorkerFixtures.java} discipline.
 */
final class PanFixtures {

    private PanFixtures() {
    }

    static final String FIXTURE_KEY = "fixture-panorama-key-1";
    static final String FIXTURE_USERNAME = "fixture-pan-user";
    static final String FIXTURE_PASSWORD = "fixture-pan-password";

    static String keyGenerationResponse(String key) {
        return "<response status=\"success\"><result><key>" + key + "</key></result></response>";
    }

    static String enumerationResponse(String... entries) {
        return "<response status=\"success\"><result><devices>"
                + String.join("", entries)
                + "</devices></result></response>";
    }

    static String deviceEntry(String serial, String hostname, String type, String ipv4, String ipv6,
            String peerSerial, String connected, String lastConnectTime, String certStatus, String certExpiry,
            String... vsysEntries) {
        StringBuilder sb = new StringBuilder("<entry>");
        appendIfPresent(sb, "serial", serial);
        appendIfPresent(sb, "hostname", hostname);
        appendIfPresent(sb, "type", type);
        appendIfPresent(sb, "ip-address", ipv4);
        appendIfPresent(sb, "ipv6-address", ipv6);
        if (peerSerial != null) {
            sb.append("<ha><peer><serial>").append(peerSerial).append("</serial></peer></ha>");
        }
        appendIfPresent(sb, "connected", connected);
        appendIfPresent(sb, "last-connect-time", lastConnectTime);
        appendIfPresent(sb, "certificate-status", certStatus);
        appendIfPresent(sb, "certificate-expiry", certExpiry);
        if (vsysEntries.length > 0) {
            sb.append("<vsys>").append(String.join("", vsysEntries)).append("</vsys>");
        }
        sb.append("</entry>");
        return sb.toString();
    }

    static String vsysEntry(String uniqid, String displayName, String policyOne, String policyTwo, String policyThree) {
        StringBuilder sb = new StringBuilder("<entry>");
        appendIfPresent(sb, "uniqid", uniqid);
        appendIfPresent(sb, "display-name", displayName);
        appendIfPresent(sb, "shared-policy-status", policyOne);
        appendIfPresent(sb, "shared-policy-md5sum", policyTwo);
        appendIfPresent(sb, "shared-policy-version", policyThree);
        sb.append("</entry>");
        return sb.toString();
    }

    /** AC-4: a response carrying a DOCTYPE with an external entity -- the parser must fail closed without resolving it. */
    static String xxeKeyGenerationResponse() {
        return "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE response [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<response status=\"success\"><result><key>&xxe;</key></result></response>";
    }

    private static void appendIfPresent(StringBuilder sb, String tag, String value) {
        if (value != null) {
            sb.append('<').append(tag).append('>').append(value).append("</").append(tag).append('>');
        }
    }
}
