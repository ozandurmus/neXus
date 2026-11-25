import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * Sanitized summary of one ui2-test run (tools/delivery/test_runner_pod.sh; docs/design/HOSTA_TEST_NAMESPACE_2026_11_24.md,
 * DRAFT). Runs only in the test pod. Reads the JUnit XML reports of each phase and prints exactly one line:
 *
 *   TESTRUN_SUMMARY {"status":...,"reason":...,"phases":[{"name","status","reason","exit_code","duration_s","tests",
 *                    "failures","errors","skipped","failed":[...]}]}
 *
 * Arguments: one per phase, {@code <name>=<exit code>:<seconds>:<reason>:<report path>[,<report path>...]}; a path is
 * a directory of TEST-*.xml files or one XML file. Failure messages, display names and parameters never leave the pod:
 * a failed test is reported as Class.method (Java) or file > test title (vitest) in a closed character set. A Java
 * failure also carries the simple name of its exception class, "Class.method (AssertionFailedError)": a code identifier,
 * never a message, so a run can tell a timeout, a bind error or a missing file from a failed assertion (2026-11-25).
 * Exit 0 PASS, 1 FAIL, 2 ERROR.
 */
class TestRunSummary {
    static final Set<String> NAMES = Set.of("java", "integration", "vitest");
    static final Set<String> REASONS = Set.of("none", "compile", "offline_miss");
    static final int MAX_FAILED = 40;
    static final String IDENT = "[A-Za-z_$][A-Za-z0-9_$]*";

    public static void main(String[] args) {
        var phases = new ArrayList<String>();
        int worst = 0;
        try {
            if (args.length == 0 || args.length > NAMES.size()) throw new IllegalArgumentException();
            var seen = new HashSet<String>();
            for (String arg : args) {
                var phase = phase(arg);
                if (!seen.add(phase.name)) throw new IllegalArgumentException();
                worst = Math.max(worst, rank(phase.status));
                phases.add(phase.json());
            }
        } catch (Exception invalid) {
            System.out.println("TESTRUN_SUMMARY {\"status\":\"ERROR\",\"reason\":\"invalid_summary_input\",\"phases\":[]}");
            System.exit(2);
        }
        String status = worst == 2 ? "ERROR" : worst == 1 ? "FAIL" : "PASS";
        System.out.println("TESTRUN_SUMMARY {\"status\":\"" + status + "\",\"reason\":\"none\",\"phases\":["
                + String.join(",", phases) + "]}");
        System.exit(worst);
    }

    static int rank(String status) {
        return switch (status) { case "PASS" -> 0; case "FAIL" -> 1; default -> 2; };
    }

    record Phase(String name, String status, String reason, int exitCode, long seconds, long tests, long failures,
                 long errors, long skipped, List<String> failed) {
        String json() {
            var names = new StringJoiner(",", "[", "]");
            failed.forEach(f -> names.add("\"" + f + "\""));
            return "{\"name\":\"" + name + "\",\"status\":\"" + status + "\",\"reason\":\"" + reason
                    + "\",\"exit_code\":" + exitCode + ",\"duration_s\":" + seconds + ",\"tests\":" + tests
                    + ",\"failures\":" + failures + ",\"errors\":" + errors + ",\"skipped\":" + skipped
                    + ",\"failed\":" + names + "}";
        }
    }

    static Phase phase(String arg) throws Exception {
        int eq = arg.indexOf('=');
        String name = arg.substring(0, eq);
        String[] parts = arg.substring(eq + 1).split(":", 4);
        if (!NAMES.contains(name) || parts.length != 4) throw new IllegalArgumentException();
        int exitCode = Integer.parseInt(parts[0]);
        long seconds = Long.parseLong(parts[1]);
        String reason = parts[2];
        if (exitCode < 0 || exitCode > 255 || seconds < 0 || !REASONS.contains(reason)) throw new IllegalArgumentException();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        long tests = 0, failures = 0, errors = 0, skipped = 0;
        var failed = new TreeSet<String>();
        int reports = 0;
        boolean invalid = false;
        for (String location : parts[3].isEmpty() ? new String[0] : parts[3].split(",")) {
            var path = Path.of(location);
            var files = new ArrayList<Path>();
            if (Files.isDirectory(path)) {
                try (var listing = Files.newDirectoryStream(path, "TEST-*.xml")) { listing.forEach(files::add); }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            }
            for (var file : files) {
                try {
                    var document = factory.newDocumentBuilder().parse(file.toFile());
                    reports++;
                    var suites = document.getElementsByTagName("testsuite");
                    for (int i = 0; i < suites.getLength(); i++) {
                        var suite = (Element) suites.item(i);
                        tests += count(suite, "tests");
                        failures += count(suite, "failures");
                        errors += count(suite, "errors");
                        skipped += count(suite, "skipped");
                    }
                    var cases = document.getElementsByTagName("testcase");
                    for (int i = 0; i < cases.getLength(); i++) {
                        var c = (Element) cases.item(i);
                        if (c.getElementsByTagName("failure").getLength() + c.getElementsByTagName("error").getLength() == 0) continue;
                        failed.add(name.equals("vitest") ? vitestName(c) : javaName(c));
                    }
                } catch (Exception unreadable) {
                    invalid = true;
                }
            }
        }
        String status;
        if (invalid) {
            status = "ERROR";
        } else if (reports == 0 || tests == 0) {
            status = exitCode != 0 && reason.equals("compile") ? "FAIL" : "ERROR";
        } else if (exitCode != 0 && reason.equals("offline_miss")) {
            status = "ERROR";
        } else if (failures + errors > 0 || exitCode != 0) {
            status = "FAIL";
        } else if (name.equals("integration") && skipped > 0) {
            // A skipped integration test means the database carrier was not seen (assumeTrue): never a pass.
            status = "FAIL";
        } else {
            status = "PASS";
        }
        return new Phase(name, status, invalid ? "invalid_report" : reason, exitCode, seconds, tests, failures, errors,
                skipped, failed.stream().limit(MAX_FAILED).toList());
    }

    static String javaName(Element c) {
        String cls = c.getAttribute("classname").replaceFirst("^.*\\.", "");
        String method = c.getAttribute("name").replaceFirst("[(\\[].*$", "").strip();
        String name = cls.matches(IDENT) && method.matches(IDENT) ? cls + "." + method : "<unnamed>";
        String type = failureType(c);
        return type == null ? name : name + " (" + type + ")";
    }

    /** Simple class name from the type attribute of the first failure/error child; null unless it is a plain identifier. */
    static String failureType(Element c) {
        for (String tag : new String[] {"failure", "error"}) {
            var found = c.getElementsByTagName(tag);
            if (found.getLength() == 0) continue;
            String simple = ((Element) found.item(0)).getAttribute("type").replaceFirst("^.*[.]", "");
            return simple.length() <= 60 && simple.matches(IDENT) ? simple : null;
        }
        return null;
    }

    static String vitestName(Element c) {
        String file = c.getAttribute("classname");
        String title = c.getAttribute("name").replaceAll("[^A-Za-z0-9 _.,:/>()#+-]", " ").replaceAll("\\s+", " ").strip();
        if (!file.matches("[A-Za-z0-9_./-]{1,120}") || file.contains("..")) file = "<file>";
        String joined = file + " > " + (title.isEmpty() ? "<unnamed>" : title);
        return joined.length() > 200 ? joined.substring(0, 200).strip() : joined;
    }

    static long count(Element e, String attribute) {
        String value = e.getAttribute(attribute);
        if (value.isEmpty()) return 0;
        long n = Long.parseLong(value);
        if (n < 0) throw new IllegalArgumentException();
        return n;
    }
}
