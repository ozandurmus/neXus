import { useEffect, useMemo, useRef, useState } from "react";
import { Box, Button, Chip, Drawer, FormControl, InputLabel, MenuItem, Select, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { ScreenRoot, ScreenHeader, EmptyPanel } from "../shell/ScreenLayout";
import { m3 } from "../theme/m3Theme";
import { listPolicies, getPolicy, getPolicyObject, type PolicyMetadata, type PolicyObject, type PolicyRule, type PolicyCell, type PolicyPage } from "../auth/adminApi";

export function PolicyScreen({ preview = false }: { preview?: boolean }) {
  const [catalog, setCatalog] = useState<PolicyMetadata[] | null>(preview ? [] : null);
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
    listPolicies().then(result => { if (active) setCatalog(result.policies); })
      .catch(() => { if (active) setError("Policy snapshots could not be loaded."); });
    return () => { active = false; };
  }, [preview, revision]);
  useEffect(() => {
    const timer = window.setTimeout(() => { setQuery(search); setPage(0); }, 250);
    return () => window.clearTimeout(timer);
  }, [search]);
  const visible = useMemo(() => (catalog ?? []).filter(p => !device || p.targets.some(t => t.deviceId === device)), [catalog, device]);
  useEffect(() => {
    setSelected(current => visible.some(p => p.id === current) ? current : visible[0]?.id ?? "");
    setPage(0);
  }, [visible]);
  useEffect(() => {
    let active = true;
    setData(null); setDrawer(null); drawerRequest.current++; setError("");
    if (selected) getPolicy(selected, page, query).then(result => { if (active) setData(result); })
      .catch(() => { if (active) setError("Policy rules could not be loaded."); });
    return () => { active = false; };
  }, [selected, page, query, revision]);
  useEffect(() => () => { drawerRequest.current++; }, []);
  const objects = useMemo(() => new Map((data?.objects ?? []).map(o => [o.id, o])), [data]);
  const targets = [...new Map((catalog ?? []).flatMap(p => p.targets).map(t => [t.deviceId, t])).values()];
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
  const tree = new Map<string, Map<string, PolicyMetadata[]>>();
  for (const policy of visible) {
    if (!tree.has(policy.sourceId)) tree.set(policy.sourceId, new Map());
    const containers = tree.get(policy.sourceId)!;
    if (!containers.has(policy.containerId)) containers.set(policy.containerId, []);
    containers.get(policy.containerId)!.push(policy);
  }
  const close = () => { drawerRequest.current++; setDrawer(null); };
  return <ScreenRoot>
    <ScreenHeader title="Policy" subtitle="Management policy · configured intent" />
    <Box sx={{ px: 3, pb: 3 }}>
      {error && <EmptyPanel title="Policy unavailable" body={error}><Button onClick={() => setRevision(n => n + 1)}>Retry</Button></EmptyPanel>}
      {catalog === null && !error && <Typography role="status">Loading policies…</Typography>}
      {catalog?.length === 0 && <EmptyPanel title="No policy snapshot" body="No management policy has been collected yet." />}
      {!!catalog?.length && <>
        <FormControl size="small" sx={{ minWidth: 240, mb: 2 }}>
          <InputLabel id="policy-device-label">Assigned device</InputLabel>
          <Select labelId="policy-device-label" label="Assigned device" value={device} onChange={e => { setDevice(e.target.value); setPage(0); }}>
            <MenuItem value="">All management policies</MenuItem>
            {targets.map(t => <MenuItem key={t.deviceId} value={t.deviceId}>{t.name}</MenuItem>)}
            {device && !targets.some(t => t.deviceId === device) && <MenuItem value={device}>Requested device</MenuItem>}
          </Select>
        </FormControl>
        {visible.length === 0 && <EmptyPanel title="No assigned policy snapshot" body="No collected policy is assigned to this device." />}
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", lg: "260px minmax(0, 1fr)" }, gap: 2 }}>
          <Box component="nav" aria-label="Management policies" sx={{ bgcolor: m3.scLow, borderRadius: 2, p: 1.5 }}>
            {[...tree.entries()].map(([source, containers]) => <Box key={source}>
              <Typography variant="subtitle2">{[...containers.values()][0][0].sourceName}</Typography>
              {[...containers.entries()].map(([container, policies]) => <Box key={container} sx={{ pl: 1 }}>
                <Typography variant="caption">{policies[0].containerName}</Typography>
                {policies.map(policy => <Button key={policy.id} fullWidth variant={selected === policy.id ? "contained" : "text"}
                  aria-current={selected === policy.id ? "page" : undefined} sx={{ justifyContent: "flex-start", textTransform: "none" }}
                  onClick={() => { setSelected(policy.id); setPage(0); setCollapsed(new Set()); }}>{policy.name}</Button>)}
              </Box>)}
            </Box>)}
          </Box>
          <Box sx={{ minWidth: 0 }}>
            {selected && !data && !error && <Typography role="status">Loading rules…</Typography>}
            {data && <>
              <Typography variant="h6">{data.metadata.name}</Typography>
              <Chip size="small" label={`From configuration collected ${data.metadata.collectedAt}`} sx={{ my: 1 }} />
              <Typography variant="body2">Assigned to: {data.metadata.targets.length === 0 ? "Unassigned" : data.metadata.targets.map(t => `${t.name}${t.context ? ` (${t.context})` : ""} · ${t.syncStatus}`).join(", ")}</Typography>
              <Typography variant="caption">Management intent; installation and runtime enforcement are not inferred.</Typography>
            </>}
            {selected && <TextField label="Search rule names and comments" size="small" fullWidth value={search}
              inputProps={{ maxLength: 200 }} onChange={e => setSearch(e.target.value)} sx={{ my: 2 }} />}
            {data && <>
              <TableContainer sx={{ maxHeight: "65vh", border: `1px solid ${m3.outlineVar}`, borderRadius: 2 }}>
                <Table stickyHeader size="small" aria-label="Policy rulebase" sx={{ minWidth: 1000 }}>
                  <TableHead><TableRow>{["No", "Name", "Source", "Destination", "Service / Application", "Action", "Log", "Comment"].map(label => <TableCell key={label}>{label}</TableCell>)}</TableRow></TableHead>
                  <TableBody>{data.sections.map(section => <SectionRows key={section.id} section={section} collapsed={collapsed.has(section.id)}
                    toggle={() => setCollapsed(previous => { const next = new Set(previous); next.has(section.id) ? next.delete(section.id) : next.add(section.id); return next; })}
                    cell={cell} openRule={openRule} />)}</TableBody>
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
            {drawer.rule![key].refs.map((id, index) => <Button key={`${id}-${index}`} size="small" onClick={() => void openObject(id)}>{objects.get(id)?.name ?? id}</Button>)}
          </Box>)}
          <Box component="pre" sx={{ whiteSpace: "pre-wrap", fontSize: 12 }}>{JSON.stringify(drawer.rule, null, 2)}</Box>
        </>}
      </Box>
    </Drawer>
  </ScreenRoot>;
}

function SectionRows({ section, collapsed, toggle, cell, openRule }: {
  section: PolicyPage["sections"][number]; collapsed: boolean; toggle: () => void;
  cell: (value: PolicyCell, rule: PolicyRule) => React.ReactNode; openRule: (rule: PolicyRule) => void;
}) {
  return <>
    <TableRow><TableCell colSpan={8} sx={{ bgcolor: m3.scLow }}>
      <Button size="small" aria-expanded={!collapsed} onClick={toggle}>{collapsed ? "▸" : "▾"} {section.name} · {section.total}</Button>
      <Chip size="small" variant="outlined" label={section.source} />
      {section.parentRuleId && <Typography variant="caption"> Inline layer · conditional on parent rule</Typography>}
    </TableCell></TableRow>
    {!collapsed && section.rules.map(rule => <TableRow key={rule.id} sx={{ opacity: rule.enabled === false ? 0.5 : 1 }}>
      <TableCell>{rule.number || "—"}</TableCell>
      <TableCell><Button size="small" onClick={() => openRule(rule)} sx={{ textTransform: "none" }}>{rule.name || "Unnamed rule"}</Button>{rule.enabled === false && <Typography variant="caption">Disabled</Typography>}</TableCell>
      <TableCell>{cell(rule.source, rule)}</TableCell><TableCell>{cell(rule.destination, rule)}</TableCell>
      <TableCell>{cell(rule.service, rule)}{rule.application.refs.length > 0 && cell(rule.application, rule)}</TableCell>
      <TableCell><Chip size="small" label={rule.action} color={/^(accept|allow)$/i.test(rule.action) ? "success" : /^(deny|drop|reject)$/i.test(rule.action) ? "error" : "default"} /></TableCell>
      <TableCell>{rule.log}</TableCell><TableCell sx={{ maxWidth: 260, overflowWrap: "anywhere" }}>{rule.comment}</TableCell>
    </TableRow>)}
  </>;
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
