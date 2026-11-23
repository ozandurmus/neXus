package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.*;

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

import com.sun.net.httpserver.HttpServer;

/**
 * Acceptance criteria of the ACI segmentation design (section 11, step 1; backlog {@code aci_segmentation_step1}):
 *
 * <ul>
 * <li>ACI-16: every request mapping of {@code CutoverAssessmentController} has a {@code CutoverCli} command, and the command
 *     really sends that method and route. The mapping table below is explicit; a route with no command fails with its
 *     path, and a table row with no route fails too, so the two cannot drift.</li>
 * <li>ACI-6 (CLI output): the CLI prints what the server answered, byte for byte, and adds nothing of the uploaded files; the
 *     server's aiview projection is therefore the CLI's output.</li>
 * </ul>
 *
 * <p>The cli module does not depend on the service module (contract section 2), so the controller's mappings are read from its
 * source file next to this module, not by reflection: the annotations are the contract and need no class loading.</p>
 */
class CutoverCliParityTest {

    private static final Path CONTROLLER = Path.of("..", "service", "src", "main", "java", "com", "securityexpert", "nexus", "ui2", "service", "api",
            "CutoverAssessmentController.java");
    private static final String SEGMENT = "11111111-2222-4333-8444-555555555555";
    private static final String ASSESSMENT = "66666666-7777-4888-9999-aaaaaaaaaaaa";
    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\((?:[^)]*?path\\s*=\\s*)?BASE\\s*\\+\\s*\"([^\"]*)\"");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** Controller route -> the CLI command line that must reach it. Ids are filled with {@link #SEGMENT} / {@link #ASSESSMENT}. */
    private static Map<String, String[]> commands(Path spec, Path file) {
        Map<String, String[]> table = new LinkedHashMap<>();
        table.put("GET /segments", new String[] {"cutover-segments"});
        table.put("POST /segments", new String[] {"cutover-segment-create", "SEG-ALPHA", "192.0.2.0/24", "198.51.100.0/24"});
        table.put("GET /segments/{id}", new String[] {"cutover-segment", SEGMENT});
        table.put("PUT /segments/{id}", new String[] {"cutover-segment-update", SEGMENT, "2", "SEG-ALPHA", "192.0.2.0/24", "198.51.100.0/24"});
        table.put("DELETE /segments/{id}", new String[] {"cutover-segment-delete", SEGMENT});
        table.put("POST /segments/{id}/assessments", new String[] {"cutover-assess", SEGMENT, spec.toString(), file.toString()});
        table.put("GET /assessments/{id}", new String[] {"cutover-assessment", ASSESSMENT});
        table.put("GET /assessments/{id}/flows", new String[] {"cutover-flows", ASSESSMENT});
        table.put("DELETE /assessments/{id}", new String[] {"cutover-assessment-delete", ASSESSMENT});
        return table;
    }

    private static String normalised(String method, String uri) {
        String route = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        return method + " " + UUID.matcher(route.substring(CutoverCli.BASE.length())).replaceAll("{id}");
    }

    private static List<String> controllerRoutes() throws Exception {
        String source = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
        Matcher base = Pattern.compile("static final String BASE = \"([^\"]*)\";").matcher(source);
        assertTrue(base.find(), "controller BASE constant");
        assertEquals(CutoverCli.BASE, base.group(1), "the CLI and the controller share one base route");
        List<String> routes = new ArrayList<>();
        Matcher mapping = MAPPING.matcher(source);
        while (mapping.find()) routes.add(mapping.group(1).toUpperCase() + " " + mapping.group(2));
        return routes;
    }

    private interface Body {
        void run(HttpServer server, List<String> seen, Path home) throws Exception;
    }

    private static void withServer(Path home, String answer, Body body) throws Exception {
        List<String> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(normalised(exchange.getRequestMethod(), exchange.getRequestURI().toString()));
            exchange.getRequestBody().readAllBytes();
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            new NexusSessionCli(home).saveSession(new NexusSessionCli.Session("http://127.0.0.1:" + server.getAddress().getPort(), "opaque-cookie", "csrf-9"));
            body.run(server, seen, home);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("ACI-16 every route of the cutover controller has a CLI command that sends that method and route")
    void aci16EveryScreenActionHasACliEquivalent(@TempDir Path home) throws Exception {
        Path spec = home.resolve("spec.json");
        Path file = home.resolve("first.csv");
        Files.writeString(spec, "{\"targetOverride\":{\"deviceId\":\"d1\"},\"mappings\":[{}]}");
        Files.writeString(file, "src,dst,port,proto\n192.0.2.8,198.51.100.8,443,tcp\n");
        Map<String, String[]> table = commands(spec, file);

        List<String> routes = controllerRoutes();
        assertFalse(routes.isEmpty(), "no request mapping found in the controller source");
        assertEquals(routes.size(), new TreeSet<>(routes).size(), "a route is mapped twice: " + routes);
        for (String route : routes) {
            assertTrue(table.containsKey(route), "ACI-16: the controller route " + route + " has no CutoverCli command in this test's mapping table");
        }
        for (String row : table.keySet()) {
            assertTrue(routes.contains(row), "the mapping table names " + row + " but the controller has no such route");
        }

        withServer(home, "{\"ok\":true}", (server, seen, dir) -> {
            NexusSessionCli cli = new NexusSessionCli(dir);
            for (Map.Entry<String, String[]> row : table.entrySet()) {
                seen.clear();
                assertEquals(0, cli.run(row.getValue()), row.getKey());
                assertEquals(List.of(row.getKey()), seen, "the command for " + row.getKey() + " must send exactly that route");
            }
        });
    }

    @Test
    @DisplayName("ACI-6 the CLI prints the server's (masked) answer verbatim and never echoes uploaded file content")
    void aci6CliOutputIsTheServersAnswerOnly(@TempDir Path home) throws Exception {
        String masked = "{\"items\":[{\"flowSource\":\"203.0.113.77\",\"decision\":\"WOULD_DROP\"}],\"total\":1}";
        Path spec = home.resolve("spec.json");
        Path file = home.resolve("first.csv");
        Files.writeString(spec, "{\"targetOverride\":{\"deviceId\":\"d1\"},\"mappings\":[{}]}");
        Files.writeString(file, "src,dst,port,proto\n192.0.2.8,198.51.100.8,443,tcp\n");
        PrintStream out = System.out;
        PrintStream err = System.err;
        try {
            withServer(home, masked, (server, seen, dir) -> {
                NexusSessionCli cli = new NexusSessionCli(dir);
                for (String[] args : List.of(new String[] {"cutover-segments"}, new String[] {"cutover-segment", SEGMENT},
                        new String[] {"cutover-assessment", ASSESSMENT}, new String[] {"cutover-flows", ASSESSMENT, "FLOW", "WOULD_DROP", "0"},
                        new String[] {"cutover-assess", SEGMENT, spec.toString(), file.toString()})) {
                    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                    System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
                    System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
                    int code = cli.run(args);
                    System.setOut(out);
                    System.setErr(err);
                    assertEquals(0, code, args[0]);
                    assertEquals(masked, stdout.toString(StandardCharsets.UTF_8).strip(), args[0] + " prints the server answer only");
                    String all = stdout + stderr.toString(StandardCharsets.UTF_8);
                    for (String raw : List.of("192.0.2.8", "198.51.100.8", "src,dst,port,proto")) {
                        assertFalse(all.contains(raw), args[0] + " must not echo " + raw);
                    }
                }
            });
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }
}
