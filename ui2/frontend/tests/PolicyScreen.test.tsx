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
    const body = treeBody(input) ?? (input === "/api/v2/policy/sources" ? { sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName, vendor: metadata.vendor }], canCollect: false }
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
  expect(screen.getByText(/FW-TANGO-04.*VS-ROMEO-01/)).toBeInTheDocument();
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
  await screen.findByRole("button", { name: metadata.sourceName });
  fireEvent.click(screen.getByRole("button", { name: metadata.sourceName }));
  expect(await screen.findByText("No policy containers in this snapshot.")).toBeInTheDocument();
  expect(screen.queryByRole("table")).toBeNull();
});
it("shows empty and failed reads distinctly", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ policies: [], devices: [] }), { status: 200 })));
  const view = mount();
  expect(await screen.findByRole("status", { name: "No policy snapshot" })).toHaveTextContent("No management policy has been collected yet."); view.unmount();
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
  await screen.findByText("Collecting · 0/?");
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
  await screen.findByText("Collecting · 0/?");
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
  const tree = await screen.findByRole("navigation", { name: "Management policies" });
  expect(within(tree).getByRole("group", { name: "Policy source" })).toBeInTheDocument();
  expect(fetch.mock.calls).toHaveLength(1);
  expect(screen.queryByText(metadata.containerName)).toBeNull();
  expect(screen.queryByRole("table")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: metadata.sourceName }));
  await screen.findByRole("button", { name: metadata.containerName });
  expect(screen.queryByText(metadata.name)).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: metadata.containerName }));
  const container = await screen.findByRole("group", { name: "Policy container" });
  const policy = await within(container).findByRole("button", { pressed: false });
  expect(policy).toHaveTextContent(metadata.name);
  expect(fetch.mock.calls.some(([url]) => url.includes("/policies/"))).toBe(false);
  fireEvent.click(policy);
  await screen.findByRole("table", { name: "Policy rulebase" });
  expect(within(container).getByRole("button", { pressed: true })).toHaveTextContent(metadata.name);
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
  expect(screen.getByText("Collecting · 2/8")).toBeInTheDocument();
  await advance(9999); expect(polls).toBe(1);
  await advance(1); expect(polls).toBe(2);
  hidden = true; fireEvent(document, new Event("visibilitychange"));
  await advance(60000); expect(polls).toBe(2);
  hidden = false; fireEvent(document, new Event("visibilitychange"));
  await advance(20000); expect(polls).toBe(3);
  expect(screen.getByText(/Last collection failed at collection step 3 of 8 — access was refused/)).toBeInTheDocument();
  expect(screen.getByText("show devicegroups target=source-1: HTTP_403").closest("details")).not.toHaveAttribute("open");
  expect(screen.queryByText(/Collecting ·/)).toBeNull();
  await advance(60000); expect(polls).toBe(3);
  view.unmount(); vi.useRealTimers(); vi.restoreAllMocks();
});
it("clearly badges a partial snapshot and its safe layer failure", async () => {
  mockFetch({ ...page, failures: [{ layerRef: "layer-1", reason: "access target=layer-1: TIMEOUT" }] }); mount(); await navigate();
  expect(await screen.findByText("Partial snapshot · incomplete")).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("the time limit was reached");
  expect(screen.queryByText(/access target=/)).toBeNull();
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
  expect(screen.getByText("Collecting · 4/8")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Collect policies" })).toBeNull();
  view!.unmount();
  await act(async () => { await vi.advanceTimersByTimeAsync(60000); });
  expect(fetch.mock.calls.some(([url]) => url.includes("/collections/"))).toBe(false);
});

it("keeps an uncollected source navigable with an accessible empty state without collecting", async () => {
  const fetch = vi.fn(async (input: string) => new Response(JSON.stringify(input === "/api/v2/policy/sources"
    ? { sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName, vendor: "PAN" }], canCollect: true }
    : { sources: [], containers: [] }), { status: 200 }));
  vi.stubGlobal("fetch", fetch); mount();
  const tree = await screen.findByRole("navigation", { name: "Management policies" });
  fireEvent.click(within(tree).getByRole("button", { expanded: false }));
  await waitFor(() => expect(fetch.mock.calls).toHaveLength(2));
  expect(screen.getByRole("status", { name: "No collected policy" })).toHaveTextContent("Select a source and container");
  expect(within(tree).queryByRole("button", { pressed: false })).toBeNull();
  expect(screen.queryByRole("table")).toBeNull();
  expect(fetch.mock.calls.some(([url]) => url.endsWith("/collect"))).toBe(false);
});

it("builds the root exclusively from sources and displays each last collection outcome", async () => {
  const fetch = vi.fn(async (_input: string) => new Response(JSON.stringify({ canCollect: false, sources: [
    { sourceId: "pan-1", sourceName: "MGR-BRAVO-01", vendor: "PAN", collection: { jobId: "job-1", state: "FAILED", reason: "COLLECTION_FAILED: HTTP_403", step: 2, total: 8 } },
    { sourceId: "pan-partial", sourceName: "MGR-TANGO-02", vendor: "PAN", collection: { jobId: "job-2", state: "COMPLETED", reason: "PARTIAL_SNAPSHOT access target=layer-1: TIMEOUT", step: 4, total: 8 } },
    { sourceId: "pan-2", sourceName: "MGR-ROMEO-03", vendor: "PAN", collection: { jobId: "job-3", state: "COMPLETED", reason: "", step: 8, total: 8 } },
  ] }), { status: 200 }));
  vi.stubGlobal("fetch", fetch); mount();
  await screen.findByRole("button", { name: "MGR-BRAVO-01" });
  expect(screen.getByText("Last collection failed at collection step 2 of 8 — access was refused")).toBeInTheDocument();
  expect(screen.getByText("Last collection was incomplete at collection step 4 of 8 — the time limit was reached")).toBeInTheDocument();
  expect(screen.getByText("Collected · 8/8 collection steps")).toBeInTheDocument();
  expect(screen.queryByText("Loading policies…")).toBeNull();
  expect(fetch.mock.calls).toHaveLength(1);
  expect(fetch.mock.calls[0][0]).toBe("/api/v2/policy/sources");
});
it("ends empty child reads and failed child reads with explicit messages", async () => {
  const fetch = mockFetch();
  fetch.mockImplementation(async (input: string) => input.includes(`container=${metadata.containerId}`)
    ? new Response(JSON.stringify({ policies: [] }), { status: 200 })
    : new Response(JSON.stringify(treeBody(input) ?? { sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName, vendor: "PAN" }], canCollect: false }), { status: 200 }));
  const view = mount();
  fireEvent.click(await screen.findByRole("button", { name: metadata.sourceName }));
  fireEvent.click(await screen.findByRole("button", { name: metadata.containerName }));
  expect(await screen.findByText("No policies in this snapshot.")).toBeInTheDocument();
  view.unmount();
  fetch.mockImplementation(async (input: string) => input.includes("/tree") ? new Response("{}", { status: 500 })
    : new Response(JSON.stringify({ sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName, vendor: "PAN" }] }), { status: 200 }));
  mount(); fireEvent.click(await screen.findByRole("button", { name: metadata.sourceName }));
  expect(await screen.findByText("Policy containers could not be loaded.")).toBeInTheDocument();
  expect(screen.queryByText("Loading policies…")).toBeNull();
});
it("preserves masked policy dates and numeric counts in the rendered view", async () => {
  mockFetch({ ...page, metadata: { ...metadata, collectedAt: "2026-10-02T03:50:21Z" }, total: 122 });
  mount(); await navigate();
  expect(await screen.findByText("From configuration collected 2026-10-02T03:50:21Z")).toBeInTheDocument();
  expect(screen.getByText("Page 1 of 1 · 122 rules")).toBeInTheDocument();
});
it("ends an empty rule response with an explicit failure", async () => {
  const fetch = mockFetch();
  fetch.mockImplementation(async (input: string) => new Response(JSON.stringify(treeBody(input)
    ?? (input === "/api/v2/policy/sources" ? { sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName, vendor: "PAN" }] } : {})), { status: 200 }));
  mount(); await navigate();
  expect(await screen.findByText("Policy rules could not be loaded.")).toBeInTheDocument();
  expect(screen.queryByText("Loading rules…")).toBeNull();
});

it("shows layer and rule progress and labels incomplete layers with reached offsets", async () => {
  vi.stubGlobal("fetch", vi.fn(async (input: string) => {
    const body = input === "/api/v2/policy/sources" ? { sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName,
      vendor: "CP", collection: { jobId: "job-auto", state: "EXECUTING", reason: "", step: 12, total: 0, layer: 2, layers: 5, rulesFetched: 4000 } }], canCollect: false }
      : treeBody(input) ?? { ...page, failures: [{ layerRef: "layer-2", layerName: "OBJ-LAYER-02", offset: 4000, reason: "COLLECTION_FAILED: JOB_DEADLINE" }] };
    return new Response(JSON.stringify(body), { status: 200, headers: { "X-Nexus-Masked": "true" } });
  }));
  mount();
  expect(await screen.findByText("Collecting · unit 2/5, rules fetched 4000")).toBeInTheDocument();
  await navigate();
  expect(await screen.findByText("OBJ-LAYER-02 · offset 4000: Last collection failed — the time limit was reached")).toBeInTheDocument();
});
it("keeps UUIDs out of primary names and the empty state inside content only", async () => {
  const id = "00000000-0000-4000-8000-000000000001";
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ canCollect: false,
    sources: [{ sourceId: id, sourceName: `Panorama ${id}`, vendor: "PAN" }] }), { status: 200 })));
  mount();
  await screen.findByRole("button", { name: "Management server" });
  expect(screen.queryByText(new RegExp(id))).toBeNull();
  const content = screen.getByRole("region", { name: "Policy content" });
  expect(within(content).getByRole("status", { name: "No collected policy" })).toBeInTheDocument();
  expect(within(screen.getByRole("navigation")).queryByText("No assigned policy snapshot")).toBeNull();
  expect(within(content).getByLabelText("Jump to device's policy")).toBeInTheDocument();
});
it("shows a human failure and relative time once with admin-only details and transcript", async () => {
  const reason = "mgmt_cli show-access-rulebase target=layer-1: INVALID_OR_INCOMPLETE_RESPONSE";
  const at = new Date(Date.now() - 8 * 60000).toISOString();
  const respond = (allowed: boolean) => vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ canCollect: allowed,
    sources: [{ sourceId: "source-1", sourceName: "MGR-BRAVO-01", vendor: "CP",
      collection: { jobId: "job-1", state: "FAILED", reason, step: 12, total: 55, collectedAt: at, hasTranscript: true } }] }), { status: 200 })));
  respond(true);
  const view = mount();
  const sentence = await screen.findByText(/Last collection failed.*the reply could not be read/);
  expect(sentence).toHaveTextContent("8 min ago");
  expect(screen.getAllByText(/Last collection failed/)).toHaveLength(1);
  const disclosure = screen.getByText("Details").closest("details")!;
  expect(disclosure.open).toBe(false);
  fireEvent.click(screen.getByText("Details"));
  expect(within(disclosure).getByText(reason)).toBeInTheDocument();
  expect(within(disclosure).getByRole("button", { name: "Transcript" })).toBeInTheDocument();
  view.unmount(); respond(false); mount();
  await screen.findByText(/Last collection failed/);
  expect(screen.queryByText("Details")).toBeNull();
  expect(screen.queryByText(reason)).toBeNull();
  expect(screen.queryByRole("button", { name: "Transcript" })).toBeNull();
});
it("refreshes opened navigation and the selected rulebase automatically after polling ends", async () => {
  let completed = false;
  const fetch = vi.fn(async (input: string) => {
    const body = input === "/api/v2/policy/sources" ? { canCollect: false, sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName, vendor: "PAN",
      collection: { jobId: "job-auto", state: completed ? "COMPLETED" : "EXECUTING", reason: "", step: completed ? 8 : 6, total: 8 } }] }
      : input.includes("/collections/") ? (completed = true, { jobId: "job-auto", state: "COMPLETED", reason: "", step: 8, total: 8 })
      : treeBody(input) ?? page;
    return new Response(JSON.stringify(body), { status: 200 });
  });
  vi.stubGlobal("fetch", fetch); mount(); await navigate();
  await screen.findByRole("table", { name: "Policy rulebase" });
  const source = screen.getByRole("group", { name: "Policy source" });
  expect(within(source).getByText("Collecting · 6/8")).toBeInTheDocument();
  const before = fetch.mock.calls.filter(([url]) => url.includes("/policies/")).length;
  vi.useFakeTimers();
  fireEvent(document, new Event("visibilitychange"));
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  await act(async () => { await Promise.resolve(); });
  expect(completed).toBe(true);
  expect(screen.queryByText("Collecting · 6/8")).toBeNull();
  expect(screen.getByRole("button", { name: metadata.name, pressed: true })).toBeInTheDocument();
  expect(screen.getByRole("table", { name: "Policy rulebase" })).toBeInTheDocument();
  expect(fetch.mock.calls.filter(([url]) => url.includes("/policies/")).length).toBeGreaterThan(before);
});
it("jumps to a loaded assigned policy from the content header", async () => {
  mockFetch(); mount(); await navigate();
  await screen.findByRole("table");
  fireEvent.mouseDown(screen.getByLabelText("Jump to device's policy"));
  fireEvent.click(await screen.findByRole("option", { name: "FW-TANGO-04" }));
  expect(screen.getByRole("button", { name: metadata.name, pressed: true })).toBeInTheDocument();
  expect(screen.queryByText("No assigned policy snapshot")).toBeNull();
});

it.each([true, false])("gates source progress cancellation with canCancel=%s", async canCancel => {
  const fetch = vi.fn(async (input: string, init?: RequestInit) => {
    const body = input === "/session/status" ? { csrf_token: "synthetic-csrf" }
      : input === "/api/v2/policy/sources" ? { canCollect: true, canCancel, sources: [{
        sourceId: "mds-1", sourceName: "MGR-BRAVO-01", vendor: "CP", collection: {
          jobId: "job-1", state: "EXECUTING", reason: "", step: 2, total: 5,
        },
      }] }
      : input.endsWith("/cancel") ? { jobId: "job-1", state: "EXECUTING", cancelRequested: true }
      : input.includes("/collections/") ? { jobId: "job-1", state: "EXECUTING", reason: "", step: 2, total: 5 }
      : { sources: [] };
    return new Response(JSON.stringify(body), { status: init?.method === "POST" ? 202 : 200 });
  });
  vi.stubGlobal("fetch", fetch); mount();
  await screen.findByText("Collecting · 2/5");
  if (!canCancel) { expect(screen.queryByRole("button", { name: "Cancel" })).toBeNull(); return; }
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(await screen.findByRole("button", { name: "Cancel requested" })).toBeDisabled();
  expect(fetch.mock.calls.some(([url, init]) => url === "/api/v2/jobs/job-1/cancel" && init?.method === "POST")).toBe(true);
});
