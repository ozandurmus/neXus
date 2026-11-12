import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { TopAppBar } from "../src/shell/TopAppBar";
import { SessionContext } from "../src/auth/SessionContext";

afterEach(() => vi.unstubAllGlobals());

function mountBar() {
  vi.stubGlobal("fetch", vi.fn(async (url: string) => new Response(JSON.stringify(url === "/project-plan"
    ? { deployed_commit: "0123456789abcdef", deployed_at: new Date().toISOString() } : url === "/notifications" ? { notifications: [] } : {}), { status: 200 })));
  return render(<ThemeProvider theme={m3Theme}><SessionContext.Provider value={{ displayName: "aiview", roleTokens: ["role:compliance_admin", "role:replay_viewer"],
    permissions: [], onSignOut: vi.fn() }}><TopAppBar /></SessionContext.Provider></ThemeProvider>);
}

it("shows the aiview persona by its own word, not as Compliance Admin (B7)", () => {
  mountBar();
  expect(screen.getByText("aiview · read-only")).toBeInTheDocument();
  expect(screen.queryByText("Compliance Admin")).toBeNull();
});

it("keeps the time-zone note and the build stamp out of the bar and in the account menu (B7)", async () => {
  mountBar();
  expect(screen.queryByText(/Times in GMT\+3/)).toBeNull();
  expect(screen.queryByText(/^Build /)).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Account menu" }));
  const menu = await screen.findByRole("menu", { name: "Account" });
  expect(within(menu).getByText("Times in GMT+3")).toBeInTheDocument();
  expect(await within(menu).findByText(/^Build 0123456/)).toBeInTheDocument();
  expect(within(menu).getByText(/aiview · read-only/)).toBeInTheDocument();
  expect(within(menu).getByRole("button", { name: "Sign out" })).toBeInTheDocument();
});

it("carries no logo and no SX badge: search and account controls only (brand area 2026-11-13)", () => {
  const { container } = mountBar();
  expect(screen.queryByRole("img", { name: "neXus" })).toBeNull();
  expect(container.querySelector("[data-brand]")).toBeNull();
  expect(screen.queryByText("SX")).toBeNull();
  expect(screen.getByRole("textbox", { name: "Search devices, settings and evidence" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Account menu" })).toBeInTheDocument();
});
