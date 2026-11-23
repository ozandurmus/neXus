/**
 * Lifecycle API client (catalog, fleet, advisories, catalog sources and uploads) and the software-history reads that the
 * lifecycle and software screens share. Split out of adminApi.ts unchanged; every existing importer still reaches these
 * names through `./adminApi` (re-exported there).
 */
import { apiCall } from "./adminApi";

export interface LifecycleCatalogEntry {
  catalog_id: string;
  vendor: string;
  kind: "HARDWARE" | "SOFTWARE";
  product: string;
  end_of_sale: string | null;
  end_of_support: string | null;
  end_of_engineering: string | null;
  /** IMPORT or MANUAL for an operator row; a vendor source key (cisco_eol, paloalto_eol, checkpoint_lifecycle) for a vendor entry. */
  source: string;
  note: string | null;
  imported_by: string;
  imported_at: string;
  /** Vendor entries only (LIFECYCLE_CATALOG_SOURCES_CONTRACT.md): how it got in, its age and where it was read. */
  origin?: "OPERATOR_ENTRY" | "FEED" | "OPERATOR_PAGE";
  age_days?: number | null;
  month_only?: string[];
  document_ref?: string;
  source_url?: string;
  end_of_security_support?: string | null;
  end_of_extended_support?: string | null;
}
export type LifecycleCatalogInput = Pick<LifecycleCatalogEntry, "vendor" | "kind" | "product" | "end_of_sale" | "end_of_support" | "end_of_engineering" | "note">;
export interface LifecycleMatch {
  /** NOT_EVALUATED: nothing current to compare with (no source, or the source is too old). NEEDS_REVIEW: the vendor entry could not be read safely. */
  status: "MATCHED" | "NO_LIFECYCLE_DATA" | "NOT_EVALUATED" | "NEEDS_REVIEW";
  reason: string | null;
  /** Stable word for the reason (MODEL_MISSING, VERSION_FORM_UNRECOGNISED, VIRTUAL_PLATFORM, NO_CATALOG_MATCH, NO_SOURCE_LOADED ...); null when MATCHED. */
  reason_code?: string | null;
  basis: string | null;
  catalog: LifecycleCatalogEntry | null;
  key?: string | null;
}
export interface LifecycleLicense {
  name?: string;
  kind?: string;
  member?: string;
  label: string;
  expiry?: string | null;
  status: string;
  days_remaining?: number | null;
  /** How many earlier entries of the same label this row stands for (a device lists expired entries beside the current one). */
  earlier_entries?: number;
  /** Stored items only (the Infoblox grid summary rows carry none of these): LICENSE, SUBSCRIPTION, SUPPORT_CONTRACT, SMART_LICENSING. */
  category?: string;
  evidence?: "DIRECT_DEVICE" | "MANAGER_REPORTED";
  detail?: string | null;
  source?: string;
  observed_at?: string | null;
}
export interface LifecycleLicenseRead { kind: string; outcome: string; reason: string | null; item_count: number; observed_at: string | null; }
export interface LifecycleLivePatchItem {
  patch: string; status: string; mode: string | null; running: number | null; total: number | null;
  installed_at: string | null; refs: string[];
  /** A ready patch with no running process of its kind: nothing to patch on this device. */
  idle?: boolean;
}
export interface LifecycleLivePatch {
  package_take: string | null; jumbo: string | null; read_outcome: string | null; observed_at: string | null;
  /** ready: installed and its process runs, not yet applied. idle: installed, no process of that kind runs on the device. */
  armed: number; ready: number; idle?: number; jumbofix: number; other: number; items: LifecycleLivePatchItem[];
  /** The patch list was asked for, the device gave none, and it names no Live Patch package (never true for a Spark). */
  missing?: boolean;
}
export interface LifecycleDevice {
  device_id: string;
  hostname: string | null;
  cluster_member_ref: string | null;
  vendor: string;
  model: string | null;
  software_version: string | null;
  hardware: LifecycleMatch;
  software: LifecycleMatch;
  status: string;
  /** Read-only: why the device is not MATCHED (each unmatched part and its reason); null when matched or from an older backend. */
  reason?: string | null;
  reason_code?: string | null;
  risk: "EXPIRED" | "HIGH" | "MEDIUM" | "LOW" | "UNKNOWN" | "NOT_EVALUATED";
  support_days: number | null;
  next_milestone: { name: string; date: string; days_remaining: number; precision?: "DAY" | "MONTH" } | null;
  nearest_license: LifecycleLicense | null;
  licenses: LifecycleLicense[];
  license_status: string;
  support_contract_status: string;
  license_reads?: LifecycleLicenseRead[];
  license_problem?: boolean;
  license_problems?: { label: string; status: string }[];
  live_patch?: LifecycleLivePatch | null;
  /** The vulnerability check of ADVISORY_MATCHING_CONTRACT.md section 7; absent on a backend that predates it. */
  vulnerabilities?: LifecycleVulnerabilities | null;
}
export type AdvisorySeverity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "NONE" | "UNKNOWN";
/** What a device was compared against: "nothing matched" is only ever said together with this (contract rule 4). */
export interface LifecycleVulnerabilityBasis {
  source: string; advisories: number; oldest: string | null; newest: string | null; loaded_at: string | null; age_days: number | null;
}
export interface LifecycleVulnerabilities {
  /** Only EVALUATED is a judgement; the other three states say why the device was not judged. */
  state: "EVALUATED" | "NOT_EVALUATED" | "NOT_COVERED" | "VERSION_UNKNOWN";
  reason?: "SOURCE_NEVER_LOADED" | "SOURCE_STALE" | "NO_COMPARATOR" | "VERSION_MISSING" | "VERSION_UNPARSED"
    | "JUMBO_TAKE_MISSING" | "JUMBO_TRAIN_MISMATCH" | "SPARK_NOT_COVERED" | "TRAIN_NOT_COVERED"
    | "TRAIN_NEVER_LOADED" | "TRAIN_STALE" | "TRAIN_RECORDS_MISSING" | null;
  product_key?: string | null;
  affected?: number; affected_exploited?: number; affected_critical_high?: number;
  affected_critical?: number; affected_high?: number; affected_medium?: number; affected_low?: number;
  not_evaluable?: number; fixed?: number;
  /** Check Point: not judged because a proposed fix rule waits for a security_admin's confirmation; kept out of the other counts. */
  pending?: number;
  worst_severity?: AdvisorySeverity | null;
  compared?: LifecycleVulnerabilityBasis | null;
}
export interface LifecycleFleet {
  as_of: string;
  devices: LifecycleDevice[];
  /** Counters; `vuln_oldest_source_age_days` is null when no age is known, and the `vuln_*` keys are absent on an older backend. */
  summary: Record<string, number | null>;
}
export interface LifecycleAdvisory {
  /** `<source>:<vendor id>`; contains a colon, so it is URL-encoded in a path. */
  advisory_id: string; source: string; title: string; severity: AdvisorySeverity; cvss_score: number | null; cve_ids: string[];
  /** Exploited: one of the CVE ids is in the CISA Known Exploited Vulnerabilities catalog. */
  exploited: boolean; exploited_since: string | null; published_on: string | null; updated_on: string | null;
  /** Untrusted public text like every other advisory field: a link only when it starts with https://. Null when the server dropped it. */
  source_url: string | null;
  parse_state: "PARSED" | "NEEDS_REVIEW"; parse_note: string | null;
  devices: { affected: number; fixed: number; not_affected: number; not_evaluable: number; pending?: number };
}
export interface LifecycleAdvisoryDevice {
  /** `hostname` is the display name (the key the server masks for a masked session). */
  advisory_id?: string; device_id: string; hostname: string | null; vendor: string; version: string | null;
  verdict: "AFFECTED" | "FIXED" | "NOT_AFFECTED" | "NOT_EVALUABLE";
  /** The code word of the rule that decided (IN_RANGE, AT_OR_ABOVE_FIX, ...); the set is open, an unknown word is shown as it is. */
  basis: string; fixed_text: string | null;
  /** Check Point only: the Live Patch that names this advisory on the device. A label; the verdict never uses it. */
  live_patch?: { patch: string; status: string; used_in_verdict: false } | null;
}
export interface LifecycleAdvisorySource {
  source: string; last_run_at: string | null; outcome: "OK" | "PARTIAL" | "FAILED" | null; reason: string | null;
  advisories: number; needs_review: number; oldest: string | null; newest: string | null; age_days: number | null;
  age_state: "OK" | "AMBER" | "RED" | "NEVER";
  /** Check Point only: each Gaia version's resolved-issues list; a device is judged only on a usable one. */
  trains?: { train: string; loaded_at: string | null; age_days: number | null; age_state: "OK" | "AMBER" | "RED" | "NEVER";
    cves: number; cves_without_advisory: number; usable: boolean }[];
}
export interface SoftwareStretch {
  device_id: string; vendor: string | null; software_version: string; hotfix_level: string | null;
  first_seen_at: string; last_seen_at: string; previous_software_version: string | null; previous_hotfix_level: string | null;
  /** Last time the previous version was still seen: the change happened after this and by `window_end`. */
  window_start: string | null; window_end: string | null; source: "inventory" | "discovery_backfill" | "platform_facts";
}
export interface SoftwareChange extends SoftwareStretch {
  hostname: string | null; from_version: string | null; from_hotfix_level: string | null; to_version: string; to_hotfix_level: string | null;
}
/** One install from a Check Point gateway's own install log (V246). `installed_at_local` is the device's clock, no offset. */
export interface CpInstallEvent {
  kind: "BASE_INSTALL" | "JUMBO_INSTALL"; product_version: string; jumbo_take: number | null;
  installed_at_local: string; file_name: string; first_observed_at: string;
}
export interface CpInstallEventWithDevice extends CpInstallEvent { device_id: string; hostname: string | null }
export const getDeviceSoftwareHistory = (id: string) =>
  apiCall<{ device_id: string; known: boolean; stretches: SoftwareStretch[]; install_events?: CpInstallEvent[] }>(`/devices/${encodeURIComponent(id)}/software-history`, "GET");
export const getSoftwareChanges = (days = 90) =>
  apiCall<{ days: number; since: string; changes: SoftwareChange[]; install_events?: CpInstallEventWithDevice[] }>(`/api/v2/software-history/changes?days=${days}`, "GET");
export const getLifecycle = () => apiCall<LifecycleFleet>("/api/v2/lifecycle", "GET");
export const getDeviceLifecycle = (id: string) => apiCall<LifecycleDevice>(`/devices/${encodeURIComponent(id)}/lifecycle`, "GET");
export const getLifecycleAdvisories = () => apiCall<{ as_of: string; advisories: LifecycleAdvisory[] }>("/api/v2/lifecycle/advisories", "GET");
export const getLifecycleAdvisory = (id: string) =>
  apiCall<{ advisory: LifecycleAdvisory; devices: LifecycleAdvisoryDevice[] }>(`/api/v2/lifecycle/advisories/${encodeURIComponent(id)}`, "GET");
export const getLifecycleAdvisorySources = () => apiCall<{ sources: LifecycleAdvisorySource[] }>("/api/v2/lifecycle/advisory-sources", "GET");
export const getLifecycleCatalog = () => apiCall<{ entries: LifecycleCatalogEntry[]; can_manage: boolean }>("/api/v2/lifecycle/catalog", "GET");
export const saveLifecycleCatalog = (entry: LifecycleCatalogInput, id?: string) =>
  apiCall(id ? `/api/v2/lifecycle/catalog/${encodeURIComponent(id)}` : "/api/v2/lifecycle/catalog", id ? "PUT" : "POST", entry);
export const importLifecycleCatalog = (csv: string) => apiCall<{ imported: number }>("/api/v2/lifecycle/catalog/import", "POST", { csv });
export const deleteLifecycleCatalog = (id: string) => apiCall(`/api/v2/lifecycle/catalog/${encodeURIComponent(id)}/delete`, "POST");
export interface LifecycleCatalogFeed {
  source: string; origin: "FEED"; last_run_outcome: string | null; last_run_reason: string | null; last_run_at: string | null;
  last_good_at: string | null; age_days: number | null; age_state: "GREEN" | "AMBER" | "RED"; fresh_days: number;
  entries: number; entries_needing_review: number;
}
export interface LifecyclePageForm { page_form: string; vendor: string; label: string; page_url: string; online: boolean; }
export interface LifecycleCatalogUpload {
  upload_id: string; page_form: string; vendor: string; entries_parsed: number; entries_needing_review: number; rows_skipped: number;
  uploaded_by: string; uploaded_at: string | null; age_days: number | null; age_state: "GREEN" | "AMBER" | "RED";
}
export interface LifecycleReviewEntry { entry_id: string; vendor: string; kind: string; product: string; key: string; source: string; origin: string; parse_note: string | null; source_url: string; }
export interface LifecycleCatalogSources {
  feeds: LifecycleCatalogFeed[]; page_forms: LifecyclePageForm[]; uploads: LifecycleCatalogUpload[]; needs_review: LifecycleReviewEntry[];
  vendor_entries: number; hand_loaded_fresh_days: number;
}
export const getLifecycleCatalogSources = () => apiCall<LifecycleCatalogSources>("/api/v2/lifecycle/catalog-sources", "GET");
export const uploadLifecycleVendorPage = (page_form: string, page: string) =>
  apiCall<{ upload_id: string; parsed: number; needs_review: number; skipped: number }>("/api/v2/lifecycle/catalog/vendor-page", "POST", { page_form, page });
