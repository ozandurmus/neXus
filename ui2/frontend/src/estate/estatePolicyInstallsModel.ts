import type { PolicyInstallState, PolicyInstallStatusRow } from "../auth/adminApi";
import {
  POLICY_INSTALL_STATES,
  parityLabel,
  parityTone,
  stateTone,
} from "../screens/PolicyInstallMonitoringPanel";
import type { StateRole } from "../theme/tokens";
import { relativeAge } from "../shell/time";

/**
 * Pure model for the read-only "Policy installs" section of the Estate Changes screen (standard-view parity).
 * Tone and HA parity words are the standard panel's own mappings (`stateTone`, `parityLabel`, `parityTone`), reused
 * rather than copied. PENDING and UNKNOWN are never shown as zero or OK.
 */

export const POLICY_INSTALLS_SHOWN = 12;

/** Estate-side word for a state the standard panel does not enumerate; not a server enum value. */
export const POLICY_INSTALL_PENDING = "PENDING" as const;

export type PolicyInstallWord = PolicyInstallState | typeof POLICY_INSTALL_PENDING;

export interface PolicyInstallLine {
  readonly key: string;
  readonly hostname: string;
  readonly target: string;
  readonly word: PolicyInstallWord;
  readonly tone: StateRole;
  readonly haWord: string | null;
  readonly haTone: StateRole | null;
  readonly installedAt: string | null;
  readonly installedAge: string | null;
}

export interface PolicyInstallsModel {
  readonly lines: readonly PolicyInstallLine[];
  /** Rows the server reports beyond the ones shown here; never negative. */
  readonly moreCount: number;
  readonly totalCount: number;
  /** Rows whose state is PENDING or UNKNOWN: not evaluated, so never counted as OK or zero. */
  readonly notEvaluatedCount: number;
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

function haParityWord(parity: PolicyInstallStatusRow["ha_parity"]): { word: string; tone: StateRole } | null {
  const word = parityLabel(parity);
  if (word === null) return null;
  return { word, tone: parityTone(parity) };
}

export function buildPolicyInstallsModel(
  rows: readonly PolicyInstallStatusRow[],
  total: number | null | undefined,
  now: Date = new Date(),
): PolicyInstallsModel {
  const shown = rows.slice(0, POLICY_INSTALLS_SHOWN).map((row): PolicyInstallLine => {
    const word = policyInstallWord(row.state);
    const ha = haParityWord(row.ha_parity);
    return {
      key: row.row_id,
      hostname: row.hostname,
      target: [row.container_name, row.package_name].filter((s) => s && s.length > 0).join(" · "),
      word,
      tone: policyInstallTone(word),
      haWord: ha ? ha.word : null,
      haTone: ha ? ha.tone : null,
      installedAt: row.installed_at,
      installedAge: row.installed_at ? relativeAge(row.installed_at, now) : null,
    };
  });
  const totalCount = Math.max(total ?? 0, rows.length);
  const notEvaluatedCount = rows.filter((r) => {
    const w = policyInstallWord(r.state);
    return w === POLICY_INSTALL_PENDING || w === "UNKNOWN";
  }).length;
  return {
    lines: shown,
    moreCount: Math.max(0, totalCount - shown.length),
    totalCount,
    notEvaluatedCount,
  };
}
