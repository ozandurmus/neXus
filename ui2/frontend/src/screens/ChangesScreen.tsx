import { useEffect, useState } from "react";

import { listPolicies, type PolicyMetadata, type PolicyTarget } from "../auth/adminApi";
import { M3Tabs } from "../shell/M3Widgets";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { urlParam, writeUrlParams } from "../shell/urlParams";
import type { ChangesView } from "../shell/legacyRoutes";
import { ChangeFeedPanel } from "./ChangeFeedPanel";
import { PolicyChangesTab } from "./PolicyChangesTab";
import { policyFailureText } from "./PolicyRuleViewer";

/**
 * Changes (UI review 2026-11-05, C3; Fable on section 2.2): "what changed in the last 24 hours" is answered in one place.
 * It merges what used to be Operations > Change feed and Policy > Changes into one rail entry with two views of the same
 * question. **Timeline** is the estate-wide feed (user changes, neXus jobs, device events). **Policy rule revisions** is
 * the stored rule history with its before and after and its CSV export. The view is part of the address
 * (`?screen=changes&view=feed|policy`); the old `?screen=operations&tab=changes` address lands on the timeline and the
 * Policy screen's own Changes tab keeps its policy-scoped link here. Neither view says what caused what.
 */
export const CHANGES_VIEWS = ["feed", "policy"] as const;

export function changesViewFromUrl(value: string | null): ChangesView {
  return value === "policy" ? "policy" : "feed";
}

export function ChangesScreen() {
  const [view, setView] = useState<ChangesView>(() => changesViewFromUrl(urlParam("view")));
  const select = (index: number) => {
    const next = CHANGES_VIEWS[index] ?? "feed";
    setView(next);
    writeUrlParams({ view: next });
  };
  return (
    <ScreenRoot screenType="list">
      <ScreenHeader title="Changes"
        subtitle="Who changed what, and when: user changes, neXus jobs and device events in one timeline, and the stored policy rule revisions with their before and after." />
      <M3Tabs ariaLabel="Changes views" value={CHANGES_VIEWS.indexOf(view)} onChange={select}
        tabs={[
          { label: "Timeline", panel: <ChangeFeedPanel initialDevice={urlParam("device_id") ?? ""} initialVendor={urlParam("vendor") ?? ""} /> },
          { label: "Policy rule revisions", panel: <PolicyRevisions /> },
        ]} />
    </ScreenRoot>
  );
}

/** The stored rule history needs the policy catalog and the gateways, which the Policy screen used to pass down. */
function PolicyRevisions() {
  const [catalog, setCatalog] = useState<{ policies: PolicyMetadata[]; devices: PolicyTarget[] } | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  useEffect(() => {
    let active = true;
    listPolicies()
      .then((result) => { if (active) setCatalog({ policies: result.policies ?? [], devices: result.devices ?? [] }); })
      .catch((error) => { if (active) setFailure(error); });
    return () => { active = false; };
  }, []);
  if (failure) {
    return isRestricted(failure)
      ? <RestrictedPanel area="Policy rule revisions" />
      : <StatePanel variant="error" title="Stored policies could not be read" body={policyFailureText(failure, "The policy catalog could not be loaded.")} />;
  }
  if (!catalog) return <StatePanel variant="empty" title="Reading the stored policies…" announce="status" />;
  return (
    <PolicyChangesTab policies={catalog.policies} devices={catalog.devices}
      openRule={(policy, rule) => window.location.assign(`?screen=policy&policy_id=${encodeURIComponent(policy)}&rule=${encodeURIComponent(rule)}`)} />
  );
}
