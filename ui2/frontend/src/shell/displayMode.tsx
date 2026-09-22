import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import GlobalStyles from "@mui/material/GlobalStyles";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";

import { DARK, LIGHT, cssVariables, m3ThemeFor, type ColorMode, type Density } from "../theme/m3Theme";

/**
 * Per-viewer display preferences (review §5–§6): colour mode, row density, and the NOC wall mode.
 * Preferences only -- nothing here changes what is fetched, shown or allowed. Stored in localStorage as a
 * convenience; a blocked or empty store simply gives the defaults. `?wall=1` forces the wall layout (dark,
 * no rail, no header actions) for an unattended display; it is a URL, so it survives a kiosk reload.
 */
export interface DisplayMode {
  readonly mode: ColorMode;
  readonly density: Density;
  readonly wall: boolean;
  readonly setMode: (m: ColorMode) => void;
  readonly setDensity: (d: Density) => void;
}

const DisplayModeContext = createContext<DisplayMode>({
  mode: "light", density: "comfortable", wall: false, setMode: () => {}, setDensity: () => {},
});

export const useDisplayMode = () => useContext(DisplayModeContext);

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // preference only
  }
}

export function isWall(search: string): boolean {
  return new URLSearchParams(search).get("wall") === "1";
}

export function DisplayModeProvider({ search, children }: { readonly search: string; readonly children: ReactNode }) {
  const wall = isWall(search);
  const [mode, setModeState] = useState<ColorMode>(() => (read("nx.mode") === "dark" ? "dark" : "light"));
  const [density, setDensityState] = useState<Density>(() => (read("nx.density") === "compact" ? "compact" : "comfortable"));
  const effectiveMode: ColorMode = wall ? "dark" : mode;
  const setMode = useCallback((m: ColorMode) => { setModeState(m); write("nx.mode", m); }, []);
  const setDensity = useCallback((d: Density) => { setDensityState(d); write("nx.density", d); }, []);
  useEffect(() => {
    if (typeof document !== "undefined") document.documentElement.dataset.theme = effectiveMode;
  }, [effectiveMode]);
  const theme = useMemo(() => m3ThemeFor(effectiveMode, density), [effectiveMode, density]);
  const value = useMemo(() => ({ mode: effectiveMode, density, wall, setMode, setDensity }), [effectiveMode, density, wall, setMode, setDensity]);
  return (
    <DisplayModeContext.Provider value={value}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <GlobalStyles styles={{
          ":root": cssVariables(LIGHT),
          ":root[data-theme='dark']": { ...cssVariables(DARK), colorScheme: "dark" },
        }} />
        {children}
      </ThemeProvider>
    </DisplayModeContext.Provider>
  );
}
