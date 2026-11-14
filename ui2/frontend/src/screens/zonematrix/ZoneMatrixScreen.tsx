import { useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { ScreenHeader, ScreenRoot } from "../../shell/ScreenLayout";
import { M3Button, M3Tabs } from "../../shell/M3Widgets";
import { RestrictedPanel, StatePanel, isRestricted } from "../../shell/States";
import { useFetchOnMount } from "../../shell/useFetchOnMount";
import { m3 } from "../../theme/m3Theme";
import { ZonesTab } from "./ZonesTab";
import { VersionsTab } from "./VersionsTab";
import {
  getZoneDraft, getZoneVersion, listZoneVersions, zoneErrorLines,
  type ZoneDraftResponse, type ZoneVersionDetail, type ZoneVersionSummary,
} from "./zoneMatrixApi";

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

async function load(blocks: boolean): Promise<ZoneMatrixData> {
  const draft = await getZoneDraft(blocks);
  const [active, versions] = await Promise.all([
    draft.active ? getZoneVersion(draft.active.version_id, blocks) : Promise.resolve(null),
    listZoneVersions(),
  ]);
  return { draft, active, versions: versions.versions ?? [], blocks };
}

export function Notice({ notice }: { readonly notice: ZoneNotice }) {
  const refused = notice.kind === "refused";
  return (
    <Box role={refused ? "alert" : "status"} data-notice={notice.kind}
      sx={{ border: `1px solid ${m3.outlineVar}`, borderRadius: "10px", px: 2, py: 1.25,
            bgcolor: refused ? m3.errorContainer : m3.scLow, color: refused ? m3.onErrorContainer : m3.onSurface }}>
      {notice.lines.map((line, index) => (
        <Typography key={index} variant="body2" sx={{ fontWeight: index === 0 ? 600 : 400 }}>{line}</Typography>
      ))}
    </Box>
  );
}

/**
 * Zone matrix (ZONE_MATRIX_CONTRACT.md section 7.1), delivery step 1: the zones (named sets of IP blocks), the draft, the
 * published versions and the four-eyes publish flow, plus the audited address lookup. There is no matrix statement,
 * cell or evaluation yet, so the screen has only the Zones and Versions tabs.
 *
 * The raw blocks are read only when the user asks ("Show blocks"), because the server writes an audit row for every
 * such read. Every control is drawn from the response's `can` booleans and `publish_check`.
 */
export function ZoneMatrixScreen() {
  const [wantBlocks, setWantBlocks] = useState(false);
  const [notice, setNotice] = useState<ZoneNotice | null>(null);
  const { data, error, rawError, refresh } = useFetchOnMount<ZoneMatrixData>(
    () => load(wantBlocks),
    (err) => zoneErrorLines(err).join(" "),
  );
  const setBlocks = (next: boolean) => { setNotice(null); setWantBlocks(next); refresh(); };
  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Zone matrix"
        subtitle="Zones are sets of IP blocks the bank maintains. The matrix statements arrive in the next delivery step." />
      {data === null
        ? (error === null ? <StatePanel variant="empty" title="Reading the zones…" announce="status" />
          : isRestricted(rawError) ? <RestrictedPanel area="Zone matrix" />
          : <StatePanel variant="error" title="The zones could not be read" body={error}
              action={<M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>} />)
        : <>
          {error !== null ? <Notice notice={{ kind: "refused", lines: ["The latest read failed; the screen shows the previous data.", error] }} /> : null}
          {notice ? <Notice notice={notice} /> : null}
          <M3Tabs ariaLabel="Zone matrix views" tabs={[
            { label: "Zones", panel: <ZonesTab data={data} onShowBlocks={setBlocks} refresh={refresh} notify={setNotice} /> },
            { label: "Versions", panel: <VersionsTab data={data} refresh={refresh} notify={setNotice} /> },
          ]} />
        </>}
    </ScreenRoot>
  );
}
