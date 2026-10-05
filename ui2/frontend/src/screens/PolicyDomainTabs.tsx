import { useEffect, useState } from "react";
import { Box, Button, Chip, Drawer, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { getPolicyObjects, getPolicyUsage, getPolicyInstallations, type PolicyMetadata, type PolicyObjectPage, type PolicyInstallationPage, type PolicyUsage } from "../auth/adminApi";
import { scheduleLabel, reportPolicyLoadError } from "./PolicyRuleViewer";

const objectTypes = ["host", "network", "range", "group", "service", "service-group", "time", "access-role", "dynamic", "dns-domain", "zone"];
const hygieneFilters = { "": "All objects", unused: "Unused", duplicates: "Duplicates", empty: "Empty groups", single: "Single-member groups" };
const truth = (value: boolean | null) => value == null ? "UNKNOWN" : value ? "Yes" : "No";

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
  const [uid, setUid] = useState(""), [usagePage, setUsagePage] = useState(0), [usage, setUsage] = useState<PolicyUsage | null>(null), [usageError, setUsageError] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => { setQuery(search); setPage(0); }, 250);
    return () => window.clearTimeout(timer);
  }, [search]);
  useEffect(() => {
    let active = true; setData(null); setError("");
    const request = tab === "Objects" ? getPolicyObjects(metadata.sourceId, metadata.containerId, page, query, type, hygiene)
      : getPolicyInstallations(metadata.sourceId, metadata.containerId, page, query, policy);
    request.then(result => {
      if (!result || !("objects" in result ? Array.isArray(result.objects) : Array.isArray(result.installations)) || !Array.isArray(result.types)) throw new Error("Invalid object response");
      if (active) setData(result);
    }).catch(failure => { if (active) { reportPolicyLoadError("inventory", failure); setError(`${tab} could not be loaded.`); } });
    return () => { active = false; };
  }, [tab, metadata.sourceId, metadata.containerId, page, query, type, hygiene, policy, retry]);
  useEffect(() => {
    if (!uid) return;
    let active = true; setUsage(null); setUsageError(false);
    getPolicyUsage(metadata.sourceId, metadata.containerId, uid, usagePage).then(result => {
      if (!Array.isArray(result.rules) || !Array.isArray(result.groups)) throw new Error("Invalid object response");
      if (active) setUsage(result);
    }).catch(failure => { if (active) { reportPolicyLoadError("usage", failure); setUsageError(true); } });
    return () => { active = false; };
  }, [metadata.sourceId, metadata.containerId, uid, usagePage]);
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
        {!data.types.length && <Typography role="status">Object inventory not collected for this domain.</Typography>}
        {data.types.filter(t => t.status !== "RESOLVED").map(t => <Chip key={t.type} size="small" color="warning" label={`${t.type}: ${t.status}`} sx={{ mr: 1, mb: 1 }} />)}
        {tab === "Objects" && <Typography variant="caption" display="block">Unused is reported by the management snapshot. Missing evidence remains UNKNOWN; hygiene flags describe stored objects.</Typography>}
        {tab === "Installation" && <Typography variant="caption" display="block">Targets are package intent; reported installation is management-plane evidence, not direct runtime enforcement.</Typography>}
        <Box sx={{ overflowX: "auto", mt: 1 }}><Table size="small" aria-label={`Policy ${tab.toLowerCase()}`}>
          <TableHead><TableRow>{(tab === "Objects" ? ["Name", "Type", "Value / schedule", "Members", "Hygiene", "Where used"]
            : ["Package", "Gateway / cluster", "Installation target", "Reported installed"]).map(label => <TableCell key={label}>{label}</TableCell>)}</TableRow></TableHead>
          <TableBody>{"objects" in data ? data.objects.map(object => <TableRow key={object.id}>
            <TableCell>{object.name}</TableCell><TableCell>{object.type}</TableCell>
            <TableCell sx={{ overflowWrap: "anywhere", maxWidth: 320 }}>{object.schedule ? scheduleLabel(object.schedule) : object.values?.join("; ") || "—"}</TableCell>
            <TableCell>{object.members?.length ?? 0}</TableCell><TableCell>
              {object.unused == null ? <Chip size="small" label="Unused: UNKNOWN" /> : object.unused && <Chip size="small" label="Unused" />}
              {object.emptyGroup && <Chip size="small" label="Empty group" />}{object.singleMember && <Chip size="small" label="Single member" />}
              {object.duplicateId && <Chip size="small" label={`Duplicate set ${duplicates.indexOf(object.duplicateId) + 1}`} />}
            </TableCell><TableCell><Button size="small" aria-label={`Where used: ${object.name}`} onClick={() => { setUid(object.uid); setUsagePage(0); }}>
              {object.ruleCount} rules · {object.groupCount} groups</Button></TableCell>
          </TableRow>) : data.installations.map(row => <TableRow key={`${row.policyId}:${row.id}`}>
            <TableCell>{row.policyName}</TableCell><TableCell>{row.deviceId ? <Button size="small" href={`/?screen=inventory&device_id=${encodeURIComponent(row.deviceId)}`}>{row.name}</Button>
              : row.allTargets && !row.name ? "ALL" : row.name || "No observed target"}</TableCell>
            <TableCell>{row.allTargets ? "ALL" : truth(row.targeted)}</TableCell><TableCell>{truth(row.installed)}</TableCell>
          </TableRow>)}</TableBody>
        </Table></Box>
        {data.total === 0 && <Typography role="status" sx={{ py: 2 }}>No matching {tab.toLowerCase()}.</Typography>}
        <Paging page={page} total={data.total} size={data.pageSize} change={setPage} />
      </>}
    <Drawer anchor="right" open={!!uid} onClose={() => setUid("")}>
      <Box role="dialog" aria-label="Object usage" sx={{ width: { xs: "90vw", sm: 640 }, p: 3 }}>
        <Button onClick={() => setUid("")}>Close</Button>
        {usageError ? <Typography role="alert">Object usage could not be loaded.</Typography> : !usage ? <Typography role="status">Loading usage…</Typography> : <>
          <Typography variant="h6">{usage.object.name}</Typography>
          <Typography variant="caption">Includes rule references through groups. Group references are direct memberships.</Typography>
          <Table size="small" aria-label="Rules using object"><TableHead><TableRow>{["Package", "Layer", "Rule number"].map(t => <TableCell key={t}>{t}</TableCell>)}</TableRow></TableHead>
            <TableBody>{usage.rules.map(r => <TableRow key={`${r.policyId}:${r.layerRef}:${r.ruleId}`}><TableCell>{r.policyName}</TableCell><TableCell>{r.layerName}</TableCell><TableCell>{r.number}</TableCell></TableRow>)}</TableBody></Table>
          <Typography variant="subtitle2" sx={{ mt: 2 }}>Groups referencing object</Typography>
          {usage.groups.map(g => <Typography key={g.id}>{g.name}</Typography>)}
          {!usage.ruleCount && !usage.groupCount && <Typography>No stored references.</Typography>}
          <Paging page={usagePage} total={Math.max(usage.ruleCount, usage.groupCount)} size={usage.pageSize} change={setUsagePage} />
        </>}
      </Box>
    </Drawer>
  </Box>;
}
