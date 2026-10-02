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
    private final ObjectMapper json = new ObjectMapper();

    public CheckPointPolicyCollector(DeviceTransport transport, GateRegistryPort gates, PolicyCollectionRepository repository) {
        this.transport = transport; this.gates = gates; this.repository = repository;
    }

    public List<PolicySnapshot> collect(DiscoveryRun run, PolicyCollectionRepository.Request request, BooleanSupplier lease) {
        if (!"check_point".equals(run.vendor())) throw failure();
        CpPolicyGates.requireAll(gates);
        if (!lease.getAsBoolean()) throw failure();
        long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
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
                    List<JsonNode> access = access(session, domainName, policy.path("access-layers"), deadline, lease, failures);
                    List<JsonNode> nat = pages(session, offset -> MgmtCliCommands.showNatRulebase(domainName, name, offset), 2, deadline, lease);
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
                    var snapshot = new CheckPointPolicyMapper().map(metadata, access, nat);
                    snapshots.add(new PolicySnapshot(metadata, snapshot.sections(), snapshot.objects(), failures));
                }
            }
            if (!found) throw failure();
            return List.copyOf(snapshots);
        } finally {
            transport.disconnect(session);
        }
    }

    private List<JsonNode> access(TransportSession session, String domain, JsonNode roots, long deadline, BooleanSupplier lease, List<CollectionFailure> failures) {
        Map<String, String> pending = new LinkedHashMap<>();
        for (JsonNode root : roots) pending.put(required(root, "uid"), required(root, "name"));
        Set<String> fetched = new HashSet<>();
        Map<String, JsonNode> dictionary = new HashMap<>();
        List<JsonNode> all = new ArrayList<>();
        while (!pending.isEmpty()) {
            var entry = pending.entrySet().iterator().next();
            String uid = entry.getKey(), name = entry.getValue(); pending.remove(uid);
            if (!fetched.add(uid)) continue;
            if (fetched.size() > MAX_PAGES) throw failure();
            List<JsonNode> layer;
            try {
                layer = pages(session, offset -> MgmtCliCommands.showAccessRulebase(domain, name, offset), 1, deadline, lease);
                for (JsonNode page : layer) if (!uid.equals(required(page, "uid"))) throw failure();
            } catch (PolicyCollectionTrace.Failure incomplete) {
                if (System.nanoTime() >= deadline || !lease.getAsBoolean()) throw incomplete;
                String reason = incomplete.getMessage();
                failures.add(new CollectionFailure(ref("cp-layer", domain, uid), reason));
                com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", reason);
                continue;
            }
            for (JsonNode page : layer) {
                if (!uid.equals(required(page, "uid"))) throw failure();
                for (JsonNode object : page.path("objects-dictionary")) dictionary.put(required(object, "uid"), object);
            }
            all.addAll(layer);
            Set<String> inline = new LinkedHashSet<>();
            for (JsonNode page : layer) inline(page.path("rulebase"), inline, 0);
            for (String child : inline) if (!fetched.contains(child) && !pending.containsKey(child)) {
                JsonNode object = dictionary.get(child);
                if (object == null) {
                    failures.add(new CollectionFailure(ref("cp-layer", domain, child), "INLINE_LAYER_NAME_MISSING"));
                    continue;
                }
                pending.put(child, required(object, "name"));
            }
        }
        return all;
    }

    private static void inline(JsonNode rules, Set<String> ids, int depth) {
        if (depth > 32) throw failure();
        for (JsonNode rule : rules) {
            if (rule.has("inline-layer")) ids.add(required(rule, "inline-layer"));
            if (rule.has("rulebase")) inline(rule.path("rulebase"), ids, depth + 1);
        }
    }

    List<JsonNode> pages(TransportSession session, IntFunction<String> command, int gate, long deadline, BooleanSupplier lease) {
        List<JsonNode> pages = new ArrayList<>();
        int offset = 0, total = -1;
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
            if (offset == total) return pages;
        }
        throw failure();
    }

    private JsonNode read(TransportSession session, String command, int gate, long deadline, BooleanSupplier lease) {
        String step = gate < 0 ? MgmtCliCommands.domainList() : com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates.COMMANDS.get(gate);
        String target = ref("cp-policy-read", command.replaceAll(" limit (100|50) offset ", " limit PAGE offset "));
        var offset = java.util.regex.Pattern.compile(" offset '([0-9]+)' ").matcher(command);
        String page = offset.find() ? " offset=" + offset.group(1) + " limit=" + (gate == 1 ? command.contains(" limit 50 ") ? 50 : 100 : 500) : "";
        PolicyCollectionTrace.step(step + page, target);
        if (System.nanoTime() >= deadline) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
        if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
        if (gate >= 0) CpPolicyGates.require(gates, gate);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw failure();
        long started = System.nanoTime();
        var result = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(() -> transport.execInteractive(session,
                new ExecSpec(command), Duration.ofNanos(Math.min(Duration.ofSeconds(gate == 1 ? 300 : 60).toNanos(), remaining))));
        PolicyCollectionTrace.result(started, result instanceof ExecResult.Completed c ? c.output().getBytes(java.nio.charset.StandardCharsets.UTF_8).length : -1,
                result.getClass().getSimpleName());
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
