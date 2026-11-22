import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { EstateCasesScreen } from "../src/estate/EstateCasesScreen";
import { resetSharedApiCache } from "../src/auth/adminApi";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const CASE = {
  case_id: "case-tango-001",
  title: "FW-TANGO-04 capture",
  created_at: new Date(Date.now() - 2 * 3_600_000).toISOString(),
  expires_at: new Date(Date.now() + 86_400_000).toISOString(),
  state: "ANALYZED",
  total_bytes: 4096,
  capture_count: 1,
  payload_zeroing: false,
  latest_job_id: null,
};

const BUNDLE = {
  id: "bundle-1",
  device_id: "dev-1",
  device_pseudonym: "FW-TANGO-04",
  vendor: "check_point",
  run_mode: "now",
  run_at: CASE.created_at,
  requested_at: new Date(Date.now() - 3_600_000).toISOString(),
  finished_at: null,
  expires_at: CASE.expires_at,
  expiry_warning: false,
  downloaded_at: null,
  download_count: 0,
  state: "COMPLETED",
  outcome: null,
  items: [],
  case_ref: CASE.case_id,
};

describe("EstateCasesScreen", () => {
  beforeEach(() => resetSharedApiCache());
  afterEach(() => vi.unstubAllGlobals());

  it("lists cases read-only, links each action to the standard view and shows UNKNOWN for sanitiser and diagnostics", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/api/v2/packet-analysis/cases")) return json({ cases: [CASE], permissions: {}, limits: { max_file_bytes: 1, max_store_bytes: 1, used_bytes: 0, retention_days: 7 } });
      if (url.includes("/api/v2/support-bundles")) return json({ support_bundles: [BUNDLE] });
      return json({}, 404);
    }));
    render(<EstateCasesScreen />);
    const row = await screen.findByTestId("case-row");
    expect(within(row).getByText("FW-TANGO-04 capture")).toBeInTheDocument();
    expect(within(row).getByText("1 name this case")).toBeInTheDocument();
    expect(within(row).getAllByText("UNKNOWN")).toHaveLength(2);
    expect(within(row).getByRole("link", { name: /New capture/ })).toHaveAttribute("href", "?screen=packet_analysis&standard=1");
    expect(within(row).getByRole("link", { name: /Create bundle/ })).toHaveAttribute("href", "?screen=support_bundles&standard=1");
    expect(within(row).getByRole("link", { name: /Run diagnostics/ })).toHaveAttribute("href", "?screen=diagnostics&standard=1");
  });

  it("shows bundles as UNKNOWN when the bundle list cannot be read", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/api/v2/packet-analysis/cases")) return json({ cases: [CASE], permissions: {}, limits: { max_file_bytes: 1, max_store_bytes: 1, used_bytes: 0, retention_days: 7 } });
      return json({ error: "forbidden" }, 403);
    }));
    render(<EstateCasesScreen />);
    await waitFor(() => expect(screen.getByTestId("cases-counts")).toHaveTextContent("could not be read"));
    const row = screen.getByTestId("case-row");
    // sanitiser, diagnostics and the bundle link are all UNKNOWN
    expect(within(row).getAllByText("UNKNOWN")).toHaveLength(3);
    expect(within(row).queryByText(/name this case/)).toBeNull();
  });
});
