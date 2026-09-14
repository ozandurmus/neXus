package com.securityexpert.nexus.ui2.worker.backup;

import java.util.List;

/**
 * The closed set of literal commands {@code cp_gateway_backup} may ever
 * send (14H section 5's seven literals), transcribed from {@code
 * docs/design/CP_BACKUP_COMMAND_GATE_ENTRIES.md} exactly -- the same
 * closed-set-of-literals pattern {@code worker.configuration.
 * ConfigurationReadPlan} already establishes.
 *
 * <p>Every Gaia command is issued as {@code clish -c "<command>"} from the
 * Expert landing shell (BK-2); {@link #CP_ARCHIVE_DIGEST_TEMPLATE} is the
 * one exception -- {@code sha256sum} is not a Gaia/Clish command, so it is
 * issued bare from Expert. {@link #CP_DELETE_BACKUP_TEMPLATE} and {@link
 * #CP_ARCHIVE_DIGEST_TEMPLATE} carry a literal {@code %s} placeholder for
 * the exact archive name entry 2's own submit output named -- never a
 * pattern, never a name derived from a listing (BK-7).</p>
 *
 * <p>This movement wires only the Clish primary form of every literal.
 * Entries 1 and 7's Expert fallback ({@code df -P /var/log}, {@code rm -f
 * -- <exact path>}) is gated and documented (CONFIRM-ON-HARDWARE, BK-7) but
 * not wired into the executor -- switching to it is a successor record's
 * decision once the pilot run shows the Clish form failing.</p>
 */
public final class BackupReadPlan {

    private BackupReadPlan() {
    }

    public static final String CP_SHOW_DISKSPACE = "clish -c \"show diskspace\"";
    public static final String CP_ADD_BACKUP_LOCAL = "clish -c \"add backup local\"";
    public static final String CP_SHOW_BACKUP_STATUS = "clish -c \"show backup status\"";
    /** Governed by entry 4 (14H section 5's closed table) but not issued by this movement's own executor flow -- see the gate doc's own entry-4 note. */
    public static final String CP_SHOW_BACKUPS = "clish -c \"show backups\"";
    public static final String CP_ARCHIVE_DIGEST_TEMPLATE = "sha256sum %s";
    public static final String CP_DELETE_BACKUP_TEMPLATE = "clish -c \"delete backup %s\"";

    /** 14H section 5's own order: free-space precondition, submit, poll (repeated), fetch, digest, delete. */
    public static final List<String> LITERALS = List.of(CP_SHOW_DISKSPACE, CP_ADD_BACKUP_LOCAL, CP_SHOW_BACKUP_STATUS,
            CP_SHOW_BACKUPS, CP_ARCHIVE_DIGEST_TEMPLATE, CP_DELETE_BACKUP_TEMPLATE);

    /** Entry 6: the device-side digest command for the exact archive name this run submitted. */
    public static String archiveDigestCommand(String archiveName) {
        return String.format(CP_ARCHIVE_DIGEST_TEMPLATE, archiveName);
    }

    /** Entry 7: deletion targets only the exact archive name this run created (BK-7). */
    public static String deleteBackupCommand(String archiveName) {
        return String.format(CP_DELETE_BACKUP_TEMPLATE, archiveName);
    }
}
