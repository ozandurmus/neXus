/**
 * Colour and typography tokens for the alternative Estate view (Opus round-2 design reference, PO 2026-11-19).
 * Scoped under `--ev-*` custom properties so nothing in the standard Material 3 token set (`--nx-*`) changes.
 *
 * Every text pair satisfies >= 4.5:1 contrast on both page (`bg`) and card (`surface`) backgrounds, and every tile
 * fill satisfies >= 3.0:1 against the card surface in both light and dark modes.
 */
export type EstateTokenName =
  | "bg" | "surface" | "surface2" | "sunk"
  | "ink" | "ink2" | "ink3"
  | "line" | "lineStrong"
  | "accent" | "accentInk" | "accentBg"
  | "act" | "actBg"
  | "watch" | "watchBg"
  | "ok" | "okBg"
  | "unk" | "unkBg"
  | "tAct" | "tWatch" | "tOk" | "tUnk" | "tUnk2" | "tNa" | "tGlyph"
  | "age1" | "age2" | "age3" | "age4"
  | "shadow";

export const ESTATE_LIGHT: Readonly<Record<EstateTokenName, string>> = {
  bg: "#F4F3EF",
  surface: "#FFFFFF",
  surface2: "#EFEDE7",
  sunk: "#E9E7E0",
  ink: "#15171A",
  ink2: "#4B5058",
  ink3: "#575C65",
  line: "#D9D6CD",
  lineStrong: "#B4B0A5",
  accent: "#1D4ED8",
  accentInk: "#FFFFFF",
  accentBg: "#E6ECFB",
  act: "#B42318",
  actBg: "#FDECEA",
  watch: "#7A4900",
  watchBg: "#FDF3DC",
  ok: "#146C43",
  okBg: "#E5F4EC",
  unk: "#52419F",
  unkBg: "#EEEBFA",
  tAct: "#C8372D",
  tWatch: "#A86800",
  tOk: "#1E7E4E",
  tUnk: "#6355B5",
  tUnk2: "#E3DFF6",
  tNa: "#8A867C",
  tGlyph: "#FFFFFF",
  age1: "#1E7E4E",
  age2: "#547F26",
  age3: "#A86800",
  age4: "#C8372D",
  shadow: "0 1px 2px rgba(20, 20, 20, 0.06), 0 4px 16px rgba(20, 20, 20, 0.05)",
};

export const ESTATE_DARK: Readonly<Record<EstateTokenName, string>> = {
  bg: "#0D0F12",
  surface: "#15191E",
  surface2: "#1C2128",
  sunk: "#101317",
  ink: "#ECEAE4",
  ink2: "#AEB4BD",
  ink3: "#9098A3",
  line: "#2A3038",
  lineStrong: "#454D58",
  accent: "#8AB0FF",
  accentInk: "#0D0F12",
  accentBg: "#1B2742",
  act: "#FF8B7E",
  actBg: "#3A1715",
  watch: "#F0B95A",
  watchBg: "#33260C",
  ok: "#5FD39A",
  okBg: "#0F2E20",
  unk: "#B9B0FF",
  unkBg: "#241F42",
  tAct: "#E85D51",
  tWatch: "#D99A2B",
  tOk: "#33A66D",
  tUnk: "#8E84E0",
  tUnk2: "#2A2550",
  tNa: "#6E7785",
  tGlyph: "#111111",
  age1: "#33A66D",
  age2: "#7DAA45",
  age3: "#D99A2B",
  age4: "#E85D51",
  shadow: "0 1px 2px rgba(0, 0, 0, 0.4)",
};

const cssVarName = (key: EstateTokenName): string =>
  `--ev-${key.replace(/[A-Z0-9]/g, (c) => `-${c.toLowerCase()}`)}`;

export const ev = Object.fromEntries(
  (Object.keys(ESTATE_LIGHT) as EstateTokenName[]).map((k) => [k, `var(${cssVarName(k)})`]),
) as Readonly<Record<EstateTokenName, string>>;

export function estateCssVariables(palette: Readonly<Record<EstateTokenName, string>>): Record<string, string> {
  return Object.fromEntries(
    (Object.keys(palette) as EstateTokenName[]).map((k) => [cssVarName(k), palette[k]]),
  );
}

export const ESTATE_SANS =
  '-apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, Roboto, "Helvetica Neue", Arial, sans-serif';
export const ESTATE_MONO =
  'ui-monospace, "SF Mono", SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace';

export const ESTATE_UNKNOWN_HATCH = `repeating-linear-gradient(135deg, ${ev.tUnk} 0 3px, ${ev.tUnk2} 3px 6px)`;
export const ESTATE_BADGE_UNKNOWN_HATCH = `repeating-linear-gradient(135deg, ${ev.unkBg} 0 4px, transparent 4px 8px)`;
