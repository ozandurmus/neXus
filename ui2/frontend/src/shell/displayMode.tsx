import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import GlobalStyles from "@mui/material/GlobalStyles";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";

import { DARK, LIGHT, cssVariables, m3ThemeFor, type ColorMode, type Density } from "../theme/m3Theme";
import { SCREEN_DENSITY, type ScreenType } from "../theme/tokens";

/**
 * Per-viewer display preferences (review §5–§6): colour mode and the NOC wall mode. Preferences only -- nothing here
 * changes what is fetched, shown or allowed. Stored in localStorage as a convenience; a blocked or empty store simply
 * gives the defaults. `?wall=1` forces the wall layout (dark, no rail, no header actions) for an unattended display;
 * it is a URL, so it survives a kiosk reload.
 *
 * Density is not a preference (design language 2026-10-19): it is chosen per screen type through `<DensityScope>`
 * -- comfortable for summary, object and tool pages, compact for list screens -- and the context only reports which
 * mode the current scope is in.
 */
export interface DisplayMode {
  readonly mode: ColorMode;
  readonly density: Density;
  readonly wall: boolean;
  readonly setMode: (m: ColorMode) => void;
}

const DisplayModeContext = createContext<DisplayMode>({
  mode: "light", density: "comfortable", wall: false, setMode: () => {},
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
  const effectiveMode: ColorMode = wall ? "dark" : mode;
  const setMode = useCallback((m: ColorMode) => { setModeState(m); write("nx.mode", m); }, []);
  useEffect(() => {
    if (typeof document !== "undefined") document.documentElement.dataset.theme = effectiveMode;
  }, [effectiveMode]);
  const theme = useMemo(() => m3ThemeFor(effectiveMode, "comfortable"), [effectiveMode]);
  const value = useMemo(() => ({ mode: effectiveMode, density: "comfortable" as const, wall, setMode }), [effectiveMode, wall, setMode]);
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

/**
 * The density of one screen, by its type (`tokens.ts` SCREEN_DENSITY) or named outright. Nested inside the provider:
 * the MUI table rows and cell padding under it follow the mode, and `data-density` on the wrapper lets a test or a
 * stylesheet see which mode a screen is in. Outside the provider (a test rendering one screen) it still works, with
 * the light theme.
 */
export function DensityScope({ screenType, density, children }: {
  readonly screenType?: ScreenType;
  readonly density?: Density;
  readonly children: ReactNode;
}) {
  const parent = useDisplayMode();
  const resolved: Density = density ?? (screenType ? SCREEN_DENSITY[screenType] : parent.density);
  const theme = useMemo(() => m3ThemeFor(parent.mode, resolved), [parent.mode, resolved]);
  const value = useMemo(() => ({ ...parent, density: resolved }), [parent, resolved]);
  return (
    <DisplayModeContext.Provider value={value}>
      <ThemeProvider theme={theme}>
        <div data-density={resolved} style={{ display: "contents" }}>{children}</div>
      </ThemeProvider>
    </DisplayModeContext.Provider>
  );
}
