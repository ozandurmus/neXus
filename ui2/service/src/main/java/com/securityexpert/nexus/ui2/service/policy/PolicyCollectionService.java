package com.securityexpert.nexus.ui2.service.policy;

import java.util.*;
import org.springframework.stereotype.Service;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.gates.JooqGateRegistryDao;
import com.securityexpert.nexus.ui2.jobs.capability.PersistenceGateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates;

@Service
public class PolicyCollectionService {
    private final PolicyCollectionRepository repository;
    private final PersistenceGateRegistryPort gates;
    public PolicyCollectionService(TransactionBoundary tx) {
        repository = new PolicyCollectionRepository(tx);
        gates = new PersistenceGateRegistryPort(new JooqGateRegistryDao(tx));
    }
    public List<Map<String, Object>> sources() {
        return repository.sources().stream().map(s -> Map.<String, Object>of("sourceId", s.sourceId(),
                "sourceName", "MDS " + s.sourceId(), "vendor", "CP")).toList();
    }
    public Optional<String> collect(String source, String domain, String actor) {
        if (source == null || source.isBlank() || domain == null || domain.length() > 100) return Optional.empty();
        CpPolicyGates.requireAll(gates);
        return repository.enqueue(source, domain, false, actor);
    }
}
