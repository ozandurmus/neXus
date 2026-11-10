package com.securityexpert.nexus.ui2.service.advisory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parser of the Palo Alto source (contract sections 2 and 6): the document of {@code /api/v1/products/PAN-OS/advisories},
 * {@code {"success":true,"data":[<CVE JSON 5 record>, ...]}}. Rules come only from {@code affected[]} entries whose product is
 * exactly {@code PAN-OS}. Version-entry forms (contract section 6):
 * <ul>
 * <li>{@code "<train> All"}, {@code "<train>.*"} with a status: the whole train; {@code "All"}: the whole product (train
 * {@code *}); {@code "<train> None"} with {@code unaffected}: the whole train is unaffected;</li>
 * <li>{@code version} with {@code lessThan}/{@code lessThanOrEqual} and a status (and optional {@code changes[]}): a range from
 * the version (a bare train means its start) to the bound (a bound {@code <train>*} means the end of the train); change points
 * decide by hotfix inside their maintenance release (see {@link DefaultAdvisoryEvaluator});</li>
 * <li>{@code version} alone, or with only {@code changes[]}: from that version to the end of the train.</li>
 * </ul>
 * Any other form makes the advisory {@code NEEDS_REVIEW} with a fixed code word. The record's own list of affected versions
 * ({@code x_affectedList}) is checked with the same evaluator the service uses (code word {@code SELF_CHECK_FAILED}). A
 * {@code NEEDS_REVIEW} advisory that names PAN-OS always carries at least an empty rule for it, so the evaluator can say
 * {@code NOT_EVALUABLE} instead of a false "not listed".
 */
@Component
public final class PaloAltoAdvisoryParser implements AdvisoryParser {

    static final String NOTE_UNKNOWN_VERSION_FORM = "UNKNOWN_VERSION_FORM";
    static final String NOTE_UNKNOWN_STATUS = "UNKNOWN_STATUS";
    static final String NOTE_UNKNOWN_BOUND_FORM = "UNKNOWN_BOUND_FORM";
    static final String NOTE_CHANGE_POINT_FORM = "CHANGE_POINT_FORM";
    static final String NOTE_RANGE_ACROSS_TRAINS = "RANGE_ACROSS_TRAINS";
    static final String NOTE_NO_VERSIONS = "NO_VERSIONS";
    static final String NOTE_SELF_CHECK_FAILED = "SELF_CHECK_FAILED";

    private static final String VENDOR_HOST_URL = "https://security.paloaltonetworks.com/";
    private static final String PRODUCT = "PAN-OS";
    private static final int MAX_TITLE = 300;
    private static final int MAX_FIXED_TEXT = 200;
    private static final Pattern TRAIN = Pattern.compile("(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})");
    private static final Pattern TRAIN_ALL = Pattern.compile("(\\S+) All");
    private static final Pattern TRAIN_NONE = Pattern.compile("(\\S+) None");
    private static final Pattern TRAIN_STAR = Pattern.compile("(\\S+)\\.\\*");
    private static final Pattern TRAIN_END = Pattern.compile("(\\S+)\\*");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,60}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AdvisoryEvaluator evaluator = new DefaultAdvisoryEvaluator(List.of(new PanOsComparator()));

    @Override
    public String source() {
        return AdvisoryModel.SOURCE_PALOALTO;
    }

    /** Thrown inside one record when a version entry is in a form this parser does not recognise. */
    private static final class Review extends Exception {
        private static final long serialVersionUID = 1L;
        Review(String note) {
            super(note, null, false, false);
        }
    }

    @Override
    public AdvisoryModel.ParseResult parse(String url, byte[] document) {
        JsonNode root;
        try {
            root = document == null ? null : MAPPER.readTree(document);
        } catch (Exception e) {
            return AdvisoryModel.ParseResult.empty();
        }
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) {
            return AdvisoryModel.ParseResult.empty();
        }
        List<AdvisoryModel.ParsedAdvisory> out = new ArrayList<>();
        int rejected = 0;
        for (JsonNode rec : data) {
            AdvisoryModel.ParsedAdvisory a = null;
            try {
                a = parseRecord(rec);
            } catch (RuntimeException e) {
                a = null;
            }
            if (a == null) {
                rejected++;
            } else {
                out.add(a);
            }
        }
        return new AdvisoryModel.ParseResult(out, List.of(), rejected);
    }

    private AdvisoryModel.ParsedAdvisory parseRecord(JsonNode rec) {
        if (rec == null || !rec.isObject()) {
            return null;
        }
        String cveId = text(rec.path("cveMetadata").get("cveId"));
        JsonNode cna = rec.path("containers").path("cna");
        if (cveId == null || !ID.matcher(cveId).matches() || !cna.isObject()) {
            return null;
        }
        String title = cleanTitle(text(cna.get("title")), cveId);
        LocalDate published = date(text(cna.get("datePublic")));
        if (published == null) {
            published = date(text(rec.path("cveMetadata").get("datePublished")));
        }
        LocalDate updated = date(text(rec.path("cveMetadata").get("dateUpdated")));
        if (updated == null) {
            updated = published;
        }
        List<String> cveIds = cveId.startsWith("CVE-") ? List.of(cveId) : List.of();

        Map<String, List<AdvisoryModel.VersionRule>> byTrain = new LinkedHashMap<>();
        Map<String, Set<String>> fixPoints = new LinkedHashMap<>();
        String note = null;
        boolean namesPanOs = false;
        JsonNode affected = cna.get("affected");
        if (affected != null && affected.isArray()) {
            for (JsonNode entry : affected) {
                if (!PRODUCT.equals(text(entry.get("product")))) {
                    continue;
                }
                namesPanOs = true;
                JsonNode versions = entry.get("versions");
                if (versions == null || !versions.isArray() || versions.isEmpty()) {
                    note = note == null ? NOTE_NO_VERSIONS : note;
                    continue;
                }
                for (JsonNode v : versions) {
                    try {
                        addVersionEntry(v, byTrain, fixPoints);
                    } catch (Review r) {
                        note = note == null ? r.getMessage() : note;
                    }
                }
            }
        }
        List<AdvisoryModel.ProductRule> rules = new ArrayList<>();
        for (Map.Entry<String, List<AdvisoryModel.VersionRule>> e : byTrain.entrySet()) {
            rules.add(new AdvisoryModel.ProductRule(AdvisoryModel.PRODUCT_PAN_OS, e.getKey(), e.getValue(),
                    fixedText(fixPoints.get(e.getKey()))));
        }
        if (note == null && !rules.isEmpty() && !selfCheck(cna, rules)) {
            note = NOTE_SELF_CHECK_FAILED;
        }
        if (note != null && rules.isEmpty()) {
            rules.add(new AdvisoryModel.ProductRule(AdvisoryModel.PRODUCT_PAN_OS, AdvisoryModel.ALL_TRAINS, List.of(), null));
        }
        if (namesPanOs && rules.isEmpty()) {
            return null;
        }
        AdvisoryModel.ParseState state = note == null ? AdvisoryModel.ParseState.PARSED : AdvisoryModel.ParseState.NEEDS_REVIEW;
        BigDecimal[] score = new BigDecimal[1];
        AdvisoryModel.Severity severity = severity(cna.get("metrics"), score);
        return new AdvisoryModel.ParsedAdvisory("paloalto:" + cveId, AdvisoryModel.SOURCE_PALOALTO, title, severity, score[0],
                cveIds, published, updated, VENDOR_HOST_URL + cveId, state, note, rules);
    }

    // ---- version entries ------------------------------------------------------------------------------------------

    private static void addVersionEntry(JsonNode v, Map<String, List<AdvisoryModel.VersionRule>> byTrain,
            Map<String, Set<String>> fixPoints) throws Review {
        if (v == null || !v.isObject()) {
            throw new Review(NOTE_UNKNOWN_VERSION_FORM);
        }
        AdvisoryModel.Status status = status(text(v.get("status")));
        String version = text(v.get("version"));
        if (version == null) {
            throw new Review(NOTE_UNKNOWN_VERSION_FORM);
        }
        version = version.trim();
        boolean hasBound = v.has("lessThan") || v.has("lessThanOrEqual");
        boolean hasChanges = v.has("changes");
        Matcher m;
        if (version.equals("All") || (m = TRAIN_ALL.matcher(version)).matches() || (m = TRAIN_STAR.matcher(version)).matches()
                || (m = TRAIN_NONE.matcher(version)).matches()) {
            if (hasBound || hasChanges) {
                throw new Review(NOTE_UNKNOWN_VERSION_FORM);
            }
            boolean none = version.endsWith(" None");
            String train = version.equals("All") ? AdvisoryModel.ALL_TRAINS : version.replaceFirst("( All| None|\\.\\*)$", "");
            if (!train.equals(AdvisoryModel.ALL_TRAINS) && !TRAIN.matcher(train).matches()) {
                throw new Review(NOTE_UNKNOWN_VERSION_FORM);
            }
            if (none && status != AdvisoryModel.Status.UNAFFECTED) {
                throw new Review(NOTE_UNKNOWN_VERSION_FORM);
            }
            byTrain.computeIfAbsent(train, k -> new ArrayList<>()).add(new AdvisoryModel.WholeTrain(status));
            return;
        }
        String from;
        String train;
        if (TRAIN.matcher(version).matches()) {
            from = null;
            train = version;
        } else {
            PanOsVersion pv = PanOsVersion.parse(version).orElseThrow(() -> new Review(NOTE_UNKNOWN_VERSION_FORM));
            from = version;
            train = pv.train();
        }
        if (v.has("lessThan") && v.has("lessThanOrEqual")) {
            throw new Review(NOTE_UNKNOWN_BOUND_FORM);
        }
        String to = null;
        boolean inclusive = v.has("lessThanOrEqual");
        if (hasBound) {
            String bound = trimmed(text(v.get(inclusive ? "lessThanOrEqual" : "lessThan")));
            if (bound == null) {
                throw new Review(NOTE_UNKNOWN_BOUND_FORM);
            }
            Matcher end = TRAIN_END.matcher(bound);
            if (end.matches() && !inclusive) {
                if (!end.group(1).equals(train)) {
                    throw new Review(NOTE_RANGE_ACROSS_TRAINS);
                }
            } else {
                PanOsVersion bv = PanOsVersion.parse(bound).orElseThrow(() -> new Review(NOTE_UNKNOWN_BOUND_FORM));
                if (!bv.train().equals(train)) {
                    throw new Review(NOTE_RANGE_ACROSS_TRAINS);
                }
                to = bound;
            }
        }
        List<AdvisoryModel.ChangePoint> changes = new ArrayList<>();
        JsonNode ch = v.get("changes");
        if (ch != null) {
            if (!ch.isArray()) {
                throw new Review(NOTE_CHANGE_POINT_FORM);
            }
            for (JsonNode c : ch) {
                String at = c == null ? null : trimmed(text(c.get("at")));
                PanOsVersion pv = PanOsVersion.parse(at).orElseThrow(() -> new Review(NOTE_CHANGE_POINT_FORM));
                if (!pv.train().equals(train)) {
                    throw new Review(NOTE_RANGE_ACROSS_TRAINS);
                }
                AdvisoryModel.Status cs = status(text(c.get("status")));
                changes.add(new AdvisoryModel.ChangePoint(at, cs));
                if (cs == AdvisoryModel.Status.UNAFFECTED) {
                    fixPoints.computeIfAbsent(train, k -> new LinkedHashSet<>()).add(at);
                }
            }
        }
        byTrain.computeIfAbsent(train, k -> new ArrayList<>())
                .add(new AdvisoryModel.Range(status, from, to, inclusive && to != null, changes));
    }

    private static AdvisoryModel.Status status(String s) throws Review {
        if ("affected".equals(s)) {
            return AdvisoryModel.Status.AFFECTED;
        }
        if ("unaffected".equals(s)) {
            return AdvisoryModel.Status.UNAFFECTED;
        }
        throw new Review(NOTE_UNKNOWN_STATUS);
    }

    private static String fixedText(Set<String> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        List<String> sorted = new ArrayList<>(points);
        Collections.sort(sorted, (a, b) -> PanOsVersion.parse(a).orElseThrow().compareTo(PanOsVersion.parse(b).orElseThrow()));
        String joined = String.join(", ", sorted);
        return joined.length() <= MAX_FIXED_TEXT ? joined : joined.substring(0, MAX_FIXED_TEXT);
    }

    // ---- self-check -----------------------------------------------------------------------------------------------

    /**
     * The vendor's own list of affected versions must all evaluate AFFECTED; every unaffected change point must not.
     * Entries of the list that are not full PAN-OS versions (a bare train, another product) are not checked.
     */
    private boolean selfCheck(JsonNode cna, List<AdvisoryModel.ProductRule> rules) {
        for (AdvisoryModel.ProductRule pr : rules) {
            for (AdvisoryModel.VersionRule r : pr.rules()) {
                if (r instanceof AdvisoryModel.Range range) {
                    for (AdvisoryModel.ChangePoint cp : range.changes()) {
                        if (cp.status() == AdvisoryModel.Status.UNAFFECTED && isAffected(cp.at(), rules)) {
                            return false;
                        }
                    }
                }
            }
        }
        JsonNode list = cna.get("x_affectedList");
        if (list != null && list.isArray()) {
            for (JsonNode item : list) {
                String s = text(item);
                if (s == null || !s.startsWith("PAN-OS ")) {
                    continue;
                }
                String version = s.substring("PAN-OS ".length());
                if (PanOsVersion.parse(version).isPresent() && !isAffected(version, rules)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isAffected(String version, List<AdvisoryModel.ProductRule> rules) {
        return evaluator.evaluate(AdvisoryModel.PRODUCT_PAN_OS, version, AdvisoryModel.ParseState.PARSED, rules).kind()
                == AdvisoryModel.VerdictKind.AFFECTED;
    }

    // ---- scalar fields --------------------------------------------------------------------------------------------

    private static AdvisoryModel.Severity severity(JsonNode metrics, BigDecimal[] scoreOut) {
        if (metrics == null || !metrics.isArray()) {
            return AdvisoryModel.Severity.UNKNOWN;
        }
        for (String key : new String[] {"cvssV4_0", "cvssV3_1", "cvssV3_0"}) {
            BigDecimal bestScore = null;
            AdvisoryModel.Severity bestSeverity = null;
            for (JsonNode m : metrics) {
                JsonNode c = m == null ? null : m.get(key);
                if (c == null || !c.isObject() || !c.path("baseScore").isNumber()) {
                    continue;
                }
                BigDecimal score = c.get("baseScore").decimalValue();
                AdvisoryModel.Severity sev = severityOf(text(c.get("baseSeverity")));
                if (bestScore == null || score.compareTo(bestScore) > 0) {
                    bestScore = score;
                    bestSeverity = sev;
                }
            }
            if (bestScore != null) {
                scoreOut[0] = bestScore;
                return bestSeverity;
            }
        }
        return AdvisoryModel.Severity.UNKNOWN;
    }

    private static AdvisoryModel.Severity severityOf(String s) {
        if (s == null) {
            return AdvisoryModel.Severity.UNKNOWN;
        }
        try {
            return AdvisoryModel.Severity.valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AdvisoryModel.Severity.UNKNOWN;
        }
    }

    private static String cleanTitle(String title, String fallback) {
        String t = title == null ? fallback : title.replaceAll("\\p{Cntrl}", " ").trim();
        if (t.isEmpty()) {
            t = fallback;
        }
        return t.length() <= MAX_TITLE ? t : t.substring(0, MAX_TITLE);
    }

    private static LocalDate date(String s) {
        if (s == null || s.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The vendor pads some fields with blanks (for example {@code " 11.0.1-h5"}); only that padding is removed, never content. */
    private static String trimmed(String s) {
        return s == null ? null : s.strip();
    }

    private static String text(JsonNode n) {
        return n != null && n.isTextual() ? n.asText() : null;
    }
}
