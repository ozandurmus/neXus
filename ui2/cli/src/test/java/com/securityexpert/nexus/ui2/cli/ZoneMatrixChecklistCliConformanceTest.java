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
 * Pins every CLI form in the Zone Matrix real-environment acceptance checklist
 * ({@code docs/design/ZONE_MATRIX_REAL_ENV_ACCEPTANCE_CHECKLIST_2026_11_26.md}, DRAFT) to {@link NexusSessionCli} and
 * {@link ZonesMatrixCli}: every {@code adm} / {@code aiv} / {@code nexus-cli} / {@code overwatch-cli} command the
 * document names is extracted, its placeholders and shell variables are replaced with synthetic values, and it is run
 * against a local stub HTTP server (no PostgreSQL, no external network). A command {@link ZonesMatrixCli} or
 * {@link NexusSessionCli} does not dispatch, an unknown flag or extra positional argument, or a request whose verb,
 * route, query string or JSON body differs from what the step names fails here.
 *
 * <p>Automated validation only: it proves the checklist's CLI forms parse and call the documented routes, queries and
 * request bodies. It makes no real-environment claim.</p>
 */
class ZoneMatrixChecklistCliConformanceTest {

    private static final String CHECKLIST = "docs/design/ZONE_MATRIX_REAL_ENV_ACCEPTANCE_CHECKLIST_2026_11_26.md";
    private static final String SYNTHETIC_VERSION_ID = "11111111-2222-4333-8444-555555555555";
    private static final String SYNTHETIC_POLICY_ID = "22222222-3333-4444-8555-666666666666";
    private static final String SYNTHETIC_FINDING_ID = "4711";
    private static final String SYNTHETIC_OVERSIZE_JSON =
            "{\"revision\":1,\"statement\":\"DENY_ALL\",\"severity\":\"MEDIUM\",\"pad\":\"synthetic\"}";
    private static final Set<String> SUMMARY_OUTCOMES = Set.of("VIOLATION", "UNKNOWN", "NOT_EVALUABLE");
    private static final int MAX_SUMMARY_LIMIT = 1000;
    private static final int MIN_COMMANDS = 30;

    private static final Pattern COMMAND_START = Pattern.compile("(?<![\\w$./-])(adm|aiv|nexus-cli|overwatch-cli)(?=\\s)");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([^<>\\n]{1,40})>");
    private static final Pattern SHELL_VARIABLE = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?");
    private static final Pattern INLINE_SPAN = Pattern.compile("`([^`\\n]+)`");

    private record RouteSpec(String method, Pattern path, int maxArgs) {
        static RouteSpec of(String method, String pathRegex, int maxArgs) {
            return new RouteSpec(method, Pattern.compile(pathRegex), maxArgs);
        }
    }

    private record Seen(String method, String path, String rawQuery, String body) {
    }

    /** Command name to its exact HTTP verb, path pattern, and maximum argument count in ZonesMatrixCli / NexusSessionCli. */
    private static final Map<String, RouteSpec> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("login", RouteSpec.of("POST", "/login", 4));
        EXPECTED.put("logout", RouteSpec.of("POST", "/session/logout", 1));
        EXPECTED.put("session", RouteSpec.of("GET", "/session/status", 1));
        EXPECTED.put("api", RouteSpec.of("GET", "/audit-logs", 4));
        EXPECTED.put("zone-matrix-versions", RouteSpec.of("GET", "/api/v2/zone-matrix/versions", 1));
        EXPECTED.put("zone-matrix-version", RouteSpec.of("GET", "/api/v2/zone-matrix/versions/" + SYNTHETIC_VERSION_ID, 3));
        EXPECTED.put("zone-matrix-draft", RouteSpec.of("GET", "/api/v2/zone-matrix/draft", 2));
        EXPECTED.put("zone-matrix-template", RouteSpec.of("GET", "/api/v2/zone-matrix/template", 1));
        EXPECTED.put("zone-matrix-policies", RouteSpec.of("GET", "/api/v2/zone-matrix/policies", 1));
        EXPECTED.put("zone-matrix-lookup", RouteSpec.of("POST", "/api/v2/zone-matrix/lookup", 3));
        EXPECTED.put("zone-matrix-default-set", RouteSpec.of("POST", "/api/v2/zone-matrix/draft/default", 2));
        EXPECTED.put("zone-matrix-evaluate", RouteSpec.of("POST", "/api/v2/zone-matrix/evaluate", 4));
        EXPECTED.put("zone-matrix-run", RouteSpec.of("POST", "/api/v2/zone-matrix/runs", 4));
        EXPECTED.put("zone-matrix-finding-witness", RouteSpec.of("GET",
                "/api/v2/zone-matrix/findings/" + SYNTHETIC_FINDING_ID + "/witness", 2));
        EXPECTED.put("zone-matrix-summary", RouteSpec.of("GET", "/api/v2/zone-matrix/summary", 4));
    }

    @Test
    void everyChecklistCommandDispatchesAndSendsTheExactRouteQueryAndBody(@TempDir Path work) throws Exception {
        String markdown = readChecklist();
        int checked = runConformance(markdown, work);
        assertTrue(checked >= MIN_COMMANDS,
                "the extractor found only " + checked + " adm/aiv/nexus-cli commands; expected at least " + MIN_COMMANDS
                        + " (did the checklist change shape?)");
        System.out.println("ZoneMatrixChecklistCliConformanceTest: " + checked + " checklist commands checked");
    }

    @Test
    void checklistCoversEveryRequiredAcceptanceSurfaceAndNoUnbuiltReportRoute(@TempDir Path work) throws Exception {
        String markdown = readChecklist();
        List<ScriptChecklistCliConformanceTest.Command> commands = extractCommands(markdown, work, "http://127.0.0.1:1");
        Set<String> usedCommands = new TreeSet<>();
        Set<String> admCommands = new TreeSet<>();
        Set<String> aivCommands = new TreeSet<>();
        Set<String> summaryOutcomes = new LinkedHashSet<>();

        for (var command : commands) {
            usedCommands.add(command.name());
            if ("adm".equals(command.helper())) {
                admCommands.add(command.name());
            } else if ("aiv".equals(command.helper())) {
                aivCommands.add(command.name());
            }
            if ("zone-matrix-summary".equals(command.name()) && command.args().size() >= 3 && !"-".equals(command.args().get(2))) {
                summaryOutcomes.add(command.args().get(2));
            }
        }

        Set<String> required = Set.of(
                "login", "logout", "session", "api",
                "zone-matrix-versions", "zone-matrix-version", "zone-matrix-draft", "zone-matrix-template",
                "zone-matrix-policies", "zone-matrix-run", "zone-matrix-summary", "zone-matrix-finding-witness",
                "zone-matrix-lookup", "zone-matrix-default-set", "zone-matrix-evaluate");
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(usedCommands);
        assertTrue(missing.isEmpty(), "checklist is missing required CLI commands: " + missing);

        assertTrue(summaryOutcomes.containsAll(Set.of("VIOLATION", "NOT_EVALUABLE")),
                "checklist must exercise VIOLATION and NOT_EVALUABLE summary queries: " + summaryOutcomes);
        assertTrue(admCommands.containsAll(Set.of("zone-matrix-run", "zone-matrix-finding-witness",
                "zone-matrix-lookup", "zone-matrix-default-set", "api")),
                "security_admin session must cover run, witness, lookup, oversize edit and audit-log commands: " + admCommands);
        assertTrue(aivCommands.containsAll(Set.of("zone-matrix-summary", "zone-matrix-versions",
                "zone-matrix-finding-witness", "zone-matrix-lookup", "zone-matrix-run", "zone-matrix-evaluate")),
                "aiview session must cover masked reads and HTTP 403 refusal probes: " + aivCommands);

        // Reports R1 catalogue is closed on origin/main; no Zone Matrix report route or CLI command exists yet.
        assertFalse(usedCommands.contains("zone-matrix-report"), "no zone-matrix-report CLI command exists on origin/main");
        assertFalse(usedCommands.contains("zone-matrix-export"), "no zone-matrix-export CLI command exists on origin/main");
    }

    @Test
    void aChecklistLineWithAnUnknownCommandOrFlagOrBadSummaryQueryFailsConformance(@TempDir Path work) {
        String badCommand = "Run `adm zone-matrix-report \"$ACTIVE_VERSION_ID\"` to export the report.\n";
        assertThrows(AssertionError.class, () -> runConformance(badCommand, work),
                "an unknown CLI command in a checklist line must fail conformance");

        String badFlagOnSummary = "Run `aiv zone-matrix-summary --outcome VIOLATION` to filter findings.\n";
        assertThrows(AssertionError.class, () -> runConformance(badFlagOnSummary, work),
                "a non-existent flag on zone-matrix-summary must fail conformance");

        String badFlagOnRun = "Run `adm zone-matrix-run \"$POLICY_ID\" --witnesses` to start a run.\n";
        assertThrows(AssertionError.class, () -> runConformance(badFlagOnRun, work),
                "an unknown flag on zone-matrix-run must fail conformance");

        String tooManyArgs = "Run `aiv zone-matrix-summary \"$POLICY_ID\" VIOLATION 20 extra` to read summary.\n";
        assertThrows(AssertionError.class, () -> runConformance(tooManyArgs, work),
                "extra positional arguments on zone-matrix-summary must fail conformance");

        String badOutcome = "Run `aiv zone-matrix-summary - COMPLIANT 20` to read compliant rules.\n";
        assertThrows(AssertionError.class, () -> runConformance(badOutcome, work),
                "an unstored outcome on zone-matrix-summary must fail conformance");

        String nonCanonicalFindingId = "Run `adm zone-matrix-finding-witness 04711` to read witness.\n";
        assertThrows(AssertionError.class, () -> runConformance(nonCanonicalFindingId, work),
                "a leading-zero findingId rejected by ZonesMatrixCli must fail conformance");
    }

    // ---- conformance runner ----------------------------------------------------------------------------------

    private static int runConformance(String markdown, Path work) throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = serve(seen);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            List<ScriptChecklistCliConformanceTest.Command> commands = extractCommands(markdown, work, baseUrl);
            assertFalse(commands.isEmpty(), "extractor found zero CLI commands");
            Path adminHome = Files.createDirectories(work.resolve("admin"));
            Path aiviewHome = Files.createDirectories(work.resolve("aiview"));

            int checked = 0;
            for (var command : commands) {
                RouteSpec spec = EXPECTED.get(command.name());
                assertNotNull(spec, "checklist command not in the conformance map (or unknown command): " + command.source());

                String where = "[" + command.source() + "] args=" + command.args();
                assertTrue(command.args().size() <= spec.maxArgs(),
                        "too many positional arguments (max " + spec.maxArgs() + ") " + where);
                checkArgumentsAndFlags(command, where);
                materialiseBodyFiles(command, work);

                Path home = "aiv".equals(command.helper()) ? aiviewHome : adminHome;
                NexusSessionCli cli = new NexusSessionCli(home);
                cli.saveSession(new NexusSessionCli.Session(baseUrl, "opaque-cookie", "csrf-token-1"));
                seen.clear();

                ScriptChecklistCliConformanceTest.Outcome outcome = capture(cli, command.args().toArray(new String[0]));
                String detail = where + " -> exit " + outcome.exit() + ", stderr: " + outcome.err().strip();

                if ("login".equals(command.name())) {
                    assertTrue(outcome.err().contains("LOGIN_REFUSED"), "login was not dispatched " + detail);
                    assertFalse(outcome.err().contains("usage:"), "login arguments rejected " + detail);
                    assertTrue(seen.isEmpty() || ("POST".equals(seen.get(0).method()) && "/login".equals(seen.get(0).path())),
                            "login called an unexpected route " + detail);
                    checked++;
                    continue;
                }

                assertFalse(outcome.err().contains("unknown command"), "not a CLI command " + detail);
                assertFalse(outcome.err().contains("usage:"), "the CLI rejects this command line " + detail);
                assertEquals(0, outcome.exit(), "the command did not succeed against the stub " + detail);
                assertEquals(1, seen.size(), "expected exactly one request " + detail + " seen=" + seen);

                Seen request = seen.get(0);
                assertEquals(spec.method(), request.method(), "wrong verb " + detail + " path=" + request.path());
                assertTrue(spec.path().matcher(request.path()).matches(),
                        "wrong route " + detail + " " + request.method() + " " + request.path()
                                + " (expected " + spec.method() + " " + spec.path().pattern() + ")");
                assertEquals(expectedQuery(command.args()), request.rawQuery(),
                        "wrong query string " + detail + " actual=" + request.rawQuery());
                checkRequestBody(command, request, detail);
                checked++;
            }
            return checked;
        } finally {
            server.stop(0);
        }
    }

    private static void checkArgumentsAndFlags(ScriptChecklistCliConformanceTest.Command command, String where) {
        List<String> args = command.args();
        String name = command.name();
        if (name.startsWith("zone-matrix-")) {
            for (int i = 1; i < args.size(); i++) {
                String arg = args.get(i);
                if ("zone-matrix-run".equals(name) && i == 2 && "--version".equals(arg)) {
                    continue;
                }
                assertFalse(arg.startsWith("--") || (arg.startsWith("-") && arg.length() > 1),
                        "unexpected flag on " + name + " (" + arg + ") " + where);
            }
        }
        if ("zone-matrix-summary".equals(name)) {
            if (args.size() >= 3 && !"-".equals(args.get(2))) {
                assertTrue(SUMMARY_OUTCOMES.contains(args.get(2)),
                        "zone-matrix-summary outcome must be one of " + SUMMARY_OUTCOMES + " " + where);
            }
            if (args.size() >= 4 && !"-".equals(args.get(3))) {
                assertTrue(args.get(3).matches("[0-9]{1,4}"),
                        "zone-matrix-summary limit must be an integer in 1.." + MAX_SUMMARY_LIMIT + " " + where);
                int limit = Integer.parseInt(args.get(3));
                assertTrue(limit >= 1 && limit <= MAX_SUMMARY_LIMIT,
                        "zone-matrix-summary limit out of 1.." + MAX_SUMMARY_LIMIT + " " + where);
            }
        }
    }

    private static String expectedQuery(List<String> args) {
        return switch (args.get(0)) {
            case "zone-matrix-version" -> args.size() > 2 && "blocks".equals(args.get(2)) ? "blocks=1" : null;
            case "zone-matrix-draft" -> args.size() > 1 && "blocks".equals(args.get(1)) ? "blocks=1" : null;
            case "zone-matrix-summary" -> {
                String[] names = {"policy_id", "outcome", "limit"};
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < names.length && 1 + i < args.size(); i++) {
                    String value = args.get(1 + i);
                    if (!"-".equals(value)) {
                        parts.add(names[i] + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
                    }
                }
                yield parts.isEmpty() ? null : String.join("&", parts);
            }
            default -> null;
        };
    }

    private static void checkRequestBody(ScriptChecklistCliConformanceTest.Command command, Seen request, String detail) {
        List<String> args = command.args();
        switch (command.name()) {
            case "api" -> {
                assertEquals(args.get(1).toUpperCase(Locale.ROOT), request.method(), detail);
                assertEquals(args.get(2), request.path(), detail);
            }
            case "zone-matrix-run" -> {
                String expected = args.size() == 4
                        ? "{\"policy_id\":\"" + args.get(1) + "\",\"version_id\":\"" + args.get(3) + "\"}"
                        : "{\"policy_id\":\"" + args.get(1) + "\"}";
                assertEquals(expected, request.body(), "wrong zone-matrix-run body " + detail);
            }
            case "zone-matrix-lookup" -> {
                String expected = args.size() == 3
                        ? "{\"address\":\"" + args.get(1) + "\",\"version_id\":\"" + args.get(2) + "\"}"
                        : "{\"address\":\"" + args.get(1) + "\"}";
                assertEquals(expected, request.body(), "wrong zone-matrix-lookup body " + detail);
            }
            case "zone-matrix-default-set" -> assertEquals(SYNTHETIC_OVERSIZE_JSON, request.body(),
                    "zone-matrix-default-set must pass the @file JSON body verbatim " + detail);
            case "zone-matrix-evaluate" -> {
                if (args.size() >= 2 && !"-".equals(args.get(1))) {
                    assertTrue(request.body().contains("\"policy_id\":\"" + args.get(1) + "\""),
                            "zone-matrix-evaluate must send policy_id " + detail);
                }
            }
            default -> {
            }
        }
    }

    private static void materialiseBodyFiles(ScriptChecklistCliConformanceTest.Command command, Path work) throws Exception {
        String prefix = "@" + work.toString();
        for (String word : command.args()) {
            if (word.startsWith(prefix) && word.endsWith(".json")) {
                Path file = Path.of(word.substring(1));
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, SYNTHETIC_OVERSIZE_JSON, StandardCharsets.UTF_8);
                }
            }
        }
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
                value = "synthetic-account";
            } else if (name.contains("findingid")) {
                value = SYNTHETIC_FINDING_ID;
            } else if (name.contains("versionid")) {
                value = SYNTHETIC_VERSION_ID;
            } else if (name.contains("policyid") || name.equals("id") || name.endsWith("id")) {
                value = SYNTHETIC_POLICY_ID;
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
                case "FINDING_ID" -> SYNTHETIC_FINDING_ID;
                case "ACTIVE_VERSION_ID", "VERSION_ID" -> SYNTHETIC_VERSION_ID;
                case "POLICY_ID" -> SYNTHETIC_POLICY_ID;
                default -> name.startsWith("ID_") || name.endsWith("_ID") ? SYNTHETIC_POLICY_ID : "synthetic-value";
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
