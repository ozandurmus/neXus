import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { DiagnosticPanel } from "../src/screens/DiagnosticPanel";
afterEach(() => vi.unstubAllGlobals());
it("runs typed proposals and opens retained execution history", async () => {
  const calls: Array<{ url: string; body?: string }> = [];
  const row = { jobId: "prior-job", targetDeviceId: "device-1", target: "FW-TANGO-04", command: "get system status",
    state: "COMPLETED", actor: "ACTOR-01", submittedAt: "2026-09-26T10:00:00Z", exitStatus: 0 };
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, body: typeof init?.body === "string" ? init.body : undefined });
    const reply = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
    if (url === "/session/status") return reply({ csrf_token: "synthetic-csrf" });
    if (url === "/api/v2/diagnostics/targets") return reply({ targets: [{ deviceId: "device-1", target: "FW-TANGO-04" }], canExecute: true });
    if (url.startsWith("/api/v2/diagnostics/history")) return reply({ runs: [row] });
    if (url === "/api/v2/diagnostics" && init?.method === "POST") return reply({ job_id: "job-1" });
    if (url === "/api/v2/diagnostics/job-1") return reply({ ...row, jobId: "job-1", output: "Status: UP", masked: true });
    if (url === "/api/v2/diagnostics/prior-job") return reply({ ...row, output: "Previous diagnostic output", masked: true });
    return reply({});
  }));
  render(<ThemeProvider theme={m3Theme}><DiagnosticPanel /></ThemeProvider>);
  expect(screen.getByText("No command has been run.")).toBeInTheDocument();
  await screen.findByRole("option", { name: "FW-TANGO-04" });
  fireEvent.change(screen.getByLabelText("Device"), { target: { value: "device-1" } });
  fireEvent.change(screen.getByLabelText("Command"), { target: { value: "get system status" } });
  fireEvent.click(screen.getByRole("button", { name: "Run read" }));
  expect(await screen.findByText("Status: UP")).toBeInTheDocument();
  const submitted = calls.find(c => c.url === "/api/v2/diagnostics" && c.body);
  expect(Object.keys(JSON.parse(submitted?.body ?? "{}"))).toEqual(["device_id", "command", "request_id"]);
  fireEvent.change(screen.getByLabelText("Command"), { target: { value: "another command" } });
  expect(screen.getByText("Status: UP")).toBeInTheDocument();
  fireEvent.click(await screen.findByRole("button", { name: "View output" }));
  expect(await screen.findByText("Previous diagnostic output")).toBeInTheDocument();
  expect(within(screen.getByRole("table")).getByText("ACTOR-01")).toBeInTheDocument();
});
