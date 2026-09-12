import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { ConfigurationScreen } from "../src/screens/ConfigurationScreen";

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const TABS = [
  { label: "Overview", marker: "No configuration overview" },
  { label: "Current state", marker: "No current-state evidence" },
  { label: "Alignment", marker: "No alignment evidence" },
  { label: "Policy & objects", marker: "No policy or object evidence" },
  { label: "History", marker: "No configuration history" },
  { label: "Evidence", marker: "No evidence bundle" },
  { label: "Backup", marker: "No backup evidence" },
];

describe("ConfigurationScreen detail tabs", () => {
  it("renders each tab's own panel, and no other tab's panel, tab by tab", () => {
    render(withTheme(<ConfigurationScreen />));
    const tablist = screen.getByRole("tablist", { name: "Configuration detail" });

    for (const tab of TABS) {
      fireEvent.click(within(tablist).getByRole("tab", { name: tab.label }));
      expect(screen.getByText(tab.marker)).toBeInTheDocument();
      for (const other of TABS) {
        if (other.label === tab.label) continue;
        expect(screen.queryByText(other.marker)).toBeNull();
      }
    }
  });

  it("defaults to the Alignment tab, empty", () => {
    render(withTheme(<ConfigurationScreen />));
    expect(screen.getByText("No alignment evidence")).toBeInTheDocument();
    expect(screen.queryByText("No configuration overview")).toBeNull();
  });
});
