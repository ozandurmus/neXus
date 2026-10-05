package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;

class PolicyUnitResumeTest {
    @Test void sameRequestAndPublishedVersionReuseOnlyCompletedPackages() throws Exception {
        var repository = mock(PolicyCollectionRepository.class);
        var json = new ObjectMapper();
        var request = new PolicyCollectionRepository.Request("source-1", "", false,
            PolicyCollectionRepository.Mode.FULL, "job-current", 2);
        String signalText = "{\"uid\":\"published-001\",\"publish-time\":{\"posix\":1791158400000,\"iso-8601\":\"2026-10-05T00:00Z\"}}";
        var signal = json.readTree(signalText);
        var metadata = new PolicySnapshot.Metadata("policy-1", "source-1", "MGR-TANGO-01", "CP", "domain-1",
            "DOM-TANGO-01", "OBJ-POLICY-01", "2026-10-05T00:00:00Z", "", List.of());
        var completed = new PolicySnapshot(metadata, List.of(), Map.of());
        when(repository.domainForRequest(request, "domain-1")).thenReturn(Optional.of(new PolicyCollectionRepository.DomainRun(
            false, json.writeValueAsString(CpDomainReuse.signal(signal)), json.writeValueAsString(List.of(completed)))));
        when(repository.saveDomain(any(), anyString(), anyString(), anyBoolean(), any(), anyString(), anyInt(), any(), anyString())).thenReturn(true);
        var reuse = new CpDomainReuse(repository, request);
        reuse.begin("domain-1");
        assertNull(reuse.decide("domain-1", signal), "the unfinished domain still needs collection");
        assertEquals(completed, reuse.resumedPackage("domain-1", "policy-1"));
        assertNull(reuse.resumedPackage("domain-1", "unfinished-policy"));
        var changed = json.readTree(signalText.replace("published-001", "published-002"));
        reuse.begin("domain-1");
        assertThrows(PolicyCollectionTrace.Failure.class, () -> reuse.decide("domain-1", changed));
    }

    @Test void cpCompletedUnitsResumeWithPendingDomainAndDatabaseGapsPreventCompletion() throws Exception {
        var json = new ObjectMapper();
        var repository = mock(PolicyCollectionRepository.class);
        var request = new PolicyCollectionRepository.Request("source-1", "", false, PolicyCollectionRepository.Mode.FULL, "job-current", 2);
        var signal = json.readTree("{\"uid\":\"published-001\",\"publish-time\":{\"posix\":1791158400000,\"iso-8601\":\"2026-10-05T00:00Z\"}}");
        var metadata = new PolicySnapshot.Metadata("policy-1", "source-1", "MGR-TANGO-01", "CP", "domain-1",
            "DOM-TANGO-01", "OBJ-POLICY-01", "2026-10-05T00:00:00Z", "", List.of());
        var completed = new PolicySnapshot(metadata, List.of(), Map.of());
        when(repository.domainForRequest(request, "domain-1")).thenReturn(Optional.of(new PolicyCollectionRepository.DomainRun(false, null, "[]")));
        when(repository.saveDomain(any(), anyString(), anyString(), anyBoolean(), any(), anyString(), anyInt(), any(), anyString())).thenReturn(true);
        when(repository.saveUnit(any(), anyString(), anyString(), anyString())).thenReturn(true);
        var reuse = new CpDomainReuse(repository, request);
        reuse.begin("domain-1"); reuse.decide("domain-1", signal); reuse.planned("domain-1", 1); reuse.snapshot(completed);
        String version = json.writeValueAsString(CpDomainReuse.signal(signal));
        verify(repository).saveUnit(request, "policy-1", version, json.writeValueAsString(completed));
        when(repository.resumedUnit(request, "policy-1", version)).thenReturn(Optional.of(json.writeValueAsString(completed)));
        reuse.begin("domain-1"); reuse.decide("domain-1", signal);
        assertEquals(completed, reuse.resumedPackage("domain-1", "policy-1"));
        var changed = json.readTree(signal.toString().replace("published-001", "published-002"));
        reuse.begin("domain-1"); reuse.decide("domain-1", changed);
        assertNull(reuse.resumedPackage("domain-1", "policy-1"));
        // Earlier gap-free attempts completed legitimately; inspect only the failing attempt below.
        verify(repository, atLeastOnce()).saveDomain(any(), anyString(), eq("COLLECTED"), eq(true),
            any(), anyString(), anyInt(), any(), anyString());
        clearInvocations(repository);
        doThrow(new com.securityexpert.nexus.ui2.persistence.policy.PolicyDatabaseFailure("synthetic", "CHUNK_INSERT", 100))
            .when(repository).saveUnit(any(), anyString(), anyString(), anyString());
        var gaps = new java.util.ArrayList<PolicySnapshot.CollectionFailure>();
        reuse.onDatabaseGap(gaps::add); reuse.planned("domain-1", 1); reuse.snapshot(completed); reuse.finish();
        assertEquals(1, gaps.size());
        verify(repository, never()).saveDomain(any(), anyString(), anyString(), eq(true), any(), anyString(), anyInt(), any(), anyString());
    }

    @Test void pendingPackagesAndPendingRuleEvidencePreventDomainCompletion() throws Exception {
        var repository = mock(PolicyCollectionRepository.class);
        var request = new PolicyCollectionRepository.Request("source-1", "", false,
            PolicyCollectionRepository.Mode.FULL, "job-current", 2);
        when(repository.saveDomain(any(), anyString(), anyString(), anyBoolean(), nullable(String.class),
            anyString(), anyInt(), nullable(String.class), anyString())).thenReturn(true);
        var metadata = new PolicySnapshot.Metadata("policy-1", "source-1", "MGR-TANGO-01", "CP", "domain-1",
            "DOM-TANGO-01", "OBJ-POLICY-01", "2026-10-05T00:00:00Z", "", List.of());
        for (boolean pendingRules : List.of(false, true)) {
            var reuse = new CpDomainReuse(repository, request);
            reuse.begin("domain-1");
            reuse.planned("domain-1", pendingRules ? 1 : 2);
            reuse.snapshot(new PolicySnapshot(metadata, List.of(), Map.of(), pendingRules
                ? List.of(new PolicySnapshot.CollectionFailure("layer-1", "COLLECTION_PENDING")) : List.of()));
            reuse.finish();
        }
        verify(repository, never()).saveDomain(any(), anyString(), anyString(), eq(true), nullable(String.class),
            anyString(), anyInt(), nullable(String.class), anyString());
    }

    @Test void panContentVersionIncludesConfigMembershipAndHierarchy() throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        var first = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream("<config><shared/></config>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var second = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream("<config><shared><entry name='synthetic-rule'/></shared></config>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String version = PanoramaPolicyCollector.contentVersion(first, "member-ref", "parent-ref");
        assertEquals(version, PanoramaPolicyCollector.contentVersion(first, "member-ref", "parent-ref"));
        assertNotEquals(version, PanoramaPolicyCollector.contentVersion(second, "member-ref", "parent-ref"));
        assertNotEquals(version, PanoramaPolicyCollector.contentVersion(first, "another-member", "parent-ref"));
        assertNotEquals(version, PanoramaPolicyCollector.contentVersion(first, "member-ref", "another-parent"));
    }
}
