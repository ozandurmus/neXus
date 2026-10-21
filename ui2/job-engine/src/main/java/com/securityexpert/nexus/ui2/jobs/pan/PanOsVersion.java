package com.securityexpert.nexus.ui2.jobs.pan;

import java.util.OptionalInt;

/** Pure helper: the leading major number of a PAN-OS version string such as 11.1.4-h7, 10.2.9 or 12.1.2. */
public final class PanOsVersion {

    private PanOsVersion() {
    }

    /** Empty for null, blank or a string that does not start with digits followed by end, '.' or '-'. */
    public static OptionalInt major(String version) {
        if (version == null) {
            return OptionalInt.empty();
        }
        String v = version.strip();
        int end = 0;
        while (end < v.length() && v.charAt(end) >= '0' && v.charAt(end) <= '9') {
            end++;
        }
        if (end == 0 || end > 4) {
            return OptionalInt.empty();
        }
        if (end < v.length() && v.charAt(end) != '.' && v.charAt(end) != '-') {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(v.substring(0, end)));
    }
}
