/**
 * The product's one presenter map (UI review 2026-10-18, Fable F-11): machine identifiers become plain words in one
 * place, and the raw value stays reachable -- on hover through `<Presented>` or as a muted suffix.
 *
 * Every map has a fallback that returns the raw value, never an invented word: an id this file does not know is shown
 * as it arrived, so a new capability or reason code is visible rather than silently mislabelled. `UNKNOWN` is never
 * rewritten (AGENTS.md "UNKNOWN / fail-closed law"): it stays the word UNKNOWN.
 *
 * Exported for the device screens too: `vendorLabel`, `jobTypeLabel`, `readinessReasonLabel`, `jobStateWord`,
 * `outcomeLine`, `presentRaw`.
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
  cp_failover_start: "Check Point failover",
  pan_failover_start: "Palo Alto failover",
  cp_system_log_collect: "Check Point log collection",
  pan_system_log_collect: "Palo Alto log collection",
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
  INVENTORY_REQUIRED: "Inventory read required",
  CLUSTER_STATE_NOT_READY: "Cluster state not ready",
  CLOCK_UNTRUSTED: "Device clock not trusted",
  NOT_EVALUATED: "Not evaluated",
  COLLECTION_FAILED: "Collection failed",
  RELATIONSHIP_INCONSISTENT: "Relationship inconsistent",
  PREFLIGHT_BLOCKING_CONDITIONS_PRESENT: "Pre-flight found blocking conditions",
  VSX_CONTEXT_INCOMPLETE: "Virtual system context incomplete",
  WRONG_ROLE: "Member holds the wrong role",
};

/** A readiness or refusal reason code in words; a sentence the server already wrote, or an unknown code, passes through. */
export function readinessReasonLabel(code: string | null | undefined): string {
  const value = (code ?? "").trim();
  if (!value) return "UNKNOWN";
  return REASON_LABELS[value] ?? value;
}

// -------------------------------------------------------------------------------------------------------------------
// Status words
// -------------------------------------------------------------------------------------------------------------------

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
