import { useEffect, useState } from "react";

import { listPolicies, type PolicyMetadata } from "../auth/adminApi";
import { M3Tabs } from "../shell/M3Widgets";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { urlParam, writeUrlParams } from "../shell/urlParams";
import { PathAnalysisPanel } from "./PathAnalysisScreen";
import { PolicyLookupTab } from "./PolicyLookupTab";
import { policyFailureText } from "./PolicyRuleViewer";

/**
 * Trace (UI review 2026-11-05, C3; Argon R-20, Fable on section 2.2): "can A reach B" has two honest answers and one
 * screen now asks both. **Topology path** is the existing Path analysis (hop by hop across the held topology);
 * **Policy rule lookup** is the existing Policy > Lookup (which rule of one stored policy decides the flow). The mode is
 * part of the address (`?screen=trace&mode=path|lookup`), so either answer is a shareable link, and the old
 * `?screen=path_analysis` and `?screen=policy&tab=lookup` addresses land here (see `legacyRoutes`). Advanced fields of
 * both forms are collapsed. Nothing is sent to a device in either mode.
 */
export const TRACE_MODES = ["path", "lookup"] as const;
export type TraceMode = (typeof TRACE_MODES)[number];

export function traceModeFromUrl(value: string | null): TraceMode {
  return value === "lookup" ? "lookup" : "path";
}

export function TraceScreen() {
  const [mode, setMode] = useState<TraceMode>(() => traceModeFromUrl(urlParam("mode")));
  const select = (index: number) => {
    const next = TRACE_MODES[index] ?? "path";
    setMode(next);
    writeUrlParams({ mode: next });
  };
  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Trace"
        subtitle="Follow one flow: the topology path hop by hop, or the rule of one stored policy that decides it. Management intent from stored evidence, never observed traffic; nothing is sent to a device." />
      <M3Tabs ariaLabel="Trace modes" keepMounted value={TRACE_MODES.indexOf(mode)} onChange={select}
        tabs={[
          { label: "Topology path", panel: <PathAnalysisPanel /> },
          { label: "Policy rule lookup", panel: <PolicyLookupMode /> },
        ]} />
    </ScreenRoot>
  );
}

/** Policy > Lookup as a Trace mode: the stored snapshots are read here, the form is the unchanged lookup form. */
function PolicyLookupMode() {
  const [catalog, setCatalog] = useState<PolicyMetadata[] | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [policy, setPolicy] = useState(() => urlParam("policy_id") ?? "");
  useEffect(() => {
    let active = true;
    listPolicies()
      .then((result) => { if (active) setCatalog(result.policies ?? []); })
      .catch((error) => { if (active) { setFailure(error); setCatalog([]); } });
    return () => { active = false; };
  }, []);
  if (catalog === null) return <StatePanel variant="empty" title="Reading the stored policies…" announce="status" />;
  if (failure) {
    return isRestricted(failure)
      ? <RestrictedPanel area="Policy rule lookup" />
      : <StatePanel variant="error" title="Stored policies could not be read" body={policyFailureText(failure, "The policy catalog could not be loaded.")} />;
  }
  if (catalog.length === 0) {
    return <StatePanel variant="empty" title="No stored policy to look up in"
      body="A lookup reads one collected policy snapshot. None is stored yet; collect a management source from Policy first." />;
  }
  return (
    <PolicyLookupTab policies={catalog} policy={policy}
      selectPolicy={(id) => { setPolicy(id); writeUrlParams({ policy_id: id }); }}
      openRule={(policyId, ruleId) => window.location.assign(`?screen=policy&policy_id=${encodeURIComponent(policyId)}&rule=${encodeURIComponent(ruleId)}`)} />
  );
}
