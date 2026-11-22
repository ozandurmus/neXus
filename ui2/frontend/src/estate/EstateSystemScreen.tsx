import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import {
  getJobStats,
  getScheduledJobs,
  getSystemHealth,
  getSystemPods,
  getSystemStorage,
  listAuditEvents,
  listDevices,
  listLocalIdentities,
  listScriptRuns,
  listScripts,
  listSessions,
  type AuditEventView,
  type DeviceSummary,
  type JobStatsView,
  type LocalIdentityView,
  type PodsView,
  type ScheduledJobsView,
  type ScriptSummary,
  type SessionView,
  type StorageView,
  type SystemHealth,
} from "../auth/adminApi";
import { isRestricted } from "../shell/States";
import type { SourceRead } from "../screens/overview/attentionRules";
import { EstateCard, EvidenceStamp, StateBadge } from "./EstatePrimitives";
import { mapJobQueue, JOB_QUEUE_NOT_REPORTED } from "./estateJobQueueModel";
import { BADGE_WORD_DEFAULT } from "./estateModel";
import { standardJobsHref } from "./estateRouting";
import {
  buildSystemSummary,
  SCRIPT_RUNS_READ_CAP,
  type ScriptRunRead,
  type SystemSection,
  type TimedRead,
} from "./estateSystemModel";
import { EstateSystemPodsStorage } from "./EstateSystemPodsStorage";
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
  const [identities, setIdentities] = useState<Settled<readonly LocalIdentityView[]>>(SKIPPED);
  const [sessions, setSessions] = useState<Settled<readonly SessionView[]>>(SKIPPED);
  const [scripts, setScripts] = useState<Settled<readonly ScriptSummary[]>>(SKIPPED);
  /** Runs per script for the first SCRIPT_RUNS_READ_CAP scripts; null while the runs are being read. */
  const [scriptRuns, setScriptRuns] = useState<readonly ScriptRunRead[] | null>(null);
  const [pods, setPods] = useState<Settled<PodsView>>(SKIPPED);
  const [storage, setStorage] = useState<Settled<StorageView>>(SKIPPED);
  const [jobStats, setJobStats] = useState<Settled<JobStatsView>>(SKIPPED);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let active = true;
    void settle(getScheduledJobs()).then((r) => { if (active) setJobs(r); });
    void settle(getJobStats()).then((r) => { if (active) setJobStats(r); });
    void settle(listDevices().then((r) => (Array.isArray(r?.devices) ? r.devices : Promise.reject(new Error("malformed")))))
      .then((r) => { if (active) setDevices(r); });
    void settle(getSystemHealth()).then((r) => { if (active) setHealth(r); });
    void settle(listAuditEvents().then((r) => (Array.isArray(r?.events) ? r.events : Promise.reject(new Error("malformed")))))
      .then((r) => { if (active) setAudit(r); });
    void settle(listLocalIdentities().then((r) => (Array.isArray(r?.identities) ? r.identities : Promise.reject(new Error("malformed")))))
      .then((r) => { if (active) setIdentities(r); });
    void settle(listSessions().then((r) => (Array.isArray(r?.sessions) ? r.sessions : Promise.reject(new Error("malformed")))))
      .then((r) => { if (active) setSessions(r); });
    void settle(listScripts().then((r) => (Array.isArray(r?.scripts) ? r.scripts : Promise.reject(new Error("malformed")))))
      .then((r) => { if (active) setScripts(r); });
    void settle(getSystemPods()).then((r) => { if (active) setPods(r); });
    void settle(getSystemStorage()).then((r) => { if (active) setStorage(r); });
    return () => { active = false; };
  }, [tick]);

  // The script list carries no last run, so runs are read per script, for the first SCRIPT_RUNS_READ_CAP scripts.
  useEffect(() => {
    if (scripts.read.state !== "ok") return;
    let active = true;
    setScriptRuns(null);
    const first = scripts.read.value.slice(0, SCRIPT_RUNS_READ_CAP);
    void Promise.all(first.map((script) =>
      settle(listScriptRuns(script.script_id).then((r) => (Array.isArray(r?.runs) ? r.runs : Promise.reject(new Error("malformed")))))
        .then((r): ScriptRunRead => ({ ...r, script })),
    )).then((rows) => { if (active) setScriptRuns(rows); });
    return () => { active = false; };
  }, [scripts]);

  return {
    jobs, jobStats, devices, health, audit, identities, sessions, pods, storage,
    scripts, scriptRuns,
    refresh: () => setTick((n) => n + 1),
  };
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
      <Box sx={{ mt: "auto", display: "flex", flexDirection: "column", gap: "4px" }}>
        {(section.extraLinks ?? []).map((link) => (
          <Box key={link.href} component="a" href={link.href} sx={{ fontSize: 13, color: ev.accent, fontWeight: 600 }}>
            {link.label} →
          </Box>
        ))}
        <Box component="a" href={section.standardHref} sx={{ fontSize: 13, color: ev.accent, fontWeight: 600 }}>
          {section.standardLabel} →
        </Box>
      </Box>
    </EstateCard>
  );
}

/** The script list read plus its runs; while the runs are in flight each listed script is shown as not yet read (UNKNOWN). */
function scriptsInput(scripts: Settled<readonly ScriptSummary[]>, runs: readonly ScriptRunRead[] | null) {
  if (scripts.read.state !== "ok") return { scripts, runs: [] };
  if (runs !== null) return { scripts, runs };
  const pending = scripts.read.value.slice(0, SCRIPT_RUNS_READ_CAP).map((script): ScriptRunRead => ({
    script, read: { state: "skipped" }, readAt: null,
  }));
  return { scripts, runs: pending };
}

/** Job queue: four read-only figures from one job-statistics read; each figure carries that read's own age. */
function JobQueueCard({ jobs, now }: { readonly jobs: Settled<JobStatsView>; readonly now: Date }) {
  const section = mapJobQueue(jobs);
  const readAt = jobs.readAt;
  const stamp = <EvidenceStamp at={readAt} source="job statistics read" now={now} />;
  return (
    <EstateCard sx={{ p: "16px 18px", display: "flex", flexDirection: "column", gap: "10px", minWidth: 0 }}>
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1 }}>
        <Box component="h2" sx={{ m: 0, fontSize: 16, fontWeight: 700 }}>Job queue</Box>
        {section.kind === "ok"
          ? <StateBadge state={section.badge} word={section.word} dense />
          : <StateBadge state="unk" word={BADGE_WORD_DEFAULT.unk} dense />}
      </Box>
      {section.kind === "unreadable" ? (
        <>
          <Box sx={{ fontSize: 13.5, fontWeight: 600, color: ev.ink }}>{section.text}</Box>
          {stamp}
        </>
      ) : (
        <Box component="dl" sx={{ m: 0, display: "grid", gridTemplateColumns: "minmax(0, 1fr) auto", rowGap: "8px", columnGap: "12px" }}>
          {section.figures.map((f) => (
            <Box key={f.id} sx={{ display: "contents" }}>
              <Box component="dt" sx={{ ...LINE_LABEL_SX, m: 0 }}>
                {f.label}
                <Box sx={{ mt: "2px" }}>{stamp}</Box>
              </Box>
              <Box component="dd" sx={{
                ...LINE_VALUE_SX, m: 0, textAlign: "right",
                ...(f.value === null ? { color: ev.ink3, fontWeight: 500, fontFamily: "inherit" } : {}),
              }}>
                {f.value ?? JOB_QUEUE_NOT_REPORTED}
              </Box>
            </Box>
          ))}
        </Box>
      )}
      <Box component="a" href={standardJobsHref()} sx={{ mt: "auto", fontSize: 13, color: ev.accent, fontWeight: 600 }}>
        Open failed jobs (standard view) →
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
    identities: reads.identities,
    sessions: reads.sessions,
    scripts: scriptsInput(reads.scripts, reads.scriptRuns),
  });

  return (
    <Box component="main" aria-label="Estate system" sx={{ p: "20px 24px", display: "flex", flexDirection: "column", gap: "16px" }}>
      <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 2, flexWrap: "wrap" }}>
        <Box>
          <Box component="h1" sx={{ m: 0, fontSize: 24, fontWeight: 700, color: ev.ink }}>System</Box>
          <Box sx={{ fontSize: 13, color: ev.ink3 }}>
            Enrolment, access counts, what runs, platform health and the audit trail. Read-only; changes happen in the standard screens.
          </Box>
        </Box>
        <Box component="button" type="button" onClick={reads.refresh}
          sx={{ border: `1px solid ${ev.line}`, bgcolor: ev.surface, color: ev.ink, borderRadius: "999px", px: "12px", py: "4px", fontSize: 12.5, cursor: "pointer", fontFamily: "inherit" }}>
          Read again
        </Box>
      </Box>
      <Box sx={{ display: "grid", gap: "14px", gridTemplateColumns: "repeat(auto-fit, minmax(300px, 1fr))" }}>
        {sections.slice(0, 1).map((s) => <SectionCard key={s.id} section={s} now={now} />)}
        <JobQueueCard jobs={reads.jobStats} now={now} />
        {sections.slice(1).map((s) => <SectionCard key={s.id} section={s} now={now} />)}
        <EstateSystemPodsStorage pods={reads.pods} storage={reads.storage} now={now} />
      </Box>
    </Box>
  );
}
