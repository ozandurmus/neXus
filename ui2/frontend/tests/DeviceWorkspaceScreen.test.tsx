import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { DeviceWorkspaceView } from "../src/auth/adminApi";
import { m3Theme } from "../src/theme/m3Theme";

const workspaceMock = vi.hoisted(() => ({ get: vi.fn<(id: string) => Promise<DeviceWorkspaceView>>() }));

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    getDeviceWorkspace: workspaceMock.get,
    getSshHostKeyView: () => Promise.resolve({ mode: "WARN", observations: [] }),
    getHttpsCertificate: () => Promise.resolve({ available: false, certificates: [] }),
    getDeviceLifecycle: () => Promise.resolve(null),
  };
});

import { DeviceWorkspaceScreen } from "../src/screens/DeviceWorkspaceScreen";

function workspace(vendor_hint: string): DeviceWorkspaceView {
  return {
    device_id: "FW-TANGO-04",
    vendor_hint,
    registration_source: "manual",
    created_at: "2026-10-18T06:00:00Z",
    is_test_target: true,
    credential_configured: true,
    enrollment_state: "ENROLLED",
    disabled: false,
    transport: {
      presence: "PRESENT",
      transports: [{ transport_kind: "https", endpoint_id: "192.0.2.10" }],
    },
    action_affordance: {},
  };
}

describe("DeviceWorkspaceScreen vendorLabel", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it.each([
    ["fortinet", "Fortinet"],
    ["check_point", "Check Point"],
    ["cisco_asa", "Cisco ASA"],
    ["bluecoat", "Blue Coat"],
    ["pulse_secure", "Pulse Secure"],
    ["", "UNKNOWN"],
  ])("renders shared presenters.vendorLabel output for %s", async (vendorHint, expectedLabel) => {
    workspaceMock.get.mockResolvedValueOnce(workspace(vendorHint));

    render(
      <ThemeProvider theme={m3Theme}>
        <DeviceWorkspaceScreen deviceId="FW-TANGO-04" />
      </ThemeProvider>,
    );

    await waitFor(() => expect(screen.getByText("Identity")).toBeInTheDocument());
    expect(screen.getByText((_, node) => node?.tagName === "P" && node.textContent === `Vendor: ${expectedLabel} (hint)`)).toBeInTheDocument();
  });
});
