package com.securityexpert.nexus.ui2.discovery.pan;

import java.util.Optional;

/**
 * VS-1/VS-2: one virtual-system entry nested inside its host device's own
 * enumeration entry. Carries its own identifier (scoped to its parent, ID-1
 * applies to it exactly as it applies to a device serial), its own display
 * name (ID-3: never a join key), and the three shared-policy elements under
 * their own role names (VS-2; VD-4: their individual meaning is out of
 * scope). No host reference field: VS-3 makes the host relationship
 * structural — it is the nesting itself, read off by
 * {@link CandidateRowAssembler}, never a field to compare.
 */
public record RawVirtualSystemInput(
        Serial stableIdentifier,
        String displayName,
        Optional<String> sharedPolicyElementOne,
        Optional<String> sharedPolicyElementTwo,
        Optional<String> sharedPolicyElementThree) {
}
