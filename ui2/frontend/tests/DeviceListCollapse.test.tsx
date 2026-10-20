import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { InventoryScreen } from "../src/screens/InventoryScreen";
import type { DeviceSummary } from "../src/auth/adminApi";

/**
 * The Devices screen's list column folds to a slim strip and comes back as an overlay (PO 2026-10-20: a list that is
 * always there is tiring). Masked fixtures only (FW-TANGO-04, 192.0.2.x).
 */
const KEY = "nx.devices.list.collapsed";
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
const mount = () => render(<ThemeProvider theme={m3Theme}><InventoryScreen /></ThemeProvider>);
const device = (over: Partial<DeviceSummary> = {}): DeviceSummary => ({
  device_id: "d1", hostname: "FW-TANGO-04", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", model: "Quantum",
  software_version: "R81.20", ha_role: null, cluster_member_ref: null, ip_addresses: "192.0.2.10", backup_target: true, ...over,
});
const FLEET = [device(), device({ device_id: "d2", hostname: "FW-JULIET-06", ip_addresses: "192.0.2.11" })];

function stub() {
  vi.stubGlobal("fetch", vi.fn((url: string) => url === "/devices" ? json({ devices: FLEET })
    : /^\/devices\/d[12]\/inventory$/.test(url) ? json({ device_id: url.split("/")[2], collected_at: new Date().toISOString(), contexts: [] })
    : json({ error: "NOT_FOUND" }, 404)));
}
afterEach(() => { vi.unstubAllGlobals(); window.localStorage.removeItem(KEY); window.history.replaceState(null, "", "/"); });

const listColumn = () => screen.getByTestId("device-list-column");

describe("the Devices list column folds to a strip", () => {
  it("hides the list behind ‹, gives the object the width, and brings it back with ›", async () => {
    stub();
    mount();
    expect(await screen.findByText("FW-TANGO-04")).toBeInTheDocument();
    expect(screen.getByTestId("list-detail")).toHaveAttribute("data-collapsed", "false");
    fireEvent.click(within(listColumn()).getByRole("button", { name: "Hide device list" }));
    expect(screen.getByTestId("list-detail")).toHaveAttribute("data-collapsed", "true");
    expect(screen.queryByTestId("device-list-column")).toBeNull();
    expect(screen.queryByRole("combobox", { name: "Scope" })).toBeNull();
    const strip = screen.getByTestId("device-list-strip");
    expect(within(strip).getByRole("button", { name: "Devices" })).toBeInTheDocument();
    // the landing keeps its content at full width
    expect(screen.getByRole("heading", { name: "Devices" })).toBeInTheDocument();
    fireEvent.click(within(strip).getByRole("button", { name: "Show device list" }));
    expect(screen.getByTestId("list-detail")).toHaveAttribute("data-collapsed", "false");
    expect(within(listColumn()).getByText("FW-TANGO-04")).toBeInTheDocument();
  });

  it("opens the folded list over the page from the Devices button, and closes it when a device is picked", async () => {
    stub();
    mount();
    await screen.findByText("FW-TANGO-04");
    fireEvent.click(screen.getByRole("button", { name: "Hide device list" }));
    expect(screen.queryByRole("dialog", { name: "Device list" })).toBeNull();
    fireEvent.click(within(screen.getByTestId("device-list-strip")).getByRole("button", { name: "Devices" }));
    const overlay = await screen.findByRole("dialog", { name: "Device list" });
    expect(within(overlay).getByRole("combobox", { name: "Scope" })).toBeInTheDocument();
    fireEvent.click(within(overlay).getByText("FW-JULIET-06"));
    await screen.findByRole("tablist", { name: "Device detail" });
    // the overlay closes, the column stays folded, the object page has the width
    expect(screen.getByTestId("list-detail")).toHaveAttribute("data-collapsed", "true");
    expect(screen.getByTestId("compact-screen-header")).toHaveTextContent("Devices");
    expect(screen.queryByRole("dialog", { name: "Device list" })).toBeNull();
    // the overlay's own close button works too
    fireEvent.click(within(screen.getByTestId("device-list-strip")).getByRole("button", { name: "Devices" }));
    fireEvent.click(within(await screen.findByRole("dialog", { name: "Device list" })).getByRole("button", { name: "Close device list" }));
    expect(screen.queryByRole("dialog", { name: "Device list" })).toBeNull();
  });

  it("remembers the fold per viewer, and renders the list when the store is unusable", async () => {
    stub();
    const first = mount();
    await screen.findByText("FW-TANGO-04");
    fireEvent.click(screen.getByRole("button", { name: "Hide device list" }));
    expect(window.localStorage.getItem(KEY)).toBe("1");
    first.unmount();
    mount();
    expect(await screen.findByTestId("device-list-strip")).toBeInTheDocument();
    expect(screen.getByTestId("list-detail")).toHaveAttribute("data-collapsed", "true");
    // the strip is there before the fleet read lands and stays after it
    expect(screen.queryByTestId("device-list-column")).toBeNull();
    screen.getByTestId("list-detail");
    const store = Object.getOwnPropertyDescriptor(window, "localStorage")!;
    Object.defineProperty(window, "localStorage", { configurable: true, get() { throw new Error("blocked"); } });
    try {
      render(<ThemeProvider theme={m3Theme}><InventoryScreen /></ThemeProvider>);
      expect(screen.getAllByTestId("list-detail").at(-1)).toHaveAttribute("data-collapsed", "false");
    } finally {
      Object.defineProperty(window, "localStorage", store);
    }
  });
});
