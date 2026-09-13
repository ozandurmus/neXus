package com.securityexpert.nexus.ui2.worker.discovery.pan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

/**
 * T-2/T-3/T-5: the closed, two-route set this transport may ever issue,
 * declared once. Both routes are documented, with all ten network-device
 * command-gate items, in {@code
 * docs/design/PAN_DISCOVERY_API_ROUTE_GATE_ENTRIES.md} (DRAFT, pending
 * Product Owner gate approval). {@link PanoramaEnumerationAdapter} builds
 * every {@link XmlApiSpec} it ever sends through this class and nowhere
 * else; {@code isMemberOfClosedSet} asserts every request a fixture run
 * ever produces is one of the two (§11 check 16).
 *
 * <p>The enumeration {@code cmd} string below is transcribed byte-for-byte
 * from {@code PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md}
 * §2 -- an UNVERIFIED candidate exactly like every {@code
 * PanoramaApiFieldBinding} entry, carried verbatim rather than corrected,
 * because correcting it would be inventing a measurement this movement does
 * not have.</p>
 */
public final class PanoramaApiRoutes {

    /** T-1: the header the session key travels on -- never a query parameter. */
    public static final String KEY_HEADER_NAME = "X-PAN-KEY";

    private static final String KEYGEN_TYPE = "keygen";
    private static final String ENUMERATION_TYPE = "op";
    private static final String ENUMERATION_CMD = "<show><devices><all></devices></show>";
    private static final String NOT_APPLICABLE = "not_applicable";
    private static final String NO_TARGET_SCOPE = "no_target";

    private PanoramaApiRoutes() {
    }

    /** T-1: username and password in the POST body, form-encoded -- never the URL, never a header. */
    public static XmlApiSpec keyGeneration(String username, char[] password) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("type", KEYGEN_TYPE);
        form.put("user", username);
        form.put("password", new String(password));
        return new XmlApiSpec("POST", KEYGEN_TYPE, NOT_APPLICABLE, NO_TARGET_SCOPE, Map.copyOf(form), Map.of());
    }

    /** T-2: the one authorized read-only managed-device enumeration; the key travels in a header (T-1). */
    public static XmlApiSpec managedDeviceEnumeration(char[] key) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("type", ENUMERATION_TYPE);
        form.put("cmd", ENUMERATION_CMD);
        Map<String, String> headers = Map.of(KEY_HEADER_NAME, new String(key));
        return new XmlApiSpec("POST", ENUMERATION_TYPE, NOT_APPLICABLE, NO_TARGET_SCOPE, Map.copyOf(form), headers);
    }

    /** §11 check 16 / T-5: every request the adapter can ever send is one of exactly these two shapes, never a {@code target}. */
    public static boolean isMemberOfClosedSet(XmlApiSpec spec) {
        if (spec.formParams().containsKey("target") || spec.headers().containsKey("target")) {
            return false;
        }
        if (KEYGEN_TYPE.equals(spec.type())) {
            return spec.formParams().keySet().equals(Set.of("type", "user", "password"))
                    && !spec.headers().containsKey(KEY_HEADER_NAME);
        }
        if (ENUMERATION_TYPE.equals(spec.type())) {
            return spec.formParams().keySet().equals(Set.of("type", "cmd"))
                    && ENUMERATION_CMD.equals(spec.formParams().get("cmd"))
                    && spec.headers().containsKey(KEY_HEADER_NAME);
        }
        return false;
    }
}
