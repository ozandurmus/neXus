package com.securityexpert.nexus.ui2.cli;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.bootstrap.LocalIdentityBootstrapFactory;
import com.securityexpert.nexus.ui2.jobs.bootstrap.SecurityAdminBootstrapFactory;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
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
            default -> System.out.println("known job states: " + java.util.Arrays.toString(JobState.values()));
        }
    }

    private static void printUsage() {
        System.out.println("usage: cli <command>");
        System.out.println("  bootstrap-security-admin <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<groupReferenceDn> <groupReferenceKeyBase64> <groupReferenceKeyId>");
        System.out.println("  bootstrap-local-identity <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<localIdentityName> <initialPasswordFile>");
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
}
