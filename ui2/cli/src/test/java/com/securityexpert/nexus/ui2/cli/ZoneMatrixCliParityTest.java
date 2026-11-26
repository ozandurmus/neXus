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
 * CLI parity for the zone matrix screen (standing directive {@code cli_parity_for_every_ui_action}, backlog
 * {@code zone_matrix_delivery}): every request mapping of {@code ZoneMatrixController} has a {@code ZonesMatrixCli} command,
 * and the command really sends that method and route. The mapping table below is explicit; a route with no command fails
 * with its path, and a table row with no route fails too, so the two cannot drift.
 *
 * <p>The cli module does not depend on the service module, so the controller's mappings are read from its source file next
 * to this module. The controller has no base constant: its routes are literal paths.</p>
 */
class ZonesMatrixCliParityTest {

    private static final Path CONTROLLER = Path.of("..", "service", "src", "main", "java", "com", "securityexpert", "nexus", "ui2", "service", "api",
            "ZoneMatrixController.java");
    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\(\"(" + Pattern.quote(ZonesMatrixCli.BASE) + "[^\"]*)\"\\)");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern FINDING_ID = Pattern.compile("/findings/[0-9]+/");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[A-Za-z]+}");
    private static final String VERSION = "11111111-2222-4333-8444-555555555555";
    private static final String ZONE = "66666666-7777-4888-9999-aaaaaaaaaaaa";

    /** Controller route (path variables written {id}) -> the command line that must reach it. */
    private static Map<String, String[]> commands() {
        Map<String, String[]> table = new LinkedHashMap<>();
        table.put("GET /versions", new String[] {"zone-matrix-versions"});
        table.put("GET /versions/{id}", new String[] {"zone-matrix-version", VERSION});
        table.put("GET /draft", new String[] {"zone-matrix-draft"});
        table.put("POST /lookup", new String[] {"zone-matrix-lookup", "192.0.2.10"});
        table.put("POST /draft/zones", new String[] {"zone-matrix-zone-add", "{\"revision\":3,\"name\":\"ZONE-ALPHA\"}"});
        table.put("PUT /draft/zones/{id}", new String[] {"zone-matrix-zone-update", ZONE, "{\"revision\":3,\"name\":\"ZONE-BRAVO\"}"});
        table.put("POST /draft/zones/{id}/delete", new String[] {"zone-matrix-zone-delete", ZONE, "3"});
        table.put("POST /draft/discard", new String[] {"zone-matrix-discard", "3"});
        table.put("GET /template", new String[] {"zone-matrix-template"});
        table.put("POST /draft/cells", new String[] {"zone-matrix-cell-set", "{\"revision\":3,\"source\":\"A\",\"destination\":\"B\"}"});
        table.put("POST /draft/cells/clear", new String[] {"zone-matrix-cell-clear", "{\"revision\":3,\"source\":\"A\",\"destination\":\"B\"}"});
        table.put("POST /draft/default", new String[] {"zone-matrix-default-set", "{\"revision\":3,\"statement\":\"DENY\"}"});
        table.put("POST /draft/service-sets", new String[] {"zone-matrix-service-set", "{\"revision\":3,\"name\":\"WEB\",\"entries\":[\"tcp/443\"]}"});
        table.put("POST /draft/service-sets/delete", new String[] {"zone-matrix-service-set-delete", "{\"revision\":3,\"name\":\"WEB\"}"});
        table.put("POST /draft/template-import", new String[] {"zone-matrix-template-import", "3"});
        table.put("POST /draft/cells/confirm-reference", new String[] {"zone-matrix-confirm-reference", "{\"revision\":3,\"source\":\"A\",\"destination\":\"B\"}"});
        table.put("POST /draft/publish", new String[] {"zone-matrix-publish", "{\"revision\":3,\"content_sha256\":\"x\",\"rationale\":\"r\"}"});
        table.put("GET /policies", new String[] {"zone-matrix-policies"});
        table.put("POST /evaluate", new String[] {"zone-matrix-evaluate"});
        table.put("GET /summary", new String[] {"zone-matrix-summary"});
        table.put("POST /runs", new String[] {"zone-matrix-run", "POL-ALPHA"});
        table.put("GET /findings/{id}/witness", new String[] {"zone-matrix-finding-witness", "4711"});
        return table;
    }

    private static String normalised(String method, String uri) {
        String route = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        String path = UUID.matcher(route.substring(ZonesMatrixCli.BASE.length())).replaceAll("{id}");
        return method + " " + FINDING_ID.matcher(path).replaceAll("/findings/{id}/");
    }

    private static List<String> controllerRoutes() throws Exception {
        String source = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
        List<String> routes = new ArrayList<>();
        Matcher mapping = MAPPING.matcher(source);
        while (mapping.find()) {
            routes.add(mapping.group(1).toUpperCase() + " " + PATH_VARIABLE.matcher(mapping.group(2).substring(ZonesMatrixCli.BASE.length())).replaceAll("{id}"));
        }
        return routes;
    }

    /** What the local server saw: method + route, the raw query, the body. */
    private record Seen(String route, String query, String body) {}

    private interface Body {
        void run(NexusSessionCli cli, List<Seen> seen) throws Exception;
    }

    private static void withServer(Path home, String answer, Body body) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String uri = exchange.getRequestURI().toString();
            String body1 = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(normalised(exchange.getRequestMethod(), uri), exchange.getRequestURI().getRawQuery(), body1));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
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

    private static Seen one(NexusSessionCli cli, List<Seen> seen, String... args) {
        seen.clear();
        assertEquals(0, cli.run(args), String.join(" ", args));
        assertEquals(1, seen.size(), String.join(" ", args));
        return seen.get(0);
    }

    @Test
    @DisplayName("every route of the zone matrix controller has a CLI command that sends that method and route")
    void everyScreenRouteHasACliCommand(@TempDir Path home) throws Exception {
        Map<String, String[]> table = commands();
        List<String> routes = controllerRoutes();
        assertFalse(routes.isEmpty(), "no request mapping found in the controller source");
        assertEquals(routes.size(), new TreeSet<>(routes).size(), "a route is mapped twice: " + routes);
        for (String route : routes) {
            assertTrue(table.containsKey(route), "the controller route " + route + " has no ZonesMatrixCli command in this test's mapping table");
        }
        for (String row : table.keySet()) {
            assertTrue(routes.contains(row), "the mapping table names " + row + " but the controller has no such route");
        }
        withServer(home, "{\"ok\":true}", (cli, seen) -> {
            for (Map.Entry<String, String[]> row : table.entrySet()) {
                assertEquals(row.getKey(), one(cli, seen, row.getValue()).route(), "the command for " + row.getKey() + " must send exactly that route");
            }
        });
    }

    @Test
    @DisplayName("query strings and bodies are exactly what the controller reads, and JSON bodies are passed through unchanged")
    void queriesAndBodies(@TempDir Path home) throws Exception {
        Path file = home.resolve("zone.json");
        String json = "{\"revision\":4,\"name\":\"ZONE-ALPHA\",\"blocks\":[\"192.0.2.0/24\"]}";
        Files.writeString(file, json);
        withServer(home, "{\"ok\":true}", (cli, seen) -> {
            assertNull(one(cli, seen, "zone-matrix-versions").query());
            assertNull(one(cli, seen, "zone-matrix-version", VERSION).query());
            assertEquals("blocks=1", one(cli, seen, "zone-matrix-version", VERSION, "blocks").query());
            assertNull(one(cli, seen, "zone-matrix-draft").query());
            assertEquals("blocks=1", one(cli, seen, "zone-matrix-draft", "blocks").query());
            assertNull(one(cli, seen, "zone-matrix-summary").query());
            assertEquals("policy_id=POL-ALPHA", one(cli, seen, "zone-matrix-summary", "POL-ALPHA").query());
            assertEquals("outcome=VIOLATION&limit=20", one(cli, seen, "zone-matrix-summary", "-", "VIOLATION", "20").query());
            assertEquals("policy_id=POL-ALPHA&outcome=GAP&limit=5", one(cli, seen, "zone-matrix-summary", "POL-ALPHA", "GAP", "5").query());
            assertEquals("policy_id=a%26b%3Dc", one(cli, seen, "zone-matrix-summary", "a&b=c").query());

            assertEquals("{\"address\":\"192.0.2.10\"}", one(cli, seen, "zone-matrix-lookup", "192.0.2.10").body());
            assertEquals("{\"address\":\"192.0.2.10\",\"version_id\":\"" + VERSION + "\"}", one(cli, seen, "zone-matrix-lookup", "192.0.2.10", VERSION).body());
            assertEquals(json, one(cli, seen, "zone-matrix-zone-add", json).body());
            assertEquals(json, one(cli, seen, "zone-matrix-zone-add", "@" + file).body());
            assertEquals(json, one(cli, seen, "zone-matrix-zone-update", ZONE, json).body());
            assertEquals("{\"revision\":3}", one(cli, seen, "zone-matrix-zone-delete", ZONE, "3").body());
            assertEquals("{\"revision\":3}", one(cli, seen, "zone-matrix-discard", "3").body());
            assertEquals("{\"revision\":3}", one(cli, seen, "zone-matrix-template-import", "3").body());
            assertEquals("{\"revision\":3,\"replace\":true}", one(cli, seen, "zone-matrix-template-import", "3", "replace").body());
            assertEquals("{}", one(cli, seen, "zone-matrix-evaluate").body());
            assertEquals("{}", one(cli, seen, "zone-matrix-evaluate", "-", "-").body());
            assertEquals("{\"policy_id\":\"POL-ALPHA\",\"version_id\":\"" + VERSION + "\",\"include_witnesses\":true}",
                    one(cli, seen, "zone-matrix-evaluate", "POL-ALPHA", VERSION, "witnesses").body());
            assertEquals("{\"include_witnesses\":true}", one(cli, seen, "zone-matrix-evaluate", "-", "-", "witnesses").body());
            assertEquals(json, one(cli, seen, "zone-matrix-publish", json).body());
            assertEquals("{\"policy_id\":\"POL-ALPHA\"}", one(cli, seen, "zone-matrix-run", "POL-ALPHA").body());
            assertEquals("{\"policy_id\":\"POL-ALPHA\",\"version_id\":\"" + VERSION + "\"}",
                    one(cli, seen, "zone-matrix-run", "POL-ALPHA", "--version", VERSION).body());
            assertNull(one(cli, seen, "zone-matrix-run", "POL-ALPHA").query());
            // step 4b-2: the audited witness read of one stored finding is a GET with no query and no body.
            Seen witness = one(cli, seen, "zone-matrix-finding-witness", "4711");
            assertEquals("GET /findings/{id}/witness", witness.route());
            assertNull(witness.query());
            assertEquals("", witness.body());
        });
    }

    @Test
    @DisplayName("usage errors exit 2 and send nothing")
    void usageErrorsSendNothing(@TempDir Path home) throws Exception {
        List<String[]> bad = List.of(
                new String[] {"zone-matrix-version"},
                new String[] {"zone-matrix-version", "not-an-id"},
                new String[] {"zone-matrix-version", VERSION, "names"},
                new String[] {"zone-matrix-draft", "names"},
                new String[] {"zone-matrix-versions", "extra"},
                new String[] {"zone-matrix-lookup"},
                new String[] {"zone-matrix-lookup", "192.0.2.10", "not-an-id"},
                new String[] {"zone-matrix-zone-add"},
                new String[] {"zone-matrix-zone-update", ZONE},
                new String[] {"zone-matrix-zone-update", "not-an-id", "{}"},
                new String[] {"zone-matrix-zone-delete", ZONE},
                new String[] {"zone-matrix-zone-delete", ZONE, "x"},
                new String[] {"zone-matrix-discard"},
                new String[] {"zone-matrix-discard", "0"},
                new String[] {"zone-matrix-cell-set"},
                new String[] {"zone-matrix-cell-clear"},
                new String[] {"zone-matrix-default-set"},
                new String[] {"zone-matrix-service-set"},
                new String[] {"zone-matrix-service-set-delete"},
                new String[] {"zone-matrix-template-import"},
                new String[] {"zone-matrix-template-import", "3", "yes"},
                new String[] {"zone-matrix-confirm-reference"},
                new String[] {"zone-matrix-publish"},
                new String[] {"zone-matrix-evaluate", "-", "not-an-id"},
                new String[] {"zone-matrix-evaluate", "-", "-", "all"},
                new String[] {"zone-matrix-run"},
                new String[] {"zone-matrix-run", "POL-ALPHA", "--version"},
                new String[] {"zone-matrix-run", "POL-ALPHA", "--version", "not-an-id"},
                new String[] {"zone-matrix-run", "POL-ALPHA", VERSION},
                new String[] {"zone-matrix-run", "POL-ALPHA", "--versions", VERSION},
                new String[] {"zone-matrix-run", "POL-ALPHA", "--version", VERSION, "extra"},
                new String[] {"zone-matrix-finding-witness"},
                new String[] {"zone-matrix-finding-witness", "0"},
                new String[] {"zone-matrix-finding-witness", "007"},
                new String[] {"zone-matrix-finding-witness", "-1"},
                new String[] {"zone-matrix-finding-witness", "../summary"},
                new String[] {"zone-matrix-finding-witness", "12", "extra"});
        PrintStream err = System.err;
        try {
            withServer(home, "{}", (cli, seen) -> {
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
    @DisplayName("the CLI prints the server's (masked) answer verbatim and adds nothing")
    void outputIsTheServersAnswerOnly(@TempDir Path home) throws Exception {
        String masked = "{\"versions\":[{\"id\":\"v1\",\"zones\":[\"ZONE-ALPHA\"]}],\"can\":{\"edit\":false}}";
        PrintStream out = System.out;
        try {
            withServer(home, masked, (cli, seen) -> {
                for (String[] args : List.of(new String[] {"zone-matrix-versions"}, new String[] {"zone-matrix-summary", "-", "VIOLATION"},
                        new String[] {"zone-matrix-draft"}, new String[] {"zone-matrix-evaluate"}, new String[] {"zone-matrix-run", "POL-ALPHA"})) {
                    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                    System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
                    int code = cli.run(args);
                    System.setOut(out);
                    assertEquals(0, code, args[0]);
                    assertEquals(masked, stdout.toString(StandardCharsets.UTF_8).strip(), args[0]);
                }
            });
        } finally {
            System.setOut(out);
        }
    }
}
