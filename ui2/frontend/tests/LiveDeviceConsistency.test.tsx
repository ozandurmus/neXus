import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import type { DeviceSummary } from "../src/auth/adminApi";
import { DeviceList, isDeviceLive } from "../src/screens/InventoryScreen";

function renderDevice(device: DeviceSummary) {
  return render(
    <ThemeProvider theme={m3Theme}>
      <DeviceList devices={[device]} selectedDeviceId={null} selectedClusterRef={null}
        onSelectDevice={() => {}} onSelectCluster={() => {}} />
    </ThemeProvider>,
  );
}

const appliance = (extra: Partial<DeviceSummary> = {}): DeviceSummary => ({
  device_id: "device-1", vendor_hint: "radware", role: "appliance", enrollment_state: "ENROLLED",
  hostname: "FW-TANGO-04", model: null, software_version: null, ha_role: null, cluster_member_ref: null,
  ip_addresses: null, ...extra,
});

it("does not show not-collected for an enrolled Radware appliance with completed inventory", () => {
  renderDevice(appliance({ inventory_collected_at: "2026-09-26T10:00:00Z" }));
  expect(screen.queryByText("Confirmed · Not collected")).toBeNull();
});

it("keeps not-collected when an enrolled device has no inventory evidence", () => {
  renderDevice(appliance());
  expect(screen.getByText("Confirmed · Not collected")).toBeInTheDocument();
});

it("keeps an enrolled device with interface addresses Live", () => {
  renderDevice(appliance({ ip_addresses: "192.0.2.1" }));
  expect(screen.queryByText("Confirmed · Not collected")).toBeNull();
});

it("counts Live per device when used as a filter callback (no index leaks into the rule)", () => {
  const devices = [appliance({ device_id: "a", ip_addresses: "192.0.2.1" }), appliance({ device_id: "b" }), appliance({ device_id: "c" })];
  expect(devices.filter(isDeviceLive).map((d) => d.device_id)).toEqual(["a"]);
});
