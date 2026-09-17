import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../theme/m3Theme";
import { LoginScreen } from "./LoginScreen";
import { PasswordChangeScreen } from "./PasswordChangeScreen";
import { SessionContext } from "./SessionContext";

type AuthState = "checking" | "authenticated" | "must-change-password" | "unauthenticated";

interface SessionStatus {
  readonly authenticated: boolean;
  readonly displayName: string | null;
  readonly roleTokens: readonly string[];
  readonly permissions: readonly string[];
  readonly mustChangePassword: boolean;
}

const UNAUTHENTICATED_STATUS: SessionStatus = {
  authenticated: false,
  displayName: null,
  roleTokens: [],
  permissions: [],
  mustChangePassword: false,
};

async function checkSession(): Promise<SessionStatus> {
  try {
    const response = await fetch("/session/status", { credentials: "include" });
    if (!response.ok) return UNAUTHENTICATED_STATUS;
    const body = await response.json();
    return {
      authenticated: true,
      displayName: typeof body.display_name === "string" ? body.display_name : null,
      roleTokens: Array.isArray(body.role_tokens) ? body.role_tokens : [],
      permissions: Array.isArray(body.permissions) ? body.permissions : [],
      mustChangePassword: body.must_change_password === true,
    };
  } catch {
    return UNAUTHENTICATED_STATUS;
  }
}

async function signOut(): Promise<void> {
  try {
    await fetch("/session/logout", { method: "POST", credentials: "include" });
  } catch {
    // The cookie is cleared client-visibly regardless (best-effort network call);
    // the caller always returns to the login screen either way.
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
  const [session, setSession] = useState<SessionStatus>(UNAUTHENTICATED_STATUS);

  function applyStatus(status: SessionStatus) {
    setSession(status);
    if (!status.authenticated) {
      setState("unauthenticated");
    } else if (status.mustChangePassword) {
      setState("must-change-password");
    } else {
      setState("authenticated");
    }
  }

  useEffect(() => {
    let cancelled = false;
    checkSession().then((status) => {
      if (!cancelled) {
        applyStatus(status);
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  async function handleSignOut() {
    await signOut();
    applyStatus(UNAUTHENTICATED_STATUS);
  }

  if (state === "authenticated") {
    return (
      <SessionContext.Provider
        value={{
          displayName: session.displayName ?? "",
          roleTokens: session.roleTokens,
          permissions: session.permissions,
          onSignOut: handleSignOut,
        }}
      >
        {children}
      </SessionContext.Provider>
    );
  }

  return (
    <ThemeProvider theme={m3Theme}>
      <CssBaseline />
      {state === "unauthenticated" ? (
        <LoginScreen onAuthenticated={() => checkSession().then(applyStatus)} />
      ) : state === "must-change-password" ? (
        <PasswordChangeScreen
          username={session.displayName ?? ""}
          onChanged={() => checkSession().then(applyStatus)}
          onSignOut={handleSignOut}
        />
      ) : (
        <Box sx={{ minHeight: "100vh" }} data-testid="auth-gate-checking" />
      )}
    </ThemeProvider>
  );
}
