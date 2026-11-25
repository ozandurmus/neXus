package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLI parity for the failover screens (standing directive {@code cli_parity_for_every_ui_action}; contract
 * {@code CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md} section 6.3; Cisco ASA and FortiGate readiness planes V209/V210; VSX
 * operation V265). Every mapping of {@code CpFailoverController} has a {@code failover-*} command, and each command really sends
 * that method and route under the plane of its vendor. The controller lives in the service module, which the cli module does not
 * depend on, so its mappings are read from the source file next to this module. Readiness-only vendors (Cisco ASA, FortiGate)
 * reach the read routes and the readiness check; every execution command refuses them at argument parsing, before any call.
 */
class FailoverCliParityTest {

    private static final Path CONTROLLER = Path.of("..", "service", "src", "main", "java", "com", "securityexpert", "nexus", "ui2", "service", "api",
            "CpFailoverController.java");
    private static final Pattern MAPPING = Pattern.compile("@(Get|Post)Mapping\\(\"(/[^\"]*)\"\\)");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[A-Za-z]+}");
    private static final Map<String, String> PLANES = Map.of(
            "check_point", "/api/v2/cp-failover",
            "palo_alto", "/api/v2/pan-failover",
            "cisco_asa", "/api/v2/asa-failover",
            "fortinet", "/api/v2/fgt-failover");
    private static final String CLUSTER = "CL-ROMEO-01";
    private static final String UNIT = "UNIT-ROMEO-01";
    private static final String REQUEST = "REQ-0001";
    private static final String RUN = "RUN-0001";
    private static final String APPROVALS = "[{\"requestId\":\"OTHER-1\",\"revision\":9,\"executionNonce\":\"zzz\"},"
            + "{\"requestId\":\"" + REQUEST + "\",\"revision\":3,\"executionNonce\":\"nonce-1\",\"reason\":\"brace } in a string\"}]";

    /** Controller route as "METHOD /path/{id}" (relative to the plane) -> the command line that must reach it (default vendor). */
    private static Map<String, String[]> commands() {
        Map<String, String[]> table = new LinkedHashMap<>();
        table.put("GET /units/{id}", new String[] {"failover-cluster-units", CLUSTER});
        table.put("GET /units", new String[] {"failover-units", CLUSTER});
        table.put("GET /summary", new String[] {"failover-summary"});
        table.put("POST /approvals", new String[] {"failover-request-approval", CLUSTER, UNIT, "--reason", "r", "--until", "2026-12-01T10:00:00Z"});
        table.put("POST /approvals/{id}/approve", new String[] {"failover-approve", REQUEST, CLUSTER, UNIT, "3"});
        table.put("GET /approvals", new String[] {"failover-approvals", CLUSTER, UNIT});
        table.put("POST /approvals/{id}/revoke", new String[] {"failover-revoke-approval", REQUEST});
        table.put("POST /runs", new String[] {"failover-start", CLUSTER, UNIT, REQUEST, "--confirm-warning"});
        table.put("POST /units/{id}/readiness", new String[] {"failover-readiness", CLUSTER, UNIT});
        table.put("POST /units/{id}/config-sync", new String[] {"failover-config-sync", CLUSTER, UNIT, "--source", "DEV-A", "--target", "DEV-B"});
        table.put("GET /config-syncs/{id}", new String[] {"failover-config-sync-status", "SYNC-1"});
        table.put("GET /runs", new String[] {"failover-runs", CLUSTER, UNIT});
        table.put("GET /runs/{id}", new String[] {"failover-run", RUN});
        table.put("POST /runs/{id}/resume-return", new String[] {"failover-resume", RUN, "--incident", "INC-1", "--reason", "r"});
        table.put("POST /runs/{id}/resume-return/approve", new String[] {"failover-resume-approve", RUN, "RES-1"});
        table.put("GET /incidents", new String[] {"failover-incidents"});
        table.put("POST /runs/{id}/incident/release", new String[] {"failover-incident-release", RUN, "--reason", "r"});
        return table;
    }

    private static List<String> controllerRoutes() throws Exception {
        String source = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
        List<String> routes = new ArrayList<>();
        Matcher mapping = MAPPING.matcher(source);
        while (mapping.find()) {
            routes.add(mapping.group(1).toUpperCase() + " " + PATH_VARIABLE.matcher(mapping.group(2)).replaceAll("{id}"));
        }
        return routes;
    }

    /** What the local server saw. */
    private record Seen(String method, String plane, String route, String query, String body, String csrf, String cookie, String ackHeader) {
        String key() {
            return method + " " + route;
        }
    }

    private record Reply(int status, String body) {
    }

    private interface Body {
        void run(NexusSessionCli cli, List<Seen> seen) throws Exception;
    }

    /** Every failover path, with its plane prefix removed and each opaque segment after a fixed word turned into {id}. */
    private static Seen record(com.sun.net.httpserver.HttpExchange exchange, String body) {
        String path = exchange.getRequestURI().getRawPath();
        String plane = PLANES.values().stream().filter(path::startsWith).findFirst().orElse("");
        String rest = path.substring(plane.length());
        String normalised = rest.replaceAll("^/(units|approvals|runs|config-syncs)/[^/]+", "/$1/{id}");
        return new Seen(exchange.getRequestMethod(), plane, normalised, exchange.getRequestURI().getRawQuery(), body,
                exchange.getRequestHeaders().getFirst("X-CSRF-Token"), exchange.getRequestHeaders().getFirst("Cookie"),
                exchange.getRequestHeaders().getFirst("X-Nexus-Business-Hours-Ack"));
    }

    private static void withServer(Path home, Function<Seen, Reply> reply, Body body) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen request = record(exchange, requestBody);
            seen.add(request);
            Reply answer = reply.apply(request);
            byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            new NexusSessionCli(home).saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            body.run(new NexusSessionCli(home), seen);
        } finally {
            server.stop(0);
        }
    }

    private static Reply ok(Seen request) {
        return "GET".equals(request.method()) && request.route().equals("/approvals") ? new Reply(200, APPROVALS) : new Reply(200, "{\"ok\":true}");
    }

    private static void withServer(Path home, Body body) throws Exception {
        withServer(home, FailoverCliParityTest::ok, body);
    }

    /** Runs the command, capturing stdout and stderr. */
    private record Result(int code, String out, String err) {
    }

    private static Result run(NexusSessionCli cli, String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int code = cli.run(args);
            return new Result(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static Seen one(NexusSessionCli cli, List<Seen> seen, String... args) {
        seen.clear();
        Result result = run(cli, args);
        assertEquals(0, result.code(), String.join(" ", args) + " -> " + result.err());
        assertEquals(1, seen.size(), String.join(" ", args));
        return seen.get(0);
    }

    private static String[] withVendor(String[] args, String vendor) {
        String[] out = new String[args.length + 2];
        System.arraycopy(args, 0, out, 0, args.length);
        out[args.length] = "--vendor";
        out[args.length + 1] = vendor;
        return out;
    }

    @Test
    @DisplayName("every mapping of the failover controller has a CLI command that sends that method and route")
    void everyControllerRouteHasACliCommand(@TempDir Path home) throws Exception {
        Map<String, String[]> table = commands();
        List<String> routes = controllerRoutes();
        assertEquals(17, routes.size(), "the controller's mapping count changed; extend FailoverCli and this table: " + routes);
        assertEquals(routes.size(), new TreeSet<>(routes).size(), "a route is mapped twice: " + routes);
        for (String route : routes) {
            assertTrue(table.containsKey(route), "the controller route " + route + " has no FailoverCli command in this test's table");
        }
        for (String row : table.keySet()) {
            assertTrue(routes.contains(row), "the table names " + row + " but the controller has no such route");
        }
        withServer(home, (cli, seen) -> {
            for (Map.Entry<String, String[]> row : table.entrySet()) {
                seen.clear();
                Result result = run(cli, row.getValue());
                assertEquals(0, result.code(), row.getValue()[0] + " -> " + result.err());
                assertFalse(seen.isEmpty(), row.getValue()[0]);
                Seen last = seen.get(seen.size() - 1);
                assertEquals(row.getKey(), last.key(), "the command for " + row.getKey() + " must send exactly that route");
                // The two configuration-sync commands are Palo Alto only; every other command defaults to Check Point.
                String expectedPlane = row.getValue()[0].startsWith("failover-config-sync") ? PLANES.get("palo_alto") : PLANES.get("check_point");
                assertEquals(expectedPlane, last.plane(), row.getValue()[0] + " must reach its plane without a --vendor option");
                // Only failover-start reads the approval list first; every other command is one call.
                assertEquals("failover-start".equals(row.getValue()[0]) ? 2 : 1, seen.size(), row.getValue()[0]);
                if (!"GET".equals(last.method())) {
                    assertEquals("csrf-9", last.csrf(), row.getValue()[0] + " must send the CSRF token on a write");
                }
                assertEquals("opaque-cookie", last.cookie().substring(last.cookie().indexOf('=') + 1), row.getValue()[0]);
            }
        });
    }

    @Test
    @DisplayName("the four vendor planes serve the six read/readiness routes with the same query and body")
    void allFourVendorsReachTheirPlane(@TempDir Path home) throws Exception {
        withServer(home, (cli, seen) -> {
            for (Map.Entry<String, String> vendor : PLANES.entrySet()) {
                String v = vendor.getKey();
                String plane = vendor.getValue();
                Seen summary = one(cli, seen, "failover-summary", "--vendor", v);
                assertEquals(plane, summary.plane(), v);
                assertEquals("GET /summary", summary.key(), v);
                assertNull(summary.query(), v);

                Seen byRef = one(cli, seen, "failover-units", CLUSTER, "--vendor", v);
                assertEquals(plane, byRef.plane(), v);
                assertEquals("GET /units", byRef.key(), v);
                assertEquals("clusterRef=" + CLUSTER, byRef.query(), v);

                Seen byMember = one(cli, seen, "failover-units", "--member", "DEV-A", "--vendor", v);
                assertEquals(plane, byMember.plane(), v);
                assertEquals("memberDeviceId=DEV-A", byMember.query(), v);

                Seen clusterUnits = one(cli, seen, "failover-cluster-units", CLUSTER, "--vendor", v);
                assertEquals(plane, clusterUnits.plane(), v);
                assertEquals("GET /units/{id}", clusterUnits.key(), v);

                Seen runs = one(cli, seen, "failover-runs", CLUSTER, UNIT, "--vendor", v);
                assertEquals(plane, runs.plane(), v);
                assertEquals("GET /runs", runs.key(), v);
                assertEquals("clusterId=" + CLUSTER + "&unitId=" + UNIT, runs.query(), v);

                Seen run = one(cli, seen, "failover-run", RUN, "--vendor", v);
                assertEquals(plane, run.plane(), v);
                assertEquals("GET /runs/{id}", run.key(), v);

                Seen readiness = one(cli, seen, "failover-readiness", CLUSTER, UNIT, "--vendor", v);
                assertEquals(plane, readiness.plane(), v);
                assertEquals("POST /units/{id}/readiness", readiness.key(), v);
                assertEquals("{\"clusterId\":\"" + CLUSTER + "\",\"unitId\":\"" + UNIT + "\"}", readiness.body(), v);
            }
        });
    }

    @Test
    @DisplayName("execution commands reach the Check Point and Palo Alto planes only")
    void executionCommandsKeepTheirTwoPlanes(@TempDir Path home) throws Exception {
        withServer(home, (cli, seen) -> {
            for (String vendor : List.of("check_point", "palo_alto")) {
                String plane = PLANES.get(vendor);
                List<String[]> writes = List.of(
                        new String[] {"failover-incidents"},
                        new String[] {"failover-approvals", CLUSTER, UNIT},
                        new String[] {"failover-request-approval", CLUSTER, UNIT, "--reason", "r", "--until", "2026-12-01T10:00:00Z"},
                        new String[] {"failover-approve", REQUEST, CLUSTER, UNIT, "3"},
                        new String[] {"failover-revoke-approval", REQUEST},
                        new String[] {"failover-start", CLUSTER, UNIT, REQUEST, "--confirm-warning"},
                        new String[] {"failover-incident-release", RUN, "--reason", "r"});
                for (String[] args : writes) {
                    seen.clear();
                    assertEquals(0, run(cli, withVendor(args, vendor)).code(), args[0] + " " + vendor);
                    assertEquals(plane, seen.get(seen.size() - 1).plane(), args[0] + " " + vendor);
                }
            }
        });
    }

    @Test
    @DisplayName("request-approval: no operation key unless --operation is given; closed values; VS operation never on Palo Alto")
    void requestApprovalOperation(@TempDir Path home) throws Exception {
        String head = "{\"clusterId\":\"" + CLUSTER + "\",\"unitId\":\"" + UNIT + "\",\"windowFrom\":\"2026-12-01T08:00:00Z\","
                + "\"windowUntil\":\"2026-12-01T10:00:00Z\",\"reason\":\"planned\",\"requestId\":\"REQ-9\"";
        String[] base = {"failover-request-approval", CLUSTER, UNIT, "--reason", "planned", "--until", "2026-12-01T10:00:00Z",
                "--from", "2026-12-01T08:00:00Z", "--request-id", "REQ-9"};
        withServer(home, (cli, seen) -> {
            assertEquals(head + "}", one(cli, seen, base).body(), "without --operation the body is unchanged");
            assertEquals(head + ",\"operation\":\"FAILOVER_ALL_VIRTUAL_SYSTEMS\"}",
                    one(cli, seen, withVendor(add(base, "--operation", "FAILOVER_ALL_VIRTUAL_SYSTEMS"), "check_point")).body());
            assertEquals(head + ",\"operation\":\"FAILOVER_ALL_VIRTUAL_SYSTEMS\"}", one(cli, seen, add(base, "--operation", "FAILOVER_ALL_VIRTUAL_SYSTEMS")).body());
            assertEquals(head + ",\"operation\":\"FAILOVER\"}", one(cli, seen, add(base, "--operation", "FAILOVER")).body());
            Seen pan = one(cli, seen, withVendor(add(base, "--operation", "FAILOVER"), "palo_alto"));
            assertEquals(PLANES.get("palo_alto"), pan.plane());
            assertTrue(pan.body().endsWith(",\"operation\":\"FAILOVER\"}"));
        });
    }

    private static String[] add(String[] args, String... more) {
        String[] out = new String[args.length + more.length];
        System.arraycopy(args, 0, out, 0, args.length);
        System.arraycopy(more, 0, out, args.length, more.length);
        return out;
    }

    @Test
    @DisplayName("approve, revoke, start, incident release, resume and config sync send the bodies the screens send")
    void writeBodies(@TempDir Path home) throws Exception {
        withServer(home, (cli, seen) -> {
            assertEquals("{\"clusterId\":\"" + CLUSTER + "\",\"unitId\":\"" + UNIT + "\",\"revision\":3}",
                    one(cli, seen, "failover-approve", REQUEST, CLUSTER, UNIT, "3").body());
            Seen revoke = one(cli, seen, "failover-revoke-approval", REQUEST);
            assertEquals("POST /approvals/{id}/revoke", revoke.key());
            assertEquals("{}", revoke.body());
            assertEquals("{\"clusterId\":\"" + CLUSTER + "\",\"unitId\":\"" + UNIT + "\",\"scheduledFor\":null,\"requestId\":\"" + REQUEST
                    + "\",\"revision\":3,\"executionNonce\":\"nonce-1\",\"warningConfirmed\":true}", lastOf(cli, seen, "failover-start", CLUSTER, UNIT, REQUEST,
                            "--confirm-warning").body());
            assertEquals(2, seen.size(), "the start reads the approval list first, then posts the run");
            assertEquals("GET /approvals", seen.get(0).key());
            assertEquals("clusterId=" + CLUSTER + "&unitId=" + UNIT, seen.get(0).query());

            Seen releaseCp = one(cli, seen, "failover-incident-release", RUN, "--reason", "done");
            assertEquals(PLANES.get("check_point"), releaseCp.plane());
            assertEquals("{\"reason\":\"done\"}", releaseCp.body());
            assertNull(releaseCp.ackHeader());
            Seen releasePan = one(cli, seen, "failover-incident-release", RUN, "--reason", "done", "--vendor", "palo_alto");
            assertEquals(PLANES.get("palo_alto"), releasePan.plane());
            assertEquals("POST /runs/{id}/incident/release", releasePan.key());
            assertNull(releasePan.ackHeader());

            assertEquals("{\"expectedIncidentId\":\"INC-1\",\"reason\":\"why\",\"businessHoursAck\":false}",
                    one(cli, seen, "failover-resume", RUN, "--incident", "INC-1", "--reason", "why").body());
            assertEquals("{\"resumeId\":\"RES-1\",\"businessHoursAck\":false}", one(cli, seen, "failover-resume-approve", RUN, "RES-1").body());

            // The acknowledgement is remembered by the CLI instance that took the flag: one fresh instance per acknowledged command.
            Seen ackRelease = one(new NexusSessionCli(home), seen, "failover-incident-release", RUN, "--reason", "done", "--vendor", "palo_alto",
                    "--business-hours-ack");
            assertEquals(PLANES.get("palo_alto"), ackRelease.plane());
            assertEquals("true", ackRelease.ackHeader());
            Seen ackResume = one(new NexusSessionCli(home), seen, "failover-resume", RUN, "--incident", "INC-1", "--reason", "why", "--business-hours-ack");
            assertEquals("{\"expectedIncidentId\":\"INC-1\",\"reason\":\"why\",\"businessHoursAck\":true}", ackResume.body());
            assertEquals("true", ackResume.ackHeader());
            assertEquals("{\"resumeId\":\"RES-1\",\"businessHoursAck\":true}",
                    one(new NexusSessionCli(home), seen, "--business-hours-ack", "failover-resume-approve", RUN, "RES-1").body());

            Seen sync = one(cli, seen, "failover-config-sync", CLUSTER, UNIT, "--source", "DEV-A", "--target", "DEV-B");
            assertEquals(PLANES.get("palo_alto"), sync.plane());
            assertEquals("{\"clusterId\":\"" + CLUSTER + "\",\"sourceDeviceId\":\"DEV-A\",\"targetDeviceId\":\"DEV-B\"}", sync.body());
            Seen status = one(cli, seen, "failover-config-sync-status", "SYNC-1");
            assertEquals(PLANES.get("palo_alto"), status.plane());
            assertEquals("GET /config-syncs/{id}", status.key());
        });
    }

    private static Seen lastOf(NexusSessionCli cli, List<Seen> seen, String... args) {
        seen.clear();
        Result result = run(cli, args);
        assertEquals(0, result.code(), String.join(" ", args) + " -> " + result.err());
        return seen.get(seen.size() - 1);
    }

    @Test
    @DisplayName("failover-start refuses, sending no run, when the request is not in the approval list")
    void startWithoutApprovalEntry(@TempDir Path home) throws Exception {
        withServer(home, (cli, seen) -> {
            Result result = run(cli, "failover-start", CLUSTER, UNIT, "REQ-MISSING", "--confirm-warning");
            assertEquals(1, result.code());
            assertTrue(result.err().startsWith("REQUEST_BINDING_REQUIRED: "), result.err());
            assertEquals(List.of("GET /approvals"), seen.stream().map(Seen::key).toList(), "no POST /runs without a binding");
        });
        // The listing itself failing is reported as the refusal it is, and no run is posted either.
        withServer(home, request -> new Reply(403, "{\"code\":\"WRONG_ROLE\"}"), (cli, seen) -> {
            Result result = run(cli, "failover-start", CLUSTER, UNIT, REQUEST, "--confirm-warning");
            assertEquals(3, result.code());
            assertEquals(1, seen.size());
        });
    }

    @Test
    @DisplayName("usage errors exit 2 and send nothing, including execution commands on Cisco ASA or FortiGate")
    void usageErrorsSendNothing(@TempDir Path home) throws Exception {
        List<String[]> bad = new ArrayList<>(List.of(
                new String[] {"failover-summary", "--vendor", "juniper"},
                new String[] {"failover-summary", "extra"},
                new String[] {"failover-summary", "--vendor"},
                new String[] {"failover-units"},
                new String[] {"failover-units", CLUSTER, "--member", "DEV-A"},
                new String[] {"failover-cluster-units"},
                new String[] {"failover-cluster-units", "a/b"},
                new String[] {"failover-cluster-units", CLUSTER, "extra"},
                new String[] {"failover-runs", CLUSTER},
                new String[] {"failover-run", "a b"},
                new String[] {"failover-readiness", CLUSTER},
                new String[] {"failover-revoke-approval"},
                new String[] {"failover-revoke-approval", "a/b"},
                new String[] {"failover-revoke-approval", REQUEST, "extra"},
                new String[] {"failover-approve", REQUEST, CLUSTER, UNIT, "three"},
                new String[] {"failover-approve", REQUEST, CLUSTER, UNIT, "-1"},
                new String[] {"failover-request-approval", CLUSTER, UNIT, "--reason", "r", "--until", "tomorrow"},
                new String[] {"failover-request-approval", CLUSTER, UNIT, "--reason", "r", "--until", "2026-12-01T10:00:00Z", "--from", "yesterday"},
                new String[] {"failover-request-approval", CLUSTER, UNIT, "--until", "2026-12-01T10:00:00Z"},
                new String[] {"failover-request-approval", CLUSTER, UNIT, "--reason", "r", "--until", "2026-12-01T10:00:00Z", "--operation", "REBOOT"},
                new String[] {"failover-request-approval", CLUSTER, UNIT, "--reason", "r", "--until", "2026-12-01T10:00:00Z", "--operation",
                        "FAILOVER_ALL_VIRTUAL_SYSTEMS", "--vendor", "palo_alto"},
                new String[] {"failover-request-approval", CLUSTER, UNIT, "--reason", "r", "--until", "2026-12-01T10:00:00Z", "--operation"},
                new String[] {"failover-start", CLUSTER, UNIT},
                new String[] {"failover-incident-release", RUN},
                new String[] {"failover-incident-release", RUN, "--reason", "r", "--vendor", "juniper"},
                new String[] {"failover-resume", RUN, "--incident", "INC-1"},
                new String[] {"failover-resume-approve", RUN},
                new String[] {"failover-config-sync", CLUSTER, UNIT, "--source", "DEV-A"},
                new String[] {"failover-config-sync-status"}));
        // Execution commands on a readiness-only vendor fail at argument parsing: SecurityWebMvcConfig maps none of them there.
        for (String vendor : List.of("cisco_asa", "fortinet")) {
            for (String[] args : List.of(
                    new String[] {"failover-incidents"},
                    new String[] {"failover-approvals", CLUSTER, UNIT},
                    new String[] {"failover-request-approval", CLUSTER, UNIT, "--reason", "r", "--until", "2026-12-01T10:00:00Z"},
                    new String[] {"failover-request-approval", CLUSTER, UNIT, "--reason", "r", "--until", "2026-12-01T10:00:00Z", "--operation",
                            "FAILOVER_ALL_VIRTUAL_SYSTEMS"},
                    new String[] {"failover-approve", REQUEST, CLUSTER, UNIT, "3"},
                    new String[] {"failover-revoke-approval", REQUEST},
                    new String[] {"failover-start", CLUSTER, UNIT, REQUEST, "--confirm-warning"},
                    new String[] {"failover-incident-release", RUN, "--reason", "r"})) {
                bad.add(withVendor(args, vendor));
            }
        }
        withServer(home, (cli, seen) -> {
            for (String[] args : bad) {
                Result result = run(cli, args);
                assertEquals(2, result.code(), String.join(" ", args));
                assertTrue(result.err().startsWith("usage: "), String.join(" ", args) + " -> " + result.err());
            }
            assertEquals(List.of(), seen, "a usage error must not reach the server");
        });
    }

    @Test
    @DisplayName("a server refusal prints its code in plain words with the shared exit codes")
    void refusalTranslation(@TempDir Path home) throws Exception {
        withServer(home, request -> new Reply(409, "{\"code\":\"OPEN_INCIDENT\"}"), (cli, seen) -> {
            Result result = run(cli, "failover-start", CLUSTER, UNIT, REQUEST, "--confirm-warning");
            // The first call is the approval listing: a 409 there is a refusal too.
            assertEquals(1, result.code());
            assertEquals("OPEN_INCIDENT: An open failover incident blocks this; a security_admin must release it first.\n", result.err());
            Result revoke = run(cli, "failover-revoke-approval", REQUEST);
            assertEquals(1, revoke.code());
            assertTrue(revoke.err().startsWith("OPEN_INCIDENT: "), revoke.err());
        });
        withServer(home, request -> new Reply(403, "{\"code\":\"WRONG_ROLE\"}"), (cli, seen) -> {
            Result result = run(cli, "failover-incident-release", RUN, "--reason", "r");
            assertEquals(3, result.code());
            assertEquals("WRONG_ROLE: Your role may not do this. (HTTP 403)\n", result.err());
        });
        withServer(home, request -> new Reply(409, "{\"code\":\"VENDOR_NOT_AVAILABLE\"}"), (cli, seen) -> {
            Result result = run(cli, "failover-readiness", CLUSTER, UNIT, "--vendor", "fortinet");
            assertEquals(1, result.code());
            assertEquals("VENDOR_NOT_AVAILABLE: Failover execution is not offered for this vendor; only readiness checks are supported.\n", result.err());
        });
        withServer(home, request -> new Reply(409, "{\"code\":\"SOMETHING_NEW\"}"), (cli, seen) -> {
            assertEquals("SOMETHING_NEW: The server refused this request.\n", run(cli, "failover-readiness", CLUSTER, UNIT).err());
        });
        withServer(home, request -> new Reply(409, "{\"code\":\"BUSINESS_HOURS_CONFIRM_REQUIRED\",\"message\":\"Inside business hours.\"}"), (cli, seen) -> {
            Result result = run(cli, "failover-revoke-approval", REQUEST);
            assertEquals(1, result.code());
            assertTrue(result.err().startsWith("BUSINESS_HOURS_CONFIRM_REQUIRED: Inside business hours."), result.err());
            assertTrue(result.err().contains("--business-hours-ack"), result.err());
        });
        withServer(home, request -> new Reply(500, "not json"), (cli, seen) -> {
            Result result = run(cli, "failover-summary", "--vendor", "cisco_asa");
            assertEquals(1, result.code());
            assertEquals("HTTP 500: not json\n", result.err());
        });
    }

    @Test
    @DisplayName("the CLI prints the server's answer verbatim on success")
    void outputIsTheServersAnswerOnly(@TempDir Path home) throws Exception {
        String answer = "{\"units\":[{\"name\":\"FW-TANGO-04\",\"state\":\"READY\"}]}";
        withServer(home, request -> new Reply(200, answer), (cli, seen) -> {
            for (String[] args : List.of(new String[] {"failover-summary", "--vendor", "cisco_asa"},
                    new String[] {"failover-cluster-units", CLUSTER, "--vendor", "fortinet"})) {
                Result result = run(cli, args);
                assertEquals(0, result.code());
                assertEquals(answer, result.out().strip());
            }
        });
    }

    @Test
    @DisplayName("the usage text lists the new commands, --operation and the four vendors, with no duplicate command name")
    void usageListsTheNewCommands() {
        PrintStream out = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            NexusSessionCli.printUsage();
        } finally {
            System.setOut(out);
        }
        String usage = stdout.toString(StandardCharsets.UTF_8);
        for (String name : List.of("failover-cluster-units", "failover-revoke-approval")) {
            assertTrue(usage.contains("  " + name + " "), "the usage text does not list " + name);
        }
        assertTrue(usage.contains("--operation FAILOVER|FAILOVER_ALL_VIRTUAL_SYSTEMS"));
        for (String vendor : PLANES.keySet()) assertTrue(usage.contains(vendor), "the usage text does not name the vendor " + vendor);
        List<String> names = new ArrayList<>();
        Matcher lineStart = Pattern.compile("(?m)^  (failover-[a-z-]+)").matcher(usage);
        while (lineStart.find()) names.add(lineStart.group(1));
        assertEquals(names.size(), new TreeSet<>(names).size(), "a failover command is listed twice: " + names);
        // Every command of the table appears as a line head of the usage text (failover-run is the tail of the failover-runs line).
        for (String[] command : commands().values()) {
            String name = command[0];
            assertTrue(usage.contains(name), "the usage text does not mention " + name);
        }
        assertTrue(names.containsAll(List.of("failover-summary", "failover-units", "failover-cluster-units", "failover-revoke-approval")), names.toString());
        String source;
        try {
            source = Files.readString(Path.of("src", "main", "java", "com", "securityexpert", "nexus", "ui2", "cli", "NexusSessionCli.java"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        for (String name : List.of("failover-cluster-units", "failover-revoke-approval")) {
            assertFalse(source.contains("case \"" + name + "\""), name + " is already a NexusSessionCli command");
        }
    }
}
