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
