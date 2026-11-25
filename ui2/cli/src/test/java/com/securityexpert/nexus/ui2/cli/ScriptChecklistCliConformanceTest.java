package com.securityexpert.nexus.ui2.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/**
 * Pins the command lines of the Script Execution real-environment checklist
 * ({@code docs/design/SCRIPT_EXECUTION_REAL_ENV_VALIDATION_CHECKLIST_2026_11_23.md}, DRAFT) to the CLI: every
 * {@code adm} / {@code opr} / {@code nexus-cli} command the document tells the operator to run is extracted,
 * its placeholders are replaced with synthetic values, and it is run through {@link NexusSessionCli} against a
 * stub server. A command the CLI does not dispatch, a flag it does not accept, or a request that is not the verb
 * and route the checklist's clause names fails here, not in the Product Owner's session.
 *
 * <p>Automated validation only: it proves the checklist's CLI forms parse and call the documented routes. It runs
 * nothing against a real environment.</p>
 */
class ScriptChecklistCliConformanceTest {

    private static final String CHECKLIST = "docs/design/SCRIPT_EXECUTION_REAL_ENV_VALIDATION_CHECKLIST_2026_11_23.md";
    private static final String CONTRACT = "docs/design/SCRIPT_EXECUTION_CONTRACT.md";
    private static final String SYNTHETIC_ID = "11111111-2222-4333-8444-555555555555";
    private static final String SYNTHETIC_RUN_ID = "run-0001";
    private static final int MIN_COMMANDS = 30;

    private static final Pattern COMMAND_START = Pattern.compile("(?<![\\w$./-])(adm|opr|nexus-cli)(?=\\s)");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([^<>\\n]{1,40})>");
    private static final Pattern SHELL_VARIABLE = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?");
    private static final Pattern INLINE_SPAN = Pattern.compile("`([^`\\n]+)`");

    /** One command of the checklist: the helper that runs it and the CLI arguments that follow. */
    record Command(String helper, List<String> args, String source) {
        String name() {
            return args.get(0);
        }
    }

    record Expect(String method, Pattern path) {
        static Expect of(String method, String pathRegex) {
            return new Expect(method, Pattern.compile(pathRegex));
        }
    }

    record Seen(String method, String path, String body) {
    }

    record Outcome(int exit, String out, String err) {
    }

    /** Command name to the verb and the route the checklist's clause names (small and explicit on purpose). */
    private static final Map<String, Expect> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("script-list", Expect.of("GET", "/api/v2/scripts(/[^/]+)?"));
        EXPECTED.put("script-get", Expect.of("GET", "/api/v2/scripts/[^/]+"));
        EXPECTED.put("script-add", Expect.of("POST", "/api/v2/scripts"));
        EXPECTED.put("script-version-add", Expect.of("POST", "/api/v2/scripts/[^/]+/versions"));
        EXPECTED.put("script-set", Expect.of("PATCH", "/api/v2/scripts/[^/]+"));
        EXPECTED.put("script-enable", Expect.of("POST", "/api/v2/scripts/[^/]+/enable"));
        EXPECTED.put("script-disable", Expect.of("POST", "/api/v2/scripts/[^/]+/disable"));
        EXPECTED.put("script-run", Expect.of("POST", "/api/v2/scripts/[^/]+/run"));
        EXPECTED.put("script-runs", Expect.of("GET", "/api/v2/scripts/[^/]+/runs"));
        EXPECTED.put("script-output", Expect.of("GET", "/api/v2/scripts/runs/[^/]+/output"));
        EXPECTED.put("notifications", Expect.of("GET", "/api/v2/config/notifications"));
        EXPECTED.put("notifications-test", Expect.of("POST", "/api/v2/config/notifications/test-(syslog|mail)"));
        EXPECTED.put("logout", Expect.of("POST", "/session/logout"));
        EXPECTED.put("login", Expect.of("POST", "/login"));
        EXPECTED.put("api", Expect.of("GET", "/sessions"));
    }

    // ---- the tests -------------------------------------------------------------------------------------------

    @Test
    void everyChecklistCommandIsDispatchedAndCallsTheRouteItsClauseNames(@TempDir Path work) throws Exception {
        List<Command> commands = extractCommands(readChecklist(), work, "http://127.0.0.1:1");
        assertTrue(commands.size() >= MIN_COMMANDS,
                "the extractor found only " + commands.size() + " adm/opr commands; the checklist has more than "
                        + MIN_COMMANDS + " (did the document change shape?)");

        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = serve(seen);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            // Re-extract now that the stub's address is known, so <baseUrl> resolves to it.
            commands = extractCommands(readChecklist(), work, baseUrl);
            Path adminHome = Files.createDirectories(work.resolve("admin"));
            Path operatorHome = Files.createDirectories(work.resolve("operator"));

            int checked = 0;
            for (Command command : commands) {
                Expect expect = EXPECTED.get(command.name());
                assertNotNull(expect, "checklist command not in the conformance map (add it with its verb and route): "
                        + command.source());
                materialiseScriptFiles(command, work);

                Path home = "opr".equals(command.helper()) ? operatorHome : adminHome;
                NexusSessionCli cli = new NexusSessionCli(home);
                cli.saveSession(new NexusSessionCli.Session(baseUrl, "opaque-cookie", "csrf-token-1"));
                seen.clear();

                Outcome outcome = capture(cli, command.args().toArray(new String[0]));
                String where = "[" + command.source() + "] -> exit " + outcome.exit() + ", stderr: " + outcome.err().strip();

                if ("login".equals(command.name())) {
                    // The password comes from NEXUS_CLI_PASSWORD or a console, never from the line, so a test run
                    // reaches LOGIN_REFUSED: that proves the arguments parsed and the command was dispatched.
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
                assertEquals(expect.method(), request.method(), "wrong verb " + where + " path=" + request.path());
                assertTrue(expect.path().matcher(request.path()).matches(),
                        "wrong route " + where + " " + request.method() + " " + request.path()
                                + " (expected " + expect.method() + " " + expect.path().pattern() + ")");
                if ("api".equals(command.name())) {
                    // `api <VERB> <path>` is verbatim: the line itself names the call.
                    assertEquals(command.args().get(1).toUpperCase(java.util.Locale.ROOT), request.method(), where);
                    assertEquals(command.args().get(2), request.path(), where);
                }
                if ("script-add".equals(command.name()) || "script-version-add".equals(command.name())) {
                    assertTrue(request.body().contains("\"file_name\":") && request.body().contains("\"content_base64\":"),
                            "an upload carries the file " + where);
                }
                if ("script-set".equals(command.name())) {
                    assertTrue(request.body().startsWith("{\"") && request.body().endsWith("}"),
                            "script-set sends at least one field " + where + " body=" + request.body());
                }
                checked++;
            }
            assertEquals(commands.size(), checked);
            System.out.println("ScriptChecklistCliConformanceTest: " + checked + " checklist commands checked");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void checklistCommandNamesAreCliCommandsAndEveryContractScriptCommandIsUsed(@TempDir Path work) throws Exception {
        List<Command> commands = extractCommands(readChecklist(), work, "http://127.0.0.1:1");
        Set<String> used = new TreeSet<>();
        for (Command command : commands) {
            used.add(command.name());
        }

        // Subset: every name used in the checklist is a command the CLI dispatches. An unknown name makes
        // NexusSessionCli.run return -1 (the entry point turns that into "unknown command"); a known one never does,
        // whatever it then does with missing arguments. The first test runs each line in full.
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = serve(seen);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            NexusSessionCli cli = new NexusSessionCli(Files.createDirectories(work.resolve("probe")));
            cli.saveSession(new NexusSessionCli.Session(baseUrl, "opaque-cookie", "csrf-token-1"));
            for (String name : used) {
                cli.saveSession(new NexusSessionCli.Session(baseUrl, "opaque-cookie", "csrf-token-1"));
                int exit = capture(cli, new String[] {name}).exit();
                assertTrue(exit != -1, "the checklist uses '" + name + "' but the CLI does not dispatch it");
            }
            assertEquals(-1, capture(cli, new String[] {"zz-no-such-command"}).exit(), "the dispatch probe cannot tell unknown names");
        } finally {
            server.stop(0);
        }

        // Every `script-*` command the contract's section 3.6 names is in the checklist at least once.
        Set<String> contractNames = contractScriptCommands();
        assertFalse(contractNames.isEmpty(), "section 3.6 of the contract names no script-* command (did it move?)");
        Set<String> missing = new LinkedHashSet<>(contractNames);
        missing.removeAll(used);
        assertTrue(missing.isEmpty(), "the contract (3.6) names these commands but the checklist never runs them: " + missing);
    }

    // ---- extraction ------------------------------------------------------------------------------------------

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

    private static Set<String> contractScriptCommands() throws Exception {
        String text = Files.readString(locate(CONTRACT), StandardCharsets.UTF_8);
        int from = text.indexOf("### 3.6");
        int to = text.indexOf("### 3.7");
        assertTrue(from >= 0 && to > from, "contract section 3.6 not found");
        Matcher m = Pattern.compile("(?<![\\w-])script(?:-[a-z]+)+").matcher(text.substring(from, to));
        Set<String> names = new LinkedHashSet<>();
        while (m.find()) {
            names.add(m.group());
        }
        return names;
    }

    /** Inline code spans and fenced lines (heredoc bodies and comment lines skipped) that hold an adm/opr/nexus-cli command. */
    static List<Command> extractCommands(String markdown, Path work, String baseUrl) {
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

        List<Command> commands = new ArrayList<>();
        for (String fragment : fragments) {
            String resolved = resolvePlaceholders(fragment, baseUrl);
            Matcher start = COMMAND_START.matcher(resolved);
            while (start.find()) {
                List<String> words = words(resolved, start.end());
                words = new ArrayList<>(words.stream().map(w -> expandVariables(w, work)).toList());
                if (words.isEmpty() || words.get(0).equals("$@") || words.get(0).equals("\"$@\"")) {
                    continue; // the adm()/opr() helper definitions forward "$@"
                }
                commands.add(new Command(start.group(1), words, fragment.strip()));
            }
        }
        return commands;
    }

    private static String resolvePlaceholders(String text, String baseUrl) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).strip().toLowerCase(java.util.Locale.ROOT);
            String value;
            if (name.contains("baseurl")) {
                value = baseUrl;
            } else if (name.contains("account")) {
                value = "synthetic-account";
            } else if (name.contains("runid")) {
                value = SYNTHETIC_RUN_ID;
            } else if (name.contains("cred")) {
                value = "cred-ref-1";
            } else if (name.equals("min")) {
                value = "5";
            } else if (name.equals("hour")) {
                value = "7";
            } else if (name.equals("mode")) {
                value = "syslog";
            } else if (name.equals("id") || name.endsWith("id")) {
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
                default -> name.startsWith("ID_") || name.endsWith("_ID") ? SYNTHETIC_ID : "synthetic-value";
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Shell-like split of {@code text} from {@code from} up to the first unquoted control operator or comment. */
    static List<String> words(String text, int from) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < text.length()) {
                    current.append(text.charAt(++i));
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inWord) {
                    if (current.toString().equals("then") && !words.isEmpty()) {
                        return words;
                    }
                    words.add(current.toString());
                    current.setLength(0);
                    inWord = false;
                }
                continue;
            }
            boolean operator = c == '|' || c == ';' || c == '&' || c == '>' || c == '<' || c == '(' || c == ')'
                    || (c == '#' && !inWord);
            if (operator) {
                break;
            }
            current.append(c);
            inWord = true;
        }
        if (inWord && !current.toString().equals("then")) {
            words.add(current.toString());
        }
        return words;
    }

    /** Script files named in a command are created in the scratch directory, as the checklist's setup would. */
    private static void materialiseScriptFiles(Command command, Path work) throws Exception {
        for (String word : command.args()) {
            if (word.startsWith(work.toString()) && Pattern.compile("\\.(sh|py|jar)$").matcher(word).find()) {
                Path file = Path.of(word);
                if (!Files.exists(file)) {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, "#!/bin/sh\necho synthetic\n");
                }
            }
        }
    }

    // ---- stub server and capture -----------------------------------------------------------------------------

    private static HttpServer serve(List<Seen> seen) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static Outcome capture(NexusSessionCli cli, String[] args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = cli.run(args);
            return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }
}
