package com.securityexpert.nexus.ui2.service.pathanalysis.engine;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securityexpert.nexus.ui2.policy.PanoramaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult.Endpoint;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyBuilder;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyFixtures;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator.Binding;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator.Device;

/**
 * Synthetic estates and policies for the stage A4 golden tests: documentation addresses (RFC 5737) and pseudonym names
 * only. Estates under {@code src/test/resources/pathanalysis/engine/} are built by the real A3 builder; policies are the
 * snapshot shapes the real mappers produce (Check Point built directly as in the A2 tests, Palo Alto by
 * {@link PanoramaPolicyMapper#local}). Renders a result as stable text keyed by hostname labels, never by salted ids.
 */
final class PathFixtures {

    static final byte[] SALT = "synthetic-path-engine-golden-salt-01".getBytes(StandardCharsets.UTF_8);
    static final Instant BUILT_AT = Instant.parse("2026-11-04T07:00:00Z");
    static final Instant COMPUTED_AT = Instant.parse("2026-11-04T08:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private PathFixtures() {
    }

    // ---- topology ------------------------------------------------------------------------------------------------

    static JsonNode estate(String name) {
        try (InputStream in = PathFixtures.class.getResourceAsStream("/pathanalysis/engine/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String text(String name) {
        try (InputStream in = PathFixtures.class.getResourceAsStream("/pathanalysis/engine/" + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TopologyGraph graph(JsonNode estate) {
        return TopologyBuilder.build(new TopologyInput("tps-engine-golden", BUILT_AT, TopologyFixtures.devices(estate),
                List.of(), SALT));
    }

    static TopologyGraph graph(String name) {
        return graph(estate(name));
    }

    /** A copy of the estate with every route of one device edited (e.g. its {@code active} fact removed). */
    static JsonNode editRoutes(JsonNode estate, String deviceId, Consumer<ObjectNode> edit) {
        JsonNode copy = estate.deepCopy();
        for (JsonNode d : copy.get("devices")) {
            if (d.get("device_id").asText().equals(deviceId)) {
                for (JsonNode c : d.get("run").get("contexts")) {
                    c.get("routes").forEach(r -> edit.accept((ObjectNode) r));
                }
            }
        }
        return copy;
    }

    /** A copy of the estate with one more route on a device's first context. */
    static JsonNode addRoute(JsonNode estate, String deviceId, String routeJson) {
        JsonNode copy = estate.deepCopy();
        try {
            JsonNode route = JSON.readTree(routeJson);
            for (JsonNode d : copy.get("devices")) {
                if (d.get("device_id").asText().equals(deviceId)) {
                    ((com.fasterxml.jackson.databind.node.ArrayNode) d.get("run").get("contexts").get(0).get("routes")).add(route);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return copy;
    }

    /** Destination NAT of the published address to the web server, entering and (by the original route) leaving the transit zone. */
    static final String PAN_DNAT = "<entry name='NAT-DNAT-01'><from><member>ZONE-TRANSIT</member></from><to><member>ZONE-TRANSIT</member></to>"
            + "<source><member>any</member></source><destination><member>OBJ-PUBLIC-01</member></destination><service>any</service>"
            + "<destination-translation><translated-address>OBJ-WEB-01</translated-address></destination-translation></entry>";

    static TopologyGraph.Node node(TopologyGraph g, String hostname) {
        return g.nodes().stream().filter(n -> n.members().stream().anyMatch(m -> m.hostname().filter(hostname::equals).isPresent()))
                .findFirst().orElseThrow(() -> new IllegalStateException("no node " + hostname));
    }

    static Map<String, String> labels(TopologyGraph g) {
        Map<String, String> labels = new HashMap<>();
        for (TopologyGraph.Node n : g.nodes()) {
            labels.put(n.nodeId(), n.kind() == TopologyGraph.NodeKind.CLOUD ? "CLOUD"
                    : n.members().stream().map(m -> m.hostname().orElse(m.deviceId())).collect(Collectors.joining("+"))
                            + "/" + n.context() + "/" + n.routingTable().orElse("?"));
        }
        return labels;
    }

    // ---- policy binding ----------------------------------------------------------------------------------------

    /** Binds a unit to the prepared policy of its routing member's device id. */
    static final class Policies implements HopPolicySource {
        private final Map<String, HopPolicy> byDevice = new LinkedHashMap<>();

        Policies bind(String deviceId, PolicySnapshot snapshot, Device device) {
            return put(deviceId, new Bound(HopPolicyEvaluator.prepare(snapshot, device, Binding.NONE),
                    Optional.of(Instant.parse("2026-11-04T05:00:00Z")), false));
        }

        Policies stale(String deviceId, PolicySnapshot snapshot, Device device) {
            return put(deviceId, new Bound(HopPolicyEvaluator.prepare(snapshot, device, Binding.NONE),
                    Optional.of(Instant.parse("2026-10-01T05:00:00Z")), true));
        }

        Policies put(String deviceId, HopPolicy policy) {
            byDevice.put(deviceId, policy);
            return this;
        }

        @Override
        public HopPolicy policyFor(TopologyGraph.Node node) {
            return node.activeMemberDeviceId().map(byDevice::get)
                    .orElse(new Unbound(PathReasons.POLICY_BINDING_UNPROVEN));
        }
    }

    // ---- Check Point snapshot (shape of CheckPointPolicyMapper, as in the A2 tests) --------------------------------

    static final String CP_POLICY = "policy-synthetic-cp-01";

    static String obj(String uid) {
        return ref(CP_POLICY, "object", uid);
    }

    private static PolicyObject object(String uid, String name, String type, String... values) {
        return new PolicyObject(obj(uid), name, type, List.of(), List.of(values), "RESOLVED");
    }

    private static Map<String, PolicyObject> cpObjects() {
        Map<String, PolicyObject> objects = new HashMap<>();
        for (var o : List.of(object("any", "ANY", "any"),
                object("web", "OBJ-WEB-01", "address", "ipv4-address: 203.0.113.10"),
                object("public", "OBJ-PUBLIC-01", "address", "ipv4-address: 203.0.113.200"),
                object("clients", "OBJ-CLIENTS-01", "address", "subnet4: 198.51.100.0", "mask-length4: 24"),
                object("https", "OBJ-HTTPS", "service", "protocol: tcp", "port: 443"))) {
            objects.put(o.id(), o);
        }
        objects.put(obj("targets"), new PolicyObject(obj("targets"), "Policy Targets", "unresolved", List.of(),
                List.of("native-type: Global"), "UNSUPPORTED"));
        objects.put(obj("original"), new PolicyObject(obj("original"), "Original", "unresolved", List.of(),
                List.of("native-type: Global"), "UNSUPPORTED"));
        return objects;
    }

    private static Cell cell(String uid) {
        return new Cell(List.of(obj(uid)), false);
    }

    static Rule cpAccess(String id, int number, String source, String destination, String service, String action) {
        return new Rule(id, "uuid-" + id, number, "RULE-" + id, true, cell(source), cell(destination), cell(service),
                new Cell(List.of(), false), action, "Log", "", Map.of("install-on", List.of(obj("targets")), "time",
                        List.of(obj("any")), "layer-name", List.of("LAYER-ALPHA")));
    }

    /** A manual static NAT rule: original (source, destination, service) to translated (source, destination, service). */
    static Rule cpNat(String id, int number, String[] original, String[] translated) {
        Map<String, List<String>> extras = new LinkedHashMap<>();
        extras.put("translated-source", List.of(obj(translated[0])));
        extras.put("translated-destination", List.of(obj(translated[1])));
        extras.put("translated-service", List.of(obj(translated[2])));
        extras.put("install-on", List.of(obj("targets")));
        extras.put("auto-generated", List.of("false"));
        return new Rule(id, "uuid-" + id, number, "NAT-" + id, true, cell(original[0]), cell(original[1]),
                cell(original[2]), new Cell(List.of(), false), "static", "UNKNOWN", "", extras);
    }

    static String[] of(String... values) {
        return values;
    }

    /** One package installed on the listed enrolled gateways (no virtual system). */
    static PolicySnapshot checkPoint(List<Rule> access, List<Rule> nat, String... deviceIds) {
        List<Target> targets = Arrays.stream(deviceIds).map(d -> new Target(d, "FW-" + d, "", "UNKNOWN")).toList();
        var metadata = new Metadata(CP_POLICY, "mds-1", "MGR-BRAVO-01", "CP", "domain-1", "DOM-TANGO-01", "POL-ALPHA-01",
                "2026-11-04T05:00:00Z", "", targets);
        List<Section> sections = new ArrayList<>(List.of(new Section(ref(CP_POLICY, "layer-uid", ""), "OBJ-SECTION-01",
                "CP access layer", null, access)));
        sections.add(new Section(ref(CP_POLICY, "nat"), "NAT", "CP NAT rulebase", null, nat));
        return new PolicySnapshot(metadata, sections, cpObjects(), List.of());
    }

    static Device cpDevice(String deviceId) {
        return new Device(deviceId, "", null);
    }

    // ---- Palo Alto local snapshot (PanoramaPolicyMapper.local) ----------------------------------------------------

    static final String PAN_OBJECTS = "<address><entry name='OBJ-WEB-01'><ip-netmask>203.0.113.10</ip-netmask></entry>"
            + "<entry name='OBJ-PUBLIC-01'><ip-netmask>203.0.113.200</ip-netmask></entry>"
            + "<entry name='OBJ-SNAT-01'><ip-netmask>203.0.113.1</ip-netmask></entry>"
            + "<entry name='OBJ-CLIENTS-01'><ip-netmask>198.51.100.0/24</ip-netmask></entry></address>"
            + "<service><entry name='SVC-HTTPS'><protocol><tcp><port>443</port></tcp></protocol></entry></service>"
            + "<application><entry name='APP-SYNTH-01'><default><port><member>tcp/8443,9443</member></port></default></entry></application>";

    static String panSecurity(String name, String from, String to, String destination, String service, String application,
            String action) {
        return "<entry name='" + name + "' uuid='uuid-" + name + "'><from><member>" + from + "</member></from><to><member>" + to
                + "</member></to><source><member>any</member></source><destination><member>" + destination
                + "</member></destination><service><member>" + service + "</member></service><application><member>"
                + application + "</member></application><action>" + action + "</action></entry>";
    }

    /** Source NAT of the client range to the DMZ interface address, from the transit zone into the DMZ. */
    static final String PAN_SNAT = "<entry name='NAT-SNAT-01'><from><member>ZONE-TRANSIT</member></from><to><member>ZONE-DMZ</member></to>"
            + "<source><member>OBJ-CLIENTS-01</member></source><destination><member>any</member></destination><service>any</service>"
            + "<source-translation><dynamic-ip-and-port><translated-address><member>OBJ-SNAT-01</member></translated-address>"
            + "</dynamic-ip-and-port></source-translation></entry>";

    static PolicySnapshot paloAlto(String deviceId, String security, String nat) {
        String xml = "<response status='success'><result><config><devices><entry name='localhost.localdomain'><vsys><entry name='vsys1'>"
                + PAN_OBJECTS + "<rulebase><security><rules>" + security + "</rules></security><nat><rules>" + nat
                + "</rules></nat><default-security-rules><rules></rules></default-security-rules></rulebase></entry></vsys></entry>"
                + "</devices></config></result></response>";
        return new PanoramaPolicyMapper().local(deviceId, "2026-11-04T05:00:00Z", "artifact-" + deviceId, xml).get(0);
    }

    /** A Panorama device-group snapshot with pre-rulebase security and NAT, the firewall in sync. */
    static PolicySnapshot panorama(String deviceId, String preSecurity, String preNat) {
        String xml = "<config><shared>" + PAN_OBJECTS + "</shared><devices><entry name='localhost.localdomain'><device-group>"
                + "<entry name='DG-TANGO'><pre-rulebase><security><rules>" + preSecurity + "</rules></security><nat><rules>" + preNat
                + "</rules></nat></pre-rulebase></entry></device-group></entry></devices></config>";
        var metadata = new Metadata("pan-policy-" + deviceId, "panorama-1", "MGR-BRAVO-01", "PAN", "dg-1", "DG-TANGO", "DG-TANGO",
                "2026-11-04T05:00:00Z", "", List.of(new Target(deviceId, "FW-" + deviceId, "vsys1", "IN_SYNC")));
        return new PanoramaPolicyMapper().map(metadata, xml, "DG-TANGO", Map.of(), false);
    }

    static Device panDevice(String deviceId) {
        return new Device(deviceId, "vsys1", null);
    }

    // ---- golden rendering --------------------------------------------------------------------------------------

    static String render(String title, TopologyGraph g, PathResult r) {
        Map<String, String> label = labels(g);
        List<String> lines = new ArrayList<>();
        var q = r.query();
        lines.add("== " + title + ": " + q.source() + " -> " + q.destination() + " " + q.protocol() + "/" + q.destinationPort()
                + (q.edgeMode() ? " edge" : "") + q.ingress().map(h -> " from " + label.get(h.nodeId())
                        + h.interfaceName().map(i -> ":" + i).orElse("")).orElse(""));
        lines.add("VERDICT " + r.verdict() + " reason=" + r.reason() + " plain=" + r.plain() + " truncated=" + r.ecmpTruncated()
                + " asymmetric=" + r.asymmetric() + " caveats=" + r.caveats() + " warnings=" + r.warnings());
        lines.add("INGRESS " + r.ingress().mode() + " " + r.ingress().status() + " "
                + r.ingress().candidates().stream().map(c -> label.get(c.nodeId())).toList());
        var s = r.executiveSummary();
        lines.add("SUMMARY \"" + s.headline() + "\" blocking=" + refs(s.blockingHops()) + " unknown=" + refs(s.unknownHops())
                + " branches=" + s.ecmpBranchCount() + " freshness=" + s.freshnessStatus());
        for (PathResult.Branch b : r.branches()) {
            lines.add("BRANCH " + b.branchId() + " " + b.verdict() + " reason=" + b.reason() + " termination=" + b.termination()
                    + " blocking=" + b.blockingHopIndex().map(String::valueOf).orElse("-") + " caveats=" + b.caveats()
                    + " warnings=" + b.warnings());
            for (PathResult.Hop h : b.hops()) {
                lines.add("  HOP " + h.index() + " " + label.get(h.nodeId()) + " own=" + h.ownVerdict() + " path=" + h.verdict()
                        + " reason=" + h.reason() + " reasons=" + h.reasons());
                lines.add("    in=" + end(h.ingress()) + " out=" + end(h.egress()) + " packet=" + packet(h.packetIn()) + " => "
                        + packet(h.packetOut()));
                lines.add("    route=" + h.route().map(rt -> rt.destination() + rt.nextHop().map(n -> " via " + n).orElse("")
                        + rt.interfaceName().map(i -> " dev " + i).orElse("") + rt.nextHopKind().map(k -> " " + k).orElse("")
                        + (rt.ecmpBranch() ? " ecmp" : "")).orElse("-")
                        + " nat=" + h.nat().map(n -> n.status() + n.rules().stream().map(m -> m.get("name")).toList()).orElse("-")
                        + " rule=" + h.security().flatMap(PathResult.Security::decidingRule).map(m -> m.get("name")).orElse("-")
                        + h.postNatSecurity().map(p -> " postNatRule=" + p.decidingRule().map(m -> m.get("name")).orElse("-")).orElse("")
                        + (h.policyStale() ? " stale" : ""));
                lines.add("    caveats=" + h.caveats() + " notes=" + h.notes() + " flags=" + h.flags());
            }
            var ret = b.returnPath();
            lines.add("  RETURN " + ret.status() + " reason=" + ret.reason());
            for (PathResult.ReturnBranch rb : ret.branches()) {
                lines.add("    RBRANCH " + rb.termination() + " " + rb.status());
                for (PathResult.ReturnHop rh : rb.hops()) {
                    lines.add("      RHOP " + rh.index() + " " + label.get(rh.nodeId()) + " " + rh.source() + ">" + rh.destination()
                            + (rh.reverseTranslated() ? " un-NAT" : "") + " route=" + rh.route().map(rt -> rt.destination()
                                    + rt.nextHop().map(n -> " via " + n).orElse("")).orElse("-")
                            + " reasons=" + rh.reasons());
                }
            }
        }
        return String.join("\n", lines) + "\n";
    }

    private static String refs(List<PathResult.HopRef> refs) {
        return refs.stream().map(x -> x.branchId() + ":" + x.hopIndex()).toList().toString();
    }

    private static String end(Endpoint e) {
        return e.interfaceName().orElse("-") + "/" + e.zone().orElse("-");
    }

    private static String packet(HopPolicyEvaluator.Tuple t) {
        return t.source() + ">" + t.destination() + ":" + t.destinationPort();
    }
}
