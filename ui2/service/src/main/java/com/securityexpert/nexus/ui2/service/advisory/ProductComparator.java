package com.securityexpert.nexus.ui2.service.advisory;

import java.util.Optional;

/**
 * Strict version handling for one product (contract section 6). A string the comparator does not fully understand is
 * empty, never trimmed or guessed into shape.
 */
public interface ProductComparator {

    /** The product key this comparator serves. */
    String productKey();

    /**
     * The canonical form of a stored device version (for example {@code v7.4.12 build2902} to {@code 7.4.12},
     * {@code 9.18(4)50} to {@code 9.18.4.50}), or empty when it cannot be parsed in full.
     */
    Optional<String> canonical(String storedVersion);

    /** The release train of a canonical version (for example {@code 11.1}), or empty when it is not a canonical version. */
    Optional<String> train(String canonicalVersion);

    /** Orders two canonical versions of the same product; throws {@link IllegalArgumentException} on a non-canonical input. */
    int compare(String canonicalA, String canonicalB);

    /**
     * A fixed code word when a device with this stored version has no advisory source in this product although the product
     * has one (Check Point: a Gaia train without a resolved-issues page, a Quantum Spark firmware); empty otherwise.
     */
    default Optional<String> notCoveredReason(String storedVersion) {
        return Optional.empty();
    }

    /** The code word for a stored version {@link #canonical} cannot read (default {@code VERSION_UNPARSED}). */
    default String unparsedReason(String storedVersion) {
        return "VERSION_UNPARSED";
    }
}
