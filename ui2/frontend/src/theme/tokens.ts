import { DARK, LIGHT, MONO, m3, type Density } from "./m3Theme";

export { m3 };

/**
 * The neXus design language, Phase 3.1 (UI product review 2026-10-18 §6; `docs/design/UI_DESIGN_LANGUAGE.md`).
 * Everything a screen may pick from lives here: five type steps, a spacing scale, three radii, the colour-role table
 * and the two density modes. A screen never invents a sixth font size or a colour outside the role table; the
 * vendor identity swatches (`m3.cp`, `m3.vsx`, `m3.pan`) are identity, not state, and stay in `m3Theme`.
 */

// -------------------------------------------------------------------------------------------------------------------
// Type scale: five steps, nothing else
// -------------------------------------------------------------------------------------------------------------------

export type TypeStep = "pageTitle" | "section" | "body" | "label" | "mono";

export interface TypeSpec {
  readonly fontSize: number;
  readonly lineHeight: string;
  readonly fontWeight: number;
  readonly fontFamily?: string;
  readonly letterSpacing?: string;
  readonly textTransform?: "uppercase";
}

/**
 * Page title 22/28 semibold · section or card title 16/22 semibold · body 14/20 · label and pill 12/16 (uppercase
 * tracked for a column or card label, sentence case for a pill: `labelUpper`) · mono 13/18 for addresses, hashes,
 * timestamps and versions. Every value of one kind uses one step: a model and a software version in the same row
 * are both mono.
 */
export const TYPE: Readonly<Record<TypeStep, TypeSpec>> = {
  pageTitle: { fontSize: 22, lineHeight: "28px", fontWeight: 600 },
  section: { fontSize: 16, lineHeight: "22px", fontWeight: 600 },
  body: { fontSize: 14, lineHeight: "20px", fontWeight: 400 },
  label: { fontSize: 12, lineHeight: "16px", fontWeight: 500 },
  mono: { fontSize: 13, lineHeight: "18px", fontWeight: 400, fontFamily: MONO },
};

/** The label step as a column or card label: uppercase, tracked, muted by the caller. */
export const LABEL_UPPER: TypeSpec = { ...TYPE.label, fontWeight: 600, letterSpacing: "0.04em", textTransform: "uppercase" };

/** The one numeric display size (a KPI figure): tabular, semibold. Not a text step -- only a number ever uses it. */
export const KPI_FIGURE = { fontSize: 28, lineHeight: "32px", fontWeight: 600, fontVariantNumeric: "tabular-nums" } as const;

// -------------------------------------------------------------------------------------------------------------------
// Spacing and radius
// -------------------------------------------------------------------------------------------------------------------

/** A 4 px scale. Gutters: 8 (compact) and 16 (comfortable); card padding 16; section gap 24. */
export const SPACE = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 } as const;

/** Three radii: a pill or input 8, a card 10, a button 20 (full round at 40 px). */
export const RADIUS = { pill: 8, card: 10, button: 20 } as const;

// -------------------------------------------------------------------------------------------------------------------
// Colour roles: paired with a word, always
// -------------------------------------------------------------------------------------------------------------------

/**
 * `bad` red: Not ready, Failed, Certificate expired, critical failing. `warn` amber: stale, partial, not confirmed,
 * Last collection failed. `ok` green: Live, Ready, Up, Completed, Compliant -- never on a count, never on an absence.
 * `neutral` slate: every count, every 0, Not applicable, Not read yet, locked. `unknown` hatched grey: UNKNOWN, Not
 * assessed, Not evidenced. `role` black outline or fill: ACTIVE / STANDBY, PRIMARY / PASSIVE. `brand` blue: links,
 * the one primary action, the selected tab -- never a state. `mem` (member-specific) and `attn` (difference observed)
 * are the two alignment tones the Configuration plane keeps beside the seven roles.
 */
export type StateRole = "bad" | "warn" | "ok" | "neutral" | "unknown" | "role" | "brand" | "mem" | "attn";

export interface RoleColors {
  readonly bg: string;
  readonly fg: string;
  readonly border: string;
  /** The hatched fill for a value that is not evidenced (unknown). */
  readonly hatched?: boolean;
}

/** The colour of each role as CSS variables, so light and dark resolve from the one attribute on <html>. */
export const ROLE_COLORS: Readonly<Record<StateRole, RoleColors>> = {
  bad: { bg: m3.errorContainer, fg: m3.onErrorContainer, border: "transparent" },
  warn: { bg: m3.warningContainer, fg: m3.onWarningContainer, border: "transparent" },
  ok: { bg: m3.successContainer, fg: m3.onSuccessContainer, border: "transparent" },
  neutral: { bg: m3.scHigh, fg: m3.neutralInk, border: "transparent" },
  unknown: { bg: m3.scHigh, fg: m3.neutralInk, border: m3.outlineVar, hatched: true },
  role: { bg: "transparent", fg: m3.onSurface, border: m3.outline },
  brand: { bg: m3.primaryContainer, fg: m3.onPrimaryContainer, border: "transparent" },
  mem: { bg: m3.memberContainer, fg: m3.onMemberContainer, border: "transparent" },
  attn: { bg: m3.attentionContainer, fg: m3.onAttentionContainer, border: "transparent" },
};

/** The resolved hex values of every role in one mode (for the reference document and the contrast tests). */
export function roleValues(mode: "light" | "dark"): Readonly<Record<StateRole, { readonly bg: string; readonly fg: string }>> {
  const t = mode === "dark" ? DARK : LIGHT;
  return {
    bad: { bg: t.errorContainer, fg: t.onErrorContainer },
    warn: { bg: t.warningContainer, fg: t.onWarningContainer },
    ok: { bg: t.successContainer, fg: t.onSuccessContainer },
    neutral: { bg: t.scHigh, fg: t.neutralInk },
    unknown: { bg: t.scHigh, fg: t.neutralInk },
    role: { bg: t.scLowest, fg: t.onSurface },
    brand: { bg: t.primaryContainer, fg: t.onPrimaryContainer },
    mem: { bg: t.memberContainer, fg: t.onMemberContainer },
    attn: { bg: t.attentionContainer, fg: t.onAttentionContainer },
  };
}

/** The hatch for the unknown role: two thin diagonal lines of the outline colour over the neutral fill. */
export const UNKNOWN_HATCH = `repeating-linear-gradient(135deg, transparent 0 5px, ${m3.outlineVar} 5px 6px)`;

/**
 * The words whose role is fixed by the table, so a caller that passes a neutral tone with one of these words still
 * gets the hatched unknown. Nothing here turns a bad or warn word into something softer.
 */
const UNKNOWN_WORDS: ReadonlySet<string> = new Set(["unknown", "not assessed", "not evidenced", "not evaluated", "outcome unknown"]);

/** True when the word is one the table hatches: UNKNOWN, Not assessed, Not evidenced, Not evaluated. */
export function isUnknownWord(word: string): boolean {
  return UNKNOWN_WORDS.has(word.trim().toLowerCase());
}

// -------------------------------------------------------------------------------------------------------------------
// Density: two modes, chosen per screen type, never by a user toggle
// -------------------------------------------------------------------------------------------------------------------

export type { Density };

export interface DensitySpec {
  /** The gap between cells and cards. */
  readonly gutter: number;
  /** The height of one table row. */
  readonly row: number;
  /** Vertical cell padding that makes a one-line row land on `row`. */
  readonly cellY: number;
}

/** Comfortable: object headers, summary cards (16 px gutters, 44 px rows). Compact: list screens and data tables (8 px gutters, 32 px one-line rows, mono for machine values). */
export const DENSITY: Readonly<Record<Density, DensitySpec>> = {
  comfortable: { gutter: SPACE.lg, row: 44, cellY: 8 },
  compact: { gutter: SPACE.sm, row: 32, cellY: 4 },
};

/** The screen types the review names (§3) and the density each one gets. */
export type ScreenType = "summary" | "list" | "object" | "tool";

export const SCREEN_DENSITY: Readonly<Record<ScreenType, Density>> = {
  summary: "comfortable",
  object: "comfortable",
  list: "compact",
  tool: "comfortable",
};
