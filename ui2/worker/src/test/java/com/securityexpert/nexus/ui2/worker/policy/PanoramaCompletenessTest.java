package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.policy.PolicyCompletenessGates;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.*;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.policy.*;
import com.securityexpert.nexus.ui2.worker.diagnostic.PanoramaConfigLogReader;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.*;

/**
 * The Palo Alto side of the policy completeness work: what the collector observes per device group (the revision marker),
 * the one-call P-1 measurement on the one trial firewall, and the P-2/P-3/P-4 hook for the one trial Panorama. Every reader
 * is off unless its setting names its target. Synthetic answers only: no socket, host or device contact.
 */
class PanoramaCompletenessTest {
    private static final Instant OPEN = Instant.parse("2026-10-18T03:05:00Z");   // 06:05 Europe/Istanbul
    private static final Instant CLOSED = Instant.parse("2026-10-18T06:30:00Z"); // 09:30 Europe/Istanbul
    private static final String NAT_REPLY = "<rule-hit-count><vsys><entry name='vsys1'><rule-base><entry name='nat'><rules>"
        + "<entry name='Synthetic NAT rule one'><hit-count>5</hit-count><last-hit-timestamp>100</last-hit-timestamp></entry>"
        + "<entry name='Synthetic NAT rule two'><hit-count>0</hit-count></entry>"
        + "</rules></entry></rule-base></entry></vsys></rule-hit-count>";

    // The fixture signs the security hit-count read off (V155); pin it to DRAFTED so only P-1 can add a firewall read.
    private final GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(getClass()
            .getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream().filter(row -> row.key().equals(key))
            .map(r -> r.gateId().equals("pan_policy_rule_hit_count")
                ? new GateRow(r.gateId(), r.vendor(), r.platformRoleScope(), r.shellContext(), r.transportKind(), r.canonicalCommandKey(),
                    r.actionClass(), SignOffState.DRAFTED, r.timeoutS(), r.retryRule(), r.maxFrequency(), r.sessionReuseRule(),
                    r.unsupportedBehaviorRef(), r.secretOutputRisk(), r.safeTelemetryFields(), r.sourceDocumentPointer()) : r).toList();
    private final DiscoveryRun run = new DiscoveryRun("run-1", "palo_alto", "192.0.2.10", "synthetic-ref", "synthetic-actor",
            DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final PolicyCollectionRepository.Request scope = new PolicyCollectionRepository.Request("manager-1", "", false);
    private final PolicyCollectionRepository repository = mock(PolicyCollectionRepository.class);
    private final DeviceTransport transport = mock(DeviceTransport.class);
    private final List<XmlApiSpec> specs = new ArrayList<>();
    private final List<ApiTarget> targets = new ArrayList<>();
    private List<String> replies;
    private final AtomicInteger index = new AtomicInteger();

    @BeforeEach void setUp() throws Exception {
        replies = responses();
        when(repository.panTargets("run-1", "manager-1", "synthetic-member-01", "vsys1", "IN_SYNC"))
                .thenReturn(List.of(new PolicySnapshot.Target("device-1", "FW-TANGO-04", "vsys1", "IN_SYNC")));
        when(repository.panFirewall("device-1")).thenReturn(Optional.of(new PolicyCollectionRepository.FirewallEndpoint("192.0.2.20", "synthetic-ref")));
        when(repository.beginDomain(anyString(), anyString(), anyBoolean())).thenReturn(true);
        doAnswer(call -> {
            targets.add(call.getArgument(0));
            specs.add(call.getArgument(1));
            int i = index.getAndIncrement();
            @SuppressWarnings("unchecked") var handler = (XmlApiStreamHandler<Element>) call.getArgument(3);
            return new XmlApiStreamOutcome.Completed<>(200, handler.handle(new ByteArrayInputStream(replies.get(i).getBytes(StandardCharsets.UTF_8))));
        }).when(transport).xmlApiCallStreaming(any(), any(), any(), any());
    }

    private PanoramaPolicyCollector collector(PolicyCompletenessSettings settings, Instant now, PanoramaConfigLogReader logReader) {
        return new PanoramaPolicyCollector(transport, gates, ref -> new PanCredentialMaterial("synthetic-user", new char[0]), repository)
                .withCompleteness(settings, new JobWindowPolicy(Clock.fixed(now, java.time.ZoneOffset.UTC), 60), logReader);
    }

    private static PolicyCompletenessSettings nat(String device) {
        return new PolicyCompletenessSettings(Optional.empty(), Optional.empty(), Optional.of(device));
    }

    private String collectWithTranscript(PanoramaPolicyCollector collector, PolicyObservations observations) throws Exception {
        index.set(0); specs.clear(); targets.clear();
        var transcript = new JobTranscript();
        try (var recording = JobTranscriptScope.open(transcript)) {
            collector.collect(run, scope, () -> true, snapshot -> {}, observations);
        }
        var output = new ByteArrayOutputStream();
        transcript.writeTo(output);
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test void withNoSettingTheCollectorMakesTheSixApprovedCallsAndObservesEveryDeviceGroup() throws Exception {
        var observations = new PolicyObservations();
        String text = collectWithTranscript(collector(PolicyCompletenessSettings.OFF, OPEN, null), observations);
        assertEquals(6, specs.size(), "keygen, devicegroups, dg-hierarchy, shared and two device groups: nothing else");
        assertFalse(text.contains("PAN_NAT_HIT_MEASURE"));
        assertFalse(text.contains("PAN_CONFIG_LOG"));
        var seen = observations.pan();
        assertEquals(2, seen.size());
        assertEquals(Set.of(PolicySnapshot.ref("manager-1", "device-group", "Synthetic parent"),
                PolicySnapshot.ref("manager-1", "device-group", "Synthetic child")),
            new HashSet<>(seen.stream().map(PolicyObservations.Pan::container).toList()));
        for (var o : seen) {
            assertTrue(o.contentVersion().matches("[0-9a-f]{64}"), "an opaque SHA-256 token, never XML");
            assertTrue(o.complete()); assertNull(o.failureReason()); assertTrue(o.rules() > 0);
        }
    }

    @Test void theContentVersionIsStableForTheSameConfigurationAndMovesWhenItChanges() throws Exception {
        var first = new PolicyObservations(); collectWithTranscript(collector(PolicyCompletenessSettings.OFF, OPEN, null), first);
        var same = new PolicyObservations(); collectWithTranscript(collector(PolicyCompletenessSettings.OFF, OPEN, null), same);
        assertEquals(first.pan().stream().map(PolicyObservations.Pan::contentVersion).toList(),
                     same.pan().stream().map(PolicyObservations.Pan::contentVersion).toList());
        replies.set(5, replies.get(5).replace("<action>allow</action>", "<action>deny</action>"));
        var changed = new PolicyObservations(); collectWithTranscript(collector(PolicyCompletenessSettings.OFF, OPEN, null), changed);
        var byContainer = new HashMap<String, String>();
        first.pan().forEach(o -> byContainer.put(o.container(), o.contentVersion()));
        String parent = PolicySnapshot.ref("manager-1", "device-group", "Synthetic parent"), child = PolicySnapshot.ref("manager-1", "device-group", "Synthetic child");
        var after = new HashMap<String, String>();
        changed.pan().forEach(o -> after.put(o.container(), o.contentVersion()));
        assertEquals(byContainer.get(parent), after.get(parent), "a change in the child group does not move its parent's marker");
        assertNotEquals(byContainer.get(child), after.get(child));
        assertNotEquals(byContainer.get(parent), byContainer.get(child), "each group has its own lineage");
    }

    @Test void aDeviceGroupThatCouldNotBeCollectedIsObservedWithItsFailureCodeAndNoVersionClaim() throws Exception {
        replies.set(5, success("<unexpected/>"));
        var observations = new PolicyObservations();
        collectWithTranscript(collector(PolicyCompletenessSettings.OFF, OPEN, null), observations);
        var failed = observations.pan().stream().filter(o -> !o.complete()).toList();
        assertEquals(1, failed.size());
        assertEquals(PolicySnapshot.ref("manager-1", "device-group", "Synthetic child"), failed.get(0).container());
        assertEquals("INVALID_OR_INCOMPLETE_RESPONSE", failed.get(0).failureReason());
        assertEquals(0, failed.get(0).rules());
    }

    @Test void theTrialFirewallGetsExactlyOneSignedNatHitCallAndOnlyASanitizedSummaryIsKept() throws Exception {
        replies.add(success("<key>synthetic-member-key</key>"));
        replies.add(success(NAT_REPLY));
        String text = collectWithTranscript(collector(nat("device-1"), OPEN, null), new PolicyObservations());
        assertEquals(8, specs.size());
        assertEquals("192.0.2.20", targets.get(7).baseUrl());
        var natCall = specs.get(7);
        assertEquals("op", natCall.type());
        assertEquals("no_target", natCall.targetScope());
        assertEquals(PolicyCompletenessGates.P1_NAT_HIT_COUNTS.text(),
            "type=op&cmd=" + natCall.formParams().get("cmd").replace("vsys1", "<VSYS>"));
        assertEquals(Set.of("X-PAN-KEY"), natCall.headers().keySet());
        assertFalse(natCall.formParams().containsKey("target"));
        verify(repository).beginDomain(eq("device-1"), eq(PolicySnapshot.ref("rule-hit-count-nat", "vsys1")), eq(false));
        assertTrue(text.contains("PAN_NAT_HIT_MEASURE outcome=MEASURED ruleCount=2 withHitCount=2 natRuleBasePresent=true"));
        assertFalse(text.contains("Synthetic NAT rule"));
    }

    @Test void aSettingThatNamesAnotherFirewallIssuesNoNatCall() throws Exception {
        String text = collectWithTranscript(collector(nat("another-device"), OPEN, null), new PolicyObservations());
        assertEquals(6, specs.size());
        assertFalse(text.contains("PAN_NAT_HIT_MEASURE"));
        verify(repository, never()).panFirewall(anyString());
    }

    @Test void aJobThatStartedOutsideTheWindowIssuesNoNatCall() throws Exception {
        String text = collectWithTranscript(collector(nat("device-1"), CLOSED, null), new PolicyObservations());
        assertEquals(6, specs.size());
        assertTrue(text.contains("PAN_NAT_HIT_MEASURE outcome=UNKNOWN reason=OUTSIDE_JOB_WINDOW"));
    }

    @Test void theSixHourThrottleKeepsAnAutomaticNatMeasurementOffTheFirewall() throws Exception {
        when(repository.beginDomain(eq("device-1"), anyString(), anyBoolean())).thenReturn(false);
        String text = collectWithTranscript(collector(nat("device-1"), OPEN, null), new PolicyObservations());
        assertEquals(6, specs.size());
        assertTrue(text.contains("PAN_NAT_HIT_MEASURE outcome=UNKNOWN reason=THROTTLED"));
    }

    @Test void anUnsignedNatGateIssuesNoCall() throws Exception {
        GateRegistryPort unsigned = key -> gates.findByCanonicalKey(key).stream()
            .filter(r -> !r.gateId().equals("pan_policy_nat_rule_hit_count")).toList();
        var collector = new PanoramaPolicyCollector(transport, unsigned, ref -> new PanCredentialMaterial("synthetic-user", new char[0]), repository)
            .withCompleteness(nat("device-1"), new JobWindowPolicy(Clock.fixed(OPEN, java.time.ZoneOffset.UTC), 60), null);
        String text = collectWithTranscript(collector, new PolicyObservations());
        assertEquals(6, specs.size());
        assertTrue(text.contains("reason=POLICY_GATE_UNAVAILABLE"));
    }

    @Test void aNatAnswerThatIsNotEvaluableIsUnknownAndNeverFailsTheCollection() throws Exception {
        replies.add(success("<key>synthetic-member-key</key>"));
        replies.add("<response status='error' code='7'><msg>x</msg></response>");
        var observations = new PolicyObservations();
        String text = collectWithTranscript(collector(nat("device-1"), OPEN, null), observations);
        assertEquals(8, specs.size());
        assertTrue(text.contains("PAN_NAT_HIT_MEASURE outcome=UNKNOWN"));
        assertEquals(2, observations.pan().size());
        assertTrue(observations.pan().stream().allMatch(PolicyObservations.Pan::complete));
    }

    @Test void theConfigLogReaderIsAskedOnlyForTheOnePanoramaTheSettingNames() throws Exception {
        var reader = mock(PanoramaConfigLogReader.class);
        collectWithTranscript(collector(PolicyCompletenessSettings.OFF, OPEN, reader), new PolicyObservations());
        collectWithTranscript(collector(new PolicyCompletenessSettings(Optional.of("another-manager"), Optional.empty(), Optional.empty()), OPEN, reader),
            new PolicyObservations());
        verifyNoInteractions(reader);
        collectWithTranscript(collector(new PolicyCompletenessSettings(Optional.of("manager-1"), Optional.empty(), Optional.empty()), OPEN, reader),
            new PolicyObservations());
        verify(reader, times(1)).read(eq("manager-1"), eq(""), eq(false), eq(OPEN), any(), any(), any());
    }

    @Test void aFailingConfigLogReaderNeverFailsThePolicyCollection() throws Exception {
        var reader = mock(PanoramaConfigLogReader.class);
        when(reader.read(any(), any(), anyBoolean(), any(), any(), any(), any())).thenThrow(new IllegalStateException("synthetic"));
        var observations = new PolicyObservations();
        String text = collectWithTranscript(collector(new PolicyCompletenessSettings(Optional.of("manager-1"), Optional.empty(), Optional.empty()), OPEN, reader),
            observations);
        assertTrue(text.contains("PAN_CONFIG_LOG outcome=UNKNOWN reason=READER_FAILED"));
        assertEquals(2, observations.pan().size());
    }

    @Test void theNatSummaryKeepsCountsAndFieldNamesOnly() throws Exception {
        var doc = PolicyXml.parse("<response status='success'><result>" + NAT_REPLY + "</result></response>").getDocumentElement();
        var summary = PanNatHitMeasure.summarize(doc).orElseThrow();
        assertTrue(summary.natRuleBasePresent());
        assertEquals(2, summary.ruleCount()); assertEquals(2, summary.withHitCount());
        assertEquals(List.of("hit-count", "last-hit-timestamp"), summary.fieldNames());
        var security = PolicyXml.parse("<response status='success'><result><rule-hit-count><vsys><entry name='vsys1'><rule-base><entry name='security'>"
            + "<rules><entry name='r'><hit-count>x</hit-count></entry></rules></entry></rule-base></entry></vsys></rule-hit-count></result></response>").getDocumentElement();
        var other = PanNatHitMeasure.summarize(security).orElseThrow();
        assertFalse(other.natRuleBasePresent(), "the substitution did not hold: said so, not assumed");
        assertEquals(0, other.withHitCount(), "a non-numeric hit value is not a count");
        assertEquals(0, PanNatHitMeasure.summarize(PolicyXml.parse("<response status='success'><result/></response>").getDocumentElement()).orElseThrow().ruleCount());
        assertTrue(PanNatHitMeasure.summarize(null).isEmpty());
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
}
