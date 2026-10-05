package com.securityexpert.nexus.ui2.service.policy;

import java.util.*;
import java.time.OffsetDateTime;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
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
    private final TransactionBoundary tx;
    private final JooqDeviceRepository devices;
    public PolicyCollectionService(TransactionBoundary tx) {
        this.tx = tx;
        devices = new JooqDeviceRepository(tx);
        repository = new PolicyCollectionRepository(tx);
        gates = new PersistenceGateRegistryPort(new JooqGateRegistryDao(tx));
    }
    public List<Map<String, Object>> sources() {
        return repository.sources().stream().map(s -> {
            Map<String, Object> view = new LinkedHashMap<>(Map.of("sourceId", s.sourceId(),
                "sourceName", devices.findSummary(s.sourceId()).flatMap(device -> device.observedHostname())
                    .filter(name -> !name.isBlank()).orElse("Management server"),
                "vendor", "palo_alto".equals(s.vendor()) ? "PAN" : "CP"));
            repository.latestStatus(s.sourceId()).ifPresent(status -> view.put("collection", presentation(status)));
            return view;
        }).toList();
    }
    public Optional<Map<String, Object>> status(String jobId) { return repository.status(jobId).map(this::presentation); }
    private Map<String, Object> presentation(Map<String, Object> status) {
        Map<String, Object> view = new LinkedHashMap<>(status);
        var domains = repository.domainProgress(String.valueOf(status.get("jobId")));
        view.put("domains", domains);
        view.put("domainsReused", domains.stream().filter(d -> "REUSED".equals(d.get("status"))).count());
        view.put("domainsCollected", domains.stream().filter(d -> "COLLECTED".equals(d.get("status"))).count());
        view.put("rulesReused", domains.stream().filter(d -> "REUSED".equals(d.get("status")))
            .mapToInt(d -> ((Number) d.get("rules")).intValue()).sum());
        String state = String.valueOf(status.get("state"));
        String reason = String.valueOf(status.get("reason"));
        boolean gaps = ((Number) status.getOrDefault("gapUnits", 0)).intValue() > 0 || reason.startsWith("PARTIAL_SNAPSHOT");
        view.put("outcome", state.equals("COMPLETED") && gaps ? "PARTIAL"
            : state.equals("COMPLETED") ? "COMPLETED"
            : Set.of("FAILED", "REJECTED", "OUTCOME_UNKNOWN").contains(state) ? "FAILED" : "UNKNOWN");
        // Completed jobs carry warnings through unit failure codes, never a contradictory terminal reason.
        if (state.equals("COMPLETED")) {
            view.put("reason", "");
            if (gaps && ((Number) view.getOrDefault("gapUnits", 0)).intValue() == 0) {
                view.remove("gapUnits"); // Legacy jobs recorded only the first warning, not the total number of units.
                view.put("unitFailureCodes", List.of(PolicyPrivacy.failureCode(reason)));
            }
        }
        tx.inTransaction(db -> db.fetch("select finished_at, transcript_artefact_ref is not null as has_transcript from jobs where job_id = {0}", status.get("jobId"))
            .stream().findFirst()).ifPresent(row -> {
                var at = row.get("finished_at", OffsetDateTime.class);
                if (at != null) view.put("collectedAt", at.toInstant().toString());
                view.put("hasTranscript", Boolean.TRUE.equals(row.get("has_transcript", Boolean.class)));
            });
        return view;
    }
    public Optional<String> collect(String source, String domain, String actor) {
        return collect(source, domain, actor, PolicyCollectionRepository.Mode.CHANGED_ONLY);
    }
    public Optional<String> collect(String source, String domain, String actor, PolicyCollectionRepository.Mode mode) {
        if (source == null || source.isBlank() || domain == null || domain.length() > 100) return Optional.empty();
        var eligible = repository.sources().stream().filter(s -> s.sourceId().equals(source)).findFirst();
        if (eligible.isEmpty()) return Optional.empty();
        if ("palo_alto".equals(eligible.get().vendor())) PanPolicyGates.requireAll(gates);
        else CpPolicyGates.requireAll(gates);
        return repository.enqueue(source, domain, false, actor, mode);
    }
}
