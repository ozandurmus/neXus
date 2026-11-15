package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.LocalAuthModeRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalBreakGlassEventRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RootIdentityRepository;
import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.Clock;

/**
 * Migration plan Step 3 / PO decision D4: ALL_LOCAL changes nothing; BREAK_GLASS_ONLY admits only the root identity and
 * aiview, refuses everyone else after the Argon2 check without counting a failure, and the switch refuses to lock the
 * acting administrator out.
 */
class BreakGlassAuthenticationTest {
    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
    private static final char[] PASSWORD = "correct-horse-battery-staple".toCharArray();
    private static final Argon2PasswordHasher.Verifier VERIFIER =
            Argon2PasswordHasher.hash(PASSWORD, Argon2PasswordHasher.DEFAULT_PARAMETERS);

    private final Map<String, LocalCredentialRecord> byName = new HashMap<>();
    private LocalCredentialsRepository credentials;
    private LocalAuthModeRepository modes;
    private RootIdentityRepository root;
    private LocalBreakGlassEventRepository events;
    private LocalAuthPolicy policy;
    private LocalMechanism mechanism;
    private String currentMode = LocalAuthModeRepository.ALL_LOCAL;

    private LocalCredentialRecord seed(String name, boolean enabled) {
        var r = new LocalCredentialRecord("id-" + name, name, VERIFIER.verifier(), VERIFIER.salt(), VERIFIER.algorithmId(),
                VERIFIER.parameters().memoryCostKib(), VERIFIER.parameters().timeCost(), VERIFIER.parameters().parallelism(),
                0, Optional.empty(), NOW, NOW, enabled, "system:bootstrap", NOW, false);
        byName.put(name, r);
        return r;
    }

    @BeforeEach
    void wire() {
        credentials = mock(LocalCredentialsRepository.class);
        when(credentials.findByName(anyString())).thenAnswer(i -> Optional.ofNullable(byName.get(i.<String>getArgument(0))));
        when(credentials.findById(anyString())).thenAnswer(i -> byName.values().stream()
                .filter(r -> r.localIdentityId().equals(i.<String>getArgument(0))).findFirst());
        when(credentials.findAll()).thenAnswer(i -> List.copyOf(byName.values()));
        modes = mock(LocalAuthModeRepository.class);
        when(modes.read()).thenAnswer(i -> new LocalAuthModeRepository.Row(currentMode, null, null));
        when(modes.write(anyString(), anyString())).thenAnswer(i -> {
            currentMode = i.getArgument(1);
            return new LocalAuthModeRepository.Row(currentMode, NOW, i.getArgument(0));
        });
        root = mock(RootIdentityRepository.class);
        when(root.rootLocalIdentityId()).thenReturn(Optional.of("id-nexusadmin"));
        events = mock(LocalBreakGlassEventRepository.class);
        policy = new LocalAuthPolicy(modes, root, events);
        Clock clock = () -> NOW;
        mechanism = new LocalMechanism(credentials, clock, policy);
        for (String name : List.of("nexusadmin", "claudeadmin", "aiview", "aiview-e2e", "someone")) {
            seed(name, true);
        }
    }

    private AttemptOutcome login(String name) {
        return mechanism.attempt(name, PASSWORD.clone());
    }

    @Test
    void defaultModeKeepsEveryLocalLoginAndWritesNoBreakGlassEvent() {
        for (String name : List.of("nexusadmin", "claudeadmin", "aiview", "aiview-e2e", "someone")) {
            assertInstanceOf(AttemptOutcome.Success.class, login(name), name);
        }
        verify(events, never()).recordLoginSucceeded(anyString(), anyString(), anyBoolean());
        verify(events, never()).recordRefused(anyString());
    }

    @Test
    void breakGlassOnlyAdmitsRootAndAiviewAndRefusesTheRestWithoutCountingAFailure() {
        currentMode = LocalAuthModeRepository.BREAK_GLASS_ONLY;
        assertInstanceOf(AttemptOutcome.Success.class, login("nexusadmin"));
        assertInstanceOf(AttemptOutcome.Success.class, login("aiview"));
        for (String name : List.of("claudeadmin", "aiview-e2e", "someone")) {
            AttemptOutcome outcome = login(name);
            AttemptOutcome.Refused refused = assertInstanceOf(AttemptOutcome.Refused.class, outcome, name);
            assertEquals("local_non_break_glass_refused", refused.reasonCode());
        }
        verify(credentials, never()).recordFailedAttempt(anyString(), any(), anyInt(), any());
        verify(credentials, never()).recordSuccessfulLogin(eq("id-claudeadmin"), any(), anyString());
    }

    @Test
    void aWrongPasswordIsTheOrdinaryRefusalAndStillCountsTowardLockoutUnderTheMode() {
        currentMode = LocalAuthModeRepository.BREAK_GLASS_ONLY;
        AttemptOutcome.Refused refused = assertInstanceOf(AttemptOutcome.Refused.class,
                mechanism.attempt("claudeadmin", "wrong-password-xyz".toCharArray()));
        assertEquals("wrong_password", refused.reasonCode());
        verify(credentials).recordFailedAttempt(eq("id-claudeadmin"), any(), anyInt(), any());
        // nothing was verified, so no event
        verify(events, never()).recordRefused(anyString());
    }

    @Test
    void theRootAndAiviewNeverLoseTheirLoginToTheModeAndRootRaisesTheAlert() {
        currentMode = LocalAuthModeRepository.BREAK_GLASS_ONLY;
        login("nexusadmin");
        login("aiview");
        verify(events).recordLoginSucceeded(LocalMechanism.actorFingerprintFor("id-nexusadmin"), "ROOT", true);
        verify(events).recordLoginSucceeded(LocalMechanism.actorFingerprintFor("id-aiview"), "VERIFICATION", false);
    }

    @Test
    void aRefusedCorrectPasswordIsAudited() {
        currentMode = LocalAuthModeRepository.BREAK_GLASS_ONLY;
        login("claudeadmin");
        verify(events, timeout(5000)).recordRefused(LocalMechanism.actorFingerprintFor("id-claudeadmin"));
    }

    @Test
    void aFailingEventWriteNeverTurnsABreakGlassLoginIntoAnError() {
        currentMode = LocalAuthModeRepository.BREAK_GLASS_ONLY;
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(events)
                .recordLoginSucceeded(anyString(), anyString(), anyBoolean());
        assertInstanceOf(AttemptOutcome.Success.class, login("nexusadmin"));
    }

    @Test
    void anUnreadableSettingKeepsTheLastKnownMode() {
        currentMode = LocalAuthModeRepository.BREAK_GLASS_ONLY;
        assertTrue(policy.breakGlassOnly(NOW));
        when(modes.read()).thenThrow(new IllegalStateException("db down"));
        assertTrue(policy.breakGlassOnly(NOW.plusSeconds(60)));
        // cold start with an unreadable setting: the default, never a lock-out
        var cold = new LocalAuthPolicy(modes, root, events);
        assertEquals(false, cold.breakGlassOnly(NOW));
    }

    // ---- the switch ----

    private LocalAuthModeService service() {
        return new LocalAuthModeService(modes, policy, root, credentials, new LocalIdentityResolver(credentials), () -> NOW);
    }

    private static Map<String, Object> mode(String value) {
        return Map.of("mode", value);
    }

    @Test
    void theRootIdentityMayEnableItAndTheNewModeAppliesAtOnce() {
        assertEquals(false, policy.breakGlassOnly(NOW));
        var result = service().update(LocalMechanism.actorFingerprintFor("id-nexusadmin"), mode("BREAK_GLASS_ONLY"));
        assertTrue(result.isOk());
        assertEquals("BREAK_GLASS_ONLY", result.body().get("mode"));
        assertTrue(policy.breakGlassOnly(NOW), "the cache was invalidated by the write");
    }

    @Test
    void aDirectoryAdministratorMayEnableItBecauseItIsNotALocalAccount() {
        var result = service().update("ldap-or-saml-actor-fingerprint", mode("BREAK_GLASS_ONLY"));
        assertTrue(result.isOk());
    }

    @Test
    void aLocalAdministratorWhoIsNotTheRootIdentityIsRefusedBecauseItWouldLockThemOut() {
        var result = service().update(LocalMechanism.actorFingerprintFor("id-claudeadmin"), mode("BREAK_GLASS_ONLY"));
        assertEquals(409, result.refusal().status());
        assertEquals("LOCAL_AUTH_MODE_ACTOR_WOULD_LOCK_OUT", result.refusal().code());
        verify(modes, never()).write(anyString(), anyString());
    }

    @Test
    void itIsRefusedWhileTheRootIdentityIsDisabledLockedOrMissing() {
        String actor = "ldap-or-saml-actor-fingerprint";
        seed("nexusadmin", false);
        assertEquals("LOCAL_AUTH_MODE_NO_BREAK_GLASS", service().update(actor, mode("BREAK_GLASS_ONLY")).refusal().code());
        var locked = seed("nexusadmin", true);
        byName.put("nexusadmin", new LocalCredentialRecord(locked.localIdentityId(), locked.localIdentityName(), locked.verifier(),
                locked.salt(), locked.algorithmId(), locked.memoryCostKib(), locked.timeCost(), locked.parallelism(), 5,
                Optional.of(NOW.plusSeconds(600)), NOW, NOW, true, "system:bootstrap", NOW, false));
        assertEquals("LOCAL_AUTH_MODE_NO_BREAK_GLASS", service().update(actor, mode("BREAK_GLASS_ONLY")).refusal().code());
        when(root.rootLocalIdentityId()).thenReturn(Optional.empty());
        assertEquals("LOCAL_AUTH_MODE_NO_BREAK_GLASS", service().update(actor, mode("BREAK_GLASS_ONLY")).refusal().code());
        verify(modes, never()).write(anyString(), anyString());
    }

    @Test
    void turningItOffIsAlwaysAllowedAndBadBodiesAreRefusedWholesale() {
        currentMode = LocalAuthModeRepository.BREAK_GLASS_ONLY;
        assertTrue(service().update(LocalMechanism.actorFingerprintFor("id-claudeadmin"), mode("ALL_LOCAL")).isOk());
        assertEquals(400, service().update("a", Map.of()).refusal().status());
        assertEquals(400, service().update("a", mode("NOBODY")).refusal().status());
        assertEquals(400, service().update("a", Map.of("mode", "ALL_LOCAL", "x", 1)).refusal().status());
        assertEquals(403, service().update(null, mode("ALL_LOCAL")).refusal().status());
        assertNotNull(service().view(true).get("mode"));
        assertNull(service().view(false).get("updated_by"));
    }
}
