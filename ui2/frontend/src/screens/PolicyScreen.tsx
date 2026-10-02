import { useEffect, useMemo, useRef, useState } from "react";
import { Box, Button, Chip, Drawer, IconButton, Tooltip, FormControl, InputLabel, MenuItem, Select, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { ScreenRoot, ScreenHeader, EmptyPanel } from "../shell/ScreenLayout";
import { VendorBadge } from "../shell/States";
import { Icon } from "../shell/Icon";
import { relativeAge } from "../shell/time";
import { JobTranscriptDrawer } from "./JobTranscriptDrawer";
import { m3 } from "../theme/m3Theme";
import { cancelJob, getPolicyTree, getPolicyCollectionStatus, type PolicyContainer, type PolicyCollectionStatus, listPolicySources, collectPolicies, type PolicyCollectionSource, getPolicy, getPolicyObject, type PolicyTarget, type PolicyMetadata, type PolicyObject, type PolicyRule, type PolicyCell, type PolicyPage } from "../auth/adminApi";

const runningStates = ["REQUESTED", "CLAIMED", "EXECUTING", "RECONCILING"];
const isRunning = (job: PolicyCollectionStatus) => runningStates.includes(job.state);
// Labels are presentation only; identifiers remain unchanged as request keys.
const displayName = (name: string | undefined, fallback: string) =>
  !name || /[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/i.test(name) ? fallback : name;
function failureSentence(reason: string, step?: number, total?: number) {
  const scope = total ? ` at collection step ${step ?? 0} of ${total}` : "";
  const problem = /TIMEOUT|TimedOut|JOB_DEADLINE/.test(reason) ? "the time limit was reached"
    : /HTTP_403|AuthenticationFailed|HostKeyRejected/.test(reason) ? "access was refused"
    : /PARSE|INVALID|RESPONSE|XML/.test(reason) ? "the reply could not be read" : "the source could not be read";
  return `Last collection ${reason.startsWith("PARTIAL_SNAPSHOT") ? "was incomplete" : "failed"}${scope} — ${problem}`;
}
function FailureDetails({ reason, allowed, job }: { reason: string; allowed: boolean; job?: PolicyCollectionStatus }) {
  return allowed ? <Box component="details" sx={{ mt: 0.5, overflowWrap: "anywhere" }}>
    <Box component="summary" sx={{ cursor: "pointer", color: m3.primary, fontSize: 12 }}>Details</Box>
    {job && <Typography variant="caption" display="block">Collection step: {job.step}/{job.total || "?"}</Typography>}
    <Typography component="pre" variant="caption" sx={{ whiteSpace: "pre-wrap", m: 0 }}>{reason}</Typography>
    {job && <JobTranscriptDrawer jobId={job.jobId} hasTranscript={job.hasTranscript === true} title="Policy collection transcript" />}
  </Box> : null;
}

export function PolicyScreen({ preview = false }: { preview?: boolean }) {
  const [catalog, setCatalog] = useState<PolicyMetadata[]>([]);
  const [sources, setSources] = useState<PolicyCollectionSource[]>([]);
  const [containers, setContainers] = useState<Record<string, PolicyContainer[]>>({});
  const [expandedSources, setExpandedSources] = useState<Set<string>>(new Set());
  const [expandedContainers, setExpandedContainers] = useState<Set<string>>(new Set());
  const [loadedContainers, setLoadedContainers] = useState<Set<string>>(new Set());
  const [loadingTree, setLoadingTree] = useState(false);
  const [jobs, setJobs] = useState<(PolicyCollectionStatus & { sourceId: string })[]>([]);
  const [targets, setTargets] = useState<PolicyTarget[]>([]);
  const [scrollTop, setScrollTop] = useState(0);
  const [canCollect, setCanCollect] = useState(false);
  const [canCancel, setCanCancel] = useState(false);
  const [cancelling, setCancelling] = useState<string[]>([]);
  const [collecting, setCollecting] = useState(false);
  const [collectionStatus, setCollectionStatus] = useState("");
  const [selected, setSelected] = useState("");
  const [device, setDevice] = useState(() => new URLSearchParams(window.location.search).get("device_id") ?? "");
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [data, setData] = useState<PolicyPage | null>(null);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60000);
    return () => window.clearInterval(timer);
  }, []);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [drawer, setDrawer] = useState<{ rule?: PolicyRule; object?: PolicyObject; loading?: boolean; error?: string } | null>(null);
  const drawerRequest = useRef(0);
  useEffect(() => {
    if (preview) return;
    let active = true;
    setError("");
    setLoadingTree(true);
    listPolicySources().then(admission => {
      if (!active) return;
      setSources(admission.sources ?? []);
      setCanCancel(admission.canCancel === true);
      setCanCollect(admission.canCollect === true);
      setJobs(current => {
        const latest = (admission.sources ?? []).flatMap(source => source.collection ? [{ ...source.collection, sourceId: source.sourceId }] : []);
        return latest.map(job => {
          const previous = current.find(item => item.jobId === job.jobId);
          return previous && !isRunning(previous) && isRunning(job) ? previous : job;
        }).concat(current.filter(job => (admission.sources ?? []).some(source => source.sourceId === job.sourceId)
          && !latest.some(item => item.sourceId === job.sourceId)));
      });
    }).catch(() => { if (active) setError("Policy snapshots could not be loaded."); })
      .finally(() => { if (active) setLoadingTree(false); });
    return () => { active = false; };
  }, [preview, revision]);
  useEffect(() => {
    if (!revision) return;
    let active = true;
    const refresh = async () => {
      try {
        const trees = await Promise.all([...expandedSources].map(async source => {
          const tree = await getPolicyTree(source, "", device);
          return [source, tree.containers ?? []] as const;
        }));
        const policies = await Promise.all([...loadedContainers].map(async container => {
          const source = trees.find(([, nodes]) => nodes.some(node => node.containerId === container))?.[0];
          return source ? (await getPolicyTree(source, container, device)).policies ?? [] : [];
        }));
        if (active) {
          setContainers(Object.fromEntries(trees)); setCatalog(policies.flat());
          setTargets([...new Map(policies.flat().flatMap(p => p.targets).map(t => [t.deviceId, t])).values()]);
        }
      } catch { if (active) setError("Policy snapshots could not be refreshed."); }
    };
    void refresh();
    return () => { active = false; };
    // Refresh the navigation already opened by the user when collection ends.
  }, [revision]);
  useEffect(() => {
    const timer = window.setTimeout(() => { setQuery(search); setPage(0); }, 250);
    return () => window.clearTimeout(timer);
  }, [search]);
  const visible = useMemo(() => (catalog ?? []).filter(p => !device || p.targets.some(t => t.deviceId === device)), [catalog, device]);
  useEffect(() => {
    let active = true;
    setScrollTop(0); setData(null); setDrawer(null); drawerRequest.current++; setError("");
    if (selected) getPolicy(selected, page, query).then(result => {
      if (!result?.metadata || !Array.isArray(result.sections)) throw new Error("Empty policy response");
      if (active) setData(result);
    })
      .catch(() => { if (active) setError("Policy rules could not be loaded."); });
    return () => { active = false; };
  }, [selected, page, query, revision]);
  useEffect(() => () => { drawerRequest.current++; }, []);
  const objects = useMemo(() => new Map((data?.objects ?? []).map(o => [o.id, o])), [data]);

  const openObject = async (id: string) => {
    const request = ++drawerRequest.current;
    setDrawer({ loading: true });
    try { const result = await getPolicyObject(id, selected); if (request === drawerRequest.current) setDrawer({ object: result.object }); }
    catch { if (request === drawerRequest.current) setDrawer({ error: "Object details could not be loaded." }); }
  };
  const openRule = (rule: PolicyRule) => { drawerRequest.current++; setDrawer({ rule }); };
  const cell = (value: PolicyCell, rule: PolicyRule) => <Stack direction="row" flexWrap="wrap" gap={0.5}>
    {value.negated && <Chip label="NOT" size="small" color="warning" />}
    {value.refs.length === 0 && <Typography variant="caption">UNKNOWN</Typography>}
    {value.refs.slice(0, 4).map((id, index) => {
      const object = objects.get(id);
      return <Chip key={`${id}-${index}`} size="small" label={object?.name ?? "Unresolved object"}
        variant={object?.type === "any" ? "filled" : "outlined"} onClick={() => void openObject(id)}
        title={object?.type ?? "unresolved"} sx={{ maxWidth: 170 }} />;
    })}
    {value.refs.length > 4 && <Button size="small" onClick={() => openRule(rule)}>+{value.refs.length - 4} more</Button>}
  </Stack>;
  const expandSource = async (source: string) => {
    const next = new Set(expandedSources);
    if (next.has(source)) { next.delete(source); setExpandedSources(next); return; }
    next.add(source); setExpandedSources(next);
    if (containers[source]) return;
    try { const tree = await getPolicyTree(source, "", device); setContainers(current => ({ ...current, [source]: tree.containers ?? [] })); }
    catch { setError("Policy containers could not be loaded."); }
  };
  const expandContainer = async (source: string, container: string) => {
    const next = new Set(expandedContainers);
    if (next.has(container)) { next.delete(container); setExpandedContainers(next); return; }
    next.add(container); setExpandedContainers(next);
    if (loadedContainers.has(container)) return;
    try {
      const tree = await getPolicyTree(source, container, device);
      setCatalog(current => [...current.filter(p => p.containerId !== container), ...(tree.policies ?? [])]);
      setTargets(current => [...new Map([...current, ...(tree.policies ?? []).flatMap(p => p.targets)].map(t => [t.deviceId, t])).values()]);
      setLoadedContainers(current => new Set(current).add(container));
    } catch { setError("Policies could not be loaded."); }
  };
  const activeJobs = jobs.filter(isRunning);
  const activeJobIds = activeJobs.map(job => job.jobId).join(",");
  useEffect(() => {
    if (!activeJobIds) return;
    const ids = activeJobIds.split(",");
    let active = true, delay = 5000, timer: number | undefined;
    const poll = async () => {
      if (!active || document.hidden) return;
      try {
        const statuses = await Promise.all(ids.map(id => getPolicyCollectionStatus(id)));
        if (!active) return;
        setJobs(current => current.map(job => ({ ...job, ...(statuses.find(status => status.jobId === job.jobId) ?? {}) })));
        if (statuses.some(status => !isRunning(status))) {
          setRevision(n => n + 1);
          return;
        }
      } catch { if (active) setCollectionStatus("Collection status is temporarily unavailable."); }
      delay = Math.min(delay * 2, 30000);
      if (active && !document.hidden) timer = window.setTimeout(() => void poll(), delay);
    };
    const visibility = () => {
      window.clearTimeout(timer);
      if (!document.hidden) timer = window.setTimeout(() => void poll(), delay);
    };
    visibility();
    document.addEventListener("visibilitychange", visibility);
    return () => { active = false; window.clearTimeout(timer); document.removeEventListener("visibilitychange", visibility); };
  }, [activeJobIds]);
  const collect = async (source: string, domain = "") => {
    setCollecting(true); setCollectionStatus("");
    try {
      const queued = await collectPolicies(source, domain);
      setJobs(current => [...current, { sourceId: source, jobId: queued.jobId, state: "REQUESTED", reason: "", step: 0, total: 0 }]);
    } catch { setCollectionStatus("Policy collection could not be queued. The source may be busy or unavailable."); }
    finally { setCollecting(false); }
  };
  const cancel = async (id: string) => {
    setCancelling(current => [...current, id]);
    try {
      const status = await cancelJob(id);
      setJobs(current => current.map(job => job.jobId === id ? { ...job, ...status } : job));
    } catch {
      setCancelling(current => current.filter(job => job !== id));
      setCollectionStatus("Job cancellation could not be requested.");
    }
  };
  const collectButton = (source: string, domain = "") => canCollect && sources.some(s => s.sourceId === source)
    ? <Button size="small" variant="outlined" disabled={collecting || activeJobs.length > 0} onClick={() => void collect(source, domain)}>Collect policies</Button> : null;
  const close = () => { drawerRequest.current++; setDrawer(null); };
  return <ScreenRoot>
    <Box sx={{ maxWidth: 1440, mx: "auto", width: "100%" }}>
    <ScreenHeader title="Policy" subtitle="Management policy · configured intent" actions={!preview &&
      <Tooltip title="Refresh snapshots"><IconButton aria-label="Refresh snapshots" onClick={() => setRevision(n => n + 1)}><Box component="span" aria-hidden="true" sx={{ fontSize: 26, lineHeight: 1 }}>↻</Box></IconButton></Tooltip>} />
    <Box sx={{ mt: 3, display: "grid", gridTemplateColumns: { xs: "minmax(0, 1fr)", md: "320px minmax(0, 1fr)" }, gap: 3, alignItems: "start" }}>
      <Box component="nav" aria-label="Management policies" sx={{ bgcolor: m3.scLow, borderRadius: 3, p: 2, minWidth: 0, overflowWrap: "anywhere", border: `1px solid ${m3.outlineVar}` }}>
        <Typography variant="subtitle2" sx={{ mb: 2 }}>Sources · {sources.length}</Typography>
        {sources.map(source => {
          const job = [...jobs].reverse().find(item => item.sourceId === source.sourceId) ?? source.collection;
          const running = job && isRunning(job);
          const failed = job && (!!job.reason || ["FAILED", "REJECTED", "OUTCOME_UNKNOWN"].includes(job.state));
          return <Box key={source.sourceId} role="group" aria-label="Policy source" sx={{ mb: 2 }}>
            <Stack direction="row" spacing={1} alignItems="center">
              <VendorBadge vendor={source.vendor === "PAN" ? "palo_alto" : source.vendor === "CP" ? "check_point" : null} />
              <Box component="span" aria-label={running ? "Collecting" : failed ? "Collection needs attention" : job?.state === "COMPLETED" ? "Collected" : "Not collected"}
                sx={{ width: 8, height: 8, flexShrink: 0, borderRadius: "50%", bgcolor: running ? m3.primary : failed ? m3.error : job?.state === "COMPLETED" ? m3.success : m3.outline }} />
              <Button aria-expanded={expandedSources.has(source.sourceId)} onClick={() => void expandSource(source.sourceId)} sx={{ minWidth: 0, textAlign: "left", textTransform: "none" }}>
                {displayName(source.sourceName, "Management server")}
              </Button>
            </Stack>
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
              {source.vendor === "PAN" ? "Panorama" : source.vendor === "CP" ? "MDS" : "Management server"} · {containers[source.sourceId]?.length ?? "?"} containers
            </Typography>
            {job && <Box sx={{ my: 1 }}>
              {running ? <Chip role="status" size="small" color="primary" label={job.layer ? `Collecting · unit ${job.layer}/${job.layers || "?"}, rules fetched ${job.rulesFetched ?? 0}` : `Collecting · ${job.step}/${job.total || "?"}`} />
                : <Typography role="status" variant="body2">{failed ? failureSentence(job.reason, job.step, job.total)
                  : job.state === "COMPLETED" ? `Collected · ${job.step}/${job.total} collection steps` : "Collection stopped"}
                  {job.collectedAt && ` · ${relativeAge(job.collectedAt, now)}`}</Typography>}
              {job.reason && <FailureDetails reason={job.reason} allowed={canCollect} job={job} />}
            </Box>}
            {running && job && canCancel && <Button size="small"
              disabled={job.cancelRequested || cancelling.includes(job.jobId)} onClick={() => void cancel(job.jobId)}>
              {job.cancelRequested || cancelling.includes(job.jobId) ? "Cancel requested" : "Cancel"}
            </Button>}
            {collectButton(source.sourceId)}
            {expandedSources.has(source.sourceId) && containers[source.sourceId]?.length === 0
              && <Typography variant="caption" display="block">No policy containers in this snapshot.</Typography>}
            {expandedSources.has(source.sourceId) && (containers[source.sourceId] ?? []).map(container => <Box key={container.containerId} role="group" aria-label="Policy container" sx={{ pl: 1, mt: 1, borderLeft: `1px solid ${m3.outlineVar}` }}>
              <Button aria-expanded={expandedContainers.has(container.containerId)} onClick={() => void expandContainer(source.sourceId, container.containerId)} sx={{ textTransform: "none", textAlign: "left" }}>
                <Box component="span" aria-hidden="true" sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: m3.outline, mr: 1, flexShrink: 0 }} />
                {displayName(container.containerName, "Policy container")}
              </Button>
              <Typography variant="caption" display="block">{loadedContainers.has(container.containerId) ? visible.filter(p => p.containerId === container.containerId).length : "?"} policies</Typography>
              {source.vendor === "CP" && collectButton(source.sourceId, container.containerId)}
              {expandedContainers.has(container.containerId) && loadedContainers.has(container.containerId)
                && !visible.some(p => p.containerId === container.containerId)
                && <Typography variant="caption">No policies in this snapshot.</Typography>}
              {expandedContainers.has(container.containerId) && visible.filter(p => p.containerId === container.containerId).map(policy =>
                <Box key={policy.id} sx={{ pl: 1 }}><Button fullWidth variant={selected === policy.id ? "contained" : "text"}
                  aria-pressed={selected === policy.id} aria-current={selected === policy.id ? "page" : undefined} sx={{ justifyContent: "flex-start", textTransform: "none" }}
                  onClick={() => { setSelected(policy.id); setPage(0); setCollapsed(new Set()); }}><Box component="span" aria-hidden="true" sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: m3.outline, mr: 1, flexShrink: 0 }} />{displayName(policy.name, "Policy")}</Button>
                  <Typography variant="caption" sx={{ pl: 1 }}>{data?.metadata.id === policy.id ? data.total : "?"} rules</Typography></Box>)}
            </Box>)}
          </Box>;
        })}
      </Box>
      <Box component="section" aria-label="Policy content" sx={{ minWidth: 0, bgcolor: m3.scLowest, borderRadius: 3, p: 3, border: `1px solid ${m3.outlineVar}` }}>
        <Stack direction="row" justifyContent="space-between" flexWrap="wrap" gap={2} sx={{ mb: 2 }}>
          <Typography variant="h6">{data ? displayName(data.metadata.name, "Policy") : "Stored policies"}</Typography>
          <FormControl size="small" sx={{ minWidth: 240 }}>
            <InputLabel id="policy-device-label">Jump to device's policy</InputLabel>
            <Select labelId="policy-device-label" label="Jump to device's policy" value={device} onChange={e => {
              const next = e.target.value; setDevice(next); setPage(0);
              setSelected(catalog.find(p => !next || p.targets.some(t => t.deviceId === next))?.id ?? "");
            }}>
              <MenuItem value="">All management policies</MenuItem>
              {targets.map(t => <MenuItem key={t.deviceId} value={t.deviceId}>{displayName(t.name, "Device")}</MenuItem>)}
              {device && !targets.some(t => t.deviceId === device) && <MenuItem value={device}>Requested device</MenuItem>}
            </Select>
          </FormControl>
        </Stack>
        {collectionStatus && <Typography role="status" sx={{ mb: 1 }}>{collectionStatus}</Typography>}
        {error && <EmptyPanel title="Policy unavailable" body={error}><Button onClick={() => setRevision(n => n + 1)}>Retry</Button></EmptyPanel>}
        {loadingTree && !error && <Typography role="status">Loading policies…</Typography>}
        {!selected && !loadingTree && !error && <Box role="status" aria-label={sources.length ? "No collected policy" : "No policy snapshot"} sx={{ textAlign: "center", py: 6, color: m3.onSurfaceVar }}>
          <Icon name="rows" size={64} />
          <Typography variant="h6" sx={{ mt: 2 }}>{sources.length ? "No assigned policy snapshot" : "No policy snapshot"}</Typography>
          <Typography variant="body2">{sources.length ? "Select a source and container to browse stored policies." : "No management policy has been collected yet."}</Typography>
        </Box>}
            {selected && !data && !error && <Typography role="status">Loading rules…</Typography>}
            {data && <>
              {!!data.failures?.length && <Box><Chip color="warning" label="Partial snapshot · incomplete" />
                {data.failures.map(f => <Box key={f.layerRef} role="alert"><Typography variant="body2">{f.layerName || f.layerRef}{f.offset !== undefined ? ` · offset ${f.offset}` : ""}: {failureSentence(f.reason)}</Typography><FailureDetails reason={f.reason} allowed={canCollect} /></Box>)}</Box>}
              {data.policyKind === "LOCAL_FIREWALL" && <Chip size="small" label="local firewall policy" />}
              <Chip size="small" label={`From configuration collected ${data.metadata.collectedAt}`} sx={{ my: 1 }} />
              <Stack direction="row" flexWrap="wrap" gap={1} alignItems="center" sx={{ my: 1 }}><Typography variant="body2">Assigned to:</Typography>
                {data.metadata.targets.length === 0 ? <Chip size="small" label="Unassigned" /> : data.metadata.targets.map(t => <Chip key={t.deviceId} size="small" variant="outlined" label={`${displayName(t.name, "Device")}${t.context ? ` (${t.context})` : ""} · ${t.syncStatus}`} />)}</Stack>
              <Typography variant="caption">{data.policyKind === "LOCAL_FIREWALL" ? "Stored local configuration; runtime enforcement is not inferred." : "Management intent; installation and runtime enforcement are not inferred."}</Typography>
            </>}
            {selected && <TextField label="Search rule names and comments" size="small" fullWidth value={search}
              inputProps={{ maxLength: 200 }} onChange={e => setSearch(e.target.value)} sx={{ my: 2 }} />}
            {data && <>
              <TableContainer onScroll={event => setScrollTop(event.currentTarget.scrollTop)} sx={{ maxHeight: "65vh", border: `1px solid ${m3.outlineVar}`, borderRadius: 2 }}>
                <Table stickyHeader size="small" aria-label="Policy rulebase" sx={{ minWidth: 1000 }}>
                  <TableHead><TableRow>{["No", "Name", "Source", "Destination", "Service / Application", "Action", "Log", "Comment"].map(label => <TableCell key={label}>{label}</TableCell>)}</TableRow></TableHead>
                  <TableBody><VirtualPolicyRows sections={data.sections} collapsed={collapsed} scrollTop={scrollTop}
                    toggle={id => setCollapsed(previous => { const next = new Set(previous); next.has(id) ? next.delete(id) : next.add(id); return next; })}
                    cell={cell} openRule={openRule} /></TableBody>
                </Table>
              </TableContainer>
              {data.total === 0 && <Typography sx={{ py: 2 }}>No matching rules.</Typography>}
              <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 1 }}>
                <Button disabled={page === 0} onClick={() => setPage(n => n - 1)}>Previous</Button>
                <Typography variant="caption">Page {page + 1} of {Math.max(1, Math.ceil(data.total / 200))} · {data.total} rules</Typography>
                <Button disabled={(page + 1) * 200 >= data.total} onClick={() => setPage(n => n + 1)}>Next</Button>
              </Stack>
            </>}
          </Box>
        </Box>
    </Box>
    <Drawer anchor="right" open={drawer !== null} onClose={close}>
      <Box role="dialog" aria-modal="true" aria-label={drawer?.rule ? "Rule details" : "Object details"} sx={{ width: { xs: "90vw", sm: 520 }, p: 3, overflowWrap: "anywhere" }}>
        <Button onClick={close}>Close</Button>
        {drawer?.loading && <Typography role="status">Loading object…</Typography>}
        {drawer?.error && <Typography role="alert">{drawer.error}</Typography>}
        {drawer?.object && <ObjectDetail object={drawer.object} />}
        {drawer?.rule && <>
          <Typography variant="h6">{drawer.rule.name || "Unnamed rule"}</Typography>
          <Typography>UUID: {drawer.rule.uuid || "UNKNOWN"}</Typography>
          <Typography>Enabled: {drawer.rule.enabled === null ? "UNKNOWN" : String(drawer.rule.enabled)}</Typography>
          {(["source", "destination", "service", "application"] as const).map(key => <Box key={key} sx={{ my: 1 }}>
            <Typography variant="subtitle2">{key}{drawer.rule![key].negated ? " (NOT)" : ""}</Typography>
            {drawer.rule![key].refs.slice(0, 200).map((id, index) => <Button key={`${id}-${index}`} size="small" onClick={() => void openObject(id)}>{objects.get(id)?.name ?? "Unresolved object"}</Button>)}
          </Box>)}
          <Typography variant="body2">Action: {drawer.rule.action} · Log: {drawer.rule.log}</Typography>
          <Typography variant="body2">{drawer.rule.comment}</Typography>
          {Object.entries(drawer.rule.extras).slice(0, 32).map(([key, values]) => <Typography key={key} variant="caption" display="block">{key}: {values.slice(0, 50).join(", ")}{values.length > 50 ? " …" : ""}</Typography>)}
        </>}
      </Box>
    </Drawer>
  </ScreenRoot>;
}

// Fixed-height rows bound DOM work even when a malformed response exceeds the 200-rule page.
export function VirtualPolicyRows({ sections, collapsed, scrollTop, toggle, cell, openRule }: {
  sections: PolicyPage["sections"]; collapsed: Set<string>; scrollTop: number; toggle: (id: string) => void;
  cell: (value: PolicyCell, rule: PolicyRule) => React.ReactNode; openRule: (rule: PolicyRule) => void;
}) {
  const height = 64, start = Math.max(0, Math.floor(scrollTop / height) - 4), end = start + 24;
  let offset = 0;
  const rows: React.ReactNode[] = [];
  for (const section of sections) {
    if (offset >= start && offset < end) rows.push(<TableRow key={section.id} sx={{ height }}><TableCell colSpan={8} sx={{ bgcolor: m3.scLow }}>
      <Button size="small" aria-expanded={!collapsed.has(section.id)} onClick={() => toggle(section.id)}>{collapsed.has(section.id) ? "▸" : "▾"} {section.name} · {section.total}</Button>
      <Chip size="small" variant="outlined" label={section.source} />
      {section.parentRuleId && <Typography variant="caption"> Inline layer · conditional on parent rule</Typography>}
    </TableCell></TableRow>);
    offset++;
    const count = collapsed.has(section.id) ? 0 : section.rules.length;
    for (let i = Math.max(0, start - offset); i < Math.min(count, end - offset); i++) {
      const rule = section.rules[i];
      rows.push(<TableRow key={rule.id} data-policy-rule sx={{ height, opacity: rule.enabled === false ? 0.5 : 1,
        "& > td": { height, boxSizing: "border-box", maxWidth: 260, overflow: "hidden", whiteSpace: "nowrap" } }}>
        <TableCell>{rule.number || "—"}</TableCell>
        <TableCell><Button size="small" onClick={() => openRule(rule)} sx={{ textTransform: "none" }}>{rule.name || "Unnamed rule"}</Button>{rule.enabled === false && <Typography variant="caption">Disabled</Typography>}</TableCell>
        <TableCell><Box sx={{ height: 40, overflow: "hidden" }}>{cell(rule.source, rule)}</Box></TableCell><TableCell><Box sx={{ height: 40, overflow: "hidden" }}>{cell(rule.destination, rule)}</Box></TableCell>
        <TableCell><Box sx={{ height: 40, overflow: "hidden" }}>{cell(rule.service, rule)}{rule.application.refs.length > 0 && cell(rule.application, rule)}</Box></TableCell>
        <TableCell><Chip size="small" label={rule.action} color={/^(accept|allow)$/i.test(rule.action) ? "success" : /^(deny|drop|reject)$/i.test(rule.action) ? "error" : "default"} /></TableCell>
        <TableCell>{rule.log}</TableCell><TableCell><Box sx={{ maxHeight: 40, overflow: "hidden" }}>{rule.comment}</Box></TableCell>
      </TableRow>);
    }
    offset += count;
  }
  const top = Math.min(start, offset) * height, bottom = Math.max(0, offset - end) * height;
  return <>{top > 0 && <TableRow aria-hidden="true"><TableCell colSpan={8} sx={{ height: top, p: 0, border: 0 }} /></TableRow>}{rows}
    {bottom > 0 && <TableRow aria-hidden="true"><TableCell colSpan={8} sx={{ height: bottom, p: 0, border: 0 }} /></TableRow>}</>;
}
function ObjectDetail({ object }: { object: PolicyObject }) {
  return <Box sx={{ pl: 1, borderLeft: `1px solid ${m3.outlineVar}` }}>
    <Typography variant="subtitle2">{object.name ?? "Unresolved object"}</Typography>
    <Typography variant="caption">{object.type} · {object.status}</Typography>
    {(object.values ?? []).map((value, i) => <Typography key={i} variant="body2">{value}</Typography>)}
    {(object.children ?? []).map((child, i) => <Box component="details" key={`${child.id}-${i}`} sx={{ my: 1 }}>
      <Box component="summary" sx={{ cursor: "pointer" }}>{child.name ?? "Unresolved object"} · {child.status}</Box><ObjectDetail object={child} />
    </Box>)}
  </Box>;
}
export function PolicyPreview() { return <PolicyScreen preview />; }
