import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  getOfflineAnalysis,
  listOfflineAnalyses,
  type OfflineAnalysisResult,
  type OfflineAnalysisSummary,
} from "../auth/adminApi";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { standardChangesHref } from "./estateRouting";
import {
  OFFLINE_RULE_CAP,
  offlineComparisonLine,
  offlineListRequest,
  offlineModeLabel,
  offlinePager,
  offlinePeriodLine,
  offlineRuleCounts,
  offlineRuleRow,
  offlineSampleLine,
  offlineStatLines,
} from "./estateOfflineModel";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";

type ListRead =
  | { readonly state: "loading" }
  | { readonly state: "ok"; readonly analyses: readonly OfflineAnalysisSummary[] }
  | { readonly state: "unknown" };

type DetailRead =
  | { readonly state: "idle" }
  | { readonly state: "loading"; readonly id: string }
  | { readonly state: "ok"; readonly value: OfflineAnalysisResult }
  | { readonly state: "unknown"; readonly id: string };

/**
 * Read-only "Offline analyses" tab of the Estate Policy browser (standard-view parity with PolicyOfflineTab). Lists the stored
 * analyses of one policy and shows the summary counts of a selected one. Reads only: no upload, no delete, no POST/DELETE.
 * An unreadable list is UNKNOWN; "no analyses" is shown only after a successful, empty read.
 */
export function EstateOfflineAnalyses({ policy, now = new Date() }: { readonly policy: string; readonly now?: Date }) {
  const [page, setPage] = useState(0);
  const [list, setList] = useState<ListRead>({ state: "loading" });
  const [detail, setDetail] = useState<DetailRead>({ state: "idle" });

  useEffect(() => { setPage(0); setDetail({ state: "idle" }); }, [policy]);

  useEffect(() => {
    if (!policy) { setList({ state: "loading" }); return; }
    let active = true;
    const request = offlineListRequest(policy, page);
    setList({ state: "loading" });
    listOfflineAnalyses(request.policy, request.page).then(
      (res) => { if (active) setList(res && Array.isArray(res.analyses) ? { state: "ok", analyses: res.analyses } : { state: "unknown" }); },
      () => { if (active) setList({ state: "unknown" }); },
    );
    return () => { active = false; };
  }, [policy, page]);

  const pager = offlinePager(page, list.state === "ok" ? list.analyses.length : 0);
  const counts = useMemo(
    () => (detail.state === "ok" ? offlineRuleCounts(detail.value.rules ?? []) : null),
    [detail],
  );

  const comparison = detail.state === "ok" ? offlineComparisonLine(detail.value) : null;
  const shownRules = useMemo(
    () => (detail.state === "ok" ? (detail.value.rules ?? []).slice(0, OFFLINE_RULE_CAP).map(offlineRuleRow) : []),
    [detail],
  );
  const statGroups = useMemo(
    () => (detail.state === "ok"
      ? [
          { label: "Parse stats", lines: offlineStatLines(detail.value.parseStats) },
          { label: "UNKNOWN stats", lines: offlineStatLines(detail.value.unknownStats) },
          { label: "Verdict stats", lines: offlineStatLines(detail.value.verdictStats) },
        ].filter((g) => g.lines.length > 0)
      : []),
    [detail],
  );

  const open = (id: string) => {
    setDetail({ state: "loading", id });
    getOfflineAnalysis(id).then(
      (value) => setDetail((current) => (current.state === "loading" && current.id === id && value ? { state: "ok", value } : current.state === "loading" && current.id === id ? { state: "unknown", id } : current)),
      () => setDetail((current) => (current.state === "loading" && current.id === id ? { state: "unknown", id } : current)),
    );
  };

  return (
    <Section label="Offline analyses">
      <p style={{ margin: "0 0 8px", fontSize: "12.5px", color: ev.ink2 }}>
        Stored SIEM-sample analyses of this policy, read-only. Upload and delete stay in the standard view.
        {" "}<a href={standardChangesHref("policy")} style={{ color: ev.accent }}>Open offline analyses in standard view</a>
      </p>

      {!policy ? (
        <p style={note}>Select a unit to list its offline analyses.</p>
      ) : list.state === "loading" ? (
        <p role="status" style={note}>Loading stored offline analyses…</p>
      ) : list.state === "unknown" ? (
        <p style={note}>Offline analyses UNKNOWN: the stored list could not be read.</p>
      ) : list.analyses.length === 0 && page === 0 ? (
        <p style={note}>No stored offline analysis for this policy.</p>
      ) : (
        <>
          <ul style={listStyle} aria-label="Stored offline analyses">
            {list.analyses.map((a) => (
              <li key={a.id} style={rowStyle}>
                <button type="button" onClick={() => open(a.id)} style={rowButton} aria-label={`Open ${a.fileName}`}>
                  {a.fileName || "(unnamed upload)"}
                </button>
                <span>{offlineModeLabel(a)}</span>
                <span>{a.rowCount} rows</span>
                <span>Rules: UNKNOWN until opened</span>
                <span style={{ color: ev.ink3 }}>status: stored</span>
                <EvidenceStamp at={a.createdAt} source="analysed" now={now} />
              </li>
            ))}
          </ul>
          <div style={{ display: "flex", gap: "8px", alignItems: "center", marginTop: "6px", fontSize: "12px", color: ev.ink2 }}>
            <button type="button" disabled={!pager.canPrevious} onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous analyses</button>
            <button type="button" disabled={!pager.canNext} onClick={() => setPage((p) => p + 1)}>Next analyses</button>
            {pager.note && <span>{pager.note}</span>}
          </div>
        </>
      )}

      {detail.state === "loading" && <p role="status" style={note}>Loading analysis summary…</p>}
      {detail.state === "unknown" && <p style={note}>Analysis summary UNKNOWN: this analysis could not be read.</p>}
      {detail.state === "ok" && counts && (
        <div style={{ marginTop: "10px", borderTop: `1px solid ${ev.line}`, paddingTop: "8px", fontSize: "12.5px", color: ev.ink }}>
          <div style={{ fontWeight: 650 }}>{detail.value.fileName} · {offlineModeLabel(detail.value)}</div>
          <div style={{ color: ev.ink2 }}>{offlineSampleLine(detail.value)}</div>
          <div style={{ display: "flex", gap: "10px", flexWrap: "wrap", color: ev.ink2 }}>
            <EvidenceStamp at={detail.value.collectedAt ?? null} source="policy snapshot" now={now} />
            <EvidenceStamp at={detail.value.createdAt} source="analysed" now={now} />
          </div>
          <div style={{ color: ev.ink2 }}>{offlinePeriodLine(detail.value)}</div>
          {detail.value.attributionNote && <p style={note}>{detail.value.attributionNote}</p>}
          {detail.value.limitations && <p style={note}>Limitations: {detail.value.limitations}</p>}
          {detail.value.caution && <p style={note}>Caution: {detail.value.caution}</p>}
          {comparison !== null && <div style={{ color: ev.ink2 }}>{comparison}</div>}
          {statGroups.length > 0 && (
            <div style={{ display: "flex", gap: "16px", flexWrap: "wrap", marginTop: "6px", fontFamily: ESTATE_MONO, fontSize: "12px", color: ev.ink2 }}>
              {statGroups.map((g) => (
                <div key={g.label}>
                  <div style={{ color: ev.ink, fontWeight: 650 }}>{g.label}</div>
                  <ul style={listStyle} aria-label={g.label}>
                    {g.lines.map((line) => <li key={line}>{line}</li>)}
                  </ul>
                </div>
              ))}
            </div>
          )}
          <ul style={listStyle} aria-label="Analysis rule counts">
            <li>Rules in this analysis: {counts.rules}</li>
            <li>Zero-traffic rules (zero attributed flows in this sample; not proof of an unused rule): {counts.zeroTraffic}</li>
            <li>Rules with unseen members (or member status UNKNOWN): {counts.unseenMembers}</li>
            <li>Rules with a narrowing suggestion status: {counts.narrowing}</li>
          </ul>
          <div style={{ marginTop: "8px" }} aria-label="Per-rule findings">
            {shownRules.map((r) => (
              <div key={r.key} style={{ padding: "4px 0", borderBottom: `1px solid ${ev.line}` }}>
                <div style={{ fontWeight: 650 }}>{r.title}</div>
                <div style={{ color: ev.ink2, fontFamily: ESTATE_MONO, fontSize: "12px" }}>{r.meta}</div>
                <div style={{ display: "flex", gap: "8px", flexWrap: "wrap", alignItems: "center", fontSize: "12px" }}>
                  <StateBadge state={r.memberBadge.state} word={r.memberBadge.word} dense />
                  {r.zeroTrafficNote && <span style={{ color: ev.ink3 }}>{r.zeroTrafficNote}</span>}
                  {r.memberNote && <span style={{ color: ev.ink3 }}>{r.memberNote}</span>}
                  {r.narrowingNote && <span style={{ color: ev.ink3 }}>{r.narrowingNote}</span>}
                </div>
              </div>
            ))}
            <MoreFooter shown={shownRules.length} total={detail.value.rules.length} noun="rules" testId="offline-rules-more" />
          </div>
        </div>
      )}
    </Section>
  );
}

function Section({ label, children }: { readonly label: string; readonly children: ReactNode }) {
  return (
    <EstateCard sx={{ mt: "14px" }}>
      <div style={{ padding: "12px 18px", fontFamily: ESTATE_SANS, fontSize: "13px", color: ev.ink }} aria-label={label}>
        <div style={{ fontWeight: 700, fontSize: "15px", marginBottom: "6px" }}>{label}</div>
        {children}
      </div>
    </EstateCard>
  );
}

const note = { margin: 0, fontSize: "12.5px", color: ev.ink2 } as const;
const listStyle = { listStyle: "none", padding: 0, margin: "6px 0 0", fontFamily: ESTATE_MONO, fontSize: "12px" } as const;
const rowStyle = { display: "flex", gap: "10px", flexWrap: "wrap", alignItems: "center", padding: "4px 0", borderBottom: `1px solid ${ev.line}` } as const;
const rowButton = { fontFamily: ESTATE_MONO, fontSize: "12px", background: "none", border: "none", color: ev.accent, cursor: "pointer", padding: 0 } as const;
