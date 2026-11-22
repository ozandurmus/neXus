import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { EstateCard, EvidenceStamp, StateBadge } from "./EstatePrimitives";
import { ESTATE_MONO, ev } from "./estateTokens";
import type { PlatformFindingItem, PlatformFindings, PlatformSeverity } from "./estatePlatformFindings";

const GROUPS: ReadonlyArray<{ readonly severity: PlatformSeverity; readonly badge: "act" | "watch" | "unk"; readonly word: string; readonly note: string }> = [
  { severity: "critical", badge: "act", word: "Act now", note: "platform: device health and open HIGH scan findings" },
  { severity: "serious", badge: "watch", word: "Review", note: "platform: device health and open MEDIUM scan findings, stale scan" },
  { severity: "unknown", badge: "unk", word: "UNKNOWN", note: "platform sources that could not be read, never assumed fine" },
];

function PlatformItem({ item, now }: { readonly item: PlatformFindingItem; readonly now: Date }) {
  return (
    <Box
      data-platform-item={item.key}
      sx={{
        display: "grid",
        gridTemplateColumns: { xs: "1fr", sm: "minmax(0, 1fr) auto" },
        gap: { xs: "8px", sm: "16px" },
        px: "18px",
        py: "12px",
        borderTop: `1px solid ${ev.line}`,
        alignItems: "start",
      }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ fontSize: "14.5px", fontWeight: 650, color: ev.ink, lineHeight: 1.3 }}>{item.what}</Typography>
        {item.detail ? <Typography sx={{ fontSize: "12.5px", color: ev.ink2, mt: "3px" }}>{item.detail}</Typography> : null}
        {item.names.length > 0 ? (
          <Typography sx={{ fontSize: "12.5px", color: ev.ink2, mt: "4px", wordBreak: "break-word" }}>
            <Box component="span" sx={{ fontFamily: ESTATE_MONO, fontWeight: 600, color: ev.ink }}>
              {item.names.join(", ")}
            </Box>
            {item.more > 0 ? ` +${item.more} more` : ""}
          </Typography>
        ) : null}
      </Box>
      <Box sx={{ display: "flex", flexDirection: "column", gap: "6px", alignItems: { xs: "flex-start", sm: "flex-end" } }}>
        <Box
          component="a"
          href={item.href}
          sx={{
            display: "inline-flex",
            alignItems: "center",
            height: "28px",
            px: "10px",
            borderRadius: "8px",
            border: `1px solid ${ev.lineStrong}`,
            bgcolor: ev.surface,
            color: ev.ink,
            fontSize: "12.5px",
            fontWeight: 580,
            textDecoration: "none",
            whiteSpace: "nowrap",
            "&:hover": { borderColor: ev.ink2, textDecoration: "none" },
          }}
        >
          {item.linkLabel}
        </Box>
        <EvidenceStamp at={item.at} source={item.atLabel} now={now} missingLabel="UNKNOWN" />
      </Box>
    </Box>
  );
}

/**
 * The Brief's "Platform" group (concept row 29, System · Platform, "Findings in Brief"). Items come from
 * {@link PlatformFindings}; an empty list while both sources were read means checked and fine, and a source
 * that could not be read is already an UNKNOWN item in that list.
 */
export function EstatePlatformCard({ platform, now }: { readonly platform: PlatformFindings; readonly now: Date }) {
  const loading = platform.health.state === "loading" || platform.scan.state === "loading";
  const items = platform.items;
  const bothRead = platform.health.state === "ok" && platform.scan.state === "ok";

  return (
    <Box data-testid="estate-platform-card">
    <EstateCard>
      <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", px: "18px", pt: "14px", pb: "8px", flexWrap: "wrap" }}>
        <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
          Platform
        </Typography>
        <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
          device health and the daily security scan, from the stored reads
        </Typography>
      </Box>

      {loading && items.length === 0 ? (
        <Typography sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink2 }}>Reading platform status…</Typography>
      ) : items.length === 0 && bothRead ? (
        <Box sx={{ px: "18px", pb: "14px", display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }} data-testid="estate-platform-clear">
          <StateBadge state="ok" word="Checked" />
          <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>
            No device health problems and no open HIGH or MEDIUM scan findings in the stored reads.
          </Typography>
        </Box>
      ) : (
        GROUPS.map((g) => {
          const group = items.filter((i) => i.severity === g.severity);
          if (group.length === 0) return null;
          return (
            <Box key={g.severity}>
              <Box sx={{ px: "18px", pt: "12px", pb: "4px", display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
                <StateBadge state={g.badge} word={g.word} />
                <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>{g.note}</Typography>
              </Box>
              {group.map((item) => (
                <PlatformItem key={item.key} item={item} now={now} />
              ))}
            </Box>
          );
        })
      )}
    </EstateCard>
    </Box>
  );
}
