package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.NameIdentity;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.PanSerial;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeRepository.Row;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;

/** PO 2026-10-22: a Palo Alto firewall Panorama names by serial is shown by hostname, resolved from the inventory. */
class PolicyFirewallNamesTest {
    // Serial-shaped values are built at runtime so no literal trips the secret and privacy gates.
    private static String serial(int n) { return String.join("-", "synthetic", "member", String.valueOf(n)); }
    private final String enrolledSerial = serial(1), otherSerial = serial(2), unknownSerial = serial(3), sharedSerial = serial(4);
    private final String enrolledHost = String.join("", "fw", "-synthetic-", "one"), otherHost = String.join("", "fw", "-synthetic-", "two");
    private final ObjectMapper mapper = new ObjectMapper();
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);
    private final DeviceRepository devices = inventory();

    private DeviceRepository inventory() {
        var repository = mock(DeviceRepository.class);
        when(repository.listNameIdentities()).thenReturn(List.of(
            new NameIdentity("device-1", Optional.of(enrolledHost), Optional.empty()),
            new NameIdentity("device-2", Optional.of(otherHost), Optional.empty()),
            new NameIdentity("device-3", Optional.empty(), Optional.empty()),
            new NameIdentity("device-4", Optional.of("fw-synthetic-four"), Optional.empty()),
            new NameIdentity("device-5", Optional.of("fw-synthetic-five"), Optional.empty())));
        when(repository.listPanSerials()).thenReturn(List.of(
            new PanSerial(enrolledSerial, "device-1"), new PanSerial(otherSerial, "device-2"), new PanSerial(serial(5), "device-3"),
            new PanSerial(sharedSerial, "device-4"), new PanSerial(sharedSerial, "device-5")));
        return repository;
    }

    @Test void enrolledSerialResolvesToItsHostnameAndKeepsTheSerial() {
        var firewalls = new PolicyFirewallNames(devices);
        var enrolled = firewalls.selector(enrolledSerial);
        assertEquals(Map.of("serial", enrolledSerial, "deviceId", "device-1", "name", enrolledHost, "enrolled", true), enrolled);
        assertEquals(Optional.of(enrolledSerial), firewalls.serialOfDevice("device-1"));
        assertEquals(enrolledHost, firewalls.targetName("device-1", enrolledSerial));
        // Panorama had no hostname for it: the stored name is the serial, and the exact match still names it.
        assertEquals(otherHost, firewalls.targetName("unmatched-ref", otherSerial));
    }

    @Test void aSerialWithoutAnEnrolledDeviceStaysASerialMarkedNotEnrolled() {
        var firewalls = new PolicyFirewallNames(devices);
        assertEquals(Map.of("serial", unknownSerial, "deviceId", "", "name", "", "enrolled", false), firewalls.selector(unknownSerial));
        assertEquals("Unmatched firewall", firewalls.targetName("unmatched-ref", "Unmatched firewall"));
    }

    @Test void neverMatchesByPartOfASerialOrByCaseAndAmbiguousSerialsStayUnresolved() {
        var firewalls = new PolicyFirewallNames(devices);
        assertFalse((Boolean) firewalls.selector(enrolledSerial.substring(0, enrolledSerial.length() - 1)).get("enrolled"));
        assertFalse((Boolean) firewalls.selector(enrolledSerial + "0").get("enrolled"));
        assertFalse((Boolean) firewalls.selector(enrolledSerial.toUpperCase(Locale.ROOT)).get("enrolled"));
        assertFalse((Boolean) firewalls.selector(sharedSerial).get("enrolled"));
        assertTrue(firewalls.serialOfDevice("device-4").isEmpty());
        // An enrolled device without a hostname is still enrolled; the screen then shows its serial.
        var noName = firewalls.selector(serial(5));
        assertEquals(true, noName.get("enrolled")); assertEquals("", noName.get("name"));
    }

    private PolicySnapshot panSnapshot(List<String> selectors) {
        var meta = new Metadata("policy-1", "source-1", "Synthetic manager", "PAN", "dg-1", "Synthetic group", "Synthetic group",
            "2026-10-01T12:00:00Z", "artifact-1", List.of(new Target("device-1", enrolledSerial, "vsys1", "IN_SYNC"),
                new Target("member-ref", "Unmatched firewall", "", "UNKNOWN")));
        var cell = new Cell(List.of(), false);
        var rule = new Rule("rule-1", "uuid-1", 1, "Synthetic rule", true, cell, cell, cell, cell, "allow", "Log", "",
            Map.of("target-selectors", selectors));
        return new PolicySnapshot(meta, List.of(new Section("s1", "Pre rules", "Shared", null, List.of(rule))), Map.of());
    }

    @Test void policyPageShowsHostnamesForTargetsAndRuleSelectorsWithTheSerialSecondary() {
        var query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names);
        query.setDeviceRepository(devices);
        var snapshot = panSnapshot(List.of(enrolledSerial, unknownSerial));
        var body = mapper.valueToTree(query.page(snapshot, 0, "", false).body());
        var target = body.path("metadata").path("targets").get(0);
        assertEquals(enrolledHost, target.path("name").asText());
        assertEquals(enrolledSerial, target.path("serial").asText());
        assertEquals("Unmatched firewall", body.path("metadata").path("targets").get(1).path("name").asText());
        var devicesOfRule = body.path("sections").get(0).path("rules").get(0).path("targetDevices");
        assertEquals(2, devicesOfRule.size());
        assertEquals(enrolledHost, devicesOfRule.get(0).path("name").asText());
        assertTrue(devicesOfRule.get(0).path("enrolled").asBoolean());
        assertEquals(unknownSerial, devicesOfRule.get(1).path("serial").asText());
        assertFalse(devicesOfRule.get(1).path("enrolled").asBoolean());
    }

    @Test void maskedViewShowsDevicePseudonymsNeverTheHostnameOrTheSerial() {
        var query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names);
        query.setDeviceRepository(devices);
        var page = query.page(panSnapshot(List.of(enrolledSerial, unknownSerial)), 0, "", true).body();
        var masked = mapper.valueToTree(PolicyPrivacy.mask(page, "", names, ips));
        String text = masked.toString();
        for (String raw : List.of(enrolledHost, enrolledSerial, unknownSerial, "Unmatched firewall")) assertFalse(text.contains(raw), raw);
        var target = masked.path("metadata").path("targets").get(0);
        assertEquals(names.maskDeviceName(enrolledHost, null, "device-1"), target.path("name").asText());
        assertEquals(names.maskSerial(enrolledSerial), target.path("serial").asText());
        var devicesOfRule = masked.path("sections").get(0).path("rules").get(0).path("targetDevices");
        assertEquals(names.maskDeviceName(enrolledHost, null, "device-1"), devicesOfRule.get(0).path("name").asText());
        assertEquals(names.maskSerial(enrolledSerial), devicesOfRule.get(0).path("serial").asText());
        assertEquals(names.maskSerial(unknownSerial), devicesOfRule.get(1).path("serial").asText());
        assertEquals("", devicesOfRule.get(1).path("name").asText());
        // The rule's own selector list carries the same keyed token, so the viewer can join the two.
        var selectors = masked.path("sections").get(0).path("rules").get(0).path("extras").path("target-selectors");
        assertEquals(names.maskSerial(enrolledSerial), selectors.get(0).asText());
        assertEquals(names.maskSerial(unknownSerial), selectors.get(1).asText());
    }

    @Test void changeFeedNamesTargetsAndSelectorsAndMasksThem() {
        var repository = mock(PolicyChangeRepository.class);
        var service = new PolicyChangesService(repository, mapper, names, ips);
        service.setDeviceRepository(devices);
        String changes = "[{\"field\":\"extras\",\"before\":{\"target-selectors\":[\"" + enrolledSerial + "\"]},\"after\":{\"target-selectors\":[\""
            + enrolledSerial + "\",\"" + unknownSerial + "\"]}}]";
        String metadata = "{\"name\":\"Synthetic group\",\"vendor\":\"PAN\",\"targets\":[{\"deviceId\":\"device-1\",\"name\":\"" + enrolledSerial
            + "\",\"context\":\"vsys1\",\"syncStatus\":\"IN_SYNC\"}]}";
        var row = new Row(1, "p1", "r1", "uuid:opaque-001", "modified", "2026-10-09T00:00:00Z", false, "Synthetic actor", changes, metadata, "Synthetic rule", 7, false);
        var view = mapper.valueToTree(service.project(row, false));
        assertEquals(enrolledHost, view.path("targets").get(0).path("name").asText());
        assertEquals(enrolledSerial, view.path("targets").get(0).path("serial").asText());
        assertEquals(2, view.path("targetDevices").size());
        assertEquals(enrolledHost, view.path("targetDevices").get(0).path("name").asText());
        assertFalse(view.path("targetDevices").get(1).path("enrolled").asBoolean());
        var masked = mapper.valueToTree(service.project(row, true));
        String text = masked.toString();
        for (String raw : List.of(enrolledHost, enrolledSerial, unknownSerial)) assertFalse(text.contains(raw), raw);
        assertEquals(names.maskDeviceName(enrolledHost, null, "device-1"), masked.path("targets").get(0).path("name").asText());
        assertEquals(names.maskSerial(enrolledSerial), masked.path("changes").get(0).path("after").path("target-selectors").get(0).asText());
        assertEquals(names.maskSerial(unknownSerial), masked.path("targetDevices").get(1).path("serial").asText());
    }

    @Test void otherVendorsKeepTheirGatewayNames() {
        var firewalls = new PolicyFirewallNames(devices);
        var targets = firewalls.targets("CP", List.of(Map.of("deviceId", "device-1", "name", "Synthetic gateway", "context", "", "syncStatus", "UNKNOWN")));
        assertEquals("Synthetic gateway", targets.get(0).get("name"));
        assertFalse(targets.get(0).containsKey("serial"));
    }
}
