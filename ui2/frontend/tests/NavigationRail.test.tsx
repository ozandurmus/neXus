import { render, screen, within, fireEvent } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { NavigationRail, DESTINATIONS, DRAWER_ONLY_DESTINATIONS } from "../src/shell/NavigationRail";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import type { ScreenId } from "../src/shell/types";

const viewer: SessionInfo = { displayName: "aiview", roleTokens: [], permissions: ["Policy", "PacketAnalysis"], onSignOut: () => {} };
const rail = (active: ScreenId, session: SessionInfo | null = null) => render(
  <SessionContext.Provider value={session}><NavigationRail active={active} /></SessionContext.Provider>);
const current = () => screen.getByRole("navigation", { name: "Primary" }).querySelectorAll('[aria-current="page"]');

describe("the rail (UI review 2026-10-18, F-17)", () => {
  it("pins Operations and Backups, and keeps Sanitize and Admin out of the collapsed rail", () => {
    rail("overview");
    const nav = screen.getByRole("navigation", { name: "Primary" });
    expect(DESTINATIONS.map((d) => d.label)).toEqual(["Overview", "Devices", "Policy", "Compliance", "Operations", "Backups", "Packets"]);
    expect(within(nav).getByText("Operations")).toBeInTheDocument();
    expect(within(nav).getByText("Backups")).toBeInTheDocument();
    expect(within(nav).queryByText("Sanitize")).toBeNull();
    expect(within(nav).queryByText("Admin")).toBeNull();
    expect(DRAWER_ONLY_DESTINATIONS.map((d) => d.label)).toEqual(["Sanitize", "Admin"]);
  });

  it("still pins Operations and Backups for a read-only session whose plane list does not hold them", () => {
    rail("operations", viewer);
    const nav = screen.getByRole("navigation", { name: "Primary" });
    expect(within(nav).getByText("Operations")).toBeInTheDocument();
    expect(within(nav).getByText("Backups")).toBeInTheDocument();
  });

  it.each<[ScreenId, string]>([
    ["overview", "Overview"], ["inventory", "Devices"], ["policy", "Policy"], ["compliance", "Compliance"],
    ["operations", "Operations"], ["backups", "Backups"], ["packet_analysis", "Packets"],
  ])("marks %s as the active item", (screenId, label) => {
    rail(screenId);
    const active = current();
    expect(active).toHaveLength(1);
    expect(active[0]).toHaveTextContent(label);
  });

  it.each<[ScreenId, string]>([["sanitizer", "Sanitize"], ["administration", "Admin"]])(
    "shows %s in the collapsed rail only while it is open, active, even for a session without that plane", (screenId, label) => {
      rail(screenId, viewer);
      const active = current();
      expect(active).toHaveLength(1);
      expect(active[0]).toHaveTextContent(label);
    });

  it("marks Sanitizer and Administration active in the expanded drawer, and an operations leaf once", () => {
    const { unmount } = rail("sanitizer");
    fireEvent.click(screen.getByRole("button", { name: "Expand navigation" }));
    expect(current()[0]).toHaveTextContent("Data Sanitizer");
    unmount();
    const second = rail("administration");
    fireEvent.click(screen.getByRole("button", { name: "Expand navigation" }));
    expect(current()[0]).toHaveTextContent("Administration");
    second.unmount();
    rail("operations");
    fireEvent.click(screen.getByRole("button", { name: "Expand navigation" }));
    expect(current()).toHaveLength(1);
    expect(current()[0]).toHaveTextContent("Operations");
  });

  it("marks Backups & Recovery active in the drawer when Backups is open", () => {
    rail("backups");
    fireEvent.click(screen.getByRole("button", { name: "Expand navigation" }));
    expect(current()[0]).toHaveTextContent("Backups & Recovery");
  });
});
