import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyScreen, VirtualPolicyRows } from "../src/screens/PolicyScreen";
import type { PolicyMetadata, PolicyPage, PolicyRule } from "../src/auth/adminApi";

const metadata: PolicyMetadata = { id: "policy-1", sourceId: "source-1", sourceName: "MGR-BRAVO-01", vendor: "PAN", containerId: "container-1",
  containerName: "DOM-TANGO-01", name: "OBJ-POLICY-01", collectedAt: "2026-10-01T12:00:00Z", artefactRef: "artifact-1",
  targets: [{ deviceId: "device-1", name: "FW-TANGO-04", context: "VS-ROMEO-01", syncStatus: "UNKNOWN" }] };
const rule: PolicyRule = { id: "rule-1", uuid: "uuid-1", number: 1, name: "OBJ-RULE-01", enabled: false,
  source: { refs: ["object-1"], negated: true }, destination: { refs: ["any-1"], negated: false }, service: { refs: ["any-1"], negated: false },
  application: { refs: [], negated: false }, action: "allow", log: "UNKNOWN", comment: "Withheld in AIView", extras: { withheld: ["Withheld in AIView"] } };
const page: PolicyPage = { metadata, sections: [{ id: "section-1", name: "OBJ-SECTION-01", source: "Shared", parentRuleId: null, rules: [rule], total: 1 }],
  objects: [{ id: "object-1", name: "OBJ-GROUP-01", type: "address-group", status: "RESOLVED" }, { id: "any-1", name: "ANY", type: "any", status: "RESOLVED" }], page: 0, pageSize: 200, total: 1 };
function treeBody(input: string, vendor = metadata.vendor) {
  if (!input.startsWith("/api/v2/policy/tree")) return undefined;
  const params = new URL(input, "https://example.invalid").searchParams;
  if (params.get("device") === "missing-device") return { sources: [] };
  return params.get("container") ? { policies: [{ ...metadata, vendor }] }
    : params.get("source") ? { containers: [{ containerId: metadata.containerId, containerName: metadata.containerName }] }
    : { sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName, vendor }] };
}
function mockFetch(result: PolicyPage = page) {
  const fetch = vi.fn(async (input: string) => {
    const body = treeBody(input) ?? (input === "/api/v2/policy/sources" ? { sources: [], canCollect: false }
      : input.includes("/objects/") ? { object: { ...page.objects[0], children: [
        { id: "leaf-1", name: "OBJ-ADDRESS-01", type: "address", status: "RESOLVED", values: ["192.0.2.8"] },
        { id: "cycle-1", name: "OBJ-GROUP-01", type: "address-group", status: "CYCLE" },
      ] } } : result);
    return new Response(JSON.stringify(body), { status: 200, headers: { "X-Nexus-Masked": "true" } });
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}
function mount() { return render(<ThemeProvider theme={m3Theme}><PolicyScreen /></ThemeProvider>); }
async function navigate() {
  fireEvent.click(await screen.findByRole("button", { name: metadata.sourceName }));
  fireEvent.click(await screen.findByRole("button", { name: metadata.containerName }));
  fireEvent.click(await screen.findByRole("button", { name: metadata.name }));
}
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

it("renders masked management navigation, assignments, section collapse, negation and disabled rules", async () => {
  mockFetch(); mount(); await navigate();
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
  mockFetch(); mount(); await navigate();
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
  const fetch = mockFetch(large); mount(); await navigate();
  const table = await screen.findByRole("table");
  expect(table.querySelectorAll("[data-policy-rule]").length).toBeLessThanOrEqual(24);
  expect(screen.getByText("Page 1 of 25 · 5000 rules")).toBeInTheDocument();
  fireEvent.click(screen.getByText("Next"));
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("page=1"))).toBe(true));
});
it("sends only the name/comment search and resets pagination", async () => {
  const fetch = mockFetch(); mount(); await navigate();
  fireEvent.change(await screen.findByLabelText("Search rule names and comments"), { target: { value: "OBJ-RULE" } });
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes("page=0&q=OBJ-RULE"))).toBe(true));
});
it("keeps an unassigned policy navigable and a device jump scoped to its assignments", async () => {
  window.history.replaceState(null, "", "?screen=policy&device_id=missing-device");
  mockFetch(); mount();
  expect(await screen.findByText("No policy snapshot")).toBeInTheDocument();
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

it("offers first collection on an empty MDS and sends only opaque scope with CSRF", async () => {
  const fetch = vi.fn(async (input: string, init?: RequestInit) => {
    const body = input === "/session/status" ? { csrf_token: "synthetic-csrf" }
      : input === "/api/v2/policy/sources" ? { sources: [{ sourceId: "mds-1", sourceName: "MGR-BRAVO-01", vendor: "CP" }], canCollect: true }
      : input.endsWith("/collect") ? { jobId: "job-1" } : { policies: [], devices: [] };
    return new Response(JSON.stringify(body), { status: init?.method === "POST" ? 202 : 200 });
  });
  vi.stubGlobal("fetch", fetch); mount();
  fireEvent.click(await screen.findByRole("button", { name: "Collect policies" }));
  await screen.findByText(/Policy collection queued/);
  const call = fetch.mock.calls.find(([url]) => url.endsWith("/collect"));
  expect(call?.[0]).toBe("/api/v2/policy/sources/mds-1/collect");
  expect(JSON.parse(call?.[1]?.body as string)).toEqual({ domainRef: "" });
  expect(new Headers(call?.[1]?.headers).get("X-CSRF-Token")).toBe("synthetic-csrf");
  expect(screen.getByRole("button", { name: "Refresh snapshots" })).toBeInTheDocument();
});
it("uses server authorization and exposes no collection action to AIView", async () => {
  mockFetch(); mount(); await navigate();
  await screen.findByRole("table");
  expect(screen.queryByRole("button", { name: "Collect policies" })).toBeNull();
});
it("offers domain collection with its opaque reference and reports queue refusal", async () => {
  vi.stubGlobal("fetch", vi.fn(async (input: string, init?: RequestInit) => {
    if (init?.method === "POST") return new Response("{}", { status: 409 });
    const body = input === "/api/v2/policy/sources" ? { sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName, vendor: "CP" }], canCollect: true }
      : treeBody(input, "CP") ?? page;
    return new Response(JSON.stringify(body), { status: 200 });
  }));
  mount(); await navigate();
  await screen.findByRole("table");
  const actions = screen.getAllByRole("button", { name: "Collect policies" });
  expect(actions).toHaveLength(2);
  fireEvent.click(actions[1]);
  await screen.findByText(/could not be queued/);
  const fetch = vi.mocked(window.fetch);
  expect(fetch.mock.calls.some(([url, init]) => String(url).endsWith("/collect") && JSON.parse(init?.body as string).domainRef === metadata.containerId)).toBe(true);
});

it("queues Panorama node collection with opaque intent and hides DG-specific actions", async () => {
  const fetch = vi.fn(async (input: string, init?: RequestInit) => {
    const body = input === "/api/v2/policy/sources"
      ? { sources: [{ sourceId: metadata.sourceId, sourceName: "MGR-BRAVO-01", vendor: "PAN" }], canCollect: true }
      : input.startsWith("/api/v2/policy/tree") ? treeBody(input)
      : input === "/session/status" ? { csrf_token: "synthetic-csrf" }
      : input.endsWith("/collect") ? { jobId: "job-pan" } : page;
    return new Response(JSON.stringify(body), { status: init?.method === "POST" ? 202 : 200 });
  });
  vi.stubGlobal("fetch", fetch); mount(); await navigate();
  await screen.findByRole("table");
  const actions = screen.getAllByRole("button", { name: "Collect policies" });
  expect(actions).toHaveLength(1); fireEvent.click(actions[0]);
  await screen.findByText(/Policy collection queued/);
  const call = fetch.mock.calls.find(([url]) => url.endsWith("/collect"));
  expect(call?.[0]).toBe(`/api/v2/policy/sources/${metadata.sourceId}/collect`);
  expect(JSON.parse(call?.[1]?.body as string)).toEqual({ domainRef: "" });
});
it("badges the stored local firewall policy under AIView", async () => {
  mockFetch({ ...page, policyKind: "LOCAL_FIREWALL" }); mount(); await navigate();
  expect(await screen.findByText("local firewall policy")).toBeInTheDocument();
  expect(screen.getByText(/Stored local configuration/)).toBeInTheDocument();
});

it("fetches only sources until each navigation level is expanded", async () => {
  const fetch = mockFetch(); mount();
  await screen.findByRole("button", { name: metadata.sourceName });
  expect(fetch.mock.calls).toHaveLength(2);
  expect(screen.queryByText(metadata.containerName)).toBeNull();
  expect(screen.queryByRole("table")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: metadata.sourceName }));
  await screen.findByRole("button", { name: metadata.containerName });
  expect(screen.queryByText(metadata.name)).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: metadata.containerName }));
  await screen.findByRole("button", { name: metadata.name });
  expect(fetch.mock.calls.some(([url]) => url.includes("/policies/"))).toBe(false);
});
it("virtualizes a 5000-rule response and reaches the final row on scroll", async () => {
  const rules = Array.from({ length: 5000 }, (_, i) => ({ ...rule, id: `rule-${i}`, name: `OBJ-RULE-${i}`, number: i + 1 }));
  const props = { sections: [{ ...page.sections[0], rules, total: 5000 }], collapsed: new Set<string>(), toggle: vi.fn(), cell: () => null, openRule: vi.fn() };
  const view = render(<ThemeProvider theme={m3Theme}><table><tbody><VirtualPolicyRows {...props} scrollTop={0} /></tbody></table></ThemeProvider>);
  expect(view.container.querySelectorAll("[data-policy-rule]").length).toBeLessThanOrEqual(24);
  expect(screen.queryByText("OBJ-RULE-4999")).toBeNull();
  view.rerender(<ThemeProvider theme={m3Theme}><table><tbody><VirtualPolicyRows {...props} scrollTop={4990 * 64} /></tbody></table></ThemeProvider>);
  expect(screen.getByText("OBJ-RULE-4999")).toBeInTheDocument();
  expect(view.container.querySelectorAll("[data-policy-rule]").length).toBeLessThanOrEqual(24);
});
it("backs off polling, suspends while hidden and stops on the precise terminal reason", async () => {
  let hidden = false, polls = 0;
  vi.spyOn(document, "hidden", "get").mockImplementation(() => hidden);
  vi.stubGlobal("fetch", vi.fn(async (input: string, init?: RequestInit) => {
    const body = input === "/session/status" ? { csrf_token: "synthetic-csrf" }
      : input === "/api/v2/policy/sources" ? { sources: [{ sourceId: "mds-1", sourceName: "MGR-BRAVO-01", vendor: "CP" }], canCollect: true }
      : input.endsWith("/collect") ? { jobId: "job-1" }
      : input.includes("/collections/") ? (++polls < 3 ? { jobId: "job-1", state: "EXECUTING", step: 2, total: 8, reason: "" }
        : { jobId: "job-1", state: "FAILED", step: 3, total: 8, reason: "show devicegroups target=source-1: HTTP_403" })
      : { sources: [] };
    return new Response(JSON.stringify(body), { status: init?.method === "POST" ? 202 : 200 });
  }));
  const view = mount();
  const action = await screen.findByRole("button", { name: "Collect policies" });
  vi.useFakeTimers();
  await act(async () => { fireEvent.click(action); });
  const advance = async (ms: number) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); };
  await advance(4999); expect(polls).toBe(0);
  await advance(1); expect(polls).toBe(1);
  expect(screen.getByText("Collecting… step 2/8")).toBeInTheDocument();
  await advance(9999); expect(polls).toBe(1);
  await advance(1); expect(polls).toBe(2);
  hidden = true; fireEvent(document, new Event("visibilitychange"));
  await advance(60000); expect(polls).toBe(2);
  hidden = false; fireEvent(document, new Event("visibilitychange"));
  await advance(20000); expect(polls).toBe(3);
  expect(screen.getByText("show devicegroups target=source-1: HTTP_403")).toBeInTheDocument();
  await advance(60000); expect(polls).toBe(3);
  view.unmount(); vi.useRealTimers(); vi.restoreAllMocks();
});
it("clearly badges a partial snapshot and its safe layer failure", async () => {
  mockFetch({ ...page, failures: [{ layerRef: "layer-1", reason: "access target=layer-1: TIMEOUT" }] }); mount(); await navigate();
  expect(await screen.findByText("Partial snapshot · incomplete")).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("TIMEOUT");
});

it("discovers automatic collections and stops all pending polling on unmount", async () => {
  const fetch = vi.fn(async (input: string) => {
    const body = input === "/api/v2/policy/sources" ? { canCollect: false, sources: [{ sourceId: "pan-1", sourceName: "MGR-BRAVO-01", vendor: "PAN",
      collection: { jobId: "job-auto", state: "EXECUTING", reason: "", step: 4, total: 8 } }] }
      : input.includes("/collections/") ? { jobId: "job-auto", state: "EXECUTING", reason: "", step: 5, total: 8 } : { sources: [] };
    return new Response(JSON.stringify(body), { status: 200 });
  });
  vi.stubGlobal("fetch", fetch);
  vi.useFakeTimers();
  let view: ReturnType<typeof mount> | undefined;
  await act(async () => { view = mount(); });
  expect(screen.getByText("Collecting… step 4/8")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Collect policies" })).toBeNull();
  view!.unmount();
  await act(async () => { await vi.advanceTimersByTimeAsync(60000); });
  expect(fetch.mock.calls.some(([url]) => url.includes("/collections/"))).toBe(false);
});
