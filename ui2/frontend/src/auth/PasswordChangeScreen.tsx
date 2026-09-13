import { useState } from "react";
import type { FormEvent } from "react";
import Box from "@mui/material/Box";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { m3 } from "../theme/m3Theme";
import { M3Button } from "../shell/M3Widgets";

// This movement's own rule, stated plainly (WORKER.md): a bootstrap identity
// that still holds its seeded password must change it before it can reach
// any product screen.
const RULE_STATEMENT =
  "This account still holds the password it was set up with. Choose a new one to continue.";
// C3A contract §5.3's single-generic-message discipline, reused here for the
// change-password path (WORKER.md): never says which rule a rejected
// password broke, beyond what C3A itself already surfaces.
const GENERIC_ERROR = "That did not work. Check the current password and try again.";
const MISMATCH_ERROR = "The new password and its confirmation do not match.";
const UNEXPECTED_ERROR = "Something went wrong. Please try again.";

type Outcome = "ok" | "refused" | "unexpected";

async function submitChange(username: string, currentPassword: string, newPassword: string): Promise<Outcome> {
  try {
    const response = await fetch("/local-credentials/change-password", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ username, currentPassword, newPassword }),
    });
    if (response.ok) return "ok";
    if (response.status === 401 || response.status === 400) return "refused";
    return "unexpected";
  } catch {
    return "unexpected";
  }
}

/**
 * Shown by AuthGate INSTEAD of the shell while the signed-in identity's
 * must-change-password flag is true (this movement's brief). The only ways
 * past this screen are a successful change (onChanged) or signing out
 * (onSignOut) -- there is no third control.
 */
export function PasswordChangeScreen({
  username,
  onChanged,
  onSignOut,
}: {
  readonly username: string;
  readonly onChanged: () => void;
  readonly onSignOut: () => void;
}) {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event?: FormEvent) {
    event?.preventDefault();
    if (submitting) return;
    if (newPassword !== confirmPassword) {
      setError(MISMATCH_ERROR);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const outcome = await submitChange(username, currentPassword, newPassword);
      switch (outcome) {
        case "ok":
          onChanged();
          return;
        case "refused":
          setError(GENERIC_ERROR);
          return;
        default:
          setError(UNEXPECTED_ERROR);
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box
      sx={{
        display: "flex",
        minHeight: "100vh",
        alignItems: "center",
        justifyContent: "center",
        bgcolor: m3.surface,
      }}
    >
      <Box
        component="form"
        onSubmit={handleSubmit}
        sx={{
          display: "flex",
          flexDirection: "column",
          gap: 2,
          width: 360,
          p: 4,
          borderRadius: "16px",
          bgcolor: m3.scLow,
          border: `1px solid ${m3.outline}`,
        }}
      >
        <Typography variant="h3">Choose a new password</Typography>
        <Typography variant="body2">{RULE_STATEMENT}</Typography>
        <TextField
          label="Current password"
          type="password"
          value={currentPassword}
          onChange={(event) => setCurrentPassword(event.target.value)}
          autoFocus
          required
          fullWidth
          inputProps={{ "aria-label": "Current password" }}
        />
        <TextField
          label="New password"
          type="password"
          value={newPassword}
          onChange={(event) => setNewPassword(event.target.value)}
          required
          fullWidth
          inputProps={{ "aria-label": "New password" }}
        />
        <TextField
          label="Confirm new password"
          type="password"
          value={confirmPassword}
          onChange={(event) => setConfirmPassword(event.target.value)}
          required
          fullWidth
          inputProps={{ "aria-label": "Confirm new password" }}
        />
        {error ? (
          <Typography role="alert" variant="body2" sx={{ color: m3.error }}>
            {error}
          </Typography>
        ) : null}
        <M3Button emphasis="filled" onClick={() => handleSubmit()}>
          {submitting ? "Changing…" : "Change password"}
        </M3Button>
        <M3Button emphasis="text" onClick={onSignOut}>
          Sign out instead
        </M3Button>
      </Box>
    </Box>
  );
}
