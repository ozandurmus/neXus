import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { m3 } from "../theme/m3Theme";
import { Icon } from "./Icon";
import { M3Button } from "./M3Widgets";
import { useSession } from "../auth/SessionContext";

/**
 * The canvas's top app bar: product name, a search affordance and a
 * notification glyph. The canvas also shows a run-status chip and an
 * operator initial badge, but both name a specific collection run and a
 * specific signed-in operator -- values this build has neither collected
 * nor authenticated, so showing them here would be fabricated certainty.
 *
 * The signed-in identity and its role tokens (this movement's brief) are
 * shown as plain text only, whatever the session carries -- this component
 * computes no visibility decision from a role token's value
 * (NoRoleConditionalRenderingInFrontendTest, AG-J3); it renders the identity
 * block at all only when a session is present (useSession() is non-null
 * inside AuthGate, null in App's own standalone tests/preview rendering).
 */
export function TopAppBar() {
  const session = useSession();
  return (
    <Box sx={{ height: 64, flex: "none", display: "flex", alignItems: "center", gap: 2, px: 3, pl: 1 }}>
      <Typography variant="h3">SecurityExpert</Typography>
      <Box sx={{ flex: 1, maxWidth: 520, height: 48, display: "flex", alignItems: "center", gap: 1.5,
                 px: 2, borderRadius: "24px", bgcolor: m3.scHigh, color: m3.onSurfaceVar, fontSize: 15 }}>
        <Icon name="search" size={20} />
        Search devices, settings, evidence
      </Box>
      <Box sx={{ ml: "auto", display: "flex", alignItems: "center", gap: 2, color: m3.onSurfaceVar }}>
        <Icon name="bell" size={20} />
        {session ? (
          <>
            <Box sx={{ display: "flex", flexDirection: "column", alignItems: "flex-end", lineHeight: 1.2 }}>
              <Typography variant="body1" sx={{ fontWeight: 500, color: m3.onSurface }}>
                {session.displayName}
              </Typography>
              <Typography variant="body2">{session.roleTokens.join(", ")}</Typography>
            </Box>
            <M3Button emphasis="text" onClick={session.onSignOut}>
              Sign out
            </M3Button>
          </>
        ) : null}
      </Box>
    </Box>
  );
}
