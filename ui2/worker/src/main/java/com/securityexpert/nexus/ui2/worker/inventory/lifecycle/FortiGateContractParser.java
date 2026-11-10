package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

/**
 * FortiGate {@code diagnose test update info contract} (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md section 2): the
 * lines between {@code System contracts:} and {@code Account contracts:}, each {@code CODE,weekday month day year}. One item
 * per code: {@code SUPPORT_CONTRACT} for HDWR, ENHN, COMP, SPRT and FMWR, otherwise {@code SUBSCRIPTION}; the label is the
 * code. The diagnostic lines before the block and the account block (account, company, industry, user, contract strings)
 * are never read.
 */
public final class FortiGateContractParser {

    public static final String READ_KIND = "fgt_update_info_contract";
    private static final Set<String> SUPPORT_CODES = Set.of("HDWR", "ENHN", "COMP", "SPRT", "FMWR");
    private static final Pattern ROW = Pattern.compile("([A-Za-z0-9_]{2,16}) *, *(.*)");

    private FortiGateContractParser() {
    }

    /** Never throws. */
    public static LicenseParse parse(String raw) {
        try {
            return doParse(raw);
        } catch (RuntimeException | StackOverflowError e) {
            return raw == null || raw.isBlank() ? LicenseParse.of(raw, List.of(), 0) : LicenseParse.unparsed();
        }
    }

    private static LicenseParse doParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return LicenseParse.of(raw, List.of(), 0);
        }
        boolean inBlock = false;
        boolean seen = false;
        List<LicenseItem> items = new ArrayList<>();
        int skipped = 0;
        for (String line : LifecycleText.lines(raw)) {
            String t = LifecycleText.collapse(line);
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.equals("system contracts:")) {
                inBlock = true;
                seen = true;
                continue;
            }
            if (lower.equals("account contracts:")) {
                if (seen) {
                    break;
                }
                continue;
            }
            if (!inBlock || t.isEmpty()) {
                continue;
            }
            Matcher m = ROW.matcher(t);
            if (!m.matches()) {
                continue;
            }
            String code = m.group(1).toUpperCase(Locale.ROOT);
            String label = LifecycleText.label(code);
            if (label == null) {
                skipped++;
                continue;
            }
            LocalDate date = LifecycleText.fortinetDate(m.group(2));
            items.add(new LicenseItem(READ_KIND, SUPPORT_CODES.contains(code) ? "SUPPORT_CONTRACT" : "SUBSCRIPTION", label,
                    date == null ? "UNKNOWN" : "VALID", date, null, null, "DIRECT_DEVICE", null));
        }
        if (!seen) {
            return LicenseParse.unparsed();
        }
        return items.isEmpty() ? (skipped > 0 ? new LicenseParse(ParseOutcome.UNPARSED, List.of(), skipped) : LicenseParse.empty(0))
                : new LicenseParse(ParseOutcome.COLLECTED, items, skipped);
    }
}
