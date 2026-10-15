package com.securityexpert.nexus.ui2.persistence.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    /**
     * The group set is read back from {@code jsonb}, which PostgreSQL renders
     * with a space after each comma. The former parser kept that space, so
     * every group after the first came back as " cn=..." and never matched a
     * binding: a held role was denied, and the self-grant check missed a group
     * the admin already held.
     */
    @Test
    void parsesPostgresJsonbRenderingWithSpacesAndEscapes() {
        assertEquals(Set.of("cn=ops,ou=groups,dc=example,dc=com", "cn=admins,ou=groups,dc=example,dc=com",
                        "cn=weird\"group\\name,dc=example,dc=com", "cn=\u00fcnicode,dc=example,dc=com"),
                JooqActorAuthzStateRepository.fromJsonArray("[\"cn=ops,ou=groups,dc=example,dc=com\", "
                        + "\"cn=admins,ou=groups,dc=example,dc=com\", \"cn=weird\\\"group\\\\name,dc=example,dc=com\", "
                        + "\"cn=\\u00fcnicode,dc=example,dc=com\"]"));
        assertEquals(Set.of(), JooqActorAuthzStateRepository.fromJsonArray("[]"));
    }

    @Test
    void refusesAnythingButAJsonArrayOfStrings() {
        for (String malformed : new String[] {"{}", "[1]", "[\"a\" \"b\"]", "[\"a\",]", "[\"a"}) {
            assertThrows(IllegalStateException.class, () -> JooqActorAuthzStateRepository.fromJsonArray(malformed),
                    malformed);
        }
    }
}
