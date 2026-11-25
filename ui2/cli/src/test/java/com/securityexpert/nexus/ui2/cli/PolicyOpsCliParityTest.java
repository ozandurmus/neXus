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
 * CLI parity for the policy changes, collection, lookup and install-monitoring screens (standing directive
 * {@code cli_parity_for_every_ui_action}, backlog {@code asa_fortigate_policy_model}): every mapping of
 * {@code PolicyChangesController}, {@code PolicyCollectionController}, {@code PolicyLookupController} and
 * {@code PolicyInstallMonitoringController} has a named command, and the command really sends that method, route, query and
 * body. The mapping table is explicit; a route with no command fails with its path, and a table row with no route fails too, so
 * the two cannot drift. The cli module does not depend on the service module, so the controllers' mappings are read from their
 * source files next to this module. The collect route is served by the existing {@code policy-collect}.
 */
class PolicyOpsCliParityTest {

    private static final Path API = Path.of("..", "service", "src", "main", "java", "com", "securityexpert", "nexus", "ui2", "service", "api");
    private static final List<String> CONTROLLERS = List.of("PolicyChangesController.java", "PolicyCollectionController.java",
            "PolicyLookupController.java", "PolicyInstallMonitoringController.java");
    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\(\"([^\"]*)\"\\)");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[A-Za-z]+}");
    private static final String POLICY = "11111111-2222-4333-8444-555555555555";
    private static final String JOB = "66666666-7777-4888-9999-aaaaaaaaaaaa";
    private static final String SOURCE = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff";

    /** Controller route (path variables written {id}) -> the command line that must reach it. */
    private static Map<String, String[]> commands() {
        Map<String, String[]> table = new LinkedHashMap<>();
        table.put("GET /api/v2/policy/changes", new String[] {"policy-changes"});
        table.put("GET /api/v2/policy/changes/actor-candidates", new String[] {"policy-change-actors", "42"});
        table.put("GET /api/v2/policy/changes.csv", new String[] {"policy-changes-csv"});
        table.put("GET /api/v2/policy/sources", new String[] {"policy-sources"});
        table.put("GET /api/v2/policy/collections/{id}", new String[] {"policy-collection", JOB});
        table.put("POST /api/v2/policy/sources/{id}/collect", new String[] {"policy-collect", SOURCE});
        table.put("POST /api/v2/policy/policies/{id}/lookup", new String[] {"policy-lookup", POLICY, "192.0.2.10", "198.51.100.7", "tcp/443"});
        table.put("GET /api/v2/operations/policy-install/status", new String[] {"policy-install-status"});
        return table;
    }

    private static String normalised(String method, String uri) {
        String route = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        return method + " " + UUID.matcher(route).replaceAll("{id}");
    }

    private static List<String> controllerRoutes() throws Exception {
        List<String> routes = new ArrayList<>();
        for (String controller : CONTROLLERS) {
            Matcher mapping = MAPPING.matcher(Files.readString(API.resolve(controller), StandardCharsets.UTF_8));
            while (mapping.find()) routes.add(mapping.group(1).toUpperCase() + " " + PATH_VARIABLE.matcher(mapping.group(2)).replaceAll("{id}"));
        }
        return routes;
    }

    /** What the local server saw: method + route, the raw query, the body and the CSRF header. */
    private record Seen(String route, String query, String body, String csrf) {}

    private interface Body {
        void run(NexusSessionCli cli, List<Seen> seen) throws Exception;
    }

    private static void withServer(Path home, String answer, Body body) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(normalised(exchange.getRequestMethod(), exchange.getRequestURI().toString()), exchange.getRequestURI().getRawQuery(), sent,
                    exchange.getRequestHeaders().getFirst("X-CSRF-Token")));
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
    @DisplayName("every mapping of the four policy controllers has a CLI command that sends that method and route")
    void everyScreenRouteHasACliCommand(@TempDir Path home) throws Exception {
        Map<String, String[]> table = commands();
        List<String> routes = controllerRoutes();
        assertEquals(8, routes.size(), "a controller's mapping count changed; extend PolicyOpsCli and this table: " + routes);
        assertEquals(routes.size(), new TreeSet<>(routes).size(), "a route is mapped twice: " + routes);
        for (String route : routes) {
            assertTrue(table.containsKey(route), "the controller route " + route + " has no command in this test's mapping table");
        }
        for (String row : table.keySet()) {
            assertTrue(routes.contains(row), "the mapping table names " + row + " but the controllers have no such route");
        }
        withServer(home, "{\"ok\":true}", (cli, seen) -> {
            for (Map.Entry<String, String[]> row : table.entrySet()) {
                Seen request = one(cli, seen, row.getValue());
                assertEquals(row.getKey(), request.route(), "the command for " + row.getKey() + " must send exactly that method and route");
                if (!row.getKey().startsWith("GET ")) assertEquals("csrf-9", request.csrf(), row.getKey() + " must carry the session's CSRF token");
            }
        });
    }

    @Test
    @DisplayName("query strings are exactly the controllers' parameter names; '-' means not given; values are URL-encoded")
    void queries(@TempDir Path home) throws Exception {
        String all = "from=2026-11-01&to=2026-11-25&policy=" + POLICY + "&device=FW-TANGO-04&actor=ADM-1&changeType=MODIFIED&field=action&q=a+b&cursor=C1&limit=50&includeInitial=true";
        withServer(home, "{\"ok\":true}", (cli, seen) -> {
            assertNull(one(cli, seen, "policy-changes").query());
            assertEquals(all, one(cli, seen, "policy-changes", "2026-11-01", "2026-11-25", POLICY, "FW-TANGO-04", "ADM-1", "MODIFIED", "action", "a b", "C1", "50", "true").query());
            assertEquals(all, one(cli, seen, "policy-changes-csv", "2026-11-01", "2026-11-25", POLICY, "FW-TANGO-04", "ADM-1", "MODIFIED", "action", "a b", "C1", "50", "true").query());
            assertEquals("actor=a%26b%3Dc&limit=10", one(cli, seen, "policy-changes", "-", "-", "-", "-", "a&b=c", "-", "-", "-", "-", "10").query());
            assertEquals("revision=42", one(cli, seen, "policy-change-actors", "42").query());
            assertNull(one(cli, seen, "policy-sources").query());
            assertNull(one(cli, seen, "policy-collection", JOB).query());
            assertNull(one(cli, seen, "policy-install-status").query());
            assertEquals("vendor=PALO_ALTO&state=FAILED&q=fw+one&page=2", one(cli, seen, "policy-install-status", "PALO_ALTO", "FAILED", "fw one", "2").query());
            assertEquals("state=FAILED", one(cli, seen, "policy-install-status", "-", "FAILED").query());
            assertNull(one(cli, seen, "policy-lookup", POLICY, "192.0.2.10", "198.51.100.7", "any").query());
        });
    }

    @Test
    @DisplayName("lookup and collect send the screen's JSON body; an argument given as '-' is left out")
    void bodies(@TempDir Path home) throws Exception {
        withServer(home, "{\"ok\":true}", (cli, seen) -> {
            assertEquals("{\"source\":\"192.0.2.10\",\"destination\":\"198.51.100.0/24\",\"service\":\"tcp/443\"}",
                    one(cli, seen, "policy-lookup", POLICY, "192.0.2.10", "198.51.100.0/24", "tcp/443").body());
            assertEquals("{\"source\":\"192.0.2.10\",\"destination\":\"198.51.100.7\",\"service\":\"udp/53\",\"sourceZone\":\"inside\","
                            + "\"destinationZone\":\"outside\",\"target\":\"FW-TANGO-04\",\"context\":\"vsys1\"}",
                    one(cli, seen, "policy-lookup", POLICY, "192.0.2.10", "198.51.100.7", "udp/53", "inside", "outside", "FW-TANGO-04", "vsys1").body());
            assertEquals("{\"source\":\"192.0.2.10\",\"destination\":\"198.51.100.7\",\"service\":\"any\",\"target\":\"FW-TANGO-04\"}",
                    one(cli, seen, "policy-lookup", POLICY, "192.0.2.10", "198.51.100.7", "any", "-", "-", "FW-TANGO-04").body());
            assertEquals("{\"mode\":\"CHANGED_ONLY\"}", one(cli, seen, "policy-collect", SOURCE).body());
            assertEquals("{\"mode\":\"FULL\",\"domainRef\":\"DOM-1\"}", one(cli, seen, "policy-collect", SOURCE, "FULL", "DOM-1").body());
        });
    }

    @Test
    @DisplayName("usage errors exit 2 and send nothing")
    void usageErrorsSendNothing(@TempDir Path home) throws Exception {
        List<String[]> bad = List.of(
                new String[] {"policy-changes", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"},
                new String[] {"policy-change-actors"},
                new String[] {"policy-change-actors", "-"},
                new String[] {"policy-change-actors", "abc"},
                new String[] {"policy-change-actors", "1", "2"},
                new String[] {"policy-changes-csv", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"},
                new String[] {"policy-sources", "extra"},
                new String[] {"policy-collection"},
                new String[] {"policy-collection", "not-an-id"},
                new String[] {"policy-collection", JOB, "extra"},
                new String[] {"policy-lookup"},
                new String[] {"policy-lookup", POLICY},
                new String[] {"policy-lookup", POLICY, "192.0.2.10", "198.51.100.7"},
                new String[] {"policy-lookup", POLICY, "192.0.2.10", "-", "any"},
                new String[] {"policy-lookup", "not-an-id", "192.0.2.10", "198.51.100.7", "any"},
                new String[] {"policy-lookup", POLICY, "a", "b", "any", "1", "2", "3", "4", "5"},
                new String[] {"policy-install-status", "1", "2", "3", "4", "5"});
        PrintStream err = System.err;
        try {
            withServer(home, "{}", (cli, seen) -> {
                for (String[] args : bad) {
                    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                    System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
                    int code = cli.run(args);
                    System.setErr(err);
                    assertEquals(2, code, String.join(" ", args));
                    assertTrue(stderr.toString(StandardCharsets.UTF_8).startsWith("usage: ") || stderr.toString(StandardCharsets.UTF_8).contains("opaque"), String.join(" ", args));
                }
                assertEquals(List.of(), seen, "a usage error must not reach the server");
            });
        } finally {
            System.setErr(err);
        }
    }

    @Test
    @DisplayName("the CLI prints the server's answer verbatim, JSON or the CSV body, and adds nothing")
    void outputIsTheServersAnswerOnly(@TempDir Path home) throws Exception {
        PrintStream out = System.out;
        try {
            for (String answer : List.of("{\"changes\":[{\"policy\":\"FW-TANGO-04\",\"actor\":\"AMBIGUOUS\"}]}", "time,policy,actor\n2026-11-25,POL-1,ADM-1")) {
                withServer(home, answer, (cli, seen) -> {
                    for (String[] args : List.of(new String[] {"policy-changes"}, new String[] {"policy-changes-csv"})) {
                        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
                        int code = cli.run(args);
                        System.setOut(out);
                        assertEquals(0, code, args[0]);
                        assertEquals(answer, stdout.toString(StandardCharsets.UTF_8).strip(), args[0]);
                    }
                });
            }
        } finally {
            System.setOut(out);
        }
    }

    @Test
    @DisplayName("the usage text lists every command, and none of the new names is an existing command")
    void usageListsEveryCommandAndNoNameCollides() throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            NexusSessionCli.printUsage();
        } finally {
            System.setOut(out);
        }
        String usage = stdout.toString(StandardCharsets.UTF_8);
        List<String> names = PolicyOpsCli.names();
        assertEquals(7, names.size());
        assertEquals(names.size(), new TreeSet<>(names).size(), "duplicate command name");
        List<String> routedByTheTable = new ArrayList<>();
        for (String[] line : commands().values()) routedByTheTable.add(line[0]);
        for (String name : names) {
            assertTrue(routedByTheTable.contains(name), name + " is not reached by the mapping table");
            assertTrue(usage.contains("  " + name + " ") || usage.contains("  " + name + "\n"), "the usage text does not list " + name);
        }
        assertTrue(usage.contains("  policy-collect "), "the usage text does not list policy-collect");
        Path sources = Path.of("src", "main", "java", "com", "securityexpert", "nexus", "ui2", "cli");
        for (String other : List.of("NexusSessionCli", "PolicyCli", "ZonesMatrixCli", "CutoverCli", "ScriptCli", "StaticRouteCli", "FailoverCli",
                "SupportBundleCli", "SanitizerCli")) {
            String source = Files.readString(sources.resolve(other + ".java"));
            for (String name : names) {
                assertFalse(source.contains("case \"" + name + "\""), name + " is already a command of " + other);
                assertFalse(source.contains("new Command(\"" + name + "\""), name + " is already a command of " + other);
            }
        }
    }
}
