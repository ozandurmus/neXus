import { createTheme } from "@mui/material/styles";

/**
 * The neXus 2026 design-refresh Material 3 scheme.
 *
 * These values are taken from the Product Owner's own design canvas
 * ("neXus 2026 Design Refresh", the `M3*` artboards) — they are not the M3
 * baseline palette and not a guess. The first shell shipped hand-written HTML
 * with the published baseline scheme, which matched neither the canvas nor the
 * contract's React/MUI requirement; this file is the correction.
 *
 * `cp`, `vsx` and `pan` are vendor accents from the same canvas. They are
 * presentation only: a colour never carries evidence meaning, and nothing may
 * infer a vendor, a state or a verdict from one.
 */
export const m3 = {
  primary: "#365CCE",
  onPrimary: "#ffffff",
  primaryContainer: "#E0E7FF",
  onPrimaryContainer: "#1E3A8A",
  secondaryContainer: "#EEF2FF",
  onSecondaryContainer: "#1E293B",
  surface: "#F4F6FB",
  surfaceDim: "#E2E8F0",
  scLowest: "#ffffff",
  scLow: "#F8FAFC",
  sc: "#F1F5F9",
  scHigh: "#E2E8F0",
  scHighest: "#CBD5E1",
  onSurface: "#0F172A",
  onSurfaceVar: "#475569",
  outline: "#94A3B8",
  outlineVar: "#E2E8F0",
  error: "#DC2626",
  onError: "#ffffff",
  errorContainer: "#FEE2E2",
  onErrorContainer: "#991B1B",
  success: "#16A34A",
  onSuccess: "#ffffff",
  successContainer: "#DCFCE7",
  onSuccessContainer: "#15803D",
  warning: "#D97706",
  onWarning: "#ffffff",
  warningContainer: "#FEF3C7",
  onWarningContainer: "#B45309",
  member: "#CA8A04",
  memberContainer: "#FEF9C3",
  onMemberContainer: "#854D0E",
  attention: "#EA580C",
  attentionContainer: "#FFEDD5",
  onAttentionContainer: "#9A3412",
  cp: "#7A3366",
  vsx: "#3D4C7A",
  pan: "#94441E",
  e1: "0 1px 3px rgba(0,0,0,0.04), 0 1px 2px rgba(0,0,0,0.02)",
  e2: "0 4px 6px -1px rgba(0,0,0,0.06), 0 2px 4px -1px rgba(0,0,0,0.03)",
} as const;

export const m3Theme = createTheme({
  palette: {
    primary: { main: m3.primary, contrastText: m3.onPrimary },
    error: { main: m3.error, contrastText: m3.onError },
    success: { main: m3.success },
    warning: { main: m3.warning },
    background: { default: m3.surface, paper: m3.scLow },
    text: { primary: m3.onSurface, secondary: m3.onSurfaceVar },
    divider: m3.outlineVar,
  },
  shape: { borderRadius: 16 },
  typography: {
    fontFamily: ['Roboto', '"Helvetica Neue"', 'Arial', 'sans-serif'].join(","),
    fontSize: 14,
    // The canvas's own type scale, name for name.
    h1: { fontSize: 36, lineHeight: "44px", letterSpacing: 0 },        // disp-s
    h2: { fontSize: 22, fontWeight: 400, letterSpacing: 0 },           // head-l
    h3: { fontSize: 18, fontWeight: 500, letterSpacing: 0 },           // title-l
    h4: { fontSize: 16, fontWeight: 500, letterSpacing: "0.15px" },    // title-m
    body1: { fontSize: 14, lineHeight: 1.43 },
    body2: { fontSize: 12, letterSpacing: "0.4px" },                   // body-s
    button: { fontSize: 14, fontWeight: 500, letterSpacing: "0.1px", textTransform: "none" },
  },
  components: {
    MuiPaper: { styleOverrides: { root: { backgroundImage: "none" } } },
  },
});
