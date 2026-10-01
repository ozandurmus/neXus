import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Sanitized JUnit output for the ephemeral integration runner. */
class IntegrationSummary {
    public static void main(String[] args) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            long tests = 0, failures = 0, errors = 0, skipped = 0;
            var names = new TreeSet<String>();
            int reports = 0;
            try (var files = Files.newDirectoryStream(Path.of(args[1]), "TEST-*.xml")) {
                for (var file : files) {
                    var root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                    reports++;
                    tests += count(root, "tests");
                    failures += count(root, "failures");
                    errors += count(root, "errors");
                    skipped += count(root, "skipped");
                    var cases = root.getElementsByTagName("testcase");
                    for (int i = 0; i < cases.getLength(); i++) {
                        var c = (Element) cases.item(i);
                        if (c.getElementsByTagName("failure").getLength() + c.getElementsByTagName("error").getLength() == 0) continue;
                        String cls = c.getAttribute("classname").replaceFirst("^.*\\.", "");
                        String method = c.getAttribute("name").replaceFirst("\\(.*$", "");
                        // Omit display names/parameters: they can contain diagnostic values.
                        if (cls.matches("[A-Za-z_$][A-Za-z0-9_$]*") && method.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
                            for (String tag : List.of("failure", "error")) {
                                var diagnostics = c.getElementsByTagName(tag);
                                for (int j = 0; j < diagnostics.getLength(); j++) {
                                    var failure = (Element) diagnostics.item(j);
                                    String type = failure.getAttribute("type").replaceFirst("^.*[.$]", "");
                                    if (!type.matches("[A-Za-z_][A-Za-z0-9_]*")) type = "Exception";
                                    names.add("FAILED " + cls + "." + method + " | " + type + ": "
                                            + sanitize(failure.getAttribute("message")));
                                }
                            }
                    }
                }
            }
            if (reports == 0 || tests == 0) throw new IllegalArgumentException();
            boolean pass = args[0].equals("0") && failures == 0 && errors == 0 && skipped == 0;
            System.out.println(pass ? "INTEGRATION: PASS (tests=" + tests + "; skipped=0)"
                : "INTEGRATION: FAIL (tests=" + tests + "; failures=" + failures + "; errors=" + errors + "; skipped=" + skipped + ")");
            names.stream().limit(40).forEach(System.out::println);
            System.exit(pass ? 0 : 1);
        } catch (Exception ignored) {
            System.out.println("INTEGRATION: ERROR (missing or invalid JUnit reports)");
            System.exit(2);
        }
    }
    static String sanitize(String message) {
        String safe = message.split("\\R", 2)[0]
                .replaceAll("(?i)(?<![a-z0-9])[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?![a-z0-9])", "<uuid>")
                // Includes compressed, scoped and IPv4-mapped IPv6, without DNS resolution.
                .replaceAll("(?i)(?<![a-z0-9:])(?:[0-9a-f]*:){2,}[0-9a-f:.]*(?:%[a-z0-9_.-]+)?", "<ip>")
                .replaceAll("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])", "<ip>")
                .replaceAll("(?i)[a-z0-9_-]+(?:\\.[a-z0-9_-]+)+\\.?", "<host>")
                .replaceAll("[0-9]{7,}", "<n>")
                .replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
        return safe.substring(0, Math.min(200, safe.length()));
    }

    private static long count(Element e, String name) {
        long n = Long.parseLong(e.getAttribute(name));
        if (n < 0) throw new IllegalArgumentException();
        return n;
    }
}
