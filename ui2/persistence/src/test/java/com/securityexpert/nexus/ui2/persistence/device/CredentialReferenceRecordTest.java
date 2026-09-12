package com.securityexpert.nexus.ui2.persistence.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * B1-4b contract §8 test 4 ({@code credential_reference_never_reveals_value}),
 * the structural half provable without a database: {@link
 * CredentialReferenceRecord} is a closed Java {@code record}, so its
 * complete field set is enumerable by reflection. This test fails if a
 * future edit ever adds a field beyond the four contract §6 names -- the
 * only way this type could ever be made to "reveal a value" is by adding
 * one, and this test catches that at compile-adjacent time, not runtime.
 */
class CredentialReferenceRecordTest {

    private static final Set<String> ALLOWED_FIELDS =
            Set.of("credentialReferenceId", "purpose", "backendPointer", "createdAt");

    @Test
    void hasExactlyTheFourContractFieldsAndNothingElse() {
        RecordComponent[] components = CredentialReferenceRecord.class.getRecordComponents();
        Set<String> actual = Arrays.stream(components).map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(ALLOWED_FIELDS, actual,
                "CredentialReferenceRecord must never grow a field beyond contract §6's four: "
                        + "id, purpose, backend_pointer, created_at -- any other field is a place a secret could hide");
    }

    @Test
    void backendPointerIsTheOnlyReferenceFieldAndItIsOpaqueText() {
        CredentialReferenceRecord record = new CredentialReferenceRecord("cred-ref-1", "device_read",
                "vault://synthetic/opaque-pointer", Instant.now());

        // toString() -- the read path a log line or exception message
        // would use if one carried this record -- exposes only these four
        // values, never anything computed from a resolved secret (there is
        // nothing in this type from which one could be computed).
        String rendered = record.toString();
        assertTrue(rendered.contains("cred-ref-1"));
        assertTrue(rendered.contains("device_read"));
        assertTrue(rendered.contains("vault://synthetic/opaque-pointer"));
        assertFalse(rendered.toLowerCase().contains("password"));
        assertFalse(rendered.toLowerCase().contains("secret"));
    }
}
