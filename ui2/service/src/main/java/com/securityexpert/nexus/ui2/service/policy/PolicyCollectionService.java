package com.securityexpert.nexus.ui2.service.policy;

import java.util.*;
import org.springframework.stereotype.Service;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.gates.JooqGateRegistryDao;
import com.securityexpert.nexus.ui2.jobs.capability.PersistenceGateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates;
import com.securityexpert.nexus.ui2.jobs.policy.PanPolicyGates;

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
                "sourceName", ("palo_alto".equals(s.vendor()) ? "Panorama " : "MDS ") + s.sourceId(),
                "vendor", "palo_alto".equals(s.vendor()) ? "PAN" : "CP")).toList();
    }
    public Optional<String> collect(String source, String domain, String actor) {
        if (source == null || source.isBlank() || domain == null || domain.length() > 100) return Optional.empty();
        var eligible = repository.sources().stream().filter(s -> s.sourceId().equals(source)).findFirst();
        if (eligible.isEmpty()) return Optional.empty();
        if ("palo_alto".equals(eligible.get().vendor())) PanPolicyGates.requireAll(gates);
        else CpPolicyGates.requireAll(gates);
        return repository.enqueue(source, domain, false, actor);
    }
}
