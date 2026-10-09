import { useState } from "react";
import { Alert, Box, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { getLifecycle, getDeviceLifecycle, type LifecycleDevice } from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { M3Button, M3Tabs } from "../shell/M3Widgets";
import { downloadText, toCsv } from "./DeviceShared";
import { LifecycleCatalogPanel, lifecycleError } from "./LifecycleCatalogPanel";

const cards = [["devices", "Devices"], ["past_end_of_support", "Past end of support"], ["within_180_days", "Within 180 days"], ["within_365_days", "Within 365 days"], ["no_lifecycle_data", "No lifecycle data"], ["licenses_within_60_days", "Licenses within 60 days"]];
const headings = ["Device / cluster", "Vendor", "Model", "Version", "Hardware end of support", "Software end of support", "Next milestone", "Nearest license expiry", "Risk", "Match basis"];
const shown = (value: string | null | undefined) => value || "UNKNOWN";
export function lifecycleCells(d: LifecycleDevice): string[] {
  return [shown(d.hostname) + (d.cluster_member_ref ? ` · ${d.cluster_member_ref}` : ""), d.vendor, shown(d.model), shown(d.software_version),
    shown(d.hardware.catalog?.end_of_support), shown(d.software.catalog?.end_of_support),
    d.next_milestone ? `${d.next_milestone.name}: ${d.next_milestone.date} (${d.next_milestone.days_remaining} days)` : "UNKNOWN",
    d.nearest_license?.expiry ?? "UNKNOWN", d.risk,
    [d.hardware.basis ?? d.hardware.reason, d.software.basis ?? d.software.reason].filter(Boolean).join(" · ")];
}
export function lifecycleCsv(devices: LifecycleDevice[]) {
  return toCsv([[...headings, "Hardware end of sale", "Hardware end of engineering", "Software end of sale", "Software end of engineering", "License details", "License collection", "Support contract collection"],
    ...devices.map(d => [...lifecycleCells(d), d.hardware.catalog?.end_of_sale, d.hardware.catalog?.end_of_engineering,
      d.software.catalog?.end_of_sale, d.software.catalog?.end_of_engineering,
      d.licenses.map(l => [l.name, l.kind, l.member, l.expiry ?? l.label, l.status].filter(Boolean).join(" · ")).join("; "),
      d.license_status, d.support_contract_status])]);
}

export function LifecycleScreen() {
  const fleet = useFetchOnMount(getLifecycle, lifecycleError);
  const [search, setSearch] = useState("");
  const [vendor, setVendor] = useState("all");
  const [risk, setRisk] = useState("all");
  const [missing, setMissing] = useState("all");
  const rows = fleet.data?.devices ?? [];
  const filtered = rows.filter(d => (vendor === "all" || d.vendor === vendor) && (risk === "all" || d.risk === risk)
    && (missing === "all" || d.status === "NO_LIFECYCLE_DATA") && lifecycleCells(d).join(" ").toLowerCase().includes(search.toLowerCase()));
  const panel = <Stack spacing={2}>
    {fleet.error && <Alert severity="error">{fleet.error}<M3Button emphasis="tonal" onClick={fleet.refresh}>Retry</M3Button></Alert>}
    {!fleet.data && !fleet.error && <Typography>Loading lifecycle…</Typography>}
    {fleet.data && <>
      <Typography variant="caption">Stored inventory · as of {fleet.data.as_of} (UTC). Each cluster member is a separate evidence entity. Support windows overlap; license count is devices with at least one expiry in the next 60 days.</Typography>
      <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", gap: 1 }}>{cards.map(([key, label]) => <Box key={key} sx={{ p: 2, border: "1px solid", borderColor: "divider", borderRadius: 2 }}><Typography variant="h6">{fleet.data?.summary[key] ?? 0}</Typography><Typography variant="caption">{label}</Typography></Box>)}</Stack>
      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
        <TextField label="Search lifecycle" size="small" value={search} onChange={e => setSearch(e.target.value)} />
        <TextField label="Vendor" select size="small" value={vendor} onChange={e => setVendor(e.target.value)}><MenuItem value="all">All vendors</MenuItem>{[...new Set(rows.map(d => d.vendor))].sort().map(v => <MenuItem key={v} value={v}>{v}</MenuItem>)}</TextField>
        <TextField label="Risk" select size="small" value={risk} onChange={e => setRisk(e.target.value)}>{["all", "EXPIRED", "HIGH", "MEDIUM", "LOW", "UNKNOWN"].map(r => <MenuItem key={r} value={r}>{r === "all" ? "All risks" : r}</MenuItem>)}</TextField>
        <TextField label="Lifecycle data" select size="small" value={missing} onChange={e => setMissing(e.target.value)}><MenuItem value="all">All devices</MenuItem><MenuItem value="missing">No lifecycle data</MenuItem></TextField>
        <M3Button emphasis="tonal" disabled={!filtered.length} onClick={() => downloadText("lifecycle.csv", lifecycleCsv(filtered))}>Export lifecycle CSV</M3Button>
        <M3Button emphasis="tonal" onClick={fleet.refresh}>Refresh</M3Button>
      </Stack>
      <Typography variant="caption">Risk uses the earliest known hardware/software end of support: EXPIRED before today, HIGH under 180 days, MEDIUM under 365 days. LOW requires both support dates; missing dates remain UNKNOWN. End of sale and engineering are separate milestones.</Typography>
      <TableContainer><Table size="small" aria-label="Fleet lifecycle"><TableHead><TableRow>{headings.map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead><TableBody>
        {filtered.map(d => <TableRow key={d.device_id}>{lifecycleCells(d).map((cell, i) => <TableCell key={i}>
          {i === 0 ? <><a href={`?screen=inventory&device_id=${encodeURIComponent(d.device_id)}`}>{shown(d.hostname)}</a>{d.cluster_member_ref && <Typography variant="caption" display="block">{d.cluster_member_ref}</Typography>}</> : cell}
          {(i === 4 || i === 5) && <Typography variant="caption" display="block">
            End of sale: {shown((i === 4 ? d.hardware : d.software).catalog?.end_of_sale)}
            {" · "}End of engineering: {shown((i === 4 ? d.hardware : d.software).catalog?.end_of_engineering)}
          </Typography>}
          {i === 7 && <Stack spacing={0.5}>
            {d.licenses.map((l, index) => <Typography key={index} variant="caption">{l.name ?? "License"} · {l.member ?? ""} · {l.expiry ?? (l.label || "UNKNOWN")} · {l.status}{l.kind && ` · ${l.kind}`}</Typography>)}
            {d.license_status === "NOT_COLLECTED" && <Typography variant="caption">License data not collected for this vendor</Typography>}
            {d.license_status === "UNKNOWN" && <Typography variant="caption">License collection status UNKNOWN</Typography>}
            {d.support_contract_status === "NOT_COLLECTED" && <Typography variant="caption">Support contract not collected for this vendor</Typography>}
          </Stack>}
          {i === 9 && d.status === "NO_LIFECYCLE_DATA" && <Typography variant="caption" display="block">NO_LIFECYCLE_DATA</Typography>}
        </TableCell>)}</TableRow>)}
        {!filtered.length && <TableRow><TableCell colSpan={headings.length}>No devices match these filters</TableCell></TableRow>}
      </TableBody></Table></TableContainer>
    </>}
  </Stack>;
  return <Box sx={{ px: 3, pb: 3 }}><M3Tabs ariaLabel="Lifecycle view" tabs={[{ label: "Fleet", panel: panel }, { label: "Catalog", panel: <LifecycleCatalogPanel onChanged={fleet.refresh} /> }]} /></Box>;
}

export function DeviceLifecycleLine({ deviceId }: { deviceId: string }) {
  const data = useFetchOnMount(() => getDeviceLifecycle(deviceId), lifecycleError);
  return <Typography variant="body2" color="text.secondary">Lifecycle: {data.error ? "Unavailable" : data.data?.risk ?? "UNKNOWN"}
    {data.data?.hardware?.catalog?.end_of_support && ` · Hardware support: ${data.data.hardware.catalog.end_of_support}`}
    {data.data?.software?.catalog?.end_of_support && ` · Software support: ${data.data.software.catalog.end_of_support}`}
    {data.data?.nearest_license?.expiry && ` · License expiry: ${data.data.nearest_license.expiry}`}
    {data.data?.status === "NO_LIFECYCLE_DATA" && " · NO_LIFECYCLE_DATA"}
    {data.error && ` · ${data.error}`}
  </Typography>;
}
