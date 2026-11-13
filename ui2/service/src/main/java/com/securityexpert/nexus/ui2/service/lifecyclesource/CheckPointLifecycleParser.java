package com.securityexpert.nexus.ui2.service.lifecyclesource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleKeys;

/**
 * The Check Point Support Life Cycle Policy page ({@code /support-services/support-life-cycle-policy/}, contract section
 * 4.3), read from a copy an operator saved and uploaded; its host is not approved for online fetching (a PO decision).
 * <ul>
 *   <li><b>Software</b>: the tables under the headings {@code Security Gateway & Management} and {@code Check Point Spark
 *       Firewall Security Gateways}, header {@code Major Version | General Availability | Affected Versions | Support
 *       Until}. One SOFTWARE entry per version the Affected Versions cell names ({@code R81.20}, {@code R82},
 *       {@code R81.10.xx}); Support Until is the end of support. The page gives these dates as a month.</li>
 *   <li><b>Hardware</b>: every table whose header starts {@code Appliance Product/Model} and names End of Sale, End of
 *       Engineering Support and End of Support. One HARDWARE entry per row whose model has a key ({@code 6600},
 *       {@code 1595R}, {@code SMART-1 6000-L}); a row whose model has no key (an open server, a legacy family) is
 *       skipped and counted.</li>
 * </ul>
 * The other tables of the page (endpoint clients, cloud services, the "Latest Announcements" summary) are not read.
 */
public final class CheckPointLifecycleParser {

    public static final String PAGE_FORM = "checkpoint_lifecycle";
    static final String VENDOR = "CHECKPOINT";
    static final String SOFTWARE_REF = "checkpoint-software-lifecycle";
    static final String HARDWARE_REF = "checkpoint-appliance-lifecycle";

    private static final Pattern VERSION_TOKEN = Pattern.compile("R\\d{2}(?:\\.\\d{2}(?:\\.xx)?)?", Pattern.CASE_INSENSITIVE);

    private CheckPointLifecycleParser() {
    }

    public static LifecyclePage.Result parse(byte[] document) {
        HtmlTables.Document page = HtmlTables.read(document);
        List<LifecyclePage.Entry> entries = new ArrayList<>();
        boolean software = false;
        boolean hardware = false;
        int skipped = 0;
        for (HtmlTables.Table table : page.tables()) {
            if (table.rows().isEmpty()) continue;
            List<String> header = lower(table.rows().getFirst());
            String heading = table.heading().toLowerCase(Locale.ROOT);
            if (header.equals(List.of("major version", "general availability", "affected versions", "support until"))
                    && (heading.equals("security gateway & management") || heading.startsWith("check point spark firewall"))) {
                software = true;
                for (List<String> row : table.rows().subList(1, table.rows().size())) {
                    if (row.size() < 4) {
                        skipped++;
                        continue;
                    }
                    List<String> keys = new ArrayList<>();
                    Matcher m = VERSION_TOKEN.matcher(row.get(2));
                    while (m.find()) {
                        String key = LifecycleKeys.catalogTrain(VENDOR, m.group());
                        if (key != null && !keys.contains(key)) keys.add(key);
                    }
                    if (keys.isEmpty()) {
                        skipped++;
                        continue;
                    }
                    Map<String, VendorDate.Read> dates = Map.of("end_of_support", VendorDate.read(row.get(3)));
                    for (String key : keys) entries.add(withSupport(LifecyclePage.entry(VENDOR, "SOFTWARE", key, row.getFirst(), SOFTWARE_REF, dates)));
                }
            } else if (!header.isEmpty() && header.getFirst().equals("appliance product/model")) {
                int sale = header.indexOf("end of sale");
                int engineering = header.indexOf("end of engineering support");
                int support = header.indexOf("end of support");
                if (sale < 0 || engineering < 0 || support < 0) continue;
                hardware = true;
                for (List<String> row : table.rows().subList(1, table.rows().size())) {
                    String key = row.isEmpty() ? null : LifecycleKeys.model(VENDOR, row.getFirst());
                    if (key == null || row.size() <= support) {
                        skipped++;
                        continue;
                    }
                    Map<String, VendorDate.Read> dates = new LinkedHashMap<>();
                    dates.put("end_of_sale", VendorDate.read(row.get(sale)));
                    dates.put("end_of_engineering", VendorDate.read(row.get(engineering)));
                    dates.put("end_of_support", VendorDate.read(row.get(support)));
                    entries.add(LifecyclePage.entry(VENDOR, "HARDWARE", key, row.getFirst(), HARDWARE_REF, dates));
                }
            }
        }
        if (entries.isEmpty()) return LifecyclePage.Result.notRecognised(PAGE_FORM);
        List<String> refs = new ArrayList<>();
        if (software) refs.add(SOFTWARE_REF);
        if (hardware) refs.add(HARDWARE_REF);
        return new LifecyclePage.Result(true, PAGE_FORM, refs, LifecyclePage.merge(entries), skipped);
    }

    /** A software row without a support date is not a lifecycle statement: NEEDS_REVIEW (MILESTONE_MISSING). */
    private static LifecyclePage.Entry withSupport(LifecyclePage.Entry entry) {
        VendorDate.Read support = entry.dates().get("end_of_support");
        if (LifecyclePage.PARSED.equals(entry.parseState()) && (support == null || support.kind() == VendorDate.Kind.NONE)) {
            return LifecyclePage.review(entry.vendor(), entry.kind(), entry.productKey(), entry.productLabel(), entry.documentRef(),
                    "MILESTONE_MISSING");
        }
        return entry;
    }

    private static List<String> lower(List<String> row) {
        return row.stream().map(c -> c.strip().toLowerCase(Locale.ROOT)).toList();
    }
}
