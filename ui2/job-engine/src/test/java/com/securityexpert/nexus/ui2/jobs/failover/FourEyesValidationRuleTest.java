package com.securityexpert.nexus.ui2.jobs.failover;

import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverAuthorizationRequest;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverLeaseToken;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FourEyesAuthorizationResult;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FourEyesValidationRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class FourEyesValidationRuleTest {

    private static final byte[] SECRET_KEY = "test-secret-key-at-least-16-bytes!".getBytes(StandardCharsets.UTF_8);
    private static final Instant FIXED_NOW = Instant.parse("2026-09-20T12:00:00Z");

    private MutableClock testClock;
    private FourEyesValidationRule rule;

    @BeforeEach
    void setUp() {
        testClock = new MutableClock(FIXED_NOW);
        rule = new FourEyesValidationRule(SECRET_KEY, testClock);
    }

    @Test
    @DisplayName("Refuses authorization when requester and approver are the same identity (4-Eyes violation)")
    void refusesSameRequesterAndApprover() {
        var request = new FailoverAuthorizationRequest(
            "cls-uuid-001",
            "operator-alice",
            "operator-alice",
            "Disaster recovery drill ticket CHG-9921",
            "CHG-9921",
            "sha256:preflightdigest12345",
            "client-nonce-001"
        );

        FourEyesAuthorizationResult result = rule.authorize(request);
        assertInstanceOf(FourEyesAuthorizationResult.Refused.class, result);
        var refused = (FourEyesAuthorizationResult.Refused) result;
        assertEquals("FOUR_EYES_IDENTITY_COLLISION", refused.code());
        assertTrue(refused.reason().contains("distinct authenticated individuals"));
    }

    @Test
    @DisplayName("Refuses authorization when reason is shorter than 8 characters")
    void refusesShortReason() {
        var request = new FailoverAuthorizationRequest(
            "cls-uuid-001",
            "operator-alice",
            "operator-bob",
            "drill", // 5 chars
            "CHG-9921",
            "sha256:preflightdigest12345",
            "client-nonce-001"
        );

        FourEyesAuthorizationResult result = rule.authorize(request);
        assertInstanceOf(FourEyesAuthorizationResult.Refused.class, result);
        var refused = (FourEyesAuthorizationResult.Refused) result;
        assertEquals("REASON_TOO_SHORT", refused.code());
    }

    @Test
    @DisplayName("Issues valid lease token for distinct requester and approver with valid reason")
    void authorizesDistinctOperators() {
        var request = new FailoverAuthorizationRequest(
            "cls-uuid-001",
            "operator-alice",
            "operator-bob",
            "Disaster recovery drill ticket CHG-9921",
            "CHG-9921",
            "sha256:preflightdigest12345",
            "client-nonce-001"
        );

        FourEyesAuthorizationResult result = rule.authorize(request);
        assertInstanceOf(FourEyesAuthorizationResult.Authorized.class, result);
        FailoverLeaseToken token = ((FourEyesAuthorizationResult.Authorized) result).leaseToken();

        assertEquals("cls-uuid-001", token.clusterRef());
        assertEquals("operator-alice", token.requesterId());
        assertEquals("operator-bob", token.approverId());
        assertEquals("sha256:preflightdigest12345", token.assessmentDigest());
        assertEquals(FIXED_NOW.plus(Duration.ofMinutes(15)), token.expiresAt());
        assertNotNull(token.tokenSignature());

        // Verify valid signature
        assertTrue(rule.verifyTokenSignature(token, "client-nonce-001"));
        // Rejects mismatched nonce
        assertFalse(rule.verifyTokenSignature(token, "tampered-nonce"));
    }

    @Test
    @DisplayName("Rejects expired lease token after 15 minutes window")
    void rejectsExpiredToken() {
        var request = new FailoverAuthorizationRequest(
            "cls-uuid-001",
            "operator-alice",
            "operator-bob",
            "Scheduled failover maintenance ticket CHG-8802",
            "CHG-8802",
            "sha256:preflightdigest12345",
            "client-nonce-001"
        );

        FourEyesAuthorizationResult result = rule.authorize(request);
        FailoverLeaseToken token = ((FourEyesAuthorizationResult.Authorized) result).leaseToken();

        // Advance clock by 15 minutes and 1 second
        testClock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertFalse(rule.verifyTokenSignature(token, "client-nonce-001"));
        assertTrue(token.isExpired(testClock.instant()));
    }

    private static class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
