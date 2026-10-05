package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliCommands;

class CheckPointPolicyCollectorTest {
    @org.junit.jupiter.api.BeforeEach void isolateRuleCollection() { System.setProperty("ui2.policy.cp.collect-objects", "false"); }
    @org.junit.jupiter.api.AfterEach void restoreObjectCollection() { System.clearProperty("ui2.policy.cp.collect-objects"); }
    private final DeviceTransport transport = mock(DeviceTransport.class);
    private final TransportSession session = () -> "synthetic-session";
    private final PolicyCollectionRepository repository = mock(PolicyCollectionRepository.class);
    private final GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(
        getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream().filter(r -> r.key().equals(key) && !r.gateId().equals("cp_policy_access_rulebase_hits")).toList();
    private final DiscoveryRun run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
        DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final PolicyCollectionRepository.Request request = new PolicyCollectionRepository.Request("mds-1", "", false);

    private CheckPointPolicyCollector setup(java.util.function.Function<String, ExecResult> answers) {
        when(transport.connect(any(), any(), any())).thenReturn(new ConnectResult.Authenticated(session));
        when(transport.execInteractive(eq(session), any(), any())).thenAnswer(call -> answers.apply(((ExecSpec) call.getArgument(1)).command()));
        when(repository.beginDomain(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of(new PolicySnapshot.Target("device-1", "FW-TANGO-04", "", "UNKNOWN")));
        return new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), 1);
    }
    private static ExecSpec policySpec(String command) { return new ExecSpec(command, false, 120_000); }

    @Test void allInstallationTargetsAndOptionalAccessLayersWorkInSerialAndParallel() {
        for (int sessions : List.of(1, 4)) for (String access : List.of("", "\"access\":false,", "\"access\":false,\"access-layers\":[],")) {
            reset(transport, repository);
            setup(command -> command.contains("show-packages")
                ? ok("{\"from\":1,\"to\":1,\"total\":1,\"packages\":[{\"uid\":\"pkg-01\",\"name\":\"Package\","
                    + access + "\"installation-targets\":\"all\"}]}") : answer(command));
            var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions);
            var snapshots = collector.collect(run, request, () -> true);
            assertEquals(1, snapshots.size());
            assertTrue(snapshots.get(0).failures().isEmpty());
            assertEquals("ALL", snapshots.get(0).metadata().targets().get(0).name());
            assertEquals("UNKNOWN", snapshots.get(0).metadata().targets().get(0).syncStatus());
            verify(repository, never()).targets(anyString(), anyString(), anyString());
            verify(transport, never()).execInteractive(any(), argThat(spec -> spec.command().contains("show-access-rulebase")), any());
            verify(transport).execInteractive(any(), eq(policySpec(MgmtCliCommands.showNatRulebase("DOM-TANGO-01", "Package", 0))), any());
        }
    }

    @Test void domainScopedLayerIdentityIsUsedInSerialAndParallel() {
        for (int sessions : List.of(1, 4)) {
            reset(transport, repository);
            setup(command -> {
                String body = ((ExecResult.Completed) answer(command)).output();
                if (command.contains("show-packages")) body = body.replace(
                    "{\"uid\":\"layer\",\"name\":\"Layer\"}", "{\"domain\":{\"uid\":\"layer\",\"name\":\"Layer\"}}");
                return ok(body);
            });
            var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions);
            var snapshots = collector.collect(run, request, () -> true);
            assertEquals(1, snapshots.size());
            assertTrue(snapshots.get(0).failures().isEmpty());
            verify(transport).execInteractive(any(), eq(policySpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0))), any());
        }
    }

    @Test void malformedPackageItemsReportOnlyIndexFieldAndType() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String field : List.of("uid", "name", "access-layers", "installation-targets")) {
            var item = mapper.createObjectNode().put("uid", "Synthetic-private-uid").put("name", "Synthetic-private-name");
            item.putArray("access-layers"); item.putArray("installation-targets");
            item.put(field, "");
            var collector = setup(command -> ok("{\"total\":1,\"packages\":[" + item + "]}"));
            var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
            try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript)) {
                var error = assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.read(session,
                    MgmtCliCommands.showPackages("DOM-TANGO-01"), 0, Long.MAX_VALUE, () -> true));
                assertTrue(error.getMessage().endsWith(": INVALID_PACKAGE_ITEM:" + field));
            }
            var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
            String notes = sink.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(notes.contains("item=0 field=" + field + " type=STRING"));
            assertFalse(notes.contains("Synthetic-private"));
        }
    }

    @Test void malformedItemFailureReachesDomainResultInSerialAndParallel() {
        for (int sessions : List.of(1, 4)) {
            reset(transport, repository);
            setup(command -> command.contains("show-packages")
                ? ok(packagePage(0, 20, 21).replaceFirst("\"installation-targets\":\\[\\]", "\"installation-targets\":\"unsupported\""))
                : answer(command));
            var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions);
            List<PolicySnapshot.CollectionFailure> failures = new ArrayList<>();
            assertTrue(collector.collect(run, request, () -> true, snapshot -> fail("Invalid package"), failures::add).isEmpty());
            assertEquals(1, failures.size());
            assertEquals(0, failures.get(0).offset());
            assertTrue(failures.get(0).reason().endsWith(": INVALID_PACKAGE_ITEM:installation-targets"));
            verify(repository, never()).targets(anyString(), anyString(), anyString());
        }
    }

    @Test void accessEnabledPackagesRequireNonemptyLayersAndLayerIdentityRemainsRequired() {
        for (String fields : List.of("\"access\":true", "\"access\":true,\"access-layers\":[]",
                "\"access\":false,\"access-layers\":null", "\"access-layers\":[{\"domain\":{\"uid\":\"layer\"}}]")) {
            var collector = setup(command -> ok("{\"total\":1,\"packages\":[{\"uid\":\"pkg\",\"name\":\"Package\","
                + fields + ",\"installation-targets\":[]}] }"));
            var error = assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.read(session,
                MgmtCliCommands.showPackages("DOM-TANGO-01"), 0, Long.MAX_VALUE, () -> true));
            assertTrue(error.getMessage().endsWith(": INVALID_PACKAGE_ITEM:access-layers"));
        }
    }

    @Test void pagedPackagesCompleteBeforeCollectionInSerialAndParallel() {
        for (int sessions : List.of(1, 4)) for (int total : List.of(21, 40, 0)) {
            reset(transport, repository);
            setup(command -> {
                if (command.contains("show-nat-rulebase")) {
                    assertTrue(java.util.stream.IntStream.range(0, total).anyMatch(index -> command.equals(
                        MgmtCliCommands.showNatRulebase("DOM-TANGO-01", "Package-" + index, 0))));
                    return ok(page("nat", 0, 0, 0, "", ""));
                }
                if (!command.contains("show-packages")) return answer(command);
                var offset = java.util.regex.Pattern.compile(" offset '([0-9]+)'").matcher(command);
                assertTrue(offset.find());
                assertTrue(command.contains(" limit 50 "));
                return ok(packagePage(Integer.parseInt(offset.group(1)), 50, total));
            });
            var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions);
            List<PolicySnapshot.CollectionFailure> failures = new ArrayList<>();
            assertEquals(total, collector.collect(run, request, () -> true, snapshot -> {}, failures::add).size());
            assertTrue(failures.isEmpty());
            verify(transport, times(1)).execInteractive(any(),
                argThat(spec -> spec.command().contains("show-packages")), any());
        }
    }

    @Test void inconsistentSecondPackagePageReportsOffsetWithoutCollectingPackages() {
        for (int sessions : List.of(1, 4)) for (String defect : List.of("total", "duplicate", "from", "size")) {
            reset(transport, repository);
            setup(command -> {
                if (!command.contains("show-packages")) return answer(command);
                if (command.contains("offset '0'")) return ok(packagePage(0, 20, 21));
                String page = packagePage(20, 20, 21);
                return ok(switch (defect) {
                    case "total" -> page.replace("\"total\":21", "\"total\":22");
                    case "duplicate" -> page.replace("pkg-20", "pkg-0");
                    case "from" -> page.replace("\"from\":21", "\"from\":20");
                    default -> page.replace("\"to\":21", "\"to\":20");
                });
            });
            var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions);
            List<PolicySnapshot.CollectionFailure> failures = new ArrayList<>();
            assertTrue(collector.collect(run, request, () -> true, snapshot -> fail("Incomplete domain"), failures::add).isEmpty());
            assertEquals(1, failures.size());
            assertEquals(20, failures.get(0).offset());
            verify(repository, never()).targets(anyString(), anyString(), anyString());
        }
    }

    @Test void packageGateIsRecheckedBeforeEveryPage() {
        for (int sessions : List.of(1, 4)) {
            reset(transport, repository);
            setup(command -> command.contains("show-packages") ? ok(packagePage(0, 50, 51)) : answer(command));
            var checks = new AtomicInteger();
            GateRegistryPort revoked = key -> {
                if (key.equals(new CanonicalCommandKey("check_point", "cp_multi_domain_server", "expert", "SSH_EXEC",
                        CpPolicyGates.COMMANDS.get(CpPolicyGates.PACKAGES_50))) && checks.incrementAndGet() > 2) return List.of();
                return gates.findByCanonicalKey(key);
            };
            var collector = new CheckPointPolicyCollector(transport, revoked, repository, Duration.ofHours(2), sessions);
            List<PolicySnapshot.CollectionFailure> failures = new ArrayList<>();
            var error = assertThrows(PolicyCollectionTrace.Failure.class, () ->
                collector.collect(run, request, () -> true, snapshot -> fail("Revoked gate"), failures::add));
            assertEquals("policy: POLICY_GATE_UNAVAILABLE", error.getMessage(), "sessions=" + sessions);
            assertEquals(3, checks.get());
            assertTrue(failures.isEmpty());
            verify(transport, times(1)).execInteractive(any(), argThat(spec -> spec.command().contains("show-packages")), any());
            verify(repository, never()).targets(anyString(), anyString(), anyString());
        }
    }

    @Test void nonPagedDomainReadHasNoPageAnnotation() throws Exception {
        var collector = setup(this::answer);
        var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
        try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript)) {
            collector.read(session, MgmtCliCommands.domainList(), -1, Long.MAX_VALUE, () -> true);
        }
        var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
        String notes = sink.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(notes.contains(" offset="));
        assertFalse(notes.contains(" limit="));
    }

    @Test void malformedPackageListingFailsOnlyItsDomainWithSafeDiagnostics() throws Exception {
        String truncated = "{\"from\":21,\"total\":25,\"packages\":[{\"name\":\"Synthetic é42\"";
        for (int sessions : List.of(1, 4)) {
            reset(transport, repository);
            setup(command -> {
                if (command.equals(MgmtCliCommands.domainList())) return ok("{\"total\":2,\"objects\":[{\"uid\":\"broken\",\"name\":\"DOM-BRAVO-02\"},{\"uid\":\"domain-01\",\"name\":\"DOM-TANGO-01\"}]}");
                if (command.contains("show-packages") && command.contains("'DOM-BRAVO-02'")) return ok(truncated);
                return answer(command);
            });
            var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions);
            var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
            List<PolicySnapshot.CollectionFailure> failures = new ArrayList<>();
            try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript)) {
                var snapshots = collector.collect(run, request, () -> true, snapshot -> {}, failures::add);
                assertEquals(1, snapshots.size());
                assertTrue(snapshots.get(0).failures().isEmpty());
            }
            assertEquals(1, failures.size());
            assertEquals(0, failures.get(0).offset());
            assertEquals(PolicySnapshot.ref("mds-1", "broken"), failures.get(0).layerRef());
            assertTrue(failures.get(0).reason().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"));
            var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
            String notes = sink.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(notes.contains("bytes=" + truncated.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
            assertTrue(notes.contains("endedMidJson=true exitCode=0"));
            assertTrue(notes.contains("STRUCTURE rootParsed=false rootType=UNPARSED"));
            assertFalse(notes.contains("Synthetic"));
            assertFalse(notes.contains("DOM-BRAVO-02"));
            verify(transport, never()).execInteractive(any(), argThat(spec -> spec.command().contains("'DOM-BRAVO-02'")
                && !spec.command().contains("show-packages")), any());
        }
    }

    @Test void packageListingRejectsGapsInconsistentTotalsAndDuplicateIdentities() {
        for (String defect : List.of("gap", "total", "duplicate")) {
            reset(transport, repository);
            var collector = setup(command -> {
                if (!command.contains("show-packages")) return answer(command);
                String page = packagePage(0, 20, 20);
                return ok(switch (defect) {
                    case "gap" -> page.replace("\"from\":1", "\"from\":2");
                    case "total" -> page.replace("\"total\":20", "\"total\":19");
                    default -> page.replace("pkg-1\"", "pkg-0\"");
                });
            });
            List<PolicySnapshot.CollectionFailure> failures = new ArrayList<>();
            assertTrue(collector.collect(run, request, () -> true, snapshot -> fail("Incomplete domain"), failures::add).isEmpty());
            assertEquals(0, failures.get(0).offset());
        }
    }

    @Test void invalidPackageJsonDiagnosticsRetainNonzeroExitAndDistinguishClosedMalformedJson() throws Exception {
        for (String body : List.of("{\"packages\":[", "{broken}")) {
            var collector = setup(command -> new ExecResult.Completed(body, 7));
            var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
            try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript)) {
                assertTrue(assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.read(session,
                    MgmtCliCommands.showPackages("DOM-TANGO-01", 20, 20), 0, Long.MAX_VALUE, () -> true))
                    .getMessage().endsWith(": EXIT_7"));
            }
            var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
            assertTrue(sink.toString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("endedMidJson=" + body.endsWith("[") + " exitCode=7"));
        }
    }

    private static String packagePage(int offset, int limit, int total) {
        int to = Math.min(offset + limit, total);
        var packages = new ArrayList<String>();
        for (int index = offset; index < to; index++) packages.add("{\"uid\":\"pkg-" + index
            + "\",\"name\":\"Package-" + index + "\",\"access-layers\":[],\"installation-targets\":[]}");
        return "{\"from\":" + (offset + 1) + ",\"to\":" + to + ",\"total\":" + total + ",\"packages\":["
            + String.join(",", packages) + "]}";
    }

    @Test void configuredTimeoutAppliesToEveryPolicyReadAndExtensionRespectsJobDeadline() {
        String previous = System.getProperty("ui2.policy.cp.read-timeout");
        try {
            System.setProperty("ui2.policy.cp.read-timeout", "420");
            var collector = setup(this::answer);
            collector.collect(run, request, () -> true);
            verify(transport, times(6)).execInteractive(any(), argThat(spec -> spec.streamingExtensionMs() == 120_000), eq(Duration.ofSeconds(420)));
            reset(transport);
            when(transport.execInteractive(any(), any(), any())).thenReturn(ok("{\"total\":0,\"objects\":[]}"));
            long now = 10;
            var bounded = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), () -> now);
            bounded.read(session, MgmtCliCommands.domainList(), -1, now + Duration.ofSeconds(450).toNanos(), () -> true);
            verify(transport).execInteractive(any(), argThat(spec -> spec.streamingExtensionMs() == 30_000), eq(Duration.ofSeconds(420)));
            reset(transport);
            when(transport.execInteractive(any(), any(), any())).thenReturn(ok("{\"total\":0,\"objects\":[]}"));
            bounded.read(session, MgmtCliCommands.domainList(), -1, now + Duration.ofSeconds(30).toNanos(), () -> true);
            verify(transport).execInteractive(any(), argThat(spec -> spec.streamingExtensionMs() == 0), eq(Duration.ofSeconds(30)));
            for (String invalid : List.of("0", "-1", "2147484")) {
                System.setProperty("ui2.policy.cp.read-timeout", invalid);
                assertThrows(IllegalArgumentException.class, CheckPointPolicyCollector::configuredReadTimeout);
            }
        } finally {
            if (previous == null) System.clearProperty("ui2.policy.cp.read-timeout");
            else System.setProperty("ui2.policy.cp.read-timeout", previous);
        }
    }

    @Test void failedPackageReadMarksDomainIncompleteAndContinuesOnFreshSession() {
        var collector = setup(command -> {
            if (command.equals(MgmtCliCommands.domainList())) return ok("{\"total\":2,\"objects\":[{\"uid\":\"broken\",\"name\":\"DOM-BRAVO-02\"},{\"uid\":\"domain-01\",\"name\":\"DOM-TANGO-01\"}]}");
            if (command.contains("show-packages") && command.contains("'DOM-BRAVO-02'")) return new ExecResult.TimedOut();
            return answer(command);
        });
        List<PolicySnapshot> published = new ArrayList<>();
        List<PolicySnapshot.CollectionFailure> failures = new ArrayList<>();
        var snapshots = collector.collect(run, request, () -> true, published::add, failures::add);
        assertEquals(1, snapshots.size());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).reason().endsWith(": TIMEOUT"));
        assertEquals("Packages", failures.get(0).layerName());
        assertEquals(PolicySnapshot.ref("mds-1", "broken"), failures.get(0).layerRef());
        assertFalse(snapshots.get(0).sections().isEmpty());
        assertTrue(published.contains(snapshots.get(0)));
        verify(transport, times(2)).connect(any(), any(), any());
        verify(transport, times(2)).disconnect(session);
    }

    @Test void completeResponsesWithBannerAndExtraFieldsWorkInSerialAndParallel() {
        for (int sessions : List.of(1, 4)) {
            setup(command -> {
                String body = ((ExecResult.Completed) answer(command)).output();
                if (command.contains("show-packages")) body = body.replace("\"total\":1", "\"message\":\"Synthetic metadata\",\"extra\":{},\"total\":1");
                return ok("\uFEFFSynthetic banner 42\r\nNotice: synthetic environment\n" + body);
            });
            var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions);
            var snapshots = collector.collect(run, request, () -> true);
            assertEquals(1, snapshots.size());
            assertTrue(snapshots.get(0).failures().isEmpty());
        }
    }

    @Test void emptyDomainsAndDomainsWithoutPackagesAreComplete() {
        for (int sessions : List.of(1, 4)) {
            for (boolean emptyDomains : List.of(true, false)) {
                setup(command -> command.contains("show-domains") && !emptyDomains ? answer(command)
                    : ok(command.contains("show-domains") ? "{\"total\":0,\"objects\":[],\"extra\":true}"
                        : "{\"total\":0,\"packages\":[],\"from\":0,\"to\":0}"));
                var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions);
                List<PolicySnapshot.CollectionFailure> failures = new ArrayList<>();
                assertTrue(collector.collect(run, request, () -> true, snapshot -> fail("No package to publish"), failures::add).isEmpty());
                assertTrue(failures.isEmpty());
            }
        }
    }

    @Test void invalidPreflightFailsClosedAndRecordsOnlyMaskedShapeAndUtf8Length() throws Exception {
        for (String body : List.of("", "Synthetic banner only", "{\"total\":0,\"packages\":[", "{\"total\":0,\"packages\":[]} trailing",
                "{\"total\":0,\"packages\":[]} {}", "{broken\n{\"total\":0,\"packages\":[]}",
                "{\"total\":1,\"packages\":[]}", "{\"packages\":[]}", "{\"total\":-1,\"packages\":[]}",
                "{\"total\":18446744073709551616,\"packages\":[]}",
                "{\"total\":\"0\",\"packages\":[]}", "{\"total\":0,\"total\":0,\"packages\":[]}",
                "{\"total\":0,\"packages\":null}", "{\"total\":1,\"packages\":[{}]}",
                "{\"total\":1,\"packages\":[{\"uid\":\"pkg\",\"name\":\"Package\"}]}",
                "{\"total\":1,\"packages\":[{\"uid\":\"pkg\",\"name\":\"Package\",\"access-layers\":[{}],\"installation-targets\":[]}]}",
                "{\"total\":1,\"packages\":[{\"uid\":\"pkg\",\"name\":\"Package\",\"access-layers\":[],\"installation-targets\":[\"\"]}]}",
                "[]", "{\"code\":\"generic_error\",\"message\":\"Synthetic failure\"}",
                "Synthetic é42\n{\"total\":0,\"packages\":[")) {
            var collector = setup(command -> ok(body));
            var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
            try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript)) {
                var error = assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.read(session,
                    MgmtCliCommands.showPackages("DOM-TANGO-01"), 0, Long.MAX_VALUE, () -> true));
                assertTrue(error.getMessage().contains("INVALID_OR_INCOMPLETE_RESPONSE")
                    || error.getMessage().contains("INVALID_PACKAGE_ITEM:"));
            }
            var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
            String note = sink.toString(java.nio.charset.StandardCharsets.UTF_8).lines()
                .map(line -> { try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(line).path("text").asText(); }
                    catch (java.io.IOException invalid) { throw new AssertionError(invalid); } })
                .filter(text -> text.startsWith("invalid preflight bytes=")).findFirst().orElseThrow();
            assertTrue(note.startsWith("invalid preflight bytes=" + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " shapeTruncated=false endedMidJson="));
            String shape = note.substring(note.indexOf(" shape=") + 7);
            assertTrue(shape.codePoints().allMatch(c -> !Character.isLetter(c) || c == 'a'));
            assertTrue(shape.codePoints().allMatch(c -> !Character.isDigit(c) || c == '9'));
            assertFalse(note.contains("Synthetic"));
            assertTrue(note.contains("STRUCTURE rootParsed="));
        }
    }

    @Test void invalidPreflightStructureReportsBannerCountersTypesAndArraySizesWithoutValues() throws Exception {
        String prefix = "Synthetic é banner\r\nSynthetic notice\n";
        String body = prefix + "{\"from\":1,\"to\":0,\"total\":3,\"packages\":[],"
            + "\"objects\":[\"Synthetic identity\"],\"message\":\"Synthetic secret\",\"Synthetic-key\":[1,2]}";
        var collector = setup(command -> ok(body));
        var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
        try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript)) {
            var error = assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.read(session,
                MgmtCliCommands.showPackages("DOM-TANGO-01"), CpPolicyGates.PACKAGES_50, Long.MAX_VALUE, () -> true));
            assertTrue(error.getMessage().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"));
        }
        String structure = CheckPointPolicyCollector.preflightStructure(body,
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.substring(prefix.length())));
        assertTrue(structure.contains("rootParsed=true rootType=OBJECT leadingNonJsonLines=2 leadingNonJsonBytes="
            + prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        assertTrue(structure.contains("packages:ARRAY(size=0)"));
        assertTrue(structure.contains("objects:ARRAY(size=1)"));
        assertTrue(structure.contains("<masked-key>:ARRAY(size=2)"));
        assertTrue(structure.contains("message:STRING"));
        assertTrue(structure.contains("counters=[from=1, to=0, total=3]"));
        assertFalse(structure.contains("Synthetic"));
        var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
        String notes = sink.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(notes.contains(structure));
        assertFalse(notes.contains("paging unavailable: gate literal fixed"));
        assertTrue(notes.contains(" offset=0"));
        assertTrue(notes.contains(" limit=50"));
        assertFalse(notes.contains("Synthetic"));
        String typed = CheckPointPolicyCollector.preflightStructure("{}",
            new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"from\":\"Synthetic value\",\"to\":null,\"packages\":[]}"));
        assertTrue(typed.contains("counters=[from=STRING, to=NULL, total=MISSING]"));
        assertFalse(typed.contains("Synthetic"));
    }

    @Test void malformedDomainPackageAndObjectReadsKeepMaskedStructureDiagnostics() throws Exception {
        for (int gate : List.of(-1, 0, CpPolicyGates.PACKAGES_50, CpPolicyGates.OBJECT_BASE)) {
            for (String body : List.of("Synthetic banner\n{\"objects\":[", "[]")) {
                var collector = setup(command -> ok(body));
                String command = gate < 0 ? MgmtCliCommands.domainList()
                    : gate >= CpPolicyGates.OBJECT_BASE ? MgmtCliCommands.showPolicyObjects("DOM-TANGO-01", "times", 0)
                    : MgmtCliCommands.showPackages("DOM-TANGO-01", 0, gate == 0 ? 20 : 50);
                var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
                try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript)) {
                    assertTrue(assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.read(session,
                        command, gate, Long.MAX_VALUE, () -> true)).getMessage().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"));
                }
                var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
                String notes = sink.toString(java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(notes.contains(body.equals("[]") ? "STRUCTURE rootParsed=true rootType=ARRAY"
                    : "STRUCTURE rootParsed=false rootType=UNPARSED"), "gate=" + gate);
                assertFalse(notes.contains("Synthetic"));
            }
        }
    }

    @Test void oversizedPackageListAndNonObjectRootHaveGenericReasonAndStructure() throws Exception {
        for (String body : List.of(packagePage(0, 501, 501), "[]")) {
            var collector = setup(command -> ok(body));
            var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
            try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript)) {
                assertTrue(assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.read(session,
                    MgmtCliCommands.showPackages("DOM-TANGO-01"), 0, Long.MAX_VALUE, () -> true))
                    .getMessage().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"));
            }
            var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
            String notes = sink.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(notes.contains("STRUCTURE rootParsed=true rootType=" + (body.equals("[]") ? "ARRAY" : "OBJECT")));
            if (!body.equals("[]")) assertTrue(notes.contains("packages:ARRAY(size=501)"));
        }
    }

    @Test void incompleteOrDuplicateDomainIdentitiesAreRejected() {
        for (String body : List.of("{\"total\":1,\"objects\":[]}", "{\"total\":1,\"objects\":[{\"uid\":\"domain\",\"name\":\"\"}]}",
                "{\"total\":2,\"objects\":[{\"uid\":\"domain\",\"name\":\"DOM-TANGO-01\"},{\"uid\":\"domain\",\"name\":\"DOM-BRAVO-02\"}]}")) {
            var collector = setup(command -> ok(body));
            assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.read(session, MgmtCliCommands.domainList(), -1, Long.MAX_VALUE, () -> true));
        }
    }

    @Test void invalidShapeIsBoundedAndNonzeroExitAndSessionPressureRemainFailures() throws Exception {
        String body = "Synthetic é42 ".repeat(300);
        var collector = setup(command -> ok(body));
        var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
        try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript)) {
            assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.read(session,
                MgmtCliCommands.domainList(), -1, Long.MAX_VALUE, () -> true));
        }
        var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
        String note = sink.toString(java.nio.charset.StandardCharsets.UTF_8).lines()
            .map(line -> { try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(line).path("text").asText(); }
                catch (java.io.IOException invalid) { throw new AssertionError(invalid); } })
            .filter(text -> text.startsWith("invalid preflight bytes=")).findFirst().orElseThrow();
        assertTrue(note.contains("bytes=" + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " shapeTruncated=true"));
        assertEquals(2048, note.substring(note.indexOf(" shape=") + 7).length());
        var exitCollector = setup(command -> new ExecResult.Completed("Synthetic failure", 1));
        assertTrue(assertThrows(PolicyCollectionTrace.Failure.class, () -> exitCollector.read(session,
            MgmtCliCommands.domainList(), -1, Long.MAX_VALUE, () -> true)).getMessage().endsWith(": EXIT_1"));
        var pressureCollector = setup(command -> ok("{\"code\":\"generic_error\",\"message\":\"Too many sessions\"}"));
        assertTrue(assertThrows(PolicyCollectionTrace.Failure.class, () -> pressureCollector.read(session,
            MgmtCliCommands.domainList(), -1, Long.MAX_VALUE, () -> true)).getMessage().endsWith(": API_SESSION_PRESSURE"));
    }

    private static ExecResult ok(String json) { return new ExecResult.Completed(json, 0); }
    private static String page(String uid, int from, int to, int total, String rules, String objects) {
        return "{\"uid\":\"" + uid + "\",\"name\":\"Layer\",\"from\":" + from + ",\"to\":" + to + ",\"total\":" + total
            + ",\"rulebase\":[" + rules + "],\"objects-dictionary\":[" + objects + "]}";
    }
    private static final String RULE = "{\"uid\":\"r1\",\"type\":\"access-rule\",\"source\":[\"host1\"],\"action\":\"accept\",\"inline-layer\":\"child\"}";
    private static final String DICTIONARY = "{\"uid\":\"host1\",\"name\":\"OBJ-ADDRESS-01\",\"type\":\"host\",\"ipv4-address\":\"192.0.2.8\"},"
        + "{\"uid\":\"accept\",\"name\":\"Accept\"},{\"uid\":\"child\",\"name\":\"Inline\",\"type\":\"access-layer\"}";
    private ExecResult answer(String command) {
        if (command.contains("show-packages")) command = command.replace(" limit 20 offset ", " limit 50 offset ");
        if (command.equals(MgmtCliCommands.domainList())) return ok("{\"total\":1,\"objects\":[{\"uid\":\"domain-01\",\"name\":\"DOM-TANGO-01\"}]}");
        if (command.equals(MgmtCliCommands.showPackages("DOM-TANGO-01"))) return ok("""
            {"from":1,"to":1,"total":1,"packages":[{"uid":"pkg-01","name":"Package","access-layers":[{"uid":"layer","name":"Layer"}],"installation-targets":[{"uid":"target-01"}]}]}
            """);
        if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0))) return ok(page("layer", 1, 1, 2, RULE, DICTIONARY));
        if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 1))) return ok(page("layer", 2, 2, 2, "{\"uid\":\"r2\",\"type\":\"access-rule\"}", ""));
        if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Inline", 0))) return ok(page("child", 1, 1, 1, "{\"uid\":\"r3\",\"type\":\"access-rule\"}", ""));
        if (command.equals(MgmtCliCommands.showNatRulebase("DOM-TANGO-01", "Package", 0))) return ok(page("nat", 1, 1, 1,
            "{\"uid\":\"n1\",\"type\":\"nat-rule\",\"original-source\":\"host1\",\"translated-source\":\"translated\",\"method\":\"hide\"}",
            "{\"uid\":\"translated\",\"name\":\"OBJ-ADDRESS-02\",\"type\":\"host\",\"ipv4-address\":\"198.51.100.8\"}"));
        throw new AssertionError("Unexpected command");
    }
    @Test void cancelBetweenPagesKeepsOnlyCompleteLayersAndDisconnects() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var collector = setup(command -> {
            var result = answer(command);
            if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 1))) cancelled.set(true);
            return result;
        });
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        try (var scope = new com.securityexpert.nexus.ui2.worker.JobCancellationScope(cancelled::get)) {
            assertThrows(PolicyCollectionTrace.Failure.class,
                () -> collector.collect(run, request, () -> true, checkpoints::add));
        }
        assertEquals(1, checkpoints.size());
        assertEquals(List.of("r1", "r2"), checkpoints.get(0).sections().stream().flatMap(s -> s.rules().stream())
            .map(PolicySnapshot.Rule::uuid).toList());
        verify(transport, never()).execInteractive(eq(session), eq(policySpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Inline", 0))), any());
        verify(transport).disconnect(session);
    }

    @Test void cancelledIncompleteLayerRestartsFromPageZero() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var collector = setup(command -> {
            var result = answer(command);
            if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0))) cancelled.set(true);
            return result;
        });
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        try (var scope = new com.securityexpert.nexus.ui2.worker.JobCancellationScope(cancelled::get)) {
            assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, () -> true, checkpoints::add));
        }
        assertTrue(checkpoints.isEmpty());
        cancelled.set(false);
        setup(this::answer).collect(run, request, () -> true);
        verify(transport, times(2)).execInteractive(eq(session), eq(policySpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0))), any());
    }

    @Test void commandsAreExactlyApprovedAndQuoted() {
        assertEquals("mgmt_cli -r true -d 'DOM' -f json show-packages limit 50 offset '0' details-level full", MgmtCliCommands.showPackages("DOM"));
        for (int offset : List.of(0, 20, 40)) {
            assertEquals(CpPolicyGates.COMMANDS.get(0).replace("<DOMAIN>", "DOM").replace("<N>", Integer.toString(offset)),
                MgmtCliCommands.showPackages("DOM", offset, 20));
        }
        var packageRows = gates.findByCanonicalKey(new CanonicalCommandKey("check_point", "cp_multi_domain_server",
            "expert", "SSH_EXEC", CpPolicyGates.COMMANDS.get(0)));
        assertEquals(1, packageRows.size());
        assertEquals("cp_policy_packages_paged", packageRows.get(0).gateId());
        assertEquals("mgmt_cli -r true -d 'DOM' -f json show-access-rulebase name 'LAYER' limit 100 offset '500' details-level full use-object-dictionary true", MgmtCliCommands.showAccessRulebase("DOM", "LAYER", 500));
        assertEquals("mgmt_cli -r true -d 'DOM' -f json show-nat-rulebase package 'PKG' limit 500 offset '0' details-level standard use-object-dictionary true", MgmtCliCommands.showNatRulebase("DOM", "PKG", 0));
        assertTrue(MgmtCliCommands.showPackages("O'Brien; $(false)").contains("'O'\\''Brien; $(false)'"));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showAccessRulebase("DOM", "Layer", -1));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showPackages("DOM\nnext"));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showPackages("DOM", -1, 20));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showPackages("DOM", 0, 0));
        assertDoesNotThrow(() -> MgmtCliCommands.showPackages("DOM", 0, 50));
        CpPolicyGates.requireAll(gates);
        assertTrue(CpPolicyGates.capability(gates).executionEligible());
        assertFalse(CpPolicyGates.capability(key -> List.of()).executionEligible());
        assertTrue(com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead.commands("check_point", "management_server", null, gates)
                .stream().noneMatch(command -> command.gateId().startsWith("cp_policy_")));
        assertThrows(IllegalStateException.class, () -> CpPolicyGates.requireAll(key -> List.of()));
    }
    @Test void pagesDictionariesInlineNatAndInstallTargetsShareOneInteractiveSession() {
        var snapshots = setup(this::answer).collect(run, request, () -> true);
        assertEquals(1, snapshots.size());
        var snapshot = snapshots.get(0);
        var rules = snapshot.sections().stream().flatMap(s -> s.rules().stream()).toList();
        assertEquals(List.of("r1", "r3", "r2", "n1"), rules.stream().map(PolicySnapshot.Rule::uuid).toList());
        assertEquals("Accept", rules.get(0).action());
        assertEquals("address", snapshot.objects().get(rules.get(0).source().refs().get(0)).type());
        assertEquals("hide", rules.get(3).action());
        assertEquals("OBJ-ADDRESS-02", snapshot.objects().get(rules.get(3).extras().get("translated-source").get(0)).name());
        assertEquals("device-1", snapshot.metadata().targets().get(0).deviceId());
        verify(transport, times(1)).connect(any(), any(), eq(Duration.ofSeconds(30)));
        verify(transport, times(6)).execInteractive(eq(session), any(), eq(Duration.ofSeconds(300)));
        verify(transport).disconnect(session);
        verify(transport, never()).exec(any(), any(), any());
    }
    @Test void unregisteredInstallTargetRetainsManagementAssignmentWithoutDeviceJoin() {
        var collector = setup(this::answer);
        when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of());
        var target = collector.collect(run, request, () -> true).get(0).metadata().targets().get(0);
        assertEquals(PolicySnapshot.ref("cp-install-target", "mds-1", "domain-01", "target-01"), target.deviceId());
        assertEquals("UNKNOWN", target.syncStatus());
    }
    @Test void timedOutNatKeepsCompletedAccessLayers() {
        var collector = setup(c -> c.contains("show-nat-rulebase") ? new ExecResult.TimedOut() : answer(c));
        var snapshot = collector.collect(run, request, () -> true).get(0);
        assertEquals("NAT", snapshot.failures().get(0).layerName());
        assertEquals(List.of("r1", "r3", "r2"), snapshot.sections().stream().flatMap(section -> section.rules().stream()).map(PolicySnapshot.Rule::uuid).toList());
        verify(transport).disconnect(session);
        verify(repository, never()).publish(anyString(), anyLong(), anyList(), anyString());
    }
    @Test void malformedPackagesOrMissingInlineDictionaryFailWithoutExtraLookups() {
        for (String broken : List.of("truncated", "inline")) {
            var collector = setup(command -> {
                if (broken.equals("truncated") && command.contains("show-packages")) return ok("{\"total\":501,\"packages\":[]}");
                if (broken.equals("inline") && command.contains("show-access-rulebase")) return ok(page("layer", 1, 1, 1, RULE, ""));
                return answer(command);
            });
            if (broken.equals("truncated")) assertThrows(IllegalStateException.class, () -> collector.collect(run, request, () -> true));
            else assertFalse(collector.collect(run, request, () -> true).get(0).failures().isEmpty());
        }
    }
    @Test void sixHourSkipAndDomainScopeDoNotIssuePackageReads() {
        var collector = setup(this::answer);
        when(repository.beginDomain(anyString(), anyString(), eq(true))).thenReturn(false);
        assertTrue(collector.collect(run, new PolicyCollectionRepository.Request("mds-1", "", true), () -> true).isEmpty());
        verify(transport, times(1)).execInteractive(any(), any(), any());
        assertThrows(IllegalStateException.class, () -> collector.collect(run,
            new PolicyCollectionRepository.Request("mds-1", "unknown-domain", false), () -> true));
    }
    @Test void pagingRefusesGapsChangingTotalsRepeatedPagesAndLimit() {
        for (String bad : List.of(page("layer", 2, 2, 2, RULE, DICTIONARY), page("layer", 1, 0, 2, RULE, DICTIONARY),
                "{\"total\":1,\"rulebase\":[],\"objects-dictionary\":[]}")) {
            var collector = setup(c -> ok(bad));
            assertThrows(IllegalStateException.class, () -> collector.pages(session, n -> "synthetic-page", 1, Long.MAX_VALUE, () -> true));
        }
        AtomicInteger changed = new AtomicInteger();
        var changing = setup(c -> ok(changed.getAndIncrement() == 0 ? page("layer", 1, 1, 2, RULE, DICTIONARY)
                : page("layer", 2, 2, 3, RULE, DICTIONARY)));
        assertThrows(IllegalStateException.class, () -> changing.pages(session, n -> "synthetic-page", 1, Long.MAX_VALUE, () -> true));
        AtomicInteger count = new AtomicInteger();
        var collector = setup(c -> { int n = count.incrementAndGet(); return ok(page("layer", n, n, 201, RULE, DICTIONARY)); });
        assertThrows(IllegalStateException.class, () -> collector.pages(session, n -> "synthetic-page", 1, Long.MAX_VALUE, () -> true));
        assertEquals(200, count.get());
        assertThrows(IllegalStateException.class, () -> collector.pages(session, n -> "synthetic-page", 1, 0, () -> true));
        assertThrows(IllegalStateException.class, () -> collector.collect(run, request, () -> false));
    }
    @Test void timedOutAccessPageRetriesSameOffsetOnceAtFiftyWithFullDictionary() {
        AtomicInteger firstPage = new AtomicInteger();
        var collector = setup(command -> {
            if (command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0)) && firstPage.getAndIncrement() == 0)
                return new ExecResult.TimedOut();
            return answer(command.contains("show-access-rulebase")
                ? command.replace(" limit 50 offset ", " limit 100 offset ") : command);
        });
        var snapshot = collector.collect(run, request, () -> true).get(0);
        assertTrue(snapshot.failures().isEmpty());
        verify(transport).execInteractive(eq(session), eq(policySpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0, 50))), eq(Duration.ofSeconds(300)));
        assertEquals(1, firstPage.get());
    }
    @Test void twiceTimedOutInlineLayerKeepsParentAndNatAndBadgesIncomplete() {
        var collector = setup(command -> command.contains("name 'Inline'") ? new ExecResult.TimedOut() : answer(command));
        var snapshot = collector.collect(run, request, () -> true).get(0);
        assertEquals(1, snapshot.failures().size());
        assertTrue(snapshot.failures().get(0).reason().endsWith(": TIMEOUT"));
        assertEquals(List.of("r1", "r2", "n1"), snapshot.sections().stream().flatMap(section -> section.rules().stream()).map(PolicySnapshot.Rule::uuid).toList());
        verify(transport).execInteractive(eq(session), eq(policySpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Inline", 0, 50))), eq(Duration.ofSeconds(300)));
        verify(transport).disconnect(session);
    }
    @Test void streamingTimeoutDoesNotReissuePageAtEitherLimit() {
        var collector = setup(command -> command.contains("name 'Inline'")
            ? new ExecResult.TimedOut(true) : answer(command));
        var snapshot = collector.collect(run, request, () -> true).get(0);
        assertEquals(1, snapshot.failures().size());
        assertTrue(snapshot.failures().get(0).reason().endsWith(": STREAMING_TIMEOUT"));
        verify(transport).execInteractive(eq(session), eq(policySpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Inline", 0))), any());
        verify(transport, never()).execInteractive(eq(session), argThat(spec -> spec.command().contains("show-access-rulebase") && spec.command().contains(" limit 50 offset ")), any());
    }
    @Test void malformedMappedInlineLayerDoesNotPreventNatOrSiblingPublication() {
        setup(command -> command.contains("name 'Inline'")
            ? ok(page("child", 1, 1, 1, "{\"uid\":\"r3\",\"type\":\"unsupported-entry\"}", "")) : answer(command));
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), () -> 0L);
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        var snapshot = collector.collect(run, request, () -> true, checkpoints::add).get(0);
        assertEquals(List.of("r1", "r2", "n1"), snapshot.sections().stream()
            .flatMap(section -> section.rules().stream()).map(PolicySnapshot.Rule::uuid).toList());
        assertEquals(1, snapshot.failures().size());
        assertEquals("Inline", snapshot.failures().get(0).layerName());
        assertEquals(2, checkpoints.size());
        verify(transport).execInteractive(eq(session), eq(policySpec(MgmtCliCommands.showNatRulebase("DOM-TANGO-01", "Package", 0))), any());
    }

    @Test void expiredJobBudgetCannotSendOrRetryAnyPage() {
        var collector = setup(this::answer);
        assertThrows(IllegalStateException.class, () -> collector.pages(session,
            offset -> MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", offset), 1, System.nanoTime() - 1, () -> true));
        verify(transport, never()).execInteractive(any(), any(), any());
    }

    @Test void deadlineMidInlineLayerRetainsParentAndStopsBeforeSiblingContact() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        setup(command -> {
            if (command.contains("name 'Inline'")) {
                clock.set(Duration.ofHours(2).toNanos());
                return ok(page("child", 1, 1, 2, "{\"uid\":\"r3\",\"type\":\"access-rule\"}", ""));
            }
            return answer(command);
        });
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), clock::get);
        var progress = new ArrayList<PolicyCollectionTrace.LayerProgress>();
        try (var trace = new PolicyCollectionTrace("run-1", (step, total) -> {}, measurement -> {}, progress::add)) {
            var failure = assertThrows(PolicyCollectionTrace.Failure.class, () -> collector.collect(run, request, () -> true, checkpoint -> {
                checkpoints.add(checkpoint);
                if (checkpoints.size() == 1) verify(transport, never()).execInteractive(eq(session),
                    eq(policySpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Inline", 0))), any());
            }));
            assertTrue(failure.getMessage().endsWith(": JOB_DEADLINE"));
        }
        assertEquals(1, checkpoints.size());
        var snapshot = checkpoints.get(0);
        assertEquals("COLLECTION_PENDING", snapshot.failures().stream().filter(f -> f.layerName().equals("Inline")).findFirst().orElseThrow().reason());
        assertEquals(List.of("r1", "r2"), snapshot.sections().stream().flatMap(section -> section.rules().stream()).map(PolicySnapshot.Rule::uuid).toList());
        assertEquals(2, progress.get(progress.size() - 1).layer());
        assertEquals(2, progress.get(progress.size() - 1).layers());
        assertEquals(2, progress.get(progress.size() - 1).rules());
        verify(transport, never()).execInteractive(eq(session), eq(policySpec(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Inline", 1))), any());
        verify(transport, never()).execInteractive(eq(session), eq(policySpec(MgmtCliCommands.showNatRulebase("DOM-TANGO-01", "Package", 0))), any());
    }
    @Test void noCompletedLayerDoesNotPublishOnDeadline() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        setup(command -> {
            if (command.contains("show-access-rulebase")) {
                clock.set(Duration.ofHours(2).toNanos());
                return ok(page("layer", 1, 1, 2, RULE, DICTIONARY));
            }
            return answer(command);
        });
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), clock::get);
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> collector.collect(run, request, () -> true, checkpoints::add));
        assertTrue(checkpoints.isEmpty());
    }
    @Test void configurableDeadlineClipsPageTimeoutAndRejectsNonpositiveBudget() {
        setup(this::answer);
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofSeconds(120), () -> 0L);
        collector.collect(run, request, () -> true);
        var commands = org.mockito.ArgumentCaptor.forClass(ExecSpec.class);
        verify(transport, times(6)).execInteractive(eq(session), commands.capture(), eq(Duration.ofSeconds(120)));
        assertEquals(6, commands.getAllValues().stream().map(ExecSpec::command).distinct().count());
        assertTrue(commands.getAllValues().stream().allMatch(spec -> spec.streamingExtensionMs() == 0));
        assertThrows(IllegalArgumentException.class, () -> new CheckPointPolicyCollector(transport, gates, repository, Duration.ZERO));
    }

    @Test void laterFailedPackageKeepsPriorPackageAndReportsItsIncompleteLayers() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        setup(command -> {
            if (command.contains("show-packages")) return ok("""
                {"from":1,"to":2,"total":2,"packages":[
                  {"uid":"pkg-01","name":"Package","access-layers":[{"uid":"layer","name":"Layer"}],"installation-targets":[]},
                  {"uid":"pkg-02","name":"Unfinished","access-layers":[{"uid":"other","name":"Unfinished"}],"installation-targets":[]}]}
                """);
            if (command.contains("'Unfinished'")) return new ExecResult.TimedOut();
            if (command.contains("name 'Inline'")) clock.set(CpPolicyParallelCollection.configuredCheckpointInterval().toNanos());
            return answer(command);
        });
        var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), clock::get);
        List<PolicySnapshot> checkpoints = new ArrayList<>();
        var collected = collector.collect(run, request, () -> true, checkpoints::add);
        assertEquals(2, collected.size());
        assertTrue(collected.get(0).failures().isEmpty());
        assertEquals(2, collected.get(1).failures().size());
        assertEquals("Unfinished", collected.get(1).failures().get(0).layerName());
        assertTrue(collected.get(1).failures().stream().allMatch(f -> f.reason().endsWith(": TIMEOUT")));
        assertEquals(4, checkpoints.size());
        assertEquals(collected.get(0), checkpoints.get(2));
        assertEquals(collected.get(1), checkpoints.get(3));
    }

    @Test void malformedRulebasePageRetriesSameCommandOnceAndKeepsMaskedStructureDiagnostics() throws Exception {
        for (int sessions : List.of(1, 4)) for (boolean recover : List.of(true, false)) {
            reset(transport, repository);
            var attempts = new AtomicInteger();
            setup(command -> command.equals(MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0))
                && (attempts.incrementAndGet() == 1 || !recover)
                ? ok("Synthetic banner\n{\"rulebase\":[") : answer(command));
            var collector = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions);
            var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
            try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript)) {
                var snapshot = collector.collect(run, request, () -> true).get(0);
                assertEquals(recover ? 0 : 1, snapshot.failures().size());
                assertFalse(snapshot.sections().stream().filter(section -> section.source().equals("CP NAT rulebase")).toList().isEmpty());
            }
            assertEquals(2, attempts.get());
            verify(transport, times(2)).execInteractive(any(), argThat(spec -> spec.command().equals(
                MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0))), any());
            var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
            String notes = sink.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(notes.contains("STRUCTURE rootParsed=false rootType=UNPARSED"));
            assertTrue(notes.contains("leadingNonJsonLines=1")); assertTrue(notes.contains("endedMidJson=true"));
            assertTrue(notes.contains("bytes=")); assertTrue(notes.contains("exitCode=0")); assertFalse(notes.contains("Synthetic"));
        }
    }

    @Test void rulebaseInvalidPaginationDiagnosticsShowOnlyKeysCountersAndArraySizes() throws Exception {
        setup(this::answer);
        String body = "Synthetic banner\n{\"uid\":\"synthetic-layer\",\"from\":2,\"to\":1,\"total\":3,"
            + "\"rulebase\":[],\"objects-dictionary\":[{\"uid\":\"synthetic-object\"}]}";
        when(transport.execInteractive(any(), any(), any())).thenReturn(ok(body));
        var transcript = new com.securityexpert.nexus.ui2.worker.transcript.JobTranscript();
        try (var scope = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.open(transcript)) {
            assertThrows(PolicyCollectionTrace.Failure.class, () -> new CheckPointPolicyCollector(transport, gates, repository)
                .read(session, MgmtCliCommands.showAccessRulebase("DOM-TANGO-01", "Layer", 0), 1, Long.MAX_VALUE, () -> true));
        }
        var sink = new java.io.ByteArrayOutputStream(); transcript.writeTo(sink);
        String notes = sink.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(notes.contains("rulebase:ARRAY(size=0)")); assertTrue(notes.contains("objects-dictionary:ARRAY(size=1)"));
        assertTrue(notes.contains("counters=[from=2, to=1, total=3]"));
        assertTrue(notes.contains("leadingNonJsonLines=1")); assertTrue(notes.contains("endedMidJson=false"));
        assertFalse(notes.contains("synthetic-layer")); assertFalse(notes.contains("synthetic-object"));
    }

}
