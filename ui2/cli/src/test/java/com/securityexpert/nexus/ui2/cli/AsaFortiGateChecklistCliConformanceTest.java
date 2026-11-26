package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins every CLI form in the ASA and FortiGate policy real-environment validation checklist
 * ({@code docs/design/ASA_FORTIGATE_POLICY_REAL_ENV_VALIDATION_CHECKLIST_2026_11_26.md}, DRAFT) to
 * {@link NexusSessionCli}, {@link PolicyCli}, {@link PolicyOpsCli} and {@link PathTopologyCli}: every {@code aiv} /
 * {@code nexus-cli} command the document names is extracted, its placeholders and shell variables are replaced with
 * synthetic values, and it is run against a local stub server (no PostgreSQL, no network). A command the CLI does not
 * dispatch, an unknown flag or extra positional argument, or a request whose verb, route, query string or JSON body
 * differs from what the step names fails here.
 *
 * <p>The test also pins the document's own promises: the status line, that the item stays {@code AUTOMATED_VALIDATED},
 * the four verdict tokens, twenty sample rows per vendor, that no device command is named, and that no address other
 * than the documentation ranges appears.</p>
 *
 * <p>Automated validation only: it proves the checklist's CLI forms parse and call the documented routes. It makes no
 * real-environment claim.</p>
 */
class AsaFortiGateChecklistCliConformanceTest {

    private static final String CHECKLIST = "docs/design/ASA_FORTIGATE_POLICY_REAL_ENV_VALIDATION_CHECKLIST_2026_11_26.md";
    private static final String STATUS_LINE =
            "DRAFT -- checklist for the PO's real-environment run; not a contract; decides nothing.";
    private static final String SYNTHETIC_ID = "11111111-2222-4333-8444-555555555555";
    private static final int MIN_COMMANDS = 25;

    private static final Pattern COMMAND_START = Pattern.compile("(?<![\\w$./-])(aiv|nexus-cli|overwatch-cli)(?=\\s)");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([^<>\\n]{1,40})>");
    private static final Pattern SHELL_VARIABLE = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?");
    private static final Pattern INLINE_SPAN = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w.])");
    private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"([A-Za-z]+)\":\"([^\"]*)\"");

    /** How a command's request is checked: verb, route, ordered query names, and (for POST bodies) the body fields. */
    private record RouteSpec(String method, Pattern path, boolean hasPathId, String[] queryNames, String[] bodyNames, boolean rawJsonBody) {
        static RouteSpec get(String pathRegex, boolean hasPathId, String... queryNames) {
            return new RouteSpec("GET", Pattern.compile(pathRegex), hasPathId, queryNames, new String[0], false);
        }

        static RouteSpec post(String pathRegex, boolean hasPathId, String... bodyNames) {
            return new RouteSpec("POST", Pattern.compile(pathRegex), hasPathId, new String[0], bodyNames, false);
        }

        static RouteSpec postRawJson(String pathRegex) {
            return new RouteSpec("POST", Pattern.compile(pathRegex), false, new String[0], new String[0], true);
        }
    }

    private record Seen(String method, String path, String rawQuery, String body) {
    }

    /** Command name to its exact HTTP verb, path pattern and positional parameter order from the CLI sources. */
    private static final Map<String, RouteSpec> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("session", RouteSpec.get("/session/status", false));
        EXPECTED.put("policy-devices", RouteSpec.get("/api/v2/policy/devices", false));
        EXPECTED.put("policy-sources", RouteSpec.get("/api/v2/policy/sources", false));
        EXPECTED.put("policy-tree", RouteSpec.get("/api/v2/policy/tree", false, "source", "container", "device"));
        EXPECTED.put("policy-ha-pairs", RouteSpec.get("/api/v2/policy/ha-pairs", false));
        EXPECTED.put("policy-hygiene-summary", RouteSpec.get("/api/v2/policy/hygiene-summary", false));
        EXPECTED.put("policy-hygiene", RouteSpec.get("/api/v2/policy/policies/" + SYNTHETIC_ID + "/hygiene", true,
                "page", "findingClass", "severity", "days"));
        EXPECTED.put("policy-show", RouteSpec.get("/api/v2/policy/policies/" + SYNTHETIC_ID, true,
                "page", "q", "hitFilter", "days", "sort", "order"));
        EXPECTED.put("policy-domain-objects", RouteSpec.get("/api/v2/policy/domains/" + SYNTHETIC_ID + "/objects", true,
                "source", "page", "q", "type", "hygiene", "sort", "order"));
        EXPECTED.put("policy-lookup", RouteSpec.post("/api/v2/policy/policies/" + SYNTHETIC_ID + "/lookup", true,
                "source", "destination", "service", "sourceZone", "destinationZone", "target", "context"));
        EXPECTED.put("path-query", RouteSpec.postRawJson("/api/v2/path-analysis/query"));
        EXPECTED.put("topology", RouteSpec.get("/api/v2/topology", false));
        EXPECTED.put("api", RouteSpec.get("/api/v2/search", false));
    }

    @Test
    void everyChecklistCommandDispatchesAndSendsTheExactRouteQueryAndBody(@TempDir Path work) throws Exception {
        String markdown = readChecklist();
        int checked = runConformance(markdown, work);
        assertTrue(checked >= MIN_COMMANDS,
                "the extractor found only " + checked + " aiv/nexus-cli commands; expected at least " + MIN_COMMANDS
                        + " (did the checklist change shape?)");
        System.out.println("AsaFortiGateChecklistCliConformanceTest: " + checked + " checklist commands checked");
    }

    @Test
    void checklistCoversEveryRequiredStepAndKeepsItsPromises(@TempDir Path work) throws Exception {
        String markdown = readChecklist();
        String flat = markdown.replaceAll("\\s+", " ");

        Set<String> usedCommands = new TreeSet<>();
        for (var command : extractCommands(markdown, work, "http://127.0.0.1:1")) {
            usedCommands.add(command.name());
        }
        Set<String> required = Set.of("login", "logout", "session", "policy-devices", "policy-sources", "policy-lookup",
                "policy-hygiene", "policy-hygiene-summary", "policy-ha-pairs", "policy-show", "policy-tree",
                "policy-domain-objects", "path-query", "topology", "api");
        Set<String> missingCommands = new LinkedHashSet<>(required);
        missingCommands.removeAll(usedCommands);
        assertTrue(missingCommands.isEmpty(), "checklist is missing required CLI commands: " + missingCommands);

        // The two vendors are looked up with the same command; both vendors have their own forms and loops.
        assertTrue(markdown.contains("aiv policy-lookup \"$ASA_POLICY_ID\""), "no ASA lookup form");
        assertTrue(markdown.contains("aiv policy-lookup \"$FGT_POLICY_ID\""), "no FortiGate lookup form");
        assertTrue(markdown.contains("aiv policy-hygiene \"$ASA_POLICY_ID\" 0 unused - 90"), "no ASA unused-class hygiene form");
        assertTrue(markdown.contains("aiv policy-hygiene \"$FGT_POLICY_ID\" 0 - - 90"), "no FortiGate hygiene form");

        // Status line, item status, and the draft proposal is pending, never approval.
        assertTrue(flat.contains("**Status: " + STATUS_LINE + "**"), "the status line changed or is missing");
        assertTrue(markdown.contains("`AUTOMATED_VALIDATED`"), "the item status must be named");
        assertTrue(flat.contains("stays `AUTOMATED_VALIDATED` until the Product Owner reports the completed run"),
                "the document must say the item stays AUTOMATED_VALIDATED until the PO reports the run");
        assertTrue(markdown.contains("FORTIGATE_POLICY_LOOKUP_VALIDATION_READ_PROPOSAL_2026_11_23.md"),
                "the FortiOS read proposal must be named as pending");
        assertTrue(markdown.contains("**pending**"), "the proposal must be called pending");
        assertTrue(markdown.contains("DRAFT -- DO NOT FREEZE"), "the proposal's own status must be quoted");

        // The four verdict tokens, the open decision about the units, the closed reason vocabulary.
        for (String token : List.of("MATCH", "MISMATCH", "UNKNOWN_BOTH", "DEVICE_ANSWER_UNAVAILABLE")) {
            assertTrue(markdown.contains("`" + token + "`"), "verdict token missing: " + token);
        }
        assertTrue(flat.contains("A product `UNKNOWN` is never counted as a `MATCH`"), "UNKNOWN-is-not-MATCH rule missing");
        assertTrue(flat.contains("the PO decides which units; this checklist does not"), "the PO owns the sample units");
        for (String reason : List.of("FGT_VIP_PRIORITY_UNPROVEN", "FGT_MATCH_VIP_UNPROVEN", "A0-FG-11", "A0-FG-19")) {
            assertTrue(markdown.contains(reason), "missing: " + reason);
        }

        // Twenty sample rows per vendor, a separate VIP table, every step id.
        for (int i = 1; i <= 20; i++) {
            assertTrue(markdown.contains(String.format("| A%02d |", i)), "missing ASA sample row A" + i);
            assertTrue(markdown.contains(String.format("| F%02d |", i)), "missing FortiGate sample row F" + i);
        }
        assertTrue(markdown.contains("| V01 |"), "missing the separately recorded VIP sample table");
        for (String step : List.of("PR1", "L-A1", "L-A2", "L-A3", "L-F1", "L-F2", "L-F3", "L-V1", "H-A1", "H-F1", "H-P1",
                "H-U1", "T-A1", "T-F1", "V1", "V2", "V3", "X01")) {
            assertTrue(markdown.contains("| " + step + " |"), "missing step " + step);
        }

        // The checklist contacts no device and names no device command.
        for (String deviceWord : List.of("packet-tracer", "diagnose", "show running", "more system", "show configuration",
                "ssh ", "sudo", "kubectl", "psql")) {
            assertFalse(markdown.toLowerCase(Locale.ROOT).contains(deviceWord), "the checklist names a device or host command: " + deviceWord);
        }

        // Synthetic values only: every IPv4 literal is in a documentation range.
        Matcher address = IPV4.matcher(markdown);
        while (address.find()) {
            String literal = address.group();
            assertTrue(literal.startsWith("192.0.2.") || literal.startsWith("198.51.100.") || literal.startsWith("127.0.0."),
                    "a non-documentation address literal appears in the checklist");
        }
    }

    @Test
    void aChecklistLineWithAWrongCommandFormOrRouteFailsConformance(@TempDir Path work) {
        // Unknown command, as if the lookup were renamed.
        assertThrows(AssertionError.class, () -> runConformance(
                "Run `aiv policy-lookups \"$POLICY_ID\" 192.0.2.10 198.51.100.20 tcp/443` to look up.\n", work),
                "an unknown CLI command in a checklist line must fail conformance");
        // Missing required positional arguments of policy-lookup.
        assertThrows(AssertionError.class, () -> runConformance(
                "Run `aiv policy-lookup \"$POLICY_ID\" 192.0.2.10` to look up.\n", work),
                "a lookup without destination and service must fail conformance");
        // A flag where the CLI takes positional arguments only.
        assertThrows(AssertionError.class, () -> runConformance(
                "Run `aiv policy-hygiene \"$POLICY_ID\" --class unused` to list findings.\n", work),
                "a non-existent flag on policy-hygiene must fail conformance");
        // One positional argument too many.
        assertThrows(AssertionError.class, () -> runConformance(
                "Run `aiv policy-hygiene \"$POLICY_ID\" 0 - - 90 extra` to list findings.\n", work),
                "extra arguments on policy-hygiene must fail conformance");
        // A route the checklist does not name (the search path is pinned to /api/v2/search).
        assertThrows(AssertionError.class, () -> runConformance(
                "Run `aiv api GET \"/api/v2/policy/ha-pairs\"` to search.\n", work),
                "a different route behind aiv api must fail conformance");
        // A write verb behind aiv api.
        assertThrows(AssertionError.class, () -> runConformance(
                "Run `aiv api POST \"/api/v2/search?q=192.0.2.10\" '{}'` to search.\n", work),
                "a POST behind aiv api must fail conformance");
        // Trace without its JSON body.
        assertThrows(AssertionError.class, () -> runConformance("Run `aiv path-query` to trace.\n", work),
                "path-query without a body must fail conformance");
    }

    @Test
    void renamingACommandInTheRealChecklistTextFailsConformance(@TempDir Path work) throws Exception {
        String markdown = readChecklist();
        assertTrue(markdown.contains("aiv policy-ha-pairs"), "precondition: the checklist names policy-ha-pairs");
        String broken = markdown.replace("aiv policy-ha-pairs", "aiv policy-ha-pair");
        assertThrows(AssertionError.class, () -> runConformance(broken, work),
                "the real checklist with one renamed command must fail conformance");
        String brokenArity = markdown.replace("aiv policy-hygiene \"$ASA_POLICY_ID\" 0 unused - 90",
                "aiv policy-hygiene \"$ASA_POLICY_ID\" 0 unused - 90 extra");
        assertThrows(AssertionError.class, () -> runConformance(brokenArity, work),
                "the real checklist with one extra argument must fail conformance");
    }

    // ---- conformance runner ----------------------------------------------------------------------------------

    private static int runConformance(String markdown, Path work) throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = serve(seen);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            List<ScriptChecklistCliConformanceTest.Command> commands = extractCommands(markdown, work, baseUrl);
            assertFalse(commands.isEmpty(), "extractor found zero CLI commands");
            Path aiviewHome = Files.createDirectories(work.resolve("aiview"));

            int checked = 0;
            for (var command : commands) {
                NexusSessionCli cli = new NexusSessionCli(aiviewHome);
                cli.saveSession(new NexusSessionCli.Session(baseUrl, "opaque-cookie", "csrf-token-1"));
                seen.clear();

                ScriptChecklistCliConformanceTest.Outcome outcome = capture(cli, command.args().toArray(new String[0]));
                String where = "[" + command.source() + "] args=" + command.args()
                        + " -> exit " + outcome.exit() + ", stderr: " + outcome.err().strip();

                if ("login".equals(command.name())) {
                    assertTrue(outcome.err().contains("LOGIN_REFUSED"), "login was not dispatched " + where);
                    assertFalse(outcome.err().contains("usage:"), "login arguments rejected " + where);
                    assertTrue(seen.isEmpty() || ("POST".equals(seen.get(0).method()) && "/login".equals(seen.get(0).path())),
                            "login called an unexpected route " + where);
                    checked++;
                    continue;
                }
                if ("logout".equals(command.name())) {
                    assertEquals(0, outcome.exit(), "logout did not succeed against the stub " + where);
                    assertTrue(seen.size() <= 1 && (seen.isEmpty()
                                    || ("POST".equals(seen.get(0).method()) && "/session/logout".equals(seen.get(0).path()))),
                            "logout called an unexpected route " + where);
                    checked++;
                    continue;
                }

                RouteSpec spec = EXPECTED.get(command.name());
                assertNotNull(spec, "checklist command not in the conformance map (or unknown command): " + command.source());

                // The policy commands take positional arguments, never flags (a flag would be sent as a value).
                if (command.name().startsWith("policy-") || command.name().startsWith("path-")) {
                    for (int i = 1; i < command.args().size(); i++) {
                        String arg = command.args().get(i);
                        assertFalse(arg.startsWith("--") || (arg.startsWith("-") && arg.length() > 1),
                                "the policy and Trace commands take positional arguments, not flags (" + arg + ") " + where);
                    }
                }

                assertFalse(outcome.err().contains("unknown command"), "not a CLI command " + where);
                assertFalse(outcome.err().contains("usage:"), "the CLI rejects this command line " + where);
                assertEquals(0, outcome.exit(), "the command did not succeed against the stub " + where);
                assertEquals(1, seen.size(), "expected exactly one request " + where + " seen=" + seen);

                Seen request = seen.get(0);
                if ("api".equals(command.name())) {
                    // aiv api <VERB> <path>: the checklist uses it only for the read-only search route.
                    assertEquals(spec.method(), command.args().get(1).toUpperCase(Locale.ROOT), "aiv api verb is pinned to GET " + where);
                    assertEquals(spec.method(), request.method(), "wrong verb " + where);
                    assertTrue(spec.path().matcher(request.path()).matches(), "wrong route " + where + " " + request.path());
                    String given = command.args().get(2);
                    int mark = given.indexOf('?');
                    assertEquals(mark < 0 ? null : given.substring(mark + 1), request.rawQuery(), "wrong query string " + where);
                    checked++;
                    continue;
                }

                assertEquals(spec.method(), request.method(), "wrong verb " + where + " path=" + request.path());
                assertTrue(spec.path().matcher(request.path()).matches(),
                        "wrong route " + where + " " + request.method() + " " + request.path()
                                + " (expected " + spec.method() + " " + spec.path().pattern() + ")");
                assertEquals(expectedQuery(command.args(), spec), request.rawQuery(),
                        "wrong query string " + where + " actual=" + request.rawQuery());
                if (spec.rawJsonBody()) {
                    assertEquals(command.args().get(1), request.body(), "the JSON body must be sent as given " + where);
                } else if (spec.bodyNames().length > 0) {
                    assertEquals(expectedBody(command.args(), spec), actualBody(request.body()),
                            "wrong request body " + where + " actual=" + request.body());
                }
                checked++;
            }
            return checked;
        } finally {
            server.stop(0);
        }
    }

    private static String expectedQuery(List<String> args, RouteSpec spec) {
        int start = 1 + (spec.hasPathId() ? 1 : 0);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < spec.queryNames().length && start + i < args.size(); i++) {
            String value = args.get(start + i);
            if (!"-".equals(value)) {
                parts.add(spec.queryNames()[i] + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        return parts.isEmpty() ? null : String.join("&", parts);
    }

    /** The JSON members {@code policy-lookup} must send: every positional argument after the id that is not {@code -}. */
    private static Map<String, String> expectedBody(List<String> args, RouteSpec spec) {
        int start = 1 + (spec.hasPathId() ? 1 : 0);
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < spec.bodyNames().length && start + i < args.size(); i++) {
            String value = args.get(start + i);
            if (!"-".equals(value)) {
                fields.put(spec.bodyNames()[i], value);
            }
        }
        return fields;
    }

    private static Map<String, String> actualBody(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher m = JSON_STRING_FIELD.matcher(body == null ? "" : body);
        while (m.find()) {
            fields.put(m.group(1), m.group(2));
        }
        return fields;
    }

    // ---- extraction (reusing ScriptChecklistCliConformanceTest.words) ----------------------------------------

    private static String readChecklist() throws Exception {
        return Files.readString(locate(CHECKLIST), StandardCharsets.UTF_8);
    }

    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve(repoRelative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        fail(repoRelative + " was not found in any parent of " + dir
                + ": the document moved or was renamed; this test must follow it, not silently pass");
        return null;
    }

    private static List<ScriptChecklistCliConformanceTest.Command> extractCommands(String markdown, Path work, String baseUrl) {
        List<String> fragments = new ArrayList<>();
        boolean fenced = false;
        boolean heredoc = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
                heredoc = false;
                continue;
            }
            if (fenced) {
                if (heredoc) {
                    heredoc = !line.strip().equals("EOF");
                    continue;
                }
                if (line.contains("<<'EOF'") || line.contains("<<EOF")) {
                    heredoc = true;
                }
                if (!line.stripLeading().startsWith("#")) {
                    fragments.add(line);
                }
            } else {
                Matcher span = INLINE_SPAN.matcher(line);
                while (span.find()) {
                    fragments.add(span.group(1));
                }
            }
        }

        List<ScriptChecklistCliConformanceTest.Command> commands = new ArrayList<>();
        for (String fragment : fragments) {
            String resolved = resolvePlaceholders(fragment, baseUrl);
            Matcher start = COMMAND_START.matcher(resolved);
            while (start.find()) {
                List<String> words = ScriptChecklistCliConformanceTest.words(resolved, start.end());
                words = new ArrayList<>(words.stream().map(w -> expandVariables(w, work)).toList());
                if (words.isEmpty() || words.get(0).equals("$@") || words.get(0).equals("\"$@\"")) {
                    continue; // helper definition forwarding "$@"
                }
                commands.add(new ScriptChecklistCliConformanceTest.Command(start.group(1), words, fragment.strip()));
            }
        }
        return commands;
    }

    private static String resolvePlaceholders(String text, String baseUrl) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).strip().toLowerCase(Locale.ROOT);
            String value;
            if (name.contains("baseurl")) {
                value = baseUrl;
            } else if (name.contains("account")) {
                value = "aiview-synthetic";
            } else if (name.endsWith("id")) {
                value = SYNTHETIC_ID;
            } else {
                value = "synthetic-value";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String expandVariables(String word, Path work) {
        Matcher m = SHELL_VARIABLE.matcher(word);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = switch (name) {
                case "WORK" -> work.toString();
                case "@" -> "$@";
                default -> name.endsWith("_ID") ? SYNTHETIC_ID : "synthetic-value";
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---- stub server and capture -----------------------------------------------------------------------------

    private static HttpServer serve(List<Seen> seen) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            URI uri = exchange.getRequestURI();
            seen.add(new Seen(exchange.getRequestMethod(), uri.getPath(), uri.getRawQuery(), body));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static ScriptChecklistCliConformanceTest.Outcome capture(NexusSessionCli cli, String[] args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = cli.run(args);
            return new ScriptChecklistCliConformanceTest.Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }
}
