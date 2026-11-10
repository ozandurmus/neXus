package com.securityexpert.nexus.ui2.service.advisory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed PAN-OS version {@code <major>.<minor>.<maintenance>[-h<hotfix>]} (contract section 6). A missing hotfix is 0. Any other
 * suffix or shape is not a version. Numbers have no leading zeros and at most four digits.
 */
record PanOsVersion(int major, int minor, int maintenance, int hotfix) implements Comparable<PanOsVersion> {

    private static final Pattern FORM = Pattern.compile("(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})(?:-h(0|[1-9][0-9]{0,3}))?");

    static Optional<PanOsVersion> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORM.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        int hotfix = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
        return Optional.of(new PanOsVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), hotfix));
    }

    String train() {
        return major + "." + minor;
    }

    boolean sameMaintenanceRelease(PanOsVersion other) {
        return major == other.major && minor == other.minor && maintenance == other.maintenance;
    }

    @Override
    public int compareTo(PanOsVersion o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        if (c == 0) {
            c = Integer.compare(maintenance, o.maintenance);
        }
        if (c == 0) {
            c = Integer.compare(hotfix, o.hotfix);
        }
        return c;
    }
}
