import { useCallback, useEffect, useState } from "react";
import { Box, Button, Chip, Drawer, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Tooltip, Typography } from "@mui/material";
import { getPolicyObjects, getPolicyUsage, getPolicyInstallations, type PolicyInventoryObject, type PolicyMetadata, type PolicyObjectPage, type PolicyInstallationPage, type PolicyUsage } from "../auth/adminApi";
import { scheduleLabel, reportPolicyLoadError, policyFailureText } from "./PolicyRuleViewer";
import { statusWord } from "../shell/presenters";
import { SortHeaderCell, useServerTableSort } from "../shell/tableSort";

const objectTypes = ["host", "network", "range", "group", "service", "service-group", "time", "access-role", "dynamic", "dns-domain", "zone"];
const hygieneFilters = { "": "All objects", unused: "Unused", duplicates: "Duplicates", empty: "Empty groups", single: "Single-member groups" };
const truth = (value: boolean | null) => value == null ? "UNKNOWN" : value ? "Yes" : "No";
/** Why a reported-installed cell is UNKNOWN: Check Point makes no installation claim the product can prove; elsewhere the server reported none. */
export function installedUnknownReason(vendor: string | null | undefined) {
  return vendor === "CP" ? "Installation state not reported by Check Point; this is not a fault of the target. Targets are package intent."
    : "The management server did not report whether this package is installed on the target.";
}
type UsageEvidence = Pick<PolicyInventoryObject, "ruleCount" | "groupCount" | "usageComplete" | "usageReasons">;
/** A count is only exact when the server proved it; a missing flag fails closed to UNKNOWN / lower bound. */
export function whereUsedLabel(usage: UsageEvidence) {
  const counts = `${usage.ruleCount} rules · ${usage.groupCount} groups`;
  if (usage.usageComplete === true) return counts;
  return usage.ruleCount + usage.groupCount === 0 ? "UNKNOWN" : `at least ${counts}`;
}
const reasonText = (reasons: string[] | undefined, fallback: string) => reasons?.length ? reasons.join(" ") : fallback;

function Paging({ page, total, size = 200, change }: { page: number; total: number; size?: number; change: (page: number) => void }) {
  return <Stack direction="row" alignItems="center" gap={1} sx={{ mt: 1 }}>
    <Button disabled={page === 0} onClick={() => change(page - 1)}>Previous</Button>
    <Typography variant="caption">Page {page + 1} of {Math.max(1, Math.ceil(total / size))} · {total} items</Typography>
    <Button disabled={(page + 1) * size >= total} onClick={() => change(page + 1)}>Next</Button>
  </Stack>;
}
export function PolicyDomainTab({ tab, metadata, policies }: { tab: "Objects" | "Installation"; metadata: PolicyMetadata; policies: PolicyMetadata[] }) {
  const [page, setPage] = useState(0), [search, setSearch] = useState(""), [query, setQuery] = useState("");
  const [type, setType] = useState(""), [hygiene, setHygiene] = useState(""), [policy, setPolicy] = useState("");
  const [data, setData] = useState<PolicyObjectPage | PolicyInstallationPage | null>(null);
  const [error, setError] = useState(""), [retry, setRetry] = useState(0);
  const [uid, setUid] = useState(""), [usagePage, setUsagePage] = useState(0), [usage, setUsage] = useState<PolicyUsage | null>(null), [usageError, setUsageError] = useState("");
  const resetPage = useCallback(() => setPage(0), []);
  const resetUsagePage = useCallback(() => setUsagePage(0), []);
  const sort = useServerTableSort({ onChange: resetPage });
  const usageSort = useServerTableSort({ onChange: resetUsagePage });
  const sortKey = sort.sort?.key ?? "";
  const sortOrder = sort.sort?.direction ?? "";
  const usageSortKey = usageSort.sort?.key ?? "";
  const usageSortOrder = usageSort.sort?.direction ?? "";
  useEffect(() => {
    const timer = window.setTimeout(() => { setQuery(search); setPage(0); }, 250);
    return () => window.clearTimeout(timer);
  }, [search]);
  useEffect(() => {
    let active = true; setData(null); setError("");
    const request = tab === "Objects"
      ? (sortKey
          ? getPolicyObjects(metadata.sourceId, metadata.containerId, page, query, type, hygiene, sortKey, sortOrder)
          : getPolicyObjects(metadata.sourceId, metadata.containerId, page, query, type, hygiene))
      : (sortKey
          ? getPolicyInstallations(metadata.sourceId, metadata.containerId, page, query, policy, sortKey, sortOrder)
          : getPolicyInstallations(metadata.sourceId, metadata.containerId, page, query, policy));
    request.then(result => {
      if (!result || !("objects" in result ? Array.isArray(result.objects) : Array.isArray(result.installations)) || !Array.isArray(result.types)) throw new Error("Invalid object response");
      if (active) setData(result);
    }).catch(failure => { if (active) { reportPolicyLoadError("inventory", failure); setError(policyFailureText(failure, `${tab} could not be loaded.`)); } });
    return () => { active = false; };
  }, [tab, metadata.sourceId, metadata.containerId, page, query, type, hygiene, policy, retry, sortKey, sortOrder]);
  useEffect(() => {
    if (!uid) return;
    let active = true; setUsage(null); setUsageError("");
    const request = usageSortKey
      ? getPolicyUsage(metadata.sourceId, metadata.containerId, uid, usagePage, usageSortKey, usageSortOrder)
      : getPolicyUsage(metadata.sourceId, metadata.containerId, uid, usagePage);
    request.then(result => {
      if (!Array.isArray(result.rules) || !Array.isArray(result.groups)) throw new Error("Invalid object response");
      if (active) setUsage(result);
    }).catch(failure => { if (active) { reportPolicyLoadError("usage", failure); setUsageError(policyFailureText(failure, "Object usage could not be loaded.")); } });
    return () => { active = false; };
  }, [metadata.sourceId, metadata.containerId, uid, usagePage, usageSortKey, usageSortOrder]);
  const duplicates = data && "objects" in data ? [...new Set(data.objects.map(o => o.duplicateId).filter(Boolean))] : [];
  return <Box role="tabpanel" aria-label={tab} sx={{ minWidth: 0 }}>
    <Typography variant="subtitle2">Domain: {metadata.containerName}</Typography>
    <Stack direction="row" flexWrap="wrap" gap={1} sx={{ my: 2 }}>
      <TextField size="small" label={`Search ${tab.toLowerCase()}`} value={search} inputProps={{ maxLength: 1000 }} onChange={e => setSearch(e.target.value)} />
      {tab === "Objects" ? <>
        <TextField select size="small" label="Object type" value={type} sx={{ minWidth: 160 }} onChange={e => { setType(e.target.value); setPage(0); }}>
          <MenuItem value="">All types</MenuItem>{objectTypes.map(t => <MenuItem key={t} value={t}>{t}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Hygiene" value={hygiene} sx={{ minWidth: 180 }} onChange={e => { setHygiene(e.target.value); setPage(0); }}>
          {Object.entries(hygieneFilters).map(([value, label]) => <MenuItem key={value} value={value}>{label}</MenuItem>)}
        </TextField>
      </> : <TextField select size="small" label="Package" value={policy} sx={{ minWidth: 180 }} onChange={e => { setPolicy(e.target.value); setPage(0); }}>
        <MenuItem value="">All packages</MenuItem>{policies.filter(p => p.sourceId === metadata.sourceId && p.containerId === metadata.containerId)
          .map(p => <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>)}
      </TextField>}
    </Stack>
    {error ? <Typography role="alert">{error} <Button onClick={() => setRetry(r => r + 1)}>Retry</Button></Typography>
      : !data ? <Typography role="status">Loading {tab.toLowerCase()}…</Typography> : <>
        {!data.types.length && <Typography role="status">Object inventory not collected for this domain. Objects are listed from the stored policy.</Typography>}
        {data.types.filter(t => t.status !== "RESOLVED").map(t => <Chip key={t.type} size="small" color="warning" label={`${t.type}: ${statusWord(t.status)}`} sx={{ mr: 1, mb: 1 }} />)}
        {tab === "Objects" && <Typography variant="caption" display="block">Unused comes only from the management unused-object report. Where used counts references in collected rules and groups; when that evidence is incomplete it shows UNKNOWN or a lower bound (hover for reasons).</Typography>}
        {"objects" in data && !!data.usageGaps?.length && <Box role="note" aria-label="Where-used evidence gaps" sx={{ mt: 1 }}>
          <Typography variant="caption" display="block">Where-used evidence is incomplete for this domain:</Typography>
          {data.usageGaps.map(gap => <Typography key={gap} variant="caption" display="block">• {gap}</Typography>)}
        </Box>}
        {tab === "Installation" && <Typography variant="caption" display="block">Targets are package intent; reported installation is management-plane evidence, not direct runtime enforcement.</Typography>}
        <Box sx={{ overflowX: "auto", mt: 1 }}><Table size="small" aria-label={`Policy ${tab.toLowerCase()}`}>
          <TableHead><TableRow>{(tab === "Objects"
            ? [["Name", "name"], ["Type", "type"], ["Value / schedule", "value"], ["Members", "members"], ["Hygiene", "hygiene"], ["Where used", "where_used"]] as const
            : [["Package", "policy"], ["Gateway / cluster", "name"], ["Installation target", "targeted"], ["Reported installed", "installed"]] as const
          ).map(([label, key]) => <SortHeaderCell key={label} label={label} state={sort.header(key)} />)}</TableRow></TableHead>
          <TableBody>{"objects" in data ? data.objects.map(object => <TableRow key={object.id}>
            <TableCell>{object.name}</TableCell><TableCell>{object.type}</TableCell>
            <TableCell sx={{ overflowWrap: "anywhere", maxWidth: 320 }}>{object.schedule ? scheduleLabel(object.schedule) : object.values?.join("; ") || "—"}</TableCell>
            <TableCell>{object.members?.length ?? 0}</TableCell><TableCell>
              {object.unused == null ? <Tooltip describeChild title={reasonText(object.unusedReasons, "Unused evidence is not available.")}>
                <Chip size="small" label="Unused: UNKNOWN" /></Tooltip> : object.unused && <Chip size="small" label="Unused" />}
              {object.emptyGroup && <Chip size="small" label="Empty group" />}{object.singleMember && <Chip size="small" label="Single member" />}
              {object.duplicateId && <Chip size="small" label={`Duplicate set ${duplicates.indexOf(object.duplicateId) + 1}`} />}
            </TableCell><TableCell><Tooltip describeChild title={object.usageComplete === true ? "Proven by the management unused-object report."
              : reasonText(object.usageReasons, "Where-used evidence is incomplete.")}>
              <Button size="small" aria-label={`Where used: ${object.name}`} onClick={() => { setUid(object.uid); setUsagePage(0); }}>
              {whereUsedLabel(object)}</Button></Tooltip></TableCell>
          </TableRow>) : data.installations.map(row => <TableRow key={`${row.policyId}:${row.id}`}>
            <TableCell>{row.policyName}</TableCell><TableCell>{row.deviceId ? <Button size="small" href={`/?screen=inventory&device_id=${encodeURIComponent(row.deviceId)}`}>{row.name}</Button>
              : row.allTargets && !row.name ? "ALL" : row.name || "No observed target"}</TableCell>
            <TableCell>{row.allTargets ? "ALL" : truth(row.targeted)}</TableCell>
            {/* A bare UNKNOWN reads as a fault; the hover says whose silence it is, as the header's sync dot does. */}
            <TableCell>{row.installed == null ? <Tooltip describeChild title={installedUnknownReason(metadata.vendor)}><span>UNKNOWN</span></Tooltip> : truth(row.installed)}</TableCell>
          </TableRow>)}</TableBody>
        </Table></Box>
        {data.total === 0 && <Typography role="status" sx={{ py: 2 }}>No matching {tab.toLowerCase()}.</Typography>}
        <Paging page={page} total={data.total} size={data.pageSize} change={setPage} />
      </>}
    <Drawer anchor="right" open={!!uid} onClose={() => setUid("")}>
      <Box role="dialog" aria-label="Object usage" sx={{ width: { xs: "90vw", sm: 640 }, p: 3 }}>
        <Button onClick={() => setUid("")}>Close</Button>
        {usageError ? <Typography role="alert">{usageError}</Typography> : !usage ? <Typography role="status">Loading usage…</Typography> : <>
          <Typography variant="h6">{usage.object.name}</Typography>
          <Typography variant="caption">Includes rule references through groups. Group references are direct memberships.</Typography>
          <Typography role="status" sx={{ mt: 1 }}>Where used: {whereUsedLabel(usage)}</Typography>
          {usage.usageComplete !== true && <Box aria-label="Usage evidence gaps">
            {(usage.usageReasons?.length ? usage.usageReasons : ["Where-used evidence is incomplete."]).map(r => <Typography key={r} variant="caption" display="block">• {r}</Typography>)}
          </Box>}
          <Table size="small" aria-label="Rules using object"><TableHead><TableRow>{([["Package", "policy"], ["Layer", "layer"], ["Rule number", "number"]] as const).map(([label, key]) => <SortHeaderCell key={label} label={label} state={usageSort.header(key)} />)}</TableRow></TableHead>
            <TableBody>{usage.rules.map(r => <TableRow key={`${r.policyId}:${r.layerRef}:${r.ruleId}`}><TableCell>{r.policyName}</TableCell><TableCell>{r.layerName}</TableCell><TableCell>{r.number}</TableCell></TableRow>)}</TableBody></Table>
          <Typography variant="subtitle2" sx={{ mt: 2 }}>Groups referencing object</Typography>
          {usage.groups.map(g => <Typography key={g.id}>{g.name}</Typography>)}
          {!usage.ruleCount && !usage.groupCount && <Typography>{usage.usageComplete === true ? "No references; confirmed by the management unused-object report."
            : "No references in collected rules and groups; usage remains UNKNOWN."}</Typography>}
          <Paging page={usagePage} total={Math.max(usage.ruleCount, usage.groupCount)} size={usage.pageSize} change={setUsagePage} />
        </>}
      </Box>
    </Drawer>
  </Box>;
}
