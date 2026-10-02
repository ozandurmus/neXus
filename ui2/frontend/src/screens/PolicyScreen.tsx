import { useEffect, useMemo, useRef, useState } from "react";
import { Box, Button, Chip, Drawer, FormControl, InputLabel, MenuItem, Select, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { ScreenRoot, ScreenHeader, EmptyPanel } from "../shell/ScreenLayout";
import { m3 } from "../theme/m3Theme";
import { getPolicyTree, getPolicyCollectionStatus, type PolicyContainer, type PolicyCollectionStatus, listPolicySources, collectPolicies, type PolicyCollectionSource, getPolicy, getPolicyObject, type PolicyTarget, type PolicyMetadata, type PolicyObject, type PolicyRule, type PolicyCell, type PolicyPage } from "../auth/adminApi";

export function PolicyScreen({ preview = false }: { preview?: boolean }) {
  const [catalog, setCatalog] = useState<PolicyMetadata[]>([]);
  const [sources, setSources] = useState<PolicyCollectionSource[]>([]);
  const [containers, setContainers] = useState<Record<string, PolicyContainer[]>>({});
  const [expandedSources, setExpandedSources] = useState<Set<string>>(new Set());
  const [expandedContainers, setExpandedContainers] = useState<Set<string>>(new Set());
  const [loadedContainers, setLoadedContainers] = useState<Set<string>>(new Set());
  const [loadingTree, setLoadingTree] = useState(false);
  const [jobs, setJobs] = useState<PolicyCollectionStatus[]>([]);
  const [targets, setTargets] = useState<PolicyTarget[]>([]);
  const [scrollTop, setScrollTop] = useState(0);
  const [canCollect, setCanCollect] = useState(false);
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
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [drawer, setDrawer] = useState<{ rule?: PolicyRule; object?: PolicyObject; loading?: boolean; error?: string } | null>(null);
  const drawerRequest = useRef(0);
  useEffect(() => {
    if (preview) return;
    let active = true;
    setError("");
    setLoadingTree(true);
    Promise.all([listPolicySources(), getPolicyTree("", "", device)]).then(([admission, tree]) => {
      if (!active) return;
      setSources([...new Map([...(tree.sources ?? []), ...(admission.sources ?? [])].map(s => [s.sourceId, s])).values()]);
      setCanCollect(admission.canCollect === true);
      setTargets(tree.devices ?? []);
      setJobs(current => {
        const latest = (admission.sources ?? []).flatMap(source => source.collection ? [source.collection] : []);
        return latest.length ? latest : current.filter(job => !["REQUESTED", "CLAIMED", "EXECUTING", "RECONCILING"].includes(job.state));
      });
      setCatalog([]); setContainers({}); setExpandedSources(new Set()); setExpandedContainers(new Set()); setLoadedContainers(new Set());
      setSelected("");
    }).catch(() => { if (active) setError("Policy snapshots could not be loaded."); })
      .finally(() => { if (active) setLoadingTree(false); });
    return () => { active = false; };
  }, [preview, revision, device]);
  useEffect(() => {
    const timer = window.setTimeout(() => { setQuery(search); setPage(0); }, 250);
    return () => window.clearTimeout(timer);
  }, [search]);
  const visible = useMemo(() => (catalog ?? []).filter(p => !device || p.targets.some(t => t.deviceId === device)), [catalog, device]);
  useEffect(() => {
    let active = true;
    setScrollTop(0); setData(null); setDrawer(null); drawerRequest.current++; setError("");
    if (selected) getPolicy(selected, page, query).then(result => { if (active) setData(result); })
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
      setLoadedContainers(current => new Set(current).add(container));
    } catch { setError("Policies could not be loaded."); }
  };
  const activeJobs = jobs.filter(job => ["REQUESTED", "CLAIMED", "EXECUTING", "RECONCILING"].includes(job.state));
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
        setJobs(current => current.map(job => statuses.find(status => status.jobId === job.jobId) ?? job));
        if (statuses.some(status => !["REQUESTED", "CLAIMED", "EXECUTING", "RECONCILING"].includes(status.state))) {
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
      setJobs(current => [...current, { jobId: queued.jobId, state: "REQUESTED", reason: "", step: 0, total: 0 }]);
      setCollectionStatus("Policy collection queued. Refresh snapshots after the job completes.");
    } catch { setCollectionStatus("Policy collection could not be queued. The source may be busy or unavailable."); }
    finally { setCollecting(false); }
  };
  const collectButton = (source: string, domain = "") => canCollect && sources.some(s => s.sourceId === source)
    ? <Button size="small" disabled={collecting || activeJobs.length > 0} onClick={() => void collect(source, domain)}>Collect policies</Button> : null;
  const close = () => { drawerRequest.current++; setDrawer(null); };
  return <ScreenRoot>
    <ScreenHeader title="Policy" subtitle="Management policy · configured intent" />
    <Box sx={{ px: 3, pb: 3 }}>
      {collectionStatus && <Typography role="status" sx={{ mb: 1 }}>{collectionStatus}</Typography>}
      {!preview && <Button onClick={() => setRevision(n => n + 1)}>Refresh snapshots</Button>}
      {activeJobs.map(job => <Chip key={job.jobId} role="status" title={job.jobId} label={`Collecting… step ${job.step}/${job.total || "?"}`} size="small" />)}
      {jobs.filter(job => job.reason).map(job => <Typography key={job.jobId} role="status">{job.reason}</Typography>)}
      {error && <EmptyPanel title="Policy unavailable" body={error}><Button onClick={() => setRevision(n => n + 1)}>Retry</Button></EmptyPanel>}
      {loadingTree && !error && <Typography role="status">Loading policies…</Typography>}
      {!loadingTree && !error && sources.length === 0 && <Box role="status" aria-label="No policy snapshot"><EmptyPanel title="No policy snapshot" body="No management policy has been collected yet." /></Box>}
      {sources.length > 0 && <>
        <FormControl size="small" sx={{ minWidth: 240, mb: 2 }}>
          <InputLabel id="policy-device-label">Assigned device</InputLabel>
          <Select labelId="policy-device-label" label="Assigned device" value={device} onChange={e => { setDevice(e.target.value); setPage(0); }}>
            <MenuItem value="">All management policies</MenuItem>
            {targets.map(t => <MenuItem key={t.deviceId} value={t.deviceId}>{t.name}</MenuItem>)}
            {device && !targets.some(t => t.deviceId === device) && <MenuItem value={device}>Requested device</MenuItem>}
          </Select>
        </FormControl>
        {visible.length === 0 && <Box role="status" aria-label="No collected policy"><EmptyPanel title="No assigned policy snapshot" body="Expand a source and container to find collected policies. No policy may have been collected for this scope yet." /></Box>}
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", lg: "260px minmax(0, 1fr)" }, gap: 2 }}>
          <Box component="nav" aria-label="Management policies" sx={{ bgcolor: m3.scLow, borderRadius: 2, p: 1.5 }}>
            {sources.map(source => <Box key={source.sourceId} role="group" aria-label="Policy source">
              <Button aria-expanded={expandedSources.has(source.sourceId)} onClick={() => void expandSource(source.sourceId)}>{source.sourceName}</Button>
              {collectButton(source.sourceId)}
              {expandedSources.has(source.sourceId) && (containers[source.sourceId] ?? []).map(container => <Box key={container.containerId} role="group" aria-label="Policy container" sx={{ pl: 1 }}>
                <Button aria-expanded={expandedContainers.has(container.containerId)} onClick={() => void expandContainer(source.sourceId, container.containerId)}>{container.containerName}</Button>
                {source.vendor === "CP" && collectButton(source.sourceId, container.containerId)}
                {expandedContainers.has(container.containerId) && visible.filter(p => p.containerId === container.containerId).map(policy =>
                  <Button key={policy.id} fullWidth variant={selected === policy.id ? "contained" : "text"}
                    aria-pressed={selected === policy.id} aria-current={selected === policy.id ? "page" : undefined} sx={{ justifyContent: "flex-start", textTransform: "none" }}
                    onClick={() => { setSelected(policy.id); setPage(0); setCollapsed(new Set()); }}>{policy.name}</Button>)}
              </Box>)}
            </Box>)}
          </Box>
          <Box sx={{ minWidth: 0 }}>
            {selected && !data && !error && <Typography role="status">Loading rules…</Typography>}
            {data && <>
              <Typography variant="h6">{data.metadata.name}</Typography>
              {!!data.failures?.length && <Box><Chip color="warning" label="Partial snapshot · incomplete" />
                {data.failures.map(f => <Typography key={f.layerRef} role="alert" variant="caption">{f.layerRef}: {f.reason}</Typography>)}</Box>}
              {data.policyKind === "LOCAL_FIREWALL" && <Chip size="small" label="local firewall policy" />}
              <Chip size="small" label={`From configuration collected ${data.metadata.collectedAt}`} sx={{ my: 1 }} />
              <Typography variant="body2">Assigned to: {data.metadata.targets.length === 0 ? "Unassigned" : data.metadata.targets.map(t => `${t.name}${t.context ? ` (${t.context})` : ""} · ${t.syncStatus}`).join(", ")}</Typography>
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
      </>}
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
            {drawer.rule![key].refs.slice(0, 200).map((id, index) => <Button key={`${id}-${index}`} size="small" onClick={() => void openObject(id)}>{objects.get(id)?.name ?? id}</Button>)}
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
