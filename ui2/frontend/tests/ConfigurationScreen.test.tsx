import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { ConfigurationScreen } from "../src/screens/ConfigurationScreen";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

describe("ConfigurationScreen device list", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetches GET /configuration and shows an empty state with no devices", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(200, { devices: [] }))));
    render(withTheme(<ConfigurationScreen />));

    await waitFor(() => expect(screen.getByText("No devices")).toBeInTheDocument());
    expect(screen.getByText("No device selected")).toBeInTheDocument();
  });

  it("renders devices with their change state", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() =>
        Promise.resolve(
          jsonResponse(200, {
            devices: [
              {
                device_id: "dev-1",
                hostname: "fw-edge-1",
                vendor: "check_point",
                last_collected_at: "2026-09-14T10:00:00Z",
                change_state: "changed",
              },
            ],
          }),
        ),
      ),
    );
    render(withTheme(<ConfigurationScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    expect(screen.getByText("Changed")).toBeInTheDocument();
    expect(screen.getByText("1 device collected")).toBeInTheDocument();
  });

  it("shows an error state with a retry action when the fetch fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("network down")));
    render(withTheme(<ConfigurationScreen />));

    await waitFor(() => expect(screen.getByText("Configuration unavailable")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });
});
