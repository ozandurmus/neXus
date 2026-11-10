package com.securityexpert.nexus.ui2.service.advisory;

import java.util.Optional;

/**
 * {@code paloalto:pan-os}: {@code 11.1.10-h7} or {@code 11.1.10}; canonical is the same form; train {@code 11.1}; ordered by
 * maintenance release, then hotfix (no hotfix = 0). Any other suffix (for example {@code -c12}) is unparsed.
 */
public final class PanOsComparator implements ProductComparator {

    @Override
    public String productKey() {
        return AdvisoryModel.PRODUCT_PAN_OS;
    }

    @Override
    public Optional<String> canonical(String storedVersion) {
        return PanOsVersion.parse(storedVersion).map(v -> storedVersion);
    }

    @Override
    public Optional<String> train(String canonicalVersion) {
        return PanOsVersion.parse(canonicalVersion).map(PanOsVersion::train);
    }

    @Override
    public int compare(String canonicalA, String canonicalB) {
        return require(canonicalA).compareTo(require(canonicalB));
    }

    private static PanOsVersion require(String v) {
        return PanOsVersion.parse(v).orElseThrow(() -> new IllegalArgumentException("not a canonical PAN-OS version"));
    }
}
