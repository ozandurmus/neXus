package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

/**
 * FortiGate {@code diagnose autoupdate versions} (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md section 2). A block is a
 * title line followed by a rule line of dashes; inside it the first {@code Contract Expiry Date:} and the first
 * {@code Result:} are read. One {@code SUBSCRIPTION} per block that has a contract line: label the title, detail the result
 * words; {@code n/a} (or a date that cannot be read) gives {@code UNKNOWN}. Version, update and attempt lines and the
 * server address line are never read.
 */
public final class FortiGateAutoupdateParser {

    public static final String READ_KIND = "fgt_autoupdate_versions";
    private static final Pattern RULE = Pattern.compile("-{3,}");
    private static final String CONTRACT = "contract expiry date:";
    private static final String RESULT = "result:";

    private FortiGateAutoupdateParser() {
    }

    /** Never throws. */
    public static LicenseParse parse(String raw) {
        try {
            return doParse(raw);
        } catch (RuntimeException | StackOverflowError e) {
            return raw == null || raw.isBlank() ? LicenseParse.of(raw, List.of(), 0) : LicenseParse.unparsed();
        }
    }

    private static final class Block {
        final String title;
        String contract;
        String result;

        Block(String title) {
            this.title = title;
        }
    }

    private static LicenseParse doParse(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : LifecycleText.lines(raw)) {
            String t = LifecycleText.collapse(line);
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }
        List<Block> blocks = new ArrayList<>();
        Block current = null;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i);
            if (i + 1 < lines.size() && RULE.matcher(lines.get(i + 1)).matches() && !RULE.matcher(t).matches()) {
                current = new Block(t);
                blocks.add(current);
                continue;
            }
            if (current == null) {
                continue;
            }
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.startsWith(CONTRACT) && current.contract == null) {
                current.contract = t.substring(CONTRACT.length()).strip();
            } else if (lower.startsWith(RESULT) && current.result == null) {
                current.result = t.substring(RESULT.length()).strip();
            }
        }
        List<LicenseItem> items = new ArrayList<>();
        int skipped = 0;
        for (Block block : blocks) {
            if (block.contract == null) {
                continue;
            }
            String label = LifecycleText.label(block.title);
            if (label == null) {
                skipped++;
                continue;
            }
            LocalDate date = LifecycleText.fortinetDate(block.contract);
            String detail = block.result == null || block.result.isEmpty() ? null : LifecycleText.detail(block.result);
            items.add(new LicenseItem(READ_KIND, "SUBSCRIPTION", label, date == null ? "UNKNOWN" : "VALID", date, detail, null,
                    "DIRECT_DEVICE", null));
        }
        return LicenseParse.of(raw, items, skipped);
    }
}
