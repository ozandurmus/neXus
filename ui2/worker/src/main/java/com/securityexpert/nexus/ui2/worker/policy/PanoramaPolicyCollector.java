package com.securityexpert.nexus.ui2.worker.policy;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.w3c.dom.Element;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.policy.PanPolicyGates;
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
    private final DeviceTransport transport;
    private final GateRegistryPort gates;
    private final PanCredentialResolver credentials;
    private final PolicyCollectionRepository repository;

    public PanoramaPolicyCollector(DeviceTransport transport, GateRegistryPort gates,
            PanCredentialResolver credentials, PolicyCollectionRepository repository) {
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
        PolicyCollectionTrace.step("PANORAMA_TARGET", scope.sourceId());
        if (!"palo_alto".equals(run.vendor()) || run.managementAddress() == null || run.managementAddress().isBlank())
            throw PolicyCollectionTrace.failure("PANORAMA_TARGET_NOT_FOUND");
        PanPolicyGates.requireAll(gates);
        if (!lease.getAsBoolean()) throw failure();
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
        long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
        long[] bytes = {0};
        ApiTarget target = new ApiTarget(scope.sourceId(), run.managementAddress());
        try {
            Element auth = read(target, PanoramaApiRoutes.keyGeneration(credential.username(), credential.password()), timer, deadline, bytes);
            String token = PolicyXml.firstRelativeText(auth, "result/key").orElseThrow(PanoramaPolicyCollector::failure);
            if (token.isBlank()) throw failure();
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
            Set<String> parsed = new HashSet<>();
            for (String name : members.keySet()) {
                var spec = checked(1, name, key, lease);
                try {
                    Element group = read(target, spec, timer, deadline, bytes, name);
                    deviceGroups.appendChild(document.importNode(group, true));
                    parsed.add(name);
                } catch (PolicyCollectionTrace.Failure invalid) {
                    if (fatal(invalid)) throw invalid;
                    failures.add(new CollectionFailure(ref(scope.sourceId(), "device-group", name), invalid.getMessage()));
                }
            }
            List<PolicySnapshot> snapshots = new ArrayList<>();
            String collectedAt = Instant.now().toString();
            long rules = 0, objects = 0;
            for (var group : members.entrySet()) {
                if (!lease.getAsBoolean() || System.nanoTime() >= deadline) throw failure();
                String container = ref(scope.sourceId(), "device-group", group.getKey());
                if (!scope.domainRef().isEmpty() && !scope.domainRef().equals(container)) continue;
                if (!parsed.contains(group.getKey())) continue;
                PolicyCollectionTrace.step("device group " + container + " mapping", scope.sourceId());
                try {
                    var targets = targets(run, scope.sourceId(), group.getValue());
                    var metadata = new Metadata(ref(container, "policy"), scope.sourceId(), "Panorama " + scope.sourceId(), "PAN", container,
                            group.getKey(), group.getKey(), collectedAt, "", targets);
                    var snapshot = new PanoramaPolicyMapper().mapUnverifiedPrecedence(metadata, config, group.getKey(), parents);
                    rules += snapshot.sections().stream().mapToLong(section -> section.rules().size()).sum();
                    objects += snapshot.objects().size();
                    if (rules > 100_000 || objects > 200_000) throw PolicyCollectionTrace.failure("SIZE_LIMIT");
                    snapshots.add(snapshot);
                } catch (IllegalArgumentException | PolicyCollectionTrace.Failure invalid) {
                    if (invalid instanceof PolicyCollectionTrace.Failure traced && fatal(traced)) throw traced;
                    failures.add(new CollectionFailure(container, PolicyCollectionTrace.reason(invalid)));
                }
            }
            if ((!scope.domainRef().isEmpty() || !members.isEmpty()) && snapshots.isEmpty()) throw failure();
            return snapshots.stream().map(s -> new PolicySnapshot(s.metadata(), s.sections(), s.objects(), failures)).toList();
        } finally {
            timer.shutdownNow();
            if (key != null) Arrays.fill(key, '\0');
            Arrays.fill(credential.password(), '\0');
        }
    }
    private XmlApiSpec checked(int index, String group, char[] key, BooleanSupplier lease) {
        if (!lease.getAsBoolean()) throw failure();
        PanPolicyGates.require(gates, index);
        return request(index, group, key);
    }
    private Element read(ApiTarget target, XmlApiSpec spec, ScheduledExecutorService timer, long deadline, long[] bytes) {
        return read(target, spec, timer, deadline, bytes, null);
    }
    private Element read(ApiTarget target, XmlApiSpec spec, ScheduledExecutorService timer, long deadline, long[] bytes, String group) {
        String step = "keygen".equals(spec.type()) ? "authentication" : spec.formParams().containsKey("xpath")
            ? ("/config/shared".equals(spec.formParams().get("xpath")) ? "shared" : "device group " + ref(spec.formParams().get("xpath")))
            : "<show><devicegroups/></show>".equals(spec.formParams().get("cmd")) ? "show devicegroups" : "show dg-hierarchy";
        String template = "keygen".equals(spec.type()) ? "type=keygen" : spec.formParams().containsKey("xpath")
            ? PanPolicyGates.COMMANDS.get("/config/shared".equals(spec.formParams().get("xpath")) ? 0 : 1)
            : PanPolicyGates.COMMANDS.get("<show><devicegroups/></show>".equals(spec.formParams().get("cmd")) ? 2 : 3);
        PolicyCollectionTrace.step(step + " " + template, target.endpointId());
        if (System.nanoTime() >= deadline) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
        long started = System.nanoTime(), before = bytes[0];
        String[] parseFailure = {""};
        var prefix = new ByteArrayOutputStream(2048);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw PolicyCollectionTrace.failure("JOB_DEADLINE");
        Duration requestTimeout = Duration.ofNanos(Math.min(remaining, TIMEOUT.toNanos()));
        long readDeadline = Math.min(deadline, System.nanoTime() + requestTimeout.toNanos());
        var outcome = com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope.withoutRecording(() -> transport.xmlApiCallStreaming(target, spec, requestTimeout, input -> {
            var expiry = timer.schedule(() -> { try { input.close(); } catch (IOException ignored) {} },
                    Math.max(0, readDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try {
                var counted = new FilterInputStream(input) {
                    private long received;
                    private void count(int n) throws IOException {
                        if (n > 0) { received += n; bytes[0] += n; }
                        if (received > 16L * 1024 * 1024 || bytes[0] > 64L * 1024 * 1024) {
                            parseFailure[0] = "SIZE_LIMIT";
                            throw new IOException("POLICY_SIZE_LIMIT");
                        }
                        if (System.nanoTime() >= deadline) throw new IOException("POLICY_JOB_TIMEOUT");
                    }
                    @Override public int read() throws IOException { int n = in.read(); if (n >= 0 && prefix.size() < 2048) prefix.write(n); count(n < 0 ? 0 : 1); return n; }
                    @Override public int read(byte[] b, int off, int len) throws IOException { int n = in.read(b, off, len); if (n > 0) prefix.write(b, off, Math.min(n, 2048 - prefix.size())); count(n); return n; }
                };
                try { return PolicyXml.parse(counted).getDocumentElement(); }
                catch (IllegalArgumentException invalid) { return null; }
            } finally { expiry.cancel(false); }
        }));
        PolicyCollectionTrace.result(started, bytes[0] - before, outcome.getClass().getSimpleName());
        try {
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
    private static boolean fatal(PolicyCollectionTrace.Failure error) {
        return Set.of("LEASE_LOST", "JOB_DEADLINE", "SIZE_LIMIT", "POLICY_GATE_UNAVAILABLE", "INTERRUPTED")
                .stream().anyMatch(reason -> error.getMessage().endsWith(": " + reason));
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
