import { ROLE_COLORS, type StateRole } from "../theme/tokens";

/**
 * The product's state vocabulary is the colour-role table in `theme/tokens.ts` (design language 2026-10-19): one
 * role per state class, shared so every screen and preview picks a colour from the same table instead of redefining
 * it per file. Red (`bad`) is reserved for real fault, unsafe drift, out-of-sync and failure; an expected member
 * difference or an intentional override never uses it. `Tone` is the older name of the same union.
 */
export type Tone = StateRole;

export const TONE_COLORS: Record<Tone, { readonly bg: string; readonly fg: string }> = Object.fromEntries(
  (Object.keys(ROLE_COLORS) as StateRole[]).map((role) => [role, { bg: ROLE_COLORS[role].bg, fg: ROLE_COLORS[role].fg }]),
) as Record<Tone, { readonly bg: string; readonly fg: string }>;

/** The alignment classification from the Overview and Configuration frames. */
export type AlignmentState =
  | "Aligned"
  | "Member-specific"
  | "Local override"
  | "Difference observed"
  | "Effective drift"
  | "Out of sync";

export const ALIGNMENT_STATES: readonly AlignmentState[] = [
  "Aligned",
  "Member-specific",
  "Local override",
  "Difference observed",
  "Effective drift",
  "Out of sync",
];

export const ALIGNMENT_STATE_TONE: Record<AlignmentState, Tone> = {
  "Aligned": "ok",
  "Member-specific": "mem",
  "Local override": "warn",
  "Difference observed": "attn",
  "Effective drift": "bad",
  "Out of sync": "bad",
};

/** The inventory liveness vocabulary from the `M3Inventory` frame. */
export const LIVENESS_TONE: Record<"Live" | "Stale" | "No live data", Tone> = {
  "Live": "ok",
  "Stale": "warn",
  "No live data": "neutral",
};
