import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { ConfigurationScreen } from "../src/screens/ConfigurationScreen";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function textResponse(status: number, body: string): Response {
  return new Response(body, { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const CP_DEVICE = {
  device_id: "dev-1",
  hostname: "fw-edge-1",
  vendor: "check_point",
  last_collected_at: "2026-09-14T12:00:00Z",
  change_state: "unchanged",
};

describe("ConfigurationScreen device selection and panels", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetches and renders a selected Check Point device's index, withheld count and sanitized text", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/configuration") {
        return Promise.resolve(jsonResponse(200, { devices: [CP_DEVICE] }));
      }
      if (url === "/devices/dev-1/configuration") {
        return Promise.resolve(
          jsonResponse(200, {
            device_id: "dev-1",
            collected_at: "2026-09-14T12:00:00Z",
            vendor: "check_point",
            read_kind: "show_configuration",
            canonical_hash: "abcdef0123456789",
            change_state: "unchanged",
            withheld_line_count: 3,
            sanitized_text_available: true,
            index: [{ context: "physical", section: "interface", source: null, entry_count: 5, has_override: false }],
            overrides: [],
            supplementary_runs: [],
          }),
        );
      }
      if (url === "/devices/dev-1/configuration/text") {
        return Promise.resolve(textResponse(200, "set interface eth0 state on\n"));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<ConfigurationScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    fireEvent.click(screen.getByText("fw-edge-1"));

    await waitFor(() => expect(screen.getByText("interface")).toBeInTheDocument());
    expect(screen.getByText(/3 secret-bearing line\(s\) withheld/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Sanitized text" }));
    await waitFor(() => expect(screen.getByText(/set interface eth0 state on/)).toBeInTheDocument());
  });

  it("shows no sanitized text tab content for a Palo Alto device (sanitized_text_available false)", async () => {
    const panDevice = { ...CP_DEVICE, vendor: "palo_alto" };
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/configuration") {
        return Promise.resolve(jsonResponse(200, { devices: [panDevice] }));
      }
      if (url === "/devices/dev-1/configuration") {
        return Promise.resolve(
          jsonResponse(200, {
            device_id: "dev-1",
            collected_at: "2026-09-14T12:00:00Z",
            vendor: "palo_alto",
            read_kind: "effective_running",
            canonical_hash: "abcdef0123456789",
            change_state: "first_run",
            withheld_line_count: 0,
            sanitized_text_available: false,
            index: [{ context: "vsys1", section: "address", source: "local", entry_count: 2, has_override: true }],
            overrides: [{ context: "vsys1", category: "address", element_path: "address/addr-2", panorama_source: null }],
            supplementary_runs: [{ read_kind: "active", collected_at: "2026-09-14T12:00:00Z", canonical_hash: "x", change_state: "first_run" }],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<ConfigurationScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    fireEvent.click(screen.getByText("fw-edge-1"));

    await waitFor(() => expect(screen.getByText("override")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("tab", { name: "Sanitized text" }));
    expect(screen.getByText("No sanitized text")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Overrides" }));
    expect(screen.getByText(/address\/addr-2/)).toBeInTheDocument();
  });

  it("Collect now submits, polls the device, and refreshes the configuration once the job is terminal", async () => {
    let devicePollCount = 0;
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/configuration") {
        return Promise.resolve(jsonResponse(200, { devices: [CP_DEVICE] }));
      }
      if (url === "/devices/dev-1/configuration") {
        return Promise.resolve(
          jsonResponse(200, {
            device_id: "dev-1",
            collected_at: null,
            vendor: null,
            read_kind: null,
            canonical_hash: null,
            change_state: null,
            withheld_line_count: 0,
            sanitized_text_available: false,
            index: [],
            overrides: [],
            supplementary_runs: [],
          }),
        );
      }
      if (url === "/session/status") {
        return Promise.resolve(jsonResponse(200, { csrf_token: "token-1" }));
      }
      if (url === "/devices/dev-1/configuration/collect" && init?.method === "POST") {
        return Promise.resolve(jsonResponse(202, { job_id: "job-2" }));
      }
      if (url === "/devices/dev-1") {
        devicePollCount += 1;
        const terminal = devicePollCount >= 2;
        return Promise.resolve(
          jsonResponse(200, {
            device_id: "dev-1",
            vendor_hint: "check_point",
            enrollment_state: "ENROLLED",
            disabled: false,
            facts: null,
            peer_follow_outcome: null,
            peer_follow_reason: null,
            identity_mismatch_state: "NONE",
            cluster_member_ref: null,
            job: { job_id: "job-2", state: terminal ? "COMPLETED" : "EXECUTING", outcome: terminal ? "SUCCESS" : null, terminal_reason: null },
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<ConfigurationScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    fireEvent.click(screen.getByText("fw-edge-1"));

    const collectButton = await screen.findByRole("button", { name: "Collect now" });
    fireEvent.click(collectButton);

    await waitFor(() =>
      expect(fetchMock.mock.calls.some((call) => String(call[0]) === "/devices/dev-1/configuration/collect")).toBe(true),
    );
    await waitFor(() => expect(devicePollCount).toBeGreaterThanOrEqual(2), { timeout: 5000 });
  });
});
