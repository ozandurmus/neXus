import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import Tooltip from "@mui/material/Tooltip";
import { m3 } from "../theme/m3Theme";
import { Icon } from "./Icon";
import { M3Button, StatusChip } from "./M3Widgets";
import { useSession } from "../auth/SessionContext";
import { NexusWordmark } from "../brand/NexusWordmark";
import { useFetchOnMount } from "./useFetchOnMount";
import { listNotifications, getProjectPlan } from "../auth/adminApi";

/**
 * The minimal notification badge NXS-LOCAL-0165 adds (WORKER.md
 * "Configuration collection": "no functional notification surface exists;
 * add ... a shell badge, and say so") -- an unread count next to the bell
 * glyph, from {@code GET /notifications}. Not a full notification center
 * (no dropdown, no per-item read/dismiss action); those are a later
 * movement's own build.
 */
function NotificationBadge() {
  const { data } = useFetchOnMount(
    () => listNotifications().then((result) => result.notifications ?? []),
    () => "",
  );
  const unread = (data ?? []).filter((n) => n.read_at === null).length;
  if (unread === 0) {
    return null;
  }
  return <StatusChip tone="warn" label={String(unread)} dense />;
}

function BuildBadge() {
  const { data } = useFetchOnMount(
    () => getProjectPlan().then((plan) => plan.current_product_build ?? "NXS-LOCAL-0313"),
    () => "",
  );
  const buildTag = data || "NXS-LOCAL-0313";
  return (
    <Tooltip title={`neXus Active Build: ${buildTag}`}>
      <Box sx={{ display: "inline-flex" }}>
        <StatusChip tone="neutral" label={buildTag} dense />
      </Box>
    </Tooltip>
  );
}

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

  let displayedRoles: string[] = [];
  if (session) {
    const roleOrder = [
      "security_admin",
      "compliance_admin",
      "backup_admin",
      "onboarding_admin",
      "operator",
      "viewer",
    ];
    displayedRoles = [...session.roleTokens].sort((a, b) => {
      const indexA = roleOrder.findIndex(r => a.endsWith(r));
      const indexB = roleOrder.findIndex(r => b.endsWith(r));
      return (indexA === -1 ? 99 : indexA) - (indexB === -1 ? 99 : indexB);
    });
  }

  return (
    <Box sx={{ height: 64, flex: "none", display: "flex", alignItems: "center", gap: 2, px: 3, pl: 1 }}>
      <NexusWordmark height={28} color={m3.onSurface} />
      <Box sx={{ flex: 1, maxWidth: 520, height: 48, display: "flex", alignItems: "center", gap: 1.5,
                 px: 2, borderRadius: "24px", bgcolor: m3.scHigh, color: m3.onSurfaceVar, fontSize: 15 }}>
        <Icon name="search" size={20} />
        Search devices, settings, evidence
      </Box>
      <Box sx={{ ml: "auto", display: "flex", alignItems: "center", gap: 2, color: m3.onSurfaceVar }}>
        <BuildBadge />
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
          <Icon name="bell" size={20} />
          {session ? <NotificationBadge /> : null}
        </Box>
        {session ? (
          <>
            <Box sx={{ display: "flex", flexDirection: "column", alignItems: "flex-end", lineHeight: 1.2 }}>
              <Typography variant="body1" sx={{ fontWeight: 500, color: m3.onSurface }}>
                {session.displayName}
              </Typography>
              <Tooltip title={displayedRoles.join(", ")}>
                <Typography variant="body2" sx={{ cursor: "default" }}>
                  {displayedRoles.length > 0
                    ? displayedRoles[0].replace("role:", "").replace("_", " ").replace(/\b\w/g, l => l.toUpperCase())
                    : "No Role"}
                </Typography>
              </Tooltip>
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
