import { useMemo, useState } from "react";
import Box from "@mui/material/Box";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { DataTable, type DataColumn } from "../../shell/DataTable";
import { StatusChip } from "../../shell/M3Widgets";
import { StatePanel, Ts, Unknown } from "../../shell/States";
import type { Tone } from "../../shell/tone";
import { m3 } from "../../theme/m3Theme";
import { Hint, SectionTitle, mono, panelSx } from "./zoneMatrixUi";
import {
  BECAME_UNDECIDED, SummaryReadFailure, ThresholdNote, ageText, flagWords, summaryGate, useEnsureSummary,
  type ZoneSummaryHandle,
} from "./zoneMatrixSummaryUi";
import {
  severityWord, statementWord,
  type SummaryFinding, type SummaryHeadline, type SummaryOutcome, type SummaryResolvedFinding, type ZoneMatrixSummaryResponse,
} from "./zoneMatrixApi";

const PAGE_SIZE = 50;
const LIMITS: readonly number[] = [200, 500, 1000];

const OUTCOMES: readonly { readonly value: SummaryOutcome; readonly label: string; readonly tone: Tone }[] = [
  { value: "VIOLATION", label: "Violation", tone: "bad" },
  { value: "UNKNOWN", label: "Outcome unknown", tone: "unknown" },
  { value: "NOT_EVALUABLE", label: "Not evaluable", tone: "warn" },
];

const SEVERITY_TONE: Readonly<Record<string, Tone>> = { CRITICAL: "bad", HIGH: "warn", MEDIUM: "attn", LOW: "neutral" };
const SEVERITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW"] as const;

/** The policy viewer's own deep link (`?screen=policy&policy_id=..&rule=..`, also used by Trace and Changes). */
function ruleHref(policyId: string, ruleId: string | null): string | null {
  return policyId && ruleId
    ? `?screen=policy&policy_id=${encodeURIComponent(policyId)}&rule=${encodeURIComponent(ruleId)}&standard=1`
    : null;
}

function ruleLabel(f: { readonly rule_number: number | null; readonly rule_name: string | null; readonly rule_uid: string }): string {
  const number = f.rule_number === null ? "#?" : `#${f.rule_number}`;
  return `${number} ${f.rule_name || f.rule_uid}`;
}

/**
 * The headline as the response proves it. Green only when the server says it is exact, nothing is stale, incomplete,
 * older-version or not evaluated, no finding is undecided, and every listed policy is CURRENT and COMPLETE; a count of
 * zero without that proof reads "not proven" (contract 7.2, ZM-34, ZM-35).
 */
export function headlineProven(data: ZoneMatrixSummaryResponse, h: SummaryHeadline): boolean {
  return data.state === "READY" && h.exact && h.staleness_headline.exact
    && h.stale_policies === 0 && h.incomplete_policies === 0 && h.older_version_policies === 0 && h.not_evaluated_policies === 0
    && h.not_evaluable_findings === 0 && h.unknown_findings === 0 && h.became_undecided_findings === 0
    && data.not_evaluated.length === 0
    && data.policies.every((p) => p.staleness.verdict === "CURRENT" && p.coverage === "COMPLETE" && p.has_complete_run);
}

function HeadlinePanel({ data, h }: { readonly data: ZoneMatrixSummaryResponse; readonly h: SummaryHeadline }) {
  const proven = headlineProven(data, h);
  const n = h.distinct_violating_rules;
  const chip: { tone: Tone; label: string } = n > 0
    ? { tone: "bad", label: h.exact ? `${n} violating rule${n === 1 ? "" : "s"}` : `At least ${n} violating rule${n === 1 ? "" : "s"}` }
    : proven ? { tone: "ok", label: "No violating rule found, exact" }
    : { tone: "unknown", label: "No violation found, not proven" };
  return (
    <Box sx={panelSx} data-testid="summary-headline" data-proven={proven ? "yes" : "no"}>
      <Stack direction="row" gap={1.5} alignItems="center" flexWrap="wrap">
        <StatusChip tone={chip.tone} label={chip.label} />
        <Typography sx={{ fontWeight: 600 }}>{h.text}</Typography>
      </Stack>
      <Typography variant="body2" data-testid="headline-count-text">{h.count_text}</Typography>
      {h.outside_headline_text ? <Typography variant="body2" data-testid="outside-headline-text">{h.outside_headline_text}</Typography> : null}
      <Typography variant="body2" data-testid="staleness-headline" sx={{ color: m3.onSurfaceVar }}>{h.staleness_headline.text}</Typography>
      <Hint>Zone matrix version {h.version_number}. Counts are rules of the latest complete run of each policy, not observed traffic.</Hint>
    </Box>
  );
}

function ResolvedPanel({ data }: { readonly data: ZoneMatrixSummaryResponse }) {
  const r = data.resolved_by_version_change;
  return (
    <Box sx={panelSx} data-testid="resolved-by-version">
      <SectionTitle note="Findings that stopped being a finding because the zone matrix changed, not because the policy did.">
        Resolved by a version change in the last {r.window_days} days
      </SectionTitle>
      <Typography variant="body2" data-testid="resolved-count">
        {r.total_last_7_days === 0
          ? `No finding was resolved by a version change in the last ${r.window_days} days.`
          : `${r.total_last_7_days} finding${r.total_last_7_days === 1 ? "" : "s"} resolved by a version change; ${r.returned} shown${r.capped ? `, +${r.more} more not shown` : ""}.`}
      </Typography>
      {r.last_7_days.length > 0 ? (
        <Box component="ul" sx={{ m: 0, pl: 3 }}>
          {r.last_7_days.map((f: SummaryResolvedFinding, i) => (
            <li key={`${f.policy_source_id}/${f.rule_uid}/${i}`}>
              <Typography variant="body2">
                {ruleLabel(f)} · {f.policy_name} · {f.violated_pairs.map((p) => `${p.source_name} -> ${p.destination_name}`).join("; ") || "UNKNOWN pairs"}
                {" · "}version {f.resolved_from_version_number ?? "UNKNOWN"} to version {f.resolved_in_version_number ?? "UNKNOWN"} · <Ts at={f.resolved_at} />
              </Typography>
            </li>
          ))}
        </Box>
      ) : null}
    </Box>
  );
}

/** What is counted, with the part left out said out loud. */
export function countsText(data: ZoneMatrixSummaryResponse, shown: number): string {
  const base = `${data.returned} of ${data.total_findings} finding${data.total_findings === 1 ? "" : "s"} returned by the server`;
  const more = data.capped ? `; +${data.more} more not shown (the list is capped at ${data.filters.limit})` : "";
  const filtered = data.filters.policy_id || data.filters.outcome ? `; ${data.unfiltered_findings} before the server filters` : "";
  const here = shown !== data.returned ? `; ${shown} match the filters on this page` : "";
  return `${base}${more}${filtered}${here}.`;
}

/**
 * Zone matrix delivery step 4, Violations tab (contract 7.1, 7.2, 5.9; ZM-26, ZM-34, ZM-35): the findings of the latest
 * COMPLETE run of each policy, from the stored summary. Names are shown exactly as the server sent them; no witness, block
 * or address is read here.
 */
export function ViolationsTab({ summary }: { readonly summary: ZoneSummaryHandle }) {
  useEnsureSummary(summary);
  const [severity, setSeverity] = useState("");
  const [flag, setFlag] = useState("");
  const [lifecycle, setLifecycle] = useState("");
  const [offset, setOffset] = useState(0);
  const { data, params, setParams } = summary;

  const flagOptions = useMemo(
    () => [...new Set((data?.findings ?? []).flatMap((f) => f.flags))].sort(),
    [data],
  );
  const rows = useMemo(() => (data?.findings ?? []).filter((f) =>
    (severity === "" || f.highest_severity === severity || f.violated_pairs.some((p) => p.severity === severity))
    && (flag === "" || f.flags.includes(flag))
    && (lifecycle === "" || (lifecycle === "undecided") === f.flags.includes(BECAME_UNDECIDED))), [data, severity, flag, lifecycle]);

  const columns: readonly DataColumn<SummaryFinding>[] = useMemo(() => [
    {
      key: "rule", header: "Rule", width: 210, wrap: true,
      render: (f) => {
        const href = ruleHref(f.policy_source_id, f.rule_id);
        return href
          ? <a href={href} aria-label={`Open ${ruleLabel(f)} in the policy viewer`}>{ruleLabel(f)}</a>
          : <span>{ruleLabel(f)}</span>;
      },
    },
    { key: "policy", header: "Policy", width: 150, wrap: true, render: (f) => f.policy_name },
    {
      key: "pairs", header: "Violated zone pairs", width: 320, wrap: true,
      render: (f) => f.violated_pairs.length === 0 ? <Unknown reason="The finding names no zone pair" /> : (
        <Box>
          {f.violated_pairs.map((p, i) => (
            <Box key={i} data-testid="violated-pair">
              {p.source_name} -&gt; {p.destination_name}
              {" · "}{p.statement ? statementWord(p.statement) : "statement UNKNOWN"}
              {" · "}{p.severity ? severityWord(p.severity) : "severity UNKNOWN"}
            </Box>
          ))}
        </Box>
      ),
    },
    {
      key: "outcome", header: "Outcome", width: 130,
      render: (f) => {
        const spec = OUTCOMES.find((o) => o.value === f.outcome);
        return <StatusChip tone={spec?.tone ?? "unknown"} label={spec?.label ?? flagWords(f.outcome)} />;
      },
    },
    {
      key: "severity", header: "Highest severity", width: 120,
      render: (f) => f.highest_severity
        ? <StatusChip tone={SEVERITY_TONE[f.highest_severity] ?? "neutral"} label={severityWord(f.highest_severity)} />
        : <Unknown reason="No severity is stored for this finding" />,
    },
    { key: "reason", header: "Reason", width: 220, wrap: true, render: (f) => f.reason ?? <Unknown reason="The finding states no reason" /> },
    {
      key: "flags", header: "Flags", width: 200, wrap: true,
      render: (f) => f.flags.length === 0 ? "None" : (
        <Stack direction="row" gap={0.5} flexWrap="wrap">
          {f.flags.map((x) => x === BECAME_UNDECIDED
            ? <span key={x} data-testid="flag-became-undecided"><StatusChip tone="warn" label={flagWords(x)} title="The finding stays open: the matrix or the policy changed so that it can no longer be decided." /></span>
            : <StatusChip key={x} tone="neutral" label={flagWords(x)} />)}
        </Stack>
      ),
    },
    {
      key: "covering", header: "Covering rule", width: 150, mono: true,
      render: (f) => f.flags.length > 0 && f.covering_rule_id ? f.covering_rule_id : "—",
    },
    {
      key: "observed", header: "First / first (any version) / last observed", width: 260, wrap: true,
      render: (f) => (
        <Box sx={{ ...mono }}>
          <div>first <Ts at={f.first_observed_at} /></div>
          <div>any version <Ts at={f.first_observed_any_version} /></div>
          <div>last <Ts at={f.last_observed_at} /></div>
        </Box>
      ),
    },
    { key: "version", header: "Matrix version", width: 100, render: (f) => `Version ${f.version_number}` },
    {
      key: "active", header: "On active version", width: 110,
      render: (f) => f.on_active_version ? "Yes" : <StatusChip tone="attn" label="No" title="The finding is from a run against a version that is not the active one." />,
    },
    {
      key: "staleness", header: "Policy staleness", width: 150, wrap: true,
      render: (f) => (
        <Box>
          <StatusChip tone={f.policy_staleness === "CURRENT" ? "ok" : f.policy_staleness === "STALE" ? "warn" : "unknown"}
            label={f.policy_staleness === "CURRENT" || f.policy_staleness === "STALE" ? flagWords(f.policy_staleness) : "UNKNOWN"} />
          <Box sx={{ color: m3.onSurfaceVar }}>snapshot age {ageText(f.snapshot_age_seconds)}</Box>
        </Box>
      ),
    },
  ], []);

  const gate = summaryGate(summary, "Zone matrix violations");
  if (gate !== null || data === null) return <Box sx={{ pt: 1.5 }}>{gate}</Box>;

  const ready = data.state === "READY";
  const pageRows = rows.slice(offset, offset + PAGE_SIZE);
  const policyOptions = data.policies;
  return (
    <Stack gap={2} sx={{ pt: 1.5 }} aria-busy={summary.loading}>
      <SummaryReadFailure summary={summary} />
      <Typography variant="body2" data-testid="summary-state-text" sx={{ color: m3.onSurfaceVar }}>{data.state_text}</Typography>
      {data.headline ? <HeadlinePanel data={data} h={data.headline} /> : (
        <StatePanel variant="unknown" title="No headline" body={data.headline_absent_reason ?? data.state_text} />
      )}
      <ThresholdNote threshold={data.staleness_threshold} />
      {data.masked ? <Hint>Names are shown as the server masked them.</Hint> : null}

      {!ready ? (
        <StatePanel variant="empty" title={data.state_text}
          body="No finding list is shown: an empty list here would not mean there is nothing wrong." />
      ) : (
        <Box sx={panelSx}>
          <SectionTitle note="Findings of the latest complete run of each policy; they show policy intent as defined in management, not observed traffic.">
            Findings
          </SectionTitle>
          <Stack direction="row" flexWrap="wrap" gap={1.5} alignItems="center">
            <TextField select size="small" label="Outcome" value={params.outcome ?? ""} sx={{ minWidth: 170 }}
              onChange={(e) => { setOffset(0); setParams({ ...params, outcome: (e.target.value || null) as SummaryOutcome | null }); }}>
              <MenuItem value="">All outcomes</MenuItem>
              {OUTCOMES.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Policy" value={params.policy_id ?? ""} sx={{ minWidth: 240 }}
              onChange={(e) => { setOffset(0); setParams({ ...params, policy_id: e.target.value || null }); }}>
              <MenuItem value="">All policies</MenuItem>
              {policyOptions.map((p) => <MenuItem key={p.policy_id} value={p.policy_id}>{p.policy_name}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Findings requested" value={params.limit ?? data.filters.limit} sx={{ minWidth: 150 }}
              onChange={(e) => { setOffset(0); setParams({ ...params, limit: Number(e.target.value) }); }}>
              {LIMITS.map((n) => <MenuItem key={n} value={n}>Up to {n}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Severity" value={severity} sx={{ minWidth: 130 }}
              onChange={(e) => { setOffset(0); setSeverity(e.target.value); }}>
              <MenuItem value="">Any severity</MenuItem>
              {SEVERITIES.map((s) => <MenuItem key={s} value={s}>{severityWord(s)}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Flag" value={flag} sx={{ minWidth: 190 }}
              onChange={(e) => { setOffset(0); setFlag(e.target.value); }}>
              <MenuItem value="">Any flag</MenuItem>
              {flagOptions.map((x) => <MenuItem key={x} value={x}>{flagWords(x)}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="State" value={lifecycle} sx={{ minWidth: 190 }}
              onChange={(e) => { setOffset(0); setLifecycle(e.target.value); }}>
              <MenuItem value="">Open and became undecided</MenuItem>
              <MenuItem value="open">Open, still decided</MenuItem>
              <MenuItem value="undecided">Became undecided</MenuItem>
            </TextField>
          </Stack>
          <Typography variant="body2" data-testid="findings-count">{countsText(data, rows.length)}</Typography>
          <Hint>The outcome, policy and request size are applied by the server; severity, flag and state only narrow the rows returned. Not evaluable and unknown rows stay in the list unless you filter them out.</Hint>
          <DataTable
            ariaLabel="Violation findings"
            columns={columns}
            rows={pageRows}
            rowKey={(f) => `${f.run_id}/${f.policy_source_id}/${f.rule_uid}/${f.inline_parent_path.join(">")}/${f.violated_pairs.map((p) => `${p.source_zone_id}>${p.destination_zone_id}`).join(",")}`}
            page={{ offset, size: PAGE_SIZE, total: rows.length, onPage: setOffset, noun: "findings" }}
            emptyText={data.total_findings === 0
              ? "The server returned no finding for these filters. This is not a statement that the policies comply; see the headline."
              : "No returned finding matches the filters on this page."}
          />
        </Box>
      )}

      <ResolvedPanel data={data} />
    </Stack>
  );
}
