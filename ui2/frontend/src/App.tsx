import Box from "@mui/material/Box";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";

import { NavigationRail } from "./shell/NavigationRail";
import { DevicesEmptyState } from "./shell/DevicesEmptyState";
import { m3Theme } from "./theme/m3Theme";

/**
 * UI 2.0 shell. React + MUI on the Material 3 scheme from the Product
 * Owner's design canvas, per the platform contract's technology decision.
 */
export function App() {
  return (
    <ThemeProvider theme={m3Theme}>
      <CssBaseline />
      <Box sx={{ display: "flex", minHeight: "100vh" }}>
        <NavigationRail active="devices" />
        <DevicesEmptyState />
      </Box>
    </ThemeProvider>
  );
}
