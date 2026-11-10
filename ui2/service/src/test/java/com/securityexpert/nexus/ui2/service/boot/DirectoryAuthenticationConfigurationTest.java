package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LdapSettingsRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LdapSettingsRepository;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.Result;

/** The live provider: disabled or missing settings are "unavailable"; a failure logs a reason code only. */
class DirectoryAuthenticationConfigurationTest {
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.securityexpert.nexus.ui2.ldap");
    private final CredentialStoreCipher cipher = CredentialStoreCipher.fromBase64Key(key());
    private LdapSettingsRecord row;

    private static String key() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    @BeforeEach void attach() { logs.start(); logger.addAppender(logs); }
    @AfterEach void detach() { logger.detachAppender(logs); }

    private DirectoryAuthenticationConfiguration.DirectoryProvider provider() {
        LdapSettingsRepository settings = new LdapSettingsRepository() {
            @Override public Optional<LdapSettingsRecord> find() { return Optional.ofNullable(row); }
            @Override public void save(LdapSettingsRecord record) { row = record; }
        };
        return new DirectoryAuthenticationConfiguration().directoryProvider(settings, mock(ActorAuthzStateRepository.class), cipher);
    }

    private LdapSettingsRecord settings(boolean enabled, int port) {
        return new LdapSettingsRecord(enabled, "ACTIVE_DIRECTORY", List.of("127.0.0.1"), port, "DC=example,DC=local",
                "CN=nexus-admins,DC=example,DC=local", "CN=nexus-users,DC=example,DC=local",
                "CN=svc-nexus,DC=example,DC=local", cipher.encrypt("synthetic-pass"), "k", 1, false, "TRUST_ANY", null,
                Instant.now(), "actor-1");
    }

    @Test void absentSettingsMakeTheMechanismUnavailable() {
        var config = new DirectoryAuthenticationConfiguration();
        var mechanism = config.ldapMechanism(provider());
        assertInstanceOf(AttemptOutcome.MechanismUnavailable.class, mechanism.attempt("synthetic", "x".toCharArray()));
        assertFalse(provider().directoryPostureEnabled());
        assertInstanceOf(Result.Err.class, provider().revalidatePrincipal("CN=synthetic,DC=example,DC=local"));
    }

    @Test void disabledSettingsAreUnavailableToo() {
        row = settings(false, 636);
        var provider = provider();
        assertFalse(provider.directoryPostureEnabled());
        assertEquals("directory_disabled", ((Result.Err<?>) provider.bind("synthetic", "x".toCharArray())).message());
    }

    @Test void aFailedAttemptLogsOnlyTheReasonCode() throws Exception {
        int closed;
        try (var socket = new java.net.ServerSocket(0)) { closed = socket.getLocalPort(); }
        row = settings(true, closed);
        var provider = provider();
        assertTrue(provider.directoryPostureEnabled());
        var result = provider.bind("leak-check-user", "leak-check-password".toCharArray());
        assertEquals("connect_failed", ((Result.Err<?>) result).message());
        assertFalse(logs.list.isEmpty());
        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage();
            assertTrue(line.matches("ldap_[a-z_]+ reason=[a-z_]+"), line);
            assertFalse(line.contains("leak-check"), line);
            assertFalse(line.contains("127.0.0.1"), line);
            assertNull(event.getThrowableProxy(), "no exception is logged");
        }
    }

    @Test void aChangedRowRetiresTheOldTrustGeneration() {
        row = settings(true, 636);
        var provider = provider();
        assertTrue(provider.directoryPostureEnabled());
        row = settings(false, 636);
        provider.reload();
        assertFalse(provider.directoryPostureEnabled());
    }
}
