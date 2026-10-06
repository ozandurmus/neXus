package com.securityexpert.nexus.ui2.service.failover;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DurableQuarantineStoreTest {
    @Test void incidentsAreIdempotentRetainedAndReleasedOnlyByExactReference() {
        var store=new DurableQuarantineStore();
        store.engageQuarantine("unit-a","incident-a","MUTATION_STOPPED",Set.of("member-a","member-b"));
        store.engageQuarantine("unit-a","incident-a","MUTATION_STOPPED",Set.of("member-a","member-b"));
        assertEquals(1,store.getAuditHistory().size());
        assertTrue(store.blocksMutation("other-unit",Set.of("member-a","member-c")));
        assertFalse(store.acknowledgeQuarantine("unit-a","stale","synthetic-reviewer-a","synthetic-reviewer-b","Reviewed locally"));
        assertTrue(store.acknowledgeQuarantine("unit-a","incident-a","synthetic-reviewer-a","synthetic-reviewer-b","Reviewed locally"));
        store.engageQuarantine("unit-a","incident-a","MUTATION_STOPPED",Set.of("member-a","member-b"));
        assertFalse(store.isClusterQuarantined("unit-a"),"A released incident cannot be reopened by replay");
        store.engageQuarantine("unit-a","incident-b","MUTATION_STOPPED",Set.of("member-a","member-b"));
        assertFalse(store.acknowledgeQuarantine("unit-a","incident-a","synthetic-reviewer-a","synthetic-reviewer-b","Reviewed locally"));
        assertTrue(store.isClusterQuarantined("unit-a"));
        assertEquals(2,store.getAuditHistory().size());
        assertTrue(store.getAuditHistory().get(0).acknowledged());
    }

    @Test void releasingOneIncidentCannotClearAnotherOpenIncident() {
        var store=new DurableQuarantineStore();
        for (String incident:Set.of("incident-a","incident-b"))
            store.engageQuarantine("unit-a",incident,"MUTATION_STOPPED",Set.of("member-a","member-b"));
        assertTrue(store.acknowledgeQuarantine("unit-a","incident-a","synthetic-reviewer-a","synthetic-reviewer-b","Reviewed locally"));
        assertTrue(store.isClusterQuarantined("unit-a"));
        assertThrows(IllegalArgumentException.class,() -> store.acknowledgeQuarantine(
            "unit-a","incident-b","synthetic-reviewer-a","synthetic-reviewer-a","Reviewed locally"));
    }
}
