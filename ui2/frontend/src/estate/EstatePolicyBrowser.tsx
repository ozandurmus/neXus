import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import {
  getPolicy,
  getPolicyHygieneSummary,
  listPolicies,
  type PolicyHygieneSummary,
  type PolicyMetadata,
  type PolicyPage,
} from "../auth/adminApi";
import { EstateCard, EvidenceStamp } from "./EstatePrimitives";
import { EstateOfflineAnalyses } from "./EstateOfflineAnalyses";
import { standardChangesHref } from "./estateRouting";
import {
  POLICY_BROWSER_OBJECT_CAP,
  POLICY_BROWSER_RULE_CAP,
  capList,
  flattenRules,
  hygieneSummaryView,
  policyUnitOptions,
} from "./estatePolicyBrowserModel";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";

type Read<T> = { readonly state: "loading" } | { readonly state: "ok"; readonly value: T } | { readonly state: "unknown" };

const SEARCH_DEBOUNCE_MS = 300;

/**
 * Read-only Policy browser inside Changes (concept rows 04-06): pick a unit, list its rules and objects with search,
 * and show the fleet hygiene summary. Every read is an existing client (listPolicies, getPolicy, getPolicyHygieneSummary);
 * nothing here writes or collects. Lists are capped with "+N more"; an unreadable source is UNKNOWN, never OK.
 */
export function EstatePolicyBrowser({ now = new Date() }: { readonly now?: Date }) {
  const [catalog, setCatalog] = useState<Read<PolicyMetadata[]>>({ state: "loading" });
  const [hygiene, setHygiene] = useState<Read<PolicyHygieneSummary>>({ state: "loading" });
  const [selected, setSelected] = useState<string>("");
  const [text, setText] = useState<string>("");
  const [query, setQuery] = useState<string>("");
  const [page, setPage] = useState<Read<PolicyPage>>({ state: "loading" });
  const [tab, setTab] = useState<"rules" | "offline">("rules");

  useEffect(() => {
    let active = true;
    listPolicies().then(
      (res) => { if (active) setCatalog(Array.isArray(res?.policies) ? { state: "ok", value: res.policies } : { state: "unknown" }); },
      () => { if (active) setCatalog({ state: "unknown" }); },
    );
    getPolicyHygieneSummary().then(
      (res) => { if (active) setHygiene(res && Array.isArray(res.containers) ? { state: "ok", value: res } : { state: "unknown" }); },
      () => { if (active) setHygiene({ state: "unknown" }); },
    );
    return () => { active = false; };
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => setQuery(text.trim()), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [text]);

  const options = useMemo(() => (catalog.state === "ok" ? policyUnitOptions(catalog.value) : []), [catalog]);

  useEffect(() => {
    if (!selected && options.length > 0) setSelected(options[0].id);
  }, [options, selected]);

  useEffect(() => {
    if (!selected) { setPage({ state: "loading" }); return; }
    let active = true;
    setPage({ state: "loading" });
    getPolicy(selected, 0, query).then(
      (res) => { if (active) setPage(res && res.metadata && Array.isArray(res.sections) ? { state: "ok", value: res } : { state: "unknown" }); },
      () => { if (active) setPage({ state: "unknown" }); },
    );
    return () => { active = false; };
  }, [selected, query]);

  const rules = useMemo(() => (page.state === "ok" ? flattenRules(page.value.sections) : []), [page]);
  const ruleList = capList(rules, POLICY_BROWSER_RULE_CAP);
  const objectList = capList(page.state === "ok" && Array.isArray(page.value.objects) ? page.value.objects : [], POLICY_BROWSER_OBJECT_CAP);
  const summary = useMemo(() => hygieneSummaryView(hygiene.state === "ok" ? hygiene.value : null), [hygiene]);
  const selectedMeta = options.find((o) => o.id === selected) ?? null;

  return (
    <EstateCard sx={{ mt: "14px" }}>
      <Box sx={{ px: "18px", py: "12px", display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
        <Typography component="h2" sx={{ fontFamily: ESTATE_SANS, fontWeight: 700, fontSize: "15px", color: ev.ink }}>
          Policy browser
        </Typography>
        <Typography sx={{ fontSize: "12px", color: ev.ink3 }}>read-only · stored evidence</Typography>
        <a href={standardChangesHref("policy")} style={{ marginLeft: "auto", fontSize: "12px", color: ev.accent }}>
          Open in standard view
        </a>
      </Box>

      <Box sx={{ px: "18px", pb: "12px", display: "flex", gap: "10px", flexWrap: "wrap", alignItems: "center" }}>
        {catalog.state === "loading" ? (
          <Typography sx={{ color: ev.ink2 }}>Loading stored policies…</Typography>
        ) : catalog.state === "unknown" ? (
          <Typography sx={{ color: ev.ink2 }}>Unit list UNKNOWN: the stored policies could not be read.</Typography>
        ) : options.length === 0 ? (
          <Typography sx={{ color: ev.ink2 }}>No stored policy containers.</Typography>
        ) : (
          <>
            <Box component="label" sx={{ fontSize: "12px", color: ev.ink2, display: "flex", gap: "6px", alignItems: "center" }}>
              Unit
              <select value={selected} onChange={(e) => setSelected(e.target.value)}
                style={{ fontFamily: ESTATE_SANS, fontSize: "12.5px", maxWidth: "520px" }}>
                {options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
              </select>
            </Box>
            <Box component="label" sx={{ fontSize: "12px", color: ev.ink2, display: "flex", gap: "6px", alignItems: "center" }}>
              Search rules and objects
              <input type="search" value={text} placeholder="name, number, address" onChange={(e) => setText(e.target.value)}
                style={{ fontFamily: ESTATE_MONO, fontSize: "12.5px", padding: "3px 6px" }} />
            </Box>
          </>
        )}
      </Box>

      <Box role="tablist" aria-label="Policy browser views" sx={{ px: "18px", pb: "10px", display: "flex", gap: "6px" }}>
        {([["rules", "Rules and objects"], ["offline", "Offline analyses"]] as const).map(([key, label]) => (
          <button key={key} role="tab" type="button" aria-selected={tab === key} onClick={() => setTab(key)}
            style={{ fontFamily: ESTATE_SANS, fontSize: "12.5px", padding: "3px 10px", border: `1px solid ${ev.line}`,
              borderRadius: "6px", background: tab === key ? ev.accent : "transparent", color: tab === key ? "#fff" : ev.ink2, cursor: "pointer" }}>
            {label}
          </button>
        ))}
      </Box>

      {tab === "offline" ? (
        <Box sx={{ px: "18px", pb: "12px" }}>
          <EstateOfflineAnalyses policy={selected} now={now} />
        </Box>
      ) : (<>
      {selectedMeta && (
        <Box sx={{ px: "18px", pb: "10px", display: "flex", gap: "10px", alignItems: "center", flexWrap: "wrap", fontSize: "12.5px", color: ev.ink2 }}>
          <span>Stored policy rules reported: {selectedMeta.ruleCount ?? "UNKNOWN"}</span>
          <EvidenceStamp at={selectedMeta.collectedAt} source="collected" now={now} />
        </Box>
      )}

      {page.state === "loading" && selected ? (
        <Box sx={{ px: "18px", pb: "12px", color: ev.ink2 }}>Loading rules and objects…</Box>
      ) : page.state === "unknown" ? (
        <Box sx={{ px: "18px", pb: "12px", color: ev.ink2 }}>Rules and objects UNKNOWN: this policy could not be read.</Box>
      ) : page.state === "ok" ? (
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, gap: "14px", px: "18px", pb: "12px" }}>
          <Box>
            <Typography sx={{ fontWeight: 650, fontSize: "13px", color: ev.ink }}>
              Rules on this page: {rules.length}{page.value.total !== undefined ? ` · server total ${page.value.total}` : ""}
            </Typography>
            {rules.length === 0 ? (
              <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>No rules match.</Typography>
            ) : (
              <Box component="ul" sx={{ listStyle: "none", p: 0, m: 0, fontFamily: ESTATE_MONO, fontSize: "12px" }}>
                {ruleList.shown.map((r) => (
                  <Box component="li" key={r.id || r.uuid || r.number} sx={{ py: "3px", borderBottom: `1px solid ${ev.line}` }}>
                    {r.number} · {r.name || "(unnamed)"} · {r.action || "action UNKNOWN"}
                  </Box>
                ))}
                {ruleList.moreLabel && <Box component="li" sx={{ py: "3px", color: ev.ink3 }}>{ruleList.moreLabel}</Box>}
              </Box>
            )}
          </Box>
          <Box>
            <Typography sx={{ fontWeight: 650, fontSize: "13px", color: ev.ink }}>Objects on this page: {page.value.objects?.length ?? 0}</Typography>
            {objectList.shown.length === 0 ? (
              <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>No objects match.</Typography>
            ) : (
              <Box component="ul" sx={{ listStyle: "none", p: 0, m: 0, fontFamily: ESTATE_MONO, fontSize: "12px" }}>
                {objectList.shown.map((o) => (
                  <Box component="li" key={o.id} sx={{ py: "3px", borderBottom: `1px solid ${ev.line}` }}>
                    {o.name} · {o.type} · {o.status}
                  </Box>
                ))}
                {objectList.moreLabel && <Box component="li" sx={{ py: "3px", color: ev.ink3 }}>{objectList.moreLabel}</Box>}
              </Box>
            )}
          </Box>
        </Box>
      ) : null}
      </>)}

      <Box sx={{ px: "18px", py: "12px", borderTop: `1px solid ${ev.line}` }}>
        <Typography sx={{ fontWeight: 650, fontSize: "13px", color: ev.ink }}>Hygiene, fleet summary</Typography>
        {summary.state === "unknown" ? (
          <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>{summary.countsLine}</Typography>
        ) : (
          <>
            <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>{summary.countsLine}</Typography>
            {summary.pendingLine && <Typography sx={{ fontSize: "12.5px", color: ev.watch }}>{summary.pendingLine}</Typography>}
            <Box component="ul" sx={{ listStyle: "none", p: 0, m: "6px 0 0", fontFamily: ESTATE_MONO, fontSize: "12px" }}>
              {summary.totals.map((t) => (
                <Box component="li" key={t.finding}>rules with {t.finding}: {t.rules}</Box>
              ))}
            </Box>
            <Box component="ul" sx={{ listStyle: "none", p: 0, m: "8px 0 0", fontSize: "12px" }}>
              {summary.rows.map((r) => (
                <Box component="li" key={r.key} sx={{ py: "3px", borderBottom: `1px solid ${ev.line}`, display: "flex", gap: "8px", flexWrap: "wrap" }}>
                  <span>{r.label}</span>
                  {r.kind === "ready" ? (
                    <span>{r.rules} rules{r.counts.length ? ` · ${r.counts.map((c) => `${c.finding} ${c.rules}`).join(" · ")}` : ""}</span>
                  ) : (
                    <span style={{ color: ev.watch }}>PENDING, no counts</span>
                  )}
                  <EvidenceStamp at={r.snapshotAt} source="snapshot" now={now} />
                </Box>
              ))}
              {summary.moreLabel && <Box component="li" sx={{ py: "3px", color: ev.ink3 }}>{summary.moreLabel}</Box>}
            </Box>
          </>
        )}
      </Box>
    </EstateCard>
  );
}
