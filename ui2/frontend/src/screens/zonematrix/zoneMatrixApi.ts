import { apiCall, type ApiError } from "../../auth/adminApi";

/**
 * Zone matrix, delivery step 1 (docs/design/ZONE_MATRIX_CONTRACT.md sections 3 and 10.1): the client of
 * `/api/v2/zone-matrix/*`. The field names are the server's own. Every response carries `can`, the caller's affordances;
 * the screen renders its controls from those booleans and from `publish_check` alone.
 */
const BASE = "/api/v2/zone-matrix";

export type ZoneVersionState = "DRAFT" | "ACTIVE" | "RETIRED" | "DISCARDED";
export type AddressFamily = "IPV4" | "IPV6";
/** Address counts per family, as decimal strings: an IPv6 count does not fit a JavaScript number. */
export type FamilySizes = Readonly<Record<AddressFamily, string>>;

export interface ZoneMatrixCan {
  readonly view_blocks: boolean;
  readonly edit: boolean;
  readonly publish: boolean;
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

export interface ZoneVersionDetail extends ZoneVersionSummary {
  readonly zones: readonly ZoneView[];
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

export interface ZoneDiff {
  readonly zones: readonly ZoneDiffRow[];
  readonly moves: readonly ZoneMove[];
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

export interface ZoneEditResult {
  readonly version_id: string;
  readonly revision: number;
  readonly content_sha256: string;
  readonly zone_id: string;
}

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

/** Carries the revision and hash of the draft the publisher reviewed; the server refuses when the draft changed since. */
export const publishZoneDraft = (revision: number, contentSha256: string, rationale: string | null) =>
  apiCall<{ version_id: string; number: number; content_sha256: string }>(`${BASE}/draft/publish`, "POST",
    { revision, content_sha256: contentSha256, rationale });

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
export const RATIONALE_REQUIRED_TEXT = "Addresses leave a zone in this version; give a rationale.";
export const AUDIT_UNAVAILABLE_TEXT = "The view was refused because its audit row could not be written.";
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
 * A failed request as lines for the user: the server's own reason first, then what its details add (one line per
 * refused block; the duplicated block and the two zones that hold it).
 */
export function zoneErrorLines(err: unknown, sentBlocks: readonly string[] = []): string[] {
  const failure = err as ApiError | undefined;
  const code = zoneErrorCode(err);
  if (failure?.status === 0) return ["The request did not complete. Check the connection and try again."];
  if (code === "REQUEST_CHANGED") return [REQUEST_CHANGED_TEXT];
  if (code === "SELF_APPROVAL" || code === "COMPLIANCE_ROLE_MISSING" || code === "PRINCIPAL_NOT_ELIGIBLE"
    || code === "NOTHING_TO_PUBLISH" || code === "RATIONALE_REQUIRED") return [publishCheckText(code)];
  const reason = failure?.body?.reason;
  // The server's own reason first; the sentences below are only for a refusal that arrives without one.
  const fallback = code === "ZONE_MATRIX_AUDIT_UNAVAILABLE" ? AUDIT_UNAVAILABLE_TEXT
    : code === "READ_ONLY_PERSONA" ? "This session is read only and cannot change or look up zones."
    : code === "ZONE_BLOCKS_NOT_PERMITTED" ? "This account may not view zone blocks."
    : code ? `The request was refused (${code}).` : "The request failed.";
  const lines = [typeof reason === "string" && reason ? sentence(reason) : fallback];
  if (code === "INVALID_BLOCKS") {
    const errors = detail(err, "errors");
    if (Array.isArray(errors)) {
      for (const e of errors as { index?: unknown; reason?: unknown }[]) {
        const index = typeof e.index === "number" ? e.index : -1;
        const text = sentBlocks[index];
        lines.push(`Line ${index + 1}${text ? ` (${text})` : ""}: ${typeof e.reason === "string" ? e.reason : "not a canonical CIDR block"}`);
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
