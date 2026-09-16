package com.securityexpert.nexus.ui2.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.bootstrap.BackupRetrievalFactory;
import com.securityexpert.nexus.ui2.jobs.bootstrap.CredentialAdministrationFactory;
import com.securityexpert.nexus.ui2.jobs.bootstrap.LocalIdentityAdministrationFactory;
import com.securityexpert.nexus.ui2.jobs.bootstrap.LocalIdentityBootstrapFactory;
import com.securityexpert.nexus.ui2.jobs.bootstrap.SecurityAdminBootstrapFactory;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.LocalIdentityAdministrationPort;
import com.securityexpert.nexus.ui2.platform.LocalIdentityBootstrapPort;
import com.securityexpert.nexus.ui2.platform.SecurityAdminBootstrapPort;

/**
 * A thin typed command entry point using the same application ports as
 * the other roles (architecture §8.3; contract §2 cli row).
 *
 * <p>{@code bootstrap-security-admin} is the CLI-only, deployment
 * -controlled action that creates the very first {@code role:security_admin}
 * binding (C3 §4.3, adjudication F8) — reachable only from here, never a
 * running service, never the browser. It is never invoked by a Routine, a
 * scheduled job, or any HTTP path.</p>
 *
 * <p>{@code bootstrap-local-identity} is the equivalent, deployment
 * -controlled seeding action for a local authentication identity (C3A
 * contract §6, §11 U-2). For {@code nexusadmin}, its printed
 * {@code local_identity_id} is then the {@code groupReferenceDn} argument
 * of a {@code bootstrap-security-admin} call, per C3A §7.1: a local
 * identity's {@code role_bindings} row encrypts a reference to itself, not
 * a directory group. {@code claudeadmin}'s role binding is left
 * unassigned, per C3A §7.3/§11 U-5.</p>
 */
public final class CliEntryPoint {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        switch (args[0]) {
            case "bootstrap-security-admin" -> bootstrapSecurityAdmin(args);
            case "bootstrap-local-identity" -> bootstrapLocalIdentity(args);
            case "local-identity-create" -> localIdentityCreate(args);
            case "local-identity-list" -> localIdentityList(args);
            case "local-identity-set-password" -> localIdentitySetPassword(args);
            case "local-identity-disable" -> localIdentityDisable(args);
            case "local-identity-enable" -> localIdentityEnable(args);
            case "credential-create" -> credentialCreate(args);
            case "credential-list" -> credentialList(args);
            case "credential-replace-secret" -> credentialReplaceSecret(args);
            case "credential-delete" -> credentialDelete(args);
            case "role-bind-create" -> roleBindCreate(args);
            case "role-bind-revoke" -> roleBindRevoke(args);
            case "backup-retrieve" -> backupRetrieve(args);
            default -> System.out.println("known job states: " + java.util.Arrays.toString(JobState.values()));
        }
    }

    private static void printUsage() {
        System.out.println("usage: cli <command>");
        System.out.println("  bootstrap-security-admin <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<groupReferenceDn> <groupReferenceKeyBase64> <groupReferenceKeyId>");
        System.out.println("  bootstrap-local-identity <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<localIdentityName> <initialPasswordFile>");
        System.out.println("  local-identity-create <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<groupReferenceKeyBase64> <localIdentityName> <initialPasswordFile> <actingAdminActorFingerprint>");
        System.out.println("  local-identity-list <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<groupReferenceKeyBase64>");
        System.out.println("  local-identity-set-password <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<groupReferenceKeyBase64> <localIdentityId> <newPasswordFile> <actingAdminActorFingerprint>");
        System.out.println("  local-identity-disable <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<groupReferenceKeyBase64> <localIdentityId> <actingAdminActorFingerprint>");
        System.out.println("  local-identity-enable <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<groupReferenceKeyBase64> <localIdentityId> <actingAdminActorFingerprint>");
        System.out.println("  credential-create <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<credentialStoreKeyBase64> <credentialStoreKeyId> <displayName> "
                + "<ssh_password|ssh_private_key|api_password> <username> <allowsCheckPoint:true|false> "
                + "<allowsPaloAlto:true|false> <secretFile> <passphraseFile|-> <actingAdminActorFingerprint>");
        System.out.println("  credential-list <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<credentialStoreKeyBase64> <credentialStoreKeyId>");
        System.out.println("  credential-replace-secret <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<credentialStoreKeyBase64> <credentialStoreKeyId> <credentialId> <secretFile> "
                + "<passphraseFile|-> <actingAdminActorFingerprint>");
        System.out.println("  credential-delete <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<credentialStoreKeyBase64> <credentialStoreKeyId> <credentialId> <actingAdminActorFingerprint>");
        System.out.println("  role-bind-create <baseUrl> <sessionCookieValue> <csrfToken> <roleToken> "
                + "<selectionHandle> <directoryProfileId> <bindingKind>");
        System.out.println("  role-bind-revoke <baseUrl> <sessionCookieValue> <csrfToken> <bindingId>");
        System.out.println("  backup-retrieve <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<artefactStoreKeyBase64> <artefactStoreRoot> <groupReferenceKeyBase64> <artefactId> "
                + "<destinationPath> <reason (>= 8 chars)> <actingAdminActorFingerprint>");
    }

    private static void bootstrapSecurityAdmin(String[] args) {
        if (args.length != 7) {
            System.err.println("bootstrap-security-admin requires exactly 6 arguments; see usage.");
            printUsage();
            return;
        }
        String jdbcUrl = args[1];
        String migrateUser = args[2];
        String migratePasswordFile = args[3];
        String groupReferenceDn = args[4];
        String groupReferenceKeyBase64 = args[5];
        String groupReferenceKeyId = args[6];

        String migratePassword = com.securityexpert.nexus.ui2.platform.SecretFile.readRequired(
                java.nio.file.Path.of(migratePasswordFile), "ui2_migrate.password");

        SecurityAdminBootstrapPort bootstrap =
                SecurityAdminBootstrapFactory.create(jdbcUrl, migrateUser, migratePassword);
        GroupReferenceCipher cipher = GroupReferenceCipher.fromBase64Key(groupReferenceKeyBase64);
        byte[] encrypted = cipher.encrypt(groupReferenceDn);

        String bindingId = bootstrap.bootstrapFirstSecurityAdminBinding(encrypted, groupReferenceKeyId);
        // Never print the plaintext group reference (C3 §4.2: "no corporate
        // group name ever appears in application code, a log line, an API
        // response, or a projection -- every reference outside this one
        // encrypted column is binding_id only").
        System.out.println("created role:security_admin binding " + bindingId);
    }

    private static void bootstrapLocalIdentity(String[] args) {
        if (args.length != 6) {
            System.err.println("bootstrap-local-identity requires exactly 5 arguments; see usage.");
            printUsage();
            return;
        }
        String jdbcUrl = args[1];
        String migrateUser = args[2];
        String migratePasswordFile = args[3];
        String localIdentityName = args[4];
        String initialPasswordFile = args[5];

        String migratePassword = com.securityexpert.nexus.ui2.platform.SecretFile.readRequired(
                java.nio.file.Path.of(migratePasswordFile), "ui2_migrate.password");
        char[] initialPassword = com.securityexpert.nexus.ui2.platform.SecretFile.readRequired(
                java.nio.file.Path.of(initialPasswordFile), "local_identity.initial_password").toCharArray();

        LocalIdentityBootstrapPort bootstrap =
                LocalIdentityBootstrapFactory.create(jdbcUrl, migrateUser, migratePassword);
        String localIdentityId;
        try {
            localIdentityId = bootstrap.bootstrapLocalIdentity(localIdentityName, initialPassword);
        } finally {
            java.util.Arrays.fill(initialPassword, '\0');
        }
        // Never print the password, its hash, or any parameter (C3A §3.1/§8).
        System.out.println("created local identity " + localIdentityId + " name=" + localIdentityName);
    }

    // ---------------------------------------------------------------
    // 13G LIA-2: local identity administration CLI parity. Every
    // operation shares LocalIdentityAdministrationPort with the HTTP API
    // (composed here through job-engine's LocalIdentityAdministrationFactory,
    // exactly as bootstrap-local-identity already shares its own port), so
    // the two paths cannot drift. A password is always read from a file,
    // never a command-line argument (it would land in shell history/process
    // listings), and the in-memory array is zeroed immediately after use.
    // No response ever prints a password, verifier or salt.
    // ---------------------------------------------------------------

    private static void localIdentityCreate(String[] args) {
        if (args.length != 8) {
            System.err.println("local-identity-create requires exactly 7 arguments; see usage.");
            printUsage();
            return;
        }
        LocalIdentityAdministrationPort port = administrationPort(args[1], args[2], args[3], args[4]);
        String localIdentityName = args[5];
        char[] initialPassword = readPasswordFile(args[6], "local_identity.initial_password");
        String actingAdminActorFingerprint = args[7];
        try {
            LocalIdentityAdministrationPort.LocalIdentityView view =
                    port.create(actingAdminActorFingerprint, localIdentityName, initialPassword);
            printView(view);
        } finally {
            Arrays.fill(initialPassword, '\0');
        }
    }

    private static void localIdentityList(String[] args) {
        if (args.length != 5) {
            System.err.println("local-identity-list requires exactly 4 arguments; see usage.");
            printUsage();
            return;
        }
        LocalIdentityAdministrationPort port = administrationPort(args[1], args[2], args[3], args[4]);
        for (LocalIdentityAdministrationPort.LocalIdentityView view : port.list()) {
            printView(view);
        }
    }

    private static void localIdentitySetPassword(String[] args) {
        if (args.length != 8) {
            System.err.println("local-identity-set-password requires exactly 7 arguments; see usage.");
            printUsage();
            return;
        }
        LocalIdentityAdministrationPort port = administrationPort(args[1], args[2], args[3], args[4]);
        String localIdentityId = args[5];
        char[] newPassword = readPasswordFile(args[6], "local_identity.new_password");
        String actingAdminActorFingerprint = args[7];
        try {
            printResult(port.setPassword(actingAdminActorFingerprint, localIdentityId, newPassword));
        } finally {
            Arrays.fill(newPassword, '\0');
        }
    }

    private static void localIdentityDisable(String[] args) {
        if (args.length != 7) {
            System.err.println("local-identity-disable requires exactly 6 arguments; see usage.");
            printUsage();
            return;
        }
        LocalIdentityAdministrationPort port = administrationPort(args[1], args[2], args[3], args[4]);
        String localIdentityId = args[5];
        String actingAdminActorFingerprint = args[6];
        printResult(port.disable(actingAdminActorFingerprint, localIdentityId));
    }

    private static void localIdentityEnable(String[] args) {
        if (args.length != 7) {
            System.err.println("local-identity-enable requires exactly 6 arguments; see usage.");
            printUsage();
            return;
        }
        LocalIdentityAdministrationPort port = administrationPort(args[1], args[2], args[3], args[4]);
        String localIdentityId = args[5];
        String actingAdminActorFingerprint = args[6];
        printResult(port.enable(actingAdminActorFingerprint, localIdentityId));
    }

    private static LocalIdentityAdministrationPort administrationPort(String jdbcUrl, String migrateUser,
            String migratePasswordFile, String groupReferenceKeyBase64) {
        String migratePassword = com.securityexpert.nexus.ui2.platform.SecretFile.readRequired(
                java.nio.file.Path.of(migratePasswordFile), "ui2_migrate.password");
        return LocalIdentityAdministrationFactory.create(jdbcUrl, migrateUser, migratePassword, groupReferenceKeyBase64);
    }

    // ---------------------------------------------------------------
    // 2026-09-14 CS-1..CS-5: credential store CLI parity. Every operation
    // shares CredentialStorePort with the HTTP API (composed here through
    // job-engine's CredentialAdministrationFactory, exactly as
    // local-identity-* already shares its own port), so the two paths
    // cannot drift. A secret (and an optional passphrase) is always read
    // from a file, never a command-line argument (it would land in shell
    // history/process listings), and the in-memory array is zeroed
    // immediately after use. No response ever prints a secret, an
    // encrypted column, or the envelope key id.
    // ---------------------------------------------------------------

    private static void credentialCreate(String[] args) {
        if (args.length != 14) {
            System.err.println("credential-create requires exactly 13 arguments; see usage.");
            printUsage();
            return;
        }
        CredentialStorePort port = credentialStorePort(args[1], args[2], args[3], args[4], args[5]);
        String displayName = args[6];
        CredentialKind kind = CredentialKind.fromWireValue(args[7]);
        String username = args[8];
        boolean allowsCheckPoint = Boolean.parseBoolean(args[9]);
        boolean allowsPaloAlto = Boolean.parseBoolean(args[10]);
        char[] secret = readPasswordFile(args[11], "credential.secret");
        Optional<char[]> passphrase = readOptionalPassphraseFile(args[12]);
        String actingAdminActorFingerprint = args[13];
        try {
            CredentialStorePort.CredentialView view = port.create(actingAdminActorFingerprint, displayName, kind,
                    username, allowsCheckPoint, allowsPaloAlto, secret, passphrase);
            printCredentialView(view);
        } finally {
            Arrays.fill(secret, '\0');
            passphrase.ifPresent(p -> Arrays.fill(p, '\0'));
        }
    }

    private static void credentialList(String[] args) {
        if (args.length != 6) {
            System.err.println("credential-list requires exactly 5 arguments; see usage.");
            printUsage();
            return;
        }
        CredentialStorePort port = credentialStorePort(args[1], args[2], args[3], args[4], args[5]);
        for (CredentialStorePort.CredentialView view : port.list()) {
            printCredentialView(view);
        }
    }

    private static void credentialReplaceSecret(String[] args) {
        if (args.length != 10) {
            System.err.println("credential-replace-secret requires exactly 9 arguments; see usage.");
            printUsage();
            return;
        }
        CredentialStorePort port = credentialStorePort(args[1], args[2], args[3], args[4], args[5]);
        String credentialId = args[6];
        char[] secret = readPasswordFile(args[7], "credential.secret");
        Optional<char[]> passphrase = readOptionalPassphraseFile(args[8]);
        String actingAdminActorFingerprint = args[9];
        try {
            CredentialStorePort.ReplaceSecretResult result =
                    port.replaceSecret(actingAdminActorFingerprint, credentialId, secret, passphrase);
            if (result instanceof CredentialStorePort.ReplaceSecretResult.Ok ok) {
                printCredentialView(ok.view());
            } else {
                System.err.println("CREDENTIAL_NOT_FOUND");
            }
        } finally {
            Arrays.fill(secret, '\0');
            passphrase.ifPresent(p -> Arrays.fill(p, '\0'));
        }
    }

    private static void credentialDelete(String[] args) {
        if (args.length != 8) {
            System.err.println("credential-delete requires exactly 7 arguments; see usage.");
            printUsage();
            return;
        }
        CredentialStorePort port = credentialStorePort(args[1], args[2], args[3], args[4], args[5]);
        String credentialId = args[6];
        String actingAdminActorFingerprint = args[7];
        CredentialStorePort.DeleteResult result = port.delete(actingAdminActorFingerprint, credentialId);
        if (result instanceof CredentialStorePort.DeleteResult.Ok) {
            System.out.println("deleted credential " + credentialId);
        } else if (result instanceof CredentialStorePort.DeleteResult.NotFound) {
            System.err.println("CREDENTIAL_NOT_FOUND");
        } else if (result instanceof CredentialStorePort.DeleteResult.CredentialInUse) {
            System.err.println("CREDENTIAL_IN_USE");
        }
    }

    private static CredentialStorePort credentialStorePort(String jdbcUrl, String migrateUser,
            String migratePasswordFile, String credentialStoreKeyBase64, String credentialStoreKeyId) {
        String migratePassword = com.securityexpert.nexus.ui2.platform.SecretFile.readRequired(
                java.nio.file.Path.of(migratePasswordFile), "ui2_migrate.password");
        return CredentialAdministrationFactory.create(jdbcUrl, migrateUser, migratePassword, credentialStoreKeyBase64,
                credentialStoreKeyId);
    }

    /** {@code "-"} means no passphrase -- never a command-line literal for the passphrase itself. */
    private static Optional<char[]> readOptionalPassphraseFile(String path) {
        if ("-".equals(path)) {
            return Optional.empty();
        }
        return Optional.of(readPasswordFile(path, "credential.passphrase"));
    }

    /** Never a secret, an encrypted column, or the envelope key id -- exactly CS-1/CS-3's allowlisted fields. */
    private static void printCredentialView(CredentialStorePort.CredentialView view) {
        System.out.println("credential_id=" + view.credentialId() + " credential_reference_id="
                + view.credentialReferenceId() + " display_name=" + view.displayName() + " kind="
                + view.kind().wireValue() + " username=" + view.username() + " allows_check_point="
                + view.allowsCheckPoint() + " allows_palo_alto=" + view.allowsPaloAlto() + " created_at="
                + view.createdAt() + " secret_set_at=" + view.secretSetAt());
    }

    private static char[] readPasswordFile(String path, String purpose) {
        return com.securityexpert.nexus.ui2.platform.SecretFile.readRequired(java.nio.file.Path.of(path), purpose)
                .toCharArray();
    }

    /** Never a password, verifier or salt -- exactly 13G section 3's allowlisted fields. */
    private static void printView(LocalIdentityAdministrationPort.LocalIdentityView view) {
        System.out.println("local_identity_id=" + view.localIdentityId() + " local_identity_name="
                + view.localIdentityName() + " enabled=" + view.enabled() + " must_change_password="
                + view.mustChangePassword() + " created_at=" + view.createdAt() + " password_set_at="
                + view.passwordSetAt());
    }

    private static void printResult(LocalIdentityAdministrationPort.MutationResult result) {
        if (result instanceof LocalIdentityAdministrationPort.MutationResult.Ok ok) {
            printView(ok.view());
        } else if (result instanceof LocalIdentityAdministrationPort.MutationResult.NotFound) {
            System.err.println("LOCAL_IDENTITY_NOT_FOUND");
        } else if (result instanceof LocalIdentityAdministrationPort.MutationResult.LastSecurityAdminRefused) {
            System.err.println("LAST_SECURITY_ADMIN_REFUSED");
        }
    }

    // ---------------------------------------------------------------
    // 13G LIA-3.4: role assignment/revocation is NOT re-implemented here --
    // these two subcommands call the existing, running service's own
    // POST /role-bindings and POST /role-bindings/revoke, whose four-eyes
    // and SELF_GRANT_REFUSED behaviour (C3 §4.3) stays exactly as frozen.
    // No password is ever involved in either call.
    // ---------------------------------------------------------------

    private static void roleBindCreate(String[] args) {
        if (args.length != 8) {
            System.err.println("role-bind-create requires exactly 7 arguments; see usage.");
            printUsage();
            return;
        }
        String targetField = "LEGACY".equals(args[7]) ? "localIdentityId" : "selectionHandle";
        String body = "{\"roleToken\":\"" + jsonEscape(args[4]) + "\",\"" + targetField + "\":\"" + jsonEscape(args[5])
                + "\",\"directoryProfileId\":\"" + jsonEscape(args[6]) + "\",\"bindingKind\":\"" + jsonEscape(args[7]) + "\"}";
        postToRoleBindings(args[1], args[2], args[3], "/role-bindings", body);
    }

    private static void roleBindRevoke(String[] args) {
        if (args.length != 5) {
            System.err.println("role-bind-revoke requires exactly 4 arguments; see usage.");
            printUsage();
            return;
        }
        String body = "{\"bindingId\":\"" + jsonEscape(args[4]) + "\"}";
        postToRoleBindings(args[1], args[2], args[3], "/role-bindings/revoke", body);
    }

    private static void postToRoleBindings(String baseUrl, String sessionCookieValue, String csrfToken, String path,
            String jsonBody) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .header("X-CSRF-Token", csrfToken)
                    .header("Cookie", "ui2_session=" + sessionCookieValue)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("status=" + response.statusCode() + " body=" + response.body());
        } catch (java.io.IOException | InterruptedException e) {
            System.err.println("role binding request failed: " + e.getMessage());
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------------------------------------------------------
    // 14I OR-1..OR-5: the only path that ever decrypts a backup/
    // configuration artefact -- no HTTP route does (BackupNoHttpRetrievalRouteTest
    // greps service sources to hold that invariant). role:backup_admin and
    // an eight-character reason are checked inside BackupArtefactRetrievalPort
    // itself (com.securityexpert.nexus.ui2.persistence.artefact.
    // BackupArtefactRetrieval), not re-checked here.
    // ---------------------------------------------------------------

    private static void backupRetrieve(String[] args) {
        if (args.length != 11) {
            System.err.println("backup-retrieve requires exactly 10 arguments; see usage.");
            printUsage();
            return;
        }
        com.securityexpert.nexus.ui2.platform.BackupArtefactRetrievalPort port = BackupRetrievalFactory.create(args[1],
                args[2], args[3], args[4], args[5], args[6]);
        String artefactId = args[7];
        String destinationPath = args[8];
        String reason = args[9];
        String actingAdminActorFingerprint = args[10];
        var result = port.retrieve(actingAdminActorFingerprint, artefactId, destinationPath, reason);
        if (result instanceof com.securityexpert.nexus.ui2.platform.BackupArtefactRetrievalPort.RetrieveResult.Ok ok) {
            System.out.println("retrieved artefact " + artefactId + " to " + ok.destinationPath());
            System.out.println("WARNING: this file may contain secret-bearing lines. It is your responsibility "
                    + "from this point on -- the product does not copy it anywhere and does not retain this "
                    + "destination.");
        } else if (result instanceof com.securityexpert.nexus.ui2.platform.BackupArtefactRetrievalPort.RetrieveResult.ArtefactNotFound) {
            System.err.println("ARTEFACT_NOT_FOUND");
        } else if (result instanceof com.securityexpert.nexus.ui2.platform.BackupArtefactRetrievalPort.RetrieveResult.RoleRefused) {
            System.err.println("ROLE_REFUSED: role:backup_admin is required");
        } else if (result instanceof com.securityexpert.nexus.ui2.platform.BackupArtefactRetrievalPort.RetrieveResult.ReasonTooShort) {
            System.err.println("REASON_TOO_SHORT: a reason of at least eight characters is required");
        } else if (result instanceof com.securityexpert.nexus.ui2.platform.BackupArtefactRetrievalPort.RetrieveResult.AuditRefused) {
            System.err.println("AUDIT_REFUSED: retrieval audit could not be recorded");
        } else if (result instanceof com.securityexpert.nexus.ui2.platform.BackupArtefactRetrievalPort.RetrieveResult.IoFailure failure) {
            System.err.println("IO_FAILURE: " + failure.reason());
        }
    }
}
