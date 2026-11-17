import { Alert, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from "@mui/material";
import { getDeviceSoftwareHistory, getSoftwareChanges, type CpInstallEvent, type SoftwareChange, type SoftwareStretch } from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { M3Button } from "../shell/M3Widgets";
import { formatStamp } from "../shell/time";
import { PRODUCT_NAME } from "../brand/product";

const SOURCE_WORDS: Record<SoftwareStretch["source"], string> = { inventory: "Inventory", discovery_backfill: "Discovery history", platform_facts: "Platform facts" };
const hotfixText = (h: string | null | undefined) => h || "—";
/** The change window as one sentence; UNKNOWN when the version was first seen with no earlier observation. */
export function changeWindowText(s: Pick<SoftwareStretch, "window_start" | "window_end">): string {
  if (!s.window_start || !s.window_end) return "UNKNOWN (first version seen)";
  return `${formatStamp(s.window_start)} to ${formatStamp(s.window_end)}`;
}
/** The device prints its own clock with no offset: shown as it is, never converted to the display zone. */
export const deviceLocalStamp = (iso: string) => iso.replace("T", " ").slice(0, 19);
/** "Base install R81.20", "Jumbo R81.20 Take 119" or "Jumbo R81.20 (take not in log)". */
export function installEventText(e: Pick<CpInstallEvent, "kind" | "product_version" | "jumbo_take">): string {
  if (e.kind === "BASE_INSTALL") return `Base install ${e.product_version}`;
  return e.jumbo_take != null ? `Jumbo ${e.product_version} Take ${e.jumbo_take}` : `Jumbo ${e.product_version} (take not in log)`;
}
const INSTALL_CLOCK_NOTE = "Times are the device's own clock, as printed in its install log.";
const apiError = (e: unknown) => e instanceof Error ? e.message : "Software history could not be loaded";

/**
 * "When was this device upgraded?" from the product's own observations (V243). Each row is a stretch of one version; the
 * change window runs from the last time the previous version was still seen to the first time the new one was.
 */
export function DeviceSoftwareHistory({ deviceId }: { deviceId: string }) {
  const history = useFetchOnMount(() => getDeviceSoftwareHistory(deviceId), apiError);
  return <Stack spacing={1} aria-label="Software history" data-software-history>
    <Typography variant="subtitle1">Software history</Typography>
    {history.error && <Alert severity="error">{history.error}</Alert>}
    {!history.data && !history.error && <Typography variant="body2">Loading software history…</Typography>}
    {history.data && !history.data.stretches.length && <Typography variant="body2">UNKNOWN: no software version has been recorded for this device yet.</Typography>}
    {history.data && history.data.stretches.length > 0 && <>
      <TableContainer><Table size="small" aria-label="Software version history">
        <TableHead><TableRow>{["Version", "Hotfix / Jumbo", "First seen", "Last seen", "Change window", "Source"].map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
        <TableBody>{history.data.stretches.map((s, i) => <TableRow key={`${s.first_seen_at}-${i}`}>
          <TableCell>{s.software_version}{i === 0 ? " (current)" : ""}</TableCell><TableCell>{hotfixText(s.hotfix_level)}</TableCell>
          <TableCell>{formatStamp(s.first_seen_at)}</TableCell><TableCell>{formatStamp(s.last_seen_at)}</TableCell>
          <TableCell>{s.previous_software_version || s.previous_hotfix_level || s.window_start ? `from ${s.previous_software_version ?? "UNKNOWN"}${s.previous_hotfix_level ? ` (${s.previous_hotfix_level})` : ""}: ${changeWindowText(s)}` : changeWindowText(s)}</TableCell>
          <TableCell>{SOURCE_WORDS[s.source] ?? s.source}</TableCell>
        </TableRow>)}</TableBody>
      </Table></TableContainer>
      <Typography variant="caption">{`A change is dated by what ${PRODUCT_NAME} observed: it happened after the last time the previous version was seen and no later than the first time the new one was. It is not a vendor upgrade log.`}</Typography>
    </>}
    {history.data && (history.data.install_events?.length ?? 0) > 0 && <>
      <Typography variant="subtitle2">Check Point install log</Typography>
      <TableContainer><Table size="small" aria-label="Check Point install log">
        <TableHead><TableRow>{["Installed (device local time)", "What", "File"].map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
        <TableBody>{history.data.install_events!.map((e, i) => <TableRow key={`${e.file_name}-${e.installed_at_local}-${i}`}>
          <TableCell>{deviceLocalStamp(e.installed_at_local)}</TableCell><TableCell>{installEventText(e)}</TableCell><TableCell>{e.file_name}</TableCell>
        </TableRow>)}</TableBody>
      </Table></TableContainer>
      <Typography variant="caption">{INSTALL_CLOCK_NOTE}</Typography>
    </>}
  </Stack>;
}

const versionText = (v: string | null, h: string | null) => `${v ?? "UNKNOWN"}${h ? ` (${h})` : ""}`;

/** Fleet-wide software changes of the last 90 days, newest first (Lifecycle, "Recent upgrades"). */
export function RecentUpgradesPanel() {
  const changes = useFetchOnMount(() => getSoftwareChanges(90), apiError);
  const rows: SoftwareChange[] = changes.data?.changes ?? [];
  return <Stack spacing={1.5} aria-label="Recent upgrades">
    {changes.error && <Alert severity="error">{changes.error}<M3Button emphasis="tonal" onClick={changes.refresh}>Retry</M3Button></Alert>}
    {!changes.data && !changes.error && <Typography>Loading recent upgrades…</Typography>}
    {changes.data && <>
      <Typography variant="caption">Software version and hotfix changes {PRODUCT_NAME} observed in the last {changes.data.days} days. Each change is dated by a window: after the last time the previous version was seen, no later than the first time the new one was.</Typography>
      <TableContainer><Table size="small" aria-label="Recent software upgrades">
        <TableHead><TableRow>{["Device", "From", "To", "Change window"].map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
        <TableBody>
          {rows.map((c, i) => <TableRow key={`${c.device_id}-${c.first_seen_at}-${i}`}>
            <TableCell><a href={`?screen=inventory&device_id=${encodeURIComponent(c.device_id)}`}>{c.hostname || c.device_id}</a></TableCell>
            <TableCell>{versionText(c.from_version, c.from_hotfix_level)}</TableCell><TableCell>{versionText(c.to_version, c.to_hotfix_level)}</TableCell>
            <TableCell>{changeWindowText(c)}</TableCell>
          </TableRow>)}
          {!rows.length && <TableRow><TableCell colSpan={4}>No software changes observed in this period</TableCell></TableRow>}
        </TableBody>
      </Table></TableContainer>
      {(changes.data.install_events?.length ?? 0) > 0 && <>
        <Typography variant="subtitle2">Check Point installs (device install log)</Typography>
        <TableContainer><Table size="small" aria-label="Check Point installs">
          <TableHead><TableRow>{["Device", "Installed (device local time)", "What"].map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
          <TableBody>{changes.data.install_events!.map((e, i) => <TableRow key={`${e.device_id}-${e.file_name}-${e.installed_at_local}-${i}`}>
            <TableCell><a href={`?screen=inventory&device_id=${encodeURIComponent(e.device_id)}`}>{e.hostname || e.device_id}</a></TableCell>
            <TableCell>{deviceLocalStamp(e.installed_at_local)}</TableCell><TableCell>{installEventText(e)}</TableCell>
          </TableRow>)}</TableBody>
        </Table></TableContainer>
        <Typography variant="caption">{INSTALL_CLOCK_NOTE} These dates come from the install log on the device, not from what {PRODUCT_NAME} observed.</Typography>
      </>}
    </>}
  </Stack>;
}
