package com.securityexpert.nexus.ui2.service.advisory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parser of the Check Point source (amendment A-2026-11-13 of the advisory matching contract). Two document kinds:
 * <ul>
 * <li>a Jumbo Hotfix Accumulator "All Resolved Issues" page of one train ({@code sc1.checkpoint.com}): every table line that
 * names a CVE id becomes a {@link AdvisoryModel.JumboStatement} of its take ({@code FIX} when the line says the CVE was
 * resolved or fixed, {@code MENTION} otherwise). The page yields no advisory; the ingest stores the statements of the train
 * as a set;</li>
 * <li>a CVE record (CVE JSON 5) of the CVE Program's public list: one advisory {@code checkpoint:<CVE id>}, whose rules are
 * built from the record and the stored statements that name the same CVE ({@link ParseContext}).</li>
 * </ul>
 * Rules, per product ({@code checkpoint:gaia-gateway}, {@code checkpoint:gaia-management}) and train:
 * <ul>
 * <li>a record of Check Point itself that says {@code "<train> with Jumbo Hotfix Take <M> or below"} for the product: affected
 * up to take M, every take above it not affected; a resolved-issues take at or below M for the same train contradicts it
 * ({@code SELF_CHECK_FAILED});</li>
 * <li>a record of Check Point that names the train without a take: the take that resolved it comes from the resolved-issues
 * page and is a proposal ({@code FIX_RULE_UNCONFIRMED}; at or above that take the version is fixed, below it the verdict is
 * pending, never "affected");</li>
 * <li>a record of another CVE authority (a third-party component Check Point fixed): the same proposal rule for both
 * products, only for the trains whose page names the CVE.</li>
 * </ul>
 * A product name, version text or status this parser does not know makes the advisory {@code NEEDS_REVIEW}; it never trims
 * or guesses an affected range. Never throws.
 */
@Component
public final class CheckPointAdvisoryParser implements AdvisoryParser {

    static final String NOTE_THIRD_PARTY_CVE = "THIRD_PARTY_CVE";
    static final String NOTE_UNKNOWN_PRODUCT = "UNKNOWN_PRODUCT";
    static final String NOTE_UNKNOWN_VERSION_FORM = "UNKNOWN_VERSION_FORM";
    static final String NOTE_UNKNOWN_STATUS = "UNKNOWN_STATUS";
    static final String NOTE_NO_AFFECTED = "NO_AFFECTED";
    static final String NOTE_RECORD_CONFLICT = "RECORD_CONFLICT";
    static final String NOTE_SELF_CHECK_FAILED = "SELF_CHECK_FAILED";

    static final String BASIS_UNCONFIRMED = "FIX_RULE_UNCONFIRMED";
    static final String BASIS_MULTIPLE_TAKES = "MULTIPLE_FIX_TAKES";
    static final String BASIS_UNCLEAR = "FIX_STATEMENT_UNCLEAR";
    static final String BASIS_NO_FIX = "NO_FIX_TAKE_PUBLISHED";
    static final String BASIS_SUBSET = "PRODUCT_SUBSET_UNKNOWN";

    static final String KIND_FIX = "FIX";
    static final String KIND_MENTION = "MENTION";

    static final String PAGE_HOST = "sc1.checkpoint.com";
    static final String RECORD_HOST = "raw.githubusercontent.com";
    static final String SK_URL = "https://support.checkpoint.com/results/sk/";

    private static final int MAX_EVIDENCE = 300;
    private static final int MAX_FIXED_TEXT = 200;
    private static final int MAX_PRODUCT_TEXT = 80;
    private static final int MAX_STATEMENTS = 20_000;
    private static final int MAX_VERSION_TEXT = 600;

    private static final Pattern PAGE_URL = Pattern.compile(
            "https://sc1\\.checkpoint\\.com/documents/Jumbo_HFA/(" + CheckPointGaiaComparator.TRAIN_TEXT + ")/All_Resolved_Issues\\.html");
    private static final String SECTION_MARK = "id=\"All_Resolved_Issues__Take_";
    private static final Pattern CVE = Pattern.compile("(?<![A-Za-z0-9-])CVE-[0-9]{4}-[0-9]{4,7}(?![0-9])");
    private static final Pattern CVE_ID = Pattern.compile("CVE-[0-9]{4}-[0-9]{4,10}");
    private static final Pattern SK = Pattern.compile("(?<![A-Za-z0-9])sk[0-9]{1,10}(?![0-9])");
    private static final Pattern SK_LINK = Pattern.compile("https://support\\.checkpoint\\.com/results/sk/(sk[0-9]{1,10})");
    /** The line says the CVE was resolved or fixed (case as the vendor writes it). */
    private static final Pattern FIX_WORDS = Pattern.compile(
            "(?:^|[\\s:.(])Resolved CVE-|\\bto fix:? CVE-|^(?:UPDATE: )?CVE-[0-9]{4}-[0-9]{4,7} - ");
    private static final Pattern TAKE_OR_BELOW = Pattern.compile(
            "(" + CheckPointGaiaComparator.TRAIN_TEXT + ") with Jumbo Hotfix Take ([1-9][0-9]{0,4}) or below");
    private static final Pattern BARE_TRAIN = Pattern.compile(CheckPointGaiaComparator.TRAIN_TEXT);
    private static final String TRAIN_LIST = CheckPointGaiaComparator.TRAIN_TEXT + "(?:(?:, | and |, and )"
            + CheckPointGaiaComparator.TRAIN_TEXT + ")*";
    /** {@code "<products> versions <train list>"}, optionally followed by {@code " and "} and another such group. */
    private static final Pattern SENTENCE = Pattern.compile("(.{1,200}?) versions? (" + TRAIN_LIST + ")(?: and (.+))?");
    private static final Pattern TRAIN_IN_LIST = Pattern.compile(CheckPointGaiaComparator.TRAIN_TEXT);

    /** What a product name of a Check Point record stands for. */
    enum ProductClass { GATEWAY, MANAGEMENT, BOTH, SUBSET, OUTSIDE }

    /**
     * The product names seen in Check Point's records (2026-11-13), without a leading "Check Point". Exact match only; any
     * other name makes the advisory {@code NEEDS_REVIEW}. {@code SUBSET}: only some gateways (Maestro, scalable chassis,
     * cloud gateways), which neXus cannot tell apart. {@code OUTSIDE}: no device of that product is covered here (Quantum
     * Spark is {@code NOT_COVERED}).
     */
    static final Map<String, ProductClass> PRODUCTS = Map.ofEntries(
            Map.entry("Quantum Gateway", ProductClass.GATEWAY),
            Map.entry("Quantum Gateways", ProductClass.GATEWAY),
            Map.entry("Quantum Security Gateway", ProductClass.GATEWAY),
            Map.entry("Quantum Security Gateways", ProductClass.GATEWAY),
            Map.entry("Security Gateway", ProductClass.GATEWAY),
            Map.entry("Security Gateways", ProductClass.GATEWAY),
            Map.entry("Quantum Appliances", ProductClass.GATEWAY),
            Map.entry("ClusterXL", ProductClass.GATEWAY),
            Map.entry("Quantum Security Management", ProductClass.MANAGEMENT),
            Map.entry("Security Management", ProductClass.MANAGEMENT),
            Map.entry("Multi-Domain Security Management", ProductClass.MANAGEMENT),
            Map.entry("Multi-Domain Server", ProductClass.MANAGEMENT),
            Map.entry("Gaia", ProductClass.BOTH),
            Map.entry("Gaia OS", ProductClass.BOTH),
            Map.entry("Quantum Maestro", ProductClass.SUBSET),
            Map.entry("Quantum Scalable Chassis", ProductClass.SUBSET),
            Map.entry("Scalable Platforms", ProductClass.SUBSET),
            Map.entry("Scalable Platforms (ElasticXL, Maestro, Chassis)", ProductClass.SUBSET),
            Map.entry("CloudGuard Network", ProductClass.SUBSET),
            Map.entry("Spark", ProductClass.OUTSIDE),
            Map.entry("Spark Gateway", ProductClass.OUTSIDE),
            Map.entry("Quantum Spark", ProductClass.OUTSIDE),
            Map.entry("Quantum Spark Gateway", ProductClass.OUTSIDE));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String source() {
        return AdvisoryModel.SOURCE_CHECKPOINT;
    }

    @Override
    public AdvisoryModel.ParseResult parse(String url, byte[] document) {
        return parse(url, document, ParseContext.EMPTY);
    }

    @Override
    public AdvisoryModel.ParseResult parse(String url, byte[] document, ParseContext context) {
        try {
            if (url == null || document == null) {
                return rejectedResult();
            }
            Matcher page = PAGE_URL.matcher(url);
            if (page.matches()) {
                return parsePage(page.group(1), document);
            }
            if (url.startsWith("https://" + RECORD_HOST + "/")) {
                return parseRecord(document, context == null ? ParseContext.EMPTY : context);
            }
            return rejectedResult();
        } catch (RuntimeException | StackOverflowError e) {
            return rejectedResult();
        }
    }

    private static AdvisoryModel.ParseResult rejectedResult() {
        return new AdvisoryModel.ParseResult(List.of(), List.of(), 1);
    }

    // ---- resolved-issues page -------------------------------------------------------------------------------------

    /**
     * Every take section ({@code id="All_Resolved_Issues__Take_<n>"}) and in it every table row of three cells (ID, Product,
     * Description) whose description names a CVE id. A page without a single take section is not this page form: nothing
     * is stored for its train. A CVE row of another shape is counted as rejected.
     */
    static AdvisoryModel.ParseResult parsePage(String train, byte[] document) {
        if (!CheckPointGaiaComparator.COVERED_TRAINS.contains(train)) {
            return rejectedResult();
        }
        String html = new String(document, StandardCharsets.UTF_8);
        List<int[]> sections = new ArrayList<>();
        int at = html.indexOf(SECTION_MARK);
        while (at >= 0) {
            int digitsStart = at + SECTION_MARK.length();
            int digitsEnd = digitsStart;
            while (digitsEnd < html.length() && digitsEnd - digitsStart < 6 && Character.isDigit(html.charAt(digitsEnd))) {
                digitsEnd++;
            }
            if (digitsEnd > digitsStart && digitsEnd < html.length() && html.charAt(digitsEnd) == '"') {
                sections.add(new int[] {at, Integer.parseInt(html.substring(digitsStart, digitsEnd))});
            }
            at = html.indexOf(SECTION_MARK, at + SECTION_MARK.length());
        }
        if (sections.isEmpty()) {
            return rejectedResult();
        }
        List<AdvisoryModel.JumboStatement> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int rejected = 0;
        for (int i = 0; i < sections.size(); i++) {
            int take = sections.get(i)[1];
            int end = i + 1 < sections.size() ? sections.get(i + 1)[0] : html.length();
            String body = html.substring(sections.get(i)[0], end);
            for (String row : blocks(body, "<tr", "</tr>")) {
                List<String> cells = blocks(row, "<td", "</td>");
                String rowText = text(row);
                if (!CVE.matcher(rowText).find()) {
                    continue;
                }
                if (cells.size() != 3 || take < 1) {
                    rejected++;
                    continue;
                }
                String description = text(cells.get(2));
                Matcher cves = CVE.matcher(description);
                if (!cves.find()) {
                    rejected++;
                    continue;
                }
                String kind = FIX_WORDS.matcher(description).find() ? KIND_FIX : KIND_MENTION;
                String product = text(cells.get(1));
                product = product.isEmpty() || product.length() > MAX_PRODUCT_TEXT ? null : product;
                Matcher sk = SK.matcher(description);
                String skRef = sk.find() ? sk.group() : null;
                String evidence = cut(description, MAX_EVIDENCE);
                cves.reset();
                while (cves.find() && out.size() < MAX_STATEMENTS) {
                    if (seen.add(take + "|" + cves.group() + "|" + kind)) {
                        out.add(new AdvisoryModel.JumboStatement(train, take, cves.group(), kind, product, skRef, evidence));
                    }
                }
            }
        }
        return new AdvisoryModel.ParseResult(List.of(), List.of(), rejected, train, out);
    }

    /** The blocks from each {@code open} tag to its {@code close} tag, without nesting; never more than the text holds. */
    private static List<String> blocks(String text, String open, String close) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = text.indexOf(open, from);
            if (start < 0) {
                return out;
            }
            int next = start + open.length();
            if (next < text.length() && Character.isLetterOrDigit(text.charAt(next))) {
                from = next;
                continue;
            }
            int end = text.indexOf(close, next);
            if (end < 0) {
                return out;
            }
            out.add(text.substring(start, end));
            from = end + close.length();
        }
    }

    /** Tags that separate words; every other tag (a, span, strong, ...) is removed without a blank. */
    private static final Set<String> BLOCK_TAGS = Set.of("p", "br", "div", "td", "th", "tr", "li", "ul", "ol", "table", "section", "h1",
            "h2", "h3", "h4");

    /** The visible text of an HTML fragment: tags removed, the common character references decoded, blanks collapsed. */
    static String text(String fragment) {
        StringBuilder out = new StringBuilder(fragment.length());
        int i = 0;
        while (i < fragment.length()) {
            char c = fragment.charAt(i);
            if (c != '<') {
                out.append(c);
                i++;
                continue;
            }
            int end = fragment.indexOf('>', i);
            if (end < 0) {
                break;
            }
            int nameStart = i + 1;
            if (nameStart < end && fragment.charAt(nameStart) == '/') {
                nameStart++;
            }
            int nameEnd = nameStart;
            while (nameEnd < end && Character.isLetterOrDigit(fragment.charAt(nameEnd))) {
                nameEnd++;
            }
            if (BLOCK_TAGS.contains(fragment.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT))) {
                out.append(' ');
            }
            i = end + 1;
        }
        String decoded = decode(out.toString());
        return decoded.replaceAll("[\\s\\u00a0]+", " ").strip();
    }

    private static String decode(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int semi = c == '&' ? s.indexOf(';', i) : -1;
            if (semi > i && semi - i <= 10) {
                String name = s.substring(i + 1, semi);
                String value = switch (name) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos", "#39" -> "'";
                    case "nbsp", "#160" -> " ";
                    default -> numeric(name);
                };
                if (value != null) {
                    out.append(value);
                    i = semi + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String numeric(String name) {
        try {
            int code;
            if (name.startsWith("#x") || name.startsWith("#X")) {
                code = Integer.parseInt(name.substring(2), 16);
            } else if (name.startsWith("#")) {
                code = Integer.parseInt(name.substring(1));
            } else {
                return null;
            }
            return code > 31 && code < 0x110000 && !Character.isSurrogate((char) code) ? new String(Character.toChars(code)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    // ---- CVE record ----------------------------------------------------------------------------------------------

    /** The trains a record says something about for one product key: take M (null = the train is named without a take). */
    private record Named(Integer take, boolean subset) {
    }

    private static final class Review extends Exception {
        private static final long serialVersionUID = 1L;

        Review(String note) {
            super(note, null, false, false);
        }
    }

    private static AdvisoryModel.ParseResult parseRecord(byte[] document, ParseContext context) {
        JsonNode rec;
        try {
            rec = MAPPER.readTree(document);
        } catch (Exception e) {
            return rejectedResult();
        }
        if (rec == null || !rec.isObject()) {
            return rejectedResult();
        }
        JsonNode meta = rec.path("cveMetadata");
        String cveId = text(meta.get("cveId"));
        if (cveId == null || !CVE_ID.matcher(cveId).matches() || !"PUBLISHED".equals(text(meta.get("state")))) {
            return rejectedResult();
        }
        JsonNode cna = rec.path("containers").path("cna");
        if (!cna.isObject()) {
            return rejectedResult();
        }
        String assigner = text(meta.get("assignerShortName"));
        boolean checkPointRecord = "checkpoint".equals(assigner);

        String title = text(cna.get("title"));
        if (title == null) {
            title = englishDescription(cna);
        }
        title = title == null ? cveId : cut(title.replaceAll("\\s+", " ").strip(), 300);
        BigDecimal score = null;
        AdvisoryModel.Severity severity = AdvisoryModel.Severity.UNKNOWN;
        JsonNode metrics = cna.get("metrics");
        if (metrics != null && metrics.isArray()) {
            outer:
            for (String key : List.of("cvssV4_0", "cvssV3_1", "cvssV3_0")) {
                for (JsonNode m : metrics) {
                    JsonNode cvss = m.get(key);
                    if (cvss != null && cvss.path("baseScore").isNumber()) {
                        score = cvss.get("baseScore").decimalValue();
                        severity = severity(text(cvss.get("baseSeverity")));
                        break outer;
                    }
                }
            }
        }
        LocalDate published = date(text(meta.get("datePublished")));
        LocalDate updated = date(text(meta.get("dateUpdated")));
        if (updated == null) {
            updated = published;
        }

        List<AdvisoryModel.JumboStatement> statements = new ArrayList<>();
        for (AdvisoryModel.JumboStatement s : context.jumboStatements(cveId)) {
            if (s != null && cveId.equals(s.cveId()) && s.train() != null && s.take() > 0) {
                statements.add(s);
            }
        }
        String sourceUrl = skLink(cna, statements);

        Map<String, Map<String, Named>> named = new LinkedHashMap<>();
        String note = null;
        if (checkPointRecord) {
            try {
                named = recordProducts(cna);
            } catch (Review review) {
                note = review.getMessage();
            }
        } else {
            note = NOTE_THIRD_PARTY_CVE;
        }

        List<AdvisoryModel.ProductRule> rules = new ArrayList<>();
        AdvisoryModel.ParseState state = AdvisoryModel.ParseState.PARSED;
        if (!checkPointRecord) {
            for (String train : trains(statements)) {
                for (String product : List.of(AdvisoryModel.PRODUCT_CP_GATEWAY, AdvisoryModel.PRODUCT_CP_MANAGEMENT)) {
                    rules.add(proposal(product, train, statements, BASIS_UNCONFIRMED));
                }
            }
        } else {
            Set<String> statementTrains = trains(statements);
            if (note == null && named.isEmpty() && !statementTrains.isEmpty()) {
                // A Gaia resolved-issues page names the CVE but the record names no Gaia product: the two disagree.
                note = NOTE_RECORD_CONFLICT;
            }
            if (note == null) {
                for (var byProduct : named.entrySet()) {
                    for (var entry : byProduct.getValue().entrySet()) {
                        AdvisoryModel.ProductRule rule = recordRule(byProduct.getKey(), entry.getKey(), entry.getValue(), statements);
                        if (rule == null) {
                            note = NOTE_SELF_CHECK_FAILED;
                        } else {
                            rules.add(rule);
                        }
                    }
                    // A train a resolved-issues line names and the record does not: a proposal for this product, never "not
                    // listed" (amendment A-2026-11-13, corrected 2026-11-14).
                    for (String train : statementTrains) {
                        if (!byProduct.getValue().containsKey(train)) {
                            rules.add(proposal(byProduct.getKey(), train, statements, BASIS_UNCONFIRMED));
                        }
                    }
                }
            }
            if (note != null) {
                // Not read in full, or contradicted: an open question for both products on every train the record or its
                // resolved-issues lines name, and for the whole product when a version text names no train.
                state = AdvisoryModel.ParseState.NEEDS_REVIEW;
                Set<String> trains = new TreeSet<>(statementTrains);
                boolean unknownTrain = recordTrains(cna, trains);
                for (Map<String, Named> byTrain : named.values()) {
                    trains.addAll(byTrain.keySet());
                }
                if (trains.isEmpty() || unknownTrain) {
                    trains.add(AdvisoryModel.ALL_TRAINS);
                }
                rules = new ArrayList<>();
                for (String train : trains) {
                    for (String product : List.of(AdvisoryModel.PRODUCT_CP_GATEWAY, AdvisoryModel.PRODUCT_CP_MANAGEMENT)) {
                        rules.add(new AdvisoryModel.ProductRule(product, train, List.of(), null));
                    }
                }
            }
        }
        List<String> cves = List.of(cveId);
        AdvisoryModel.ParsedAdvisory advisory = new AdvisoryModel.ParsedAdvisory(AdvisoryModel.SOURCE_CHECKPOINT + ":" + cveId,
                AdvisoryModel.SOURCE_CHECKPOINT, title, severity, score, cves, published, updated, sourceUrl, state, note, rules);
        return new AdvisoryModel.ParseResult(List.of(advisory), List.of(), 0);
    }

    private static final Pattern TRAIN_IN_TEXT = Pattern.compile("(?<![A-Za-z0-9])" + CheckPointGaiaComparator.TRAIN_TEXT + "(?![0-9])");

    /**
     * Adds every Gaia train any version text of the record names, read or not, to {@code into}; true when some version text
     * names no train at all (or the record has no readable {@code affected} list), so that only the whole product is safe.
     */
    static boolean recordTrains(JsonNode cna, Set<String> into) {
        JsonNode affected = cna.get("affected");
        if (affected == null || !affected.isArray() || affected.isEmpty()) {
            return true;
        }
        boolean unknown = false;
        for (JsonNode entry : affected) {
            JsonNode versions = entry.get("versions");
            if (versions == null || !versions.isArray() || versions.isEmpty()) {
                unknown = true;
                continue;
            }
            for (JsonNode v : versions) {
                String version = text(v.get("version"));
                Matcher m = TRAIN_IN_TEXT.matcher(version == null ? "" : version.length() > MAX_VERSION_TEXT ? "" : version);
                boolean any = false;
                while (m.find()) {
                    into.add(m.group());
                    any = true;
                }
                unknown |= !any;
            }
        }
        return unknown;
    }

    /** The rule of one product and train of a Check Point record, or null when the resolved-issues page contradicts it. */
    private static AdvisoryModel.ProductRule recordRule(String product, String train, Named n, List<AdvisoryModel.JumboStatement> statements) {
        Set<Integer> fixTakes = takes(statements, train, KIND_FIX);
        if (n.take() == null) {
            return proposal(product, train, statements, n.subset() ? BASIS_SUBSET : BASIS_UNCONFIRMED);
        }
        int lastAffected = n.take();
        for (int fix : fixTakes) {
            if (fix <= lastAffected) {
                return null;
            }
        }
        String bound = version(train, lastAffected);
        String fixed = fixTakes.isEmpty() ? "Affected up to " + bound + " (vendor CVE record)"
                : fixedText(train, statements, fixTakes.iterator().next());
        AdvisoryModel.VersionRule rule = n.subset()
                ? new AdvisoryModel.JumboFix(bound, true, BASIS_SUBSET)
                : new AdvisoryModel.Range(AdvisoryModel.Status.AFFECTED, null, bound, true, List.of());
        return new AdvisoryModel.ProductRule(product, train, List.of(rule), cut(fixed, MAX_FIXED_TEXT));
    }

    /**
     * The proposal rule of one product and train from the resolved-issues lines alone: fixed at or above the one take that
     * resolved the CVE; with several such takes, at or above the highest; with only mentions, or no line at all, no take
     * clears it. {@code basis} is the pending reason when a take does resolve it.
     */
    private static AdvisoryModel.ProductRule proposal(String product, String train, List<AdvisoryModel.JumboStatement> statements, String basis) {
        Set<Integer> fixTakes = takes(statements, train, KIND_FIX);
        Set<Integer> mentions = takes(statements, train, KIND_MENTION);
        AdvisoryModel.JumboFix rule;
        String fixed = null;
        if (fixTakes.size() == 1) {
            int take = fixTakes.iterator().next();
            rule = new AdvisoryModel.JumboFix(version(train, take), false, basis);
            fixed = fixedText(train, statements, take);
        } else if (fixTakes.size() > 1) {
            int highest = ((TreeSet<Integer>) fixTakes).last();
            rule = new AdvisoryModel.JumboFix(version(train, highest), false, BASIS_SUBSET.equals(basis) ? basis : BASIS_MULTIPLE_TAKES);
            fixed = fixedText(train, statements, highest);
        } else if (!mentions.isEmpty()) {
            rule = new AdvisoryModel.JumboFix(null, false, BASIS_SUBSET.equals(basis) ? basis : BASIS_UNCLEAR);
            fixed = fixedText(train, statements, ((TreeSet<Integer>) mentions).first());
        } else {
            rule = new AdvisoryModel.JumboFix(null, false, BASIS_SUBSET.equals(basis) ? basis : BASIS_NO_FIX);
        }
        return new AdvisoryModel.ProductRule(product, train, List.of(rule), fixed == null ? null : cut(fixed, MAX_FIXED_TEXT));
    }

    /** "<train> Jumbo Take <n>: <the line it was read from>". */
    private static String fixedText(String train, List<AdvisoryModel.JumboStatement> statements, int take) {
        for (AdvisoryModel.JumboStatement s : statements) {
            if (train.equals(s.train()) && s.take() == take) {
                return version(train, take) + ": " + s.evidence();
            }
        }
        return version(train, take);
    }

    private static String version(String train, int take) {
        return train + " Jumbo Take " + take;
    }

    private static Set<Integer> takes(List<AdvisoryModel.JumboStatement> statements, String train, String kind) {
        Set<Integer> out = new TreeSet<>();
        for (AdvisoryModel.JumboStatement s : statements) {
            if (train.equals(s.train()) && kind.equals(s.kind())) {
                out.add(s.take());
            }
        }
        return out;
    }

    private static Set<String> trains(List<AdvisoryModel.JumboStatement> statements) {
        Set<String> out = new TreeSet<>();
        for (AdvisoryModel.JumboStatement s : statements) {
            out.add(s.train());
        }
        return out;
    }

    /**
     * Per product key, the trains a Check Point record names in {@code affected[]} and how. Forms: {@code "<train> with
     * Jumbo Hotfix Take <M> or below"}, a bare train, and the sentence {@code "<products> versions <train list>"} (several
     * joined by {@code " and "}), whose own product names then decide. Status must be {@code affected}.
     */
    static Map<String, Map<String, Named>> recordProducts(JsonNode cna) throws Review {
        JsonNode affected = cna.get("affected");
        if (affected == null || !affected.isArray() || affected.isEmpty()) {
            throw new Review(NOTE_NO_AFFECTED);
        }
        Map<String, Map<String, Named>> out = new LinkedHashMap<>();
        for (JsonNode entry : affected) {
            String product = text(entry.get("product"));
            JsonNode versions = entry.get("versions");
            if (product == null || versions == null || !versions.isArray() || versions.isEmpty()) {
                throw new Review(NOTE_UNKNOWN_VERSION_FORM);
            }
            for (JsonNode v : versions) {
                String status = text(v.get("status"));
                String version = text(v.get("version"));
                if (!"affected".equals(status)) {
                    throw new Review(NOTE_UNKNOWN_STATUS);
                }
                if (version == null || version.length() > MAX_VERSION_TEXT || v.has("lessThan") || v.has("lessThanOrEqual")
                        || v.has("changes")) {
                    throw new Review(NOTE_UNKNOWN_VERSION_FORM);
                }
                version = version.strip();
                Matcher take = TAKE_OR_BELOW.matcher(version);
                if (take.matches()) {
                    add(out, classify(product), take.group(1), Integer.parseInt(take.group(2)));
                } else if (BARE_TRAIN.matcher(version).matches()) {
                    add(out, classify(product), version, null);
                } else {
                    sentence(out, classify(product), version.endsWith(".") ? version.substring(0, version.length() - 1) : version);
                }
            }
        }
        return out;
    }

    /**
     * The sentence form: each group's own products decide its trains. A product class the entry's product field names but
     * no group names (a record whose product field lists management while its sentence speaks of gateways) gets the trains
     * of the sentence when it has one group; with several groups the record is a conflict.
     */
    private static void sentence(Map<String, Map<String, Named>> out, Set<ProductClass> entryClasses, String text) throws Review {
        String rest = text;
        Set<ProductClass> named = new LinkedHashSet<>();
        List<String> allTrains = new ArrayList<>();
        int groups = 0;
        while (rest != null) {
            Matcher m = SENTENCE.matcher(rest);
            if (!m.matches() || ++groups > 20) {
                throw new Review(NOTE_UNKNOWN_VERSION_FORM);
            }
            Set<ProductClass> classes = classify(m.group(1));
            named.addAll(classes);
            Matcher trains = TRAIN_IN_LIST.matcher(m.group(2));
            while (trains.find()) {
                add(out, classes, trains.group(), null);
                allTrains.add(trains.group());
            }
            rest = m.group(3);
        }
        // Compared by the product key each class stands for (gateway or management), not by class: a full-gateway name in
        // the product field and a subset name in the sentence both mean gateways (review 2026-11-14, finding 6).
        Set<String> extraKeys = keys(entryClasses);
        extraKeys.removeAll(keys(named));
        if (extraKeys.isEmpty()) {
            return;
        }
        if (groups > 1) {
            throw new Review(NOTE_RECORD_CONFLICT);
        }
        Set<ProductClass> extra = new LinkedHashSet<>();
        if (extraKeys.contains(AdvisoryModel.PRODUCT_CP_GATEWAY)) {
            boolean full = entryClasses.contains(ProductClass.GATEWAY) || entryClasses.contains(ProductClass.BOTH);
            extra.add(full ? ProductClass.GATEWAY : ProductClass.SUBSET);
        }
        if (extraKeys.contains(AdvisoryModel.PRODUCT_CP_MANAGEMENT)) {
            extra.add(ProductClass.MANAGEMENT);
        }
        for (String train : allTrains) {
            add(out, extra, train, null);
        }
    }

    /** The product keys a set of classes stands for. */
    private static Set<String> keys(Set<ProductClass> classes) {
        Set<String> out = new LinkedHashSet<>();
        for (ProductClass c : classes) {
            switch (c) {
                case GATEWAY, SUBSET -> out.add(AdvisoryModel.PRODUCT_CP_GATEWAY);
                case MANAGEMENT -> out.add(AdvisoryModel.PRODUCT_CP_MANAGEMENT);
                case BOTH -> {
                    out.add(AdvisoryModel.PRODUCT_CP_GATEWAY);
                    out.add(AdvisoryModel.PRODUCT_CP_MANAGEMENT);
                }
                case OUTSIDE -> { }
            }
        }
        return out;
    }

    /** Adds one statement for every product key the classes name; a different statement for the same train is a conflict. */
    private static void add(Map<String, Map<String, Named>> out, Set<ProductClass> classes, String train, Integer take) throws Review {
        boolean gateway = classes.contains(ProductClass.GATEWAY) || classes.contains(ProductClass.BOTH);
        boolean subset = !gateway && classes.contains(ProductClass.SUBSET);
        boolean management = classes.contains(ProductClass.MANAGEMENT) || classes.contains(ProductClass.BOTH);
        if (gateway || subset) {
            put(out, AdvisoryModel.PRODUCT_CP_GATEWAY, train, new Named(take, subset));
        }
        if (management) {
            put(out, AdvisoryModel.PRODUCT_CP_MANAGEMENT, train, new Named(take, false));
        }
    }

    private static void put(Map<String, Map<String, Named>> out, String product, String train, Named named) throws Review {
        Map<String, Named> trains = out.computeIfAbsent(product, k -> new LinkedHashMap<>());
        Named before = trains.get(train);
        if (before == null) {
            trains.put(train, named);
            return;
        }
        if (before.equals(named)) {
            return;
        }
        // A full-gateway statement supersedes a subset one of the same take; anything else disagrees.
        if (java.util.Objects.equals(before.take(), named.take())) {
            trains.put(train, new Named(named.take(), before.subset() && named.subset()));
            return;
        }
        throw new Review(NOTE_RECORD_CONFLICT);
    }

    /**
     * The classes of a product text: the whole text when it is a known name, otherwise each part of it split at
     * {@code ", "} and {@code " and "} outside parentheses. A leading "Check Point" is not part of a name.
     */
    static Set<ProductClass> classify(String productText) throws Review {
        Set<ProductClass> out = new LinkedHashSet<>();
        String whole = withoutVendor(productText);
        ProductClass known = PRODUCTS.get(whole);
        if (known != null) {
            out.add(known);
            return out;
        }
        for (String part : split(whole)) {
            ProductClass c = PRODUCTS.get(withoutVendor(part));
            if (c == null) {
                throw new Review(NOTE_UNKNOWN_PRODUCT);
            }
            out.add(c);
        }
        if (out.isEmpty()) {
            throw new Review(NOTE_UNKNOWN_PRODUCT);
        }
        return out;
    }

    private static String withoutVendor(String text) {
        String t = text == null ? "" : text.strip();
        return t.startsWith("Check Point ") ? t.substring("Check Point ".length()).strip() : t;
    }

    private static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && c == ',') {
                parts.add(text.substring(start, i));
                start = i + 1;
            } else if (depth == 0 && text.startsWith(" and ", i)) {
                parts.add(text.substring(start, i));
                start = i + " and ".length();
                i = start;
                continue;
            }
            i++;
        }
        parts.add(text.substring(start));
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.strip();
            if (t.startsWith("and ")) {
                t = t.substring(4).strip();
            }
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** The record's own sk article, else the one its resolved-issues lines refer to; null when neither names one. */
    private static String skLink(JsonNode cna, List<AdvisoryModel.JumboStatement> statements) {
        JsonNode refs = cna.get("references");
        if (refs != null && refs.isArray()) {
            for (JsonNode ref : refs) {
                String url = text(ref.get("url"));
                if (url != null && SK_LINK.matcher(url).matches()) {
                    return url;
                }
            }
        }
        for (AdvisoryModel.JumboStatement s : statements) {
            if (s.skRef() != null && SK.matcher(s.skRef()).matches()) {
                return SK_URL + s.skRef();
            }
        }
        return null;
    }

    private static String englishDescription(JsonNode cna) {
        JsonNode descriptions = cna.get("descriptions");
        if (descriptions == null || !descriptions.isArray()) {
            return null;
        }
        for (JsonNode d : descriptions) {
            String lang = text(d.get("lang"));
            if (lang != null && lang.toLowerCase(Locale.ROOT).startsWith("en")) {
                return text(d.get("value"));
            }
        }
        return null;
    }

    private static AdvisoryModel.Severity severity(String value) {
        if (value == null) {
            return AdvisoryModel.Severity.UNKNOWN;
        }
        try {
            return AdvisoryModel.Severity.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AdvisoryModel.Severity.UNKNOWN;
        }
    }

    private static LocalDate date(String text) {
        if (text == null || text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
