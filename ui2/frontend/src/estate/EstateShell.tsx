import { useCallback, useEffect, useState, type MouseEvent, type ReactNode } from "react";
import Box from "@mui/material/Box";
import GlobalStyles from "@mui/material/GlobalStyles";
import IconButton from "@mui/material/IconButton";
import Menu from "@mui/material/Menu";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import { responsesAreMasked } from "../auth/adminApi";
import { useSession } from "../auth/SessionContext";
import { NexusWordmark } from "../brand/NexusWordmark";
import { planeGranted } from "../shell/actionAccess";
import { useDisplayMode } from "../shell/displayMode";
import { GlobalSearch } from "../shell/GlobalSearch";
import { Icon } from "../shell/Icon";
import { StatusChip } from "../shell/M3Widgets";
import { PlatformHealthIndicator } from "../shell/PlatformHealthIndicator";
import { roleTokenLabel } from "../shell/roleLabel";
import { DISPLAY_TZ_LABEL, formatUtc, relativeAge } from "../shell/time";
import { ViewModeControl } from "../shell/ViewModeControl";
import { ESTATE_LENSES, LENS_META, lensProblemCount } from "./estateModel";
import { EstateFindPalette } from "./EstateFindPalette";
import { useEstateFindShortcut } from "./estateFind";
import { EvidenceStamp, StateBadge } from "./EstatePrimitives";
import {
  askHref,
  backupsHref,
  briefHref,
  rewindHref,
  changesHref,
  complianceHref,
  failoverHref,
  wallHref,
  type EstateLens,
  type EstateRoute,
} from "./estateRouting";
import {
  ESTATE_DARK,
  ESTATE_LIGHT,
  ESTATE_MONO,
  ESTATE_SANS,
  estateCssVariables,
  ev,
} from "./estateTokens";
import { useEstateData } from "./useEstateData";

function EstateMaskBadge() {
  const [masked, setMasked] = useState(responsesAreMasked());
  useEffect(() => {
    const on = () => setMasked(true);
    window.addEventListener("nx-masked", on);
    return () => window.removeEventListener("nx-masked", on);
  }, []);
  return masked ? <StatusChip tone="mem" label="aiview · names masked" dense /> : null;
}

function EstateClock({
  newestAt,
  oldestAt,
}: {
  readonly newestAt: string | null;
  readonly oldestAt: string | null;
}) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 30_000);
    return () => window.clearInterval(id);
  }, []);
  const hhmm = formatUtc(now.toISOString(), false).slice(11);
  return (
    <Tooltip title="Evidence clock (GMT+3). Opens Rewind: read stored evidence as of a past moment.">
      <Box
        component="a"
        href={rewindHref()}
        data-testid="estate-rewind-link"
        sx={{
          display: "inline-flex",
          alignItems: "center",
          gap: "7px",
          height: "32px",
          px: "10px",
          borderRadius: "8px",
          border: `1px solid ${ev.line}`,
          bgcolor: ev.surface,
          color: ev.ink,
          textDecoration: "none",
          whiteSpace: "nowrap",
          fontSize: "12.5px",
          "&:hover": { borderColor: ev.lineStrong, textDecoration: "none" },
        }}
      >
        <StateBadge state="ok" word="Live" dense />
        <Box component="span" sx={{ color: ev.ink2 }}>
          as of{" "}
          <Box component="b" sx={{ fontFamily: ESTATE_MONO, fontWeight: 650, color: ev.ink }}>
            {hhmm}
          </Box>{" "}
          <Box component="span" sx={{ fontSize: "11.5px", color: ev.ink3 }}>
            {DISPLAY_TZ_LABEL}
          </Box>
        </Box>
        {newestAt ? (
          <Box
            component="span"
            sx={{
              fontSize: "11.5px",
              color: ev.ink3,
              display: { xs: "none", xl: "inline" },
            }}
          >
            newest {relativeAge(newestAt, now).replace(/\s+ago$/, "")}
            {oldestAt ? ` · oldest ${relativeAge(oldestAt, now).replace(/\s+ago$/, "")}` : ""}
          </Box>
        ) : null}
      </Box>
    </Tooltip>
  );
}

function EstateAccountButton() {
  const session = useSession();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  if (!session) return null;
  const initials = session.displayName ? session.displayName.slice(0, 2).toUpperCase() : "OP";
  return (
    <>
      <Tooltip title={`${session.displayName} · Account menu`}>
        <IconButton
          aria-label="Account menu"
          aria-haspopup="menu"
          aria-expanded={Boolean(anchor)}
          onClick={(e: MouseEvent<HTMLElement>) => setAnchor(e.currentTarget)}
          sx={{
            width: 32,
            height: 32,
            bgcolor: ev.accentBg,
            color: ev.accent,
            fontWeight: 700,
            fontSize: 12,
            flexShrink: 0,
            "&:hover": { bgcolor: ev.accentBg },
          }}
        >
          {initials}
        </IconButton>
      </Tooltip>
      <Menu
        anchorEl={anchor}
        open={Boolean(anchor)}
        onClose={() => setAnchor(null)}
        MenuListProps={{ "aria-label": "Account", dense: true }}
        anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
        transformOrigin={{ vertical: "top", horizontal: "right" }}
      >
        <Box sx={{ px: 2, py: 1, minWidth: 220, display: "flex", flexDirection: "column", gap: 0.5 }}>
          <Typography sx={{ fontWeight: 600 }}>{session.displayName}</Typography>
          <Typography variant="body2">
            {session.roleTokens.length > 0 ? session.roleTokens.map(roleTokenLabel).join(" · ") : "No Role"}
          </Typography>
          <Typography variant="caption" sx={{ color: "text.secondary" }}>
            Times in {DISPLAY_TZ_LABEL}
          </Typography>
        </Box>
        <Box sx={{ borderTop: `1px solid ${ev.line}`, mt: 0.5, pt: 0.5, px: 1.5, pb: 0.5 }}>
          <Box
            component="button"
            type="button"
            onClick={() => {
              setAnchor(null);
              session.onSignOut();
            }}
            sx={{
              border: 0,
              background: "none",
              color: ev.accent,
              fontWeight: 600,
              cursor: "pointer",
              p: "4px 6px",
              fontFamily: "inherit",
            }}
          >
            Sign out
          </Box>
        </Box>
      </Menu>
    </>
  );
}

function lensBarHref(lens: EstateLens): string {
  if (lens === "failover") return failoverHref();
  if (lens === "backups") return backupsHref();
  if (lens === "compliance") return complianceHref();
  return wallHref(lens);
}

export function EstateLensBar({ activeLens }: { readonly activeLens: EstateLens | null }) {
  const { model } = useEstateData();
  const units = model?.units ?? [];
  return (
    <Box
      component="nav"
      aria-label="Estate lenses"
      sx={{
        bgcolor: ev.surface,
        borderBottom: `1px solid ${ev.line}`,
        px: { xs: 2, lg: 3 },
        display: "flex",
        alignItems: "center",
        gap: "6px",
        minHeight: "46px",
        overflowX: "auto",
      }}
    >
      <Typography
        component="span"
        sx={{ fontSize: "12px", color: ev.ink3, mr: "6px", whiteSpace: "nowrap", fontFamily: ESTATE_SANS }}
      >
        Look at the estate through
      </Typography>
      {ESTATE_LENSES.map((lens) => {
        const meta = LENS_META[lens];
        const on = activeLens === lens;
        const count = lens === "worst" ? null : lensProblemCount(units, lens);
        return (
          <Box
            key={lens}
            component="a"
            href={lensBarHref(lens)}
            aria-current={on ? "page" : undefined}
            data-lens-pill={lens}
            sx={{
              display: "inline-flex",
              alignItems: "center",
              gap: "6px",
              height: "30px",
              px: "12px",
              borderRadius: "999px",
              border: `1px solid ${on ? ev.ink : ev.line}`,
              bgcolor: on ? ev.ink : ev.surface,
              color: on ? ev.surface : ev.ink2,
              fontWeight: 560,
              fontSize: "13px",
              fontFamily: ESTATE_SANS,
              whiteSpace: "nowrap",
              textDecoration: "none",
              "&:hover": {
                borderColor: on ? ev.ink : ev.lineStrong,
                color: on ? ev.surface : ev.ink,
                textDecoration: "none",
              },
            }}
          >
            <span>{lens === "worst" ? "Wall" : meta.label}</span>
            {count !== null ? (
              <Box
                component="span"
                sx={{
                  fontFamily: ESTATE_MONO,
                  fontWeight: 600,
                  fontSize: "11.5px",
                  px: "6px",
                  py: "1px",
                  borderRadius: "999px",
                  bgcolor: on ? "rgba(127, 127, 127, 0.35)" : ev.surface2,
                  color: on ? ev.surface : ev.ink2,
                }}
              >
                {count}
              </Box>
            ) : null}
          </Box>
        );
      })}
      {model?.newestEvidenceAt ? (
        <Box sx={{ ml: "auto", pl: 1, display: { xs: "none", md: "inline-flex" } }}>
          <EvidenceStamp at={model.newestEvidenceAt} source="newest read" now={model.now} />
        </Box>
      ) : null}
    </Box>
  );
}

export function EstateShell({
  route,
  children,
}: {
  readonly route: EstateRoute;
  readonly children: ReactNode;
}) {
  const { mode, setMode } = useDisplayMode();
  const session = useSession();
  const { model } = useEstateData();
  const permissions = session?.permissions;
  const [findOpen, setFindOpen] = useState(false);
  const toggleFind = useCallback(() => setFindOpen((open) => !open), []);
  const closeFind = useCallback(() => setFindOpen(false), []);
  useEstateFindShortcut(toggleFind);
  const casesHref = planeGranted(permissions, "PacketAnalysis") ? "?screen=packet_analysis" : "?screen=diagnostics";

  const inEstateSection =
    route.kind === "wall" ||
    route.kind === "dossier" ||
    route.kind === "failover" ||
    route.kind === "backups" ||
    route.kind === "compliance" ||
    route.kind === "rewind";

  const navItems: ReadonlyArray<{
    readonly id: string;
    readonly label: string;
    readonly href: string;
    readonly current: boolean;
    readonly standardHint?: string;
  }> = [
    { id: "brief", label: "Brief", href: briefHref(), current: route.kind === "brief" },
    { id: "estate", label: "Estate", href: wallHref(), current: inEstateSection },
    { id: "changes", label: "Changes", href: changesHref(), current: route.kind === "changes" },
    { id: "ask", label: "Ask", href: askHref(), current: route.kind === "ask" },
    { id: "cases", label: "Cases", href: casesHref, current: route.kind === "cases", standardHint: "Opens standard Cases / Tools screen" },
    { id: "reports", label: "Reports", href: "?screen=reports", current: route.kind === "reports" },
    { id: "system", label: "System", href: "?screen=administration", current: false, standardHint: "Opens standard Administration screen" },
  ];

  const showLensBar = inEstateSection;
  const activeLens: EstateLens | null =
    route.kind === "failover"
      ? "failover"
      : route.kind === "backups"
        ? "backups"
        : route.kind === "compliance"
          ? "compliance"
          : route.kind === "wall"
            ? route.lens === "exposure" || route.lens === "install" || route.lens === "age"
              ? route.lens
              : "worst"
            : null;

  return (
    <Box
      data-estate-shell
      sx={{
        flex: 1,
        minHeight: "100vh",
        display: "flex",
        flexDirection: "column",
        bgcolor: ev.bg,
        color: ev.ink,
        fontFamily: ESTATE_SANS,
        fontSize: "14px",
        lineHeight: 1.45,
        fontVariantNumeric: "tabular-nums",
      }}
    >
      <GlobalStyles
        styles={{
          ":root": estateCssVariables(ESTATE_LIGHT),
          ":root[data-theme='dark']": estateCssVariables(ESTATE_DARK),
        }}
      />
      <Box
        component="header"
        sx={{
          position: "sticky",
          top: 0,
          zIndex: 30,
          bgcolor: ev.surface,
          borderBottom: `1px solid ${ev.line}`,
          display: "flex",
          alignItems: "center",
          gap: { xs: "10px", lg: "16px" },
          height: "56px",
          px: { xs: 2, lg: 3 },
          minWidth: 0,
        }}
      >
        <Box
          component="a"
          href={briefHref()}
          aria-label="Shift brief home"
          sx={{
            display: "inline-flex",
            alignItems: "center",
            flexShrink: 0,
            textDecoration: "none",
          }}
        >
          <NexusWordmark height={22} />
        </Box>

        <Box
          component="nav"
          aria-label="Estate navigation"
          sx={{ display: "flex", gap: "2px", height: "100%", flexShrink: 0 }}
        >
          {navItems.map((item) => {
            const link = (
              <Box
                key={item.id}
                component="a"
                href={item.href}
                aria-current={item.current ? "page" : undefined}
                data-estate-nav={item.id}
                sx={{
                  display: "flex",
                  alignItems: "center",
                  px: { xs: "8px", lg: "11px" },
                  color: item.current ? ev.ink : ev.ink2,
                  fontWeight: 560,
                  fontSize: "13.5px",
                  borderBottom: `3px solid ${item.current ? ev.accent : "transparent"}`,
                  mb: "-1px",
                  textDecoration: "none",
                  whiteSpace: "nowrap",
                  "&:hover": { color: ev.ink, textDecoration: "none" },
                }}
              >
                {item.label}
              </Box>
            );
            return item.standardHint ? (
              <Tooltip key={item.id} title={item.standardHint} describeChild>
                {link}
              </Tooltip>
            ) : (
              link
            );
          })}
        </Box>

        <Box sx={{ flex: 1, minWidth: 0 }} />

        <Box sx={{ minWidth: 0, flex: "0 1 240px", display: { xs: "none", sm: "block" } }}>
          <GlobalSearch />
        </Box>

        <Tooltip title="Find a unit, cluster or screen (Ctrl+K or Cmd+K)">
          <IconButton
            aria-label="Find in the estate (Ctrl+K or Cmd+K)"
            aria-haspopup="dialog"
            aria-expanded={findOpen}
            onClick={toggleFind}
            data-testid="estate-find-button"
            size="small"
            sx={{
              width: 32,
              height: 32,
              borderRadius: "8px",
              border: `1px solid ${ev.line}`,
              bgcolor: ev.surface,
              color: ev.ink2,
              flexShrink: 0,
              "&:hover": { bgcolor: ev.surface2, color: ev.ink },
            }}
          >
            <Icon name="search" size={17} />
          </IconButton>
        </Tooltip>

        <Box sx={{ display: { xs: "none", md: "inline-flex" }, flexShrink: 0 }}>
          <EstateClock newestAt={model?.newestEvidenceAt ?? null} oldestAt={model?.oldestEvidenceAt ?? null} />
        </Box>

        {session ? <PlatformHealthIndicator session={session} /> : null}
        <EstateMaskBadge />

        <Box sx={{ display: "flex", alignItems: "center", gap: "6px", flexShrink: 0 }}>
          <ViewModeControl variant="estate" />
          <Tooltip title={mode === "dark" ? "Light mode" : "Dark mode"}>
            <IconButton
              aria-label={mode === "dark" ? "Switch to light mode" : "Switch to dark mode"}
              size="small"
              onClick={() => setMode(mode === "dark" ? "light" : "dark")}
              sx={{
                width: 32,
                height: 32,
                borderRadius: "8px",
                border: `1px solid ${ev.line}`,
                bgcolor: ev.surface,
                color: ev.ink2,
                "&:hover": { bgcolor: ev.surface2, color: ev.ink },
              }}
            >
              <Icon name={mode === "dark" ? "sun" : "moon"} size={17} />
            </IconButton>
          </Tooltip>
        </Box>

        <EstateAccountButton />
      </Box>

      <EstateFindPalette open={findOpen} onClose={closeFind} units={model?.units ?? []} clusters={model?.clusters ?? []} />

      {showLensBar ? <EstateLensBar activeLens={activeLens} /> : null}

      <Box
        component="main"
        sx={{
          flex: 1,
          width: "100%",
          maxWidth: "1440px",
          mx: "auto",
          px: { xs: 2, lg: 3 },
           pt: "22px",
          pb: "56px",
          boxSizing: "border-box",
        }}
      >
        {children}
      </Box>
    </Box>
  );
}
