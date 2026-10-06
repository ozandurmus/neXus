package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;

class CpDomainReuseTest {
    @Test void advisoryNotesDoNotPreventCompletionOrReuseButUnknownRuleGapsDo() throws Exception {
        var repository = mock(PolicyCollectionRepository.class);
        var json = new ObjectMapper();
        var saved = new HashMap<String, PolicyCollectionRepository.DomainRun>();
        when(repository.previousDomain(anyString(), anyString())).thenAnswer(c -> Optional.ofNullable(saved.get(c.getArgument(1))));
        when(repository.saveUnit(any(), anyString(), anyString(), anyString())).thenReturn(true);
        when(repository.saveDomain(any(), anyString(), anyString(), anyBoolean(), nullable(String.class),
                anyString(), anyInt(), nullable(String.class), anyString())).thenAnswer(c -> {
            saved.put(c.getArgument(1), new PolicyCollectionRepository.DomainRun(c.getArgument(3), c.getArgument(4), c.getArgument(5)));
            return true;
        });
        var full = new PolicyCollectionRepository.Request("source-1", "", false, PolicyCollectionRepository.Mode.FULL, "job-full", 1);
        var reuse = new CpDomainReuse(repository, full);
        var signal = json.readTree("{\"uid\":\"published-001\",\"publish-time\":{\"posix\":1791158400000,\"iso-8601\":\"2026-10-05T00:00Z\"}}");
        List<String> reasons = List.of("POLICY_HITS_UNAVAILABLE", "POLICY_DB_INVENTORY_WRITE_FAILED",
            "COLLECTION_PENDING", "TIMEOUT", "UNRECOGNIZED_FAILURE");
        for (int i = 0; i < reasons.size(); i++) {
            String domain = "domain-" + i;
            reuse.begin(domain); reuse.decide(domain, signal); reuse.planned(domain, 1);
            var metadata = new PolicySnapshot.Metadata("policy-" + i, "source-1", "MGR-TANGO-01", "CP",
                domain, "DOM-TANGO-01", "OBJ-POLICY-01", "2026-10-05T00:00:00Z", "", List.of());
            reuse.snapshot(new PolicySnapshot(metadata, List.of(), Map.of(),
                List.of(new PolicySnapshot.CollectionFailure("layer-" + i, reasons.get(i)))));
        }
        reuse.finish();
        var changed = new CpDomainReuse(repository, new PolicyCollectionRepository.Request("source-1", "", false,
            PolicyCollectionRepository.Mode.CHANGED_ONLY, "job-changed", 1));
        for (int i = 0; i < reasons.size(); i++) {
            String domain = "domain-" + i;
            assertEquals(i < 2, saved.get(domain).complete());
            changed.begin(domain);
            if (i < 2) assertEquals(1, changed.decide(domain, signal).size());
            else assertNull(changed.decide(domain, signal));
        }
    }
}
