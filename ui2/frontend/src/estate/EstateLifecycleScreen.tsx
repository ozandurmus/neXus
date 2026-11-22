import { useMemo, type ReactNode } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getLifecycleAdvisories, getLifecycleCatalog, type LifecycleAdvisory } from "../auth/adminApi";
import { isRestricted } from "../shell/States";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { EstateLifecycleSources } from "./EstateLifecycleSources";
import { standardLifecycleHref } from "./estateRouting";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";
import {
  LIFECYCLE_CATALOG_CAP,
  lifecycleCatalogCountText,
  lifecycleCatalogRows,
  type LifecycleCatalogRow,
} from "./estateLifecycleCatalogModel";

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
function LifecycleAdvisoryCard() {
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
    </EstateCard>
    <EstateLifecycleSources />
    </Box>
  );
}

function CatalogRowView({ row }: { readonly row: LifecycleCatalogRow }) {
  return (
    <Box
      component="li"
      data-testid="estate-catalog-row"
      data-status={row.status}
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
          {row.vendor} · {row.product}
        </Typography>
        <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "11.5px", color: ev.ink2 }}>
          {row.kind} · End of sale: {row.endOfSale} · End of support: {row.endOfSupport} · End of engineering: {row.endOfEngineering}
        </Typography>
        <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "11.5px", color: ev.ink3, overflowWrap: "anywhere" }}>
          Source: {row.source}
          {row.note ? ` · ${row.note}` : ""}
        </Typography>
        <Box sx={{ mt: 0.5 }}>
          <EvidenceStamp at={row.importedAt} source="catalog row" />
          <Typography component="span" sx={{ fontSize: "12px", color: ev.ink3, ml: "6px" }}>
            imported by {row.importedBy}
          </Typography>
        </Box>
      </Box>
      <Box sx={{ display: "grid", justifyItems: "end", gap: "6px" }}>
        <StateBadge state={row.status === "CURRENT" ? "na" : "unk"} word={row.status} />
        {row.reason ? (
          <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "11.5px", color: ev.ink2 }}>{row.reason}</Typography>
        ) : null}
      </Box>
    </Box>
  );
}

/**
 * Read-only operator catalog (the Estate counterpart of the standard Lifecycle catalog table). Rows are used before any
 * vendor entry for the same product. No import, save, edit or delete control: changes happen in the standard view.
 */
function OperatorCatalogSection() {
  const catalog = useFetchOnMount(getLifecycleCatalog, describeError);
  const data = catalog.data;

  const rows = useMemo(() => (data ? lifecycleCatalogRows(data.entries ?? [], new Date()) : []), [data]);
  const shown = rows.slice(0, LIFECYCLE_CATALOG_CAP);

  const header = (
    <Box sx={{ p: 2, borderBottom: `1px solid ${ev.line}` }}>
      <Typography component="h2" sx={{ fontFamily: ESTATE_SANS, fontSize: "18px", fontWeight: 650, color: ev.ink }}>
        Operator catalog
      </Typography>
      <Typography sx={{ color: ev.ink2, fontSize: "13px", mt: 0.5 }}>
        Read-only. Milestone dates are the operator's entries; a blank date is UNKNOWN. Age is the age of each row's import.
        Catalog is changed on the standard view.
      </Typography>
      <Box component="a" href={standardLifecycleHref()} sx={{ display: "inline-block", mt: 0.75, fontSize: "12.5px", color: ev.ink2, textDecoration: "underline" }}>
        Catalog is changed on the standard view
      </Box>
    </Box>
  );

  let body: ReactNode;
  if (catalog.error && !data) {
    body = <Typography sx={{ p: 2, color: ev.ink2 }}>Operator catalog could not be loaded: {catalog.error}</Typography>;
  } else if (!data) {
    body = <Typography sx={{ p: 2, color: ev.ink2 }}>Loading operator catalog…</Typography>;
  } else if (rows.length === 0) {
    body = <Typography sx={{ p: 2, color: ev.ink2 }}>No lifecycle catalog data</Typography>;
  } else {
    body = (
      <>
        <Typography data-testid="estate-catalog-count" sx={{ px: 2, pt: 1.25, fontFamily: ESTATE_MONO, fontSize: "12px", color: ev.ink2 }}>
          {lifecycleCatalogCountText(rows.length, shown.length)}
        </Typography>
        <Box component="ul" sx={{ m: 0, p: 0 }}>
          {shown.map((row) => (
            <CatalogRowView key={row.catalogId} row={row} />
          ))}
          {rows.length > shown.length ? (
            <Typography component="li" sx={{ listStyle: "none", px: 2, py: 1.25, color: ev.ink2, borderTop: `1px solid ${ev.line}` }}>
              <MoreFooter shown={shown.length} total={rows.length} noun="catalog rows" testId="estate-catalog-more" />
            </Typography>
          ) : null}
        </Box>
      </>
    );
  }

  return (
    <Box data-testid="estate-catalog-section" sx={{ mt: 2 }}>
      <EstateCard sx={{ overflow: "hidden" }}>
        {header}
        {body}
      </EstateCard>
    </Box>
  );
}

/** Estate lifecycle screen: the advisory list, then the read-only operator catalog. */
export function EstateLifecycleScreen() {
  return (
    <>
      <LifecycleAdvisoryCard />
      <OperatorCatalogSection />
    </>
  );
}
