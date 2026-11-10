package com.securityexpert.nexus.ui2.service.security;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.identity.ldap.DirectoryProbe;
import com.securityexpert.nexus.ui2.identity.ldap.DirectoryProfile;
import com.securityexpert.nexus.ui2.persistence.identity.LdapSettingsRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LdapSettingsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.DirectoryBindingKind;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * Administration › LDAP (design LDAP_SERVICE_BIND_DESIGN_2026_11_11 §2, §5, §7): validates and stores the
 * Active Directory settings, keeps the service account password encrypted under the credential store key,
 * synchronises the system-managed group role bindings, and runs the connection test.
 *
 * <p>The password is write-only: the view says only whether one is stored. A blank password keeps the stored one
 * only while the connection target is unchanged (servers, port, bind DN, LDAPS, trust mode and certificate), so
 * the stored secret can never be sent to a newly entered server (Argon review finding 5).</p>
 */
public final class LdapSettingsService {

    /** Creator of the system-managed DIRECTORY_GROUP bindings; hand-made bindings are never touched. */
    public static final String SYNC_ACTOR = "system:ldap-settings";

    /** PO decision record 2026-11-11, role mapping. */
    public static final List<String> ADMIN_GROUP_ROLES = List.of(RoleToken.SECURITY_ADMIN, RoleToken.OPERATION_ADMIN,
            RoleToken.OPERATOR, RoleToken.ONBOARDING_ADMIN, RoleToken.BACKUP_ADMIN, RoleToken.COMPLIANCE_ADMIN,
            RoleToken.VIEWER);
    public static final List<String> USERS_GROUP_ROLES = List.of(RoleToken.VIEWER);

    public static final String SERVER_TYPE = "ACTIVE_DIRECTORY";

    /** What the browser reads: never the password or its ciphertext. */
    public record View(boolean configured, boolean enabled, String serverType, List<String> servers, int port,
            String domainDn, String adminGroupDn, String usersGroupDn, String bindDn, boolean bindPasswordSet,
            int connectTimeoutSeconds, boolean ldaps, String trustMode, String trustPem, Instant updatedAt,
            List<String> adminGroupRoles, List<String> usersGroupRoles) { }

    /** What the browser and the CLI send. {@code bindPassword} blank = keep the stored one (see class comment). */
    public record Request(Boolean enabled, String serverType, List<String> servers, Integer port, String domainDn,
            String adminGroupDn, String usersGroupDn, String bindDn, String bindPassword, Integer connectTimeoutSeconds,
            Boolean ldaps, String trustMode, String trustPem) {
        @Override public String toString() { return "LdapSettingsService.Request[redacted]"; }
    }

    public record Problem(String field, String message) { }

    public sealed interface Outcome {
        record Saved(View view) implements Outcome { }
        record Invalid(List<Problem> problems) implements Outcome { }
        record Tested(DirectoryProbe.Report report) implements Outcome { }
    }

    private final LdapSettingsRepository settings;
    private final RoleBindingRepository bindings;
    private final CredentialStoreCipher passwords;
    private final String passwordKeyId;
    private final GroupReferenceCipher groups;
    private final Clock clock;
    private final Runnable settingsChanged;

    public LdapSettingsService(LdapSettingsRepository settings, RoleBindingRepository bindings,
            CredentialStoreCipher passwords, String passwordKeyId, GroupReferenceCipher groups, Clock clock,
            Runnable settingsChanged) {
        this.settings = Objects.requireNonNull(settings);
        this.bindings = Objects.requireNonNull(bindings);
        this.passwords = Objects.requireNonNull(passwords);
        this.passwordKeyId = Objects.requireNonNull(passwordKeyId);
        this.groups = Objects.requireNonNull(groups);
        this.clock = Objects.requireNonNull(clock);
        this.settingsChanged = Objects.requireNonNull(settingsChanged);
    }

    public View view() {
        return settings.find().map(LdapSettingsService::view).orElseGet(() -> new View(false, false, SERVER_TYPE,
                List.of(), 636, "", "", "", "", false, 10, true, DirectoryProfile.TrustMode.TRUST_PEM.name(), "", null,
                ADMIN_GROUP_ROLES, USERS_GROUP_ROLES));
    }

    private static View view(LdapSettingsRecord r) {
        return new View(true, r.enabled(), r.serverType(), r.servers(), r.port(), r.domainDn(), r.adminGroupDn(),
                r.usersGroupDn(), r.bindDn(), r.bindPasswordEncrypted() != null && r.bindPasswordEncrypted().length > 0,
                r.connectTimeoutSeconds(), r.ldaps(), r.trustMode(), r.trustPem() == null ? "" : r.trustPem(),
                r.updatedAt(), ADMIN_GROUP_ROLES, USERS_GROUP_ROLES);
    }

    public synchronized Outcome save(Request request, String actor) {
        if (actor == null || actor.isBlank()) return new Outcome.Invalid(List.of(new Problem("", "No signed-in actor.")));
        Optional<LdapSettingsRecord> stored = settings.find();
        Prepared prepared = prepare(request, stored);
        if (!prepared.problems().isEmpty()) return new Outcome.Invalid(prepared.problems());
        byte[] encrypted = prepared.newPassword() != null ? passwords.encrypt(prepared.newPassword())
                : stored.get().bindPasswordEncrypted();
        String keyId = prepared.newPassword() != null ? passwordKeyId : stored.get().bindPasswordKeyId();
        DirectoryProfile p = prepared.profile();
        settings.save(new LdapSettingsRecord(prepared.enabled(), SERVER_TYPE, p.servers(), p.port(), p.domainDn(),
                p.adminGroupDn(), p.usersGroupDn(), p.bindDn(), encrypted, keyId, p.timeoutSeconds(), p.ldaps(),
                p.trustMode().name(), p.ldaps() && p.trustMode() == DirectoryProfile.TrustMode.TRUST_PEM ? p.trustPem().strip() : null,
                null, actor));
        syncRoleBindings();
        settingsChanged.run();
        return new Outcome.Saved(view());
    }

    /** Service bind and searches with the form as entered; the stored password only under the same rule as save. */
    public Outcome test(Request request, String testUser) {
        Optional<LdapSettingsRecord> stored = settings.find();
        Prepared prepared = prepare(request, stored);
        if (!prepared.problems().isEmpty()) return new Outcome.Invalid(prepared.problems());
        char[] password = prepared.newPassword() != null ? prepared.newPassword().toCharArray()
                : servicePassword(stored.get()).get();
        return new Outcome.Tested(DirectoryProbe.run(prepared.profile(), password, testUser == null ? null : testUser.strip()));
    }

    /**
     * Brings the system-managed DIRECTORY_GROUP bindings in line with the stored settings, under the role-binding
     * table lock, reading the settings inside that lock so concurrent saves converge on the last committed row.
     */
    public void syncRoleBindings() {
        Instant now = clock.instant();
        bindings.directoryMutation(tx -> {
            Optional<LdapSettingsRecord> current = settings.find();
            Set<List<String>> desired = new LinkedHashSet<>();
            current.ifPresent(r -> {
                for (String token : ADMIN_GROUP_ROLES) desired.add(List.of(token, r.adminGroupDn()));
                for (String token : USERS_GROUP_ROLES) desired.add(List.of(token, r.usersGroupDn()));
            });
            for (RoleBindingRecord binding : tx.bindings().findAllActive()) {
                if (binding.bindingKind() != DirectoryBindingKind.DIRECTORY_GROUP
                        || !SYNC_ACTOR.equals(binding.createdByActorFingerprint())
                        || !DirectoryProfile.DEFAULT_PROFILE_ID.equals(binding.directoryProfileId())) {
                    continue;
                }
                String group;
                try {
                    group = groups.decryptDirectory(binding.groupReferenceEncrypted(), binding.directoryProfileId(),
                            binding.bindingKind(), binding.groupReferenceKeyId());
                } catch (RuntimeException e) {
                    group = null;
                }
                if (group == null || !desired.remove(List.of(binding.roleToken(), group))) {
                    tx.bindings().revoke(binding.bindingId(), SYNC_ACTOR, ActionRegistry.LDAP_CONFIG_WRITE);
                }
            }
            for (List<String> pair : desired) {
                tx.bindings().createDirectory(new RoleBindingRecord(OpaqueId.random().value(), pair.get(0),
                        groups.encryptDirectory(pair.get(1), DirectoryProfile.DEFAULT_PROFILE_ID, DirectoryBindingKind.DIRECTORY_GROUP),
                        groups.keyId(), SYNC_ACTOR, now, Optional.empty(), Optional.empty(),
                        DirectoryBindingKind.DIRECTORY_GROUP, DirectoryProfile.DEFAULT_PROFILE_ID), ActionRegistry.LDAP_CONFIG_WRITE);
            }
            return null;
        });
    }

    /** The adapter profile of a stored row; empty when the row no longer validates (it is then not used). */
    public static Optional<DirectoryProfile> profile(LdapSettingsRecord r) {
        try {
            return Optional.of(new DirectoryProfile(DirectoryProfile.DEFAULT_PROFILE_ID, r.servers(), r.port(),
                    r.domainDn(), r.adminGroupDn(), r.usersGroupDn(), r.bindDn(), r.connectTimeoutSeconds(), r.ldaps(),
                    DirectoryProfile.TrustMode.valueOf(r.trustMode()), r.trustPem()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** A fresh decrypted copy per call; the adapters zero it after the bind. */
    public Supplier<char[]> servicePassword(LdapSettingsRecord r) {
        byte[] encrypted = r.bindPasswordEncrypted();
        return () -> passwords.decrypt(encrypted).toCharArray();
    }

    private record Prepared(boolean enabled, DirectoryProfile profile, String newPassword, List<Problem> problems) { }

    private Prepared prepare(Request request, Optional<LdapSettingsRecord> stored) {
        List<Problem> problems = new ArrayList<>();
        if (request == null) return new Prepared(false, null, null, List.of(new Problem("", "The settings are missing.")));
        if (request.serverType() != null && !SERVER_TYPE.equals(request.serverType())) {
            problems.add(new Problem("serverType", "Only Active Directory is supported."));
        }
        List<String> servers = new ArrayList<>();
        if (request.servers() != null) {
            for (String server : request.servers()) {
                if (server != null && !server.isBlank()) servers.add(server.strip());
            }
        }
        int port = request.port() == null ? 636 : request.port();
        int timeout = request.connectTimeoutSeconds() == null ? 10 : request.connectTimeoutSeconds();
        boolean ldaps = request.ldaps() == null || request.ldaps();
        DirectoryProfile.TrustMode mode = null;
        if (request.trustMode() == null || request.trustMode().isBlank()) {
            mode = ldaps ? null : DirectoryProfile.TrustMode.TRUST_ANY;
        } else {
            try {
                mode = DirectoryProfile.TrustMode.valueOf(request.trustMode());
            } catch (IllegalArgumentException e) {
                problems.add(new Problem("trustMode", "Unknown trust mode."));
            }
        }
        if (!ldaps && mode == null) mode = DirectoryProfile.TrustMode.TRUST_ANY;
        String pem = request.trustPem() == null ? "" : request.trustPem().strip();
        String domainDn = strip(request.domainDn());
        String adminGroupDn = strip(request.adminGroupDn());
        String usersGroupDn = strip(request.usersGroupDn());
        String bindDn = strip(request.bindDn());
        for (DirectoryProfile.Problem problem : DirectoryProfile.problems(servers, port, domainDn, adminGroupDn,
                usersGroupDn, bindDn, timeout, ldaps, mode, pem, clock.instant(), true)) {
            problems.add(new Problem(problem.field(), problem.message()));
        }
        String newPassword = request.bindPassword() == null || request.bindPassword().isEmpty() ? null : request.bindPassword();
        if (newPassword != null && (newPassword.length() > 512 || newPassword.isBlank())) {
            problems.add(new Problem("bindPassword", "The bind password must be 1 to 512 characters."));
        }
        if (newPassword == null) {
            if (stored.isEmpty() || stored.get().bindPasswordEncrypted() == null) {
                problems.add(new Problem("bindPassword", "Enter the LDAP bind password."));
            } else if (problems.isEmpty() && !sameTarget(stored.get(), servers, port, bindDn, ldaps, mode, pem)) {
                problems.add(new Problem("bindPassword", "Enter the bind password again: the servers, port, bind DN or "
                        + "TLS settings changed, so the stored password is not sent to them."));
            }
        }
        if (!problems.isEmpty()) return new Prepared(false, null, null, problems);
        DirectoryProfile profile = new DirectoryProfile(DirectoryProfile.DEFAULT_PROFILE_ID, servers, port, domainDn,
                adminGroupDn, usersGroupDn, bindDn, timeout, ldaps, mode,
                ldaps && mode == DirectoryProfile.TrustMode.TRUST_PEM ? pem : null);
        return new Prepared(Boolean.TRUE.equals(request.enabled()), profile, newPassword, List.of());
    }

    private static boolean sameTarget(LdapSettingsRecord stored, List<String> servers, int port, String bindDn,
            boolean ldaps, DirectoryProfile.TrustMode mode, String pem) {
        String storedPem = stored.trustPem() == null ? "" : stored.trustPem().strip();
        boolean pemRelevant = ldaps && mode == DirectoryProfile.TrustMode.TRUST_PEM;
        return stored.servers().equals(servers) && stored.port() == port && Objects.equals(stored.bindDn(), bindDn)
                && stored.ldaps() == ldaps && (!ldaps || Objects.equals(stored.trustMode(), mode == null ? null : mode.name()))
                && (!pemRelevant || storedPem.equals(pem));
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
