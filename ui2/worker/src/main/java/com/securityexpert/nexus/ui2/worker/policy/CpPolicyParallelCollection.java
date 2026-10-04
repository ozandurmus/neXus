package com.securityexpert.nexus.ui2.worker.policy;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.jobs.transport.*;
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
    private final List<Policy> policies = new ArrayList<>();
    private final Map<String, Integer> domainOrder = new HashMap<>();
    private final Safety safety;
    private final long checkpointInterval = configuredCheckpointInterval().toNanos();

    CpPolicyParallelCollection(CheckPointPolicyCollector collector, PolicyCollectionRepository repository,
            int maximum, LongSupplier clock, DiscoveryRun run, PolicyCollectionRepository.Request request,
            long deadline, BooleanSupplier lease, Consumer<PolicySnapshot> publish, Consumer<CollectionFailure> domainFailure) {
        this.collector = collector; this.repository = repository; this.clock = clock; this.run = run;
        this.request = request; this.deadline = deadline; this.lease = lease; this.publish = publish; this.domainFailure = domainFailure;
        safety = new Safety(maximum);
        pool = Executors.newFixedThreadPool(maximum, worker -> new Thread(worker, "cp-policy-page"));
        completions = new ExecutorCompletionService<>(pool);
    }

    List<PolicySnapshot> collect() {
        int active = 0;
        boolean stopped = false;
        PolicyCollectionTrace.concurrency(1, safety.limit);
        pending.add(new Work(MgmtCliCommands.domainList(), -1, null, 0, 0, 0, this::domains));
        try {
            while (!pending.isEmpty() || active > 0) {
                collector.checkPublication(deadline, lease);
                stopped |= cancelled.getAsBoolean();
                if (stopped) pending.clear();
                while (!stopped && active < safety.limit && !pending.isEmpty()) {
                    Work work = pending.removeFirst();
                    if (work.layer != null && work.layer.failure != null) continue;
                    if (cancelled.getAsBoolean()) { stopped = true; pending.clear(); break; }
                    collector.checkActive(deadline, lease);
                    completions.submit(PolicyCollectionTrace.worker(() -> execute(work)));
                    active++;
                }
                if (active == 0) break;
                Result result = completions.take().get(); active--;
                Work work = result.work;
                if (result.error != null) {
                    if (PolicyCollectionTrace.fatal(result.error)) {
                        if (PolicyCollectionTrace.reason(result.error).endsWith(": CANCELLED")) {
                            stopped = true; pending.clear(); continue;
                        }
                        throw result.error;
                    }
                    if (work.layer != null) {
                        if (retryable(result.error)) safety.pressure();
                        else safety.streak = 0;
                    }
                    stopped |= cancelled.getAsBoolean();
                    if (!stopped && work.layer != null && retryable(result.error) && work.attempt == 0) {
                        pending.addFirst(new Work(work.command, work.gate, work.layer, work.offset, work.end, 1, work.accept));
                    } else if (work.layer != null) {
                        fail(work.layer, result.error, work.offset);
                    } else if (work.failed != null) work.failed.accept(result.error);
                    else throw result.error;
                } else {
                    if (work.layer == null || work.layer.failure == null) {
                        try {
                            work.accept.accept(result.page);
                            if (work.layer != null && work.layer.failure == null) safety.success(result.elapsed);
                        }
                        catch (RuntimeException invalid) {
                            if (PolicyCollectionTrace.fatal(invalid)) throw invalid;
                            if (work.layer == null) {
                                if (work.failed == null) throw invalid;
                                work.failed.accept(invalid);
                            } else {
                                safety.streak = 0;
                                fail(work.layer, invalid, work.offset);
                            }
                        }
                    }
                }
            }
            if (stopped || cancelled.getAsBoolean()) throw PolicyCollectionTrace.failure("CANCELLED");
            collector.checkActive(deadline, lease);
            return policies.stream().sorted(Comparator.comparingInt(p -> domainOrder.get(p.metadata.containerId())))
                .map(p -> p.latest).toList();
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
            return new Result(work, collector.read(session.get(), work.command, work.gate, deadline, lease), null, clock.getAsLong() - started);
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
        boolean found = request.domainRef().isEmpty();
        for (JsonNode domain : root.path("objects")) {
            String uid = required(domain, "uid"), name = required(domain, "name");
            String container = ref(request.sourceId(), uid);
            if (!request.domainRef().isEmpty() && !container.equals(request.domainRef())) continue;
            found = true;
            domainOrder.putIfAbsent(container, domainOrder.size());
            if (repository.beginDomain(request.sourceId(), container, request.automatic()))
                pending.add(new Work(MgmtCliCommands.showPackages(name), 0, null, 0, 0, 0, packages -> packages(uid, name, container, packages),
                    error -> {
                        collector.checkPublication(deadline, lease);
                        domainFailure.accept(new CollectionFailure(container, PolicyCollectionTrace.reason(error), "Packages", 0));
                    }));
        }
        if (!found) throw failure();
    }

    private void packages(String domainUid, String domain, String container, JsonNode root) {
        completeList(root, "packages");
        Set<String> seen = new HashSet<>();
        for (JsonNode node : root.path("packages")) {
            String uid = required(node, "uid"), name = required(node, "name");
            if (!seen.add(uid) || !node.path("access-layers").isArray() || !node.path("installation-targets").isArray()) throw failure();
            List<Target> targets = new ArrayList<>();
            for (JsonNode target : node.path("installation-targets")) {
                String targetUid = target.isTextual() ? target.textValue() : required(target, "uid");
                var enrolled = repository.targets(run.runId(), domain, targetUid);
                if (enrolled.isEmpty()) targets.add(new Target(ref("cp-install-target", request.sourceId(), domainUid, targetUid),
                    target.isObject() ? target.path("name").asText("Unresolved installation target") : "Unresolved installation target", "", "UNKNOWN"));
                else targets.addAll(enrolled);
            }
            var metadata = new Metadata(ref(request.sourceId(), domainUid, uid), request.sourceId(), "MDS " + request.sourceId(),
                "CP", container, domain, name, Instant.now().toString(), "", targets.stream().distinct().toList());
            Policy policy = new Policy(metadata, domain);
            policies.add(policy);
            Map<String, String> roots = new LinkedHashMap<>();
            for (JsonNode layer : node.path("access-layers")) roots.put(required(layer, "uid"), required(layer, "name"));
            roots.forEach((layerUid, layerName) -> addLayer(policy, layerUid, layerName));
            policy.nat = new Layer(policy, "", "NAT", ref("cp-nat", container, uid), 2,
                offset -> MgmtCliCommands.showNatRulebase(domain, name, offset));
            enqueue(policy.nat, 0, 0);
        }
    }

    private void addLayer(Policy policy, String uid, String name) {
        if (policy.layers.containsKey(uid)) return;
        if (policy.layers.size() >= MAX_PAGES) throw failure();
        Layer layer = new Layer(policy, uid, name, ref("cp-layer", policy.domain, uid), 1,
            offset -> MgmtCliCommands.showAccessRulebase(policy.domain, name, offset));
        policy.layers.put(uid, layer); enqueue(layer, 0, 0);
    }

    private void enqueue(Layer layer, int offset, int end) {
        if (++layer.issued > MAX_PAGES) throw failure();
        pending.add(new Work(layer.command.apply(offset), layer.gate, layer, offset, end, 0,
            page -> page(layer, offset, end, page)));
    }

    private void page(Layer layer, int offset, int end, JsonNode page) {
        int total = pageTotal(page, offset, layer.total, layer.gate);
        if (layer.gate == 1 && !layer.uid.equals(required(page, "uid"))) throw failure();
        int to = total == 0 ? 0 : page.path("to").intValue();
        if (end > 0 && to > end) throw failure();
        for (JsonNode object : page.path("objects-dictionary")) required(object, "uid");
        layer.pages.put(offset, page);
        progress(layer.policy);
        if (layer.total < 0) {
            layer.total = total;
            int width = layer.gate == 1 ? 100 : 500;
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
        if (layer.gate == 1) {
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
    }

    private void progress(Policy policy) {
        int rules = policy.layers.values().stream().flatMap(l -> l.pages.values().stream()).mapToInt(p -> ruleCount(p.path("rulebase"))).sum();
        int started = (int) policy.layers.values().stream().filter(l -> !l.pages.isEmpty() || l.failure != null).count();
        PolicyCollectionTrace.layer(started, policy.layers.size(), rules);
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
            Consumer<JsonNode> accept, Consumer<RuntimeException> failed) {
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
        final int gate;
        final IntFunction<String> command;
        final TreeMap<Integer, JsonNode> pages = new TreeMap<>();
        int total = -1, issued;
        boolean done;
        CollectionFailure failure;
        Layer(Policy policy, String uid, String name, String ref, int gate, IntFunction<String> command) {
            this.policy = policy; this.uid = uid; this.name = name; this.ref = ref; this.gate = gate; this.command = command;
        }
    }
}
