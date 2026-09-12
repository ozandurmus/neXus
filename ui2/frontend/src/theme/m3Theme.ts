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
  primary: "#415f91",
  onPrimary: "#ffffff",
  primaryContainer: "#d6e3ff",
  onPrimaryContainer: "#001b3e",
  secondaryContainer: "#dae2f9",
  onSecondaryContainer: "#131c2b",
  surface: "#f9f9ff",
  surfaceDim: "#d9d9e0",
  scLowest: "#ffffff",
  scLow: "#f3f3fa",
  sc: "#eeedf4",
  scHigh: "#e8e7ef",
  scHighest: "#e2e2e9",
  onSurface: "#191c20",
  onSurfaceVar: "#44474e",
  outline: "#74777f",
  outlineVar: "#c4c6d0",
  error: "#ba1a1a",
  onError: "#ffffff",
  errorContainer: "#ffdad6",
  onErrorContainer: "#410002",
  success: "#3d6b3f",
  successContainer: "#c0ecbe",
  onSuccessContainer: "#00210a",
  warning: "#7d5700",
  warningContainer: "#ffdea6",
  onWarningContainer: "#281900",
  member: "#6b5d1f",
  memberContainer: "#f4e3a6",
  onMemberContainer: "#221b00",
  attention: "#8b5000",
  attentionContainer: "#ffdcc2",
  cp: "#8e4585",
  vsx: "#4a4a8f",
  pan: "#8b4513",
  e1: "0 1px 2px rgba(0,0,0,.28), 0 1px 3px 1px rgba(0,0,0,.13)",
  e2: "0 1px 2px rgba(0,0,0,.28), 0 2px 6px 2px rgba(0,0,0,.13)",
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
