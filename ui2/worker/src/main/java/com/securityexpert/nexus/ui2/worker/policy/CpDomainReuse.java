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
        boolean reused, gap, databaseFailed, sameRequest;
        int expected = -1;
        final Map<String, PolicySnapshot> snapshots = new LinkedHashMap<>();
    }
    CpDomainReuse(PolicyCollectionRepository repository, PolicyCollectionRepository.Request request) {
        this.repository = repository; this.request = request;
    }
    void begin(String container) {
        Domain domain = new Domain();
        // Read before recording the current pending attempt, including empty complete domains.
        var current = repository.domainForRequest(request, container);
        domain.sameRequest = current.isPresent();
        domain.previous = current.orElseGet(() -> repository.previousDomain(request.sourceId(), container).orElse(null));
        domains.put(container, domain);
        if (!domain.sameRequest) save(container, domain, "COLLECTING", false);
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
        if (domain.sameRequest && domain.previous != null && domain.previous.signalJson() != null && !"[]".equals(domain.previous.snapshotsJson())) {
            try {
                Signal previous = domain.previous.signalJson() == null ? null : JSON.readValue(domain.previous.signalJson(), Signal.class);
                if (previous == null || domain.signal == null || !domain.signal.equals(previous))
                    throw PolicyCollectionTrace.failure("CHECKPOINT_SOURCE_VERSION_NOT_EVALUABLE");
            } catch (java.io.IOException invalid) { throw PolicyCollectionTrace.failure("CHECKPOINT_SOURCE_VERSION_NOT_EVALUABLE"); }
        }
        if (!domain.databaseFailed && (request.mode() == PolicyCollectionRepository.Mode.CHANGED_ONLY || domain.sameRequest) && domain.signal != null
                && domain.previous != null && (domain.previous.complete() || domain.sameRequest) && domain.previous.signalJson() != null) {
            try {
                Signal previous = JSON.readValue(domain.previous.signalJson(), Signal.class);
                List<PolicySnapshot> snapshots = JSON.readValue(domain.previous.snapshotsJson(),
                    JSON.getTypeFactory().constructCollectionType(List.class, PolicySnapshot.class));
                boolean identity = snapshots.stream().allMatch(snapshot -> snapshot.metadata().sourceId().equals(request.sourceId())
                    && snapshot.metadata().containerId().equals(container));
                if (identity && domain.signal.equals(previous)) {
                    snapshots.stream().filter(CpDomainReuse::ruleComplete)
                        .forEach(snapshot -> domain.snapshots.put(snapshot.metadata().id(), snapshot));
                    domain.reused = domain.previous.complete() && domain.snapshots.size() == snapshots.size();
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
    PolicySnapshot resumedPackage(String container, String policy) {
        Domain domain = domains.get(container);
        if (domain == null || !domain.sameRequest || domain.databaseFailed) return null;
        PolicySnapshot stored = domain.snapshots.get(policy);
        if (stored != null || domain.signal == null) return stored;
        try {
            var checkpoint = repository.resumedUnit(request, policy, JSON.writeValueAsString(domain.signal));
            if (checkpoint.isEmpty()) return null;
            stored = JSON.readValue(checkpoint.get(), PolicySnapshot.class);
            if (!stored.metadata().sourceId().equals(request.sourceId()) || !stored.metadata().containerId().equals(container)
                    || !stored.metadata().id().equals(policy) || !ruleComplete(stored))
                throw PolicyCollectionTrace.failure("CHECKPOINT_IDENTITY_MISMATCH");
            domain.snapshots.put(policy, stored);
            return stored;
        } catch (java.io.IOException invalid) { throw PolicyCollectionTrace.failure("CHECKPOINT_SOURCE_VERSION_NOT_EVALUABLE"); }
    }
    private void checkpoint(PolicySnapshot snapshot, Domain domain) {
        if (request.jobId().isEmpty() || domain.databaseFailed || domain.signal == null || !ruleComplete(snapshot)) return;
        try {
            if (!repository.saveUnit(request, snapshot.metadata().id(), JSON.writeValueAsString(domain.signal), JSON.writeValueAsString(snapshot)))
                throw PolicyCollectionTrace.failure("LEASE_LOST");
        } catch (com.securityexpert.nexus.ui2.persistence.policy.PolicyDatabaseFailure unavailable) {
            domain.databaseFailed = true; domain.gap = true;
            databaseGap.accept(new PolicySnapshot.CollectionFailure(snapshot.metadata().containerId(), "POLICY_DB_UNIT_WRITE_FAILED"));
        } catch (java.io.IOException invalid) { throw PolicyCollectionTrace.failure("INVALID_OR_INCOMPLETE_RESPONSE"); }
    }
    void snapshot(PolicySnapshot snapshot) {
        Domain domain = domains.get(snapshot.metadata().containerId());
        if (domain == null || domain.reused) return;
        checkpoint(snapshot, domain);
        domain.snapshots.put(snapshot.metadata().id(), snapshot);
        update(snapshot.metadata().containerId(), domain);
    }
    void planned(String container, int packages) {
        Domain domain = domains.get(container); domain.expected = packages; update(container, domain);
    }
    // Only proven non-rule gaps are advisory. Unknown reasons remain fail-closed.
    private static boolean ruleComplete(PolicySnapshot snapshot) {
        return snapshot.failures().stream().allMatch(f -> Set.of(
            "POLICY_HITS_UNAVAILABLE", "POLICY_DB_INVENTORY_WRITE_FAILED").contains(f.reason()));
    }
    private void update(String container, Domain domain) {
        // Publish each terminal domain before inventory collection or a later drain can stop the run.
        boolean terminal = domain.expected >= 0 && domain.snapshots.size() == domain.expected
            && domain.snapshots.values().stream().flatMap(s -> s.failures().stream())
                .noneMatch(f -> f.reason().equals("COLLECTION_PENDING"));
        boolean complete = terminal && !domain.gap
            && domain.snapshots.values().stream().allMatch(CpDomainReuse::ruleComplete);
        save(container, domain, terminal ? "COLLECTED" : "COLLECTING", complete);
    }
    void gap(String container) {
        if (domains.containsKey(container)) { Domain domain = domains.get(container); domain.gap = true; save(container, domain, "COLLECTED", false); }
    }
    void finish() {
        domains.forEach((container, domain) -> {
            save(container, domain, domain.reused ? "REUSED" : "COLLECTED", !domain.gap
                && (domain.reused || domain.expected >= 0 && domain.snapshots.size() == domain.expected)
                && domain.snapshots.values().stream().allMatch(CpDomainReuse::ruleComplete));
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
                    domain.signal != null ? JSON.writeValueAsString(domain.signal) : null,
                    JSON.writeValueAsString(status.equals("COLLECTING") || domain.reused && !complete ? List.of() : domain.snapshots.values()), rules, hits, WorkerActor.RESERVED_ACTOR_FINGERPRINT))
                throw PolicyCollectionTrace.failure("LEASE_LOST");
        } catch (com.securityexpert.nexus.ui2.persistence.policy.PolicyDatabaseFailure unavailable) {
            domain.databaseFailed = true; domain.gap = true;
            databaseGap.accept(new PolicySnapshot.CollectionFailure(container, "POLICY_DB_DOMAIN_WRITE_FAILED"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw PolicyCollectionTrace.failure("INVALID_OR_INCOMPLETE_RESPONSE");
        }
    }
}
