/**
 * The product's one presenter map (UI review 2026-10-18, Fable F-11): machine identifiers become plain words in one
 * place, and the raw value stays reachable -- on hover through `<Presented>` or as a muted suffix.
 *
 * Every map has a fallback that returns the raw value, never an invented word: an id this file does not know is shown
 * as it arrived, so a new capability or reason code is visible rather than silently mislabelled. `UNKNOWN` is never
 * rewritten (AGENTS.md "UNKNOWN / fail-closed law"): it stays the word UNKNOWN.
 *
 * Exported for the device screens too: `vendorLabel`, `jobTypeLabel`, `readinessReasonLabel`, `jobStateWord`,
 * `outcomeLine`, `presentRaw`, `completedReasonOutcome`, `actorLabel`.
 */
import { DISPLAY_TZ, formatDuration, formatStamp } from "./time";

/** A presented value: the plain label, and the raw identifier it came from (null when the raw value is the label). */
export interface PresentedValue {
  readonly label: string;
  readonly raw: string | null;
  /** True when the map knew the identifier; false means the label is the raw value itself. */
  readonly known: boolean;
}

/** Wraps a map lookup so a caller can show the label and keep the raw value for a tooltip or a muted suffix. */
export function presentRaw(raw: string | null | undefined, label: (raw: string) => string, absent = "UNKNOWN"): PresentedValue {
  const value = (raw ?? "").trim();
  if (!value) return { label: absent, raw: null, known: false };
  const mapped = label(value);
  return { label: mapped, raw: mapped === value ? null : value, known: mapped !== value };
}

// -------------------------------------------------------------------------------------------------------------------
// Vendors
// -------------------------------------------------------------------------------------------------------------------

const VENDOR_LABELS: Readonly<Record<string, string>> = {
  check_point: "Check Point",
  palo_alto: "Palo Alto",
  fortinet: "Fortinet",
  cisco_asa: "Cisco ASA",
  bluecoat: "Blue Coat",
  infoblox: "Infoblox",
  radware: "Radware",
  pulse_secure: "Pulse Secure",
};

/** The vendor's display name. An unknown id is returned as it came; an absent value is `UNKNOWN`. */
export function vendorLabel(id: string | null | undefined): string {
  const value = (id ?? "").trim();
  if (!value) return "UNKNOWN";
  return VENDOR_LABELS[value] ?? value;
}

// -------------------------------------------------------------------------------------------------------------------
// Job types (capability ids)
// -------------------------------------------------------------------------------------------------------------------

/** Job-type prefixes and the product name that goes in front of the object phrase. */
const JOB_PREFIX_VENDORS: ReadonlyArray<readonly [string, string]> = [
  ["cp_mds_", "Check Point MDS"], ["cp_", "Check Point"], ["pan_", "Palo Alto"], ["fgt_", "FortiGate"], ["fmg_", "FortiManager"],
  ["asa_", "Cisco ASA"], ["rdw_cc_", "Radware Cyber Controller"], ["rdw_", "Radware"], ["bcmc_", "Blue Coat"], ["proxysg_", "Blue Coat"],
  ["ib_", "Infoblox"], ["pulse_", "Pulse Secure"],
];

/** The vendor named by a `device_confirm_<x>` capability, and the `vendorHint` ids that arrive from the device list. */
const CONFIRM_VENDORS: Readonly<Record<string, string>> = {
  check_point: "Check Point", palo_alto: "Palo Alto", fortigate: "FortiGate", cisco_asa: "Cisco ASA",
};

/** Whole-id labels where the object phrase is not derivable from the suffix. */
const JOB_TYPE_LABELS: Readonly<Record<string, string>> = {
  cp_failover_readiness: "Check Point readiness check",
  pan_failover_readiness: "Palo Alto readiness check",
  pan_failover_config_sync: "Palo Alto HA configuration sync",
  cp_failover_start: "Check Point failover",
  pan_failover_start: "Palo Alto failover",
  cp_system_log_collect: "Check Point log collection",
  pan_system_log_collect: "Palo Alto log collection",
  cp_spike_layout_review: "Check Point Spike Detective layout review",
  cp_syslog_measure: "Check Point log measurement",
  pan_syslog_measure: "Palo Alto log measurement",
  cp_gaia_snapshot: "Check Point Gaia snapshot",
  cp_mds_export: "Check Point MDS export",
  cp_gateway_backup: "Check Point backup",
  cp_spark_sftp_backup: "Check Point Spark backup",
  cp_backup: "Check Point backup",
  cp_diagnostic_cpview_measure: "Check Point diagnostic measurement",
  cp_policy_collect: "Check Point policy collection",
  pan_policy_collect: "Palo Alto policy collection",
  pan_policy_rule_hit_count: "Palo Alto rule hit-count read",
  pan_policy_nat_rule_hit_count: "Palo Alto NAT rule hit-count read",
  cp_scheduled_policy_request: "Check Point scheduled policy request",
  pan_device_state_backup: "Palo Alto backup",
  pan_set_config_read: "Palo Alto backup (set-format read)",
  pan_config_backup: "Palo Alto backup",
  fgt_config_backup: "FortiGate backup",
  fgt_gateway_backup: "FortiGate backup",
  asa_config_backup: "Cisco ASA backup",
  rdw_cc_config_backup: "Radware Cyber Controller backup",
  rdw_cc_backup_create: "Radware Cyber Controller backup creation",
  rdw_cc_backup_export: "Radware Cyber Controller backup export",
  rdw_cc_backup_delete: "Radware Cyber Controller backup removal",
  mds_export: "Check Point MDS export",
  fmg_diagnostic_run: "FortiManager diagnostic run",
  fmg_diagnostic_read: "FortiManager diagnostic read",
  https_inventory_collect: "HTTPS inventory read",
  https_vendor_backup: "HTTPS vendor backup",
  packet_analysis_run: "Packet analysis",
  packet_analysis_analyze: "Packet analysis",
  data_sanitize_run: "Data sanitizer run",
  data_sanitize_revert: "Data sanitizer revert",
  data_sanitize_prune: "Data sanitizer prune",
  data_sanitize_info: "Data sanitizer info",
  policy_offline_analysis: "Policy offline analysis",
  preflight_readiness: "Readiness pre-check",
};

/** Object phrases for the `<vendor>_<suffix>` families. */
const JOB_SUFFIX_PHRASES: ReadonlyArray<readonly [string, string]> = [
  ["_inventory_collect", "inventory read"],
  ["_configuration_collect", "configuration read"],
  ["_discovery_enumerate", "discovery"],
  ["_failover_readiness", "readiness check"],
  ["_system_log_collect", "log collection"],
  ["_policy_collect", "policy collection"],
  ["_config_backup", "backup"],
  ["_gateway_backup", "backup"],
  ["_device_state_backup", "backup"],
  ["_snapshot", "backup"],
  ["_export", "backup"],
];

/**
 * The plain label of a worker capability id (`pan_failover_readiness` becomes "Palo Alto readiness check"). An id the
 * map cannot name is returned unchanged. `vendorHint` names the vendor for the vendor-neutral `device_confirm_https`
 * and `*_diagnostic` ids, exactly as the device list reports it.
 */
export function jobTypeLabel(capabilityId: string, vendorHint?: string): string {
  const id = (capabilityId ?? "").trim();
  if (!id) return "UNKNOWN";
  const whole = JOB_TYPE_LABELS[id];
  if (whole) return whole;
  const hinted = vendorHint ? (VENDOR_LABELS[vendorHint] ?? undefined) : undefined;
  if (id.startsWith("device_confirm_")) {
    const named = CONFIRM_VENDORS[id.slice("device_confirm_".length)] ?? hinted;
    return named ? `${named} identity check` : "Identity check";
  }
  const prefixVendor = JOB_PREFIX_VENDORS.find(([prefix]) => id.startsWith(prefix))?.[1];
  const phrase = JOB_SUFFIX_PHRASES.find(([suffix]) => id.endsWith(suffix))?.[1]
    ?? (id.includes("diagnostic") ? "diagnostic read" : undefined);
  if (phrase) {
    const vendor = prefixVendor ?? hinted;
    return vendor ? `${vendor} ${phrase}` : phrase.charAt(0).toUpperCase() + phrase.slice(1);
  }
  return id;
}

// -------------------------------------------------------------------------------------------------------------------
// Stored artefact classes (Administration > System)
// -------------------------------------------------------------------------------------------------------------------

const ARTEFACT_CLASS_LABELS: Readonly<Record<string, string>> = {
  backup: "Backup", configuration: "Configuration", system_log: "System log", snapshot: "Snapshot",
};

/** A stored artefact class in words (`system_log` becomes "System log"); an unknown class is returned as it came. */
export function artefactClassLabel(id: string | null | undefined): string {
  const value = (id ?? "").trim();
  if (!value) return "UNKNOWN";
  return ARTEFACT_CLASS_LABELS[value] ?? value;
}

// -------------------------------------------------------------------------------------------------------------------
// Readiness reasons and other machine reason codes
// -------------------------------------------------------------------------------------------------------------------

const REASON_LABELS: Readonly<Record<string, string>> = {
  CHECK_NOT_READY: "Pre-check failed",
  INSUFFICIENT_EVIDENCE: "Insufficient evidence",
  COMMAND_UNAVAILABLE: "Command not available on device (login environment)",
  TRUSTED_CONNECTION_REQUIRED: "Trusted connection required",
  TRUSTED_HOST_KEY_REQUIRED: "Trusted SSH host key required",
  READINESS_NOT_READY: "Readiness check found blockers",
  UNSUPPORTED_MODE: "Cluster mode not supported",
  UNSUPPORTED_HA_MODE: "HA mode not supported",
  IDENTITY_NOT_RECORDED: "Device identity not recorded",
  UNSUPPORTED: "Not supported",
  OPEN_INCIDENT: "Open failover incident",
  OUTSIDE_JOB_WINDOW: "Outside the job window",
  BUSINESS_HOURS_CONFIRM_REQUIRED: "Not started: business hours were not confirmed",
  INVENTORY_REQUIRED: "Inventory read required",
  CLUSTER_STATE_NOT_READY: "Cluster state not ready",
  CLOCK_UNTRUSTED: "Device clock not trusted",
  NOT_EVALUATED: "Not evaluated",
  COLLECTION_FAILED: "Collection failed",
  RELATIONSHIP_INCONSISTENT: "Relationship inconsistent",
  PREFLIGHT_BLOCKING_CONDITIONS_PRESENT: "Pre-flight found blocking conditions",
  VSX_CONTEXT_INCOMPLETE: "Virtual system context incomplete",
  VSX_CHASSIS_COMMANDS_NOT_APPROVED: "VSX chassis reads await command approval",
  VSX_CHASSIS_FAILOVER_NOT_APPROVED: "VSX chassis failover is not enabled",
  VS_RUN_ACTIVE: "A virtual system failover is open on this cluster",
  CHASSIS_RUN_ACTIVE: "A chassis run is open on this cluster",
  WRONG_ROLE: "Member holds the wrong role",
};

/** `READ_SIZE_BELOW_OFFSET` as plain words, `read size below offset`; only for codes a prefix already names. */
function codeWords(code: string): string {
  return code.trim().replace(/_/g, " ").toLowerCase();
}

/**
 * A readiness or refusal reason code in words; a sentence the server already wrote, or an unknown code, passes through.
 * A `partial:<CODE>` outcome of a log collection (one source failed, the others were stored) reads `Partial — <code words>`;
 * the raw code stays on hover through `<Presented>`.
 */
export function readinessReasonLabel(code: string | null | undefined): string {
  const value = (code ?? "").trim();
  if (!value) return "UNKNOWN";
  const partial = /^partial:([A-Z0-9_]+)$/.exec(value);
  if (partial) return `Partial \u2014 ${codeWords(partial[1])}`;
  return REASON_LABELS[value] ?? value;
}

/**
 * The terminal reasons that are really the outcome word of a completed job: a readiness check records READY / NOT_READY /
 * UNKNOWN there (the worker writes it as the terminal reason, not the outcome column), a log collection records that it
 * collected. Anything else on a completed job (`COMPLETED`, `completed in 8038ms`) says nothing and stays hidden.
 */
const COMPLETED_REASON_OUTCOMES: Readonly<Record<string, string>> = {
  READY: "Ready", NOT_READY: "Not ready", UNKNOWN: "UNKNOWN", PASS: "Pass", FAIL: "Fail",
  SYSTEM_LOG_COLLECTED: "Collected", SYSTEM_LOG_UNSUPPORTED_PLATFORM: "Platform not supported", OUTPUT_RECORDED: "Output recorded",
};

/** The outcome word a completed job's terminal reason carries, or null when the reason only repeats the state. */
export function completedReasonOutcome(reason: string | null | undefined): string | null {
  const value = (reason ?? "").trim();
  if (!value) return null;
  return COMPLETED_REASON_OUTCOMES[value.toUpperCase()] ?? null;
}

// -------------------------------------------------------------------------------------------------------------------
// Actors
// -------------------------------------------------------------------------------------------------------------------

/**
 * An actor token in words. A service actor (`system:cp-failover-worker`, `system:scheduler`) reads `service · neXus
 * <name>`; a masked session actor (the `SN-` pseudonym the masked view gives every user) reads `Masked actor` with the
 * stable token kept beside it, so two rows by the same actor still compare. A plain user name passes through; nothing
 * here can unmask anybody -- the raw value is only what the server already sent.
 */
export function actorLabel(actor: string | null | undefined): PresentedValue {
  const value = (actor ?? "").trim();
  if (!value) return { label: "UNKNOWN", raw: null, known: false };
  const service = /^system:([a-z0-9-]+)$/i.exec(value);
  if (service) return { label: `service \u00b7 neXus ${service[1].replace(/-/g, " ")}`, raw: value, known: true };
  if (/^SN-[0-9A-F]{10}$/.test(value)) return { label: "Masked actor", raw: value, known: true };
  return { label: value, raw: null, known: false };
}

// -------------------------------------------------------------------------------------------------------------------
// Status words
// -------------------------------------------------------------------------------------------------------------------

/**
 * Tokens that keep their upper case: `UNKNOWN` is written UNKNOWN (fail-closed law), HA roles are identity and stay as
 * the device reported them (`ACTIVE`, `STANDBY`, `PRIMARY`, `PASSIVE`, `MASTER`, `BACKUP`), `DIFF` is a finding marker.
 */
const KEEP_UPPER: ReadonlySet<string> = new Set(["UNKNOWN", "ACTIVE", "STANDBY", "PRIMARY", "PASSIVE", "MASTER", "BACKUP", "DIFF", "N/A", "OK"]);

/** Whole-token spellings a plain sentence-case rule would get wrong. */
const STATUS_WORDS: Readonly<Record<string, string>> = {
  NOT_EVALUATED: "Not evaluated", NOT_READY: "Not ready", NOT_APPLICABLE: "Not applicable", NOT_ASSESSED: "Not assessed",
  NOT_EVIDENCED: "Not evidenced", NOT_COLLECTED: "Not collected", NOT_CONFIRMED: "Not confirmed", NOT_READ: "Not read yet",
  NOT_ENROLLED: "Not enrolled", NOT_SUPPORTED: "Not supported", PENDING_CONFIRMATION: "Pending confirmation",
  IN_PROGRESS: "In progress", OUTCOME_UNKNOWN: "Outcome UNKNOWN", INSUFFICIENT_EVIDENCE: "Insufficient evidence",
  COLLECTION_FAILED: "Collection failed", NON_COMPLIANT: "Non-compliant", COMPLIANT: "Compliant",
};

/**
 * The sentence-case rule for an upper-case status token (design language 2026-10-19, label map): `COMPLETED` becomes
 * `Completed`, `ENROLLED` becomes `Enrolled`, `NOT EVALUATED` and `NOT_EVALUATED` become `Not evaluated`. `UNKNOWN`
 * stays UNKNOWN and an HA role stays upper case; a value that is not an upper-case token (a sentence the server wrote,
 * a hostname) passes through unchanged; an absent value is UNKNOWN.
 */
export function statusWord(raw: string | null | undefined): string {
  const value = (raw ?? "").trim();
  if (!value) return "UNKNOWN";
  if (KEEP_UPPER.has(value)) return value;
  if (!/^[A-Z]+(?:[ _][A-Z]+)*$/.test(value)) return value; // not an upper-case status token (a sentence, a hostname, a code with digits)
  const token = value.replace(/\s+/g, "_");
  const known = STATUS_WORDS[token] ?? STATE_WORDS[token] ?? OUTCOME_WORDS[token];
  if (known) return known;
  const words = token.toLowerCase().split("_").filter(Boolean);
  if (words.length === 0) return value;
  return words[0].charAt(0).toUpperCase() + words[0].slice(1) + (words.length > 1 ? ` ${words.slice(1).join(" ")}` : "");
}

/** Cluster mode as the inventory and failover reads record it, in plain words; the raw value belongs on hover. */
const CLUSTER_MODE_WORDS: Readonly<Record<string, string>> = {
  HA: "High availability", HIGH_AVAILABILITY: "High availability", LOAD_SHARING: "Load sharing", VSLS: "Virtual system load sharing",
  ACTIVE_PASSIVE: "Active/passive", ACTIVE_ACTIVE: "Active/active", VRRP: "VRRP",
};

/**
 * A cluster mode in plain words (`HA` becomes "High availability", `active-passive` becomes "Active/passive"). A mode
 * this map does not know passes through as it came (the server may already have written a sentence); an absent mode is
 * UNKNOWN, never a guess.
 */
export function clusterModeWord(raw: string | null | undefined): string {
  const value = (raw ?? "").trim();
  if (!value) return "UNKNOWN";
  if (value === "UNKNOWN") return value;
  if (!/^[A-Za-z]+(?:[_-][A-Za-z]+)*$/.test(value)) return value; // already a sentence (spaces)
  return CLUSTER_MODE_WORDS[value.toUpperCase().replace(/-/g, "_")] ?? value;
}

const STATE_WORDS: Readonly<Record<string, string>> = {
  COMPLETED: "Completed", SUCCEEDED: "Completed", FAILED: "Failed", CANCELLED: "Cancelled", REJECTED: "Rejected",
  REQUESTED: "Queued", CLAIMED: "Claimed", EXECUTING: "Running", OUTCOME_UNKNOWN: "Outcome UNKNOWN", RECONCILED: "Outcome UNKNOWN",
  SUBMITTED: "Submitted",
};

/** A job state in one word (`COMPLETED` becomes "Completed"); an unknown state is returned as it came. */
export function jobStateWord(state: string | null | undefined): string {
  const value = (state ?? "").trim();
  if (!value) return "UNKNOWN";
  return STATE_WORDS[value.toUpperCase()] ?? value;
}

/** Job outcome words as the worker records them (readiness, log collection): the word the operator reads. */
const OUTCOME_WORDS: Readonly<Record<string, string>> = {
  READY: "Ready", NOT_READY: "Not ready", PASS: "Pass", FAIL: "Fail", PARTIAL: "Partial", SUCCESS: "Completed",
  SUCCEEDED: "Completed", COMPLETED: "Completed", FAILURE: "Failed", FAILED: "Failed", UNKNOWN: "UNKNOWN", NOT_EVALUATED: "Not evaluated",
};

/**
 * The outcome a job record carries, in one word (`NOT_READY` becomes "Not ready"); an unknown outcome is returned as it
 * came, an absent one is null -- the caller decides what an empty cell says, this never invents a word.
 */
export function jobOutcomeWord(outcome: string | null | undefined): string | null {
  const value = (outcome ?? "").trim();
  if (!value) return null;
  return OUTCOME_WORDS[value.toUpperCase()] ?? value;
}

/** True when the outcome merely repeats the terminal state (SUCCESS on COMPLETED, FAILURE on FAILED) and says nothing new. */
export function outcomeEchoesState(outcome: string | null | undefined, state: string | null | undefined): boolean {
  const word = jobOutcomeWord(outcome);
  return word !== null && word === jobStateWord(state);
}

const ZONED_DAY = new Intl.DateTimeFormat("sv-SE", { timeZone: DISPLAY_TZ, year: "numeric", month: "2-digit", day: "2-digit" });
const ZONED_CLOCK = new Intl.DateTimeFormat("sv-SE", { timeZone: DISPLAY_TZ, hour: "2-digit", minute: "2-digit", hour12: false });

/** `06:01` in display time. */
export function formatClock(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : ZONED_CLOCK.format(d);
}

/**
 * One outcome line, `Completed · 8.0 s · 06:01`. The clock time is replaced by the full stamp when the run is not from
 * today, so an old run is never mistaken for a recent one. Parts that are not recorded are left out, never shown as 0.
 */
export function outcomeLine(run: {
  readonly state: string | null | undefined; readonly durationMs?: number | null; readonly at?: string | null;
}, now: Date = new Date()): string {
  const parts = [jobStateWord(run.state)];
  if (run.durationMs !== null && run.durationMs !== undefined && !Number.isNaN(run.durationMs)) parts.push(formatDuration(run.durationMs));
  if (run.at) {
    const when = new Date(run.at);
    if (!Number.isNaN(when.getTime())) {
      parts.push(ZONED_DAY.format(when) === ZONED_DAY.format(now) ? (formatClock(run.at) ?? "") : formatStamp(run.at));
    }
  }
  return parts.filter(Boolean).join(" · ");
}

/** A refused API request as text: the server's `code`, else its `error`, with the HTTP status: `CODE (HTTP 403)`; the code alone without a status; "Request failed (HTTP 500)" without a code. */
export function requestFailure(failure: { status?: number; body?: { code?: string; error?: string }; message?: string } | null | undefined): string {
  const code = failure?.body?.code ?? failure?.body?.error;
  const status = typeof failure?.status === "number" && failure.status > 0 ? `HTTP ${failure.status}` : null;
  if (code) return status ? `${code} (${status})` : code;
  if (status) return `Request failed (${status})`;
  return failure instanceof Error && failure.message ? failure.message : "Request failed";
}
