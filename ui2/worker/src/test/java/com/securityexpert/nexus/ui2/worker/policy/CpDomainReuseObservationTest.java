package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;

/** The collector's observations of a domain: the published-session marker, never the session's names or users. */
class CpDomainReuseObservationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PolicyCollectionRepository repository = mock(PolicyCollectionRepository.class);
    private final PolicyCollectionRepository.Request request = new PolicyCollectionRepository.Request("mds-1", "", true,
        PolicyCollectionRepository.Mode.CHANGED_ONLY, "", 0);

    private static com.fasterxml.jackson.databind.JsonNode signal(String uid, long posix, String iso) throws Exception {
        return JSON.readTree("{\"uid\":\"" + uid + "\",\"publish-time\":{\"posix\":" + posix + ",\"iso-8601\":\"" + iso + "\"},\"user-name\":\"synthetic-admin\"}");
    }

    @Test void theMarkerHoldsTheSessionUidAndPublishTimeOnly() throws Exception {
        var reuse = new CpDomainReuse(repository, request);
        reuse.begin("c1", "DOM-1");
        reuse.decide("c1", signal("sess-2", 1792292400000L, "2026-10-18T06:00:00+0300"));
        var sink = new PolicyObservations();
        reuse.observe(sink);
        var seen = sink.cp().get(0);
        assertEquals("c1", seen.container());
        assertEquals("DOM-1", seen.domainName());
        var marker = JSON.readTree(seen.markerJson());
        assertEquals(java.util.Set.of("uid", "publish_time", "posix"), java.util.Set.copyOf(java.util.stream.StreamSupport
            .stream(java.util.Spliterators.spliteratorUnknownSize(marker.fieldNames(), 0), false).toList()));
        assertEquals("sess-2", marker.path("uid").asText());
        assertEquals("2026-10-18T06:00:00+0300", marker.path("publish_time").asText());
        assertFalse(seen.markerJson().contains("synthetic-admin"));
        assertNull(seen.previousMarkerJson());
        assertFalse(seen.reused());
        assertFalse(seen.complete(), "no snapshot was published for the domain");
    }

    @Test void aMissingSignalIsAnObservationWithoutAMarkerNotAGuess() {
        var reuse = new CpDomainReuse(repository, request);
        reuse.begin("c1", "DOM-1");
        reuse.decide("c1", null);
        var sink = new PolicyObservations();
        reuse.observe(sink);
        assertNull(sink.cp().get(0).markerJson());
    }

    @Test void theEarlierMarkerComesFromTheLastRunOfAFreshRequestOnly() throws Exception {
        String earlier = "{\"uid\":\"sess-1\",\"posix\":1792206000000,\"iso8601\":\"2026-10-17T06:00:00+0300\",\"publishTime\":\"2026-10-17T03:00:00Z\"}";
        when(repository.previousDomain("mds-1", "c1")).thenReturn(Optional.of(new PolicyCollectionRepository.DomainRun(true, earlier, "[]")));
        var reuse = new CpDomainReuse(repository, request);
        reuse.begin("c1", "DOM-1");
        reuse.decide("c1", signal("sess-2", 1792292400000L, "2026-10-18T06:00:00+0300"));
        var sink = new PolicyObservations();
        reuse.observe(sink);
        var previous = JSON.readTree(sink.cp().get(0).previousMarkerJson());
        assertEquals("sess-1", previous.path("uid").asText());
        assertEquals("2026-10-17T06:00:00+0300", previous.path("publish_time").asText());
        // A resumed request compares against its own earlier save, which would read as "unchanged": no earlier marker is offered.
        var leased = new PolicyCollectionRepository.Request("mds-1", "", true, PolicyCollectionRepository.Mode.CHANGED_ONLY, "job-1", 1);
        when(repository.domainForRequest(leased, "c1")).thenReturn(Optional.of(new PolicyCollectionRepository.DomainRun(false, earlier, "[]")));
        var resumed = new CpDomainReuse(repository, leased);
        resumed.begin("c1", "DOM-1");
        var sink2 = new PolicyObservations();
        resumed.observe(sink2);
        assertNull(sink2.cp().get(0).previousMarkerJson());
    }

    /** One source of truth: the revision observation and the changed-only trial's run record read the same domain state. */
    @Test void theObservationAndTheChangedOnlyTrialDecisionAgreeForEveryCase() throws Exception {
        String trial = "aaaaaaaa-0000-3000-8000-00000000000a", other = "00000000-0000-3000-8000-000000000002";
        var previousSignal = new CpDomainReuse.Signal("published-001", 1791158400000L, "2026-10-05T00:00+0000", "2026-10-05T00:00:00Z");
        long now = java.time.Instant.parse("2026-10-19T03:05:00Z").toEpochMilli();
        for (String domain : new String[] {trial, other}) {
            var repo = mock(PolicyCollectionRepository.class);
            when(repo.saveDomain(any(), anyString(), anyString(), anyBoolean(), nullable(String.class), anyString(), anyInt(), nullable(String.class), anyString())).thenReturn(true);
            var metadata = new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata("policy-" + domain, "mds-1", "MDS mds-1", "CP", domain,
                "DOM-1", "POL-1", "2026-10-18T21:30:00Z", "", java.util.List.of());
            var snapshot = new com.securityexpert.nexus.ui2.policy.PolicySnapshot(metadata, java.util.List.of(), java.util.Map.of());
            when(repo.previousDomain("mds-1", domain)).thenReturn(Optional.of(new PolicyCollectionRepository.DomainRun(true,
                JSON.writeValueAsString(previousSignal), JSON.writeValueAsString(java.util.List.of(snapshot)))));
            var scheduled = new PolicyCollectionRepository.Request("mds-1", "", true, PolicyCollectionRepository.Mode.CHANGED_ONLY, "job-" + domain, 1, true);
            var reuse = new CpDomainReuse(repo, scheduled, CpChangedOnlyDomains.parse(trial, "24"), () -> now);
            var sink = new PolicyObservations();
            try (var trace = new PolicyCollectionTrace("run-1", (step, total) -> {})) {
                reuse.begin(domain, "DOM-1");
                var reused = reuse.decide(domain, signal("published-001", 1791158400000L, "2026-10-05T00:00+0000"));
                if (reused == null) reuse.planned(domain, 1);
                reuse.record();
                reuse.observe(sink);
                var decision = trace.decisions().get(domain);
                var seen = sink.cp().get(0);
                assertEquals(domain.equals(trial), seen.reused(), domain);
                assertEquals(seen.reused() ? "REUSED" : "READ_NOT_IN_CHANGED_ONLY_SET", decision.decision(), domain);
                assertEquals(JSON.readTree(seen.markerJson()).path("uid").asText(), "published-001");
                assertEquals(seen.previousMarkerJson(), seen.markerJson(), "an unchanged signal is the same marker");
            }
        }
    }
}
