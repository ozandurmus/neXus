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
    /** A layer whose total changes while it is paged is re-read from offset 0 at most this often per run. */
    static final int MAX_LAYER_RESTARTS = 2;
    final DeviceTransport transport;
    private final int maxSessions;
    private final GateRegistryPort gates;
    private final PolicyCollectionRepository repository;
    /** Domains a scheduled run may collect changed-only; read once so an invalid setting fails at construction. */
    final CpChangedOnlyDomains changedOnly;
    private final Duration jobTimeout;
    private final Duration readTimeout;
    private final Duration restartBackoff;
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
        this.restartBackoff = configuredRestartBackoff();
        this.packagePageSize = 50;
        this.changedOnly = CpChangedOnlyDomains.configured();
        if (jobTimeout.isZero() || jobTimeout.isNegative()) throw new IllegalArgumentException("POLICY_DEADLINE_MUST_BE_POSITIVE");
        this.transport = transport; this.gates = gates; this.repository = repository;
        this.jobTimeout = jobTimeout; this.nanoTime = nanoTime;
    }

    /** Pause before a layer restart, in milliseconds; bounded so a restart can never stall a run. */
    static Duration configuredRestartBackoff() {
        Duration backoff = Duration.ofMillis(Long.parseLong(System.getProperty("ui2.policy.cp.layer-restart-backoff-ms",
            System.getenv().getOrDefault("UI2_POLICY_CP_LAYER_RESTART_BACKOFF_MS", "3000"))));
        if (backoff.isNegative() || backoff.toMillis() > 60_000)
            throw new IllegalArgumentException("POLICY_LAYER_RESTART_BACKOFF_OUT_OF_RANGE");
        return backoff;
    }

    /** Log one masked restart line, back off briefly, then require the run to still be active. */
    void restartLayer(int attempt, String kind, long deadline, BooleanSupplier lease) {
        String note = "[CP_LAYER_RESTART] reason=TOTAL_CHANGED attempt=" + attempt + " kind=" + kind;
        com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", note);
        System.getLogger(CheckPointPolicyCollector.class.getName()).log(System.Logger.Level.WARNING, note);
        long pause = Math.min(restartBackoff.toNanos(), Math.max(0, deadline - nanoTime.getAsLong()));
        if (pause > 0) {
            try { java.util.concurrent.TimeUnit.NANOSECONDS.sleep(pause); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw PolicyCollectionTrace.failure("INTERRUPTED");
            }
        }
        checkActive(deadline, lease);
    }

    /** True only for a page whose total differs from the layer's first page in this pass. */
    static boolean totalChanged(RuntimeException error) {
        return error instanceof PageRejected rejected && rejected.check.equals("TOTAL_CHANGED")
            || error instanceof PageFailure failed && "TOTAL_CHANGED".equals(failed.check);
    }

    /** The vendor's name-not-unique API error, found directly or as the cause of a page failure. */
    static NameNotUnique nameNotUnique(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof NameNotUnique notUnique) return notUnique;
        return null;
    }

    /**
     * Relationship-only facts for a name-not-unique answer. Values stay in memory; only MATCH/NO and counts are logged.
     * targetNames are installation-target names already present in the collected show-packages item.
     */
    record NameContext(String domain, String packageName, List<String> targetNames, int sameNamePackages) {
        NameContext { targetNames = List.copyOf(targetNames); }
        @Override public String toString() { return "NameContext[masked]"; }
        static NameContext of(String domain, JsonNode policy, List<JsonNode> packages) {
            String name = policy.path("name").asText("");
            List<String> targets = new ArrayList<>();
            JsonNode installation = policy.path("installation-targets");
            if (installation.isArray()) for (JsonNode target : installation) {
                String targetName = target.isObject() ? target.path("name").asText("") : "";
                if (!targetName.isBlank()) targets.add(targetName);
            }
            int same = (int) packages.stream().filter(p -> name.equals(p.path("name").asText(null))).count();
            return new NameContext(domain, name, targets, same);
        }
        void diagnose(String kind, Throwable error) {
            NameNotUnique notUnique = nameNotUnique(error);
            String message = notUnique == null ? "" : notUnique.apiMessage;
            long targetMatches = targetNames.stream().distinct().filter(message::contains).count();
            String note = "[CP_NAME_NOT_UNIQUE] kind=" + kind
                + " messagePackage=" + (!packageName.isEmpty() && message.contains(packageName) ? "MATCH" : "NO")
                + " messageDomain=" + (!domain.isEmpty() && message.contains(domain) ? "MATCH" : "NO")
                + " installTargetNames=" + targetNames.stream().distinct().count()
                + " installTargetNamesInMessage=" + targetMatches
                + " packagesWithRequestedName=" + sameNamePackages;
            com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", note);
            System.getLogger(CheckPointPolicyCollector.class.getName()).log(System.Logger.Level.WARNING, note);
        }
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
        return collect(run, request, lease, publish, failure -> {
            if ("THROTTLED".equals(failure.reason())) {
                com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", failure.layerRef() + ": THROTTLED");
            } else throw new PolicyCollectionTrace.Failure(failure.reason());
        });
    }

    public List<PolicySnapshot> collect(DiscoveryRun run, PolicyCollectionRepository.Request request, BooleanSupplier lease,
            Consumer<PolicySnapshot> publish, Consumer<CollectionFailure> domainFailure) {
        return collect(run, request, lease, publish, domainFailure, new PolicyObservations());
    }

    /**
     * As above, and every domain's published-session marker is handed to {@code observations} (also when the run fails
     * part-way), so the completeness coordinator can write the revision rows. Collection itself is unchanged.
     */
    public List<PolicySnapshot> collect(DiscoveryRun run, PolicyCollectionRepository.Request request, BooleanSupplier lease,
            Consumer<PolicySnapshot> publish, Consumer<CollectionFailure> domainFailure, PolicyObservations observations) {
        var reuse = new CpDomainReuse(repository, request, changedOnly, System::currentTimeMillis).onDatabaseGap(domainFailure);
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
            finally { reuse.record(); PolicyCollectionTrace.elapsed(nanoTime.getAsLong() - started); reuse.observe(observations); }
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
                if (!repository.beginDomain(request, container)) {
                    domainFailure.accept(new CollectionFailure(container, "THROTTLED"));
                    PolicyCollectionTrace.packages(0); continue;
                }
                reuse.begin(container, domainName);
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
                    NameContext names = NameContext.of(domainName, policy, packages);
                    List<JsonNode> access = access(session, domainName, policy.path("access-layers"), deadline, lease, failures,
                        completed -> {
                            var checkpoint = snapshot(metadata, completed, List.of(), failures);
                            // Validate each layer even when its checkpoint write is throttled.
                            if (nanoTime.getAsLong() - lastCheckpoint[0] < checkpointInterval) return;
                            checkPublication(deadline, lease);
                            domainPublish.accept(checkpoint);
                            lastCheckpoint[0] = nanoTime.getAsLong();
                        }, names);
                    List<JsonNode> nat = List.of();
                    try {
                        nat = pages(session, offset -> MgmtCliCommands.showNatRulebase(domainName, name, offset), 2, deadline, lease,
                            count -> PolicyCollectionTrace.rules("nat", count));
                        failures.removeIf(f -> f.layerRef().equals(natRef));
                    } catch (RuntimeException incomplete) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(incomplete);
                        if (PolicyCollectionTrace.fatal(incomplete)) throw incomplete;
                        failures.removeIf(f -> f.layerRef().equals(natRef));
                        failures.add(natFailure(natRef, incomplete, names));
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
            reuse.record();
            PolicyCollectionTrace.elapsed(nanoTime.getAsLong() - started);
            reuse.observe(observations);
        }
    }

    /** A fresh, trusted MDS session for the optional change reader; the caller disconnects it through {@link #transport}. */
    TransportSession openSession(DiscoveryRun run, long deadline, BooleanSupplier lease) { return connect(run, deadline, lease); }

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
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(fallback); throw new PageFailure(PolicyCollectionTrace.reason(fallback), offset, null); }
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
        storeObjects(source, container, type, listing.items, listing.pagesRead(), elapsed, "TYPE_INCOMPLETE",
            "TYPE_INCOMPLETE fetched=" + listing.items.size() + " total=" + (listing.total < 0 ? "UNKNOWN" : listing.total)
                + (gap instanceof ListingRejected rejected ? " check=" + rejected.check + " restarts=" + listing.restarts : "")
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
            PackagePages listing = new PackagePages(50, "objects").begin(started);
            int offset = 0;
            try {
                while (true) {
                    JsonNode page = read(session, MgmtCliCommands.showPolicyObjects(domain, type, offset),
                        CpPolicyGates.OBJECT_BASE + CpPolicyGates.OBJECT_TYPES.indexOf(type), deadline, lease);
                    try { if (listing.add(page, offset)) break; }
                    catch (ListingRejected rejected) {
                        if (!restartListing(type, listing, rejected, deadline, lease)) throw rejected;
                        offset = 0; continue;
                    }
                    offset = listing.to;
                }
                storeObjects(source, container, type, listing.items, listing.pagesRead(), nanoTime.getAsLong() - started,
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
        /** Pages read in earlier, discarded passes of this listing (restarts after a concurrent change). */
        int pagesBefore;
        int restarts;
        long passStarted;
        PackagePages(int limit) { this(limit, "packages"); }
        PackagePages(int limit, String field) { this.limit = limit; this.field = field; }
        PackagePages begin(long now) { passStarted = now; return this; }
        int pagesRead() { return pagesBefore + count; }
        /** Discard the current pass; the next page must be offset 0 again. */
        void restart(long now) {
            pagesBefore += count; restarts++;
            items.clear(); seen.clear(); total = -1; count = 0; to = 0; passStarted = now;
        }
        int pageCap(int total) {
            return field.equals("objects") ? (int) Math.min(2000L, (total + (long) limit - 1) / limit + 2) : MAX_PAGES;
        }
        boolean add(JsonNode page, int offset) {
            if (field.equals("packages")) validatePreflight(page, field);
            else if (!page.path(field).isArray() || !page.path("total").isIntegralNumber()
                    || !page.path("total").canConvertToInt() || page.path("total").intValue() < 0) throw new ListingRejected("PAGE_SCHEMA");
            int nextTotal = page.path("total").intValue();
            int cap = pageCap(nextTotal);
            if (++count > cap) throw new ListingRejected("PAGE_CAP");
            if (offset != to) throw new ListingRejected("OFFSET");
            // A changed total means the listing moved while it was paged (an object was added or deleted).
            if (total != -1 && total != nextTotal) throw new ListingRejected("TOTAL_CHANGED");
            total = nextTotal;
            if (total == 0) {
                // The management API omits from/to on an empty list: {"objects":[],"total":0} (36-byte answers on the live MDS).
                if (offset != 0 || !(emptyCounter(page, "from", 0) || emptyCounter(page, "from", 1)) || !emptyCounter(page, "to", 0)
                        || !page.path(field).isEmpty()) throw new ListingRejected("EMPTY_PAGE_RANGE");
                return true;
            }
            if (!page.path("from").isIntegralNumber() || !page.path("from").canConvertToInt() || page.path("from").asLong() != offset + 1L
                    || !page.path("to").isIntegralNumber() || !page.path("to").canConvertToInt()) throw new ListingRejected("PAGE_FROM");
            int nextTo = page.path("to").intValue();
            if (nextTo <= offset || nextTo > total || nextTo - offset > limit || nextTo - offset != page.path(field).size())
                throw new ListingRejected("PAGE_TO");
            // A short page before the end: the listing shrank between the total and this page.
            if (nextTo < total && nextTo - offset != limit) throw new ListingRejected("SHORT_PAGE");
            Set<String> pageSeen = new HashSet<>();
            int index = 0;
            for (JsonNode item : page.path(field)) {
                String uid;
                try { uid = required(item, "uid"); }
                catch (PolicyCollectionTrace.Failure invalid) {
                    if (field.equals("packages")) throw invalid;
                    throw new ListingRejected("ITEM_UID");
                }
                if (!pageSeen.add(uid)) {
                    if (field.equals("packages")) throw invalidPackage(index, "uid", item.path("uid"));
                    throw new ListingRejected("DUPLICATE_IN_PAGE");
                }
                // An item already returned on an earlier page: the listing shifted while it was paged.
                if (seen.contains(uid)) {
                    if (field.equals("packages")) throw invalidPackage(index, "uid", item.path("uid"));
                    throw new ListingRejected("DUPLICATE_UID");
                }
                index++;
            }
            seen.addAll(pageSeen);
            page.path(field).forEach(items::add);
            to = nextTo;
            if (to < total && count == cap) throw new ListingRejected("PAGE_CAP");
            if (to == total && items.size() != total) throw new ListingRejected("COUNT");
            return to == total;
        }
    }

    /** Fixed vocabulary: a paged object listing rejected one page; the check names the failed rule, never a value. */
    static final class ListingRejected extends PolicyCollectionTrace.Failure {
        /** Checks that prove only that the listing moved while it was paged; a fresh pass from offset 0 may succeed. */
        static final Set<String> CONCURRENT = Set.of("TOTAL_CHANGED", "SHORT_PAGE", "DUPLICATE_UID");
        final String check;
        ListingRejected(String check) { super(PolicyCollectionTrace.failure("INVALID_OR_INCOMPLETE_RESPONSE").getMessage()); this.check = check; }
    }

    static final int MAX_LISTING_RESTARTS = 2;

    /**
     * Restart an object listing from offset 0 after a concurrent change (bounded per listing, masked note, short back-off).
     * A restart is refused when the estimated full pass cannot finish before the run deadline: the gap then stays explicit.
     */
    boolean restartListing(String type, PackagePages listing, RuntimeException error, long deadline, BooleanSupplier lease) {
        if (!(error instanceof ListingRejected rejected) || !ListingRejected.CONCURRENT.contains(rejected.check)) return false;
        long now = nanoTime.getAsLong();
        long pagesNeeded = Math.max(1, (Math.max(listing.total, 0) + 49L) / 50);
        long estimate = (now - listing.passStarted) / Math.max(1, listing.count) * pagesNeeded;
        boolean fits = now + estimate + restartBackoff.toNanos() < deadline;
        String note = "[CP_OBJECT_LISTING_" + (listing.restarts < MAX_LISTING_RESTARTS && fits ? "RESTART" : "REJECTED")
            + "] type=" + type + " check=" + rejected.check + " attempt=" + (listing.restarts + 1)
            + " fetched=" + listing.items.size() + " total=" + listing.total + " pages=" + listing.count;
        com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", note);
        System.getLogger(CheckPointPolicyCollector.class.getName()).log(System.Logger.Level.WARNING, note);
        if (listing.restarts >= MAX_LISTING_RESTARTS || !fits) return false;
        long pause = Math.min(restartBackoff.toNanos(), Math.max(0, deadline - now));
        if (pause > 0) {
            try { java.util.concurrent.TimeUnit.NANOSECONDS.sleep(pause); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw PolicyCollectionTrace.failure("INTERRUPTED");
            }
        }
        checkActive(deadline, lease);
        listing.restart(nanoTime.getAsLong());
        return true;
    }

    private static int reached(RuntimeException error) { return error instanceof PageFailure page ? page.offset : 0; }

    /** NAT that the vendor cannot address uniquely is UNKNOWN (fixed reason); every other NAT failure stays a gap. */
    static CollectionFailure natFailure(String natRef, RuntimeException error, NameContext names) {
        if (nameNotUnique(error) == null)
            return new CollectionFailure(natRef, PolicyCollectionTrace.reason(error), "NAT", reached(error));
        names.diagnose("nat", error);
        return new CollectionFailure(natRef, VENDOR_NAME_NOT_UNIQUE, "NAT", 0);
    }

    private List<JsonNode> access(TransportSession session, String domain, JsonNode roots, long deadline, BooleanSupplier lease,
            List<CollectionFailure> failures, Consumer<List<JsonNode>> publish, NameContext names) {
        Map<String, JsonNode> pending = new LinkedHashMap<>();
        for (JsonNode root : roots) {
            JsonNode layer = scopedLayer(root);
            pending.put(layerKey(layer), layer);
        }
        Set<String> fetched = new HashSet<>();
        Map<String, JsonNode> dictionary = new HashMap<>();
        List<JsonNode> all = new ArrayList<>();
        int[] rules = {0};
        while (!pending.isEmpty()) {
            var entry = pending.entrySet().iterator().next();
            String key = entry.getKey();
            JsonNode reference = entry.getValue();
            String uid = layerUid(reference), name = required(reference, "name"); pending.remove(key);
            if (!fetched.add(key)) continue;
            String layerRef = ref("cp-layer", domain, uid == null ? key : uid);
            String domainType = layerDomainType(reference);
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
                int[] restarts = {0};
                try {
                    layer = pages(session, offset -> MgmtCliCommands.showAccessRulebase(domain, uid, name, offset)
                        + (hits ? " show-hits true" : ""), hits ? 3 : 1, deadline, lease, progress, uid, domainType, restarts);
                } catch (RuntimeException unsupportedHits) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(unsupportedHits);
                    if (!hits || PolicyCollectionTrace.fatal(unsupportedHits) || totalChanged(unsupportedHits)) throw unsupportedHits;
                    if (nameNotUnique(unsupportedHits) != null) names.diagnose("access-hits", unsupportedHits);
                    layer = pages(session, offset -> MgmtCliCommands.showAccessRulebase(domain, uid, name, offset), 1, deadline, lease, progress, uid, domainType, restarts);
                }
                for (JsonNode page : layer)
                    for (JsonNode object : page.path("objects-dictionary")) dictionary.put(required(object, "uid"), object);
                Map<String, JsonNode> inline = new LinkedHashMap<>();
                for (JsonNode page : layer) inline(page.path("rulebase"), inline, 0);
                for (String child : inline.keySet()) if (!fetched.contains("uid:" + child) && !pending.containsKey("uid:" + child)) {
                    JsonNode object = inlineReference(inline.get(child), dictionary);
                    pending.put(layerKey(object), object);
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
            pending.forEach((id, referenceNode) -> failures.add(new CollectionFailure(ref("cp-layer", domain, layerUid(referenceNode) == null ? id : layerUid(referenceNode)), "COLLECTION_PENDING", required(referenceNode, "name"), 0)));
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

    static void inline(JsonNode rules, Map<String, JsonNode> ids, int depth) {
        if (depth > 32) throw failure();
        for (JsonNode rule : rules) {
            if (rule.has("inline-layer")) {
                JsonNode reference = rule.path("inline-layer");
                ids.put(inlineUid(reference), reference);
            }
            if (rule.has("rulebase")) inline(rule.path("rulebase"), ids, depth + 1);
        }
    }

    static String inlineUid(JsonNode reference) {
        if (reference.isObject()) return required(reference, "uid");
        if (!reference.isTextual() || reference.textValue().isBlank()) throw failure();
        return reference.textValue();
    }

    static JsonNode inlineReference(JsonNode reference, Map<String, JsonNode> dictionary) {
        String uid = inlineUid(reference);
        JsonNode object = reference.isObject() ? reference : dictionary.get(uid);
        var layer = object == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            : ((com.fasterxml.jackson.databind.node.ObjectNode) object).deepCopy();
        layer.put("uid", uid);
        if (!layer.has("name")) layer.put("name", "Inline layer");
        return layer;
    }

    List<JsonNode> pages(TransportSession session, IntFunction<String> command, int gate, long deadline, BooleanSupplier lease) {
        return pages(session, command, gate, deadline, lease, count -> {});
    }
    private List<JsonNode> pages(TransportSession session, IntFunction<String> command, int gate, long deadline, BooleanSupplier lease, IntConsumer progress) {
        return pages(session, command, gate, deadline, lease, progress, null, "ABSENT", new int[1]);
    }
    /** A pass whose total changes is discarded and re-read from offset 0, at most MAX_LAYER_RESTARTS times per layer. */
    private List<JsonNode> pages(TransportSession session, IntFunction<String> command, int gate, long deadline, BooleanSupplier lease,
            IntConsumer progress, String layerUid, String domainType, int[] restarts) {
        while (true) {
            try { return pass(session, command, gate, deadline, lease, progress, layerUid, domainType); }
            catch (PageFailure incomplete) {
                if (!totalChanged(incomplete) || restarts[0] >= MAX_LAYER_RESTARTS) throw incomplete;
                restartLayer(++restarts[0], gate == 2 ? "nat" : "access", deadline, lease);
            }
        }
    }
    private List<JsonNode> pass(TransportSession session, IntFunction<String> command, int gate, long deadline, BooleanSupplier lease, IntConsumer progress, String layerUid, String domainType) {
        List<JsonNode> pages = new ArrayList<>();
        int offset = 0, total = -1, rules = 0;
        Set<String> seenRules = new HashSet<>();
        try {
            for (int pageNo = 0; pageNo < MAX_PAGES; pageNo++) {
                JsonNode page;
                try { page = read(session, command.apply(offset), gate, deadline, lease, total, domainType); }
                catch (PolicyCollectionTrace.Failure timedOut) {
                    if ((gate == 1 || gate == 3) && timedOut.getMessage().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE") && !totalChanged(timedOut))
                        page = read(session, command.apply(offset), gate, deadline, lease, total, domainType);
                    else {
                        if (gate != 1 || !timedOut.getMessage().endsWith(": TIMEOUT")) throw timedOut;
                        page = read(session, command.apply(offset).replace(" limit 100 offset ", " limit 50 offset "), gate, deadline, lease, total, domainType);
                    }
                }
                try { total = pageTotal(page, offset, total, gate, layerUid); }
                catch (RuntimeException invalid) { rejectedRulebase(page, rejectingCheck(invalid), domainType); throw invalid; }
                Set<String> ids = ruleUids(page.path("rulebase"));
                if (!Collections.disjoint(seenRules, ids)) {
                    rejectedRulebase(page, "DUPLICATE_RULE_UID", domainType);
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
            PageFailure failed = new PageFailure(PolicyCollectionTrace.reason(incomplete), offset,
                incomplete instanceof PageRejected rejected ? rejected.check : null);
            failed.initCause(incomplete);
            throw failed;
        }
    }
    static int pageTotal(JsonNode page, int offset, int expectedTotal, int gate) {
        return pageTotal(page, offset, expectedTotal, gate, null);
    }
    static int pageTotal(JsonNode page, int offset, int expectedTotal, int gate, String layerUid) {
        if (!page.path("rulebase").isArray()
                || !page.path("total").canConvertToInt() || !page.path("total").isIntegralNumber()) throw pageRejected("PAGE_SCHEMA");
        boolean empty = offset == 0 && page.path("total").intValue() == 0 && page.path("rulebase").isEmpty();
        // An empty access or NAT rulebase may omit the dictionary (empty access layers do on the live MDS).
        if (!page.path("objects-dictionary").isArray() && !(empty && !page.has("objects-dictionary")))
            throw pageRejected("PAGE_SCHEMA");
        if ((gate == 1 || gate == 3) && layerUid != null && !layerUid.equals(required(page, "uid")))
            throw pageRejected("LAYER_UID");
        for (JsonNode object : page.path("objects-dictionary")) required(object, "uid");
        int total = page.path("total").intValue();
        if (total < 0 || (expectedTotal != -1 && expectedTotal != total)) throw pageRejected("TOTAL_CHANGED");
        if (total == 0) {
            if (!empty || !(emptyCounter(page, "from", 0) || emptyCounter(page, "from", 1)) || !emptyCounter(page, "to", 0))
                throw pageRejected("EMPTY_PAGE_RANGE");
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

    private static boolean emptyCounter(JsonNode page, String field, int expected) {
        JsonNode counter = page.path(field);
        return counter.isMissingNode() || counter.isIntegralNumber() && counter.canConvertToInt() && counter.intValue() == expected;
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
    static String rejectingCheck(RuntimeException error) {
        return error instanceof PageRejected rejected ? rejected.check : error instanceof NameNotUnique ? VENDOR_NAME_NOT_UNIQUE : "SCHEMA_OR_MAPPING";
    }
    private static final class PageRejected extends PolicyCollectionTrace.Failure {
        final String check;
        PageRejected(String check) { super(PolicyCollectionTrace.failure("INVALID_OR_INCOMPLETE_RESPONSE").getMessage()); this.check = check; }
    }
    private static final class PageFailure extends PolicyCollectionTrace.Failure {
        final int offset;
        final String check;
        PageFailure(String reason, int offset, String check) { super(reason); this.offset = offset; this.check = check; }
    }
    /** The API message is held in memory for local relationship checks only; it is never part of the exception text. */
    static final class NameNotUnique extends PolicyCollectionTrace.Failure {
        final transient String apiMessage;
        NameNotUnique(String apiMessage) {
            super(PolicyCollectionTrace.failure(VENDOR_NAME_NOT_UNIQUE).getMessage());
            this.apiMessage = apiMessage == null ? "" : apiMessage;
        }
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
        return read(session, command, gate, deadline, lease, expectedTotal, "ABSENT");
    }

    JsonNode read(TransportSession session, String command, int gate, long deadline, BooleanSupplier lease, int expectedTotal, String domainType) {
        boolean uidAccess = command.contains(" -f json show-access-rulebase uid ");
        String step = uidAccess ? CpPolicyGates.accessUidCommand(gate == 3) : gate == 3 ? PolicyHitGates.CP_COMMAND : gate < 0 ? MgmtCliCommands.domainList() : com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates.COMMANDS.get(gate);
        String target = ref("cp-policy-read", command.replaceAll(" limit [0-9]+ offset ", " limit PAGE offset "));
        var offset = java.util.regex.Pattern.compile(" offset '([0-9]+)' ").matcher(command);
        var limit = java.util.regex.Pattern.compile(" limit ([0-9]+) ").matcher(command);
        String page = offset.find() ? " offset=" + offset.group(1) + (limit.find() ? " limit=" + limit.group(1) : "") : "";
        PolicyCollectionTrace.step(step + page + ((gate == 1 || gate == 3) ? " layerDomainType=" + domainType : ""), target);
        checkActive(deadline, lease);
        try {
            if (uidAccess) CpPolicyGates.requireAccessUid(gates, gate == 3);
            else if (gate == 3) PolicyHitGates.require(gates, true);
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
                if ((gate == 1 || gate == 3) && !uidAccess && "generic_err_object_field_not_unique".equals(code))
                    throw PolicyCollectionTrace.failure("LAYER_NAME_AMBIGUOUS");
                if (gate >= 1 && gate <= 3 && "generic_err_object_field_not_unique".equals(code))
                    throw new NameNotUnique(root.path("message").asText(""));
                String message = (root.path("code").asText("") + " " + root.path("message").asText("")).toLowerCase(Locale.ROOT);
                if (message.contains("session") || message.contains("lock") || message.contains("too many"))
                    throw PolicyCollectionTrace.failure("API_SESSION_PRESSURE");
                // NAT and object reads have no retry: name a vendor error answer apart from a malformed page, so the
                // stored gap (not only the transcript) shows which one it was. Access reads keep their retry contract.
                if (gate == 2 || gate >= CpPolicyGates.OBJECT_BASE && gate < CpPolicyGates.LAST_PUBLISHED_SESSION)
                    throw PolicyCollectionTrace.failure("API_RESPONSE_ERROR");
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
            if (gate >= 1 && gate <= 3) rejectedRulebase(completed.output(), completed.exitStatus(), false, root, rejectingCheck(invalid), domainType);
            else invalidPreflight(completed.output(), completed.exitStatus(), false, root, rejectingCheck(invalid));
            if (completed.exitStatus() != 0 && invalid.getMessage().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"))
                throw PolicyCollectionTrace.failure("EXIT_" + completed.exitStatus());
            throw invalid;
        } catch (java.io.IOException invalid) {
            if (gate >= 1 && gate <= 3) rejectedRulebase(completed.output(), completed.exitStatus(),
                invalid instanceof com.fasterxml.jackson.core.io.JsonEOFException, root, "JSON_PARSE", domainType);
            else invalidPreflight(completed.output(), completed.exitStatus(),
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
                    layerUid(scoped); required(scoped, "name");
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

    static String layerUid(JsonNode layer) {
        return layer.has("uid") ? required(layer, "uid") : null;
    }

    static String layerKey(JsonNode layer) {
        String uid = layerUid(layer);
        return uid == null ? "name:" + required(layer, "name") : "uid:" + uid;
    }

    static String layerDomainType(JsonNode layer) {
        String value = layer.path("domain").path("domain-type").asText("");
        if (value.isEmpty()) value = layer.path("domain-type").asText("");
        return value.isEmpty() ? "ABSENT" : Set.of("domain", "global domain", "data domain").contains(value) ? value : "<masked>";
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
        String structure = preflightStructure(output, root) + apiDiagnostic(root) + " check=" + check;
        com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note",
            "invalid preflight bytes=" + output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " shapeTruncated=" + (limit < output.length()) + " endedMidJson=" + endedMidJson
                + " exitCode=" + exitCode + " " + structure + " shape=" + shape);
        System.getLogger(CheckPointPolicyCollector.class.getName()).log(System.Logger.Level.WARNING,
            "invalid preflight bytes=" + output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " endedMidJson=" + endedMidJson + " exitCode=" + exitCode + " " + structure);
    }

    static void rejectedRulebase(JsonNode root, String check) {
        rejectedRulebase(root, check, "ABSENT");
    }

    static void rejectedRulebase(JsonNode root, String check, String domainType) {
        rejectedRulebase("", 0, false, root, check, domainType);
    }

    private static String apiDiagnostic(JsonNode root) {
        return root != null && root.has("code") ? " apiCode=" + safeApiCode(root.path("code")) : "";
    }

    private static void rejectedRulebase(String output, int exitCode, boolean endedMidJson, JsonNode root, String check, String domainType) {
        int[] counts = new int[2];
        if (root != null) structureCounts(root.path("rulebase"), counts, 0);
        String note = "invalid rulebase bytes=" + output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + " endedMidJson=" + endedMidJson + " exitCode=" + exitCode + " " + preflightStructure(output, root)
            + apiDiagnostic(root) + " layerDomainType=" + domainType
            + " sections=" + counts[0] + " rules=" + counts[1] + " check=" + check;
        com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", note);
        System.getLogger(CheckPointPolicyCollector.class.getName()).log(System.Logger.Level.WARNING, note);
    }

    private static void structureCounts(JsonNode nodes, int[] counts, int depth) {
        if (!nodes.isArray() || depth > 32) return;
        for (JsonNode node : nodes) {
            if (node.has("rulebase")) { counts[0]++; structureCounts(node.path("rulebase"), counts, depth + 1); }
            else counts[1]++;
        }
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
