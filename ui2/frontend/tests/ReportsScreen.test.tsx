import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReportsScreen } from "../src/screens/ReportsScreen";
import type { ReportCatalogue, ReportDocumentView, ReportRunView } from "../src/auth/adminApi";

// Masked fixtures only (REPORTS_R1_CONTRACT.md "UI"): pseudonyms and 192.0.2.x.
const templates = [
  { template_id: "RPT-01", title: "Executive weekly summary", audience: "Executive", question: "How is the estate doing this week?", formats: ["PDF", "XLSX", "CSV"], framework_parameter: false, missing_reads: [] },
  { template_id: "RPT-02", title: "Framework compliance and evidence coverage", audience: "Auditor", question: "How do the firewalls comply?", formats: ["PDF", "XLSX", "CSV"], framework_parameter: true, missing_reads: [] },
  { template_id: "RPT-03", title: "Backup coverage and anomalies", audience: "Operations", question: "Which targets have an archive?", formats: ["PDF", "XLSX", "CSV"], framework_parameter: false, missing_reads: [] },
  { template_id: "RPT-05", title: "HA and failover readiness", audience: "Operations", question: "Which clusters are ready?", formats: ["PDF", "XLSX", "CSV"], framework_parameter: false, missing_reads: ["cp_failover_read"] },
] as const;

function catalogue(rawView: boolean, masked: boolean): ReportCatalogue {
  return { templates, frameworks: ["CIS", "NIST_800_53", "PCI_DSS", "FINANCIAL_BASELINE"], permissions: { masked, raw_view: rawView }, retention_days: 30 };
}

const doc: ReportDocumentView = {
  template_id: "RPT-03", title: "Backup coverage and anomalies", audience: "Operations", question: "Which targets have an archive?",
  generated_at: "2026-11-05T06:00:00Z", projection: "MASKED", parameters: { from: "2026-10-30", to: "2026-11-05" },
  data_as_of: [{ plane: "backup", newest: "2026-11-05T02:00:00Z", oldest: "2026-11-03T02:00:00Z", covered: 2, total: 3, stale: 1, unit: "backup targets" }],
  notices: ["STALE: 1 of 2 backup targets with backup evidence have evidence older than 24 h."],
  sections: [{ id: "coverage", title: "Backup coverage", data_as_of: [], kpis: [{ label: "Without an archive", value: "1", tone: "BAD" }],
    tables: [{ id: "targets", title: "Backup targets", columns: [{ key: "device", label: "Device" }, { key: "state", label: "State" }],
      rows: [[{ text: "FW-TANGO-04", kind: "DEVICE" }, { text: "NO ARCHIVE", kind: "STATUS" }]], empty_text: "none" }],
    notes: [] }],
};

function run(over: Partial<ReportRunView> = {}): ReportRunView {
  return { run_id: "11111111-2222-4333-8444-555555555555", template_id: "RPT-03", title: "Backup coverage and anomalies", state: "SUCCEEDED",
    projection: "MASKED", format: "PDF", parameters: { from: "2026-10-30", to: "2026-11-05" },
    data_as_of: { planes: doc.data_as_of }, plaintext_bytes: 2048, error_code: null, requested_at: "2026-11-05T06:00:00Z",
    finished_at: "2026-11-05T06:00:02Z", expires_at: "2026-12-05T06:00:00Z", downloadable: true, mine: true, ...over };
}

interface Call { url: string; method: string; body: unknown }

function server(opts: { rawView?: boolean; masked?: boolean; runs?: ReportRunView[] } = {}) {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
    if (url === "/session/status") return json({ csrf_token: "csrf" });
    if (url === "/api/v2/reports/catalogue") return json(catalogue(opts.rawView ?? false, opts.masked ?? true));
    if (url === "/api/v2/reports/preview") return json(doc);
    if (url === "/api/v2/reports/runs" && method === "POST") return json(run({ state: "SUCCEEDED" }), 202);
    if (url === "/api/v2/reports/runs") return json({ runs: opts.runs ?? [], permissions: { masked: opts.masked ?? true, raw_view: opts.rawView ?? false } });
    return json({}, 404);
  }));
  return calls;
}

afterEach(() => vi.unstubAllGlobals());

describe("Reports screen (Reports R1)", () => {
  it("shows the three tabs and a catalogue card per report with its audience and question", async () => {
    server();
    render(<ReportsScreen />);
    const tabs = screen.getByRole("tablist", { name: "Reports sections" });
    expect(within(tabs).getAllByRole("tab").map(t => t.textContent)).toEqual(["Catalogue", "Run & preview", "History"]);
    expect(await screen.findByText("Executive weekly summary")).toBeInTheDocument();
    expect(screen.getByText("RPT-02 · Auditor")).toBeInTheDocument();
    expect(screen.getByText("How do the firewalls comply?")).toBeInTheDocument();
    // a report whose underlying domain read is missing says so and cannot be opened
    expect(screen.getByText("Needs read access: cp_failover_read")).toBeInTheDocument();
  });

  it("previews on screen with data as of, stale notices and the masked stamp, and offers PDF, XLSX and CSV", async () => {
    const calls = server();
    render(<ReportsScreen />);
    await screen.findByText("Executive weekly summary");
    fireEvent.click(screen.getByRole("tab", { name: "Run & preview" }));
    for (const f of ["PDF", "XLSX", "CSV"]) expect(screen.getByRole("button", { name: `Generate ${f}` })).toBeInTheDocument();
    // without report_raw_view the projection is not offered: the server decides, the screen only follows
    expect(screen.queryByLabelText("Projection")).toBeNull();
    expect(screen.getByText("Projection: masked")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Preview" }));
    expect(await screen.findByText("FW-TANGO-04")).toBeInTheDocument();
    expect(screen.getByText("MASKED")).toBeInTheDocument();
    expect(screen.getByText(/backup: 2 of 3 backup targets with evidence/)).toBeInTheDocument();
    expect(screen.getByText(/STALE: 1 of 2 backup targets/)).toBeInTheDocument();
    const preview = calls.find(c => c.url === "/api/v2/reports/preview")!;
    expect(preview.method).toBe("POST");
    expect(preview.body).toMatchObject({ template_id: "RPT-01", projection: "MASKED" });
  });

  it("asks for a framework only on RPT-02 and sends it with the request", async () => {
    const calls = server();
    render(<ReportsScreen />);
    await screen.findByText("Executive weekly summary");
    fireEvent.click(screen.getAllByRole("button", { name: "Run & preview" })[1]);
    expect(screen.getByLabelText("Framework")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Generate CSV" }));
    await waitFor(() => expect(calls.some(c => c.url === "/api/v2/reports/runs" && c.method === "POST")).toBe(true));
    const post = calls.find(c => c.url === "/api/v2/reports/runs" && c.method === "POST")!;
    expect(post.body).toMatchObject({ template_id: "RPT-02", format: "CSV", framework: "ALL", projection: "MASKED" });
    expect(await screen.findByText(/stays available for 30 days/)).toBeInTheDocument();
  });

  it("offers RAW only when the server grants report_raw_view", async () => {
    server({ rawView: true, masked: false });
    render(<ReportsScreen />);
    await screen.findByText("Executive weekly summary");
    fireEvent.click(screen.getByRole("tab", { name: "Run & preview" }));
    expect(screen.getByLabelText("Projection")).toBeInTheDocument();
  });

  it("lists history with state, data as of, projection and a download link only within retention", async () => {
    server({ runs: [run(), run({ run_id: "22222222-2222-4333-8444-555555555555", downloadable: false }),
      run({ run_id: "33333333-2222-4333-8444-555555555555", state: "FAILED", error_code: "REPORT_TOO_LARGE", downloadable: false, data_as_of: {} })] });
    render(<ReportsScreen />);
    fireEvent.click(screen.getByRole("tab", { name: "History" }));
    const table = await screen.findByRole("table", { name: "Report history" });
    const links = within(table).getAllByRole("link", { name: "Download" });
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute("href", "/api/v2/reports/runs/11111111-2222-4333-8444-555555555555/download");
    expect(within(table).getByText("Expired")).toBeInTheDocument();
    expect(within(table).getByText("REPORT_TOO_LARGE")).toBeInTheDocument();
    expect(within(table).getAllByText(/backup: 2 of 3 backup targets with evidence/).length).toBe(2);
    expect(within(table).getByText("UNKNOWN")).toBeInTheDocument();
    expect(screen.getByText(/Only masked reports are listed for this session/)).toBeInTheDocument();
  });
});
