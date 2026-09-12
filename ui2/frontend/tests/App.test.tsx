import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { DESTINATIONS, DRAWER_GROUPS } from "../src/shell/NavigationRail";

// Deliberately distinct from any navigation-rail label ("Compliance" and
// "Operations" both also name a collapsed rail item, so the screen's own
// subtitle/panel text is used instead of its heading to keep each query
// unambiguous.
const PRODUCT_MARKERS: Record<string, string> = {
  overview: "Operational posture",
  inventory: "Network inventory",
  configuration: "Configuration",
  compliance: "No framework assigned · nothing assessed yet",
  operations: "No jobs yet",
  administration: "Administration",
};

const PREVIEW_MARKERS: Record<string, string> = {
  overview: "fw-ist-core-02",
  inventory: "cp-mds-01",
  configuration: "fw-ist-core-CLS",
  compliance: "CIS Benchmarks",
  operations: "Recent jobs",
  administration: "pan-izm-edge-02",
};

describe("the UI 2.0 shell navigation", () => {
  it("renders every collapsed rail destination from the design canvas, in order", () => {
    render(<App />);
    const rail = screen.getByRole("navigation", { name: "Primary" });
    const labels = DESTINATIONS.map((d) => d.label);
    expect(labels).toEqual(["Overview", "Devices", "Config", "Compliance", "Operations", "Admin"]);
    for (const d of DESTINATIONS) {
      expect(within(rail).getByText(d.label)).toBeInTheDocument();
    }
  });

  it("expands to the M3Components drawer and back", () => {
    render(<App />);
    const rail = screen.getByRole("navigation", { name: "Primary" });

    expect(within(rail).queryByText("SecurityExpert")).toBeNull();
    fireEvent.click(within(rail).getByRole("button", { name: "Expand navigation" }));

    const drawer = screen.getByRole("navigation", { name: "Primary" });
    expect(within(drawer).getByText("SecurityExpert")).toBeInTheDocument();
    expect(within(drawer).getByText("Overview")).toBeInTheDocument();
    for (const g of DRAWER_GROUPS) {
      expect(within(drawer).getByText(g.header)).toBeInTheDocument();
      for (const leaf of g.leaves) {
        expect(within(drawer).getByText(leaf.label)).toBeInTheDocument();
      }
    }

    fireEvent.click(within(drawer).getByRole("button", { name: "Collapse navigation" }));
    const collapsedRail = screen.getByRole("navigation", { name: "Primary" });
    expect(within(collapsedRail).queryByText("SecurityExpert")).toBeNull();
  });

  it("marks a leaf the shell cannot yet serve as disabled rather than hiding it", () => {
    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: "Expand navigation" }));
    const drawer = screen.getByRole("navigation", { name: "Primary" });

    const unbuilt = DRAWER_GROUPS.flatMap((g) => g.leaves).filter((l) => !l.screen);
    expect(unbuilt.length).toBeGreaterThan(0);
    for (const leaf of unbuilt) {
      const item = within(drawer).getByText(leaf.label).closest("[aria-disabled]");
      expect(item).not.toBeNull();
    }
  });
});

describe("the six product screens", () => {
  it("each renders its own empty state, with no evidence invented", () => {
    // Every screen's own subtitle or panel text says the state is empty
    // (a zero count or an explicit "nothing yet"/"no device" statement);
    // none of them ever shows a plausible-looking populated card.
    for (const [id, marker] of Object.entries(PRODUCT_MARKERS)) {
      const { unmount } = render(<App search={`?screen=${id}`} />);
      expect(screen.getByText(marker)).toBeInTheDocument();
      unmount();
    }
  });

  it("shows a metric card's count as zero, never a placeholder, on the screens that have one", () => {
    for (const id of ["overview", "compliance", "operations"]) {
      const { unmount } = render(<App search={`?screen=${id}`} />);
      expect(screen.getAllByText("0").length).toBeGreaterThan(0);
      unmount();
    }
  });

  it("defaults to the Overview screen, empty, when no screen is named", () => {
    render(<App search="" />);
    expect(screen.getByText("Operational posture")).toBeInTheDocument();
    expect(screen.getAllByText("0").length).toBeGreaterThan(0);
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
