package com.securityexpert.nexus.ui2.worker.pathvalidation;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation;

/**
 * In-memory parsers of the four approved matcher answers (PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md rows 2-5). Each keeps the
 * minimum the comparison needs and nothing else; the caller discards the answer right after (raw-evidence law).
 *
 * <p>Answer shapes are fixture-proven only, not vendor-proven (AGENTS.md "Automated validation != real-environment
 * validation"): anything that is not exactly the recognised shape is empty, which the comparison turns into
 * {@code NOT_EVALUABLE}, never into a MISMATCH. The first PO-chosen sample run is what proves or corrects them.</p>
 */
final class MatcherAnswers {

    private MatcherAnswers() {
    }

    // ---- Check Point: ip -4 route get --------------------------------------------------------------------------------

    /** {@code UNICAST} (a forwarding route), {@code LOCAL} (the gateway itself), {@code DISCARD}, {@code NO_ROUTE}. */
    enum RouteKind { UNICAST, LOCAL, DISCARD, NO_ROUTE }

    record RouteAnswer(RouteKind kind, Optional<String> device, Optional<String> via) {
    }

    private static final Set<String> DISCARD_TYPES = Set.of("blackhole", "unreachable", "prohibit");
    private static final Set<String> OTHER_TYPES = Set.of("broadcast", "throw", "multicast", "anycast", "nat");

    /**
     * The kernel's decision: the first line that starts with the destination or with a route type ({@code vsenv} prints
     * a context banner first). {@code RTNETLINK answers: Network is unreachable} is the kernel's "no route"; every other
     * error text, an unknown type or a line without {@code dev} is empty.
     */
    static Optional<RouteAnswer> routeGet(String output, String destination) {
        if (output == null) {
            return Optional.empty();
        }
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("RTNETLINK answers:")) {
                return line.equals("RTNETLINK answers: Network is unreachable")
                        ? Optional.of(new RouteAnswer(RouteKind.NO_ROUTE, Optional.empty(), Optional.empty()))
                        : Optional.empty();
            }
            String[] tokens = line.split("\\s+");
            if (tokens.length == 0) {
                continue;
            }
            int at = 0;
            RouteKind kind = RouteKind.UNICAST;
            if (DISCARD_TYPES.contains(tokens[0])) {
                kind = RouteKind.DISCARD;
                at = 1;
            } else if ("local".equals(tokens[0])) {
                kind = RouteKind.LOCAL;
                at = 1;
            } else if ("unicast".equals(tokens[0])) {
                at = 1;
            } else if (OTHER_TYPES.contains(tokens[0])) {
                return Optional.empty();
            }
            if (tokens.length <= at || !tokens[at].equals(destination)) {
                continue;
            }
            Optional<String> dev = after(tokens, at + 1, "dev"), via = after(tokens, at + 1, "via");
            if (via.isPresent() && !PathValidation.ipv4(via.get())) {
                return Optional.empty();
            }
            if (kind == RouteKind.DISCARD) {
                return Optional.of(new RouteAnswer(kind, dev, Optional.empty()));
            }
            return dev.isEmpty() ? Optional.empty() : Optional.of(new RouteAnswer(kind, dev, via));
        }
        return Optional.empty();
    }

    private static Optional<String> after(String[] tokens, int from, String keyword) {
        for (int i = from; i + 1 < tokens.length; i++) {
            if (tokens[i].equals(keyword)) {
                return Optional.of(tokens[i + 1]);
            }
        }
        return Optional.empty();
    }

    // ---- Palo Alto: XML API op answers -------------------------------------------------------------------------------

    /** {@code nextHop} is an IPv4 next hop when the answer names one; {@code discard} when {@code <nh>} says so. */
    record FibAnswer(String egressInterface, Optional<String> nextHop, boolean discard) {
    }

    /**
     * {@code <response status="success"><result>...<interface>IF</interface>...</result></response>}, optional
     * {@code <nh>} (next-hop type) and {@code <ip>} (next-hop address). No interface (and no discard type) is empty.
     */
    static Optional<FibAnswer> fibLookup(String xml) {
        Optional<Element> result = successResult(xml);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> nh = childText(result.get(), "nh");
        Optional<String> iface = childText(result.get(), "interface");
        boolean discard = nh.filter(t -> t.equals("discard") || t.equals("drop")).isPresent();
        if (iface.isEmpty() && !discard) {
            return Optional.empty();
        }
        Optional<String> ip = childText(result.get(), "ip").filter(PathValidation::ipv4).filter(v -> !v.equals("0.0.0.0"));
        Optional<String> nextHop = nh.isEmpty() || nh.get().equals("ip") ? ip : Optional.empty();
        return Optional.of(new FibAnswer(iface.orElse(""), nextHop, discard));
    }

    /**
     * The matched rule names of a security- or NAT-policy-match answer, in answer order:
     * {@code <result><rules><entry name="R">...} (or an entry whose text is {@code R; index: N}). An empty
     * {@code <rules/>} is "no rule matched"; an answer without {@code <rules>} is empty (shape unproven).
     */
    static Optional<List<String>> matchedRules(String xml) {
        Optional<Element> result = successResult(xml);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        Optional<Element> rules = child(result.get(), "rules");
        if (rules.isEmpty()) {
            return Optional.empty();
        }
        List<String> names = new ArrayList<>();
        for (Node n = rules.get().getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element entry)) {
                continue;
            }
            if (!entry.getTagName().equals("entry")) {
                return Optional.empty();
            }
            String name = entry.getAttribute("name");
            if (name.isEmpty() && child(entry, null).isEmpty()) {
                String text = entry.getTextContent().strip();
                int semi = text.indexOf(';');
                name = (semi >= 0 ? text.substring(0, semi) : text).strip();
            }
            if (name.isEmpty()) {
                return Optional.empty();
            }
            names.add(name);
        }
        return Optional.of(List.copyOf(names));
    }

    private static Optional<Element> successResult(String xml) {
        Optional<Document> doc = parse(xml);
        if (doc.isEmpty()) {
            return Optional.empty();
        }
        Element root = doc.get().getDocumentElement();
        if (!root.getTagName().equals("response") || !"success".equals(root.getAttribute("status"))) {
            return Optional.empty();
        }
        return child(root, "result");
    }

    private static Optional<Element> child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (tag == null || e.getTagName().equals(tag))) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> childText(Element parent, String tag) {
        return child(parent, tag).map(e -> e.getTextContent().strip()).filter(t -> !t.isEmpty());
    }

    private static Optional<Document> parse(String xml) {
        if (xml == null || xml.isBlank() || xml.length() > PathValidation.OUTPUT_LIMIT_BYTES) {
            return Optional.empty();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            var builder = factory.newDocumentBuilder();
            // The default handler prints parse errors to stderr; nothing of an answer is ever logged.
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }
            });
            return Optional.of(builder.parse(new InputSource(new StringReader(xml))));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }
}
