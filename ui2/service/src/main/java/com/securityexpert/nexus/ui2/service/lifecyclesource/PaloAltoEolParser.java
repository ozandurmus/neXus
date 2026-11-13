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
 * The two Palo Alto Networks end-of-life pages (contract section 4.2), read from a copy an operator saved and uploaded;
 * the host that serves them is not approved for online fetching (a PO decision, contract section 3).
 * <ul>
 *   <li><b>Software</b> ({@code /services/support/end-of-life-announcements/end-of-life-summary}): the table whose caption
 *       row is {@code PAN-OS & Panorama}, header {@code Version | Release Date | End-of-Life Date}, the End-of-Life
 *       column split into Standard Support and Extended Support. One SOFTWARE entry per plain train ({@code 11.1}); the
 *       standard-support date is the end of support, the extended-support date is kept beside it. A qualified row
 *       ({@code 9.0-XFR (VM-Series only)}, {@code 5.1 (Panorama only)}) is skipped and counted.</li>
 *   <li><b>Hardware</b> ({@code .../hardware-end-of-life-dates}): the table with header {@code End-of-Sale Product |
 *       End-of-Sale Date | End-of-Life Date | ...}. The product cell names one or more models ({@code PAN-PA-440
 *       PAN-PA-445}, {@code PA-800 Series (PA-820, PA-850) (PA-820-ZTP, PA-850-ZTP)}); one HARDWARE entry per model, a
 *       series name itself is not a model, nor is a card or module part number. End-of-Life Date is the end of support;
 *       the page's " (EOL)" marker after a passed date is removed. Rows of other product lines (LightCyber, Prisma SD-WAN
 *       ION) are skipped.</li>
 * </ul>
 */
public final class PaloAltoEolParser {

    public static final String SOFTWARE_FORM = "paloalto_software_eol";
    public static final String HARDWARE_FORM = "paloalto_hardware_eol";
    static final String VENDOR = "PALOALTO";
    static final String SOFTWARE_REF = "paloalto-panos-eol-summary";
    static final String HARDWARE_REF = "paloalto-hardware-eol";

    private static final Pattern PLAIN_TRAIN = Pattern.compile("^\\d{1,2}\\.\\d{1,2}$");
    /**
     * A model name or a model's system SKU: {@code PA-3220}, {@code PAN-PA-440}, {@code PA-820-ZTP}, {@code PAN-PA-5450-AC-SYS}.
     * A part number of a card or module ({@code PAN-PA-5400-DPC-A}, {@code PA-7000-20GXM-NPC}, {@code PA-5280-K2}) and a
     * series name ({@code PA-800 Series}) are not models.
     */
    private static final Pattern MODEL_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9-])(?:PAN-)?((?:PA|M)-\\d{3,4}[A-Z]?)(?:-ZTP|-(?:AC|DC)(?:-SYS)?)?(?![A-Za-z0-9-])(?!\\s+Series)");
    /** Rows of other product lines on the same page; their model names can look like a Panorama appliance (LightCyber M-100). */
    private static final Pattern OTHER_LINE = Pattern.compile("\\b(?:LightCyber|Magna|Traps|Prisma|ION)\\b");
    /** The page marks a passed date with " (EOL)"; the marker is removed, the date is read as written. */
    private static final Pattern EOL_MARK = Pattern.compile("\\s*\\(EOL\\)$");

    private PaloAltoEolParser() {
    }

    public static LifecyclePage.Result software(byte[] document) {
        HtmlTables.Document page = HtmlTables.read(document);
        for (HtmlTables.Table table : page.tables()) {
            int caption = -1;
            for (int i = 0; i < Math.min(4, table.rows().size()); i++) {
                List<String> row = table.rows().get(i);
                if (row.size() == 1 && row.getFirst().equalsIgnoreCase("PAN-OS & Panorama")) caption = i;
            }
            if (caption < 0 && !"pan-os-panorama".equals(table.id())) continue;
            int header = -1;
            for (int i = 0; i < Math.min(5, table.rows().size()); i++) {
                List<String> row = table.rows().get(i);
                if (row.size() >= 3 && row.get(0).equalsIgnoreCase("Version") && row.get(1).equalsIgnoreCase("Release Date")
                        && row.get(2).equalsIgnoreCase("End-of-Life Date")) header = i;
            }
            if (header < 0) return LifecyclePage.Result.notRecognised(SOFTWARE_FORM);
            int first = header + 1;
            int extendedColumn = -1;
            if (first < table.rows().size() && table.rows().get(first).stream().anyMatch(c -> c.equalsIgnoreCase("Standard Support"))) {
                List<String> sub = table.rows().get(first);
                for (int i = 0; i < sub.size(); i++) if (sub.get(i).toLowerCase(Locale.ROOT).startsWith("extended support")) extendedColumn = i;
                first++;
            }
            List<LifecyclePage.Entry> entries = new ArrayList<>();
            int skipped = 0;
            for (List<String> row : table.rows().subList(first, table.rows().size())) {
                if (row.size() < 3) continue;
                String version = row.getFirst().strip();
                if (!PLAIN_TRAIN.matcher(version).matches()) {
                    skipped++;
                    continue;
                }
                String key = LifecycleKeys.catalogTrain(VENDOR, version);
                Map<String, VendorDate.Read> dates = new LinkedHashMap<>();
                dates.put("end_of_support", VendorDate.read(row.get(2)));
                if (extendedColumn > 0 && extendedColumn < row.size()) dates.put("end_of_extended_support", VendorDate.read(row.get(extendedColumn)));
                LifecyclePage.Entry entry = dates.get("end_of_support").kind() == VendorDate.Kind.NONE
                        ? LifecyclePage.review(VENDOR, "SOFTWARE", key, "PAN-OS " + version, SOFTWARE_REF, "MILESTONE_MISSING")
                        : LifecyclePage.entry(VENDOR, "SOFTWARE", key, "PAN-OS " + version, SOFTWARE_REF, dates);
                entries.add(entry);
            }
            if (entries.isEmpty()) return LifecyclePage.Result.notRecognised(SOFTWARE_FORM);
            return new LifecyclePage.Result(true, SOFTWARE_FORM, List.of(SOFTWARE_REF), LifecyclePage.merge(entries), skipped);
        }
        return LifecyclePage.Result.notRecognised(SOFTWARE_FORM);
    }

    public static LifecyclePage.Result hardware(byte[] document) {
        HtmlTables.Document page = HtmlTables.read(document);
        for (HtmlTables.Table table : page.tables()) {
            if (table.rows().isEmpty()) continue;
            List<String> header = table.rows().getFirst();
            if (header.size() < 3 || !header.get(0).equalsIgnoreCase("End-of-Sale Product")
                    || !header.get(1).equalsIgnoreCase("End-of-Sale Date") || !header.get(2).equalsIgnoreCase("End-of-Life Date")) continue;
            List<LifecyclePage.Entry> entries = new ArrayList<>();
            int skipped = 0;
            for (List<String> row : table.rows().subList(1, table.rows().size())) {
                if (row.size() < 3) continue;
                if (OTHER_LINE.matcher(row.getFirst()).find()) {
                    skipped++;
                    continue;
                }
                List<String> models = new ArrayList<>();
                Matcher m = MODEL_TOKEN.matcher(row.getFirst());
                while (m.find()) {
                    String key = LifecycleKeys.model(VENDOR, m.group(1));
                    if (key != null && !models.contains(key)) models.add(key);
                }
                if (models.isEmpty()) {
                    skipped++;
                    continue;
                }
                Map<String, VendorDate.Read> dates = new LinkedHashMap<>();
                dates.put("end_of_sale", VendorDate.read(EOL_MARK.matcher(row.get(1).strip()).replaceFirst("")));
                dates.put("end_of_support", VendorDate.read(EOL_MARK.matcher(row.get(2).strip()).replaceFirst("")));
                for (String model : models) {
                    entries.add(dates.get("end_of_support").kind() == VendorDate.Kind.NONE
                            ? LifecyclePage.review(VENDOR, "HARDWARE", model, row.getFirst(), HARDWARE_REF, "MILESTONE_MISSING")
                            : LifecyclePage.entry(VENDOR, "HARDWARE", model, row.getFirst(), HARDWARE_REF, dates));
                }
            }
            if (entries.isEmpty()) return LifecyclePage.Result.notRecognised(HARDWARE_FORM);
            return new LifecyclePage.Result(true, HARDWARE_FORM, List.of(HARDWARE_REF), LifecyclePage.merge(entries), skipped);
        }
        return LifecyclePage.Result.notRecognised(HARDWARE_FORM);
    }
}
