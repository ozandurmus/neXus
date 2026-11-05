import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { JobLogsPanel, MASKED_SEARCH_NOTE, MASKED_TARGET_SORT_NOTE, jobTargetLabel, localInputToIso } from "../src/screens/JobLogsPanel";
import { jobTypeLabel } from "../src/jobs/jobTypeLabel";

// The panel writes `?job=<id>` when a row opens; jsdom keeps the URL between tests, so it is reset here.
/** The Failed state opens on the last seven days (UI review 2026-11-05, B6): its page request carries a `since`. */
const failedWindowRequest = (url: string) => /^\/api\/v2\/jobs\?state=FAILED&since=[^&]+&page=1&page_size=50$/.test(url);

afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

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
  ["asa_inventory_collect", undefined, "Cisco ASA inventory read"],
  ["cp_configuration_collect", undefined, "Check Point configuration read"],
  ["pan_config_backup", undefined, "Palo Alto backup"],
  ["fgt_gateway_backup", undefined, "FortiGate backup"],
  ["fmg_discovery_enumerate", undefined, "FortiManager discovery"],
  ["device_confirm_https", "cisco_asa", "Cisco ASA identity check"],
  ["https_diagnostic", "fortinet", "Fortinet diagnostic read"],
  ["rdw_cc_config_backup", undefined, "Radware Cyber Controller backup"],
  ["bcmc_discovery_enumerate", undefined, "Blue Coat discovery"],
  ["proxysg_configuration_collect", undefined, "Blue Coat configuration read"],
  ["ib_diagnostic", undefined, "Infoblox diagnostic read"],
  ["pulse_inventory_collect", undefined, "Pulse Secure inventory read"],
  ["device_confirm_cisco_asa", undefined, "Cisco ASA identity check"],
  ["https_inventory_collect", undefined, "HTTPS inventory read"],
  ["pan_failover_readiness", undefined, "Palo Alto readiness check"],
  ["cp_system_log_collect", undefined, "Check Point log collection"],
  ["unknown_capability", undefined, "unknown_capability"],
])("labels %s", (id, vendor, expected) => {
  expect(jobTypeLabel(id, vendor)).toBe(expected);
});

it("keeps job and device UUIDs in the expandable details", async () => {
  const jobId = "00000000-0000-4000-8000-000000000001";
  const deviceId = "00000000-0000-4000-8000-000000000002";
  stubApi({ items: [{ job_id: jobId, target_device_id: deviceId, job_type: "asa_inventory_collect", state: "FAILED", terminal_reason: "COLLECTION_FAILED" }], page: 1, page_size: 50, total: 1 });
  render(<JobLogsPanel />);
  await screen.findByText("No target recorded");
  expect(screen.getByText("Cisco ASA inventory read")).toBeInTheDocument();
  // the reason in words, the raw code on hover (post-release review 2026-10-18, R8)
  expect(screen.getByText("Collection failed")).toHaveAttribute("data-raw", "COLLECTION_FAILED");
  expect(screen.queryByText(jobId)).toBeNull();
  expect(screen.queryByText(deviceId)).toBeNull();
  fireEvent.click(screen.getByText("No target recorded").closest("tr")!);
  expect(await screen.findByText(jobId)).toBeInTheDocument();
  expect(screen.getByText(deviceId)).toBeInTheDocument();
});

it("names every job target in masked form (device, cluster/failover unit, policy source/container, fleet) and says No target recorded only when none exists", async () => {
  expect(jobTargetLabel({ job_type: "cp_inventory_collect", target_device_id: "d1", device_name: "FW-TANGO-04" })).toBe("FW-TANGO-04");
  expect(jobTargetLabel({ job_type: "cp_failover_readiness", target_device_id: "d1" }, { d1: { name: "FW-ROMEO-M1", cluster: "CLS-ROMEO-01", vendor: "check_point" } })).toBe("CLS-ROMEO-01");
  expect(jobTargetLabel({ job_type: "cp_cluster_failover", target_device_id: "d1", device_name: "VS-ROMEO-01-V1", target_kind: "failover_unit" })).toBe("VS-ROMEO-01-V1");
  expect(jobTargetLabel({ job_type: "cp_policy_collect", target_device_id: null, device_name: "DOM-ALPHA-01", target_kind: "policy_container" })).toBe("DOM-ALPHA-01");
  expect(jobTargetLabel({ job_type: "pan_policy_collect", target_device_id: "mgr-1", device_name: "FW-BRAVO-02", target_kind: "policy_source" })).toBe("FW-BRAVO-02");
  expect(jobTargetLabel({ job_type: "cp_system_log_collect", target_device_id: null, target_device_count: 24, target_kind: "fleet" })).toBe("Fleet (24 devices)");
  expect(jobTargetLabel({ job_type: "cp_policy_collect", target_device_id: null, device_name: null })).toBe("No target recorded");

  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input);
    if (url.startsWith("/api/v2/jobs/facets")) return Promise.resolve(new Response(JSON.stringify({ states: ["COMPLETED"], job_types: [] })));
    if (url.startsWith("/api/v2/jobs")) {
      return Promise.resolve(new Response(JSON.stringify({
        items: [
          { job_id: "j-del", target_device_id: "deleted-dev", device_name: "FW-JULIET-06", target_kind: "device", job_type: "cp_inventory_collect", state: "COMPLETED", submitted_at: "2026-10-18T03:00:00Z", has_transcript: false },
          { job_id: "j-cls", target_device_id: "member-01", job_type: "pan_failover_readiness", state: "COMPLETED", submitted_at: "2026-10-18T03:01:00Z", has_transcript: false },
          { job_id: "j-dom", target_device_id: "mgr-01", device_name: "DOM-ALPHA-01", target_kind: "policy_container", job_type: "cp_policy_collect", state: "COMPLETED", submitted_at: "2026-10-18T03:02:00Z", has_transcript: false },
          { job_id: "j-flt", target_device_id: null, device_name: "Fleet (12 devices)", target_kind: "fleet", job_type: "cp_system_log_collect", state: "COMPLETED", submitted_at: "2026-10-18T03:03:00Z", has_transcript: false },
          { job_id: "j-non", target_device_id: null, device_name: null, job_type: "cp_policy_collect", state: "FAILED", submitted_at: "2026-10-18T03:04:00Z", has_transcript: false },
        ],
        page: 1,
        page_size: 50,
        total: 5,
      })));
    }
    if (url === "/devices") {
      return Promise.resolve(new Response(JSON.stringify({
        devices: [{ device_id: "member-01", hostname: "FW-ROMEO-M1", cluster_member_ref: "CLS-ROMEO-01", vendor_hint: "palo_alto" }],
      })));
    }
    return Promise.resolve(new Response("{}", { status: 404 }));
  }));

  render(<JobLogsPanel />);
  const table = await screen.findByRole("table", { name: "Job logs" });
  await waitFor(() => expect(within(table).getByText("CLS-ROMEO-01")).toBeInTheDocument());
  expect(within(table).getByText("FW-JULIET-06")).toBeInTheDocument();
  expect(within(table).getByText("DOM-ALPHA-01")).toBeInTheDocument();
  expect(within(table).getByText("Fleet (12 devices)")).toBeInTheDocument();
  expect(within(table).getByText("No target recorded")).toBeInTheDocument();
  expect(within(table).queryByText("Unknown device")).toBeNull();
});

describe("history, filters, pages and export (PO P0, 2026-09-22)", () => {
  // The one pagination footer (post-release review 2026-10-20, N15): range words, Per page, previous/next and first/last.
  it("pages the whole history on the server through the one pagination footer", async () => {
    const calls = stubApi({ items: [{ job_id: "job-9", target_device_id: "opaque-001", job_type: "inventory", state: "FAILED" }], page: 1, page_size: 50, total: 137 });

    render(<JobLogsPanel />);

    await waitFor(() => expect(screen.getByText("1–50 of 137 jobs")).toBeInTheDocument());
    expect(calls.some((c) => c === "/api/v2/jobs?page=1&page_size=50")).toBe(true);
    const footer = screen.getByTestId("pagination-footer");
    expect(within(footer).getByRole("button", { name: "Go to first page" })).toBeDisabled();
    expect(within(footer).getByRole("button", { name: "Go to previous page" })).toBeDisabled();

    // 137 rows at 50 per page: the last page is page 3
    fireEvent.click(within(footer).getByRole("button", { name: "Go to last page" }));
    await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?page=3&page_size=50")).toBe(true));
    fireEvent.click(within(footer).getByRole("button", { name: "Go to first page" }));
    fireEvent.click(within(footer).getByRole("button", { name: "Go to next page" }));
    await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?page=2&page_size=50")).toBe(true));

    fireEvent.change(within(footer).getByRole("combobox", { name: "Rows per page" }), { target: { value: "100" } });
    await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?page=1&page_size=100")).toBe(true));
  });

  it("sends the state filter to the server and returns to page one", async () => {
    const calls = stubApi({ items: [], page: 1, page_size: 50, total: 0 });

    render(<JobLogsPanel />);
    await waitFor(() => expect(screen.getByLabelText("State")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("State"), { target: { value: "FAILED" } });
    await waitFor(() => expect(calls.some((c) => failedWindowRequest(c))).toBe(true));
  });

  it("exports the current filter as CSV through the export route", async () => {
    const calls = stubApi({ items: [], page: 1, page_size: 50, total: 0 });
    vi.stubGlobal("URL", { ...URL, createObjectURL: vi.fn(() => "blob:x"), revokeObjectURL: vi.fn() });

    render(<JobLogsPanel />);
    await waitFor(() => expect(screen.getByLabelText("State")).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText("State"), { target: { value: "FAILED" } });
    fireEvent.click(screen.getByRole("button", { name: "Export CSV" }));

    await waitFor(() => expect(calls.some((c) => /^\/api\/v2\/jobs\/export\.csv\?state=FAILED&since=/.test(c))).toBe(true));
  });
});

it("turns a local datetime input into an ISO instant and leaves an empty one empty", () => {
  expect(localInputToIso("")).toBeUndefined();
  expect(localInputToIso("2026-09-22T14:00")).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
});

it("offers cancellation only for permitted active jobs and submits the opaque job id", async () => {
  const fetch = vi.fn(async (input: string, init?: RequestInit) => {
    const body = input === "/session/status" ? { csrf_token: "synthetic-csrf" }
      : input.endsWith("/cancel") ? { jobId: "job-1", state: "EXECUTING", cancelRequested: true }
      : input.startsWith("/api/v2/jobs/facets") ? { states: [], job_types: [] }
      : input.startsWith("/api/v2/jobs") ? { can_cancel: true, total: 2, items: [
        { job_id: "job-1", state: "EXECUTING", target_device_id: "opaque-001", job_type: "inventory" },
        { job_id: "job-2", state: "COMPLETED", target_device_id: "opaque-001", job_type: "inventory" },
      ] } : { devices: [] };
    return new Response(JSON.stringify(body), { status: init?.method === "POST" ? 202 : 200 });
  });
  vi.stubGlobal("fetch", fetch);
  render(<JobLogsPanel />);
  const button = await screen.findByRole("button", { name: "Cancel" });
  expect(screen.getAllByRole("button", { name: "Cancel" })).toHaveLength(1);
  fireEvent.click(button);
  await waitFor(() => expect(fetch.mock.calls.some(([url, init]) => url === "/api/v2/jobs/job-1/cancel" && init?.method === "POST")).toBe(true));
  expect(await screen.findByRole("button", { name: "Cancel requested" })).toBeDisabled();
});

it("hides cancellation when the server has not granted it", async () => {
  stubApi({ items: [{ job_id: "job-1", state: "EXECUTING", job_type: "inventory", target_device_id: "opaque-001" }], total: 1 });
  render(<JobLogsPanel />);
  await screen.findByText("Running");
  expect(screen.queryByRole("button", { name: "Cancel" })).toBeNull();
});

it("cycles server sort asc -> desc -> default on header click and resets to page 1", async () => {
  const calls = stubApi({ items: [{ job_id: "job-9", target_device_id: "opaque-001", job_type: "inventory", state: "FAILED" }], page: 1, page_size: 50, total: 120 });
  render(<JobLogsPanel />);
  await waitFor(() => expect(screen.getByText("1–50 of 120 jobs")).toBeInTheDocument());

  fireEvent.click(within(screen.getByTestId("pagination-footer")).getByRole("button", { name: "Go to next page" }));
  await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?page=2&page_size=50")).toBe(true));

  const durationHeader = within(screen.getByRole("columnheader", { name: /Duration/i })).getByRole("button");
  fireEvent.click(durationHeader);
  await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?page=1&page_size=50&sort=duration&order=asc")).toBe(true));
  expect(screen.getByRole("columnheader", { name: /Duration/i })).toHaveAttribute("aria-sort", "ascending");

  fireEvent.click(durationHeader);
  await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?page=1&page_size=50&sort=duration&order=desc")).toBe(true));
  expect(screen.getByRole("columnheader", { name: /Duration/i })).toHaveAttribute("aria-sort", "descending");

  calls.length = 0;
  fireEvent.click(durationHeader);
  await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?page=1&page_size=50")).toBe(true));
  expect(screen.getByRole("columnheader", { name: /Duration/i })).not.toHaveAttribute("aria-sort");
});

// Security review 2026-11-05 M-2: the masked page says Target is not sortable and the search matches ids only.
it("offers no Target sort and says the search keeps to ids when the server marks the page masked", async () => {
  stubApi({ items: [{ job_id: "job-9", target_device_id: "opaque-001", job_type: "inventory", state: "FAILED" }],
    page: 1, page_size: 50, total: 1, target_sortable: false, search_scope: "ids" });
  render(<JobLogsPanel />);
  await waitFor(() => expect(screen.getByText("1–1 of 1 jobs")).toBeInTheDocument());

  const target = screen.getByRole("columnheader", { name: /Target Device/i });
  expect(within(target).queryByRole("button")).toBeNull();
  expect(target).toHaveAttribute("title", MASKED_TARGET_SORT_NOTE);
  // The other columns still sort.
  expect(within(screen.getByRole("columnheader", { name: /Duration/i })).getByRole("button")).toBeInTheDocument();
  expect(screen.getByText(`${MASKED_TARGET_SORT_NOTE} ${MASKED_SEARCH_NOTE}`)).toBeInTheDocument();
  expect(screen.getByPlaceholderText("job id or device id…")).toBeInTheDocument();
});

it("keeps the Target sort and the full search when the page is not masked", async () => {
  const calls = stubApi({ items: [{ job_id: "job-9", target_device_id: "opaque-001", job_type: "inventory", state: "FAILED" }],
    page: 1, page_size: 50, total: 1 });
  render(<JobLogsPanel />);
  await waitFor(() => expect(screen.getByText("1–1 of 1 jobs")).toBeInTheDocument());

  fireEvent.click(within(screen.getByRole("columnheader", { name: /Target Device/i })).getByRole("button"));
  await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?page=1&page_size=50&sort=device&order=asc")).toBe(true));
  expect(screen.queryByText(MASKED_SEARCH_NOTE, { exact: false })).toBeNull();
  expect(screen.getByPlaceholderText("job id, device id or reason…")).toBeInTheDocument();
});
