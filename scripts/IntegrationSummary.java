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
                            names.add("FAILED " + cls + "." + method);
                    }
                }
            }
            if (reports == 0 || tests == 0) throw new IllegalArgumentException();
            boolean pass = args[0].equals("0") && failures == 0 && errors == 0 && skipped == 0;
            System.out.println(pass ? "INTEGRATION: PASS (tests=" + tests + "; skipped=0)"
                : "INTEGRATION: FAIL (tests=" + tests + "; failures=" + failures + "; errors=" + errors + "; skipped=" + skipped + ")");
            names.stream().limit(20).forEach(System.out::println);
            System.exit(pass ? 0 : 1);
        } catch (Exception ignored) {
            System.out.println("INTEGRATION: ERROR (missing or invalid JUnit reports)");
            System.exit(2);
        }
    }
    private static long count(Element e, String name) {
        long n = Long.parseLong(e.getAttribute(name));
        if (n < 0) throw new IllegalArgumentException();
        return n;
    }
}
