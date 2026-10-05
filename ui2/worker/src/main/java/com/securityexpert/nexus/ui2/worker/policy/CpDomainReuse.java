package com.securityexpert.nexus.ui2.worker.policy;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.platform.WorkerActor;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;

/** Parsed domain evidence only; a pending or gapped latest attempt always prevents reuse. */
final class CpDomainReuse {
    record Signal(String uid, long posix, String iso8601, String publishTime) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PolicyCollectionRepository repository;
    private final PolicyCollectionRepository.Request request;
    private java.util.function.Consumer<PolicySnapshot.CollectionFailure> databaseGap = gap -> {};
    CpDomainReuse onDatabaseGap(java.util.function.Consumer<PolicySnapshot.CollectionFailure> callback) { databaseGap = callback; return this; }
    private final Map<String, Domain> domains = new LinkedHashMap<>();
    private static final class Domain {
        PolicyCollectionRepository.DomainRun previous;
        Signal signal;
        boolean reused, gap, databaseFailed;
        int expected = -1;
        final Map<String, PolicySnapshot> snapshots = new LinkedHashMap<>();
    }
    CpDomainReuse(PolicyCollectionRepository repository, PolicyCollectionRepository.Request request) {
        this.repository = repository; this.request = request;
    }
    void begin(String container) {
        Domain domain = new Domain();
        // Read before recording the current pending attempt, including empty complete domains.
        domain.previous = repository.previousDomain(request.sourceId(), container).orElse(null);
        domains.put(container, domain);
        save(container, domain, "COLLECTING", false);
    }
    static Signal signal(JsonNode root) {
        try {
            var uid = root.path("uid");
            var time = root.path("publish-time");
            var posix = time.path("posix");
            var iso = time.path("iso-8601");
            if (!uid.isTextual() || uid.textValue().isBlank() || !posix.isIntegralNumber()
                    || !posix.canConvertToLong() || posix.longValue() <= 0 || !iso.isTextual()) return null;
            // CP ISO dates may use an offset without a colon and minute precision.
            OffsetDateTime.parse(iso.textValue().replaceFirst("([+-][0-9]{2})([0-9]{2})$", "$1:$2"));
            return new Signal(uid.textValue(), posix.longValue(), iso.textValue(), Instant.ofEpochMilli(posix.longValue()).toString());
        } catch (RuntimeException invalid) { return null; }
    }
    List<PolicySnapshot> decide(String container, JsonNode root) {
        Domain domain = domains.get(container);
        domain.signal = root == null ? null : signal(root);
        if (!domain.databaseFailed && request.mode() == PolicyCollectionRepository.Mode.CHANGED_ONLY && domain.signal != null
                && domain.previous != null && domain.previous.complete() && domain.previous.signalJson() != null) {
            try {
                Signal previous = JSON.readValue(domain.previous.signalJson(), Signal.class);
                List<PolicySnapshot> snapshots = JSON.readValue(domain.previous.snapshotsJson(),
                    JSON.getTypeFactory().constructCollectionType(List.class, PolicySnapshot.class));
                if (domain.signal.equals(previous) && snapshots.stream().allMatch(s -> s.failures().isEmpty()
                        && s.metadata().sourceId().equals(request.sourceId()) && s.metadata().containerId().equals(container))) {
                    snapshots.forEach(s -> domain.snapshots.put(s.metadata().id(), s));
                    domain.reused = true;
                }
            } catch (java.io.IOException | RuntimeException invalid) { /* Unproven stored evidence requires a full collection. */ }
        }
        if (domain.reused) {
            save(container, domain, "REUSED", true);
            PolicyCollectionTrace.packages(domain.snapshots.size());
            domain.snapshots.values().forEach(s -> PolicyCollectionTrace.done(s.metadata().id()));
            return List.copyOf(domain.snapshots.values());
        }
        save(container, domain, "COLLECTING", false);
        return null;
    }
    void snapshot(PolicySnapshot snapshot) {
        Domain domain = domains.get(snapshot.metadata().containerId());
        if (domain == null || domain.reused) return;
        domain.snapshots.put(snapshot.metadata().id(), snapshot);
        update(snapshot.metadata().containerId(), domain);
    }
    void planned(String container, int packages) {
        Domain domain = domains.get(container); domain.expected = packages; update(container, domain);
    }
    private void update(String container, Domain domain) {
        boolean finished = domain.expected >= 0 && domain.snapshots.size() == domain.expected
            && domain.snapshots.values().stream().flatMap(s -> s.failures().stream()).noneMatch(f -> f.reason().equals("COLLECTION_PENDING"));
        save(container, domain, finished ? "COLLECTED" : "COLLECTING", finished && !domain.gap
            && domain.snapshots.values().stream().allMatch(s -> s.failures().isEmpty()));
    }
    void gap(String container) {
        if (domains.containsKey(container)) { Domain domain = domains.get(container); domain.gap = true; save(container, domain, "COLLECTED", false); }
    }
    void finish() {
        domains.forEach((container, domain) -> {
            if (!domain.reused) save(container, domain, "COLLECTED", !domain.gap && domain.expected >= 0 && domain.snapshots.size() == domain.expected
                && domain.snapshots.values().stream().allMatch(s -> s.failures().isEmpty()));
        });
    }
    private void save(String container, Domain domain, String status, boolean complete) {
        // Direct offline collector tests have no ledger job. Production requests always carry a lease.
        if (request.jobId().isEmpty() || domain.databaseFailed) return;
        try {
            int rules = domain.snapshots.values().stream().flatMap(s -> s.sections().stream()).mapToInt(s -> s.rules().size()).sum();
            String hits = domain.snapshots.values().stream().flatMap(s -> s.sections().stream()).flatMap(s -> s.rules().stream())
                .map(PolicySnapshot.Rule::hitCounts).filter(Objects::nonNull).map(PolicySnapshot.HitCounts::collectedAt)
                .filter(Objects::nonNull).min(String::compareTo).orElse(null);
            if (!repository.saveDomain(request, container, status, complete,
                    domain.signal == null ? null : JSON.writeValueAsString(domain.signal),
                    JSON.writeValueAsString(status.equals("COLLECTING") ? List.of() : domain.snapshots.values()), rules, hits, WorkerActor.RESERVED_ACTOR_FINGERPRINT))
                throw PolicyCollectionTrace.failure("LEASE_LOST");
        } catch (com.securityexpert.nexus.ui2.persistence.policy.PolicyDatabaseFailure unavailable) {
            domain.databaseFailed = true; domain.gap = true;
            databaseGap.accept(new PolicySnapshot.CollectionFailure(container, "POLICY_DB_DOMAIN_WRITE_FAILED"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw PolicyCollectionTrace.failure("INVALID_OR_INCOMPLETE_RESPONSE");
        }
    }
}
