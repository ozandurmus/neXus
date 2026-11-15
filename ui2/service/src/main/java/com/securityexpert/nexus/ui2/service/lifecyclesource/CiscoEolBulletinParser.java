package com.securityexpert.nexus.ui2.service.lifecyclesource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleKeys;

/**
 * One Cisco End-of-Sale and End-of-Life bulletin page (contract section 4.1). Read:
 * <ul>
 *   <li>the bulletin id ({@code EOL15698}) from the page text; without one the page path names the publication;</li>
 *   <li>the milestone table (header {@code Milestone | Definition | Date}): End-of-Sale Date, End of SW Maintenance
 *       Releases Date (end of engineering), End of Vulnerability/Security Support, Last Date of Support (end of
 *       support);</li>
 *   <li>the products: ASA software trains named in the title ({@code ASA Release 9.18(x)}, {@code (ASA) 9.17.(x)},
 *       {@code ASA 9.0 and 9.3}) as SOFTWARE; Firepower and ASA appliance models named by the part numbers of the product
 *       table ({@code FPR-4115-K9}, {@code FPR4110-ASA-K9}, {@code ASA5516-FPWR-K9}) as HARDWARE.</li>
 * </ul>
 * A milestone row is labelled with what it covers ({@code : HW}, {@code : App SW}, {@code : HW,License}); a software entry
 * takes the rows that name SW, a hardware entry the rows that name HW, and a milestone that still has two different dates
 * makes the entries NEEDS_REVIEW (MILESTONE_CONFLICT). A bulletin without a readable Last Date of Support is NEEDS_REVIEW
 * (MILESTONE_MISSING). A page that is not a bulletin, or a bulletin that names no ASA train and no appliance model
 * (subscriptions, licences, FTD-only releases), is not recognised and changes nothing.
 */
public final class CiscoEolBulletinParser {

    public static final String PAGE_FORM = "cisco_eol_bulletin";
    static final String VENDOR = "CISCO_ASA";

    private static final Pattern BULLETIN_ID = Pattern.compile("\\b(EOL\\d{4,6})\\b");
    private static final Pattern ASA_TRAIN = Pattern.compile(
            "(?:\\bASA\\b|\\(ASA\\))\\s*(?:Software\\s+)?(?:Release\\s+)?(\\d{1,2}\\.\\d{1,2})(?:\\.?\\s?\\(x\\)|\\.x)?"
                    + "((?:\\s*(?:,|and|&)\\s*\\d{1,2}\\.\\d{1,2}(?:\\.?\\s?\\(x\\)|\\.x)?)*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MORE_TRAINS = Pattern.compile("(\\d{1,2}\\.\\d{1,2})");
    private static final Pattern MODEL_PART = Pattern.compile("^(?:(FPR)-?(\\d{4})|(ASA)(55\\d\\d))(?:-[A-Z0-9]{1,6}){0,3}-K9=?$");

    private static final Map<String, String> MILESTONES = Map.of(
            "end-of-sale date", "end_of_sale",
            "end of sw maintenance releases date", "end_of_engineering",
            "end of vulnerability/security support", "end_of_security_support",
            "last date of support", "end_of_support");

    private CiscoEolBulletinParser() {
    }

    /** @param pathRef a code word for the page path, used when the page carries no bulletin id */
    public static LifecyclePage.Result parse(byte[] document, String pathRef) {
        HtmlTables.Document page = HtmlTables.read(document);
        if (!page.title().toLowerCase(Locale.ROOT).contains("end-of-sale and end-of-life announcement")) {
            return LifecyclePage.Result.notRecognised(PAGE_FORM);
        }
        Matcher id = BULLETIN_ID.matcher(page.text());
        String ref = id.find() ? id.group(1) : pathRef;

        Set<String> trains = trains(page.title());
        Set<String> models = new LinkedHashSet<>();
        Map<String, List<String[]>> milestones = null;
        for (HtmlTables.Table table : page.tables()) {
            if (table.rows().isEmpty()) continue;
            List<String> header = table.rows().getFirst();
            if (milestones == null && header.size() >= 2 && header.getFirst().equalsIgnoreCase("Milestone")
                    && header.getLast().equalsIgnoreCase("Date")) {
                milestones = milestones(table.rows());
            } else if (!header.isEmpty() && header.getFirst().toLowerCase(Locale.ROOT).contains("product part number")) {
                for (List<String> row : table.rows().subList(1, table.rows().size())) {
                    if (row.isEmpty()) continue;
                    Matcher part = MODEL_PART.matcher(row.getFirst().strip());
                    if (part.matches()) models.add(part.group(1) != null ? "FPR" + part.group(2) : "ASA" + part.group(4));
                }
            }
        }
        if (trains.isEmpty() && models.isEmpty()) return LifecyclePage.Result.notRecognised(PAGE_FORM);

        List<LifecyclePage.Entry> entries = new ArrayList<>();
        for (String train : trains) {
            entries.add(entry("SOFTWARE", "ASA " + train, "ASA " + train + " (" + ref + ")", ref, milestones, "SW"));
        }
        for (String model : models) {
            String key = LifecycleKeys.model(VENDOR, model);
            if (key != null) entries.add(entry("HARDWARE", key, model + " (" + ref + ")", ref, milestones, "HW"));
        }
        return new LifecyclePage.Result(true, PAGE_FORM, List.of(ref), LifecyclePage.merge(entries), 0);
    }

    /** The ASA trains the title names, in order. */
    static Set<String> trains(String title) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = ASA_TRAIN.matcher(title);
        while (m.find()) {
            out.add(m.group(1));
            Matcher more = MORE_TRAINS.matcher(m.group(2) == null ? "" : m.group(2));
            while (more.find()) out.add(more.group(1));
        }
        return out;
    }

    /** Milestone field to the list of (scope, date text) rows, in page order. */
    private static Map<String, List<String[]>> milestones(List<List<String>> rows) {
        Map<String, List<String[]>> out = new LinkedHashMap<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() < 2) continue;
            String label = row.getFirst();
            int colon = label.indexOf(':');
            String base = (colon < 0 ? label : label.substring(0, colon)).strip().toLowerCase(Locale.ROOT);
            String scope = colon < 0 ? "" : label.substring(colon + 1).strip().toUpperCase(Locale.ROOT);
            String field = MILESTONES.get(base);
            if (field != null) out.computeIfAbsent(field, k -> new ArrayList<>()).add(new String[]{scope, row.getLast()});
        }
        return out;
    }

    private static LifecyclePage.Entry entry(String kind, String key, String label, String ref, Map<String, List<String[]>> milestones,
            String scopeWord) {
        if (milestones == null) return LifecyclePage.review(VENDOR, kind, key, label, ref, "MILESTONE_TABLE_MISSING");
        Map<String, VendorDate.Read> dates = new LinkedHashMap<>();
        for (Map.Entry<String, List<String[]>> milestone : milestones.entrySet()) {
            List<String[]> rows = milestone.getValue();
            List<String[]> scoped = rows.stream().filter(r -> covers(r[0], scopeWord)).toList();
            List<String[]> use = scoped.isEmpty() ? rows : scoped;
            Set<String> texts = new LinkedHashSet<>();
            for (String[] r : use) texts.add(r[1].strip());
            if (texts.size() > 1) return LifecyclePage.review(VENDOR, kind, key, label, ref, "MILESTONE_CONFLICT");
            dates.put(milestone.getKey(), VendorDate.read(texts.iterator().next()));
        }
        VendorDate.Read support = dates.get("end_of_support");
        if (support == null || support.kind() == VendorDate.Kind.NONE) {
            return LifecyclePage.review(VENDOR, kind, key, label, ref, "MILESTONE_MISSING");
        }
        return LifecyclePage.entry(VENDOR, kind, key, label, ref, dates);
    }

    /** True when a milestone scope ({@code HW}, {@code App SW}, {@code HW,License}, {@code OS SW}) covers the kind. */
    private static boolean covers(String scope, String word) {
        for (String part : scope.split("[,/]")) {
            String p = part.strip();
            if (p.equals(word) || p.endsWith(" " + word)) return true;
        }
        return false;
    }
}
