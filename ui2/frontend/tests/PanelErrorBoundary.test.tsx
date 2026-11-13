import { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PanelErrorBoundary } from "../src/shell/PanelErrorBoundary";
import { M3Tabs } from "../src/shell/M3Widgets";

const withTheme = (node: React.ReactElement) => <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;

/** Throws while rendering, with a message that stands in for response contents (it must never be logged). */
const SECRET = "synthetic-secret-hostname-fw-tango-04";
function Boom(): React.ReactElement {
  throw new TypeError(SECRET);
}

/** Throws until `state.broken` is cleared, to prove Retry mounts the children again. */
function Flaky({ state }: { readonly state: { broken: boolean; mounts: number } }) {
  state.mounts += 1;
  if (state.broken) throw new Error(SECRET);
  return <p>Section content</p>;
}

let errorSpy: ReturnType<typeof vi.spyOn>;
beforeEach(() => { errorSpy = vi.spyOn(console, "error").mockImplementation(() => {}); });
afterEach(() => { errorSpy.mockRestore(); });

describe("PanelErrorBoundary", () => {
  it("shows a contained state with Retry for a throwing child and leaves the shell usable", () => {
    function Shell() {
      const [signedOut, setSignedOut] = useState(false);
      return (
        <div>
          <nav aria-label="Shell">
            <button onClick={() => setSignedOut(true)}>Sign out</button>
            <span>{signedOut ? "Signed out" : "Signed in"}</span>
          </nav>
          <main><PanelErrorBoundary label="test panel"><Boom /></PanelErrorBoundary></main>
        </div>
      );
    }
    render(withTheme(<Shell />));
    expect(screen.getByText("This section could not be shown")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
    // The shell outside the boundary still works.
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect(screen.getByText("Signed out")).toBeInTheDocument();
  });

  it("logs the section and the error class, never the error's message", () => {
    render(withTheme(<PanelErrorBoundary label="test panel"><Boom /></PanelErrorBoundary>));
    const logged = errorSpy.mock.calls.map((call) => call.map(String).join(" "));
    expect(logged.some((line) => line.includes("Section could not be shown (test panel): TypeError"))).toBe(true);
    // React's own report of the error also goes to console.error in a test run; the product's line is the one asserted above.
    expect(logged.filter((line) => line.startsWith("Section could not be shown")).every((line) => !line.includes(SECRET))).toBe(true);
  });

  it("Retry mounts the children again, and they render once the cause is gone", () => {
    const state = { broken: true, mounts: 0 };
    render(withTheme(<PanelErrorBoundary><Flaky state={state} /></PanelErrorBoundary>));
    expect(screen.getByText("This section could not be shown")).toBeInTheDocument();
    const before = state.mounts;
    state.broken = false;
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(screen.getByText("Section content")).toBeInTheDocument();
    expect(screen.queryByText("This section could not be shown")).not.toBeInTheDocument();
    expect(state.mounts).toBeGreaterThan(before);
  });

  it("clears a shown failure when resetKey changes", () => {
    const state = { broken: true, mounts: 0 };
    const view = render(withTheme(<PanelErrorBoundary resetKey="a"><Flaky state={state} /></PanelErrorBoundary>));
    expect(screen.getByText("This section could not be shown")).toBeInTheDocument();
    state.broken = false;
    view.rerender(withTheme(<PanelErrorBoundary resetKey="b"><Flaky state={state} /></PanelErrorBoundary>));
    expect(screen.getByText("Section content")).toBeInTheDocument();
  });

  it("M3Tabs contains a failing tab panel: the tab list stays and another tab still opens", () => {
    render(withTheme(
      <M3Tabs ariaLabel="Test sections" tabs={[
        { label: "Broken", panel: <Boom /> },
        { label: "Fine", panel: <p>Fine content</p> },
      ]} />,
    ));
    expect(screen.getByText("This section could not be shown")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Fine" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "Fine" }));
    expect(screen.getByText("Fine content")).toBeInTheDocument();
    expect(screen.queryByText("This section could not be shown")).not.toBeInTheDocument();
  });

  it("M3Tabs with keepMounted contains a failing panel too", () => {
    render(withTheme(
      <M3Tabs ariaLabel="Kept sections" keepMounted tabs={[
        { label: "Broken", panel: <Boom /> },
        { label: "Fine", panel: <p>Fine content</p> },
      ]} />,
    ));
    expect(screen.getByText("This section could not be shown")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "Fine" }));
    expect(screen.getByText("Fine content")).toBeInTheDocument();
  });
});
