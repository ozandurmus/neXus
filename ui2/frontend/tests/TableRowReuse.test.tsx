import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { DeviceManagementPane } from "../src/screens/DeviceRegistryPanel";
import { RoutesPanel } from "../src/screens/InventoryPanels";
import type { DeviceSummary, InventoryContext } from "../src/auth/adminApi";
import { bodyRowsOf, getSortButton } from "./fastQueries";

/**
 * A header click re-orders rows, it does not rebuild them: the table keys rows by what they show, not by their position,
 * and the rows are memoised, so a row that is on the page before and after a sort is the very same DOM node (a page of 50
 * rows is several hundred styled elements; rebuilding them on each click was most of a click's cost).
 */
afterEach(() => vi.unstubAllGlobals());

const rowText = (text: string) => {
  const rows = bodyRowsOf().filter((row) => row.textContent?.includes(text));
  expect(rows).toHaveLength(1);
  return rows[0];
};

it("keeps a route row's DOM node across a header sort, duplicates included", () => {
  const routes = [
    ...Array.from({ length: 30 }, (_, k) => ({ destination: `198.51.100.${((k * 7) % 30) + 1}/32`, next_hop: `192.0.2.${k + 1}`, interface: `ethernet1/${k % 4}`, protocol: "static" })),
    // two identical routes: the key must still tell them apart
    { destination: "203.0.113.9/32", next_hop: "192.0.2.99", interface: "ethernet1/9", protocol: "static" },
    { destination: "203.0.113.9/32", next_hop: "192.0.2.99", interface: "ethernet1/9", protocol: "static" },
  ];
  const contexts = [{ context: "physical", vs_name: null, ha: null, interfaces: [], routes }] as unknown as InventoryContext[];
  render(<ThemeProvider theme={m3Theme}><RoutesPanel contexts={contexts} member={{ device_id: "d1", hostname: "FW-TANGO-01" }} /></ThemeProvider>);
  expect(bodyRowsOf()).toHaveLength(32);
  const before = rowText("198.51.100.7/32");
  const order = bodyRowsOf().map((row) => row.textContent);
  fireEvent.click(getSortButton(document.body, "Destination"));
  expect(bodyRowsOf().map((row) => row.textContent)).not.toEqual(order);
  expect(bodyRowsOf()).toHaveLength(32);
  expect(rowText("198.51.100.7/32")).toBe(before);
  expect(bodyRowsOf().filter((row) => row.textContent?.includes("203.0.113.9/32"))).toHaveLength(2);
});

it("keeps a registry row's DOM node across a header sort", async () => {
  const devices = Array.from({ length: 40 }, (_, k): DeviceSummary => ({
    device_id: `dev-${k}`, vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: `FW-ALPHA-${String(((k * 7) % 40) + 1).padStart(3, "0")}`,
    model: null, software_version: null, ha_role: null, cluster_member_ref: null, inventory_collected_at: "2026-10-18T03:00:00Z",
  }));
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(url.startsWith("/devices") ? { devices } : {}), { status: 200 }))));
  render(<ThemeProvider theme={m3Theme}><DeviceManagementPane /></ThemeProvider>);
  await screen.findByText("FW-ALPHA-001");
  const before = rowText("FW-ALPHA-001");
  const order = bodyRowsOf().map((row) => row.textContent);
  fireEvent.click(getSortButton(document.body, "DEVICE / HOSTNAME"));
  expect(bodyRowsOf().map((row) => row.textContent)).not.toEqual(order);
  expect(rowText("FW-ALPHA-001")).toBe(before);
});
