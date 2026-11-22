import type { SshHostKeyObservation, SshHostKeyView } from "../auth/adminApi";
import type { BadgeState } from "./estateModel";

/** Rows printed before the "+N more" footer. */
export const SSH_TRUST_ROW_CAP = 5;

/** The standard view's SSH trust page (Administration). Read-only Estate links only ever point here. */
export const SSH_TRUST_STANDARD_HREF = "?screen=administration&tab=ssh-trust&standard=1";

/** `ok` is false when the read failed or returned no usable view: that is UNKNOWN, never OK. */
export type SshTrustRead = { readonly ok: true; readonly view: SshHostKeyView } | { readonly ok: false };

export interface SshTrustRow {
  readonly observationId: string;
  readonly badge: BadgeState;
  readonly word: string;
  /** The reported seen count, or null when the observation did not report one (never shown as 0). */
  readonly seenCount: number | null;
  /** The observation's own last-seen time: the evidence age for this row. */
  readonly lastSeen: string | null;
}

export interface SshTrustModel {
  readonly badge: BadgeState;
  readonly badgeWord: string;
  readonly headline: string;
  /** The trust mode word, or null when the read failed. */
  readonly mode: "WARN" | "STRICT" | null;
  readonly counts: {
    readonly observations: number;
    readonly open: number;
    readonly rejected: number;
    readonly accepted: number;
    readonly unrecognised: number;
  };
  /** Non-accepted rows first (OPEN, then REJECTED), then ACCEPTED; capped at SSH_TRUST_ROW_CAP. */
  readonly rows: readonly SshTrustRow[];
  /** Rows left out of `rows` by the cap. */
  readonly hiddenCount: number;
}

const RANK: Readonly<Record<string, number>> = { OPEN: 0, REJECTED: 1, ACCEPTED: 2 };

function rowOf(o: SshHostKeyObservation): SshTrustRow {
  const seen = typeof o.seen_count === "number" && Number.isFinite(o.seen_count) ? o.seen_count : null;
  const lastSeen = typeof o.last_seen === "string" && o.last_seen !== "" ? o.last_seen : null;
  if (o.status === "OPEN") return { observationId: o.observation_id, badge: "watch", word: "OPEN", seenCount: seen, lastSeen };
  if (o.status === "REJECTED") return { observationId: o.observation_id, badge: "act", word: "REJECTED", seenCount: seen, lastSeen };
  if (o.status === "ACCEPTED") return { observationId: o.observation_id, badge: "ok", word: "ACCEPTED", seenCount: seen, lastSeen };
  return { observationId: o.observation_id, badge: "unk", word: "UNKNOWN", seenCount: seen, lastSeen };
}

function newestFirst(a: SshTrustRow, b: SshTrustRow): number {
  const ta = a.lastSeen ? Date.parse(a.lastSeen) : Number.NaN;
  const tb = b.lastSeen ? Date.parse(b.lastSeen) : Number.NaN;
  const va = Number.isNaN(ta) ? -Infinity : ta;
  const vb = Number.isNaN(tb) ? -Infinity : tb;
  return vb - va;
}

/**
 * Pure mapping from the SSH host-key view to the dossier block. A failed read is UNKNOWN, zero observations is
 * UNKNOWN, and an unrecognised status is UNKNOWN: none of them ever reads as "Fine". Carries counts and status
 * words only; no fingerprint, key material, address or hostname reaches this model.
 */
export function buildSshTrustModel(read: SshTrustRead): SshTrustModel {
  // A failed read, or a view without an observations array (malformed), is the same UNKNOWN model.
  const view = read.ok && Array.isArray(read.view.observations) ? read.view : null;
  if (view === null) {
    return {
      badge: "unk",
      badgeWord: "UNKNOWN",
      headline: "SSH host-key status unavailable",
      mode: null,
      counts: { observations: 0, open: 0, rejected: 0, accepted: 0, unrecognised: 0 },
      rows: [],
      hiddenCount: 0,
    };
  }
  const mode = view.mode === "STRICT" ? "STRICT" : view.mode === "WARN" ? "WARN" : null;
  const all = view.observations.map(rowOf);
  const sorted = [...all].sort((a, b) => {
    const ra = RANK[a.word] ?? 3;
    const rb = RANK[b.word] ?? 3;
    return ra !== rb ? ra - rb : newestFirst(a, b);
  });
  const counts = {
    observations: all.length,
    open: all.filter((r) => r.word === "OPEN").length,
    rejected: all.filter((r) => r.word === "REJECTED").length,
    accepted: all.filter((r) => r.word === "ACCEPTED").length,
    unrecognised: all.filter((r) => r.word === "UNKNOWN").length,
  };
  const rows = sorted.slice(0, SSH_TRUST_ROW_CAP);
  const hiddenCount = sorted.length - rows.length;

  let badge: BadgeState;
  let badgeWord: string;
  let headline: string;
  if (mode === null || counts.observations === 0) {
    badge = "unk";
    badgeWord = "UNKNOWN";
    headline = counts.observations === 0 ? "No SSH host-key observation for this unit" : "SSH trust mode unknown";
  } else if (counts.unrecognised > 0) {
    badge = "unk";
    badgeWord = "UNKNOWN";
    headline = `${counts.unrecognised} observation${counts.unrecognised === 1 ? "" : "s"} with an unrecognised status`;
  } else if (counts.rejected > 0) {
    badge = "act";
    badgeWord = "REJECTED";
    headline = `${counts.rejected} rejected observation${counts.rejected === 1 ? "" : "s"}`;
  } else if (counts.open > 0) {
    badge = "watch";
    badgeWord = "OPEN";
    headline = `${counts.open} open observation${counts.open === 1 ? "" : "s"}, not yet accepted`;
  } else {
    badge = "ok";
    badgeWord = "ACCEPTED";
    headline = "All observations accepted";
  }

  return { badge, badgeWord, headline, mode, counts, rows, hiddenCount };
}
