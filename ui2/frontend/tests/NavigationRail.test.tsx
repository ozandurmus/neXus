import { render, screen, within, fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NavigationRail, DESTINATIONS, RAIL_ENTRIES, ADMINISTRATION, DRAWER_GROUPS, DRAWER_BELOW_PX } from "../src/shell/NavigationRail";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { SCREEN_IDS, type ScreenId } from "../src/shell/types";

const viewer: SessionInfo = { displayName: "aiview", roleTokens: [], permissions: ["Policy", "PacketAnalysis"], onSignOut: () => {} };
const rail = (active: ScreenId, session: SessionInfo | null = null) => render(
  <SessionContext.Provider value={session}><NavigationRail active={active} /></SessionContext.Provider>);
const nav = () => screen.getByRole("navigation", { name: "Primary" });
const current = () => nav().querySelectorAll('[aria-current="page"]');

/** jsdom has no matchMedia: stub one that answers the rail's min-width query for a given viewport width. */
function viewport(width: number) {
  vi.stubGlobal("matchMedia", (query: string) => {
    const min = Number(/min-width:\s*(\d+)px/.exec(query)?.[1] ?? 0);
    return { matches: width >= min, media: query, onchange: null, addListener: () => {}, removeListener: () => {},
      addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false };
  });
}
afterEach(() => vi.unstubAllGlobals());

describe("the rail (UI product review 2026-10-18, §2.3 / roadmap 1.1)", () => {
  it("reads Overview · SEE Devices · VERIFY Compliance, Policy · RECOVER Backups · OPERATE Operations, Tools, with Administration last", () => {
    rail("overview");
    expect(RAIL_ENTRIES.map((e) => [e.group, e.label])).toEqual([
      [null, "Overview"], ["SEE", "Devices"], ["VERIFY", "Compliance"], ["VERIFY", "Policy"], ["RECOVER", "Backups"], ["OPERATE", "Operations"], ["OPERATE", "Tools"],
    ]);
    expect(ADMINISTRATION.label).toBe("Administration");
    const text = nav().textContent ?? "";
    for (const word of ["SEE", "VERIFY", "RECOVER", "OPERATE"]) expect(text).toContain(word);
    expect(text.indexOf("SEE")).toBeLessThan(text.indexOf("Devices"));
    expect(text.indexOf("VERIFY")).toBeLessThan(text.indexOf("Compliance"));
    expect(text.indexOf("RECOVER")).toBeLessThan(text.indexOf("Backups"));
    expect(text.indexOf("OPERATE")).toBeLessThan(text.indexOf("Operations"));
    expect(text.trim().endsWith("Administration")).toBe(true);
    // the wide rail has no drawer button: the rail itself is the navigation
    expect(screen.queryByRole("button", { name: /navigation/ })).toBeNull();
  });

  it("lists every screen as a destination exactly once (configuration is a tab of Devices, not a rail item)", () => {
    const screens = DESTINATIONS.map((d) => d.screen);
    expect(new Set(screens).size).toBe(screens.length);
    expect([...screens].sort()).toEqual(SCREEN_IDS.filter((s) => s !== "configuration").sort());
  });

  it.each<[ScreenId, string]>([
    ["overview", "Overview"], ["inventory", "Devices"], ["compliance", "Compliance"], ["policy", "Policy"], ["backups", "Backups"],
    ["operations", "Operations"], ["packet_analysis", "Tools"], ["sanitizer", "Tools"], ["administration", "Administration"],
  ])("marks exactly one active item for %s", (screenId, label) => {
    rail(screenId);
    const active = current();
    expect(active).toHaveLength(1);
    expect(active[0]).toHaveTextContent(label);
  });

  it("opens Packets and Sanitizer from the one Tools entry, marking the open one", () => {
    rail("sanitizer");
    fireEvent.click(within(nav()).getByRole("button", { name: "Tools" }));
    const menu = screen.getByRole("menu", { name: "Tools" });
    const items = within(menu).getAllByRole("menuitem");
    expect(items.map((i) => [i.textContent, i.getAttribute("href")])).toEqual([["Packets", "?screen=packet_analysis"], ["Sanitizer", "?screen=sanitizer"]]);
    expect(items[1]).toHaveAttribute("aria-current", "page");
    expect(items[0]).not.toHaveAttribute("aria-current");
  });

  it("keeps Operations, Backups and Administration for a read-only session whose plane list does not hold them, and hides Tools entries it may not use", () => {
    rail("operations", viewer);
    expect(within(nav()).getByText("Operations")).toBeInTheDocument();
    expect(within(nav()).getByText("Backups")).toBeInTheDocument();
    expect(within(nav()).queryByText("Devices")).toBeNull();
    expect(within(nav()).getByRole("link", { name: "Administration" })).toHaveAttribute("href", "?screen=administration");
    fireEvent.click(within(nav()).getByRole("button", { name: "Tools" }));
    expect(within(screen.getByRole("menu", { name: "Tools" })).getAllByRole("menuitem").map((i) => i.textContent)).toEqual(["Packets"]);
  });

  it("still shows Administration, active, while it is the open screen for a session without that plane", () => {
    rail("administration", viewer);
    expect(current()).toHaveLength(1);
    expect(current()[0]).toHaveTextContent("Administration");
  });

  // Post-release review 2026-10-18, R4: Administration was rendered only on Administration routes.
  it.each<ScreenId>(["overview", "inventory", "packet_analysis", "sanitizer", "compliance"])("has Administration on %s, last in the rail, pinned to its bottom edge", (screenId) => {
    rail(screenId, viewer);
    const admin = within(nav()).getByRole("link", { name: "Administration" });
    expect(admin).toHaveAttribute("href", "?screen=administration");
    expect(admin).not.toHaveAttribute("aria-current");
    expect((nav().textContent ?? "").trim().endsWith("Administration")).toBe(true);
    expect(screen.getByTestId("rail-administration")).toHaveStyle({ position: "sticky", bottom: "0px" });
    // the rail itself stays in view on a long page, so its bottom edge is the viewport's
    expect(nav()).toHaveStyle({ position: "sticky", top: "0px" });
  });

  it("offers Tools as a real button with its two destinations as menu links, so the keyboard reaches both", () => {
    rail("overview");
    const tools = within(nav()).getByRole("button", { name: "Tools" });
    expect(tools.tagName).toBe("BUTTON");
    expect(tools).toHaveAttribute("aria-haspopup", "menu");
    expect(tools.tabIndex).toBeGreaterThanOrEqual(0);
    tools.focus();
    fireEvent.click(tools);
    const menu = screen.getByRole("menu", { name: "Tools" });
    expect(tools).toHaveAttribute("aria-expanded", "true");
    const items = within(menu).getAllByRole("menuitem");
    expect(items.map((i) => i.textContent)).toEqual(["Packets", "Sanitizer"]);
    for (const item of items) expect(item.tagName).toBe("A");
    // the menu takes focus when it opens, so Arrow keys and Enter move between and follow the links
    expect(menu.contains(document.activeElement)).toBe(true);
  });

  it(`shrinks to icons below ${DRAWER_BELOW_PX} px, keeps the active item, and opens the drawer from the ≡ button`, () => {
    viewport(DRAWER_BELOW_PX - 1);
    rail("backups");
    expect(within(nav()).queryByText("Backups")).toBeNull();
    expect(current()).toHaveLength(1);
    expect(within(nav()).queryByText("RECOVER")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    const drawer = screen.getByRole("navigation", { name: "Primary navigation drawer" });
    for (const g of DRAWER_GROUPS) {
      expect(within(drawer).getByText(g.header)).toBeInTheDocument();
      for (const leaf of g.leaves) expect(within(drawer).getByText(leaf.label)).toBeInTheDocument();
    }
    expect(within(drawer).getByText("Backups").closest("a")).toHaveAttribute("aria-current", "page");
    expect(within(drawer).getAllByRole("link").filter((a) => a.getAttribute("aria-current") === "page")).toHaveLength(1);
  });

  it("re-homes what the old drawer held: Jobs, Device management and Inventory exclusions are deep links; Discovery stays a disabled leaf", () => {
    const leaves = DRAWER_GROUPS.flatMap((g) => g.leaves);
    const by = (label: string) => leaves.find((l) => l.label === label);
    expect(by("Jobs")?.href).toBe("?screen=operations&tab=jobs");
    expect(by("Device management")?.href).toBe("?screen=administration&tab=device-management");
    expect(by("Inventory exclusions")?.href).toBe("?screen=administration&tab=inventory-exclusions");
    expect(by("Packets")?.href).toBe("?screen=packet_analysis");
    expect(by("Sanitizer")?.href).toBe("?screen=sanitizer");
    expect(by("Backups")?.href).toBe("?screen=backups");
    expect(by("Discovery")?.href).toBeUndefined();
    viewport(900);
    rail("overview");
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    const drawer = screen.getByRole("navigation", { name: "Primary navigation drawer" });
    expect(within(drawer).getByText("Discovery").closest("[aria-disabled]")).not.toBeNull();
  });

  it(`shows the wide rail from ${DRAWER_BELOW_PX} px with no drawer button`, () => {
    viewport(DRAWER_BELOW_PX);
    rail("overview");
    expect(within(nav()).getByText("Devices")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Open navigation" })).toBeNull();
  });
});
