import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { AdministrationScreen } from "../src/screens/AdministrationScreen";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

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

describe("AdministrationScreen Local identities tab", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows the empty state and a create action when no identity exists", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(200, { identities: [] })));
    render(withTheme(<AdministrationScreen />));

    fireEvent.click(screen.getByRole("tab", { name: "Local identities" }));

    await waitFor(() => expect(screen.getByText("No local identity yet")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Create identity" })).toBeInTheDocument();
  });

  it("lists an identity's 13G section 3 fields and never a password, verifier or salt", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(200, {
          identities: [
            {
              local_identity_id: "id-1",
              local_identity_name: "alice",
              enabled: true,
              must_change_password: true,
              created_at: "2026-09-13T00:00:00Z",
              password_set_at: "2026-09-13T00:00:00Z",
            },
          ],
        }),
      ),
    );
    render(withTheme(<AdministrationScreen />));

    fireEvent.click(screen.getByRole("tab", { name: "Local identities" }));

    await waitFor(() => expect(screen.getByText("alice")).toBeInTheDocument());
    expect(screen.getByText("Enabled")).toBeInTheDocument();
    expect(screen.getByText(/must change password at next sign-in/)).toBeInTheDocument();
    expect(document.body.textContent?.toLowerCase()).not.toMatch(/verifier|salt/);
  });

  it("shows the server's own refusal verbatim rather than inventing a different message", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(403, { error: "ACTION_REFUSED" })));
    render(withTheme(<AdministrationScreen />));

    fireEvent.click(screen.getByRole("tab", { name: "Local identities" }));

    await waitFor(() => expect(screen.getByText("ACTION_REFUSED")).toBeInTheDocument());
  });
});
