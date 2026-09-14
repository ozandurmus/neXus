package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code vsx stat -v} (14C §3): VSIDs only -- "the VSID column is the only
 * trustworthy one," the status column is policy/SIC state, never HA state.
 * {@code UNVERIFIED} against a real VSX host. Rows begin with the VSID
 * then {@code |}; the header row (containing the literal {@code VSID})
 * is skipped. VS0 is included in the parsed list -- the caller decides not
 * to re-enter it (14C §3: "VS0 is the physical context and is not
 * re-entered").
 */
public final class CheckPointVsxStatParser {

    private CheckPointVsxStatParser() {
    }

    public static List<String> parseVsids(String output) {
        List<String> vsids = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return vsids;
        }
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || !line.contains("|")) {
                continue;
            }
            String first = line.substring(0, line.indexOf('|')).trim();
            if (first.isEmpty() || first.equalsIgnoreCase("VSID")) {
                continue;
            }
            vsids.add(first);
        }
        return vsids;
    }
}
