import type { PacketCase, SupportBundleRequest } from "../auth/adminApi";
import { relativeAge } from "../shell/time";
import { parseTimeMs, type BadgeState } from "./estateModel";

/** Support bundles listed under one case before the row prints "+N more". */
export const CASES_BUNDLES_PER_CASE = 3;
/** Cases listed before the screen prints "+N more cases". */
export const CASES_SHOWN = 25;

/**
 * Where each Cases action lands: the standard screen, always with `standard=1` (device and admin actions stay in the
 * standard view). The packet capture, diagnostics, bundle and sanitiser screens are not addressed per case.
 */
export const CASE_ACTION_HREFS = {
  newCapture: "?screen=packet_analysis&standard=1",
  diagnostics: "?screen=diagnostics&standard=1",
  bundle: "?screen=support_bundles&standard=1",
  sanitiser: "?screen=sanitizer&standard=1",
} as const;

export interface CaseStateView {
  readonly state: BadgeState;
  readonly word: string;
}

/** Packet case state as one badge; an unrecognised value is UNKNOWN, never OK. */
export function caseStateView(state: string | null | undefined): CaseStateView {
  switch (state) {
    case "ANALYZED":
      return { state: "ok", word: "Analysed" };
    case "QUEUED":
    case "ANALYZING":
      return { state: "watch", word: "Running" };
    case "OPEN":
      return { state: "na", word: "Open, no analysis yet" };
    case "FAILED":
      return { state: "act", word: "Analysis failed" };
    default:
      return { state: "unk", word: "UNKNOWN" };
  }
}

export interface CaseBundleLine {
  readonly id: string;
  readonly vendor: string;
  /** The bundle's own state as the server reports it; absent state is UNKNOWN. */
  readonly stateWord: string;
  /** Age of the bundle's request (its evidence), not of the case. */
  readonly requestedAge: string;
}

export interface CaseRow {
  readonly caseId: string;
  readonly title: string;
  readonly view: CaseStateView;
  readonly captureCount: number;
  readonly totalBytes: number;
  /** Age of the case's creation stamp: the evidence the row is built from. */
  readonly createdAge: string;
  /** Bundles whose case reference equals this case's id (exact match, no guessing). */
  readonly bundleCount: number;
  readonly bundles: readonly CaseBundleLine[];
  readonly bundlesMore: number;
}

export interface CasesModel {
  readonly rows: readonly CaseRow[];
  readonly rowsMore: number;
  readonly caseCount: number;
  /** Bundles read successfully; false means the bundle link for every case is UNKNOWN. */
  readonly bundlesKnown: boolean;
  readonly bundleTotal: number;
  /** Bundles whose case reference is set but matches no listed case. */
  readonly bundlesUnlinked: number;
  /** Bundles whose case reference is not visible to this role (absent field). */
  readonly bundlesReferenceHidden: number;
}

/** Sort key: a missing or unparseable timestamp sorts last (oldest). */
function timeOf(iso: string | null | undefined): number {
  return parseTimeMs(iso) ?? Number.NEGATIVE_INFINITY;
}

function bundleLine(b: SupportBundleRequest, now: Date): CaseBundleLine {
  return {
    id: b.id,
    vendor: b.vendor || "UNKNOWN",
    stateWord: b.outcome ? `${b.state || "UNKNOWN"} · ${b.outcome}` : b.state || "UNKNOWN",
    requestedAge: relativeAge(b.requested_at, now),
  };
}

/**
 * Groups packet cases with the support bundles that name them. Cases are newest first. Sanitiser runs and
 * diagnostics runs carry no case reference in the data the screen reads, so they are not counted here at all:
 * the screen states that they are UNKNOWN per case. `bundles` is `null` when the bundle list could not be read.
 */
export function buildCasesModel(
  cases: readonly PacketCase[],
  bundles: readonly SupportBundleRequest[] | null,
  now: Date,
): CasesModel {
  const sorted = [...cases].sort((a, b) => timeOf(b.created_at) - timeOf(a.created_at));
  const byCase = new Map<string, SupportBundleRequest[]>();
  let referenceHidden = 0;
  for (const b of bundles ?? []) {
    const ref = b.case_ref?.trim() ?? "";
    if (b.case_ref === undefined) referenceHidden += 1;
    if (!ref) continue;
    const list = byCase.get(ref) ?? [];
    list.push(b);
    byCase.set(ref, list);
  }
  const caseIds = new Set(sorted.map((c) => c.case_id));
  let unlinked = 0;
  for (const [ref, list] of byCase) if (!caseIds.has(ref)) unlinked += list.length;

  const rows: CaseRow[] = sorted.slice(0, CASES_SHOWN).map((c) => {
    const linked = [...(byCase.get(c.case_id) ?? [])].sort((a, b) => timeOf(b.requested_at) - timeOf(a.requested_at));
    return {
      caseId: c.case_id,
      title: c.title?.trim() || "Untitled case",
      view: caseStateView(c.state),
      captureCount: c.capture_count,
      totalBytes: c.total_bytes,
      createdAge: relativeAge(c.created_at, now),
      bundleCount: linked.length,
      bundles: linked.slice(0, CASES_BUNDLES_PER_CASE).map((b) => bundleLine(b, now)),
      bundlesMore: Math.max(0, linked.length - CASES_BUNDLES_PER_CASE),
    };
  });

  return {
    rows,
    rowsMore: Math.max(0, sorted.length - CASES_SHOWN),
    caseCount: sorted.length,
    bundlesKnown: bundles !== null,
    bundleTotal: bundles?.length ?? 0,
    bundlesUnlinked: unlinked,
    bundlesReferenceHidden: referenceHidden,
  };
}
