import { useEffect, useState } from "react";
import MenuItem from "@mui/material/MenuItem";
import TextField from "@mui/material/TextField";

import { listPolicies, type PolicyMetadata } from "../auth/adminApi";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { urlParam, writeUrlParams } from "../shell/urlParams";
import { PolicyOfflineTab } from "./PolicyOfflineTab";
import { policyFailureText } from "./PolicyRuleViewer";

/**
 * Offline analysis under Tools (UI review 2026-11-05, C4; Argon R-21): a SIEM export checked against one stored policy
 * is a file-in, report-out tool like Packets and the Sanitizer, not a tab of Policy. The upload, column mapping and
 * result view are the unchanged `PolicyOfflineTab`; what moved is the policy choice, which used to be whatever policy
 * the Policy screen had open and is now named here. `?screen=policy&tab=offline` lands on this screen.
 */
export function OfflineAnalysisScreen() {
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
  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Offline analysis"
        subtitle="Check a SIEM export against one stored policy: which rules the logged traffic matched, which it did not, and which it could not be decided against." />
      {catalog === null ? <StatePanel variant="empty" title="Reading the stored policies…" announce="status" />
        : failure ? (isRestricted(failure)
          ? <RestrictedPanel area="Offline analysis" />
          : <StatePanel variant="error" title="Stored policies could not be read" body={policyFailureText(failure, "The policy catalog could not be loaded.")} />)
        : catalog.length === 0 ? <StatePanel variant="empty" title="No stored policy to analyse against"
            body="An offline analysis reads one collected policy snapshot. None is stored yet; collect a management source from Policy first." />
        : <>
          <TextField select size="small" label="Policy snapshot" value={catalog.some((p) => p.id === policy) ? policy : ""} sx={{ maxWidth: 420 }}
            helperText="The stored snapshot the export is checked against"
            onChange={(e) => { setPolicy(e.target.value); writeUrlParams({ policy_id: e.target.value }); }}>
            {catalog.map((p) => <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>)}
          </TextField>
          <PolicyOfflineTab key={policy} policy={policy}
            openRule={(ruleId) => window.location.assign(`?screen=policy&policy_id=${encodeURIComponent(policy)}&rule=${encodeURIComponent(ruleId)}`)} />
        </>}
    </ScreenRoot>
  );
}
