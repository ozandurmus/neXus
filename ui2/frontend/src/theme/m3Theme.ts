import { createTheme, type Theme } from "@mui/material/styles";

/**
 * The neXus design tokens (UI_VISUAL_REVIEW_2026_09_23_FABLE.md §5, adopted 2026-09-23): the Palette study's
 * neutrals, hairlines and mono usage, the M3 study's state vocabulary, and the status and categorical palettes
 * already in force. Two token sets, light and dark; every screen reads a token through `m3.<name>`, which is a
 * CSS custom property, so the whole product switches mode without re-rendering a single colour decision.
 *
 * `cp`, `vsx` and `pan` are vendor identity, never state: nothing may infer a vendor, a state or a verdict
 * from a colour. `*Ink` tokens are the status colours darkened (light) or lifted (dark) for text, because the
 * status fills fail as text on white (warning 1.9:1).
 */
type TokenName =
  | "primary" | "onPrimary" | "primaryContainer" | "onPrimaryContainer"
  | "secondaryContainer" | "onSecondaryContainer"
  | "surface" | "surfaceDim" | "scLowest" | "scLow" | "sc" | "scHigh" | "scHighest"
  | "onSurface" | "onSurfaceVar" | "outline" | "outlineVar"
  | "error" | "onError" | "errorContainer" | "onErrorContainer"
  | "success" | "onSuccess" | "successContainer" | "onSuccessContainer"
  | "warning" | "onWarning" | "warningContainer" | "onWarningContainer"
  | "member" | "memberContainer" | "onMemberContainer"
  | "attention" | "attentionContainer" | "onAttentionContainer"
  | "goodInk" | "warningInk" | "seriousInk" | "criticalInk" | "neutralInk"
  | "cp" | "vsx" | "pan"
  | "e1" | "e2";

export const LIGHT: Record<TokenName, string> = {
  primary: "#3457d5",
  onPrimary: "#ffffff",
  primaryContainer: "#eaeefb",
  onPrimaryContainer: "#1e2f7a",
  secondaryContainer: "#eaeefb",
  onSecondaryContainer: "#1e2f7a",
  surface: "#f5f6f8",
  surfaceDim: "#e3e7ec",
  scLowest: "#ffffff",
  scLow: "#fafbfc",
  sc: "#f1f3f6",
  scHigh: "#e3e7ec",
  scHighest: "#cfd5dd",
  onSurface: "#171b22",
  onSurfaceVar: "#5f6978",
  outline: "#9aa3b0",
  outlineVar: "#e3e7ec",
  error: "#d03b3b",
  onError: "#ffffff",
  errorContainer: "#fbe7e7",
  onErrorContainer: "#8f1f1f",
  success: "#0ca30c",
  onSuccess: "#ffffff",
  successContainer: "#e3f5e3",
  onSuccessContainer: "#087a08",
  warning: "#fab219",
  onWarning: "#171b22",
  warningContainer: "#fdf1d6",
  onWarningContainer: "#8a5a00",
  member: "#7a5c12",
  memberContainer: "#fbf4dc",
  onMemberContainer: "#7a5c12",
  attention: "#ec835a",
  attentionContainer: "#fce8df",
  onAttentionContainer: "#b0421a",
  goodInk: "#087a08",
  warningInk: "#8a5a00",
  seriousInk: "#b0421a",
  criticalInk: "#d03b3b",
  neutralInk: "#5b6473",
  cp: "#c2338a",
  vsx: "#4a3aa7",
  pan: "#d9731a",
  e1: "none",
  e2: "0 4px 14px rgba(15,23,42,0.08)",
};

export const DARK: Record<TokenName, string> = {
  primary: "#7b9cff",
  onPrimary: "#0f1216",
  primaryContainer: "#1f2a4d",
  onPrimaryContainer: "#c9d5ff",
  secondaryContainer: "#1f2a4d",
  onSecondaryContainer: "#dfe5f5",
  surface: "#0f1216",
  surfaceDim: "#0b0e11",
  scLowest: "#161a20",
  scLow: "#181d24",
  sc: "#1c2129",
  scHigh: "#272e38",
  scHighest: "#333b47",
  onSurface: "#eef1f5",
  onSurfaceVar: "#a5adba",
  outline: "#5b6473",
  outlineVar: "#272e38",
  error: "#ef6a6a",
  onError: "#0f1216",
  errorContainer: "#3a1d1f",
  onErrorContainer: "#ffb4b4",
  success: "#2fc12f",
  onSuccess: "#0f1216",
  successContainer: "#16301a",
  onSuccessContainer: "#7ee07e",
  warning: "#ffc445",
  onWarning: "#0f1216",
  warningContainer: "#3a2f12",
  onWarningContainer: "#ffd98a",
  member: "#e3c46f",
  memberContainer: "#33291a",
  onMemberContainer: "#f0d68e",
  attention: "#f39a74",
  attentionContainer: "#3a2419",
  onAttentionContainer: "#ffbfa3",
  goodInk: "#7ee07e",
  warningInk: "#ffd98a",
  seriousInk: "#ffbfa3",
  criticalInk: "#ff9b9b",
  neutralInk: "#a5adba",
  cp: "#e06ab0",
  vsx: "#9a8fe0",
  pan: "#f0a060",
  e1: "none",
  e2: "0 4px 14px rgba(0,0,0,0.4)",
};

const cssName = (name: string) => `--nx-${name.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;

/** Every token as `var(--nx-…)`, so a mode switch is one attribute on <html>. */
export const m3 = Object.fromEntries(
  (Object.keys(LIGHT) as TokenName[]).map((name) => [name, `var(${cssName(name)})`]),
) as Record<TokenName, string>;

/** The custom-property declarations for one token set (GlobalStyles in App). */
export function cssVariables(tokens: Record<TokenName, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(tokens).map(([name, value]) => [cssName(name), value]));
}

export const MONO = '"JetBrains Mono", "SFMono-Regular", Menlo, Consolas, monospace';

export type ColorMode = "light" | "dark";
export type Density = "comfortable" | "compact";

/**
 * The MUI theme for one mode and density. MUI computes contrast and hover shades from real colours, so the
 * palette takes the hex values; everything a screen writes in `sx` goes through `m3` (the variables).
 * Type scale (review §4): display 32/40, headline 22/28, title 16/24, body 13/20, label 11/16, mono 12/18.
 */
export function m3ThemeFor(mode: ColorMode = "light", density: Density = "comfortable"): Theme {
  const t = mode === "dark" ? DARK : LIGHT;
  const compact = density === "compact";
  return createTheme({
    palette: {
      mode,
      primary: { main: t.primary, contrastText: t.onPrimary },
      error: { main: t.error, contrastText: t.onError },
      success: { main: t.success },
      warning: { main: t.warning },
      background: { default: t.surface, paper: t.scLowest },
      text: { primary: t.onSurface, secondary: t.onSurfaceVar },
      divider: t.outlineVar,
    },
    shape: { borderRadius: 10 },
    typography: {
      fontFamily: ['Roboto', '"Helvetica Neue"', 'Arial', 'sans-serif'].join(","),
      fontSize: 13,
      h1: { fontSize: 32, lineHeight: "40px", fontWeight: 600, letterSpacing: 0 },
      h2: { fontSize: 22, lineHeight: "28px", fontWeight: 500, letterSpacing: 0 },
      h3: { fontSize: 18, fontWeight: 500, letterSpacing: 0 },
      h4: { fontSize: 16, lineHeight: "24px", fontWeight: 500, letterSpacing: 0 },
      body1: { fontSize: 13, lineHeight: "20px" },
      body2: { fontSize: 12, lineHeight: "18px", letterSpacing: "0.2px" },
      caption: { fontSize: 11, lineHeight: "16px" },
      button: { fontSize: 13, fontWeight: 500, letterSpacing: "0.1px", textTransform: "none" },
    },
    components: {
      MuiCssBaseline: { styleOverrides: { body: { fontVariantNumeric: "tabular-nums" } } },
      MuiPaper: { styleOverrides: { root: { backgroundImage: "none" } } },
      MuiTableCell: {
        styleOverrides: {
          root: { borderColor: t.outlineVar, paddingTop: compact ? 4 : 8, paddingBottom: compact ? 4 : 8 },
          head: { fontSize: 11, lineHeight: "16px", letterSpacing: "0.04em", textTransform: "uppercase", fontWeight: 600, color: t.onSurfaceVar },
        },
      },
      MuiTableRow: { styleOverrides: { root: { height: compact ? 36 : 44 } } },
    },
  });
}

/** Light, comfortable -- kept for callers and tests that render outside the App shell. */
export const m3Theme = m3ThemeFor("light", "comfortable");

/**
 * Declares both token sets on the document once at start-up, so every surface -- the login screen outside the
 * app shell included -- resolves `var(--nx-…)`. The saved mode is applied before first paint.
 */
export function installTokenStyles(doc: Document = document): void {
  if (doc.getElementById("nx-tokens")) return;
  const decl = (tokens: Record<TokenName, string>) =>
    Object.entries(cssVariables(tokens)).map(([k, v]) => `${k}:${v};`).join("");
  const style = doc.createElement("style");
  style.id = "nx-tokens";
  style.textContent = `:root{${decl(LIGHT)}}:root[data-theme='dark']{${decl(DARK)}color-scheme:dark;}`;
  doc.head.appendChild(style);
  try {
    const wall = new URLSearchParams(doc.location?.search ?? "").get("wall") === "1";
    doc.documentElement.dataset.theme = wall || window.localStorage.getItem("nx.mode") === "dark" ? "dark" : "light";
  } catch {
    doc.documentElement.dataset.theme = "light";
  }
}
