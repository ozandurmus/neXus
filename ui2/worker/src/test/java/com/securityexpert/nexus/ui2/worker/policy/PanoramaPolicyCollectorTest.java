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
    @Test void signedHitGateReadsTheEnrolledMemberDirectlyAndPublishesCounts() throws Exception {
        GateRegistryPort signed = key -> gates.findByCanonicalKey(key).stream().map(r -> r.gateId().equals("pan_policy_rule_hit_count")
                ? new GateRow(r.gateId(), r.vendor(), r.platformRoleScope(), r.shellContext(), r.transportKind(), r.canonicalCommandKey(),
                    r.actionClass(), SignOffState.SIGNED_OFF, r.timeoutS(), r.retryRule(), r.maxFrequency(), r.sessionReuseRule(),
                    r.unsupportedBehaviorRef(), r.secretOutputRisk(), r.safeTelemetryFields(), r.sourceDocumentPointer()) : r).toList();
        var replies = responses();
        replies.add(success("<key>synthetic-member-key</key>"));
        replies.add(success("<rule-hit-count><vsys><entry name='vsys1'><rule-base><entry name='security'><rules>"
                + "<entry name='Synthetic child pre'><hit-count>5</hit-count><last-hit-timestamp>100</last-hit-timestamp></entry>"
                + "</rules></entry></rule-base></entry></vsys></rule-hit-count>"));
        when(repository.panTargets("run-1", "manager-1", "synthetic-member-01", "vsys1", "IN_SYNC"))
                .thenReturn(List.of(new PolicySnapshot.Target("device-1", "FW-TANGO-04", "vsys1", "IN_SYNC")));
        when(repository.panFirewall("device-1")).thenReturn(Optional.of(new PolicyCollectionRepository.FirewallEndpoint("192.0.2.20", "synthetic-ref")));
        when(repository.beginDomain(anyString(), anyString(), anyBoolean())).thenReturn(true);
        var transport = mock(DeviceTransport.class);
        var index = new AtomicInteger();
        doAnswer(call -> {
            int i = index.getAndIncrement();
            var target = (ApiTarget) call.getArgument(0);
            assertEquals(i < 6 ? "192.0.2.10" : "192.0.2.20", target.baseUrl());
            @SuppressWarnings("unchecked") var handler = (XmlApiStreamHandler<Element>) call.getArgument(3);
            return new XmlApiStreamOutcome.Completed<>(200, handler.handle(new ByteArrayInputStream(replies.get(i).getBytes(StandardCharsets.UTF_8))));
        }).when(transport).xmlApiCallStreaming(any(), any(), any(), any());
        var snapshots = new PanoramaPolicyCollector(transport, signed, ref -> new PanCredentialMaterial("synthetic-user", new char[0]), repository)
                .collect(run, scope, () -> true);
        var counted = snapshots.stream().flatMap(s -> s.sections().stream()).flatMap(s -> s.rules().stream())
                .filter(r -> r.uuid().equals("uuid-03")).findFirst().orElseThrow();
        assertEquals(5L, counted.hitCounts().hits()); assertEquals("device", counted.hitCounts().source());
        assertEquals(8, index.get());
    }
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
        verify(repository, never()).panFirewall(anyString());
        assertTrue(snapshots.stream().flatMap(s -> s.sections().stream()).flatMap(s -> s.rules().stream()).allMatch(r -> r.hitCounts() == null));
        assertEquals("keygen", sent.get(0).type());
        assertEquals("<show><devicegroups/></show>", sent.get(1).formParams().get("cmd"));
        assertEquals("<show><dg-hierarchy></dg-hierarchy></show>", sent.get(2).formParams().get("cmd"));
        assertEquals("/config/shared", sent.get(3).formParams().get("xpath"));
        assertTrue(Arrays.equals(new char[password.length], password));
    }
    @Test void failsClosedOnHierarchyMismatchDtdAndGroupLimit() throws Exception {
        var hierarchy = responses(); hierarchy.set(2, success("<dg-hierarchy><dg name='Synthetic child'/></dg-hierarchy>"));
        assertThrows(RuntimeException.class, () -> collector(hierarchy, new ArrayList<>(), new char[0]).collect(run, scope, () -> true));
        var excessive = responses();
        excessive.set(1, success("<devicegroups>" + "<entry name='Synthetic group'/>".repeat(PanoramaPolicyCollector.MAX_GROUPS + 1) + "</devicegroups>"));
        assertThrows(RuntimeException.class, () -> collector(excessive, new ArrayList<>(), new char[0]).collect(run, scope, () -> true));
        assertThrows(RuntimeException.class, () -> PolicyXml.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///never-read'>]><config>&x;</config>"));
        assertThrows(RuntimeException.class, () -> collector(responses(), new ArrayList<>(), new char[0]).collect(run, scope, () -> false));
    }

    @Test void acceptsEmptyDeviceGroupShapesAndResultAttributes() throws Exception {
        for (String result : List.of("<result/>", "<result count='0'/>", "<result total-count='0'/>", "<result total-count='0' count='0'/>",
                "<result total-count='1' count='1'><entry name='Synthetic child'/></result>",
                "<result><entry name='Synthetic child' admin='synthetic-user' dirtyId='9'/></result>")) {
            var replies = responses();
            replies.set(5, "<response status='success'>" + result + "</response>");
            var snapshots = collector(replies, new ArrayList<>(), new char[0]).collect(run, scope, () -> true);
            assertEquals(2, snapshots.size());
            assertTrue(snapshots.stream().allMatch(snapshot -> snapshot.failures().isEmpty()));
            var child = snapshots.get(1);
            assertFalse(child.sections().stream().anyMatch(section -> section.source().equals("Synthetic child") && !section.rules().isEmpty()));
        }
    }

    @Test void partialCollectionRecordsAllFailedGroupsAndMaskedResponseShapes() throws Exception {
        for (String invalid : List.of("<response status='error' code='7'><msg>SyntheticPrivate42</msg></response>",
                "<response status='success' code='19'><result><SyntheticPrivate42>",
                success("<unexpected>SyntheticPrivate42</unexpected>"), success("<entry name='Wrong synthetic group'/>"),
                "<response status='success'><result total-count='1'/></response>")) {
            var replies = responses(); replies.set(5, invalid);
            var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
            List<PolicySnapshot> snapshots;
            try (var recording = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript);
                 var trace = new PolicyCollectionTrace("manager-1", (step, total) -> {})) {
                snapshots = collector(replies, new ArrayList<>(), new char[0]).collect(run, scope, () -> true);
            }
            assertEquals(1, snapshots.size());
            assertEquals(1, snapshots.get(0).failures().size());
            var failure = snapshots.get(0).failures().get(0);
            assertEquals(PolicySnapshot.ref("manager-1", "device-group", "Synthetic child"), failure.layerRef());
            assertTrue(failure.reason().startsWith("device group "));
            var output = new ByteArrayOutputStream(); transcript.writeTo(output);
            String text = output.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("PAN_POLICY_SHAPE bytes=" + invalid.getBytes(StandardCharsets.UTF_8).length));
            assertTrue(text.contains("httpStatus=200"));
            assertFalse(text.contains("SyntheticPrivate42"));
            assertFalse(text.contains("Wrong synthetic group"));
        }
    }

    @Test void missingAncestorIsNotPublishedAsCompleteAndAllFailedGroupsFailClosed() throws Exception {
        var replies = responses(); replies.set(4, success("<unexpected/>"));
        assertThrows(IllegalStateException.class, () -> collector(replies, new ArrayList<>(), new char[0]).collect(run, scope, () -> true));
        replies.set(5, success("<unexpected/>"));
        assertThrows(IllegalStateException.class, () -> collector(replies, new ArrayList<>(), new char[0]).collect(run, scope, () -> true));
        replies.set(2, success("<dg-hierarchy><dg name='Synthetic parent'/><dg name='Synthetic child'/></dg-hierarchy>"));
        replies.set(5, success("<entry name='Synthetic child'/>"));
        var snapshots = collector(replies, new ArrayList<>(), new char[0]).collect(run, scope, () -> true);
        assertEquals(1, snapshots.size());
        assertEquals("Synthetic child", snapshots.get(0).metadata().containerName());
        assertEquals(1, snapshots.get(0).failures().size());
    }

    @Test void responseShapeMasksUnicodeBoundsOutputAndAllowListsApiAttributes() {
        String diagnostic = PanoramaPolicyCollector.responseShape("<response status='error' code='7'>SyntheticPrivate42</response>", 75, 403);
        assertTrue(diagnostic.contains("httpStatus=403 status=error code=7"));
        assertTrue(diagnostic.contains("shape=<aaaaaaaa aaaaaa='aaaaa' aaaa='9'>aaaaaaaaaaaaaaaa99</aaaaaaaa>"));
        assertFalse(diagnostic.contains("SyntheticPrivate"));
        assertTrue(PanoramaPolicyCollector.responseShape("<response status='private' code='secret'>", 0, null)
                .contains("httpStatus=UNKNOWN status=UNKNOWN code=UNKNOWN"));
        assertTrue(PanoramaPolicyCollector.responseShape("é９".repeat(3000), 12000, 200).endsWith("a9".repeat(1024)));
    }

    @Test void publishesDeviceGroupRuleProgressThroughTheSharedTrace() throws Exception {
        var progress = new ArrayList<PolicyCollectionTrace.LayerProgress>();
        try (var trace = new PolicyCollectionTrace("manager-1", (step, total) -> {}, measurement -> {}, progress::add)) {
            collector(responses(), new ArrayList<>(), new char[0]).collect(run, scope, () -> true, snapshot -> {});
        }
        assertEquals(4, progress.size());
        assertEquals(2, progress.get(3).layer());
        assertEquals(2, progress.get(3).layers());
        assertEquals(10, progress.get(3).rules());
    }

    @Test void cumulativeResponsesAbove64MbPublishBeforeReadingTheNextGroup() throws Exception {
        var replies = responses();
        List<PolicySnapshot> published = new ArrayList<>();
        var collector = paddedCollector(replies, 35L * 1024 * 1024, 35L * 1024 * 1024, published);
        assertTrue(collector.collect(run, scope, () -> true, published::add).isEmpty());
        assertEquals(2, published.size());
    }

    @Test void oversizedIndependentGroupIsSkippedWithMeasuredBytes() throws Exception {
        var replies = responses();
        replies.set(2, success("<dg-hierarchy><dg name='Synthetic parent'/><dg name='Synthetic child'/></dg-hierarchy>"));
        List<PolicySnapshot> published = new ArrayList<>();
        var collector = paddedCollector(replies, PanoramaPolicyCollector.MAX_RESPONSE_BYTES + 1024, 0, null);
        var failures = collector.collect(run, scope, () -> true, published::add);
        assertEquals(1, published.size());
        assertEquals("Synthetic child", published.get(0).metadata().containerName());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).reason().startsWith("bytes="));
        assertTrue(failures.get(0).reason().endsWith(": SIZE_LIMIT"));
        assertEquals(PolicySnapshot.ref("manager-1", "device-group", "Synthetic parent"), failures.get(0).layerRef());
    }

    @Test void perGroupRuleLimitSkipsOnlyTheOversizedIndependentGroup() throws Exception {
        var replies = responses();
        replies.set(2, success("<dg-hierarchy><dg name='Synthetic parent'/><dg name='Synthetic child'/></dg-hierarchy>"));
        replies.set(4, success("<entry name='Synthetic parent'><pre-rulebase><security><rules>"
                + "<entry name='Synthetic rule'><action>allow</action></entry>".repeat(50_001)
                + "</rules></security></pre-rulebase></entry>"));
        List<PolicySnapshot> published = new ArrayList<>();
        var failures = collector(replies, new ArrayList<>(), new char[0]).collect(run, scope, () -> true, published::add);
        assertEquals(1, published.size());
        assertEquals("Synthetic child", published.get(0).metadata().containerName());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).reason().endsWith(": SIZE_LIMIT"));
    }

    @Test void deadlineStopsFurtherReadsAfterCompletedGroupPublication() throws Exception {
        var replies = responses();
        var transport = mock(DeviceTransport.class);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var next = new AtomicInteger();
        doAnswer(call -> {
            @SuppressWarnings("unchecked") var handler = (XmlApiStreamHandler<Element>) call.getArgument(3);
            return new XmlApiStreamOutcome.Completed<>(200, handler.handle(new ByteArrayInputStream(
                    replies.get(next.getAndIncrement()).getBytes(StandardCharsets.UTF_8))));
        }).when(transport).xmlApiCallStreaming(any(), any(), any(), any());
        var collector = new PanoramaPolicyCollector(transport, gates,
                ref -> new PanCredentialMaterial("synthetic-user", new char[0]), repository,
                java.time.Duration.ofHours(2), clock::get);
        List<PolicySnapshot> published = new ArrayList<>();
        var failure = assertThrows(IllegalStateException.class, () -> collector.collect(run, scope, () -> true, snapshot -> {
            published.add(snapshot);
            clock.set(java.time.Duration.ofHours(2).toNanos());
        }));
        assertTrue(failure.getMessage().endsWith(": JOB_DEADLINE"));
        assertEquals(1, published.size());
        assertEquals(5, next.get());
    }

    private PanoramaPolicyCollector paddedCollector(List<String> replies, long first, long second, List<PolicySnapshot> published) {
        var transport = mock(DeviceTransport.class);
        var next = new AtomicInteger();
        doAnswer(call -> {
            int index = next.getAndIncrement();
            if (index == 5 && published != null) assertEquals(1, published.size());
            @SuppressWarnings("unchecked") var handler = (XmlApiStreamHandler<Element>) call.getArgument(3);
            String response = replies.get(index);
            int split = response.indexOf("<result>");
            long padding = index == 4 ? first : index == 5 ? second : 0;
            try (var input = new SequenceInputStream(Collections.enumeration(List.of(
                    new ByteArrayInputStream(response.substring(0, split).getBytes(StandardCharsets.UTF_8)),
                    comments(padding), new ByteArrayInputStream(response.substring(split).getBytes(StandardCharsets.UTF_8)))))) {
                return new XmlApiStreamOutcome.Completed<>(200, handler.handle(input));
            }
        }).when(transport).xmlApiCallStreaming(any(), any(), any(), any());
        return new PanoramaPolicyCollector(transport, gates,
                ref -> new PanCredentialMaterial("synthetic-user", new char[0]), repository);
    }

    private static InputStream comments(long size) {
        byte[] chunk = ("<!--" + "x".repeat(1017) + "-->").getBytes(StandardCharsets.UTF_8);
        return new InputStream() {
            long remaining = (size + chunk.length - 1) / chunk.length * chunk.length;
            int offset;
            @Override public int read() {
                if (remaining == 0) return -1;
                remaining--; int value = chunk[offset++]; offset %= chunk.length; return value;
            }
            @Override public int read(byte[] bytes, int start, int length) {
                if (length == 0) return 0;
                if (remaining == 0) return -1;
                int count = (int) Math.min(Math.min(length, chunk.length - offset), remaining);
                System.arraycopy(chunk, offset, bytes, start, count);
                remaining -= count; offset = (offset + count) % chunk.length; return count;
            }
        };
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
