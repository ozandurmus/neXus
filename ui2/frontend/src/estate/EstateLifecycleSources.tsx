import type { ReactNode } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getLifecycleCatalogSources, type LifecycleCatalogFeed, type LifecycleCatalogSources } from "../auth/adminApi";
import { lifecycleSourceName } from "../screens/LifecycleSourcesPanel";
import { isRestricted } from "../shell/States";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { newestIso } from "./estateModel";
import { standardLifecycleHref } from "./estateRouting";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";

/** Online feed rows shown before "+N more". */
export const LIFECYCLE_SOURCE_CAP = 10;

type BadgeState = "act" | "watch" | "ok" | "unk";

function describeError(err: unknown): string {
  return String((err as { message?: string } | null)?.message ?? err);
}

/** The age cell of one feed. A null age is UNKNOWN and never a number or OK, whatever the recorded age state. */
export function lifecycleFeedAge(feed: LifecycleCatalogFeed): { state: BadgeState; word: string } {
  if (feed.age_days == null) return { state: "unk", word: "UNKNOWN" };
  const state: BadgeState = feed.age_state === "RED" ? "act" : feed.age_state === "AMBER" ? "watch" : "ok";
  return { state, word: `${feed.age_days} days` };
}

function lastRunText(feed: LifecycleCatalogFeed): string {
  if (!feed.last_run_outcome) return "No run recorded";
  return feed.last_run_reason ? `${feed.last_run_outcome} (${feed.last_run_reason})` : feed.last_run_outcome;
}

function FeedRow({ feed }: { readonly feed: LifecycleCatalogFeed }) {
  const age = lifecycleFeedAge(feed);
  return (
    <Box
      component="li"
      data-testid="estate-lifecycle-source-row"
      data-state={age.state}
      sx={{
        display: "grid",
        gridTemplateColumns: "minmax(0, 1fr) auto",
        gap: "6px 16px",
        alignItems: "center",
        py: 1.25,
        px: 2,
        borderTop: `1px solid ${ev.line}`,
        listStyle: "none",
      }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ fontWeight: 600, color: ev.ink, overflowWrap: "anywhere" }}>
          {lifecycleSourceName(feed.source)} (online)
        </Typography>
        <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "11.5px", color: ev.ink3 }}>
          Last good load: {feed.last_good_at ?? "Never loaded"} · Last run: {lastRunText(feed)}
        </Typography>
      </Box>
      <Box sx={{ display: "grid", justifyItems: "end", gap: "4px" }}>
        <StateBadge state={age.state} word={age.word} />
        <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "12px", color: ev.ink2 }}>
          {`${feed.entries} entries · ${feed.entries_needing_review} need review`}
        </Typography>
      </Box>
    </Box>
  );
}

function SourcesCard({ children }: { readonly children: ReactNode }) {
  return (
    <Box data-testid="estate-lifecycle-sources" sx={{ mt: 2 }}>
      <EstateCard sx={{ overflow: "hidden" }}>
        <Box sx={{ p: 2, borderBottom: `1px solid ${ev.line}` }}>
          <Typography component="h2" sx={{ fontFamily: ESTATE_SANS, fontSize: "18px", fontWeight: 650, color: ev.ink }}>
            Vendor sources
          </Typography>
          <Typography sx={{ color: ev.ink2, fontSize: "13px", mt: 0.5 }}>
            Read-only. Changes happen in the standard view.{" "}
            <Box component="a" href={standardLifecycleHref()} sx={{ color: ev.ink2, textDecoration: "underline" }}>
              Open sources in standard view
            </Box>
          </Typography>
        </Box>
        {children}
      </EstateCard>
    </Box>
  );
}

function SourcesBody({ data }: { readonly data: LifecycleCatalogSources }) {
  const shown = data.feeds.slice(0, LIFECYCLE_SOURCE_CAP);
  const pageForms = data.page_forms.length;
  return (
    <>
      <Box sx={{ p: 2 }}>
        <EvidenceStamp at={newestIso(data.feeds.map((f) => f.last_good_at))} source="vendor sources" />
      </Box>
      {data.feeds.length === 0 ? (
        <Typography sx={{ px: 2, pb: 2, color: ev.ink2 }}>No online sources are configured. This is not a clean result on its own.</Typography>
      ) : (
        <Box component="ul" sx={{ m: 0, p: 0 }}>
          {shown.map((feed) => (
            <FeedRow key={feed.source} feed={feed} />
          ))}
          {data.feeds.length > LIFECYCLE_SOURCE_CAP ? (
            <Typography component="li" sx={{ listStyle: "none", px: 2, py: 1.25, color: ev.ink2, borderTop: `1px solid ${ev.line}` }}>
              <MoreFooter shown={shown.length} total={data.feeds.length} noun="sources" />
            </Typography>
          ) : null}
        </Box>
      )}
      <Typography sx={{ px: 2, py: 1.25, fontSize: "12.5px", color: ev.ink3, borderTop: `1px solid ${ev.line}` }}>
        {`${pageForms} vendor pages saved for upload (not shown here)`}
      </Typography>
    </>
  );
}

/**
 * Read-only vendor sources of the lifecycle catalog (the Estate counterpart of the standard panel's online-source table):
 * per online feed its last good load, age, last run outcome, entry count and need-review count. No upload, no write control.
 */
export function EstateLifecycleSources() {
  const sources = useFetchOnMount(getLifecycleCatalogSources, describeError);
  const data = sources.data;

  if (sources.error && !data) {
    const restricted = isRestricted(sources.rawError);
    return (
      <SourcesCard>
        <Box sx={{ p: 2 }}>
          <StateBadge state={restricted ? "na" : "unk"} word={restricted ? "Restricted" : "UNKNOWN"} />
          <Typography sx={{ color: ev.ink2, mt: 1 }}>
            {restricted ? "Your role cannot read lifecycle sources." : `Sources could not be loaded: ${sources.error}`}
          </Typography>
        </Box>
      </SourcesCard>
    );
  }

  if (!data) {
    return (
      <SourcesCard>
        <Typography sx={{ p: 2, color: ev.ink2 }}>Loading sources…</Typography>
      </SourcesCard>
    );
  }

  return (
    <SourcesCard>
      <SourcesBody data={data} />
    </SourcesCard>
  );
}
