package com.securityexpert.nexus.ui2.cli;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.bootstrap.SecurityAdminBootstrapFactory;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
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
 */
public final class CliEntryPoint {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        switch (args[0]) {
            case "bootstrap-security-admin" -> bootstrapSecurityAdmin(args);
            default -> System.out.println("known job states: " + java.util.Arrays.toString(JobState.values()));
        }
    }

    private static void printUsage() {
        System.out.println("usage: cli <command>");
        System.out.println("  bootstrap-security-admin <jdbcUrl> <migrateUser> <migratePasswordFile> "
                + "<groupReferenceDn> <groupReferenceKeyBase64> <groupReferenceKeyId>");
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
}
