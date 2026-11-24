import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { ManagedDevicesPanel, managedHealth } from "../src/screens/ManagedDevicesPanel";
import type { GridMemberView } from "../src/auth/adminApi";

/**
 * A Management Center's managed-device Health column (PO 2026-11-24, V271): the Management Center's own verdict beside the
 * enrolment state. ERROR red, WARNING amber, OK green, INACTIVE neutral, UNKNOWN neutral hatched with its reason -- never green.
 */
const member = (name: string, over: Partial<GridMemberView> = {}): GridMemberView => ({
  virtual_system: name, address: "192.0.2.15", platform: "Reporter", hardware_type: "Reporter VA", hypervisor: "11.1.1",
  grid_master: false, master_candidate: false, ha_enabled: false, ha_status: null, node_status: "FULLY_MANAGED", replication: null,
  disk_percent: null, memory_percent: null, cpu_percent: null, db_percent: null,
  services: [{ service: "DEPLOYMENT", status: "DEPLOYED" }], ...over,
});
const show = (members: readonly GridMemberView[], showHealth?: boolean) => render(<ThemeProvider theme={m3Theme}><ManagedDevicesPanel members={members} showHealth={showHealth} /></ThemeProvider>);
const healthCell = (name: string) => {
  const row = screen.getByRole("button", { name }).closest("tr") as HTMLElement;
  return row.querySelector("[data-health-cell]") as HTMLElement;
};

describe("ManagedDevicesPanel Health column", () => {
  it("shows ERROR red with its counts and the vendor text in the tooltip, beside the unchanged Management chip", () => {
    show([member("MD-BRAVO-02", {
      health_state: "ERROR", health_errors: 1, health_warnings: 0, health_checked_at: "2026-11-24T10:00:00",
      health_messages: ["Error: Unknown error"],
    })], true);
    expect(screen.getByRole("columnheader", { name: "Health" })).toBeInTheDocument();
    const cell = healthCell("MD-BRAVO-02");
    const chip = within(cell).getByText("Error");
    expect(chip.closest("[data-pill]")).toHaveAttribute("data-pill", "bad");
    expect(within(cell).getByText("1 error")).toBeInTheDocument();
    expect(cell.querySelector("[title*='Unknown error']")).not.toBeNull();
    // enrolment and health are separate facts: the device is still "Fully managed"
    expect(screen.getByText("Fully managed")).toBeInTheDocument();
  });

  it("keeps the old columns for a manager that reports no health, and shows the column for a Management Center regardless", () => {
    const { unmount } = show([member("MD-HOTEL-08")]);
    expect(screen.queryByRole("columnheader", { name: "Health" })).toBeNull();
    unmount();
    show([member("MD-HOTEL-08")], true);
    expect(screen.getByRole("columnheader", { name: "Health" })).toBeInTheDocument();
  });

  it("maps every state to its tone and word", () => {
    const of = (state: GridMemberView["health_state"], over: Partial<GridMemberView> = {}) => managedHealth(member("MD-X", { health_state: state, ...over }));
    expect(of("ERROR")).toMatchObject({ tone: "bad", label: "Error" });
    expect(of("WARNING", { health_warnings: 2 })).toMatchObject({ tone: "warn", label: "Warning", detail: "2 warnings" });
    expect(of("OK")).toMatchObject({ tone: "ok", label: "OK", detail: null });
    expect(of("INACTIVE")).toMatchObject({ tone: "neutral", label: "Inactive" });
    expect(of("UNKNOWN")).toMatchObject({ tone: "unknown", label: "UNKNOWN" });
  });

  it("never shows green for a device whose health was not read, and says so", () => {
    show([member("MD-CHARLIE-03", { health_state: null }), member("MD-DELTA-04")], true);
    for (const name of ["MD-CHARLIE-03", "MD-DELTA-04"]) {
      const cell = healthCell(name);
      expect(within(cell).getByText("UNKNOWN").closest("[data-pill]")).toHaveAttribute("data-pill", "unknown");
      expect(within(cell).getByText("not read")).toBeInTheDocument();
      expect(cell.querySelector("[data-pill='ok']")).toBeNull();
    }
  });

  it("names the closed reason of an UNKNOWN, in words", () => {
    show([
      member("MD-ECHO-05", { health_state: "UNKNOWN", health_messages: ["GATE_NOT_APPROVED"] }),
      member("MD-FOXTROT-06", { health_state: "UNKNOWN", health_messages: ["HEALTH_READ_FAILED"] }),
      member("MD-GOLF-07", { health_state: "UNKNOWN", health_messages: ["NOT_IN_STATUS_ANSWER"] }),
    ], true);
    expect(within(healthCell("MD-ECHO-05")).getByText("read not approved yet")).toBeInTheDocument();
    expect(within(healthCell("MD-FOXTROT-06")).getByText("read failed")).toBeInTheDocument();
    expect(within(healthCell("MD-GOLF-07")).getByText("not in the Management Center's answer")).toBeInTheDocument();
  });
});
