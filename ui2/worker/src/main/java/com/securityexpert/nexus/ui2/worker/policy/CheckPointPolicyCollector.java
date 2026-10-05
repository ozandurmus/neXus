package com.securityexpert.nexus.ui2.worker.policy;

import java.time.*;
import java.util.*;
import java.util.function.*;
import com.fasterxml.jackson.databind.*;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates;
import com.securityexpert.nexus.ui2.jobs.policy.PolicyHitGates;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliCommands;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/** Trusted MDS sessions; complete sibling layers survive a failed layer. */
public final class CheckPointPolicyCollector {
    static final int MAX_PAGES = 200;
    final DeviceTransport transport;
    private final int maxSessions;
    private final GateRegistryPort gates;
    private final PolicyCollectionRepository repository;
    private final Duration jobTimeout;
    private final Duration readTimeout;
    final int packagePageSize;
    private final LongSupplier nanoTime;
    private final ObjectMapper json = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public CheckPointPolicyCollector(DeviceTransport transport, GateRegistryPort gates, PolicyCollectionRepository repository) {
        this(transport, gates, repository, Duration.ofSeconds(Long.parseLong(
            System.getenv().getOrDefault("UI2_POLICY_RUN_DEADLINE_SECONDS", "7200"))));
    }
    public CheckPointPolicyCollector(DeviceTransport transport, GateRegistryPort gates, PolicyCollectionRepository repository, Duration jobTimeout) {
        this(transport, gates, repository, jobTimeout, configuredMaxSessions(), System::nanoTime);
    }
    CheckPointPolicyCollector(DeviceTransport transport, GateRegistryPort gates, PolicyCollectionRepository repository, Duration jobTimeout, LongSupplier nanoTime) {
        this(transport, gates, repository, jobTimeout, 1, nanoTime);
    }
    public CheckPointPolicyCollector(DeviceTransport transport, GateRegistryPort gates, PolicyCollectionRepository repository, Duration jobTimeout, int maxSessions) {
        this(transport, gates, repository, jobTimeout, maxSessions, System::nanoTime);
    }
    CheckPointPolicyCollector(DeviceTransport transport, GateRegistryPort gates, PolicyCollectionRepository repository, Duration jobTimeout, int maxSessions, LongSupplier nanoTime) {
        if (maxSessions < 1 || maxSessions > 4) throw new IllegalArgumentException("POLICY_MAX_SESSIONS_MUST_BE_1_TO_4");
        this.maxSessions = maxSessions;
        this.readTimeout = configuredReadTimeout();
        this.packagePageSize = 50;
        if (jobTimeout.isZero() || jobTimeout.isNegative()) throw new IllegalArgumentException("POLICY_DEADLINE_MUST_BE_POSITIVE");
        this.transport = transport; this.gates = gates; this.repository = repository;
        this.jobTimeout = jobTimeout; this.nanoTime = nanoTime;
    }

    /** Per-command policy read budget, in seconds. */
    static Duration configuredReadTimeout() {
        Duration timeout = Duration.ofSeconds(Long.parseLong(System.getProperty("ui2.policy.cp.read-timeout",
            System.getenv().getOrDefault("UI2_POLICY_CP_READ_TIMEOUT", "300"))));
        if (timeout.isZero() || timeout.isNegative() || timeout.toMillis() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("POLICY_READ_TIMEOUT_OUT_OF_RANGE");
        return timeout;
    }

    private TransportSession connect(DiscoveryRun run, long deadline, BooleanSupplier lease) {
        checkActive(deadline, lease);
        PolicyCollectionTrace.step("connect", "cp-policy-source");
        var result = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(() -> transport.connect(
            new ConnectionTarget(run.managementAddress(), com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress.host(run.managementAddress()), com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress.port(run.managementAddress(), 22)),
            new ConnectSpec(run.credentialReferenceId(), PersistedManagementEndpointTrustResolver.scopeRef(com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress.host(run.managementAddress()), com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress.port(run.managementAddress(), 22)), Optional.empty()),
            Duration.ofNanos(Math.min(Duration.ofSeconds(30).toNanos(), deadline - nanoTime.getAsLong()))));
        if (!(result instanceof ConnectResult.Authenticated connected)) throw PolicyCollectionTrace.failure(result.getClass().getSimpleName());
        return connected.session();
    }

    GateRegistryPort gates() { return gates; }

    long cleanupTimeoutSeconds() { return readTimeout.toSeconds() + 150; }

    static int configuredMaxSessions() {
        return Integer.parseInt(System.getProperty("ui2.policy.cp.max-sessions",
            System.getenv().getOrDefault("UI2_POLICY_CP_MAX_SESSIONS", "4")));
    }

    public List<PolicySnapshot> collect(DiscoveryRun run, PolicyCollectionRepository.Request request, BooleanSupplier lease) {
        return collect(run, request, lease, snapshot -> {});
    }

    public List<PolicySnapshot> collect(DiscoveryRun run, PolicyCollectionRepository.Request request, BooleanSupplier lease, Consumer<PolicySnapshot> publish) {
        return collect(run, request, lease, publish, failure -> { throw new PolicyCollectionTrace.Failure(failure.reason()); });
    }

    public List<PolicySnapshot> collect(DiscoveryRun run, PolicyCollectionRepository.Request request, BooleanSupplier lease,
            Consumer<PolicySnapshot> publish, Consumer<CollectionFailure> domainFailure) {
        var reuse = new CpDomainReuse(repository, request).onDatabaseGap(domainFailure);
        Consumer<PolicySnapshot> domainPublish = snapshot -> {
            try { publish.accept(snapshot); reuse.snapshot(snapshot); }
            catch (com.securityexpert.nexus.ui2.persistence.policy.PolicyDatabaseFailure unavailable) {
                reuse.gap(snapshot.metadata().containerId());
                domainFailure.accept(new CollectionFailure(snapshot.metadata().id(), "POLICY_DB_SNAPSHOT_WRITE_FAILED"));
            }
        };
        Consumer<CollectionFailure> domainGap = gap -> {
            // Object gaps are reported independently; they cannot invalidate gap-free rule evidence.
            if (!gap.reason().equals("POLICY_DB_INVENTORY_WRITE_FAILED")) reuse.gap(gap.layerRef());
            domainFailure.accept(gap);
        };
        if (!"check_point".equals(run.vendor())) throw failure();
        CpPolicyGates.requireAll(gates);
        if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
        PolicyCollectionTrace.timeout(readTimeout);
        long deadline = nanoTime.getAsLong() + jobTimeout.toNanos();
        long started = nanoTime.getAsLong();
        if (maxSessions > 1) {
            try { return new CpPolicyParallelCollection(this, repository, maxSessions, nanoTime,
                run, request, deadline, lease, domainPublish, domainGap, reuse).collect(); }
            finally { PolicyCollectionTrace.elapsed(nanoTime.getAsLong() - started); }
        }
        TransportSession session = connect(run, deadline, lease);
        try {
            var domains = read(session, MgmtCliCommands.domainList(), -1, deadline, lease);
            completeList(domains, "objects");
            PolicyCollectionTrace.domains((int) java.util.stream.StreamSupport.stream(domains.path("objects").spliterator(), false)
                .filter(d -> request.domainRef().isEmpty() || ref(request.sourceId(), required(d, "uid")).equals(request.domainRef())).count());
            List<PolicySnapshot> snapshots = new ArrayList<>();
            Map<String, String> objectDomains = new LinkedHashMap<>();
            boolean found = request.domainRef().isEmpty();
            for (JsonNode domain : domains.path("objects")) {
                String domainUid = required(domain, "uid"), domainName = required(domain, "name");
                String container = ref(request.sourceId(), domainUid);
                if (!request.domainRef().isEmpty() && !container.equals(request.domainRef())) continue;
                found = true;
                if (!repository.beginDomain(request, container)) { PolicyCollectionTrace.packages(0); continue; }
                reuse.begin(container);
                if (session == null) session = connect(run, deadline, lease);
                JsonNode signal = null;
                if (request.mode() == PolicyCollectionRepository.Mode.CHANGED_ONLY || !request.jobId().isEmpty()) {
                    try { signal = read(session, MgmtCliCommands.showLastPublishedSession(domainName),
                        CpPolicyGates.LAST_PUBLISHED_SESSION, deadline, lease); }
                    catch (RuntimeException unavailable) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unavailable);
                        if (PolicyCollectionTrace.fatal(unavailable)) throw unavailable;
                        transport.disconnect(session); session = connect(run, deadline, lease);
                    }
                }
                List<PolicySnapshot> reused = reuse.decide(container, signal);
                objectDomains.put(container, domainName);
                if (reused != null) {
                    for (PolicySnapshot snapshot : reused) domainPublish.accept(snapshot);
                    snapshots.addAll(reused); continue;
                }
                List<JsonNode> packages;
                try {
                    packages = packagePages(session, domainName, deadline, lease);
                } catch (RuntimeException incomplete) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(incomplete);
                    if (PolicyCollectionTrace.fatal(incomplete)) throw incomplete;
                    checkPublication(deadline, lease);
                    domainGap.accept(new CollectionFailure(container, PolicyCollectionTrace.reason(incomplete), "Packages", reached(incomplete)));
                    transport.disconnect(session); session = null;
                    continue;
                }
                objectDomains.put(container, domainName);
                reuse.planned(container, packages.size());
                PolicyCollectionTrace.packages(packages.size());
                Set<String> seen = new HashSet<>();
                for (JsonNode policy : packages) {
                    String uid = required(policy, "uid"), name = required(policy, "name");
                    if (!seen.add(uid)) throw failure();
                    var resumed = reuse.resumedPackage(container, ref(request.sourceId(), domainUid, uid));
                    if (resumed != null) {
                        domainPublish.accept(resumed); snapshots.add(resumed); PolicyCollectionTrace.done(resumed.metadata().id()); continue;
                    }
                    List<CollectionFailure> failures = new ArrayList<>();
                    List<Target> targets = installationTargets(run, request, domainUid, domainName, policy);
                    Metadata metadata = new Metadata(ref(request.sourceId(), domainUid, uid), request.sourceId(),
                        "MDS " + request.sourceId(), "CP", container, domainName, name, Instant.now().toString(), "", targets.stream().distinct().toList());
                    PolicyCollectionTrace.unit(metadata.id());
                    String natRef = ref("cp-nat", container, uid);
                    failures.add(new CollectionFailure(natRef, "COLLECTION_PENDING", "NAT", 0));
                    long checkpointInterval = CpPolicyParallelCollection.configuredCheckpointInterval().toNanos();
                    long[] lastCheckpoint = {nanoTime.getAsLong() - checkpointInterval};
                    List<JsonNode> access = access(session, domainName, policy.path("access-layers"), deadline, lease, failures,
                        completed -> {
                            var checkpoint = snapshot(metadata, completed, List.of(), failures);
                            // Validate each layer even when its checkpoint write is throttled.
                            if (nanoTime.getAsLong() - lastCheckpoint[0] < checkpointInterval) return;
                            checkPublication(deadline, lease);
                            domainPublish.accept(checkpoint);
                            lastCheckpoint[0] = nanoTime.getAsLong();
                        });
                    List<JsonNode> nat = List.of();
                    try {
                        nat = pages(session, offset -> MgmtCliCommands.showNatRulebase(domainName, name, offset), 2, deadline, lease,
                            count -> PolicyCollectionTrace.rules("nat", count));
                        failures.removeIf(f -> f.layerRef().equals(natRef));
                    } catch (RuntimeException incomplete) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(incomplete);
                        if (PolicyCollectionTrace.fatal(incomplete)) throw incomplete;
                        failures.removeIf(f -> f.layerRef().equals(natRef));
                        failures.add(new CollectionFailure(natRef, PolicyCollectionTrace.reason(incomplete), "NAT", reached(incomplete)));
                    }
                    PolicySnapshot snapshot;
                    try { snapshot = snapshot(metadata, access, nat, failures); }
                    catch (IllegalArgumentException invalid) {
                        failures.add(new CollectionFailure(natRef, PolicyCollectionTrace.reason(invalid), "NAT", 0));
                        snapshot = snapshot(metadata, access, List.of(), failures);
                    }
                    checkPublication(deadline, lease);
                    domainPublish.accept(snapshot);
                    snapshots.add(snapshot);
                    PolicyCollectionTrace.done(metadata.id());
                }
            }
            if (!found) throw failure();
            for (var domain : objectDomains.entrySet()) {
                if (session == null) session = connect(run, deadline, lease);
                try { collectObjects(session, request.sourceId(), domain.getKey(), domain.getValue(), deadline, lease); }
                catch (com.securityexpert.nexus.ui2.persistence.policy.PolicyDatabaseFailure unavailable) {
                    domainGap.accept(new CollectionFailure(domain.getKey(), "POLICY_DB_INVENTORY_WRITE_FAILED"));
                }
            }
            checkActive(deadline, lease);
            reuse.finish();
            return List.copyOf(snapshots);
        } finally {
            if (session != null) transport.disconnect(session);
            PolicyCollectionTrace.elapsed(nanoTime.getAsLong() - started);
        }
    }

    static PolicySnapshot snapshot(Metadata metadata, List<JsonNode> access, List<JsonNode> nat, List<CollectionFailure> failures) {
        // Refresh the publication timestamp so each completed layer can replace its earlier checkpoint.
        var updated = new Metadata(metadata.id(), metadata.sourceId(), metadata.sourceName(), metadata.vendor(), metadata.containerId(),
            metadata.containerName(), metadata.name(), Instant.now().toString(), metadata.artefactRef(), metadata.targets());
        var mapped = new CheckPointPolicyMapper().map(updated, access, nat);
        return new PolicySnapshot(updated, mapped.sections(), mapped.objects(), failures);
    }

    /** Assemble the complete listing before collecting any package in this domain. */
    private List<JsonNode> packagePages(TransportSession session, String domain, long deadline, BooleanSupplier lease) {
        PackagePages listing = new PackagePages(packagePageSize);
        int offset = 0;
        try {
            while (true) {
                JsonNode page = read(session, MgmtCliCommands.showPackages(domain, offset, packagePageSize), CpPolicyGates.PACKAGES_50, deadline, lease);
                if (listing.add(page, offset)) return List.copyOf(listing.items);
                offset = listing.to;
            }
        } catch (RuntimeException incomplete) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(incomplete);
            if (PolicyCollectionTrace.fatal(incomplete)) throw incomplete;
            listing = new PackagePages(20); offset = 0;
            try {
                while (true) {
                    JsonNode page = read(session, MgmtCliCommands.showPackages(domain, offset, 20), 0, deadline, lease);
                    if (listing.add(page, offset)) return List.copyOf(listing.items);
                    offset = listing.to;
                }
            } catch (RuntimeException fallback) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(fallback); throw new PageFailure(PolicyCollectionTrace.reason(fallback), offset); }
        }
    }

    static boolean collectObjectsEnabled() {
        return Boolean.parseBoolean(System.getProperty("ui2.policy.cp.collect-objects",
            System.getenv().getOrDefault("UI2_POLICY_CP_COLLECT_OBJECTS", "true")));
    }

    /** Seconds between domain/type inventory attempts; reuse preserves the original timestamp and gaps. */
    static Duration configuredObjectsEvery() {
        Duration interval = Duration.ofSeconds(Long.parseLong(System.getProperty("ui2.policy.cp.objects-every",
            System.getenv().getOrDefault("UI2_POLICY_CP_OBJECTS_EVERY", "86400"))));
        if (interval.isNegative()) throw new IllegalArgumentException("POLICY_OBJECTS_EVERY_MUST_BE_NON_NEGATIVE");
        return interval;
    }

    boolean reuseObjects(String source, String container, String type) {
        return repository.inventoryFresh(source, container, type, Instant.now().minus(configuredObjectsEvery()));
    }

    void storeIncompleteObjects(String source, String container, String type, PackagePages listing,
            long elapsed, RuntimeException gap, long deadline, BooleanSupplier lease) {
        storeObjects(source, container, type, listing.items, listing.count, elapsed, "TYPE_INCOMPLETE",
            "TYPE_INCOMPLETE fetched=" + listing.items.size() + " total=" + (listing.total < 0 ? "UNKNOWN" : listing.total)
                + " " + PolicyCollectionTrace.reason(gap), deadline, lease);
    }

    void collectObjects(TransportSession session, String source, String container, String domain,
            long deadline, BooleanSupplier lease) {
        for (String type : CpPolicyGates.OBJECT_TYPES) {
            if (reuseObjects(source, container, type)) continue;
            if (!collectObjectsEnabled() || type.equals("gateways-and-servers") && domain.equalsIgnoreCase("Global")) {
                storeObjects(source, container, type, List.of(), 0, 0, "UNSUPPORTED", "COLLECTION_SKIPPED", deadline, lease);
                continue;
            }
            long started = nanoTime.getAsLong();
            PackagePages listing = new PackagePages(50, "objects");
            int offset = 0;
            try {
                while (true) {
                    JsonNode page = read(session, MgmtCliCommands.showPolicyObjects(domain, type, offset),
                        CpPolicyGates.OBJECT_BASE + CpPolicyGates.OBJECT_TYPES.indexOf(type), deadline, lease);
                    if (listing.add(page, offset)) break;
                    offset = listing.to;
                }
                storeObjects(source, container, type, listing.items, listing.count, nanoTime.getAsLong() - started,
                    "RESOLVED", "", deadline, lease);
            } catch (RuntimeException gap) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(gap);
                if (PolicyCollectionTrace.fatal(gap)) throw gap;
                storeIncompleteObjects(source, container, type, listing, nanoTime.getAsLong() - started, gap, deadline, lease);
            }
        }
    }

    void storeObjects(String source, String container, String type, List<JsonNode> nodes,
            int pages, long elapsed, String status, String reason, long deadline, BooleanSupplier lease) {
        var items = CpObjectInventoryParser.parse(source, container, nodes);
        var snapshot = new com.securityexpert.nexus.ui2.policy.CpObjectInventory(source, container, type,
            Instant.now().toString(), status, reason, items, pages, elapsed / 1_000_000_000.0);
        final String payload;
        try { payload = json.writeValueAsString(snapshot); }
        catch (java.io.IOException invalid) { throw failure(); }
        checkPublication(deadline, lease);
        repository.saveInventory(source, container, type, snapshot.collectedAt(), payload, com.securityexpert.nexus.ui2.platform.WorkerActor.RESERVED_ACTOR_FINGERPRINT);
        PolicyCollectionTrace.objects(type, pages, items.size(), elapsed);
    }

    static final class PackagePages {
        final List<JsonNode> items = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final int limit;
        private final String field;
        int total = -1;
        Long started;
        long elapsed;
        int count;
        int to;
        PackagePages(int limit) { this(limit, "packages"); }
        PackagePages(int limit, String field) { this.limit = limit; this.field = field; }
        int pageCap(int total) {
            return field.equals("objects") ? (int) Math.min(2000L, (total + (long) limit - 1) / limit + 2) : MAX_PAGES;
        }
        boolean add(JsonNode page, int offset) {
            if (field.equals("packages")) validatePreflight(page, field);
            else if (!page.path(field).isArray() || !page.path("total").isIntegralNumber()
                    || !page.path("total").canConvertToInt() || page.path("total").intValue() < 0) throw failure();
            int nextTotal = page.path("total").intValue();
            int cap = pageCap(nextTotal);
            if (++count > cap || offset != to || (total != -1 && total != nextTotal)) throw failure();
            total = nextTotal;
            if (!page.path("from").isIntegralNumber() || !page.path("from").canConvertToInt()
                    || !page.path("to").isIntegralNumber() || !page.path("to").canConvertToInt()) throw failure();
            if (total == 0) {
                if (offset != 0 || (page.path("from").intValue() != 0 && page.path("from").intValue() != 1) || page.path("to").intValue() != 0 || !page.path(field).isEmpty()) throw failure();
                return true;
            }
            if (!page.path("from").isIntegralNumber() || !page.path("from").canConvertToInt() || page.path("from").asLong() != offset + 1L
                    || !page.path("to").isIntegralNumber() || !page.path("to").canConvertToInt()) throw failure();
            int nextTo = page.path("to").intValue();
            if (nextTo <= offset || nextTo > total || nextTo - offset > limit || nextTo - offset != page.path(field).size()) throw failure();
            if (nextTo < total && nextTo - offset != limit) throw failure();
            Set<String> pageSeen = new HashSet<>();
            int index = 0;
            for (JsonNode item : page.path(field)) {
                String uid = required(item, "uid");
                if (seen.contains(uid) || !pageSeen.add(uid)) {
                    if (field.equals("packages")) throw invalidPackage(index, "uid", item.path("uid"));
                    throw failure();
                }
                index++;
            }
            seen.addAll(pageSeen);
            page.path(field).forEach(items::add);
            to = nextTo;
            if (to < total && count == cap) throw failure();
            if (to == total && items.size() != total) throw failure();
            return to == total;
        }
    }

    private static int reached(RuntimeException error) { return error instanceof PageFailure page ? page.offset : 0; }

    private List<JsonNode> access(TransportSession session, String domain, JsonNode roots, long deadline, BooleanSupplier lease,
            List<CollectionFailure> failures, Consumer<List<JsonNode>> publish) {
        Map<String, String> pending = new LinkedHashMap<>();
        for (JsonNode root : roots) {
            JsonNode layer = scopedLayer(root);
            pending.put(required(layer, "uid"), required(layer, "name"));
        }
        Set<String> fetched = new HashSet<>();
        Map<String, JsonNode> dictionary = new HashMap<>();
        List<JsonNode> all = new ArrayList<>();
        int[] rules = {0};
        while (!pending.isEmpty()) {
            var entry = pending.entrySet().iterator().next();
            String uid = entry.getKey(), name = entry.getValue(); pending.remove(uid);
            if (!fetched.add(uid)) continue;
            String layerRef = ref("cp-layer", domain, uid);
            PolicyCollectionTrace.layer(fetched.size(), fetched.size() + pending.size(), rules[0]);
            List<JsonNode> layer;
            try {
                if (fetched.size() > MAX_PAGES) throw failure();
                final int previousRules = rules[0];
                boolean hits = PolicyHitGates.enabled(gates, true);
                IntConsumer progress = count -> {
                    rules[0] = previousRules + count;
                    PolicyCollectionTrace.layer(fetched.size(), fetched.size() + pending.size(), rules[0]);
                };
                try {
                    layer = pages(session, offset -> MgmtCliCommands.showAccessRulebase(domain, name, offset)
                        + (hits ? " show-hits true" : ""), hits ? 3 : 1, deadline, lease, progress, uid);
                } catch (RuntimeException unsupportedHits) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unsupportedHits);
                    if (!hits || PolicyCollectionTrace.fatal(unsupportedHits)) throw unsupportedHits;
                    layer = pages(session, offset -> MgmtCliCommands.showAccessRulebase(domain, name, offset), 1, deadline, lease, progress, uid);
                }
                for (JsonNode page : layer)
                    for (JsonNode object : page.path("objects-dictionary")) dictionary.put(required(object, "uid"), object);
                Set<String> inline = new LinkedHashSet<>();
                for (JsonNode page : layer) inline(page.path("rulebase"), inline, 0);
                for (String child : inline) if (!fetched.contains(child) && !pending.containsKey(child)) {
                    JsonNode object = dictionary.get(child);
                    if (object == null) {
                        failures.add(new CollectionFailure(ref("cp-layer", domain, child), "INLINE_LAYER_NAME_MISSING"));
                    } else pending.put(child, required(object, "name"));
                }
            } catch (RuntimeException incomplete) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(incomplete);
                if (PolicyCollectionTrace.fatal(incomplete)) throw incomplete;
                failures.add(new CollectionFailure(layerRef, PolicyCollectionTrace.reason(incomplete), name, reached(incomplete)));
                continue;
            }
            int previousPages = all.size();
            all.addAll(layer);
            // A persisted checkpoint is explicitly incomplete until pending siblings and NAT are fetched.
            List<CollectionFailure> checkpoint = new ArrayList<>(failures);
            pending.forEach((id, label) -> failures.add(new CollectionFailure(ref("cp-layer", domain, id), "COLLECTION_PENDING", label, 0)));
            try { publish.accept(List.copyOf(all)); }
            catch (RuntimeException incomplete) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(incomplete);
                if (PolicyCollectionTrace.fatal(incomplete)) throw incomplete;
                all.subList(previousPages, all.size()).clear();
                checkpoint.add(new CollectionFailure(layerRef, PolicyCollectionTrace.reason(incomplete), name, 0));
            }
            finally { failures.clear(); failures.addAll(checkpoint); }
        }
        return all;
    }

    static int ruleCount(JsonNode rules) { return ruleCount(rules, 0); }
    private static int ruleCount(JsonNode rules, int depth) {
        if (depth > 32 || !rules.isArray()) throw pageRejected("RULEBASE_NESTING");
        int count = 0;
        for (JsonNode rule : rules) count += rule.has("rulebase") ? ruleCount(rule.path("rulebase"), depth + 1) : 1;
        return count;
    }

    static void inline(JsonNode rules, Set<String> ids, int depth) {
        if (depth > 32) throw failure();
        for (JsonNode rule : rules) {
            if (rule.has("inline-layer")) ids.add(required(rule, "inline-layer"));
            if (rule.has("rulebase")) inline(rule.path("rulebase"), ids, depth + 1);
        }
    }

    List<JsonNode> pages(TransportSession session, IntFunction<String> command, int gate, long deadline, BooleanSupplier lease) {
        return pages(session, command, gate, deadline, lease, count -> {});
    }
    private List<JsonNode> pages(TransportSession session, IntFunction<String> command, int gate, long deadline, BooleanSupplier lease, IntConsumer progress) {
        return pages(session, command, gate, deadline, lease, progress, null);
    }
    private List<JsonNode> pages(TransportSession session, IntFunction<String> command, int gate, long deadline, BooleanSupplier lease, IntConsumer progress, String layerUid) {
        List<JsonNode> pages = new ArrayList<>();
        int offset = 0, total = -1, rules = 0;
        Set<String> seenRules = new HashSet<>();
        try {
            for (int pageNo = 0; pageNo < MAX_PAGES; pageNo++) {
                JsonNode page;
                try { page = read(session, command.apply(offset), gate, deadline, lease, total); }
                catch (PolicyCollectionTrace.Failure timedOut) {
                    if ((gate == 1 || gate == 3) && timedOut.getMessage().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"))
                        page = read(session, command.apply(offset), gate, deadline, lease, total);
                    else {
                        if (gate != 1 || !timedOut.getMessage().endsWith(": TIMEOUT")) throw timedOut;
                        page = read(session, command.apply(offset).replace(" limit 100 offset ", " limit 50 offset "), gate, deadline, lease, total);
                    }
                }
                total = pageTotal(page, offset, total, gate, layerUid);
                Set<String> ids = ruleUids(page.path("rulebase"));
                if (!Collections.disjoint(seenRules, ids)) {
                    invalidPreflight("", 0, false, page, "DUPLICATE_RULE_UID");
                    throw pageRejected("DUPLICATE_RULE_UID");
                }
                seenRules.addAll(ids);
                if (total == 0) { pages.add(page); return pages; }
                int to = page.path("to").intValue();
                pages.add(page); offset = to;
                rules += ruleCount(page.path("rulebase"));
                progress.accept(rules);
                if (offset == total) return pages;
            }
            throw failure();
        } catch (RuntimeException incomplete) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(incomplete);
            throw new PageFailure(PolicyCollectionTrace.reason(incomplete), offset);
        }
    }
    static int pageTotal(JsonNode page, int offset, int expectedTotal, int gate) {
        return pageTotal(page, offset, expectedTotal, gate, null);
    }
    static int pageTotal(JsonNode page, int offset, int expectedTotal, int gate, String layerUid) {
        if (!page.path("rulebase").isArray() || !page.path("objects-dictionary").isArray()
                || !page.path("total").canConvertToInt() || !page.path("total").isIntegralNumber()) throw pageRejected("PAGE_SCHEMA");
        if ((gate == 1 || gate == 3) && layerUid != null && !layerUid.equals(required(page, "uid")))
            throw pageRejected("LAYER_UID");
        for (JsonNode object : page.path("objects-dictionary")) required(object, "uid");
        int total = page.path("total").intValue();
        if (total < 0 || (expectedTotal != -1 && expectedTotal != total)) throw pageRejected("TOTAL_CHANGED");
        if (total == 0) {
            if (offset != 0 || !page.path("rulebase").isEmpty()) throw pageRejected("EMPTY_PAGE_RANGE");
            return total;
        }
        if (!page.path("from").isIntegralNumber() || !page.path("to").isIntegralNumber()
                || !page.path("to").canConvertToInt() || page.path("from").asLong() != offset + 1L) throw pageRejected("PAGE_FROM");
        int to = page.path("to").intValue();
        if (to <= offset || to > total || to - offset > (gate == 1 || gate == 3 ? 100 : 500)) throw pageRejected("PAGE_TO");
        if (ruleCount(page.path("rulebase")) != to - offset) throw pageRejected("NESTED_RULE_COUNT");
        ruleUids(page.path("rulebase"));
        return total;
    }

    static Set<String> ruleUids(JsonNode nodes) {
        Set<String> ids = new HashSet<>();
        ruleUids(nodes, ids, 0);
        return ids;
    }
    private static void ruleUids(JsonNode nodes, Set<String> ids, int depth) {
        if (depth > 32 || !nodes.isArray()) throw pageRejected("RULEBASE_NESTING");
        for (JsonNode node : nodes) {
            if (node.has("rulebase")) ruleUids(node.path("rulebase"), ids, depth + 1);
            else if (node.path("uid").isTextual() && !ids.add(node.path("uid").textValue())) throw pageRejected("DUPLICATE_RULE_UID");
        }
    }
    static PolicyCollectionTrace.Failure pageRejected(String check) { return new PageRejected(check); }
    static String rejectingCheck(RuntimeException error) { return error instanceof PageRejected rejected ? rejected.check : "SCHEMA_OR_MAPPING"; }
    private static final class PageRejected extends PolicyCollectionTrace.Failure {
        final String check;
        PageRejected(String check) { super(PolicyCollectionTrace.failure("INVALID_OR_INCOMPLETE_RESPONSE").getMessage()); this.check = check; }
    }
    private static final class PageFailure extends PolicyCollectionTrace.Failure {
        final int offset;
        PageFailure(String reason, int offset) { super(reason); this.offset = offset; }
    }

    void checkActive(long deadline, BooleanSupplier lease) {
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        checkPublication(deadline, lease);
    }

    void checkPublication(long deadline, BooleanSupplier lease) {
        if (Thread.currentThread().isInterrupted()) throw PolicyCollectionTrace.failure("INTERRUPTED");
        if (nanoTime.getAsLong() >= deadline) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
        if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
    }

    JsonNode read(TransportSession session, String command, int gate, long deadline, BooleanSupplier lease) {
        return read(session, command, gate, deadline, lease, -1);
    }

    JsonNode read(TransportSession session, String command, int gate, long deadline, BooleanSupplier lease, int expectedTotal) {
        String step = gate == 3 ? PolicyHitGates.CP_COMMAND : gate < 0 ? MgmtCliCommands.domainList() : com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates.COMMANDS.get(gate);
        String target = ref("cp-policy-read", command.replaceAll(" limit [0-9]+ offset ", " limit PAGE offset "));
        var offset = java.util.regex.Pattern.compile(" offset '([0-9]+)' ").matcher(command);
        var limit = java.util.regex.Pattern.compile(" limit ([0-9]+) ").matcher(command);
        String page = offset.find() ? " offset=" + offset.group(1) + (limit.find() ? " limit=" + limit.group(1) : "") : "";
        PolicyCollectionTrace.step(step + page, target);
        checkActive(deadline, lease);
        try {
            if (gate == 3) PolicyHitGates.require(gates, true);
            else if (gate >= 0) CpPolicyGates.require(gates, gate);
        } catch (IllegalStateException unavailable) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unavailable);
            if (!"POLICY_GATE_UNAVAILABLE".equals(unavailable.getMessage())) throw unavailable;
            throw PolicyCollectionTrace.failure("POLICY_GATE_UNAVAILABLE");
        }
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
        long started = System.nanoTime();
        long timeoutNanos = Math.min(readTimeout.toNanos(), remaining);
        long extensionMs = Math.min(120_000, Duration.ofNanos(remaining - timeoutNanos).toMillis());
        var result = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(() -> transport.execInteractive(session,
                new ExecSpec(command, false, extensionMs), Duration.ofNanos(timeoutNanos)));
        PolicyCollectionTrace.result(started, result instanceof ExecResult.Completed c ? c.output().getBytes(java.nio.charset.StandardCharsets.UTF_8).length : -1,
                result.getClass().getSimpleName());
        if (Thread.currentThread().isInterrupted()) throw PolicyCollectionTrace.failure("INTERRUPTED");
        if (nanoTime.getAsLong() >= deadline) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
        if (result instanceof ExecResult.TimedOut timedOut)
            throw PolicyCollectionTrace.failure(timedOut.streaming() ? "STREAMING_TIMEOUT" : "TIMEOUT");
        if (!(result instanceof ExecResult.Completed completed)) throw PolicyCollectionTrace.failure(result.getClass().getSimpleName());
        JsonNode root = null;
        try {
            root = json.readTree(jsonBody(completed.output()));
            if (root != null && root.isObject() && (root.has("code") || (root.has("message") && !root.has("objects") && !root.has("packages") && !root.has("rulebase")))) {
                String code = safeApiCode(root.path("code"));
                if (gate == CpPolicyGates.LAST_PUBLISHED_SESSION) {
                    String note = "published-session signal unavailable code=" + code + " message=<masked>";
                    com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", note);
                    System.getLogger(getClass().getName()).log(System.Logger.Level.INFO, note);
                    // An API error never proves an unchanged domain. Collect fully, including unknown errors.
                    return json.createObjectNode();
                }
                String message = (root.path("code").asText("") + " " + root.path("message").asText("")).toLowerCase(Locale.ROOT);
                if (message.contains("session") || message.contains("lock") || message.contains("too many"))
                    throw PolicyCollectionTrace.failure("API_SESSION_PRESSURE");
                throw failure();
            }
            if (completed.exitStatus() != 0) throw PolicyCollectionTrace.failure("EXIT_" + completed.exitStatus());
            if (root == null || !root.isObject()) throw failure();
            if (gate <= 0 || gate == CpPolicyGates.PACKAGES_50) validatePreflight(root, gate < 0 ? "objects" : "packages");
            if (gate >= 1 && gate <= 3) {
                var pageOffset = java.util.regex.Pattern.compile(" offset '([0-9]+)' ").matcher(command);
                if (!pageOffset.find()) throw failure();
                pageTotal(root, Integer.parseInt(pageOffset.group(1)), expectedTotal, gate);
            }
            return root;
        } catch (PolicyCollectionTrace.Failure invalid) {
            invalidPreflight(completed.output(), completed.exitStatus(), false, root, rejectingCheck(invalid));
            if (completed.exitStatus() != 0 && invalid.getMessage().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"))
                throw PolicyCollectionTrace.failure("EXIT_" + completed.exitStatus());
            throw invalid;
        } catch (java.io.IOException invalid) {
            invalidPreflight(completed.output(), completed.exitStatus(),
                invalid instanceof com.fasterxml.jackson.core.io.JsonEOFException, root, "JSON_PARSE");
            if (completed.exitStatus() != 0) throw PolicyCollectionTrace.failure("EXIT_" + completed.exitStatus());
            throw failure();
        }
    }

    /** Skip text-only banner lines, never search past a malformed JSON candidate. */
    private static String jsonBody(String output) {
        String body = output.startsWith("\uFEFF") ? output.substring(1) : output;
        body = body.stripLeading();
        while (!body.startsWith("{") && !body.startsWith("[")) {
            int newline = body.indexOf('\n');
            if (newline < 0 || body.substring(0, newline).contains("{") || body.substring(0, newline).contains("[")) throw failure();
            body = body.substring(newline + 1).stripLeading();
        }
        return body;
    }

    private static void validatePreflight(JsonNode root, String field) {
        if (field.equals("objects")) completeList(root, field);
        else if (!root.path(field).isArray() || !root.path("total").isIntegralNumber()
                || !root.path("total").canConvertToInt() || root.path("total").intValue() < root.path(field).size()
                || root.path(field).size() > 50 || (root.path("total").intValue() > 0 && root.path(field).isEmpty())) throw failure();
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (JsonNode item : root.path(field)) {
            if (field.equals("packages")) validatePackage(item, index, seen);
            else {
                if (!seen.add(required(item, "uid"))) throw failure();
                required(item, "name");
            }
            index++;
        }
    }

    private static void validatePackage(JsonNode item, int index, Set<String> seen) {
        String field = "uid";
        JsonNode value = item.path(field);
        try {
            if (!seen.add(required(item, field))) throw failure();
            field = "name"; value = item.path(field);
            required(item, field);
            field = "access-layers"; value = item.path(field);
            boolean optional = !item.has("access") || item.path("access").isBoolean() && !item.path("access").booleanValue();
            if (value.isMissingNode()) {
                if (!optional) throw failure();
            } else {
                if (!value.isArray() || value.isEmpty() && !optional) throw failure();
                for (JsonNode layer : value) {
                    JsonNode scoped = scopedLayer(layer);
                    required(scoped, "uid"); required(scoped, "name");
                }
            }
            field = "installation-targets"; value = item.path(field);
            if (value.isTextual() && "all".equals(value.textValue())) return;
            if (!value.isArray()) throw failure();
            for (JsonNode target : value) {
                if (target.isTextual()) {
                    if (target.textValue().isBlank()) throw failure();
                } else required(target, "uid");
            }
        } catch (PolicyCollectionTrace.Failure invalid) {
            throw invalidPackage(index, field, value);
        }
    }

    private static PolicyCollectionTrace.Failure invalidPackage(int index, String field, JsonNode value) {
        String diagnostic = "invalid package item=" + index + " field=" + field + " type=" + value.getNodeType();
        com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", diagnostic);
        System.getLogger(CheckPointPolicyCollector.class.getName()).log(System.Logger.Level.WARNING, diagnostic);
        return PolicyCollectionTrace.failure("INVALID_PACKAGE_ITEM:" + field);
    }

    static JsonNode scopedLayer(JsonNode layer) {
        return !layer.has("uid") && !layer.has("name") ? layer.path("domain") : layer;
    }

    List<Target> installationTargets(DiscoveryRun run, PolicyCollectionRepository.Request request,
            String domainUid, String domain, JsonNode policy) {
        List<Target> targets = new ArrayList<>();
        JsonNode installation = policy.path("installation-targets");
        if (installation.isTextual() && "all".equals(installation.textValue())) {
            // ALL is management intent, not proof of enrollment or runtime installation.
            return List.of(new Target(ref("cp-install-target-all", request.sourceId(), domainUid), "ALL", "", "UNKNOWN"));
        }
        for (JsonNode target : installation) {
            String uid = target.isTextual() ? target.textValue() : required(target, "uid");
            var enrolled = repository.targets(run.runId(), domain, uid);
            if (enrolled.isEmpty()) targets.add(new Target(ref("cp-install-target", request.sourceId(), domainUid, uid),
                target.isObject() ? target.path("name").asText("Unresolved installation target") : "Unresolved installation target", "", "UNKNOWN"));
            else targets.addAll(enrolled);
        }
        return targets;
    }

    static String safeApiCode(JsonNode code) {
        String value = code.asText("");
        return value.matches("(?:generic_err_|err_)[a-z0-9_]{1,80}") ? value : "<masked-code>";
    }

    private static void invalidPreflight(String output, int exitCode, boolean endedMidJson, JsonNode root, String check) {
        int limit = Math.min(output.length(), 2048);
        String shape = output.substring(0, limit).replaceAll("\\p{L}", "a").replaceAll("\\p{N}", "9")
            .replaceAll("[^\\x20-\\x7E\r\n\t]", "?");
        String structure = preflightStructure(output, root) + " check=" + check;
        com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note",
            "invalid preflight bytes=" + output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " shapeTruncated=" + (limit < output.length()) + " endedMidJson=" + endedMidJson
                + " exitCode=" + exitCode + " " + structure + " shape=" + shape);
        System.getLogger(CheckPointPolicyCollector.class.getName()).log(System.Logger.Level.WARNING,
            "invalid preflight bytes=" + output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " endedMidJson=" + endedMidJson + " exitCode=" + exitCode + " " + structure);
    }

    /** Only schema keys and integer pagination counters may be emitted; unknown keys can carry identities. */
    static String preflightStructure(String output, JsonNode root) {
        int brace = output.indexOf('{');
        String prefix = brace < 0 ? output : output.substring(0, brace);
        long lines = prefix.chars().filter(c -> c == '\n').count();
        if (!prefix.isBlank() && !prefix.endsWith("\n")) lines++;
        var fields = new ArrayList<String>();
        var counters = new ArrayList<String>();
        if (root != null && root.isObject()) {
            root.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                String safeKey = Set.of("from", "to", "total", "packages", "objects", "rulebase", "objects-dictionary", "code", "message", "uid", "name")
                    .contains(key) ? key : "<masked-key>";
                fields.add(safeKey + ":" + value.getNodeType() + (value.isArray() ? "(size=" + value.size() + ")" : ""));
            });
            for (String key : List.of("from", "to", "total")) {
                JsonNode value = root.path(key);
                counters.add(key + "=" + (value.isIntegralNumber() ? value.bigIntegerValue() : value.getNodeType()));
            }
        }
        return "STRUCTURE rootParsed=" + (root != null) + " rootType=" + (root == null ? "UNPARSED" : root.getNodeType())
            + " leadingNonJsonLines=" + lines + " leadingNonJsonBytes=" + prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + " fields=" + fields + " counters=" + counters;
    }

    static void completeList(JsonNode root, String field) {
        if (!root.path(field).isArray() || !root.path("total").isIntegralNumber() || !root.path("total").canConvertToInt()
                || root.path("total").asLong() != root.path(field).size() || root.path(field).size() > 500) throw failure();
    }
    static String required(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw failure();
        return value.textValue();
    }
    static PolicyCollectionTrace.Failure failure() { return PolicyCollectionTrace.failure("INVALID_OR_INCOMPLETE_RESPONSE"); }
}
