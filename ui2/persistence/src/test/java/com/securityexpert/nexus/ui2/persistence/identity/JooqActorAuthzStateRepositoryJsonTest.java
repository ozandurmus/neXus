package com.securityexpert.nexus.ui2.persistence.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Container-free unit test for the JSONB group-reference round trip used by
 * {@link JooqActorAuthzStateRepository} (no database is touched).
 */
class JooqActorAuthzStateRepositoryJsonTest {

    @Test
    void roundTripsAnEmptySet() {
        assertEquals(Set.of(), JooqActorAuthzStateRepository.fromJsonArray(
                JooqActorAuthzStateRepository.toJsonArray(Set.of())));
    }

    @Test
    void roundTripsGroupReferencesIncludingQuotesAndBackslashes() {
        Set<String> original = new LinkedHashSet<>();
        original.add("cn=ops,ou=groups,dc=example,dc=com");
        original.add("cn=weird\"group\\name,dc=example,dc=com");

        String json = JooqActorAuthzStateRepository.toJsonArray(original);
        Set<String> roundTripped = JooqActorAuthzStateRepository.fromJsonArray(json);

        assertEquals(original, roundTripped);
    }
}
