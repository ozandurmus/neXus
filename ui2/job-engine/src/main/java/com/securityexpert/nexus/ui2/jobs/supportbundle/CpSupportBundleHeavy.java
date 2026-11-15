package com.securityexpert.nexus.ui2.jobs.supportbundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.platform.ActionClass;

import static com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundle.CP_ENV;

/**
 * Support bundle collection, Check Point heavy items (step S6; PO approval 2026-11-15: C8-C20 exactly as written in
 * SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 6, plus the two cpdata_collector literals approved later the same
 * day). Every literal carries the {@code CP_ENV} prefix (no login shell). File creating and removing commands are
 * recovery-write by the V61 precedent and BW-2: transient artefacts the run removes by exact name (PO 14K).
 *
 * <p>Each heavy item gets its own work directory {@code /var/log/nexus-sb-<12 hex>} (the token is a digest of the job id
 * and the item's ordinal, never operator text): C8 creates it, the tool starts detached and writes its exit code to a file,
 * C15 polls that file, C16 lists the directory, C17 bundles it, C18 digests the bundle on the device, C19 streams it into the
 * vault, C20 removes exactly that directory and bundle once the two digests match. The gate rows exist per platform scope:
 * the gateway ids as designed ({@code cp_sb_*}) and the management scope ({@code cpm_sb_*}, V234).</p>
 */
public final class CpSupportBundleHeavy {
    private CpSupportBundleHeavy() {}

    /** The work directory pattern V230 enforces on {@code support_bundle_request.work_dir_path}. */
    public static final Pattern WORK_DIR = Pattern.compile("/var/log/nexus-sb-[a-f0-9]{12}");
    /** A virtual system id: an integer 0..4095 (design 5.9), validated again against stored inventory by the executor. */
    public static final Pattern VSID = Pattern.compile("[0-9]{1,4}");
    /** A report name HCP wrote, as listed by C6 in the same run. */
    public static final Pattern HCP_REPORT = Pattern.compile("hcp_report_[A-Za-z0-9._-]{1,100}\\.tar\\.gz");

    public static final Duration HEAVY_DEADLINE = Duration.ofHours(2);
    public static final Duration POLL_INTERVAL = Duration.ofSeconds(30);
    /** The approved cpdata_collector run is bounded on the device by {@code timeout 1800}; its rc file is polled (PO 2026-11-15). */
    public static final Duration CPDATA_DEVICE_TIMEOUT = Duration.ofSeconds(1800);

    public static final long HCP_BUNDLE_MAX_BYTES = 16L * 1024 * 1024;
    public static final long CPINFO_MAX_BYTES = 20L * 1024 * 1024 * 1024;
    /** Design 5.5 states no cpdata_collector bound; the request aggregate cap applies and this is the item bound. */
    public static final long CPDATA_MAX_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long REQUEST_AGGREGATE_MAX_BYTES = 25L * 1024 * 1024 * 1024;
    public static final long HCP_MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long CPINFO_MIN_FREE_BYTES = 5L * 1024 * 1024 * 1024;
    public static final int MAX_CORE_PERCENT = 70;

    public static final String SFTP_GATE = "cp_sb_sftp_get_bundle";

    /** C8-C20 plus the two cpdata_collector rows, byte-identical to the V234 and V230 literals. */
    public enum Step {
        MKDIR("mkdir", CP_ENV + "mkdir -p %s", ActionClass.CLASS_1_RECOVERY_WRITE, 30, true),
        HCP_START("hcp_start", CP_ENV + "cd %1$s && setsid nohup bash --noprofile --norc -c \"hcp -r all --include-wts yes -s > %1$s/hcp.log 2>&1; "
                + "echo \\$? > %1$s/hcp.rc\" </dev/null >/dev/null 2>&1 &", ActionClass.CLASS_1_RECOVERY_WRITE, 60, true),
        HCP_START_VS("hcp_start_vs", CP_ENV + "cd %1$s && setsid nohup bash --noprofile --norc -c \"hcp -r all --include-wts yes -s -c <VSID> > %1$s/hcp.log 2>&1; "
                + "echo \\$? > %1$s/hcp.rc\" </dev/null >/dev/null 2>&1 &", ActionClass.CLASS_1_RECOVERY_WRITE, 60, false),
        COPY_HCP("copy_hcp", CP_ENV + "cp /var/log/hcp/last/<listed name> %s/", ActionClass.CLASS_1_RECOVERY_WRITE, 60, true),
        CPINFO_START("cpinfo_start", CP_ENV + "cd %1$s && setsid nohup bash --noprofile --norc -c \"cpinfo -z -D -d -S -o %1$s/cpinfo > %1$s/cpinfo.log 2>&1; "
                + "echo \\$? > %1$s/cpinfo.rc\" </dev/null >/dev/null 2>&1 &", ActionClass.CLASS_1_RECOVERY_WRITE, 60, true),
        CPINFO_START_SMS("cpinfo_start_sms", CP_ENV + "cd %1$s && setsid nohup bash --noprofile --norc -c \"cpinfo -z -D -d -S -x -o %1$s/cpinfo > %1$s/cpinfo.log 2>&1; "
                + "echo \\$? > %1$s/cpinfo.rc\" </dev/null >/dev/null 2>&1 &", ActionClass.CLASS_1_RECOVERY_WRITE, 60, true),
        CPINFO_START_VS("cpinfo_start_vs", CP_ENV + "cd %1$s && setsid nohup bash --noprofile --norc -c \"vsenv <VSID> >/dev/null 2>&1 && cpinfo -z -D -d -S -o %1$s/cpinfo > %1$s/cpinfo.log 2>&1; "
                + "echo \\$? > %1$s/cpinfo.rc\" </dev/null >/dev/null 2>&1 &", ActionClass.CLASS_1_RECOVERY_WRITE, 60, false),
        POLL_RC("poll_rc", CP_ENV + "cat %s/<hcp|cpinfo>.rc", ActionClass.CLASS_0_READ, 30, true),
        LIST_WORKDIR("list_workdir", CP_ENV + "ls -l %s", ActionClass.CLASS_0_READ, 30, true),
        BUNDLE("bundle", CP_ENV + "cd %1$s && tar -czf %1$s.tgz .", ActionClass.CLASS_1_RECOVERY_WRITE, 3600, true),
        SHA256("sha256", CP_ENV + "sha256sum %s.tgz", ActionClass.CLASS_0_READ, 600, true),
        REMOVE("remove_workdir", CP_ENV + "rm -rf %1$s %1$s.tgz", ActionClass.CLASS_1_RECOVERY_WRITE, 120, true),
        /** Approved 2026-11-15 after the S2 brief; the gate rows are V230 (gateway) and V234 (management). */
        CPDATA_LIST("cpdata_collector_list", CP_ENV + "cpdata_collector --list", ActionClass.CLASS_0_READ, 60, true),
        CPDATA_RUN("cpdata_collector_run", CP_ENV + "cd %1$s && setsid nohup bash --noprofile --norc -c \"timeout 1800 cpdata_collector --run -p %1$s/cpsdc.tgz -t 3 -m 100 "
                + "> %1$s/cpsdc.log 2>&1; echo \\$? > %1$s/cpsdc.rc\" </dev/null >/dev/null 2>&1 &", ActionClass.CLASS_1_RECOVERY_WRITE, 60, true),
        /** Approved by the Product Owner 2026-11-15 (about 22:30): {@code cat <workdir>/cpsdc.rc}, a read, every 30 s until the deadline; gate rows are V235. */
        POLL_CPSDC("poll_cpsdc", CP_ENV + "cat %s/cpsdc.rc", ActionClass.CLASS_0_READ, 30, true);

        private final String stem;
        private final String template;
        private final ActionClass actionClass;
        private final int timeoutS;
        private final boolean onManagement;

        Step(String stem, String template, ActionClass actionClass, int timeoutS, boolean onManagement) {
            this.stem = stem; this.template = template; this.actionClass = actionClass; this.timeoutS = timeoutS;
            this.onManagement = onManagement;
        }

        public String stem() { return stem; }
        /** The canonical literal with its placeholders ({@code %s}, {@code %1$s}, {@code <VSID>}, {@code <listed name>}). */
        public String template() { return template; }
        public ActionClass actionClass() { return actionClass; }
        public int timeoutS() { return timeoutS; }
        public boolean writes() { return actionClass != ActionClass.CLASS_0_READ; }
        /** C10 and C14 (a VS) exist for a gateway only; every other row exists for both scopes. */
        public boolean onScope(String scope) { return CpSupportBundle.MANAGEMENT_SCOPE.equals(scope) ? onManagement : true; }

        public String gateId(String scope) {
            return (CpSupportBundle.MANAGEMENT_SCOPE.equals(scope) ? "cpm_sb_" : "cp_sb_") + stem;
        }

        public CanonicalCommandKey key(String scope) {
            return new CanonicalCommandKey(CpSupportBundle.VENDOR, scope, CpSupportBundle.SHELL, "SSH_EXEC", template);
        }

        /** The live, signed-off gate row of this literal under {@code scope}; empty when missing, unsigned or of another class. */
        public Optional<GateResolution.Known> gate(String scope, GateRegistryPort registry) {
            if (!onScope(scope)) return Optional.empty();
            return SupportBundleGates.resolve(key(scope), gateId(scope), actionClass, registry);
        }

        public Duration timeout() { return Duration.ofSeconds(timeoutS); }
    }

    /** C19: the streaming SFTP get of the bundle. A read, secret-bearing. */
    public static final String SFTP_KEY = "sftp get of %s.tgz";
    public static final int SFTP_TIMEOUT_S = 7200;

    public static String sftpGateId(String scope) {
        return (CpSupportBundle.MANAGEMENT_SCOPE.equals(scope) ? "cpm_sb_" : "cp_sb_") + "sftp_get_bundle";
    }

    public static Optional<GateResolution.Known> sftpGate(String scope, GateRegistryPort registry) {
        return SupportBundleGates.resolve(new CanonicalCommandKey(CpSupportBundle.VENDOR, scope, "not_applicable", "SFTP_GET", SFTP_KEY),
                sftpGateId(scope), ActionClass.CLASS_0_READ, registry);
    }

    /** The read gates the presence, precheck and listing reads need under a scope (C0, C1, C2, C6). */
    public static Optional<GateResolution.Known> readGate(CpSupportBundle.Exec exec, String scope, GateRegistryPort registry) {
        return CpSupportBundle.gate(exec, registry, scope);
    }

    /**
     * Whether every gate a heavy run needs is signed off under {@code scope}: the work directory flow (C8, C11, C15-C20), the
     * start literal(s) the platform uses, the prechecks and the HCP listing. A run whose gates are not all known never starts.
     */
    public static boolean allGatesKnown(String scope, GateRegistryPort registry) {
        for (Step step : Step.values()) {
            if (!step.onScope(scope)) continue;
            if (step.gate(scope, registry).isEmpty()) return false;
        }
        for (CpSupportBundle.Exec exec : CpSupportBundle.Exec.values()) {
            boolean needed = exec == CpSupportBundle.Exec.CPDATA_PRESENT || exec == CpSupportBundle.Exec.CPU_MULTI
                    || exec == CpSupportBundle.Exec.DF_VAR_LOG || exec == CpSupportBundle.Exec.LS_HCP_LAST;
            if (needed && readGate(exec, scope, registry).isEmpty()) return false;
        }
        return sftpGate(scope, registry).isPresent();
    }

    /** The work directory of one item: {@code /var/log/nexus-sb-} and 12 hex characters of a digest of the job id and ordinal. */
    public static String workDir(String jobId, int ordinal) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((jobId + "/" + ordinal).getBytes(StandardCharsets.UTF_8));
            return "/var/log/nexus-sb-" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fills the placeholders of a template. Only validated values are ever substituted: no operator text reaches a command. */
    public static String render(Step step, String workDir, String vsId, String reportName) {
        if (!WORK_DIR.matcher(workDir).matches()) throw new IllegalArgumentException("work directory");
        String out = step.template().replace("%1$s", workDir).replace("%s", workDir);
        if (out.contains("<VSID>")) {
            if (vsId == null || !validVsId(vsId)) throw new IllegalArgumentException("vs id");
            out = out.replace("<VSID>", Integer.toString(Integer.parseInt(vsId)));
        }
        if (out.contains("<listed name>")) {
            if (reportName == null || !HCP_REPORT.matcher(reportName).matches()) throw new IllegalArgumentException("report name");
            out = out.replace("<listed name>", reportName);
        }
        return out;
    }

    /** C15 for a tool: {@code cat <dir>/hcp.rc} or {@code cat <dir>/cpinfo.rc}. */
    public static String renderPoll(String workDir, String tool) {
        if (!"hcp".equals(tool) && !"cpinfo".equals(tool)) throw new IllegalArgumentException("tool");
        return render(Step.POLL_RC, workDir, null, null).replace("<hcp|cpinfo>", tool);
    }

    /** The approved cpdata_collector rc read: {@code cat <dir>/cpsdc.rc}. */
    public static String renderPollCpsdc(String workDir) {
        return render(Step.POLL_CPSDC, workDir, null, null);
    }

    public static String renderBundlePath(String workDir) {
        if (!WORK_DIR.matcher(workDir).matches()) throw new IllegalArgumentException("work directory");
        return workDir + ".tgz";
    }

    public static boolean validVsId(String vsId) {
        if (vsId == null || !VSID.matcher(vsId).matches()) return false;
        int value = Integer.parseInt(vsId);
        return value >= 0 && value <= 4095;
    }
}
