import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { resetSharedApiCache, type ScriptRunSummary, type ScriptSummary } from "../src/auth/adminApi";
import { JobLogsPanel } from "../src/screens/JobLogsPanel";
import { ScriptRunsSection } from "../src/screens/ScriptRunsSection";
import { m3Theme } from "../src/theme/m3Theme";

const OPERATOR_SESSION: SessionInfo = {
  displayName: "netops",
  roleTokens: ["role:operator"],
  canReadAdministration: false,
  permissions: ["Devices", "Config", "Compliance", "Operations"],
  onSignOut: () => {},
};

const VIEWER_SESSION: SessionInfo = {
  displayName: "auditor",
  roleTokens: ["role:viewer"],
  canReadAdministration: false,
  permissions: ["Devices", "Config", "Compliance"],
  onSignOut: () => {},
};

const AIVIEW_SESSION: SessionInfo = {
  displayName: "aiview",
  roleTokens: ["role:viewer", "role:replay_viewer"],
  canReadAdministration: false,
  permissions: ["Devices", "Config", "Compliance", "Policy", "PacketAnalysis"],
  onSignOut: () => {},
};

const SAMPLE_SCRIPT: ScriptSummary = {
  script_id: "scr-alpha-01",
  name: "dr-sync-verify",
  kind: "sh",
  current_version: 3,
  sha256: "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff",
  size_bytes: 256,
  secret_findings: { count: 0, line_numbers: [], findings: [] },
  enabled: true,
  schedule: "0 2 * * *",
  next_five_fires: [],
  timeout_s: 300,
  egress: ["192.0.2.10:443"],
  egress_device_targets: ["DEV-TANGO-04"],
  credential_refs: [],
  notify: "none",
  run_as: "role:operator",
  created_by_actor_fingerprint: "USR-0a1b2c3d4e",
  created_at: "2026-11-25T08:00:00Z",
  updated_at: "2026-11-25T08:30:00Z",
  warnings: [],
  versions: [],
};

const SAMPLE_RUN: ScriptRunSummary = {
  job_id: "run-401",
  run_id: "run-401",
  script_id: "scr-alpha-01",
  version: 3,
  state: "COMPLETED",
  outcome: "SUCCESS",
  trigger_kind: "SCHEDULED",
  scheduled_slot: "2026-11-25T02:00:00Z",
  run_as: "role:operator",
  requested_by: "system:script_scheduler",
  requested_at: "2026-11-25T02:00:01Z",
  started_at: "2026-11-25T02:00:02Z",
  finished_at: "2026-11-25T02:00:06Z",
  duration_ms: 4000,
  exit_code: 0,
  stdout_bytes: 262144,
  stderr_bytes: 128,
  stdout_truncated: true,
  stderr_truncated: false,
  has_output: true,
  output_sha256: "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
  output_size_bytes: 262320,
  output_tail: "=== STDOUT === [TRUNCATED AT 256 KiB]\nchecked FW-TANGO-04 (192.0.2.10): sync OK\n=== STDERR ===\n",
  egress: ["192.0.2.10:443"],
  egress_device_targets: ["DEV-TANGO-04"],
  credential_refs_used: [],
  failure_reason: null,
};

function renderJobsWithSession(session: SessionInfo | null) {
  return render(
    <ThemeProvider theme={m3Theme}>
      <SessionContext.Provider value={session}>
        <JobLogsPanel />
      </SessionContext.Provider>
    </ThemeProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  resetSharedApiCache();
  window.history.replaceState(null, "", "/");
});

describe("Operations > Jobs inline script_run output tail and audited download", () => {
  it("fetches getScriptRun only when a script_run row expands, renders metadata and the 4 KiB output_tail inline, and downloads full output with server audit notice for Operator", async () => {
    const calls: string[] = [];
    let downloadedUrl: string | null = null;
    const createObjectUrlSpy = vi.fn(() => "blob:job-script-output");
    const revokeObjectUrlSpy = vi.fn();
    vi.stubGlobal("URL", {
      ...URL,
      createObjectURL: createObjectUrlSpy,
      revokeObjectURL: revokeObjectUrlSpy,
    });
    const anchorClickSpy = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});

    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        calls.push(`${init?.method ?? "GET"} ${url}`);
        if (url.startsWith("/api/v2/jobs/stats")) {
          return Promise.resolve(
            new Response(
              JSON.stringify({ total: 2, total_24h: 2, completed_24h: 2, failed_24h: 0, running: 0, by_state: { COMPLETED: 2 } }),
              { status: 200 },
            ),
          );
        }
        if (url.startsWith("/api/v2/jobs/facets")) {
          return Promise.resolve(
            new Response(JSON.stringify({ states: ["COMPLETED"], job_types: ["script_run", "cp_inventory_collect"] }), { status: 200 }),
          );
        }
        if (url.startsWith("/api/v2/jobs")) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                items: [
                  {
                    job_id: "run-401",
                    job_type: "script_run",
                    target_device_id: null,
                    device_name: "dr-sync-verify",
                    target_kind: "script",
                    target_ref: "scr-alpha-01@3",
                    state: "COMPLETED",
                    outcome: "SUCCESS",
                    submitted_at: "2026-11-25T02:00:01Z",
                    finished_at: "2026-11-25T02:00:06Z",
                    duration_ms: 4000,
                    has_transcript: false,
                  },
                  {
                    job_id: "job-inv-1",
                    job_type: "cp_inventory_collect",
                    target_device_id: "dev-tango-04",
                    device_name: "FW-TANGO-04",
                    target_kind: "device",
                    state: "COMPLETED",
                    outcome: "SUCCESS",
                    submitted_at: "2026-11-25T01:00:00Z",
                    has_transcript: false,
                  },
                ],
                page: 1,
                page_size: 50,
                total: 2,
              }),
              { status: 200 },
            ),
          );
        }
        if (url === "/devices") {
          return Promise.resolve(new Response(JSON.stringify({ devices: [] }), { status: 200 }));
        }
        if (url === "/api/v2/scripts/runs/run-401") {
          return Promise.resolve(new Response(JSON.stringify(SAMPLE_RUN), { status: 200 }));
        }
        if (url === "/api/v2/scripts/runs/run-401/output") {
          downloadedUrl = url;
          return Promise.resolve(
            new Response("=== STDOUT ===\nfull masked output for FW-TANGO-04\n=== STDERR ===\n", {
              status: 200,
              headers: { "Content-Type": "text/plain" },
            }),
          );
        }
        return Promise.resolve(new Response("{}", { status: 404 }));
      }),
    );

    try {
      renderJobsWithSession(OPERATOR_SESSION);
      const table = await screen.findByRole("table", { name: "Job logs" });
      expect(within(table).getByText("dr-sync-verify")).toBeInTheDocument();

      // Collapsed rows and expanding a non-script job never call getScriptRun
      expect(calls.some((c) => c.includes("/api/v2/scripts/runs/"))).toBe(false);
      fireEvent.click(within(table).getByText("FW-TANGO-04").closest("tr")!);
      expect(await screen.findByText("job-inv-1")).toBeInTheDocument();
      expect(calls.some((c) => c.includes("/api/v2/scripts/runs/"))).toBe(false);

      // Expand the script_run row -> fetches GET /api/v2/scripts/runs/run-401
      fireEvent.click(within(table).getByText("dr-sync-verify").closest("tr")!);
      const pre = await screen.findByTestId("job-script-output-tail");
      expect(calls.filter((c) => c === "GET /api/v2/scripts/runs/run-401")).toHaveLength(1);
      expect(screen.getByText("Captured output tail (masked, up to 4 KiB)")).toBeInTheDocument();
      expect(pre.tagName).toBe("PRE");
      expect(pre).toHaveTextContent("checked FW-TANGO-04 (192.0.2.10): sync OK");

      const meta = screen.getByTestId("job-script-run-meta");
      expect(meta).toHaveTextContent("v3");
      expect(meta).toHaveTextContent("SCHEDULED");
      expect(meta).toHaveTextContent("Exit 0");
      expect(meta).toHaveTextContent("stdout 256.0 KB (truncated)");
      expect(meta).toHaveTextContent("stderr 128 B");

      // Audited output download
      expect(screen.getByTestId("job-script-output-audit-note")).toHaveTextContent(/recorded in the server audit log/i);
      fireEvent.click(screen.getByRole("button", { name: "Download run output" }));
      await waitFor(() => expect(downloadedUrl).toBe("/api/v2/scripts/runs/run-401/output"));
      expect(createObjectUrlSpy).toHaveBeenCalledTimes(1);
      expect(await screen.findByText(/Downloaded script-run-run-401\.log.*recorded in the server audit log/i)).toBeInTheDocument();

      // Collapsing removes the detail cleanly
      fireEvent.click(within(table).getByText("dr-sync-verify").closest("tr")!);
      expect(screen.queryByTestId("job-script-output-tail")).toBeNull();
    } finally {
      anchorClickSpy.mockRestore();
    }
  });

  it("renders an inline error alert when getScriptRun fails and re-fetches on Retry", async () => {
    let runAttempts = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith("/api/v2/jobs/stats")) {
          return Promise.resolve(new Response(JSON.stringify({ total: 1, total_24h: 1, completed_24h: 1, failed_24h: 0, running: 0, by_state: { COMPLETED: 1 } })));
        }
        if (url.startsWith("/api/v2/jobs/facets")) {
          return Promise.resolve(new Response(JSON.stringify({ states: ["COMPLETED"], job_types: ["script_run"] })));
        }
        if (url.startsWith("/api/v2/jobs")) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                items: [
                  {
                    job_id: "run-401",
                    job_type: "script_run",
                    device_name: "dr-sync-verify",
                    target_kind: "script",
                    target_ref: "scr-alpha-01@3",
                    state: "COMPLETED",
                    outcome: "SUCCESS",
                    submitted_at: "2026-11-25T02:00:01Z",
                    has_transcript: false,
                  },
                ],
                page: 1,
                page_size: 50,
                total: 1,
              }),
            ),
          );
        }
        if (url === "/devices") {
          return Promise.resolve(new Response(JSON.stringify({ devices: [] })));
        }
        if (url === "/api/v2/scripts/runs/run-401") {
          runAttempts += 1;
          if (runAttempts === 1) {
            return Promise.resolve(
              new Response(JSON.stringify({ message: "Script run store unavailable" }), { status: 503 }),
            );
          }
          return Promise.resolve(new Response(JSON.stringify(SAMPLE_RUN), { status: 200 }));
        }
        return Promise.resolve(new Response("{}", { status: 404 }));
      }),
    );

    renderJobsWithSession(OPERATOR_SESSION);
    const table = await screen.findByRole("table", { name: "Job logs" });
    fireEvent.click(within(table).getByText("dr-sync-verify").closest("tr")!);

    const alert = await screen.findByTestId("job-script-run-error");
    expect(alert).toHaveAttribute("role", "alert");
    expect(alert).toHaveTextContent("Could not load script run details: Script run store unavailable");
    expect(screen.queryByTestId("job-script-output-tail")).toBeNull();

    fireEvent.click(within(alert).getByRole("button", { name: "Retry" }));
    const pre = await screen.findByTestId("job-script-output-tail");
    expect(runAttempts).toBe(2);
    expect(pre).toHaveTextContent("checked FW-TANGO-04 (192.0.2.10): sync OK");
  });

  it("states empty output plainly without rendering a fake pre box", async () => {
    const emptyOutputRun: ScriptRunSummary = {
      ...SAMPLE_RUN,
      has_output: false,
      output_tail: null,
      stdout_bytes: 0,
      stderr_bytes: 0,
      stdout_truncated: false,
      stderr_truncated: false,
    };

    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith("/api/v2/jobs/stats")) {
          return Promise.resolve(new Response(JSON.stringify({ total: 1, total_24h: 1, completed_24h: 1, failed_24h: 0, running: 0, by_state: { COMPLETED: 1 } })));
        }
        if (url.startsWith("/api/v2/jobs/facets")) {
          return Promise.resolve(new Response(JSON.stringify({ states: ["COMPLETED"], job_types: ["script_run"] })));
        }
        if (url.startsWith("/api/v2/jobs")) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                items: [
                  {
                    job_id: "run-401",
                    job_type: "script_run",
                    device_name: "dr-sync-verify",
                    target_kind: "script",
                    target_ref: "scr-alpha-01@3",
                    state: "COMPLETED",
                    outcome: "SUCCESS",
                    submitted_at: "2026-11-25T02:00:01Z",
                    has_transcript: false,
                  },
                ],
                page: 1,
                page_size: 50,
                total: 1,
              }),
            ),
          );
        }
        if (url === "/devices") {
          return Promise.resolve(new Response(JSON.stringify({ devices: [] })));
        }
        if (url === "/api/v2/scripts/runs/run-401") {
          return Promise.resolve(new Response(JSON.stringify(emptyOutputRun), { status: 200 }));
        }
        return Promise.resolve(new Response("{}", { status: 404 }));
      }),
    );

    renderJobsWithSession(OPERATOR_SESSION);
    const table = await screen.findByRole("table", { name: "Job logs" });
    fireEvent.click(within(table).getByText("dr-sync-verify").closest("tr")!);
    expect(await screen.findByTestId("job-script-output-empty")).toHaveTextContent(
      "No captured output stored for this run.",
    );
    expect(screen.queryByTestId("job-script-output-tail")).toBeNull();
    expect(screen.queryByRole("button", { name: /Download run output/i })).toBeNull();
  });

  it("handles a server 403 refusal on downloadScriptRunOutput cleanly", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith("/api/v2/jobs/stats")) {
          return Promise.resolve(new Response(JSON.stringify({ total: 1, total_24h: 1, completed_24h: 1, failed_24h: 0, running: 0, by_state: { COMPLETED: 1 } })));
        }
        if (url.startsWith("/api/v2/jobs/facets")) {
          return Promise.resolve(new Response(JSON.stringify({ states: ["COMPLETED"], job_types: ["script_run"] })));
        }
        if (url.startsWith("/api/v2/jobs")) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                items: [
                  {
                    job_id: "run-401",
                    job_type: "script_run",
                    device_name: "dr-sync-verify",
                    target_kind: "script",
                    target_ref: "scr-alpha-01@3",
                    state: "COMPLETED",
                    outcome: "SUCCESS",
                    submitted_at: "2026-11-25T02:00:01Z",
                    has_transcript: false,
                  },
                ],
                page: 1,
                page_size: 50,
                total: 1,
              }),
            ),
          );
        }
        if (url === "/devices") {
          return Promise.resolve(new Response(JSON.stringify({ devices: [] })));
        }
        if (url === "/api/v2/scripts/runs/run-401") {
          return Promise.resolve(new Response(JSON.stringify(SAMPLE_RUN), { status: 200 }));
        }
        if (url === "/api/v2/scripts/runs/run-401/output") {
          return Promise.resolve(
            new Response(JSON.stringify({ message: "Action refused by policy" }), { status: 403 }),
          );
        }
        return Promise.resolve(new Response("{}", { status: 404 }));
      }),
    );

    renderJobsWithSession(OPERATOR_SESSION);
    const table = await screen.findByRole("table", { name: "Job logs" });
    fireEvent.click(within(table).getByText("dr-sync-verify").closest("tr")!);
    await screen.findByTestId("job-script-output-tail");
    fireEvent.click(screen.getByRole("button", { name: "Download run output" }));
    expect(await screen.findByText(/Output download refused \(HTTP 403\): Action refused by policy/i)).toBeInTheDocument();
  });

  it("hides raw output box and download button under a viewer session where output_tail is null, stating gating honestly", async () => {
    const withheldRun: ScriptRunSummary = {
      ...SAMPLE_RUN,
      output_tail: null,
    };
    const calls: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        calls.push(url);
        if (url.startsWith("/api/v2/jobs/stats")) {
          return Promise.resolve(new Response(JSON.stringify({ total: 1, total_24h: 1, completed_24h: 0, failed_24h: 0, running: 0, by_state: { OUTCOME_UNKNOWN: 1 } })));
        }
        if (url.startsWith("/api/v2/jobs/facets")) {
          return Promise.resolve(new Response(JSON.stringify({ states: ["OUTCOME_UNKNOWN"], job_types: ["script_run"] })));
        }
        if (url.startsWith("/api/v2/jobs")) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                items: [
                  {
                    job_id: "run-401",
                    job_type: "script_run",
                    device_name: "dr-sync-verify",
                    target_kind: "script",
                    target_ref: "scr-alpha-01@3",
                    state: "OUTCOME_UNKNOWN",
                    outcome: null,
                    submitted_at: "2026-11-25T02:00:01Z",
                    has_transcript: false,
                  },
                ],
                page: 1,
                page_size: 50,
                total: 1,
              }),
            ),
          );
        }
        if (url === "/devices") {
          return Promise.resolve(new Response(JSON.stringify({ devices: [] })));
        }
        if (url === "/api/v2/scripts/runs/run-401") {
          return Promise.resolve(new Response(JSON.stringify(withheldRun), { status: 200 }));
        }
        return Promise.resolve(new Response("{}", { status: 403 }));
      }),
    );

    renderJobsWithSession(VIEWER_SESSION);
    const viewerTable = await screen.findByRole("table", { name: "Job logs" });
    // OUTCOME_UNKNOWN is never rendered as OK
    expect(within(viewerTable).queryByText("Completed")).toBeNull();
    fireEvent.click(within(viewerTable).getByText("dr-sync-verify").closest("tr")!);
    const viewerGated = await screen.findByTestId("job-script-output-gated");
    expect(viewerGated).toHaveTextContent(/Operator role or above/i);
    expect(screen.queryByTestId("job-script-output-tail")).toBeNull();
    expect(screen.queryByRole("button", { name: /Download run output/i })).toBeNull();
    expect(calls.some((u) => u.endsWith("/output"))).toBe(false);
  });

  it("hides raw output box and download button under a masked (aiview) session where output_tail is null", async () => {
    const withheldRun: ScriptRunSummary = {
      ...SAMPLE_RUN,
      output_tail: null,
    };
    const calls: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        calls.push(url);
        const respond = (body: unknown) =>
          Promise.resolve(
            new Response(JSON.stringify(body), {
              status: 200,
              headers: { "X-Nexus-Masked": "true" },
            }),
          );
        if (url.startsWith("/api/v2/jobs/stats")) {
          return respond({ total: 1, total_24h: 1, completed_24h: 1, failed_24h: 0, running: 0, by_state: { COMPLETED: 1 } });
        }
        if (url.startsWith("/api/v2/jobs/facets")) {
          return respond({ states: ["COMPLETED"], job_types: ["script_run"] });
        }
        if (url.startsWith("/api/v2/jobs")) {
          return respond({
            items: [
              {
                job_id: "run-401",
                job_type: "script_run",
                device_name: "dr-sync-verify",
                target_kind: "script",
                target_ref: "scr-alpha-01@3",
                state: "COMPLETED",
                outcome: "SUCCESS",
                submitted_at: "2026-11-25T02:00:01Z",
                has_transcript: false,
              },
            ],
            page: 1,
            page_size: 50,
            total: 1,
            target_sortable: false,
            search_scope: "ids",
          });
        }
        if (url === "/devices") {
          return respond({ devices: [] });
        }
        if (url === "/api/v2/scripts/runs/run-401") {
          return respond(withheldRun);
        }
        return Promise.resolve(new Response("{}", { status: 403 }));
      }),
    );

    renderJobsWithSession(AIVIEW_SESSION);
    const aiviewTable = await screen.findByRole("table", { name: "Job logs" });
    fireEvent.click(within(aiviewTable).getByText("dr-sync-verify").closest("tr")!);
    const aiviewGated = await screen.findByTestId("job-script-output-gated");
    expect(aiviewGated).toHaveTextContent(/withheld in masked \(aiview\) inspection mode/i);
    expect(screen.queryByTestId("job-script-output-tail")).toBeNull();
    expect(screen.queryByRole("button", { name: /Download run output/i })).toBeNull();
    expect(calls.some((u) => u.endsWith("/output"))).toBe(false);
  });
});

describe("ScriptRunsSection filtered Jobs and per-run deep links", () => {
  it("renders the Open in Jobs link filtered by job_type=script_run&q=<script_id> and the per-run ?screen=operations&tab=jobs&job=<run_id> link", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === `/api/v2/scripts/${SAMPLE_SCRIPT.script_id}/runs`) {
          return Promise.resolve(
            new Response(JSON.stringify({ script_id: SAMPLE_SCRIPT.script_id, runs: [SAMPLE_RUN] }), { status: 200 }),
          );
        }
        if (url === `/api/v2/scripts/runs/${SAMPLE_RUN.run_id}`) {
          return Promise.resolve(new Response(JSON.stringify(SAMPLE_RUN), { status: 200 }));
        }
        return Promise.resolve(new Response("{}", { status: 404 }));
      }),
    );

    render(
      <ThemeProvider theme={m3Theme}>
        <SessionContext.Provider value={OPERATOR_SESSION}>
          <ScriptRunsSection
            script={SAMPLE_SCRIPT}
            canRun
            runnerAvailable
            canReadOutput
            isMasked={false}
            externalRun={null}
          />
        </SessionContext.Provider>
      </ThemeProvider>,
    );

    const history = await screen.findByTestId("script-run-history");
    const openInJobs = within(history).getByRole("link", { name: "Open in Jobs" });
    expect(openInJobs).toHaveAttribute(
      "href",
      "?screen=operations&tab=jobs&job_type=script_run&q=scr-alpha-01",
    );

    const runsTable = await within(history).findByRole("table", { name: "Script runs table" });
    const row = within(runsTable).getByText("run-401").closest("tr")!;
    const rowJobLink = within(row).getByRole("link", { name: "Open job row" });
    expect(rowJobLink).toHaveAttribute("href", "?screen=operations&tab=jobs&job=run-401");

    fireEvent.click(within(row).getByRole("button", { name: "Inspect run" }));
    const runDetail = await within(history).findByTestId("script-run-detail");
    const detailJobLink = within(runDetail).getByRole("link", { name: "Open job row" });
    expect(detailJobLink).toHaveAttribute("href", "?screen=operations&tab=jobs&job=run-401");
  });
});
