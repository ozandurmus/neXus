package com.securityexpert.nexus.ui2.identity.ldap;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Design §2: every save-time rejection names its field. */
class DirectoryProfileValidationTest {
    private static final String BASE = "DC=example,DC=local";
    private static final String ADMINS = "CN=nexus-admins,OU=Groups,DC=example,DC=local";
    private static final String USERS = "CN=nexus-users,OU=Groups,DC=example,DC=local";
    private static final String BIND = "CN=svc-nexus,OU=People,DC=example,DC=local";

    private static List<String> fields(List<String> servers, int port, String domain, String admins, String users,
            String bind, int timeout, boolean ldaps, DirectoryProfile.TrustMode mode, String pem) {
        return DirectoryProfile.problems(servers, port, domain, admins, users, bind, timeout, ldaps, mode, pem,
                Instant.now(), true).stream().map(DirectoryProfile.Problem::field).toList();
    }

    @Test
    void aCompleteTrustAnyFormIsValid() {
        assertEquals(List.of(), fields(List.of("dc1.example.local", "192.0.2.10", "2001:db8::10"), 636, BASE, ADMINS,
                USERS, BIND, 10, true, DirectoryProfile.TrustMode.TRUST_ANY, null));
        assertEquals(List.of(), fields(List.of("192.0.2.10"), 389, BASE, ADMINS, USERS, "svc-nexus@example.local", 10,
                false, null, null));
    }

    @Test
    void theOldTemplateFormIsRejectedWithFieldNames() {
        assertEquals(List.of("servers", "domainDn", "adminGroupDn", "usersGroupDn", "bindDn", "trustPem"),
                fields(List.of(), 636, "", "", "", "%s", 10, true, DirectoryProfile.TrustMode.TRUST_PEM, ""));
    }

    @Test
    void serverNamesAreHostsOrAddressesOnly() {
        for (String bad : List.of("ldaps://dc1", "dc1:636", "dc 1", "10.0.0.300", "-dc", "dc1.", "")) {
            assertEquals(List.of("servers"), fields(List.of(bad), 636, BASE, ADMINS, USERS, BIND, 10, true,
                    DirectoryProfile.TrustMode.TRUST_ANY, null), bad);
        }
        assertEquals(List.of("servers"), fields(List.of("dc1", "DC1"), 636, BASE, ADMINS, USERS, BIND, 10, true,
                DirectoryProfile.TrustMode.TRUST_ANY, null));
        assertEquals(List.of("servers"), fields(List.of("a", "b", "c", "d", "e", "f"), 636, BASE, ADMINS, USERS, BIND,
                10, true, DirectoryProfile.TrustMode.TRUST_ANY, null));
    }

    @Test
    void rangesAndGroupsAreChecked() {
        assertEquals(List.of("port", "connectTimeoutSeconds"), fields(List.of("dc1"), 0, BASE, ADMINS, USERS, BIND, 61,
                true, DirectoryProfile.TrustMode.TRUST_ANY, null));
        assertEquals(List.of("usersGroupDn"), fields(List.of("dc1"), 636, BASE, ADMINS,
                "cn=NEXUS-ADMINS,ou=groups,dc=example,dc=local", BIND, 10, true, DirectoryProfile.TrustMode.TRUST_ANY, null));
    }

    @Test
    void pemTextMustBeCertificatesOnly() throws Exception {
        String ca = ActiveDirectoryFixture.Tls.material().caPem();
        assertEquals(List.of(), fields(List.of("dc1"), 636, BASE, ADMINS, USERS, BIND, 10, true,
                DirectoryProfile.TrustMode.TRUST_PEM, ca));
        for (String bad : List.of("hello", ca + "\ntrailing", "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----",
                "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----")) {
            assertEquals(List.of("trustPem"), fields(List.of("dc1"), 636, BASE, ADMINS, USERS, BIND, 10, true,
                    DirectoryProfile.TrustMode.TRUST_PEM, bad));
        }
    }

    @Test
    void anExpiredCertificateIsRefusedAtSaveWithItsDates() throws Exception {
        String ca = ActiveDirectoryFixture.Tls.material().caPem();
        var problems = DirectoryProfile.problems(List.of("dc1"), 636, BASE, ADMINS, USERS, BIND, 10, true,
                DirectoryProfile.TrustMode.TRUST_PEM, ca, Instant.now().plusSeconds(400L * 24 * 3600), true);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).message().contains("validity period"));
    }

    @Test
    void theRecordRefusesInvalidSettingsAndNeverPrintsThem() {
        assertThrows(LdapStartupException.class, () -> new DirectoryProfile("default", List.of(), 636, BASE, ADMINS,
                USERS, BIND, 10, true, DirectoryProfile.TrustMode.TRUST_ANY, null));
        DirectoryProfile ok = new DirectoryProfile("default", List.of("dc1"), 636, BASE, ADMINS, USERS, BIND, 10, true,
                DirectoryProfile.TrustMode.TRUST_ANY, null);
        assertEquals("DirectoryProfile[redacted]", ok.toString());
    }
}
