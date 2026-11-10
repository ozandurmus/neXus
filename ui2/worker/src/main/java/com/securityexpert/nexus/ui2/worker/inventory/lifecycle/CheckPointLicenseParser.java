package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

/**
 * Check Point {@code cplic print} (Gaia gateway, VSX gateway, management server) and Spark {@code show license}: one parser
 * (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md section 2).
 *
 * <p>Read: the lines after {@code Host Expiration Features} (second token {@code never} or a {@code dMMMyyyy} date; the
 * first feature token is the label, the feature count the detail) and the rows of the {@code Contract Coverage:} table
 * ({@code # | ID | Expiration | SKU}; the SKU is the label). Never read: the host column, the {@code CK-} key, the contract
 * ID, the continuation lines starting with {@code |}, and any line before the header (the {@code vsenv} context line).</p>
 */
public final class CheckPointLicenseParser {

    private static final String HEADER = "host expiration features";
    private static final String CONTRACTS = "contract coverage:";
    private static final String EVIDENCE = "DIRECT_DEVICE";

    private CheckPointLicenseParser() {
    }

    /** {@code readKind}: the token stored on the items (cp_cplic_print or cp_spark_show_license). Never throws. */
    public static LicenseParse parse(String readKind, String raw) {
        try {
            return doParse(readKind, raw);
        } catch (RuntimeException | StackOverflowError e) {
            return raw == null || raw.isBlank() ? LicenseParse.of(raw, List.of(), 0) : LicenseParse.unparsed();
        }
    }

    private static LicenseParse doParse(String readKind, String raw) {
        List<LicenseItem> items = new ArrayList<>();
        int skipped = 0;
        int state = 0; // 0 before the header, 1 license lines, 2 contract table
        for (String line : LifecycleText.lines(raw)) {
            String t = LifecycleText.collapse(line);
            if (t.isEmpty() || t.startsWith("|")) {
                continue;
            }
            String lower = t.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals(HEADER)) {
                state = 1;
                continue;
            }
            if (lower.startsWith(CONTRACTS)) {
                state = 2;
                continue;
            }
            if (state == 1) {
                int outcome = licenseLine(readKind, t, items);
                skipped += outcome < 0 ? 1 : 0;
            } else if (state == 2) {
                int outcome = contractRow(readKind, t, items);
                skipped += outcome < 0 ? 1 : 0;
            }
        }
        return LicenseParse.of(raw, items, skipped);
    }

    /** 1 item added, 0 not a license line, -1 a license line without a clean label. */
    private static int licenseLine(String readKind, String line, List<LicenseItem> items) {
        String[] tokens = line.split(" ");
        if (tokens.length < 3) {
            return 0;
        }
        String when = tokens[1];
        boolean never = when.equalsIgnoreCase("never");
        boolean dateShaped = LifecycleText.looksLikeCheckPointDate(when);
        boolean keyLast = tokens[tokens.length - 1].startsWith("CK-");
        if (!never && !dateShaped && !keyLast) {
            return 0;
        }
        int end = keyLast ? tokens.length - 1 : tokens.length;
        int features = end - 2;
        if (features < 1) {
            return -1;
        }
        String label = LifecycleText.label(tokens[2]);
        if (label == null || label.startsWith("CK-")) {
            return -1;
        }
        String state;
        LocalDate expires = null;
        if (never) {
            state = "PERMANENT";
        } else {
            expires = LifecycleText.checkPointDate(when);
            state = expires == null ? "UNKNOWN" : "VALID";
        }
        items.add(new LicenseItem(readKind, "LICENSE", label, state, expires, features + (features == 1 ? " feature" : " features"),
                null, EVIDENCE, null));
        return 1;
    }

    /** 1 item added, 0 not a contract row, -1 a contract row without a clean label. */
    private static int contractRow(String readKind, String line, List<LicenseItem> items) {
        String[] cols = line.split("\\|", -1);
        if (cols.length < 4 || !LifecycleText.isDigits(cols[0].strip())) {
            return 0;
        }
        String label = LifecycleText.label(cols[3]);
        if (label == null) {
            return -1;
        }
        String when = LifecycleText.collapse(cols[2]);
        String state;
        LocalDate expires = null;
        if (when.equalsIgnoreCase("never")) {
            state = "PERMANENT";
        } else {
            expires = LifecycleText.checkPointDate(when);
            state = expires == null ? "UNKNOWN" : "VALID";
        }
        items.add(new LicenseItem(readKind, "SUPPORT_CONTRACT", label, state, expires, null, null, EVIDENCE, null));
        return 1;
    }
}
