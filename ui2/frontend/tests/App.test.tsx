import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { DESTINATIONS } from "../src/shell/NavigationRail";

describe("the UI 2.0 shell", () => {
  it("renders every navigation destination from the design canvas", () => {
    render(<App />);
    const rail = screen.getByRole("navigation", { name: "Primary" });
    for (const d of DESTINATIONS) {
      expect(within(rail).getByText(d.label)).toBeInTheDocument();
    }
  });

  it("marks a destination the shell cannot serve as disabled rather than hiding it", () => {
    // The product rule is visible-but-refused: a control the build cannot
    // serve is shown and marked, never dropped from the menu. Hiding it would
    // make the shell look complete and leave the operator guessing what exists.
    render(<App />);
    const rail = screen.getByRole("navigation", { name: "Primary" });
    const unavailable = DESTINATIONS.filter((d) => !d.enabled);
    expect(unavailable.length).toBeGreaterThan(0);
    for (const d of unavailable) {
      const item = within(rail).getByText(d.label).closest("[aria-disabled]");
      expect(item).not.toBeNull();
    }
  });

  it("states the empty state as empty instead of showing seeded counts", () => {
    // AGENTS.md forbids fabricated certainty, and a demo row in a product's
    // first screen is exactly that. The counts must read zero because the
    // database is empty, not because a placeholder says so.
    render(<App />);
    expect(screen.getByText("No devices yet")).toBeInTheDocument();
    expect(screen.getByText("0 enrolled")).toBeInTheDocument();
    expect(screen.getAllByText("0")).toHaveLength(3);
  });
});

describe("the design preview", () => {
  it("renders the populated target screens only behind an explicit flag", () => {
    render(<App search="?preview=overview" />);
    expect(screen.getByText("Operational posture")).toBeInTheDocument();
    expect(screen.getByText("fw-ist-core-02")).toBeInTheDocument();
  });

  it("always says a preview is synthetic", () => {
    // A mockup that does not say it is a mockup becomes a screenshot someone
    // later reads as a status report.
    for (const s of ["?preview=overview", "?preview=inventory"]) {
      const { unmount } = render(<App search={s} />);
      expect(screen.getByText("DESIGN PREVIEW")).toBeInTheDocument();
      expect(screen.getByText(/No device has been contacted/)).toBeInTheDocument();
      unmount();
    }
  });

  it("never leaks preview data into the product screen", () => {
    // The default screen is the product's own, and the database is empty.
    render(<App search="" />);
    expect(screen.getByText("No devices yet")).toBeInTheDocument();
    expect(screen.queryByText("fw-ist-core-02")).toBeNull();
    expect(screen.queryByText("DESIGN PREVIEW")).toBeNull();
  });
});
