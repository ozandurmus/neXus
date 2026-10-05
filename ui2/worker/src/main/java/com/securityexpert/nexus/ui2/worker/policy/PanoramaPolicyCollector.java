package com.securityexpert.nexus.ui2.worker.policy;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.w3c.dom.Element;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.policy.PanPolicyGates;
import com.securityexpert.nexus.ui2.jobs.policy.PolicyHitGates;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.worker.discovery.pan.PanoramaApiRoutes;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/** Serial Panorama intent reads; complete independent DGs survive a failed sibling. */
public final class PanoramaPolicyCollector {
    static final int MAX_GROUPS = 200;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    static final long MAX_RESPONSE_BYTES = 128L * 1024 * 1024;
    private final Duration jobTimeout;
    private final java.util.function.LongSupplier clock;
    private final DeviceTransport transport;
    private final GateRegistryPort gates;
    private final PanCredentialResolver credentials;
    private final PolicyCollectionRepository repository;

    public PanoramaPolicyCollector(DeviceTransport transport, GateRegistryPort gates,
            PanCredentialResolver credentials, PolicyCollectionRepository repository) {
        this(transport, gates, credentials, repository, Duration.ofSeconds(Long.parseLong(
                System.getenv().getOrDefault("UI2_POLICY_RUN_DEADLINE_SECONDS", "7200"))));
    }

    public PanoramaPolicyCollector(DeviceTransport transport, GateRegistryPort gates, PanCredentialResolver credentials,
            PolicyCollectionRepository repository, Duration jobTimeout) {
        this(transport, gates, credentials, repository, jobTimeout, System::nanoTime);
    }

    PanoramaPolicyCollector(DeviceTransport transport, GateRegistryPort gates, PanCredentialResolver credentials,
            PolicyCollectionRepository repository, Duration jobTimeout, java.util.function.LongSupplier clock) {
        this.clock = clock;
        if (jobTimeout.isZero() || jobTimeout.isNegative()) throw new IllegalArgumentException("POLICY_DEADLINE_MUST_BE_POSITIVE");
        this.jobTimeout = jobTimeout;
        this.transport = transport; this.gates = gates; this.credentials = credentials; this.repository = repository;
    }

    public static XmlApiSpec request(int index, String group, char[] key) {
        if (index < 0 || index >= PanPolicyGates.COMMANDS.size()) throw failure();
        Map<String, String> form = new LinkedHashMap<>();
        if (index < 2) {
            form.put("action", "show");
            form.put("xpath", index == 0 ? "/config/shared" :
                    "/config/devices/entry[@name='localhost.localdomain']/device-group/entry[@name=" + xpathLiteral(group) + "]");
        } else form.put("cmd", index == 2 ? "<show><devicegroups/></show>" : "<show><dg-hierarchy></dg-hierarchy></show>");
        return new XmlApiSpec("POST", index < 2 ? "config" : "op", "not_applicable", "no_target", form,
                Map.of(PanoramaApiRoutes.KEY_HEADER_NAME, new String(key)));
    }
    /** XPath literals are form values, not XML markup; the transport performs form encoding once. */
    static String xpathLiteral(String value) {
        if (value == null || value.isEmpty() || value.length() > 255 || value.chars().anyMatch(c -> c < 32)) throw failure();
        if (!value.contains("'")) return "'" + value + "'";
        if (!value.contains("\"")) return "\"" + value + "\"";
        return "concat('" + value.replace("'", "',\"'\",'") + "')";
    }

    public List<PolicySnapshot> collect(DiscoveryRun run, PolicyCollectionRepository.Request scope, BooleanSupplier lease) {
        List<PolicySnapshot> snapshots = new ArrayList<>();
        var failures = collect(run, scope, lease, snapshots::add);
        return snapshots.stream().map(s -> new PolicySnapshot(s.metadata(), s.sections(), s.objects(), failures)).toList();
    }

    /** Production path publishes immediately; only lineage XML is retained, never sibling snapshots. */
    public List<CollectionFailure> collect(DiscoveryRun run, PolicyCollectionRepository.Request scope,
            BooleanSupplier lease, Consumer<PolicySnapshot> publish) {
        PolicyCollectionTrace.step("PANORAMA_TARGET", scope.sourceId());
        if (!"palo_alto".equals(run.vendor()) || run.managementAddress() == null || run.managementAddress().isBlank())
            throw PolicyCollectionTrace.failure("PANORAMA_TARGET_NOT_FOUND");
        PanPolicyGates.requireAll(gates);
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
        PolicyCollectionTrace.step("credential resolution", scope.sourceId());
        com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial credential;
        try { credential = credentials.resolve(run.credentialReferenceId()); }
        catch (RuntimeException unavailable) { throw PolicyCollectionTrace.failure("CREDENTIAL_UNRESOLVABLE"); }
        if (credential == null) throw PolicyCollectionTrace.failure("CREDENTIAL_UNUSABLE");
        if (credential.username() == null || credential.username().isBlank() || credential.password() == null) {
            if (credential.password() != null) Arrays.fill(credential.password(), '\0');
            throw PolicyCollectionTrace.failure("CREDENTIAL_UNUSABLE");
        }
        char[] key = null;
        var timer = Executors.newSingleThreadScheduledExecutor();
        PolicyCollectionTrace.timeout(TIMEOUT);
        long deadline = clock.getAsLong() + jobTimeout.toNanos();
        long[] bytes = {0};
        ApiTarget target = new ApiTarget(scope.sourceId(), run.managementAddress());
        try {
            Element auth = read(target, PanoramaApiRoutes.keyGeneration(credential.username(), credential.password()), timer, deadline, bytes);
            String token = PolicyXml.firstRelativeText(auth, "result/key").orElseThrow(() -> PolicyCollectionTrace.failure("AUTHENTICATION_FAILURE"));
            if (token.isBlank()) throw PolicyCollectionTrace.failure("AUTHENTICATION_FAILURE");
            key = token.toCharArray();
            Element groups = result(read(target, checked(2, "", key, lease), timer, deadline, bytes), "devicegroups");
            var entries = PolicyXml.selectRelative(groups, "entry");
            if (entries.size() > MAX_GROUPS) throw failure();
            Map<String, Element> members = new LinkedHashMap<>();
            for (Element entry : entries) {
                String name = entry.getAttribute("name");
                xpathLiteral(name);
                if (members.putIfAbsent(name, entry) != null) throw failure();
            }
            PolicyCollectionTrace.plan(entries.size() + 6);
            Element hierarchy = result(read(target, checked(3, "", key, lease), timer, deadline, bytes), "dg-hierarchy");
            Map<String, String> parents = new LinkedHashMap<>();
            hierarchy(hierarchy, "", parents, 0);
            if (!parents.keySet().equals(members.keySet())) throw failure();
            var shared = result(read(target, checked(0, "", key, lease), timer, deadline, bytes), "shared");
            var document = shared.getOwnerDocument();
            Element config = document.createElement("config");
            config.appendChild(shared.cloneNode(true));
            Element devices = document.createElement("devices"); config.appendChild(devices);
            Element local = document.createElement("entry"); local.setAttribute("name", "localhost.localdomain"); devices.appendChild(local);
            Element deviceGroups = document.createElement("device-group"); local.appendChild(deviceGroups);
            List<CollectionFailure> failures = new ArrayList<>();
            // Parent-first order allows inheritance without retaining unrelated DG responses.
            List<String> order = new ArrayList<>(parents.keySet());
            PolicyCollectionTrace.packages((int) order.stream().filter(name -> scope.domainRef().isEmpty()
                || scope.domainRef().equals(ref(scope.sourceId(), "device-group", name))).count());
            String collectedAt = Instant.now().toString();
            Map<String, Map<String, FirewallHits>> hitCache = new HashMap<>();
            int published = 0, rulesFetched = 0;
            for (int i = 0; i < order.size(); i++) {
                String name = order.get(i);
                String container = ref(scope.sourceId(), "device-group", name);
                long before = bytes[0];
                boolean unitFinished = false;
                PolicyCollectionTrace.unit("pan-groups");
                PolicyCollectionTrace.layer(0, 0, rulesFetched);
                try {
                    Element group = read(target, checked(1, name, key, lease), timer, deadline, bytes, name);
                    deviceGroups.appendChild(document.importNode(group, true));
                    if (!scope.domainRef().isEmpty() && !scope.domainRef().equals(container)) continue;
                    com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
                    if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
                    if (clock.getAsLong() >= deadline) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
                    PolicyCollectionTrace.step("device group " + container + " mapping", scope.sourceId());
                    var metadata = new Metadata(ref(container, "policy"), scope.sourceId(), "Panorama " + scope.sourceId(), "PAN", container,
                            name, name, collectedAt, "", targets(run, scope.sourceId(), members.get(name)));
                    var snapshot = new PanoramaPolicyMapper().mapUnverifiedPrecedence(metadata, config, name, parents);
                    long rules = snapshot.sections().stream().mapToLong(section -> section.rules().size()).sum();
                    long objects = snapshot.objects().size();
                    rulesFetched += (int) rules;
                    PolicyCollectionTrace.layer(0, 0, rulesFetched);
                    if (rules > 50_000 || objects > 200_000) {
                        com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note",
                                "DG_SIZE bytes=" + (bytes[0] - before) + " rules=" + rules + " objects=" + objects);
                        throw PolicyCollectionTrace.failure("SIZE_LIMIT");
                    }
                    snapshot = collectHits(snapshot, timer, deadline, bytes, lease, hitCache, scope.automatic());
                    if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
                    if (clock.getAsLong() >= deadline) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
                    if (Thread.currentThread().isInterrupted()) throw PolicyCollectionTrace.failure("INTERRUPTED");
                    publish.accept(snapshot);
                    published++;
                    unitFinished = true;
                } catch (IllegalArgumentException | PolicyCollectionTrace.Failure invalid) {
                    if (invalid instanceof PolicyCollectionTrace.Failure traced && PolicyCollectionTrace.fatal(traced)) throw traced;
                    unitFinished = true;
                    String reason = PolicyCollectionTrace.reason(invalid);
                    if (reason.endsWith(": SIZE_LIMIT")) reason = "bytes=" + (bytes[0] - before) + " " + reason;
                    failures.add(new CollectionFailure(container, reason));
                    com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note",
                            "INCOMPLETE_DG " + container + " " + reason);
                } finally {
                    if (unitFinished && (scope.domainRef().isEmpty() || scope.domainRef().equals(container))) PolicyCollectionTrace.done(container);
                    Set<String> needed = new HashSet<>();
                    for (String future : order.subList(i + 1, order.size())) {
                        String parent = parents.get(future);
                        while (parent != null && !parent.isEmpty() && needed.add(parent)) parent = parents.get(parent);
                    }
                    for (Element retained : PolicyXml.selectRelative(deviceGroups, "entry"))
                        if (!needed.contains(retained.getAttribute("name"))) deviceGroups.removeChild(retained);
                }
            }
            com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
            if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
            if (clock.getAsLong() >= deadline) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
            if (Thread.currentThread().isInterrupted()) throw PolicyCollectionTrace.failure("INTERRUPTED");
            if ((!scope.domainRef().isEmpty() || !members.isEmpty()) && published == 0) throw failure();
            return List.copyOf(failures);
        } finally {
            timer.shutdownNow();
            if (key != null) Arrays.fill(key, '\0');
            Arrays.fill(credential.password(), '\0');
        }
    }
    static XmlApiSpec hitRequest(String context, char[] key) {
        if (context == null || context.isBlank() || context.length() > 255 || context.chars().anyMatch(c -> c < 32)) throw failure();
        String escaped = context.replace("&", "&amp;").replace("'", "&apos;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
        return new XmlApiSpec("POST", "op", "not_applicable", "no_target",
                Map.of("cmd", PolicyHitGates.PAN_COMMAND.substring("type=op&cmd=".length()).replace("<VSYS>", escaped)),
                Map.of(PanoramaApiRoutes.KEY_HEADER_NAME, new String(key)));
    }
    private PolicySnapshot collectHits(PolicySnapshot snapshot, ScheduledExecutorService timer, long deadline,
            long[] bytes, BooleanSupplier lease, Map<String, Map<String, FirewallHits>> cache, boolean automatic) {
        if (!PolicyHitGates.enabled(gates, false)) return snapshot;
        List<Map<String, FirewallHits>> members = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Target member : snapshot.metadata().targets()) {
            String memberRef = ref(member.deviceId(), member.context());
            if (!seen.add(memberRef)) continue;
            if (cache.containsKey(memberRef)) { members.add(cache.get(memberRef)); continue; }
            cache.put(memberRef, Map.of()); // Failed members are not retried for sibling DGs.
            char[] key = null;
            com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial credential = null;
            try {
                // No implicit default VSYS, address/name joins, or manager credential fallback.
                hitRequest(member.context(), new char[0]);
                var endpoint = repository.panFirewall(member.deviceId());
                if (endpoint.isEmpty()) continue;
                checkHitRead(lease);
                if (!repository.beginDomain(member.deviceId(), ref("rule-hit-count", member.context()), automatic)) continue;
                credential = credentials.resolve(endpoint.get().credentialReferenceId());
                if (credential == null || credential.username() == null || credential.username().isBlank() || credential.password() == null)
                    continue;
                var target = new ApiTarget(member.deviceId(), endpoint.get().address());
                checkHitRead(lease);
                var auth = read(target, PanoramaApiRoutes.keyGeneration(credential.username(), credential.password()), timer, deadline, bytes);
                key = PolicyXml.firstRelativeText(auth, "result/key").filter(k -> !k.isBlank()).orElseThrow(PanoramaPolicyCollector::failure).toCharArray();
                checkHitRead(lease);
                var response = read(target, hitRequest(member.context(), key), timer, deadline, bytes);
                var parsed = PolicyHitCounts.pan(response, member, Instant.now().toString());
                cache.put(memberRef, parsed);
                members.add(parsed);
            } catch (RuntimeException unavailable) {
                String reason = PolicyCollectionTrace.reason(unavailable);
                if (Set.of("CANCELLED", "LEASE_LOST", "JOB_DEADLINE", "POLICY_GATE_UNAVAILABLE", "INTERRUPTED")
                        .stream().anyMatch(code -> reason.endsWith(": " + code))) throw unavailable;
                com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", "POLICY_HITS_UNAVAILABLE");
            } finally {
                if (key != null) Arrays.fill(key, '\0');
                if (credential != null && credential.password() != null) Arrays.fill(credential.password(), '\0');
            }
        }
        return PolicyHitCounts.aggregate(snapshot, members);
    }
    private void checkHitRead(BooleanSupplier lease) {
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
        PolicyHitGates.require(gates, false);
    }
    private XmlApiSpec checked(int index, String group, char[] key, BooleanSupplier lease) {
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
        PanPolicyGates.require(gates, index);
        return request(index, group, key);
    }
    private Element read(ApiTarget target, XmlApiSpec spec, ScheduledExecutorService timer, long deadline, long[] bytes) {
        return read(target, spec, timer, deadline, bytes, null);
    }
    private Element read(ApiTarget target, XmlApiSpec spec, ScheduledExecutorService timer, long deadline, long[] bytes, String group) {
        boolean hits = spec.formParams().getOrDefault("cmd", "").startsWith("<show><rule-hit-count>");
        String step = hits ? "rule hit counts" : "keygen".equals(spec.type()) ? "authentication" : spec.formParams().containsKey("xpath")
            ? ("/config/shared".equals(spec.formParams().get("xpath")) ? "shared" : "device group " + ref(spec.formParams().get("xpath")))
            : "<show><devicegroups/></show>".equals(spec.formParams().get("cmd")) ? "show devicegroups" : "show dg-hierarchy";
        String template = hits ? PolicyHitGates.PAN_COMMAND : "keygen".equals(spec.type()) ? "type=keygen" : spec.formParams().containsKey("xpath")
            ? PanPolicyGates.COMMANDS.get("/config/shared".equals(spec.formParams().get("xpath")) ? 0 : 1)
            : PanPolicyGates.COMMANDS.get("<show><devicegroups/></show>".equals(spec.formParams().get("cmd")) ? 2 : 3);
        PolicyCollectionTrace.step(step + " " + template, target.endpointId());
        if (Thread.currentThread().isInterrupted()) throw PolicyCollectionTrace.failure("INTERRUPTED");
        if (clock.getAsLong() >= deadline) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
        long started = System.nanoTime(), before = bytes[0];
        String[] parseFailure = {""};
        var prefix = new ByteArrayOutputStream(2048);
        long remaining = deadline - clock.getAsLong();
        if (remaining <= 0) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
        Duration requestTimeout = Duration.ofNanos(Math.min(remaining, TIMEOUT.toNanos()));
        long readDeadline = System.nanoTime() + requestTimeout.toNanos();
        var outcome = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(() -> transport.xmlApiCallStreaming(target, spec, requestTimeout, input -> {
            var expiry = timer.schedule(() -> { try { input.close(); } catch (IOException ignored) {} },
                    Math.max(0, readDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try {
                var counted = new FilterInputStream(input) {
                    private long received;
                    private void count(int n) throws IOException {
                        if (n > 0) { received += n; bytes[0] += n; }
                        if (received > MAX_RESPONSE_BYTES) {
                            parseFailure[0] = "SIZE_LIMIT";
                            throw new IOException("POLICY_SIZE_LIMIT");
                        }
                        if (Thread.currentThread().isInterrupted()) { parseFailure[0] = "INTERRUPTED"; throw new IOException("POLICY_INTERRUPTED"); }
                        if (clock.getAsLong() >= deadline) { parseFailure[0] = "JOB_DEADLINE"; throw new IOException("POLICY_JOB_TIMEOUT"); }
                    }
                    @Override public int read() throws IOException { int n = in.read(); if (n >= 0 && prefix.size() < 2048) prefix.write(n); count(n < 0 ? 0 : 1); return n; }
                    @Override public int read(byte[] b, int off, int len) throws IOException { int n = in.read(b, off, len); if (n > 0) prefix.write(b, off, Math.min(n, 2048 - prefix.size())); count(n); return n; }
                };
                try { return PolicyXml.parse(counted, false, MAX_RESPONSE_BYTES, 5_000_000).getDocumentElement(); }
                catch (IllegalArgumentException invalid) { return null; }
            } finally { expiry.cancel(false); }
        }));
        PolicyCollectionTrace.result(started, bytes[0] - before, outcome.getClass().getSimpleName());
        try {
            if (clock.getAsLong() >= deadline) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
            if (outcome instanceof XmlApiStreamOutcome.Completed<Element> response && response.httpStatus() != 200)
                throw PolicyCollectionTrace.failure("HTTP_" + response.httpStatus());
            if (!parseFailure[0].isEmpty()) throw PolicyCollectionTrace.failure(parseFailure[0]);
            if (!(outcome instanceof XmlApiStreamOutcome.Completed<Element> completed) || completed.httpStatus() != 200
                    || completed.handled() == null || !completed.handled().getTagName().equals("response") || !"success".equals(completed.handled().getAttribute("status")) || System.nanoTime() >= readDeadline)
                throw PolicyCollectionTrace.failure(System.nanoTime() >= readDeadline ? "TIMEOUT" : outcome instanceof XmlApiStreamOutcome.Failed<?> failed ? transportFailure(failed.reason())
                    : completedReason(outcome));
            if (group != null) return deviceGroup(completed.handled(), group);
            return completed.handled();
        } catch (PolicyCollectionTrace.Failure invalid) {
            if (hits) throw invalid;
            String diagnostic = responseShape(prefix.toString(java.nio.charset.StandardCharsets.UTF_8), bytes[0] - before,
                    outcome instanceof XmlApiStreamOutcome.Completed<Element> response ? response.httpStatus() : null);
            com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.add("job", "note", diagnostic);
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, diagnostic);
            throw invalid;
        }
    }
    static String responseShape(String prefix, long bytes, Integer httpStatus) {
        StringBuilder shape = new StringBuilder();
        prefix.codePoints().limit(2048).forEach(c -> shape.appendCodePoint(Character.isLetter(c) ? 'a' : Character.isDigit(c) ? '9'
                : Character.isISOControl(c) || Character.getType(c) == Character.FORMAT ? '?' : c));
        // Only known status enums and numeric API codes may survive masking, including on malformed XML.
        var root = java.util.regex.Pattern.compile("^\\s*(?:<\\?xml[^>]*>\\s*)?<response\\s+([^>]*)>").matcher(prefix);
        String status = "UNKNOWN", code = "UNKNOWN";
        if (root.find()) {
            var attributes = java.util.regex.Pattern.compile("(?:^|\\s)(status|code)\\s*=\\s*(['\"])(.*?)\\2").matcher(root.group(1));
            while (attributes.find()) {
                String value = attributes.group(3);
                if (attributes.group(1).equals("status") && Set.of("success", "error").contains(value)) status = value;
                if (attributes.group(1).equals("code") && value.matches("[0-9]{1,6}")) code = value;
            }
        }
        return "PAN_POLICY_SHAPE bytes=" + bytes + " httpStatus=" + (httpStatus == null ? "UNKNOWN" : httpStatus)
                + " status=" + status + " code=" + code + " shape=" + shape;
    }

    private static Element deviceGroup(Element response, String name) {
        var results = PolicyXml.selectRelative(response, "result");
        if (results.size() != 1) throw failure();
        Element result = results.get(0);
        var entries = PolicyXml.selectRelative(result, "entry");
        if (entries.size() == 1 && name.equals(entries.get(0).getAttribute("name"))) return entries.get(0);
        boolean hasElements = false;
        for (var child = result.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element) hasElements = true;
        if (!hasElements && result.getTextContent().isBlank()
                && (!result.hasAttribute("total-count") || "0".equals(result.getAttribute("total-count")))
                && (!result.hasAttribute("count") || "0".equals(result.getAttribute("count")))) {
            Element empty = result.getOwnerDocument().createElement("entry");
            empty.setAttribute("name", name);
            return empty;
        }
        throw failure();
    }
    private static String transportFailure(String reason) {
        if (reason == null) return "TRANSPORT_FAILED";
        if (reason.contains("transport adapter")) return "TRANSPORT_NOT_REGISTERED";
        if (reason.contains("TLS") || reason.contains("trust")) return "TLS_TARGET_UNRESOLVABLE";
        if (reason.toLowerCase(Locale.ROOT).contains("timeout") || reason.toLowerCase(Locale.ROOT).contains("timed out")) return "TIMEOUT";
        if (reason.contains("interrupted")) return "INTERRUPTED";
        return "TRANSPORT_FAILED";
    }
    private static String completedReason(XmlApiStreamOutcome<Element> outcome) {
        if (outcome instanceof XmlApiStreamOutcome.Completed<Element> response) {
            if (response.handled() == null) return "XML_PARSE_OR_SIZE_FAILED";
            String code = response.handled().getAttribute("code");
            if (code.matches("[0-9]{1,6}")) return "API_ERROR_" + code;
        }
        return "API_RESPONSE_ERROR";
    }
    private static Element result(Element response, String name) {
        var entries = PolicyXml.selectRelative(response, "result/" + name);
        if (entries.size() != 1) throw failure();
        return entries.get(0);
    }
    private static void hierarchy(Element node, String parent, Map<String, String> parents, int depth) {
        if (depth >= 32) throw failure();
        for (Element child : PolicyXml.selectRelative(node, "dg")) {
            String name = child.getAttribute("name");
            xpathLiteral(name);
            if (parents.size() >= MAX_GROUPS || parents.putIfAbsent(name, parent) != null) throw failure();
            hierarchy(child, name, parents, depth + 1);
        }
    }
    private List<Target> targets(DiscoveryRun run, String sourceId, Element group) {
        List<Target> targets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element member : PolicyXml.selectRelative(group, "devices/entry")) {
            String serial = member.getAttribute("name");
            if (serial.isEmpty() || !seen.add(serial)) throw failure();
            var contexts = PolicyXml.selectRelative(member, "vsys/entry");
            if (contexts.isEmpty()) targets.addAll(repository.panTargets(run.runId(), sourceId, serial, "", sync(member)));
            else for (Element context : contexts) {
                String name = context.getAttribute("name");
                if (name.isEmpty()) throw failure();
                String status = PolicyXml.firstRelativeText(context, "shared-policy-status").isPresent() ? sync(context) : sync(member);
                targets.addAll(repository.panTargets(run.runId(), sourceId, serial, name, status));
            }
        }
        return targets;
    }
    private static String sync(Element entry) {
        return switch (PolicyXml.firstRelativeText(entry, "shared-policy-status").orElse("")) {
            case "In Sync" -> "IN_SYNC";
            case "Out of Sync" -> "OUT_OF_SYNC";
            default -> "UNKNOWN";
        };
    }
    private static IllegalStateException failure() { return PolicyCollectionTrace.failure("INVALID_OR_INCOMPLETE_RESPONSE"); }
}
