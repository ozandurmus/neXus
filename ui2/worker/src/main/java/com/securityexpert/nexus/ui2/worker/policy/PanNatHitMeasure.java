package com.securityexpert.nexus.ui2.worker.policy;

import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The sanitized projection of one P-1 answer (Palo Alto NAT rule hit counts, PO approval 2026-10-18, plan section 6): a
 * measurement, not a collection. The exact XML of the NAT hit-count answer is UNKNOWN (plan section 8): the request is the
 * documented security form with the rule base substituted, so the first call is one PO-approved measurement on one firewall
 * and VSYS. Per the raw-evidence law only presence booleans, counts and a capped enumeration of child element names are kept;
 * rule names, hit values and every other text are discarded in memory. The summary is written to the job transcript note,
 * never to a snapshot, and no NAT hit count is joined to a rule until a later decision.
 */
final class PanNatHitMeasure {
    private PanNatHitMeasure() {}
    private static final Pattern FIELD_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    static final int MAX_FIELD_NAMES = 20;

    /**
     * @param natRuleBasePresent an {@code entry} named {@code nat} sits under a {@code rule-base} element (the substitution held)
     * @param ruleCount entries under the first {@code rules} element
     * @param withHitCount of those, entries with a {@code hit-count} child holding digits
     * @param fieldNames the distinct child element names of rule entries, shape-checked, sorted, capped
     */
    record Summary(boolean natRuleBasePresent, int ruleCount, int withHitCount, java.util.List<String> fieldNames) {
        /** The safe telemetry of plan section 6, P-1 field 10 (outcome, ruleCount), plus the measurement booleans. */
        String note() {
            return "PAN_NAT_HIT_MEASURE outcome=MEASURED ruleCount=" + ruleCount + " withHitCount=" + withHitCount
                    + " natRuleBasePresent=" + natRuleBasePresent + " fields=" + fieldNames;
        }
    }

    static Optional<Summary> summarize(Element response) {
        if (response == null) return Optional.empty();
        boolean natBase = false;
        for (Element base : descendants(response, "rule-base"))
            for (Element entry : children(base, "entry")) if ("nat".equals(entry.getAttribute("name"))) natBase = true;
        var rulesElements = descendants(response, "rules");
        int rules = 0, withHits = 0;
        TreeSet<String> names = new TreeSet<>();
        if (!rulesElements.isEmpty()) {
            for (Element rule : children(rulesElements.get(0), "entry")) {
                rules++;
                for (Node child = rule.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (!(child instanceof Element element)) continue;
                    String name = element.getTagName();
                    if (FIELD_NAME.matcher(name).matches() && names.size() < MAX_FIELD_NAMES) names.add(name);
                    if (name.equals("hit-count") && element.getTextContent() != null && element.getTextContent().strip().matches("[0-9]{1,20}"))
                        withHits++;
                }
            }
        }
        return Optional.of(new Summary(natBase, rules, withHits, java.util.List.copyOf(names)));
    }

    private static java.util.List<Element> descendants(Element root, String name) {
        var out = new java.util.ArrayList<Element>();
        NodeList nodes = root.getElementsByTagName(name);
        for (int i = 0; i < nodes.getLength(); i++) out.add((Element) nodes.item(i));
        return out;
    }

    private static java.util.List<Element> children(Element parent, String name) {
        var out = new java.util.ArrayList<Element>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element element && element.getTagName().equals(name)) out.add(element);
        return out;
    }
}
