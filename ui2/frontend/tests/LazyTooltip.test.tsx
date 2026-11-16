import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import Box from "@mui/material/Box";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { LazyTooltip } from "../src/shell/LazyTooltip";

const mount = (node: React.ReactElement) => render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>);

describe("LazyTooltip: a MUI Tooltip built on first contact", () => {
  it("gives the child the label MUI would, and builds nothing until contact", () => {
    mount(<LazyTooltip title="Exact value"><Box component="span">12 min ago</Box></LazyTooltip>);
    expect(screen.getByText("12 min ago")).toHaveAttribute("aria-label", "Exact value");
    expect(screen.queryByRole("tooltip")).toBeNull();
  });

  it("keeps a label the child names itself with", () => {
    mount(<LazyTooltip title="Check Point"><Box component="span" aria-label="Vendor badge">CP</Box></LazyTooltip>);
    expect(screen.getByText("CP")).toHaveAttribute("aria-label", "Vendor badge");
  });

  it("shows the tooltip on the first hover, as the plain Tooltip does, and hides it when the pointer leaves", async () => {
    mount(<LazyTooltip title="175.1x the fleet median"><Box component="span">Large</Box></LazyTooltip>);
    fireEvent.mouseOver(screen.getByText("Large"));
    expect(await screen.findByRole("tooltip")).toHaveTextContent("175.1x the fleet median");
    fireEvent.mouseLeave(screen.getByText("Large"));
    await waitFor(() => expect(screen.queryByRole("tooltip")).toBeNull());
    // a second hover works on the real Tooltip
    fireEvent.mouseOver(screen.getByText("Large"));
    expect(await screen.findByRole("tooltip")).toHaveTextContent("175.1x the fleet median");
  });

  it("still runs the child's own hover handler on the first contact", () => {
    const own = vi.fn();
    mount(<LazyTooltip title="t"><Box component="span" onMouseOver={own}>word</Box></LazyTooltip>);
    fireEvent.mouseOver(screen.getByText("word"));
    expect(own).toHaveBeenCalledTimes(1);
  });

  it("keeps the child's ref working before and after the Tooltip is built", async () => {
    const seen: (HTMLElement | null)[] = [];
    mount(<LazyTooltip title="t"><Box component="span" ref={(node: HTMLElement | null) => { seen.push(node); }}>word</Box></LazyTooltip>);
    expect(seen.at(-1)).toBe(screen.getByText("word"));
    fireEvent.mouseOver(screen.getByText("word"));
    await screen.findByRole("tooltip");
    expect(seen.at(-1)).toBe(screen.getByText("word"));
  });
});
