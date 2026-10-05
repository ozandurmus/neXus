package com.securityexpert.nexus.ui2.worker.policy;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.worker.JobCancellationScope;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliCommands;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.worker.policy.CheckPointPolicyCollector.*;

/** One coordinator owns assembly/publication; each executor thread owns one trusted SSH session. */
final class CpPolicyParallelCollection {
    private final CheckPointPolicyCollector collector;
    private final CpDomainReuse reuse;
    private final List<PolicySnapshot> reused = new ArrayList<>();
    private final PolicyCollectionRepository repository;
    private final DiscoveryRun run;
    private final PolicyCollectionRepository.Request request;
    private final long deadline;
    private final LongSupplier clock;
    private final BooleanSupplier lease, cancelled = JobCancellationScope.requested();
    private final Consumer<PolicySnapshot> publish;
    private final Consumer<CollectionFailure> domainFailure;
    private final ThreadLocal<TransportSession> session = new ThreadLocal<>();
    private final Set<TransportSession> sessions = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean closeFailed = new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService pool;
    private final CompletionService<Result> completions;
    private final Deque<Work> pending = new ArrayDeque<>();
    private final Map<String, String> objectDomains = new LinkedHashMap<>();
    private final List<Policy> policies = new ArrayList<>();
    private final Map<String, Integer> domainOrder = new HashMap<>();
    private final Safety safety;
    private final long checkpointInterval = configuredCheckpointInterval().toNanos();

    CpPolicyParallelCollection(CheckPointPolicyCollector collector, PolicyCollectionRepository repository,
            int maximum, LongSupplier clock, DiscoveryRun run, PolicyCollectionRepository.Request request,
            long deadline, BooleanSupplier lease, Consumer<PolicySnapshot> publish, Consumer<CollectionFailure> domainFailure, CpDomainReuse reuse) {
        this.reuse = reuse; this.collector = collector; this.repository = repository; this.clock = clock; this.run = run;
        this.request = request; this.deadline = deadline; this.lease = lease; this.publish = publish; this.domainFailure = domainFailure;
        safety = new Safety(maximum);
        pool = Executors.newFixedThreadPool(maximum, worker -> new Thread(worker, "cp-policy-page"));
        completions = new ExecutorCompletionService<>(pool);
    }

    List<PolicySnapshot> collect() {
        int active = 0;
        boolean stopped = false, objectsPhase = false;
        PolicyCollectionTrace.concurrency(1, safety.limit);
        pending.add(new Work(MgmtCliCommands.domainList(), -1, null, 0, 0, 0, this::domains));
        try {
            while (true) {
                if (pending.isEmpty() && active == 0) {
                    if (stopped || objectsPhase) break;
                    objectsPhase = true;
                    collector.checkActive(deadline, lease);
                    for (String type : CpPolicyGates.OBJECT_TYPES)
                        objectDomains.forEach((container, domain) -> enqueueObjects(domain, container, type));
                    if (pending.isEmpty()) break;
                }
                collector.checkPublication(deadline, lease);
                stopped |= cancelled.getAsBoolean();
                if (stopped) pending.clear();
                while (!stopped && active < safety.limit && !pending.isEmpty()) {
                    Work work = pending.removeFirst();
                    if (work.layer != null && (work.layer.failure != null || work.gate != work.layer.gate)) continue;
                    if (cancelled.getAsBoolean()) { stopped = true; pending.clear(); break; }
                    collector.checkActive(deadline, lease);
                    completions.submit(PolicyCollectionTrace.worker(() -> execute(work)));
                    active++;
                }
                if (active == 0) break;
                Result result = completions.take().get(); active--;
                Work work = result.work;
                if (work.inventory != null) work.inventory.elapsed = clock.getAsLong() - work.inventory.started;
                if (work.layer != null && work.gate != work.layer.gate) {
                    if (result.error != null && PolicyCollectionTrace.fatal(result.error)) throw result.error;
                    continue;
                }
                if (result.error != null) {
                    if (PolicyCollectionTrace.fatal(result.error)) {
                        if (PolicyCollectionTrace.reason(result.error).endsWith(": CANCELLED")) {
                            stopped = true; pending.clear(); continue;
                        }
                        throw result.error;
                    }
                    if (work.layer != null || (work.gate >= CpPolicyGates.OBJECT_BASE && work.gate < CpPolicyGates.LAST_PUBLISHED_SESSION)) {
                        if (retryable(result.error)) safety.pressure();
                        else safety.streak = 0;
                    }
                    stopped |= cancelled.getAsBoolean();
                    if (!stopped && work.layer != null && retryPage(work, result.error)) {
                        pending.addFirst(new Work(work.command, work.gate, work.layer, work.offset, work.end, 1, work.accept));
                    } else if (!stopped && work.layer != null && work.gate == 3) {
                        fallbackHits(work.layer);
                    } else if (work.layer != null) {
                        fail(work.layer, result.error, work.offset);
                    } else if (work.failed != null) work.failed.accept(result.error);
                    else throw result.error;
                } else {
                    if (work.layer == null || work.layer.failure == null) {
                        try {
                            work.accept.accept(result.page);
                            if ((work.layer != null && work.layer.failure == null) || (work.gate >= CpPolicyGates.OBJECT_BASE && work.gate < CpPolicyGates.LAST_PUBLISHED_SESSION)) safety.success(result.elapsed);
                        }
                        catch (RuntimeException invalid) {
                            if (PolicyCollectionTrace.fatal(invalid)) throw invalid;
                            if (work.layer == null) {
                                if (work.failed == null) throw invalid;
                                work.failed.accept(invalid);
                            } else {
                                safety.streak = 0;
                                if (work.gate == 1 || work.gate == 3)
                                    JobTranscriptScope.add("job", "note", "invalid rulebase " + preflightStructure("", result.page));
                                if (retryPage(work, invalid))
                                    pending.addFirst(new Work(work.command, work.gate, work.layer, work.offset, work.end, 1, work.accept));
                                else if (work.gate == 3) fallbackHits(work.layer);
                                else fail(work.layer, invalid, work.offset);
                            }
                        }
                    }
                }
            }
            if (stopped || cancelled.getAsBoolean()) throw PolicyCollectionTrace.failure("CANCELLED");
            collector.checkActive(deadline, lease);
            reuse.finish();
            List<PolicySnapshot> result = new ArrayList<>(reused);
            policies.forEach(p -> result.add(p.latest));
            return result.stream().sorted(Comparator.comparingInt(p -> domainOrder.get(p.metadata().containerId()))).toList();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw PolicyCollectionTrace.failure("INTERRUPTED");
        } catch (ExecutionException failed) {
            throw PolicyCollectionTrace.failure("WORKER_FAILED");
        } finally {
            // Interrupt exceptional exits; normal/cancelled exits have drained every submitted read.
            pool.shutdownNow();
            boolean interrupted = Thread.interrupted();
            try {
                if (!pool.awaitTermination(collector.cleanupTimeoutSeconds(), TimeUnit.SECONDS)) throw PolicyCollectionTrace.failure("SESSION_CLEANUP_TIMEOUT");
            } catch (InterruptedException interruptedCleanup) {
                interrupted = true;
                throw PolicyCollectionTrace.failure("INTERRUPTED");
            } finally {
                for (TransportSession owned : sessions) release(owned);
                if (interrupted) Thread.currentThread().interrupt();
                if (closeFailed.get()) throw PolicyCollectionTrace.failure("SESSION_CLEANUP_FAILED");
            }
        }
    }

    private Result execute(Work work) {
        long started = clock.getAsLong();
        if (work.inventory != null && work.inventory.started == null) work.inventory.started = started;
        try {
            if (closeFailed.get()) throw PolicyCollectionTrace.failure("SESSION_CLEANUP_FAILED");
            collector.checkActive(deadline, lease);
            if (session.get() == null) {
                PolicyCollectionTrace.step("connect", request.sourceId());
                long remaining = deadline - clock.getAsLong();
                if (remaining <= 0) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
                ConnectResult connected = JobTranscriptScope.withoutRecording(() -> collector.transport.connect(
                    new ConnectionTarget(run.managementAddress(), run.managementAddress(), 22),
                    new ConnectSpec(run.credentialReferenceId(), PersistedManagementEndpointTrustResolver.scopeRef(run.managementAddress(), 22), Optional.empty()),
                    Duration.ofNanos(Math.min(Duration.ofSeconds(30).toNanos(), remaining))));
                if (!(connected instanceof ConnectResult.Authenticated authenticated))
                    throw PolicyCollectionTrace.failure(connected.getClass().getSimpleName());
                session.set(authenticated.session()); sessions.add(authenticated.session());
            }
            return new Result(work, collector.read(session.get(), work.command, work.gate, deadline, lease, work.layer == null ? -1 : work.layer.total), null, clock.getAsLong() - started);
        } catch (RuntimeException error) {
            // An unhealthy shell must not carry the retried page or any sibling read.
            closeSession();
            if (closeFailed.get()) error = PolicyCollectionTrace.failure("SESSION_CLEANUP_FAILED");
            return new Result(work, null, error, clock.getAsLong() - started);
        }
    }

    private void closeSession() {
        TransportSession owned = session.get(); session.remove();
        release(owned);
    }

    private void release(TransportSession owned) {
        if (owned == null || !sessions.remove(owned)) return;
        JobTranscriptScope.withoutRecording(() -> {
            try { collector.transport.disconnect(owned); }
            catch (RuntimeException failed) {
                closeFailed.set(true);
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "POLICY_SESSION_CLOSE_FAILED");
            }
            return null;
        });
    }

    private void domains(JsonNode root) {
        completeList(root, "objects");
        PolicyCollectionTrace.domains((int) java.util.stream.StreamSupport.stream(root.path("objects").spliterator(), false)
            .filter(d -> request.domainRef().isEmpty() || ref(request.sourceId(), required(d, "uid")).equals(request.domainRef())).count());
        boolean found = request.domainRef().isEmpty();
        for (JsonNode domain : root.path("objects")) {
            String uid = required(domain, "uid"), name = required(domain, "name");
            String container = ref(request.sourceId(), uid);
            if (!request.domainRef().isEmpty() && !container.equals(request.domainRef())) continue;
            found = true;
            domainOrder.putIfAbsent(container, domainOrder.size());
            if (repository.beginDomain(request.sourceId(), container, request.automatic())) {
                reuse.begin(container);
                if (request.mode() == PolicyCollectionRepository.Mode.FULL) domainSignal(uid, name, container, null);
                else pending.add(new Work(MgmtCliCommands.showLastPublishedSession(name), CpPolicyGates.LAST_PUBLISHED_SESSION,
                    null, 0, 0, 0, page -> domainSignal(uid, name, container, page),
                    error -> domainSignal(uid, name, container, null)));
            } else PolicyCollectionTrace.packages(0);
        }
        if (!found) throw failure();
    }

    private void domainSignal(String uid, String name, String container, JsonNode signal) {
        List<PolicySnapshot> snapshots = reuse.decide(container, signal);
        if (snapshots == null) enqueuePackages(uid, name, container, new PackagePages(collector.packagePageSize), 0, collector.packagePageSize);
        else {
            objectDomains.put(container, name);
            snapshots.forEach(publish);
            reused.addAll(snapshots);
        }
    }

    private void enqueuePackages(String uid, String domain, String container, PackagePages listing, int offset, int limit) {
        pending.add(new Work(MgmtCliCommands.showPackages(domain, offset, limit), limit == 50 ? CpPolicyGates.PACKAGES_50 : 0, null, offset, 0, 0,
            page -> {
                if (listing.add(page, offset)) packages(uid, domain, container, listing.items);
                else enqueuePackages(uid, domain, container, listing, listing.to, limit);
            }, error -> {
                collector.checkPublication(deadline, lease);
                if (limit == 50) enqueuePackages(uid, domain, container, new PackagePages(20), 0, 20);
                else domainFailure.accept(new CollectionFailure(container, PolicyCollectionTrace.reason(error), "Packages", offset));
            }));
    }

    private void packages(String domainUid, String domain, String container, List<JsonNode> items) {
        objectDomains.put(container, domain);
        reuse.planned(container, items.size());
        PolicyCollectionTrace.packages(items.size());
        Set<String> seen = new HashSet<>();
        for (JsonNode node : items) {
            String uid = required(node, "uid"), name = required(node, "name");
            if (!seen.add(uid)) throw failure();
            List<Target> targets = collector.installationTargets(run, request, domainUid, domain, node);
            var metadata = new Metadata(ref(request.sourceId(), domainUid, uid), request.sourceId(), "MDS " + request.sourceId(),
                "CP", container, domain, name, Instant.now().toString(), "", targets.stream().distinct().toList());
            Policy policy = new Policy(metadata, domain);
            policies.add(policy);
            Map<String, String> roots = new LinkedHashMap<>();
            for (JsonNode layer : node.path("access-layers")) {
                JsonNode scoped = CheckPointPolicyCollector.scopedLayer(layer);
                roots.put(required(scoped, "uid"), required(scoped, "name"));
            }
            roots.forEach((layerUid, layerName) -> addLayer(policy, layerUid, layerName));
            policy.nat = new Layer(policy, "", "NAT", ref("cp-nat", container, uid), 2,
                offset -> MgmtCliCommands.showNatRulebase(domain, name, offset));
            enqueue(policy.nat, 0, 0);
        }
    }

    private void addLayer(Policy policy, String uid, String name) {
        if (policy.layers.containsKey(uid)) return;
        if (policy.layers.size() >= MAX_PAGES) throw failure();
        boolean hits = com.securityexpert.nexus.ui2.jobs.policy.PolicyHitGates.enabled(collector.gates(), true);
        Layer layer = new Layer(policy, uid, name, ref("cp-layer", policy.domain, uid), hits ? 3 : 1,
            offset -> MgmtCliCommands.showAccessRulebase(policy.domain, name, offset) + (hits ? " show-hits true" : ""));
        policy.layers.put(uid, layer); enqueue(layer, 0, 0);
    }

    private void enqueue(Layer layer, int offset, int end) {
        if (++layer.issued > MAX_PAGES) throw failure();
        pending.add(new Work(layer.command.apply(offset), layer.gate, layer, offset, end, 0,
            page -> page(layer, offset, end, page)));
    }

    private void page(Layer layer, int offset, int end, JsonNode page) {
        int total = pageTotal(page, offset, layer.total, layer.gate);
        if ((layer.gate == 1 || layer.gate == 3) && !layer.uid.equals(required(page, "uid"))) throw failure();
        int to = total == 0 ? 0 : page.path("to").intValue();
        if (end > 0 && to > end) throw failure();
        for (JsonNode object : page.path("objects-dictionary")) required(object, "uid");
        if (layer.pages.put(offset, page) == null) layer.rulesFetched += ruleCount(page.path("rulebase"));
        progress(layer.policy);
        if (layer.total < 0) {
            layer.total = total;
            int width = (layer.gate == 1 || layer.gate == 3) ? 100 : 500;
            for (int next = to; next < total; next += width) enqueue(layer, next, Math.min(total, next + width));
        } else if (to < end) enqueue(layer, to, end);
        int next = 0;
        for (var entry : layer.pages.entrySet()) {
            if (entry.getKey() != next) return;
            next = total == 0 ? 0 : entry.getValue().path("to").intValue();
        }
        if (next != total) return;
        layer.done = true;
        Policy policy = layer.policy;
        if (layer.gate == 1 || layer.gate == 3) {
            Map<String, JsonNode> dictionary = new HashMap<>();
            // Use ordered pages so dictionary collision semantics equal the serial collector.
            for (Layer sibling : policy.layers.values()) if (sibling.done && sibling.failure == null)
                for (JsonNode p : sibling.pages.values()) for (JsonNode object : p.path("objects-dictionary")) dictionary.put(required(object, "uid"), object);
            Set<String> children = new LinkedHashSet<>();
            for (JsonNode p : layer.pages.values()) inline(p.path("rulebase"), children, 0);
            for (String child : children) if (!policy.layers.containsKey(child)) {
                JsonNode object = dictionary.get(child);
                if (object == null) policy.extra.add(new CollectionFailure(ref("cp-layer", policy.domain, child), "INLINE_LAYER_NAME_MISSING"));
                else addLayer(policy, child, required(object, "name"));
            }
        }
        checkpoint(policy, layer);
    }

    private void fallbackHits(Layer layer) {
        layer.gate = 1;
        layer.command = offset -> MgmtCliCommands.showAccessRulebase(layer.policy.domain, layer.name, offset);
        layer.pages.clear(); layer.total = -1; layer.issued = 0; layer.rulesFetched = 0; layer.done = false;
        enqueue(layer, 0, 0);
    }

    private void enqueueObjects(String domain, String container, String type) {
        if (collector.reuseObjects(request.sourceId(), container, type)) return;
        if (!CheckPointPolicyCollector.collectObjectsEnabled() || type.equals("gateways-and-servers") && domain.equalsIgnoreCase("Global")) {
            collector.storeObjects(request.sourceId(), container, type, List.of(), 0, 0,
                "UNSUPPORTED", "COLLECTION_SKIPPED", deadline, lease);
        } else enqueueObjectPage(domain, container, type, new PackagePages(50, "objects"), 0);
    }

    private void enqueueObjectPage(String domain, String container, String type, PackagePages listing, int offset) {
        int gate = CpPolicyGates.OBJECT_BASE + CpPolicyGates.OBJECT_TYPES.indexOf(type);
        pending.add(new Work(MgmtCliCommands.showPolicyObjects(domain, type, offset), gate, null, offset, 0, 0,
            page -> {
                if (listing.add(page, offset)) collector.storeObjects(request.sourceId(), container, type,
                    listing.items, listing.count, listing.elapsed, "RESOLVED", "", deadline, lease);
                else enqueueObjectPage(domain, container, type, listing, listing.to);
            }, error -> collector.storeIncompleteObjects(request.sourceId(), container, type, listing,
                listing.elapsed, error, deadline, lease), listing));
    }

    private void fail(Layer layer, RuntimeException error, int offset) {
        if (layer.failure != null) return;
        layer.failure = new CollectionFailure(layer.ref, PolicyCollectionTrace.reason(error), layer.name, offset);
        layer.pages.clear();
        checkpoint(layer.policy, layer);
    }

    static Duration configuredCheckpointInterval() {
        Duration interval = Duration.ofSeconds(Long.parseLong(System.getProperty("ui2.policy.cp.checkpoint-interval",
            System.getenv().getOrDefault("UI2_POLICY_CP_CHECKPOINT_INTERVAL", "60"))));
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("POLICY_CHECKPOINT_INTERVAL_MUST_BE_POSITIVE");
        return interval;
    }

    private void checkpoint(Policy policy, Layer changed) {
        progress(policy);
        boolean complete = policy.layers.values().stream().allMatch(l -> l.done || l.failure != null)
            && (policy.nat.done || policy.nat.failure != null);
        long now = clock.getAsLong();
        List<JsonNode> access = new ArrayList<>();
        List<CollectionFailure> failures = new ArrayList<>(policy.extra);
        for (Layer layer : policy.layers.values()) {
            if (layer.done && layer.failure == null) access.addAll(layer.pages.values());
            else failures.add(layer.failure != null ? layer.failure : new CollectionFailure(layer.ref, "COLLECTION_PENDING", layer.name, 0));
        }
        Layer nat = policy.nat;
        List<JsonNode> natPages = nat.done && nat.failure == null ? List.copyOf(nat.pages.values()) : List.of();
        if (!nat.done || nat.failure != null) failures.add(nat.failure != null ? nat.failure : new CollectionFailure(nat.ref, "COLLECTION_PENDING", "NAT", 0));
        PolicySnapshot snapshot;
        try { snapshot = snapshot(policy.metadata, access, natPages, failures); }
        catch (IllegalArgumentException invalid) {
            if (changed.failure != null) throw invalid;
            changed.done = false;
            fail(changed, invalid, 0); return;
        }
        // Mapping failures belong to the changed layer, even between persisted checkpoints.
        if (!complete && policy.lastCheckpoint != null && now - policy.lastCheckpoint < checkpointInterval) return;
        collector.checkPublication(deadline, lease);
        publish.accept(snapshot); policy.latest = snapshot;
        policy.lastCheckpoint = clock.getAsLong();
        if (complete) PolicyCollectionTrace.done(policy.metadata.id());
    }

    private void progress(Policy policy) {
        int rules = policy.layers.values().stream().mapToInt(l -> l.rulesFetched).sum();
        int started = (int) policy.layers.values().stream().filter(l -> !l.pages.isEmpty() || l.failure != null).count();
        rules += policy.nat.rulesFetched;
        PolicyCollectionTrace.layer(policy.metadata.id(), started, policy.layers.size(), rules);
    }

    private static boolean retryPage(Work work, RuntimeException error) {
        return work.attempt == 0 && (retryable(error) || (work.gate == 1 || work.gate == 3)
            && PolicyCollectionTrace.reason(error).endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"));
    }

    static boolean retryable(RuntimeException error) {
        String reason = PolicyCollectionTrace.reason(error);
        return Set.of("TIMEOUT", "TimedOut", "ChannelFailed", "API_SESSION_PRESSURE")
            .stream().anyMatch(code -> reason.endsWith(": " + code));
    }

    static final class Safety {
        final int maximum;
        int limit, streak;
        Safety(int maximum) { this.maximum = maximum; limit = Math.min(2, maximum); }
        void success(long elapsed) {
            if (elapsed >= Duration.ofSeconds(60).toNanos()) { streak = 0; return; }
            if (++streak == 3) { streak = 0; change(maximum); }
        }
        void pressure() { streak = 0; change(Math.max(1, limit / 2)); }
        private void change(int next) {
            if (next != limit) { PolicyCollectionTrace.concurrency(limit, next); limit = next; }
        }
    }

    private record Work(String command, int gate, Layer layer, int offset, int end, int attempt,
            Consumer<JsonNode> accept, Consumer<RuntimeException> failed, PackagePages inventory) {
        Work(String command, int gate, Layer layer, int offset, int end, int attempt,
                Consumer<JsonNode> accept, Consumer<RuntimeException> failed) {
            this(command, gate, layer, offset, end, attempt, accept, failed, null);
        }
        Work(String command, int gate, Layer layer, int offset, int end, int attempt, Consumer<JsonNode> accept) {
            this(command, gate, layer, offset, end, attempt, accept, null);
        }
    }
    private record Result(Work work, JsonNode page, RuntimeException error, long elapsed) {}
    private static final class Policy {
        final Metadata metadata;
        final String domain;
        final Map<String, Layer> layers = new LinkedHashMap<>();
        final List<CollectionFailure> extra = new ArrayList<>();
        Layer nat;
        PolicySnapshot latest;
        Long lastCheckpoint;
        Policy(Metadata metadata, String domain) { this.metadata = metadata; this.domain = domain; }
    }
    private static final class Layer {
        final Policy policy;
        final String uid, name, ref;
        int gate;
        IntFunction<String> command;
        final TreeMap<Integer, JsonNode> pages = new TreeMap<>();
        int total = -1, issued, rulesFetched;
        boolean done;
        CollectionFailure failure;
        Layer(Policy policy, String uid, String name, String ref, int gate, IntFunction<String> command) {
            this.policy = policy; this.uid = uid; this.name = name; this.ref = ref; this.gate = gate; this.command = command;
        }
    }
}
