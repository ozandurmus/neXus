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
 * CLI parity for the policy screens (standing directive {@code cli_parity_for_every_ui_action}, backlog
 * {@code asa_fortigate_policy_model}): every GET mapping of {@code PolicyController} has a {@code PolicyCli} command, and the
 * command really sends that method and route. The mapping table is explicit; a route with no command fails with its path, and
 * a table row with no route fails too, so the two cannot drift. The cli module does not depend on the service module, so the
 * controller's mappings are read from its source file next to this module.
 */
class PolicyCliParityTest {

    private static final Path CONTROLLER = Path.of("..", "service", "src", "main", "java", "com", "securityexpert", "nexus", "ui2", "service", "api",
            "PolicyController.java");
    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\(\"(" + Pattern.quote(PolicyCli.BASE) + "[^\"]*)\"\\)");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[A-Za-z]+}");
    private static final String POLICY = "11111111-2222-4333-8444-555555555555";
    private static final String DEVICE = "66666666-7777-4888-9999-aaaaaaaaaaaa";
    private static final String DOMAIN = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff";
    private static final String OBJECT = "01234567-89ab-4cde-8f01-23456789abcd";
    private static final String INSIGHT = "fedcba98-7654-4321-8fed-cba987654321";

    /** Controller route (path variables written {id}) -> the command line that must reach it. */
    private static Map<String, String[]> commands() {
        Map<String, String[]> table = new LinkedHashMap<>();
        table.put("GET /ha-pairs", new String[] {"policy-ha-pairs"});
        table.put("GET /hygiene-summary", new String[] {"policy-hygiene-summary"});
        table.put("GET /policies/{id}/hygiene", new String[] {"policy-hygiene", POLICY});
        table.put("GET /policies/{id}/insights", new String[] {"policy-insights", POLICY});
        table.put("GET /policies/{id}/insight", new String[] {"policy-insight", POLICY, INSIGHT});
        table.put("GET /policies/{id}/rules", new String[] {"policy-rule", POLICY, "RULE-1"});
        table.put("GET /policies/{id}/hygiene.csv", new String[] {"policy-hygiene-csv", POLICY});
        table.put("GET /tree", new String[] {"policy-tree"});
        table.put("GET /devices", new String[] {"policy-devices"});
        table.put("GET /devices/{id}", new String[] {"policy-device", DEVICE});
        table.put("GET /policies/{id}", new String[] {"policy-show", POLICY});
        table.put("GET /policies/{id}/history", new String[] {"policy-history", POLICY});
        table.put("GET /objects/{id}", new String[] {"policy-object", OBJECT, DEVICE});
        table.put("GET /domains/{id}/objects", new String[] {"policy-domain-objects", DOMAIN, "SRC-1"});
        table.put("GET /domains/{id}/object-usage", new String[] {"policy-domain-object-usage", DOMAIN, "SRC-1", OBJECT});
        table.put("GET /domains/{id}/objects/duplicates", new String[] {"policy-domain-duplicates", DOMAIN, "SRC-1"});
        table.put("GET /domains/{id}/installation", new String[] {"policy-domain-installation", DOMAIN, "SRC-1"});
        table.put("GET /domains/{id}/unused", new String[] {"policy-domain-unused", DOMAIN, "SRC-1"});
        table.put("GET /domains/{id}/gateways", new String[] {"policy-domain-gateways", DOMAIN, "SRC-1"});
        table.put("GET /domains/{id}/hits", new String[] {"policy-domain-hits", DOMAIN, "SRC-1"});
        return table;
    }

    private static String normalised(String method, String uri) {
        String route = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        return method + " " + UUID.matcher(route.substring(PolicyCli.BASE.length())).replaceAll("{id}");
    }

    private static List<String> controllerRoutes() throws Exception {
        String source = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
        List<String> routes = new ArrayList<>();
        Matcher mapping = MAPPING.matcher(source);
        while (mapping.find()) {
            routes.add(mapping.group(1).toUpperCase() + " " + PATH_VARIABLE.matcher(mapping.group(2).substring(PolicyCli.BASE.length())).replaceAll("{id}"));
        }
        return routes;
    }

    /** What the local server saw: method + route, the raw query. */
    private record Seen(String route, String query) {}

    private interface Body {
        void run(NexusSessionCli cli, List<Seen> seen) throws Exception;
    }

    private static void withServer(Path home, String answer, Body body) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(new Seen(normalised(exchange.getRequestMethod(), exchange.getRequestURI().toString()), exchange.getRequestURI().getRawQuery()));
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
    @DisplayName("every mapping of the policy controller has a CLI command that sends that method and route")
    void everyScreenRouteHasACliCommand(@TempDir Path home) throws Exception {
        Map<String, String[]> table = commands();
        List<String> routes = controllerRoutes();
        assertEquals(20, routes.size(), "the controller's mapping count changed; extend PolicyCli and this table: " + routes);
        assertEquals(routes.size(), new TreeSet<>(routes).size(), "a route is mapped twice: " + routes);
        for (String route : routes) {
            assertTrue(table.containsKey(route), "the controller route " + route + " has no PolicyCli command in this test's mapping table");
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
    @DisplayName("query strings are exactly the controller's parameter names; '-' means not given; values are URL-encoded")
    void queries(@TempDir Path home) throws Exception {
        withServer(home, "{\"ok\":true}", (cli, seen) -> {
            assertNull(one(cli, seen, "policy-ha-pairs").query());
            assertNull(one(cli, seen, "policy-hygiene-summary").query());
            assertNull(one(cli, seen, "policy-hygiene", POLICY).query());
            assertEquals("page=2&findingClass=SHADOWED&severity=HIGH&days=30", one(cli, seen, "policy-hygiene", POLICY, "2", "SHADOWED", "HIGH", "30").query());
            assertEquals("severity=HIGH", one(cli, seen, "policy-hygiene", POLICY, "-", "-", "HIGH").query());
            assertEquals("page=1&type=T&grade=G&days=7", one(cli, seen, "policy-insights", POLICY, "1", "T", "G", "7").query());
            assertEquals("insightId=" + INSIGHT, one(cli, seen, "policy-insight", POLICY, INSIGHT).query());
            assertEquals("insightId=" + INSIGHT + "&days=14", one(cli, seen, "policy-insight", POLICY, INSIGHT, "14").query());
            assertEquals("ruleId=a%26b%3Dc", one(cli, seen, "policy-rule", POLICY, "a&b=c").query());
            assertEquals("ruleId=R1&days=5", one(cli, seen, "policy-rule", POLICY, "R1", "5").query());
            assertEquals("findingClass=SHADOWED&days=9", one(cli, seen, "policy-hygiene-csv", POLICY, "SHADOWED", "-", "9").query());
            assertNull(one(cli, seen, "policy-tree").query());
            assertEquals("source=S1&container=C1&device=" + DEVICE, one(cli, seen, "policy-tree", "S1", "C1", DEVICE).query());
            assertEquals("container=C1", one(cli, seen, "policy-tree", "-", "C1").query());
            assertNull(one(cli, seen, "policy-devices").query());
            assertEquals("policy=" + POLICY + "&page=1&q=fw+one", one(cli, seen, "policy-device", DEVICE, POLICY, "1", "fw one").query());
            assertEquals("page=3&q=x&hitFilter=never&days=60&sort=name&order=desc",
                    one(cli, seen, "policy-show", POLICY, "3", "x", "never", "60", "name", "desc").query());
            assertEquals("rule=R1&page=2", one(cli, seen, "policy-history", POLICY, "R1", "2").query());
            assertEquals("device=" + DEVICE, one(cli, seen, "policy-object", OBJECT, DEVICE).query());
            assertEquals("policy=" + POLICY, one(cli, seen, "policy-object", OBJECT, "-", POLICY).query());
            assertEquals("source=SRC-1", one(cli, seen, "policy-domain-objects", DOMAIN, "SRC-1").query());
            assertEquals("source=SRC-1&page=1&q=a&type=host&hygiene=unused&sort=name&order=asc",
                    one(cli, seen, "policy-domain-objects", DOMAIN, "SRC-1", "1", "a", "host", "unused", "name", "asc").query());
            assertEquals("source=SRC-1&uid=" + OBJECT + "&page=2&sort=n&order=desc",
                    one(cli, seen, "policy-domain-object-usage", DOMAIN, "SRC-1", OBJECT, "2", "n", "desc").query());
            assertEquals("source=SRC-1&page=1&q=a", one(cli, seen, "policy-domain-duplicates", DOMAIN, "SRC-1", "1", "a").query());
            assertEquals("source=SRC-1&policy=" + POLICY + "&sort=s&order=asc",
                    one(cli, seen, "policy-domain-installation", DOMAIN, "SRC-1", "-", "-", POLICY, "s", "asc").query());
            assertEquals("source=SRC-1&page=4", one(cli, seen, "policy-domain-unused", DOMAIN, "SRC-1", "4").query());
            assertEquals("source=SRC-1&page=4", one(cli, seen, "policy-domain-gateways", DOMAIN, "SRC-1", "4").query());
            assertEquals("source=SRC-1&page=4", one(cli, seen, "policy-domain-hits", DOMAIN, "SRC-1", "4").query());
        });
    }

    @Test
    @DisplayName("usage errors exit 2 and send nothing")
    void usageErrorsSendNothing(@TempDir Path home) throws Exception {
        List<String[]> bad = List.of(
                new String[] {"policy-ha-pairs", "extra"},
                new String[] {"policy-hygiene"},
                new String[] {"policy-hygiene", "not-an-id"},
                new String[] {"policy-hygiene", POLICY, "1", "a", "b", "30", "extra"},
                new String[] {"policy-insight", POLICY},
                new String[] {"policy-insight", POLICY, "-"},
                new String[] {"policy-rule", POLICY},
                new String[] {"policy-device"},
                new String[] {"policy-device", "not-an-id"},
                new String[] {"policy-object", "not-an-id"},
                new String[] {"policy-domain-objects", DOMAIN},
                new String[] {"policy-domain-objects", DOMAIN, "-"},
                new String[] {"policy-domain-object-usage", DOMAIN, "SRC-1"},
                new String[] {"policy-domain-hits", "not-an-id", "SRC-1"},
                new String[] {"policy-domain-hits", DOMAIN, "SRC-1", "1", "extra"});
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
            for (String answer : List.of("{\"pairs\":[{\"name\":\"FW-TANGO-04\",\"state\":\"HA_POLICY_DIVERGENT\"}]}",
                    "rule,class,severity\nR1,SHADOWED,HIGH")) {
                withServer(home, answer, (cli, seen) -> {
                    for (String[] args : List.of(new String[] {"policy-ha-pairs"}, new String[] {"policy-hygiene-csv", POLICY})) {
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
    @DisplayName("the usage text lists every policy command, and none of the names is an existing command")
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
        List<String> names = PolicyCli.names();
        assertEquals(commands().size(), names.size());
        assertEquals(names.size(), new TreeSet<>(names).size(), "duplicate command name");
        for (String name : names) {
            assertTrue(name.startsWith("policy-"), name);
            assertFalse(List.of("policy", "policy-set", "policy-collect").contains(name), name + " collides with an existing command");
            assertTrue(usage.contains("  " + name + " ") || usage.contains("  " + name + "\n"), "the usage text does not list " + name);
        }
        // Every other command word of the usage text and of the source dispatch is not one of ours.
        String dispatch = Files.readString(Path.of("src", "main", "java", "com", "securityexpert", "nexus", "ui2", "cli", "NexusSessionCli.java"));
        for (String name : names) {
            assertFalse(dispatch.contains("case \"" + name + "\""), name + " is already a NexusSessionCli command");
        }
        for (String other : List.of("ZonesMatrixCli", "CutoverCli", "ScriptCli", "StaticRouteCli", "FailoverCli", "SupportBundleCli", "SanitizerCli")) {
            String source = Files.readString(Path.of("src", "main", "java", "com", "securityexpert", "nexus", "ui2", "cli", other + ".java"));
            for (String name : names) assertFalse(source.contains("case \"" + name + "\""), name + " is already a command of " + other);
        }
    }
}
