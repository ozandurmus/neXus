import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { InventoryScreen } from "../src/screens/InventoryScreen";

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const TABS = [
  { label: "Interfaces", marker: "No interface evidence" },
  { label: "Routing", marker: "No routing evidence" },
  { label: "Cluster members", marker: "No cluster membership evidence" },
  { label: "Identity & provenance", marker: "No identity or provenance evidence" },
];

describe("InventoryScreen detail tabs", () => {
  it("renders each tab's own panel, and no other tab's panel, tab by tab", () => {
    render(withTheme(<InventoryScreen />));
    const tablist = screen.getByRole("tablist", { name: "Device detail" });

    for (const tab of TABS) {
      fireEvent.click(within(tablist).getByRole("tab", { name: tab.label }));
      expect(screen.getByText(tab.marker)).toBeInTheDocument();
      for (const other of TABS) {
        if (other.label === tab.label) continue;
        expect(screen.queryByText(other.marker)).toBeNull();
      }
    }
  });

  it("defaults to the Interfaces tab, empty", () => {
    render(withTheme(<InventoryScreen />));
    expect(screen.getByText("No interface evidence")).toBeInTheDocument();
    expect(screen.queryByText("No routing evidence")).toBeNull();
  });
});
