package com.securityexpert.nexus.ui2.worker.backup.radware;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The closed CLI set for a Radware Cyber Controller's own configuration backup
 * (RADWARE_CYBER_CONTROLLER_OWN_BACKUP_RECEIVER.md, V69). Prompts and success texts as measured on 10.13.0-3,
 * 2026-09-24. {@code %s} is the run's backup name, {@code nexus-<token>}; the export target is built by
 * {@link #exportTarget}.
 */
public final class CyberControllerBackupPlan {

    public static final String CREATE = "system backup config create %s";
    public static final String EXPORT = "system backup config export %s %s";
    public static final String LIST = "system backup config list";
    public static final String DELETE = "system backup config delete %s";
    public static final List<String> LITERALS = List.of(CREATE, EXPORT, LIST, DELETE);

    /** Measured prompts. */
    public static final String PASSWORD_PROMPT = "Password:";
    public static final String DELETE_CONFIRM_PROMPT = "(Y/N)?";
    /** Measured success texts. */
    public static final String EXPORT_DONE = "Export completed.";
    public static final String DELETE_DONE = "Remove completed.";
    /** The Cyber Controller appends {@code .tar} to the export target's name (measured). */
    public static final String EXPORT_SUFFIX = ".tar";

    private static final Pattern NAME = Pattern.compile("nexus-[0-9a-f]{16}");

    private CyberControllerBackupPlan() {
    }

    public static String name(String token) {
        String n = "nexus-" + token;
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("backup token must be 16 lowercase hex characters");
        }
        return n;
    }

    public static String with(String template, Object... args) {
        return String.format(template, args);
    }

    /** {@code sftp://<user>@<host>:/in/<token>.tgz} -- relative to the receiver's chroot. */
    public static String exportTarget(String receiverUser, String receiverHost, String token) {
        return "sftp://" + receiverUser + "@" + receiverHost + ":/in/" + token + ".tgz";
    }

    /** The {@code Size(K)} column of our backup's row in {@code system backup config list}, if present. */
    public static Optional<Long> listedSizeKb(String listing, String name) {
        if (listing == null) {
            return Optional.empty();
        }
        Pattern row = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\s+(\\d+)\\s");
        Matcher m = row.matcher(listing);
        return m.find() ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
    }
}
