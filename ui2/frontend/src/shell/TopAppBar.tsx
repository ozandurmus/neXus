import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import Tooltip from "@mui/material/Tooltip";
import { MONO, m3 } from "../theme/m3Theme";
import { useDisplayMode } from "./displayMode";
import { GlobalSearch } from "./GlobalSearch";
import { formatUtc } from "./time";
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

function relativeTimeFromNow(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return iso;
  const diffMs = Date.now() - then;
  const minutes = Math.round(diffMs / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  return `${days}d ago`;
}

/**
 * Shows exactly what HOST-A's build script actually baked into the running image
 * (project/deploy_info.json's commit + built_at, generated fresh per deploy) instead of a
 * hand-maintained build id -- there is nothing here to remember to update by hand. Falls
 * back to the project plan's own current_product_build only until the first deploy using
 * that step has run.
 */
function BuildBadge() {
  const { data } = useFetchOnMount(
    () => getProjectPlan().then((plan) => ({
      commit: plan.deployed_commit ?? null,
      builtAt: plan.deployed_at ?? null,
      fallback: plan.current_product_build ?? "NXS-LOCAL-0313",
    })),
    () => "",
  );
  if (!data) {
    return null;
  }
  const label = data.commit
    ? `Build ${data.commit.slice(0, 7)}${data.builtAt ? ` · deployed ${relativeTimeFromNow(data.builtAt)}` : ""}`
    : `Build ${data.fallback}`;
  const tooltip = data.commit
    ? `Deployed commit ${data.commit}${data.builtAt ? ` at ${data.builtAt}` : ""}`
    : `neXus Active Build: ${data.fallback}`;
  return (
    <Tooltip title={tooltip}>
      <Box sx={{ display: "inline-flex" }}>
        <StatusChip tone="neutral" label={label} dense />
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
/** The as-of clock the wall display shows (review §6): UTC, ticking every 30 s. */
function AsOfClock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => { const t = setInterval(() => setNow(new Date()), 30_000); return () => clearInterval(t); }, []);
  return <Typography sx={{ fontFamily: MONO, fontSize: 15, color: m3.onSurface }}>as of {formatUtc(now.toISOString(), false)} UTC</Typography>;
}

/** Colour mode, density and wall display -- per-viewer preferences only. */
function DisplayControls() {
  const { mode, density, setMode, setDensity } = useDisplayMode();
  const wallHref = (() => {
    if (typeof window === "undefined") return "?screen=overview&wall=1";
    const p = new URLSearchParams(window.location.search);
    p.set("screen", "overview");
    p.set("wall", "1");
    return `?${p.toString()}`;
  })();
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 0.25 }}>
      <Tooltip title={mode === "dark" ? "Light mode" : "Dark mode"}>
        <IconButton aria-label={mode === "dark" ? "Switch to light mode" : "Switch to dark mode"} size="small"
          onClick={() => setMode(mode === "dark" ? "light" : "dark")} sx={{ color: m3.onSurfaceVar }}>
          <Icon name={mode === "dark" ? "sun" : "moon"} size={18} />
        </IconButton>
      </Tooltip>
      <Tooltip title={density === "compact" ? "Comfortable rows" : "Compact rows"}>
        <IconButton aria-label={density === "compact" ? "Switch to comfortable rows" : "Switch to compact rows"} size="small"
          aria-pressed={density === "compact"} onClick={() => setDensity(density === "compact" ? "comfortable" : "compact")}
          sx={{ color: density === "compact" ? m3.primary : m3.onSurfaceVar }}>
          <Icon name="rows" size={18} />
        </IconButton>
      </Tooltip>
      <Tooltip title="Wall display: the Overview for an unattended screen, dark, refreshed every 60 s">
        <IconButton aria-label="Open the wall display" size="small" href={wallHref} sx={{ color: m3.onSurfaceVar }}>
          <Icon name="external" size={18} />
        </IconButton>
      </Tooltip>
    </Box>
  );
}

export function TopAppBar() {
  const session = useSession();
  const { wall } = useDisplayMode();

  if (wall) {
    const exit = (() => {
      if (typeof window === "undefined") return "?screen=overview";
      const p = new URLSearchParams(window.location.search);
      p.delete("wall");
      return `?${p.toString()}`;
    })();
    return (
      <Box sx={{ height: 56, flex: "none", display: "flex", alignItems: "center", gap: 2, px: 3 }}>
        <NexusWordmark height={24} color={m3.onSurface} />
        <Box sx={{ ml: "auto", display: "flex", alignItems: "center", gap: 2 }}>
          <AsOfClock />
          <Link href={exit} sx={{ fontSize: 12, color: m3.onSurfaceVar }}>Exit wall display</Link>
        </Box>
      </Box>
    );
  }

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

  const userInitials = session?.displayName
    ? session.displayName.slice(0, 2).toUpperCase()
    : "OP";

  return (
    <Box sx={{ height: 64, flex: "none", display: "flex", alignItems: "center", gap: 2, px: 3, pl: 1 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.25 }}>
        <Box
          sx={{
            width: 34,
            height: 34,
            borderRadius: "8px",
            bgcolor: m3.primaryContainer,
            color: m3.onPrimaryContainer,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            fontWeight: 700,
            fontSize: 14,
            letterSpacing: "0.5px",
            boxShadow: m3.e1,
            flexShrink: 0,
          }}
        >
          SX
        </Box>
        <NexusWordmark height={26} color={m3.onSurface} />
      </Box>
      <Box sx={{ ml: 3 }}><GlobalSearch /></Box>
      <Box sx={{ ml: "auto", display: "flex", alignItems: "center", gap: 2, color: m3.onSurfaceVar }}>
        <Tooltip title="Every time on every screen is UTC"><Typography variant="caption" sx={{ color: m3.onSurfaceVar, whiteSpace: "nowrap" }}>Times in UTC</Typography></Tooltip>
        <BuildBadge />
        <DisplayControls />
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
          <Icon name="bell" size={20} />
          {session ? <NotificationBadge /> : null}
        </Box>
        {session ? (
          <>
            <Box
              sx={{
                width: 34,
                height: 34,
                borderRadius: "50%",
                bgcolor: m3.primaryContainer,
                color: m3.onPrimaryContainer,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                fontWeight: 600,
                fontSize: 13,
                flexShrink: 0,
              }}
            >
              {userInitials}
            </Box>
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
