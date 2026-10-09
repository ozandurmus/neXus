import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { LifecycleScreen, DeviceLifecycleLine, lifecycleCsv } from "../src/screens/LifecycleScreen";
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
    summary: { devices: 2, no_lifecycle_data: 2, past_end_of_support: 0, within_180_days: 0, within_365_days: 0, licenses_within_60_days: 0 } });
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
    expect(within(table).getAllByText("Support contract not collected for this vendor")).toHaveLength(2);
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
    expect(await screen.findByText(/DNS · FW-BRAVO-02 · Never · UNKNOWN/)).toBeInTheDocument();
    expect(screen.getByText(/DHCP · FW-BRAVO-02 · 2026-10-20 · DATED/)).toBeInTheDocument();
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
