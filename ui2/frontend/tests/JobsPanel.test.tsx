import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { JobsPanel, type VisibleJob } from "../src/screens/JobsPanel";
import { m3Theme } from "../src/theme/m3Theme";

const jobs: readonly VisibleJob[] = [
  { jobId: "job-active", capability: "Inventory", target: "device-a", state: "EXECUTING", steps: [{ stepIndex: 1, stepKind: "collect", attemptNumber: 1 }] },
  { jobId: "job-stopped", capability: "Inventory", target: "device-b", state: "CANCELLED" },
  { jobId: "job-unknown", capability: "Inventory", target: "device-c", state: "OUTCOME_UNKNOWN" },
];

function renderPanel() {
  render(<ThemeProvider theme={m3Theme}><JobsPanel jobs={jobs} /></ThemeProvider>);
}

describe("JobsPanel", () => {
  it("keeps state classifications and filter counts together", () => {
    renderPanel();
    const tabs = screen.getByRole("tablist", { name: "Job visibility" });
    expect(within(tabs).getByRole("tab", { name: "Active (1)" })).toBeInTheDocument();
    expect(within(tabs).getByRole("tab", { name: "Needs you (1)" })).toBeInTheDocument();
    expect(within(tabs).getByRole("tab", { name: "Archive (1)" })).toBeInTheDocument();
  });

  it("never leaks a selected job into a different filter", () => {
    renderPanel();
    fireEvent.click(screen.getByRole("button", { name: /Inventory/ }));
    fireEvent.click(screen.getByRole("tab", { name: "Archive (1)" }));
    expect(screen.getByText("job-stopped · Inventory · device-b")).toBeInTheDocument();
    expect(screen.queryByText("job-active · Inventory · device-a")).toBeNull();
  });

  it("labels unknown duration and outcome without inventing progress", () => {
    renderPanel();
    fireEvent.click(screen.getByRole("tab", { name: "Needs you (1)" }));
    expect(screen.getByText("Outcome unknown")).toBeInTheDocument();
    expect(screen.getByText("Duration unknown")).toBeInTheDocument();
    expect(screen.getByText("a command may have reached the device; no automatic retry")).toBeInTheDocument();
    expect(screen.queryByText(/%/)).toBeNull();
  });

  it("supports keyboard filter changes and renders only committed steps", () => {
    renderPanel();
    const tabs = screen.getByRole("tablist", { name: "Job visibility" });
    fireEvent.keyDown(within(tabs).getByRole("tab", { name: "Active (1)" }), { key: "ArrowRight" });
    expect(screen.getByText("Outcome unknown")).toBeInTheDocument();
    fireEvent.keyDown(within(tabs).getByRole("tab", { name: "Needs you (1)" }), { key: "ArrowLeft" });
    expect(screen.getByText("collect · attempt 1")).toBeInTheDocument();
  });
});
