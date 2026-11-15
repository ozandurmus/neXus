package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.identity.DirectoryMutationRepositories;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SamlSettingsRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SamlSettingsRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.DirectoryBindingKind;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;

/** Migration plan Step 4, PO decisions D1 and D5: validation, pasted metadata, the write-only SP key, and the SAML_GROUP mapping. */
class SamlSettingsServiceTest {
    private static final String ADMINS = "nexus-admins";
    private static final String USERS = "nexus-users";

    static final class Settings implements SamlSettingsRepository {
        SamlSettingsRecord row;
        int reads;
        @Override public Optional<SamlSettingsRecord> find() { reads++; return Optional.ofNullable(row); }
        @Override public void save(SamlSettingsRecord r) {
            row = new SamlSettingsRecord(r.enabled(), r.spBaseUrl(), r.spEntityId(), r.idpEntityId(), r.idpSsoUrl(), r.idpSsoBinding(),
                    r.idpSigningCertPem(), r.groupClaimAttribute(), r.adminGroupValue(), r.usersGroupValue(), r.clockSkewSeconds(),
                    r.signAuthnRequests(), r.spCertPem(), r.spPrivateKeyEncrypted(), r.spPrivateKeyKeyId(), Instant.now(), r.updatedBy());
        }
    }

    static final class Bindings implements RoleBindingRepository {
        final List<RoleBindingRecord> rows = new ArrayList<>();
        @Override public List<RoleBindingRecord> findActiveByToken(String token) {
            return rows.stream().filter(r -> r.isActive() && r.roleToken().equals(token)).toList();
        }
        @Override public List<RoleBindingRecord> findAllActive() { return rows.stream().filter(RoleBindingRecord::isActive).toList(); }
        @Override public Optional<RoleBindingRecord> find(String id) { return rows.stream().filter(r -> r.bindingId().equals(id)).findFirst(); }
        @Override public boolean hasAnyActiveBinding(String token) { return !findActiveByToken(token).isEmpty(); }
        @Override public String create(String id, String token, byte[] ref, String keyId, String by, String action) { throw new AssertionError(); }
        @Override public String createDirectory(RoleBindingRecord binding, String actionId) { rows.add(binding); return binding.bindingId(); }
        @Override public <T> T directoryMutation(java.util.function.Function<DirectoryMutationRepositories, T> work) {
            return work.apply(new DirectoryMutationRepositories(this, null, null, null));
        }
        @Override public boolean revoke(String id, String by, String action) {
            boolean changed = false;
            for (int i = 0; i < rows.size(); i++) {
                RoleBindingRecord r = rows.get(i);
                if (r.bindingId().equals(id) && r.isActive()) {
                    changed = true;
                    rows.set(i, new RoleBindingRecord(r.bindingId(), r.roleToken(), r.groupReferenceEncrypted(), r.groupReferenceKeyId(),
                            r.createdByActorFingerprint(), r.createdAt(), Optional.of(Instant.now()), Optional.of(by), r.bindingKind(),
                            r.directoryProfileId()));
                }
            }
            return changed;
        }
    }

    private final Settings settings = new Settings();
    private final Bindings bindings = new Bindings();
    private final GroupReferenceCipher groups = GroupReferenceCipher.fromBase64Key(key(), "synthetic-group-key");
    private final CredentialStoreCipher keys = CredentialStoreCipher.fromBase64Key(key());
    private final AtomicInteger changed = new AtomicInteger();
    private final SamlSettingsService service = service(SamlSettingsService.SpKeyFiles.none());

    private SamlSettingsService service(SamlSettingsService.SpKeyFiles files) {
        return new SamlSettingsService(settings, bindings, keys, "synthetic-cs-key", groups, Clock.systemUTC(), files, changed::incrementAndGet);
    }

    private static String key() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static final KeyPair IDP_KEYS = rsa();
    private static final X509Certificate IDP_CERT = cert(IDP_KEYS, "CN=Test ADFS", System.currentTimeMillis() - 60_000, System.currentTimeMillis() + 86_400_000L);

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509Certificate cert(KeyPair keys, String subject, long from, long to) {
        try {
            X500Name name = new X500Name(subject);
            return new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(from),
                    new Date(from), new Date(to), name, keys.getPublic())
                    .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String metadata(X509Certificate certificate) throws Exception {
        return "<EntityDescriptor xmlns=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\"https://adfs.test.example/adfs/services/trust\">"
                + "<IDPSSODescriptor protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\">"
                + "<KeyDescriptor use=\"signing\"><KeyInfo xmlns=\"http://www.w3.org/2000/09/xmldsig#\"><X509Data><X509Certificate>"
                + Base64.getEncoder().encodeToString(certificate.getEncoded()) + "</X509Certificate></X509Data></KeyInfo></KeyDescriptor>"
                + "<SingleSignOnService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" Location=\"https://adfs.test.example/adfs/ls/\"/>"
                + "</IDPSSODescriptor></EntityDescriptor>";
    }

    private static String pem(X509Certificate c) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(c.getEncoded()) + "\n-----END CERTIFICATE-----\n";
    }

    private static String keyPem(KeyPair keys) {
        String label = "PRIVATE " + "KEY";
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END " + label + "-----\n";
    }

    private static SamlSettingsService.Request form(String metadata, String admins, String users) {
        return new SamlSettingsService.Request(true, "https://nexus.example.test", null, metadata, null, null, null, null, null, admins,
                users, null, null, null, null, null);
    }

    private List<String> fields(SamlSettingsService.Outcome outcome) {
        return ((SamlSettingsService.Outcome.Invalid) outcome).problems().stream().map(SamlSettingsService.Problem::field).toList();
    }

    private List<List<String>> managed() {
        return bindings.findAllActive().stream().filter(b -> SamlSettingsService.SYNC_ACTOR.equals(b.createdByActorFingerprint()))
                .map(b -> List.of(b.roleToken(), groups.decryptDirectory(b.groupReferenceEncrypted(), b.directoryProfileId(),
                        b.bindingKind(), b.groupReferenceKeyId()))).toList();
    }

    @Test
    void pastedMetadataFillsTheIdpFieldsAndTheDefaultsAreTheAdfsOnes() throws Exception {
        var outcome = service.save(form(metadata(IDP_CERT), ADMINS, USERS), "actor-1");
        var view = ((SamlSettingsService.Outcome.Saved) outcome).view();
        assertTrue(view.configured());
        assertTrue(view.enabled());
        assertEquals("https://adfs.test.example/adfs/services/trust", view.idpEntityId());
        assertEquals("https://adfs.test.example/adfs/ls/", view.idpSsoUrl());
        assertEquals("REDIRECT", view.idpSsoBinding());
        assertEquals(pem(IDP_CERT).strip(), view.idpSigningCertPem().strip());
        assertEquals("http://schemas.xmlsoap.org/claims/Group", view.groupClaimAttribute(), "D1 default claim");
        assertEquals(60, view.clockSkewSeconds());
        assertEquals("https://nexus.example.test/saml2/service-provider-metadata/adfs", view.spEntityId());
        assertEquals("https://nexus.example.test/login/saml2/sso/adfs", view.acsUrl());
        assertEquals("NONE", view.spKey().source());
        assertEquals("actor-1", settings.row.updatedBy());
        assertEquals(1, changed.get());
        assertTrue(service.isEnabled());
    }

    @Test
    void theClaimNameIsConfigurable() throws Exception {
        var request = new SamlSettingsService.Request(true, "https://nexus.example.test", null, metadata(IDP_CERT), null, null, null, null,
                "http://schemas.microsoft.com/ws/2008/06/identity/claims/role", ADMINS, USERS, null, null, null, null, null);
        var view = ((SamlSettingsService.Outcome.Saved) service.save(request, "actor-1")).view();
        assertEquals("http://schemas.microsoft.com/ws/2008/06/identity/claims/role", view.groupClaimAttribute());
    }

    @Test
    void invalidSettingsAreRefusedFieldByField() throws Exception {
        assertTrue(fields(service.save(form(metadata(IDP_CERT), "", ""), "a")).containsAll(List.of("adminGroupValue", "usersGroupValue")));
        assertEquals(List.of("usersGroupValue"), fields(service.save(form(metadata(IDP_CERT), ADMINS, ADMINS.toUpperCase()), "a")));
        var plainHttp = new SamlSettingsService.Request(true, "http://nexus.example.test", null, metadata(IDP_CERT), null, null, null, null,
                null, ADMINS, USERS, null, null, null, null, null);
        assertEquals(List.of("spBaseUrl"), fields(service.save(plainHttp, "a")));
        var withPath = new SamlSettingsService.Request(true, "https://nexus.example.test/app", null, metadata(IDP_CERT), null, null, null,
                null, null, ADMINS, USERS, null, null, null, null, null);
        assertEquals(List.of("spBaseUrl"), fields(service.save(withPath, "a")));
        var loopback = new SamlSettingsService.Request(true, "http://127.0.0.1:8080", null, metadata(IDP_CERT), null, null, null, null,
                null, ADMINS, USERS, null, null, null, null, null);
        assertInstanceOf(SamlSettingsService.Outcome.Saved.class, service.save(loopback, "a"), "loopback http is allowed for local runs");
        var skew = new SamlSettingsService.Request(true, "https://nexus.example.test", null, metadata(IDP_CERT), null, null, null, null,
                null, ADMINS, USERS, 301, null, null, null, null);
        assertEquals(List.of("clockSkewSeconds"), fields(service.save(skew, "a")));
        assertEquals(List.of("idpMetadataXml"), fields(service.save(form("<not-metadata/>", ADMINS, USERS), "a")));
        assertEquals(List.of("idpMetadataXml"), fields(service.save(form("not xml at all", ADMINS, USERS), "a")));
        // No metadata and no typed fields: the three IdP fields are named.
        assertTrue(fields(service.save(form(null, ADMINS, USERS), "a")).containsAll(List.of("idpEntityId", "idpSsoUrl", "idpSigningCertPem")));
        // An expired IdP certificate is refused.
        X509Certificate expired = cert(IDP_KEYS, "CN=Old", System.currentTimeMillis() - 200_000_000L, System.currentTimeMillis() - 100_000_000L);
        assertEquals(List.of("idpSigningCertPem"), fields(service.save(form(metadata(expired), ADMINS, USERS), "a")));
        // No signed-in actor, no write.
        assertInstanceOf(SamlSettingsService.Outcome.Invalid.class, service.save(form(metadata(IDP_CERT), ADMINS, USERS), " "));
    }

    @Test
    void theServiceProviderKeyIsEncryptedNeverShownAndKeptUntilReplacedOrCleared() throws Exception {
        KeyPair sp = rsa();
        X509Certificate spCert = cert(sp, "CN=Test SP", System.currentTimeMillis() - 60_000, System.currentTimeMillis() + 86_400_000L);
        String keyText = keyPem(sp);
        var withKey = new SamlSettingsService.Request(true, "https://nexus.example.test", null, metadata(IDP_CERT), null, null, null, null,
                null, ADMINS, USERS, null, true, pem(spCert), keyText, null);
        var view = ((SamlSettingsService.Outcome.Saved) service.save(withKey, "actor-1")).view();
        assertEquals("SETTINGS", view.spKey().source());
        assertTrue(view.spKey().privateKeySet());
        assertTrue(view.signAuthnRequests());
        assertFalse(view.toString().contains("PRIVATE KEY"));
        assertFalse(new String(settings.row.spPrivateKeyEncrypted(), StandardCharsets.ISO_8859_1).contains("PRIVATE"));
        assertEquals("synthetic-cs-key", settings.row.spPrivateKeyKeyId());
        assertTrue(service.spKey(settings.row).isPresent());
        assertFalse(java.util.Arrays.stream(SamlSettingsService.View.class.getRecordComponents())
                .anyMatch(c -> c.getName().toLowerCase().contains("privatekey")), "the view has no private key component");

        // A blank key keeps the stored one.
        byte[] before = settings.row.spPrivateKeyEncrypted();
        var keep = new SamlSettingsService.Request(true, "https://nexus.example.test", null, metadata(IDP_CERT), null, null, null, null,
                null, ADMINS, USERS, null, true, null, "", null);
        service.save(keep, "actor-2");
        assertArrayEquals(before, settings.row.spPrivateKeyEncrypted());
        assertTrue(settings.row.signAuthnRequests());

        // A certificate that does not belong to the key is refused.
        X509Certificate other = cert(rsa(), "CN=Other", System.currentTimeMillis() - 60_000, System.currentTimeMillis() + 86_400_000L);
        var mismatch = new SamlSettingsService.Request(true, "https://nexus.example.test", null, metadata(IDP_CERT), null, null, null, null,
                null, ADMINS, USERS, null, null, pem(other), keyText, null);
        assertEquals(List.of("spCertPem"), fields(service.save(mismatch, "a")));
        var garbage = new SamlSettingsService.Request(true, "https://nexus.example.test", null, metadata(IDP_CERT), null, null, null, null,
                null, ADMINS, USERS, null, null, pem(spCert), "-----BEGIN " + "PRIVATE KEY-----\nAAAA\n-----END " + "PRIVATE KEY-----", null);
        assertEquals(List.of("spPrivateKeyPem"), fields(service.save(garbage, "a")));

        // Clearing removes it, and signing then has nothing to sign with.
        var signWithoutKey = new SamlSettingsService.Request(true, "https://nexus.example.test", null, metadata(IDP_CERT), null, null, null,
                null, null, ADMINS, USERS, null, true, null, null, true);
        assertEquals(List.of("signAuthnRequests"), fields(service.save(signWithoutKey, "a")));
        var clear = new SamlSettingsService.Request(true, "https://nexus.example.test", null, metadata(IDP_CERT), null, null, null, null,
                null, ADMINS, USERS, null, false, null, null, true);
        service.save(clear, "actor-3");
        assertNull(settings.row.spPrivateKeyEncrypted());
        assertEquals("NONE", service.view().spKey().source());
    }

    @Test
    void theEnvironmentFilesWinOverAStoredKey(@TempDir Path dir) throws Exception {
        KeyPair stored = rsa();
        KeyPair fromFile = rsa();
        X509Certificate storedCert = cert(stored, "CN=Stored", System.currentTimeMillis() - 60_000, System.currentTimeMillis() + 86_400_000L);
        X509Certificate fileCert = cert(fromFile, "CN=File", System.currentTimeMillis() - 60_000, System.currentTimeMillis() + 86_400_000L);
        Path keyFile = Files.writeString(dir.resolve("sp.key"), keyPem(fromFile));
        Path certFile = Files.writeString(dir.resolve("sp.crt"), pem(fileCert));
        SamlSettingsService withFiles = service(new SamlSettingsService.SpKeyFiles(keyFile, certFile));
        assertEquals("FILE", withFiles.view().spKey().source(), "files alone are enough");
        var withKey = new SamlSettingsService.Request(true, "https://nexus.example.test", null, metadata(IDP_CERT), null, null, null, null,
                null, ADMINS, USERS, null, true, pem(storedCert), keyPem(stored), null);
        withFiles.save(withKey, "actor-1");
        var pair = withFiles.spKey(settings.row).orElseThrow();
        assertEquals("FILE", pair.source());
        assertEquals(fileCert, pair.certificate());
        // Mismatched files are not used; the stored key is.
        Files.writeString(certFile, pem(storedCert));
        assertEquals("SETTINGS", withFiles.spKey(settings.row).orElseThrow().source());
    }

    @Test
    void theGroupValuesMapToTheRolesAsSamlGroupBindingsAndFollowEveryChange() throws Exception {
        service.save(form(metadata(IDP_CERT), ADMINS, USERS), "actor-1");
        List<List<String>> first = managed();
        assertEquals(LdapSettingsService.ADMIN_GROUP_ROLES.size() + LdapSettingsService.USERS_GROUP_ROLES.size(), first.size());
        for (String role : LdapSettingsService.ADMIN_GROUP_ROLES) assertTrue(first.contains(List.of(role, ADMINS)), role);
        assertTrue(first.contains(List.of("role:viewer", USERS)));
        assertTrue(bindings.findAllActive().stream().allMatch(b -> b.bindingKind() == DirectoryBindingKind.SAML_GROUP
                && SamlSettingsService.PROFILE_ID.equals(b.directoryProfileId())));
        assertNotEquals("default", SamlSettingsService.PROFILE_ID, "never the LDAP profile");

        // A hand-made binding is never touched.
        bindings.createDirectory(new RoleBindingRecord("hand-made", "role:viewer", groups.encryptDirectory("by-hand", "saml:adfs",
                DirectoryBindingKind.SAML_GROUP), groups.keyId(), "someone", Instant.now(), Optional.empty(), Optional.empty(),
                DirectoryBindingKind.SAML_GROUP, "saml:adfs"), "x");

        service.save(form(metadata(IDP_CERT), "other-admins", USERS), "actor-1");
        List<List<String>> second = managed();
        assertTrue(second.contains(List.of("role:security_admin", "other-admins")));
        assertFalse(second.contains(List.of("role:security_admin", ADMINS)));
        assertTrue(bindings.find("hand-made").orElseThrow().isActive());

        // Off: no SAML binding remains, so an open SAML session loses its roles at once.
        service.save(new SamlSettingsService.Request(false, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null), "actor-1");
        assertTrue(managed().isEmpty());
        assertFalse(settings.row.enabled());
        assertEquals("other-admins", settings.row.adminGroupValue(), "turning it off keeps the settings");
        assertFalse(service.isEnabled());
        assertTrue(bindings.find("hand-made").orElseThrow().isActive());
        // And on again with the same settings restores the mapping.
        service.save(form(metadata(IDP_CERT), "other-admins", USERS), "actor-1");
        assertFalse(managed().isEmpty());
    }

    @Test
    void anOffRequestWithNothingSavedIsANoOpAndTheViewIsEmpty() {
        var view = ((SamlSettingsService.Outcome.Saved) service.save(new SamlSettingsService.Request(false, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null), "a")).view();
        assertFalse(view.configured());
        assertFalse(view.enabled());
        assertNull(settings.row);
        assertEquals("http://schemas.xmlsoap.org/claims/Group", view.groupClaimAttribute());
        assertEquals(LdapSettingsService.ADMIN_GROUP_ROLES, view.adminGroupRoles());
    }
}
