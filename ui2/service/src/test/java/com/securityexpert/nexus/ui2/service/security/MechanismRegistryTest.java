package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.Clock;
import com.securityexpert.nexus.ui2.platform.Mechanism;

/**
 * C3A contract §11.2 test 11 (registry membership: {@code local} cannot be
 * disabled) and test 12 (mechanism-independent outcome reporting), proved
 * without a database.
 */
class MechanismRegistryTest {

    private static final class NoOpRepository implements LocalCredentialsRepository {
        @Override
        public Optional<LocalCredentialRecord> findByName(String localIdentityName) {
            return Optional.empty();
        }

        @Override
        public Optional<LocalCredentialRecord> findById(String localIdentityId) {
            return Optional.empty();
        }

        @Override
        public String create(String localIdentityId, String localIdentityName, Argon2PasswordHasher.Verifier verifier,
                String createdByActorFingerprint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean anyExist() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordFailedAttempt(String localIdentityId, Instant now, int lockoutThreshold,
                java.time.Duration lockoutDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordSuccessfulLogin(String localIdentityId, Instant now, String actorFingerprint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void changePassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String changedByActorFingerprint) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void localIsUnconditionallyPresentWithNoAdditionalMechanismRegistered() {
        MechanismRegistry registry = new MechanismRegistry(
                new LocalMechanism(new NoOpRepository(), Clock.system()), List.of());

        assertTrue(registry.isRegistered("local"));
        assertTrue(registry.find("local").isPresent());
    }

    @Test
    void thereIsNoConstructorOrConfigurationPathThatOmitsLocal() {
        // Structural, not behavioural: MechanismRegistry has exactly one
        // public constructor, and its first parameter is the concrete
        // LocalMechanism type -- not Mechanism, not Optional<Mechanism> --
        // so no caller can construct a registry without one (§2.1, AC-2).
        var constructors = MechanismRegistry.class.getConstructors();
        assertEquals(1, constructors.length, "MechanismRegistry must have exactly one constructor");
        assertEquals(LocalMechanism.class, constructors[0].getParameterTypes()[0],
                "the registry's first constructor parameter must be the concrete LocalMechanism type");
    }

    @Test
    void anAdditionalRegisteredMechanismReportsThroughTheIdenticalOutcomeShape() {
        Mechanism stubbedSecondMechanism = new Mechanism() {
            @Override
            public String mechanismId() {
                return "stub";
            }

            @Override
            public AttemptOutcome attempt(String identity, char[] credential) {
                return AttemptOutcome.refused("stub_refused");
            }
        };
        MechanismRegistry registry = new MechanismRegistry(
                new LocalMechanism(new NoOpRepository(), Clock.system()), List.of(stubbedSecondMechanism));

        AttemptOutcome fromStub = registry.find("stub").orElseThrow().attempt("x", "y".toCharArray());
        AttemptOutcome fromLocal = registry.find("local").orElseThrow().attempt("x", "y".toCharArray());

        // A caller (LoginController) branches on the outcome's type alone --
        // both are AttemptOutcome.Refused, regardless of mechanism_id.
        assertEquals(fromStub.getClass(), fromLocal.getClass());
    }
}
