import { useState } from "react";
import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button, StatusChip } from "../shell/M3Widgets";
import { m3 } from "../theme/m3Theme";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import {
  getProjectPlan,
  type ApiError,
  type ProjectPlanBacklogItem,
  type ProjectPlanBuild,
  type ProjectPlanFeature,
  type ProjectPlanTrack,
  type ProjectPlanView,
} from "../auth/adminApi";
import type { Tone } from "../shell/tone";

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

/**
 * Earlier product defect #1 (WORKER.md, SESSION_CLOSE): the open-backlog
 * hero counted every item whose status was not "done", which counts
 * "deferred" and the two validated states as open. The fix uses the
 * terminal set instead.
 */
const TERMINAL_BACKLOG_STATUSES = new Set(["done", "automated_validated", "real_env_validated", "deferred"]);

const ROADMAP_STATUS_TONE: Record<string, Tone> = {
  done: "ok",
  complete: "ok",
  complete_with_followup: "ok",
  automated_validated: "ok",
  real_env_validated: "ok",
  in_progress: "attn",
  blocked: "bad",
  deferred: "warn",
};

function roadmapStatusTone(status: string | null | undefined): Tone {
  return ROADMAP_STATUS_TONE[status ?? ""] ?? "neutral";
}

function roadmapStatusLabel(status: string | null | undefined): string {
  const value = (status || "planned").replaceAll("_", " ");
  return value.replace(/\b\w/g, (c) => c.toUpperCase());
}

function formatPercent(value: number | null | undefined): string {
  return `${(value ?? 0).toFixed(1)}%`;
}

const cardSx = {
  bgcolor: m3.scLowest,
  boxShadow: m3.e1,
  borderRadius: "16px",
  p: 2.5,
  display: "flex",
  flexDirection: "column",
  gap: 1,
} as const;

function ProgressBar({ value }: { readonly value: number | null | undefined }) {
  const pct = Math.max(0, Math.min(100, value ?? 0));
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
      <Box sx={{ flex: 1, height: 8, borderRadius: 4, bgcolor: m3.scHigh, overflow: "hidden" }}>
        <Box sx={{ width: `${pct}%`, height: "100%", bgcolor: m3.primary, borderRadius: 4 }} />
      </Box>
      <Typography variant="body2" sx={{ minWidth: 44, textAlign: "right" }}>
        {formatPercent(pct)}
      </Typography>
    </Box>
  );
}

function Section({ title, children }: { readonly title: string; readonly children: ReactNode }) {
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 1.25 }}>
      <Typography variant="h3">{title}</Typography>
      {children}
    </Box>
  );
}

function HeroCards({ plan }: { readonly plan: ProjectPlanView }) {
  const openBacklogCount = plan.backlog.filter((item) => !TERMINAL_BACKLOG_STATUSES.has(item.status)).length;
  return (
    <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))" }}>
      <Card sx={cardSx}>
        <Typography variant="body2" sx={{ textTransform: "uppercase", letterSpacing: "0.5px" }}>
          Declared roadmap completion
        </Typography>
        <Typography variant="h1" aria-label="Declared roadmap completion percent">
          {formatPercent(plan.overall_progress_percent)}
        </Typography>
        <ProgressBar value={plan.overall_progress_percent} />
        <Typography variant="body2">
          Across the declared product roadmap. This is acceptance-criterion completion, not a time estimate.
        </Typography>
      </Card>
      <Card sx={cardSx}>
        <Typography variant="body2" sx={{ textTransform: "uppercase", letterSpacing: "0.5px" }}>
          Current major track
        </Typography>
        <Typography variant="h4">{plan.current_track ?? "—"}</Typography>
        <Typography variant="h2" aria-label="Current track completion percent">
          {formatPercent(plan.current_track_progress_percent)}
        </Typography>
        <ProgressBar value={plan.current_track_progress_percent} />
        <Typography variant="body2">
          {plan.progress_contract ?? "Roadmap progress is computed from completed acceptance criteria."}
        </Typography>
      </Card>
      <Card sx={cardSx}>
        <Typography variant="body2" sx={{ textTransform: "uppercase", letterSpacing: "0.5px" }}>
          Open backlog
        </Typography>
        <Typography variant="h1" aria-label="Open backlog count">{openBacklogCount}</Typography>
        <Stack spacing={0.5}>
          {Object.entries(plan.backlog_counts).map(([status, count]) => (
            <Box key={status} sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
              <Typography variant="body2">{roadmapStatusLabel(status)}</Typography>
              <StatusChip tone={roadmapStatusTone(status)} label={String(count)} dense />
            </Box>
          ))}
        </Stack>
      </Card>
    </Box>
  );
}

function NowNextSection({ plan }: { readonly plan: ProjectPlanView }) {
  const nowNext = plan.now_next;
  const upcoming = nowNext.upcoming ?? [];
  return (
    <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))" }}>
      <Card sx={cardSx}>
        <Typography variant="body2" sx={{ fontWeight: 700 }}>NOW</Typography>
        <Typography variant="h4">{nowNext.now?.build ?? "—"}</Typography>
        <Typography variant="body1">{nowNext.now?.title ?? "Current build"}</Typography>
        {nowNext.now?.goal ? <Typography variant="body2">{nowNext.now.goal}</Typography> : null}
      </Card>
      <Card sx={cardSx}>
        <Typography variant="body2" sx={{ fontWeight: 700 }}>NEXT</Typography>
        <Typography variant="h4">{nowNext.next?.build ?? "—"}</Typography>
        <Typography variant="body1">{nowNext.next?.title ?? "Next milestone"}</Typography>
        {nowNext.next?.goal ? <Typography variant="body2">{nowNext.next.goal}</Typography> : null}
      </Card>
      <Card sx={cardSx}>
        <Typography variant="body2" sx={{ fontWeight: 700 }}>UPCOMING</Typography>
        <Stack spacing={1}>
          {upcoming.map((item, index) => (
            <Box key={`${item.build}-${index}`} sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
              <Typography variant="body2" sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {item.title}
              </Typography>
              <StatusChip tone={roadmapStatusTone(item.status)} label={roadmapStatusLabel(item.status)} dense />
            </Box>
          ))}
        </Stack>
      </Card>
    </Box>
  );
}

function TracksSection({
  tracks,
  currentTrackId,
}: {
  readonly tracks: ProjectPlanTrack[];
  readonly currentTrackId: string | null;
}) {
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const toggle = (id: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  return (
    <Stack spacing={1.5}>
      {tracks.map((track) => {
        const isCurrent = track.id === currentTrackId;
        const isExpanded = expanded.has(track.id);
        return (
          <Card
            key={track.id}
            sx={{ ...cardSx, boxShadow: "none", border: `1px solid ${isCurrent ? m3.primary : m3.outlineVar}` }}
          >
            <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 1 }}>
              <Box sx={{ minWidth: 0 }}>
                <Typography variant="body2">
                  {track.id}
                  {track.theme ? ` · ${track.theme}` : ""}
                </Typography>
                <Typography variant="h4">{track.title}</Typography>
              </Box>
              <Stack direction="row" spacing={0.75} alignItems="center">
                {isCurrent ? <StatusChip tone="ok" label="Current" dense /> : null}
                <StatusChip tone={roadmapStatusTone(track.status)} label={roadmapStatusLabel(track.status)} dense />
              </Stack>
            </Box>
            <ProgressBar value={track.progress_percent} />
            <Typography variant="body2">
              {track.done_features} complete feature{track.done_features === 1 ? "" : "s"} · {track.feature_count} tracked
            </Typography>
            <Box
              role="button"
              tabIndex={0}
              aria-expanded={isExpanded}
              onClick={() => toggle(track.id)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") toggle(track.id);
              }}
              sx={{ cursor: "pointer" }}
            >
              <Typography variant="body2" sx={{ color: m3.primary, fontWeight: 500 }}>
                {isExpanded ? "Hide feature map" : "Show feature map"}
              </Typography>
            </Box>
            {isExpanded && (
              <Stack spacing={1}>
                {track.features.map((feature) => (
                  <Box
                    key={feature.id}
                    sx={{
                      display: "flex",
                      justifyContent: "space-between",
                      alignItems: "center",
                      gap: 1,
                      px: 1.5,
                      py: 1,
                      border: "1px solid",
                      borderColor: "divider",
                      borderRadius: 1.5,
                    }}
                  >
                    <Box sx={{ minWidth: 0 }}>
                      <Typography variant="body2" sx={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                        {feature.title}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {feature.target || feature.introduced || ""}
                      </Typography>
                    </Box>
                    <Stack direction="row" spacing={1} alignItems="center">
                      <StatusChip tone={roadmapStatusTone(feature.status)} label={roadmapStatusLabel(feature.status)} dense />
                      <Typography variant="body2">{formatPercent(feature.progress_percent)}</Typography>
                    </Stack>
                  </Box>
                ))}
              </Stack>
            )}
          </Card>
        );
      })}
    </Stack>
  );
}

/** WORKER.md AC-4: a group is expanded by default when it holds a P0 or an in_progress row. */
function backlogDefaultExpandedCategories(groups: ReadonlyMap<string, readonly ProjectPlanBacklogItem[]>): Set<string> {
  const result = new Set<string>();
  for (const [category, rows] of groups) {
    if (rows.some((row) => row.priority === "P0" || row.status === "in_progress")) {
      result.add(category);
    }
  }
  return result;
}

function BacklogSection({ items }: { readonly items: ProjectPlanBacklogItem[] }) {
  const groups = new Map<string, ProjectPlanBacklogItem[]>();
  for (const item of items) {
    const category = item.category || "Other";
    const rows = groups.get(category) ?? [];
    rows.push(item);
    groups.set(category, rows);
  }
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => backlogDefaultExpandedCategories(groups));
  const toggle = (category: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(category)) next.delete(category);
      else next.add(category);
      return next;
    });
  };
  return (
    <Stack spacing={1.25}>
      {[...groups.entries()].map(([category, rows]) => {
        const isExpanded = expanded.has(category);
        return (
          <Box key={category} sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
            <Box
              role="button"
              tabIndex={0}
              aria-expanded={isExpanded}
              aria-label={category}
              onClick={() => toggle(category)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") toggle(category);
              }}
              sx={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                cursor: "pointer",
                bgcolor: m3.scHigh,
                borderRadius: 2,
                px: 1.5,
                py: 1,
              }}
            >
              <Typography variant="body2">{category}</Typography>
              <StatusChip tone="neutral" label={String(rows.length)} dense />
            </Box>
            {isExpanded && (
              <Stack spacing={1}>
                {rows.map((row) => (
                  <Box
                    key={row.id}
                    sx={{
                      display: "flex",
                      flexDirection: "column",
                      gap: 0.25,
                      px: 1.5,
                      py: 1,
                      border: "1px solid",
                      borderColor: "divider",
                      borderRadius: 1.5,
                    }}
                  >
                    <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1 }}>
                      <Typography variant="body2">{row.title}</Typography>
                      <Stack direction="row" spacing={0.75} alignItems="center">
                        {row.priority ? <StatusChip tone="neutral" label={row.priority} dense /> : null}
                        <StatusChip tone={roadmapStatusTone(row.status)} label={roadmapStatusLabel(row.status)} dense />
                      </Stack>
                    </Box>
                    <Typography variant="caption" color="text.secondary">
                      Target: {row.target || "Unscheduled"}
                    </Typography>
                    {row.note ? <Typography variant="body2">{row.note}</Typography> : null}
                  </Box>
                ))}
              </Stack>
            )}
          </Box>
        );
      })}
    </Stack>
  );
}

function CompletedFeaturesSection({ features }: { readonly features: ProjectPlanFeature[] }) {
  return (
    <Stack spacing={1.25}>
      {features.map((feature) => (
        <Card key={feature.id} sx={{ ...cardSx, boxShadow: "none", border: `1px solid ${m3.outlineVar}` }}>
          <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 1 }}>
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="body2">{feature.introduced || "Delivered"}</Typography>
              <Typography variant="h4">{feature.title}</Typography>
            </Box>
            <StatusChip tone="ok" label="Complete" dense />
          </Box>
          {feature.summary ? <Typography variant="body2">{feature.summary}</Typography> : null}
          {feature.why ? (
            <Box>
              <Typography variant="body2" sx={{ fontWeight: 500 }}>Why it matters</Typography>
              <Typography variant="body2">{feature.why}</Typography>
            </Box>
          ) : null}
          {feature.evidence ? (
            <Box>
              <Typography variant="body2" sx={{ fontWeight: 500 }}>Validated evidence</Typography>
              <Typography variant="body2">{feature.evidence}</Typography>
            </Box>
          ) : null}
        </Card>
      ))}
    </Stack>
  );
}

function BuildHistorySection({
  builds,
  archivedCount,
}: {
  readonly builds: ProjectPlanBuild[];
  readonly archivedCount: number;
}) {
  return (
    <Stack spacing={1}>
      {builds.map((build) => (
        <Box
          key={build.build}
          sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1.5, px: 1.5, py: 1, border: "1px solid", borderColor: "divider", borderRadius: 1.5 }}
        >
          <Typography variant="body2" sx={{ minWidth: 100 }}>{build.build}</Typography>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="body2" sx={{ fontWeight: 500 }}>{build.title}</Typography>
            {build.summary ? (
              <Typography variant="caption" color="text.secondary">{build.summary}</Typography>
            ) : null}
          </Box>
          <StatusChip tone={roadmapStatusTone(build.status)} label={roadmapStatusLabel(build.status)} dense />
        </Box>
      ))}
      {archivedCount > 0 ? (
        <Typography variant="caption" color="text.secondary">
          {archivedCount} build{archivedCount === 1 ? "" : "s"} archived
        </Typography>
      ) : null}
    </Stack>
  );
}

function WarningsAndNotesSection({ warnings, notes }: { readonly warnings: string[]; readonly notes: string[] }) {
  return (
    <Stack spacing={1.5}>
      {warnings.length > 0 ? (
        <Card sx={{ bgcolor: m3.warningContainer, borderRadius: "16px", p: 2, display: "flex", flexDirection: "column", gap: 0.5, boxShadow: "none" }}>
          <Typography variant="h4" sx={{ color: m3.onWarningContainer }}>Roadmap metadata warning</Typography>
          {warnings.map((warning, index) => (
            <Typography key={index} variant="body2" sx={{ color: m3.onWarningContainer }}>
              {warning}
            </Typography>
          ))}
        </Card>
      ) : null}
      <Stack spacing={0.75}>
        {notes.map((note, index) => (
          <Box key={index} sx={{ display: "flex", gap: 1 }}>
            <Typography variant="body2">•</Typography>
            <Typography variant="body2">{note}</Typography>
          </Box>
        ))}
      </Stack>
    </Stack>
  );
}

/**
 * The Administration screen's "Project plan" tab (movement NXS-LOCAL-0174),
 * backed by a real `GET /project-plan` fetch. Reproduces the earlier
 * product's layout -- three hero cards, now/next/upcoming, major tracks
 * with collapsible feature maps, backlog and technical debt grouped by
 * category, completed features, build history and a metadata-warning block
 * followed by the roadmap notes -- in this product's own Material 3
 * components, with no role-conditional rendering.
 */
export function ProjectPlanPanel() {
  const { data: plan, error, refresh } = useFetchOnMount(() => getProjectPlan(), describeApiError);

  if (error) {
    return (
      <EmptyPanel title="Project plan unavailable" body={error}>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>
        </Box>
      </EmptyPanel>
    );
  }

  if (plan === null) {
    return <EmptyPanel title="Project plan" body="Loading…" />;
  }

  return (
    <Stack spacing={3}>
      <HeroCards plan={plan} />
      <Section title="Now / Next / Upcoming">
        <NowNextSection plan={plan} />
      </Section>
      <Section title="Major tracks">
        <TracksSection tracks={plan.tracks} currentTrackId={plan.current_track} />
      </Section>
      <Section title="Backlog & technical debt">
        <BacklogSection items={plan.backlog} />
      </Section>
      <Section title="Completed features">
        <CompletedFeaturesSection features={plan.completed_features} />
      </Section>
      <Section title="Build history">
        <BuildHistorySection builds={plan.build_history} archivedCount={plan.archived_build_count} />
      </Section>
      <WarningsAndNotesSection warnings={plan.metadata_warnings} notes={plan.roadmap_notes} />
    </Stack>
  );
}
