package com.securityexpert.nexus.ui2.service.advisory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseResult;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseState;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParsedAdvisory;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ProductRule;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.Status;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.VersionRule;

/**
 * The Fortinet source (contract sections 2 and 6, "Fortinet rule"). One parser for both document kinds, told apart by
 * content: the RSS feed names CSAF files for the fetcher and yields nothing here; a CSAF 2.0 file yields one advisory.
 * Only the products {@code FortiOS} and {@code FortiManager} are kept. A range text in a form other than
 * {@code >=A|<=B}, {@code X.Y all versions} or a single version, a status id that cannot be tied to the product tree, or a
 * product tree without ranges makes the advisory {@code NEEDS_REVIEW} (contract rule 5), with a fixed code word as note.
 */
@Component
public class FortinetAdvisoryParser implements AdvisoryParser {

    static final String NOTE_EMPTY_TREE = "EMPTY_PRODUCT_TREE";
    static final String NOTE_RANGE_UNREADABLE = "RANGE_UNREADABLE";
    static final String NOTE_TRAIN_MISMATCH = "RANGE_TRAIN_MISMATCH";
    static final String NOTE_STATUS_UNRESOLVED = "STATUS_ID_UNRESOLVED";
    static final String NOTE_NO_STATUS = "NO_STATUS_FOR_RANGES";
    static final String NOTE_CONFLICT = "CONFLICTING_STATUS";

    private static final Map<String, String> PRODUCTS = Map.of(
            "FortiOS", AdvisoryModel.PRODUCT_FORTIOS,
            "FortiManager", AdvisoryModel.PRODUCT_FORTIMANAGER);

    private static final String VERSION = "\\d{1,3}\\.\\d{1,3}\\.\\d{1,4}";
    private static final Pattern RANGE = Pattern.compile(">=(" + VERSION + ")\\|<=(" + VERSION + ")");
    private static final Pattern WHOLE_TRAIN = Pattern.compile("(\\d{1,3}\\.\\d{1,3}) all versions");
    private static final Pattern SINGLE = Pattern.compile(VERSION);
    private static final Pattern PRODUCT_VERSION_ID = Pattern.compile("(FortiOS|FortiManager)[ -]+(" + VERSION + ")");
    private static final Pattern FIX_LINE = Pattern.compile("(FortiOS|FortiManager) (\\d{1,3}\\.\\d{1,3}): (.+)");

    @Override
    public String source() {
        return AdvisoryModel.SOURCE_FORTINET;
    }

    @Override
    public ParseResult parse(String url, byte[] document) {
        try {
            if (CsafSupport.startsWith(document, '<')) {
                // The RSS feed: only the fetcher reads it (the csaf:link items); nothing is parsed, nothing is stored.
                return ParseResult.empty();
            }
            JsonNode root = CsafSupport.readTree(document);
            String id = root == null ? null : CsafSupport.trackingId(root);
            if (id == null) {
                return new ParseResult(List.of(), List.of(), 1);
            }
            return new ParseResult(List.of(csaf(root, id)), List.of(), 0);
        } catch (Exception | StackOverflowError e) {
            return new ParseResult(List.of(), List.of(), 1);
        }
    }

    private static ParsedAdvisory csaf(JsonNode root, String id) {
        JsonNode vulns = root.path("vulnerabilities");
        JsonNode tracking = root.path("document").path("tracking");
        BigDecimal score = CsafSupport.highestScore(vulns);
        Outcome outcome = rules(root, vulns);
        return new ParsedAdvisory("fortinet:" + id, AdvisoryModel.SOURCE_FORTINET,
                CsafSupport.plain(CsafSupport.text(root.path("document").path("title")), CsafSupport.MAX_TITLE),
                CsafSupport.severity(score), score, CsafSupport.cveIds(vulns),
                CsafSupport.date(CsafSupport.text(tracking.path("initial_release_date"))),
                CsafSupport.date(CsafSupport.text(tracking.path("current_release_date"))),
                "https://fortiguard.fortinet.com/psirt/" + id,
                outcome.note == null ? ParseState.PARSED : ParseState.NEEDS_REVIEW, outcome.note, outcome.rules);
    }

    private record Outcome(String note, List<ProductRule> rules) {
        static Outcome review(String note, Set<String> productKeys) {
            List<ProductRule> named = new ArrayList<>();
            for (String key : new TreeSet<>(productKeys)) {
                named.add(new ProductRule(key, AdvisoryModel.ALL_TRAINS, List.of(), null));
            }
            return new Outcome(note, named);
        }
    }

    /** A readable range text: kind R (range), W (whole train) or X (one version). */
    private record Shape(char kind, String from, String to) {
        String train() {
            return kind == 'W' ? from : trainOf(from);
        }
    }

    private record Leaf(String product, Shape shape) {
    }

    private static String trainOf(String version) {
        int first = version.indexOf('.');
        int second = version.indexOf('.', first + 1);
        return version.substring(0, second);
    }

    private static Outcome rules(JsonNode root, JsonNode vulns) {
        Set<String> named = new LinkedHashSet<>();
        Set<String> allProductNames = new HashSet<>();
        Map<String, Leaf> byKey = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        String[] unreadable = new String[1];
        for (JsonNode top : root.path("product_tree").path("branches")) {
            walk(top, null, named, allProductNames, byKey, ambiguous, unreadable);
        }
        if (named.isEmpty()) {
            // No product branch at all is an empty tree (review); products that are not ours leave no rules (parsed).
            return allProductNames.isEmpty() ? Outcome.review(NOTE_EMPTY_TREE, Set.of()) : new Outcome(null, List.of());
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String name : named) {
            keys.add(PRODUCTS.get(name));
        }
        if (unreadable[0] != null) {
            return Outcome.review(unreadable[0], keys);
        }
        // product -> train -> shape -> statuses
        Map<String, Map<String, Map<Shape, Set<Status>>>> found = new LinkedHashMap<>();
        for (JsonNode vuln : vulns.isArray() ? vulns : List.<JsonNode>of()) {
            JsonNode status = vuln.path("product_status");
            for (Object[] list : new Object[][] {{"known_affected", Status.AFFECTED}, {"known_not_affected", Status.UNAFFECTED}}) {
                for (JsonNode idNode : status.path((String) list[0])) {
                    String raw = CsafSupport.text(idNode);
                    if (raw == null) {
                        return Outcome.review(NOTE_STATUS_UNRESOLVED, keys);
                    }
                    String norm = normalise(raw);
                    Leaf leaf = byKey.get(norm);
                    if (leaf == null && !ambiguous.contains(norm)) {
                        Matcher single = PRODUCT_VERSION_ID.matcher(norm);
                        if (single.matches() && named.contains(single.group(1))) {
                            leaf = new Leaf(single.group(1), new Shape('X', single.group(2), null));
                        }
                    }
                    if (leaf == null) {
                        if (ambiguous.contains(norm) || refersToKept(norm, named, allProductNames)) {
                            return Outcome.review(NOTE_STATUS_UNRESOLVED, keys);
                        }
                        continue;
                    }
                    found.computeIfAbsent(PRODUCTS.get(leaf.product), k -> new LinkedHashMap<>())
                            .computeIfAbsent(leaf.shape.train(), k -> new LinkedHashMap<>())
                            .computeIfAbsent(leaf.shape, k -> new LinkedHashSet<>()).add((Status) list[1]);
                }
            }
        }
        if (found.isEmpty()) {
            return Outcome.review(NOTE_NO_STATUS, keys);
        }
        Map<String, Map<String, String>> fixes = fixes(root);
        List<ProductRule> out = new ArrayList<>();
        for (var product : found.entrySet()) {
            for (var train : product.getValue().entrySet()) {
                List<VersionRule> rules = new ArrayList<>();
                Map<Status, Set<String>> exact = new LinkedHashMap<>();
                for (Status status : new Status[] {Status.AFFECTED, Status.UNAFFECTED}) {
                    for (var shape : train.getValue().entrySet()) {
                        Set<Status> statuses = shape.getValue();
                        if (statuses.size() > 1) {
                            return Outcome.review(NOTE_CONFLICT, keys);
                        }
                        if (!statuses.contains(status)) {
                            continue;
                        }
                        Shape s = shape.getKey();
                        switch (s.kind) {
                            case 'R' -> rules.add(new AdvisoryModel.Range(status, s.from, s.to, true, List.of()));
                            case 'W' -> rules.add(new AdvisoryModel.WholeTrain(status));
                            default -> exact.computeIfAbsent(status, k -> new LinkedHashSet<>()).add(s.from);
                        }
                    }
                    if (exact.containsKey(status)) {
                        rules.add(new AdvisoryModel.ExactVersions(status, exact.get(status)));
                    }
                }
                String name = PRODUCTS.entrySet().stream().filter(e -> e.getValue().equals(product.getKey()))
                        .findFirst().orElseThrow().getKey();
                String fix = fixes.getOrDefault(name, Map.of()).get(train.getKey());
                out.add(new ProductRule(product.getKey(), train.getKey(), rules, fix));
            }
        }
        return new Outcome(null, out);
    }

    private static void walk(JsonNode node, String current, Set<String> named, Set<String> allProductNames,
            Map<String, Leaf> byKey, Set<String> ambiguous, String[] unreadable) {
        String category = CsafSupport.text(node.path("category"));
        String name = CsafSupport.text(node.path("name"));
        String product = current;
        if ("product".equals(category) && name != null) {
            String trimmed = name.trim();
            allProductNames.add(trimmed);
            product = PRODUCTS.containsKey(trimmed) ? trimmed : null;
            if (product != null) {
                named.add(product);
            }
        } else if (product != null && node.has("product")) {
            Shape shape = shapeOf(product, name);
            if (shape == null) {
                unreadable[0] = unreadable[0] == null ? NOTE_RANGE_UNREADABLE : unreadable[0];
            } else if (shape.kind == 'R' && !trainOf(shape.from).equals(trainOf(shape.to))) {
                unreadable[0] = NOTE_TRAIN_MISMATCH;
            } else {
                Leaf leaf = new Leaf(product, shape);
                register(byKey, ambiguous, normalise(name), leaf);
                register(byKey, ambiguous, normalise(CsafSupport.text(node.path("product").path("product_id"))), leaf);
            }
        }
        for (JsonNode child : node.path("branches")) {
            walk(child, product, named, allProductNames, byKey, ambiguous, unreadable);
        }
    }

    private static void register(Map<String, Leaf> byKey, Set<String> ambiguous, String key, Leaf leaf) {
        if (key.isEmpty()) {
            return;
        }
        Leaf existing = byKey.get(key);
        if (existing != null && !existing.equals(leaf)) {
            ambiguous.add(key);
        }
        byKey.put(key, leaf);
    }

    /** The range text after the product name and a separator, or null when it is in no known form. */
    private static Shape shapeOf(String product, String branchName) {
        if (branchName == null) {
            return null;
        }
        String name = branchName.trim();
        if (!name.startsWith(product)) {
            return null;
        }
        String rest = name.substring(product.length()).replaceAll("^[\\s/]+", "").replaceAll("\\s+", " ").trim();
        Matcher range = RANGE.matcher(rest);
        if (range.matches()) {
            return new Shape('R', range.group(1), range.group(2));
        }
        Matcher whole = WHOLE_TRAIN.matcher(rest);
        if (whole.matches()) {
            return new Shape('W', whole.group(1), null);
        }
        if (SINGLE.matcher(rest).matches()) {
            return new Shape('X', rest, null);
        }
        return null;
    }

    /** Whitespace collapsed and a {@code /} separator turned into a space; nothing else is changed. */
    static String normalise(String id) {
        return id == null ? "" : id.replaceAll("\\s*/\\s*", " ").replaceAll("\\s+", " ").trim();
    }

    /** True when a status id begins with one of our product names and not with a longer product name of the tree. */
    private static boolean refersToKept(String norm, Set<String> named, Set<String> allProductNames) {
        for (String ours : named) {
            if (norm.equals(ours) || (norm.startsWith(ours) && norm.length() > ours.length()
                    && " /-".indexOf(norm.charAt(ours.length())) >= 0)) {
                return true;
            }
        }
        return false;
    }

    /** product name -> train -> fix text, from the {@code vendor_fix} remediation lines. */
    private static Map<String, Map<String, String>> fixes(JsonNode root) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (JsonNode vuln : root.path("vulnerabilities")) {
            for (JsonNode remediation : vuln.path("remediations")) {
                if (!"vendor_fix".equals(CsafSupport.text(remediation.path("category")))) {
                    continue;
                }
                String details = CsafSupport.text(remediation.path("details"));
                if (details == null) {
                    continue;
                }
                for (String line : details.split("\\R")) {
                    Matcher m = FIX_LINE.matcher(line.trim());
                    if (m.matches() && !m.group(3).trim().equalsIgnoreCase("Not Applicable")) {
                        out.computeIfAbsent(m.group(1), k -> new HashMap<>()).putIfAbsent(m.group(2),
                                CsafSupport.plain(m.group(3), CsafSupport.MAX_FIXED_TEXT));
                    }
                }
            }
        }
        return out;
    }
}
