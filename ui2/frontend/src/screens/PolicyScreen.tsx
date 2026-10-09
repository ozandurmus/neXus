import { JobButton } from "../shell/JobWindow";
import { useEffect, useMemo, useRef, useState } from "react";
import { Box, Button, Tabs, Tab, Checkbox, Chip, Drawer, IconButton, Tooltip, FormControl, InputLabel, MenuItem, Select, Stack, TextField, Typography } from "@mui/material";
import { ScreenRoot, ScreenHeader, EmptyPanel } from "../shell/ScreenLayout";
import { VendorBadge } from "../shell/States";
import { Icon } from "../shell/Icon";
import { relativeAge, DISPLAY_TZ } from "../shell/time";
import { JobTranscriptDrawer } from "./JobTranscriptDrawer";
import { m3 } from "../theme/m3Theme";
import { cancelJob, listPolicies, getPolicyCollectionStatus, type PolicyCollectionMode, type PolicyCollectionStatus, listPolicySources, collectPolicies, type PolicyCollectionSource, getPolicy, getPolicyObject, type PolicyTarget, type PolicyMetadata, type PolicyObject, type PolicyRule, type PolicyCell, type PolicyPage, getPolicyRule, type PolicyRuleDetail } from "../auth/adminApi";

import { VirtualPolicyCards, RuleDetailPanel, RuleHistory, scheduleLabel, sectionLabel, reportPolicyLoadError } from "./PolicyRuleViewer";

import { PolicyHygieneTab } from "./PolicyHygieneTab";
import { PolicyDomainTab } from "./PolicyDomainTabs";

const runningStates = ["REQUESTED", "CLAIMED", "EXECUTING", "RECONCILING", "RUNNING"];
const isRunning = (job: PolicyCollectionStatus) => runningStates.includes(job.state);
// Labels are presentation only; identifiers remain unchanged as request keys.
const displayName = (name: string | undefined, fallback: string) =>
  !name || /[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/i.test(name) ? fallback : name;
export const bounded = (step: number, total: number) => Math.min(Math.max(0, Number.isFinite(step) ? step : 0), total > 0 && Number.isFinite(total) ? total : Infinity);
export function progress(step: number, total: number, sentence = true) {
  const count = bounded(step, total);
  return total > 0 && Number.isFinite(total) ? sentence ? `step ${count} of ${total}` : `${count}/${total}` : `${count} steps`;
}
export function collectionOutcome(job: PolicyCollectionStatus) {
  if (isRunning(job)) return "RUNNING";
  if (job.state === "CANCELLED") return "CANCELLED";
  if (["FAILED", "REJECTED", "OUTCOME_UNKNOWN"].includes(job.state)) return "FAILED";
  if ((job.gapUnits ?? 0) > 0 || job.outcome === "PARTIAL" || job.reason?.startsWith("PARTIAL_SNAPSHOT")) return "PARTIAL";
  if (job.state === "COMPLETED") return "COMPLETED";
  return "UNKNOWN";
}
export function collectedLabel(at: string, now: Date) {
  const date = new Date(at);
  if (!Number.isFinite(date.getTime())) return "Collection time unknown";
  const day = new Intl.DateTimeFormat("en-GB", { timeZone: DISPLAY_TZ, day: "numeric", month: "short", year: "numeric" }).format(date);
  const time = new Intl.DateTimeFormat("en-GB", { timeZone: DISPLAY_TZ, hour: "2-digit", minute: "2-digit", hour12: false }).format(date);
  return `Collected ${day}, ${time} · ${relativeAge(at, now)}`;
}
export function sourceStatusLabel(job: PolicyCollectionStatus, now: Date) {
  const age = relativeAge(job.collectedAt, now);
  const outcome = collectionOutcome(job);
  if (outcome === "COMPLETED") return `Collected · ${age}`;
  if (outcome === "PARTIAL") return `Completed with gaps (${job.gapUnits === undefined || job.gapUnits === 0 ? "unit count unknown" : `${job.gapUnits} units`}) · ${age}`;
  if (outcome === "CANCELLED") return `Cancelled · ${job.reason || "Collection cancelled"}`;
  if (outcome === "FAILED") return failureSentence(job.reason, job.step, job.total) + (job.collectedAt ? ` · ${age}` : "");
  return "Collection status unknown";
}
export function collectionStalled(job: PolicyCollectionStatus, now: Date) {
  const activity = Date.parse(job.lastActivityAt ?? "");
  return isRunning(job) && Number.isFinite(activity) && (job.readTimeoutSeconds ?? 0) > 0
    && now.getTime() - activity > job.readTimeoutSeconds! * 2000;
}
export function runningLabel(job: PolicyCollectionStatus, now: Date) {
  const started = Date.parse(job.startedAt ?? "");
  const elapsed = Number.isFinite(started) ? Math.max(0, Math.floor((now.getTime() - started) / 60000)) : undefined;
  const activity = Date.parse(job.lastActivityAt ?? "");
  const seconds = Number.isFinite(activity) ? Math.max(0, Math.floor((now.getTime() - activity) / 1000)) : undefined;
  const total = job.packagesTotal ?? 0;
  const discovering = (job.domainsTotal ?? 0) > (job.domainsDone ?? 0);
  const denominator = total === 0 && !job.domainsTotal ? "?" : `${discovering ? "at least " : ""}${total}`;
  return `Running${elapsed === undefined ? "" : ` for ${elapsed} min`} · ${job.packagesDone ?? 0}/${denominator} packages · ${(job.rulesFetched ?? 0).toLocaleString("en-US")} rules · last activity ${seconds === undefined ? "unknown" : `${seconds} s ago`}`;
}
// Spreadsheet formula characters must remain text, including after leading whitespace.
export function csvCell(value: unknown) {
  const text = String(value ?? "");
  return `"${(/^[\s]*[=+@-]/.test(text) ? "'" : "") + text.replace(/"/g, '""')}"`;
}
function failureSentence(reason: string, step?: number, total?: number) {
  const scope = step === undefined ? "" : ` at collection ${progress(step, total ?? 0)}`;
  const problem = /TIMEOUT|TimedOut|JOB_DEADLINE/.test(reason) ? "the time limit was reached"
    : /HTTP_403|AuthenticationFailed|HostKeyRejected/.test(reason) ? "access was refused"
    : /PARSE|INVALID|RESPONSE|XML/.test(reason) ? "the reply was incomplete" : "the source did not answer";
  return `Last collection ${reason.startsWith("PARTIAL_SNAPSHOT") ? "was incomplete" : "failed"}${scope} — ${problem}`;
}
function FailureDetails({ reason, allowed, job }: { reason: string; allowed: boolean; job?: PolicyCollectionStatus }) {
  return allowed ? <Box component="details" sx={{ mt: 0.5, overflowWrap: "anywhere" }}>
    <Box component="summary" sx={{ cursor: "pointer", color: m3.primary, fontSize: 12 }}>Details</Box>
    {job && <Typography variant="caption" display="block">Collection progress: {job.packagesTotal === undefined ? progress(job.step, job.total) : `${job.packagesDone ?? 0}/${job.packagesTotal} packages`}</Typography>}
    <Typography component="pre" variant="caption" sx={{ whiteSpace: "pre-wrap", m: 0 }}>{reason}</Typography>
    {job && <JobTranscriptDrawer jobId={job.jobId} hasTranscript={job.hasTranscript === true} title="Policy collection transcript" />}
  </Box> : null;
}

export function PolicyScreen({ preview = false }: { preview?: boolean }) {
  const [catalog, setCatalog] = useState<PolicyMetadata[]>([]);
  const [sources, setSources] = useState<PolicyCollectionSource[]>([]);
  const [expandedSources, setExpandedSources] = useState<Set<string>>(new Set());
  const [expandedContainers, setExpandedContainers] = useState<Set<string>>(new Set());
  const [loadingTree, setLoadingTree] = useState(false);
  const [jobs, setJobs] = useState<(PolicyCollectionStatus & { sourceId: string })[]>([]);
  const [targets, setTargets] = useState<PolicyTarget[]>([]);
  const [scrollTop, setScrollTop] = useState(0);
  const [treeSearch, setTreeSearch] = useState("");
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [selectedRules, setSelectedRules] = useState<Set<string>>(new Set());
  const [selectingRules, setSelectingRules] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [bulkQueue, setBulkQueue] = useState<{ source: string; domain: string }[]>([]);
  const [canCollect, setCanCollect] = useState(false);
  const [canCancel, setCanCancel] = useState(false);
  const [cancelling, setCancelling] = useState<string[]>([]);
  const [eligibleSources, setEligibleSources] = useState<Set<string>>(new Set());
  const [collecting, setCollecting] = useState(false);
  const [collectionStatus, setCollectionStatus] = useState("");
  const [selected, setSelected] = useState(() => new URLSearchParams(window.location.search).get("policy_id") ?? "");
  const [device, setDevice] = useState(() => new URLSearchParams(window.location.search).get("device_id") ?? "");
  const [tab, setTab] = useState<"Rules" | "Objects" | "Installation" | "Hygiene">(() => new URLSearchParams(window.location.search).get("tab") === "objects" ? "Objects" : "Rules");
  const [hitFilter, setHitFilter] = useState("");
  const [hitDays, setHitDays] = useState(90);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [data, setData] = useState<PolicyPage | null>(null);
  const [error, setError] = useState("");
  const [errorCode, setErrorCode] = useState("");
  const [revision, setRevision] = useState(0);
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [drawer, setDrawer] = useState<{ rule?: PolicyRule; detail?: PolicyRuleDetail; object?: PolicyObject; loading?: boolean; error?: string; errorCode?: string; history?: boolean } | null>(null);
  const drawerRequest = useRef(0);
  const policyRequest = useRef(0);
  useEffect(() => {
    if (preview) return;
    let active = true;
    setError("");
    setErrorCode("");
    setLoadingTree(true);
    Promise.all([listPolicySources(), listPolicies()]).then(([admission, stored]) => {
      if (!active) return;
      setCanCancel(admission.canCancel === true);
      const policies = stored.policies ?? [];
      const admitted = admission.sources ?? [];
      // Stored snapshots stay reachable even if their source is no longer eligible for collection.
      const allSources = new Map(admitted.map(source => [source.sourceId, source]));
      policies.forEach(policy => {
        if (!allSources.has(policy.sourceId)) allSources.set(policy.sourceId, {
          sourceId: policy.sourceId, sourceName: policy.sourceName, vendor: policy.vendor,
        });
      });
      setSources([...allSources.values()]);
      setCatalog(policies);
      setTargets(stored.devices ?? []);
      setCanCollect(admission.canCollect === true);
      setEligibleSources(new Set(admitted.map(source => source.sourceId)));
      setJobs(current => admitted.flatMap(source => {
        const job = source.collection;
        const previous = current.find(item => item.sourceId === source.sourceId);
        if (!job) return previous ? [previous] : [];
        // Preserve a terminal poll over a stale projection of the SAME job only.
        return [{ ...(previous?.jobId === job.jobId && !isRunning(previous) && isRunning(job) ? previous : job), sourceId: source.sourceId }];
      }));
    }).catch(error => { if (active) { setErrorCode(reportPolicyLoadError("snapshots", error)); setError("Policy snapshots could not be loaded."); } })
      .finally(() => { if (active) setLoadingTree(false); });
    return () => { active = false; };
  }, [preview, revision]);
  useEffect(() => {
    const timer = window.setTimeout(() => { setQuery(search); setPage(0); }, 250);
    return () => window.clearTimeout(timer);
  }, [search]);
  const visible = useMemo(() => (catalog ?? []).filter(p => !device || p.targets.some(t => t.deviceId === device)), [catalog, device]);
  useEffect(() => {
    let active = true;
    policyRequest.current++;
    setScrollTop(0); setData(null); setDrawer(null); drawerRequest.current++; setError(""); setErrorCode("");
    if (selected) getPolicy(selected, page, query, hitFilter, hitDays).then(result => {
      if (!result?.metadata || !Array.isArray(result.sections)) throw new Error("Empty policy response");
      if (active) setData(result);
    })
      .catch(error => { if (active) { setErrorCode(reportPolicyLoadError("rules", error)); setError("Policy rules could not be loaded."); } });
    return () => { active = false; };
  }, [selected, page, query, revision, hitFilter, hitDays]);
  useEffect(() => () => { drawerRequest.current++; }, []);
  const scheduleMinute = Math.floor(now.getTime() / 60000);
  // Refresh derived schedule status from stored evidence; never triggers collection.
  useEffect(() => {
    if (!selected || !data) return;
    const request = policyRequest.current; let active = true;
    getPolicy(selected, page, query, hitFilter, hitDays).then(result => {
      if (active && request === policyRequest.current) setData(result);
    }).catch(() => { /* Retain the last stored projection on a transient read failure. */ });
    return () => { active = false; };
  }, [scheduleMinute]);
  const objects = useMemo(() => new Map((data?.objects ?? []).map(o => [o.id, o])), [data]);

  const ruleSelectionRequest = useRef(0);
  useEffect(() => { ruleSelectionRequest.current++; setSelectedRules(new Set()); }, [selected, query, revision, hitFilter, hitDays]);
  const selectAllRules = async (value: boolean) => {
    const request = ++ruleSelectionRequest.current;
    if (!value || !data) { setSelectedRules(new Set()); return; }
    setSelectingRules(true);
    try {
      const ids = new Set<string>();
      for (let index = 0; index * data.pageSize < data.total; index++) {
        const result = index === page ? data : await getPolicy(selected, index, query, hitFilter, hitDays);
        if (request !== ruleSelectionRequest.current) return;
        result.sections.forEach(section => section.rules.forEach(rule => ids.add(rule.id)));
      }
      setSelectedRules(ids);
    } catch { if (request === ruleSelectionRequest.current) setCollectionStatus("Rule selection could not be completed."); }
    finally { setSelectingRules(false); }
  };
  const openObject = async (id: string) => {
    const request = ++drawerRequest.current;
    setDrawer({ loading: true });
    try {
      const result = await getPolicyObject(id, selected);
      if (!result?.object) throw new Error("Invalid object response");
      if (request === drawerRequest.current) setDrawer({ object: result.object });
    }
    catch (error) { if (request === drawerRequest.current) setDrawer({ error: "Object details could not be loaded.", errorCode: reportPolicyLoadError("object", error) }); }
  };
  const openRuleId = async (id: string, days = 90) => {
    const request = ++drawerRequest.current; setDrawer({ loading: true });
    try {
      const detail = await getPolicyRule(selected, id, days);
      if (request === drawerRequest.current) setDrawer({ rule: detail.rule, detail });
    } catch { if (request === drawerRequest.current) setDrawer({ error: "Rule details could not be loaded." }); }
  };
  const openRule = (rule: PolicyRule) => { drawerRequest.current++; setDrawer({ rule }); };
  const toggle = (set: Set<string>, id: string) => {
    const next = new Set(set); next.has(id) ? next.delete(id) : next.add(id); return next;
  };
  const expandSource = (source: string) => setExpandedSources(current => toggle(current, source));
  const expandContainer = (key: string) => setExpandedContainers(current => toggle(current, key));
  const selectPolicies = (ids: string[], value: boolean) => setChecked(current => {
    const next = new Set(current); ids.forEach(id => value ? next.add(id) : next.delete(id)); return next;
  });
  const selection = (policies: PolicyMetadata[], name: string) => {
    const ids = policies.map(p => p.id), count = ids.filter(id => checked.has(id)).length;
    return <Checkbox size="small" checked={ids.length > 0 && count === ids.length} indeterminate={count > 0 && count < ids.length}
      disabled={ids.length === 0} inputProps={{ "aria-label": `Select ${name}` }} onChange={(_, value) => selectPolicies(ids, value)} sx={{ p: 0.5 }} />;
  };
  const selectedPolicies = catalog.filter(policy => checked.has(policy.id));
  const selectedMetadata = catalog.find(policy => policy.id === selected);
  const [linkedDomain] = useState(() => {
    const params = new URLSearchParams(window.location.search);
    const sourceId = params.get("source_id"), containerId = params.get("container_id");
    return sourceId && containerId ? { sourceId, containerId } : null;
  });
  const objectMetadata = selectedMetadata ?? (linkedDomain ? {
    ...linkedDomain, id: "", sourceName: "", containerName: "", name: "", vendor: "CP",
    collectedAt: "", artefactRef: "", targets: [],
  } : null);
  const exportSelected = async () => {
    setExporting(true); setCollectionStatus("");
    try {
      const rows: unknown[][] = [["Policy", "Snapshot", "Section", "No", "Name", "UUID", "Enabled", "Source", "Destination", "Service", "Application", "Action", "Log", "Comment", "Extras"]];
      // Fetch every page through the existing masked API, independent of the table's search/page.
      for (const policy of selectedPolicies) {
        let index = 0, total = 1;
        do {
          const snapshot = await getPolicy(policy.id, index, ""); total = snapshot.total;
          const names = new Map(snapshot.objects.map(object => [object.id, object.name]));
          const text = (value: PolicyCell) => `${value.negated ? "NOT " : ""}${value.refs.map(id => names.get(id) ?? "Unresolved object").join("; ")}`;
          snapshot.sections.forEach(section => section.rules.forEach(rule => rows.push([
            snapshot.metadata.name, snapshot.metadata.collectedAt, sectionLabel(snapshot.metadata, section), rule.number, rule.name, rule.uuid, rule.enabled ?? "UNKNOWN",
            text(rule.source), text(rule.destination), text(rule.service), text(rule.application), rule.action, rule.log, rule.comment, JSON.stringify(rule.extras),
          ])));
          index++;
        } while (index * 200 < total);
      }
      const csv = rows.map(row => row.map(csvCell).join(",")).join("\r\n");
      const url = URL.createObjectURL(new Blob(["\ufeff", csv], { type: "text/csv;charset=utf-8" }));
      const anchor = document.createElement("a"); anchor.href = url; anchor.download = "selected-policy-rules.csv";
      anchor.click(); URL.revokeObjectURL(url);
      setCollectionStatus(`Exported ${rows.length - 1} rules.`);
    } catch { setCollectionStatus("Rule export is temporarily unavailable. No file was created."); }
    finally { setExporting(false); }
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
          setLoadingTree(true);
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
  const collect = async (source: string, domain = "", mode: PolicyCollectionMode = "CHANGED_ONLY") => {
    setCollecting(true); setCollectionStatus("");
    try {
      const queued = await collectPolicies(source, domain, mode);
      setJobs(current => [...current.filter(job => job.sourceId !== source), { sourceId: source, jobId: queued.jobId, state: "REQUESTED", reason: "", step: 0, total: 0 }]);
    } catch { setBulkQueue([]); setCollectionStatus("Policy collection is temporarily unavailable. The source may be busy."); }
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
  const collectButton = (source: string, domain = "") => canCollect && eligibleSources.has(source)
    ? <Stack direction="row" flexWrap="wrap" gap={1}>
      <JobButton size="small" variant="outlined" disabled={collecting || activeJobs.length > 0 || bulkQueue.length > 0} onClick={() => void collect(source, domain)}>Collect changes</JobButton>
      <JobButton size="small" disabled={collecting || activeJobs.length > 0 || bulkQueue.length > 0} onClick={() => void collect(source, domain, "FULL")}>Full refresh (incl. hit counts)</JobButton>
    </Stack> : null;
  useEffect(() => {
    if (!canCollect || collecting || loadingTree || activeJobIds || !bulkQueue.length) return;
    const [next, ...rest] = bulkQueue; setBulkQueue(rest);
    void collect(next.source, next.domain);
  }, [bulkQueue, collecting, loadingTree, activeJobIds, canCollect]);
  const collectSelected = () => {
    const scopes = new Map<string, { source: string; domain: string }>();
    selectedPolicies.filter(p => eligibleSources.has(p.sourceId)).forEach(p => {
      const domain = p.vendor === "CP" ? p.containerId : "";
      scopes.set(JSON.stringify([p.sourceId, domain]), { source: p.sourceId, domain });
    });
    setBulkQueue([...scopes.values()]);
  };
  const close = () => { drawerRequest.current++; setDrawer(null); };
  return <ScreenRoot>
    <Box sx={{ width: "100%", height: "calc(100dvh - 116px)", minHeight: 0, display: "flex", flexDirection: "column" }}>
    <ScreenHeader title="Policy" subtitle="Management policy · configured intent" actions={!preview &&
      <Tooltip title="Refresh snapshots"><IconButton aria-label="Refresh snapshots" onClick={() => setRevision(n => n + 1)}><Box component="span" aria-hidden="true" sx={{ fontSize: 26, lineHeight: 1 }}>↻</Box></IconButton></Tooltip>} />
    <Box sx={{ mt: 2, flex: 1, minHeight: 0, display: "grid", gridTemplateColumns: { xs: "minmax(0, 1fr)", md: "270px minmax(0, 1fr)" }, gap: 2, alignItems: "stretch" }}>
      <Box component="nav" aria-label="Management policies" sx={{ bgcolor: m3.scLow, borderRadius: 3, p: 2, minWidth: 0, overflowWrap: "anywhere", border: `1px solid ${m3.outlineVar}` }}>
        <Typography variant="subtitle2">Sources · {sources.length}</Typography>
        <TextField label="Search sources, containers and policies" size="small" fullWidth value={treeSearch} onChange={e => setTreeSearch(e.target.value)} sx={{ my: 1 }} />
        <Stack spacing={0.5} sx={{ mb: 1 }}>
          <Typography variant="caption">{selectedPolicies.length} policies selected</Typography>
          {canCollect && <JobButton size="small" variant="outlined" disabled={collecting || !!activeJobIds || !!bulkQueue.length || !selectedPolicies.some(p => eligibleSources.has(p.sourceId))}
            title="PAN collects the source; CP collects selected domains sequentially" onClick={collectSelected}>Collect selected</JobButton>}
          <Button size="small" disabled={exporting || !selectedPolicies.length} onClick={() => void exportSelected()}>Export selected rules (CSV)</Button>
          {!!bulkQueue.length && <Typography role="status" variant="caption">{bulkQueue.length} collection scopes waiting</Typography>}
        </Stack>
        <Box sx={{ overflowY: "auto", maxHeight: { md: "calc(100vh - 360px)" } }}>
        {sources.map(source => {
          const policies = visible.filter(p => p.sourceId === source.sourceId);
          const nodes = [...new Map(policies.map(p => [p.containerId, { containerId: p.containerId, containerName: p.containerName }])).values()];
          const term = treeSearch.trim().toLowerCase();
          const matches = (name: string) => name.toLowerCase().includes(term);
          const sourceMatches = matches(source.sourceName);
          const filtered = nodes.filter(node => sourceMatches || matches(node.containerName) || policies.some(p => p.containerId === node.containerId && matches(p.name)));
          if (term && !sourceMatches && !filtered.length) return null;
          const job = jobs.find(item => item.sourceId === source.sourceId) ?? source.collection;
          const outcome = job ? collectionOutcome(job) : "UNKNOWN";
          const running = job && isRunning(job);
          const dot = running ? m3.primary : outcome === "FAILED" ? m3.error : outcome === "PARTIAL" ? m3.warning : outcome === "COMPLETED" ? m3.success : m3.outline;
          return <Box key={source.sourceId} role="group" aria-label="Policy source" sx={{ mb: 1, pb: 1, borderBottom: `1px solid ${m3.outlineVar}` }}>
            <Stack direction="row" spacing={0.75} alignItems="center">
              <VendorBadge vendor={source.vendor === "PAN" ? "palo_alto" : source.vendor === "CP" ? "check_point" : null} />
              <Box component="span" aria-label={running ? "Collecting" : outcome === "FAILED" ? "Collection needs attention" : outcome === "PARTIAL" ? "Partial collection" : outcome === "COMPLETED" ? "Collected" : "Not collected"}
                sx={{ width: 8, height: 8, flexShrink: 0, borderRadius: "50%", bgcolor: dot }} />
              <Button aria-expanded={expandedSources.has(source.sourceId) || !!term} onClick={() => expandSource(source.sourceId)} sx={{ minWidth: 0, textAlign: "left", textTransform: "none", justifyContent: "flex-start" }}>
                {displayName(source.sourceName, "Management server")}
              </Button>
            </Stack>
            <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
              {source.vendor === "PAN" ? "Panorama" : source.vendor === "CP" ? "MDS" : "Management server"} · {nodes.length} containers
            </Typography>
            {job && <Box sx={{ my: 0.5 }}>
              {running ? <Chip role="status" size="small" color="primary" sx={{ maxWidth: "100%", height: "auto", "& .MuiChip-label": { whiteSpace: "normal", py: 0.5 } }} label={job.packagesTotal !== undefined ? runningLabel(job, now) : job.layer ? `Collecting · unit ${bounded(job.layer, job.layers ?? 0)}${job.layers ? `/${job.layers}` : ""}, rules fetched ${job.rulesFetched ?? 0}` : `Collecting · ${progress(job.step, job.total, false)}`} />
                : <Typography role="status" variant="caption">{sourceStatusLabel(job, now)}
                  {outcome === "FAILED" && (job.outcome === "PARTIAL" || job.reason.startsWith("PARTIAL_SNAPSHOT")) && " · partial snapshot available"}
                  </Typography>}
              {running && (job.layers ?? 0) > 0 && job.packagesTotal !== undefined && <Typography variant="caption" display="block">Current package layer {bounded(job.layer ?? 0, job.layers ?? 0)}/{job.layers}</Typography>}
              {!!job.domains?.length && <Box>
                <Typography variant="caption" display="block">Domains: {job.domainsReused ?? 0} reused · {job.domainsCollected ?? 0} collected · Rules: {job.rulesReused ?? 0} reused · {job.rulesFetched ?? 0} fetched</Typography>
                {job.domains.map(domain => <Typography key={domain.containerId} variant="caption" display="block">
                  {displayName(catalog.find(p => p.containerId === domain.containerId)?.containerName, "Policy domain")}: {domain.status === "REUSED" ? `reused · unchanged since ${domain.publishTime ?? "unknown"} · hits collected at ${domain.hitsCollectedAt ?? "unknown"}` : domain.status === "COLLECTED" ? "collected" : "collecting"}
                </Typography>)}
              </Box>}
              {running && collectionStalled(job, now) && <Typography role="alert" variant="caption" color="warning.main" display="block">No activity for more than twice the read timeout.</Typography>}
              {outcome === "PARTIAL" && <Typography variant="caption" display="block">Unit failure codes: {(job.unitFailureCodes?.length ? job.unitFailureCodes : ["COLLECTION_FAILED"]).join(", ")}</Typography>}
              {outcome === "PARTIAL" && <FailureDetails reason={(job.unitFailureCodes?.length ? job.unitFailureCodes : ["COLLECTION_FAILED"]).join("\n")} allowed={canCollect} job={job} />}
              {outcome === "FAILED" && job.reason && <FailureDetails reason={job.reason} allowed={canCollect} job={job} />}
            </Box>}
            {running && job && canCancel && <Button size="small"
              disabled={job.cancelRequested || cancelling.includes(job.jobId)} onClick={() => void cancel(job.jobId)}>
              {job.cancelRequested || cancelling.includes(job.jobId) ? "Cancel requested" : "Cancel"}
            </Button>}
            {collectButton(source.sourceId)}
            {(expandedSources.has(source.sourceId) || !!term) && nodes.length === 0
              && <Typography variant="caption" display="block">No policy containers in this snapshot.</Typography>}
            {(expandedSources.has(source.sourceId) || !!term) && filtered.map(container => {
              const key = JSON.stringify([source.sourceId, container.containerId]);
              const children = policies.filter(p => p.containerId === container.containerId);
              const count = children.every(p => p.ruleCount !== undefined) ? children.reduce((sum, p) => sum + p.ruleCount!, 0) : undefined;
              return <Box key={key} role="group" aria-label="Policy container" sx={{ pl: 1, mt: 0.5, borderLeft: `1px solid ${m3.outlineVar}` }}>
                <Stack direction="row" alignItems="center">
                  {selection(children, displayName(container.containerName, "Policy container"))}
                  <Button aria-label={displayName(container.containerName, "Policy container")} aria-expanded={expandedContainers.has(key) || !!term} onClick={() => expandContainer(key)} sx={{ textTransform: "none", textAlign: "left", justifyContent: "flex-start" }}>
                    <Box component="span" aria-label="Stored snapshot" sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: m3.success, mr: 1, flexShrink: 0 }} />
                    {displayName(container.containerName, "Policy container")}
                  </Button>
                </Stack>
                <Typography variant="caption" display="block" sx={{ pl: 4 }}>{children.length} policies · {count ?? "UNKNOWN"} rules</Typography>
                {source.vendor === "CP" && collectButton(source.sourceId, container.containerId)}
                {(expandedContainers.has(key) || !!term) && children.filter(p => !term || sourceMatches || matches(container.containerName) || matches(p.name)).map(policy =>
                  <Box key={policy.id} sx={{ pl: 1 }}><Stack direction="row" alignItems="center">
                    {selection([policy], displayName(policy.name, "Policy"))}
                    <Button fullWidth aria-label={`Policy ${displayName(policy.name, "Policy")}`} variant="text"
                      aria-pressed={selected === policy.id} aria-current={selected === policy.id ? "page" : undefined} sx={{ justifyContent: "flex-start", textTransform: "none", bgcolor: selected === policy.id ? m3.scHigh : undefined, boxShadow: "none", minWidth: 0 }}
                      onClick={() => { setSelected(policy.id); setPage(0); setCollapsed(new Set()); }}>
                      <Box component="span" aria-label="Stored snapshot" sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: m3.success, mr: 1, flexShrink: 0 }} />{displayName(policy.name, "Policy")}</Button>
                  </Stack><Typography variant="caption" sx={{ pl: 4 }}>{policy.ruleCount ?? (data?.metadata.id === policy.id ? data.total : "UNKNOWN")} rules</Typography></Box>)}
              </Box>;
            })}
          </Box>;
        })}
        </Box>
      </Box>
      <Box component="section" aria-label="Policy content" sx={{ minWidth: 0, minHeight: 0, display: "flex", flexDirection: "column", overflowY: "auto", overflowX: "hidden", bgcolor: m3.scLowest, borderRadius: 3, p: 2, border: `1px solid ${m3.outlineVar}`, '& > :not([role="region"])': { flexShrink: 0 } }}>
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
        {(selected || objectMetadata) && <Tabs value={tab} onChange={(_, value) => { setTab(value); setDrawer(null); drawerRequest.current++; }} aria-label="Policy tabs" sx={{ mb: 2 }}>
          {["Rules", "Objects", "Installation", "Hygiene"].map(label => <Tab key={label} label={label} value={label} />)}
        </Tabs>}
        {(tab === "Objects" || tab === "Installation") && objectMetadata && <PolicyDomainTab
          key={`${tab}:${objectMetadata.sourceId}:${objectMetadata.containerId}:${revision}`}
          tab={tab} metadata={objectMetadata} policies={catalog} />}
        {tab === "Hygiene" && <PolicyHygieneTab key={`${selected}:${revision}`} policy={selected} openRule={(id, days) => void openRuleId(id, days)} openObject={openObject} />}
        <Box role="tabpanel" aria-label="Rules" hidden={tab !== "Rules"} sx={{ display: tab === "Rules" ? "contents" : "none" }}>
        {error && <Box data-error-code={errorCode}><EmptyPanel title="Policy unavailable" body={error}><Button onClick={() => setRevision(n => n + 1)}>Retry</Button></EmptyPanel></Box>}
        {loadingTree && !error && <Typography role="status">Loading policies…</Typography>}
        {!selected && !loadingTree && !error && <Box role="status" aria-label={sources.length ? "No collected policy" : "No policy snapshot"} sx={{ textAlign: "center", py: 3, color: m3.onSurfaceVar }}>
          <Icon name="rows" size={32} />
          <Typography variant="h6" sx={{ mt: 2 }}>{sources.length ? "No assigned policy snapshot" : "No policy snapshot"}</Typography>
          <Typography variant="body2">{sources.length ? "Select a source and container to browse stored policies." : "No management policy has been collected yet."}</Typography>
        </Box>}
            {selected && !data && !error && <Typography role="status">Loading rules…</Typography>}
            {data && <>
              {!!data.failures?.length && <Box><Chip color="warning" label="Partial snapshot · incomplete" />
                {data.failures.map(f => <Box key={f.layerRef} role="alert"><Typography variant="body2">{f.layerName || f.layerRef}{f.offset !== undefined ? ` · offset ${f.offset}` : ""}: {failureSentence(f.reason)}</Typography><FailureDetails reason={f.reason} allowed={canCollect} /></Box>)}</Box>}
              {data.policyKind === "LOCAL_FIREWALL" && <Chip size="small" label="local firewall policy" />}
              <Chip size="small" title={data.metadata.collectedAt} label={collectedLabel(data.metadata.collectedAt, now)} sx={{ my: 1, alignSelf: "flex-start" }} />
              <Stack direction="row" flexWrap="wrap" gap={1} alignItems="center" sx={{ my: 1 }}><Typography variant="body2">Assigned to:</Typography>
                {data.metadata.targets.length === 0 ? <Chip size="small" label="Unassigned" /> : data.metadata.targets.map(t => <Tooltip key={t.deviceId} title={`${t.context ? `${t.context} · ` : ""}${t.syncStatus}`}><Chip size="small" variant="outlined" label={<Stack direction="row" gap={0.75} alignItems="center"><span>{displayName(t.name, "Device")}</span><Box component="span" aria-label={t.syncStatus} sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: t.syncStatus === "IN_SYNC" ? m3.success : t.syncStatus === "OUT_OF_SYNC" ? m3.warning : m3.outline }} /></Stack>} /></Tooltip>)}</Stack>
              <Typography variant="caption">{data.policyKind === "LOCAL_FIREWALL" ? "Stored local configuration; runtime enforcement is not inferred." : "Management intent; installation and runtime enforcement are not inferred."}</Typography>
            </>}
            {selected && <Stack direction="row" gap={1} sx={{ mt: 1 }}>
              <TextField select label="Hit filter" size="small" value={hitFilter} sx={{ minWidth: 200 }} onChange={e => { setHitFilter(e.target.value); setPage(0); }}>
                <MenuItem value="">All hit evidence</MenuItem><MenuItem value="inactive">No hits in N days</MenuItem><MenuItem value="never">Never hit</MenuItem>
              </TextField>
              {hitFilter === "inactive" && <TextField label="Days without hits" type="number" size="small" value={hitDays} inputProps={{ min: 1, max: 36500 }}
                onChange={e => { const n = Number(e.target.value); if (Number.isInteger(n) && n >= 1 && n <= 36500) { setHitDays(n); setPage(0); } }} />}
            </Stack>}
            {selected && <TextField label="Rule query" size="small" fullWidth value={search}
              placeholder="source.ip='192.0.2.10' AND service='tcp/443'"
              helperText="Fields: source.ip, destination.ip, service, application, action, name, comment, user, zone.from, zone.to, enabled, expired, time. Hit filters: lasthit.days>90, hits=0. AND / OR / NOT, parentheses; plain text searches names and comments."
              inputProps={{ maxLength: 1000 }} onChange={e => setSearch(e.target.value)} sx={{ my: 2 }} />}
            {data && <>
              <Stack direction="row" flexWrap="wrap" gap={1} alignItems="center" sx={{ mb: 1 }}>
                <Checkbox size="small" inputProps={{ "aria-label": "Select all" }} checked={data.total > 0 && selectedRules.size === data.total}
                  indeterminate={selectedRules.size > 0 && selectedRules.size < data.total} disabled={selectingRules || !data.total}
                  onChange={(_, value) => { void selectAllRules(value); }} />
                <Typography variant="body2">{data.total} rules found</Typography>
                {selectedRules.size > 0 && <Typography variant="caption">{selectedRules.size} selected</Typography>}
                {query && <>{(query.match(/[a-z.]+\s*=\s*(?:'(?:\\.|[^'])*'|true|false)/gi) ?? [query]).map((filter, index) => <Chip key={index} size="small" label={filter} title={query} sx={{ maxWidth: 500 }} />)}<Button size="small" onClick={() => setSearch("")}>Clear</Button></>}
                <Button size="small" onClick={() => { drawerRequest.current++; setDrawer({ history: true }); }}>Policy history</Button>
              </Stack>
              <Box role="region" aria-label="Policy rulebase" onScroll={event => setScrollTop(event.currentTarget.scrollTop)}
                sx={{ flex: "1 1 180px", minHeight: 140, overflowY: "auto", overflowX: "hidden", border: `1px solid ${m3.outlineVar}`, borderRadius: 2 }}>
                <Box sx={{ minWidth: 0, p: 1 }}><VirtualPolicyCards sections={data.sections} collapsed={collapsed} scrollTop={scrollTop}
                  metadata={data.metadata} objects={objects} openObject={openObject} openRule={openRule} openRuleId={id => void openRuleId(id)}
                  selected={selectedRules} onSelect={id => setSelectedRules(previous => toggle(previous, id))}
                  toggle={id => setCollapsed(previous => toggle(previous, id))} /></Box>
              </Box>
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
    </Box>
    <Drawer anchor="right" open={drawer !== null} onClose={close}>
      <Box role="dialog" aria-modal="true" aria-label={drawer?.rule ? "Rule details" : drawer?.history ? "Policy history" : "Object details"} sx={{ width: { xs: "90vw", sm: drawer?.rule || drawer?.history ? 960 : 520 }, p: 3, overflowWrap: "anywhere" }}>
        <Button onClick={close}>Close</Button>
        {drawer?.loading && <Typography role="status">Loading details…</Typography>}
        {drawer?.error && <Typography role="alert" data-error-code={drawer.errorCode}>{drawer.error}</Typography>}
        {drawer?.object && <ObjectDetail object={drawer.object} />}
        {drawer?.history && <RuleHistory policy={selected} />}
        {drawer?.rule && (drawer.detail || data) && <RuleDetailPanel key={drawer.rule.id} rule={drawer.rule} metadata={drawer.detail?.metadata ?? data!.metadata}
          section={drawer.detail?.section ?? data!.sections.find(section => section.rules.some(rule => rule.id === drawer.rule?.id))!}
          objects={drawer.detail ? new Map(drawer.detail.objects.map(o => [o.id, o])) : objects} openObject={openObject} openRuleId={id => void openRuleId(id)} />}
      </Box>
    </Drawer>
  </ScreenRoot>;
}

function ObjectDetail({ object }: { object: PolicyObject }) {
  return <Box sx={{ pl: 1, borderLeft: `1px solid ${m3.outlineVar}` }}>
    <Typography variant="subtitle2">{object.name ?? "Unresolved object"}</Typography>
    <Typography variant="caption">{object.type} · {object.status}</Typography>
    {object.schedule && <Typography variant="body2">{scheduleLabel(object.schedule)}</Typography>}
    {(object.values ?? []).map((value, i) => <Typography key={i} variant="body2">{value}</Typography>)}
    {(object.children ?? []).map((child, i) => <Box component="details" key={`${child.id}-${i}`} sx={{ my: 1 }}>
      <Box component="summary" sx={{ cursor: "pointer" }}>{child.name ?? "Unresolved object"} · {child.status}</Box><ObjectDetail object={child} />
    </Box>)}
  </Box>;
}
export function PolicyPreview() { return <PolicyScreen preview />; }
