import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { DiagnosticPanel } from "../src/screens/DiagnosticPanel";

afterEach(() => vi.unstubAllGlobals());

it("submits only typed intent and shows the safe terminal projection", async () => {
  const calls: Array<{ url: string; body?: string }> = [];
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, body: typeof init?.body === "string" ? init.body : undefined });
    const reply = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
    if (url === "/session/status") return reply({ role_tokens: ["role:security_admin"], csrf_token: "synthetic-csrf" });
    if (url === "/api/v2/diagnostics/targets") return reply({ targets: [{ deviceId: "device-1", target: "FW-TANGO-04" }], canExecute: true });
    if (url === "/api/v2/diagnostics/ports?device_id=device-1") return reply({ ports: ["port5"] });
    if (url.startsWith("/api/v2/diagnostics/preview")) return reply({ templateId: "fmg_interface_detail", deviceId: "device-1",
      target: "FW-TANGO-04", port: "port5", command: "diagnose fmnetwork interface detail port5", gateRevision: 90, timeoutSeconds: 60,
      retry: "none", frequency: "one per target per minute" });
    if (url === "/api/v2/diagnostics" && init?.method === "POST") return reply({ job_id: "job-1" });
    if (url === "/api/v2/diagnostics/job-1") return reply({ jobId: "job-1", targetDeviceId: "device-1", port: "port5",
      state: "COMPLETED", statusToken: "ABSENT", statusPresent: false, lineCount: 13, shapeId: "FMG_DETAIL_NO_STATUS",
      maskedOutput: "[INTERFACE_HEADER]\n[FLAGS_FIELD]" });
    return reply({});
  }));
  render(<ThemeProvider theme={m3Theme}><DiagnosticPanel /></ThemeProvider>);
  expect(screen.getByText("No command has been run.")).toBeInTheDocument();
  await screen.findByRole("option", { name: "FW-TANGO-04" });
  fireEvent.change(await screen.findByLabelText("Device"), { target: { value: "device-1" } });
  expect((screen.getByLabelText("Device") as HTMLSelectElement).value).toBe("device-1");
  fireEvent.change(screen.getByLabelText("Command"), { target: { value: "execute reboot" } });
  expect(await screen.findByText("Command or port is not gated for this device.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Run read" })).toBeDisabled();
  fireEvent.change(await screen.findByLabelText("Command"), { target: { value: "diagnose fmnetwork interface detail port5" } });
  expect(await screen.findByText(/Command: diagnose fmnetwork interface detail port5/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Run read" }));
  expect(await screen.findByText("Physical link: UNKNOWN until vendor semantics are proven.")).toBeInTheDocument();
  expect(screen.getByRole("status", { name: "Output" })).toBeInTheDocument();
  expect(screen.getByText(/\[INTERFACE_HEADER\]/)).toBeInTheDocument();
  const submitted = calls.find(c => c.url === "/api/v2/diagnostics" && c.body);
  expect(Object.keys(JSON.parse(submitted?.body ?? "{}"))).toEqual(["device_id", "port", "request_id"]);
  fireEvent.change(screen.getByLabelText("Command"), { target: { value: "another command" } });
  expect(screen.getByText(/\[INTERFACE_HEADER\]/)).toBeInTheDocument();
});
