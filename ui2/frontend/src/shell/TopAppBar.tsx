import { useEffect, useState, type MouseEvent } from "react";
import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Link from "@mui/material/Link";
import Menu from "@mui/material/Menu";
import Typography from "@mui/material/Typography";
import Tooltip from "@mui/material/Tooltip";
import { MONO, m3 } from "../theme/m3Theme";
import { useDisplayMode } from "./displayMode";
import { GlobalSearch } from "./GlobalSearch";
import { DISPLAY_TZ_LABEL, formatUtc } from "./time";
import { Icon } from "./Icon";
import { M3Button, StatusChip } from "./M3Widgets";
import { useSession } from "../auth/SessionContext";
import { NexusWordmark } from "../brand/NexusWordmark";
import { useFetchOnMount } from "./useFetchOnMount";
import { listNotifications, getProjectPlan, responsesAreMasked } from "../auth/adminApi";
import { primaryRoleLabel, roleTokenLabel } from "./roleLabel";
import { PlatformHealthIndicator } from "./PlatformHealthIndicator";

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
 * The account menu (UI review 2026-11-05, B7): who is signed in and with which roles, the zone every time is shown in, and
 * the running build. The build stamp and the time-zone note used to sit in the bar on every screen, where they read as part of
 * the page; they are facts about the session and the deployment, so they live here, one click away. The build is read only when
 * the menu is opened.
 */
function AccountMenu({ initials, displayName, roles, onSignOut }: {
  readonly initials: string; readonly displayName: string; readonly roles: readonly string[]; readonly onSignOut: () => void;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  return (
    <>
      <Tooltip title="Account: roles, time zone, build">
        <IconButton aria-label="Account menu" aria-haspopup="menu" aria-expanded={Boolean(anchor)} onClick={(e: MouseEvent<HTMLElement>) => setAnchor(e.currentTarget)}
          sx={{ width: 34, height: 34, bgcolor: m3.primaryContainer, color: m3.onPrimaryContainer, fontWeight: 600, fontSize: 13, flexShrink: 0,
                "&:hover": { bgcolor: m3.primaryContainer } }}>
          {initials}
        </IconButton>
      </Tooltip>
      <Menu anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)} MenuListProps={{ "aria-label": "Account", dense: true }}
        anchorOrigin={{ vertical: "bottom", horizontal: "right" }} transformOrigin={{ vertical: "top", horizontal: "right" }}>
        <Box sx={{ px: 2, py: 1, minWidth: 260, display: "flex", flexDirection: "column", gap: 0.75, color: m3.onSurface }}>
          <Typography sx={{ fontWeight: 600 }}>{displayName}</Typography>
          <Typography variant="body2">{roles.length > 0 ? roles.map(roleTokenLabel).join(" · ") : "No Role"}</Typography>
          <Tooltip title="Every time on every screen is Turkey time (Europe/Istanbul). Hover a time for the UTC value; exports carry UTC.">
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar, width: "fit-content" }}>Times in {DISPLAY_TZ_LABEL}</Typography>
          </Tooltip>
          {anchor && <Box sx={{ display: "inline-flex" }}><BuildBadge /></Box>}
        </Box>
        <Box sx={{ borderTop: `1px solid ${m3.outlineVar}`, mt: 0.5, pt: 0.5, px: 1 }}>
          <M3Button emphasis="text" onClick={() => { setAnchor(null); onSignOut(); }}>Sign out</M3Button>
        </Box>
      </Menu>
    </>
  );
}

/**
 * The top app bar: search, display controls, a notification glyph and the
 * account. The logo is in the sidebar header, not here; only the wall
 * display's bar, which has no sidebar, shows the wordmark. The canvas also shows a run-status chip and an
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
/** "aiview · names masked" on every screen, once any response came back masked (server header). */
function MaskChip() {
  const [masked, setMasked] = useState(responsesAreMasked());
  useEffect(() => {
    const on = () => setMasked(true);
    window.addEventListener("nx-masked", on);
    return () => window.removeEventListener("nx-masked", on);
  }, []);
  return masked ? <StatusChip tone="mem" label="aiview · names masked" dense /> : null;
}

/** The as-of clock the wall display shows (review §6): GMT+3, ticking every 30 s. */
function AsOfClock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => { const t = setInterval(() => setNow(new Date()), 30_000); return () => clearInterval(t); }, []);
  return <Typography sx={{ fontFamily: MONO, fontSize: 15, color: m3.onSurface }}>as of {formatUtc(now.toISOString(), false)} {DISPLAY_TZ_LABEL}</Typography>;
}

/** Colour mode and wall display -- per-viewer preferences only (density is per screen type, not a toggle). */
function DisplayControls() {
  const { mode, setMode } = useDisplayMode();
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
        {/* The wall display has no sidebar, so its bar carries the logo. */}
        <NexusWordmark height={24} />
        <Box sx={{ ml: "auto", display: "flex", alignItems: "center", gap: 2 }}>
          <MaskChip />
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
    <Box sx={{ height: 64, flex: "none", display: "flex", alignItems: "center", gap: 2, px: 3 }}>
      {/* No logo here: the sidebar header beside this bar carries it, once (brand area 2026-11-13). */}
      <Box sx={{ minWidth: 0, flex: "0 1 auto" }}><GlobalSearch /></Box>
      {/* At 1000 px the bar used to run past the viewport (post-release review 2026-10-18, R1 check): the right block
          now shrinks with the window and the two informational chips fold away below 1200 px; the zone is still stated
          beside every time on hover and the build in the account menu. */}
      <Box sx={{ ml: "auto", display: "flex", alignItems: "center", gap: { xs: 1, lg: 2 }, color: m3.onSurfaceVar, minWidth: 0, flex: "0 1 auto" }}>
        {/* neXus' own health for every signed-in user (PO 2026-11-13): quiet when OK, a link to Platform status where allowed. */}
        {session ? <PlatformHealthIndicator session={session} /> : null}
        <MaskChip />
        <DisplayControls />
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
          <Icon name="bell" size={20} />
          {session ? <NotificationBadge /> : null}
        </Box>
        {session ? (
          <>
            <AccountMenu initials={userInitials} displayName={session.displayName} roles={displayedRoles} onSignOut={session.onSignOut} />
            <Box sx={{ display: "flex", flexDirection: "column", alignItems: "flex-end", lineHeight: 1.2, whiteSpace: "nowrap" }}>
              <Typography variant="body1" sx={{ fontWeight: 500, color: m3.onSurface }}>
                {session.displayName}
              </Typography>
              <Tooltip title={displayedRoles.map(roleTokenLabel).join(", ")}>
                <Typography variant="body2" sx={{ cursor: "default", whiteSpace: "nowrap" }}>
                  {primaryRoleLabel(displayedRoles)}
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
