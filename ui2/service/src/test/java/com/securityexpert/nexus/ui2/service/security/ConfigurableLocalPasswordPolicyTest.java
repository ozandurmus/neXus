package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalPasswordPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalPasswordPolicySettings;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;

/**
 * The configurable local password policy: off is exactly the original behaviour (even with strict values stored),
 * on makes each rule reject with its own closed-vocabulary reason, and the settings change is one audited write.
 * Test passwords are assembled at runtime, never written as literals.
 */
class ConfigurableLocalPasswordPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final String NAME = "breakglass-01";

    /** Builds a password from a stem and a suffix so no secret-shaped literal sits in the source. */
    private static char[] pw(String stem, String suffix) {
        return (stem + suffix).toCharArray();
    }

    private static final char[] CURRENT = pw("current".repeat(2), "x");

    private static final class FakePolicyRepo implements LocalPasswordPolicyRepository {
        LocalPasswordPolicySettings settings = LocalPasswordPolicySettings.DEFAULTS;
        final List<Argon2PasswordHasher.Verifier> retired = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        int retiredKeep = -1;

        @Override
        public Row read() {
            return new Row(settings, null, null);
        }

        @Override
        public Row write(String actorFingerprint, LocalPasswordPolicySettings next) {
            writes.add(actorFingerprint + "|" + WRITE_ACTION);
            settings = next;
            return new Row(next, NOW, actorFingerprint);
        }

        @Override
        public List<Argon2PasswordHasher.Verifier> recentRetired(String id, int limit) {
            return retired.subList(0, Math.min(limit, retired.size()));
        }

        @Override
        public void retire(String id, Argon2PasswordHasher.Verifier v, int keep) {
            retired.add(0, v);
            retiredKeep = keep;
        }
    }

    private static LocalCredentialRecord record() {
        Argon2PasswordHasher.Verifier v = Argon2PasswordHasher.hash(CURRENT, Argon2PasswordHasher.DEFAULT_PARAMETERS);
        return new LocalCredentialRecord("id-1", NAME, v.verifier(), v.salt(), v.algorithmId(),
                v.parameters().memoryCostKib(), v.parameters().timeCost(), v.parameters().parallelism(), 0,
                Optional.empty(), NOW, NOW, true, "system:bootstrap", NOW, false);
    }

    private static LocalCredentialsRepository credentials(LocalCredentialRecord r) {
        LocalCredentialsRepository repo = mock(LocalCredentialsRepository.class);
        when(repo.findByName(NAME)).thenReturn(Optional.of(r));
        return repo;
    }

    private static LocalPasswordPolicySettings on(int min, boolean up, boolean lo, boolean di, boolean sy, int hist, int age) {
        return new LocalPasswordPolicySettings(true, min, up, lo, di, sy, hist, age);
    }

    private static String code(PasswordChangeService.Result result) {
        return assertInstanceOf(PasswordChangeService.Result.PolicyViolation.class, result).violationCode();
    }

    @Test
    void offIsTodaysBehaviourEvenWhenStrictValuesAreStored() {
        FakePolicyRepo policy = new FakePolicyRepo();
        policy.settings = new LocalPasswordPolicySettings(false, 40, true, true, true, true, 5, 30);
        LocalCredentialRecord r = record();
        PasswordChangeService strictButOff = new PasswordChangeService(credentials(r), policy);
        PasswordChangeService original = new PasswordChangeService(credentials(r));

        char[] lowercaseOnly = "abcdefghijklmnop".toCharArray();
        assertInstanceOf(PasswordChangeService.Result.Ok.class, strictButOff.changePassword(NAME, CURRENT, lowercaseOnly));
        assertInstanceOf(PasswordChangeService.Result.Ok.class, original.changePassword(NAME, CURRENT, lowercaseOnly));
        // Reusing the current password is allowed today, and still is with the policy off.
        assertInstanceOf(PasswordChangeService.Result.Ok.class, strictButOff.changePassword(NAME, CURRENT, CURRENT));
        assertEquals("password_too_short", code(strictButOff.changePassword(NAME, CURRENT, "short".toCharArray())));
        assertEquals("password_equals_identity_name",
                code(strictButOff.changePassword(NAME, CURRENT, NAME.toUpperCase().toCharArray())));
        assertTrue(policy.retired.isEmpty(), "no history is kept while the policy is off");
    }

    @Test
    void onEachRuleRejectsWithItsOwnReason() {
        FakePolicyRepo policy = new FakePolicyRepo();
        PasswordChangeService service = new PasswordChangeService(credentials(record()), policy);

        policy.settings = on(16, false, false, false, false, 0, 0);
        assertEquals("password_too_short", code(service.changePassword(NAME, CURRENT, "abcdefghijklmn".toCharArray())));

        policy.settings = on(12, true, false, false, false, 0, 0);
        assertEquals("password_missing_uppercase", code(service.changePassword(NAME, CURRENT, "abcdefghijklm1!".toCharArray())));
        policy.settings = on(12, false, true, false, false, 0, 0);
        assertEquals("password_missing_lowercase", code(service.changePassword(NAME, CURRENT, "ABCDEFGHIJKLM1!".toCharArray())));
        policy.settings = on(12, false, false, true, false, 0, 0);
        assertEquals("password_missing_digit", code(service.changePassword(NAME, CURRENT, "abcdefghijklmN!".toCharArray())));
        policy.settings = on(12, false, false, false, true, 0, 0);
        assertEquals("password_missing_symbol", code(service.changePassword(NAME, CURRENT, "abcdefghijklmN1".toCharArray())));

        policy.settings = on(16, true, true, true, true, 0, 0);
        assertInstanceOf(PasswordChangeService.Result.Ok.class, service.changePassword(NAME, CURRENT, "Abcdefghijklmn1!".toCharArray()));
    }

    @Test
    void reuseOfTheLastNIsRejectedAndTheRetiredVerifierIsKept() {
        FakePolicyRepo policy = new FakePolicyRepo();
        policy.settings = on(12, false, false, false, false, 3, 0);
        LocalCredentialRecord r = record();
        PasswordChangeService service = new PasswordChangeService(credentials(r), policy);

        assertEquals("password_reused", code(service.changePassword(NAME, CURRENT, CURRENT)));

        char[] older = pw("older".repeat(3), "z");
        policy.retired.add(Argon2PasswordHasher.hash(older, Argon2PasswordHasher.DEFAULT_PARAMETERS));
        assertEquals("password_reused", code(service.changePassword(NAME, CURRENT, older)));

        // Count 2 means the current password plus one retired one: the second-newest retired is free again.
        policy.settings = on(12, false, false, false, false, 1, 0);
        assertInstanceOf(PasswordChangeService.Result.Ok.class, service.changePassword(NAME, CURRENT, older));
        assertEquals(1, policy.retiredKeep);
        assertEquals(2, policy.retired.size(), "the replaced password was retired");
    }

    @Test
    void aWrongCurrentPasswordNeverReachesThePolicy() {
        FakePolicyRepo policy = new FakePolicyRepo();
        policy.settings = on(12, true, true, true, true, 3, 0);
        LocalCredentialsRepository creds = credentials(record());
        PasswordChangeService service = new PasswordChangeService(creds, policy);
        assertInstanceOf(PasswordChangeService.Result.InvalidCredentials.class,
                service.changePassword(NAME, "wrong-current-pw".toCharArray(), "Abcdefghijklmn1!".toCharArray()));
        verify(creds, never()).changePassword(any(), any(), any());
        assertTrue(policy.retired.isEmpty());
    }

    @Test
    void maximumAgeOnlyAppliesWhenTheSwitchIsOnAndAnAgeIsSet() {
        FakePolicyRepo policy = new FakePolicyRepo();
        LocalPasswordPolicyService service = new LocalPasswordPolicyService(policy);
        LocalCredentialRecord old = record();
        Instant later = NOW.plusSeconds(100L * 86_400);

        assertFalse(service.passwordExpired(old, later), "off: never expires");
        policy.settings = new LocalPasswordPolicySettings(false, 12, false, false, false, false, 0, 30);
        assertFalse(new LocalPasswordPolicyService(policy).passwordExpired(old, later), "age stored but switch off");
        policy.settings = on(12, false, false, false, false, 0, 0);
        assertFalse(new LocalPasswordPolicyService(policy).passwordExpired(old, later), "age 0 means no expiry");
        policy.settings = on(12, false, false, false, false, 0, 30);
        assertTrue(new LocalPasswordPolicyService(policy).passwordExpired(old, later));
        assertFalse(new LocalPasswordPolicyService(policy).passwordExpired(old, NOW.plusSeconds(10L * 86_400)));
    }

    @Test
    void theSettingsChangeIsOneAuditedWriteUnderTheActorAndInvalidBodiesWriteNothing() {
        FakePolicyRepo policy = new FakePolicyRepo();
        LocalPasswordPolicyService service = new LocalPasswordPolicyService(policy);

        Map<String, Object> view = service.view(true);
        assertEquals(false, view.get("enabled"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", true);
        body.put("min_length", 14);
        body.put("history_count", 5);
        var ok = service.update("actor-fp", body);
        assertTrue(ok.isOk());
        assertEquals(List.of("actor-fp|local_password_policy_write"), policy.writes);
        assertEquals(14, ok.body().get("min_length"));
        assertEquals("actor-fp", ok.body().get("updated_by"));
        assertEquals(false, ok.body().get("require_upper"), "keys not named keep their stored value");

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("min_length", 8);
        bad.put("history_count", 99);
        bad.put("nonsense", true);
        bad.put("enabled", "yes");
        var refused = service.update("actor-fp", bad);
        assertFalse(refused.isOk());
        assertEquals(400, refused.refusal().status());
        assertEquals(4, refused.refusal().problems().size());
        assertEquals(1, policy.writes.size(), "a refused body writes nothing");

        assertEquals(403, service.update(" ", body).refusal().status());
        assertEquals(400, service.update("actor-fp", Map.of()).refusal().status());
    }

    @Test
    void theRoutesAreRegisteredAsSecurityAdminOnlyActions() {
        assertEquals(ActionRegistry.LOCAL_PASSWORD_POLICY_READ,
                SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET /api/v2/config/local-password-policy"));
        assertEquals(ActionRegistry.LOCAL_PASSWORD_POLICY_WRITE,
                SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("PUT /api/v2/config/local-password-policy"));
        var write = new ActionRegistry().find(ActionRegistry.LOCAL_PASSWORD_POLICY_WRITE).orElseThrow();
        assertEquals(java.util.Set.of(com.securityexpert.nexus.ui2.platform.RoleToken.SECURITY_ADMIN), write.requiredRoleTokens());
    }
}
