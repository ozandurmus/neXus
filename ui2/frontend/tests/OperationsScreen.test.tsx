import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { OperationsScreen } from "../src/screens/OperationsScreen";

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const TABS = [
  { label: "HA & readiness", marker: "No HA pair or cluster enrolled" },
  { label: "Jobs", marker: "No jobs yet" },
  { label: "Queue", marker: "Nothing queued" },
  { label: "History", marker: "No job history" },
];

describe("OperationsScreen tabs", () => {
  it("renders each tab's own panel, and no other tab's panel, tab by tab", () => {
    render(withTheme(<OperationsScreen />));
    const tablist = screen.getByRole("tablist", { name: "Operations sections" });

    for (const tab of TABS) {
      fireEvent.click(within(tablist).getByRole("tab", { name: tab.label }));
      expect(screen.getByText(tab.marker)).toBeInTheDocument();
      for (const other of TABS) {
        if (other.label === tab.label) continue;
        expect(screen.queryByText(other.marker)).toBeNull();
      }
    }
  });

  it("defaults to the HA & readiness tab, empty", () => {
    render(withTheme(<OperationsScreen />));
    expect(screen.getByText("No HA pair or cluster enrolled")).toBeInTheDocument();
    expect(screen.queryByText("No jobs yet")).toBeNull();
  });

  it("renders rich pre-flight checklist and verdict banner when a cluster is inspected", () => {
    render(withTheme(<OperationsScreen />));

    // Click to inspect Check Point cluster
    fireEvent.click(screen.getByText(/Inspect CLS-ROMEO-01/i));

    // Verify cluster card & verdict banner
    expect(screen.getByText("CLS-ROMEO-01")).toBeInTheDocument();
    expect(screen.getByText(/VERDICT: NO_BLOCKING_CONDITIONS_OBSERVED/i)).toBeInTheDocument();

    // Verify core pre-flight checks are rendered
    expect(screen.getByText("Two-Sided Split-Brain Prevention")).toBeInTheDocument();
    expect(screen.getByText("Critical Problem Notifications (pnotes)")).toBeInTheDocument();
    expect(screen.getByText("State Synchronization Health")).toBeInTheDocument();

    // Verify failover mutation buttons are safely disabled
    const failoverBtn = screen.getByRole("button", { name: "Initiate Failover" });
    expect(failoverBtn).toBeDisabled();

    // Switch to Palo Alto cluster
    fireEvent.click(screen.getByText("CLS-TANGO-01 (PAN)"));
    expect(screen.getByText("CLS-TANGO-01")).toBeInTheDocument();
    expect(screen.getByText("HA Path & Link Monitoring")).toBeInTheDocument();
    expect(screen.getByText("Pending / In-Flight Commits")).toBeInTheDocument();

    // Clear cluster inspection
    fireEvent.click(screen.getByText("Clear"));
    expect(screen.getByText("No HA pair or cluster enrolled")).toBeInTheDocument();
  });
});
