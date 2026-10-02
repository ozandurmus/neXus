package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.policy.PanPolicyGates;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.*;
import com.securityexpert.nexus.ui2.policy.*;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.*;

class PanoramaPolicyCollectorTest {
    private final GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(getClass()
            .getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream().filter(row -> row.key().equals(key)).toList();
    private final DiscoveryRun run = new DiscoveryRun("run-1", "palo_alto", "192.0.2.10", "synthetic-ref", "synthetic-actor",
            DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final PolicyCollectionRepository.Request scope = new PolicyCollectionRepository.Request("manager-1", "", false);
    private final PolicyCollectionRepository repository = mock(PolicyCollectionRepository.class);

    @Test void requestBuildersPreserveExactApprovedShapesAndEscapeXPathLiteralsOnce() throws Exception {
        PanPolicyGates.requireAll(gates);
        for (int index = 0; index < 4; index++) {
            var spec = PanoramaPolicyCollector.request(index, "<DG>", "synthetic-key".toCharArray());
            var form = spec.formParams();
            String canonical = "type=" + spec.type() + (index < 2 ? "&action=" + form.get("action") + "&xpath=" + form.get("xpath") : "&cmd=" + form.get("cmd"));
            assertEquals(PanPolicyGates.COMMANDS.get(index), canonical);
            assertEquals(Set.of("X-PAN-KEY"), spec.headers().keySet());
            assertFalse(form.containsKey("target"));
        }
        String name = "Synthetic '&\"<group>";
        var document = PolicyXml.parse("<config><devices><entry name='localhost.localdomain'><device-group>"
                + "<entry name='Synthetic &apos;&amp;&quot;&lt;group&gt;'/></device-group></entry></devices></config>");
        String xpath = PanoramaPolicyCollector.request(1, name, new char[0]).formParams().get("xpath");
        assertEquals(name, XPathFactory.newInstance().newXPath().evaluate(xpath + "/@name", document));
        assertThrows(IllegalStateException.class, () -> PanoramaPolicyCollector.request(1, "", new char[0]));
        assertThrows(IllegalStateException.class, () -> PanPolicyGates.requireAll(key -> List.of()));
    }

    @Test void collectsSharedParentChildPrePostDynamicObjectsAndExactMembersSerially() throws Exception {
        List<XmlApiSpec> sent = new ArrayList<>();
        char[] password = "synthetic-password".toCharArray();
        var collector = collector(responses(), sent, password);
        when(repository.panTargets("run-1", "manager-1", "synthetic-member-01", "vsys1", "IN_SYNC"))
                .thenReturn(List.of(new PolicySnapshot.Target("device-1", "FW-TANGO-04", "vsys1", "IN_SYNC")));
        var snapshots = collector.collect(run, scope, () -> true);
        assertEquals(2, snapshots.size());
        var child = snapshots.stream().filter(s -> s.metadata().containerName().equals("Synthetic child")).findFirst().orElseThrow();
        assertEquals(List.of("uuid-01", "uuid-02", "uuid-03", "uuid-04", "uuid-05", "uuid-06"), child.sections().stream()
                .flatMap(s -> s.rules().stream()).map(PolicySnapshot.Rule::uuid).toList());
        assertTrue(child.objects().values().stream().anyMatch(o -> o.status().equals("DYNAMIC")));
        assertTrue(child.objects().values().stream().anyMatch(o -> o.name().equals("Synthetic address") && o.status().equals("UNKNOWN") && o.values().isEmpty()));
        assertEquals("device-1", child.metadata().targets().get(0).deviceId());
        assertEquals("IN_SYNC", child.metadata().targets().get(0).syncStatus());
        assertEquals(6, sent.size());
        assertEquals("keygen", sent.get(0).type());
        assertEquals("<show><devicegroups/></show>", sent.get(1).formParams().get("cmd"));
        assertEquals("<show><dg-hierarchy></dg-hierarchy></show>", sent.get(2).formParams().get("cmd"));
        assertEquals("/config/shared", sent.get(3).formParams().get("xpath"));
        assertTrue(Arrays.equals(new char[password.length], password));
    }
    @Test void failsClosedOnPartialResponseHierarchyMismatchDtdAndGroupLimit() throws Exception {
        var partial = responses(); partial.set(5, "<response status='error'><msg>synthetic failure</msg></response>");
        assertThrows(RuntimeException.class, () -> collector(partial, new ArrayList<>(), new char[0]).collect(run, scope, () -> true));
        var hierarchy = responses(); hierarchy.set(2, success("<dg-hierarchy><dg name='Synthetic child'/></dg-hierarchy>"));
        assertThrows(RuntimeException.class, () -> collector(hierarchy, new ArrayList<>(), new char[0]).collect(run, scope, () -> true));
        var excessive = responses();
        excessive.set(1, success("<devicegroups>" + "<entry name='Synthetic group'/>".repeat(PanoramaPolicyCollector.MAX_GROUPS + 1) + "</devicegroups>"));
        assertThrows(RuntimeException.class, () -> collector(excessive, new ArrayList<>(), new char[0]).collect(run, scope, () -> true));
        assertThrows(RuntimeException.class, () -> PolicyXml.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///never-read'>]><config>&x;</config>"));
        assertThrows(RuntimeException.class, () -> collector(responses(), new ArrayList<>(), new char[0]).collect(run, scope, () -> false));
    }

    @SuppressWarnings("unchecked")
    private PanoramaPolicyCollector collector(List<String> responses, List<XmlApiSpec> sent, char[] password) {
        var transport = mock(DeviceTransport.class);
        AtomicInteger index = new AtomicInteger();
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(1));
            assertEquals(java.time.Duration.ofSeconds(60), invocation.getArgument(2));
            var handler = (XmlApiStreamHandler<Element>) invocation.getArgument(3);
            try (var input = new ByteArrayInputStream(responses.get(index.getAndIncrement()).getBytes(StandardCharsets.UTF_8))) {
                return new XmlApiStreamOutcome.Completed<>(200, handler.handle(input));
            }
        }).when(transport).xmlApiCallStreaming(any(), any(), any(), any());
        return new PanoramaPolicyCollector(transport, gates, ref -> new PanCredentialMaterial("synthetic-user", password), repository);
    }
    private List<String> responses() throws Exception {
        String fixture;
        try (var input = getClass().getResourceAsStream("/fixtures/policy/panorama.xml")) {
            fixture = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        Element config = PolicyXml.parse(fixture).getDocumentElement();
        return new ArrayList<>(List.of(success("<key>synthetic-key</key>"),
                success("<devicegroups><entry name='Synthetic parent'/><entry name='Synthetic child'><devices>"
                    + "<entry name='synthetic-member-01'><vsys><entry name='vsys1'><shared-policy-status>In Sync</shared-policy-status>"
                    + "</entry></vsys></entry></devices></entry></devicegroups>"),
                success("<dg-hierarchy><dg name='Synthetic parent'><dg name='Synthetic child'/></dg></dg-hierarchy>"),
                success(xml(PolicyXml.selectRelative(config, "shared").get(0))),
                success(xml(PolicyXml.selectRelative(config, "devices/entry/device-group/entry").get(0))),
                success(xml(PolicyXml.selectRelative(config, "devices/entry/device-group/entry").get(1)))));
    }
    private static String success(String result) { return "<response status='success'><result>" + result + "</result></response>"; }
    private static String xml(Element element) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        var output = new StringWriter(); transformer.transform(new DOMSource(element), new StreamResult(output)); return output.toString();
    }
    @Test void activeTranscriptDoesNotRejectPanoramaAndRetainsOnlyDerivedMeasurements() throws Exception {
        var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
        var sent = new ArrayList<XmlApiSpec>();
        try (var recording = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript);
             var trace = new PolicyCollectionTrace("manager-1", (step, total) -> {})) {
            assertEquals(2, collector(responses(), sent, new char[0]).collect(run, scope, () -> true).size());
        }
        var bytes = new java.io.ByteArrayOutputStream(); transcript.writeTo(bytes);
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(6, sent.size());
        assertTrue(text.contains("show devicegroups"));
        assertTrue(text.contains("bytes="));
        assertTrue(text.contains("durationMs="));
        assertFalse(text.contains("synthetic-key"));
        assertFalse(text.contains("Synthetic child"));
        assertFalse(text.contains("192.0.2.10"));
    }
    @Test void httpFailureNamesStepAndOpaqueTargetWithoutVendorErrorText() throws Exception {
        var transport = mock(DeviceTransport.class);
        var responses = responses();
        AtomicInteger index = new AtomicInteger();
        doAnswer(call -> {
            @SuppressWarnings("unchecked") var handler = (XmlApiStreamHandler<Element>) call.getArgument(3);
            int i = index.getAndIncrement();
            return i == 0 ? new XmlApiStreamOutcome.Completed<>(200, handler.handle(new ByteArrayInputStream(responses.get(0).getBytes(StandardCharsets.UTF_8))))
                : new XmlApiStreamOutcome.Completed<Element>(403, null);
        }).when(transport).xmlApiCallStreaming(any(), any(), any(), any());
        try (var trace = new PolicyCollectionTrace("manager-1", (step, total) -> {})) {
            var error = assertThrows(IllegalStateException.class, () -> new PanoramaPolicyCollector(transport, gates,
                ref -> new PanCredentialMaterial("synthetic-user", new char[0]), repository).collect(run, scope, () -> true));
            assertTrue(error.getMessage().contains("show devicegroups"));
            assertTrue(error.getMessage().contains("target=manager-1"));
            assertTrue(error.getMessage().endsWith("HTTP_403"));
        }
        verify(transport, times(2)).xmlApiCallStreaming(any(), any(), any(), any());
    }
    @Test void missingCredentialsFailAtNamedPreflightBeforeAnyApiContact() {
        var transport = mock(DeviceTransport.class);
        try (var trace = new PolicyCollectionTrace("manager-1", (step, total) -> {})) {
            var error = assertThrows(IllegalStateException.class, () -> new PanoramaPolicyCollector(transport, gates,
                ref -> { throw new IllegalStateException("private synthetic detail"); }, repository).collect(run, scope, () -> true));
            assertTrue(error.getMessage().contains("credential resolution target=manager-1"));
            assertTrue(error.getMessage().endsWith("CREDENTIAL_UNRESOLVABLE"));
            assertFalse(error.getMessage().contains("private synthetic detail"));
        }
        verifyNoInteractions(transport);
    }

    @Test void normalizedManagementUrlUsesExistingTransportUriResolution() throws Exception {
        var transport = mock(DeviceTransport.class);
        AtomicInteger index = new AtomicInteger(); var responses = responses();
        doAnswer(call -> {
            assertEquals("https://192.0.2.10:443", ((ApiTarget) call.getArgument(0)).baseUrl());
            @SuppressWarnings("unchecked") var handler = (XmlApiStreamHandler<Element>) call.getArgument(3);
            return new XmlApiStreamOutcome.Completed<>(200, handler.handle(new ByteArrayInputStream(responses.get(index.getAndIncrement()).getBytes(StandardCharsets.UTF_8))));
        }).when(transport).xmlApiCallStreaming(any(), any(), any(), any());
        var normalized = new DiscoveryRun("run-1", "palo_alto", "https://192.0.2.10:443", "synthetic-ref", "synthetic-actor",
            DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(2, new PanoramaPolicyCollector(transport, gates,
            ref -> new PanCredentialMaterial("synthetic-user", new char[0]), repository).collect(normalized, scope, () -> true).size());
    }

}
