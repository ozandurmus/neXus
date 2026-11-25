package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceReadRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentity;
import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentityRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The fleet hygiene summary: counts per container from stored snapshots, one container per pass, unchanged snapshots skipped. Synthetic data only. */
class PolicyHygieneFleetSummaryTest {
    private static Metadata meta(String id, String container, String collectedAt) {
        return new Metadata(id, "s1", "MGR-BRAVO-01", "PAN", container, "DOM-" + container, "POL-" + id, collectedAt, "artefact-" + collectedAt, List.of());
    }
    private static PolicySnapshot snapshot(Metadata meta, List<Rule> rules) {
        return new PolicySnapshot(meta, List.of(new Section("s1", "Pre rules", "Shared", null, rules)), PolicyHygieneTest.objects());
    }
    private final PolicyQueryService query = spy(new PolicyQueryService(mock(TransactionBoundary.class), new ObjectMapper(), new TopologyNamePseudonymizer(new byte[32])));

    @Test void summarisesEveryContainerAndTotalsWithUnknownSeparate() {
        var a = meta("p1", "c1", "2026-11-01T10:00:00Z"); var b = meta("p2", "c2", "2026-11-02T10:00:00Z");
        var disabled = PolicyHygieneTest.rule("r1", "net", "port", "allow", false);
        var live = PolicyHygieneTest.rule("r2", "host", "port", "allow", true);
        doReturn(List.of(a, b)).when(query).catalog();
        doReturn(Optional.of(snapshot(a, List.of(disabled, live)))).when(query).find("p1");
        doReturn(Optional.of(snapshot(b, List.of(live)))).when(query).find("p2");
        var summary = new PolicyHygieneFleetSummary(query);

        assertEquals(2, summary.view().get("pending"), "nothing is shown as clean before it is analysed");
        assertTrue(summary.refreshNext()); // one container per pass
        assertEquals(1, summary.view().get("pending"));
        assertTrue(summary.refreshNext());
        assertFalse(summary.refreshNext(), "every container is current");

        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) summary.view().get("containers");
        @SuppressWarnings("unchecked") var first = (Map<String, Integer>) rows.get(0).get("counts");
        assertEquals("READY", rows.get(0).get("state"));
        assertEquals("2026-11-01T10:00:00Z", rows.get(0).get("snapshotAt"));
        assertEquals(2, rows.get(0).get("rules"));
        assertEquals(1, first.get("disabled"));
        assertEquals(List.of("shadowed", "conflict", "disabled", "unused", "expired", "broad", "unknown"), List.copyOf(first.keySet()));
        @SuppressWarnings("unchecked") var totals = (Map<String, Integer>) summary.view().get("totals");
        assertEquals(1, totals.get("disabled"));
        assertEquals(3, summary.view().get("rules"));
        assertEquals(0, summary.view().get("pending"));
    }

    @Test void unchangedSnapshotsAreSkippedAndANewSnapshotIsReanalysed() {
        var v1 = meta("p1", "c1", "2026-11-01T10:00:00Z");
        var rule = PolicyHygieneTest.rule("r1", "net", "port", "allow", false);
        doReturn(List.of(v1)).when(query).catalog();
        doReturn(Optional.of(snapshot(v1, List.of(rule)))).when(query).find(anyString());
        var summary = new PolicyHygieneFleetSummary(query);
        assertTrue(summary.refreshNext());
        assertFalse(summary.refreshNext());
        assertFalse(summary.refreshNext());
        verify(query, times(1)).hygieneReport(any(), anyInt());

        var v2 = meta("p1", "c1", "2026-11-03T10:00:00Z");
        doReturn(List.of(v2)).when(query).catalog();
        doReturn(Optional.of(snapshot(v2, List.of(rule, PolicyHygieneTest.rule("r2", "host", "port", "allow", false))))).when(query).find(anyString());
        assertEquals(1, summary.view().get("pending"), "a new snapshot is not served with the old counts");
        assertTrue(summary.refreshNext());
        verify(query, times(2)).hygieneReport(any(), anyInt());
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) summary.view().get("containers");
        assertEquals("2026-11-03T10:00:00Z", rows.get(0).get("snapshotAt"));
        assertEquals(2, rows.get(0).get("rules"));
    }

    @Test void aFailedContainerStaysPendingAndNeverShowsZeros() {
        var a = meta("p1", "c1", "2026-11-01T10:00:00Z");
        doReturn(List.of(a)).when(query).catalog();
        doReturn(Optional.empty()).when(query).find("p1");
        var summary = new PolicyHygieneFleetSummary(query);
        assertTrue(summary.refreshNext());
        var row = ((List<?>) summary.view().get("containers")).get(0);
        assertEquals("PENDING", ((Map<?, ?>) row).get("state"));
        assertFalse(((Map<?, ?>) row).containsKey("counts"));
    }

    @SuppressWarnings("unchecked") private static Map<String, Integer> ints(Object value) { return (Map<String, Integer>) value; }

    @Test void unknownReasonsAndBlockerReasonsAreCountedPerContainerAndInTotalsFromOneAnalysis() {
        // r1 carries a constraint the engine cannot read (UNKNOWN for its own reason); r2 is behind it and ends UNKNOWN "earlier rule has unsupported constraints" with r1 as blocker.
        var blocker = PolicyHygieneTest.edit(PolicyHygieneTest.rule("r1", "net", "port", "allow", true), PolicyHygieneTest.cell("net"), Map.of("unexpected-field", List.of("yes")));
        var behind = PolicyHygieneTest.rule("r2", "host", "port", "allow", true);
        var a = meta("p1", "c1", "2026-11-01T10:00:00Z"); var b = meta("p2", "c2", "2026-11-02T10:00:00Z"); var c = meta("p3", "c3", "2026-11-03T10:00:00Z");
        doReturn(List.of(a, b, c)).when(query).catalog();
        doReturn(Optional.of(snapshot(a, List.of(blocker, behind)))).when(query).find("p1");
        doReturn(Optional.of(snapshot(b, List.of(blocker, behind)))).when(query).find("p2");
        doReturn(Optional.empty()).when(query).find("p3"); // never analysed: stays PENDING
        var summary = new PolicyHygieneFleetSummary(query);
        for (int pass = 0; pass < 3; pass++) assertTrue(summary.refreshNext()); // one container per pass; the unreadable one is retried, so never loop until false
        verify(query, times(2)).hygieneReport(any(), anyInt()); // one analysis per snapshot, not two

        var expected = PolicyHygiene.analyze(snapshot(a, List.of(blocker, behind)), PolicyHygieneFleetSummary.DAYS, java.time.Instant.now(), PolicyHygiene.TOTAL_WORK).rules();
        assertEquals("UNKNOWN", expected.get("r1").shadowStatus());
        assertEquals(PolicyHygiene.EARLIER_UNSUPPORTED, expected.get("r2").shadowReason());
        assertEquals("r1", expected.get("r2").blockingRuleId());
        String blockerReason = expected.get("r1").shadowReason();
        assertNotEquals(PolicyHygiene.EARLIER_UNSUPPORTED, blockerReason);

        var view = summary.view();
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) view.get("containers");
        for (var row : rows.subList(0, 2)) {
            assertEquals("READY", row.get("state"));
            assertEquals(Map.of(blockerReason, 1, PolicyHygiene.EARLIER_UNSUPPORTED, 1), ints(row.get("unknownReasons")));
            assertEquals(Map.of(blockerReason, 1), ints(row.get("blockerReasons")), "the rule behind a blocker is counted under the blocker's own reason");
        }
        assertEquals("PENDING", rows.get(2).get("state"));
        assertFalse(rows.get(2).containsKey("unknownReasons"), "a pending container carries no maps, never zeros");
        assertFalse(rows.get(2).containsKey("blockerReasons"));
        assertEquals(1, view.get("pending"));
        assertEquals(Map.of(blockerReason, 2, PolicyHygiene.EARLIER_UNSUPPORTED, 2), ints(view.get("unknownReasons")), "totals cover READY containers only");
        assertEquals(Map.of(blockerReason, 2), ints(view.get("blockerReasons")));

        // Reasons are the engine's fixed literals: never a rule id, rule name, object name or uuid of the fixture.
        var keys = new TreeSet<>(ints(view.get("unknownReasons")).keySet()); keys.addAll(ints(view.get("blockerReasons")).keySet());
        for (var key : keys) {
            assertTrue(PolicyHygiene.REASONS.contains(key) || key.equals(PolicyQueryService.NO_REASON), key);
            for (String fixture : List.of("r1", "r2", "OBJ-", "uuid-", "POL-", "DOM-", "MGR-")) assertFalse(key.contains(fixture), key);
        }
    }

    @Test void reasonsAreRankedByCountThenName() {
        var sorted = new PolicyHygieneFleetSummary(query);
        var entries = new LinkedHashMap<String, Integer>();
        entries.put("b reason", 2); entries.put("a reason", 2); entries.put("z reason", 5);
        var a = meta("p1", "c1", "2026-11-01T10:00:00Z");
        doReturn(List.of(a)).when(query).catalog();
        doReturn(Optional.of(snapshot(a, List.of(PolicyHygieneTest.rule("r1", "net", "port", "allow", true))))).when(query).find("p1");
        doReturn(new PolicyQueryService.HygieneReport(new TreeMap<>(), entries, new TreeMap<>())).when(query).hygieneReport(any(), anyInt());
        sorted.refreshNext();
        assertEquals(List.of("z reason", "a reason", "b reason"), List.copyOf(ints(sorted.view().get("unknownReasons")).keySet()));
    }

    private static DeviceSummaryRecord pairDevice(String id, String vendorHint, String host, String cluster) {
        return new DeviceSummaryRecord(id, "gateway", vendorHint, DeviceEnrollmentState.ENROLLED, Optional.of(host),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(cluster));
    }

    private static PolicySnapshot mappedAsa(String deviceId, String host, String collectedAt, String extraAcl) {
        String config = """
            ASA Version 9.18(4)22
            interface GigabitEthernet0/0
             nameif outside
             security-level 0
            interface GigabitEthernet0/1
             nameif inside
             security-level 100
            time-range TR-MAINT
             periodic daily 01:00 to 02:00
            access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 443 inactive
            access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 8443 time-range TR-MAINT
            access-list ACL-OUT extended deny tcp host 192.0.2.10 host 198.51.100.20 eq 8443
            """ + extraAcl + """
            access-group ACL-OUT in interface outside
            """;
        return AsaPolicyMapper.map(deviceId, host, collectedAt,
            PolicySnapshot.ref("configuration-artefact", "artefact-" + deviceId), AsaConfigPolicyParser.parse(config));
    }

    private static PolicySnapshot mappedFgt(String deviceId, String host, String collectedAt, String action) {
        String config = """
            #config-version=FGVM64-7.4.8-FW-build2795-250521:opmode=0:vdom=0:user=admin
            config system interface
                edit "port1"
                    set vdom "root"
                    set ip 192.0.2.1 255.255.255.0
                next
                edit "port2"
                    set vdom "root"
                    set ip 198.51.100.1 255.255.255.0
                next
            end
            config firewall address
                edit "all"
                next
                edit "HOST-WEB"
                    set subnet 198.51.100.20 255.255.255.255
                next
            end
            config firewall service custom
                edit "HTTPS"
                    set tcp-portrange 443
                next
            end
            config firewall policy
                edit 1
                    set srcintf "port1"
                    set dstintf "port2"
                    set srcaddr "all"
                    set dstaddr "HOST-WEB"
                    set service "HTTPS"
                    set schedule "always"
                    set status disable
                    set action %s
                next
            end
            """.formatted(action);
        return FortiGatePolicyMapper.map(deviceId, host, collectedAt,
            PolicySnapshot.ref("configuration-artefact", "artefact-" + deviceId), FortiGateConfigPolicyParser.parse(config)).get(0);
    }

    @Test void sameAsaAndFortiGateHaPairsCountOnceAndReappearImmediatelyWhenDivergent() {
        var local = mock(LocalFirewallPolicyService.class);
        var devices = mock(DeviceReadRepository.class);
        var identities = mock(HaPairIdentityRepository.class);
        when(devices.listAll()).thenReturn(List.of(
            pairDevice("dev-tango-04", "cisco_asa", "FW-TANGO-04", "cluster-asa"),
            pairDevice("dev-tango-05", "cisco_asa", "FW-TANGO-05", "cluster-asa"),
            pairDevice("dev-romeo-01", "fortinet", "FW-ROMEO-01", "cluster-fgt"),
            pairDevice("dev-romeo-02", "fortinet", "FW-ROMEO-02", "cluster-fgt")));
        when(identities.find("cluster-asa")).thenReturn(Optional.of(new HaPairIdentity("cluster-asa", "cisco_asa", "S1", "S2", HaPairIdentity.State.MATCH)));
        when(identities.find("cluster-fgt")).thenReturn(Optional.of(new HaPairIdentity("cluster-fgt", "fortinet", "S3", "S4", HaPairIdentity.State.MATCH)));

        var asaA = mappedAsa("dev-tango-04", "FW-TANGO-04", "2026-11-20T10:00:00Z", "");
        var asaB = mappedAsa("dev-tango-05", "FW-TANGO-05", "2026-11-20T11:00:00Z", "");
        var fgtA = mappedFgt("dev-romeo-01", "FW-ROMEO-01", "2026-11-20T10:00:00Z", "accept");
        var fgtB = mappedFgt("dev-romeo-02", "FW-ROMEO-02", "2026-11-20T11:00:00Z", "accept");

        when(local.asaSnapshots()).thenReturn(List.of(asaA, asaB));
        when(local.fgtSnapshots()).thenReturn(List.of(fgtA, fgtB));
        doReturn(List.of(asaA.metadata(), asaB.metadata(), fgtA.metadata(), fgtB.metadata())).when(query).catalog();
        for (var snap : List.of(asaA, asaB, fgtA, fgtB)) doReturn(Optional.of(snap)).when(query).find(snap.metadata().id());

        var pairService = new HaPolicyPairService(local, devices, identities, Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC));
        var summary = new PolicyHygieneFleetSummary(query, pairService);

        assertEquals(4, summary.view().get("pending"), "before the first tick no pair index is published: every container is unanalysed and PENDING");
        while (summary.refreshNext()) { /* bounded pass over every container, memberB included */ }
        verify(query, times(4)).hygieneReport(any(), anyInt()); // memberB is analysed too: its hit counters are member-specific

        var sameView = summary.view();
        assertEquals(2, sameView.get("haPairsCountedOnce"));
        assertEquals(0, sameView.get("haPairsDivergent"));
        assertEquals(0, sameView.get("haPairsUnknown"));
        assertEquals(0, sameView.get("pending"));

        @SuppressWarnings("unchecked") var sameRows = (List<Map<String, Object>>) sameView.get("containers");
        assertEquals(2, sameRows.size());
        assertEquals("dev-tango-04", sameRows.get(0).get("sourceId"));
        assertEquals(HaPolicyPairService.opaque("cluster-asa"), sameRows.get(0).get("pairRef"));
        assertEquals("single", sameRows.get(0).get("haContext"));
        assertEquals("SAME", sameRows.get(0).get("haOutcome"));
        assertNull(sameRows.get(0).get("haReason"));
        assertEquals("dev-romeo-01", sameRows.get(1).get("sourceId"));
        assertEquals(HaPolicyPairService.opaque("cluster-fgt"), sameRows.get(1).get("pairRef"));
        assertEquals("root", sameRows.get(1).get("haContext"));
        assertEquals("SAME", sameRows.get(1).get("haOutcome"));

        int asaRules = asaA.sections().stream().mapToInt(s -> s.rules().size()).sum();
        int fgtRules = fgtA.sections().stream().mapToInt(s -> s.rules().size()).sum();
        assertEquals(asaRules + fgtRules, sameView.get("rules"));
        assertEquals(2, ints(sameView.get("totals")).get("disabled"), "1 disabled rule on ASA + 1 on FortiGate, each pair counted once");
        Map<String, Integer> singleUnknownReasons = new LinkedHashMap<>(ints(sameView.get("unknownReasons")));
        Map<String, Integer> singleBlockerReasons = new LinkedHashMap<>(ints(sameView.get("blockerReasons")));
        assertFalse(singleUnknownReasons.isEmpty());
        assertFalse(singleBlockerReasons.isEmpty());

        // Member B of the ASA pair changes policy (even with identical timestamp/artefactRef metadata, testing pair-state fingerprint invalidation):
        var asaBDivergent = mappedAsa("dev-tango-05", "FW-TANGO-05", "2026-11-20T11:00:00Z",
            "access-list ACL-OUT extended permit tcp any host 198.51.100.21 eq 443 inactive\n");
        when(local.asaSnapshots()).thenReturn(List.of(asaA, asaBDivergent));
        doReturn(List.of(asaA.metadata(), asaBDivergent.metadata(), fgtA.metadata(), fgtB.metadata())).when(query).catalog();
        doReturn(Optional.of(asaBDivergent)).when(query).find(asaBDivergent.metadata().id());

        // view() never evaluates pairs itself: until the next tick it serves the published index (bounded staleness of one tick).
        var immediateView = summary.view();
        assertEquals(2, immediateView.get("haPairsCountedOnce"), "published index is unchanged until the next tick");
        assertEquals(0, immediateView.get("haPairsDivergent"));

        while (summary.refreshNext()) { /* analyse divergent ASA pair */ }
        var divView = summary.view();
        @SuppressWarnings("unchecked") var divRows = (List<Map<String, Object>>) divView.get("containers");
        assertEquals(3, divRows.size());
        assertEquals(List.of("dev-tango-04", "dev-tango-05", "dev-romeo-01"), divRows.stream().map(r -> (String) r.get("sourceId")).toList());
        assertEquals("HA_POLICY_DIVERGENT", divRows.get(0).get("haOutcome"));
        assertEquals("HA_POLICY_DIVERGENT", divRows.get(1).get("haOutcome"));
        assertEquals("SAME", divRows.get(2).get("haOutcome"));
        assertEquals(1, divView.get("haPairsDivergent"));
        assertEquals(1, divView.get("haPairsCountedOnce"));
        assertEquals(0, divView.get("pending"));
        assertEquals(asaRules + asaBDivergent.sections().stream().mapToInt(s -> s.rules().size()).sum() + fgtRules, divView.get("rules"));
        assertEquals(4, ints(divView.get("totals")).get("disabled"), "1 on ASA-A + 2 on ASA-B + 1 on FGT pair");
        singleBlockerReasons.forEach((reason, count) ->
            assertEquals(count * 2, ints(divView.get("blockerReasons")).get(reason), "both divergent ASA members contribute to blockerReasons"));
    }

    private final java.util.concurrent.atomic.AtomicReference<HaPolicyPairService> realPairService = new java.util.concurrent.atomic.AtomicReference<>();

    private PolicyHygieneFleetSummary samePair(String idA, String idB) {
        var local = mock(LocalFirewallPolicyService.class);
        var devices = mock(DeviceReadRepository.class);
        var identities = mock(HaPairIdentityRepository.class);
        when(devices.listAll()).thenReturn(List.of(
            pairDevice(idA, "cisco_asa", "FW-TANGO-04", "cluster-asa"), pairDevice(idB, "cisco_asa", "FW-TANGO-05", "cluster-asa")));
        when(identities.find("cluster-asa")).thenReturn(Optional.of(new HaPairIdentity("cluster-asa", "cisco_asa", "S1", "S2", HaPairIdentity.State.MATCH)));
        var a = mappedAsa(idA, "FW-TANGO-04", "2026-11-20T10:00:00Z", "");
        var b = mappedAsa(idB, "FW-TANGO-05", "2026-11-20T11:00:00Z", "");
        when(local.asaSnapshots()).thenReturn(List.of(a, b));
        when(local.fgtSnapshots()).thenReturn(List.of());
        doReturn(List.of(a.metadata(), b.metadata())).when(query).catalog();
        for (var snap : List.of(a, b)) doReturn(Optional.of(snap)).when(query).find(snap.metadata().id());
        var pairService = new HaPolicyPairService(local, devices, identities, Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC));
        realPairService.set(pairService);
        return new PolicyHygieneFleetSummary(query, pairService);
    }

    private static PolicyQueryService.HygieneReport report(int disabled, int unused, int unknown, int hitUnused, int hitOnlyUnknown) {
        Map<String, Integer> classes = new TreeMap<>(); PolicyHygiene.CLASSES.forEach(c -> classes.put(c, 0));
        classes.put("disabled", disabled); classes.put("unused", unused); classes.put("unknown", unknown);
        return new PolicyQueryService.HygieneReport(classes, new TreeMap<>(), new TreeMap<>(), hitUnused, hitOnlyUnknown);
    }

    @Test void sameHaPairCountsContentOnceButHitCounterFindingsPerMemberWhateverMemberIsStandby() {
        var summary = samePair("dev-tango-04", "dev-tango-05");
        // memberA is the standby (every rule zero-hit), memberB is active with real use.
        doAnswer(inv -> ((PolicySnapshot) inv.getArgument(0)).metadata().sourceId().equals("dev-tango-04")
            ? report(1, 5, 5, 5, 0) : report(1, 1, 3, 1, 2)).when(query).hygieneReport(any(), anyInt());
        assertTrue(summary.refreshNext());
        assertEquals(1, summary.view().get("pending"), "memberA alone is not the pair's answer: PENDING until memberB is analysed");
        while (summary.refreshNext()) { /* bounded */ }
        var view = summary.view();
        assertEquals(0, view.get("pending"));
        assertEquals(1, view.get("haPairsCountedOnce"));
        var totals = ints(view.get("totals"));
        assertEquals(1, totals.get("disabled"), "policy-content class counted once");
        assertEquals(5 + 1, totals.get("unused"), "unused is member-specific: memberA's own plus memberB's own hit-derived findings");
        assertEquals(5 + 2, totals.get("unknown"), "hit-derived UNKNOWN of memberB is added, its content UNKNOWN is not counted twice");
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) view.get("containers");
        assertEquals(1, rows.size());
        assertEquals(Map.of("unused", 1, "unknown", 2), rows.get(0).get("peerMemberSpecific"));

        // Reversed roles: the same pair with memberA active and memberB standby must lose no standby finding.
        var reversed = samePair("dev-tango-04", "dev-tango-05");
        doAnswer(inv -> ((PolicySnapshot) inv.getArgument(0)).metadata().sourceId().equals("dev-tango-04")
            ? report(1, 1, 3, 1, 2) : report(1, 5, 5, 5, 0)).when(query).hygieneReport(any(), anyInt());
        while (reversed.refreshNext()) { /* bounded */ }
        var reversedTotals = ints(reversed.view().get("totals"));
        assertEquals(1, reversedTotals.get("disabled"));
        assertEquals(1 + 5, reversedTotals.get("unused"), "same pair total whichever member is memberA");
        assertEquals(3 + 0, reversedTotals.get("unknown"));
    }

    @Test void hygieneReportSeparatesHitCounterEvidenceFromPolicyContentEvidence() {
        var meta = meta("p1", "c1", "2026-11-01T10:00:00Z");
        var zero = PolicyHygieneTest.rule("r1", "host", "port", "allow", true)
            .withHitCounts(new HitCounts(0L, null, null, "mds", Instant.now().toString(), "zero", List.of()));
        var noHits = PolicyHygieneTest.rule("r2", "host", "port", "allow", true);
        var report = query.hygieneReport(snapshot(meta, List.of(zero, noHits)), 90);
        assertEquals(1, report.hitUnused(), "the zero-hit rule is hit-derived unused");
        assertTrue(report.hitOnlyUnknown() <= report.classes().get("unknown"));
        assertEquals(report.classes().get("unused"), report.hitUnused(), "no other source of unused in this snapshot");
    }

    @Test void unknownHaPairsAndUnavailablePairServiceNeverMerge() {
        var local = mock(LocalFirewallPolicyService.class);
        var devices = mock(DeviceReadRepository.class);
        var identities = mock(HaPairIdentityRepository.class);
        when(devices.listAll()).thenReturn(List.of(
            pairDevice("dev-tango-04", "cisco_asa", "FW-TANGO-04", "cluster-mismatch"),
            pairDevice("dev-tango-05", "cisco_asa", "FW-TANGO-05", "cluster-mismatch"),
            pairDevice("dev-tango-06", "cisco_asa", "FW-TANGO-06", "cluster-failed"),
            pairDevice("dev-tango-07", "cisco_asa", "FW-TANGO-07", "cluster-failed")));
        when(identities.find("cluster-mismatch")).thenReturn(Optional.of(new HaPairIdentity("cluster-mismatch", "cisco_asa", "S1", "S2", HaPairIdentity.State.MISMATCH)));
        when(identities.find("cluster-failed")).thenReturn(Optional.of(new HaPairIdentity("cluster-failed", "cisco_asa", "S3", "S4", HaPairIdentity.State.MATCH)));

        var mismatchA = mappedAsa("dev-tango-04", "FW-TANGO-04", "2026-11-20T10:00:00Z", "");
        var mismatchB = mappedAsa("dev-tango-05", "FW-TANGO-05", "2026-11-20T10:00:00Z", "");
        var okMember = mappedAsa("dev-tango-06", "FW-TANGO-06", "2026-11-20T10:00:00Z", "");
        var failedMember = new PolicySnapshot(
            new Metadata(PolicySnapshot.ref("asa", "dev-tango-07", "single"), "dev-tango-07", "FW-TANGO-07", "ASA",
                PolicySnapshot.ref("dev-tango-07", "single"), "single", "ASA access rules", "2026-11-20T10:00:00Z",
                "artefact-dev-tango-07", List.of()),
            okMember.sections(), okMember.objects(),
            List.of(new CollectionFailure("layer", "ASA_UNSUPPORTED_MODE", "transparent", 0)));

        when(local.asaSnapshots()).thenReturn(List.of(mismatchA, mismatchB, okMember, failedMember));
        doReturn(List.of(mismatchA.metadata(), mismatchB.metadata(), okMember.metadata(), failedMember.metadata())).when(query).catalog();
        for (var snap : List.of(mismatchA, mismatchB, okMember, failedMember)) doReturn(Optional.of(snap)).when(query).find(snap.metadata().id());

        var pairService = new HaPolicyPairService(local, devices, identities, Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC));
        var summary = new PolicyHygieneFleetSummary(query);
        summary.setHaPolicyPairService(pairService);
        while (summary.refreshNext()) { /* analyse all 4 separate containers */ }

        var view = summary.view();
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) view.get("containers");
        assertEquals(4, rows.size(), "UNKNOWN pairs are never merged");
        assertEquals(0, view.get("haPairsCountedOnce"));
        assertEquals(0, view.get("haPairsDivergent"));
        assertEquals(2, view.get("haPairsUnknown"));
        assertEquals("UNKNOWN", rows.get(0).get("haOutcome"));
        assertEquals("PAIR_IDENTITY_NOT_MATCH", rows.get(0).get("haReason"));
        assertEquals("UNKNOWN", rows.get(1).get("haOutcome"));
        assertEquals("PAIR_IDENTITY_NOT_MATCH", rows.get(1).get("haReason"));
        assertEquals("UNKNOWN", rows.get(2).get("haOutcome"));
        assertEquals("SNAPSHOT_HAS_COLLECTION_FAILURES", rows.get(2).get("haReason"));
        assertEquals("UNKNOWN", rows.get(3).get("haOutcome"));
        assertEquals("SNAPSHOT_HAS_COLLECTION_FAILURES", rows.get(3).get("haReason"));
        assertEquals(4, ints(view.get("totals")).get("disabled"), "all 4 member containers contribute separately");

        // Fail closed when HaPolicyPairService throws (e.g. artefact store unavailable): keep all rows unmerged.
        when(local.asaSnapshots()).thenThrow(new IllegalStateException("LOCAL_POLICY_CONFIGURATION_UNAVAILABLE"));
        summary.refreshNext();
        var fallbackView = summary.view();
        @SuppressWarnings("unchecked") var fallbackRows = (List<Map<String, Object>>) fallbackView.get("containers");
        assertEquals(4, fallbackRows.size());
        assertEquals(2, fallbackView.get("haPairsUnknown"), "a failed tick keeps the last good index");
        assertEquals(0, fallbackView.get("pending"), "and its fingerprints, so nothing re-analyses");

        var never = new PolicyHygieneFleetSummary(query);
        var failing = mock(HaPolicyPairService.class);
        when(failing.evaluatePairs()).thenThrow(new IllegalStateException("LOCAL_POLICY_CONFIGURATION_UNAVAILABLE"));
        never.setHaPolicyPairService(failing);
        never.refreshNext();
        assertFalse(never.view().containsKey("haPairsCountedOnce"), "fails closed when there never was a good index");
    }

    @Test void viewNeverEvaluatesPairsAndAFailedTickKeepsTheLastGoodIndexAndFingerprint() {
        var summary = samePair("dev-tango-04", "dev-tango-05"); // wired with the real pair service
        var service = mock(HaPolicyPairService.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var failNext = new java.util.concurrent.atomic.AtomicBoolean();
        var realResults = new java.util.concurrent.atomic.AtomicReference<List<HaPolicyPairing.Result>>();
        when(service.evaluatePairs()).thenAnswer(inv -> {
            calls.incrementAndGet();
            if (failNext.get()) throw new IllegalStateException("transient");
            return realResults.get();
        });
        var real = realPairService.get();
        realResults.set(real.evaluatePairs());
        summary.setHaPolicyPairService(service);

        assertTrue(summary.refreshNext());
        assertEquals(1, calls.get());
        for (int i = 0; i < 25; i++) summary.view();
        assertEquals(1, calls.get(), "view() never evaluates pairs; only the tick does");
        while (summary.refreshNext()) { /* analyse both members */ }
        int afterAnalysis = calls.get();
        for (int i = 0; i < 25; i++) summary.view();
        assertEquals(afterAnalysis, calls.get());
        assertFalse(summary.refreshNext());
        assertEquals(afterAnalysis + 1, calls.get(), "exactly one evaluation per tick");
        verify(query, times(2)).hygieneReport(any(), anyInt());

        failNext.set(true);
        assertFalse(summary.refreshNext(), "a transient evaluation failure re-analyses nothing");
        var view = summary.view();
        assertEquals(1, view.get("haPairsCountedOnce"), "last good index is kept");
        assertEquals(0, view.get("pending"));
        verify(query, times(2)).hygieneReport(any(), anyInt());
    }

    @Test void privacyMaskPreservesHaFieldsAndCountsWithoutLeakingHostnamesOrWithholding() throws Exception {
        String rawAsaA = "synth-raw-asa-01", rawAsaB = "synth-raw-asa-02";
        String rawFgtA = "synth-raw-fgt-01", rawFgtB = "synth-raw-fgt-02";
        String rawUnkA = "synth-raw-asa-03", rawUnkB = "synth-raw-asa-04";
        String rawVdom = "VDOM-SYNTH-CORP";

        var asaA = mappedAsa("dev-asa-1", rawAsaA, "2026-11-20T10:00:00Z", "");
        var asaB = mappedAsa("dev-asa-2", rawAsaB, "2026-11-20T10:00:00Z", "");
        var fgtA = HaPolicyPairingTest.snapshot("dev-fgt-1", "FGT", rawVdom, "2026-11-20T10:00:00Z",
            List.of(new HaPolicyPairingTest.Spec("R1", "accept")), List.of(), null);
        var fgtB = HaPolicyPairingTest.snapshot("dev-fgt-2", "FGT", rawVdom, "2026-11-20T10:00:00Z",
            List.of(new HaPolicyPairingTest.Spec("R1", "deny")), List.of(), null);
        var unkA = mappedAsa("dev-asa-3", rawUnkA, "2026-11-20T10:00:00Z", "");
        var unkB = mappedAsa("dev-asa-4", rawUnkB, "2026-11-20T10:00:00Z", "");

        var local = mock(LocalFirewallPolicyService.class);
        var devices = mock(DeviceReadRepository.class);
        var identities = mock(HaPairIdentityRepository.class);
        when(devices.listAll()).thenReturn(List.of(
            pairDevice("dev-asa-1", "cisco_asa", rawAsaA, "cluster-same"),
            pairDevice("dev-asa-2", "cisco_asa", rawAsaB, "cluster-same"),
            pairDevice("dev-fgt-1", "fortinet", rawFgtA, "cluster-div"),
            pairDevice("dev-fgt-2", "fortinet", rawFgtB, "cluster-div"),
            pairDevice("dev-asa-3", "cisco_asa", rawUnkA, "cluster-unk"),
            pairDevice("dev-asa-4", "cisco_asa", rawUnkB, "cluster-unk")));
        when(identities.find("cluster-same")).thenReturn(Optional.of(new HaPairIdentity("cluster-same", "cisco_asa", "S1", "S2", HaPairIdentity.State.MATCH)));
        when(identities.find("cluster-div")).thenReturn(Optional.of(new HaPairIdentity("cluster-div", "fortinet", "S3", "S4", HaPairIdentity.State.MATCH)));
        when(identities.find("cluster-unk")).thenReturn(Optional.of(new HaPairIdentity("cluster-unk", "cisco_asa", "S5", "S6", HaPairIdentity.State.MISMATCH)));
        when(local.asaSnapshots()).thenReturn(List.of(asaA, asaB, unkA, unkB));
        when(local.fgtSnapshots()).thenReturn(List.of(fgtA, fgtB));

        // Ensure raw hostnames flow into Metadata.sourceName so PolicyPrivacy.mask must pseudonymize them.
        Metadata fgtMetaA = new Metadata(fgtA.metadata().id(), "dev-fgt-1", rawFgtA, "FGT", fgtA.metadata().containerId(), rawVdom, "FGT firewall policy", "2026-11-20T10:00:00Z", "artefact-fgt-1", List.of());
        Metadata fgtMetaB = new Metadata(fgtB.metadata().id(), "dev-fgt-2", rawFgtB, "FGT", fgtB.metadata().containerId(), rawVdom, "FGT firewall policy", "2026-11-20T10:00:00Z", "artefact-fgt-2", List.of());
        doReturn(List.of(asaA.metadata(), asaB.metadata(), fgtMetaA, fgtMetaB, unkA.metadata(), unkB.metadata())).when(query).catalog();
        for (var snap : List.of(asaA, asaB, fgtA, fgtB, unkA, unkB)) doReturn(Optional.of(snap)).when(query).find(snap.metadata().id());

        var pairService = new HaPolicyPairService(local, devices, identities, Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC));
        var summary = new PolicyHygieneFleetSummary(query, pairService);
        while (summary.refreshNext()) { /* analyse active containers */ }

        var names = new TopologyNamePseudonymizer(new byte[32]);
        var ips = new SubnetPreservingIpMasker(new byte[32]);
        @SuppressWarnings("unchecked")
        var masked = (Map<String, Object>) PolicyPrivacy.mask(summary.view(), "", names, ips);

        assertEquals(1, masked.get("haPairsCountedOnce"));
        assertEquals(1, masked.get("haPairsDivergent"));
        assertEquals(1, masked.get("haPairsUnknown"));

        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) masked.get("containers");
        assertEquals(5, rows.size());
        assertEquals("SAME", rows.get(0).get("haOutcome"));
        assertEquals("single", rows.get(0).get("haContext"));
        assertEquals(HaPolicyPairService.opaque("cluster-same"), rows.get(0).get("pairRef"));
        assertNull(rows.get(0).get("haReason"));

        assertEquals("HA_POLICY_DIVERGENT", rows.get(1).get("haOutcome"));
        assertEquals(names.maskPolicyName("context", rawVdom), rows.get(1).get("haContext"));
        assertEquals(HaPolicyPairService.opaque("cluster-div"), rows.get(1).get("pairRef"));
        assertEquals("HA_POLICY_DIVERGENT", rows.get(2).get("haOutcome"));

        assertEquals("UNKNOWN", rows.get(3).get("haOutcome"));
        assertEquals("PAIR_IDENTITY_NOT_MATCH", rows.get(3).get("haReason"));
        assertEquals(HaPolicyPairService.opaque("cluster-unk"), rows.get(3).get("pairRef"));

        String json = new ObjectMapper().writeValueAsString(masked);
        assertFalse(json.contains("Withheld in AIView"), json);
        for (String raw : List.of(rawAsaA, rawAsaB, rawFgtA, rawFgtB, rawUnkA, rawUnkB, rawVdom, "cluster-same", "cluster-div", "cluster-unk")) {
            assertFalse(json.contains(raw), "masked summary must not leak " + raw);
        }
    }
}
