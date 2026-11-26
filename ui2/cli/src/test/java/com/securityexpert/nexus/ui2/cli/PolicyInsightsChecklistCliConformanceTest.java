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
 * Pins every CLI form in the Policy Insights real-environment review checklist
 * ({@code docs/design/POLICY_INSIGHTS_REAL_ENV_REVIEW_CHECKLIST_2026_11_26.md}, DRAFT) to {@link NexusSessionCli},
 * {@link PolicyCli} and {@link PolicyOpsCli}: every {@code aiv} / {@code nexus-cli} command the document names is
 * extracted, its placeholders and shell variables are replaced with synthetic values, and it is run against a local
 * stub server (no PostgreSQL, no network). A command the CLI does not dispatch, an unknown flag or extra positional
 * argument, or a request whose verb, route or query string differs from what the step names fails here.
 *
 * <p>Automated validation only: it proves the checklist's CLI forms parse and call the documented routes and queries.
 * It makes no real-environment claim.</p>
 */
class PolicyInsightsChecklistCliConformanceTest {

    private static final String CHECKLIST = "docs/design/POLICY_INSIGHTS_REAL_ENV_REVIEW_CHECKLIST_2026_11_26.md";
    private static final String SYNTHETIC_POLICY_ID = "11111111-2222-4333-8444-555555555555";
    private static final String SYNTHETIC_INSIGHT_ID = "fedcba98-7654-4321-8fed-cba987654321";
    private static final String SYNTHETIC_RULE_ID = "rule-0001";
    private static final int MIN_COMMANDS = 25;

    private static final Pattern COMMAND_START = Pattern.compile("(?<![\\w$./-])(aiv|nexus-cli|overwatch-cli)(?=\\s)");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([^<>\\n]{1,40})>");
    private static final Pattern SHELL_VARIABLE = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?");
    private static final Pattern INLINE_SPAN = Pattern.compile("`([^`\\n]+)`");

    private record RouteSpec(String method, Pattern path, boolean hasPathId, String[] queryNames) {
        static RouteSpec of(String method, String pathRegex, boolean hasPathId, String... queryNames) {
            return new RouteSpec(method, Pattern.compile(pathRegex), hasPathId, queryNames);
        }
    }

    private record Seen(String method, String path, String rawQuery) {
    }

    /** Command name to its exact HTTP verb, path pattern, and positional query parameter order from PolicyCli/PolicyOpsCli. */
    private static final Map<String, RouteSpec> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("login", RouteSpec.of("POST", "/login", false));
        EXPECTED.put("logout", RouteSpec.of("POST", "/session/logout", false));
        EXPECTED.put("session", RouteSpec.of("GET", "/session/status", false));
        EXPECTED.put("policy-devices", RouteSpec.of("GET", "/api/v2/policy/devices", false));
        EXPECTED.put("policy-sources", RouteSpec.of("GET", "/api/v2/policy/sources", false));
        EXPECTED.put("policy-insights", RouteSpec.of("GET", "/api/v2/policy/policies/" + SYNTHETIC_POLICY_ID + "/insights", true,
                "page", "type", "grade", "days"));
        EXPECTED.put("policy-insight", RouteSpec.of("GET", "/api/v2/policy/policies/" + SYNTHETIC_POLICY_ID + "/insight", true,
                "insightId", "days"));
        EXPECTED.put("policy-hygiene", RouteSpec.of("GET", "/api/v2/policy/policies/" + SYNTHETIC_POLICY_ID + "/hygiene", true,
                "page", "findingClass", "severity", "days"));
        EXPECTED.put("policy-rule", RouteSpec.of("GET", "/api/v2/policy/policies/" + SYNTHETIC_POLICY_ID + "/rules", true,
                "ruleId", "days"));
        EXPECTED.put("policy-show", RouteSpec.of("GET", "/api/v2/policy/policies/" + SYNTHETIC_POLICY_ID, true,
                "page", "q", "hitFilter", "days", "sort", "order"));
        EXPECTED.put("policy-history", RouteSpec.of("GET", "/api/v2/policy/policies/" + SYNTHETIC_POLICY_ID + "/history", true,
                "rule", "page"));
    }

    @Test
    void everyChecklistCommandDispatchesAndSendsTheExactRouteAndQuery(@TempDir Path work) throws Exception {
        String markdown = readChecklist();
        int checked = runConformance(markdown, work);
        assertTrue(checked >= MIN_COMMANDS,
                "the extractor found only " + checked + " aiv/nexus-cli commands; expected at least " + MIN_COMMANDS
                        + " (did the checklist change shape?)");
        System.out.println("PolicyInsightsChecklistCliConformanceTest: " + checked + " checklist commands checked");
    }

    @Test
    void checklistCoversEveryRequiredReviewPhaseAndNoUnbuiltRoute(@TempDir Path work) throws Exception {
        String markdown = readChecklist();
        List<ScriptChecklistCliConformanceTest.Command> commands = extractCommands(markdown, work, "http://127.0.0.1:1");
        Set<String> usedCommands = new TreeSet<>();
        Set<String> insightTypesQueried = new LinkedHashSet<>();
        for (var command : commands) {
            usedCommands.add(command.name());
            if ("policy-insights".equals(command.name()) && command.args().size() >= 4 && !"-".equals(command.args().get(3))) {
                insightTypesQueried.add(command.args().get(3));
            }
        }

        // Required commands across preconditions, P1-a, P1-b, P2-a, P2-c, P2-d and cleanup.
        Set<String> required = Set.of("login", "logout", "session", "policy-devices", "policy-sources",
                "policy-insights", "policy-insight", "policy-hygiene", "policy-rule", "policy-show", "policy-history");
        Set<String> missingCommands = new LinkedHashSet<>(required);
        missingCommands.removeAll(usedCommands);
        assertTrue(missingCommands.isEmpty(), "checklist is missing required CLI commands: " + missingCommands);

        // Every P1 + P2a + P2b + P2c-1 insight type has an explicit policy-insights CLI form in the checklist.
        Set<String> requiredTypes = Set.of("I1", "I2", "I3", "I4", "I7", "I8", "I9", "I10", "I11", "I12");
        Set<String> missingTypes = new LinkedHashSet<>(requiredTypes);
        missingTypes.removeAll(insightTypesQueried);
        assertTrue(missingTypes.isEmpty(), "checklist is missing policy-insights CLI forms for types: " + missingTypes);

        // Unbuilt routes (I5 merge / proposal route) are not invoked as commands in this checklist.
        assertFalse(usedCommands.contains("policy-proposal"), "no proposal CLI command exists on origin/main");
        assertFalse(usedCommands.contains("policy-merge"), "no merge CLI command exists on origin/main");
    }

    @Test
    void aChecklistLineWithAnUnknownCommandOrFlagFailsConformance(@TempDir Path work) {
        String badCommand = "Run `aiv policy-proposal \"$POLICY_ID\"` to build a proposal.\n";
        assertThrows(AssertionError.class, () -> runConformance(badCommand, work),
                "an unknown CLI command in a checklist line must fail conformance");

        String badFlag = "Run `aiv policy-insights \"$POLICY_ID\" --type I7` to list reorder moves.\n";
        assertThrows(AssertionError.class, () -> runConformance(badFlag, work),
                "a non-existent flag on policy-insights must fail conformance");

        String tooManyArgs = "Run `aiv policy-hygiene \"$POLICY_ID\" 0 - - 90 extra` to check hygiene.\n";
        assertThrows(AssertionError.class, () -> runConformance(tooManyArgs, work),
                "extra arguments on policy-hygiene must fail conformance");
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
                RouteSpec spec = EXPECTED.get(command.name());
                assertNotNull(spec, "checklist command not in the conformance map (or unknown command): " + command.source());

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

                // Reject flags anywhere in PolicyCli/PolicyOpsCli positional args (e.g. --type would otherwise be sent as a page value).
                if (command.name().startsWith("policy-")) {
                    for (int i = 1; i < command.args().size(); i++) {
                        String arg = command.args().get(i);
                        assertFalse(arg.startsWith("--") || (arg.startsWith("-") && arg.length() > 1),
                                "PolicyCli/PolicyOpsCli takes positional arguments, not flags (" + arg + ") " + where);
                    }
                }

                assertFalse(outcome.err().contains("unknown command"), "not a CLI command " + where);
                assertFalse(outcome.err().contains("usage:"), "the CLI rejects this command line " + where);
                assertEquals(0, outcome.exit(), "the command did not succeed against the stub " + where);
                assertEquals(1, seen.size(), "expected exactly one request " + where + " seen=" + seen);

                Seen request = seen.get(0);
                assertEquals(spec.method(), request.method(), "wrong verb " + where + " path=" + request.path());
                assertTrue(spec.path().matcher(request.path()).matches(),
                        "wrong route " + where + " " + request.method() + " " + request.path()
                                + " (expected " + spec.method() + " " + spec.path().pattern() + ")");
                assertEquals(expectedQuery(command.args(), spec), request.rawQuery(),
                        "wrong query string " + where + " actual=" + request.rawQuery());
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
            } else if (name.contains("insightid")) {
                value = SYNTHETIC_INSIGHT_ID;
            } else if (name.contains("ruleid")) {
                value = SYNTHETIC_RULE_ID;
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
                case "POLICY_ID" -> SYNTHETIC_POLICY_ID;
                case "INSIGHT_ID" -> SYNTHETIC_INSIGHT_ID;
                case "RULE_ID" -> SYNTHETIC_RULE_ID;
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
            exchange.getRequestBody().readAllBytes();
            URI uri = exchange.getRequestURI();
            seen.add(new Seen(exchange.getRequestMethod(), uri.getPath(), uri.getRawQuery()));
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
