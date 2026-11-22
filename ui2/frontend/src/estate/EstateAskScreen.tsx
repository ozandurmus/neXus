import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import {
  getCutoverAssessment,
  getCutoverSegment,
  getOfflineAnalysis,
  listCutoverSegments,
  listOfflineAnalyses,
  listPolicies,
  lookupPolicy,
  queryPath,
  type CutoverSegment,
  type OfflineAnalysisSummary,
  type PolicyMetadata,
} from "../auth/adminApi";
import { classifyPathFailure } from "../screens/pathanalysis/pathWords";
import { isRestricted } from "../shell/States";
import { writeUrlParams } from "../shell/urlParams";
import {
  evaluateZoneMatrixPolicy,
  getZoneVersion,
  listZoneMatrixPolicies,
  listZoneVersions,
  type ZoneMatrixPolicyItem,
  type ZoneVersionDetail,
} from "../screens/zonematrix/zoneMatrixApi";
import {
  ASK_MODES,
  ASK_MODE_META,
  CANNOT_PROVE,
  buildCutoverAnswer,
  buildLookupAnswer,
  buildPathAnswer,
  buildUsageAnswer,
  buildZonesAnswer,
  flowService,
  parseFlowQuestion,
  type AskAnswer,
  type AskEvidenceRow,
  type AskMode,
} from "./estateAskModel";
import { EstateCard, EvidenceStamp, StateBadge } from "./EstatePrimitives";
import { EstateZoneMatrixGrid } from "./EstateZoneMatrixGrid";
import { standardTraceHref } from "./estateRouting";
import { ESTATE_MONO, ESTATE_UNKNOWN_HATCH, ev } from "./estateTokens";

type Failure = { readonly title: string; readonly body: string; readonly restricted: boolean };

const SELECT_SX = {
  font: "inherit",
  fontSize: "14px",
  height: "38px",
  borderRadius: "8px",
  border: `1px solid ${ev.lineStrong}`,
  bgcolor: ev.surface,
  color: ev.ink,
  px: "10px",
  minWidth: "240px",
  maxWidth: "100%",
} as const;

function failureFor(error: unknown, area: string): Failure {
  if (isRestricted(error)) {
    return { title: "Restricted", body: `Your role does not include ${area}. No answer is claimed.`, restricted: true };
  }
  const f = classifyPathFailure(error);
  switch (f.kind) {
    case "not_built":
      return { title: "Topology not built", body: "No topology is held, so no path can be evaluated. A query never builds one.", restricted: false };
    case "refused":
      return { title: "A value was not recognised", body: "In a masked session type the values this product shows (FW-TANGO-04, 192.0.2.10). Nothing about the refused value is stored.", restricted: false };
    case "busy":
      return { title: "The analysis is busy", body: "Every analysis slot is taken. This is a wait, not a fault of the question: retry in a few seconds.", restricted: false };
    case "rate_limited":
      return { title: "Question limit reached", body: "Retry after one minute; the question is unchanged.", restricted: false };
    case "audit":
      return { title: "Answer withheld", body: "The query record could not be written, so no answer is shown. Retry.", restricted: false };
    case "invalid":
      return { title: "The question was not accepted", body: "Check the addresses, the protocol and the port, then ask again.", restricted: false };
    case "offline":
      return { title: "The request did not complete", body: "No answer arrived in time. Retry.", restricted: false };
    default:
      return { title: "The server could not answer", body: "No verdict is claimed.", restricted: false };
  }
}

interface RecentQuestion {
  readonly id: number;
  readonly mode: AskMode;
  readonly label: string;
  readonly verdictWord: string;
  readonly state: AskAnswer["state"];
  readonly at: Date;
}

/** The active zone version (read-only, no blocks). Resolves to `null` when none is ACTIVE. */
async function loadActiveZoneVersion(): Promise<ZoneVersionDetail | null> {
  const list = await listZoneVersions();
  const active = list.versions.find((v) => v.state === "ACTIVE");
  return active ? getZoneVersion(active.version_id, false) : null;
}

function useLoaded<T>(loader: () => Promise<T>, enabled: boolean): { readonly value: T | null; readonly failure: unknown; readonly loading: boolean } {
  const [value, setValue] = useState<T | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [loading, setLoading] = useState(enabled);
  const ref = useRef(loader);
  ref.current = loader;
  useEffect(() => {
    if (!enabled) return;
    let active = true;
    setLoading(true);
    ref.current().then(
      (v) => { if (active) { setValue(v); setFailure(null); setLoading(false); } },
      (e: unknown) => { if (active) { setFailure(e); setLoading(false); } },
    );
    return () => { active = false; };
  }, [enabled]);
  return { value, failure, loading };
}

function AnswerView({ answer, now }: { readonly answer: AskAnswer; readonly now: Date }) {
  return (
    <EstateCard sx={{ p: "18px", mt: "16px" }}>
      <Box data-testid="ask-answer" data-ask-state={answer.state} data-ask-unprovable={answer.unprovable ? "true" : "false"}>
        <Box sx={{ display: "flex", alignItems: "center", gap: "12px", flexWrap: "wrap" }}>
          <StateBadge state={answer.state} word={answer.verdictWord} />
          <EvidenceStamp at={answer.evidenceAt} source="stored evidence" now={now} />
        </Box>
        {answer.unprovable ? (
          <Typography
            data-testid="ask-cannot-prove"
            sx={{ mt: "10px", p: "8px 10px", fontSize: "14px", fontWeight: 650, borderRadius: "8px", border: `1px dashed ${ev.unk}`, color: ev.unk, backgroundImage: ESTATE_UNKNOWN_HATCH, display: "inline-block" }}
          >
            {CANNOT_PROVE}: the stored evidence does not decide this question.
          </Typography>
        ) : null}
        <Typography component="h2" sx={{ fontSize: "20px", fontWeight: 680, mt: "10px", lineHeight: 1.3 }}>{answer.headline}</Typography>
        {answer.detail ? <Typography sx={{ fontSize: "13.5px", color: ev.ink2, mt: "4px" }}>{answer.detail}</Typography> : null}
        {answer.rows.length > 0 ? (
          <Box component="ol" aria-label="Evidence" sx={{ listStyle: "none", m: "14px 0 0", p: 0, display: "grid", gap: "8px" }}>
            {answer.rows.map((row) => <EvidenceRow key={row.key} row={row} now={now} />)}
          </Box>
        ) : null}
        {answer.limitations ? (
          <Typography sx={{ fontSize: "12px", color: ev.ink3, mt: "12px" }}>{answer.limitations}</Typography>
        ) : null}
      </Box>
    </EstateCard>
  );
}

function EvidenceRow({ row, now }: { readonly row: AskEvidenceRow; readonly now: Date }) {
  return (
    <Box component="li" data-ask-hop={row.key} data-ask-hop-state={row.state}
      sx={{ border: `1px solid ${row.state === "unk" ? ev.unk : ev.line}`, borderRadius: "10px", p: "10px 12px", bgcolor: ev.surface2 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
        <Typography sx={{ fontWeight: 650, fontSize: "14px" }}>{row.title}</Typography>
        <StateBadge state={row.state} word={row.stateWord} dense />
        {row.at !== undefined ? <EvidenceStamp at={row.at} source={row.source} now={now} /> : null}
      </Box>
      {row.lines.map((line, i) => (
        <Typography key={i} sx={{ fontFamily: ESTATE_MONO, fontSize: "12.5px", color: ev.ink2, mt: "2px" }}>{line}</Typography>
      ))}
    </Box>
  );
}

function StoredPicker<T>({ label, items, value, onChange, optionLabel, optionValue }: {
  readonly label: string;
  readonly items: readonly T[];
  readonly value: string;
  readonly onChange: (v: string) => void;
  readonly optionLabel: (item: T) => string;
  readonly optionValue: (item: T) => string;
}) {
  return (
    <Box component="label" sx={{ display: "grid", gap: "4px", fontSize: "12px", color: ev.ink3, fontWeight: 650 }}>
      {label}
      <Box component="select" value={value} onChange={(e: { target: { value: string } }) => onChange(e.target.value)} sx={SELECT_SX}>
        <option value="">Choose…</option>
        {items.map((item) => <option key={optionValue(item)} value={optionValue(item)}>{optionLabel(item)}</option>)}
      </Box>
    </Box>
  );
}

const PRIMARY_BUTTON_SX = {
  font: "inherit", fontWeight: 650, fontSize: "14px", height: "38px", px: "18px", borderRadius: "8px", border: 0, cursor: "pointer",
  bgcolor: ev.accent, color: ev.accentInk, "&:disabled": { opacity: 0.6, cursor: "default" },
} as const;

export function EstateAskScreen({ initialMode }: { readonly initialMode: AskMode }) {
  const [mode, setMode] = useState<AskMode>(initialMode);
  useEffect(() => setMode(initialMode), [initialMode]);
  const [text, setText] = useState("");
  const [policyId, setPolicyId] = useState("");
  const [pickId, setPickId] = useState("");
  const [answer, setAnswer] = useState<AskAnswer | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [busy, setBusy] = useState(false);
  const [recent, setRecent] = useState<readonly RecentQuestion[]>([]);
  const inFlight = useRef(false);
  const counter = useRef(0);
  const now = new Date();

  const needsPolicies = mode === "lookup" || mode === "usage";
  const policies = useLoaded(listPolicies, needsPolicies);
  const segments = useLoaded(listCutoverSegments, mode === "cutover");
  const zonePolicies = useLoaded(listZoneMatrixPolicies, mode === "zones");
  const zoneGrid = useLoaded(loadActiveZoneVersion, mode === "zones");
  const policyList: readonly PolicyMetadata[] = policies.value?.policies ?? [];
  const analyses = useLoaded(
    useCallback(() => (policyId ? listOfflineAnalyses(policyId) : Promise.resolve({ analyses: [] as OfflineAnalysisSummary[], canManage: false })), [policyId]),
    mode === "usage" && Boolean(policyId),
  );

  const choose = (next: AskMode) => {
    setMode(next); setAnswer(null); setFailure(null); setPickId("");
    writeUrlParams({ mode: next === "path" ? null : next }, "push");
  };

  const flow = parseFlowQuestion(text);
  const missing: string[] = [];
  if (mode === "path" || mode === "lookup") missing.push(...flow.missing);
  if (mode === "lookup" && !policyId) missing.push("a stored policy");
  if (mode === "usage" && !policyId) missing.push("a stored policy");
  if (mode === "usage" && policyId && !pickId) missing.push("a stored one-rule analysis");
  if (mode === "cutover" && !pickId) missing.push("a segment");
  if (mode === "zones" && !pickId) missing.push("a policy");
  const reason = missing.length ? `Needs ${missing.join(", ")}` : null;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (inFlight.current || reason) return;
    inFlight.current = true; setBusy(true); setFailure(null);
    try {
      let result: AskAnswer;
      let label = "";
      if (mode === "path" && flow.flow) {
        label = `${flow.flow.src} → ${flow.flow.dst} ${flowService(flow.flow)}`;
        const r = await queryPath({ src: flow.flow.src, dst: flow.flow.dst, protocol: flow.flow.protocol, port: flow.flow.port });
        if (r?.status !== "COMPUTED" || !Array.isArray(r.branches) || !r.executive_summary) throw new Error("UNEXPECTED_RESULT_SHAPE");
        result = buildPathAnswer(r);
      } else if (mode === "lookup" && flow.flow) {
        label = `${flow.flow.src} → ${flow.flow.dst} ${flowService(flow.flow)}`;
        const r = await lookupPolicy(policyId, { source: flow.flow.src, destination: flow.flow.dst, service: flowService(flow.flow) });
        if (!["ALLOWED", "DENIED", "PARTIAL", "UNKNOWN"].includes(r.verdict) || !Array.isArray(r.undecidableRules)) throw new Error("UNEXPECTED_RESULT_SHAPE");
        result = buildLookupAnswer(r);
      } else if (mode === "usage") {
        label = "Rule object usage";
        result = buildUsageAnswer(await getOfflineAnalysis(pickId));
      } else if (mode === "cutover") {
        label = "Cutover impact";
        const segment = await getCutoverSegment(pickId);
        const newest = [...(segment.assessments ?? [])].sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))[0];
        if (!newest) {
          setAnswer({ mode: "cutover", state: "unk", verdictWord: "UNKNOWN", headline: "No cutover assessment is stored for this segment.", detail: "Run one from the standard Cutover impact screen; it needs uploaded traffic logs.", unprovable: true, rows: [], evidenceAt: null, limitations: null });
          return;
        }
        result = buildCutoverAnswer(await getCutoverAssessment(newest.assessmentId));
      } else {
        label = "Zone matrix";
        result = buildZonesAnswer(await evaluateZoneMatrixPolicy(pickId));
      }
      setAnswer(result);
      counter.current += 1;
      setRecent((prev) => [{ id: counter.current, mode, label, verdictWord: result.verdictWord, state: result.state, at: new Date() }, ...prev].slice(0, 8));
    } catch (error) {
      setAnswer(null); setFailure(failureFor(error, ASK_MODE_META[mode].question));
    } finally {
      inFlight.current = false; setBusy(false);
    }
  };

  const catalogueFailure = [policies.failure, mode === "cutover" ? segments.failure : null, mode === "zones" ? zonePolicies.failure : null, analyses.failure].find(Boolean);
  const segmentList: readonly CutoverSegment[] = segments.value?.segments ?? [];
  const oneRuleAnalyses = (analyses.value?.analyses ?? []).filter((a) => a.mode === "ONE_RULE");

  return (
    <Box data-testid="estate-ask-screen">
      <Typography sx={{ fontSize: "11px", letterSpacing: "0.09em", textTransform: "uppercase", color: ev.ink3, fontWeight: 650 }}>Estate · Ask</Typography>
      <Typography component="h1" sx={{ fontSize: "26px", fontWeight: 680, lineHeight: 1.2, mt: "2px" }}>Ask the stored evidence a question about traffic</Typography>
      <Typography sx={{ fontSize: "14.5px", color: ev.ink2, mt: "6px", maxWidth: "760px" }}>
        Answers come from stored policy and routing intent, never from observed traffic, and nothing is sent to a device. The verdict comes first; every hop says where its evidence came from and how old it is.
      </Typography>

      <Box role="tablist" aria-label="Questions" sx={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: "10px", mt: "16px" }}>
        {ASK_MODES.map((m) => (
          <Box key={m} component="button" type="button" role="tab" aria-selected={m === mode} data-ask-mode={m} onClick={() => choose(m)}
            sx={{ textAlign: "left", font: "inherit", cursor: "pointer", p: "10px 12px", borderRadius: "10px", color: ev.ink,
              border: `1px solid ${m === mode ? ev.accent : ev.line}`, bgcolor: m === mode ? ev.accentBg : ev.surface }}>
            <Typography component="span" sx={{ display: "block", fontWeight: 650, fontSize: "14px" }}>{ASK_MODE_META[m].question}</Typography>
            <Typography component="span" sx={{ display: "block", fontSize: "12px", color: ev.ink3 }}>{ASK_MODE_META[m].hint}</Typography>
          </Box>
        ))}
      </Box>

      <EstateCard sx={{ p: "16px", mt: "16px" }}>
        <Box component="form" aria-label="Ask" onSubmit={(e: FormEvent) => void submit(e)} sx={{ display: "grid", gap: "12px" }}>
          {mode === "path" || mode === "lookup" ? (
            <Box component="label" sx={{ display: "grid", gap: "4px", fontSize: "12px", color: ev.ink3, fontWeight: 650 }}>
              {mode === "path" ? "Can this source reach this destination?" : "Which rule decides this flow?"}
              <Box component="input" value={text} onChange={(e: { target: { value: string } }) => setText(e.target.value)} placeholder="192.0.2.10 → 198.51.100.40 tcp/8443"
                maxLength={200} data-testid="ask-input" sx={{ ...SELECT_SX, fontFamily: ESTATE_MONO, width: "100%", boxSizing: "border-box" }} />
            </Box>
          ) : null}
          {mode === "lookup" || mode === "usage" ? (
            <StoredPicker label="Stored policy" items={policyList} value={policyId} onChange={(v) => { setPolicyId(v); setPickId(""); }}
              optionLabel={(p) => p.name} optionValue={(p) => p.id} />
          ) : null}
          {mode === "usage" && policyId ? (
            <StoredPicker label="One-rule analysis" items={oneRuleAnalyses} value={pickId} onChange={setPickId}
              optionLabel={(a) => `${a.fileName} · rule ${a.ruleId ?? "?"} · ${a.periodStart}`} optionValue={(a) => a.id} />
          ) : null}
          {mode === "cutover" ? (
            <StoredPicker label="Segment" items={segmentList} value={pickId} onChange={setPickId} optionLabel={(s) => s.name} optionValue={(s) => s.segmentId} />
          ) : null}
          {mode === "zones" ? (
            <StoredPicker label="Policy to evaluate" items={(zonePolicies.value?.policies ?? []) as readonly ZoneMatrixPolicyItem[]} value={pickId} onChange={setPickId}
              optionLabel={(p) => `${p.name} · ${p.vendor}`} optionValue={(p) => p.id} />
          ) : null}
          <Box sx={{ display: "flex", gap: "12px", alignItems: "center", flexWrap: "wrap" }}>
            <Box component="button" type="submit" disabled={busy || reason !== null} data-testid="ask-submit" sx={PRIMARY_BUTTON_SX}>
              {busy ? "Asking…" : reason ? `Ask · ${reason}` : "Ask"}
            </Box>
            <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
              {mode === "usage" || mode === "cutover"
                ? "Reads the stored result. A new upload is run from the standard screen."
                : "Each question is recorded with its verdict; the result itself is not stored."}
            </Typography>
          </Box>
        </Box>
        {catalogueFailure ? (
          <Typography role="status" sx={{ mt: "10px", fontSize: "13px", color: ev.ink2 }}>
            {isRestricted(catalogueFailure) ? "Your role does not include this question’s stored data." : "The stored list for this question could not be read."}
          </Typography>
        ) : null}
        {mode === "lookup" && !policies.loading && !catalogueFailure && policyList.length === 0 ? (
          <Typography role="status" sx={{ mt: "10px", fontSize: "13px", color: ev.ink2 }}>No stored policy to look up in. Collect a management source first.</Typography>
        ) : null}
      </EstateCard>

      {failure ? (
        <EstateCard sx={{ p: "16px", mt: "16px" }}>
          <Box role="alert" data-testid="ask-failure">
            <StateBadge state={failure.restricted ? "na" : "unk"} word={failure.restricted ? "Restricted" : "No answer"} />
            <Typography sx={{ fontWeight: 650, mt: "8px" }}>{failure.title}</Typography>
            <Typography sx={{ fontSize: "13.5px", color: ev.ink2 }}>{failure.body}</Typography>
          </Box>
        </EstateCard>
      ) : null}
      {answer ? <AnswerView answer={answer} now={now} /> : null}
      {mode === "zones" ? (
        <EstateZoneMatrixGrid version={zoneGrid.value} loading={zoneGrid.loading} failed={zoneGrid.failure !== null} />
      ) : null}
      {!answer && !failure && !busy ? (
        <Typography data-testid="ask-empty" sx={{ mt: "16px", fontSize: "14px", color: ev.ink3 }}>No question asked yet. Pick a question above and ask.</Typography>
      ) : null}

      {recent.length > 0 ? (
        <EstateCard sx={{ p: "16px", mt: "16px" }}>
          <Typography component="h2" sx={{ fontSize: "14px", fontWeight: 650 }}>Recent questions · this session, verdict only</Typography>
          <Box component="ul" aria-label="Recent questions" sx={{ listStyle: "none", m: "8px 0 0", p: 0, display: "grid", gap: "6px" }}>
            {recent.map((r) => (
              <Box component="li" key={r.id} sx={{ display: "flex", gap: "10px", alignItems: "center", flexWrap: "wrap", fontSize: "13px" }}>
                <Box component="span" sx={{ fontFamily: ESTATE_MONO }}>{r.label}</Box>
                <StateBadge state={r.state} word={r.verdictWord} dense />
              </Box>
            ))}
          </Box>
        </EstateCard>
      ) : null}

      <Typography sx={{ mt: "16px", fontSize: "12.5px", color: ev.ink3 }}>
        Need the full tool? <Box component="a" href={standardTraceHref(mode === "lookup" ? "lookup" : "path")} data-testid="ask-standard-link" sx={{ color: ev.accent }}>Open the standard Trace screen →</Box>
      </Typography>
    </Box>
  );
}
