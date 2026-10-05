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
        this.packagePageSize = Integer.parseInt(System.getProperty("ui2.policy.cp.package-page-size",
            System.getenv().getOrDefault("UI2_POLICY_CP_PACKAGE_PAGE_SIZE", "20")));
        if (packagePageSize < 1 || packagePageSize > 500) throw new IllegalArgumentException("POLICY_PACKAGE_PAGE_SIZE_OUT_OF_RANGE");
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
            new ConnectionTarget(run.managementAddress(), run.managementAddress(), 22),
            new ConnectSpec(run.credentialReferenceId(), PersistedManagementEndpointTrustResolver.scopeRef(run.managementAddress(), 22), Optional.empty()),
            Duration.ofNanos(Math.min(Duration.ofSeconds(30).toNanos(), deadline - nanoTime.getAsLong()))));
        if (!(result instanceof ConnectResult.Authenticated connected)) throw PolicyCollectionTrace.failure(result.getClass().getSimpleName());
        return connected.session();
    }

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
        if (!"check_point".equals(run.vendor())) throw failure();
        CpPolicyGates.requireAll(gates);
        if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
        long deadline = nanoTime.getAsLong() + jobTimeout.toNanos();
        if (maxSessions > 1) return new CpPolicyParallelCollection(this, repository, maxSessions, nanoTime,
            run, request, deadline, lease, publish, domainFailure).collect();
        TransportSession session = connect(run, deadline, lease);
        try {
            var domains = read(session, MgmtCliCommands.domainList(), -1, deadline, lease);
            completeList(domains, "objects");
            List<PolicySnapshot> snapshots = new ArrayList<>();
            boolean found = request.domainRef().isEmpty();
            for (JsonNode domain : domains.path("objects")) {
                String domainUid = required(domain, "uid"), domainName = required(domain, "name");
                String container = ref(request.sourceId(), domainUid);
                if (!request.domainRef().isEmpty() && !container.equals(request.domainRef())) continue;
                found = true;
                if (!repository.beginDomain(request.sourceId(), container, request.automatic())) continue;
                if (session == null) session = connect(run, deadline, lease);
                List<JsonNode> packages;
                try {
                    packages = packagePages(session, domainName, deadline, lease);
                } catch (RuntimeException incomplete) {
                    if (PolicyCollectionTrace.fatal(incomplete)) throw incomplete;
                    checkPublication(deadline, lease);
                    domainFailure.accept(new CollectionFailure(container, PolicyCollectionTrace.reason(incomplete), "Packages", reached(incomplete)));
                    transport.disconnect(session); session = null;
                    continue;
                }
                Set<String> seen = new HashSet<>();
                for (JsonNode policy : packages) {
                    String uid = required(policy, "uid"), name = required(policy, "name");
                    if (!seen.add(uid) || !policy.path("access-layers").isArray()) throw failure();
                    List<CollectionFailure> failures = new ArrayList<>();
                    List<Target> targets = new ArrayList<>();
                    if (!policy.path("installation-targets").isArray()) throw failure();
                    for (JsonNode target : policy.path("installation-targets")) {
                        String targetUid = target.isTextual() ? target.textValue() : required(target, "uid");
                        var enrolled = repository.targets(run.runId(), domainName, targetUid);
                        if (enrolled.isEmpty()) {
                            // Preserve management assignments without inventing an enrolled-device match.
                            targets.add(new Target(ref("cp-install-target", request.sourceId(), domainUid, targetUid),
                                target.isObject() ? target.path("name").asText("Unresolved installation target") : "Unresolved installation target", "", "UNKNOWN"));
                        } else targets.addAll(enrolled);
                    }
                    Metadata metadata = new Metadata(ref(request.sourceId(), domainUid, uid), request.sourceId(),
                        "MDS " + request.sourceId(), "CP", container, domainName, name, Instant.now().toString(), "", targets.stream().distinct().toList());
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
                            publish.accept(checkpoint);
                            lastCheckpoint[0] = nanoTime.getAsLong();
                        });
                    List<JsonNode> nat = List.of();
                    try {
                        nat = pages(session, offset -> MgmtCliCommands.showNatRulebase(domainName, name, offset), 2, deadline, lease);
                        failures.removeIf(f -> f.layerRef().equals(natRef));
                    } catch (RuntimeException incomplete) {
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
                    publish.accept(snapshot);
                    snapshots.add(snapshot);
                }
            }
            if (!found) throw failure();
            checkActive(deadline, lease);
            return List.copyOf(snapshots);
        } finally {
            if (session != null) transport.disconnect(session);
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
                JsonNode page = read(session, MgmtCliCommands.showPackages(domain, offset, packagePageSize), 0, deadline, lease);
                if (listing.add(page, offset)) return List.copyOf(listing.items);
                offset = listing.to;
            }
        } catch (RuntimeException incomplete) {
            throw new PageFailure(PolicyCollectionTrace.reason(incomplete), offset);
        }
    }

    static final class PackagePages {
        final List<JsonNode> items = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final int limit;
        private int total = -1, count;
        int to;
        PackagePages(int limit) { this.limit = limit; }
        boolean add(JsonNode page, int offset) {
            validatePreflight(page, "packages");
            int nextTotal = page.path("total").intValue();
            if (++count > MAX_PAGES || offset != to || (total != -1 && total != nextTotal)) throw failure();
            total = nextTotal;
            if (total == 0) {
                if (offset != 0 || !page.path("packages").isEmpty()) throw failure();
                return true;
            }
            if (!page.path("from").isIntegralNumber() || !page.path("from").canConvertToInt() || page.path("from").asLong() != offset + 1L
                    || !page.path("to").isIntegralNumber() || !page.path("to").canConvertToInt()) throw failure();
            to = page.path("to").intValue();
            if (to <= offset || to > total || to - offset > limit || to - offset != page.path("packages").size()) throw failure();
            for (JsonNode item : page.path("packages")) {
                if (!seen.add(required(item, "uid"))) throw failure();
                items.add(item);
            }
            if (to < total && count == MAX_PAGES) throw failure();
            return to >= total;
        }
    }

    private static int reached(RuntimeException error) { return error instanceof PageFailure page ? page.offset : 0; }

    private List<JsonNode> access(TransportSession session, String domain, JsonNode roots, long deadline, BooleanSupplier lease,
            List<CollectionFailure> failures, Consumer<List<JsonNode>> publish) {
        Map<String, String> pending = new LinkedHashMap<>();
        for (JsonNode root : roots) pending.put(required(root, "uid"), required(root, "name"));
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
                layer = pages(session, offset -> MgmtCliCommands.showAccessRulebase(domain, name, offset)
                        + (hits ? " show-hits true" : ""), hits ? 3 : 1, deadline, lease,
                    count -> {
                        rules[0] = previousRules + count;
                        PolicyCollectionTrace.layer(fetched.size(), fetched.size() + pending.size(), rules[0]);
                    });
                for (JsonNode page : layer) if (!uid.equals(required(page, "uid"))) throw failure();
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
        if (depth > 32) throw failure();
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
        List<JsonNode> pages = new ArrayList<>();
        int offset = 0, total = -1, rules = 0;
        try {
            for (int pageNo = 0; pageNo < MAX_PAGES; pageNo++) {
                JsonNode page;
                try { page = read(session, command.apply(offset), gate, deadline, lease); }
                catch (PolicyCollectionTrace.Failure timedOut) {
                    if (gate != 1 || !timedOut.getMessage().endsWith(": TIMEOUT")) throw timedOut;
                    page = read(session, command.apply(offset).replace(" limit 100 offset ", " limit 50 offset "), gate, deadline, lease);
                }
                total = pageTotal(page, offset, total, gate);
                if (total == 0) { pages.add(page); return pages; }
                int to = page.path("to").intValue();
                pages.add(page); offset = to;
                rules += ruleCount(page.path("rulebase"));
                progress.accept(rules);
                if (offset == total) return pages;
            }
            throw failure();
        } catch (RuntimeException incomplete) {
            throw new PageFailure(PolicyCollectionTrace.reason(incomplete), offset);
        }
    }
    static int pageTotal(JsonNode page, int offset, int expectedTotal, int gate) {
        if (!page.path("rulebase").isArray() || !page.path("objects-dictionary").isArray()
                || !page.path("total").canConvertToInt() || !page.path("total").isIntegralNumber()) throw failure();
        int total = page.path("total").intValue();
        if (total < 0 || (expectedTotal != -1 && expectedTotal != total)) throw failure();
        if (total == 0) {
            if (offset != 0 || !page.path("rulebase").isEmpty()) throw failure();
            return total;
        }
        if (!page.path("from").isIntegralNumber() || !page.path("to").isIntegralNumber()
                || !page.path("to").canConvertToInt() || page.path("from").asLong() != offset + 1L) throw failure();
        int to = page.path("to").intValue();
        if (to <= offset || to > total || to - offset > (gate == 1 || gate == 3 ? 100 : 500) || page.path("rulebase").isEmpty()) throw failure();
        return total;
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
        String step = gate == 3 ? PolicyHitGates.CP_COMMAND : gate < 0 ? MgmtCliCommands.domainList() : com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates.COMMANDS.get(gate);
        String target = ref("cp-policy-read", command.replaceAll(" limit [0-9]+ offset ", " limit PAGE offset "));
        var offset = java.util.regex.Pattern.compile(" offset '([0-9]+)' ").matcher(command);
        var limit = java.util.regex.Pattern.compile(" limit ([0-9]+) ").matcher(command);
        String page = gate == 0 ? " paging unavailable: gate literal fixed" : offset.find() ? " offset=" + offset.group(1) + (limit.find() ? " limit=" + limit.group(1) : "") : "";
        PolicyCollectionTrace.step(step + page, target);
        checkActive(deadline, lease);
        if (gate == 3) PolicyHitGates.require(gates, true);
        else if (gate >= 0) CpPolicyGates.require(gates, gate);
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
                String message = (root.path("code").asText("") + " " + root.path("message").asText("")).toLowerCase(Locale.ROOT);
                if (message.contains("session") || message.contains("lock") || message.contains("too many"))
                    throw PolicyCollectionTrace.failure("API_SESSION_PRESSURE");
                throw failure();
            }
            if (completed.exitStatus() != 0) throw PolicyCollectionTrace.failure("EXIT_" + completed.exitStatus());
            if (root == null || !root.isObject()) throw failure();
            if (gate <= 0) validatePreflight(root, gate < 0 ? "objects" : "packages");
            return root;
        } catch (PolicyCollectionTrace.Failure invalid) {
            if (gate <= 0) invalidPreflight(completed.output(), completed.exitStatus(), false, root);
            if (completed.exitStatus() != 0 && invalid.getMessage().endsWith(": INVALID_OR_INCOMPLETE_RESPONSE"))
                throw PolicyCollectionTrace.failure("EXIT_" + completed.exitStatus());
            throw invalid;
        } catch (java.io.IOException invalid) {
            if (gate <= 0) invalidPreflight(completed.output(), completed.exitStatus(),
                invalid instanceof com.fasterxml.jackson.core.io.JsonEOFException, root);
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
        if (field.equals("packages") && root.path(field).isArray() && root.path("total").isIntegralNumber()
                && root.path("total").bigIntegerValue().compareTo(java.math.BigInteger.valueOf(root.path(field).size())) > 0)
            throw PolicyCollectionTrace.failure("PACKAGE_LIST_TRUNCATED_BY_SERVER (total=" + root.path("total").bigIntegerValue()
                + ", returned=" + root.path(field).size() + ")");
        if (field.equals("objects")) completeList(root, field);
        else if (!root.path(field).isArray() || !root.path("total").isIntegralNumber()
                || !root.path("total").canConvertToInt() || root.path("total").intValue() < root.path(field).size()
                || root.path(field).size() > 500 || (root.path("total").intValue() > 0 && root.path(field).isEmpty())) throw failure();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : root.path(field)) {
            if (!seen.add(required(item, "uid"))) throw failure();
            required(item, "name");
            if (field.equals("packages")) {
                if (!item.path("access-layers").isArray() || !item.path("installation-targets").isArray()) throw failure();
                for (JsonNode layer : item.path("access-layers")) {
                    required(layer, "uid"); required(layer, "name");
                }
                for (JsonNode target : item.path("installation-targets")) {
                    if (target.isTextual()) {
                        if (target.textValue().isBlank()) throw failure();
                    } else required(target, "uid");
                }
            }
        }
    }

    private static void invalidPreflight(String output, int exitCode, boolean endedMidJson, JsonNode root) {
        int limit = Math.min(output.length(), 2048);
        String shape = output.substring(0, limit).replaceAll("\\p{L}", "a").replaceAll("\\p{N}", "9")
            .replaceAll("[^\\x20-\\x7E\r\n\t]", "?");
        String structure = preflightStructure(output, root);
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
                String safeKey = Set.of("from", "to", "total", "packages", "objects", "rulebase", "code", "message", "uid", "name")
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
