import type { PolicyInstallState, PolicyInstallStatusRow } from "../auth/adminApi";
import {
  POLICY_INSTALL_STATES,
  parityLabel,
  parityTone,
  stateTone,
} from "../screens/PolicyInstallMonitoringPanel";
import { vendorLabel } from "../shell/presenters";
import { relativeAge } from "../shell/time";
import type { StateRole } from "../theme/tokens";
import { newestIso, type BadgeState } from "./estateModel";

/**
 * Pure model for the read-only "Policy installs" section of the Estate Changes screen (standard-view parity).
 * Tone and HA parity words are the standard panel's own mappings (`stateTone`, `parityLabel`, `parityTone`), reused
 * rather than copied. PENDING and UNKNOWN are never shown as zero or OK.
 */

export const POLICY_INSTALLS_SHOWN = 12;

/** Estate-side word for a state the standard panel does not enumerate; not a server enum value. */
export const POLICY_INSTALL_PENDING = "PENDING" as const;

export type PolicyInstallWord = PolicyInstallState | typeof POLICY_INSTALL_PENDING;

export interface PolicyInstallStateCount {
  readonly state: PolicyInstallState;
  readonly badgeState: BadgeState;
  readonly count: number;
  readonly label: string;
}

export interface PolicyInstallLine {
  readonly key: string;
  readonly hostname: string;
  readonly vendorLabel: string;
  readonly contextLabel: string | null;
  readonly target: string;
  readonly word: PolicyInstallWord;
  readonly tone: StateRole;
  readonly badgeState: BadgeState;
  readonly haWord: string | null;
  readonly haTone: StateRole | null;
  readonly haBadgeState: BadgeState | null;
  readonly policyName: string | null;
  readonly installedAt: string | null;
  readonly installedAge: string | null;
  readonly lastChangeAt: string | null;
  readonly reason: string | null;
}

export interface PolicyInstallsModel {
  readonly lines: readonly PolicyInstallLine[];
  /** Rows the server reports beyond the ones shown here; never negative. Always 0 when the total is not known. */
  readonly moreCount: number;
  /** The server total, or null when the read carried no finite total. Never inferred from the row count. */
  readonly totalCount: number | null;
  /** False when the read carried no finite total: the list is shown as "total not reported", never as complete. */
  readonly totalKnown: boolean;
  /** Rows whose state is PENDING or UNKNOWN: not evaluated, so never counted as OK or zero. */
  readonly notEvaluatedCount: number;
  /** Ordered per-state summary counts across `POLICY_INSTALL_STATES`. */
  readonly stateCounts: readonly PolicyInstallStateCount[];
  /** False when `counts` is missing or not a valid object: the UI says "state counts not reported" rather than inventing zeroes. */
  readonly stateCountsKnown: boolean;
  /** Newest `installed_at` or `last_change_at` timestamp across the rows, or null when none is recorded. */
  readonly newestEvidenceAt: string | null;
}

/** The link target of every row and of the "+N more" footer: the standard install panel (read-only here). */
export function policyInstallsHref(): string {
  return "?screen=operations&tab=policy-install&standard=1";
}

/** Maps any raw state to a word the section can show. A state the client does not know becomes UNKNOWN. */
export function policyInstallWord(state: string | null | undefined): PolicyInstallWord {
  if (state === POLICY_INSTALL_PENDING) return POLICY_INSTALL_PENDING;
  if (state && (POLICY_INSTALL_STATES as readonly string[]).includes(state)) {
    return state as PolicyInstallState;
  }
  return "UNKNOWN";
}

/** Tone for a word: PENDING is neutral-unknown, never ok; everything else uses the standard `stateTone`. */
export function policyInstallTone(word: PolicyInstallWord): StateRole {
  if (word === POLICY_INSTALL_PENDING) return "unknown";
  return stateTone(word);
}

/** Maps an M3 StateRole to an Estate geometric BadgeState. UNKNOWN and PENDING always map to "unk", never "ok". */
export function stateRoleToBadgeState(tone: StateRole): BadgeState {
  switch (tone) {
    case "ok":
      return "ok";
    case "warn":
      return "watch";
    case "bad":
      return "act";
    case "neutral":
      return "na";
    case "unknown":
    default:
      return "unk";
  }
}

/** Estate BadgeState for a policy install word. */
export function policyInstallBadgeState(word: PolicyInstallWord): BadgeState {
  return stateRoleToBadgeState(policyInstallTone(word));
}

function haParityWord(
  parity: PolicyInstallStatusRow["ha_parity"],
): { word: string; tone: StateRole; badgeState: BadgeState } | null {
  const word = parityLabel(parity);
  if (word === null) return null;
  const tone = parityTone(parity);
  return { word, tone, badgeState: stateRoleToBadgeState(tone) };
}

function buildStateCounts(
  counts: Partial<Record<PolicyInstallState, number>> | null | undefined,
): { readonly stateCounts: readonly PolicyInstallStateCount[]; readonly stateCountsKnown: boolean } {
  if (!counts || typeof counts !== "object" || Array.isArray(counts)) {
    return { stateCounts: [], stateCountsKnown: false };
  }
  const stateCounts: PolicyInstallStateCount[] = [];
  for (const state of POLICY_INSTALL_STATES) {
    if (!(state in counts) || counts[state] === undefined) {
      continue;
    }
    const raw = counts[state];
    if (typeof raw !== "number" || !Number.isFinite(raw) || raw < 0) {
      return { stateCounts: [], stateCountsKnown: false };
    }
    stateCounts.push({
      state,
      badgeState: stateRoleToBadgeState(stateTone(state)),
      count: raw,
      label: `${raw} ${state}`,
    });
  }
  if (stateCounts.length === 0) {
    return { stateCounts: [], stateCountsKnown: false };
  }
  return { stateCounts, stateCountsKnown: true };
}

export function buildPolicyInstallsModel(
  rows: readonly PolicyInstallStatusRow[],
  total: number | null | undefined,
  nowOrCounts: Date | Partial<Record<PolicyInstallState, number>> | null = new Date(),
  countsArg?: Partial<Record<PolicyInstallState, number>> | null,
): PolicyInstallsModel {
  const now = nowOrCounts instanceof Date ? nowOrCounts : new Date();
  const counts = nowOrCounts instanceof Date ? countsArg : nowOrCounts;

  const shown = rows.slice(0, POLICY_INSTALLS_SHOWN).map((row): PolicyInstallLine => {
    const word = policyInstallWord(row.state);
    const tone = policyInstallTone(word);
    const ha = haParityWord(row.ha_parity);
    const contextTrimmed = row.context?.trim();
    return {
      key: row.row_id,
      hostname: row.hostname,
      vendorLabel: vendorLabel(row.vendor),
      contextLabel: contextTrimmed ? `VS ${contextTrimmed}` : null,
      target: [row.container_name, row.package_name].filter((s) => s && s.length > 0).join(" · "),
      word,
      tone,
      badgeState: stateRoleToBadgeState(tone),
      haWord: ha ? ha.word : null,
      haTone: ha ? ha.tone : null,
      haBadgeState: ha ? ha.badgeState : null,
      policyName: row.policy_name?.trim() || null,
      installedAt: row.installed_at,
      installedAge: row.installed_at ? relativeAge(row.installed_at, now) : null,
      lastChangeAt: row.last_change_at ?? null,
      reason: row.reason?.trim() || null,
    };
  });
  const totalKnown = typeof total === "number" && Number.isFinite(total);
  const totalCount = totalKnown ? Math.max(total, rows.length) : null;
  const notEvaluatedCount = rows.filter((r) => {
    const w = policyInstallWord(r.state);
    return w === POLICY_INSTALL_PENDING || w === "UNKNOWN";
  }).length;
  const { stateCounts, stateCountsKnown } = buildStateCounts(counts);
  const newestEvidenceAt = newestIso(rows.flatMap((r) => [r.installed_at, r.last_change_at]));

  return {
    lines: shown,
    moreCount: totalCount === null ? 0 : Math.max(0, totalCount - shown.length),
    totalCount,
    totalKnown,
    notEvaluatedCount,
    stateCounts,
    stateCountsKnown,
    newestEvidenceAt,
  };
}
