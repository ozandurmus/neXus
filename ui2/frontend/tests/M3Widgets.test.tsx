import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { CapabilityMenu, M3Button, M3Tabs, StatusChip, ToggleRow } from "../src/shell/M3Widgets";
import { AddDeviceDialogTrigger } from "../src/shell/AddDeviceDialog";

/**
 * The `M3Components` states the six screens now draw from as shared
 * components, asserted directly rather than only through one host screen.
 */
function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

describe("StatusChip", () => {
  it("renders the label for every tone in the state vocabulary", () => {
    for (const tone of ["neutral", "ok", "mem", "warn", "attn", "bad"] as const) {
      const { unmount } = render(withTheme(<StatusChip tone={tone} label={`${tone} label`} />));
      expect(screen.getByText(`${tone} label`)).toBeInTheDocument();
      unmount();
    }
  });
});

describe("M3Tabs", () => {
  const threeTabs = [
    { label: "One", panel: <span>One's panel</span> },
    { label: "Two", panel: <span>Two's panel</span> },
    { label: "Three", panel: <span>Three's panel</span> },
  ];

  it("switches the selected tab and its panel on click", () => {
    render(withTheme(<M3Tabs ariaLabel="Test tabs" tabs={threeTabs} />));
    const tablist = screen.getByRole("tablist", { name: "Test tabs" });
    const one = within(tablist).getByRole("tab", { name: "One" });
    const two = within(tablist).getByRole("tab", { name: "Two" });
    expect(one).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("One's panel")).toBeInTheDocument();

    fireEvent.click(two);
    expect(two).toHaveAttribute("aria-selected", "true");
    expect(one).toHaveAttribute("aria-selected", "false");
    expect(screen.getByText("Two's panel")).toBeInTheDocument();
  });

  it("never renders a non-selected tab's panel at the same time as the selected one", () => {
    render(withTheme(<M3Tabs ariaLabel="Test tabs" tabs={threeTabs} />));
    expect(screen.getByText("One's panel")).toBeInTheDocument();
    expect(screen.queryByText("Two's panel")).toBeNull();
    expect(screen.queryByText("Three's panel")).toBeNull();

    fireEvent.click(screen.getByRole("tab", { name: "Three" }));
    expect(screen.getByText("Three's panel")).toBeInTheDocument();
    expect(screen.queryByText("One's panel")).toBeNull();
    expect(screen.queryByText("Two's panel")).toBeNull();
  });

  it("gives the panel role tabpanel, associated with the selected tab", () => {
    render(withTheme(<M3Tabs ariaLabel="Test tabs" tabs={threeTabs} />));
    const one = screen.getByRole("tab", { name: "One" });
    const panel = screen.getByRole("tabpanel");
    expect(panel).toHaveAttribute("aria-labelledby", one.id);
    expect(one).toHaveAttribute("aria-controls", panel.id);
  });

  it("moves the selection with arrow-key navigation", () => {
    render(withTheme(<M3Tabs ariaLabel="Test tabs" tabs={threeTabs} />));
    const one = screen.getByRole("tab", { name: "One" });
    const two = screen.getByRole("tab", { name: "Two" });
    act(() => one.focus());
    fireEvent.keyDown(one, { key: "ArrowRight" });
    expect(two).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("Two's panel")).toBeInTheDocument();
  });
});

describe("ToggleRow", () => {
  it("shows a disabled switch, on or off, without inviting a write", () => {
    render(withTheme(<ToggleRow label="Backup creation" checked={false} helperText="Stays off." />));
    expect(screen.getByText("Backup creation")).toBeInTheDocument();
    const toggle = screen.getByRole("checkbox");
    expect(toggle).toBeDisabled();
    expect(toggle).not.toBeChecked();
    expect(screen.getByText("Stays off.")).toBeInTheDocument();
  });
});

describe("CapabilityMenu", () => {
  it("shows an available action and an explained, disabled one", () => {
    render(
      withTheme(
        <CapabilityMenu
          ariaLabel="Test capabilities"
          items={[
            { label: "Export evidence bundle" },
            { label: "Collect now", disabledReason: "console only", dividerBefore: true },
          ]}
        />
      )
    );
    fireEvent.click(screen.getByRole("button", { name: "Test capabilities" }));
    const exportItem = screen.getByRole("menuitem", { name: /Export evidence bundle/ });
    const collectItem = screen.getByRole("menuitem", { name: /Collect now/ });
    expect(exportItem).not.toHaveAttribute("aria-disabled");
    expect(collectItem).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByText("console only")).toBeInTheDocument();
  });
});

describe("M3Button", () => {
  it("renders every emphasis", () => {
    for (const emphasis of ["filled", "tonal", "outlined", "text"] as const) {
      const { unmount } = render(withTheme(<M3Button emphasis={emphasis}>{emphasis} action</M3Button>));
      expect(screen.getByRole("button", { name: `${emphasis} action` })).toBeInTheDocument();
      unmount();
    }
  });
});

describe("AddDeviceDialogTrigger", () => {
  it("opens with blank fields and closes on Enrol without claiming success", async () => {
    render(withTheme(<AddDeviceDialogTrigger />));
    fireEvent.click(screen.getByRole("button", { name: "Add device" }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByLabelText("Device name")).toHaveValue("");
    expect(screen.getByText(/does not submit anywhere/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Enrol" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });
});
