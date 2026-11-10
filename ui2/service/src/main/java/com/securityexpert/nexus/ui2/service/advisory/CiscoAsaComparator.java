package com.securityexpert.nexus.ui2.service.advisory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code cisco:asa}: stored {@code 9.18(4)50} gives {@code 9.18.4.50}; {@code 9.18(4)} gives {@code 9.18.4}; an already dotted
 * {@code 9.18.4.50} (three or four parts) is accepted; train {@code 9.18}. A missing last part is not the version with
 * {@code .0}: {@code 9.18.4} orders before {@code 9.18.4.0} and the two are never equal.
 */
public final class CiscoAsaComparator implements ProductComparator {

    private static final String N = "(0|[1-9][0-9]{0,3})";
    private static final Pattern PAREN = Pattern.compile(N + "\\." + N + "\\(" + N + "\\)(?:" + N + ")?");
    private static final Pattern DOTTED = Pattern.compile(N + "\\." + N + "\\." + N + "(?:\\." + N + ")?");

    @Override
    public String productKey() {
        return AdvisoryModel.PRODUCT_CISCO_ASA;
    }

    @Override
    public Optional<String> canonical(String storedVersion) {
        if (storedVersion == null) {
            return Optional.empty();
        }
        Matcher p = PAREN.matcher(storedVersion);
        if (p.matches()) {
            String base = p.group(1) + "." + p.group(2) + "." + p.group(3);
            return Optional.of(p.group(4) == null ? base : base + "." + p.group(4));
        }
        return DOTTED.matcher(storedVersion).matches() ? Optional.of(storedVersion) : Optional.empty();
    }

    @Override
    public Optional<String> train(String canonicalVersion) {
        if (canonicalVersion == null) {
            return Optional.empty();
        }
        Matcher m = DOTTED.matcher(canonicalVersion);
        return m.matches() ? Optional.of(m.group(1) + "." + m.group(2)) : Optional.empty();
    }

    @Override
    public int compare(String canonicalA, String canonicalB) {
        int[] a = parts(canonicalA);
        int[] b = parts(canonicalB);
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static int[] parts(String v) {
        Matcher m = v == null ? null : DOTTED.matcher(v);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("not a canonical Cisco ASA version");
        }
        int n = m.group(4) == null ? 3 : 4;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = Integer.parseInt(m.group(i + 1));
        }
        return out;
    }
}
