import { apiCall, type ApiError } from "../../auth/adminApi";

/**
 * Zone matrix, delivery steps 1 and 2 (docs/design/ZONE_MATRIX_CONTRACT.md sections 3, 4, 6.1 and 10.1): the client of
 * `/api/v2/zone-matrix/*`. The field names are the server's own. Every response carries `can`, the caller's affordances;
 * the screen renders its controls from those booleans and from `publish_check` alone.
 */
const BASE = "/api/v2/zone-matrix";

export type ZoneVersionState = "DRAFT" | "ACTIVE" | "RETIRED" | "DISCARDED";
export type AddressFamily = "IPV4" | "IPV6";
/** Address counts per family, as decimal strings: an IPv6 count does not fit a JavaScript number. */
export type FamilySizes = Readonly<Record<AddressFamily, string>>;

export type MatrixStatement = "DENY_ALL" | "ALLOW_LISTED" | "ALLOW_ANY";
export type DefaultStatement = "DENY_ALL" | "ALLOW_LISTED";
export type MatrixSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export type ReferenceStatus = "EMPTY" | "PROPOSED" | "CONFIRMED";

export interface ZoneMatrixCan {
  readonly view_blocks: boolean;
  readonly edit: boolean;
  readonly publish: boolean;
  readonly confirm_reference?: boolean;
  readonly evaluate?: boolean;
}

export interface ZoneVersionSummary {
  readonly version_id: string;
  /** `null` for the draft. */
  readonly number: number | null;
  readonly state: ZoneVersionState;
  readonly revision: number;
  readonly content_sha256: string;
  readonly created_at: string | null;
  readonly updated_at: string | null;
  readonly published_by: string | null;
  readonly published_at: string | null;
}

export interface ZoneView {
  readonly zone_id: string;
  readonly name: string;
  readonly description: string | null;
  readonly role: string | null;
  readonly block_count: number;
  readonly effective_addresses: FamilySizes;
  /** `null` unless the blocks were asked for (`blocks=1`) and the caller may see them. */
  readonly blocks: readonly string[] | null;
}

export interface RedundantBlock {
  readonly zone: string;
  readonly block: string;
  readonly within: string;
}

export interface ZoneRevision {
  readonly revision: number;
  readonly actor: string;
  readonly occurred_at: string | null;
  readonly change_kind: string;
  readonly zone_id: string | null;
  readonly content_sha256: string;
  readonly rationale_given: boolean | null;
}

export interface ServiceEntryView {
  readonly entry: string;
  readonly evaluable: boolean;
  readonly not_evaluable_reason: string | null;
}

export interface ServiceSetView {
  readonly name: string;
  readonly entries: readonly ServiceEntryView[];
}

export interface TemplateOrigin {
  readonly name: string;
  readonly revision: string;
}

export interface MatrixCellView {
  readonly source: string;
  readonly destination: string;
  readonly source_name: string;
  readonly destination_name: string;
  readonly statement: MatrixStatement;
  readonly services: readonly ServiceEntryView[];
  readonly severity: MatrixSeverity;
  /** `null` when absent or in the masked view. */
  readonly rationale: string | null;
  /** `null` in the masked view; `""` when empty in unmasked views. */
  readonly regulation_reference: string | null;
  readonly reference_status: ReferenceStatus;
  readonly confirmed_by: string | null;
  readonly confirmed_at: string | null;
  readonly cell_sha256: string;
}

export interface ZoneMatrixView {
  readonly default_statement: DefaultStatement;
  readonly default_severity: MatrixSeverity;
  readonly unassigned_to_unassigned: string;
  readonly template_origin: TemplateOrigin | null;
  readonly service_sets: readonly ServiceSetView[];
  readonly cells: readonly MatrixCellView[];
  readonly cells_total: number;
  readonly cells_with_reference: number;
  readonly cells_confirmed: number;
}

export interface ZoneVersionDetail extends ZoneVersionSummary {
  readonly zones: readonly ZoneView[];
  readonly matrix?: ZoneMatrixView;
  readonly unassigned_addresses: FamilySizes;
  /** The list when blocks are shown, otherwise only how many there are. */
  readonly redundant_blocks: readonly RedundantBlock[] | number;
  readonly revisions: readonly ZoneRevision[];
}

export interface ZoneDiffRow {
  readonly zone_id: string;
  readonly change: "ADDED" | "REMOVED" | "CHANGED";
  readonly name_before: string | null;
  readonly name_after: string | null;
  readonly role_before: string | null;
  readonly role_after: string | null;
  /** Lists when blocks are shown, otherwise counts. */
  readonly blocks_added: readonly string[] | number;
  readonly blocks_removed: readonly string[] | number;
  readonly effective_before: FamilySizes;
  readonly effective_after: FamilySizes;
}

export interface ZoneMove {
  readonly from: string;
  readonly to: string;
  readonly family: AddressFamily;
  readonly addresses: string;
  readonly leaves_a_zone: boolean;
}

export interface CellDiffSide {
  readonly explicit: boolean;
  readonly statement: MatrixStatement;
  readonly services: readonly string[];
  readonly severity: MatrixSeverity;
  readonly reference_status: ReferenceStatus | null;
}

export interface CellDiffRow {
  readonly source: string;
  readonly destination: string;
  readonly source_name: string;
  readonly destination_name: string;
  readonly change: "ADDED" | "REMOVED" | "CHANGED";
  readonly before: CellDiffSide | null;
  readonly after: CellDiffSide | null;
  readonly loosening?: readonly string[];
}

export interface DefaultDiff {
  readonly statement_before: DefaultStatement | null;
  readonly statement_after: DefaultStatement;
  readonly severity_before: MatrixSeverity | null;
  readonly severity_after: MatrixSeverity;
}

export type LooseningKind =
  | "DEFAULT_CHANGED"
  | "STATEMENT_RELAXED"
  | "SERVICES_ADDED"
  | "SEVERITY_DOWNGRADED"
  | "ADDRESSES_MOVED"
  | "UNASSIGNED_SHRUNK";

export interface LooseningRow {
  readonly kind: LooseningKind | string;
  readonly source?: string | null;
  readonly destination?: string | null;
  readonly source_name?: string | null;
  readonly destination_name?: string | null;
  readonly family?: AddressFamily | null;
  readonly addresses?: string | null;
}

export interface ZoneDiff {
  readonly zones: readonly ZoneDiffRow[];
  readonly moves: readonly ZoneMove[];
  readonly cells?: readonly CellDiffRow[];
  readonly default?: DefaultDiff;
  readonly loosening?: readonly LooseningRow[];
  readonly rationale_required: boolean;
  readonly unassigned_before: FamilySizes;
  readonly unassigned_after: FamilySizes;
}

export interface PublishCheck {
  readonly eligible: boolean;
  readonly reason: string | null;
}

export interface ZoneDraftResponse {
  readonly active: ZoneVersionSummary | null;
  /** True when a draft exists, also for readers who are not shown it (they get draft: null). */
  readonly draft_exists?: boolean;
  readonly draft: ZoneVersionDetail | null;
  readonly diff: ZoneDiff | null;
  readonly publish_check: PublishCheck | null;
  readonly can: ZoneMatrixCan;
}

export interface ZoneLookupResult {
  readonly version_id: string;
  readonly number: number | null;
  readonly state: ZoneVersionState;
  readonly family: AddressFamily;
  readonly zone_id: string | null;
  /** `UNASSIGNED` when no zone holds the address. */
  readonly zone_name: string | null;
}

export interface ZoneInput {
  readonly name: string;
  readonly description: string | null;
  readonly role: string | null;
  readonly blocks: readonly string[];
}

export interface CellInput {
  readonly source: string;
  readonly destination: string;
  readonly statement: MatrixStatement;
  readonly services: readonly string[];
  readonly severity: MatrixSeverity;
  readonly rationale: string | null;
  readonly regulation_reference: string;
}

export interface TemplateRow {
  readonly row: string;
  readonly statement: MatrixStatement;
  readonly severity: MatrixSeverity;
}

export interface ZoneMatrixTemplateResponse {
  readonly name: string;
  readonly revision: string;
  readonly rows: readonly TemplateRow[];
  readonly basis: string;
  readonly roles: readonly string[];
  readonly can: ZoneMatrixCan;
}

export interface ZoneEditResult {
  readonly version_id: string;
  readonly revision: number;
  readonly content_sha256: string;
  readonly zone_id: string | null;
}

export type EvaluationOutcome = "VIOLATION" | "COMPLIANT" | "NOT_EVALUABLE" | "UNKNOWN";

export interface ZoneMatrixPolicyItem {
  readonly id: string;
  readonly name: string;
  readonly vendor: string;
  readonly source_id: string | null;
  readonly source_name: string | null;
  readonly container_id: string | null;
  readonly container_name: string | null;
  readonly collected_at: string | null;
  readonly rule_count: number;
  readonly target_count: number;
}

export interface ZoneMatrixNotEvaluatedItem {
  readonly id: string;
  readonly name: string;
  readonly vendor: string;
  readonly source_id: string | null;
  readonly source_name: string | null;
  readonly reason: string;
}

export interface ZoneMatrixPoliciesResponse {
  readonly policies: readonly ZoneMatrixPolicyItem[];
  readonly not_evaluated: readonly ZoneMatrixNotEvaluatedItem[];
  readonly can: ZoneMatrixCan;
}

export interface EvaluationCoverageItem {
  readonly code: string;
  readonly description: string;
}

export interface EvaluationEndedSchedule {
  readonly rule_id: string;
  readonly rule_number: number;
  readonly rule_name: string;
  readonly detail: string;
}

export interface EvaluationNotApplicable {
  readonly disabled: number;
  readonly deny_type: number;
  readonly outside_security_rulebase: number;
  readonly apply_layer_decided_by_inline: number;
  readonly schedule_ended: number;
  readonly total: number;
  readonly ended_schedules: readonly EvaluationEndedSchedule[];
}

export interface EvaluationHeadline {
  readonly distinct_violating_rules: number;
  readonly violated_zone_pairs: number;
  readonly critical_rules: number;
  readonly covered_by_earlier_rule: number;
  readonly conditional_rules: number;
  readonly outside_headline_ordered_layer: number;
  readonly not_evaluable_rules: number;
  readonly unknown_rules: number;
  readonly incomplete_policies: number;
  readonly not_evaluated_policies: number;
  readonly exact: boolean;
  readonly text: string;
  readonly outside_headline_text: string | null;
}

export interface EvaluationWitness {
  readonly source_range: string;
  readonly destination_range: string;
  readonly service: string;
}

export interface EvaluationCellViolation {
  readonly source: string;
  readonly destination: string;
  readonly source_name: string;
  readonly destination_name: string;
  readonly statement: MatrixStatement;
  readonly severity: MatrixSeverity;
  readonly not_allowed_services: readonly string[];
  readonly nat_view: string;
  readonly witness: EvaluationWitness | null;
}

export interface EvaluationRuleRow {
  readonly finding_identity: {
    readonly policy_id: string;
    readonly rule_uid: string;
    readonly inline_parent_path: readonly string[];
    readonly violated_pairs: readonly string[];
  };
  readonly rule_id: string;
  readonly rule_uid: string;
  readonly rule_number: number;
  readonly rule_name: string;
  readonly section_id: string;
  readonly section_name: string;
  readonly inline_parent_path: readonly string[];
  readonly target_device_ids: readonly string[];
  readonly outcome: EvaluationOutcome;
  readonly reason: string | null;
  readonly highest_severity: MatrixSeverity | null;
  readonly flags: readonly string[];
  readonly covering_rule_id: string | null;
  readonly touched_pairs: readonly string[];
  readonly touched_pair_names: readonly string[];
  readonly violated_cells: readonly EvaluationCellViolation[];
  readonly undecided_parts: readonly string[];
  readonly nat_view: string | null;
}

export interface EvaluationZoneGap {
  readonly rule_id: string;
  readonly rule_number: number;
  readonly rule_name: string;
  readonly section_name: string;
  readonly range_count: number;
  readonly unassigned_private_ranges: readonly string[] | null;
}

export interface ZoneMatrixEvaluationResponse {
  readonly engine_version: string;
  readonly version_id: string;
  readonly version_number: number;
  readonly content_sha256: string;
  readonly policy_id: string;
  readonly policy_name: string;
  readonly source_id: string | null;
  readonly source_name: string | null;
  readonly container_id: string | null;
  readonly container_name: string | null;
  readonly vendor: string;
  readonly collected_at: string | null;
  readonly witnesses_included: boolean;
  readonly targets: readonly {
    readonly device_id: string;
    readonly name: string | null;
    readonly context: string | null;
    readonly sync_status: string | null;
  }[];
  readonly evaluated: boolean;
  readonly not_evaluated_reason: string | null;
  readonly coverage: "COMPLETE" | "INCOMPLETE" | string;
  readonly coverage_items: readonly EvaluationCoverageItem[];
  readonly failure_count: number;
  readonly outcome_counts: Readonly<Record<string, number>>;
  readonly not_applicable: EvaluationNotApplicable;
  readonly headline: EvaluationHeadline;
  readonly rules: readonly EvaluationRuleRow[];
  readonly zone_definition_gaps: readonly EvaluationZoneGap[];
  readonly hygiene_budget_reached: boolean;
  readonly duration_ms: number;
  readonly hygiene_ms: number;
  readonly can: ZoneMatrixCan;
}

const EMPTY_MATRIX: ZoneMatrixView = {
  default_statement: "DENY_ALL",
  default_severity: "MEDIUM",
  unassigned_to_unassigned: "DENY_ALL unless an explicit cell says otherwise",
  template_origin: null,
  service_sets: [],
  cells: [],
  cells_total: 0,
  cells_with_reference: 0,
  cells_confirmed: 0,
};

/** The matrix of one version, or the initial empty matrix when a fixture omits `matrix`. */
export const matrixOf = (version: ZoneVersionDetail): ZoneMatrixView => version.matrix ?? EMPTY_MATRIX;

const flag = (blocks: boolean) => (blocks ? "1" : "0");

export const listZoneVersions = () =>
  apiCall<{ versions: ZoneVersionSummary[]; can: ZoneMatrixCan }>(`${BASE}/versions`, "GET");

/** `blocks` true asks for the raw blocks; the server writes an audit row for every such read. */
export const getZoneVersion = (versionId: string, blocks: boolean) =>
  apiCall<ZoneVersionDetail & { can: ZoneMatrixCan }>(`${BASE}/versions/${encodeURIComponent(versionId)}?blocks=${flag(blocks)}`, "GET");

export const getZoneDraft = (blocks: boolean) => apiCall<ZoneDraftResponse>(`${BASE}/draft?blocks=${flag(blocks)}`, "GET");

/** Which zone one address is in. Audited server side, so it is sent only when the user asks. */
export const lookupZoneAddress = (address: string, versionId?: string) =>
  apiCall<ZoneLookupResult>(`${BASE}/lookup`, "POST", versionId ? { address, version_id: versionId } : { address });

/** `revision` is the draft revision the editor is looking at; `null` when there is no draft yet. */
export const createZone = (revision: number | null, zone: ZoneInput) =>
  apiCall<ZoneEditResult>(`${BASE}/draft/zones`, "POST", { revision, ...zone });

export const updateZone = (revision: number | null, zoneId: string, zone: ZoneInput) =>
  apiCall<ZoneEditResult>(`${BASE}/draft/zones/${encodeURIComponent(zoneId)}`, "PUT", { revision, ...zone });

export const deleteZone = (revision: number | null, zoneId: string) =>
  apiCall<ZoneEditResult>(`${BASE}/draft/zones/${encodeURIComponent(zoneId)}/delete`, "POST", { revision });

export const discardZoneDraft = (revision: number) => apiCall<{ discarded: string }>(`${BASE}/draft/discard`, "POST", { revision });

export const getZoneMatrixTemplate = () =>
  apiCall<ZoneMatrixTemplateResponse>(`${BASE}/template`, "GET");

export const setMatrixCell = (revision: number | null, cell: CellInput) =>
  apiCall<ZoneEditResult>(`${BASE}/draft/cells`, "POST", { revision, ...cell });

export const clearMatrixCell = (revision: number | null, source: string, destination: string) =>
  apiCall<ZoneEditResult>(`${BASE}/draft/cells/clear`, "POST", { revision, source, destination });

export const setMatrixDefault = (revision: number | null, statement: DefaultStatement, severity: MatrixSeverity) =>
  apiCall<ZoneEditResult>(`${BASE}/draft/default`, "POST", { revision, statement, severity });

export const saveServiceSet = (revision: number | null, name: string, entries: readonly string[]) =>
  apiCall<ZoneEditResult>(`${BASE}/draft/service-sets`, "POST", { revision, name, entries });

export const deleteServiceSet = (revision: number | null, name: string) =>
  apiCall<ZoneEditResult>(`${BASE}/draft/service-sets/delete`, "POST", { revision, name });

export const importMatrixTemplate = (revision: number | null, replace: boolean) =>
  apiCall<ZoneEditResult>(`${BASE}/draft/template-import`, "POST", { revision, replace });

export const confirmCellReference = (revision: number | null, source: string, destination: string, cellSha256: string) =>
  apiCall<ZoneEditResult>(`${BASE}/draft/cells/confirm-reference`, "POST", {
    revision, source, destination, cell_sha256: cellSha256,
  });

/** Carries the revision and hash of the draft the publisher reviewed; the server refuses when the draft changed since. */
export const publishZoneDraft = (revision: number, contentSha256: string, rationale: string | null) =>
  apiCall<{ version_id: string; number: number; content_sha256: string }>(`${BASE}/draft/publish`, "POST",
    { revision, content_sha256: contentSha256, rationale });

export const listZoneMatrixPolicies = () =>
  apiCall<ZoneMatrixPoliciesResponse>(`${BASE}/policies`, "GET");

/** Evaluates one stored policy snapshot on demand against a published version; `includeWitnesses` is audited server side. */
export const evaluateZoneMatrixPolicy = (policyId: string, versionId?: string | null, includeWitnesses = false) =>
  apiCall<ZoneMatrixEvaluationResponse>(`${BASE}/evaluate`, "POST", {
    policy_id: policyId,
    ...(versionId ? { version_id: versionId } : {}),
    include_witnesses: includeWitnesses,
  });

/** The stored outcomes the summary route keeps (a COMPLIANT rule is not stored). */
export type SummaryOutcome = "VIOLATION" | "UNKNOWN" | "NOT_EVALUABLE";
export type SummaryState = "READY" | "NO_ACTIVE_VERSION" | "NO_EVALUABLE_POLICY" | "NO_COMPLETE_RUN";
export type StalenessVerdict = "CURRENT" | "STALE";

export interface SummaryStalenessThreshold {
  readonly seconds: number;
  readonly hours: number | null;
  /** `true` while the 24-hour value is the Product Owner's interim one (PO 2026-11-23), not the bank's. */
  readonly interim: boolean;
  readonly source: string;
  readonly label: string;
}

export interface SummaryActiveVersion {
  readonly version_id: string;
  readonly version_number: number;
  readonly content_sha256: string;
  readonly published_at: string | null;
}

export interface SummaryStalenessHeadline {
  readonly count: number;
  readonly exact: boolean;
  readonly text: string;
}

export interface SummaryHeadline {
  readonly version_id: string;
  readonly version_number: number;
  readonly distinct_violating_rules: number;
  readonly violated_zone_pairs: number;
  readonly critical_rules: number;
  readonly covered_by_earlier_rule: number;
  readonly conditional_rules: number;
  readonly outside_headline_ordered_layer: number;
  readonly not_evaluable_findings: number;
  readonly unknown_findings: number;
  readonly became_undecided_findings: number;
  readonly stale_policies: number;
  readonly incomplete_policies: number;
  readonly older_version_policies: number;
  readonly not_evaluated_policies: number;
  readonly resolved_by_version_change_last_7_days: number;
  readonly exact: boolean;
  readonly count_text: string;
  readonly text: string;
  readonly outside_headline_text: string | null;
  readonly staleness_headline: SummaryStalenessHeadline;
}

export interface SummaryRunCounts {
  readonly violation: number;
  readonly compliant: number;
  readonly not_evaluable: number;
  readonly unknown: number;
  readonly not_applicable: number;
  readonly findings: number;
}

export interface SummaryRun {
  readonly run_id: string;
  /** RUNNING, COMPLETE or FAILED. */
  readonly state: string;
  readonly failure_reason: string | null;
  readonly version_id: string;
  readonly version_number: number;
  readonly content_sha256: string;
  readonly started_at: string | null;
  readonly finished_at: string | null;
  readonly snapshot_collected_at: string | null;
  readonly coverage: string | null;
  readonly counts: SummaryRunCounts | null;
}

export interface SummaryPolicyStaleness {
  readonly verdict: StalenessVerdict;
  /** SNAPSHOT_OLDER_THAN_THRESHOLD, LAST_RUN_FAILED, VERSION_NOT_ACTIVE or SNAPSHOT_TIME_UNKNOWN. */
  readonly reasons: readonly string[];
  /** `null` when the age cannot be stated; never 0 in its place. */
  readonly snapshot_age_seconds: number | null;
  readonly snapshot_age_known: boolean;
}

export interface SummaryPolicy {
  readonly policy_id: string;
  readonly policy_name: string;
  readonly vendor: string;
  readonly source_id: string | null;
  readonly source_name: string | null;
  readonly container_id: string | null;
  readonly container_name: string | null;
  readonly collected_at: string | null;
  readonly rule_count: number;
  readonly staleness: SummaryPolicyStaleness;
  readonly coverage: "COMPLETE" | "INCOMPLETE" | string;
  readonly coverage_items: readonly string[];
  /** `null` when the policy has no COMPLETE run: the number is not known. */
  readonly collection_failures: number | null;
  readonly has_complete_run: boolean;
  readonly latest_complete_run: SummaryRun | null;
  readonly latest_run: SummaryRun | null;
}

export interface SummaryNotEvaluated {
  readonly id: string;
  readonly name: string;
  readonly vendor: string;
  readonly source_id: string | null;
  readonly source_name: string | null;
  readonly reason: string;
}

export interface SummaryViolatedPair {
  readonly source_zone_id: string | null;
  readonly destination_zone_id: string | null;
  readonly source_name: string;
  readonly destination_name: string;
  readonly statement: MatrixStatement | string | null;
  readonly severity: MatrixSeverity | string | null;
  readonly not_allowed_services: readonly string[];
}

/** The fields a stored finding and a resolved-by-version-change finding share. */
export interface SummaryFindingBase {
  readonly policy_source_id: string;
  readonly policy_name: string;
  readonly rule_uid: string;
  readonly inline_parent_path: readonly string[];
  readonly rule_id: string | null;
  readonly rule_number: number | null;
  readonly rule_name: string | null;
  readonly target_device_ids: readonly string[];
  readonly outcome: SummaryOutcome | string;
  readonly reason: string | null;
  readonly highest_severity: MatrixSeverity | string | null;
  readonly flags: readonly string[];
  readonly covering_rule_id: string | null;
  readonly violated_pairs: readonly SummaryViolatedPair[];
  readonly undecided_parts: readonly string[];
  readonly first_observed_at: string | null;
  readonly first_observed_any_version: string | null;
  readonly last_observed_at: string | null;
}

export interface SummaryFinding extends SummaryFindingBase {
  /** The opaque key of the audited witness read; `null` (or absent) when the store kept none: no witness read is offered. */
  readonly finding_id?: string | null;
  readonly run_id: string;
  readonly version_id: string;
  readonly version_number: number;
  readonly content_sha256: string;
  readonly on_active_version: boolean;
  /** CURRENT or STALE: the staleness verdict of the finding's policy. */
  readonly policy_staleness: StalenessVerdict | string;
  readonly snapshot_age_seconds: number | null;
}

export interface SummaryResolvedFinding extends SummaryFindingBase {
  readonly resolved_from_version_id: string;
  readonly resolved_from_version_number: number | null;
  readonly resolved_in_version_id: string | null;
  readonly resolved_in_version_number: number | null;
  readonly resolved_at: string | null;
}

export interface SummaryResolvedByVersion {
  readonly window_days: number;
  readonly total_last_7_days: number;
  readonly returned: number;
  readonly more: number;
  readonly capped: boolean;
  readonly last_7_days: readonly SummaryResolvedFinding[];
  readonly by_version: readonly { readonly version_id: string; readonly version_number: number | null; readonly count: number }[];
}

/** One zone pair with open violations on the active version (contract 7.1 Matrix tab; `ZoneMatrixSummaryView`). */
export interface SummaryCellCount {
  readonly source_zone_id: string;
  readonly destination_zone_id: string;
  readonly source_name: string;
  readonly destination_name: string;
  /** Rules counted by the headline; a lower bound unless `cell_counts_exact`. */
  readonly headline_rules: number;
  readonly outside_headline_rules: number;
  readonly highest_severity: MatrixSeverity | null;
}

export interface ZoneMatrixSummaryResponse {
  /** The caller's affordances; `view_blocks` (zone_matrix_zones_read, never for a masked session) opens the witness read. */
  readonly can?: ZoneMatrixCan;
  readonly evaluated_at: string | null;
  readonly state: SummaryState | string;
  readonly state_text: string;
  readonly masked: boolean;
  readonly policy_names_masked: boolean;
  readonly witnesses_included: boolean;
  readonly staleness_threshold: SummaryStalenessThreshold;
  readonly active_version: SummaryActiveVersion | null;
  readonly headline: SummaryHeadline | null;
  readonly headline_absent_reason: string | null;
  readonly policies: readonly SummaryPolicy[];
  readonly not_evaluated: readonly SummaryNotEvaluated[];
  readonly filters: { readonly policy_id: string | null; readonly outcome: string | null; readonly limit: number };
  /** All findings of the latest complete runs, before the route's own filters. */
  readonly unfiltered_findings: number;
  /** Findings matching the route's filters; `returned` of them are in `findings`. */
  readonly total_findings: number;
  readonly returned: number;
  readonly more: number;
  readonly capped: boolean;
  readonly findings: readonly SummaryFinding[];
  readonly resolved_by_version_change: SummaryResolvedByVersion;
  /**
   * Per-cell open-violation counts of the active version, capped by the request limit. All six keys are `null` (or absent)
   * when there is no active version or no stored counts: that is never zero.
   */
  readonly cell_counts?: readonly SummaryCellCount[] | null;
  /** The headline's `exact`: when false every count is a lower bound ("at least"). */
  readonly cell_counts_exact?: boolean | null;
  readonly cell_counts_total?: number | null;
  readonly cell_counts_returned?: number | null;
  readonly cell_counts_more?: number | null;
  readonly cell_counts_capped?: boolean | null;
}

/** The query the summary route accepts; anything else is a 400. */
export interface ZoneMatrixSummaryParams {
  readonly policy_id?: string | null;
  readonly outcome?: SummaryOutcome | null;
  readonly limit?: number | null;
}

/** The stored summary: findings of the latest COMPLETE run of each policy, coverage and staleness (contract 7.1, 7.2). */
export function getZoneMatrixSummary(params: ZoneMatrixSummaryParams = {}) {
  const query = new URLSearchParams();
  if (params.policy_id) query.set("policy_id", params.policy_id);
  if (params.outcome) query.set("outcome", params.outcome);
  if (params.limit) query.set("limit", String(params.limit));
  const text = query.toString();
  return apiCall<ZoneMatrixSummaryResponse>(`${BASE}/summary${text ? `?${text}` : ""}`, "GET");
}

/** One stored witness tuple, as stored: ranges, never narrowed to one address. An unreadable part is "UNKNOWN". */
export interface StoredWitnessTuple {
  readonly source_range: string;
  readonly destination_range: string;
  /** `tcp`, `udp`, `any`, or `UNKNOWN`. */
  readonly protocol: string;
  /** A port number as text, `any`, or `UNKNOWN`. */
  readonly port: string;
  /** The stored service text (e.g. `tcp/443`, `application-default`). */
  readonly service: string;
}

export interface StoredWitnessCell {
  readonly source_zone_id: string | null;
  readonly destination_zone_id: string | null;
  readonly nat_view: string;
  /** `null`: this cell stored no witness; the screen says UNKNOWN. */
  readonly witness: StoredWitnessTuple | null;
}

/** `GET /api/v2/zone-matrix/findings/{findingId}/witness`: one audited read (`WITNESS_VIEW`), never cached. */
export interface FindingWitnessResponse {
  readonly finding_id: string;
  readonly run_id: string;
  readonly version_id: string;
  readonly version_number: number;
  readonly nat_view: string;
  readonly witnesses_included: boolean;
  readonly audited: boolean;
  readonly cells_total: number;
  readonly witnesses_stored: number;
  readonly cells: readonly StoredWitnessCell[];
  readonly can: ZoneMatrixCan;
}

/**
 * The stored witness tuples of one finding (contract 7.1, 8.1). The server refuses it without zone_matrix_zones_read and
 * for a masked session, and records every answer in the audit log before it sends it, so it is called only on a click.
 */
export const getFindingWitness = (findingId: string) =>
  apiCall<FindingWitnessResponse>(`${BASE}/findings/${encodeURIComponent(findingId)}/witness`, "GET");

/** The zone roles of the template (contract section 6), with the words the screen shows. */
export const ZONE_ROLES: readonly { readonly value: string; readonly label: string }[] = [
  { value: "INTERNET_UNTRUSTED", label: "Internet (untrusted)" },
  { value: "DMZ", label: "DMZ" },
  { value: "USER", label: "User" },
  { value: "APPLICATION", label: "Application" },
  { value: "DATABASE", label: "Database" },
  { value: "MANAGEMENT", label: "Management" },
  { value: "PARTNER", label: "Partner" },
  { value: "NON_PRODUCTION", label: "Non-production" },
];

export const zoneRoleLabel = (role: string | null | undefined): string =>
  role ? (ZONE_ROLES.find((r) => r.value === role)?.label ?? role) : "None";

export const MATRIX_STATEMENTS: readonly { readonly value: MatrixStatement; readonly label: string }[] = [
  { value: "DENY_ALL", label: "Deny all" },
  { value: "ALLOW_LISTED", label: "Allow listed" },
  { value: "ALLOW_ANY", label: "Allow any" },
];

export const DEFAULT_STATEMENTS: readonly { readonly value: DefaultStatement; readonly label: string }[] = [
  { value: "DENY_ALL", label: "Deny all" },
  { value: "ALLOW_LISTED", label: "Allow listed" },
];

export const MATRIX_SEVERITIES: readonly { readonly value: MatrixSeverity; readonly label: string }[] = [
  { value: "CRITICAL", label: "Critical" },
  { value: "HIGH", label: "High" },
  { value: "MEDIUM", label: "Medium" },
  { value: "LOW", label: "Low" },
];

export const statementWord = (statement: string | null | undefined): string =>
  MATRIX_STATEMENTS.find((s) => s.value === statement)?.label ?? (statement ?? "UNKNOWN");

export const severityWord = (severity: string | null | undefined): string =>
  MATRIX_SEVERITIES.find((s) => s.value === severity)?.label ?? (severity ?? "UNKNOWN");

export const referenceStatusWord = (status: ReferenceStatus | string | null | undefined): string => {
  switch (status) {
    case "EMPTY": return "Empty";
    case "PROPOSED": return "Proposed";
    case "CONFIRMED": return "Confirmed";
    default: return status ?? "UNKNOWN";
  }
};

/** The server's refusal code (`error`), or `null` when the failure is not a refusal with a code. */
export function zoneErrorCode(err: unknown): string | null {
  const code = (err as ApiError | undefined)?.body?.error;
  return typeof code === "string" ? code : null;
}

/** A refusal detail: the server writes details beside `error` and `reason`; a nested `details` object is read too. */
function detail(err: unknown, key: string): unknown {
  const body = (err as ApiError | undefined)?.body;
  if (!body) return undefined;
  if (body[key] !== undefined) return body[key];
  const nested = body.details;
  return nested && typeof nested === "object" ? (nested as Record<string, unknown>)[key] : undefined;
}

export const REQUEST_CHANGED_TEXT = "The draft changed since you loaded it. The latest draft is loaded now; check it and try again.";
export const RATIONALE_REQUIRED_TEXT = "This version loosens the segmentation policy; give a rationale.";
export const AUDIT_UNAVAILABLE_TEXT = "The view was refused because its audit row could not be written.";
export const COMPLIANCE_ROLE_REQUIRED_TEXT = "Only a compliance administrator confirms a reference.";
export const REFERENCE_EMPTY_TEXT = "There is no reference to confirm.";
export const ALREADY_CONFIRMED_TEXT = "This reference is already confirmed.";
export const CELL_NOT_FOUND_TEXT = "This pair has no explicit cell.";
/** Said before every publish: the contract makes a published version immutable. */
export const PUBLISH_FINAL_TEXT = "A published version cannot be changed afterwards. A later change is a new draft that another person publishes.";

/** Why the publish control is closed, as a plain sentence. An unknown code is shown as it is. */
export function publishCheckText(reason: string | null | undefined): string {
  switch (reason) {
    case "SELF_APPROVAL": return "You changed this draft; another person must publish it.";
    case "COMPLIANCE_ROLE_MISSING": return "The publisher or an editor of this draft must hold the compliance administrator role.";
    case "PRINCIPAL_NOT_ELIGIBLE": return "This account cannot publish.";
    case "NOTHING_TO_PUBLISH": return "The draft equals the active version; there is nothing to publish.";
    case "RATIONALE_REQUIRED": return RATIONALE_REQUIRED_TEXT;
    default: return reason ? `This draft cannot be published by this account (${reason}).` : "This draft cannot be published by this account.";
  }
}

/**
 * A failed request as lines for the user: the refusal sentences the contract names, else the server's own reason first,
 * then what its details add (one line per refused block or service; the duplicated block and the two zones that hold it).
 */
export function zoneErrorLines(err: unknown, sentItems: readonly string[] = []): string[] {
  const failure = err as ApiError | undefined;
  const code = zoneErrorCode(err);
  if (failure?.status === 0) return ["The request did not complete. Check the connection and try again."];
  if (code === "REQUEST_CHANGED") return [REQUEST_CHANGED_TEXT];
  if (code === "COMPLIANCE_ROLE_REQUIRED") return [COMPLIANCE_ROLE_REQUIRED_TEXT];
  if (code === "REFERENCE_EMPTY") return [REFERENCE_EMPTY_TEXT];
  if (code === "ALREADY_CONFIRMED") return [ALREADY_CONFIRMED_TEXT];
  if (code === "CELL_NOT_FOUND") return [CELL_NOT_FOUND_TEXT];
  if (code === "SELF_APPROVAL" || code === "COMPLIANCE_ROLE_MISSING" || code === "PRINCIPAL_NOT_ELIGIBLE"
    || code === "NOTHING_TO_PUBLISH" || code === "RATIONALE_REQUIRED") return [publishCheckText(code)];
  const reason = failure?.body?.reason;
  // The server's own reason first; the sentences below are only for a refusal that arrives without one.
  const fallback = code === "ZONE_MATRIX_AUDIT_UNAVAILABLE" ? AUDIT_UNAVAILABLE_TEXT
    : code === "READ_ONLY_PERSONA" ? "This session is read only and cannot change or look up zones."
    : code === "ZONE_BLOCKS_NOT_PERMITTED" ? "This account may not view zone blocks."
    : code ? `The request was refused (${code}).` : "The request failed.";
  const lines = [typeof reason === "string" && reason ? sentence(reason) : fallback];
  if (code === "INVALID_BLOCKS" || code === "INVALID_SERVICES") {
    const errors = detail(err, "errors");
    const defaultReason = code === "INVALID_SERVICES" ? "not a canonical service entry" : "not a canonical CIDR block";
    if (Array.isArray(errors)) {
      for (const e of errors as { index?: unknown; reason?: unknown }[]) {
        const index = typeof e.index === "number" ? e.index : -1;
        const text = sentItems[index];
        lines.push(`Line ${index + 1}${text ? ` (${text})` : ""}: ${typeof e.reason === "string" ? e.reason : defaultReason}`);
      }
    }
  }
  if (code === "ZONE_BLOCK_DUPLICATE") {
    const block = detail(err, "block");
    const zones = detail(err, "zones");
    if (typeof block === "string") {
      lines.push(`${block} is in ${Array.isArray(zones) && zones.length > 0 ? zones.join(" and ") : "two zones"}.`);
    }
  }
  return lines;
}

const sentence = (text: string) => {
  const upper = text.charAt(0).toUpperCase() + text.slice(1);
  return /[.!?]$/.test(upper) ? upper : `${upper}.`;
};

/**
 * An address count for reading: grouped digits up to twelve digits, a rounded power of ten above that (the exact value
 * stays on hover). Works on the decimal string, never through a JavaScript number.
 */
export function addressCount(value: string | null | undefined): string {
  if (!value || !/^\d+$/.test(value)) return "UNKNOWN";
  const digits = value.replace(/^0+(?=\d)/, "");
  if (digits.length <= 12) return digits.replace(/\B(?=(\d{3})+$)/g, ",");
  return `about ${digits[0]}.${digits.slice(1, 3)} x 10^${digits.length - 1}`;
}

/** The short form of a content hash; the full value belongs in a `title`. */
export const shortHash = (hash: string | null | undefined): string => (hash ? hash.slice(0, 12) : "");
