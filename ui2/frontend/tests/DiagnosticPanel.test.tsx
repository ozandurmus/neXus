import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { DiagnosticPanel } from "../src/screens/DiagnosticPanel";

afterEach(() => vi.unstubAllGlobals());
const row = { jobId: "prior-job", targetDeviceId: "device-1", target: "FW-TANGO-04", command: "get system status",
  description: "System status", state: "COMPLETED", actor: "ACTOR-01", submittedAt: "2026-09-26T10:00:00Z",
  startedAt: "2026-09-26T10:00:01Z", durationMs: 1500, exitStatus: 0 };
function setup({ canExecute = true, queued = false, runnable = true } = {}) {
  const calls: Array<{ url: string; body?: string }> = [];
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, body: typeof init?.body === "string" ? init.body : undefined });
    const reply = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
    if (url === "/session/status") return reply({ csrf_token: "synthetic-csrf" });
    if (url === "/api/v2/diagnostics/targets") return reply({ targets: [
      { deviceId: "device-2", target: "FW-BRAVO-02", vendor: "check_point", cluster: "CLS-ROMEO-01", virtualSystems: ["001", "13"],
        commands: [{ gate_id: "cp_inventory_vsid_cphaprob_stat", description: "Virtual system cluster status",
          command_template: "bash -lc 'vsenv <VSID> && cphaprob stat'", timeout_s: 30, runnable },
          { gate_id: "cp_inventory_cphaprob_stat", description: "ZZ Plain cluster status",
            command_template: "cphaprob stat", timeout_s: 30, runnable }] },
      { deviceId: "device-1", target: "FW-TANGO-04", vendor: "fortinet", virtualSystems: [], commands: [
        { gate_id: "fgt_get_system_status", description: "System status", command_template: "get system status", timeout_s: 30, runnable },
        { gate_id: "fgt_read_parameter", description: "Interface details", command_template: "show interface <name>", timeout_s: 30, runnable },
      ] }], canExecute });
    if (url.startsWith("/api/v2/diagnostics/history")) return reply({ runs: [row, { ...row, jobId: "other-job", targetDeviceId: "device-2", actor: "ACTOR-02" }] });
    if (url === "/api/v2/diagnostics" && init?.method === "POST") return reply({ job_id: "job-1" });
    if (url === "/api/v2/diagnostics/job-1") return reply({ ...row, jobId: "job-1", state: queued ? "REQUESTED" : "COMPLETED",
      output: queued ? null : "Status: UP", queuePosition: queued ? 2 : null, masked: true });
    if (url === "/api/v2/diagnostics/prior-job") return reply({ ...row, output: "Previous diagnostic output", masked: true });
    return reply({});
  }));
  render(<ThemeProvider theme={m3Theme}><DiagnosticPanel /></ThemeProvider>);
  return calls;
}
it("runs a gated read, validates parameters, copies output and filters history by device", async () => {
  const calls = setup();
  const copy = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { value: { writeText: copy }, configurable: true });
  fireEvent.click(await screen.findByRole("button", { name: "FW-TANGO-04" }));
  fireEvent.click(screen.getByRole("button", { name: "System status get system status" }));
  fireEvent.click(screen.getByRole("button", { name: "Run read" }));
  expect(await screen.findByText("Status: UP")).toBeInTheDocument();
  const submitted = calls.find(c => c.url === "/api/v2/diagnostics" && c.body);
  expect(JSON.parse(submitted?.body ?? "{}")).toMatchObject({ device_id: "device-1", gate_id: "fgt_get_system_status" });
  fireEvent.click(screen.getByRole("button", { name: "Copy" }));
  await waitFor(() => expect(copy).toHaveBeenCalledWith("Status: UP"));
  expect(screen.getByRole("button", { name: "Download .txt" })).toBeEnabled();
  fireEvent.click(screen.getByRole("button", { name: "Interface details show interface <name>" }));
  fireEvent.change(screen.getByLabelText("Parameter"), { target: { value: "bad;token" } });
  expect(screen.getByRole("button", { name: "Run read" })).toBeDisabled();
  fireEvent.click(await screen.findByRole("button", { name: "View" }));
  expect(await screen.findByText("Previous diagnostic output")).toBeInTheDocument();
  expect(within(screen.getByRole("table")).getByText("ACTOR-01")).toBeInTheDocument();
  expect(within(screen.getByRole("table")).queryByText("ACTOR-02")).not.toBeInTheDocument();
  expect(calls.some(c => c.url.includes("device_id=device-1"))).toBe(true);
});
it("searches masked names and vendors and groups sorted devices with vendor filters", async () => {
  setup();
  await screen.findByRole("button", { name: "FW-TANGO-04" });
  expect(screen.getByText("CLS-ROMEO-01")).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Search devices"), { target: { value: "tango" } });
  expect(screen.queryByRole("button", { name: "FW-BRAVO-02" })).not.toBeInTheDocument();
  expect(screen.getByText("1 / 2")).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Search devices"), { target: { value: "CHECK_POINT" } });
  expect(screen.getByRole("button", { name: "FW-BRAVO-02" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "FW-TANGO-04" })).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Search devices"), { target: { value: "" } });
  fireEvent.click(screen.getByRole("button", { name: "fortinet" }));
  expect(screen.queryByRole("button", { name: "FW-BRAVO-02" })).not.toBeInTheDocument();
});
it("uses inventoried VS identifiers without normalizing them and never shows a free parameter for vsenv", async () => {
  const calls = setup();
  fireEvent.click(await screen.findByRole("button", { name: "FW-BRAVO-02 VS 001" }));
  expect(screen.getByLabelText("Virtual system")).toHaveValue("001");
  expect(screen.queryByLabelText("Parameter")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Run read" }));
  await waitFor(() => expect(calls.find(c => c.url === "/api/v2/diagnostics" && c.body)).toBeDefined());
  expect(JSON.parse(calls.find(c => c.url === "/api/v2/diagnostics" && c.body)!.body!)).toMatchObject({ device_id: "device-2", parameter: "001" });
});
it("shows queue position while waiting and keeps output actions disabled", async () => {
  setup({ queued: true });
  fireEvent.click(await screen.findByRole("button", { name: "FW-TANGO-04" }));
  fireEvent.click(screen.getByRole("button", { name: "System status get system status" }));
  fireEvent.click(screen.getByRole("button", { name: "Run read" }));
  expect(await screen.findByText("Queued · Position 2")).toBeInTheDocument();
  expect(screen.queryByText(/Output is not available/)).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Copy" })).toBeDisabled();
});
it("honors server canExecute while retaining masked history and has no client role literals", async () => {
  setup({ canExecute: false });
  fireEvent.click(await screen.findByRole("button", { name: "FW-TANGO-04" }));
  fireEvent.click(screen.getByRole("button", { name: "System status get system status" }));
  expect(screen.queryByRole("button", { name: "Run read" })).not.toBeInTheDocument();
  fireEvent.click(await screen.findByRole("button", { name: "View" }));
  expect(await screen.findByText("Masked")).toBeInTheDocument();
  const source = readFileSync("src/screens/DiagnosticPanel.tsx", "utf8");
  expect(source).not.toMatch(/security_admin|replay_viewer|role:/);
});

it("orders plain reads before VS reads and only shows the selector for a VS command", async () => {
  setup();
  fireEvent.click(await screen.findByRole("button", { name: "FW-BRAVO-02" }));
  const commands = within(screen.getByRole("list", { name: "Approved commands" })).getAllByRole("button");
  expect(commands[0]).toHaveTextContent("ZZ Plain cluster status");
  expect(screen.queryByLabelText("Virtual system")).not.toBeInTheDocument();
  fireEvent.click(commands[1]);
  expect(screen.getByLabelText("Virtual system")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Run read" })).toBeDisabled();
  fireEvent.change(screen.getByLabelText("Virtual system"), { target: { value: "001" } });
  expect(screen.getByRole("button", { name: "Run read" })).toBeEnabled();
  fireEvent.click(commands[0]);
  expect(screen.queryByLabelText("Virtual system")).not.toBeInTheDocument();
  expect(screen.queryByLabelText("Parameter")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Run read" })).toBeEnabled();
});

it("does not offer Run for a non-runnable command even when the session can execute", async () => {
  const calls = setup({ runnable: false });
  fireEvent.click(await screen.findByRole("button", { name: "FW-TANGO-04" }));
  fireEvent.click(screen.getByRole("button", { name: "System status get system status" }));
  expect(screen.queryByRole("button", { name: "Run read" })).not.toBeInTheDocument();
  expect(calls.some(c => c.url === "/api/v2/diagnostics")).toBe(false);
});
