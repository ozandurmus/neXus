import { useEffect, useState } from "react";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";

import { listPolicies, type PolicyMetadata } from "../auth/adminApi";
import { ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { urlParam, writeUrlParams } from "../shell/urlParams";
import { PolicyOfflineTab, type RuleUsageMode } from "./PolicyOfflineTab";
import { policyFailureText } from "./PolicyRuleViewer";

/** `?mode=rule|policy`; anything else is the default, One rule. */
export const ruleUsageModeFromUrl = (value: string | null): RuleUsageMode => (value === "policy" ? "policy" : "rule");

/**
 * Rule object usage (RULE_OBJECT_USAGE_CONTRACT.md, PO 2026-11-13), under Policy and changes. One uploaded log export is
 * checked against one stored policy in one of two modes: **One rule** (the default) answers which of that rule's objects
 * carried traffic and which never did; **Whole policy** is the former Tools > Offline analysis, unchanged. The mode and
 * the policy are part of the address, so either view is a shareable link; the old Offline analysis addresses land here in
 * Whole policy mode (`legacyRoutes`).
 */
export function RuleUsageScreen() {
  const [catalog, setCatalog] = useState<PolicyMetadata[] | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [policy, setPolicy] = useState(() => urlParam("policy_id") ?? "");
  const [mode, setMode] = useState<RuleUsageMode>(() => ruleUsageModeFromUrl(urlParam("mode")));
  useEffect(() => {
    let active = true;
    listPolicies()
      .then((result) => { if (active) setCatalog(result.policies ?? []); })
      .catch((error) => { if (active) { setFailure(error); setCatalog([]); } });
    return () => { active = false; };
  }, []);
  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Rule object usage"
        subtitle="Check a log export against one stored policy: which of a rule's objects carried traffic in that period, and which never did." />
      {catalog === null ? <StatePanel variant="empty" title="Reading the stored policies…" announce="status" />
        : failure ? (isRestricted(failure)
          ? <RestrictedPanel area="Rule object usage" />
          : <StatePanel variant="error" title="Stored policies could not be read" body={policyFailureText(failure, "The policy catalog could not be loaded.")} />)
        : catalog.length === 0 ? <StatePanel variant="empty" title="No stored policy to analyse against"
            body="Rule object usage reads one collected policy snapshot. None is stored yet; collect a management source from Policy first." />
        : <>
          <Stack direction="row" gap={2} flexWrap="wrap" alignItems="flex-start">
            <ToggleButtonGroup exclusive size="small" value={mode} aria-label="Analysis mode"
              onChange={(_e, value: RuleUsageMode | null) => { if (value) { setMode(value); writeUrlParams({ mode: value }); } }}>
              <ToggleButton value="rule">One rule</ToggleButton>
              <ToggleButton value="policy">Whole policy</ToggleButton>
            </ToggleButtonGroup>
            <TextField select size="small" label="Policy snapshot" value={catalog.some((p) => p.id === policy) ? policy : ""} sx={{ minWidth: 280, maxWidth: 420, flex: 1 }}
              helperText="The stored snapshot the export is checked against"
              onChange={(e) => { setPolicy(e.target.value); writeUrlParams({ policy_id: e.target.value }); }}>
              {catalog.map((p) => <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>)}
            </TextField>
          </Stack>
          <PolicyOfflineTab key={policy} policy={policy} mode={mode}
            openRule={(ruleId) => window.location.assign(`?screen=policy&policy_id=${encodeURIComponent(policy)}&rule=${encodeURIComponent(ruleId)}`)} />
        </>}
    </ScreenRoot>
  );
}
