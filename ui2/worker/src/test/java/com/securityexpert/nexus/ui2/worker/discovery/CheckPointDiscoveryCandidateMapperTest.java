package com.securityexpert.nexus.ui2.worker.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.cp.Address;
import com.securityexpert.nexus.ui2.discovery.cp.CandidateKey;
import com.securityexpert.nexus.ui2.discovery.cp.CandidateKind;
import com.securityexpert.nexus.ui2.discovery.cp.CandidateRow;
import com.securityexpert.nexus.ui2.discovery.cp.ClusterLink;
import com.securityexpert.nexus.ui2.discovery.cp.ClusterReference;
import com.securityexpert.nexus.ui2.discovery.cp.HostLink;
import com.securityexpert.nexus.ui2.discovery.cp.ObjectType;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

/** Synthetic Check Point candidate rows only (FW-TANGO-04 style names, 192.0.2.x / 198.51.100.x). */
class CheckPointDiscoveryCandidateMapperTest {

    private static final String DOMAIN = "DOM-SYN-ALPHA";

    private static CandidateRow row(String stableId, String displayName, CandidateKind kind, Optional<HostLink> hostLink,
            Optional<ClusterReference> cluster, Address own, Address mgmt) {
        return new CandidateRow(
                new CandidateKey(OpaqueId.of(DOMAIN), OpaqueId.of(stableId)),
                ObjectType.GATEWAY,
                kind,
                displayName,
                own,
                mgmt,
                cluster,
                Optional.of("SYN-MODEL-1"),
                Optional.of("R99"),
                Optional.of("CONNECTED"),
                Optional.empty(),
                hostLink,
                new ClusterLink.NotEvaluable());
    }

    private static CandidateRow complete(String stableId, String displayName, CandidateKind kind) {
        return row(stableId, displayName, kind, Optional.empty(), Optional.empty(),
                Address.of("192.0.2.10"), Address.of("198.51.100.10"));
    }

    @Test
    void completeSyntheticRecordMapsToCheckPointCandidateWithIdentityFields() {
        List<DiscoveryCandidateRecord> out = CheckPointDiscoveryCandidateMapper.map("run-1",
                List.of(complete("GW-TANGO-04-ID", "FW-TANGO-04", CandidateKind.STANDALONE_PRODUCT_GATEWAY)));

        assertEquals(1, out.size());
        DiscoveryCandidateRecord r = out.get(0);
        assertEquals("run-1", r.runId());
        assertEquals("check_point", r.vendor());
        assertEquals("STANDALONE_PRODUCT_GATEWAY", r.kind());
        assertEquals("GW-TANGO-04-ID", r.stableIdentifier());
        assertEquals(Optional.of(DOMAIN), r.owningDomain());
        assertEquals("FW-TANGO-04", r.displayName());
        assertEquals(Optional.of("192.0.2.10"), r.ownAddress());
        assertEquals(Optional.of("198.51.100.10"), r.managementAddress());
        assertEquals(Optional.of("SYN-MODEL-1"), r.model());
        assertEquals(Optional.of("R99"), r.softwareVersion());
        assertEquals(Optional.of("CONNECTED"), r.connectionState());
        assertTrue(r.importable());
        assertTrue(r.candidateId() != null && !r.candidateId().equals(r.stableIdentifier()),
                "candidate id is opaque, never the stable identifier");
    }

    @Test
    void displayNameIsNeverUsedAsTheStableIdentifierJoinKey() {
        DiscoveryCandidateRecord r = CheckPointDiscoveryCandidateMapper.map("run-1",
                List.of(complete("GW-TANGO-04-ID", "FW-TANGO-04", CandidateKind.STANDALONE_PRODUCT_GATEWAY))).get(0);
        assertNotEquals(r.displayName(), r.stableIdentifier());
    }

    @Test
    void absentAddressesStayEmptyAndAreNeverFilledFromEachOther() {
        DiscoveryCandidateRecord r = CheckPointDiscoveryCandidateMapper.map("run-1",
                List.of(row("GW-ROMEO-02-ID", "FW-ROMEO-02", CandidateKind.STANDALONE_PRODUCT_GATEWAY,
                        Optional.empty(), Optional.empty(), Address.absent(), Address.of("198.51.100.20")))).get(0);
        assertEquals(Optional.empty(), r.ownAddress(), "own address is not copied from management address");
        assertEquals(Optional.of("198.51.100.20"), r.managementAddress());
    }

    @Test
    void unclassifiedAndAmbiguousRowsAreShownButNeverImportable() {
        List<DiscoveryCandidateRecord> out = CheckPointDiscoveryCandidateMapper.map("run-1", List.of(
                complete("UNK-SYN-01", "UNK-SYN-01", CandidateKind.UNCLASSIFIED),
                complete("AMB-SYN-01", "AMB-SYN-01", CandidateKind.AMBIGUOUS)));
        assertEquals("UNCLASSIFIED", out.get(0).kind());
        assertFalse(out.get(0).importable());
        assertEquals("AMBIGUOUS", out.get(1).kind());
        assertFalse(out.get(1).importable());
    }

    @Test
    void virtualSystemWithMissingHostLinkHasNoGuessedParentAndIsNotImportable() {
        DiscoveryCandidateRecord r = CheckPointDiscoveryCandidateMapper.map("run-1", List.of(
                row("VS-SYN-07", "VS-SYN-07", CandidateKind.STANDALONE_VIRTUAL_SYSTEM,
                        Optional.of(new HostLink.Missing()), Optional.empty(),
                        Address.of("192.0.2.30"), Address.of("198.51.100.30")))).get(0);
        assertNull(r.parentCandidateId().orElse(null));
        assertFalse(r.importable(), "IM-2: a virtual system whose host did not resolve is not importable standalone");
    }

    @Test
    void virtualSystemWithAmbiguousHostLinkHasNoGuessedParent() {
        DiscoveryCandidateRecord r = CheckPointDiscoveryCandidateMapper.map("run-1", List.of(
                row("VS-SYN-08", "VS-SYN-08", CandidateKind.STANDALONE_VIRTUAL_SYSTEM,
                        Optional.of(new HostLink.Ambiguous()), Optional.empty(),
                        Address.of("192.0.2.31"), Address.of("198.51.100.31")))).get(0);
        assertEquals(Optional.empty(), r.parentCandidateId());
        assertFalse(r.importable());
    }

    @Test
    void virtualSystemLinkedToHostInSameRunResolvesToThatHostsCandidateId() {
        List<CandidateRow> rows = List.of(
                complete("HOST-SYN-01", "HOST-SYN-01", CandidateKind.STANDALONE_VIRTUALIZATION_HOST),
                row("VS-SYN-09", "VS-SYN-09", CandidateKind.STANDALONE_VIRTUAL_SYSTEM,
                        Optional.of(new HostLink.Linked(OpaqueId.of("HOST-SYN-01"))), Optional.empty(),
                        Address.of("192.0.2.32"), Address.of("198.51.100.32")));
        List<DiscoveryCandidateRecord> out = CheckPointDiscoveryCandidateMapper.map("run-1", rows);
        assertEquals(out.get(0).candidateId(), out.get(1).parentCandidateId().orElseThrow());
        assertTrue(out.get(1).importable());
    }

    @Test
    void hostLinkToHostOutsideThisRunLeavesParentNullNotGuessed() {
        DiscoveryCandidateRecord r = CheckPointDiscoveryCandidateMapper.map("run-1", List.of(
                row("VS-SYN-10", "VS-SYN-10", CandidateKind.STANDALONE_VIRTUAL_SYSTEM,
                        Optional.of(new HostLink.Linked(OpaqueId.of("HOST-NOT-IN-RUN"))), Optional.empty(),
                        Address.of("192.0.2.33"), Address.of("198.51.100.33")))).get(0);
        assertEquals(Optional.empty(), r.parentCandidateId());
    }

    @Test
    void clusterMemberResolvesParentOnlyWhenClusterRowIsInRun() {
        List<DiscoveryCandidateRecord> out = CheckPointDiscoveryCandidateMapper.map("run-1", List.of(
                complete("CLU-ROMEO-01-ID", "CLU-ROMEO-01", CandidateKind.PLAIN_HIGH_AVAILABILITY_CLUSTER),
                row("MEM-SYN-01", "MEM-SYN-01", CandidateKind.PLAIN_CLUSTER_MEMBER, Optional.empty(),
                        Optional.of(new ClusterReference(Optional.of(OpaqueId.of("CLU-ROMEO-01-ID")), Optional.empty())),
                        Address.of("192.0.2.40"), Address.of("198.51.100.40")),
                row("MEM-SYN-02", "MEM-SYN-02", CandidateKind.PLAIN_CLUSTER_MEMBER, Optional.empty(),
                        Optional.of(new ClusterReference(Optional.of(OpaqueId.of("CLU-NOT-IN-RUN")), Optional.empty())),
                        Address.of("192.0.2.41"), Address.of("198.51.100.41"))));

        assertFalse(out.get(0).importable(), "a cluster is a target modifier, never a row of its own (IM-3)");
        assertEquals(out.get(0).candidateId(), out.get(1).parentCandidateId().orElseThrow());
        assertEquals(Optional.of("CLU-ROMEO-01-ID"), out.get(1).clusterReference());
        assertEquals(Optional.empty(), out.get(2).parentCandidateId(), "unresolved cluster is never guessed");
    }

    @Test
    void outcomeSummaryCountsByKindOnly() {
        var counts = CheckPointDiscoveryCandidateMapper.outcomeSummary(List.of(
                complete("GW-A-ID", "FW-TANGO-04", CandidateKind.STANDALONE_PRODUCT_GATEWAY),
                complete("GW-B-ID", "FW-JULIET-06", CandidateKind.STANDALONE_PRODUCT_GATEWAY),
                complete("UNK-ID", "UNK-SYN", CandidateKind.UNCLASSIFIED)));
        assertEquals(2, counts.get("STANDALONE_PRODUCT_GATEWAY"));
        assertEquals(1, counts.get("UNCLASSIFIED"));
        assertFalse(counts.toString().contains("FW-TANGO-04"));
    }
}
