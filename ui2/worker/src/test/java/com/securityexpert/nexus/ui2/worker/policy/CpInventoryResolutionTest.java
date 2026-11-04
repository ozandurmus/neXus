package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.*;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Group members (and other references) that the rulebase objects-dictionary did not carry are resolved by exact uid from
 * the stored object inventory of the same domain, then the MDS Global domain; stale, incomplete, foreign or unreadable
 * inventories leave them UNRESOLVED. Synthetic values only.
 */
class CpInventoryResolutionTest {
    private static final String SOURCE = "mds-1";
    private static final String DOMAIN = ref(SOURCE, "domain-uid-01");
    private static final String GLOBAL = ref(SOURCE, "domain-uid-global");
    private static final Instant COLLECTED = Instant.parse("2026-11-04T12:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final PolicyCollectionRepository repository = mock(PolicyCollectionRepository.class);
    /** Stored inventories by domain: type -> serialized document, with its manifest time. */
    private final Map<String, Map<String, Stored>> inventories = new HashMap<>();
    private record Stored(Instant collectedAt, String document) {}

    CpInventoryResolutionTest() {
        doAnswer(call -> {
            String domain = call.getArgument(1);
            Consumer<PolicyCollectionRepository.InventoryDocument> consume = call.getArgument(2);
            new TreeMap<>(inventories.getOrDefault(domain, Map.of())).forEach((type, stored) -> consume.accept(
                new PolicyCollectionRepository.InventoryDocument(type, stored.collectedAt(), new StringReader(stored.document()))));
            return null;
        }).when(repository).readInventories(eq(SOURCE), anyString(), any());
    }

    private Metadata metadata(String domain, Instant collectedAt) {
        return new Metadata("policy-1", SOURCE, "MDS " + SOURCE, "CP", domain, "DOM-TANGO-01", "OBJ-POLICY-01",
            collectedAt.toString(), "", List.of());
    }
    private static CpObjectInventory.Item item(String uid, String name, String type, List<String> members, String... values) {
        return new CpObjectInventory.Item(ref(SOURCE, DOMAIN, "object", uid), uid, name, type, members, List.of(values), null, List.of());
    }
    private void store(String domain, String type, Instant at, String status, CpObjectInventory.Item... items) throws Exception {
        var inventory = new CpObjectInventory(SOURCE, domain, type, at.toString(), status, "", List.of(items), 1, 0.1);
        inventories.computeIfAbsent(domain, k -> new HashMap<>()).put(type, new Stored(at, json.writeValueAsString(inventory)));
    }
    private CpInventoryObjects resolver() { return new CpInventoryObjects(repository, SOURCE, () -> COLLECTED, 1_000); }
    private static String id(String uid) { return ref("policy-1", "object", uid); }

    /** One access layer whose only rule's source is group-01 and destination missing-01; group-01 lists inventory-only members. */
    private JsonNode page() throws Exception {
        return json.readTree("""
            {"uid":"layer-01","name":"Synthetic access","from":1,"to":1,"total":1,
             "objects-dictionary":[
               {"uid":"group-01","type":"group","name":"OBJ-GROUP-01","members":["host-01","net-01","group-02","absent-01","Host-01"]},
               {"uid":"svc-01","type":"service-tcp","name":"OBJ-HTTPS","port":"443"},
               {"uid":"accept-01","type":"RulebaseAction","name":"Accept"}],
             "rulebase":[{"uid":"rule-001","type":"access-rule","rule-number":1,"source":["group-01"],"destination":["range-01"],
               "service":["svc-01"],"action":"accept-01"}]}
            """);
    }
    private void storeDomain(Instant at) throws Exception {
        store(DOMAIN, "hosts", at, "RESOLVED", item("host-01", "FW-TANGO-04", "host", List.of(), "ipv4-address: 192.0.2.10", "subnet-mask: 255.255.255.255"));
        store(DOMAIN, "networks", at, "RESOLVED", item("net-01", "OBJ-NET-01", "network", List.of(), "subnet4: 198.51.100.0", "mask-length4: 24"));
        store(DOMAIN, "groups", at, "RESOLVED", item("group-02", "OBJ-GROUP-02", "group", List.of("group-03")),
            item("group-03", "OBJ-GROUP-03", "group", List.of("host-02")));
        store(DOMAIN, "address-ranges", at, "RESOLVED",
            item("range-01", "OBJ-RANGE-01", "address-range", List.of(), "ipv4-address-first: 192.0.2.20", "ipv4-address-last: 192.0.2.29"),
            item("host-02", "FW-JULIET-06", "host", List.of(), "ipv4-address: 192.0.2.30"));
    }
    private PolicySnapshot map(CpInventoryObjects resolver, Instant collectedAt) throws Exception {
        return new CheckPointPolicyMapper().map(metadata(DOMAIN, collectedAt), List.of(page()), List.of(),
            resolver.forDomain(DOMAIN));
    }

    @Test void groupMembersOnlyInTheInventoryResolveWithValuesAtAnyDepthAndProvenanceIsCounted() throws Exception {
        storeDomain(COLLECTED.minus(Duration.ofHours(20)));
        var snapshot = map(resolver(), COLLECTED);
        var objects = snapshot.objects();
        var host = objects.get(id("host-01"));
        assertEquals("RESOLVED", host.status()); assertEquals("address", host.type()); assertEquals("FW-TANGO-04", host.name());
        // Exactly the mapper's dictionary fields: subnet-mask is an inventory-only field and is not carried.
        assertEquals(List.of("ipv4-address: 192.0.2.10"), host.values());
        assertEquals(List.of("subnet4: 198.51.100.0", "mask-length4: 24"), objects.get(id("net-01")).values());
        // A rule cell missing from the dictionary resolves the same way.
        assertEquals(List.of("ipv4-address-first: 192.0.2.20", "ipv4-address-last: 192.0.2.29"), objects.get(id("range-01")).values());
        // Nested: group-02 -> group-03 -> host-02, all inventory-only.
        assertEquals(List.of(id("group-03")), objects.get(id("group-02")).members());
        assertEquals(List.of(id("host-02")), objects.get(id("group-03")).members());
        assertEquals(List.of("ipv4-address: 192.0.2.30"), objects.get(id("host-02")).values());
        assertEquals("group", objects.get(id("group-02")).type());
        // Neither source: stays UNRESOLVED.
        assertEquals("UNRESOLVED", objects.get(id("absent-01")).status());
        // Opaque identity: a uid differing only in case is a different object.
        assertEquals("UNRESOLVED", objects.get(id("Host-01")).status());
        var provenance = snapshot.metadata().inventoryProvenance();
        assertEquals(6, provenance.resolvedObjects()); // host-01, net-01, group-02, group-03, host-02, range-01
        assertEquals(COLLECTED.minus(Duration.ofHours(20)).toString(), provenance.oldestCollectedAt());
        assertEquals(snapshot.metadata(), snapshot.metadata().withInventoryProvenance(provenance));
    }

    @Test void theOldestInventoryUsedIsReportedAndDictionaryObjectsAreNeverReplaced() throws Exception {
        storeDomain(COLLECTED.minus(Duration.ofHours(2)));
        store(DOMAIN, "networks", COLLECTED.minus(Duration.ofHours(30)), "RESOLVED",
            item("net-01", "OBJ-NET-01", "network", List.of(), "subnet4: 198.51.100.0", "mask-length4: 24"));
        store(DOMAIN, "services-tcp", COLLECTED, "RESOLVED", item("svc-01", "OBJ-OTHER", "service-tcp", List.of(), "port: 8443"));
        var snapshot = map(resolver(), COLLECTED);
        assertEquals(COLLECTED.minus(Duration.ofHours(30)).toString(), snapshot.metadata().inventoryProvenance().oldestCollectedAt());
        assertEquals(List.of("protocol: tcp", "port: 443"), snapshot.objects().get(id("svc-01")).values());
        assertEquals(6, snapshot.metadata().inventoryProvenance().resolvedObjects());
    }

    @Test void anInventoryOlderThanTheBoundRelativeToTheSnapshotLeavesMembersUnresolved() throws Exception {
        storeDomain(COLLECTED.minus(Duration.ofHours(20)));
        // The same inventory is 49 h older than a snapshot taken 29 h later: not used.
        var late = map(new CpInventoryObjects(repository, SOURCE, () -> COLLECTED, 1_000), COLLECTED.plus(Duration.ofHours(29)));
        assertEquals("UNRESOLVED", late.objects().get(id("host-01")).status());
        assertEquals(0, late.metadata().inventoryProvenance().resolvedObjects());
        assertNull(late.metadata().inventoryProvenance().oldestCollectedAt());
        // Exactly at the bound it is still used.
        var bound = map(resolver(), COLLECTED.minus(Duration.ofHours(20)).plus(CheckPointPolicyMapper.INVENTORY_MAX_AGE));
        assertEquals("RESOLVED", bound.objects().get(id("host-01")).status());
        // A stored inventory already beyond the bound when it is read is not decoded at all.
        inventories.clear();
        storeDomain(COLLECTED.minus(Duration.ofHours(49)));
        var stale = map(resolver(), COLLECTED);
        assertTrue(stale.objects().values().stream().filter(o -> !o.id().equals(id("group-01")) && !o.id().equals(id("svc-01"))
            && !o.id().equals(id("accept-01"))).allMatch(o -> o.status().equals("UNRESOLVED")));
    }

    @Test void missingIncompleteForeignOrUnreadableInventoriesFailClosed() throws Exception {
        // Missing: nothing stored for the domain.
        assertEquals("UNRESOLVED", map(resolver(), COLLECTED).objects().get(id("host-01")).status());
        Instant at = COLLECTED.minus(Duration.ofHours(1));
        storeDomain(at);
        // Incomplete listing: the stored partial hosts are not used, the complete networks are.
        store(DOMAIN, "hosts", at, "TYPE_INCOMPLETE", item("host-01", "FW-TANGO-04", "host", List.of(), "ipv4-address: 192.0.2.10"));
        // A document that names another domain is not this domain's inventory.
        var foreign = new CpObjectInventory(SOURCE, GLOBAL, "groups", at.toString(), "RESOLVED", "",
            List.of(item("group-02", "OBJ-GROUP-02", "group", List.of())), 1, 0.1);
        inventories.get(DOMAIN).put("groups", new Stored(at, json.writeValueAsString(foreign)));
        // A chunk set that breaks part-way: nothing read from that type is used.
        String ranges = inventories.get(DOMAIN).get("address-ranges").document();
        inventories.get(DOMAIN).put("address-ranges", new Stored(at, ranges.substring(0, ranges.length() / 2)));
        var objects = map(resolver(), COLLECTED).objects();
        assertEquals("UNRESOLVED", objects.get(id("host-01")).status());
        assertEquals("UNRESOLVED", objects.get(id("group-02")).status());
        assertEquals("UNRESOLVED", objects.get(id("range-01")).status());
        assertEquals("RESOLVED", objects.get(id("net-01")).status());
    }

    @Test void aChunkReaderThatThrowsAndAnUnavailableDatabaseFailClosed() throws Exception {
        storeDomain(COLLECTED.minus(Duration.ofHours(1)));
        var broken = mock(PolicyCollectionRepository.class);
        doAnswer(call -> {
            Consumer<PolicyCollectionRepository.InventoryDocument> consume = call.getArgument(2);
            consume.accept(new PolicyCollectionRepository.InventoryDocument("hosts", COLLECTED, new Reader() {
                boolean first = true;
                @Override public int read(char[] buffer, int offset, int length) {
                    if (!first) throw new IllegalStateException("POLICY_CHUNK_MISSING");
                    first = false;
                    String start = inventories.get(DOMAIN).get("hosts").document().substring(0, 60);
                    start.getChars(0, start.length(), buffer, offset); return start.length();
                }
                @Override public void close() {}
            }));
            String networks = inventories.get(DOMAIN).get("networks").document();
            consume.accept(new PolicyCollectionRepository.InventoryDocument("networks", COLLECTED, new StringReader(networks)));
            return null;
        }).when(broken).readInventories(anyString(), anyString(), any());
        var objects = new CheckPointPolicyMapper().map(metadata(DOMAIN, COLLECTED), List.of(page()), List.of(),
            new CpInventoryObjects(broken, SOURCE, () -> COLLECTED, 1_000).forDomain(DOMAIN)).objects();
        assertEquals("UNRESOLVED", objects.get(id("host-01")).status());
        assertEquals("RESOLVED", objects.get(id("net-01")).status());
        var down = mock(PolicyCollectionRepository.class);
        doThrow(new IllegalStateException("synthetic database failure")).when(down).readInventories(anyString(), anyString(), any());
        var unavailable = new CheckPointPolicyMapper().map(metadata(DOMAIN, COLLECTED), List.of(page()), List.of(),
            new CpInventoryObjects(down, SOURCE, () -> COLLECTED, 1_000).forDomain(DOMAIN));
        assertEquals("UNRESOLVED", unavailable.objects().get(id("net-01")).status());
        assertEquals(0, unavailable.metadata().inventoryProvenance().resolvedObjects());
    }

    @Test void aCustomerDomainFallsBackToTheGlobalDomainInventoryOnlyWhenTheListingNamesOne() throws Exception {
        Instant at = COLLECTED.minus(Duration.ofHours(3));
        store(DOMAIN, "hosts", at, "RESOLVED", item("host-01", "FW-TANGO-04", "host", List.of(), "ipv4-address: 192.0.2.10"));
        store(GLOBAL, "networks", at, "RESOLVED", item("net-01", "OBJ-GLOBAL-NET", "network", List.of(), "subnet4: 198.51.100.0", "mask-length4: 25"));
        store(GLOBAL, "hosts", at, "RESOLVED", item("host-01", "OBJ-GLOBAL-HOST", "host", List.of(), "ipv4-address: 192.0.2.99"));
        var withoutGlobal = map(resolver(), COLLECTED).objects();
        assertEquals("UNRESOLVED", withoutGlobal.get(id("net-01")).status());
        var resolver = resolver();
        resolver.domains(json.readTree("""
            {"total":2,"objects":[{"uid":"domain-uid-01","name":"DOM-TANGO-01"},{"uid":"domain-uid-global","name":"Global"}]}
            """));
        var objects = map(resolver, COLLECTED).objects();
        assertEquals(List.of("subnet4: 198.51.100.0", "mask-length4: 25"), objects.get(id("net-01")).values());
        // The policy's own domain wins over Global.
        assertEquals(List.of("ipv4-address: 192.0.2.10"), objects.get(id("host-01")).values());
        // A policy of the Global domain itself reads only the Global inventory and has no further fallback.
        assertEquals("OBJ-GLOBAL-HOST", resolver.find(GLOBAL, "host-01").node().path("name").asText());
        assertNull(resolver.find(GLOBAL, "absent-01"));
        // Two domains that both look like Global name no fallback at all.
        var ambiguous = resolver();
        ambiguous.domains(json.readTree("""
            {"total":2,"objects":[{"uid":"domain-uid-global","name":"Global"},{"uid":"domain-uid-02","name":"GLOBAL"}]}
            """));
        assertNull(ambiguous.find(DOMAIN, "net-01"));
    }

    @Test void typesOutsideTheClosedListStayUnsupportedAndTimeObjectsKeepTheirParsedSchedule() throws Exception {
        Instant at = COLLECTED.minus(Duration.ofHours(1));
        var schedule = new PolicySchedule("one-time", null, null, List.of(), "UTC", false);
        store(DOMAIN, "groups-with-exclusion", at, "RESOLVED", item("host-01", "OBJ-EXCL-01", "group-with-exclusion", List.of(), "include: net-01"));
        store(DOMAIN, "dynamic-objects", at, "RESOLVED", item("net-01", "OBJ-FUTURE", "CpmiSyntheticFuture", List.of()));
        store(DOMAIN, "times", at, "RESOLVED", new CpObjectInventory.Item("x", "range-01", "OBJ-TIME-01", "time", List.of(),
            List.of("start-now: true"), schedule, List.of()));
        var objects = map(resolver(), COLLECTED).objects();
        assertEquals("UNSUPPORTED", objects.get(id("host-01")).status());
        assertEquals(List.of(), objects.get(id("host-01")).values());
        assertEquals("UNSUPPORTED", objects.get(id("net-01")).status());
        assertEquals("unresolved", objects.get(id("net-01")).type());
        assertEquals(List.of("native-type: CpmiSyntheticFuture"), objects.get(id("net-01")).values());
        assertEquals("time", objects.get(id("range-01")).type());
        assertEquals(schedule, objects.get(id("range-01")).schedule());
    }

    @Test void aDomainIsReadOnceAndTheRetainedFootprintIsBounded() throws Exception {
        Instant at = COLLECTED.minus(Duration.ofHours(1));
        String other = ref(SOURCE, "domain-uid-02");
        store(DOMAIN, "hosts", at, "RESOLVED", item("host-01", "FW-TANGO-04", "host", List.of(), "ipv4-address: 192.0.2.10"),
            item("host-02", "FW-JULIET-06", "host", List.of(), "ipv4-address: 192.0.2.11"));
        store(other, "hosts", at, "RESOLVED", item("host-03", "FW-BRAVO-02", "host", List.of(), "ipv4-address: 192.0.2.12"),
            item("host-04", "FW-BRAVO-03", "host", List.of(), "ipv4-address: 192.0.2.13"));
        var resolver = new CpInventoryObjects(repository, SOURCE, () -> COLLECTED, 3);
        assertNotNull(resolver.find(DOMAIN, "host-01")); assertNotNull(resolver.find(DOMAIN, "host-02"));
        assertNull(resolver.find(DOMAIN, "host-09"));
        assertEquals(1, resolver.domainReads);
        // A second domain over the bound evicts the first; reading it again is a second read, still correct.
        assertNotNull(resolver.find(other, "host-03"));
        assertEquals(2, resolver.domainReads);
        assertNotNull(resolver.find(DOMAIN, "host-01"));
        assertEquals(3, resolver.domainReads);
        var unbounded = new CpInventoryObjects(repository, SOURCE, () -> COLLECTED, 1_000);
        unbounded.find(DOMAIN, "host-01"); unbounded.find(other, "host-03"); unbounded.find(DOMAIN, "host-02");
        assertEquals(2, unbounded.domainReads);
        verify(repository, never()).readInventories(eq(SOURCE), eq(GLOBAL), any());
    }

    @Test void aDeepInventoryChainStopsAtTheNestingLimitInsteadOfFailingTheMapping() throws Exception {
        List<CpObjectInventory.Item> chain = new ArrayList<>();
        chain.add(item("group-02", "OBJ-CHAIN-00", "group", List.of("chain-01")));
        for (int i = 1; i < 40; i++) chain.add(item("chain-%02d".formatted(i), "OBJ-CHAIN-%02d".formatted(i), "group", List.of("chain-%02d".formatted(i + 1))));
        store(DOMAIN, "groups", COLLECTED, "RESOLVED", chain.toArray(CpObjectInventory.Item[]::new));
        var objects = assertDoesNotThrow(() -> map(resolver(), COLLECTED)).objects();
        assertEquals("RESOLVED", objects.get(id("chain-05")).status());
        // group-01 (dictionary) -> group-02 (depth 1) -> chain-NN at depth NN + 1: inventory is consulted below depth 32.
        assertEquals("RESOLVED", objects.get(id("chain-30")).status());
        assertEquals("UNRESOLVED", objects.get(id("chain-31")).status());
        assertNull(objects.get(id("chain-32")));
    }

    @Test void withoutALookupTheMapperIsUnchangedAndRecordsNoProvenance() throws Exception {
        var snapshot = new CheckPointPolicyMapper().map(metadata(DOMAIN, COLLECTED), List.of(page()));
        assertNull(snapshot.metadata().inventoryProvenance());
        assertEquals("UNRESOLVED", snapshot.objects().get(id("host-01")).status());
        var fixture = json.readTree(fixture("check-point.json"));
        storeDomain(COLLECTED.minus(Duration.ofHours(1)));
        store(DOMAIN, "hosts", COLLECTED.minus(Duration.ofHours(1)), "RESOLVED",
            item("missing-01", "FW-TANGO-04", "host", List.of(), "ipv4-address: 192.0.2.40"));
        var plain = new CheckPointPolicyMapper().map(metadata(DOMAIN, COLLECTED), List.of(fixture.get(0), fixture.get(1)));
        var resolved = new CheckPointPolicyMapper().map(metadata(DOMAIN, COLLECTED), List.of(fixture.get(0), fixture.get(1)), List.of(),
            resolver().forDomain(DOMAIN));
        assertEquals("UNRESOLVED", plain.objects().get(id("missing-01")).status());
        assertEquals(List.of("ipv4-address: 192.0.2.40"), resolved.objects().get(id("missing-01")).values());
        assertEquals(plain.sections(), resolved.sections());
        assertEquals(1, resolved.metadata().inventoryProvenance().resolvedObjects());
    }

    // ---- Both collection paths read the stored inventory and publish resolved snapshots with provenance. ----

    private final GateRegistryPort gates = key -> GateRegistryFixtureLoader.loadFromStream(
        getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream().filter(r -> r.key().equals(key)).toList();
    private final DeviceTransport transport = mock(DeviceTransport.class);

    @ParameterizedTest @ValueSource(ints = {1, 4})
    void collectedSnapshotsAreStoredResolved(int sessions) throws Exception {
        String container = ref(SOURCE, "domain-1");
        Instant at = Instant.now().minus(Duration.ofHours(5));
        store(container, "hosts", at, "RESOLVED", item("host-01", "FW-TANGO-04", "host", List.of(), "ipv4-address: 192.0.2.10"));
        when(transport.connect(any(), any(), any())).thenAnswer(call -> new ConnectResult.Authenticated(() -> "synthetic-session"));
        when(transport.execInteractive(any(), any(), any())).thenAnswer(call -> {
            String command = ((ExecSpec) call.getArgument(1)).command();
            if (command.contains("show-domains")) return ok("{\"total\":1,\"objects\":[{\"uid\":\"domain-1\",\"name\":\"DOM-TANGO-01\"}]}");
            if (command.contains("show-packages")) return ok("""
                {"from":1,"to":1,"total":1,"packages":[{"uid":"pkg-1","name":"OBJ-POLICY-01",
                 "access-layers":[{"uid":"layer-1","name":"OBJ-LAYER-01"}],"installation-targets":[]}]}
                """);
            if (command.contains("show-access-rulebase")) return ok("""
                {"uid":"layer-1","name":"OBJ-LAYER-01","from":1,"to":1,"total":1,
                 "objects-dictionary":[{"uid":"group-01","type":"group","name":"OBJ-GROUP-01","members":["host-01","absent-01"]}],
                 "rulebase":[{"uid":"rule-1","type":"access-rule","rule-number":1,"source":["group-01"]}]}
                """);
            if (command.contains("show-nat-rulebase")) return ok("{\"total\":0,\"rulebase\":[],\"objects-dictionary\":[]}");
            return ok("{\"from\":0,\"to\":0,\"total\":0,\"objects\":[]}");
        });
        when(repository.beginDomain(any(PolicyCollectionRepository.Request.class), anyString())).thenReturn(true);
        when(repository.targets(anyString(), anyString(), anyString())).thenReturn(List.of());
        var run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
            DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var request = new PolicyCollectionRepository.Request(SOURCE, "", false, PolicyCollectionRepository.Mode.FULL, "", 0);
        var snapshots = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions, System::nanoTime)
            .collect(run, request, () -> true);
        var snapshot = snapshots.get(0);
        String policy = snapshot.metadata().id();
        var host = snapshot.objects().get(ref(policy, "object", "host-01"));
        assertEquals("RESOLVED", host.status()); assertEquals(List.of("ipv4-address: 192.0.2.10"), host.values());
        assertEquals("UNRESOLVED", snapshot.objects().get(ref(policy, "object", "absent-01")).status());
        assertEquals(new InventoryProvenance(1, at.toString()), snapshot.metadata().inventoryProvenance());
        // The provenance survives the stored JSON round trip, and older stored metadata without it still reads.
        var stored = json.readValue(json.writeValueAsString(snapshot), PolicySnapshot.class);
        assertEquals(snapshot.metadata(), stored.metadata());
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(snapshot.metadata());
        legacy.remove("inventoryProvenance");
        assertNull(json.treeToValue(legacy, Metadata.class).inventoryProvenance());
        verify(repository, times(1)).readInventories(eq(SOURCE), eq(container), any());
        try {
            System.setProperty("ui2.policy.cp.resolve-from-inventory", "false");
            var off = new CheckPointPolicyCollector(transport, gates, repository, Duration.ofHours(2), sessions, System::nanoTime)
                .collect(run, request, () -> true).get(0);
            assertEquals("UNRESOLVED", off.objects().get(ref(policy, "object", "host-01")).status());
            assertNull(off.metadata().inventoryProvenance());
        } finally { System.clearProperty("ui2.policy.cp.resolve-from-inventory"); }
    }
    private static ExecResult ok(String body) { return new ExecResult.Completed(body, 0); }
    private String fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/policy/" + name)) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
