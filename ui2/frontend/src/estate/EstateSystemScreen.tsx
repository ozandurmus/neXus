import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import {
  getScheduledJobs,
  getSystemHealth,
  listAuditEvents,
  listDevices,
  type AuditEventView,
  type DeviceSummary,
  type ScheduledJobsView,
  type SystemHealth,
} from "../auth/adminApi";
import { isRestricted } from "../shell/States";
import type { SourceRead } from "../screens/overview/attentionRules";
import { EstateCard, EvidenceStamp, StateBadge } from "./EstatePrimitives";
import { buildSystemSummary, type SystemSection, type TimedRead } from "./estateSystemModel";
import { ESTATE_MONO, ev } from "./estateTokens";

type Settled<T> = TimedRead<T>;

/** One read per source; a failed read is recorded as failed (UNKNOWN), a 403-style refusal as restricted. */
function settle<T>(promise: Promise<T>): Promise<Settled<T>> {
  return promise.then(
    (value): Settled<T> => ({ read: { state: "ok", value }, readAt: new Date().toISOString() }),
    (err: unknown): Settled<T> => ({
      read: isRestricted(err) ? { state: "restricted" } : { state: "failed" },
      readAt: new Date().toISOString(),
    }),
  );
}

const SKIPPED = { read: { state: "skipped" } as SourceRead<never>, readAt: null };

function useSystemReads() {
  const [jobs, setJobs] = useState<Settled<ScheduledJobsView>>(SKIPPED);
  const [devices, setDevices] = useState<Settled<readonly DeviceSummary[]>>(SKIPPED);
  const [health, setHealth] = useState<Settled<SystemHealth>>(SKIPPED);
  const [audit, setAudit] = useState<Settled<readonly AuditEventView[]>>(SKIPPED);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let active = true;
    void settle(getScheduledJobs()).then((r) => { if (active) setJobs(r); });
    void settle(listDevices().then((r) => (Array.isArray(r?.devices) ? r.devices : Promise.reject(new Error("malformed")))))
      .then((r) => { if (active) setDevices(r); });
    void settle(getSystemHealth()).then((r) => { if (active) setHealth(r); });
    void settle(listAuditEvents().then((r) => (Array.isArray(r?.events) ? r.events : Promise.reject(new Error("malformed")))))
      .then((r) => { if (active) setAudit(r); });
    return () => { active = false; };
  }, [tick]);

  return { jobs, devices, health, audit, refresh: () => setTick((n) => n + 1) };
}

const LINE_LABEL_SX = { color: ev.ink2, fontSize: 12.5, lineHeight: 1.35 } as const;
const LINE_VALUE_SX = { fontFamily: ESTATE_MONO, fontSize: 13.5, fontWeight: 600, color: ev.ink, wordBreak: "break-word" } as const;

function SectionCard({ section, now }: { readonly section: SystemSection; readonly now: Date }) {
  return (
    <EstateCard sx={{ p: "16px 18px", display: "flex", flexDirection: "column", gap: "10px", minWidth: 0 }}>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1 }}>
        <Box component="h2" sx={{ m: 0, fontSize: 16, fontWeight: 700 }}>{section.title}</Box>
        <StateBadge state={section.badge} word={section.word} dense />
      </Box>
      <Box component="dl" sx={{ m: 0, display: "grid", gridTemplateColumns: "minmax(0, 1fr) auto", rowGap: "6px", columnGap: "12px" }}>
        {section.lines.map((line, i) => (
          <Box key={`${line.label}:${i}`} sx={{ display: "contents" }}>
            <Box component="dt" sx={{ ...LINE_LABEL_SX, m: 0 }}>{line.label}</Box>
            <Box component="dd" sx={{ ...LINE_VALUE_SX, m: 0, textAlign: "right" }}>{line.value}</Box>
          </Box>
        ))}
      </Box>
      {section.more ? <Box sx={{ fontSize: 12, color: ev.ink3 }}>{section.more}</Box> : null}
      <EvidenceStamp at={section.evidenceAt} source={section.source} now={now} />
      <Box component="a" href={section.standardHref} sx={{ mt: "auto", fontSize: 13, color: ev.accent, fontWeight: 600 }}>
        {section.standardLabel} →
      </Box>
    </EstateCard>
  );
}

/** Estate System screen: four compact read-only sections; every action opens the standard screen with `standard=1`. */
export function EstateSystemScreen() {
  const reads = useSystemReads();
  const [now] = useState(() => new Date());
  const sections = buildSystemSummary({
    jobs: reads.jobs,
    devices: reads.devices,
    health: reads.health,
    audit: reads.audit,
  });

  return (
    <Box component="main" aria-label="Estate system" sx={{ p: "20px 24px", display: "flex", flexDirection: "column", gap: "16px" }}>
      <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 2, flexWrap: "wrap" }}>
        <Box>
          <Box component="h1" sx={{ m: 0, fontSize: 24, fontWeight: 700, color: ev.ink }}>System</Box>
          <Box sx={{ fontSize: 13, color: ev.ink3 }}>
            Enrolment, what runs, platform health and the audit trail. Read-only; changes happen in the standard screens.
          </Box>
        </Box>
        <Box component="button" type="button" onClick={reads.refresh}
          sx={{ border: `1px solid ${ev.line}`, bgcolor: ev.surface, color: ev.ink, borderRadius: "999px", px: "12px", py: "4px", fontSize: 12.5, cursor: "pointer", fontFamily: "inherit" }}>
          Read again
        </Box>
      </Box>
      <Box sx={{ display: "grid", gap: "14px", gridTemplateColumns: "repeat(auto-fit, minmax(300px, 1fr))" }}>
        {sections.map((s) => <SectionCard key={s.id} section={s} now={now} />)}
      </Box>
    </Box>
  );
}
