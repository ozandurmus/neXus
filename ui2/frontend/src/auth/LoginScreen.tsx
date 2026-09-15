import { useState } from "react";
import type { FormEvent } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { M3Button } from "../shell/M3Widgets";
import { NexusWordmark } from "../brand/NexusWordmark";

// C3A contract §5.3: every local login failure -- unknown identity, wrong
// password, empty password, and an active lockout -- returns the identical
// body from the server. This screen mirrors that on the client: one
// message, regardless of which of those four caused the 401.
const GENERIC_ERROR = "Incorrect username or password.";
const UNEXPECTED_ERROR = "Something went wrong. Please try again.";

type Conflict = {
  readonly token: string;
  readonly createdAt: string;
  readonly lastSeenAt: string;
};

type Outcome = "ok" | "invalid" | "unexpected" | { readonly conflict: Conflict };

async function submitLogin(username: string, password: string): Promise<Outcome> {
  try {
    const response = await fetch("/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ username, password, mechanism_id: "local" }),
    });
    if (response.ok) return "ok";
    if (response.status === 409) {
      const body = await response.json();
      if (
        typeof body.conflict_token === "string" &&
        typeof body.prior_session?.created_at === "string" &&
        typeof body.prior_session?.last_seen_at === "string"
      ) {
        return {
          conflict: {
            token: body.conflict_token,
            createdAt: body.prior_session.created_at,
            lastSeenAt: body.prior_session.last_seen_at,
          },
        };
      }
      return "unexpected";
    }
    if (response.status === 401) return "invalid";
    return "unexpected";
  } catch {
    return "unexpected";
  }
}

async function takeOver(conflictToken: string): Promise<"ok" | "lapsed" | "unexpected"> {
  try {
    const response = await fetch("/login/resolve", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ conflictToken, action: "takeover" }),
    });
    if (response.ok) return "ok";
    if (response.status === 409 && (await response.json()).reason_code === "conflict_token_expired_or_unknown") {
      return "lapsed";
    }
    return "unexpected";
  } catch {
    return "unexpected";
  }
}

function readableTime(instant: string) {
  return new Date(instant).toLocaleString();
}

/**
 * Username, password, submit -- and one error presentation shared by every
 * failure mode (this movement's brief; C3A contract §5.3). No product
 * screen is reachable from here: a successful submission calls
 * onAuthenticated, which is the only way past this component (see AuthGate).
 */
export function LoginScreen({ onAuthenticated }: { readonly onAuthenticated: () => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState<Conflict | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event?: FormEvent) {
    event?.preventDefault();
    if (conflict) {
      setConflict(null);
      return;
    }
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const outcome = await submitLogin(username, password);
      if (typeof outcome !== "string") {
        setConflict(outcome.conflict);
        return;
      }
      switch (outcome) {
        case "ok":
          onAuthenticated();
          return;
        case "invalid":
          setError(GENERIC_ERROR);
          return;
        default:
          setError(UNEXPECTED_ERROR);
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function handleTakeover() {
    if (!conflict || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const outcome = await takeOver(conflict.token);
      if (outcome === "ok") {
        onAuthenticated();
        return;
      }
      setConflict(null);
      setError(outcome === "lapsed" ? "This session offer has lapsed. Please sign in again." : UNEXPECTED_ERROR);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box
      sx={{
        display: "flex",
        flexDirection: "column",
        minHeight: "100vh",
        alignItems: "center",
        justifyContent: "center",
        gap: 3,
        bgcolor: m3.surface,
      }}
    >
      <NexusWordmark height={56} color={m3.onSurface} />
      <Box
        component="form"
        onSubmit={handleSubmit}
        sx={{
          display: "flex",
          flexDirection: "column",
          gap: 2,
          width: 320,
          p: 4,
          borderRadius: "16px",
          bgcolor: m3.scLow,
          border: `1px solid ${m3.outline}`,
        }}
      >
        <Typography variant="h4">Sign in</Typography>
        {conflict ? (
          <>
            <Typography role="alert" variant="body2" sx={{ color: m3.error }}>
              A session for this account is already active. It started {readableTime(conflict.createdAt)} and was last seen {readableTime(conflict.lastSeenAt)}.
            </Typography>
            <Typography variant="body2">Taking over ends the other session.</Typography>
            <Button type="button" variant="contained" onClick={handleTakeover} disabled={submitting}>
              {submitting ? "Taking over…" : "Take over session"}
            </Button>
            <Button type="button" variant="outlined" onClick={() => setConflict(null)} disabled={submitting}>
              Refuse and return to sign in
            </Button>
          </>
        ) : (
          <>
            <TextField
              label="Username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoFocus
              required
              fullWidth
              inputProps={{ "aria-label": "Username" }}
            />
            <TextField
              label="Password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
              fullWidth
              inputProps={{ "aria-label": "Password" }}
            />
          </>
        )}
        {error ? (
          <Typography role="alert" variant="body2" sx={{ color: m3.error }}>
            {error}
          </Typography>
        ) : null}
        {!conflict ? (
          <M3Button emphasis="filled">
            {submitting ? "Signing in…" : "Sign in"}
          </M3Button>
        ) : null}
      </Box>
    </Box>
  );
}
