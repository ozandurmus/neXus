package com.securityexpert.nexus.ui2.jobs.policy;

import java.util.List;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Policy collection completeness (PO approval 2026-10-18, docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md;
 * plan docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md section 6): the closed set of six CLASS_0 read commands the
 * completeness readers may issue, each resolved against its SIGNED_OFF gate row (migration V163) before it is sent. A
 * DRAFTED, unsigned, ambiguous or missing row never authorizes transport.
 *
 * <p>The seventh approved command, C-3 (a smaller Check Point NAT page), is registered in V163 and is deliberately absent
 * here: it is conditional on Check Point lane evidence, and no collector may use it before then.</p>
 *
 * <p>Official documentation for each call (plan section 6, field 1):</p>
 * <ul>
 *   <li>P-1: <a href="https://docs.paloaltonetworks.com/pan-os/11-1/pan-os-admin/monitoring/view-policy-rule-usage.html">View
 *       Policy Rule Usage (PAN-OS 11.1)</a> states that hit counts exist for NAT rules; the security form of the call is
 *       <a href="https://pan.dev/panos/docs/tutorials/rule-hit-counts/">pan.dev, rule hit counts</a>.
 *       <b>UNKNOWN (plan section 8):</b> the exact XML element {@code rule-base/entry[@name='nat']} is derived by
 *       substituting the rule base into the documented security form; only one PO-approved measurement call confirms it.
 *       The NAT response size per VSYS is UNKNOWN too.</li>
 *   <li>P-2, P-3: <a href="https://docs.paloaltonetworks.com/pan-os/10-0/pan-os-panorama-api/pan-os-xml-api-request-types/retrieve-logs-api/api-log-retrieval-parameters">API
 *       Log Retrieval Parameters</a> ({@code log-type=config}, {@code nlogs} up to 5000, {@code dir=forward},
 *       {@code query}). <b>UNKNOWN:</b> the Panorama config-log field content for device-group changes (for example whether
 *       {@code path} names the device group); any API rate limit.</li>
 *   <li>P-4: <a href="https://docs.paloaltonetworks.com/ngfw/api/pan-os-xml-api-request-types-and-actions/retrieve-logs">Retrieve
 *       Logs</a> (the asynchronous {@code action=get&job-id=} flow).</li>
 *   <li>C-1, C-2: <a href="https://sc1.checkpoint.com/documents/latest/APIs/index.html#cli/show-changes~v1.9">Management
 *       API reference, show-changes</a> (v1.8 and v1.9) and its {@code show-task} section. <b>UNKNOWN:</b> whether
 *       {@code show-changes} opens a session or lock on the MDS (measure once); whether a modified rule's diff names its
 *       layer; the exact R81.10/R81.20 API version on the MDS.</li>
 * </ul>
 */
public final class PolicyCompletenessGates {
    private PolicyCompletenessGates() {}

    public static final String CAPABILITY_PAN = PanPolicyGates.CAPABILITY;
    public static final String CAPABILITY_CP = CpPolicyGates.CAPABILITY;

    /** One approved command: the canonical key parts and the sign-off terms the live row must still carry. */
    public record Command(String label, String gateId, String vendor, String platformRoleScope, String shellContext,
            String transportKind, String text, int timeoutS, String retryRule) {
        public CanonicalCommandKey key() {
            return new CanonicalCommandKey(vendor, platformRoleScope, shellContext, transportKind, text);
        }
    }

    /** P-1. {@code <VSYS>} is the stored VSYS context of a device-group target, substituted server-side. */
    public static final Command P1_NAT_HIT_COUNTS = new Command("P-1", "pan_policy_nat_rule_hit_count", "palo_alto",
            "pan_firewall", "not_applicable", "PAN_XML_API",
            "type=op&cmd=<show><rule-hit-count><vsys><vsys-name><entry name='<VSYS>'><rule-base><entry name='nat'><rules><all/></rules></entry></rule-base></entry></vsys-name></vsys></rule-hit-count></show>",
            60, "none");
    /** P-2: the same literal as the approved firewall row pan_syslog_collect_config_enqueue, a new key by scope only. */
    public static final Command P2_CONFIG_LOG_ENQUEUE = new Command("P-2", "pan_policy_config_log_enqueue", "palo_alto",
            "panorama", "not_applicable", "PAN_XML_API",
            "type=log&log-type=config&nlogs=<n>&dir=forward&query=(receive_time geq '<ts>')", 30, "none");
    public static final Command P3_CONFIG_LOG_SEED = new Command("P-3", "pan_policy_config_log_seed", "palo_alto",
            "panorama", "not_applicable", "PAN_XML_API", "type=log&log-type=config&nlogs=<n>", 30, "none");
    public static final Command P4_LOG_JOB_GET = new Command("P-4", "pan_policy_log_job_get", "palo_alto",
            "panorama", "not_applicable", "PAN_XML_API", "type=log&action=get&job-id=<id>", 30, "none");
    public static final Command C1_SHOW_CHANGES = new Command("C-1", "cp_policy_show_changes", "check_point",
            "cp_multi_domain_server", "expert", "SSH_EXEC",
            "mgmt_cli -r true -d '<DOMAIN>' -f json show-changes from-date '<FROM>' to-date '<TO>' limit 10 offset '<N>'",
            60, "none");
    public static final Command C2_SHOW_TASK = new Command("C-2", "cp_policy_show_task", "check_point",
            "cp_multi_domain_server", "expert", "SSH_EXEC",
            "mgmt_cli -r true -d '<DOMAIN>' -f json show-task task-id '<UID>' details-level full", 60, "none");

    public static final List<Command> COMMANDS = List.of(P1_NAT_HIT_COUNTS, P2_CONFIG_LOG_ENQUEUE, P3_CONFIG_LOG_SEED,
            P4_LOG_JOB_GET, C1_SHOW_CHANGES, C2_SHOW_TASK);

    // Parameter bounds of plan section 6 (fields 4 to 6).
    /** P-2/P-3 {@code <n>}: digits 1 to 5000. */
    public static final int PAN_LOG_PAGE_MAX = 5000;
    /** P-2/P-3: at most 8 MiB per page. */
    public static final int PAN_LOG_MAX_RESULT_CHARS = 8 * 1024 * 1024;
    /** P-2: at most 4 pages per Panorama per job window. */
    public static final int PAN_LOG_MAX_PAGES = 4;
    /** P-4: every 2 s until FIN, at most 30 polls per enqueued job. */
    public static final long PAN_LOG_POLL_SECONDS = 2;
    public static final int PAN_LOG_MAX_POLLS = 30;
    /** C-1: at most 20 offsets per run; {@code limit 10} sessions per call (the literal of the approved text). */
    public static final int CP_SHOW_CHANGES_MAX_OFFSETS = 20;
    public static final int CP_SHOW_CHANGES_LIMIT = 10;
    /** C-2: poll every 5 s until the task finishes, at most 24 polls per task. */
    public static final long CP_SHOW_TASK_POLL_SECONDS = 5;
    public static final int CP_SHOW_TASK_MAX_POLLS = 24;

    /** P-2 {@code <ts>}: the stored cursor, in the form the plan's field 6 allows. */
    public static final Pattern PAN_TIMESTAMP = Pattern.compile("[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}");
    /** P-4 {@code <id>}: digits only, from the enqueue answer. */
    public static final Pattern PAN_JOB_ID = Pattern.compile("[0-9]{1,20}");
    /** C-1 {@code <FROM>}/{@code <TO>}: an ISO 8601 publish time as the vendor printed it. */
    public static final Pattern CP_ISO_TIME = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,6})?)?(?:Z|[+-][0-9]{2}:?[0-9]{2})?");
    /** C-2 {@code <UID>}: the task id the C-1 answer returned. */
    public static final Pattern CP_TASK_ID = Pattern.compile("[0-9A-Za-z-]{8,64}");

    /** True only for exactly one live row with the approved sign-off, class, timeout and retry terms. */
    public static boolean enabled(GateRegistryPort registry, Command command) {
        var rows = registry.findByCanonicalKey(command.key());
        return rows.size() == 1 && command.gateId().equals(rows.get(0).gateId())
                && rows.get(0).signOffState() == SignOffState.SIGNED_OFF
                && rows.get(0).actionClass() == ActionClass.CLASS_0_READ && rows.get(0).timeoutS() == command.timeoutS()
                && command.retryRule().equals(rows.get(0).retryRule());
    }

    public static void require(GateRegistryPort registry, Command... commands) {
        for (Command command : commands)
            if (!enabled(registry, command)) throw new IllegalStateException("POLICY_GATE_UNAVAILABLE");
    }

    /** P-2, P-3 and P-4 together: the Panorama config-log reader needs all three. */
    public static boolean panConfigLogEnabled(GateRegistryPort registry) {
        return enabled(registry, P2_CONFIG_LOG_ENQUEUE) && enabled(registry, P3_CONFIG_LOG_SEED) && enabled(registry, P4_LOG_JOB_GET);
    }

    /** C-1 and C-2 together: the Check Point change reader needs both. */
    public static boolean cpShowChangesEnabled(GateRegistryPort registry) {
        return enabled(registry, C1_SHOW_CHANGES) && enabled(registry, C2_SHOW_TASK);
    }
}
