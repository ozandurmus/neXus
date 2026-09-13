import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../theme/m3Theme";
import { LoginScreen } from "./LoginScreen";

type AuthState = "checking" | "authenticated" | "unauthenticated";

async function checkSession(): Promise<boolean> {
  try {
    const response = await fetch("/session/status", { credentials: "include" });
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * Gates every product screen behind a real session (this movement's own
 * stated goal: "the product cannot be used without logging in, even on
 * localhost"; C3A contract §2's flow). Nothing this build ships renders as
 * a child of this component until {@code GET /session/status} confirms an
 * ACTIVE session; until then, the login screen is the only thing on the
 * page.
 */
export function AuthGate({ children }: { readonly children: ReactNode }) {
  const [state, setState] = useState<AuthState>("checking");

  useEffect(() => {
    let cancelled = false;
    checkSession().then((authenticated) => {
      if (!cancelled) {
        setState(authenticated ? "authenticated" : "unauthenticated");
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (state === "authenticated") {
    return <>{children}</>;
  }

  return (
    <ThemeProvider theme={m3Theme}>
      <CssBaseline />
      {state === "unauthenticated" ? (
        <LoginScreen onAuthenticated={() => setState("authenticated")} />
      ) : (
        <Box sx={{ minHeight: "100vh" }} data-testid="auth-gate-checking" />
      )}
    </ThemeProvider>
  );
}
