import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getSecurityScanSummary, type SecurityScanSummaryView } from "../auth/adminApi";
import { isRestricted } from "../shell/States";
import { formatStamp } from "../shell/time";
import { EstateCard, EvidenceStamp, StateBadge } from "./EstatePrimitives";
import { PLATFORM_STATUS_HREF, type PlatformReadState } from "./estatePlatformFindings";
import {
  SCAN_COUNT_CAPTION,
  openSeverityCounts,
  scanCardStatus,
  severityCountLine,
} from "./estateSecurityScanModel";
import { ESTATE_MONO, ev } from "./estateTokens";

/** The standard view that holds the full scan detail. */
const STANDARD_SCAN_HREF = `${PLATFORM_STATUS_HREF}&standard=1`;

/**
 * Brief card "Daily security scan" (read-only, standard-view parity with SecurityScanCard). Reads the latest
 * summary once on mount. Shows the scan date as an evidence stamp, the open counts per severity named by severity,
 * and explicit loading / failed / restricted / no-scan states. A failed read is never shown as clean.
 */
export function EstateSecurityScanCard({ now }: { readonly now: Date }) {
  const [read, setRead] = useState<PlatformReadState<SecurityScanSummaryView>>({ state: "loading" });

  useEffect(() => {
    let live = true;
    getSecurityScanSummary()
      .then((value) => { if (live) setRead({ state: "ok", value }); })
      .catch((err: unknown) => {
        if (live) setRead(isRestricted(err) ? { state: "restricted" } : { state: "failed" });
      });
    return () => { live = false; };
  }, []);

  return (
    <Box data-testid="estate-security-scan-card">
      <EstateCard>
        <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", px: "18px", pt: "14px", pb: "8px", flexWrap: "wrap" }}>
          <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
            Daily security scan
          </Typography>
          <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
            the latest daily scan summary, counts and date only
          </Typography>
        </Box>
        <ScanBody read={read} now={now} />
      </EstateCard>
    </Box>
  );
}

function ScanBody({
  read,
  now,
}: {
  readonly read: PlatformReadState<SecurityScanSummaryView>;
  readonly now: Date;
}) {
  if (read.state === "loading") {
    return (
      <Typography data-testid="estate-scan-loading" sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink2 }}>
        Reading the latest scan summary…
      </Typography>
    );
  }

  const status = scanCardStatus(read, now);
  const view = status.kind === "populated" && read.state === "ok" ? read.value : null;
  const counts = view ? openSeverityCounts(view) : null;

  return (
    <Box sx={{ px: "18px", pb: "14px", display: "flex", flexDirection: "column", gap: "8px" }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
        <StateBadge state={status.badge} word={status.word} />
        {status.kind === "populated" ? (
          <EvidenceStamp at={status.at} source="scan" now={now} missingLabel="UNKNOWN" />
        ) : null}
      </Box>

      <Typography data-testid="estate-scan-headline" sx={{ fontSize: "13px", color: ev.ink2 }}>
        {status.headline}
      </Typography>

      {status.kind === "populated" && status.at ? (
        <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }} data-testid="estate-scan-date">
          Scan finished {formatStamp(status.at)}
        </Typography>
      ) : null}

      {counts ? (
        <Box data-testid="estate-scan-counts">
          <Typography sx={{ fontSize: "13px", fontFamily: ESTATE_MONO, fontWeight: 600, color: ev.ink }}>
            {severityCountLine(counts)}
          </Typography>
          <Typography sx={{ fontSize: "12px", color: ev.ink3 }}>{SCAN_COUNT_CAPTION}</Typography>
        </Box>
      ) : null}

      {status.kind === "populated" && view && counts === null ? (
        <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }} data-testid="estate-scan-counts-unknown">
          Severity counts are not in this summary: UNKNOWN, not zero.
        </Typography>
      ) : null}

      <Box>
        <Box
          component="a"
          href={STANDARD_SCAN_HREF}
          sx={{ fontSize: "12.5px", fontWeight: 580, color: ev.ink, textDecoration: "underline" }}
        >
          Open platform status (standard view)
        </Box>
      </Box>
    </Box>
  );
}
