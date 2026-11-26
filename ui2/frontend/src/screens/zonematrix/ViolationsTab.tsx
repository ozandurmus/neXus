import { useMemo, useState, type ReactNode } from "react";
import Box from "@mui/material/Box";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { DataTable, type DataColumn } from "../../shell/DataTable";
import { M3Button, StatusChip } from "../../shell/M3Widgets";
import { RestrictedPanel, StatePanel, Ts, Unknown, isRestricted } from "../../shell/States";
import type { Tone } from "../../shell/tone";
import { parseTraceTuple, traceTupleHref } from "../../shell/traceTupleLink";
import { m3 } from "../../theme/m3Theme";
import { Hint, SectionTitle, mono, panelSx } from "./zoneMatrixUi";
import {
  BECAME_UNDECIDED, SummaryReadFailure, ThresholdNote, ageText, flagWords, summaryGate, useEnsureSummary,
  type ZoneSummaryHandle,
} from "./zoneMatrixSummaryUi";
import {
  AUDIT_UNAVAILABLE_TEXT, getFindingWitness, severityWord, statementWord, zoneErrorCode,
  type FindingWitnessResponse, type StoredWitnessTuple,
  type SummaryCellCount, type SummaryFinding, type SummaryHeadline, type SummaryOutcome, type SummaryResolvedFinding, type ZoneMatrixSummaryResponse,
} from "./zoneMatrixApi";
import {
  FIRST_OBSERVED_OPTIONS, NO_REASON_VALUE, STALENESS_OPTIONS, UNKNOWN_VENDOR_VALUE,
  buildDeviceOptions, buildPolicySourceOptions, buildPolicyVendorByRunId, buildReasonOptions, buildVendorOptions,
  matchesViolationFilters, type FirstObservedRange, type PolicyStalenessChoice, type ViolationFilterOption,
} from "./violationFilters";

const PAGE_SIZE = 50;
const LIMITS: readonly number[] = [200, 500, 1000];

const OUTCOMES: readonly { readonly value: SummaryOutcome; readonly label: string; readonly tone: Tone }[] = [
  { value: "VIOLATION", label: "Violation", tone: "bad" },
  { value: "UNKNOWN", label: "Outcome unknown", tone: "unknown" },
  { value: "NOT_EVALUABLE", label: "Not evaluable", tone: "warn" },
];

/** The zone pair the findings are narrowed to; either side may be `ALL_ZONES`. */
export interface ZonePairFilter {
  readonly sourceZoneId: string;
  readonly destinationZoneId: string;
}

const ALL_ZONES = "ALL";

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

function renderPairNotAllowed(
  pair: SummaryFinding["violated_pairs"][number],
  outcome: SummaryFinding["outcome"],
  multiPair: boolean,
): { readonly node: ReactNode; readonly isMono: boolean } {
  const services = pair.not_allowed_services ?? [];
  const prefix = multiPair ? `${pair.source_name} -> ${pair.destination_name}: ` : "";
  if (services.length > 0) {
    return { node: `${prefix}${services.join(", ")}`, isMono: true };
  }
  if (outcome === "VIOLATION" && pair.statement === "DENY_ALL") {
    return { node: `${prefix}All traffic in pair (deny all)`, isMono: false };
  }
  const reason = multiPair
    ? "No not-allowed service part is recorded for this pair"
    : "No not-allowed service part is recorded for this finding";
  return {
    node: prefix ? <>{prefix}<Unknown reason={reason} /></> : <Unknown reason={reason} />,
    isMono: false,
  };
}

function renderNotAllowedPart(f: SummaryFinding) {
  const violatedPairs = f.violated_pairs ?? [];
  const undecidedParts = f.undecided_parts ?? [];
  const multiPair = violatedPairs.length > 1;
  const items: { readonly node: ReactNode; readonly isMono: boolean }[] = violatedPairs.map((p) =>
    renderPairNotAllowed(p, f.outcome, multiPair),
  );
  if (violatedPairs.length === 0 && f.outcome === "VIOLATION") {
    items.push({
      node: <Unknown reason="No violated zone pair is recorded for this violation finding" />,
      isMono: false,
    });
  }
  if (undecidedParts.length > 0) {
    items.push({ node: `undecided: ${undecidedParts.join(", ")}`, isMono: true });
  }
  if (items.length === 0) {
    items.push({
      node: <Unknown reason="No not-allowed service part is recorded for this finding" />,
      isMono: false,
    });
  }
  if (items.length === 1) {
    return (
      <Box component="span" data-testid="finding-not-allowed" sx={items[0].isMono ? mono : undefined}>
        {items[0].node}
      </Box>
    );
  }
  return (
    <Box data-testid="finding-not-allowed">
      {items.map((item, i) => (
        <Box key={i} sx={item.isMono ? mono : undefined}>{i > 0 ? "\n" : ""}{item.node}</Box>
      ))}
    </Box>
  );
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

/**
 * The staleness line follows the same proof as the headline: when it is not proven, a bare number never prints; the
 * line reads "at least N" even if the response says exact (ZM-26, contract 7.2).
 */
export function stalenessHeadlineText(data: ZoneMatrixSummaryResponse, h: SummaryHeadline): string {
  const raw = h.staleness_headline.text;
  if (headlineProven(data, h) || /^\s*at least\b/i.test(raw)) return raw;
  return `at least ${h.distinct_violating_rules}`;
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
      <Typography variant="body2" data-testid="staleness-headline" sx={{ color: m3.onSurfaceVar }}>{stalenessHeadlineText(data, h)}</Typography>
      <Hint>Zone matrix version {h.version_number}. Counts are rules of the latest complete run of each policy, not observed traffic.</Hint>
    </Box>
  );
}

/**
 * Per-cell open violations (contract 7.1, 7.2): the rules the headline counts, per zone pair of the active version. A count
 * is "at least" unless the server says the headline is exact; a capped list says how many cells it leaves out. Selecting a
 * row narrows the findings below to that pair.
 */
function CellCountsPanel({ data, selected, onSelect }: {
  readonly data: ZoneMatrixSummaryResponse;
  readonly selected: ZonePairFilter | null;
  readonly onSelect: (cell: SummaryCellCount) => void;
}) {
  const cells = data.cell_counts ?? [];
  if (cells.length === 0) return null;
  const exact = data.cell_counts_exact === true;
  return (
    <Box sx={panelSx} data-testid="violations-cell-counts">
      <SectionTitle note="Rules of the headline per zone pair of the active matrix version; select a pair to narrow the findings.">
        Per-cell open violation counts
      </SectionTitle>
      <Box component="ul" aria-label="Per-cell open violation counts" sx={{ m: 0, pl: 0, listStyle: "none" }}>
        {cells.map((c) => {
          const isSelected = selected?.sourceZoneId === c.source_zone_id && selected?.destinationZoneId === c.destination_zone_id;
          return (
            <li key={`${c.source_zone_id}>${c.destination_zone_id}`}>
              <M3Button emphasis={isSelected ? "tonal" : "text"} onClick={() => onSelect(c)}>
                {c.source_name} -&gt; {c.destination_name}
                {" · "}{exact ? `${c.headline_rules}` : `at least ${c.headline_rules}`} rule{c.headline_rules === 1 ? "" : "s"}
                {c.outside_headline_rules > 0 ? ` · +${c.outside_headline_rules} outside headline` : ""}
                {" · "}{c.highest_severity ? severityWord(c.highest_severity) : "severity UNKNOWN"}
              </M3Button>
            </li>
          );
        })}
      </Box>
      {data.cell_counts_capped ? (
        <Typography variant="body2" data-testid="cell-counts-more" sx={{ color: m3.onSurfaceVar }}>
          +{data.cell_counts_more ?? 0} more cells not shown ({data.cell_counts_returned ?? cells.length} of {data.cell_counts_total ?? cells.length} listed).
        </Typography>
      ) : null}
      {!exact ? <Hint>The headline is not exact, so every count is a lower bound.</Hint> : null}
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

/** Said with every witness shown: the server wrote a WITNESS_VIEW audit row before it answered. */
export const WITNESS_AUDIT_NOTICE = "This view is recorded in the audit log";

/** One tuple as copyable text, ranges exactly as stored; an unreadable part stays "UNKNOWN". */
export function witnessText(w: StoredWitnessTuple): string {
  const service = w.protocol === "UNKNOWN" || w.port === "UNKNOWN" ? `service ${w.service || "UNKNOWN"}` : `${w.protocol}/${w.port}`;
  return `source ${w.source_range || "UNKNOWN"} -> destination ${w.destination_range || "UNKNOWN"} ${service}`;
}

/**
 * "Open in Trace" href for one stored witness, or null. Only a tuple whose source and destination are each one concrete IPv4
 * host address (no range, no prefix) with protocol tcp or udp and a port 1..65535 resolves; anything else (a range, `any`,
 * `UNKNOWN`) gets no link. The candidate is validated by the same strict parser Trace uses, so a link never opens a tuple
 * Trace would refuse. Never called for a masked session.
 */
export function witnessTraceHref(w: StoredWitnessTuple): string | null {
  const href = traceTupleHref({ src: w.source_range, dst: w.destination_range, protocol: w.protocol, port: w.port });
  return parseTraceTuple(href.slice(href.indexOf("#"))) ? href : null;
}

type WitnessState =
  | { readonly kind: "idle" }
  | { readonly kind: "loading" }
  | { readonly kind: "shown"; readonly data: FindingWitnessResponse }
  | { readonly kind: "not_stored" }
  | { readonly kind: "restricted" }
  | { readonly kind: "failed"; readonly text: string };

/**
 * Contract 7.1 Violations tab, "open Trace with the witness tuple (only with zone_matrix_zones_read)": the stored witness of
 * one finding, read only on a click (an audited `WITNESS_VIEW` read, never prefetched), held by this row alone and dropped
 * when hidden. A tuple of single IPv4 hosts over tcp/udp also offers "Open in Trace" (fragment prefill, no auto-run); a range
 * or other protocol stays copyable text only. This component is rendered only for unmasked sessions with `view_blocks`.
 */
function FindingWitness({ finding }: { readonly finding: SummaryFinding }) {
  const [state, setState] = useState<WitnessState>({ kind: "idle" });
  const findingId = finding.finding_id;
  if (!findingId) return <span data-testid="witness-no-id"><Unknown reason="This finding carries no id for a witness read" /></span>;
  const read = () => {
    setState({ kind: "loading" });
    getFindingWitness(findingId)
      .then((data) => setState({ kind: "shown", data }))
      .catch((err: unknown) => {
        const code = zoneErrorCode(err);
        if (code === "WITNESS_NOT_STORED") setState({ kind: "not_stored" });
        else if (isRestricted(err)) setState({ kind: "restricted" });
        else setState({ kind: "failed", text: code === "ZONE_MATRIX_AUDIT_UNAVAILABLE" ? AUDIT_UNAVAILABLE_TEXT
          : "The stored witness could not be read." });
      });
  };
  const hide = <M3Button emphasis="text" onClick={() => setState({ kind: "idle" })}>Hide witness</M3Button>;
  switch (state.kind) {
    case "idle":
      return <M3Button emphasis="text" onClick={read}>Show witness</M3Button>;
    case "loading":
      return <Typography variant="body2" role="status">Reading the stored witness…</Typography>;
    case "restricted":
      return <Box data-testid="witness-restricted"><RestrictedPanel area="Witness addresses" /></Box>;
    case "not_stored":
      return (
        <Box data-testid="witness-unknown">
          <StatePanel variant="unknown" title="Witness UNKNOWN" announce="status"
            body="This finding stored no witness tuple, so its witness is unknown; it is not an empty witness." />
          {hide}
        </Box>
      );
    case "failed":
      return (
        <Box data-testid="witness-error">
          <StatePanel variant="error" title="Witness not shown" body={state.text}
            action={<M3Button emphasis="tonal" onClick={read}>Retry</M3Button>} />
        </Box>
      );
    case "shown": {
      const names = new Map<string, string>();
      for (const p of finding.violated_pairs) {
        if (p.source_zone_id) names.set(p.source_zone_id, p.source_name);
        if (p.destination_zone_id) names.set(p.destination_zone_id, p.destination_name);
      }
      const zone = (id: string | null) => (id ? names.get(id) ?? id : "UNKNOWN");
      return (
        <Box data-testid="witness-shown">
          <Typography variant="body2" data-testid="witness-audit-notice" sx={{ fontWeight: 600 }}>{WITNESS_AUDIT_NOTICE}</Typography>
          {state.data.cells.map((c, i) => (
            <Box key={i} data-testid="witness-cell" sx={{ mt: 0.5 }}>
              <Typography variant="body2">{zone(c.source_zone_id)} -&gt; {zone(c.destination_zone_id)} · NAT view {c.nat_view || "UNKNOWN"}</Typography>
              {c.witness && witnessTraceHref(c.witness)
                ? <Box component="a" href={witnessTraceHref(c.witness)!} data-testid="witness-trace-link" sx={{ display: "block" }}>Open in Trace</Box>
                : null}
              {c.witness
                ? <Box component="code" data-testid="witness-tuple" sx={{ ...mono, display: "block", userSelect: "all" }}>{witnessText(c.witness)}</Box>
                : <Unknown reason="No witness tuple was stored for this cell" />}
            </Box>
          ))}
          <Hint>Witness {state.data.witnesses_stored} of {state.data.cells_total} violated cells; matrix version {state.data.version_number}.
            Ranges and non-TCP/UDP tuples are not opened in Trace: copy them instead.</Hint>
          {hide}
        </Box>
      );
    }
  }
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
 * COMPLETE run of each policy, from the stored summary. Names are shown exactly as the server sent them; the summary carries
 * no witness, block or address. A session the server lets view blocks gets a per-row "Show witness" (an audited read of that
 * one finding's stored tuple, only on a click).
 */
export function ViolationsTab({ summary, pair = null, onPairChange }: {
  readonly summary: ZoneSummaryHandle;
  /** The pair the screen narrows the findings to (a drilldown from a Matrix cell); `null` is all pairs. */
  readonly pair?: ZonePairFilter | null;
  readonly onPairChange?: (pair: ZonePairFilter | null) => void;
}) {
  useEnsureSummary(summary);
  const [localPair, setLocalPair] = useState<ZonePairFilter | null>(null);
  const shownPair = onPairChange ? pair : localPair;
  const setPair = onPairChange ?? setLocalPair;
  const sourceZone = shownPair?.sourceZoneId ?? ALL_ZONES;
  const destinationZone = shownPair?.destinationZoneId ?? ALL_ZONES;
  const changePair = (next: ZonePairFilter) =>
    setPair(next.sourceZoneId === ALL_ZONES && next.destinationZoneId === ALL_ZONES ? null : next);
  const [severity, setSeverity] = useState("");
  const [vendor, setVendor] = useState("");
  const [policySourceId, setPolicySourceId] = useState("");
  const [deviceId, setDeviceId] = useState("");
  const [flag, setFlag] = useState("");
  const [reason, setReason] = useState("");
  const [firstObserved, setFirstObserved] = useState<FirstObservedRange | "">("");
  const [lifecycle, setLifecycle] = useState("");
  const [staleness, setStaleness] = useState<PolicyStalenessChoice | "">("");
  const [offset, setOffset] = useState(0);
  const { data, params, setParams } = summary;
  // The witness read is offered only when the server says this session may view blocks; never for a masked session.
  const canWitness = data?.can?.view_blocks === true && data?.masked !== true;
  // Non-masked callers with policy_names_masked hold zone_matrix_read without policy_read (contract 3.4, 7.1).
  const canOpenPolicyViewer = data?.masked === true || data?.policy_names_masked !== true;

  const keepSelected = (options: readonly ViolationFilterOption[], current: string, fallbackLabel = current): readonly ViolationFilterOption[] =>
    current && !options.some((o) => o.value === current) ? [...options, { value: current, label: fallbackLabel }] : options;

  const policyVendorByRunId = useMemo(() => buildPolicyVendorByRunId(data?.policies ?? []), [data]);
  const vendorOptions = useMemo(
    () => keepSelected(
      buildVendorOptions(data?.policies ?? [], data?.findings ?? [], policyVendorByRunId),
      vendor,
      vendor === UNKNOWN_VENDOR_VALUE ? "Vendor unknown" : vendor,
    ),
    [data, policyVendorByRunId, vendor],
  );
  const policySourceOptions = useMemo(
    () => keepSelected(buildPolicySourceOptions(data?.findings ?? [], data?.policies ?? []), policySourceId),
    [data, policySourceId],
  );
  const deviceOptions = useMemo(
    () => keepSelected(buildDeviceOptions(data?.findings ?? []), deviceId),
    [data, deviceId],
  );
  const reasonOptions = useMemo(
    () => reason === NO_REASON_VALUE
      ? buildReasonOptions(data?.findings ?? [])
      : keepSelected(buildReasonOptions(data?.findings ?? []), reason),
    [data, reason],
  );
  const flagOptions = useMemo(
    () => [...new Set((data?.findings ?? []).flatMap((f) => f.flags))].sort(),
    [data],
  );
  const zoneOptions = useMemo(() => {
    const byId = new Map<string, string>();
    for (const f of data?.findings ?? []) {
      for (const p of f.violated_pairs) {
        if (p.source_zone_id) byId.set(p.source_zone_id, p.source_name);
        if (p.destination_zone_id) byId.set(p.destination_zone_id, p.destination_name);
      }
    }
    for (const c of data?.cell_counts ?? []) { byId.set(c.source_zone_id, c.source_name); byId.set(c.destination_zone_id, c.destination_name); }
    // A selected zone that no returned finding names stays selectable, so the control never shows a value it cannot list.
    for (const id of [sourceZone, destinationZone]) if (id !== ALL_ZONES && !byId.has(id)) byId.set(id, id);
    return [...byId.entries()].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name));
  }, [data, sourceZone, destinationZone]);
  const renderNowMs = Date.now();
  const rows = (data?.findings ?? []).filter((f) => matchesViolationFilters(f, policyVendorByRunId, {
    sourceZoneId: sourceZone, destinationZoneId: destinationZone, severity, vendor, policySourceId, deviceId,
    flag, reason, firstObserved, lifecycle, staleness,
  }, renderNowMs));

  const columns: readonly DataColumn<SummaryFinding>[] = useMemo(() => [
    {
      key: "rule", header: "Rule", width: 210, wrap: true,
      render: (f) => {
        const label = ruleLabel(f);
        const href = ruleHref(f.policy_source_id, f.rule_id);
        const showUid = Boolean(f.rule_uid) && f.rule_uid !== (f.rule_name || f.rule_uid);
        return (
          <Box>
            {href !== null && canOpenPolicyViewer
              ? <a href={href} aria-label={`Open ${label} in the policy viewer`}>{label}</a>
              : <span>{label}</span>}
            {showUid ? <Box data-testid="finding-rule-uid" sx={{ ...mono, color: m3.onSurfaceVar }}>{f.rule_uid}</Box> : null}
          </Box>
        );
      },
    },
    ...(canWitness ? [{
      key: "witness", header: "Witness", width: 300, wrap: true,
      render: (f: SummaryFinding) => <FindingWitness finding={f} />,
    }] : []),
    { key: "policy", header: "Policy", width: 150, wrap: true, render: (f) => f.policy_name },
    {
      key: "devices", header: "Devices", width: 180, wrap: true,
      render: (f) => f.target_device_ids.length > 0
        ? <Box component="span" data-testid="finding-devices" sx={mono}>{f.target_device_ids.join(", ")}</Box>
        : <span data-testid="finding-devices"><Unknown reason="No target device is recorded for this finding" /></span>,
    },
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
      key: "notAllowed", header: "Not-allowed part", width: 240, wrap: true,
      render: renderNotAllowedPart,
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
  ], [canWitness, canOpenPolicyViewer]);

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
        <>
          <CellCountsPanel data={data} selected={shownPair}
            onSelect={(c) => { setOffset(0); setPair({ sourceZoneId: c.source_zone_id, destinationZoneId: c.destination_zone_id }); }} />
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
            <TextField select size="small" label="Source zone" value={sourceZone} sx={{ minWidth: 170 }}
              onChange={(e) => { setOffset(0); changePair({ sourceZoneId: e.target.value, destinationZoneId: destinationZone }); }}>
              <MenuItem value={ALL_ZONES}>All source zones</MenuItem>
              {zoneOptions.map((z) => <MenuItem key={z.id} value={z.id}>{z.name}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Destination zone" value={destinationZone} sx={{ minWidth: 190 }}
              onChange={(e) => { setOffset(0); changePair({ sourceZoneId: sourceZone, destinationZoneId: e.target.value }); }}>
              <MenuItem value={ALL_ZONES}>All destination zones</MenuItem>
              {zoneOptions.map((z) => <MenuItem key={z.id} value={z.id}>{z.name}</MenuItem>)}
            </TextField>
            {shownPair ? (
              <M3Button emphasis="text" onClick={() => { setOffset(0); setPair(null); }}>Clear zone filters</M3Button>
            ) : null}
            <TextField select size="small" label="Vendor" value={vendor} sx={{ minWidth: 160 }}
              onChange={(e) => { setOffset(0); setVendor(e.target.value); }}>
              <MenuItem value="">All vendors</MenuItem>
              {vendorOptions.map((v) => <MenuItem key={v.value} value={v.value}>{v.label}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Policy source" value={policySourceId} sx={{ minWidth: 190 }}
              onChange={(e) => { setOffset(0); setPolicySourceId(e.target.value); }}>
              <MenuItem value="">All policy sources</MenuItem>
              {policySourceOptions.map((s) => <MenuItem key={s.value} value={s.value}>{s.label}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Device" value={deviceId} sx={{ minWidth: 180 }}
              onChange={(e) => { setOffset(0); setDeviceId(e.target.value); }}>
              <MenuItem value="">All devices</MenuItem>
              {deviceOptions.map((d) => <MenuItem key={d.value} value={d.value}>{d.label}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Flag" value={flag} sx={{ minWidth: 190 }}
              onChange={(e) => { setOffset(0); setFlag(e.target.value); }}>
              <MenuItem value="">Any flag</MenuItem>
              {flagOptions.map((x) => <MenuItem key={x} value={x}>{flagWords(x)}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Reason" value={reason} sx={{ minWidth: 210 }}
              onChange={(e) => { setOffset(0); setReason(e.target.value); }}>
              <MenuItem value="">Any reason</MenuItem>
              {reasonOptions.map((r) => <MenuItem key={r.value} value={r.value}>{r.label}</MenuItem>)}
              <MenuItem value={NO_REASON_VALUE}>No reason recorded</MenuItem>
            </TextField>
            <TextField select size="small" label="First observed (current version history, not rule's age)" value={firstObserved} sx={{ minWidth: 360 }}
              onChange={(e) => { setOffset(0); setFirstObserved(e.target.value as FirstObservedRange | ""); }}>
              <MenuItem value="">Any first-observed date</MenuItem>
              {FIRST_OBSERVED_OPTIONS.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="State" value={lifecycle} sx={{ minWidth: 190 }}
              onChange={(e) => { setOffset(0); setLifecycle(e.target.value); }}>
              <MenuItem value="">Open and became undecided</MenuItem>
              <MenuItem value="open">Open, still decided</MenuItem>
              <MenuItem value="undecided">Became undecided</MenuItem>
            </TextField>
            <TextField select size="small" label="Staleness" value={staleness} sx={{ minWidth: 160 }}
              onChange={(e) => { setOffset(0); setStaleness(e.target.value as PolicyStalenessChoice | ""); }}>
              <MenuItem value="">Any staleness</MenuItem>
              {STALENESS_OPTIONS.map((s) => <MenuItem key={s.value} value={s.value}>{s.label}</MenuItem>)}
            </TextField>
          </Stack>
          <Typography variant="body2" data-testid="findings-count">{countsText(data, rows.length)}</Typography>
          <Hint>The outcome, policy and request size are applied by the server; severity, source zone, destination zone, vendor, policy source, device, flag, reason, first observed and staleness only narrow the rows returned. First observed is the first-observed date in the current version history, not the rule&apos;s age. Not evaluable and unknown rows stay in the list unless you filter them out.</Hint>
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
        </>
      )}

      <ResolvedPanel data={data} />
    </Stack>
  );
}
