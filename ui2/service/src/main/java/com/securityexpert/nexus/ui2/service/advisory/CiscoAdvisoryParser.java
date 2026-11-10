package com.securityexpert.nexus.ui2.service.advisory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseResult;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParseState;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ParsedAdvisory;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ProductRule;

/**
 * The Cisco source (contract sections 2 and 6, "Cisco rule"). Documents: the change list {@code changes.csv} (for the
 * fetcher; yields nothing here) and CSAF 2.0 files. Rules exist only for the product family branch "Cisco Secure Firewall
 * Adaptive Security Appliance (ASA) Software" (or its older name): each release listed under it is affected when its own
 * product id, or the id of a relationship that references it, is in {@code known_affected}. One rule per train, holding the
 * exact dotted releases. A release version that is not of the form {@code 9.18.4} or {@code 9.16.2.14} makes the advisory
 * {@code NEEDS_REVIEW} when that release is affected (contract rule 5).
 */
@Component
public class CiscoAdvisoryParser implements AdvisoryParser {

    static final String NOTE_NO_RELEASE = "ASA_NO_RELEASE_READABLE";
    static final String NOTE_VERSION_UNREADABLE = "ASA_AFFECTED_VERSION_UNREADABLE";

    private static final Set<String> ASA_FAMILIES = Set.of(
            "Cisco Secure Firewall Adaptive Security Appliance (ASA) Software",
            "Cisco Adaptive Security Appliance (ASA) Software");
    private static final Pattern ASA_VERSION = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,4}(\\.\\d{1,4})?");
    private static final Pattern CHANGES_LINE = Pattern.compile("\\s*[^,\\s]+\\.json,\\s*\\d{4}-\\d{2}-\\d{2}T[^,\\s]*\\s*");
    private static final String ADVISORY_BASE = "https://sec.cloudapps.cisco.com/security/center/content/CiscoSecurityAdvisory/";

    @Override
    public String source() {
        return AdvisoryModel.SOURCE_CISCO;
    }

    @Override
    public ParseResult parse(String url, byte[] document) {
        try {
            if (!CsafSupport.startsWith(document, '{')) {
                return isChangeList(document) ? ParseResult.empty() : new ParseResult(List.of(), List.of(), 1);
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

    /** The change list: a first line of the form {@code <path>.json,<timestamp>} (nothing else is checked, nothing stored). */
    private static boolean isChangeList(byte[] document) {
        if (document == null || document.length == 0) {
            return false;
        }
        int end = 0;
        while (end < document.length && end < 1000 && document[end] != '\n') {
            end++;
        }
        return CHANGES_LINE.matcher(new String(document, 0, end, java.nio.charset.StandardCharsets.UTF_8)).matches();
    }

    private static ParsedAdvisory csaf(JsonNode root, String id) {
        JsonNode doc = root.path("document");
        JsonNode vulns = root.path("vulnerabilities");
        JsonNode tracking = doc.path("tracking");
        BigDecimal score = CsafSupport.highestScore(vulns);
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        Outcome outcome = rules(root, vulns);
        return new ParsedAdvisory("cisco:" + lower, AdvisoryModel.SOURCE_CISCO,
                CsafSupport.plain(CsafSupport.text(doc.path("title")), CsafSupport.MAX_TITLE),
                CsafSupport.severity(score), score, CsafSupport.cveIds(vulns),
                CsafSupport.date(CsafSupport.text(tracking.path("initial_release_date"))),
                CsafSupport.date(CsafSupport.text(tracking.path("current_release_date"))),
                selfUrl(doc, id), outcome.note == null ? ParseState.PARSED : ParseState.NEEDS_REVIEW, outcome.note,
                outcome.rules);
    }

    private static String selfUrl(JsonNode doc, String id) {
        for (JsonNode ref : doc.path("references")) {
            if ("self".equals(CsafSupport.text(ref.path("category")))) {
                String url = CsafSupport.httpsUrl(CsafSupport.text(ref.path("url")));
                if (url != null) {
                    return url;
                }
            }
        }
        return ADVISORY_BASE + id;
    }

    private record Outcome(String note, List<ProductRule> rules) {
    }

    private record Release(String id, String version) {
    }

    private static Outcome rules(JsonNode root, JsonNode vulns) {
        JsonNode tree = root.path("product_tree");
        List<Release> releases = new ArrayList<>();
        boolean[] family = new boolean[1];
        for (JsonNode top : tree.path("branches")) {
            walk(top, false, family, releases);
        }
        if (!family[0]) {
            return new Outcome(null, List.of());
        }
        // Maps are built once: affected ids, then the releases referenced by an affected relationship id.
        Set<String> affectedIds = new HashSet<>();
        for (JsonNode vuln : vulns) {
            for (JsonNode idNode : vuln.path("product_status").path("known_affected")) {
                String pid = CsafSupport.text(idNode);
                if (pid != null) {
                    affectedIds.add(pid);
                }
            }
        }
        Set<String> affectedReleaseIds = new HashSet<>();
        for (JsonNode rel : tree.path("relationships")) {
            String combined = CsafSupport.text(rel.path("full_product_name").path("product_id"));
            String reference = CsafSupport.text(rel.path("product_reference"));
            if (combined != null && reference != null && affectedIds.contains(combined)) {
                affectedReleaseIds.add(reference);
            }
        }
        Map<String, Set<String>> byTrain = new TreeMap<>();
        boolean readable = false;
        for (Release release : releases) {
            boolean valid = release.version != null && ASA_VERSION.matcher(release.version).matches();
            readable |= valid;
            if (!affectedIds.contains(release.id) && !affectedReleaseIds.contains(release.id)) {
                continue;
            }
            if (!valid) {
                return review(NOTE_VERSION_UNREADABLE);
            }
            byTrain.computeIfAbsent(trainOf(release.version), k -> new TreeSet<>()).add(release.version);
        }
        if (!readable) {
            return review(NOTE_NO_RELEASE);
        }
        List<ProductRule> out = new ArrayList<>();
        for (var train : byTrain.entrySet()) {
            out.add(new ProductRule(AdvisoryModel.PRODUCT_CISCO_ASA, train.getKey(),
                    List.of(new AdvisoryModel.ExactVersions(AdvisoryModel.Status.AFFECTED, train.getValue())), null));
        }
        return new Outcome(null, out);
    }

    private static Outcome review(String note) {
        return new Outcome(note, List.of(new ProductRule(AdvisoryModel.PRODUCT_CISCO_ASA, AdvisoryModel.ALL_TRAINS, List.of(), null)));
    }

    private static String trainOf(String version) {
        int first = version.indexOf('.');
        int second = version.indexOf('.', first + 1);
        return version.substring(0, second);
    }

    /** Collects every release (a branch with a {@code product}) below an ASA family branch. */
    private static void walk(JsonNode node, boolean inFamily, boolean[] family, List<Release> releases) {
        boolean here = inFamily;
        if (!inFamily && "product_family".equals(CsafSupport.text(node.path("category")))) {
            String name = CsafSupport.text(node.path("name"));
            if (name != null && ASA_FAMILIES.contains(name.trim())) {
                here = true;
                family[0] = true;
            }
        } else if (inFamily && node.has("product")) {
            String pid = CsafSupport.text(node.path("product").path("product_id"));
            String name = CsafSupport.text(node.path("name"));
            if (pid != null) {
                releases.add(new Release(pid, name == null ? null : name.trim()));
            }
        }
        for (JsonNode child : node.path("branches")) {
            walk(child, here, family, releases);
        }
    }
}
