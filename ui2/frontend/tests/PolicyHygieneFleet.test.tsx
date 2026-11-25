import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyHygieneFleet, reasonList } from "../src/screens/PolicyHygieneFleet";
import type { PolicyHygieneSummaryWithHa } from "../src/screens/PolicyHygieneSummaryTable";
import type { PolicyHygieneSummary } from "../src/auth/adminApi";
import type { ContainerSummary } from "../src/screens/PolicyScreen";

const now = new Date("2026-11-23T10:00:00Z");
const rows = [{ sourceId: "s1", sourceName: "MGR-BRAVO-01", vendor: "PAN", containerId: "c1", containerName: "DOM-TANGO-01", collectedAt: "2026-11-23T08:00:00Z", policyIds: ["p1"], gateways: ["FW-TANGO-04"] }] as unknown as ContainerSummary[];
const counts = { shadowed: 0, conflict: 0, disabled: 1, unused: 0, expired: 0, broad: 0, unknown: 7 };
const base: PolicyHygieneSummary = { containers: [{ sourceId: "s1", sourceName: "MGR-BRAVO-01", vendor: "PAN", containerId: "c1", containerName: "DOM-TANGO-01", snapshotAt: "2026-11-23T08:00:00Z", state: "READY", rules: 9, counts }],
  totals: counts, rules: 9, pending: 0, days: 90 };
function mount(summary: PolicyHygieneSummaryWithHa) {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(summary), { status: 200 })));
  return render(<ThemeProvider theme={m3Theme}><PolicyHygieneFleet rows={rows} now={now} /></ThemeProvider>);
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("lists the top five UNKNOWN reasons with rule counts, '+N more', and the top three blocker reasons", async () => {
  mount({ ...base,
    unknownReasons: { "zone or install-on coverage is unproven": 40, "earlier rule has unsupported constraints": 30, "negated selector": 20, "analysis budget": 10, "missing selector": 5, "unresolved object": 1, "zone evidence not collected": 1 },
    blockerReasons: { "negated selector": 12, "unresolved object": 3, "missing selector": 2, "analysis budget": 1 } });
  const box = await screen.findByLabelText("Why UNKNOWN");
  expect(box).toHaveTextContent("zone or install-on coverage is unproven (40 rules)");
  expect(box).toHaveTextContent("missing selector (5 rules)");
  expect(box).toHaveTextContent("+2 more");
  expect(box).not.toHaveTextContent("zone evidence not collected");
  expect(box).toHaveTextContent("Behind an earlier rule that is itself UNKNOWN for: negated selector (12 rules); unresolved object (3 rules); missing selector (2 rules); +1 more");
  expect(box).not.toHaveTextContent("analysis budget (1 rule)");
});
it("uses the singular for one rule and omits the blocker line when no blocker is named", async () => {
  mount({ ...base, unknownReasons: { "negated selector": 1 } });
  const box = await screen.findByLabelText("Why UNKNOWN");
  expect(box).toHaveTextContent("negated selector (1 rule)");
  expect(box).not.toHaveTextContent("Behind an earlier rule");
  expect(box).not.toHaveTextContent("more");
});
it("renders nothing when the service sent no maps or no rule is UNKNOWN", async () => {
  const first = mount(base);
  await screen.findByText("Findings");
  await waitFor(() => expect(screen.getByLabelText("Hygiene fleet summary")).toHaveTextContent("UNKNOWN counted separately"));
  expect(screen.queryByLabelText("Why UNKNOWN")).toBeNull();
  expect(screen.getByLabelText("Hygiene fleet summary")).not.toHaveTextContent("0 rules");
  first.unmount();
  const zero = { ...counts, unknown: 0 };
  mount({ ...base, totals: zero, unknownReasons: { "negated selector": 4 }, blockerReasons: { "negated selector": 2 } });
  await screen.findByText("Findings");
  await waitFor(() => expect(screen.getByLabelText("Hygiene fleet summary")).toHaveTextContent("rule findings across"));
  expect(screen.queryByLabelText("Why UNKNOWN")).toBeNull();
});
it("reasonList ranks by count then name and ignores empty or invalid counts", () => {
  expect(reasonList(undefined, 3)).toEqual({ shown: [], more: 0 });
  expect(reasonList({ b: 2, a: 2, c: 0, d: Number.NaN, e: 5 }, 2)).toEqual({ shown: ["e (5 rules)", "a (2 rules)"], more: 1 });
});
it("renders HA_POLICY_DIVERGENT warning, counted-once summary note, and per-row HA pair badges", async () => {
  mount({
    containers: [
      { sourceId: "dev-tango-04", sourceName: "FW-TANGO-04", vendor: "ASA", containerId: "c-asa-1", containerName: "single", snapshotAt: "2026-11-23T08:00:00Z", state: "READY", rules: 12, counts, pairRef: "00000000-0000-3000-8000-000000000001", haContext: "single", haOutcome: "SAME", haReason: null },
      { sourceId: "dev-romeo-01", sourceName: "FW-ROMEO-01", vendor: "FGT", containerId: "c-fgt-1", containerName: "DOM-ROMEO-01", snapshotAt: "2026-11-23T08:00:00Z", state: "READY", rules: 5, counts, pairRef: "00000000-0000-3000-8000-000000000002", haContext: "DOM-ROMEO-01", haOutcome: "HA_POLICY_DIVERGENT", haReason: null },
      { sourceId: "dev-romeo-02", sourceName: "FW-ROMEO-02", vendor: "FGT", containerId: "c-fgt-2", containerName: "DOM-ROMEO-01", snapshotAt: "2026-11-23T08:00:00Z", state: "READY", rules: 6, counts, pairRef: "00000000-0000-3000-8000-000000000002", haContext: "DOM-ROMEO-01", haOutcome: "HA_POLICY_DIVERGENT", haReason: null },
      { sourceId: "dev-bravo-01", sourceName: "FW-BRAVO-01", vendor: "ASA", containerId: "c-asa-3", containerName: "single", snapshotAt: "2026-11-23T08:00:00Z", state: "READY", rules: 4, counts, pairRef: "00000000-0000-3000-8000-000000000003", haContext: "single", haOutcome: "UNKNOWN", haReason: "PAIR_IDENTITY_NOT_MATCH" },
    ],
    totals: { ...counts, disabled: 4, unknown: 28 },
    rules: 27,
    pending: 0,
    days: 90,
    haPairsCountedOnce: 1,
    haPairsDivergent: 1,
    haPairsUnknown: 1,
  });
  const section = await screen.findByLabelText("Hygiene fleet summary");
  await waitFor(() => expect(section).toHaveTextContent("1 HA pair context flagged HA_POLICY_DIVERGENT: member policies differ, so both members are counted separately and never merged."));
  expect(section).toHaveTextContent("Identical HA pairs (1 pair) are counted once for policy-content findings; unused and hit-counter UNKNOWN findings are member-specific and counted per member.");
  expect(section).toHaveTextContent("(1 identical HA pair counted once)");
  expect(screen.getByText("HA pair · content counted once, hit counters per member")).toBeInTheDocument();
  expect(screen.getAllByText("HA_POLICY_DIVERGENT · members differ")).toHaveLength(2);
  expect(screen.getByText("HA pair · comparison UNKNOWN (PAIR_IDENTITY_NOT_MATCH)")).toBeInTheDocument();
});
it("omits HA pair warnings and row badges when HA fields are absent", async () => {
  mount(base);
  const section = await screen.findByLabelText("Hygiene fleet summary");
  await waitFor(() => expect(section).toHaveTextContent("rule findings across 1 analysed container"));
  expect(section).not.toHaveTextContent("HA_POLICY_DIVERGENT");
  expect(section).not.toHaveTextContent("HA pair · content counted once, hit counters per member");
  expect(section).not.toHaveTextContent("comparison UNKNOWN");
  expect(section).not.toHaveTextContent("Identical HA pairs");
});
