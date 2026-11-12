import { act, render, screen, within, fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NavigationRail, DRAWER_BELOW_PX, EXPANDED_BY_DEFAULT_PX, SIDEBAR_PX, FOLDED_PX } from "../src/shell/NavigationRail";
import { NAV_ENTRIES, NAV_LEAVES } from "../src/shell/navModel";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { writeUrlParams } from "../src/shell/urlParams";
import type { ScreenId } from "../src/shell/types";

// A read-only session: two planes, no administration-read grant.
const viewer: SessionInfo = { displayName: "aiview", roleTokens: [], permissions: ["Policy", "PacketAnalysis"], onSignOut: () => {} };
const FOLD_KEY = "nx.rail.collapsed";
const GROUPS_KEY = "nx.nav.groups";

/** Renders the navigation as the shell does: the resolved screen as a prop, the sub-view in the address. */
const rail = (active: ScreenId, session: SessionInfo | null = null, search = `?screen=${active}`) => {
  window.history.replaceState(null, "", `/${search}`);
  return render(<SessionContext.Provider value={session}><NavigationRail active={active} /></SessionContext.Provider>);
};
const nav = () => screen.getByRole("navigation", { name: "Primary" });
const current = (root: HTMLElement = nav()) => root.querySelectorAll('[aria-current="page"]');
const header = (name: string, root: HTMLElement = nav()) => within(root).getByRole("button", { name });

/** jsdom has no matchMedia: stub one that answers the min-width queries for a given viewport width. */
function viewport(width: number) {
  vi.stubGlobal("matchMedia", (query: string) => {
    const min = Number(/min-width:\s*(\d+)px/.exec(query)?.[1] ?? 0);
    return { matches: width >= min, media: query, onchange: null, addListener: () => {}, removeListener: () => {},
      addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false };
  });
}
afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.removeItem(FOLD_KEY);
  window.localStorage.removeItem(GROUPS_KEY);
  window.history.replaceState(null, "", "/");
});

describe("the expanded sidebar (navigation redesign 2026-11-12)", () => {
  it("reads the nine entries in order, groups by domain, prints no verb word, and keeps Administration closed until it is used", () => {
    rail("overview");
    expect(nav()).toHaveAttribute("data-nav-layout", "expanded");
    expect(nav()).toHaveStyle({ width: `${SIDEBAR_PX}px` });
    const text = nav().textContent ?? "";
    let at = -1;
    for (const label of ["Overview", "Reports", "Devices", "Compliance and security", "Lifecycle and security", "Policy and changes", "Trace", "Changes", "Backups",
      "Operations", "HA and readiness", "Jobs", "Scheduled jobs", "Device health", "Platform status", "Tools", "Packet analysis", "Data sanitizer", "Diagnostics", "Offline analysis", "Administration"]) {
      const next = text.indexOf(label, at + 1);
      expect(next, label).toBeGreaterThan(at);
      at = next;
    }
    for (const word of ["SEE", "VERIFY", "TRACE", "RECOVER", "OPERATE"]) expect(text).not.toContain(word);
    expect(text.trim().endsWith("Administration")).toBe(true);
    for (const name of ["Compliance and security", "Policy and changes", "Operations", "Tools"]) expect(header(name)).toHaveAttribute("aria-expanded", "true");
    expect(header("Administration")).toHaveAttribute("aria-expanded", "false");
    expect(within(nav()).queryByText("Device registry")).toBeNull();
    // the sidebar itself is the navigation: no drawer button, and it stays in view on a long page
    expect(screen.queryByRole("button", { name: "Open navigation" })).toBeNull();
    expect(nav()).toHaveStyle({ position: "sticky", top: "0px" });
  });

  it("links every entry to its address; a group header is a button, never a link or the current page", () => {
    rail("overview");
    fireEvent.click(header("Administration"));
    const links = within(nav()).getAllByRole("link");
    expect(links.map((a) => [a.textContent, a.getAttribute("href")])).toEqual(NAV_LEAVES.map((l) => [l.label, l.href]));
    for (const entry of NAV_ENTRIES.filter((e) => e.kind === "group")) {
      const button = header(entry.label);
      expect(button.tagName).toBe("BUTTON");
      expect(button).not.toHaveAttribute("aria-current");
      expect(button).not.toHaveAttribute("aria-haspopup");
    }
  });

  it.each<[ScreenId, string, string, string | null]>([
    ["overview", "?screen=overview", "Overview", null],
    ["reports", "?screen=reports", "Reports", null],
    ["inventory", "?screen=inventory", "Devices", null],
    ["inventory", "?screen=inventory&view=lifecycle", "Lifecycle and security", "Compliance and security"],
    ["compliance", "?screen=compliance", "Compliance", "Compliance and security"],
    ["policy", "?screen=policy", "Policy", "Policy and changes"],
    ["trace", "?screen=trace&mode=lookup", "Trace", "Policy and changes"],
    ["changes", "?screen=changes", "Changes", "Policy and changes"],
    ["backups", "?screen=backups", "Backups", null],
    ["operations", "?screen=operations", "HA and readiness", "Operations"],
    ["operations", "?screen=operations&tab=jobs", "Jobs", "Operations"],
    ["operations", "?screen=operations&tab=health", "Device health", "Operations"],
    ["operations", "?screen=operations&tab=platform-status", "Platform status", "Operations"],
    ["operations", "?screen=operations&tab=schedules", "Scheduled jobs", "Operations"],
    ["packet_analysis", "?screen=packet_analysis", "Packet analysis", "Tools"],
    ["sanitizer", "?screen=sanitizer", "Data sanitizer", "Tools"],
    ["diagnostics", "?screen=diagnostics", "Diagnostics", "Tools"],
    ["offline_analysis", "?screen=offline_analysis", "Offline analysis", "Tools"],
    ["administration", "?screen=administration", "Device registry", "Administration"],
    ["administration", "?screen=administration&tab=access&section=ldap-settings", "Access and identity", "Administration"],
    ["administration", "?screen=administration&tab=platform", "Notifications and logging", "Administration"],
    ["administration", "?screen=administration&tab=audit", "Audit log", "Administration"],
    ["administration", "?screen=administration&tab=product", "Delivery plan", "Administration"],
  ])("lights exactly one entry for %s %s: %s", (screenId, search, label, groupLabel) => {
    rail(screenId, null, search);
    const lit = current();
    expect(lit).toHaveLength(1);
    expect(lit[0].tagName).toBe("A");
    expect(lit[0].textContent).toBe(label);
    // the group that holds the open page is open and drawn stronger; no other header is
    const strong = [...nav().querySelectorAll('[data-holds-current="true"]')].map((b) => b.textContent);
    expect(strong).toEqual(groupLabel ? [groupLabel] : []);
    if (groupLabel) expect(header(groupLabel)).toHaveAttribute("aria-expanded", "true");
  });

  it("follows a tab switched on the screen itself, and the browser's Back", () => {
    rail("operations");
    expect(current()[0]).toHaveTextContent("HA and readiness");
    act(() => writeUrlParams({ tab: "jobs" }, "push"));
    expect(current()).toHaveLength(1);
    expect(current()[0]).toHaveTextContent("Jobs");
    act(() => {
      window.history.replaceState(null, "", "/?screen=operations&tab=health");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(current()[0]).toHaveTextContent("Device health");
  });

  it("opens and closes a group in place, remembers it per viewer, and never closes the group of the open page", () => {
    const first = rail("overview");
    fireEvent.click(header("Tools"));
    expect(header("Tools")).toHaveAttribute("aria-expanded", "false");
    expect(within(nav()).queryByText("Diagnostics")).toBeNull();
    fireEvent.click(header("Administration"));
    expect(within(nav()).getByRole("link", { name: "Notifications and logging" })).toHaveAttribute("href", "?screen=administration&tab=platform");
    expect(window.localStorage.getItem(GROUPS_KEY)).toBe("tools:0,administration:1");
    first.unmount();

    const second = rail("overview");
    expect(header("Tools")).toHaveAttribute("aria-expanded", "false");
    expect(header("Administration")).toHaveAttribute("aria-expanded", "true");
    expect(header("Operations")).toHaveAttribute("aria-expanded", "true");
    second.unmount();

    // Tools was closed by the viewer, but Diagnostics is the open page: its group is open, and stays open on a click.
    rail("diagnostics");
    expect(header("Tools")).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(header("Tools"));
    expect(header("Tools")).toHaveAttribute("aria-expanded", "true");
    expect(current()[0]).toHaveTextContent("Diagnostics");
  });

  it("lists by plane as before: no Devices or Compliance for a session without those planes, Tools without the Data sanitizer", () => {
    rail("operations", viewer);
    expect(within(nav()).queryByRole("link", { name: "Devices" })).toBeNull();
    expect(within(nav()).queryByText("Compliance and security")).toBeNull();
    expect(within(nav()).getByRole("link", { name: "Backups" })).toBeInTheDocument();
    expect(within(nav()).getByRole("link", { name: "Jobs" })).toBeInTheDocument();
    const tools = document.getElementById("nav-group-tools")!;
    expect(within(tools).getAllByRole("link").map((a) => a.textContent)).toEqual(["Packet analysis", "Diagnostics", "Offline analysis"]);
  });

  it("keeps only the lit entry for a session without the Devices plane that opens Devices", () => {
    const first = rail("inventory", viewer);
    expect(current()).toHaveLength(1);
    expect(current()[0]).toHaveTextContent("Devices");
    expect(within(nav()).queryByText("Lifecycle and security")).toBeNull();
    expect(within(nav()).queryByText("Compliance and security")).toBeNull();
    first.unmount();
    rail("inventory", viewer, "?screen=inventory&view=lifecycle");
    expect(current()).toHaveLength(1);
    expect(current()[0]).toHaveTextContent("Lifecycle and security");
    expect(within(nav()).queryByRole("link", { name: "Devices" })).toBeNull();
    expect(within(nav()).queryByRole("link", { name: "Compliance" })).toBeNull();
  });

  it("keeps the entry of the open screen for a session without its plane", () => {
    rail("sanitizer", viewer);
    expect(current()).toHaveLength(1);
    expect(current()[0]).toHaveTextContent("Data sanitizer");
  });

  // Post-release review 2026-10-18, R4, as amended 2026-11-12: Administration is on every screen for every session, last.
  it.each<ScreenId>(["overview", "inventory", "packet_analysis", "sanitizer", "compliance"])("has Administration on %s for a read-only session, last, with its pages listed and locked rather than hidden", (screenId) => {
    rail(screenId, viewer);
    expect((nav().textContent ?? "").trim().endsWith("Administration")).toBe(true);
    fireEvent.click(header("Administration"));
    const pages = within(document.getElementById("nav-group-administration")!).getAllByRole("link");
    expect(pages.map((a) => [a.textContent, a.getAttribute("href"), a.getAttribute("data-locked")])).toEqual([
      ["Device registry", "?screen=administration&tab=registry", null],
      ["Access and identity", "?screen=administration&tab=access", "true"],
      ["Notifications and logging", "?screen=administration&tab=platform", "true"],
      ["Audit log", "?screen=administration&tab=audit", "true"],
      ["Delivery plan", "?screen=administration&tab=product", "true"],
    ]);
    expect(pages[1].querySelector("[data-nav-lock]")).toHaveAttribute("title", "Restricted for this account");
    for (const page of pages) expect(page).not.toHaveAttribute("aria-current");
  });

  it("locks nothing for a session that holds the Admin plane and the administration-read grant", () => {
    rail("administration", { ...viewer, permissions: ["Admin"], canReadAdministration: true });
    expect(nav().querySelectorAll("[data-locked]")).toHaveLength(0);
    expect(current()[0]).toHaveTextContent("Device registry");
  });
});

describe("the narrow layout", () => {
  it(`shrinks to icons below ${DRAWER_BELOW_PX} px, keeps the active item, and opens the same list as a drawer from the ≡ button`, () => {
    viewport(DRAWER_BELOW_PX - 1);
    rail("backups");
    expect(nav()).toHaveAttribute("data-nav-layout", "narrow");
    expect(nav()).toHaveStyle({ width: `${FOLDED_PX}px` });
    expect(within(nav()).queryByText("Backups")).toBeNull();
    expect(current()).toHaveLength(1);
    expect(current()[0]).toHaveAccessibleName("Backups");
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    const drawer = screen.getByRole("navigation", { name: "Primary navigation drawer" });
    for (const entry of NAV_ENTRIES) {
      expect(within(drawer).getByText(entry.label)).toBeInTheDocument();
      if (entry.kind === "group" && !entry.startsClosed) for (const leaf of entry.children) expect(within(drawer).getByRole("link", { name: leaf.label })).toHaveAttribute("href", leaf.href);
    }
    // no placeholder, nothing disabled (D-NAV6b)
    expect(within(drawer).queryByText("Discovery")).toBeNull();
    expect(drawer.querySelector("[aria-disabled]")).toBeNull();
    expect(current(drawer)).toHaveLength(1);
    expect(current(drawer)[0]).toHaveTextContent("Backups");
    fireEvent.click(within(drawer).getByRole("button", { name: "Close navigation" }));
  });

  it("lights the exact page in the drawer, not only its screen", () => {
    viewport(900);
    rail("operations", null, "?screen=operations&tab=jobs");
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    const drawer = screen.getByRole("navigation", { name: "Primary navigation drawer" });
    expect(current(drawer)).toHaveLength(1);
    expect(current(drawer)[0]).toHaveTextContent("Jobs");
  });

  // PO 2026-11-12, question 2: the drawer used to hide the Administration pages without the Admin plane.
  it("lists the five Administration pages, locked, in the drawer of a session without the Admin plane", () => {
    viewport(900);
    rail("overview", viewer);
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    const drawer = screen.getByRole("navigation", { name: "Primary navigation drawer" });
    fireEvent.click(header("Administration", drawer));
    const pages = within(drawer).getAllByRole("link").filter((a) => (a.getAttribute("href") ?? "").startsWith("?screen=administration"));
    expect(pages.map((a) => [a.textContent, a.getAttribute("data-locked")])).toEqual([
      ["Device registry", null], ["Access and identity", "true"], ["Notifications and logging", "true"], ["Audit log", "true"], ["Delivery plan", "true"],
    ]);
  });

  it("has no fold control below the drawer width", () => {
    viewport(DRAWER_BELOW_PX - 1);
    rail("overview");
    expect(within(nav()).queryByRole("button", { name: /menu$/ })).toBeNull();
    expect(screen.getByRole("button", { name: "Open navigation" })).toBeInTheDocument();
  });
});

describe("the sidebar folds to icons (PO 2026-10-20)", () => {
  it("collapses to icons, keeps every destination reachable by name, and expands again", () => {
    viewport(1440);
    rail("inventory");
    const toggle = within(nav()).getByRole("button", { name: "Collapse menu" });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    expect(within(nav()).getByText("Devices")).toBeInTheDocument();
    fireEvent.click(toggle);
    // labels gone, the icons named for the hover tooltip and the screen reader, the active item kept
    expect(nav()).toHaveAttribute("data-nav-layout", "folded");
    expect(within(nav()).queryByText("Devices")).toBeNull();
    expect(within(nav()).getByRole("link", { name: "Devices" })).toHaveAttribute("aria-current", "page");
    // the same nine entries (D-NAV2): four links, five groups
    expect(within(nav()).getAllByRole("link").map((a) => a.getAttribute("aria-label"))).toEqual(["Overview", "Reports", "Devices", "Backups"]);
    for (const name of ["Compliance and security", "Policy and changes", "Operations", "Tools", "Administration"]) {
      expect(header(name)).toHaveAttribute("aria-haspopup", "menu");
    }
    expect(current()).toHaveLength(1);
    // no drawer at this width, folded or not
    expect(screen.queryByRole("button", { name: "Open navigation" })).toBeNull();
    const expand = within(nav()).getByRole("button", { name: "Expand menu" });
    expect(expand).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(expand);
    expect(within(nav()).getByText("Devices")).toBeInTheDocument();
    expect(within(nav()).getByText("Tools")).toBeInTheDocument();
  });

  it("opens a group's children from its icon as menu links, marking the open page, so the keyboard reaches each", () => {
    viewport(1440);
    window.localStorage.setItem(FOLD_KEY, "1");
    rail("operations", null, "?screen=operations&tab=jobs");
    // the page is a child: the group's icon says it holds the current page, the page itself is marked in the menu
    expect(current()).toHaveLength(0);
    const operations = header("Operations");
    expect(operations).toHaveAttribute("aria-current", "true");
    expect(operations.tabIndex).toBeGreaterThanOrEqual(0);
    fireEvent.click(operations);
    const menu = screen.getByRole("menu", { name: "Operations" });
    expect(operations).toHaveAttribute("aria-expanded", "true");
    const items = within(menu).getAllByRole("menuitem");
    expect(items.map((i) => [i.textContent, i.getAttribute("href")])).toEqual([
      ["HA and readiness", "?screen=operations"], ["Jobs", "?screen=operations&tab=jobs"], ["Scheduled jobs", "?screen=operations&tab=schedules"],
      ["Device health", "?screen=operations&tab=health"], ["Platform status", "?screen=operations&tab=platform-status"],
    ]);
    for (const item of items) expect(item.tagName).toBe("A");
    expect(items[1]).toHaveAttribute("aria-current", "page");
    expect(items[0]).not.toHaveAttribute("aria-current");
    expect(menu.contains(document.activeElement)).toBe(true);
  });

  it("applies the plane list to a folded group's menu too", () => {
    viewport(1440);
    window.localStorage.setItem(FOLD_KEY, "1");
    rail("operations", viewer);
    fireEvent.click(header("Tools"));
    expect(within(screen.getByRole("menu", { name: "Tools" })).getAllByRole("menuitem").map((i) => i.textContent)).toEqual(["Packet analysis", "Diagnostics", "Offline analysis"]);
  });

  it("remembers the fold per viewer and reads it back on the next render", () => {
    viewport(1440);
    const first = rail("overview");
    fireEvent.click(within(nav()).getByRole("button", { name: "Collapse menu" }));
    expect(window.localStorage.getItem(FOLD_KEY)).toBe("1");
    first.unmount();
    rail("overview");
    expect(within(nav()).queryByText("Overview")).toBeNull();
    expect(within(nav()).getByRole("button", { name: "Expand menu" })).toBeInTheDocument();
  });

  it(`starts folded between ${DRAWER_BELOW_PX} and ${EXPANDED_BY_DEFAULT_PX - 1} px, expanded from ${EXPANDED_BY_DEFAULT_PX} px, and the viewer's choice wins either way`, () => {
    viewport(DRAWER_BELOW_PX);
    const tight = rail("overview");
    expect(nav()).toHaveAttribute("data-nav-layout", "folded");
    expect(screen.queryByRole("button", { name: "Open navigation" })).toBeNull();
    fireEvent.click(within(nav()).getByRole("button", { name: "Expand menu" }));
    expect(nav()).toHaveAttribute("data-nav-layout", "expanded");
    expect(window.localStorage.getItem(FOLD_KEY)).toBe("0");
    tight.unmount();
    // the stored choice holds at the tight width
    const again = rail("overview");
    expect(nav()).toHaveAttribute("data-nav-layout", "expanded");
    again.unmount();
    window.localStorage.removeItem(FOLD_KEY);

    viewport(EXPANDED_BY_DEFAULT_PX - 1);
    const below = rail("overview");
    expect(nav()).toHaveAttribute("data-nav-layout", "folded");
    below.unmount();

    viewport(EXPANDED_BY_DEFAULT_PX);
    const roomy = rail("overview");
    expect(nav()).toHaveAttribute("data-nav-layout", "expanded");
    roomy.unmount();
    window.localStorage.setItem(FOLD_KEY, "1");
    rail("overview");
    expect(nav()).toHaveAttribute("data-nav-layout", "folded");
  });

  it("renders the expanded sidebar when the browser store is unusable, and still folds for the visit", () => {
    viewport(1440);
    const store = Object.getOwnPropertyDescriptor(window, "localStorage")!;
    Object.defineProperty(window, "localStorage", { configurable: true, get() { throw new Error("blocked"); } });
    try {
      rail("overview");
      expect(within(nav()).getByText("Devices")).toBeInTheDocument();
      fireEvent.click(header("Tools"));
      expect(header("Tools")).toHaveAttribute("aria-expanded", "false");
      fireEvent.click(within(nav()).getByRole("button", { name: "Collapse menu" }));
      expect(within(nav()).queryByText("Devices")).toBeNull();
    } finally {
      Object.defineProperty(window, "localStorage", store);
    }
  });
});

describe("the logo (brand area 2026-11-13)", () => {
  const brands = (root: ParentNode = document) => [...root.querySelectorAll("[data-brand]")].map((el) => el.getAttribute("data-brand"));

  it("is the wordmark, once, on the expanded sidebar", () => {
    viewport(1440);
    rail("overview");
    expect(nav()).toHaveAttribute("data-nav-layout", "expanded");
    expect(within(nav()).getAllByRole("img", { name: "neXus" })).toHaveLength(1);
    expect(brands()).toEqual(["wordmark"]);
    expect(document.querySelector("img")).toBeNull();
  });

  it("is the X mark alone, once, when the sidebar is folded", () => {
    viewport(1440);
    rail("overview");
    fireEvent.click(within(nav()).getByRole("button", { name: "Collapse menu" }));
    expect(nav()).toHaveAttribute("data-nav-layout", "folded");
    expect(within(nav()).getAllByRole("img", { name: "neXus" })).toHaveLength(1);
    expect(brands()).toEqual(["mark"]);
  });

  it("is the X mark on the narrow strip, and the wordmark once in the drawer with no caption and no footer text", () => {
    viewport(1000);
    rail("overview");
    expect(nav()).toHaveAttribute("data-nav-layout", "narrow");
    expect(brands()).toEqual(["mark"]);
    fireEvent.click(within(nav()).getByRole("button", { name: "Open navigation" }));
    const drawer = screen.getByRole("navigation", { name: "Primary navigation drawer" });
    expect(within(drawer).getAllByRole("img", { name: "neXus" })).toHaveLength(1);
    expect(brands(drawer)).toEqual(["wordmark"]);
    // The open drawer hides the strip behind it from the accessibility tree; the strip itself still holds the one mark.
    expect(brands(document.querySelector('[data-nav-layout="narrow"]')!)).toEqual(["mark"]);
    expect(within(drawer).getByRole("button", { name: "Close navigation" })).toBeInTheDocument();
    expect(screen.queryByText("Verified state")).toBeNull();
    expect(screen.queryByText(/Read-only evidence/)).toBeNull();
  });

  it("never prints SX in any layout", () => {
    for (const width of [1440, 1150, 1000]) {
      viewport(width);
      const { unmount } = rail("overview");
      expect(screen.queryByText("SX")).toBeNull();
      unmount();
    }
  });
});
