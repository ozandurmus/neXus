package com.securityexpert.nexus.ui2.worker.policy;

import java.time.*;
import java.util.*;
import java.util.function.*;
import com.fasterxml.jackson.databind.*;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliCommands;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/** One serial, trusted MDS session; complete sibling layers survive a failed layer. */
public final class CheckPointPolicyCollector {
    static final int MAX_PAGES = 200;
    private final DeviceTransport transport;
    private final GateRegistryPort gates;
    private final PolicyCollectionRepository repository;
    private final Duration jobTimeout;
    private final LongSupplier nanoTime;
    private final ObjectMapper json = new ObjectMapper();

    public CheckPointPolicyCollector(DeviceTransport transport, GateRegistryPort gates, PolicyCollectionRepository repository) {
        this(transport, gates, repository, Duration.ofSeconds(Long.parseLong(
            System.getenv().getOrDefault("UI2_POLICY_RUN_DEADLINE_SECONDS", "7200"))));
    }
    public CheckPointPolicyCollector(DeviceTransport transport, GateRegistryPort gates, PolicyCollectionRepository repository, Duration jobTimeout) {
        this(transport, gates, repository, jobTimeout, System::nanoTime);
    }
    CheckPointPolicyCollector(DeviceTransport transport, GateRegistryPort gates, PolicyCollectionRepository repository, Duration jobTimeout, LongSupplier nanoTime) {
        if (jobTimeout.isZero() || jobTimeout.isNegative()) throw new IllegalArgumentException("POLICY_DEADLINE_MUST_BE_POSITIVE");
        this.transport = transport; this.gates = gates; this.repository = repository;
        this.jobTimeout = jobTimeout; this.nanoTime = nanoTime;
    }

    public List<PolicySnapshot> collect(DiscoveryRun run, PolicyCollectionRepository.Request request, BooleanSupplier lease) {
        return collect(run, request, lease, snapshot -> {});
    }

    public List<PolicySnapshot> collect(DiscoveryRun run, PolicyCollectionRepository.Request request, BooleanSupplier lease, Consumer<PolicySnapshot> publish) {
        if (!"check_point".equals(run.vendor())) throw failure();
        CpPolicyGates.requireAll(gates);
        if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
        long deadline = nanoTime.getAsLong() + jobTimeout.toNanos();
        checkActive(deadline, lease);
        PolicyCollectionTrace.step("connect", request.sourceId());
        var result = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(() -> transport.connect(new ConnectionTarget(run.managementAddress(), run.managementAddress(), 22),
            new ConnectSpec(run.credentialReferenceId(), PersistedManagementEndpointTrustResolver.scopeRef(run.managementAddress(), 22), Optional.empty()), Duration.ofSeconds(30)));
        if (!(result instanceof ConnectResult.Authenticated connected)) throw PolicyCollectionTrace.failure(result.getClass().getSimpleName());
        TransportSession session = connected.session();
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
                JsonNode packages = read(session, MgmtCliCommands.showPackages(domainName), 0, deadline, lease);
                completeList(packages, "packages");
                Set<String> seen = new HashSet<>();
                for (JsonNode policy : packages.path("packages")) {
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
                    List<JsonNode> access = access(session, domainName, policy.path("access-layers"), deadline, lease, failures,
                        completed -> {
                            var checkpoint = snapshot(metadata, completed, List.of(), failures);
                            checkPublication(deadline, lease);
                            publish.accept(checkpoint);
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
            transport.disconnect(session);
        }
    }

    private static PolicySnapshot snapshot(Metadata metadata, List<JsonNode> access, List<JsonNode> nat, List<CollectionFailure> failures) {
        // Refresh the publication timestamp so each completed layer can replace its earlier checkpoint.
        var updated = new Metadata(metadata.id(), metadata.sourceId(), metadata.sourceName(), metadata.vendor(), metadata.containerId(),
            metadata.containerName(), metadata.name(), Instant.now().toString(), metadata.artefactRef(), metadata.targets());
        var mapped = new CheckPointPolicyMapper().map(updated, access, nat);
        return new PolicySnapshot(updated, mapped.sections(), mapped.objects(), failures);
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
                layer = pages(session, offset -> MgmtCliCommands.showAccessRulebase(domain, name, offset), 1, deadline, lease,
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

    private static int ruleCount(JsonNode rules) { return ruleCount(rules, 0); }
    private static int ruleCount(JsonNode rules, int depth) {
        if (depth > 32) throw failure();
        int count = 0;
        for (JsonNode rule : rules) count += rule.has("rulebase") ? ruleCount(rule.path("rulebase"), depth + 1) : 1;
        return count;
    }

    private static void inline(JsonNode rules, Set<String> ids, int depth) {
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
                if (!page.path("rulebase").isArray() || !page.path("objects-dictionary").isArray()
                        || !page.path("total").canConvertToInt() || !page.path("total").isIntegralNumber()) throw failure();
                int count = page.path("total").intValue();
                if (count < 0 || (total != -1 && total != count)) throw failure();
                total = count;
                if (total == 0) {
                    if (offset != 0 || !page.path("rulebase").isEmpty()) throw failure();
                    pages.add(page); return pages;
                }
                if (!page.path("from").isIntegralNumber() || !page.path("to").isIntegralNumber()
                        || !page.path("to").canConvertToInt() || page.path("from").asLong() != offset + 1L) throw failure();
                int to = page.path("to").intValue();
                if (to <= offset || to > total || to - offset > (gate == 1 ? 100 : 500) || page.path("rulebase").isEmpty()) throw failure();
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
    private static final class PageFailure extends PolicyCollectionTrace.Failure {
        final int offset;
        PageFailure(String reason, int offset) { super(reason); this.offset = offset; }
    }

    private void checkActive(long deadline, BooleanSupplier lease) {
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        checkPublication(deadline, lease);
    }

    private void checkPublication(long deadline, BooleanSupplier lease) {
        if (Thread.currentThread().isInterrupted()) throw PolicyCollectionTrace.failure("INTERRUPTED");
        if (nanoTime.getAsLong() >= deadline) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
        if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
    }

    private JsonNode read(TransportSession session, String command, int gate, long deadline, BooleanSupplier lease) {
        String step = gate < 0 ? MgmtCliCommands.domainList() : com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates.COMMANDS.get(gate);
        String target = ref("cp-policy-read", command.replaceAll(" limit (100|50) offset ", " limit PAGE offset "));
        var offset = java.util.regex.Pattern.compile(" offset '([0-9]+)' ").matcher(command);
        String page = offset.find() ? " offset=" + offset.group(1) + " limit=" + (gate == 1 ? command.contains(" limit 50 ") ? 50 : 100 : 500) : "";
        PolicyCollectionTrace.step(step + page, target);
        checkActive(deadline, lease);
        if (gate >= 0) CpPolicyGates.require(gates, gate);
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
        long started = System.nanoTime();
        var result = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(() -> transport.execInteractive(session,
                new ExecSpec(command), Duration.ofNanos(Math.min(Duration.ofSeconds(gate == 1 ? 300 : 60).toNanos(), remaining))));
        PolicyCollectionTrace.result(started, result instanceof ExecResult.Completed c ? c.output().getBytes(java.nio.charset.StandardCharsets.UTF_8).length : -1,
                result.getClass().getSimpleName());
        if (Thread.currentThread().isInterrupted()) throw PolicyCollectionTrace.failure("INTERRUPTED");
        if (nanoTime.getAsLong() >= deadline) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
        if (result instanceof ExecResult.TimedOut) throw PolicyCollectionTrace.failure("TIMEOUT");
        if (!(result instanceof ExecResult.Completed completed)) throw PolicyCollectionTrace.failure(result.getClass().getSimpleName());
        if (completed.exitStatus() != 0) throw PolicyCollectionTrace.failure("EXIT_" + completed.exitStatus());
        try {
            JsonNode root = json.readTree(completed.output());
            if (root == null || !root.isObject() || root.has("code") || root.has("message")) throw failure();
            return root;
        } catch (java.io.IOException invalid) { throw failure(); }
    }

    private static void completeList(JsonNode root, String field) {
        if (!root.path(field).isArray() || !root.path("total").isIntegralNumber()
                || root.path("total").asLong() != root.path(field).size() || root.path(field).size() > 500) throw failure();
    }
    private static String required(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw failure();
        return value.textValue();
    }
    private static PolicyCollectionTrace.Failure failure() { return PolicyCollectionTrace.failure("INVALID_OR_INCOMPLETE_RESPONSE"); }
}
