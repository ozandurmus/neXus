package com.securityexpert.nexus.ui2.service.advisory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code fortinet:fortios} and {@code fortinet:fortimanager}: stored {@code v7.4.12 build2902} (also {@code 7.4.12} and
 * {@code v7.4.12}); canonical {@code 7.4.12}; train {@code 7.4}. The build number is ignored; anything else is unparsed.
 */
public final class FortinetComparator implements ProductComparator {

    private static final Pattern STORED = Pattern.compile("v?(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})(?: build[0-9]{1,6})?");
    private static final Pattern CANONICAL = Pattern.compile("(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})");

    private final String productKey;

    public FortinetComparator(String productKey) {
        this.productKey = productKey;
    }

    @Override
    public String productKey() {
        return productKey;
    }

    @Override
    public Optional<String> canonical(String storedVersion) {
        if (storedVersion == null) {
            return Optional.empty();
        }
        Matcher m = STORED.matcher(storedVersion);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(m.group(1) + "." + m.group(2) + "." + m.group(3));
    }

    @Override
    public Optional<String> train(String canonicalVersion) {
        if (canonicalVersion == null) {
            return Optional.empty();
        }
        Matcher m = CANONICAL.matcher(canonicalVersion);
        return m.matches() ? Optional.of(m.group(1) + "." + m.group(2)) : Optional.empty();
    }

    @Override
    public int compare(String canonicalA, String canonicalB) {
        int[] a = parts(canonicalA);
        int[] b = parts(canonicalB);
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int[] parts(String v) {
        Matcher m = v == null ? null : CANONICAL.matcher(v);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("not a canonical Fortinet version");
        }
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }
}
