import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { LIFECYCLE_PAGE, LifecycleScreen, DeviceLifecycleLine, lifecycleCsv, livePatchCell } from "../src/screens/LifecycleScreen";
import { LifecycleCatalogPanel } from "../src/screens/LifecycleCatalogPanel";
import * as api from "../src/auth/adminApi";
import type { LifecycleDevice } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async importOriginal => ({
  ...await importOriginal<typeof import("../src/auth/adminApi")>(),
  getLifecycle: vi.fn(), getDeviceLifecycle: vi.fn(), getLifecycleCatalog: vi.fn(),
  saveLifecycleCatalog: vi.fn(), importLifecycleCatalog: vi.fn(), deleteLifecycleCatalog: vi.fn(),
}));
const noMatch = { status: "NO_LIFECYCLE_DATA" as const, reason: "No catalog match", basis: null, catalog: null };
const row = (id: string, risk: LifecycleDevice["risk"] = "UNKNOWN"): LifecycleDevice => ({
  device_id: id, hostname: id === "member-a" ? "FW-TANGO-04" : "FW-JULIET-06", cluster_member_ref: "CLS-ROMEO-01",
  vendor: "CHECKPOINT", model: "Example Appliance", software_version: "R81.20 Jumbo Hotfix Take 99",
  hardware: noMatch, software: noMatch, status: "NO_LIFECYCLE_DATA", risk, support_days: null, next_milestone: null,
  nearest_license: null, licenses: [], license_status: "NOT_COLLECTED", support_contract_status: "NOT_COLLECTED",
});
const entry: api.LifecycleCatalogEntry = { catalog_id: "row-1", vendor: "CHECKPOINT", kind: "HARDWARE", product: "Example Appliance",
  end_of_sale: null, end_of_support: "2030-01-01", end_of_engineering: null, note: "Synthetic source", source: "MANUAL", imported_by: "synthetic-actor", imported_at: "2026-10-09T00:00:00Z" };
function themed(node: React.ReactNode) { return render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>); }

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getLifecycle).mockResolvedValue({ as_of: "2026-10-09", devices: [row("member-a"), row("member-b", "HIGH")],
    summary: { devices: 2, no_lifecycle_data: 2, past_end_of_support: 0, within_180_days: 0, within_365_days: 0, licenses_within_60_days: 0, license_problems: 1, licenses_collected: 0, live_patch_not_active: 2 } });
  vi.mocked(api.getLifecycleCatalog).mockResolvedValue({ can_manage: false, entries: [] });
});

describe("fleet lifecycle", () => {
  it("shows separate masked members, summary counts, unknown reasons and collection gaps", async () => {
    themed(<LifecycleScreen />);
    const table = await screen.findByRole("table", { name: "Fleet lifecycle" });
    expect(within(table).getByText("FW-TANGO-04")).toBeInTheDocument();
    expect(within(table).getByText("FW-JULIET-06")).toBeInTheDocument();
    expect(within(table).getAllByText("CLS-ROMEO-01")).toHaveLength(2);
    expect(within(table).getAllByText("NO_LIFECYCLE_DATA")).toHaveLength(2);
    expect(within(table).getAllByText("Not collected")).toHaveLength(2);
    expect(within(table).getAllByRole("button", { name: "Details" })).toHaveLength(2);
    fireEvent.click(within(table).getAllByRole("button", { name: "Details" })[0]);
    expect(within(table).getByText("Support contract not collected for this device")).toBeInTheDocument();
    expect(screen.getByText("No lifecycle data")).toBeInTheDocument();
    expect(within(table).getAllByText("No catalog match · No catalog match")).toHaveLength(2);
  });
  it("filters rows and exports the same projected identities with CSV formula protection", async () => {
    themed(<LifecycleScreen />);
    await screen.findByText("FW-TANGO-04");
    fireEvent.change(screen.getByLabelText("Search lifecycle"), { target: { value: "JULIET" } });
    expect(screen.queryByText("FW-TANGO-04")).not.toBeInTheDocument();
    expect(screen.getByText("FW-JULIET-06")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search lifecycle"), { target: { value: "" } });
    fireEvent.mouseDown(screen.getByLabelText("Risk"));
    fireEvent.click(screen.getByRole("option", { name: "HIGH" }));
    expect(screen.queryByText("FW-TANGO-04")).not.toBeInTheDocument();
    expect(screen.getByText("FW-JULIET-06")).toBeInTheDocument();
    const csv = lifecycleCsv([{ ...row("member-a"), hostname: "=synthetic_formula" }]);
    expect(csv).toContain("'=synthetic_formula");
    expect(csv).toContain("CLS-ROMEO-01");
    expect(csv).toContain("UNKNOWN");
  });
  it("preserves unknown stored license labels and shows dated license evidence", async () => {
    const device = { ...row("member-a"), vendor: "INFOBLOX", license_status: "COLLECTED", licenses: [
      { name: "DNS", member: "FW-BRAVO-02", label: "Never", expiry: null, status: "UNKNOWN" },
      { name: "DHCP", member: "FW-BRAVO-02", label: "2026-10-20", expiry: "2026-10-20", status: "DATED" },
    ], nearest_license: { label: "2026-10-20", expiry: "2026-10-20", status: "DATED" } };
    vi.mocked(api.getLifecycle).mockResolvedValue({ as_of: "2026-10-09", summary: {}, devices: [device] });
    themed(<LifecycleScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "Details" }));
    expect(await screen.findByText(/^DNS · FW-BRAVO-02 · Never · UNKNOWN$/)).toBeInTheDocument();
    expect(screen.getByText(/^DHCP · FW-BRAVO-02 · 2026-10-20$/)).toBeInTheDocument();
    expect(screen.getAllByText("Licenses").length).toBeGreaterThanOrEqual(2);
    expect(lifecycleCsv([device])).toContain("DNS · FW-BRAVO-02 · Never · UNKNOWN");
  });
  it("shows and exports sale and engineering milestones as separate dates", async () => {
    const device = { ...row("member-a"), hardware: { status: "MATCHED" as const, reason: null, basis: "model exact",
      catalog: { ...entry, end_of_sale: "2025-01-01", end_of_engineering: "2029-01-01" } } };
    vi.mocked(api.getLifecycle).mockResolvedValue({ as_of: "2026-10-09", summary: {}, devices: [device] });
    themed(<LifecycleScreen />);
    expect(await screen.findByText(/End of sale: 2025-01-01 · End of engineering: 2029-01-01/)).toBeInTheDocument();
    expect(lifecycleCsv([device])).toContain("Hardware end of engineering");
    expect(lifecycleCsv([device])).toContain("2025-01-01,2029-01-01");
  });
  it("pages the fleet at 50 with '+N more' and keeps every row reachable (post-release review 2026-10-18, 1.7)", async () => {
    const many = Array.from({ length: LIFECYCLE_PAGE + 12 }, (_, i) => ({ ...row(`member-${i}`), hostname: `FW-SYNTH-${String(i).padStart(3, "0")}` }));
    vi.mocked(api.getLifecycle).mockResolvedValue({ as_of: "2026-10-09", summary: {}, devices: many });
    themed(<LifecycleScreen />);
    const table = await screen.findByRole("table", { name: "Fleet lifecycle" });
    expect(within(table).getAllByRole("row")).toHaveLength(LIFECYCLE_PAGE + 1);
    expect(document.querySelector("[data-lifecycle-page]")).toHaveTextContent(`1–${LIFECYCLE_PAGE} of ${LIFECYCLE_PAGE + 12} · +12 more`);
    fireEvent.click(screen.getByRole("button", { name: "Show next 12" }));
    expect(within(table).getAllByRole("row")).toHaveLength(LIFECYCLE_PAGE + 13);
    expect(document.querySelector("[data-lifecycle-page]")).toHaveTextContent(`1–${LIFECYCLE_PAGE + 12} of ${LIFECYCLE_PAGE + 12}`);
    expect(screen.queryByRole("button", { name: /^Show next/ })).toBeNull();
    // a new filter starts again at the first page
    fireEvent.change(screen.getByLabelText("Search lifecycle"), { target: { value: "FW-SYNTH-0" } });
    expect(within(table).getAllByRole("row")).toHaveLength(LIFECYCLE_PAGE + 1);
  });
  it("shows empty and failed reads without fabricating data", async () => {
    vi.mocked(api.getLifecycle).mockRejectedValue({ status: 503, body: { error: "UNAVAILABLE" } });
    themed(<LifecycleScreen />);
    expect(await screen.findByText("UNAVAILABLE")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Fleet lifecycle" })).not.toBeInTheDocument();
  });
  it("shows a compact device lifecycle line", async () => {
    vi.mocked(api.getDeviceLifecycle).mockResolvedValue({ ...row("member-a"), hardware: { status: "MATCHED", reason: null, basis: "model exact", catalog: entry } });
    themed(<DeviceLifecycleLine deviceId="member-a" />);
    expect(await screen.findByText(/Hardware support: 2030-01-01/)).toBeInTheDocument();
    expect(api.getDeviceLifecycle).toHaveBeenCalledWith("member-a");
  });
});

const lic = (o: Partial<api.LifecycleLicense>): api.LifecycleLicense => ({ name: "CPSB-FW", kind: "LICENSE", category: "LICENSE", label: "Permanent", expiry: null, status: "PERMANENT", days_remaining: null, evidence: "DIRECT_DEVICE", ...o });
const patchBlock = (o: Partial<api.LifecycleLivePatch> = {}): api.LifecycleLivePatch => ({ package_take: "42", jumbo: "Jumbo Hotfix Take 99", read_outcome: "COLLECTED", observed_at: "2026-10-09T01:00:00Z",
  armed: 3, ready: 1, jumbofix: 1, other: 0, items: [
    { patch: "PATCH-SYNTH-01", status: "armed", mode: "auto", running: 2, total: 2, installed_at: "2026-09-01T08:30", refs: ["sk000000"] },
    { patch: "PATCH-SYNTH-02", status: "ready", mode: "auto", running: 0, total: 2, installed_at: null, refs: ["CVE-2026-0001", "sk000001"] }], ...o });

describe("license and Live Patch view", () => {
  const problemDevice: LifecycleDevice = { ...row("member-a"), license_status: "COLLECTED", support_contract_status: "COLLECTED", license_problem: true,
    license_problems: [{ label: "CPSB-IPS", status: "EXPIRED" }], nearest_license: lic({ expiry: "2026-10-06", status: "EXPIRED" }),
    licenses: [lic({}), lic({ name: "CPSB-IPS", expiry: "2026-10-06", label: "2026-10-06", status: "EXPIRED", days_remaining: -3 }),
      lic({ name: "AV", category: "SUBSCRIPTION", kind: "SUBSCRIPTION", expiry: "2026-11-01", label: "2026-11-01", status: "DATED", days_remaining: 23 }),
      lic({ name: "CPAP-SYNTH", category: "SUPPORT_CONTRACT", kind: "SUPPORT_CONTRACT", expiry: "2027-01-01", label: "2027-01-01", status: "DATED", days_remaining: 84 }),
      lic({ name: "Smart Licensing", category: "SMART_LICENSING", kind: "SMART_LICENSING", label: "UNREGISTERED", status: "UNREGISTERED" }),
      lic({ name: "Web Filter", member: "FW-BRAVO-02", evidence: "MANAGER_REPORTED", expiry: "2026-10-20", label: "2026-10-20", status: "DATED", days_remaining: 11 })],
    live_patch: patchBlock() };
  const cleanDevice: LifecycleDevice = { ...row("member-b"), license_status: "COLLECTED", nearest_license: lic({ expiry: "2027-02-02", status: "DATED" }), live_patch: patchBlock({ ready: 0, items: [] }) };
  const plainDevice: LifecycleDevice = { ...row("device-3"), hostname: "FW-PAPA-03", vendor: "PALOALTO", license_status: "COLLECTED", live_patch: null };
  const unknownDevice: LifecycleDevice = { ...row("device-4"), hostname: "FW-OSCAR-05", vendor: "FORTINET", license_status: "UNKNOWN", live_patch: null,
    license_reads: [{ kind: "fgt_update_info_contract", outcome: "FAILED", reason: "TIMEOUT", item_count: 0, observed_at: null }] };
  const load = (devices: LifecycleDevice[]) => vi.mocked(api.getLifecycle).mockResolvedValue({ as_of: "2026-10-09", devices,
    summary: { devices: devices.length, license_problems: 1, live_patch_not_active: 1, licenses_collected: 3 } });

  it("shows the renamed cards and the compact Licenses and Live Patch columns", async () => {
    load([problemDevice, cleanDevice, plainDevice, unknownDevice]);
    themed(<LifecycleScreen />);
    const table = await screen.findByRole("table", { name: "Fleet lifecycle" });
    expect(screen.getByText("License problems")).toBeInTheDocument();
    expect(screen.getByText("Live Patch not active")).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Licenses" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Live Patch" })).toBeInTheDocument();
    expect(within(table).getByText("Problem")).toBeInTheDocument();
    expect(within(table).getAllByText("2027-02-02")).toHaveLength(2);
    expect(within(table).getByText("Collected")).toBeInTheDocument();
    expect(within(table).getByText("Unknown")).toBeInTheDocument();
    expect(within(table).getAllByText("take 42 · 3 armed")).toHaveLength(2);
    expect(within(table).getByText("· 1 not active")).toBeInTheDocument();
    expect(within(table).getAllByText("—").length).toBeGreaterThanOrEqual(2);
    expect(within(table).queryAllByText(/not active/)).toHaveLength(1);
  });

  it("groups the detail by category with the manager note, permanent and state words", async () => {
    load([problemDevice]);
    themed(<LifecycleScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "Details" }));
    const detail = await screen.findByRole("group", { name: /Details of FW-TANGO-04/ });
    for (const title of ["Subscriptions", "Support contracts"]) expect(within(detail).getByText(title)).toBeInTheDocument();
    expect(within(detail).getByText("CPSB-FW · Permanent")).toBeInTheDocument();
    expect(within(detail).getByText("CPSB-IPS · 2026-10-06 · EXPIRED · expired 3 days ago")).toBeInTheDocument();
    expect(within(detail).getByText("AV · 2026-11-01 · 23 days remaining")).toBeInTheDocument();
    expect(within(detail).getByText(/^Smart Licensing · UNREGISTERED$/)).toBeInTheDocument();
    expect(within(detail).getByText(/Web Filter · FW-BRAVO-02 · 2026-10-20 · 11 days remaining/)).toBeInTheDocument();
    expect(within(detail).getAllByText(/reported by manager/)).toHaveLength(1);
    expect(within(detail).getByText("License problems: CPSB-IPS (EXPIRED)")).toBeInTheDocument();
  });

  it("lists the Live Patch rows with ready shown as installed, not active, and references as text", async () => {
    load([problemDevice]);
    themed(<LifecycleScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "Details" }));
    const list = await screen.findByRole("table", { name: "Live Patch patches" });
    expect(within(list).getByText("PATCH-SYNTH-02")).toBeInTheDocument();
    expect(within(list).getByText("installed, not active")).toBeInTheDocument();
    expect(within(list).queryByText("ready")).toBeNull();
    expect(within(list).getByText("CVE-2026-0001, sk000001")).toBeInTheDocument();
    expect(within(list).getByText("0 of 2")).toBeInTheDocument();
    expect(screen.getByText(/Package take 42 · Jumbo Jumbo Hotfix Take 99 · read COLLECTED/)).toBeInTheDocument();
  });

  it("shows the read outcomes when collection is UNKNOWN", async () => {
    load([unknownDevice]);
    themed(<LifecycleScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "Details" }));
    expect(await screen.findByText(/Read fgt_update_info_contract: FAILED \(TIMEOUT\)/)).toBeInTheDocument();
  });

  it("exports the license problem, the collection per kind and the Live Patch columns", () => {
    const csv = lifecycleCsv([{ ...problemDevice, license_reads: [{ kind: "cp_cplic_print", outcome: "COLLECTED", reason: "OK", item_count: 5, observed_at: null }] }, plainDevice]);
    const [head, first, second] = csv.trim().split("\r\n");
    for (const h of ["License problem", "License collection by kind", "Live Patch package take", "Live Patch armed", "Live Patch ready", "Live Patch jumbofix"]) expect(head).toContain(h);
    expect(first.endsWith(`yes,cp_cplic_print: COLLECTED,42,3,1,1`)).toBe(true);
    expect(second.endsWith(`no,,,,,`)).toBe(true);
  });
});

describe("catalog administration", () => {
  it("masked read-only users see no mutation controls", async () => {
    themed(<LifecycleCatalogPanel onChanged={vi.fn()} />);
    expect(await screen.findByText("Catalog is read-only for this session.")).toBeInTheDocument();
    expect(screen.queryByLabelText("Import lifecycle CSV")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add row" })).not.toBeInTheDocument();
  });
  it("admins can add, edit and delete rows through the existing authenticated API", async () => {
    vi.mocked(api.getLifecycleCatalog).mockResolvedValue({ can_manage: true, entries: [entry] });
    vi.mocked(api.saveLifecycleCatalog).mockResolvedValue({});
    vi.mocked(api.deleteLifecycleCatalog).mockResolvedValue({});
    const changed = vi.fn();
    themed(<LifecycleCatalogPanel onChanged={changed} />);
    fireEvent.change(await screen.findByLabelText(/^Product/), { target: { value: "Example New Appliance" } });
    fireEvent.click(screen.getByRole("button", { name: "Add row" }));
    await waitFor(() => expect(api.saveLifecycleCatalog).toHaveBeenCalledWith(expect.objectContaining({ product: "Example New Appliance" }), undefined));
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    fireEvent.change(screen.getByLabelText("end of support"), { target: { value: "2031-01-01" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(api.saveLifecycleCatalog).toHaveBeenCalledWith(expect.objectContaining({ end_of_support: "2031-01-01" }), "row-1"));
    await waitFor(() => expect(screen.getByRole("button", { name: "Delete" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(api.deleteLifecycleCatalog).toHaveBeenCalledWith("row-1"));
    await waitFor(() => expect(changed).toHaveBeenCalledTimes(3));
  });
  it("reports CSV row errors and keeps the catalog visible", async () => {
    vi.mocked(api.getLifecycleCatalog).mockResolvedValue({ can_manage: true, entries: [entry] });
    vi.mocked(api.importLifecycleCatalog).mockRejectedValue({ status: 400, body: { error: "INVALID_CSV", errors: [{ row: 3, reason: "Bad date" }] } });
    const changed = vi.fn();
    themed(<LifecycleCatalogPanel onChanged={changed} />);
    const file = new File(["synthetic csv"], "catalog.csv", { type: "text/csv" });
    Object.defineProperty(file, "text", { value: async () => "synthetic csv" });
    fireEvent.change(await screen.findByLabelText("Import lifecycle CSV"), { target: { files: [file] } });
    expect(await screen.findByText("Row 3: Bad date")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Lifecycle catalog" })).toBeInTheDocument();
    expect(changed).not.toHaveBeenCalled();
  });
});

describe("livePatchCell", () => {
  const patch = (over: Record<string, unknown>) => ({ live_patch: { package_take: "30", jumbo: null, read_outcome: null, observed_at: null,
    armed: 0, ready: 0, jumbofix: 0, other: 0, items: [], ...over } }) as unknown as LifecycleDevice;
  it("says the armed count only when the patch list was read", () => {
    expect(livePatchCell(patch({ read_outcome: "COLLECTED", armed: 3, ready: 1 }))).toBe("take 30 · 3 armed · 1 not active");
    expect(livePatchCell(patch({}))).toBe("take 30 · patches not read");
    expect(livePatchCell(patch({ read_outcome: "FAILED" }))).toBe("take 30 · patches not read");
    expect(livePatchCell(patch({ package_take: null }))).toBe("Not collected");
    expect(livePatchCell({ live_patch: null } as unknown as LifecycleDevice)).toBe("—");
  });
});
