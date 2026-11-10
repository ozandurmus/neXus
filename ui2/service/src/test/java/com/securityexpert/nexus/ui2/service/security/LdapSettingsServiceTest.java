package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.DirectoryMutationRepositories;
import com.securityexpert.nexus.ui2.persistence.identity.LdapSettingsRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LdapSettingsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.DirectoryBindingKind;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/** Design LDAP_SERVICE_BIND_DESIGN_2026_11_11 §2 (validation, write-only password) and §5 (role sync). */
class LdapSettingsServiceTest {
    private static final String ADMINS = "CN=nexus-admins,OU=Groups,DC=example,DC=local";
    private static final String USERS = "CN=nexus-users,OU=Groups,DC=example,DC=local";
    private static final String BIND = "CN=svc-nexus,OU=People,DC=example,DC=local";
    private static final String SECRET = "synthetic-Service-Pass-1";

    static final class Settings implements LdapSettingsRepository {
        LdapSettingsRecord row;
        @Override public Optional<LdapSettingsRecord> find() { return Optional.ofNullable(row); }
        @Override public void save(LdapSettingsRecord r) {
            row = new LdapSettingsRecord(r.enabled(), r.serverType(), r.servers(), r.port(), r.domainDn(), r.adminGroupDn(),
                    r.usersGroupDn(), r.bindDn(), r.bindPasswordEncrypted(), r.bindPasswordKeyId(), r.connectTimeoutSeconds(),
                    r.ldaps(), r.trustMode(), r.trustPem(), Instant.now(), r.updatedBy());
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
        @Override public void revoke(String id, String by, String action) {
            for (int i = 0; i < rows.size(); i++) {
                RoleBindingRecord r = rows.get(i);
                if (r.bindingId().equals(id)) rows.set(i, new RoleBindingRecord(r.bindingId(), r.roleToken(), r.groupReferenceEncrypted(),
                        r.groupReferenceKeyId(), r.createdByActorFingerprint(), r.createdAt(), Optional.of(Instant.now()),
                        Optional.of(by), r.bindingKind(), r.directoryProfileId()));
            }
        }
    }

    private final Settings settings = new Settings();
    private final Bindings bindings = new Bindings();
    private final GroupReferenceCipher groups = GroupReferenceCipher.fromBase64Key(key(), "synthetic-group-key");
    private final AtomicInteger changed = new AtomicInteger();
    private final LdapSettingsService service = new LdapSettingsService(settings, bindings,
            CredentialStoreCipher.fromBase64Key(key()), "synthetic-cs-key", groups, Clock.systemUTC(), changed::incrementAndGet);

    private static String key() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static LdapSettingsService.Request form(List<String> servers, String admins, String password, String mode) {
        return new LdapSettingsService.Request(true, "ACTIVE_DIRECTORY", servers, 636, "DC=example,DC=local", admins,
                USERS, BIND, password, 10, true, mode, null);
    }

    private List<String> fields(LdapSettingsService.Outcome outcome) {
        return ((LdapSettingsService.Outcome.Invalid) outcome).problems().stream().map(LdapSettingsService.Problem::field).toList();
    }

    private List<List<String>> managed() {
        return bindings.findAllActive().stream().filter(b -> LdapSettingsService.SYNC_ACTOR.equals(b.createdByActorFingerprint()))
                .map(b -> List.of(b.roleToken(), groups.decryptDirectory(b.groupReferenceEncrypted(), b.directoryProfileId(),
                        b.bindingKind(), b.groupReferenceKeyId()))).toList();
    }

    @Test
    void saveEncryptsThePasswordAndNeverReturnsIt() {
        var outcome = service.save(form(List.of("192.0.2.10"), ADMINS, SECRET, "TRUST_ANY"), "actor-1");
        var view = ((LdapSettingsService.Outcome.Saved) outcome).view();
        assertTrue(view.bindPasswordSet());
        assertFalse(view.toString().contains(SECRET));
        assertFalse(new String(settings.row.bindPasswordEncrypted(), StandardCharsets.ISO_8859_1).contains(SECRET));
        assertEquals(SECRET, new String(service.servicePassword(settings.row).get()));
        assertEquals("synthetic-cs-key", settings.row.bindPasswordKeyId());
        assertEquals("actor-1", settings.row.updatedBy());
        assertEquals(1, changed.get());
        assertFalse(java.util.Arrays.stream(LdapSettingsService.View.class.getRecordComponents())
                .anyMatch(c -> c.getName().toLowerCase().contains("password") && c.getType() != boolean.class));
    }

    @Test
    void saveSyncsTheManagedRoleBindings() {
        service.save(form(List.of("dc1.example.local"), ADMINS, SECRET, "TRUST_ANY"), "actor-1");
        assertEquals(LdapSettingsService.ADMIN_GROUP_ROLES.size() + 1, managed().size());
        assertTrue(managed().contains(List.of(RoleToken.SECURITY_ADMIN, ADMINS)));
        assertTrue(managed().contains(List.of(RoleToken.VIEWER, USERS)));
        assertFalse(managed().contains(List.of(RoleToken.SECURITY_ADMIN, USERS)));
        assertTrue(bindings.findAllActive().stream().allMatch(b -> b.bindingKind() == DirectoryBindingKind.DIRECTORY_GROUP
                && "default".equals(b.directoryProfileId())));
    }

    @Test
    void changingTheAdminGroupMovesOnlyManagedBindingsAndKeepsHandMadeOnes() {
        bindings.createDirectory(new RoleBindingRecord("hand-made", RoleToken.SECURITY_ADMIN,
                groups.encryptDirectory("CN=other,DC=example,DC=local", "default", DirectoryBindingKind.DIRECTORY_GROUP),
                groups.keyId(), "actor-1", Instant.now(), Optional.empty(), Optional.empty(), DirectoryBindingKind.DIRECTORY_GROUP,
                "default"), "x");
        service.save(form(List.of("dc1.example.local"), ADMINS, SECRET, "TRUST_ANY"), "actor-1");
        String moved = "CN=fw-admins,OU=Groups,DC=example,DC=local";
        var outcome = service.save(form(List.of("dc1.example.local"), moved, "", "TRUST_ANY"), "actor-1");
        assertInstanceOf(LdapSettingsService.Outcome.Saved.class, outcome, () -> outcome.toString());
        assertTrue(managed().contains(List.of(RoleToken.SECURITY_ADMIN, moved)));
        assertFalse(managed().contains(List.of(RoleToken.SECURITY_ADMIN, ADMINS)));
        assertEquals(LdapSettingsService.ADMIN_GROUP_ROLES.size() + 1, managed().size());
        assertTrue(bindings.find("hand-made").orElseThrow().isActive());
    }

    @Test
    void blankPasswordKeepsTheStoredOneOnlyForTheSameTarget() {
        service.save(form(List.of("dc1.example.local"), ADMINS, SECRET, "TRUST_ANY"), "actor-1");
        byte[] before = settings.row.bindPasswordEncrypted();
        assertInstanceOf(LdapSettingsService.Outcome.Saved.class,
                service.save(form(List.of("dc1.example.local"), ADMINS, "", "TRUST_ANY"), "actor-1"));
        assertArrayEquals(before, settings.row.bindPasswordEncrypted());
        // A new server, or a weaker transport, never receives the stored password.
        assertEquals(List.of("bindPassword"), fields(service.save(form(List.of("192.0.2.66"), ADMINS, "", "TRUST_ANY"), "actor-1")));
        var plain = new LdapSettingsService.Request(true, null, List.of("dc1.example.local"), 389, "DC=example,DC=local",
                ADMINS, USERS, BIND, "", 10, false, null, null);
        assertEquals(List.of("bindPassword"), fields(service.save(plain, "actor-1")));
        assertEquals(List.of("bindPassword"), fields(service.test(form(List.of("192.0.2.66"), ADMINS, null, "TRUST_ANY"), "u")));
        assertArrayEquals(before, settings.row.bindPasswordEncrypted());
    }

    @Test
    void firstSaveNeedsAPasswordAndEveryProblemNamesItsField() {
        assertEquals(List.of("bindPassword"), fields(service.save(form(List.of("dc1"), ADMINS, "", "TRUST_ANY"), "actor-1")));
        var outcome = service.save(new LdapSettingsService.Request(true, "OPENLDAP", List.of("ldaps://dc1:636"), 70000,
                "", "not a dn", USERS, "", SECRET, 0, true, "TRUST_PEM", ""), "actor-1");
        assertEquals(List.of("serverType", "servers", "port", "domainDn", "adminGroupDn", "bindDn",
                "connectTimeoutSeconds", "trustPem"), fields(outcome));
        assertNull(settings.row);
        assertEquals(0, changed.get());
    }

    @Test
    void requestToStringNeverCarriesThePassword() {
        assertFalse(form(List.of("dc1"), ADMINS, SECRET, "TRUST_ANY").toString().contains(SECRET));
    }
}
