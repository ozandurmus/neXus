import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyDomainTab, whereUsedLabel } from "../src/screens/PolicyDomainTabs";
import type { PolicyInventoryObject, PolicyMetadata, PolicyObjectPage } from "../src/auth/adminApi";

const metadata: PolicyMetadata = { id: "p1", name: "POL-ALPHA-01", sourceId: "src1", sourceName: "MGR-BRAVO-01", vendor: "PAN",
  containerId: "c1", containerName: "DG-TANGO-04", collectedAt: "2026-10-15T00:00:00Z", artefactRef: "a1", targets: [] };
const base = { type: "address", values: ["ip-netmask: 192.0.2.10"], members: [], emptyGroup: false, singleMember: false, duplicateId: null };
const NAT = "NAT, decryption and other non-security rulebases are not collected.";
const REPORT = "Management unused-object report is not collected.";
const rows: PolicyInventoryObject[] = [
  { ...base, id: "o1", uid: "o1", name: "ADDR-PROVEN-01", unused: true, ruleCount: 0, groupCount: 0, usageComplete: true, usageReasons: [], unusedReasons: [] },
  { ...base, id: "o2", uid: "o2", name: "ADDR-UNKNOWN-02", unused: null, ruleCount: 0, groupCount: 0, usageComplete: false, usageReasons: [REPORT, NAT], unusedReasons: [REPORT] },
  { ...base, id: "o3", uid: "o3", name: "ADDR-PARTIAL-03", unused: null, ruleCount: 3, groupCount: 1, usageComplete: false, usageReasons: [NAT], unusedReasons: [REPORT] },
  { ...base, id: "o4", uid: "o4", name: "ADDR-LEGACY-04", unused: null, ruleCount: 0, groupCount: 0 },
] as PolicyInventoryObject[];
const page: PolicyObjectPage = { objects: rows, total: rows.length, page: 0, pageSize: 200, types: [], usageGaps: [NAT] };
function response(value: unknown) { return new Response(JSON.stringify(value), { status: 200 }); }
function mount() { return render(<ThemeProvider theme={m3Theme}><PolicyDomainTab tab="Objects" metadata={metadata} policies={[metadata]} /></ThemeProvider>); }
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it("labels a count exact only when the server proved it", () => {
  expect(whereUsedLabel({ ruleCount: 0, groupCount: 0, usageComplete: true })).toBe("0 rules · 0 groups");
  expect(whereUsedLabel({ ruleCount: 0, groupCount: 0, usageComplete: false })).toBe("UNKNOWN");
  expect(whereUsedLabel({ ruleCount: 0, groupCount: 0 })).toBe("UNKNOWN");
  expect(whereUsedLabel({ ruleCount: 2, groupCount: 0, usageComplete: false })).toBe("at least 2 rules · 0 groups");
});

it("never shows an unproven zero and keeps hygiene and where-used consistent", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => response(page)));
  mount();
  expect(screen.getByText("Loading objects…")).toBeInTheDocument();
  const table = await screen.findByRole("table", { name: "Policy objects" });
  const row = (name: string) => within(table).getByText(name).closest("tr") as HTMLElement;
  expect(within(row("ADDR-PROVEN-01")).getByRole("button", { name: "Where used: ADDR-PROVEN-01" })).toHaveTextContent("0 rules · 0 groups");
  expect(within(row("ADDR-PROVEN-01")).getByText("Unused")).toBeInTheDocument();
  expect(within(row("ADDR-UNKNOWN-02")).getByRole("button", { name: "Where used: ADDR-UNKNOWN-02" })).toHaveTextContent("UNKNOWN");
  expect(within(row("ADDR-UNKNOWN-02")).getByText("Unused: UNKNOWN")).toBeInTheDocument();
  expect(within(row("ADDR-PARTIAL-03")).getByRole("button", { name: "Where used: ADDR-PARTIAL-03" })).toHaveTextContent("at least 3 rules · 1 groups");
  expect(within(row("ADDR-LEGACY-04")).getByRole("button", { name: "Where used: ADDR-LEGACY-04" })).toHaveTextContent("UNKNOWN");
  expect(within(table).queryAllByText("0 rules · 0 groups")).toHaveLength(1);
  expect(within(screen.getByRole("note", { name: "Where-used evidence gaps" })).getByText(`• ${NAT}`)).toBeInTheDocument();
  expect(screen.getByText(/Objects are listed from the stored policy/)).toBeInTheDocument();
  fireEvent.mouseOver(within(row("ADDR-UNKNOWN-02")).getByRole("button", { name: "Where used: ADDR-UNKNOWN-02" }));
  expect(await screen.findByRole("tooltip")).toHaveTextContent(`${REPORT} ${NAT}`);
});

it("explains an unknown zero in the usage drawer instead of claiming no references", async () => {
  const fetcher = vi.fn(async (url: string) => url.includes("/object-usage") ? response({ object: rows[1], rules: [], groups: [], ruleCount: 0, groupCount: 0,
    page: 0, pageSize: 200, usageComplete: false, usageReasons: [REPORT, NAT] }) : response(page));
  vi.stubGlobal("fetch", fetcher);
  mount();
  fireEvent.click(await screen.findByRole("button", { name: "Where used: ADDR-UNKNOWN-02" }));
  const drawer = await screen.findByRole("dialog", { name: "Object usage" });
  expect(await within(drawer).findByText("No references in collected rules and groups; usage remains UNKNOWN.")).toBeInTheDocument();
  expect(within(drawer).getByText("Where used: UNKNOWN")).toBeInTheDocument();
  expect(within(within(drawer).getByLabelText("Usage evidence gaps")).getByText(`• ${NAT}`)).toBeInTheDocument();
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith(expect.stringContaining("uid=o2"), expect.anything()));
});

it("confirms a proven zero in the usage drawer", async () => {
  vi.stubGlobal("fetch", vi.fn(async (url: string) => url.includes("/object-usage") ? response({ object: rows[0], rules: [], groups: [], ruleCount: 0, groupCount: 0,
    page: 0, pageSize: 200, usageComplete: true, usageReasons: [] }) : response(page)));
  mount();
  fireEvent.click(await screen.findByRole("button", { name: "Where used: ADDR-PROVEN-01" }));
  const drawer = await screen.findByRole("dialog", { name: "Object usage" });
  expect(await within(drawer).findByText("No references; confirmed by the management unused-object report.")).toBeInTheDocument();
  expect(within(drawer).queryByLabelText("Usage evidence gaps")).toBeNull();
});
