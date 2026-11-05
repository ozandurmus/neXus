import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { DESTINATIONS, DRAWER_GROUPS } from "../src/shell/NavigationRail";

// Deliberately distinct from any navigation-rail label ("Compliance" and
// "Operations" both also name a collapsed rail item, so the screen's own
// subtitle/panel text is used instead of its heading to keep each query
// unambiguous.
const PRODUCT_MARKERS: Record<string, string> = {
  overview: "Reading the fleet…",
  // Without a server the catalogue read fails and the screen says so; it never shows a seeded report.
  reports: "The report catalogue could not be read: The report service could not be reached.",
  inventory: "No device to show",
  // One device screen (PO 2026-09-25): ?screen=configuration lands on Devices.
  configuration: "No device to show",
  // Without a store the overview read fails: the headline writes UNKNOWN for every figure, never a claim (review 4.3).
  compliance: "UNKNOWN of evidenced controls pass, UNKNOWN have no evidence · checks UNKNOWN · firewalls UNKNOWN · critical failing UNKNOWN",
  operations: "No HA pair or cluster enrolled",
  administration: "Administration",
  // Trace (C3) opens on the topology path mode, the old Path analysis (A5b): nothing is evaluated until a query is sent; the
  // screen never shows a seeded verdict.
  trace: "No path evaluated yet",
};

const PREVIEW_MARKERS: Record<string, string> = {
  overview: "2 of 45 HA clusters not ready for failover",
  reports: "Never inventoried (192.0.2.15)",
  inventory: "FW-MIKE-08",
  configuration: "CLS-ROMEO-01",
  compliance: "CIS Benchmarks",
  backups: "FW-TANGO-04 (PA-5410)",
  operations: "Recent jobs",
  administration: "FW-JULIET-06-M2",
  trace: "Traffic is PARTIAL: 2 branch(es) disagree",
};

/** jsdom has no matchMedia; this one says the viewport is narrower than the rail's drawer rule. */
function narrow() {
  vi.stubGlobal("matchMedia", (query: string) => ({ matches: false, media: query, onchange: null, addListener: () => {}, removeListener: () => {},
    addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false }));
}
afterEach(() => vi.unstubAllGlobals());

describe("the UI 2.0 shell navigation", () => {
  it.each(["", "/devices/synthetic-device"])("exposes exactly one content landmark at %s", pathname => {
    render(<App pathname={pathname} />);
    expect(screen.getAllByRole("main")).toHaveLength(1);
    expect(within(screen.getByRole("main")).queryByRole("navigation", { name: "Primary" })).toBeNull();
  });
  it("does not present non-interactive search text in the app bar", () => {
    render(<App />);
    expect(screen.queryByText(/Search devices, settings, evidence/)).toBeNull();
  });

  it("renders the five-verb rail in order, Administration last", () => {
    render(<App />);
    const rail = screen.getByRole("navigation", { name: "Primary" });
    // Reports R1: Reports sits directly under Overview (REPORTS_R1_CONTRACT.md "UI").
    expect(DESTINATIONS.map((d) => d.label)).toEqual(["Overview", "Reports", "Devices", "Compliance", "Policy", "Trace", "Changes", "Backups", "Operations", "Packets", "Sanitizer", "Diagnostics", "Offline analysis", "Administration"]);
    for (const label of ["Overview", "Reports", "Devices", "Compliance", "Policy", "Trace", "Changes", "Backups", "Operations", "Tools", "Administration"]) {
      expect(within(rail).getByText(label)).toBeInTheDocument();
    }
    // Packets, Sanitizer, Diagnostics and Offline analysis live behind the one Tools entry; the wide rail has no drawer
    expect(within(rail).queryByText("Packets")).toBeNull();
    expect(within(rail).queryByText("Diagnostics")).toBeNull();
    expect(screen.queryByRole("button", { name: "Open navigation" })).toBeNull();
  });

  it("opens the drawer only below the width rule, with every group and leaf, and closes it again", () => {
    narrow();
    render(<App />);
    const rail = screen.getByRole("navigation", { name: "Primary" });
    expect(within(rail).getByAltText("neXus")).toBeInTheDocument();
    fireEvent.click(within(rail).getByRole("button", { name: "Open navigation" }));

    const drawer = screen.getByRole("navigation", { name: "Primary navigation drawer" });
    expect(within(drawer).getByText("Overview")).toBeInTheDocument();
    for (const g of DRAWER_GROUPS) {
      expect(within(drawer).getByText(g.header)).toBeInTheDocument();
      for (const leaf of g.leaves) {
        expect(within(drawer).getByText(leaf.label)).toBeInTheDocument();
      }
    }
    fireEvent.click(within(drawer).getByRole("button", { name: "Close navigation" }));
    expect(screen.getByRole("navigation", { name: "Primary" })).toBeInTheDocument();
  });

  it("marks a leaf the shell cannot yet serve as disabled rather than hiding it", () => {
    narrow();
    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: "Open navigation" }));
    const drawer = screen.getByRole("navigation", { name: "Primary navigation drawer" });

    const unbuilt = DRAWER_GROUPS.flatMap((g) => g.leaves).filter((l) => !l.href);
    expect(unbuilt.length).toBeGreaterThan(0);
    for (const leaf of unbuilt) {
      const item = within(drawer).getByText(leaf.label).closest("[aria-disabled]");
      expect(item).not.toBeNull();
    }
  });
});

describe("the six product screens", () => {
  it("each renders its own empty state, with no evidence invented", async () => {
    // Every screen's own subtitle or panel text says the state is empty
    // (a zero count or an explicit "nothing yet"/"no device" statement);
    // none of them ever shows a plausible-looking populated card. Screens are
    // lazy chunks, so each is awaited.
    for (const [id, marker] of Object.entries(PRODUCT_MARKERS)) {
      const { unmount } = render(<App search={`?screen=${id}`} />);
      expect(await screen.findByText(marker)).toBeInTheDocument();
      unmount();
    }
  });

  // Overview and Operations count from the store since 2026-09-22 (PO: "0 devices enrolled" was a constant
  // over a fleet of 103); without a store they say the read failed, never 0. Compliance still renders zeros.
  it("writes UNKNOWN on a metric card whose figure was not read, never 0 or a placeholder dash", async () => {
    for (const id of ["compliance"]) {
      const { unmount } = render(<App search={`?screen=${id}`} />);
      expect((await screen.findAllByText("UNKNOWN")).length).toBeGreaterThan(0);
      expect(screen.queryByText("0%")).toBeNull();
      unmount();
    }
  });

  // Old addresses keep working (UI review 2026-11-05): each lands on its new home, its own parameters kept.
  it.each([
    ["?screen=path_analysis", "trace", "No path evaluated yet"],
    ["?screen=operations&tab=changes", "changes", "Who changed what, and when"],
    ["?screen=operations&tab=diagnostics", "diagnostics", "Run an approved read-only diagnostic"],
    ["?screen=administration&tab=job-logs", "operations", "All jobs"],
  ])("lands %s on its new screen", async (search, screenId, marker) => {
    const { unmount } = render(<App search={search} />);
    expect(await screen.findByText(new RegExp(marker))).toBeInTheDocument();
    expect(new URLSearchParams(window.location.search).get("screen")).toBe(screenId);
    unmount();
    window.history.replaceState(null, "", "/");
  });

  it("defaults to the Overview screen when no screen is named", async () => {
    render(<App search="" />);
    expect(await screen.findByText("Reading the fleet…")).toBeInTheDocument();
  });
});

describe("the design preview", () => {
  it("renders the populated target screen only behind an explicit flag, for every screen", () => {
    for (const [id, marker] of Object.entries(PREVIEW_MARKERS)) {
      const { unmount } = render(<App search={`?preview=${id}`} />);
      expect(screen.getByText(marker)).toBeInTheDocument();
      unmount();
    }
  });

  it("keeps the old ?preview=path_analysis address: it previews Trace", () => {
    const { unmount } = render(<App search="?preview=path_analysis" />);
    expect(screen.getByText(PREVIEW_MARKERS.trace)).toBeInTheDocument();
    unmount();
    window.history.replaceState(null, "", "/");
  });

  it("always says a preview is synthetic", () => {
    for (const id of Object.keys(PREVIEW_MARKERS)) {
      const { unmount } = render(<App search={`?preview=${id}`} />);
      expect(screen.getByText("DESIGN PREVIEW")).toBeInTheDocument();
      expect(screen.getByText(/No device has been contacted/)).toBeInTheDocument();
      unmount();
    }
  });

  it("never leaks preview data into any product screen", () => {
    for (const id of Object.keys(PRODUCT_MARKERS)) {
      const { unmount } = render(<App search={`?screen=${id}`} />);
      expect(screen.queryByText("DESIGN PREVIEW")).toBeNull();
      for (const marker of Object.values(PREVIEW_MARKERS)) {
        expect(screen.queryByText(marker)).toBeNull();
      }
      unmount();
    }
  });
});
