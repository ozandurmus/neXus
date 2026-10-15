package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.Map;
import java.util.Optional;

/**
 * The injected device-name pseudonym function (DATA_SANITIZER_CONTRACT.md §2 "Known device names"): the service
 * passes the neXus inventory's names with their {@code TopologyNamePseudonymizer} pseudonyms ({@code FW-TANGO-04}),
 * so a device reads the same in the UI, in exports and in sanitized files. Matching is exact and case-sensitive,
 * like the UI's own masking; names shorter than three characters or all digits are never matched.
 */
public interface KnownNames {

    KnownNames NONE = name -> Optional.empty();

    /** The pseudonym for an exact inventory name, if it is one. */
    Optional<String> pseudonymFor(String name);

    /** A fixed snapshot (tests, or a service-built map of name to pseudonym). */
    static KnownNames of(Map<String, String> pseudonyms) {
        Map<String, String> copy = Map.copyOf(pseudonyms);
        return name -> name.length() < 3 || name.chars().allMatch(Character::isDigit)
                ? Optional.empty() : Optional.ofNullable(copy.get(name));
    }
}
