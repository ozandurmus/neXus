import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getSystemLogCrashFiles, getSystemLogGaps, getSystemLogSources } from "../auth/adminApi";
import { gapKindLabel } from "../screens/DeviceLogsPanel";
import type { SourceRead } from "../screens/overview/attentionRules";
import { EstateCard, EvidenceStamp, StateBadge } from "./EstatePrimitives";
import {
  buildDeviceLogsSummary,
  standardDeviceLogsHref,
  type DeviceLogsReads,
  type LogsReadPart,
} from "./estateDeviceLogsSummary";
import { ESTATE_MONO, ev } from "./estateTokens";

/**
 * Reads the three summary routes (sources, gaps, crash-file listing). Never calls `getSystemLogText` and never
 * rejects: a failed or refused read becomes an UNKNOWN part, so the other two figures still show.
 */
export async function loadDeviceLogsReads(deviceId: string): Promise<DeviceLogsReads> {
  // A payload without its list is a failed read too: the figure it feeds becomes UNKNOWN, never a crash.
  const part = <T,>(p: Promise<T>, listOf: (value: T) => readonly unknown[] | undefined): Promise<LogsReadPart<T>> =>
    p.then(
      (value) => (Array.isArray(listOf(value)) ? { ok: true as const, value } : { ok: false as const }),
      () => ({ ok: false as const }),
    );
  const [sources, gaps, crashFiles] = await Promise.all([
    part(getSystemLogSources(deviceId), (v) => v?.sources),
    part(getSystemLogGaps(deviceId), (v) => v?.gaps),
    part(getSystemLogCrashFiles(deviceId), (v) => v?.files),
  ]);
  return { sources, gaps, crashFiles };
}

const ALL_FAILED: DeviceLogsReads = {
  sources: { ok: false },
  gaps: { ok: false },
  crashFiles: { ok: false },
};

export function EstateDossierLogsBlock({
  deviceId,
  logs,
  now,
}: {
  readonly deviceId: string;
  /** `"loading"` until the one settled read lands; a non-ok settle means every figure is UNKNOWN. */
  readonly logs: SourceRead<DeviceLogsReads> | "loading";
  readonly now: Date;
}) {
  const header = (
    <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", px: "18px", pt: "14px", pb: "10px", flexWrap: "wrap" }}>
      <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
        Logs
      </Typography>
      <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
        read-only summary: counts and ages only, no log text
      </Typography>
    </Box>
  );
  const link = (
    <Box
      component="a"
      href={standardDeviceLogsHref(deviceId)}
      sx={{ fontSize: "12.5px", color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
    >
      Open full logs (standard view) →
    </Box>
  );

  if (logs === "loading") {
    return (
      <EstateCard sx={{ mt: "16px" }}>
        {header}
        <Typography sx={{ px: "18px", pb: "14px", fontSize: "12.5px", color: ev.ink3 }}>Reading log summary…</Typography>
      </EstateCard>
    );
  }

  const reads = logs.state === "ok" ? logs.value : ALL_FAILED;
  const s = buildDeviceLogsSummary(reads);

  const figureRow = (key: string, label: string, value: string | null, detail: ReactNode, at: string | null, source: string) => (
    <Box
      key={key}
      data-logs-figure={key}
      sx={{ display: "flex", alignItems: "baseline", gap: "10px", flexWrap: "wrap", py: "8px", borderTop: `1px solid ${ev.line}` }}
    >
      <Typography sx={{ fontSize: "12px", color: ev.ink3, fontWeight: 600, minWidth: "150px" }}>{label}</Typography>
      {value === null ? (
        <StateBadge state="unk" word="UNKNOWN" dense />
      ) : (
        <Typography sx={{ fontSize: "14px", fontWeight: 650, fontFamily: ESTATE_MONO }}>
          {value}
        </Typography>
      )}
      {detail}
      <Box sx={{ ml: "auto" }}>
        <EvidenceStamp at={at} source={source} now={now} />
      </Box>
    </Box>
  );

  const sourcesDetail = s.sourceCount === null ? null : (
    <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
      {s.chunkCount} {s.chunkCount === 1 ? "chunk" : "chunks"} stored
    </Typography>
  );
  const gapsDetail = s.gapCount === null ? null : (
    <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
      {s.gapKinds.length === 0
        ? "none recorded"
        : s.gapKinds.map((g) => `${gapKindLabel(g.kind)}${g.count > 1 ? ` ×${g.count}` : ""}`).join(" · ")}
      {s.gapsTruncated ? " · list truncated" : ""}
    </Typography>
  );
  const crashDetail = s.crashFileCount === null ? null : (
    <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
      present on the device{s.crashFilesTruncated ? " · list truncated" : ""}
    </Typography>
  );

  return (
    <EstateCard sx={{ mt: "16px" }}>
      <Box data-testid="dossier-logs-block">
        {header}
        <Box sx={{ px: "18px", pb: "8px", display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
          <StateBadge state={s.badge} word={s.badgeWord} dense />
          <Typography sx={{ fontSize: "14px", fontWeight: 650 }}>{s.headline}</Typography>
        </Box>
        <Box sx={{ px: "18px", pb: "8px" }}>
          {figureRow("sources", "Sources collected", s.sourceCount === null ? null : String(s.sourceCount), sourcesDetail,
            s.sourcesEvidenceAt, "newest chunk")}
          {figureRow("gaps", "Gaps", s.gapCount === null ? null : String(s.gapCount), gapsDetail, s.gapsEvidenceAt, "gap record")}
          {figureRow("crash_files", "Crash files", s.crashFileCount === null ? null : String(s.crashFileCount), crashDetail,
            s.crashEvidenceAt, "crash listing")}
        </Box>
        <Box sx={{ px: "18px", pb: "14px", pt: "4px" }}>{link}</Box>
      </Box>
    </EstateCard>
  );
}
