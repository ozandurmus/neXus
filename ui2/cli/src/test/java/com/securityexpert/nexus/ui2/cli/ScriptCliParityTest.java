package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLI parity for Operations > Script Execution (standing directive {@code cli_parity_for_every_ui_action}, backlog
 * {@code script_execution_module}; {@code SCRIPT_EXECUTION_CONTRACT.md} sections 3.5 and 3.6): every request mapping of
 * {@code ScriptExecutionController} under {@code /api/v2/scripts} has an explicit {@code ScriptCli} command, and each
 * command really sends that method and route with CSRF and Origin headers on every non-GET request.
 *
 * <p>The cli module does not depend on the service module, so the controller's mappings are read directly from its
 * source file next to this module.</p>
 */
class ScriptCliParityTest {

    private static final Path CONTROLLER = Path.of("..", "service", "src", "main", "java", "com", "securityexpert",
            "nexus", "ui2", "service", "api", "ScriptExecutionController.java");
    private static final Path CLI_PACKAGE_DIR = Path.of("src", "main", "java", "com", "securityexpert", "nexus", "ui2",
            "cli");
    private static final Pattern MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping\\(\"(" + Pattern.quote(ScriptCli.BASE) + "[^\"]*)\"\\)");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[A-Za-z]+}");
    private static final String SCRIPT_ID = "11111111-2222-4333-8444-555555555555";
    private static final String RUN_ID = "66666666-7777-4888-9999-aaaaaaaaaaaa";

    private record CommandSpec(String expectedPath, String[] args) {
    }

    /** Controller route (path variables normalised to {id}) -> concrete expected path + CLI command line. */
    private static Map<String, CommandSpec> commands(Path scriptFile) {
        Map<String, CommandSpec> table = new LinkedHashMap<>();
        table.put("GET /api/v2/scripts",
                new CommandSpec("/api/v2/scripts", new String[] {"script-list"}));
        table.put("GET /api/v2/scripts/{id}",
                new CommandSpec("/api/v2/scripts/" + SCRIPT_ID, new String[] {"script-get", SCRIPT_ID}));
        table.put("POST /api/v2/scripts",
                new CommandSpec("/api/v2/scripts", new String[] {"script-add", scriptFile.toString()}));
        table.put("POST /api/v2/scripts/{id}/versions",
                new CommandSpec("/api/v2/scripts/" + SCRIPT_ID + "/versions",
                        new String[] {"script-version-add", SCRIPT_ID, scriptFile.toString()}));
        table.put("PATCH /api/v2/scripts/{id}",
                new CommandSpec("/api/v2/scripts/" + SCRIPT_ID,
                        new String[] {"script-set", SCRIPT_ID, "--mail-on-failure-only", "true"}));
        table.put("POST /api/v2/scripts/{id}/enable",
                new CommandSpec("/api/v2/scripts/" + SCRIPT_ID + "/enable", new String[] {"script-enable", SCRIPT_ID}));
        table.put("POST /api/v2/scripts/{id}/disable",
                new CommandSpec("/api/v2/scripts/" + SCRIPT_ID + "/disable", new String[] {"script-disable", SCRIPT_ID}));
        table.put("POST /api/v2/scripts/{id}/run",
                new CommandSpec("/api/v2/scripts/" + SCRIPT_ID + "/run", new String[] {"script-run", SCRIPT_ID}));
        table.put("GET /api/v2/scripts/{id}/runs",
                new CommandSpec("/api/v2/scripts/" + SCRIPT_ID + "/runs", new String[] {"script-runs", SCRIPT_ID}));
        table.put("GET /api/v2/scripts/runs/{id}",
                new CommandSpec("/api/v2/scripts/runs/" + RUN_ID, new String[] {"script-run-show", RUN_ID}));
        table.put("GET /api/v2/scripts/runs/{id}/output",
                new CommandSpec("/api/v2/scripts/runs/" + RUN_ID + "/output", new String[] {"script-output", RUN_ID}));
        return table;
    }

    private static String normalised(String method, String uri) {
        String route = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        return method + " " + UUID.matcher(route).replaceAll("{id}");
    }

    private static List<String> controllerRoutes() throws Exception {
        String source = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
        List<String> routes = new ArrayList<>();
        Matcher mapping = MAPPING.matcher(source);
        while (mapping.find()) {
            String method = mapping.group(1).toUpperCase(Locale.ROOT);
            String path = PATH_VARIABLE.matcher(mapping.group(2)).replaceAll("{id}");
            routes.add(method + " " + path);
        }
        return routes;
    }

    private record Seen(String method, String path, String route, String csrf, String origin, String body) {
    }

    private interface Body {
        void run(NexusSessionCli cli, List<Seen> seen, String expectedOrigin) throws Exception;
    }

    private static void withServer(Path home, String answer, Body body) throws Exception {
        List<Seen> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().toString();
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(
                    method,
                    path,
                    normalised(method, path),
                    exchange.getRequestHeaders().getFirst("X-CSRF-Token"),
                    exchange.getRequestHeaders().getFirst("Origin"),
                    reqBody));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            new NexusSessionCli(home).saveSession(new NexusSessionCli.Session(baseUrl, "opaque-cookie", "csrf-9"));
            body.run(new NexusSessionCli(home), seen, baseUrl);
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
    @DisplayName("every mapping of ScriptExecutionController has a ScriptCli command that sends that exact method and route")
    void everyControllerRouteHasACliCommand(@TempDir Path home) throws Exception {
        Path scriptFile = home.resolve("nightly_check.sh");
        Files.writeString(scriptFile, "#!/bin/bash\necho ok\n", StandardCharsets.UTF_8);
        Map<String, CommandSpec> table = commands(scriptFile);

        List<String> routes = controllerRoutes();
        assertEquals(11, routes.size(),
                "ScriptExecutionController mapping count changed; extend ScriptCli and this table: " + routes);
        assertEquals(routes.size(), new TreeSet<>(routes).size(), "a route is mapped twice: " + routes);
        for (String route : routes) {
            assertTrue(table.containsKey(route),
                    "the controller route " + route + " has no ScriptCli command in this test's mapping table");
        }
        for (String row : table.keySet()) {
            assertTrue(routes.contains(row),
                    "the mapping table names " + row + " but ScriptExecutionController has no such route");
        }

        withServer(home, "{\"ok\":true}", (cli, seen, expectedOrigin) -> {
            for (Map.Entry<String, CommandSpec> row : table.entrySet()) {
                Seen call = one(cli, seen, row.getValue().args());
                assertEquals(row.getKey(), call.route(),
                        "the command for " + row.getKey() + " must send that normalised route");
                assertEquals(row.getValue().expectedPath(), call.path(),
                        "the command for " + row.getKey() + " must send that exact path");
                if ("GET".equals(call.method())) {
                    assertNull(call.csrf(), "GET must not send X-CSRF-Token");
                } else {
                    assertEquals("csrf-9", call.csrf(), "X-CSRF-Token required on " + row.getKey());
                    assertEquals(expectedOrigin, call.origin(), "Origin required on " + row.getKey());
                }
            }
        });
    }

    @Test
    @DisplayName("script-run-show prints the server's masked run detail and notification summary verbatim")
    void scriptRunShowPrintsVerbatimResponse(@TempDir Path home) throws Exception {
        String runDetail = "{\"run_id\":\"" + RUN_ID + "\",\"script_id\":\"" + SCRIPT_ID
                + "\",\"state\":\"SUCCEEDED\",\"outcome\":\"SUCCESS\",\"output_tail\":\"marker=ok\\n\","
                + "\"notification\":{\"syslog\":\"SENT\",\"smtp\":\"SKIPPED_FAILURE_ONLY\"}}";
        PrintStream out = System.out;
        PrintStream err = System.err;
        try {
            withServer(home, runDetail, (cli, seen, expectedOrigin) -> {
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
                int code = cli.run(new String[] {"script-run-show", RUN_ID});
                System.setOut(out);
                System.setErr(err);

                assertEquals(0, code);
                assertEquals(1, seen.size());
                assertEquals("GET", seen.get(0).method());
                assertEquals("/api/v2/scripts/runs/" + RUN_ID, seen.get(0).path());
                assertEquals(runDetail, stdout.toString(StandardCharsets.UTF_8).strip());
                assertEquals("", stderr.toString(StandardCharsets.UTF_8));
            });
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    @Test
    @DisplayName("--mail-on-failure-only serialises notify_mail_on_failure_only on script-add and script-set")
    void mailOnFailureOnlyFlagOnAddAndSet(@TempDir Path home) throws Exception {
        Path scriptFile = home.resolve("nightly_check.sh");
        Files.writeString(scriptFile, "#!/bin/bash\necho ok\n", StandardCharsets.UTF_8);

        withServer(home, "{\"ok\":true}", (cli, seen, expectedOrigin) -> {
            Seen addDefault = one(cli, seen, "script-add", scriptFile.toString(), "--notify", "smtp");
            assertFalse(addDefault.body().contains("notify_mail_on_failure_only"), addDefault.body());

            Seen addTrue = one(cli, seen, "script-add", scriptFile.toString(),
                    "--notify", "smtp", "--mail-on-failure-only", "true");
            assertTrue(addTrue.body().contains("\"notify\":\"smtp\""), addTrue.body());
            assertTrue(addTrue.body().contains("\"notify_mail_on_failure_only\":true"), addTrue.body());
            assertEquals("csrf-9", addTrue.csrf());
            assertEquals(expectedOrigin, addTrue.origin());

            Seen addOn = one(cli, seen, "script-add", scriptFile.toString(),
                    "--mail-on-failure-only", "on");
            assertTrue(addOn.body().contains("\"notify_mail_on_failure_only\":true"), addOn.body());

            Seen addFalse = one(cli, seen, "script-add", scriptFile.toString(),
                    "--mail-on-failure-only", "false");
            assertTrue(addFalse.body().contains("\"notify_mail_on_failure_only\":false"), addFalse.body());

            Seen addOff = one(cli, seen, "script-add", scriptFile.toString(),
                    "--mail-on-failure-only", "off");
            assertTrue(addOff.body().contains("\"notify_mail_on_failure_only\":false"), addOff.body());

            Seen setStandaloneTrue = one(cli, seen, "script-set", SCRIPT_ID, "--mail-on-failure-only", "true");
            assertEquals("PATCH", setStandaloneTrue.method());
            assertEquals("/api/v2/scripts/" + SCRIPT_ID, setStandaloneTrue.path());
            assertEquals("{\"notify_mail_on_failure_only\":true}", setStandaloneTrue.body());
            assertEquals("csrf-9", setStandaloneTrue.csrf());
            assertEquals(expectedOrigin, setStandaloneTrue.origin());

            Seen setStandaloneOff = one(cli, seen, "script-set", SCRIPT_ID, "--mail-on-failure-only", "off");
            assertEquals("{\"notify_mail_on_failure_only\":false}", setStandaloneOff.body());

            Seen setCombinedOn = one(cli, seen, "script-set", SCRIPT_ID,
                    "--notify", "both", "--mail-on-failure-only", "on");
            assertEquals("{\"notify\":\"both\",\"notify_mail_on_failure_only\":true}", setCombinedOn.body());

            Seen setCombinedFalse = one(cli, seen, "script-set", SCRIPT_ID,
                    "--notify", "smtp", "--mail-on-failure-only", "false", "--run-as", "role:operator");
            assertEquals("{\"notify\":\"smtp\",\"notify_mail_on_failure_only\":false,\"run_as\":\"role:operator\"}",
                    setCombinedFalse.body());
        });
    }

    @Test
    @DisplayName("usage errors exit 2 and send zero HTTP requests")
    void usageErrorsExitTwoWithoutSendingRequests(@TempDir Path home) throws Exception {
        Path scriptFile = home.resolve("nightly_check.sh");
        Files.writeString(scriptFile, "#!/bin/bash\necho ok\n", StandardCharsets.UTF_8);

        List<String[]> bad = List.of(
                // Missing positional arguments or flag values
                new String[] {"script-get"},
                new String[] {"script-run-show"},
                new String[] {"script-runs"},
                new String[] {"script-output"},
                new String[] {"script-enable"},
                new String[] {"script-disable"},
                new String[] {"script-run"},
                new String[] {"script-add"},
                new String[] {"script-add", scriptFile.toString(), "--mail-on-failure-only"},
                new String[] {"script-set"},
                new String[] {"script-set", SCRIPT_ID},
                new String[] {"script-set", SCRIPT_ID, "--mail-on-failure-only"},
                new String[] {"script-version-add"},
                new String[] {"script-version-add", SCRIPT_ID},
                // Extra positional arguments
                new String[] {"script-run-show", RUN_ID, "extra"},
                new String[] {"script-get", SCRIPT_ID, "extra"},
                new String[] {"script-list", SCRIPT_ID, "extra"},
                new String[] {"script-runs", SCRIPT_ID, "extra"},
                new String[] {"script-output", RUN_ID, "extra"},
                new String[] {"script-enable", SCRIPT_ID, "extra"},
                new String[] {"script-disable", SCRIPT_ID, "extra"},
                new String[] {"script-run", SCRIPT_ID, "extra"},
                // Non-opaque IDs
                new String[] {"script-run-show", "bad/id"},
                new String[] {"script-run-show", "id with space"},
                new String[] {"script-get", "bad/id"},
                new String[] {"script-runs", "bad/id"},
                new String[] {"script-output", "bad/id"},
                new String[] {"script-enable", "bad/id"},
                new String[] {"script-disable", "bad/id"},
                new String[] {"script-run", "bad/id"},
                new String[] {"script-set", "bad/id", "--mail-on-failure-only", "true"},
                new String[] {"script-version-add", "bad/id", scriptFile.toString()},
                new String[] {"script-add", scriptFile.toString(), "--script-id", "bad/id"},
                // Invalid boolean values on --mail-on-failure-only
                new String[] {"script-add", scriptFile.toString(), "--mail-on-failure-only", "maybe"},
                new String[] {"script-add", scriptFile.toString(), "--mail-on-failure-only", "2"},
                new String[] {"script-set", SCRIPT_ID, "--mail-on-failure-only", "invalid"},
                new String[] {"script-set", SCRIPT_ID, "--mail-on-failure-only", "2"},
                // --mail-on-failure-only rejected when --script-id switches script-add into version-upload mode
                new String[] {"script-add", scriptFile.toString(), "--script-id", SCRIPT_ID,
                        "--mail-on-failure-only", "true"},
                new String[] {"script-add", scriptFile.toString(), "--mail-on-failure-only", "false",
                        "--script-id", SCRIPT_ID});

        PrintStream err = System.err;
        try {
            withServer(home, "{}", (cli, seen, expectedOrigin) -> {
                for (String[] args : bad) {
                    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                    System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
                    int code = cli.run(args);
                    System.setErr(err);
                    String errText = stderr.toString(StandardCharsets.UTF_8);
                    assertEquals(2, code, String.join(" ", args));
                    assertTrue(errText.startsWith("usage: "), "expected usage prefix for " + String.join(" ", args)
                            + ", got: " + errText);
                }
                assertEquals(List.of(), seen, "a usage error must not send any HTTP request");
            });
        } finally {
            System.setErr(err);
        }
    }

    @Test
    @DisplayName("NexusSessionCli.printUsage() lists every ScriptCli name and --mail-on-failure-only, and no name collides")
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

        List<String> names = ScriptCli.names();
        assertEquals(11, names.size(), "expected 11 ScriptCli command words");
        assertEquals(names.size(), new TreeSet<>(names).size(), "duplicate command name in ScriptCli.names()");
        assertTrue(usage.contains("--mail-on-failure-only <true|false|on|off>"),
                "usage output must document --mail-on-failure-only <true|false|on|off>");

        String scriptCliSource = Files.readString(CLI_PACKAGE_DIR.resolve("ScriptCli.java"), StandardCharsets.UTF_8);
        for (String name : names) {
            assertTrue(name.startsWith("script-"), name);
            assertTrue(usage.contains("  " + name + " ") || usage.contains("  " + name + "\n"),
                    "NexusSessionCli.printUsage() does not list " + name);
            assertTrue(scriptCliSource.contains("case \"" + name + "\""),
                    "ScriptCli.run switch does not handle " + name);
        }

        String sessionDispatch = Files.readString(CLI_PACKAGE_DIR.resolve("NexusSessionCli.java"),
                StandardCharsets.UTF_8);
        for (String name : names) {
            assertFalse(sessionDispatch.contains("case \"" + name + "\""),
                    name + " collides with a case in NexusSessionCli.java");
        }

        List<Path> siblings;
        try (Stream<Path> files = Files.list(CLI_PACKAGE_DIR)) {
            siblings = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Cli.java"))
                    .filter(p -> !p.getFileName().toString().equals("ScriptCli.java"))
                    .sorted()
                    .toList();
        }
        assertFalse(siblings.isEmpty(), "expected sibling *Cli.java files in " + CLI_PACKAGE_DIR);
        for (Path sibling : siblings) {
            String source = Files.readString(sibling, StandardCharsets.UTF_8);
            for (String name : names) {
                assertFalse(source.contains("case \"" + name + "\""),
                        name + " collides with a command in " + sibling.getFileName());
            }
        }
        for (String name : names) {
            assertFalse(PolicyCli.names().contains(name), name + " collides with a command in PolicyCli");
        }
    }
}
