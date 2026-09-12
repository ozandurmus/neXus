import { m3 } from "../theme/m3Theme";

/**
 * The canvas's chip-colour vocabulary (`M3Components`' "State vocabulary"
 * card): one tone per state class, shared so every screen and preview picks
 * a colour from the same table instead of redefining it per file. Red
 * (`bad`) is reserved for real fault, unsafe drift, out-of-sync and failure;
 * an expected member difference or an intentional override never uses it.
 */
export type Tone = "neutral" | "ok" | "mem" | "warn" | "attn" | "bad";

export const TONE_COLORS: Record<Tone, { readonly bg: string; readonly fg: string }> = {
  neutral: { bg: m3.scHigh, fg: m3.onSurfaceVar },
  ok: { bg: m3.successContainer, fg: m3.onSuccessContainer },
  mem: { bg: m3.memberContainer, fg: m3.onMemberContainer },
  warn: { bg: m3.warningContainer, fg: m3.onWarningContainer },
  attn: { bg: m3.attentionContainer, fg: "#2b1700" },
  bad: { bg: m3.errorContainer, fg: m3.onErrorContainer },
};

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
