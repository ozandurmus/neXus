import { useMemo } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getLifecycleAdvisories, type LifecycleAdvisory } from "../auth/adminApi";
import { isRestricted } from "../shell/States";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { standardLifecycleHref } from "./estateRouting";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";

/** Advisory rows shown before "+N more" (the same cap the standard Lifecycle view uses). */
export const LIFECYCLE_ADVISORY_CAP = 50;

type BadgeState = "act" | "watch" | "ok" | "unk" | "na";

export interface LifecycleAdvisoryRow {
  readonly advisoryId: string;
  readonly title: string;
  readonly source: string;
  readonly state: BadgeState;
  readonly word: string;
  readonly affected: number | null;
  readonly notEvaluable: number | null;
  readonly evidenceAt: string | null;
}

/**
 * The row state of one advisory. Fail closed: a missing device tally, an advisory the server flagged for review, an
 * unknown severity, or a device that was not judged never reads as OK.
 */
export function lifecycleAdvisoryRow(a: LifecycleAdvisory): LifecycleAdvisoryRow {
  const devices = a.devices;
  const base = {
    advisoryId: a.advisory_id,
    title: a.title || "Untitled advisory",
    source: a.source || "UNKNOWN",
    evidenceAt: a.updated_on ?? a.published_on ?? null,
  };
  if (!devices || typeof devices.affected !== "number") {
    return { ...base, state: "unk", word: "UNKNOWN", affected: null, notEvaluable: null };
  }
  const pending = devices.pending ?? 0;
  const counts = { affected: devices.affected, notEvaluable: devices.not_evaluable };
  if (a.parse_state !== "PARSED" || a.severity === "UNKNOWN") {
    return { ...base, ...counts, state: "unk", word: "UNKNOWN" };
  }
  if (devices.affected > 0) {
    return a.exploited
      ? { ...base, ...counts, state: "act", word: "Affected · exploited" }
      : { ...base, ...counts, state: "watch", word: "Affected" };
  }
  if (devices.not_evaluable > 0 || pending > 0) {
    return { ...base, ...counts, state: "unk", word: "UNKNOWN" };
  }
  return { ...base, ...counts, state: "ok", word: "OK" };
}

function describeError(err: unknown): string {
  return String((err as { message?: string } | null)?.message ?? err);
}

function AdvisoryRowView({ row }: { readonly row: LifecycleAdvisoryRow }) {
  return (
    <Box
      component="li"
      data-testid="estate-lifecycle-row"
      data-state={row.state}
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
        <Typography sx={{ fontWeight: 600, color: ev.ink, overflowWrap: "anywhere" }}>{row.title}</Typography>
        <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "11.5px", color: ev.ink3 }}>
          {row.source} · End of support: UNKNOWN (not in the advisory feed)
        </Typography>
        <Box sx={{ mt: 0.5 }}>
          <EvidenceStamp at={row.evidenceAt} source="advisory" />
        </Box>
      </Box>
      <Box sx={{ display: "grid", justifyItems: "end", gap: "6px" }}>
        <StateBadge state={row.state} word={row.word} />
        <Typography data-testid="estate-lifecycle-count" sx={{ fontFamily: ESTATE_MONO, fontSize: "12px", color: ev.ink2 }}>
          {row.affected === null ? "devices affected: UNKNOWN" : `${row.affected} devices affected`}
          {row.notEvaluable ? ` · ${row.notEvaluable} not evaluable` : ""}
        </Typography>
        <Box
          component="a"
          href={standardLifecycleHref()}
          sx={{ fontSize: "12.5px", color: ev.ink2, textDecoration: "underline" }}
        >
          Open in standard view
        </Box>
      </Box>
    </Box>
  );
}

/** Read-only list of lifecycle advisories (the Estate counterpart of the standard Lifecycle advisories list). */
export function EstateLifecycleScreen() {
  const advisories = useFetchOnMount(getLifecycleAdvisories, describeError);
  const data = advisories.data;

  const rows = useMemo(() => {
    const list = data?.advisories ?? [];
    return list
      .map(lifecycleAdvisoryRow)
      .sort((x, y) => (y.affected ?? -1) - (x.affected ?? -1) || x.title.localeCompare(y.title));
  }, [data]);

  const header = (
    <Box sx={{ p: 2, borderBottom: `1px solid ${ev.line}` }}>
      <Typography component="h1" sx={{ fontFamily: ESTATE_SANS, fontSize: "22px", fontWeight: 650, color: ev.ink }}>
        Lifecycle advisories
      </Typography>
      <Typography sx={{ color: ev.ink2, fontSize: "13px", mt: 0.5 }}>
        Read-only. Counts are devices affected by each advisory, from the advisory feed. Changes happen in the standard view.
      </Typography>
      <Box sx={{ mt: 1 }}>
        <EvidenceStamp at={data?.as_of ?? null} source="advisory list" />
      </Box>
    </Box>
  );

  if (advisories.error && !data) {
    const restricted = isRestricted(advisories.rawError);
    return (
      <Box data-testid="estate-lifecycle-screen"><EstateCard sx={{ overflow: "hidden" }}>
        {header}
        <Box sx={{ p: 2 }}>
          <StateBadge state={restricted ? "na" : "unk"} word={restricted ? "Restricted" : "UNKNOWN"} />
          <Typography sx={{ color: ev.ink2, mt: 1 }}>
            {restricted ? "Your role cannot read lifecycle advisories." : `Advisories could not be loaded: ${advisories.error}`}
          </Typography>
        </Box>
      </EstateCard></Box>
    );
  }

  if (!data) {
    return (
      <Box data-testid="estate-lifecycle-screen"><EstateCard sx={{ overflow: "hidden" }}>
        {header}
        <Typography sx={{ p: 2, color: ev.ink2 }}>Loading advisories…</Typography>
      </EstateCard></Box>
    );
  }

  const shown = rows.slice(0, LIFECYCLE_ADVISORY_CAP);

  return (
    <Box data-testid="estate-lifecycle-screen"><EstateCard sx={{ overflow: "hidden" }}>
      {header}
      {rows.length === 0 ? (
        <Typography sx={{ p: 2, color: ev.ink2 }}>No advisories returned. This is not a clean result on its own.</Typography>
      ) : (
        <Box component="ul" sx={{ m: 0, p: 0 }}>
          {shown.map((row) => (
            <AdvisoryRowView key={row.advisoryId} row={row} />
          ))}
          {rows.length > LIFECYCLE_ADVISORY_CAP ? (
            <Typography component="li" sx={{ listStyle: "none", px: 2, py: 1.25, color: ev.ink2, borderTop: `1px solid ${ev.line}` }}>
              <MoreFooter shown={shown.length} total={rows.length} noun="advisories" />
              <Box component="span" sx={{ display: "block" }}>open the standard view to see all</Box>
            </Typography>
          ) : null}
        </Box>
      )}
    </EstateCard></Box>
  );
}
