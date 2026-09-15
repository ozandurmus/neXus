import { useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Tabs, StatusChip } from "../shell/M3Widgets";
import type { Tone } from "../shell/tone";
import { m3 } from "../theme/m3Theme";

export type JobState =
  | "REQUESTED" | "CLAIMED" | "EXECUTING" | "COMPLETED" | "FAILED"
  | "REJECTED" | "CANCELLED" | "OUTCOME_UNKNOWN" | "RECONCILED";

export interface JobStep {
  readonly stepIndex: number;
  readonly stepKind: string;
  readonly attemptNumber: number;
  readonly sentAt?: string;
  readonly matchedExpectation?: boolean | null;
  readonly errorClass?: string | null;
}

export interface VisibleJob {
  readonly jobId: string;
  readonly capability: string;
  readonly target: string;
  readonly state: JobState;
  readonly startedAt?: string;
  readonly duration?: string;
  readonly precheckReason?: string;
  readonly steps?: readonly JobStep[];
}

const PRESENTATION: Record<JobState, { label: string; tone: Tone; caption?: string }> = {
  REQUESTED: { label: "Queued", tone: "neutral" },
  CLAIMED: { label: "Claimed", tone: "neutral" },
  EXECUTING: { label: "Running", tone: "neutral" },
  COMPLETED: { label: "Succeeded", tone: "ok" },
  FAILED: { label: "Failed", tone: "bad" },
  REJECTED: { label: "Blocked", tone: "neutral" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
  OUTCOME_UNKNOWN: {
    label: "Outcome unknown",
    tone: "attn",
    caption: "a command may have reached the device; no automatic retry",
  },
  RECONCILED: { label: "Reconciled", tone: "neutral" },
};

const FILTERS = [
  { label: "Active", states: ["REQUESTED", "CLAIMED", "EXECUTING"] as readonly JobState[] },
  { label: "Needs you", states: ["REJECTED", "OUTCOME_UNKNOWN"] as readonly JobState[] },
  { label: "Archive", states: ["COMPLETED", "FAILED", "CANCELLED", "RECONCILED"] as readonly JobState[] },
] as const;

export function JobsPanel({ jobs = [] }: { readonly jobs?: readonly VisibleJob[] }) {
  const [selectedId, setSelectedId] = useState<string>();

  return (
    <M3Tabs
      ariaLabel="Job visibility"
      tabs={FILTERS.map((filter) => {
        const visible = jobs.filter((job) => filter.states.some((state) => state === job.state));
        const selected = visible.find((job) => job.jobId === selectedId) ?? visible[0];
        return {
          label: `${filter.label} (${visible.length})`,
          panel: (
            <Box sx={{ gap: 2, display: "flex", flexDirection: "column", pt: 2 }}>
              {visible.length === 0 ? (
                <EmptyPanel title="No jobs yet" body="No visible jobs match this view." />
              ) : (
                <Box sx={{ display: "grid", gridTemplateColumns: "minmax(280px, 1fr) minmax(0, 1.4fr)", gap: 2 }}>
                  <Card component="ul" aria-label={`${filter.label} jobs`} sx={{ m: 0, p: 0, listStyle: "none", boxShadow: "none", borderRadius: "16px", overflow: "hidden" }}>
                    {visible.map((job) => <JobRow key={job.jobId} job={job} selected={job.jobId === selected?.jobId} onSelect={setSelectedId} />)}
                  </Card>
                  <JobDetail job={selected} />
                </Box>
              )}
            </Box>
          ),
        };
      })}
    />
  );
}

function JobRow({ job, selected, onSelect }: { readonly job: VisibleJob; readonly selected: boolean; readonly onSelect: (jobId: string) => void }) {
  const presentation = PRESENTATION[job.state];
  return (
    <Box component="li">
      <Box component="button" type="button" onClick={() => onSelect(job.jobId)} aria-pressed={selected} sx={{ width: "100%", border: 0, textAlign: "left", p: 2, cursor: "pointer", bgcolor: selected ? m3.secondaryContainer : m3.surface }}>
        <Box sx={{ display: "flex", gap: 1, alignItems: "center" }}>
          <Typography sx={{ fontWeight: 600, flex: 1 }}>{job.capability}</Typography>
          <StatusChip label={presentation.label} tone={presentation.tone} dense />
        </Box>
        <Typography variant="body2">{job.target} · {job.duration ?? "Duration unknown"}</Typography>
      </Box>
    </Box>
  );
}

function JobDetail({ job }: { readonly job?: VisibleJob }) {
  if (!job) return null;
  const presentation = PRESENTATION[job.state];
  return (
    <Card sx={{ p: 2.5, boxShadow: "none", borderRadius: "16px", bgcolor: m3.scLow }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
        <Typography variant="h4" sx={{ flex: 1 }}>Job details</Typography>
        <StatusChip label={presentation.label} tone={presentation.tone} />
      </Box>
      <Typography variant="body2">{job.jobId} · {job.capability} · {job.target}</Typography>
      {presentation.caption ? <Typography variant="body2" sx={{ mt: 1 }}>{presentation.caption}</Typography> : null}
      {job.precheckReason ? <Typography variant="body2" sx={{ mt: 1 }}>{job.precheckReason}</Typography> : null}
      <Typography variant="h4" sx={{ mt: 2, mb: 1 }}>Step log</Typography>
      {job.steps?.length ? (
        <Box component="ol" sx={{ m: 0, pl: 2.5 }}>
          {[...job.steps].sort((a, b) => a.stepIndex - b.stepIndex).map((step) => (
            <li key={`${step.stepIndex}-${step.attemptNumber}`}>
              <Typography variant="body2">{step.stepKind} · attempt {step.attemptNumber}{step.errorClass ? ` · ${step.errorClass}` : ""}</Typography>
            </li>
          ))}
        </Box>
      ) : <Typography variant="body2">No server-reported steps.</Typography>}
    </Card>
  );
}
