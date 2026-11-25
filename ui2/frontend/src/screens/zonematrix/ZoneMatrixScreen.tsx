import { useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ScreenRoot } from "../../shell/ScreenLayout";
import { M3Button, M3Tabs, StatusChip } from "../../shell/M3Widgets";
import { RestrictedPanel, StatePanel, Ts, isRestricted } from "../../shell/States";
import { actorLabel } from "../../shell/presenters";
import { useFetchOnMount } from "../../shell/useFetchOnMount";
import { m3 } from "../../theme/m3Theme";
import { RADIUS } from "../../theme/tokens";
import { MatrixTab } from "./MatrixTab";
import { ZonesTab } from "./ZonesTab";
import { VersionsTab } from "./VersionsTab";
import { EvaluateTab } from "./EvaluateTab";
import { ViolationsTab, type ZonePairFilter } from "./ViolationsTab";
import { CoverageTab } from "./CoverageTab";
import { useZoneSummary } from "./zoneMatrixSummaryUi";
import {
  getZoneDraft, getZoneVersion, listZoneVersions, zoneErrorLines,
  type ZoneDraftResponse, type ZoneVersionDetail, type ZoneVersionSummary,
} from "./zoneMatrixApi";

/** The tab indexes the screen switches to itself; the order is the `tabs` list below. */
const TAB_MATRIX = 0;
const TAB_VIOLATIONS = 1;

/** What one load of the screen holds. `blocks` says whether this load asked for (and so shows) the raw blocks. */
export interface ZoneMatrixData {
  readonly draft: ZoneDraftResponse;
  readonly active: ZoneVersionDetail | null;
  readonly versions: readonly ZoneVersionSummary[];
  readonly blocks: boolean;
}

/** A message the screen shows above its tabs after an action: what happened, or why it was refused. */
export interface ZoneNotice {
  readonly kind: "done" | "refused";
  readonly lines: readonly string[];
}

/** What every tab gets: the data, a reload, a way to say something, and whether a read is running. */
export interface ZoneTabProps {
  readonly data: ZoneMatrixData;
  /** Reads the screen again; `hideBlocks` drops the raw blocks, so they are read again only when the user asks. */
  readonly refresh: (hideBlocks?: boolean) => void;
  readonly notify: (notice: ZoneNotice | null) => void;
  readonly reading: boolean;
}

/**
 * One load. The draft read comes first: it says whether a draft exists, and with `blocks=1` the server also reads the
 * blocks of the active version for the comparison (one audit row each). So when an editor looks at a draft, the active
 * version is read without blocks here; the Zones tab asks for its blocks only if the editor switches to it.
 */
async function load(blocks: boolean): Promise<ZoneMatrixData> {
  const draft = await getZoneDraft(blocks);
  const activeBlocks = blocks && !(draft.can.edit && draft.draft !== null);
  const [active, versions] = await Promise.all([
    draft.active ? getZoneVersion(draft.active.version_id, activeBlocks) : Promise.resolve(null),
    listZoneVersions(),
  ]);
  return { draft, active, versions: versions.versions ?? [], blocks };
}

export function Notice({ notice }: { readonly notice: ZoneNotice }) {
  const refused = notice.kind === "refused";
  return (
    <Box role={refused ? "alert" : "status"} data-notice={notice.kind}
      sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px`, px: 2, py: 1.25,
            bgcolor: refused ? m3.errorContainer : m3.scLow, color: refused ? m3.onErrorContainer : m3.onSurface }}>
      {notice.lines.map((line, index) => (
        <Typography key={index} variant="body2" sx={{ fontWeight: index === 0 ? 600 : 400 }}>{line}</Typography>
      ))}
    </Box>
  );
}

/**
 * The draft, said once above both tabs. The caller who may see it gets its revision and last editor; a reader the server
 * withholds it from is told only that a draft is being prepared.
 */
function DraftBanner({ data }: { readonly data: ZoneMatrixData }) {
  const { draft, draft_exists: exists } = data.draft;
  if (draft) {
    const last = draft.revisions.length > 0 ? draft.revisions[draft.revisions.length - 1] : null;
    return (
      <Box data-testid="draft-banner" sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 1.5, px: 2, py: 1,
        border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px`, bgcolor: m3.scLow }}>
        <StatusChip tone="attn" label={`Draft r${draft.revision}, not published`} />
        <Typography variant="body2">Changes apply only after another person publishes the draft.</Typography>
        {last ? (
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
            Last changed by <span title={actorLabel(last.actor).raw ?? undefined}>{actorLabel(last.actor).label}</span> <Ts at={last.occurred_at} />
          </Typography>
        ) : null}
      </Box>
    );
  }
  if (exists) {
    return (
      <Box data-testid="draft-banner" sx={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 1.5, px: 2, py: 1,
        border: `1px solid ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px`, bgcolor: m3.scLow }}>
        <StatusChip tone="attn" label="A draft is being prepared" />
        <Typography variant="body2">The zones below are the active version; the draft is shown to editors and publishers only.</Typography>
      </Box>
    );
  }
  return null;
}

/**
 * Zone matrix (ZONE_MATRIX_CONTRACT.md section 7.1), delivery steps 1 to 4: the Matrix, Violations, Zones, Versions,
 * Evaluate and Coverage tabs (the zone matrix grid, explicit cells, version default, named service sets, template import,
 * compliance reference confirmation, zones, draft diff with loosening changes, four-eyes publish flow, audited address
 * lookup, and on-demand deterministic evaluation of stored Check Point and Palo Alto policies against published
 * versions).
 *
 * The raw blocks and witness addresses are read only when the user asks, because the server writes an audit row for
 * every such read. Every control is drawn from the response's `can` booleans and `publish_check`. The "names masked"
 * badge is the shell's own, shown once any response came back masked.
 */
export function ZoneMatrixScreen() {
  const [wantBlocks, setWantBlocks] = useState(false);
  const [reading, setReading] = useState(false);
  const [notice, setNotice] = useState<ZoneNotice | null>(null);
  const [activeTab, setActiveTab] = useState(TAB_MATRIX);
  // The zone pair the Violations tab is narrowed to (a drilldown from a Matrix cell, or the filters there); `null` is all pairs.
  const [pair, setPair] = useState<ZonePairFilter | null>(null);
  const { data, error, rawError, refresh } = useFetchOnMount<ZoneMatrixData>(
    () => {
      setReading(true);
      // A refused blocks read must not leave the screen asking for blocks again on the next reload.
      return load(wantBlocks)
        .catch((err: unknown) => { if (wantBlocks) setWantBlocks(false); throw err; })
        .finally(() => setReading(false));
    },
    (err) => zoneErrorLines(err).join(" "),
  );
  const openCellViolations = (sourceZoneId: string, destinationZoneId: string) => {
    setPair({ sourceZoneId, destinationZoneId });
    setActiveTab(TAB_VIOLATIONS);
  };
  const reload = (hideBlocks = false) => { if (hideBlocks) setWantBlocks(false); refresh(); };
  const showBlocks = (next: boolean) => { setNotice(null); setWantBlocks(next); refresh(); };
  // The stored summary is read when the Violations or Coverage tab is first opened, not with the screen.
  const summary = useZoneSummary();
  const common = data === null ? null : { data, refresh: reload, notify: setNotice, reading };
  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Zone matrix"
        subtitle="Zones are sets of IP blocks the bank maintains. Statements and evaluations show policy intent as defined in management, not observed traffic." />
      {data === null || common === null
        ? (error === null ? <StatePanel variant="empty" title="Reading the zones…" announce="status" />
          : isRestricted(rawError) ? <RestrictedPanel area="Zone matrix" />
          : <StatePanel variant="error" title="The zones could not be read" body={error}
              action={<M3Button emphasis="outlined" onClick={() => refresh()}>Retry</M3Button>} />)
        : <>
          {error !== null ? (
            <Notice notice={{ kind: "refused", lines: ["The latest read failed; the screen shows the previous data.", error] }} />
          ) : null}
          {notice ? <Notice notice={notice} /> : null}
          <DraftBanner data={data} />
          <M3Tabs ariaLabel="Zone matrix views" value={activeTab} onChange={setActiveTab} tabs={[
            { label: "Matrix", panel: <MatrixTab {...common} summary={summary} onOpenCellViolations={openCellViolations} /> },
            { label: "Violations", panel: <ViolationsTab summary={summary} pair={pair} onPairChange={setPair} /> },
            { label: "Zones", panel: <ZonesTab {...common} onShowBlocks={showBlocks} /> },
            { label: "Versions", panel: <VersionsTab {...common} summary={summary} /> },
            { label: "Evaluate", panel: <EvaluateTab {...common} /> },
            { label: "Coverage", panel: <CoverageTab summary={summary} /> },
          ]} />
        </>}
    </ScreenRoot>
  );
}
