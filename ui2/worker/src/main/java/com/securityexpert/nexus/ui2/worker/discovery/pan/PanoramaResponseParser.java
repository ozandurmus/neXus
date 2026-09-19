package com.securityexpert.nexus.ui2.worker.discovery.pan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.securityexpert.nexus.ui2.discovery.pan.PanoramaApiFieldBinding;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaApiFieldBinding.Role;
import com.securityexpert.nexus.ui2.discovery.pan.RawDeviceInput;
import com.securityexpert.nexus.ui2.discovery.pan.RawVirtualSystemInput;
import com.securityexpert.nexus.ui2.discovery.pan.Serial;

/**
 * FB-2: every field read through {@link PanoramaApiFieldBinding} -- no
 * element-name literal anywhere in this class. T-7: the parsed {@link
 * Document} and every intermediate string live only inside one call's stack
 * on the way to a {@link RawDeviceInput}/{@code char[]} return value; none
 * of it is retained by this class. Sections 8-10 (DI-3 to DI-6): every
 * {@code <entry>} the container path finds becomes a {@link RawDeviceInput},
 * regardless of whether the serial or connection-state role is present.
 */
final class PanoramaResponseParser {

    private PanoramaResponseParser() {
    }

    /** T-1: the key-generation response's key element -- discarded by the caller once copied into a char[]. */
    static Optional<String> extractKey(String responseBody) {
        Document doc = PanoramaXmlSupport.parse(responseBody);
        String path = fieldPath(Role.KEY_GENERATION_RESPONSE_KEY);
        return PanoramaXmlSupport.firstText(doc, path);
    }

    /** T-2/DI-3 to DI-6: every device entry the container path finds, filtered by nothing. */
    static List<RawDeviceInput> extractDevices(String responseBody) {
        Document doc = PanoramaXmlSupport.parse(responseBody);
        List<Element> entries = PanoramaXmlSupport.select(doc, fieldPath(Role.DEVICE_ENTRY_CONTAINER));
        List<RawDeviceInput> devices = new ArrayList<>(entries.size());
        for (Element entry : entries) {
            devices.add(parseDevice(entry));
        }
        return List.copyOf(devices);
    }

    private static RawDeviceInput parseDevice(Element entry) {
        Serial stableIdentifier = Serial.ofOptional(serialOf(entry));
        String displayName = relativeText(entry, Role.DISPLAY_NAME).orElse("");
        if (displayName.isBlank()) {
            displayName = stableIdentifier.value().orElse("");
        }
        String deviceTypeMarker = relativeText(entry, Role.DEVICE_TYPE_MARKER).orElse("");
        Optional<String> ownIpv4 = relativeText(entry, Role.OWN_IPV4_ADDRESS);
        Optional<String> ownIpv6 = relativeText(entry, Role.OWN_IPV6_ADDRESS);
        Serial peerSerial = Serial.ofOptional(relativeText(entry, Role.PEER_SERIAL_REFERENCE));
        Optional<String> connectionState = relativeText(entry, Role.CONNECTION_STATE);
        Optional<String> connectionTimestamp = relativeText(entry, Role.CONNECTION_TIMESTAMP);
        Optional<String> certificateStatus = relativeText(entry, Role.CERTIFICATE_STATUS);
        Optional<String> certificateExpiration = relativeText(entry, Role.CERTIFICATE_EXPIRATION);
        List<RawVirtualSystemInput> virtualSystems = parseVirtualSystems(entry);
        return new RawDeviceInput(stableIdentifier, displayName, deviceTypeMarker, ownIpv4, ownIpv6, peerSerial,
                connectionState, connectionTimestamp, certificateStatus, certificateExpiration, virtualSystems);
    }

    private static Optional<String> serialOf(Element entry) {
        Optional<String> text = relativeText(entry, Role.STABLE_IDENTIFIER);
        if (text.isPresent() && !text.get().isBlank()) {
            return text;
        }
        String nameAttr = entry.getAttribute("name");
        if (nameAttr != null && !nameAttr.isBlank()) {
            return Optional.of(nameAttr.trim());
        }
        return Optional.empty();
    }

    private static List<RawVirtualSystemInput> parseVirtualSystems(Element deviceEntry) {
        String containerPath = fieldPath(Role.VIRTUAL_SYSTEM_ENTRY_CONTAINER);
        List<Element> vsEntries = PanoramaXmlSupport.selectRelative(deviceEntry, containerPath);
        List<RawVirtualSystemInput> result = new ArrayList<>(vsEntries.size());
        for (Element vsEntry : vsEntries) {
            Serial stableIdentifier = Serial.ofOptional(vsSerialOf(vsEntry));
            String displayName = vsDisplayNameOf(vsEntry);
            Optional<String> policyOne = vsRelativeText(vsEntry, Role.VIRTUAL_SYSTEM_SHARED_POLICY_ONE);
            Optional<String> policyTwo = vsRelativeText(vsEntry, Role.VIRTUAL_SYSTEM_SHARED_POLICY_TWO);
            Optional<String> policyThree = vsRelativeText(vsEntry, Role.VIRTUAL_SYSTEM_SHARED_POLICY_THREE);
            result.add(new RawVirtualSystemInput(stableIdentifier, displayName, policyOne, policyTwo, policyThree));
        }
        return List.copyOf(result);
    }

    private static Optional<String> vsSerialOf(Element vsEntry) {
        Optional<String> text = vsRelativeText(vsEntry, Role.VIRTUAL_SYSTEM_STABLE_IDENTIFIER);
        if (text.isPresent() && !text.get().isBlank()) {
            return text;
        }
        String nameAttr = vsEntry.getAttribute("name");
        if (nameAttr != null && !nameAttr.isBlank()) {
            return Optional.of(nameAttr.trim());
        }
        return Optional.empty();
    }

    private static String vsDisplayNameOf(Element vsEntry) {
        Optional<String> text = vsRelativeText(vsEntry, Role.VIRTUAL_SYSTEM_DISPLAY_NAME);
        if (text.isPresent() && !text.get().isBlank()) {
            return text.get();
        }
        String nameAttr = vsEntry.getAttribute("name");
        if (nameAttr != null && !nameAttr.isBlank()) {
            return nameAttr.trim();
        }
        return "";
    }

    private static Optional<String> relativeText(Element entry, Role role) {
        return PanoramaXmlSupport.firstRelativeText(entry, fieldPath(role));
    }

    /** VS field paths are recorded device-entry-relative (they share the container's own prefix); trimmed to vsys-entry-relative here. */
    private static Optional<String> vsRelativeText(Element vsEntry, Role role) {
        String full = fieldPath(role);
        String containerPrefix = fieldPath(Role.VIRTUAL_SYSTEM_ENTRY_CONTAINER) + "/";
        String relative = full.startsWith(containerPrefix) ? full.substring(containerPrefix.length()) : full;
        return PanoramaXmlSupport.firstRelativeText(vsEntry, relative);
    }

    private static String fieldPath(Role role) {
        return PanoramaApiFieldBinding.forRole(role).apiFieldPath().orElseThrow(PanoramaQueryFailedException::new);
    }
}
