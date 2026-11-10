package com.securityexpert.nexus.ui2.worker.inventory;

import com.securityexpert.nexus.ui2.worker.configuration.ConfigurationCapabilityExecutor;

/**
 * The shape of a Check Point {@code show hostname} answer as counts and a class, for the 2026-11-11 measurement of three
 * devices whose observed hostname stayed unknown. Raw-evidence law: nothing of the text survives, only the exit status,
 * the line count, the 1-based index of the first line that is exactly one hostname token (0 when none), whether line 1
 * is a {@code CLINFR<digits>} message and a coarse class of line 1.
 */
final class HostnameReadShape {

    private HostnameReadShape() {
    }

    static String describe(String output, int exitStatus) {
        String[] lines = output == null || output.isEmpty() ? new String[0] : output.split("\\R", -1);
        int count = lines.length;
        // A trailing newline yields a last empty element: it is not a line of its own.
        if (count > 0 && lines[count - 1].isEmpty()) {
            count--;
        }
        int firstToken = 0;
        for (int i = 0; i < count; i++) {
            if (ConfigurationCapabilityExecutor.isHostnameToken(lines[i])) {
                firstToken = i + 1;
                break;
            }
        }
        String first = count == 0 ? "" : lines[0].strip();
        boolean line1Clinfr = ConfigurationCapabilityExecutor.isCliMessageLine(first);
        String line1Class = count == 0 || first.isEmpty() ? "empty"
                : line1Clinfr ? "warning"
                : ConfigurationCapabilityExecutor.isHostnameToken(first) ? "token" : "other";
        return "exit=" + exitStatus + " lines=" + count + " first_token_line=" + firstToken
                + " line1_clinfr=" + line1Clinfr + " line1_class=" + line1Class;
    }
}
