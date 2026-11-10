import { Fragment, useEffect, useState } from "react";
import { Alert, Box, FormControlLabel, Link, MenuItem, Stack, Switch, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from "@mui/material";
import {
  getLifecycleAdvisories, getLifecycleAdvisory, getLifecycleAdvisorySources,
  type LifecycleAdvisory, type LifecycleAdvisoryDevice, type LifecycleAdvisorySource, type LifecycleVulnerabilities,
} from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { M3Button } from "../shell/M3Widgets";
import { PillRow, StatusPill } from "../shell/StatusPill";
import { plural } from "../shell/plural";
import { formatStamp, relativeAge } from "../shell/time";
import { m3 } from "../theme/m3Theme";
import { lifecycleError } from "./LifecycleCatalogPanel";

/*
 * The vulnerability check (ADVISORY_MATCHING_CONTRACT.md, sections 1, 7 and 8) in words.
 *
 * Colour is kept for what concerns this fleet: red for an exploited advisory that affects a device, amber for
 * "affected by version". Severity is a word, a clean result is plain text with what it was compared against, and a
 * device that was not judged is hatched or muted -- never green (contract rule 4).
 *
 * Every advisory field (title, fix statement, code word, link) is untrusted public text: it is only ever rendered as
 * a React text node, and a code word is looked up in a Map so that no word can reach an object's own members.
 */

/** Rows shown before "+N more", as on the Fleet table. */
export const ADVISORY_PAGE = 50;
/** The sentence every affected verdict carries (contract rule 6): once per list, not per row. */
export const AFFECTED_BY_VERSION = "Affected by version; exposure depends on configuration.";

const SOURCE_NAMES = new Map([["paloalto", "Palo Alto Networks"], ["fortinet", "Fortinet"], ["cisco", "Cisco"], ["cisa_kev", "CISA Known Exploited Vulnerabilities"]]);
export const advisorySourceName = (key: string | null | undefined) => (key && SOURCE_NAMES.get(key)) || key || "UNKNOWN";
const SEVERITY_WORDS = new Map([["CRITICAL", "Critical"], ["HIGH", "High"], ["MEDIUM", "Medium"], ["LOW", "Low"], ["NONE", "None"], ["UNKNOWN", "Unknown"]]);
export const severityWord = (severity: string | null | undefined) => (severity && SEVERITY_WORDS.get(severity)) || severity || "Unknown";
const REASON_WORDS = new Map([
  ["SOURCE_NEVER_LOADED", "advisory source never loaded"], ["SOURCE_STALE", "advisory source older than 30 days"],
  ["NO_COMPARATOR", "no version comparison for this product"], ["VERSION_MISSING", "no software version stored"],
  ["VERSION_UNPARSED", "version form not recognised"]]);
const reasonWord = (reason: string | null | undefined) => (reason ? REASON_WORDS.get(reason) ?? reason : null);
const BASIS_SENTENCES = new Map([
  ["IN_RANGE", "The version lies inside the affected range."],
  ["BELOW_HOTFIX", "The version is below the hotfix the vendor names as fixed."],
  ["EXACT_VERSION_LISTED", "The vendor lists exactly this version as affected."],
  ["AT_OR_ABOVE_FIX", "The version is at or above the vendor's fixed version."],
  ["TRAIN_UNAFFECTED", "The vendor names this release line as unaffected."],
  ["VERSION_NOT_LISTED_IN_TRAIN", "The vendor lists the affected versions of this release line; this one is not among them."],
  ["BELOW_AFFECTED_RANGE", "The version is older than the affected range."],
  ["VERSION_UNPARSED", "The device's version text was not recognised."],
  ["ADVISORY_NEEDS_REVIEW", "The advisory was not read in full."],
  ["NO_RULE_COVERS_VERSION", "No rule of the advisory covers this version."],
  ["NO_COMPARATOR", "neXus has no version comparison for this product."],
  ["TRAIN_AFFECTED", "The vendor names this whole release line as affected."],
  ["CHANGE_POINT_AFFECTED", "The version is at or above the point from which the vendor names the release line affected."],
  ["IN_UNAFFECTED_RANGE", "The version lies inside a range the vendor names as unaffected."],
  ["EXACT_VERSION_UNAFFECTED", "The vendor lists exactly this version as unaffected."],
  ["BELOW_HOTFIX_UNAFFECTED", "The version is below the hotfix from which the vendor names the release affected."],
  ["PRODUCT_NOT_NAMED", "The advisory does not name this product."],
  ["TRAIN_NOT_NAMED", "The advisory does not name this release line."],
  ["EVALUATION_ERROR", "The comparison stopped on an internal fault; there is no verdict."]]);
/** The rule that decided, as a sentence; a code word this build does not know is shown as it came. */
export const basisSentence = (code: string | null | undefined) => (code ? BASIS_SENTENCES.get(code) ?? code : "UNKNOWN");

const muted = { color: "text.secondary" } as const;
const count = (n: number | null | undefined) => (typeof n === "number" && n > 0 ? n : 0);

/** "loaded 1 d ago", from the age the server counted, else from the load time. */
function loadedAge(b: { age_days?: number | null; loaded_at?: string | null }): string {
  if (typeof b.age_days === "number") return b.age_days <= 0 ? "loaded today" : `loaded ${b.age_days} d ago`;
  return b.loaded_at ? `loaded ${relativeAge(b.loaded_at)}` : "load time not known";
}

/** What a clean result was compared against: "of 359 advisories, loaded 1 d ago". Never empty for an evaluated device. */
export function vulnerabilityBasis(v: LifecycleVulnerabilities | null | undefined): string {
  const b = v?.compared;
  return b ? `of ${plural(b.advisories ?? 0, "advisory", "advisories")}, ${loadedAge(b)}` : "comparison basis not reported";
}

/**
 * The Vulnerabilities cell of a device as one line of text (search and CSV); {@link VulnerabilityCell} draws the same
 * words. Only an evaluated device with nothing affected reads "None affected"; every other state says why not.
 */
export function vulnerabilitySummary(v: LifecycleVulnerabilities | null | undefined): string {
  if (!v) return "No vulnerability data";
  const notJudged = count(v.not_evaluable) ? ` · ${count(v.not_evaluable)} could not be judged` : "";
  const reason = reasonWord(v.reason);
  switch (v.state) {
    case "EVALUATED":
      return count(v.affected)
        ? `${count(v.affected)} affected${count(v.affected_exploited) ? ` · ${count(v.affected_exploited)} exploited` : ""}${notJudged}`
        : `None affected${notJudged}`;
    case "NOT_EVALUATED": return `Not evaluated${reason ? ` · ${reason}` : ""}`;
    case "NOT_COVERED": return "No advisory source for this product";
    case "VERSION_UNKNOWN": return `Version not known${reason ? ` · ${reason}` : ""}`;
    default: return `Not evaluated · ${String(v.state ?? "UNKNOWN")}`;
  }
}

/** Sort rank of a device: exploited and affected first, then affected by a critical or high advisory, then affected. */
export function vulnerabilityRank(v: LifecycleVulnerabilities | null | undefined): number {
  if (v?.state !== "EVALUATED" || !count(v.affected)) return 3;
  return count(v.affected_exploited) ? 0 : count(v.affected_critical_high) ? 1 : 2;
}

const caption = (text: string, color: string = "text.secondary") => <Typography variant="caption" display="block" sx={{ color }}>{text}</Typography>;

/** The Vulnerabilities cell of the Fleet table. */
export function VulnerabilityCell({ v }: { v: LifecycleVulnerabilities | null | undefined }) {
  if (!v) return <Typography variant="body2" component="span" sx={muted}>No vulnerability data</Typography>;
  const reason = reasonWord(v.reason);
  const notJudged = count(v.not_evaluable) ? `${count(v.not_evaluable)} could not be judged` : null;
  if (v.state === "EVALUATED" && count(v.affected)) {
    const notes = [count(v.affected_critical_high) ? `${count(v.affected_critical_high)} critical or high` : null, notJudged].filter(Boolean).join(" · ");
    return <Box data-vulnerability="affected" sx={{ minWidth: 180 }}><PillRow>
      <StatusPill tone="warn" word={`${count(v.affected)} affected`} dense fact="vulnerability:affected" />
      {count(v.affected_exploited) > 0 && <StatusPill tone="bad" word={`${count(v.affected_exploited)} exploited`} dense fact="vulnerability:exploited" />}
    </PillRow>{notes && caption(notes)}</Box>;
  }
  if (v.state === "EVALUATED") return <Box data-vulnerability="none-affected">
    <Typography variant="body2" component="span">None affected</Typography>
    {notJudged && caption(notJudged, m3.warningInk)}{caption(vulnerabilityBasis(v))}
  </Box>;
  if (v.state === "NOT_COVERED") return <Typography variant="body2" component="span" sx={muted} data-vulnerability="not-covered">No advisory source for this product</Typography>;
  return <Box data-vulnerability="not-evaluated">
    <StatusPill tone="unknown" word={v.state === "VERSION_UNKNOWN" ? "Version not known" : "Not evaluated"} dense fact="vulnerability:not-evaluated" />
    {caption(reason ?? (v.state === "VERSION_UNKNOWN" || v.state === "NOT_EVALUATED" ? "reason not reported" : String(v.state)))}
  </Box>;
}

/** The Vulnerabilities block of an expanded Fleet row: the counts, what they were compared against, and what is not known. */
export function VulnerabilityDetail({ v }: { v: LifecycleVulnerabilities }) {
  const b = v.compared;
  const evaluated = v.state === "EVALUATED";
  const counts = [plural(count(v.affected), "advisory names", "advisories name") + " this version as affected",
    count(v.affected_exploited) ? `${count(v.affected_exploited)} exploited` : null,
    count(v.affected_critical_high) ? `${count(v.affected_critical_high)} critical or high` : null,
    count(v.affected) && v.worst_severity ? `worst severity ${severityWord(v.worst_severity)}` : null,
    count(v.fixed) ? `${count(v.fixed)} fixed in this version` : null,
    count(v.not_evaluable) ? `${count(v.not_evaluable)} could not be judged` : null].filter(Boolean).join(" · ");
  return <Box>
    <Typography variant="subtitle2">Vulnerabilities</Typography>
    <Typography variant="caption" display="block">{evaluated ? counts : `${vulnerabilitySummary(v)}. This device is not known to be unaffected.`}</Typography>
    {b && <Typography variant="caption" display="block">{`Compared against ${advisorySourceName(b.source)}: ${plural(b.advisories ?? 0, "advisory", "advisories")}`
      + `${b.oldest && b.newest ? ` from ${b.oldest} to ${b.newest}` : ""}, ${loadedAge(b)}${b.loaded_at ? ` (${formatStamp(b.loaded_at)})` : ""}`}</Typography>}
    {evaluated && !b && <Typography variant="caption" display="block">Comparison basis not reported.</Typography>}
    {v.product_key && <Typography variant="caption" display="block" sx={muted}>{`Product: ${v.product_key}`}</Typography>}
    {evaluated && count(v.affected) > 0 && <Typography variant="caption" display="block">{`${AFFECTED_BY_VERSION} The advisories are listed on the Vulnerabilities tab.`}</Typography>}
  </Box>;
}

/** The address of the vendor page, only when it is an https address; anything else is shown as text. */
export function vendorLink(url: string | null | undefined): URL | null {
  if (typeof url !== "string" || !url.startsWith("https://")) return null;
  try { const parsed = new URL(url); return parsed.protocol === "https:" && parsed.hostname ? parsed : null; }
  catch { return null; }
}

const VERDICTS = new Map([["AFFECTED", "Affected"], ["NOT_EVALUABLE", "Could not be judged"], ["FIXED", "Fixed"], ["NOT_AFFECTED", "Not affected"]]);
const VERDICT_ORDER = [...VERDICTS.keys()];
const verdictRank = (verdict: string) => { const i = VERDICT_ORDER.indexOf(verdict); return i < 0 ? VERDICT_ORDER.length : i; };

/** The devices of one advisory: verdict, the version compared, the rule that decided, and the vendor's fix statement. */
function AdvisoryDevices({ advisory }: { advisory: LifecycleAdvisory }) {
  const detail = useFetchOnMount(() => getLifecycleAdvisory(advisory.advisory_id), lifecycleError);
  const [all, setAll] = useState(false);
  if (detail.error) return <Alert severity="error">{detail.error}<M3Button emphasis="tonal" onClick={detail.refresh}>Retry</M3Button></Alert>;
  if (!detail.data) return <Typography variant="body2">Loading devices…</Typography>;
  const devices = [...(detail.data.devices ?? [])].sort((a, b) => verdictRank(a.verdict) - verdictRank(b.verdict));
  if (!devices.length) return <Typography variant="body2">No device has a verdict for this advisory.</Typography>;
  const visible = all ? devices : devices.slice(0, ADVISORY_PAGE);
  const verdict = (d: LifecycleAdvisoryDevice) => d.verdict === "AFFECTED" ? <StatusPill tone={advisory.exploited ? "bad" : "warn"} word="Affected" dense />
    : d.verdict === "NOT_EVALUABLE" ? <StatusPill tone="unknown" word="Could not be judged" dense />
    : <Typography variant="body2" component="span">{VERDICTS.get(d.verdict) ?? d.verdict}</Typography>;
  return <Stack spacing={1} role="group" aria-label={`Devices of ${advisory.advisory_id}`}>
    {devices.some(d => d.verdict === "AFFECTED") && <Typography variant="body2">{AFFECTED_BY_VERSION}</Typography>}
    <Table size="small" aria-label={`Devices judged against ${advisory.advisory_id}`}>
      <TableHead><TableRow>{["Device", "Vendor", "Version compared", "Verdict", "Basis", "Vendor fix statement"].map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
      <TableBody>{visible.map(d => <TableRow key={d.device_id}>
        <TableCell><a href={`?screen=inventory&device_id=${encodeURIComponent(d.device_id)}`}>{d.hostname || "UNKNOWN"}</a></TableCell>
        <TableCell>{d.vendor}</TableCell><TableCell>{d.version || "UNKNOWN"}</TableCell><TableCell>{verdict(d)}</TableCell>
        <TableCell>{basisSentence(d.basis)}</TableCell><TableCell>{d.fixed_text || "—"}</TableCell>
      </TableRow>)}</TableBody>
    </Table>
    {devices.length > visible.length && <Box><M3Button emphasis="text" onClick={() => setAll(true)}>Show all {devices.length} devices</M3Button></Box>}
  </Stack>;
}

const neverLoaded = (s: LifecycleAdvisorySource) => s.age_state === "NEVER" || (!s.age_state && !s.last_run_at);

function sourceAge(s: LifecycleAdvisorySource) {
  const days = typeof s.age_days === "number" ? `${s.age_days} d` : null;
  if (neverLoaded(s)) return <StatusPill tone="unknown" word="Never loaded" dense />;
  if (s.age_state === "RED") return <StatusPill tone="bad" word="Older than 30 days" suffix={days} dense />;
  if (s.age_state === "AMBER") return <StatusPill tone="warn" word="Older than 7 days" suffix={days} dense />;
  if (s.age_state === "OK") return <StatusPill tone="ok" word="Current" suffix={days} dense />;
  return <StatusPill tone="unknown" word={String(s.age_state)} suffix={days} dense />;
}

function sourceOutcome(s: LifecycleAdvisorySource) {
  return <>
    {s.outcome === "FAILED" ? <StatusPill tone="bad" word="Failed" dense /> : s.outcome === "PARTIAL" ? <StatusPill tone="warn" word="Partial" dense />
      : s.outcome === "OK" ? "Complete" : s.outcome ?? "—"}
    {s.reason && caption(s.reason)}
  </>;
}

/** Where the advisories come from and how old each load is: the basis for trusting a row that shows nothing affected. */
function SourcesTable({ sources }: { sources: LifecycleAdvisorySource[] }) {
  return <Box>
    <Typography variant="subtitle2">Advisory sources</Typography>
    <Typography variant="caption" display="block" sx={{ mb: 1 }}>A device is judged only against a source loaded in the last 30 days. Age: amber after 7 days, red after 30.</Typography>
    <TableContainer><Table size="small" aria-label="Advisory sources">
      <TableHead><TableRow>{["Source", "Last run", "Outcome", "Advisories", "Not read in full", "Coverage", "Age of last load"].map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
      <TableBody>{sources.map(s => <TableRow key={s.source}>
        <TableCell>{advisorySourceName(s.source)}</TableCell>
        <TableCell>{s.last_run_at ? <>{formatStamp(s.last_run_at)}{caption(relativeAge(s.last_run_at))}</> : "Never"}</TableCell>
        <TableCell>{sourceOutcome(s)}</TableCell>
        <TableCell>{s.advisories ?? 0}</TableCell>
        <TableCell>{count(s.needs_review) ? <Typography variant="body2" component="span" sx={{ color: m3.warningInk }}>{s.needs_review}</Typography> : 0}</TableCell>
        <TableCell>{s.oldest && s.newest ? `from ${s.oldest} to ${s.newest}` : "—"}</TableCell>
        <TableCell>{sourceAge(s)}</TableCell>
      </TableRow>)}</TableBody>
    </Table></TableContainer>
  </Box>;
}

const headings = ["Severity", "Advisory", "Exploited", "Devices", "Published", "Vendor page"];
const SEVERITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "NONE", "UNKNOWN"];
const judgedDevices = (a: LifecycleAdvisory) => count(a.devices?.affected) + count(a.devices?.fixed) + count(a.devices?.not_affected) + count(a.devices?.not_evaluable);

function AdvisoryRow({ a, open, onToggle, exploitedKnown }: { a: LifecycleAdvisory; open: boolean; onToggle: () => void; exploitedKnown: boolean }) {
  const affected = count(a.devices?.affected);
  const notJudged = count(a.devices?.not_evaluable);
  const link = vendorLink(a.source_url);
  const cves = (a.cve_ids ?? []).filter(cve => !a.advisory_id.includes(cve));
  const others = [count(a.devices?.fixed) ? `${count(a.devices?.fixed)} fixed` : null, count(a.devices?.not_affected) ? `${count(a.devices?.not_affected)} not affected` : null,
    affected && notJudged ? `${notJudged} could not be judged` : null].filter(Boolean).join(" · ");
  const strong = a.severity === "CRITICAL" || a.severity === "HIGH";
  return <Fragment>
    <TableRow data-advisory={a.advisory_id}>
      <TableCell><Typography variant="body2" component="span" sx={{ fontWeight: strong ? 600 : 400 }}>{severityWord(a.severity)}</Typography>
        {typeof a.cvss_score === "number" && caption(`CVSS ${a.cvss_score}`)}</TableCell>
      <TableCell sx={{ maxWidth: 520 }}>
        <Typography variant="body2">{a.title || a.advisory_id}</Typography>
        {caption(`${advisorySourceName(a.source)} · ${a.advisory_id}`)}
        {cves.length > 0 && caption(cves.join(", "))}
        {a.parse_state === "NEEDS_REVIEW" && <Box sx={{ mt: 0.5 }}>
          <StatusPill tone="warn" word="Not read in full" dense fact="advisory:needs-review" />
          <Typography variant="caption" display="block">Devices of this product cannot be judged against it.{a.parse_note && <Box component="span" sx={muted}>{` · ${a.parse_note}`}</Box>}</Typography>
        </Box>}
        {judgedDevices(a) > 0 && <M3Button emphasis="text" aria-expanded={open} onClick={onToggle}>{open ? "Hide devices" : `Show ${plural(judgedDevices(a), "device")}`}</M3Button>}
      </TableCell>
      <TableCell>{a.exploited ? <StatusPill tone={affected ? "bad" : "neutral"} word="Exploited" suffix={a.exploited_since ? `since ${a.exploited_since}` : null} dense fact="advisory:exploited" />
        : <Typography variant="body2" component="span" sx={muted}>{exploitedKnown ? "—" : "Not known"}</Typography>}</TableCell>
      <TableCell>
        {affected ? <StatusPill tone="warn" word={`${affected} affected`} dense fact="advisory:affected" />
          : notJudged ? <StatusPill tone="unknown" word={`${notJudged} could not be judged`} dense />
          : <Typography variant="body2" component="span" sx={muted}>None affected</Typography>}
        {others && caption(others)}
      </TableCell>
      <TableCell sx={{ whiteSpace: "nowrap" }}>{a.published_on ?? "UNKNOWN"}{a.updated_on && a.updated_on !== a.published_on && caption(`updated ${a.updated_on}`)}</TableCell>
      <TableCell>{link
        ? <Link href={link.href} target="_blank" rel="noopener noreferrer" aria-label={`Vendor page of ${a.advisory_id} on ${link.hostname} (opens in a new tab)`}>{link.hostname}</Link>
        : <Typography variant="caption" sx={{ ...muted, wordBreak: "break-all" }}>{a.source_url ? `Not linked: ${a.source_url}` : "—"}</Typography>}</TableCell>
    </TableRow>
    {open && <TableRow><TableCell colSpan={headings.length}><AdvisoryDevices advisory={a} /></TableCell></TableRow>}
  </Fragment>;
}

/** The Vulnerabilities tab: the sources (can a clean row be trusted), then the advisories (what needs action soonest). */
export function LifecycleVulnerabilitiesPanel() {
  const sources = useFetchOnMount(getLifecycleAdvisorySources, lifecycleError);
  const list = useFetchOnMount(getLifecycleAdvisories, lifecycleError);
  const [search, setSearch] = useState("");
  const [severity, setSeverity] = useState("all");
  const [exploitedOnly, setExploitedOnly] = useState(false);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const toggle = (id: string) => setOpen(prev => { const next = new Set(prev); if (!next.delete(id)) next.add(id); return next; });
  const [limit, setLimit] = useState(ADVISORY_PAGE);
  useEffect(() => { setLimit(ADVISORY_PAGE); }, [search, severity, exploitedOnly]);
  const refresh = () => { sources.refresh(); list.refresh(); };

  const sourceRows = sources.data?.sources ?? [];
  const nothingLoaded = sources.data !== null && sourceRows.every(neverLoaded);
  const exploitedCatalog = sourceRows.find(s => s.source === "cisa_kev");
  // Without the known-exploited catalog an advisory that is not flagged is "not known", not "not exploited".
  const exploitedKnown = sources.data === null || (exploitedCatalog !== undefined && !neverLoaded(exploitedCatalog));
  const advisories = list.data?.advisories ?? [];
  const needle = search.trim().toLowerCase();
  const filtered = advisories.filter(a => (severity === "all" || a.severity === severity) && (!exploitedOnly || a.exploited)
    && (!needle || [a.title, a.advisory_id, advisorySourceName(a.source), severityWord(a.severity), ...(a.cve_ids ?? [])].join(" ").toLowerCase().includes(needle)));
  const visible = filtered.slice(0, limit);
  const remaining = filtered.length - visible.length;
  const affecting = advisories.filter(a => count(a.devices?.affected) > 0);
  const unread = advisories.filter(a => a.parse_state === "NEEDS_REVIEW").length;

  return <Stack spacing={2}>
    <Typography variant="caption">A vendor security advisory names the software versions it affects; neXus compares each device's stored version with it. Affected means affected by version: whether the device is exposed depends on its configuration. Exploited: listed in the CISA Known Exploited Vulnerabilities catalog (attacks seen in the wild). Nothing here contacts a device.</Typography>
    {sources.error && <Alert severity="error">{sources.error}<M3Button emphasis="tonal" onClick={sources.refresh}>Retry</M3Button></Alert>}
    {!sources.data && !sources.error && <Typography>Loading advisory sources…</Typography>}
    {nothingLoaded && <Alert severity="info" action={<M3Button emphasis="text" onClick={refresh}>Refresh</M3Button>}>No advisory source has been loaded yet. Devices are not evaluated.</Alert>}
    {sources.data && sourceRows.length > 0 && (!nothingLoaded || sourceRows.some(s => s.last_run_at)) && <SourcesTable sources={sourceRows} />}

    {list.error && <Alert severity="error">{list.error}<M3Button emphasis="tonal" onClick={list.refresh}>Retry</M3Button></Alert>}
    {!list.data && !list.error && <Typography>Loading advisories…</Typography>}
    {list.data && !(nothingLoaded && advisories.length === 0) && <>
      <Box>
        <Typography variant="subtitle2">Security advisories</Typography>
        <Typography variant="caption" display="block" data-advisory-counts>{`${plural(advisories.length, "advisory concerns", "advisories concern")} this fleet`
          + ` · ${affecting.length} affect at least one device · ${affecting.filter(a => a.exploited).length} of those exploited · ${unread} not read in full`
          + ` · as of ${formatStamp(list.data.as_of)}. Exploited and affecting advisories come first.`}</Typography>
        {!exploitedKnown && <Typography variant="caption" display="block" sx={{ color: m3.warningInk }}>The known-exploited catalog has not been loaded: no advisory can be marked exploited.</Typography>}
      </Box>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: "wrap", gap: 1 }}>
        <TextField label="Search advisories" size="small" value={search} onChange={e => setSearch(e.target.value)} />
        <TextField label="Severity" select size="small" value={severity} sx={{ minWidth: 150 }} onChange={e => setSeverity(e.target.value)}>
          <MenuItem value="all">All severities</MenuItem>{SEVERITIES.map(s => <MenuItem key={s} value={s}>{severityWord(s)}</MenuItem>)}
        </TextField>
        <FormControlLabel control={<Switch size="small" checked={exploitedOnly} onChange={e => setExploitedOnly(e.target.checked)} />} label="Exploited only" />
        <M3Button emphasis="tonal" onClick={refresh}>Refresh</M3Button>
      </Stack>
      <TableContainer><Table size="small" aria-label="Security advisories">
        <TableHead><TableRow>{headings.map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
        <TableBody>
          {visible.map(a => <AdvisoryRow key={a.advisory_id} a={a} open={open.has(a.advisory_id)} onToggle={() => toggle(a.advisory_id)} exploitedKnown={exploitedKnown} />)}
          {!filtered.length && <TableRow><TableCell colSpan={headings.length}>{advisories.length ? "No advisories match these filters"
            : "No advisory in the loaded sources names a product and version of this fleet."}</TableCell></TableRow>}
        </TableBody>
      </Table></TableContainer>
      {filtered.length > 0 && <Stack direction="row" spacing={2} alignItems="center">
        <Typography variant="caption" data-advisory-page>{`1–${visible.length} of ${filtered.length}${remaining > 0 ? ` · +${remaining} more` : ""}`}</Typography>
        {remaining > 0 && <M3Button emphasis="tonal" onClick={() => setLimit(n => n + ADVISORY_PAGE)}>Show next {Math.min(ADVISORY_PAGE, remaining)}</M3Button>}
        {remaining > 0 && <M3Button emphasis="text" onClick={() => setLimit(filtered.length)}>Show all {filtered.length}</M3Button>}
      </Stack>}
    </>}
  </Stack>;
}
