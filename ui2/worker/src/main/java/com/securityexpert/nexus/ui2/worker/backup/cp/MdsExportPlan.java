package com.securityexpert.nexus.ui2.worker.backup.cp;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The Multi-Domain Server export's closed command set (V61 gate rows, VENDOR_BACKUP_CONTRACTS_2026_09_22.md §7 as
 * amended 2026-09-23). {@code %s} is always the run's own work directory, {@code /var/log/nexus-mds-<token>}, where
 * the token is the job id reduced to {@code [a-f0-9-]} -- never operator text. mds_backup backs up the whole server
 * and every domain in one run (R81.20 CLI reference); it runs in the background and writes its exit code to a file
 * the run polls, so no SSH command is held open for the backup's duration (14H "the asynchronous truth").
 */
public final class MdsExportPlan {

    public static final String DF_VAR_LOG = "df -P /var/log";
    public static final String SHOW_VERSION_ALL = "clish -c 'show version all'";
    public static final String MKDIR = "mkdir -p %s";
    public static final String MDSSTAT = "bash -lc 'mdsstat' > %s/mdsstat.txt 2>&1";
    public static final String GAIA_CONFIGURATION = "clish -c 'show configuration' > %s/gaia_config.txt";
    public static final String LICENSES = "bash -lc 'cplic print -x' > %s/cplic.txt 2>&1";
    public static final String ROUTES = "netstat -rn > %s/netstat.txt";
    public static final String UNAME = "uname -a > %s/uname.txt";
    /** Started in the background from /var/log (outside the product tree, as the vendor requires); exit code to a file. */
    /** Detached from the SSH channel (setsid, stdin from /dev/null): measured 2026-09-24, without it the exec
     * channel stayed open on the background process's stdin and the start "timed out" while mds_backup ran. */
    public static final String MDS_BACKUP_START = "cd /var/log && setsid nohup bash -lc '$CPMDIR/scripts/mds_backup -b -l -d %1$s "
            + "> %1$s/mds_backup.log 2>&1; echo $? > %1$s/mds_backup.rc' </dev/null >/dev/null 2>&1 &";
    public static final String MDS_BACKUP_POLL = "cat %s/mds_backup.rc";
    public static final String LIST = "ls %s";
    public static final String BUNDLE = "cd %1$s && tar -czf %1$s.tgz .";
    public static final String DIGEST = "sha256sum %s.tgz";
    public static final String REMOVE = "rm -rf %1$s %1$s.tgz";

    public static final List<String> LITERALS = List.of(SHOW_VERSION_ALL, DF_VAR_LOG, MKDIR, MDSSTAT, GAIA_CONFIGURATION, LICENSES, ROUTES, UNAME,
            MDS_BACKUP_START, MDS_BACKUP_POLL, LIST, BUNDLE, DIGEST, REMOVE);

    private static final Pattern NOT_TOKEN = Pattern.compile("[^a-f0-9-]");

    private MdsExportPlan() {
    }

    /** The run's work directory: fixed prefix, token from the job id only. */
    public static String workDir(String jobId) {
        String token = NOT_TOKEN.matcher(jobId == null ? "" : jobId.toLowerCase(java.util.Locale.ROOT)).replaceAll("");
        if (token.length() < 8) {
            throw new IllegalArgumentException("job id too short for a work directory token");
        }
        return "/var/log/nexus-mds-" + token.substring(0, Math.min(36, token.length()));
    }

    public static String with(String template, String workDir) {
        return String.format(template, workDir);
    }
}
