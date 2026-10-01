import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyScreen } from "../src/screens/PolicyScreen";
import type { PolicyMetadata, PolicyPage, PolicyRule } from "../src/auth/adminApi";

const metadata: PolicyMetadata = { id: "policy-1", sourceId: "source-1", sourceName: "MGR-BRAVO-01", vendor: "PAN", containerId: "container-1",
  containerName: "DOM-TANGO-01", name: "OBJ-POLICY-01", collectedAt: "2026-10-01T12:00:00Z", artefactRef: "artifact-1",
  targets: [{ deviceId: "device-1", name: "FW-TANGO-04", context: "VS-ROMEO-01", syncStatus: "UNKNOWN" }] };
const rule: PolicyRule = { id: "rule-1", uuid: "uuid-1", number: 1, name: "OBJ-RULE-01", enabled: false,
  source: { refs: ["object-1"], negated: true }, destination: { refs: ["any-1"], negated: false }, service: { refs: ["any-1"], negated: false },
  application: { refs: [], negated: false }, action: "allow", log: "UNKNOWN", comment: "Withheld in AIView", extras: { withheld: ["Withheld in AIView"] } };
const page: PolicyPage = { metadata, sections: [{ id: "section-1", name: "OBJ-SECTION-01", source: "Shared", parentRuleId: null, rules: [rule], total: 1 }],
  objects: [{ id: "object-1", name: "OBJ-GROUP-01", type: "address-group", status: "RESOLVED" }, { id: "any-1", name: "ANY", type: "any", status: "RESOLVED" }], page: 0, pageSize: 200, total: 1 };
function mockFetch(result: PolicyPage = page) {
  const fetch = vi.fn(async (input: string) => {
    const body = input === "/api/v2/policy/devices" ? { policies: [metadata], devices: metadata.targets }
      : input.includes("/objects/") ? { object: { ...page.objects[0], children: [
        { id: "leaf-1", name: "OBJ-ADDRESS-01", type: "address", status: "RESOLVED", values: ["192.0.2.8"] },
        { id: "cycle-1", name: "OBJ-GROUP-01", type: "address-group", status: "CYCLE" },
      ] } } : result;
    return new Response(JSON.stringify(body), { status: 200, headers: { "X-Nexus-Masked": "true" } });
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}
function mount() { return render(<ThemeProvider theme={m3Theme}><PolicyScreen /></ThemeProvider>); }
afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

it("renders masked management navigation, assignments, section collapse, negation and disabled rules", async () => {
  mockFetch(); mount();
  const table = await screen.findByRole("table", { name: "Policy rulebase" });
  expect(screen.getByText("MGR-BRAVO-01")).toBeInTheDocument();
  expect(screen.getByText(/Assigned to: FW-TANGO-04/)).toBeInTheDocument();
  expect(within(table).getByText("NOT")).toBeInTheDocument();
  expect(within(table).getByText("Disabled")).toBeInTheDocument();
  expect(within(table).getAllByText("ANY")).toHaveLength(2);
  const section = within(table).getByRole("button", { name: /OBJ-SECTION-01/ });
  fireEvent.click(section);
  expect(within(table).queryByText("OBJ-RULE-01")).toBeNull();
  fireEvent.click(section);
  expect(within(table).getByText("OBJ-RULE-01")).toBeInTheDocument();
});
it("opens recursive object members with cycle status and complete rule details", async () => {
  mockFetch(); mount();
  fireEvent.click(await screen.findByText("OBJ-GROUP-01"));
  let drawer = await screen.findByRole("dialog", { name: "Object details" });
  await within(drawer).findByText("192.0.2.8");
  expect(within(drawer).getAllByText(/CYCLE/).length).toBeGreaterThan(0);
  fireEvent.click(within(drawer).getByText("Close"));
  fireEvent.click(screen.getByText("OBJ-RULE-01"));
  drawer = screen.getByRole("dialog", { name: "Rule details" });
  expect(within(drawer).getByText("UUID: uuid-1")).toBeInTheDocument();
  expect(within(drawer).getByText("Enabled: false")).toBeInTheDocument();
});
it("renders only a 200-rule page from a 5000-rule policy and requests the next page", async () => {
  const large = { ...page, total: 5000, sections: [{ ...page.sections[0], total: 5000,
    rules: Array.from({ length: 200 }, (_, i) => ({ ...rule, id: `rule-${i}`, number: i + 1, name: `OBJ-RULE-${i}` })) }] };
  const fetch = mockFetch(large); mount();
  const table = await screen.findByRole("table");
  expect(within(table).getAllByRole("row")).toHaveLength(202);
  expect(screen.getByText("Page 1 of 25 · 5000 rules")).toBeInTheDocument();
  fireEvent.click(screen.getByText("Next"));
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("page=1"))).toBe(true));
});
it("sends only the name/comment search and resets pagination", async () => {
  const fetch = mockFetch(); mount();
  fireEvent.change(await screen.findByLabelText("Search rule names and comments"), { target: { value: "OBJ-RULE" } });
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("page=0&q=OBJ-RULE"))).toBe(true));
});
it("keeps an unassigned policy navigable and a device jump scoped to its assignments", async () => {
  window.history.replaceState(null, "", "?screen=policy&device_id=missing-device");
  mockFetch(); mount();
  expect(await screen.findByText("No assigned policy snapshot")).toBeInTheDocument();
  expect(screen.queryByRole("table")).toBeNull();
});
it("shows empty and failed reads distinctly", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ policies: [], devices: [] }), { status: 200 })));
  const view = mount();
  expect(await screen.findByText("No policy snapshot")).toBeInTheDocument(); view.unmount();
  vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", { status: 500 })));
  mount(); expect(await screen.findByText("Policy unavailable")).toBeInTheDocument();
  expect(screen.queryByText("No policy snapshot")).toBeNull();
});
