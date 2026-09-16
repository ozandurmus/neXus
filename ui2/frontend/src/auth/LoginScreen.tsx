import { useState } from "react";
import type { FormEvent } from "react";
import Box from "@mui/material/Box";
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
const CONFLICT_MESSAGE = "Another session is already active for this account.";
const UNEXPECTED_ERROR = "Something went wrong. Please try again.";

type Outcome = "ok" | "invalid" | "conflict" | "unexpected";
type LoginMechanism = "local" | "ldap";

async function submitLogin(username: string, password: string, mechanism: LoginMechanism): Promise<Outcome> {
  try {
    const response = await fetch("/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ username, password, mechanism_id: mechanism }),
    });
    if (response.ok) return "ok";
    if (response.status === 409) return "conflict";
    if (response.status === 401) return "invalid";
    return "unexpected";
  } catch {
    return "unexpected";
  }
}

/**
 * Username, password, submit -- and one error presentation shared by every
 * failure mode (this movement's brief; C3A contract §5.3). No product
 * screen is reachable from here: a successful submission calls
 * onAuthenticated, which is the only way past this component (see AuthGate).
 */
export function LoginScreen({ onAuthenticated }: { readonly onAuthenticated: () => void }) {
  const [username, setUsername] = useState("");
  const [mechanism, setMechanism] = useState<LoginMechanism>("local");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event?: FormEvent) {
    event?.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const outcome = await submitLogin(username, password, mechanism);
      switch (outcome) {
        case "ok":
          onAuthenticated();
          return;
        case "conflict":
          setError(CONFLICT_MESSAGE);
          return;
        case "invalid":
          setError(GENERIC_ERROR);
          return;
        default:
          setError(UNEXPECTED_ERROR);
      }
    } finally {
      if (mechanism === "ldap") setPassword("");
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
      <NexusWordmark height={56} color={m3.onSurface} tagline />
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
        <TextField
          select
          label="Authentication"
          value={mechanism}
          disabled={submitting}
          SelectProps={{ native: true }}
          inputProps={{ "aria-label": "Authentication" }}
          onChange={(event) => {
            if (event.target.value === "local" || event.target.value === "ldap") setMechanism(event.target.value);
          }}
        >
          <option value="local">Local account</option>
          <option value="ldap">LDAP account</option>
        </TextField>
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
        {error ? (
          <Typography role="alert" variant="body2" sx={{ color: m3.error }}>
            {error}
          </Typography>
        ) : null}
        <M3Button emphasis="filled" type="submit">
          {submitting ? "Signing in…" : "Sign in"}
        </M3Button>
      </Box>
    </Box>
  );
}
