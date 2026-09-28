package com.securityexpert.nexus.ui2.worker.backup.cp;

import java.util.List;

/** The two approved Gaia Embedded clish forms for a push backup. */
public final class QuantumSparkBackupPlan {
    public static final String LOG = "show backup-settings-log";
    public static final String PUSH = "backup settings to sftp server %s filename %s file-encryption on password %s "
            + "backup-policy on username nexus-spark password %s";
    public static final List<String> LITERALS = List.of(LOG, PUSH);

    private QuantumSparkBackupPlan() {
    }

    public static String command(String receiverHost, String token, String encryptionPassword, char[] receiverPassword) {
        if (receiverHost == null || !receiverHost.matches("[A-Za-z0-9.-]+")
                || token == null || !token.matches("[0-9a-f]{16}")
                || encryptionPassword == null || !encryptionPassword.matches("[A-Za-z0-9]{24}")
                || receiverPassword == null || receiverPassword.length == 0
                || !new String(receiverPassword).matches("[A-Za-z0-9._@!%+=:-]+")) {
            throw new IllegalArgumentException("invalid Spark receiver command parameter");
        }
        return String.format(PUSH, receiverHost, token, encryptionPassword, new String(receiverPassword));
    }
}
