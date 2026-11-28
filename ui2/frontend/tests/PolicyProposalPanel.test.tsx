import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyProposalPanel } from "../src/screens/PolicyProposalPanel";
import { CHANGE_LIST_NOTICE, loadChangeList } from "../src/screens/policyInsightActions";
import type { PolicyProposalResponse } from "../src/auth/adminApi";

const ref = (id: string, number: number, name: string) => ({ ruleId: id, ruleName: name, ruleNumber: number });
const response: PolicyProposalResponse = { vendor: "CP", supported: true, days: 90, collectedAt: "2026-11-23T08:00:00Z", complete: true, packageVerdict: "EQUIVALENT",
  label: "no behaviour change", accepted: 1, refused: { ANCHOR_BLOCKED: 1 }, inspectionChanges: 0, budgetStopped: false, emptyReason: null, maxSubjects: 50, candidatesTotal: 2, tried: 2,
  notTried: 0, truncated: false, notTriedNote: null,
  steps: [{ subject: ref("r3", 3, "RULE-ALPHA-03"), above: ref("r1", 1, "RULE-ALPHA-01"), result: "SAFE", accepted: true, equivalence: "EQUIVALENT", crossedRules: 2, inspectionChanges: false },
    { subject: ref("r4", 4, "RULE-ALPHA-04"), above: ref("r2", 2, "RULE-ALPHA-02"), result: "ANCHOR_BLOCKED", accepted: false, equivalence: null, crossedRules: 0, inspectionChanges: false }],
  order: [{ sectionId: "s1", offset: 0, totalRules: 4, before: ["r1", "r2", "r3"], after: ["r3", "r1", "r2"], moreRules: 1, more: "+1 more" }],
  caveat: "Moves are stacked in the order shown.", insightCaveats: ["INSTALL_STATE_NOT_VERIFIED"] };

function mount(body: unknown, status = 200) {
  const fetchMock = vi.fn(async () => new Response(JSON.stringify(body), { status }));
  vi.stubGlobal("fetch", fetchMock);
  render(<ThemeProvider theme={m3Theme}><PolicyProposalPanel policy="p1" /></ThemeProvider>);
  return fetchMock;
}
const open = () => { fireEvent.click(screen.getByRole("button", { name: "Reorder proposal" })); fireEvent.click(screen.getByRole("button", { name: "Show reorder proposal" })); };
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); window.localStorage.clear(); });

it("is collapsed and makes no request until the click", async () => {
  const fetchMock = mount(response);
  expect(screen.queryByRole("button", { name: "Show reorder proposal" })).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Reorder proposal" }));
  expect(fetchMock).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Show reorder proposal" }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  expect(String((fetchMock.mock.calls[0] as unknown[])[0])).toContain("/policies/p1/proposal?days=90");
  await waitFor(() => expect(document.querySelector("[data-proposal-headline]")).toHaveTextContent("No behaviour change"));
});
it("says unsupported for a non Check Point package", async () => {
  mount({ vendor: "PA", supported: false }); open();
  expect(await screen.findByText("The reorder proposal is available for Check Point packages only.")).toBeInTheDocument();
});
it("shows the failure, not an empty or no-moves result", async () => {
  mount({ error: "boom" }, 500); open();
  expect(await screen.findByRole("alert")).toHaveTextContent("could not be loaded");
  expect(screen.queryByText(/no move was proposed/)).toBeNull();
});
it("prints +N more not tried for a truncated run and the order view", async () => {
  mount({ ...response, truncated: true, notTried: 3, notTriedNote: "+3 more not tried", candidatesTotal: 5 }); open();
  expect(await screen.findByText(/2 of 5 candidate rules tried; 1 moves accepted; 1 refused · \+3 more not tried/)).toBeInTheDocument();
  expect(screen.getByText("+1 more")).toBeInTheDocument();
  expect(screen.getByText(/rule 3 RULE-ALPHA-03 above rule 1 RULE-ALPHA-01/)).toBeInTheDocument();
});
it("never claims no behaviour change for a NOT_PROVEN run", async () => {
  mount({ ...response, packageVerdict: "NOT_PROVEN", label: null, budgetStopped: true, accepted: 0,
    steps: [{ ...response.steps![0], result: "NOT_PROVEN", accepted: false, equivalence: "NOT_PROVEN", crossedRules: 0 }] }); open();
  expect(await screen.findByText("Not proven (work budget)")).toBeInTheDocument();
  expect(screen.queryByText("No behaviour change")).toBeNull();
});
it("adds the proposal to the change list once and shows the notice", async () => {
  mount(response); open();
  const add = await screen.findByRole("button", { name: "Add the proposal to the change list" });
  fireEvent.click(add); fireEvent.click(add);
  expect(await screen.findByText(CHANGE_LIST_NOTICE)).toBeInTheDocument();
  const items = loadChangeList("p1", false);
  expect(items).toHaveLength(1);
  expect(items[0].kind).toBe("REORDER_RULE");
});
it("disables the change list button when nothing was accepted", async () => {
  mount({ ...response, accepted: 0, steps: [response.steps![1]] }); open();
  expect(await screen.findByRole("button", { name: "Add the proposal to the change list" })).toBeDisabled();
});
it("renders without the optional merge fields and with them", async () => {
  mount(response); open();
  await waitFor(() => expect(document.querySelector("[data-proposal-headline]")).toHaveTextContent("No behaviour change"));
  expect(screen.queryByLabelText("Proposed merges")).toBeNull();
});
it("renders a merge row with the struck merged rule", async () => {
  mount({ ...response, mergesAccepted: 1, merges: [{ surviving: ref("r1", 1, "RULE-ALPHA-01"), merged: ref("r2", 2, "RULE-ALPHA-02"), differingCell: "source", accepted: true, equivalence: "EQUIVALENT" }],
    order: [{ sectionId: "s1", offset: 0, totalRules: 2, before: ["r1", "r2"], after: ["r1"], moreRules: 0 }] }); open();
  expect(await screen.findByText("Merge rule 2 RULE-ALPHA-02 into rule 1 RULE-ALPHA-01 (differs only in Source)")).toBeInTheDocument();
  expect(document.querySelector("[data-struck]")).not.toBeNull();
});
