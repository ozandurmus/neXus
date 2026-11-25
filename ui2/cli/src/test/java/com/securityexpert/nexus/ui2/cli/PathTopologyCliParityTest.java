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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLI parity for {@code OfflineAnalysisController}, {@code PathAnalysisController}, {@code PathValidationController} and
 * {@code TopologyController} (standing directive {@code cli_parity_for_every_ui_action}): every request mapping of the four
 * controllers has a named {@code PathTopologyCli} command, and every command sends that exact method, route, query, body and
 * CSRF header. The mapping table is explicit; a controller route with no command fails with its path, and a table row with no
 * controller route fails too, so the two cannot drift. Because the cli module does not depend on the service module, the
 * controllers' mappings are read directly from their source files next to this module.
 */
class PathTopologyCliParityTest {

    private static final Path API = Path.of("..", "service", "src", "main", "java", "com", "securityexpert", "nexus", "ui2",
            "service", "api");
    private static final List<String> CONTROLLERS = List.of(
            "OfflineAnalysisController.java",
            "PathAnalysisController.java",
            "PathValidationController.java",
            "TopologyController.java");
    private static final Pattern MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping\\((?:[^)]*?\\b(?:path|value)\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "(?:pv-|tlo-)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[A-Za-z0-9_]+}");

    private static final String POLICY = "11111111-2222-4333-8444-555555555555";
    private static final String ANALYSIS = "22222222-3333-4444-8555-666666666666";
    private static final String VALIDATION = "pv-33333333-4444-4555-8666-777777777777";
    private static final String OVERRIDE = "tlo-44444444-5555-4666-8777-888888888888";

    private static final String MAPPING_JSON = "{\"src\":\"source_ip\",\"dst\":\"dest_ip\",\"port\":\"dest_port\",\"protocol\":\"proto\"}";
    private static final String PATH_QUERY_JSON = "{\"src\":\"192.0.2.10\",\"dst\":\"198.51.100.20\",\"protocol\":\"tcp\",\"port\":443}";
    private static final String PATH_VALIDATE_JSON =
            "{\"src\":\"192.0.2.10\",\"dst\":\"198.51.100.20\",\"protocol\":\"tcp\",\"port\":443,\"branch_id\":\"b0\"}";
    private static final String OVERRIDE_JSON = "{\"action\":\"ADD\",\"link_kind\":\"P2P\",\"a_device_id\":\"FW-TANGO-04\","
            + "\"a_context\":\"0\",\"a_interface_name\":\"eth1\",\"b_device_id\":\"FW-BRAVO-02\",\"b_context\":\"0\","
            + "\"b_interface_name\":\"eth2\",\"assumed_subnet\":\"192.0.2.0/30\",\"reason_code\":\"TRANSIT_VLAN\"}";

    private record Row(String[] args, String expectedJsonBody, boolean multipart) {
        static Row noBody(String... args) {
            return new Row(args, null, false);
        }

        static Row json(String expectedBody, String... args) {
            return new Row(args, expectedBody, false);
        }

        static Row multipartUpload(String... args) {
            return new Row(args, null, true);
        }
    }

    /** Controller route (path variables written {id}) -> the command line that must reach it. */
    private static Map<String, Row> commands(Path csvFile) {
        Map<String, Row> table = new LinkedHashMap<>();
        table.put("GET /api/v2/policy/policies/{id}/offline-analyses",
                Row.noBody("policy-offline-analyses", POLICY));
        table.put("GET /api/v2/policy/offline-analyses/{id}",
                Row.noBody("policy-offline-analysis", ANALYSIS));
        table.put("POST /api/v2/policy/policies/{id}/offline-analyses",
                Row.multipartUpload("policy-offline-analysis-upload", POLICY, MAPPING_JSON, csvFile.toString()));
        table.put("DELETE /api/v2/policy/offline-analyses/{id}",
                Row.noBody("policy-offline-analysis-delete", ANALYSIS));
        table.put("POST /api/v2/path-analysis/query",
                Row.json(PATH_QUERY_JSON, "path-query", PATH_QUERY_JSON));
        table.put("POST /api/v2/path-analysis/validate",
                Row.json(PATH_VALIDATE_JSON, "path-validate", PATH_VALIDATE_JSON));
        table.put("GET /api/v2/path-analysis/validate/{id}",
                Row.noBody("path-validation", VALIDATION));
        table.put("GET /api/v2/topology",
                Row.noBody("topology"));
        table.put("GET /api/v2/topology/overrides",
                Row.noBody("topology-overrides"));
        table.put("POST /api/v2/topology/overrides",
                Row.json(OVERRIDE_JSON, "topology-override-add", OVERRIDE_JSON));
        table.put("POST /api/v2/topology/overrides/{id}/revoke",
                Row.noBody("topology-override-revoke", OVERRIDE));
        table.put("POST /api/v2/topology/rebuild",
                Row.noBody("topology-rebuild"));
        return table;
    }

    private static String normalised(String method, String uri) {
        String route = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        return method + " " + ID_SEGMENT.matcher(route).replaceAll("{id}");
    }

    private static List<String> controllerRoutes() throws Exception {
        List<String> routes = new ArrayList<>();
        for (String controller : CONTROLLERS) {
            String source = Files.readString(API.resolve(controller), StandardCharsets.UTF_8);
            assertFalse(source.contains("@RequestMapping"),
                    controller + " uses @RequestMapping; extend PathTopologyCliParityTest to parse it");
            Matcher mapping = MAPPING.matcher(source);
            int found = 0;
            while (mapping.find()) {
                routes.add(mapping.group(1).toUpperCase() + " " + PATH_VARIABLE.matcher(mapping.group(2)).replaceAll("{id}"));
                found++;
            }
            assertTrue(found > 0, "no request mappings found in " + controller);
        }
        return routes;
    }

    private static void assertRouteTableParity(List<String> routes, Map<String, ?> table) {
        List<String> missingCommands = routes.stream().filter(r -> !table.containsKey(r)).toList();
        assertTrue(missingCommands.isEmpty(),
                "controller routes with no PathTopologyCli command in this test's mapping table: " + missingCommands);
        List<String> missingRoutes = table.keySet().stream().filter(r -> !routes.contains(r)).toList();
        assertTrue(missingRoutes.isEmpty(),
                "mapping table rows whose route does not exist in any of the four controllers: " + missingRoutes);
    }

    /** What the local server saw: method + normalized route, raw query, body, CSRF, Cookie, Origin, Content-Type and ack. */
    private record Seen(
            String route,
            String query,
            String body,
            String csrf,
            String cookie,
            String origin,
            String contentType,
            String businessHoursAck) {}

    private interface Body {
        void run(NexusSessionCli cli, List<Seen> seen, int port) throws Exception;
    }

    private static void withServer(Path home, int status, String answer, Body body) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var headers = exchange.getRequestHeaders();
            seen.add(new Seen(
                    normalised(exchange.getRequestMethod(), exchange.getRequestURI().toString()),
                    exchange.getRequestURI().getRawQuery(),
                    sent,
                    headers.getFirst("X-CSRF-Token"),
                    headers.getFirst("Cookie"),
                    headers.getFirst("Origin"),
                    headers.getFirst("Content-Type"),
                    headers.getFirst(NexusSessionCli.BUSINESS_HOURS_ACK_HEADER)));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            new NexusSessionCli(home).saveSession(
                    new NexusSessionCli.Session("http://127.0.0.1:" + port, "opaque-cookie", "csrf-9"));
            body.run(new NexusSessionCli(home), seen, port);
        } finally {
            server.stop(0);
        }
    }

    private static Seen one(NexusSessionCli cli, List<Seen> seen, String... args) {
        seen.clear();
        assertEquals(0, cli.run(args), String.join(" ", args));
        assertEquals(1, seen.size(), String.join(" ", args));
        return seen.get(0);
    }

    @Test
    @DisplayName("every mapping of the four controllers has a CLI command that sends that method, route, body and CSRF header")
    void everyScreenRouteHasACliCommand(@TempDir Path home) throws Exception {
        Path csv = home.resolve("sample.csv");
        String csvBody = "source_ip,dest_ip,dest_port,proto\n192.0.2.10,198.51.100.20,443,tcp\n";
        Files.writeString(csv, csvBody, StandardCharsets.UTF_8);
        Map<String, Row> table = commands(csv);
        List<String> routes = controllerRoutes();
        assertEquals(12, routes.size(),
                "a controller's mapping count changed; extend PathTopologyCli and this table: " + routes);
        assertEquals(routes.size(), new TreeSet<>(routes).size(), "a route is mapped twice: " + routes);
        assertRouteTableParity(routes, table);

        // Prove the parity guard fails both when a controller gains an unmapped route and when the table has a stale row.
        List<String> withExtraRoute = new ArrayList<>(routes);
        withExtraRoute.add("GET /api/v2/topology/unmapped");
        AssertionError missingCmd = assertThrows(AssertionError.class, () -> assertRouteTableParity(withExtraRoute, table));
        assertTrue(missingCmd.getMessage().contains("GET /api/v2/topology/unmapped"));
        Map<String, Row> withStaleRow = new LinkedHashMap<>(table);
        withStaleRow.put("POST /api/v2/path-analysis/stale", Row.noBody("path-stale"));
        AssertionError staleRow = assertThrows(AssertionError.class, () -> assertRouteTableParity(routes, withStaleRow));
        assertTrue(staleRow.getMessage().contains("POST /api/v2/path-analysis/stale"));

        withServer(home, 200, "{\"ok\":true}", (cli, seen, port) -> {
            for (Map.Entry<String, Row> entry : table.entrySet()) {
                Row row = entry.getValue();
                Seen request = one(cli, seen, row.args());
                assertEquals(entry.getKey(), request.route(),
                        "the command for " + entry.getKey() + " must send exactly that method and route");
                assertEquals("ui2_session=opaque-cookie", request.cookie());
                if (entry.getKey().startsWith("GET ")) {
                    assertNull(request.csrf(), "GET must not require a CSRF header");
                    assertEquals("", request.body());
                } else {
                    assertEquals("csrf-9", request.csrf(), entry.getKey() + " must carry the session's CSRF token");
                    assertEquals("http://127.0.0.1:" + port, request.origin(), entry.getKey() + " must carry Origin");
                    if (row.multipart()) {
                        assertNotNull(request.contentType());
                        assertTrue(request.contentType().startsWith("multipart/form-data; boundary="), request.contentType());
                        assertTrue(request.body().contains("name=\"mapping\"\r\n\r\n" + MAPPING_JSON), request.body());
                        assertTrue(request.body().contains("name=\"file\"; filename=\"sample.csv\""), request.body());
                        assertTrue(request.body().contains(csvBody), request.body());
                    } else if (row.expectedJsonBody() != null) {
                        assertEquals("application/json", request.contentType());
                        assertEquals(row.expectedJsonBody(), request.body());
                    } else {
                        assertEquals("", request.body(), entry.getKey() + " takes no request body in the controller");
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("optional query parameters use '-' as not given, @file bodies pass through unchanged, and --business-hours-ack forwards")
    void queriesAndAtFileBodies(@TempDir Path home) throws Exception {
        Path queryFile = home.resolve("query.json");
        Path validateFile = home.resolve("validate.json");
        Path overrideFile = home.resolve("override.json");
        Path mappingFile = home.resolve("mapping.json");
        Path csvFile = home.resolve("flows.csv");
        Files.writeString(queryFile, PATH_QUERY_JSON, StandardCharsets.UTF_8);
        Files.writeString(validateFile, PATH_VALIDATE_JSON, StandardCharsets.UTF_8);
        Files.writeString(overrideFile, OVERRIDE_JSON, StandardCharsets.UTF_8);
        Files.writeString(mappingFile, MAPPING_JSON, StandardCharsets.UTF_8);
        Files.writeString(csvFile, "source_ip,dest_ip,dest_port,proto\n192.0.2.10,198.51.100.20,443,tcp\n", StandardCharsets.UTF_8);

        withServer(home, 200, "{\"ok\":true}", (cli, seen, port) -> {
            assertNull(one(cli, seen, "policy-offline-analyses", POLICY).query());
            assertNull(one(cli, seen, "policy-offline-analyses", POLICY, "-").query());
            assertEquals("page=3", one(cli, seen, "policy-offline-analyses", POLICY, "3").query());
            assertEquals("page=a%26b", one(cli, seen, "policy-offline-analyses", POLICY, "a&b").query());

            assertEquals(PATH_QUERY_JSON, one(cli, seen, "path-query", "@" + queryFile).body());
            assertEquals(PATH_VALIDATE_JSON, one(cli, seen, "path-validate", "@" + validateFile).body());
            assertEquals(OVERRIDE_JSON, one(cli, seen, "topology-override-add", "@" + overrideFile).body());

            Seen upload = one(cli, seen, "policy-offline-analysis-upload", POLICY, "@" + mappingFile, csvFile.toString());
            assertTrue(upload.body().contains("name=\"mapping\"\r\n\r\n" + MAPPING_JSON));
            assertTrue(upload.body().contains("name=\"file\"; filename=\"flows.csv\""));

            Seen acked = one(new NexusSessionCli(home), seen,
                    "--business-hours-ack", "path-validate", PATH_VALIDATE_JSON);
            assertEquals("true", acked.businessHoursAck());
            assertEquals("csrf-9", acked.csrf());
        });
    }

    @Test
    @DisplayName("usage errors exit 2 and send nothing to the server")
    void usageErrorsSendNothing(@TempDir Path home) throws Exception {
        Path missing = home.resolve("missing.json");
        List<String[]> bad = List.of(
                new String[] {"policy-offline-analyses"},
                new String[] {"policy-offline-analyses", "-"},
                new String[] {"policy-offline-analyses", "bad/id"},
                new String[] {"policy-offline-analyses", POLICY, "0", "extra"},
                new String[] {"policy-offline-analysis"},
                new String[] {"policy-offline-analysis", "-"},
                new String[] {"policy-offline-analysis", ANALYSIS, "extra"},
                new String[] {"policy-offline-analysis-upload", POLICY, MAPPING_JSON},
                new String[] {"policy-offline-analysis-upload", POLICY, MAPPING_JSON, missing.toString()},
                new String[] {"policy-offline-analysis-delete"},
                new String[] {"policy-offline-analysis-delete", ".."},
                new String[] {"policy-offline-analysis-delete", ANALYSIS, "extra"},
                new String[] {"path-query"},
                new String[] {"path-query", "@"},
                new String[] {"path-query", "@" + missing},
                new String[] {"path-query", PATH_QUERY_JSON, "extra"},
                new String[] {"path-validate"},
                new String[] {"path-validate", PATH_VALIDATE_JSON, "extra"},
                new String[] {"path-validation"},
                new String[] {"path-validation", "bad id"},
                new String[] {"path-validation", VALIDATION, "extra"},
                new String[] {"topology", "extra"},
                new String[] {"topology-overrides", "extra"},
                new String[] {"topology-override-add"},
                new String[] {"topology-override-add", OVERRIDE_JSON, "extra"},
                new String[] {"topology-override-revoke"},
                new String[] {"topology-override-revoke", OVERRIDE, "{}"},
                new String[] {"topology-rebuild", "{}"});
        PrintStream err = System.err;
        try {
            withServer(home, 200, "{}", (cli, seen, port) -> {
                for (String[] args : bad) {
                    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                    System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
                    int code = cli.run(args);
                    System.setErr(err);
                    assertEquals(2, code, String.join(" ", args));
                    assertTrue(stderr.toString(StandardCharsets.UTF_8).startsWith("usage: "), String.join(" ", args));
                }
                assertEquals(List.of(), seen, "a usage error must not reach the server");
            });
        } finally {
            System.setErr(err);
        }
    }

    @Test
    @DisplayName("the CLI prints the server's (masked) answer verbatim, never echoes uploaded CSV content, and preserves exit codes")
    void outputIsTheServersAnswerOnly(@TempDir Path home) throws Exception {
        String masked = "{\"verdict\":\"ALLOW\",\"hops\":[{\"node\":\"FW-TANGO-04\",\"egress\":\"192.0.2.1\"}]}";
        Path csv = home.resolve("sample.csv");
        Files.writeString(csv, "source_ip,dest_ip,dest_port,proto\n192.0.2.88,198.51.100.99,8443,tcp\n", StandardCharsets.UTF_8);
        PrintStream out = System.out;
        PrintStream err = System.err;
        try {
            withServer(home, 200, masked, (cli, seen, port) -> {
                for (String[] args : List.of(
                        new String[] {"topology"},
                        new String[] {"path-query", PATH_QUERY_JSON},
                        new String[] {"policy-offline-analysis-upload", POLICY, MAPPING_JSON, csv.toString()})) {
                    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                    System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
                    System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
                    int code = cli.run(args);
                    System.setOut(out);
                    System.setErr(err);
                    assertEquals(0, code, args[0]);
                    assertEquals(masked, stdout.toString(StandardCharsets.UTF_8).strip(), args[0]);
                    String combined = stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8);
                    assertFalse(combined.contains("192.0.2.88"), "uploaded CSV content must never be echoed");
                    assertFalse(combined.contains("198.51.100.99"), "uploaded CSV content must never be echoed");
                }
            });
            withServer(home, 403, "{\"code\":\"WRONG_ROLE\"}", (cli, seen, port) -> {
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
                int code = cli.run(new String[] {"topology-rebuild"});
                System.setErr(err);
                assertEquals(3, code);
            });
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    @Test
    @DisplayName("the usage text lists every command name, and none collides with an existing CLI command")
    void usageListsEveryCommandAndNoNameCollides(@TempDir Path home) throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            NexusSessionCli.printUsage();
        } finally {
            System.setOut(out);
        }
        String usage = stdout.toString(StandardCharsets.UTF_8);
        List<String> names = PathTopologyCli.names();
        Map<String, Row> table = commands(home.resolve("sample.csv"));
        assertEquals(table.size(), names.size());
        assertEquals(names.size(), new TreeSet<>(names).size(), "duplicate command name");
        List<String> routedByTable = table.values().stream().map(r -> r.args()[0]).toList();
        for (String name : names) {
            assertTrue(routedByTable.contains(name), name + " is not covered by the parity table");
            assertTrue(usage.contains("  " + name + " ") || usage.contains("  " + name + "\n"),
                    "the usage text does not list " + name);
        }
        Path sources = Path.of("src", "main", "java", "com", "securityexpert", "nexus", "ui2", "cli");
        for (String other : List.of("CliEntryPoint", "NexusSessionCli", "PolicyCli", "ZonesMatrixCli", "CutoverCli",
                "ScriptCli", "StaticRouteCli", "FailoverCli", "SupportBundleCli", "SanitizerCli")) {
            String source = Files.readString(sources.resolve(other + ".java"), StandardCharsets.UTF_8);
            for (String name : names) {
                assertFalse(source.contains("case \"" + name + "\""), name + " is already a command of " + other);
                assertFalse(source.contains("new Command(\"" + name + "\""), name + " is already a command of " + other);
            }
        }
    }
}
