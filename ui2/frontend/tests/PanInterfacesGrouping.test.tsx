import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { InterfacesPanel } from "../src/screens/InventoryPanels";

/**
 * Palo Alto interfaces grouped by VSYS, the physical group collapsed by default, with a "With address only" chip (UI review
 * 2026-10-18, F-19). Nothing is removed: every row is one click away. Synthetic names and documentation addresses only.
 */
const iface = (name: string, address?: string) => ({
  name, parent: null, kind: "physical", state: "up", vlan_id: null,
  addresses: address ? [{ address, family: "ipv4" as const, role: "member" as const }] : [],
});
const ctx = (context: string, interfaces: ReturnType<typeof iface>[]) => ({ context, vs_name: context === "physical" ? null : context, ha: null, routes: [], interfaces });
const physical = ctx("physical", Array.from({ length: 4 }, (_, i) => iface(`ethernet1/${i + 1}`)));
const vsys1 = ctx("vsys1", [iface("ethernet2/1", "192.0.2.1/24"), iface("ethernet2/2")]);
const vsys2 = ctx("vsys2", [iface("ethernet3/1", "198.51.100.1/24")]);
const mount = (contexts: unknown[], vsys: string[] = ["vsys1", "vsys2"]) => render(
  <ThemeProvider theme={m3Theme}>
    <InterfacesPanel contexts={contexts as never} virtualSystems={vsys} member={{ device_id: "pa-1", hostname: "FW-SYNTH-PA" }} isPaloAlto />
  </ThemeProvider>);
const group = (label: string) => document.querySelector(`[data-vsys-group="${label}"]`) as HTMLElement;

describe("grouped by VSYS", () => {
  it("has one header row per VSYS with its counts, and no VSYS column", () => {
    mount([physical, vsys1, vsys2]);
    expect(screen.queryByRole("columnheader", { name: "VSYS" })).toBeNull();
    expect(group("Physical / VS0")).toHaveTextContent("4 interfaces · 0 with an address · collapsed");
    expect(group("VSYS: vsys1")).toHaveTextContent("2 interfaces · 1 with an address");
    expect(group("VSYS: vsys2")).toHaveTextContent("1 interface · 1 with an address");
  });

  it("collapses the physical group by default and keeps the virtual systems open, so addressed rows lead the table", () => {
    mount([physical, vsys1, vsys2]);
    expect(within(group("Physical / VS0")).getByRole("button")).toHaveAttribute("aria-expanded", "false");
    expect(within(group("VSYS: vsys1")).getByRole("button")).toHaveAttribute("aria-expanded", "true");
    expect(screen.queryByText("ethernet1/1")).toBeNull();
    expect(screen.getByText("ethernet2/1")).toBeInTheDocument();
    expect(screen.getByText("ethernet3/1")).toBeInTheDocument();
  });

  it("keeps every row reachable: opening the physical group shows all of its rows, and a group can be closed", () => {
    mount([physical, vsys1, vsys2]);
    fireEvent.click(within(group("Physical / VS0")).getByRole("button"));
    for (const n of [1, 2, 3, 4]) expect(screen.getByText(`ethernet1/${n}`)).toBeInTheDocument();
    expect(group("Physical / VS0")).toHaveTextContent("4 interfaces · 0 with an address");
    expect(group("Physical / VS0")).not.toHaveTextContent("collapsed");
    fireEvent.click(within(group("VSYS: vsys2")).getByRole("button"));
    expect(screen.queryByText("ethernet3/1")).toBeNull();
    fireEvent.click(within(group("VSYS: vsys2")).getByRole("button"));
    expect(screen.getByText("ethernet3/1")).toBeInTheDocument();
  });

  it("opens a collapsed group that holds a row the filter asks for", () => {
    mount([physical, vsys1, vsys2]);
    fireEvent.change(screen.getByPlaceholderText("Filter interfaces..."), { target: { value: "ethernet1/3" } });
    expect(screen.getByText("ethernet1/3")).toBeInTheDocument();
    expect(screen.queryByText("ethernet2/1")).toBeNull();
  });

  it("does not collapse a physical group that is the only group", () => {
    mount([physical], []);
    expect(screen.getByText("ethernet1/1")).toBeInTheDocument();
  });
});

describe("the With address only chip", () => {
  it("is off by default, narrows the rows to those with an address, and drops groups left empty", () => {
    mount([physical, vsys1, vsys2]);
    const chip = screen.getByRole("button", { name: "With address only" });
    expect(chip).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByText("Interfaces · 7 of 7 shown · Active only")).toBeInTheDocument();
    fireEvent.click(chip);
    expect(chip).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("Interfaces · 2 of 7 shown · Active only · With address only")).toBeInTheDocument();
    expect(screen.getByText("ethernet2/1")).toBeInTheDocument();
    expect(screen.queryByText("ethernet2/2")).toBeNull();
    expect(group("Physical / VS0")).toBeNull();
  });

  it("restores every row when switched off again", () => {
    mount([physical, vsys1, vsys2]);
    const chip = screen.getByRole("button", { name: "With address only" });
    fireEvent.click(chip);
    fireEvent.click(chip);
    expect(screen.getByText("Interfaces · 7 of 7 shown · Active only")).toBeInTheDocument();
    expect(screen.getByText("ethernet2/2")).toBeInTheDocument();
    expect(group("Physical / VS0")).toHaveTextContent("collapsed");
  });
});
