package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.core.Authentication;

import com.securityexpert.nexus.ui2.identity.ldap.LdapMechanism;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.Clock;
import com.securityexpert.nexus.ui2.platform.Mechanism;
import com.securityexpert.nexus.ui2.platform.Result;

/** Migration plan Step 2: the local and LDAP adapters and the ProviderManager decide exactly as the mechanisms do. */
class MechanismAuthenticatorTest {

    private static LocalCredentialsRepository emptyLocalRepository() {
        return org.mockito.Mockito.mock(LocalCredentialsRepository.class, invocation ->
                invocation.getMethod().getReturnType() == Optional.class ? Optional.empty() : null);
    }

    private static Mechanism stub(String id, AttemptOutcome outcome) {
        return new Mechanism() {
            @Override
            public String mechanismId() {
                return id;
            }

            @Override
            public AttemptOutcome attempt(String identity, char[] credential) {
                return outcome;
            }
        };
    }

    private static MechanismRegistry registry(List<Mechanism> additional) {
        return new MechanismRegistry(new LocalMechanism(emptyLocalRepository(), Clock.system()), additional);
    }

    @Test
    void localIsAlwaysAProviderAndAnUnknownIdentityIsRefusedExactlyLikeTheMechanismRefuses() {
        MechanismAuthenticator authenticator = new MechanismAuthenticator(registry(List.of()));
        BadCredentialsException refused = assertThrows(BadCredentialsException.class,
                () -> authenticator.authenticate("local", "nobody", "wrong-password-1".toCharArray()));
        assertEquals("unknown_identity", refused.getMessage(), "the same reason code the mechanism reports internally");
    }

    @Test
    void theLocalProviderOnlyAnswersItsOwnMechanismAndOnlyTheAttemptTokenType() {
        LocalAuthenticationProvider provider = new LocalAuthenticationProvider(
                new LocalMechanism(emptyLocalRepository(), Clock.system()));
        assertNull(provider.authenticate(new MechanismAuthenticationToken("ldap", "x", "y".toCharArray())));
        assertTrue(provider.supports(MechanismAuthenticationToken.class));
        assertTrue(!provider.supports(Ui2SessionAuthenticationToken.class));
        assertNull(provider.authenticate(new Ui2SessionAuthenticationToken("a", "s", false)));
    }

    @Test
    void ldapUnavailableAndRefusedAndSuccessMapToTheSameThreeOutcomes() {
        // admission disabled: the mechanism reports "unavailable"
        LdapAuthenticationProvider disabled = new LdapAuthenticationProvider(new LdapMechanism(
                (user, pw) -> Result.err("never_reached", "never reached"), false));
        assertThrows(MechanismUnavailableAuthenticationException.class,
                () -> disabled.authenticate(new MechanismAuthenticationToken("ldap", "u", "p".toCharArray())));
        // a refused bind
        LdapAuthenticationProvider refusing = new LdapAuthenticationProvider(new LdapMechanism(
                (user, pw) -> Result.err("invalid_credentials", "no")));
        assertThrows(BadCredentialsException.class,
                () -> refusing.authenticate(new MechanismAuthenticationToken("ldap", "u", "p".toCharArray())));
        // the provider ignores an attempt for another mechanism
        assertNull(refusing.authenticate(new MechanismAuthenticationToken("local", "u", "p".toCharArray())));
    }

    @Test
    void successCarriesTheMechanismsOwnOutcomeAndNoCredentials() {
        AttemptOutcome.Success success = new AttemptOutcome.Success("abcdef012345");
        MechanismAuthenticator authenticator = new MechanismAuthenticator(registry(List.of(stub("stub", success))));
        char[] password = "synthetic-password".toCharArray();
        assertSame(success, authenticator.authenticate("stub", "someone", password));
        assertArrayEquals("synthetic-password".toCharArray(), password, "the caller still owns, and zeroes, its own array");
        Authentication token = new MechanismAuthenticationProvider(stub("stub", success))
                .authenticate(new MechanismAuthenticationToken("stub", "someone", password));
        assertTrue(token.isAuthenticated());
        assertEquals("abcdef012345", token.getPrincipal());
        assertNull(token.getCredentials());
        assertTrue(!token.toString().contains("synthetic-password"));
    }

    @Test
    void refusedAndUnavailableFromAnyRegisteredMechanismKeepTheirMeaningThroughTheProviderManager() {
        MechanismAuthenticator authenticator = new MechanismAuthenticator(registry(List.of(
                stub("refusing", AttemptOutcome.refused("nope")),
                stub("down", AttemptOutcome.mechanismUnavailable("directory_unavailable")))));
        assertThrows(BadCredentialsException.class, () -> authenticator.authenticate("refusing", "u", "p".toCharArray()));
        MechanismUnavailableAuthenticationException unavailable = assertThrows(
                MechanismUnavailableAuthenticationException.class, () -> authenticator.authenticate("down", "u", "p".toCharArray()));
        assertEquals("directory_unavailable", unavailable.reasonCode());
        assertInstanceOf(ProviderNotFoundException.class,
                assertThrows(org.springframework.security.core.AuthenticationException.class,
                        () -> authenticator.authenticate("unregistered", "u", "p".toCharArray())));
    }

    @Test
    void theRegistryStillRequiresLocalAtCompileTimeAndListsEveryMechanism() {
        assertEquals(LocalMechanism.class, MechanismRegistry.class.getConstructors()[0].getParameterTypes()[0]);
        assertEquals(2, registry(List.of(stub("stub", AttemptOutcome.refused("x")))).all().size());
    }
}
