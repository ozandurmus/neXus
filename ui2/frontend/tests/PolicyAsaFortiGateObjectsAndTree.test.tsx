import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyDomainTab } from "../src/screens/PolicyDomainTabs";
import { PolicyScreen } from "../src/screens/PolicyScreen";
import { expandByText, findButtonByText, findByAria } from "./fastQueries";
import type { PolicyInventoryObject, PolicyMetadata, PolicyObjectPage, PolicyPage } from "../src/auth/adminApi";

const metadataFor = (vendor: string, containerName: string): PolicyMetadata => ({ id: "policy-1", name: "POL-ALPHA-01", sourceId: "source-1", sourceName: "FW-TANGO-04",
  vendor, containerId: "container-1", containerName, collectedAt: "2026-11-20T00:00:00Z", artefactRef: "artifact-1", ruleCount: 1, targets: [] });
const base = { values: ["192.0.2.10"], members: [], emptyGroup: false, singleMember: false, unused: null, ruleCount: 1, groupCount: 0, usageComplete: false, usageReasons: [], unusedReasons: [] };
const objectPage = (gap: string): PolicyObjectPage => ({ page: 0, pageSize: 200, total: 3, types: [{ type: "address", status: "RESOLVED", collectedAt: "2026-11-20T00:00:00Z", objects: 2 }],
  usageGaps: [gap], objects: [
    { ...base, id: "o1", uid: "o1", name: "OBJ-EDGE-01", type: "address", duplicateId: "dup-a" },
    { ...base, id: "o2", uid: "o2", name: "OBJ-EDGE-02", type: "address", duplicateId: "dup-a", values: ["198.51.100.7"] },
    { ...base, id: "o3", uid: "o3", name: "OBJ-WINDOW-01", type: "schedule", duplicateId: null, values: [] },
  ] as PolicyInventoryObject[] });
const usageBody = { object: { id: "o1", name: "OBJ-EDGE-01" }, rules: [{ policyId: "policy-1", policyName: "POL-ALPHA-01", layerRef: "l1", layerName: "SEC-ONE", ruleId: "r1", number: 1 }],
  groups: [], ruleCount: 1, groupCount: 0, usageComplete: false, usageReasons: [], page: 0, pageSize: 200 };
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "X-Nexus-Masked": "true" } });
function mountTab(metadata: PolicyMetadata, gap = "ASA_OUTSIDE") {
  const fetch = vi.fn(async (input: string) => json(input.includes("object-usage") ? usageBody : objectPage(gap)));
  vi.stubGlobal("fetch", fetch);
  render(<ThemeProvider theme={m3Theme}><PolicyDomainTab tab="Objects" metadata={metadata} policies={[metadata]} /></ThemeProvider>);
  return fetch;
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

it.each([["ASA", "single", "Context: single", "Policy", "Access-group", "ASA_OUTSIDE"], ["FGT", "root", "VDOM: root", "Policy", "Section", "FGT_OUTSIDE"],
  ["CP", "DOM-TANGO-01", "Domain: DOM-TANGO-01", "Package", "Layer", "CP_NOTE"]])(
  "labels the %s Objects tab by its own container and filters address and schedule", async (vendor, container, caption, first, second, gap) => {
    const fetch = mountTab(metadataFor(vendor, container), gap);
    const table = await screen.findByRole("table", { name: "Policy objects" });
    expect(screen.getByText(caption)).toBeInTheDocument();
    expect(screen.getByText(`• ${gap}`)).toBeInTheDocument();
    expect(within(table).getAllByText("Duplicate set 1")).toHaveLength(2);
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Object type" }));
    const options = (await screen.findAllByRole("option")).map(o => o.textContent);
    expect(options).toEqual(expect.arrayContaining(["All types", "address", "schedule"]));
    fireEvent.click(screen.getByRole("option", { name: "address" }));
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => String(url).includes("type=address"))).toBe(true));
    fireEvent.click(await screen.findByRole("button", { name: "Where used: OBJ-EDGE-01" }));
    const drawer = await screen.findByRole("dialog", { name: "Object usage" });
    const rules = await within(drawer).findByRole("table", { name: "Rules using object" });
    expect(within(rules).getAllByRole("columnheader").map(h => h.textContent?.trim())).toEqual([first, second, "Rule number"]);
  });

function mockTree(vendor: string) {
  const metadata = metadataFor(vendor, vendor === "ASA" ? "single" : vendor === "FGT" ? "root" : "DOM-TANGO-01");
  const rule = { id: "rule-1", uuid: "uuid-1", number: 1, name: "RULE-ONE", enabled: true, source: { refs: ["any-1"], negated: false }, destination: { refs: ["any-1"], negated: false },
    service: { refs: ["any-1"], negated: false }, application: { refs: [], negated: false }, action: "allow", log: "UNKNOWN", comment: "", extras: {} };
  const page: PolicyPage = { metadata, sections: [{ id: "s1", name: "SEC-ONE", source: "Shared", parentRuleId: null, rules: [rule], total: 1 }],
    objects: [{ id: "any-1", name: "ANY", type: "any", status: "RESOLVED" }], page: 0, pageSize: 200, total: 1 } as unknown as PolicyPage;
  vi.stubGlobal("fetch", vi.fn(async (input: string) => {
    if (input === "/api/v2/policy/devices") return json({ policies: [metadata], devices: [] });
    if (input === "/api/v2/policy/sources") return json({ sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName, vendor }], canCollect: false });
    if (input.startsWith("/api/v2/policy/tree")) {
      const params = new URL(input, "https://example.invalid").searchParams;
      return json(params.get("container") ? { policies: [metadata] } : params.get("source") ? { containers: [{ containerId: metadata.containerId, containerName: metadata.containerName }] }
        : { sources: [{ sourceId: metadata.sourceId, sourceName: metadata.sourceName, vendor }] });
    }
    return json(page);
  }));
  return metadata;
}
async function openPolicy(metadata: PolicyMetadata) {
  render(<ThemeProvider theme={m3Theme}><PolicyScreen /></ThemeProvider>);
  await expandByText(metadata.sourceName);
  fireEvent.click(await findButtonByText(metadata.containerName));
  fireEvent.click(await findByAria(`Policy ${metadata.name}`));
  await screen.findByRole("region", { name: "Policy rulebase" });
}
it.each([["ASA", "ASA", "Cisco ASA"], ["FGT", "FTNT", "FortiGate"]])("shows the %s source with its badge and caption and no management-package Installation", async (vendor, badge, caption) => {
  await openPolicy(mockTree(vendor));
  const group = screen.getByRole("group", { name: "Policy source" });
  expect(within(group).getByText(badge)).toBeInTheDocument();
  expect(within(group).getByText(new RegExp(`^${caption} · 1 containers`))).toBeInTheDocument();
  expect(within(group).queryByText(/^Management server ·/)).toBeNull();
  expect(screen.queryByRole("region", { name: "Installation" })).toBeNull();
});
it.each([["CP", "CP", "MDS"], ["PAN", "PAN", "Panorama"]])("keeps the %s Installation section and caption", async (vendor, badge, caption) => {
  await openPolicy(mockTree(vendor));
  const group = screen.getByRole("group", { name: "Policy source" });
  expect(within(group).getByText(badge)).toBeInTheDocument();
  expect(within(group).getByText(new RegExp(`^${caption} · 1 containers`))).toBeInTheDocument();
  expect(screen.getByRole("region", { name: "Installation" })).toBeInTheDocument();
});
