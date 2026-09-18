import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { afterEach, expect, it, vi } from "vitest";
import { CustomRolesPanel } from "../src/screens/CustomRolesPanel";
import { m3Theme } from "../src/theme/m3Theme";

afterEach(() => vi.unstubAllGlobals());

it("lets an operator select and clear permissions while creating a role", async () => {
  const fetch = vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
    if (url === "/roles" || url.endsWith("/roles")) {
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }));
    }
    if (url === "/role-bindings" || url.endsWith("/role-bindings")) {
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }));
    }
    return Promise.resolve(new Response(JSON.stringify({ id: "role-1" }), { status: 200 }));
  });
  vi.stubGlobal("fetch", fetch);
  render(<ThemeProvider theme={m3Theme}><CustomRolesPanel /></ThemeProvider>);
  await waitFor(() => expect(fetch).toHaveBeenCalledWith("/roles"));
  fireEvent.click(screen.getByRole("button", { name: "Create role" }));
  const permission = screen.getByRole("checkbox", { name: "Devices" });
  fireEvent.click(permission);
  expect(permission).toBeChecked();
  fireEvent.click(permission);
  expect(permission).not.toBeChecked();
});
