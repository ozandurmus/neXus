package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.AiviewSweepEstate;
import com.securityexpert.nexus.ui2.integration.support.AiviewSweepEstate.Estate;
import com.securityexpert.nexus.ui2.integration.support.AiviewSweepEstate.ValueClass;
import com.securityexpert.nexus.ui2.integration.support.KeepAliveHttp;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;

/**
 * The non-GET half of the raw-versus-masked sweep of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory
 * AIView inspection &amp; post-parsing masking law"). {@code AiviewRawVersusMaskedSweepTest} GETs every aiview-reachable
 * GET route; this test sends a request to every POST, PUT and DELETE route the shared enumeration
 * ({@link AiviewReachableRoutes#reachable()}) says aiview may call and counts the seeded raw values that survive in the
 * <em>response</em> body (a lookup, a preview or a create answer can echo what it was given or what it found).
 *
 * <p>Every such route has one row in {@link #TABLE}, reviewed by hand: {@code FIRE} with a synthetic request built from
 * the seeded raw values (so the response has something to leak), or {@code SKIP} with the reason. The sweep is
 * fail-closed in both directions: a reachable non-GET route without a row fails with its key, and a row without a
 * route fails too, so a new route needs a conscious review. A fired request must answer with one of the row's expected
 * statuses, so a refused request is never mistaken for "no leak"; a row whose only answer is a 4xx is marked
 * {@code NOT COVERED} (the response is still counted, but the success path was not exercised) and says why.</p>
 *
 * <p>No fired request starts work against a device: the environment has no devices, every route that would start a
 * device read, a discovery or a collection is either skipped or sent with identifiers that cannot resolve, so it
 * is refused before anything is queued. Output names route, status and value <em>class</em> with a count, never a value.
 * Seeded values are synthetic and built at runtime ({@link AiviewSweepEstate}).</p>
 */
class AiviewNonGetRoutesSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Surviving raw values found by this sweep and left for a follow-up lane: {@code "<METHOD> <route> <CLASS>"}. Must
     * equal the findings exactly. Empty means no seeded raw value survives in any fired aiview response.
     */
    static final Set<String> KNOWN_LEAKS = Set.of(
            // reviewed, by design: the lookup answers with the query it was given (PolicyPrivacy: "Lookup addresses are
            // user-entered or bounded subdivisions in the viewer's address space"). A viewer types addresses of its own
            // masked space and gets them back unchanged; masking the echo would turn them into other addresses. The
            // finding is only the caller's own raw input (2 occurrences: source and destination); the seeded rule,
            // object and policy data the lookup found carries none (the masked-space request above counts 0).
            "POST /api/v2/policy/policies/*/lookup (typed raw input) ADDRESS");

    private static final String UNKNOWN_ID = "00000000-0000-4000-8000-0000000000aa";

    // ------------------------------------------------------------------ the reviewed table

    /** One request of a row. */
    record Req(String method, String target, String contentType, byte[] body, Map<String, String> headers, String variant,
            Integer expected) {
        Req(String method, String target, String contentType, byte[] body, Map<String, String> headers) {
            this(method, target, contentType, body, headers, null, null);
        }

        /** The same request, which must answer exactly {@code status} (narrower than the statuses of its row). */
        Req expecting(int status) {
            return new Req(method, target, contentType, body, headers, variant, status);
        }

        static Req json(String method, String target, Object body) {
            try {
                return new Req(method, target, "application/json", JSON.writeValueAsBytes(body), Map.of());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        static Req bare(String method, String target) {
            return new Req(method, target, null, null, Map.of());
        }

        /** The same request, labelled as one that carries seeded raw values typed by the caller (its echo is reported apart). */
        Req typedRaw() {
            return new Req(method, target, contentType, body, headers, "typed raw input", expected);
        }
    }

    /**
     * What the sweep does with one route. {@code requests} is empty for a skip. {@code covered} says whether the success
     * path of the route is exercised (then an expected status is 2xx; a request may name its own exact status); a fired
     * route that is only refused is {@code NOT COVERED} and {@code note} says why.
     */
    record Row(String action, String note, boolean covered, Set<Integer> statuses, Function<Ctx, List<Req>> requests) {
        static Row fire(String note, int status, Function<Ctx, List<Req>> requests) {
            return new Row("FIRE", note, status / 100 == 2, Set.of(status), requests);
        }

        static Row fire(String note, Set<Integer> statuses, Function<Ctx, List<Req>> requests) {
            return new Row("FIRE", note, statuses.stream().anyMatch(s -> s / 100 == 2), statuses, requests);
        }

        static Row skip(String reason) {
            return new Row("SKIP", reason, false, Set.of(), c -> List.of());
        }
    }

    /** What a request builder may use: the seeded estate and the ids earlier rows of the run created. */
    static final class Ctx {
        final Estate e;
        final Map<String, String> state = new TreeMap<>();

        Ctx(Estate e) {
            this.e = e;
        }

        String state(String key) {
            return state.getOrDefault(key, UNKNOWN_ID);
        }

        /** A supporting request of the row (counted and reported like any other, status not asserted). */
        Resp setup(String label, Req req) {
            try {
                return setupCall(label, req);
            } catch (Exception e) {
                throw new IllegalStateException("setup request failed: " + label, e);
            }
        }
    }

    private static final String PA = "/api/v2/packet-analysis";

    /** Route key -> reviewed action. Insertion order is the order of the run (the packet-analysis rows depend on it). */
    static final Map<String, Row> TABLE = new LinkedHashMap<>();

    static {
        // ---- (a) read-style routes: a lookup, an evaluation, a preview, a path query
        TABLE.put("POST /api/v2/policy/policies/*/lookup", Row.fire(
                "two lookups of the seeded policy: one typed in the viewer's own address space (a masked address read from "
                        + "the masked object view, which finds the seeded rule), one typed with raw seeded addresses, names "
                        + "and cluster (the response echoes the address the caller typed: PolicyPrivacy keeps 'Lookup "
                        + "addresses are user-entered', see KNOWN_LEAKS)",
                200, c -> {
                    String policy = enc(c.e.policyId());
                    Resp object = c.setup("GET object (setup, masked address)", Req.bare("GET", "/api/v2/policy/objects/"
                            + enc(c.e.objectId()) + "?device=" + enc(c.e.cpMemberA().id()) + "&policy=" + policy));
                    Matcher masked = Pattern.compile("\\b[0-9]{1,3}(?:\\.[0-9]{1,3}){3}\\b").matcher(object.body());
                    String viewerAddress = masked.find() ? masked.group() : "192.0.2.1";
                    return List.of(
                            Req.json("POST", "/api/v2/policy/policies/" + policy + "/lookup", Map.of(
                                    "source", viewerAddress, "destination", viewerAddress, "service", "tcp/443",
                                    "target", c.e.cpMemberA().id())),
                            Req.json("POST", "/api/v2/policy/policies/" + policy + "/lookup", Map.of(
                                    "source", c.e.objectAddress(), "destination", c.e.panFirewall().managementAddress(),
                                    "service", "tcp/443", "target", c.e.cpMemberA().hostname(), "context", c.e.cluster()))
                                    .typedRaw());
                }));
        TABLE.put("POST /compliance/evaluate", Row.fire(
                "device evaluation of the seeded member and the estate overview (empty body)",
                200, c -> List.of(
                        Req.json("POST", "/compliance/evaluate", Map.of("deviceId", c.e.cpMemberA().id())),
                        Req.json("POST", "/compliance/evaluate", Map.of("deviceId", c.e.panFirewall().id())),
                        Req.json("POST", "/compliance/evaluate", Map.of()))));
        TABLE.put("POST /api/v2/reports/preview", Row.fire(
                "the five report templates, masked projection, on the seeded estate",
                200, c -> List.of("RPT-01", "RPT-02", "RPT-03", "RPT-05", "RPT-17").stream()
                        .map(t -> Req.json("POST", "/api/v2/reports/preview", reportBody(t, "MASKED", null))).toList()));
        TABLE.put("POST /api/v2/path-analysis/query", Row.fire(
                "reads the masked topology aiview sees (GET /api/v2/topology: the seeded connected estate must build segments "
                        + "and attachments), then asks two masked questions of it (a cluster leaf host to a firewall leaf "
                        + "host, which binds the seeded Check Point rule; the same source to a host inside the cutover "
                        + "segment, which adds the fabric-redirect caveat) and one with raw seeded addresses (422 "
                        + "PSEUDONYM_REFUSED); the audit rows are read back from authz_decisions",
                Set.of(200, 422), c -> pathQueries(c)));
        TABLE.put("POST /api/v2/path-analysis/validate", Row.fire(
                "NOT COVERED: a resolvable path would start device validation jobs, which this sweep never does (and the seed "
                        + "topology resolves none, see the query); the raw seeded addresses are refused (422 PSEUDONYM_REFUSED) "
                        + "before any plan or job exists",
                422, c -> List.of(Req.json("POST", "/api/v2/path-analysis/validate", Map.of(
                        "src", c.e.cpMemberA().managementAddress(), "dst", c.e.panFirewall().managementAddress(),
                        "protocol", "tcp", "port", 443)).typedRaw())));
        TABLE.put("POST /api/v2/operations/static-route/preview", Row.fire(
                "NOT COVERED: a valid preview admits a live device read job, so the body carries an invalid prefix length "
                        + "(400) together with raw unit, next hop and names; the stored preview is covered by the GET sweep",
                400, c -> List.of(new Req("POST", "/api/v2/operations/static-route/preview", "application/json",
                        bytes(Map.of("unit_id", c.e.cluster(), "vendor", "check_point", "routing_context", "default",
                                "operation", "ADD", "destination_prefix", "203.0.113.0/33",
                                "next_hop_ip", c.e.cpMemberB().interfaceAddress(), "interface_name", "eth1",
                                "route_name", "to-" + c.e.panFirewall().hostname(),
                                "device_comment", "for " + c.e.principal(), "reason", "ticket by " + c.e.principal())),
                        Map.of("Idempotency-Key", "sweep-" + RANDOM.nextInt(Integer.MAX_VALUE) + "-" + RANDOM.nextInt(Integer.MAX_VALUE))))));

        // ---- (b) starts and creates that answer with a body
        TABLE.put("POST /discovery/runs", Row.fire(
                "NOT COVERED: a valid start queues a device discovery; the credential reference does not exist, so the "
                        + "start is refused (422) before a run or job is written, the body is sent with the raw management address",
                422, c -> List.of(Req.json("POST", "/discovery/runs", Map.of("management_address",
                        c.e.management().managementAddress(), "vendor", "check_point",
                        "credential_reference_id", "cred-" + UNKNOWN_ID)))));
        TABLE.put("POST /devices/*/inventory/collect", Row.fire(
                "NOT COVERED: collecting a seeded device would queue a device read; an unknown device id is refused (409 "
                        + "DEVICE_NOT_FOUND) before any job",
                409, c -> List.of(Req.bare("POST", "/devices/" + UNKNOWN_ID + "/inventory/collect"))));
        TABLE.put("POST /devices/*/configuration/collect", Row.fire(
                "NOT COVERED: as the inventory collect; an unknown device id is refused (409 DEVICE_NOT_FOUND) before any job",
                409, c -> List.of(Req.bare("POST", "/devices/" + UNKNOWN_ID + "/configuration/collect"))));
        TABLE.put("POST /devices/inventory/collect-all", Row.skip(
                "starts an inventory read job for every enrolled device of the seed (real work against devices); the answer "
                        + "is three counters"));
        TABLE.put("POST /devices/configuration/collect-all", Row.skip(
                "starts a configuration collection job for every enrolled device of the seed (real work against devices)"));
        TABLE.put("POST /api/v2/policy/sources/*/collect", Row.fire(
                "NOT COVERED: collecting the seeded management server would queue a device read; an unknown source id is "
                        + "refused before any job",
                Set.of(404, 409), c -> List.of(Req.json("POST", "/api/v2/policy/sources/" + UNKNOWN_ID + "/collect",
                        Map.of("domainRef", c.e.domainId())))));
        TABLE.put("POST /api/v2/diagnostics", Row.fire(
                "NOT COVERED: a diagnostic read of a seeded device would reach it; an unknown device and gate are refused "
                        + "(409) before any job",
                Set.of(403, 409), c -> List.of(Req.json("POST", "/api/v2/diagnostics",
                        Map.of("device_id", UNKNOWN_ID, "gate_id", "gate-" + UNKNOWN_ID, "parameter", c.e.cpMemberA().hostname())))));
        TABLE.put("POST /api/v2/diagnostics/system-log-measurements/start", Row.skip(
                "starts a system-log measurement on every enrolled firewall of the seed (real work against devices)"));
        TABLE.put("POST /api/v2/diagnostics/system-log-measurements/spike-layout-review", Row.fire(
                "NOT COVERED: a review of a seeded device would reach it; an unknown device id is refused (409) before any job",
                Set.of(409), c -> List.of(Req.json("POST", "/api/v2/diagnostics/system-log-measurements/spike-layout-review",
                        Map.of("device_id", UNKNOWN_ID)))));
        for (String vendorPrefix : List.of("cp", "pan", "asa", "fgt")) {
            TABLE.put("POST /api/v2/" + vendorPrefix + "-failover/units/*/readiness", Row.fire(
                    "NOT COVERED: a readiness run of a seeded unit reads both members; a unit that does not exist is refused "
                            + "(409, a fixed code) before any job, sent with the raw cluster name",
                    Set.of(400, 409), c -> List.of(Req.json("POST",
                            "/api/v2/" + vendorPrefix + "-failover/units/" + UNKNOWN_ID + "/readiness",
                            Map.of("clusterId", c.e.cluster(), "unitId", UNKNOWN_ID)))));
        }
        TABLE.put("POST /api/v2/reports/runs", Row.fire(
                "queues one in-service report build (CSV, masked projection); no device is involved",
                202, c -> List.of(Req.json("POST", "/api/v2/reports/runs", reportBody("RPT-03", "MASKED", "CSV")))));

        // ---- packet analysis: a case aiview creates itself, its captures and telemetry, in dependency order
        TABLE.put("POST " + PA + "/cases", Row.fire(
                "creates a case whose title carries a raw hostname, a raw address and the administrator; the case "
                        + "and the list are read back masked",
                201, c -> List.of(Req.json("POST", PA + "/cases", Map.of("title",
                        "case " + c.e.panFirewall().hostname() + " " + c.e.panFirewall().managementAddress() + " "
                                + c.e.principal())))));
        TABLE.put("POST " + PA + "/cases/*/captures", Row.fire(
                "uploads an empty classic capture whose file name carries a raw hostname and a raw address",
                201, c -> List.of(multipart(PA + "/cases/" + c.state("caseId") + "/captures",
                        "cap-" + c.e.cpMemberA().hostname() + "-" + c.e.cpMemberA().managementAddress() + ".pcap",
                        emptyPcap()))));
        TABLE.put("POST " + PA + "/cases/*/telemetry", Row.fire(
                "uploads a text file whose name carries a raw hostname and a raw address and whose lines name the same",
                201, c -> List.of(multipart(PA + "/cases/" + c.state("caseId") + "/telemetry",
                        "sar-" + c.e.cpMemberB().hostname() + "-" + c.e.cpMemberB().managementAddress() + ".txt",
                        ("Linux 5.14 (" + c.e.cpMemberB().hostname() + ")\t11/20/2026\t_x86_64_\t(4 CPU)\n"
                                + "10:00:01 AM     CPU     %user     %system     %idle\n"
                                + "10:00:02 AM     all      1.00        0.50     98.50\n"
                                + "# peer " + c.e.cpMemberA().managementAddress() + " by " + c.e.principal() + "\n")
                                .getBytes(StandardCharsets.UTF_8)))));
        TABLE.put("PUT " + PA + "/telemetry/*", Row.fire(
                "sets the clock alignment of the telemetry file just uploaded",
                200, c -> List.of(Req.json("PUT", PA + "/telemetry/" + c.state("telemetryId"),
                        Map.of("time_zone", "UTC", "offset_seconds", 0)))));
        TABLE.put("POST " + PA + "/cases/*/compare", Row.fire(
                "NOT COVERED: a comparison reads the completed analysis database of the case; the seed holds none "
                        + "(409 ANALYSIS_NOT_READY), no aiview route finishes an analysis inside the test",
                409, c -> List.of(Req.json("POST", PA + "/cases/" + c.state("caseId") + "/compare",
                        Map.of("reference", c.e.cpMemberA().hostname(), "against", c.e.cpMemberB().managementAddress())))));
        TABLE.put("PUT " + PA + "/cases/*/topology", Row.fire(
                "NOT COVERED: saving a topology re-runs the analysis of a finished case; the seed holds none (409 "
                        + "ANALYSIS_NOT_READY)",
                409, c -> List.of(Req.json("PUT", PA + "/cases/" + c.state("caseId") + "/topology",
                        Map.of("nodes", List.of(Map.of("name", c.e.cpMemberA().hostname(),
                                "address", c.e.cpMemberA().managementAddress())))))));
        TABLE.put("DELETE " + PA + "/telemetry/*", Row.fire(
                "deletes the telemetry file just uploaded",
                200, c -> List.of(Req.bare("DELETE", PA + "/telemetry/" + c.state("telemetryId")))));
        TABLE.put("DELETE " + PA + "/cases/*", Row.fire(
                "deletes a second case aiview created for the purpose (the analysed one stays for the read-back)",
                200, c -> List.of(Req.bare("DELETE", PA + "/cases/" + c.state("caseIdToDelete")))));
        TABLE.put("POST " + PA + "/cases/*/analyze", Row.fire(
                "requests the analysis of the case aiview created; the answer is the queued job (last: the analysis then "
                        + "runs in the background of the test application)",
                202, c -> List.of(Req.bare("POST", PA + "/cases/" + c.state("caseId") + "/analyze"))));
    }

    /**
     * Covered answers that must show seeded data (an opaque id, never a raw value), so a zero count is a count over real
     * rows and not over an empty answer: route label -> what one of its responses must contain.
     */
    static final Map<String, Function<Estate, String>> MARKERS = Map.of(
            "POST /api/v2/policy/policies/*/lookup", Estate::ruleId,
            "POST /compliance/evaluate", e -> "\"total_firewalls\":",
            "POST /api/v2/reports/preview", e -> "RPT-05",
            "POST /api/v2/path-analysis/query", e -> "\"verdict\":");

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(Object body) {
        try {
            return JSON.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> reportBody(String template, String projection, String format) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template_id", template);
        body.put("projection", projection);
        if (format != null) body.put("format", format);
        return body;
    }

    /** A classic little-endian capture file header (24 bytes) with no packets. */
    private static byte[] emptyPcap() {
        return new byte[] {(byte) 0xd4, (byte) 0xc3, (byte) 0xb2, (byte) 0xa1, 2, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xff, (byte) 0xff, 0, 0, 1, 0, 0, 0};
    }

    // ------------------------------------------------------------------ path analysis query

    private static final String PATH_QUERY = "POST /api/v2/path-analysis/query";
    /** The 200 answers of the path query row (read after the sweep: computed, with hops, nothing raw). */
    private static final List<String> PATH_ANSWERS = new ArrayList<>();

    /**
     * The row's requests. The application builds the topology on a background thread after start, so the row waits for
     * the masked topology read to show it, then derives masked hosts from the attachments the viewer was shown: the host
     * offset inside a {@code /29} is preserved by the masker, so a host next to a firewall address is a host of the
     * same masked leaf network (the PathPseudonymMap rule: "a host offset it picked inside a masked network it was shown").
     */
    private static List<Req> pathQueries(Ctx c) {
        JsonNode topology = awaitBuiltTopology();
        Map<String, String> vendorOfNode = new LinkedHashMap<>();
        for (JsonNode n : topology.path("nodes")) vendorOfNode.put(n.path("node_id").asText(), n.path("vendor").asText());
        Set<String> stubs = new TreeSet<>();
        for (JsonNode seg : topology.path("segments")) {
            if (seg.path("segment_kind").asText().equals("STUB")) stubs.add(seg.path("segment_id").asText());
        }
        var topo = c.e.topo();
        long cpLeaf = -1;
        long panLeaf = -1;
        long panCutover = -1;
        int panLeafOffset = hostOffset(topo.dstLeafMember());
        int panCutoverOffset = hostOffset(topo.cutoverMember());
        for (JsonNode a : topology.path("attachments")) {
            String vendor = vendorOfNode.get(a.path("node_id").asText());
            String address = a.path("address").asText("");
            if (vendor == null || !stubs.contains(a.path("segment_id").asText()) || !address.contains("/")) continue;
            long host = ipValue(address.substring(0, address.indexOf('/')));
            if (vendor.equals("check_point")) {
                cpLeaf = host;
            } else if (vendor.equals("palo_alto")) {
                if (host % 8 == panLeafOffset) panLeaf = host;
                else if (host % 8 == panCutoverOffset) panCutover = host;
            }
        }
        assertTrue(cpLeaf >= 0 && panLeaf >= 0 && panCutover >= 0,
                "the masked topology must show the cluster leaf, the firewall leaf and the cutover attachment on stub segments");
        // the seeded hosts sit one below (source, destination leaf host) or one above (cutover) the attachment address
        String src = ipText(cpLeaf - (hostOffset(topo.srcLeafVip()) - hostOffset(c.e.objectAddress())));
        String dst = ipText(panLeaf - (panLeafOffset - hostOffset(topo.dstHost())));
        String cutoverDst = ipText(panCutover + (hostOffset(c.e.p1().flowSource()) - panCutoverOffset));
        Map<String, Object> plain = Map.of("src", src, "dst", dst, "protocol", "tcp", "port", 443);
        Map<String, Object> cutover = Map.of("src", src, "dst", cutoverDst, "protocol", "tcp", "port", 443);
        return List.of(
                Req.json("POST", "/api/v2/path-analysis/query", plain).expecting(200),
                Req.json("POST", "/api/v2/path-analysis/query", cutover).expecting(200),
                Req.json("POST", "/api/v2/path-analysis/query", Map.of(
                        "src", c.e.cpMemberA().interfaceAddress(), "dst", c.e.panFirewall().interfaceAddress(),
                        "protocol", "tcp", "port", 443)).typedRaw().expecting(422));
    }

    /** {@code GET /api/v2/topology} as aiview until the start-up build shows (counted like any answer, last one only). */
    private static JsonNode awaitBuiltTopology() {
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            Resp resp;
            JsonNode body;
            do {
                resp = send(Req.bare("GET", "/api/v2/topology"), sessionCookie, sessionCsrf);
                body = JSON.readTree(resp.body());
                if (resp.status() == 200 && "BUILT".equals(body.path("status").asText())) break;
                Thread.sleep(500);
            } while (System.nanoTime() < deadline);
            record("GET /api/v2/topology (setup, masked)", resp);
            assertEquals(200, resp.status(), "aiview topology read");
            assertEquals("BUILT", body.path("status").asText(), "the topology the application builds at start");
            assertTrue(body.path("segments").size() >= 3, "seeded estate: transit, leaf and cutover segments, got "
                    + body.path("segments").size());
            assertTrue(body.path("attachments").size() >= 5, "seeded estate: attachments, got " + body.path("attachments").size());
            return body;
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException("topology read failed", failure);
        } catch (Exception failure) {
            throw new IllegalStateException("topology read failed: " + failure.getClass().getSimpleName(), failure);
        }
    }

    private static int hostOffset(String address) {
        return (int) (ipValue(address) % 8);
    }

    private static long ipValue(String address) {
        long value = 0;
        for (String octet : address.split("\\.")) value = value * 256 + Integer.parseInt(octet);
        return value;
    }

    private static String ipText(long value) {
        return (value >> 24 & 255) + "." + (value >> 16 & 255) + "." + (value >> 8 & 255) + "." + (value & 255);
    }

    /** What the path query row must show after the sweep: computed answers with hops and caveats, nothing raw in the audit rows. */
    private static void assertPathQueryAnswersAndAudit(List<String> problems) throws Exception {
        if (PATH_ANSWERS.size() != 2) {
            problems.add("path query: " + PATH_ANSWERS.size() + " computed answers, expected 2");
            return;
        }
        boolean cutoverCaveat = false;
        // the seeded Check Point rule decided a hop of the first answer: its id passes (opaque), its name and section are masked
        if (!PATH_ANSWERS.get(0).contains(estate.ruleId())) problems.add("path query: the seeded rule is not bound to a hop");
        for (String body : PATH_ANSWERS) {
            JsonNode answer = JSON.readTree(body);
            if (!"COMPUTED".equals(answer.path("status").asText())) problems.add("path query answer is not COMPUTED");
            JsonNode branches = answer.path("branches");
            if (branches.isEmpty() || branches.get(0).path("hops").isEmpty()) problems.add("path query answer has no hops");
            for (JsonNode caveat : answer.path("caveats")) {
                cutoverCaveat |= "FABRIC_REDIRECT_NOT_MODELLED".equals(caveat.asText());
            }
        }
        if (!cutoverCaveat) problems.add("path query: no answer carries FABRIC_REDIRECT_NOT_MODELLED for the cutover segment");
        int computedRows = 0;
        int refusedRows = 0;
        int rows = 0;
        try (var connection = fixture.migrateConnection();
                var statement = connection.prepareStatement("select coalesce(reason_code, ''), coalesce(target_ref, ''), "
                        + "coalesce(authority, '') from authz_decisions where action_id = 'path_analysis_read' "
                        + "and authority = 'path_analysis_query'");
                var result = statement.executeQuery()) {
            while (result.next()) {
                rows++;
                String evidence = result.getString(1) + "\n" + result.getString(2);
                Map<ValueClass, Integer> found = AiviewSweepEstate.count(evidence, estate.raw());
                if (!found.isEmpty()) problems.add("authz_decisions path query row holds seeded raw values: " + found);
                if (evidence.contains("\"verdict\":\"REFUSED\"")) refusedRows++;
                else if (evidence.contains("\"masked_input\":true")) computedRows++;
            }
        }
        System.out.println("AIVIEW_NONGET path_analysis audit rows=" + rows + " computed=" + computedRows + " refused=" + refusedRows);
        if (rows != 3 || computedRows != 2 || refusedRows != 1) {
            problems.add("authz_decisions path query rows: " + rows + " (computed " + computedRows + ", refused " + refusedRows
                    + "), expected 3 (2, 1)");
        }
    }

    private static final String BOUNDARY = "----aiviewsweep7d2f1c";

    private static Req multipart(String target, String fileName, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Req("POST", target, "multipart/form-data; boundary=" + BOUNDARY, out.toByteArray(), Map.of());
    }

    // ------------------------------------------------------------------ boot

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static ArtefactStore store;
    private static Estate estate;

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_nonget");
        work = Files.createTempDirectory("ui2-aiview-nonget-");
        byte[] storeKey = new byte[32];
        RANDOM.nextBytes(storeKey);
        Path storeRoot = Files.createDirectory(work.resolve("artefacts"));
        store = new FileArtefactStore(storeRoot,
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(storeKey)));
        estate = Estate.generate();
        AiviewSweepEstate.seedInto(fixture, store, estate);
        context = new SpringApplicationBuilder(Ui2Application.class)
                .initializers(ctx -> ctx.addBeanFactoryPostProcessor(AiviewNonGetRoutesSweepTest::useTestArtefactStore))
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--ui2.db.url=" + fixture.databaseJdbcUrl(),
                        "--ui2.db.user-file=" + fixture.appUserSecretFile(),
                        "--ui2.db.password-file=" + fixture.appPasswordSecretFile(),
                        "--ui2.db.migrate-user-file=" + fixture.migrateUserSecretFile(),
                        "--ui2.db.migrate-password-file=" + fixture.migratePasswordSecretFile(),
                        "--ui2.role-binding.group-reference-key-file=" + keyFile("group_ref.key"),
                        "--ui2.credential-store.key-file=" + keyFile("credential.key"),
                        "--ui2.failover.master-key-file=" + work.resolve("failover.key"),
                        "--ui2.packet-analysis.root=" + Files.createDirectory(work.resolve("pa")),
                        "--ui2.sanitizer.work-dir=" + Files.createDirectory(work.resolve("sanitizer")),
                        "--ui2.scheduling.enabled=false");
        port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    /** The env-configured (here: absent) artefact store is replaced by the test store; the fixed machine port stays closed. */
    private static void useTestArtefactStore(ConfigurableListableBeanFactory beanFactory) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        registry.removeBeanDefinition("artefactStoreAccess");
        beanFactory.registerSingleton("artefactStoreAccess", new DeviceCompositionConfiguration.ArtefactStoreAccess(store));
        registry.removeBeanDefinition("machineConnector");
    }

    @AfterAll
    static void shutDown() throws IOException {
        if (context != null) context.close();
        if (fixture != null) fixture.close();
        if (work != null) {
            try (var paths = Files.walk(work)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static Path keyFile(String name) throws IOException {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        Path file = work.resolve(name);
        Files.writeString(file, Base64.getEncoder().encodeToString(key));
        return file;
    }

    // ------------------------------------------------------------------ HTTP

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    record Resp(int status, String body) {
    }

    private static String loginAsAiview() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username", "aiview", "password", "aiview"))))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "aiview login");
        for (String cookie : response.headers().allValues("set-cookie")) {
            if (cookie.startsWith("ui2_session=")) {
                String value = cookie.substring("ui2_session=".length());
                int end = value.indexOf(';');
                return end < 0 ? value : value.substring(0, end);
            }
        }
        throw new IllegalStateException("no session cookie on the aiview login");
    }

    /** One request on its own connection: the cookie, the CSRF token and the same-origin Origin a browser sends. */
    private static Resp send(Req req, String cookie, String csrf) throws Exception {
        String origin = "http://127.0.0.1:" + port;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", "ui2_session=" + cookie);
        headers.put("Accept", "application/json, text/plain, */*");
        if (!req.method().equals("GET")) {
            headers.put("Origin", origin);
            headers.put("X-CSRF-Token", csrf);
        }
        if (req.contentType() != null) headers.put("Content-Type", req.contentType());
        headers.putAll(req.headers());
        try (KeepAliveHttp http = new KeepAliveHttp(port, 60_000)) {
            KeepAliveHttp.Response response = http.exchange(req.method(), req.target(), headers, req.body());
            return new Resp(response.status(), new String(response.body(), StandardCharsets.UTF_8));
        }
    }

    private static String csrfToken(String cookie) throws Exception {
        try (KeepAliveHttp http = new KeepAliveHttp(port, 30_000)) {
            KeepAliveHttp.Response response = http.exchange("GET", "/session/status",
                    Map.of("Cookie", "ui2_session=" + cookie), null);
            assertEquals(200, response.status(), "aiview session status");
            return JSON.readTree(response.body()).path("csrf_token").asText("");
        }
    }

    private static String field(String body, String name) {
        try {
            JsonNode node = JSON.readTree(body).path(name);
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        } catch (IOException notJson) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the sweep

    /** The run's state: the session, and what every request (a row's own or a supporting one) found. */
    private static String sessionCookie;
    private static String sessionCsrf;
    private static final List<String> REPORT = new ArrayList<>();
    private static final Map<String, Map<ValueClass, Integer>> LEAKS = new TreeMap<>();
    private static int requestCount;
    private static int occurrenceCount;
    private static final Set<String> MARKERS_SEEN = new TreeSet<>();

    /** A supporting request of a row: counted like any other, its status is not asserted. */
    private static Resp setupCall(String label, Req req) throws Exception {
        Resp resp = send(req, sessionCookie, sessionCsrf);
        record(label, resp);
        return resp;
    }

    @Test
    void noSeededRawValueSurvivesInAnyAiviewNonGetResponse() throws Exception {
        List<AiviewReachableRoutes.Endpoint> reachable = AiviewReachableRoutes.reachable();
        assertTrue(reachable.size() > 100, "aiview-reachable routes: " + reachable.size());
        Set<String> nonGet = new TreeSet<>();
        for (var r : reachable) if (!r.method().equals("GET")) nonGet.add(r.key());

        // fail-closed in both directions: every reachable non-GET route has a reviewed row, every row has a route
        Set<String> withoutRow = new TreeSet<>(nonGet);
        withoutRow.removeAll(TABLE.keySet());
        Set<String> withoutRoute = new TreeSet<>(TABLE.keySet());
        withoutRoute.removeAll(nonGet);
        assertEquals(Set.of(), withoutRow, "aiview-reachable non-GET routes without a reviewed row in TABLE");
        assertEquals(Set.of(), withoutRoute, "TABLE rows without an aiview-reachable non-GET route");

        sessionCookie = loginAsAiview();
        assertNotNull(sessionCookie);
        sessionCsrf = csrfToken(sessionCookie);
        assertTrue(!sessionCsrf.isEmpty(), "aiview csrf token");

        Ctx ctx = new Ctx(estate);
        List<String> wrongStatus = new ArrayList<>();
        int fired = 0;
        int skipped = 0;
        int covered = 0;
        int notCovered = 0;

        for (var entry : TABLE.entrySet()) {
            String key = entry.getKey();
            Row row = entry.getValue();
            if (row.action().equals("SKIP")) {
                skipped++;
                REPORT.add(String.format(Locale.ROOT, "AIVIEW_NONGET SKIP  %-70s reason=%s", key, row.note()));
                continue;
            }
            fired++;
            if (row.covered()) covered++; else notCovered++;
            if (key.equals("DELETE " + PA + "/cases/*")) {
                // a second case, created only to be deleted
                Resp made = ctx.setup(key + " (setup create)", Req.json("POST", PA + "/cases",
                        Map.of("title", "throwaway " + estate.cpMemberB().hostname())));
                ctx.state.put("caseIdToDelete", String.valueOf(field(made.body(), "case_id")));
            }
            for (Req req : row.requests().apply(ctx)) {
                Resp resp = send(req, sessionCookie, sessionCsrf);
                Set<Integer> allowed = req.expected() == null ? row.statuses() : Set.of(req.expected());
                if (!allowed.contains(resp.status())) {
                    wrongStatus.add(key + " answered " + resp.status() + ", expected " + allowed);
                }
                String variant = req.variant() == null ? "" : " (" + req.variant() + ")";
                record(key + variant + (row.covered() ? "" : " [NOT COVERED]"), resp);
                captureIds(key, resp, ctx);
            }
            // after the create and the uploads: read the case and the list back, masked, with the raw-named children
            if (key.equals("PUT " + PA + "/telemetry/*")) {
                String caseTarget = PA + "/cases/" + ctx.state("caseId");
                for (String target : List.of(caseTarget, PA + "/cases")) {
                    Resp read = ctx.setup("GET " + (target.equals(caseTarget) ? PA + "/cases/*" : target) + " (read-back)",
                            Req.bare("GET", target));
                    if (read.status() != 200) wrongStatus.add("read-back of " + target.replace(ctx.state("caseId"), "*")
                            + " answered " + read.status());
                }
            }
        }

        assertPathQueryAnswersAndAudit(wrongStatus);

        REPORT.forEach(System.out::println);
        System.out.println("AIVIEW_NONGET_SUMMARY reachable_non_get_routes=" + nonGet.size() + " fired=" + fired
                + " skipped=" + skipped + " success_path_covered=" + covered + " refusal_only_not_covered=" + notCovered
                + " requests=" + requestCount + " seeded_values=" + estate.raw().size()
                + " surviving_raw_occurrences=" + occurrenceCount);

        Set<String> observed = new TreeSet<>();
        LEAKS.forEach((route, found) -> found.keySet().forEach(cls -> observed.add(route + " " + cls)));
        assertEquals(new TreeSet<>(MARKERS.keySet()), MARKERS_SEEN,
                "covered answers that must show seeded rows (by opaque id), or a zero count proves nothing");
        assertEquals(List.of(), wrongStatus, "every fired request must answer with one of its row's expected statuses");
        assertEquals(new TreeSet<>(KNOWN_LEAKS), observed,
                "seeded raw values surviving in aiview non-GET response bodies (route + value class); KNOWN_LEAKS must match exactly");
    }

    /** Counts, prints one line (route, status, value classes with counts, field paths) and remembers a finding. */
    private static void record(String label, Resp resp) {
        Map<String, ValueClass> raw = estate.raw();
        Map<ValueClass, Integer> found = AiviewSweepEstate.count(resp.body(), raw);
        requestCount++;
        occurrenceCount += found.values().stream().mapToInt(Integer::intValue).sum();
        String routeKey = label.replace(" [NOT COVERED]", "").replace(" (setup create)", "").replace(" (read-back)", "");
        Function<Estate, String> marker = MARKERS.get(routeKey.replace(" (typed raw input)", ""));
        if (marker != null && resp.body().contains(marker.apply(estate))) MARKERS_SEEN.add(routeKey.replace(" (typed raw input)", ""));
        if (!found.isEmpty()) LEAKS.merge(routeKey, found, (a, b) -> {
            Map<ValueClass, Integer> merged = new TreeMap<>(a);
            b.forEach((k, v) -> merged.merge(k, v, Integer::sum));
            return merged;
        });
        REPORT.add(String.format(Locale.ROOT, "AIVIEW_NONGET FIRE  %3d %-70s raw=%s%s", resp.status(), label,
                found.isEmpty() ? "0" : found.toString(), found.isEmpty() ? "" : " at=" + fieldsHolding(resp.body(), raw)));
    }

    /** The JSON field paths whose text holds a seeded raw value (the path only, never the value; at most 8). */
    static List<String> fieldsHolding(String body, Map<String, ValueClass> raw) {
        List<String> out = new ArrayList<>();
        try {
            walk(JSON.readTree(body), "", raw, out);
        } catch (IOException notJson) {
            out.add("(not JSON)");
        }
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    private static void walk(JsonNode node, String path, Map<String, ValueClass> raw, List<String> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(f -> walk(f.getValue(), path + "/" + f.getKey(), raw, out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) walk(node.get(i), path + "/*", raw, out);
        } else if (node.isTextual() && !AiviewSweepEstate.count(node.asText(), raw).isEmpty() && !out.contains(path)) {
            out.add(path);
        }
    }

    /** The ids a later row needs from an earlier answer (never printed). */
    private static void captureIds(String key, Resp resp, Ctx ctx) {
        if (key.equals(PATH_QUERY) && resp.status() == 200) PATH_ANSWERS.add(resp.body());
        if (key.equals("POST " + PA + "/cases")) {
            String id = field(resp.body(), "case_id");
            if (id != null) ctx.state.put("caseId", id);
        } else if (key.equals("POST " + PA + "/cases/*/telemetry")) {
            String id = field(resp.body(), "telemetry_id");
            if (id != null) ctx.state.put("telemetryId", id);
        }
    }

    /** The counter, applied here to this test's own estate: every raw value of the estate is found in a body that holds it. */
    @Test
    void theCounterSeesTheSeededValuesOfThisEstate() {
        Estate e = Estate.generate();
        String body = "{\"a\":\"" + e.cpMemberA().hostname() + "\",\"b\":\"" + e.cpMemberA().managementAddress()
                + "\",\"c\":\"" + e.cpMemberA().serial() + "\",\"d\":\"" + e.principal() + "\",\"e\":\"FW-TANGO-04\"}";
        assertEquals(Map.of(ValueClass.HOSTNAME, 1, ValueClass.ADDRESS, 1, ValueClass.SERIAL, 1, ValueClass.PRINCIPAL, 1),
                AiviewSweepEstate.count(body, e.raw()));
        assertEquals(Map.of(), AiviewSweepEstate.count("{\"hostname\":\"FW-TANGO-04\",\"ip\":\"192.0.2.1\"}",
                Map.of(e.cpMemberA().hostname(), ValueClass.HOSTNAME)));
    }
}
