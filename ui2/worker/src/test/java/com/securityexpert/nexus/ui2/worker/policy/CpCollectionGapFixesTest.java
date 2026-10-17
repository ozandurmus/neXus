package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.*;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Live MDS gaps, 2026-10-05..15 (counts from read-only queries; shapes synthetic, Management API v1.8/v1.9 style):
 * 12 object types per daily inventory ended TYPE_INCOMPLETE fetched=0 total=0 on 36-byte empty answers
 * ({"objects":[],"total":0}, no from/to); two hosts listings stopped after 33 and 50 pages while the domains were
 * edited (page rejected by the listing checks); empty rulebases may omit the objects dictionary.
 */
class CpCollectionGapFixesTest {
    /** Pretty-printed exactly as mgmt_cli -f json prints an empty list: 36 bytes. */
    private static final String EMPTY_OBJECTS = "{\n  \"objects\" : [ ],\n  \"total\" : 0\n}";
    private final ObjectMapper json = new ObjectMapper();
    private final GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(
        getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream().filter(r -> r.key().equals(key)).toList();
    private final DeviceTransport transport = mock(DeviceTransport.class);
    private final PolicyCollectionRepository repository = mock(PolicyCollectionRepository.class);
    private final DiscoveryRun run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
        DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final PolicyCollectionRepository.Request request = new PolicyCollectionRepository.Request("mds-1", "", false,
        PolicyCollectionRepository.Mode.FULL, "", 0);
    private final List<CpObjectInventory> stored = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach void fastRestarts() { System.setProperty("ui2.policy.cp.layer-restart-backoff-ms", "0"); }
    @AfterEach void restore() { System.clearProperty("ui2.policy.cp.layer-restart-backoff-ms"); }

    @Test void emptyListsWithoutCountersAreCompleteButMalformedEmptyPagesStayRejected() throws Exception {
        assertEquals(36, EMPTY_OBJECTS.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        for (String empty : List.of(EMPTY_OBJECTS, "{\"objects\":[],\"total\":0,\"from\":0,\"to\":0}", "{\"objects\":[],\"total\":0,\"from\":1,\"to\":0}")) {
            var listing = new CheckPointPolicyCollector.PackagePages(50, "objects");
            assertTrue(listing.add(json.readTree(empty), 0), empty);
            assertTrue(listing.items.isEmpty()); assertEquals(0, listing.total);
        }
        var packages = new CheckPointPolicyCollector.PackagePages(50);
        assertTrue(packages.add(json.readTree("{\"packages\":[],\"total\":0}"), 0));
        for (String bad : List.of("{\"objects\":[],\"total\":0,\"from\":2}", "{\"objects\":[],\"total\":0,\"to\":1}",
                "{\"objects\":[{\"uid\":\"host-1\"}],\"total\":0}", "{\"objects\":[],\"total\":0,\"from\":null}")) {
            var rejected = assertThrows(CheckPointPolicyCollector.ListingRejected.class,
                () -> new CheckPointPolicyCollector.PackagePages(50, "objects").add(json.readTree(bad), 0), bad);
            assertEquals("EMPTY_PAGE_RANGE", rejected.check);
            assertTrue(rejected.getMessage().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"));
        }
        assertThrows(CheckPointPolicyCollector.ListingRejected.class,
            () -> new CheckPointPolicyCollector.PackagePages(50, "objects").add(json.readTree(EMPTY_OBJECTS), 50));
    }

    @Test void listingChecksNameTheRuleAndOnlyConcurrentChangesAreRestartable() throws Exception {
        var listing = new CheckPointPolicyCollector.PackagePages(50, "objects");
        assertFalse(listing.add(json.readTree(hosts(0, 120, 0)), 0));
        assertEquals("TOTAL_CHANGED", assertThrows(CheckPointPolicyCollector.ListingRejected.class,
            () -> listing.add(json.readTree(hosts(50, 121, 0)), 50)).check);
        var duplicate = new CheckPointPolicyCollector.PackagePages(50, "objects");
        duplicate.add(json.readTree(hosts(0, 120, 0)), 0);
        // One object deleted before offset 50: the next page starts one item earlier and repeats the last uid.
        assertEquals("DUPLICATE_UID", assertThrows(CheckPointPolicyCollector.ListingRejected.class,
            () -> duplicate.add(json.readTree(hosts(50, 120, -1)), 50)).check);
        var schema = new CheckPointPolicyCollector.PackagePages(50, "objects");
        assertEquals("PAGE_SCHEMA", assertThrows(CheckPointPolicyCollector.ListingRejected.class,
            () -> schema.add(json.readTree("{\"objects\":{},\"total\":1}"), 0)).check);
        assertEquals(Set.of("TOTAL_CHANGED", "SHORT_PAGE", "DUPLICATE_UID"), CheckPointPolicyCollector.ListingRejected.CONCURRENT);
    }

    @ParameterizedTest @ValueSource(ints = {1, 4})
    void emptyObjectTypesAreResolvedInBothCollectors(int sessions) throws Exception {
        collector(sessions, command -> command.contains("show-hosts") ? ok(hosts(0, 3, 0)) : answer(command), System::nanoTime)
            .collect(run, request, () -> true);
        assertEquals(22, stored.size());
        assertTrue(stored.stream().allMatch(i -> i.status().equals("RESOLVED")), () -> stored.stream()
            .filter(i -> !i.status().equals("RESOLVED")).map(i -> i.type() + ":" + i.reason()).toList().toString());
        assertEquals(3, stored.stream().filter(i -> i.type().equals("hosts")).findFirst().orElseThrow().objects().size());
    }

    @ParameterizedTest @ValueSource(ints = {1, 4})
    void hostsListingThatMovesWhilePagedIsReadAgainFromZeroAndCompletes(int sessions) throws Exception {
        // 160 hosts; one host is added while page 2 is read in the first pass only.
        var firstPass = new AtomicInteger();
        var transcript = new JobTranscript();
        try (var scope = JobTranscriptScope.open(transcript)) {
            collector(sessions, command -> {
                if (!command.contains("show-hosts")) return answer(command);
                int offset = offset(command);
                if (offset == 50 && firstPass.getAndIncrement() == 0) return ok(hosts(50, 161, 0));
                return ok(hosts(offset, 160, 0));
            }, System::nanoTime).collect(run, request, () -> true);
        }
        var hosts = stored.stream().filter(i -> i.type().equals("hosts")).findFirst().orElseThrow();
        assertEquals("RESOLVED", hosts.status());
        assertEquals(160, hosts.objects().size());
        assertEquals(160, hosts.objects().stream().map(CpObjectInventory.Item::uid).distinct().count());
        assertEquals(2 + 4, hosts.pages(), "pages of the discarded pass are counted");
        String text = lines(transcript);
        assertTrue(text.contains("[CP_OBJECT_LISTING_RESTART] type=hosts check=TOTAL_CHANGED attempt=1 fetched=50 total=160 pages=2"), text);
        assertFalse(text.contains("OBJ-HOST-"), "the masked note never carries object names");
    }

    @ParameterizedTest @ValueSource(ints = {1, 4})
    void restartsAreBoundedAndTheGapNamesItsCheck(int sessions) throws Exception {
        var total = new AtomicInteger(160);
        collector(sessions, command -> {
            if (!command.contains("show-hosts")) return answer(command);
            int offset = offset(command);
            // The listing grows on every second page read: it never holds still for a full pass.
            return ok(hosts(offset, offset == 0 ? total.get() : total.incrementAndGet(), 0));
        }, System::nanoTime).collect(run, request, () -> true);
        var hosts = stored.stream().filter(i -> i.type().equals("hosts")).findFirst().orElseThrow();
        assertEquals("TYPE_INCOMPLETE", hosts.status());
        assertTrue(hosts.reason().contains("check=TOTAL_CHANGED restarts=2 "), hosts.reason());
        assertTrue(hosts.reason().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"), hosts.reason());
        verify(transport, times(3)).execInteractive(any(), argThat(spec -> spec.command().contains("show-hosts")
            && spec.command().contains("offset '0'")), any());
    }

    @Test void noRestartWhenAFullPassCannotFinishBeforeTheDeadline() throws Exception {
        long[] now = {0};
        var listing = new CheckPointPolicyCollector.PackagePages(50, "objects").begin(0);
        listing.add(json.readTree(hosts(0, 28_413, 0)), 0);
        now[0] = Duration.ofSeconds(8).toNanos();
        var rejected = assertThrows(CheckPointPolicyCollector.ListingRejected.class,
            () -> listing.add(json.readTree(hosts(50, 28_414, 0)), 50));
        var collector = collector(1, this::answer, () -> now[0]);
        // 569 pages at ~4 s per page do not fit into the 10 minutes left.
        assertFalse(collector.restartListing("hosts", listing, rejected, Duration.ofMinutes(10).toNanos(), () -> true));
        assertEquals(0, listing.restarts);
        assertTrue(collector.restartListing("hosts", listing, rejected, Duration.ofHours(2).toNanos(), () -> true));
        assertEquals(1, listing.restarts); assertTrue(listing.items.isEmpty()); assertEquals(2, listing.pagesRead());
        var schema = new CheckPointPolicyCollector.ListingRejected("PAGE_TO");
        assertFalse(collector.restartListing("hosts", listing, schema, Duration.ofHours(2).toNanos(), () -> true));
    }

    @ParameterizedTest @ValueSource(ints = {1, 4})
    void emptyNatRulebaseWithoutDictionaryIsCompleteInBothCollectors(int sessions) throws Exception {
        var snapshots = collector(sessions, command -> command.contains("show-nat-rulebase")
            ? ok("{\n  \"uid\" : \"nat-layer-1\",\n  \"rulebase\" : [ ],\n  \"total\" : 0\n}") : answer(command), System::nanoTime)
            .collect(run, request, () -> true);
        assertTrue(snapshots.get(0).failures().isEmpty(), snapshots.get(0).failures().toString());
        assertTrue(snapshots.get(0).sections().stream().anyMatch(s -> s.source().equals("CP NAT rulebase") && s.rules().isEmpty()));
        assertEquals(0, CheckPointPolicyCollector.pageTotal(json.readTree("{\"rulebase\":[],\"total\":0}"), 0, -1, 2));
        assertThrows(PolicyCollectionTrace.Failure.class,
            () -> CheckPointPolicyCollector.pageTotal(json.readTree("{\"rulebase\":[],\"total\":0,\"objects-dictionary\":null}"), 0, -1, 2));
        assertThrows(PolicyCollectionTrace.Failure.class,
            () -> CheckPointPolicyCollector.pageTotal(json.readTree("{\"rulebase\":[{\"uid\":\"r\",\"type\":\"nat-rule\"}],\"total\":1,\"from\":1,\"to\":1}"), 0, -1, 2));
    }

    @ParameterizedTest @ValueSource(ints = {1, 4})
    void vendorErrorAnswersOnNatAndObjectReadsAreNamedApartFromMalformedPages(int sessions) throws Exception {
        String error = "{\n  \"code\" : \"generic_err_synthetic\",\n  \"message\" : \"Synthetic-private package OBJ-POLICY-01\"\n}";
        var snapshots = collector(sessions, command -> command.contains("show-nat-rulebase") || command.contains("show-networks")
            ? ok(error) : answer(command), System::nanoTime).collect(run, request, () -> true, snapshot -> {}, failure -> {});
        var nat = snapshots.get(0).failures().stream().filter(f -> "NAT".equals(f.layerName())).findFirst().orElseThrow();
        assertTrue(nat.reason().endsWith(": API_RESPONSE_ERROR"), nat.reason());
        assertFalse(nat.reason().contains("Synthetic-private"));
        var networks = stored.stream().filter(i -> i.type().equals("networks")).findFirst().orElseThrow();
        assertEquals("TYPE_INCOMPLETE", networks.status());
        assertTrue(networks.reason().endsWith(": API_RESPONSE_ERROR"), networks.reason());
        // A malformed NAT page stays a malformed page.
        stored.clear(); reset(transport);
        var malformed = collector(sessions, command -> command.contains("show-nat-rulebase")
            ? ok("{\"rulebase\":{},\"total\":0}") : answer(command), System::nanoTime).collect(run, request, () -> true, snapshot -> {}, failure -> {});
        assertTrue(malformed.get(0).failures().stream().anyMatch(f -> f.reason().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE")));
    }

    private CheckPointPolicyCollector collector(int sessions, Function<String, ExecResult> answers, java.util.function.LongSupplier clock) {
        var sequence = new AtomicInteger();
        when(transport.connect(any(), any(), any())).thenAnswer(call -> {
            String id = "synthetic-session-" + sequence.incrementAndGet();
            TransportSession session = () -> id;
            return new ConnectResult.Authenticated(session);
        });
        when(transport.execInteractive(any(), any(), any())).thenAnswer(call -> answers.apply(((ExecSpec) call.getArgument(1)).command()));
        when(repository.beginDomain(any(PolicyCollectionRepository.Request.class), anyString())).thenReturn(true);
        when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of());
        doAnswer(call -> { stored.add(json.readValue(call.getArgument(4, String.class), CpObjectInventory.class)); return null; })
            .when(repository).saveInventory(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        return new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions, clock);
    }
    private static ExecResult ok(String body) { return new ExecResult.Completed(body, 0); }
    private static int offset(String command) {
        var matcher = java.util.regex.Pattern.compile(" offset '([0-9]+)' ").matcher(command);
        if (!matcher.find()) throw new AssertionError("no offset");
        return Integer.parseInt(matcher.group(1));
    }
    private ExecResult answer(String command) {
        if (command.contains("show-domains")) return ok("{\"total\":1,\"objects\":[{\"uid\":\"domain-1\",\"name\":\"DOM-TANGO-01\"}]}");
        if (command.contains("show-packages")) return ok("""
            {"from":1,"to":1,"total":1,"packages":[{"uid":"pkg-1","name":"OBJ-POLICY-01",
             "access-layers":[{"uid":"layer-1","name":"OBJ-LAYER-01"}],"installation-targets":[]}]}
            """);
        if (command.contains("show-access-rulebase"))
            return ok("{\"uid\":\"layer-1\",\"name\":\"OBJ-LAYER-01\",\"from\":1,\"to\":1,\"total\":1,\"objects-dictionary\":[],"
                + "\"rulebase\":[{\"uid\":\"rule-1\",\"type\":\"access-rule\"}]}");
        if (command.contains("show-nat-rulebase")) return ok("{\"total\":0,\"rulebase\":[],\"objects-dictionary\":[]}");
        if (command.contains("show-last-published-session")) return ok("{}");
        return ok(EMPTY_OBJECTS);
    }
    /** Hosts page at offset of a listing with the given total; shift moves the page window (an earlier deletion). */
    private static String hosts(int offset, int total, int shift) {
        int start = offset + shift, end = Math.min(offset + 50, total);
        List<String> items = new ArrayList<>();
        for (int i = start; i < start + (end - offset); i++)
            items.add("{\"uid\":\"host-" + i + "\",\"name\":\"OBJ-HOST-" + i + "\",\"type\":\"host\",\"ipv4-address\":\"192.0.2." + (i % 250 + 1) + "\"}");
        return "{\"from\":" + (offset + 1) + ",\"to\":" + end + ",\"total\":" + total + ",\"objects\":[" + String.join(",", items) + "]}";
    }
    private static String lines(JobTranscript transcript) throws java.io.IOException {
        var out = new java.io.ByteArrayOutputStream();
        transcript.writeTo(out);
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
