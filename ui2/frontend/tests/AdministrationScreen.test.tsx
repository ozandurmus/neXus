import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { AdministrationScreen } from "../src/screens/AdministrationScreen";

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const TABS = [
  { label: "Device management", marker: "Device registry · 0 entries" },
  { label: "Inventory exclusions", marker: "No device excluded" },
  { label: "Credentials", marker: "No credential profile configured" },
  { label: "Project plan", marker: "No project plan" },
];

describe("AdministrationScreen tabs", () => {
  it("renders each tab's own panel, and no other tab's panel, tab by tab", () => {
    render(withTheme(<AdministrationScreen />));
    const tablist = screen.getByRole("tablist", { name: "Administration sections" });

    for (const tab of TABS) {
      fireEvent.click(within(tablist).getByRole("tab", { name: tab.label }));
      expect(screen.getByText(tab.marker)).toBeInTheDocument();
      for (const other of TABS) {
        if (other.label === tab.label) continue;
        expect(screen.queryByText(other.marker)).toBeNull();
      }
    }
  });

  it("Project plan is no longer blank", () => {
    render(withTheme(<AdministrationScreen />));
    fireEvent.click(screen.getByRole("tab", { name: "Project plan" }));
    expect(screen.getByText("No project plan")).toBeInTheDocument();
  });

  it("defaults to the Device management tab, empty", () => {
    render(withTheme(<AdministrationScreen />));
    expect(screen.getByText("Device registry · 0 entries")).toBeInTheDocument();
    expect(screen.queryByText("No project plan")).toBeNull();
  });
});
