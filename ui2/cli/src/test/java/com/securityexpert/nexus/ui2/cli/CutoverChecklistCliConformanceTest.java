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
 * Pins every CLI form in the ACI Step 1 real-environment acceptance checklist
 * ({@code docs/design/ACI_STEP1_REAL_ENV_ACCEPTANCE_CHECKLIST_2026_11_26.md}, DRAFT) to {@link NexusSessionCli},
 * {@link CutoverCli}, {@link PolicyCli} and {@link PathTopologyCli}: every {@code adm} / {@code aiv} /
 * {@code nexus-cli} / {@code overwatch-cli} command in the document is extracted, its placeholders and shell variables
 * are replaced with synthetic values (including a tiny synthetic {@code spec.json}, {@code export.csv} and
 * {@code trace-in-segment.json} in a temporary directory and {@code 192.0.2.0/24}-style documentation blocks), and it
 * is executed against a local stub server (no PostgreSQL, no external network).
 *
 * <p>Automated validation only: it proves the checklist's CLI forms parse, reject unknown commands or flags, and send
 * the documented HTTP verb, route and query string. It makes no real-environment claim.</p>
 */
class CutoverChecklistCliConformanceTest {

    private static final String CHECKLIST = "docs/design/ACI_STEP1_REAL_ENV_ACCEPTANCE_CHECKLIST_2026_11_26.md";
    private static final String SYNTHETIC_SEGMENT_ID = "11111111-2222-4333-8444-555555555555";
    private static final String SYNTHETIC_ASSESSMENT_ID = "66666666-7777-4888-9999-aaaaaaaaaaaa";
    private static final String SYNTHETIC_POLICY_ID = "22222222-3333-4444-8555-666666666666";
    private static final String SYNTHETIC_RULE_ID = "rule-0001";
    private static final int MIN_COMMANDS = 25;

    private static final Pattern COMMAND_START = Pattern.compile("(?<![\\w$./-])(adm|aiv|nexus-cli|overwatch-cli)(?=\\s)");
    private static final Pattern BARE_CUTOVER_CMD = Pattern.compile("^\\s*cutover-[a-z0-9-]+\\s+");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([^<>\\n]{1,40})>");
    private static final Pattern SHELL_VARIABLE = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?");
    private static final Pattern INLINE_SPAN = Pattern.compile("`([^`\\n]+)`");

    private enum QueryKind {
        NONE,
        CUTOVER_FLOWS,
        POLICY_POSITIONAL
    }

    private record RouteSpec(
            String method,
            Pattern path,
            int minArgs,
            int maxArgs,
            boolean hasPathId,
            QueryKind queryKind,
            String... queryNames) {

        static RouteSpec fixed(String method, String pathRegex, int minArgs, int maxArgs) {
            return new RouteSpec(method, Pattern.compile(pathRegex), minArgs, maxArgs, false, QueryKind.NONE);
        }

        static RouteSpec cutoverFlows(String pathRegex) {
            return new RouteSpec("GET", Pattern.compile(pathRegex), 2, 5, true, QueryKind.CUTOVER_FLOWS);
        }

        static RouteSpec policyQuery(String pathRegex, int minArgs, int maxArgs, String... queryNames) {
            return new RouteSpec("GET", Pattern.compile(pathRegex), minArgs, maxArgs, true, QueryKind.POLICY_POSITIONAL, queryNames);
        }
    }

    private record Seen(String method, String path, String rawQuery, String contentType, String body) {
    }

    /** Exact verb, path pattern, arity bounds and query rules from CutoverCli, PolicyCli, PathTopologyCli and NexusSessionCli. */
    private static final Map<String, RouteSpec> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("login", RouteSpec.fixed("POST", "/login", 3, 4));
        EXPECTED.put("logout", RouteSpec.fixed("POST", "/session/logout", 1, 1));
        EXPECTED.put("session", RouteSpec.fixed("GET", "/session/status", 1, 1));
        EXPECTED.put("cutover-segments", RouteSpec.fixed("GET", "/api/v2/cutover/segments", 1, 1));
        EXPECTED.put("cutover-segment", RouteSpec.fixed("GET", "/api/v2/cutover/segments/" + SYNTHETIC_SEGMENT_ID, 2, 2));
        EXPECTED.put("cutover-segment-create", RouteSpec.fixed("POST", "/api/v2/cutover/segments", 4, 4));
        EXPECTED.put("cutover-segment-update", RouteSpec.fixed("PUT", "/api/v2/cutover/segments/" + SYNTHETIC_SEGMENT_ID, 6, 6));
        EXPECTED.put("cutover-segment-delete", RouteSpec.fixed("DELETE", "/api/v2/cutover/segments/" + SYNTHETIC_SEGMENT_ID, 2, 2));
        EXPECTED.put("cutover-assess", RouteSpec.fixed("POST", "/api/v2/cutover/segments/" + SYNTHETIC_SEGMENT_ID + "/assessments", 4, 11));
        EXPECTED.put("cutover-assessment", RouteSpec.fixed("GET", "/api/v2/cutover/assessments/" + SYNTHETIC_ASSESSMENT_ID, 2, 2));
        EXPECTED.put("cutover-assessment-delete", RouteSpec.fixed("DELETE", "/api/v2/cutover/assessments/" + SYNTHETIC_ASSESSMENT_ID, 2, 2));
        EXPECTED.put("cutover-flows", RouteSpec.cutoverFlows("/api/v2/cutover/assessments/" + SYNTHETIC_ASSESSMENT_ID + "/flows"));
        EXPECTED.put("policy-devices", RouteSpec.fixed("GET", "/api/v2/policy/devices", 1, 1));
        EXPECTED.put("policy-rule", RouteSpec.policyQuery("/api/v2/policy/policies/" + SYNTHETIC_POLICY_ID + "/rules", 3, 4,
                "ruleId", "days"));
        EXPECTED.put("policy-show", RouteSpec.policyQuery("/api/v2/policy/policies/" + SYNTHETIC_POLICY_ID, 2, 8,
                "page", "q", "hitFilter", "days", "sort", "order"));
        EXPECTED.put("topology", RouteSpec.fixed("GET", "/api/v2/topology", 1, 1));
        EXPECTED.put("path-query", RouteSpec.fixed("POST", "/api/v2/path-analysis/query", 2, 2));
    }

    @Test
    void everyChecklistCommandDispatchesAndSendsTheExactRouteAndQuery(@TempDir Path work) throws Exception {
        String markdown = readChecklist();
        int checked = runConformance(markdown, work);
        assertTrue(checked >= MIN_COMMANDS,
                "the extractor found only " + checked + " CLI commands; expected at least " + MIN_COMMANDS
                        + " (did the checklist change shape?)");
        System.out.println("CutoverChecklistCliConformanceTest: " + checked + " checklist commands checked");
    }

    @Test
    void checklistCoversEveryRequiredStepAndExcludesApicAndWatchCommands(@TempDir Path work) throws Exception {
        String markdown = readChecklist();
        materialiseSyntheticFiles(work);
        List<ScriptChecklistCliConformanceTest.Command> commands = extractCommands(markdown, work, "http://127.0.0.1:1");

        Set<String> usedCommands = new TreeSet<>();
        Set<String> flowKinds = new LinkedHashSet<>();
        Set<String> flowDecisions = new LinkedHashSet<>();
        boolean sawIpv4Create = false;
        boolean sawIpv6RefusalProbe = false;

        for (var command : commands) {
            usedCommands.add(command.name());
            if ("cutover-flows".equals(command.name())) {
                String kind = command.args().size() > 2 ? command.args().get(2) : "FLOW";
                flowKinds.add(kind);
                if (command.args().size() > 3 && !"-".equals(command.args().get(3))) {
                    flowDecisions.add(command.args().get(3));
                }
            }
            if ("cutover-segment-create".equals(command.name()) && command.args().size() >= 4) {
                String blocks = command.args().get(2);
                if (blocks.contains("192.0.2.0/24")) sawIpv4Create = true;
                if (blocks.contains("2001:db8::/32")) sawIpv6RefusalProbe = true;
            }
        }

        Set<String> required = Set.of(
                "login", "logout", "session",
                "cutover-segments", "cutover-segment", "cutover-segment-create",
                "cutover-assess", "cutover-assessment", "cutover-flows",
                "cutover-assessment-delete", "cutover-segment-delete",
                "policy-devices", "policy-rule", "policy-show",
                "topology", "path-query");
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(usedCommands);
        assertTrue(missing.isEmpty(), "checklist is missing required CLI commands: " + missing);

        assertEquals(Set.of("FLOW", "MISSING_RULE"), flowKinds, "checklist must query both FLOW and MISSING_RULE kinds");
        assertEquals(Set.of("WOULD_DROP", "NOT_DECIDED", "NOT_EVALUABLE", "WOULD_ALLOW"), flowDecisions,
                "checklist must query every CutoverAssessment decision class");
        assertTrue(sawIpv4Create, "checklist must create a segment with IPv4 CIDR blocks");
        assertTrue(sawIpv6RefusalProbe, "checklist must include the observed-refusal IPv6 segment probe");

        // ACI-9..15 (APIC and watch view) are out of scope and must not appear as CLI commands.
        for (String name : usedCommands) {
            assertFalse(name.startsWith("aci-") || name.startsWith("apic-") || name.startsWith("cutover-watch"),
                    "APIC / watch-view command is out of step-1 checklist scope: " + name);
        }
    }

    @Test
    void aChecklistLineWithAnUnknownCommandFlagExtraArgOrBadFilterFailsConformance(@TempDir Path work) {
        String unknownCommand = "Run `adm cutover-watch \"$SEGMENT_ID\"` to open a watch.\n";
        assertThrows(AssertionError.class, () -> runConformance(unknownCommand, work),
                "an unknown CLI command in a checklist line must fail conformance");

        String unknownFlag = "Run `aiv cutover-assessment \"$ASSESSMENT_ID\" --flows-expired` to check retention.\n";
        assertThrows(AssertionError.class, () -> runConformance(unknownFlag, work),
                "a non-existent flag on cutover-assessment must fail conformance");

        String extraPositional = "Run `adm cutover-segments extra` to list segments.\n";
        assertThrows(AssertionError.class, () -> runConformance(extraPositional, work),
                "extra arguments that CutoverCli would otherwise ignore must fail conformance");

        String invalidFlowKind = "Run `aiv cutover-flows \"$ASSESSMENT_ID\" EVERYTHING - 0` to list rows.\n";
        assertThrows(AssertionError.class, () -> runConformance(invalidFlowKind, work),
                "an invalid cutover-flows kind must fail conformance");

        String invalidFlowDecision = "Run `aiv cutover-flows \"$ASSESSMENT_ID\" FLOW SAFE 0` to list rows.\n";
        assertThrows(AssertionError.class, () -> runConformance(invalidFlowDecision, work),
                "an invalid cutover-flows decision must fail conformance");

        String bareUnprefixed = "Run `cutover-assess <segmentId> <spec.json> <file.csv>` directly.\n";
        assertThrows(AssertionError.class, () -> runConformance(bareUnprefixed, work),
                "an unprefixed cutover-* command with arguments must fail so every CLI line goes through adm/aiv/nexus-cli");
    }

    // ---- conformance runner ----------------------------------------------------------------------------------

    private static int runConformance(String markdown, Path work) throws Exception {
        materialiseSyntheticFiles(work);
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

                // Positional CLI commands in CutoverCli, PolicyCli and PathTopologyCli take no option flags.
                for (int i = 1; i < command.args().size(); i++) {
                    String arg = command.args().get(i);
                    assertFalse(arg.startsWith("--") || (arg.startsWith("-") && arg.length() > 1),
                            "unexpected option flag (" + arg + ") in positional command [" + command.source() + "]");
                }
                assertTrue(command.args().size() >= spec.minArgs() && command.args().size() <= spec.maxArgs(),
                        "argument count " + command.args().size() + " outside [" + spec.minArgs() + ".." + spec.maxArgs()
                                + "] for [" + command.source() + "] args=" + command.args());

                Path home = "aiv".equals(command.helper()) ? aiviewHome : adminHome;
                NexusSessionCli cli = new NexusSessionCli(home);
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

                if ("cutover-segment-create".equals(command.name())) {
                    assertTrue(request.body().contains("\"name\":") && request.body().contains("\"blocks\":")
                                    && request.body().contains("\"peerBlocks\":"),
                            "cutover-segment-create must send segment JSON " + where + " body=" + request.body());
                }
                if ("cutover-assess".equals(command.name())) {
                    assertTrue(request.contentType() != null && request.contentType().startsWith("multipart/form-data; boundary="),
                            "cutover-assess must send multipart/form-data " + where);
                    int specPart = request.body().indexOf("name=\"spec\"");
                    int filePart = request.body().indexOf("filename=\"export.csv\"");
                    assertTrue(specPart >= 0 && filePart > specPart,
                            "cutover-assess must send spec part before CSV file part " + where);
                }
                if ("path-query".equals(command.name())) {
                    assertTrue(request.body().contains("\"src\":\"192.0.2.8\"") && request.body().contains("\"port\":443"),
                            "path-query must send the JSON tuple body from @file " + where + " body=" + request.body());
                }
                checked++;
            }
            return checked;
        } finally {
            server.stop(0);
        }
    }

    private static String expectedQuery(List<String> args, RouteSpec spec) {
        return switch (spec.queryKind()) {
            case NONE -> null;
            case CUTOVER_FLOWS -> {
                String kind = args.size() > 2 ? args.get(2) : "FLOW";
                String decision = args.size() > 3 && !"-".equals(args.get(3)) ? args.get(3) : null;
                String page = args.size() > 4 ? args.get(4) : "0";
                yield "kind=" + kind + (decision == null ? "" : "&decision=" + decision) + "&page=" + page;
            }
            case POLICY_POSITIONAL -> {
                int start = 1 + (spec.hasPathId() ? 1 : 0);
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < spec.queryNames().length && start + i < args.size(); i++) {
                    String value = args.get(start + i);
                    if (!"-".equals(value)) {
                        parts.add(spec.queryNames()[i] + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
                    }
                }
                yield parts.isEmpty() ? null : String.join("&", parts);
            }
        };
    }

    private static void materialiseSyntheticFiles(Path work) throws Exception {
        Files.createDirectories(work);
        Files.writeString(work.resolve("spec.json"),
                "{\"mappings\":[{\"source\":\"src\",\"destination\":\"dst\",\"port\":\"port\",\"protocol\":\"proto\"}]}",
                StandardCharsets.UTF_8);
        Files.writeString(work.resolve("export.csv"),
                "src,dst,port,proto\n192.0.2.8,198.51.100.8,443,tcp\n",
                StandardCharsets.UTF_8);
        Files.writeString(work.resolve("trace-in-segment.json"),
                "{\"src\":\"192.0.2.8\",\"dst\":\"198.51.100.8\",\"protocol\":\"tcp\",\"port\":443}",
                StandardCharsets.UTF_8);
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
            assertFalse(BARE_CUTOVER_CMD.matcher(fragment).find(),
                    "prefix every cutover-* command invocation with adm, aiv, nexus-cli or overwatch-cli so conformance runs it: "
                            + fragment.strip());
            String resolved = resolvePlaceholders(fragment, work, baseUrl);
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

    private static String resolvePlaceholders(String text, Path work, String baseUrl) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).strip().toLowerCase(Locale.ROOT);
            String value;
            if (name.contains("baseurl")) {
                value = baseUrl;
            } else if (name.contains("account")) {
                value = "synthetic-account";
            } else if (name.contains("segname")) {
                value = "SEG-ALPHA";
            } else if (name.contains("ipv4peerblocks") || name.contains("peerblocks")) {
                value = "198.51.100.0/24";
            } else if (name.contains("ipv4blocks") || name.equals("blocks")) {
                value = "192.0.2.0/24";
            } else if (name.contains("spec.json")) {
                value = work.resolve("spec.json").toString();
            } else if (name.contains("file.csv")) {
                value = work.resolve("export.csv").toString();
            } else if (name.contains("assessmentid")) {
                value = SYNTHETIC_ASSESSMENT_ID;
            } else if (name.contains("segmentid")) {
                value = SYNTHETIC_SEGMENT_ID;
            } else if (name.contains("policyid")) {
                value = SYNTHETIC_POLICY_ID;
            } else if (name.contains("ruleid")) {
                value = SYNTHETIC_RULE_ID;
            } else if (name.equals("id") || name.endsWith("id")) {
                value = SYNTHETIC_SEGMENT_ID;
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
                case "SEGMENT_ID" -> SYNTHETIC_SEGMENT_ID;
                case "ASSESSMENT_ID" -> SYNTHETIC_ASSESSMENT_ID;
                case "POLICY_ID" -> SYNTHETIC_POLICY_ID;
                case "RULE_ID" -> SYNTHETIC_RULE_ID;
                default -> name.startsWith("ID_") || name.endsWith("_ID") ? SYNTHETIC_SEGMENT_ID : "synthetic-value";
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
            seen.add(new Seen(
                    exchange.getRequestMethod(),
                    uri.getPath(),
                    uri.getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    body));
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
            return new ScriptChecklistCliConformanceTest.Outcome(
                    exit,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }
}
