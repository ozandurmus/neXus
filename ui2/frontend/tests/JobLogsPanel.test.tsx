import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { JobLogsPanel, localInputToIso } from "../src/screens/JobLogsPanel";
import { jobTypeLabel } from "../src/jobs/jobTypeLabel";

afterEach(() => vi.unstubAllGlobals());

function stubApi(jobsPage: unknown, calls: string[] = []) {
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input);
    calls.push(url);
    if (url.startsWith("/api/v2/jobs/facets")) {
      return Promise.resolve(new Response(JSON.stringify({ states: ["COMPLETED", "FAILED"], job_types: ["inventory", "cp_gateway_backup"] })));
    }
    if (url.startsWith("/api/v2/jobs")) return Promise.resolve(new Response(JSON.stringify(jobsPage)));
    if (url === "/devices") return Promise.resolve(new Response(JSON.stringify({ devices: [{ device_id: "opaque-001", hostname: "FW-TANGO-04" }] })));
    return Promise.resolve(new Response("{}", { status: 404 }));
  }));
  return calls;
}

it("shows the recorded device name without its identifier in the main row", async () => {
  stubApi({ items: [{ job_id: "job-1", target_device_id: "opaque-001", job_type: "inventory", state: "COMPLETED" }], page: 1, page_size: 50, total: 1 });

  render(<JobLogsPanel />);

  await waitFor(() => expect(screen.getAllByText("FW-TANGO-04").some((node) => node.tagName === "TD")).toBe(true));
  expect(screen.queryByText("opaque-001")).toBeNull();
  fireEvent.click(screen.getAllByText("FW-TANGO-04").find((node) => node.tagName === "TD")!.closest("tr")!);
  expect(await screen.findByText("opaque-001")).toBeInTheDocument();
  expect(screen.getByText("1–1 of 1 jobs")).toBeInTheDocument();
});

it.each([
  ["asa_inventory_collect", undefined, "Cisco ASA · read inventory"],
  ["cp_configuration_collect", undefined, "Check Point · read configuration"],
  ["pan_config_backup", undefined, "Palo Alto · backup"],
  ["fgt_gateway_backup", undefined, "FortiGate · backup"],
  ["fmg_discovery_enumerate", undefined, "FortiManager · discovery"],
  ["device_confirm_https", "cisco_asa", "Cisco ASA · identity check"],
  ["https_diagnostic", "fortinet", "FortiGate · diagnostic read"],
  ["rdw_cc_config_backup", undefined, "Radware Cyber Controller · backup"],
  ["bcmc_discovery_enumerate", undefined, "Symantec · discovery"],
  ["proxysg_configuration_collect", undefined, "Symantec · read configuration"],
  ["ib_diagnostic", undefined, "Infoblox · diagnostic read"],
  ["pulse_inventory_collect", undefined, "Pulse Secure · read inventory"],
  ["device_confirm_cisco_asa", undefined, "Cisco ASA · identity check"],
  ["https_inventory_collect", undefined, "read inventory"],
  ["unknown_capability", undefined, "unknown_capability"],
])("labels %s", (id, vendor, expected) => {
  expect(jobTypeLabel(id, vendor)).toBe(expected);
});

it.each([
  ["device_confirm_check_point", "Check Point · identity check"], ["device_confirm_palo_alto", "Palo Alto · identity check"],
  ["device_confirm_https", "identity check"], ["device_confirm_cisco_asa", "Cisco ASA · identity check"],
  ["device_confirm_fortigate", "FortiGate · identity check"], ["cp_configuration_collect", "Check Point · read configuration"],
  ["pan_configuration_collect", "Palo Alto · read configuration"], ["fgt_configuration_collect", "FortiGate · read configuration"],
  ["asa_configuration_collect", "Cisco ASA · read configuration"], ["proxysg_configuration_collect", "Symantec · read configuration"],
  ["cp_gateway_backup", "Check Point · backup"], ["cp_gaia_snapshot", "Check Point · backup"], ["pan_device_state_backup", "Palo Alto · backup"],
  ["pan_set_config_read", "Palo Alto · backup"], ["cp_mds_export", "Check Point MDS · backup"], ["https_vendor_backup", "backup"],
  ["rdw_cc_config_backup", "Radware Cyber Controller · backup"], ["asa_config_backup", "Cisco ASA · backup"], ["fgt_config_backup", "FortiGate · backup"],
  ["cp_inventory_collect", "Check Point · read inventory"], ["pan_inventory_collect", "Palo Alto · read inventory"],
  ["https_inventory_collect", "read inventory"], ["asa_inventory_collect", "Cisco ASA · read inventory"], ["fgt_inventory_collect", "FortiGate · read inventory"],
  ["cp_discovery_enumerate", "Check Point · discovery"], ["pan_discovery_enumerate", "Palo Alto · discovery"],
  ["rdw_discovery_enumerate", "Radware · discovery"], ["fmg_discovery_enumerate", "FortiManager · discovery"],
  ["bcmc_discovery_enumerate", "Symantec · discovery"],
])("labels worker capability %s", (id, expected) => expect(jobTypeLabel(id)).toBe(expected));

it("keeps job and device UUIDs in the expandable details", async () => {
  const jobId = "00000000-0000-4000-8000-000000000001";
  const deviceId = "00000000-0000-4000-8000-000000000002";
  stubApi({ items: [{ job_id: jobId, target_device_id: deviceId, job_type: "asa_inventory_collect", state: "FAILED", terminal_reason: "COLLECTION_FAILED" }], page: 1, page_size: 50, total: 1 });
  render(<JobLogsPanel />);
  await screen.findByText("Unknown device");
  expect(screen.getByText("Cisco ASA · read inventory")).toBeInTheDocument();
  expect(screen.getByText("COLLECTION_FAILED")).toBeInTheDocument();
  expect(screen.queryByText(jobId)).toBeNull();
  expect(screen.queryByText(deviceId)).toBeNull();
  fireEvent.click(screen.getByText("Unknown device").closest("tr")!);
  expect(await screen.findByText(jobId)).toBeInTheDocument();
  expect(screen.getByText(deviceId)).toBeInTheDocument();
});

describe("history, filters, pages and export (PO P0, 2026-09-22)", () => {
  it("pages the whole history on the server and shows numbered pages", async () => {
    const calls = stubApi({ items: [{ job_id: "job-9", target_device_id: "opaque-001", job_type: "inventory", state: "FAILED" }], page: 1, page_size: 50, total: 137 });

    render(<JobLogsPanel />);

    await waitFor(() => expect(screen.getByText("1–50 of 137 jobs")).toBeInTheDocument());
    expect(calls.some((c) => c === "/api/v2/jobs?page=1&page_size=50")).toBe(true);
    // 137 rows at 50 per page = 3 numbered pages
    expect(screen.getByRole("button", { name: "Go to page 3" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Go to page 3" }));
    await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?page=3&page_size=50")).toBe(true));
  });

  it("sends the state filter to the server and returns to page one", async () => {
    const calls = stubApi({ items: [], page: 1, page_size: 50, total: 0 });

    render(<JobLogsPanel />);
    await waitFor(() => expect(screen.getByLabelText("State")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("State"), { target: { value: "FAILED" } });
    await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?state=FAILED&page=1&page_size=50")).toBe(true));
  });

  it("exports the current filter as CSV through the export route", async () => {
    const calls = stubApi({ items: [], page: 1, page_size: 50, total: 0 });
    vi.stubGlobal("URL", { ...URL, createObjectURL: vi.fn(() => "blob:x"), revokeObjectURL: vi.fn() });

    render(<JobLogsPanel />);
    await waitFor(() => expect(screen.getByLabelText("State")).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText("State"), { target: { value: "FAILED" } });
    fireEvent.click(screen.getByRole("button", { name: "Export CSV" }));

    await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs/export.csv?state=FAILED")).toBe(true));
  });
});

it("turns a local datetime input into an ISO instant and leaves an empty one empty", () => {
  expect(localInputToIso("")).toBeUndefined();
  expect(localInputToIso("2026-09-22T14:00")).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
});
