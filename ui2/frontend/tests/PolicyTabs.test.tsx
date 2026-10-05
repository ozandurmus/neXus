import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyScreen } from "../src/screens/PolicyScreen";
import { PolicyDomainTab } from "../src/screens/PolicyDomainTabs";
import type { PolicyMetadata } from "../src/auth/adminApi";

const metadata: PolicyMetadata = { id: "policy-1", sourceId: "source-1", sourceName: "MGR-BRAVO-01", vendor: "CP",
  containerId: "domain-1", containerName: "DOM-TANGO-01", name: "POL-TANGO-01", collectedAt: "2026-10-05T00:00:00Z", artefactRef: "artifact-1", targets: [] };
const objects = [{ id: "object-1", uid: "uid-1", name: "ADDR-TANGO-01", type: "host", status: "RESOLVED", values: ["ipv4-address: 240.12.0.8"],
  members: [], unused: true, emptyGroup: false, singleMember: false, duplicateId: "duplicate-1", ruleCount: 1, groupCount: 1 }];
const types = [{ type: "hosts", status: "RESOLVED", collectedAt: metadata.collectedAt, objects: 1 }];
const objectPage = { objects, types, total: 201, page: 0, pageSize: 200 };
const installationPage = { installations: [{ id: "gateway-1", policyId: metadata.id, policyName: metadata.name,
  name: "FW-TANGO-04", deviceId: "device-1", allTargets: true, targeted: true, installed: null }], types, total: 1, page: 0, pageSize: 200 };
function mount(node: React.ReactNode) { return render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>); }
function mock(result: unknown = objectPage) {
  const request = vi.fn(async (url: string) => new Response(JSON.stringify(result), { status: 200, headers: { "X-Nexus-Masked": "true" } }));
  vi.stubGlobal("fetch", request); return request;
}
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it("opens all three tabs, retains Rules and sends hit filters alongside the existing query", async () => {
  const request = vi.fn(async (url: string) => {
    const body = url.endsWith("/sources") ? { sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName, vendor: "CP" }], canCollect: false }
      : url.endsWith("/devices") ? { policies: [metadata], devices: [] }
      : url.includes("/domains/") ? url.includes("/installation?") ? installationPage : objectPage
      : { metadata, sections: [{ id: "layer-1", name: "LAYER-TANGO-01", parentRuleId: null, source: "CP access layer", total: 0, rules: [] }], objects: [], page: 0, pageSize: 200, total: 0 };
    return new Response(JSON.stringify(body), { status: 200, headers: { "X-Nexus-Masked": "true" } });
  });
  vi.stubGlobal("fetch", request); mount(<PolicyScreen />);
  fireEvent.click(await screen.findByRole("button", { name: metadata.sourceName }));
  fireEvent.click(screen.getByRole("button", { name: metadata.containerName }));
  fireEvent.click(screen.getByRole("button", { name: `Policy ${metadata.name}` }));
  await screen.findByRole("region", { name: "Policy rulebase" });
  expect(screen.getByRole("tab", { name: "Rules" })).toHaveAttribute("aria-selected", "true");
  fireEvent.mouseDown(screen.getByLabelText("Hit filter")); fireEvent.click(await screen.findByRole("option", { name: "Never hit" }));
  await waitFor(() => expect(request.mock.calls.some(([url]) => url.includes("hitFilter=never"))).toBe(true));
  fireEvent.mouseDown(screen.getByLabelText("Hit filter")); fireEvent.click(await screen.findByRole("option", { name: "No hits in N days" }));
  fireEvent.change(screen.getByLabelText("Days without hits"), { target: { value: "30" } });
  fireEvent.change(screen.getByLabelText("Rule query"), { target: { value: "action='Accept'" } });
  await waitFor(() => expect(request.mock.calls.some(([url]) => url.includes("hitFilter=inactive&days=30") && url.includes("q=action%3D"))).toBe(true));
  fireEvent.click(screen.getByRole("tab", { name: "Objects" }));
  expect(await screen.findByRole("table", { name: "Policy objects" })).toHaveTextContent("240.12.0.8");
  fireEvent.click(screen.getByRole("tab", { name: "Installation" }));
  expect(await screen.findByRole("table", { name: "Policy installation" })).toHaveTextContent("FW-TANGO-04");
  fireEvent.click(screen.getByRole("tab", { name: "Rules" }));
  expect(screen.getByLabelText("Rule query")).toHaveValue("action='Accept'");
  expect(screen.getByRole("region", { name: "Policy rulebase" })).toBeVisible();
});

it("loads and pages masked objects and sends type, hygiene and search to the server", async () => {
  const request = mock(); mount(<PolicyDomainTab tab="Objects" metadata={metadata} policies={[metadata]} />);
  expect(screen.getByRole("status")).toHaveTextContent("Loading objects");
  const table = await screen.findByRole("table", { name: "Policy objects" });
  expect(within(table).getByText("Unused")).toBeInTheDocument(); expect(within(table).getByText("Duplicate set 1")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Next" }));
  await waitFor(() => expect(request.mock.calls.at(-1)?.[0]).toContain("page=1"));
  fireEvent.mouseDown(screen.getByLabelText("Object type")); fireEvent.click(await screen.findByRole("option", { name: "host" }));
  fireEvent.mouseDown(screen.getByLabelText("Hygiene")); fireEvent.click(await screen.findByRole("option", { name: "Unused" }));
  fireEvent.change(screen.getByLabelText("Search objects"), { target: { value: "ADDR-TANGO" } });
  await waitFor(() => expect(request.mock.calls.at(-1)?.[0]).toContain("page=0&q=ADDR-TANGO&type=host&hygiene=unused"));
});

it("shows where-used rule locations and group references in a masked, paged drawer", async () => {
  const request = vi.fn(async (url: string) => new Response(JSON.stringify(url.includes("/usage?") ? {
    object: objects[0], rules: [{ policyId: "policy-1", policyName: "POL-TANGO-01", layerRef: "layer-1", layerName: "LAYER-TANGO-01", ruleId: "rule-1", number: 7 }],
    groups: [{ id: "group-1", uid: "group-uid", name: "GRP-TANGO-01", type: "group" }], ruleCount: 201, groupCount: 1, page: 0, pageSize: 200,
  } : objectPage), { status: 200, headers: { "X-Nexus-Masked": "true" } }));
  vi.stubGlobal("fetch", request); mount(<PolicyDomainTab tab="Objects" metadata={metadata} policies={[metadata]} />);
  fireEvent.click(await screen.findByRole("button", { name: "Where used: ADDR-TANGO-01" }));
  const drawer = await screen.findByRole("dialog", { name: "Object usage" });
  expect(await within(drawer).findByRole("table", { name: "Rules using object" })).toHaveTextContent("LAYER-TANGO-01");
  expect(within(drawer).getByText("7")).toBeInTheDocument(); expect(within(drawer).getByText("GRP-TANGO-01")).toBeInTheDocument();
  fireEvent.click(within(drawer).getByRole("button", { name: "Next" }));
  await waitFor(() => expect(request.mock.calls.at(-1)?.[0]).toContain("objects/uid-1/usage?source=source-1&page=1"));
});

it.each(["Objects", "Installation"] as const)("shows %s empty and incomplete evidence instead of invented results", async tab => {
  mock({ ...(tab === "Objects" ? { objects: [] } : { installations: [] }), total: 0, page: 0, pageSize: 200,
    types: [{ ...types[0], type: "unused-objects", status: "COLLECTION_FAILED" }] });
  mount(<PolicyDomainTab tab={tab} metadata={metadata} policies={[metadata]} />);
  expect(screen.getByRole("status")).toHaveTextContent(`Loading ${tab.toLowerCase()}`);
  expect(await screen.findByText(`No matching ${tab.toLowerCase()}.`)).toBeInTheDocument();
  expect(screen.getByText("unused-objects: COLLECTION_FAILED")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
});

it("keeps ALL intent distinct from unknown installation and links only matched inventory IDs", async () => {
  const request = mock(installationPage); mount(<PolicyDomainTab tab="Installation" metadata={metadata} policies={[metadata]} />);
  const table = await screen.findByRole("table", { name: "Policy installation" });
  expect(within(table).getByRole("link", { name: "FW-TANGO-04" })).toHaveAttribute("href", "/?screen=inventory&device_id=device-1");
  expect(within(table).getByText("ALL")).toBeInTheDocument(); expect(within(table).getByText("UNKNOWN")).toBeInTheDocument();
  fireEvent.mouseDown(screen.getByLabelText("Package")); fireEvent.click(await screen.findByRole("option", { name: metadata.name }));
  await waitFor(() => expect(request.mock.calls.at(-1)?.[0]).toContain("policy=policy-1"));
});

it("reports sanitized load errors and retries without displaying response details", async () => {
  const debug = vi.spyOn(console, "debug").mockImplementation(() => {});
  const request = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ error: "Synthetic private detail" }), { status: 500 }))
    .mockImplementation(async () => new Response(JSON.stringify(objectPage), { status: 200 }));
  vi.stubGlobal("fetch", request); mount(<PolicyDomainTab tab="Objects" metadata={metadata} policies={[metadata]} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("Objects could not be loaded.");
  expect(screen.queryByText("Synthetic private detail")).toBeNull();
  expect(debug).toHaveBeenCalledWith("policy-load", "inventory", "HTTP_500");
  fireEvent.click(screen.getByRole("button", { name: "Retry" }));
  await screen.findByRole("table", { name: "Policy objects" });
});

it("renders empty/single-member flags and readable schedule values in Objects", async () => {
  mock({ ...objectPage, total: 2, objects: [
    { ...objects[0], id: "empty-group", name: "GRP-TANGO-01", type: "group", values: [], emptyGroup: true, unused: null, duplicateId: null },
    { ...objects[0], id: "time-group", name: "TIME-TANGO-01", type: "time", singleMember: true, schedule: {
      kind: "recurring", start: "2026-10-01T00:00", end: "2026-10-31T23:59", windows: [{ days: [1, 2, 3, 4, 5], monthDays: [], start: "08:00", end: "17:00" }], timezone: "UTC", timezoneKnown: false,
    } },
  ] });
  mount(<PolicyDomainTab tab="Objects" metadata={metadata} policies={[metadata]} />);
  const table = await screen.findByRole("table", { name: "Policy objects" });
  expect(within(table).getByText("Empty group")).toBeInTheDocument();
  expect(within(table).getByText("Single member")).toBeInTheDocument();
  expect(within(table).getByText("Unused: UNKNOWN")).toBeInTheDocument();
  expect(within(table).getByText(/Weekdays 08:00–17:00/)).toHaveTextContent("From 1 Oct 2026 00:00");
});
