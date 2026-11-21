package com.securityexpert.nexus.ui2.service.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SamlSettingsRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SamlSettingsRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.DirectoryBindingKind;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * Administration > SAML (migration plan Step 4, PO decisions D1-D5 of 2026-11-15): validates and stores the ADFS service
 * provider settings, keeps the optional SP private key encrypted under the credential store key, and synchronises the
 * system-managed {@code SAML_GROUP} role bindings (the group to role mapping stays inside neXus, as the LDAP one does).
 *
 * <p>SAML is off unless a row exists with {@code enabled = true}. The IdP data comes from pasted metadata XML or from fields
 * typed in; nothing is ever fetched. The private key is write-only: the view says only whether one is stored (or supplied
 * through the file paths {@code UI2_SAML_SP_PRIVATE_KEY_PATH} / {@code UI2_SAML_SP_CERT_PATH}, which win over a stored key).</p>
 */
public final class SamlSettingsService {
    private static final Logger LOG = LoggerFactory.getLogger("com.securityexpert.nexus.ui2.saml");

    public static final String REGISTRATION_ID = "adfs";
    /** The directory profile id of every SAML actor proof and {@code SAML_GROUP} binding; never equals an LDAP profile id. */
    public static final String PROFILE_ID = "saml:adfs";
    /** Prefix of the opaque actor fingerprint input (D2): {@code "saml:adfs:" + normalized NameID}. */
    public static final String FINGERPRINT_PREFIX = "saml:" + REGISTRATION_ID + ":";
    /** Creator of the system-managed SAML_GROUP bindings; hand-made bindings are never touched. */
    public static final String SYNC_ACTOR = "system:saml-settings";
    /** D1 default: the ADFS "Group" claim type. */
    public static final String DEFAULT_GROUP_CLAIM = "http://schemas.xmlsoap.org/claims/Group";
    public static final String AUTHENTICATE_PATH = "/saml2/authenticate/" + REGISTRATION_ID;
    public static final String ACS_PATH = "/login/saml2/sso/" + REGISTRATION_ID;
    public static final String METADATA_PATH = "/saml2/service-provider-metadata/" + REGISTRATION_ID;
    public static final int DEFAULT_CLOCK_SKEW_SECONDS = 60;
    public static final int MAX_CLOCK_SKEW_SECONDS = 300;

    /** Same role mapping as the LDAP administrator and users groups (PO decision record 2026-11-11). */
    public static final List<String> ADMIN_GROUP_ROLES = LdapSettingsService.ADMIN_GROUP_ROLES;
    public static final List<String> USERS_GROUP_ROLES = LdapSettingsService.USERS_GROUP_ROLES;

    /** Where the SP key pair comes from: NONE, FILE (environment paths) or SETTINGS (encrypted in the settings row). */
    public record SpKeyView(String source, String certPem, boolean privateKeySet) { }

    /** What the browser reads: never the private key or its ciphertext. */
    public record View(boolean configured, boolean enabled, String spBaseUrl, String spEntityId, String acsUrl,
            String metadataUrl, String loginUrl, String idpEntityId, String idpSsoUrl, String idpSsoBinding,
            String idpSigningCertPem, String groupClaimAttribute, String adminGroupValue, String usersGroupValue,
            int clockSkewSeconds, boolean signAuthnRequests, SpKeyView spKey, Instant updatedAt,
            List<String> adminGroupRoles, List<String> usersGroupRoles) { }

    /**
     * What the browser and the CLI send. {@code idpMetadataXml}, when present, replaces the four IdP fields. A blank
     * {@code spPrivateKeyPem} keeps the stored key; {@code clearSpKey} removes it. A request holding only
     * {@code enabled = false} switches SAML off and changes nothing else.
     */
    public record Request(Boolean enabled, String spBaseUrl, String spEntityId, String idpMetadataXml, String idpEntityId,
            String idpSsoUrl, String idpSsoBinding, String idpSigningCertPem, String groupClaimAttribute,
            String adminGroupValue, String usersGroupValue, Integer clockSkewSeconds, Boolean signAuthnRequests,
            String spCertPem, String spPrivateKeyPem, Boolean clearSpKey) {
        @Override public String toString() { return "SamlSettingsService.Request[redacted]"; }

        boolean onlyDisables() {
            return Boolean.FALSE.equals(enabled) && spBaseUrl == null && spEntityId == null && idpMetadataXml == null
                    && idpEntityId == null && idpSsoUrl == null && idpSsoBinding == null && idpSigningCertPem == null
                    && groupClaimAttribute == null && adminGroupValue == null && usersGroupValue == null
                    && clockSkewSeconds == null && signAuthnRequests == null && spCertPem == null && spPrivateKeyPem == null
                    && clearSpKey == null;
        }
    }

    public record Problem(String field, String message) { }

    public sealed interface Outcome {
        record Saved(View view) implements Outcome { }
        record Invalid(List<Problem> problems) implements Outcome { }
    }

    /** The environment-supplied SP key files (D5); either may be absent. */
    public record SpKeyFiles(Path privateKey, Path certificate) {
        public static SpKeyFiles none() { return new SpKeyFiles(null, null); }
        boolean configured() { return privateKey != null && certificate != null; }
    }

    /** The SP key pair in use, whatever its source. */
    public record SpKeyPair(PrivateKey privateKey, X509Certificate certificate, String source) { }

    private final SamlSettingsRepository settings;
    private final RoleBindingRepository bindings;
    private final CredentialStoreCipher keys;
    private final String keyId;
    private final GroupReferenceCipher groups;
    private final Clock clock;
    private final SpKeyFiles files;
    private final Runnable settingsChanged;

    private volatile Boolean enabledCache;
    private volatile long enabledCacheAt;

    public SamlSettingsService(SamlSettingsRepository settings, RoleBindingRepository bindings, CredentialStoreCipher keys,
            String keyId, GroupReferenceCipher groups, Clock clock, SpKeyFiles files, Runnable settingsChanged) {
        this.settings = Objects.requireNonNull(settings);
        this.bindings = Objects.requireNonNull(bindings);
        this.keys = Objects.requireNonNull(keys);
        this.keyId = Objects.requireNonNull(keyId);
        this.groups = Objects.requireNonNull(groups);
        this.clock = Objects.requireNonNull(clock);
        this.files = files == null ? SpKeyFiles.none() : files;
        this.settingsChanged = Objects.requireNonNull(settingsChanged);
    }

    /** True when SAML sign-in is switched on. A read failure counts as off; cached for a few seconds. */
    public boolean isEnabled() {
        Boolean cached = enabledCache;
        if (cached != null && System.nanoTime() - enabledCacheAt < 5_000_000_000L) return cached;
        boolean enabled;
        try {
            enabled = settings.find().map(SamlSettingsRecord::enabled).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
        enabledCacheAt = System.nanoTime();
        enabledCache = enabled;
        return enabled;
    }

    public Optional<SamlSettingsRecord> current() {
        try {
            return settings.find();
        } catch (RuntimeException e) {
            LOG.warn("saml_settings_unreadable");
            return Optional.empty();
        }
    }

    public View view() {
        return settings.find().map(this::view).orElseGet(() -> new View(false, false, "", "", "", "", "", "", "", "REDIRECT", "",
                DEFAULT_GROUP_CLAIM, "", "", DEFAULT_CLOCK_SKEW_SECONDS, false, spKeyView(null), null, ADMIN_GROUP_ROLES,
                USERS_GROUP_ROLES));
    }

    private View view(SamlSettingsRecord r) {
        String base = stripTrailingSlash(r.spBaseUrl());
        return new View(true, r.enabled(), r.spBaseUrl(), r.spEntityId(), base + ACS_PATH, base + METADATA_PATH,
                base + AUTHENTICATE_PATH, r.idpEntityId(), r.idpSsoUrl(), r.idpSsoBinding(), r.idpSigningCertPem(),
                r.groupClaimAttribute(), r.adminGroupValue(), r.usersGroupValue(), r.clockSkewSeconds(),
                r.signAuthnRequests(), spKeyView(r), r.updatedAt(), ADMIN_GROUP_ROLES, USERS_GROUP_ROLES);
    }

    private SpKeyView spKeyView(SamlSettingsRecord r) {
        Optional<SpKeyPair> pair = spKey(r);
        if (pair.isEmpty()) return new SpKeyView("NONE", "", false);
        return new SpKeyView(pair.get().source(), SamlPem.toPem(pair.get().certificate()), true);
    }

    /** The SP key pair: the environment files when both are configured and readable, else the stored encrypted key. */
    public Optional<SpKeyPair> spKey(SamlSettingsRecord r) {
        if (files.configured()) {
            try {
                PrivateKey key = SamlPem.privateKey(Files.readString(files.privateKey(), StandardCharsets.US_ASCII));
                List<X509Certificate> certs = SamlPem.certificates(Files.readString(files.certificate(), StandardCharsets.US_ASCII));
                if (key != null && certs.size() == 1 && SamlPem.matches(key, certs.get(0))) {
                    return Optional.of(new SpKeyPair(key, certs.get(0), "FILE"));
                }
                LOG.warn("saml_sp_key_files_rejected");
            } catch (java.io.IOException | RuntimeException e) {
                LOG.warn("saml_sp_key_files_unreadable");
            }
        }
        if (r != null && r.spPrivateKeyEncrypted() != null && r.spCertPem() != null) {
            try {
                PrivateKey key = SamlPem.privateKey(keys.decrypt(r.spPrivateKeyEncrypted()));
                List<X509Certificate> certs = SamlPem.certificates(r.spCertPem());
                if (key != null && certs.size() == 1) return Optional.of(new SpKeyPair(key, certs.get(0), "SETTINGS"));
            } catch (RuntimeException e) {
                LOG.warn("saml_sp_key_unreadable");
            }
        }
        return Optional.empty();
    }

    public synchronized Outcome save(Request request, String actor) {
        if (actor == null || actor.isBlank()) return new Outcome.Invalid(List.of(new Problem("", "No signed-in actor.")));
        if (request == null) return new Outcome.Invalid(List.of(new Problem("", "The settings are missing.")));
        Optional<SamlSettingsRecord> stored = settings.find();
        if (request.onlyDisables()) {
            if (stored.isEmpty()) return new Outcome.Saved(view());
            SamlSettingsRecord s = stored.get();
            persist(new SamlSettingsRecord(false, s.spBaseUrl(), s.spEntityId(), s.idpEntityId(), s.idpSsoUrl(),
                    s.idpSsoBinding(), s.idpSigningCertPem(), s.groupClaimAttribute(), s.adminGroupValue(), s.usersGroupValue(),
                    s.clockSkewSeconds(), s.signAuthnRequests(), s.spCertPem(), s.spPrivateKeyEncrypted(),
                    s.spPrivateKeyKeyId(), null, actor));
            return new Outcome.Saved(view());
        }
        List<Problem> problems = new ArrayList<>();
        SamlSettingsRecord record = prepare(request, stored, actor, problems);
        if (!problems.isEmpty()) return new Outcome.Invalid(problems);
        persist(record);
        return new Outcome.Saved(view());
    }

    private void persist(SamlSettingsRecord record) {
        settings.save(record);
        enabledCache = null;
        syncRoleBindings();
        settingsChanged.run();
    }

    private SamlSettingsRecord prepare(Request request, Optional<SamlSettingsRecord> stored, String actor, List<Problem> problems) {
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        String baseUrl = strip(request.spBaseUrl());
        if (!validUrl(baseUrl, true) || baseUrl.contains("?") || baseUrl.contains("#")) {
            problems.add(new Problem("spBaseUrl", "Enter the address users reach Overwatch at (https://host[:port]), without a path."));
        } else if (URI.create(stripTrailingSlash(baseUrl)).getPath() != null && !URI.create(stripTrailingSlash(baseUrl)).getPath().isEmpty()) {
            problems.add(new Problem("spBaseUrl", "Enter the address users reach Overwatch at (https://host[:port]), without a path."));
        }
        String entityId = strip(request.spEntityId());
        if (entityId == null || entityId.isEmpty()) {
            entityId = validUrl(baseUrl, true) ? stripTrailingSlash(baseUrl) + METADATA_PATH : "";
        }
        boolean baseUrlProblem = problems.stream().anyMatch(p -> "spBaseUrl".equals(p.field()));
        if ((entityId.isEmpty() && !baseUrlProblem) || entityId.length() > 1024) {
            problems.add(new Problem("spEntityId", "Enter the service provider entity id."));
        }

        String idpEntityId = strip(request.idpEntityId());
        String ssoUrl = strip(request.idpSsoUrl());
        String binding = request.idpSsoBinding() == null || request.idpSsoBinding().isBlank() ? "REDIRECT"
                : request.idpSsoBinding().strip().toUpperCase(Locale.ROOT);
        String certPem = request.idpSigningCertPem() == null ? "" : request.idpSigningCertPem().strip();
        boolean metadataFailed = false;
        if (request.idpMetadataXml() != null && !request.idpMetadataXml().isBlank()) {
            try {
                SamlIdpMetadata.Parsed parsed = SamlIdpMetadata.parse(request.idpMetadataXml());
                idpEntityId = parsed.entityId();
                ssoUrl = parsed.ssoUrl();
                binding = parsed.binding();
                certPem = parsed.signingCertsPem().strip();
            } catch (IllegalArgumentException e) {
                problems.add(new Problem("idpMetadataXml", e.getMessage()));
                metadataFailed = true;
            }
        }
        if (!metadataFailed) validateIdp(idpEntityId, ssoUrl, binding, certPem, problems);
        String claim = strip(request.groupClaimAttribute());
        if (claim == null || claim.isEmpty()) claim = DEFAULT_GROUP_CLAIM;
        if (claim.length() > 512) problems.add(new Problem("groupClaimAttribute", "The group claim name is too long."));
        String admin = strip(request.adminGroupValue());
        String users = strip(request.usersGroupValue());
        if (admin == null || admin.isEmpty() || admin.length() > 512) {
            problems.add(new Problem("adminGroupValue", "Enter the administrator group value (1 to 512 characters)."));
        }
        if (users == null || users.isEmpty() || users.length() > 512) {
            problems.add(new Problem("usersGroupValue", "Enter the users group value (1 to 512 characters)."));
        }
        if (admin != null && admin.equalsIgnoreCase(users)) {
            problems.add(new Problem("usersGroupValue", "The administrator and users groups must be different."));
        }
        int skew = request.clockSkewSeconds() == null ? DEFAULT_CLOCK_SKEW_SECONDS : request.clockSkewSeconds();
        if (skew < 0 || skew > MAX_CLOCK_SKEW_SECONDS) {
            problems.add(new Problem("clockSkewSeconds", "The clock skew must be between 0 and " + MAX_CLOCK_SKEW_SECONDS + " seconds."));
        }

        // SP key pair (D5): a new pair, the stored pair, or none.
        String spCert = request.spCertPem() == null ? "" : request.spCertPem().strip();
        String newKeyPem = request.spPrivateKeyPem() == null ? "" : request.spPrivateKeyPem().strip();
        boolean clear = Boolean.TRUE.equals(request.clearSpKey());
        byte[] keyCipher = null;
        String keyKeyId = null;
        String storedCert = null;
        if (!newKeyPem.isEmpty()) {
            PrivateKey key = SamlPem.privateKey(newKeyPem);
            List<X509Certificate> certs = SamlPem.certificates(spCert);
            if (key == null) {
                problems.add(new Problem("spPrivateKeyPem", "The private key must be an unencrypted PKCS#8 PEM (BEGIN PRIVATE KEY), RSA or EC."));
            } else if (certs.size() != 1 || !SamlPem.onlyCertificates(spCert)) {
                problems.add(new Problem("spCertPem", "Enter the service provider certificate (one PEM certificate) that goes with the key."));
            } else if (!SamlPem.matches(key, certs.get(0))) {
                problems.add(new Problem("spCertPem", "The certificate does not belong to the private key."));
            } else if (!SamlPem.anyValidAt(certs, clock.instant())) {
                problems.add(new Problem("spCertPem", "The service provider certificate is expired or not yet valid."));
            } else {
                keyCipher = keys.encrypt(newKeyPem);
                keyKeyId = keyId;
                storedCert = SamlPem.toPem(certs.get(0));
            }
        } else if (!clear && stored.isPresent() && stored.get().spPrivateKeyEncrypted() != null) {
            keyCipher = stored.get().spPrivateKeyEncrypted();
            keyKeyId = stored.get().spPrivateKeyKeyId();
            storedCert = stored.get().spCertPem();
        }
        boolean sign = Boolean.TRUE.equals(request.signAuthnRequests());
        if (sign && storedCert == null && !files.configured()) {
            problems.add(new Problem("signAuthnRequests", "Signing sign-in requests needs a service provider key pair "
                    + "(enter one, or set UI2_SAML_SP_PRIVATE_KEY_PATH and UI2_SAML_SP_CERT_PATH)."));
        }
        if (!problems.isEmpty()) return null;
        return new SamlSettingsRecord(enabled, stripTrailingSlash(baseUrl), entityId, idpEntityId, ssoUrl, binding,
                certPem.endsWith("\n") ? certPem : certPem + "\n", claim, admin, users, skew, sign, storedCert, keyCipher,
                keyKeyId, null, actor);
    }


    private void validateIdp(String idpEntityId, String ssoUrl, String binding, String certPem, List<Problem> problems) {
        if (idpEntityId == null || idpEntityId.isBlank() || idpEntityId.length() > 1024) {
            problems.add(new Problem("idpEntityId", "Enter the identity provider entity id, or paste its metadata."));
        }
        if (!validUrl(ssoUrl, false) || ssoUrl.length() > 2048) {
            problems.add(new Problem("idpSsoUrl", "Enter the identity provider's https sign-in URL, or paste its metadata."));
        }
        if (!"REDIRECT".equals(binding) && !"POST".equals(binding)) {
            problems.add(new Problem("idpSsoBinding", "The sign-in binding must be REDIRECT or POST."));
        }
        List<X509Certificate> idpCerts = SamlPem.certificates(certPem);
        if (idpCerts.isEmpty() || !SamlPem.onlyCertificates(certPem)) {
            problems.add(new Problem("idpSigningCertPem", "Enter the identity provider's signing certificate(s) in PEM form, "
                    + "or paste its metadata."));
        } else if (!SamlPem.anyValidAt(idpCerts, clock.instant())) {
            problems.add(new Problem("idpSigningCertPem", "Every identity provider signing certificate is expired or not yet valid."));
        }
    }

    /**
     * Brings the system-managed SAML_GROUP bindings in line with the stored settings, under the role-binding table lock:
     * while SAML is on, the administrator group maps to the administrator roles and the users group to viewer; while it is
     * off no SAML binding exists, so an open SAML session loses its roles at once (fail closed).
     */
    public void syncRoleBindings() {
        Instant now = clock.instant();
        bindings.directoryMutation(tx -> {
            Optional<SamlSettingsRecord> current = settings.find().filter(SamlSettingsRecord::enabled);
            Set<List<String>> desired = new LinkedHashSet<>();
            current.ifPresent(r -> {
                for (String token : ADMIN_GROUP_ROLES) desired.add(List.of(token, r.adminGroupValue()));
                for (String token : USERS_GROUP_ROLES) desired.add(List.of(token, r.usersGroupValue()));
            });
            for (RoleBindingRecord binding : tx.bindings().findAllActive()) {
                if (binding.bindingKind() != DirectoryBindingKind.SAML_GROUP
                        || !SYNC_ACTOR.equals(binding.createdByActorFingerprint())
                        || !PROFILE_ID.equals(binding.directoryProfileId())) {
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
                    tx.bindings().revoke(binding.bindingId(), SYNC_ACTOR, ActionRegistry.SAML_CONFIG_WRITE);
                }
            }
            for (List<String> pair : desired) {
                tx.bindings().createDirectory(new RoleBindingRecord(OpaqueId.random().value(), pair.get(0),
                        groups.encryptDirectory(pair.get(1), PROFILE_ID, DirectoryBindingKind.SAML_GROUP),
                        groups.keyId(), SYNC_ACTOR, now, Optional.empty(), Optional.empty(),
                        DirectoryBindingKind.SAML_GROUP, PROFILE_ID), ActionRegistry.SAML_CONFIG_WRITE);
            }
            return null;
        });
    }

    private static boolean validUrl(String value, boolean allowLoopbackHttp) {
        if (value == null || value.isEmpty() || value.length() > 2048) return false;
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null) return false;
            if ("https".equalsIgnoreCase(uri.getScheme())) return true;
            return allowLoopbackHttp && "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    static String stripTrailingSlash(String value) {
        if (value == null) return null;
        String result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
