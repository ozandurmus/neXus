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

/**
 * A URL-routed fetch mock, one fresh `Response` per call.
 *
 * `AdministrationScreen`'s default tab now fetches `GET /devices` on mount
 * (NXS-LOCAL-0157), so a single test can trigger more than one distinct
 * fetch (device registry on mount, then credentials/local-identities on tab
 * switch). A single shared `vi.fn().mockResolvedValue(response)` reuses the
 * *same* `Response` object for every call, and a `Response` body can only be
 * read once -- the second `.json()` read throws, which `adminApi.call()`
 * swallows into `{}`, silently corrupting the second endpoint's result.
 * Routing per path, and constructing a fresh `Response` per call, avoids
 * that entirely instead of relying on tests happening to make only one call.
 */
function routedFetch(routes: Readonly<Record<string, { readonly status?: number; readonly body: unknown }>>) {
  return vi.fn((input: RequestInfo | URL) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
    const path = url.split("?")[0];
    const route = routes[path];
    if (!route) return Promise.resolve(jsonResponse(404, { error: `NOT_MOCKED:${path}` }));
    return Promise.resolve(jsonResponse(route.status ?? 200, route.body));
  });
}

const NO_DEVICES = { "/devices": { body: { devices: [] } } };

const EMPTY_PROJECT_PLAN = {
  "/project-plan": {
    body: {
      schema_version: "1.0",
      generated_at: "2026-09-14T00:00:00Z",
      current_build: null,
      current_track: null,
      progress_contract: null,
      overall_progress_percent: 0,
      current_track_progress_percent: 0,
      tracks: [],
      now_next: {},
      roadmap_notes: [],
      backlog: [],
      backlog_counts: {},
      completed_features: [],
      build_history: [],
      archived_build_count: 0,
      metadata_warnings: [],
    },
  },
};

const UNCONFIGURED_PROJECT_PLAN = {
  "/project-plan": {
    body: {
      ...EMPTY_PROJECT_PLAN["/project-plan"].body,
      metadata_warnings: ["No project-plan source is configured."],
    },
  },
};

const TABS = [
  { label: "Device management", marker: "Device registry · 0 entries" },
  { label: "Inventory exclusions", marker: "No device excluded" },
  { label: "Credentials", marker: "No credential stored" },
  { label: "Project plan", marker: "Declared roadmap completion" },
  { label: "Audit log", marker: "No audit events" },
];

describe("AdministrationScreen tabs", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders each tab's own panel, and no other tab's panel, tab by tab", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN, "/credentials": { body: { credentials: [] } }, "/audit-log": { body: { events: [] } } }),
    );
    render(withTheme(<AdministrationScreen />));
    const tablist = screen.getByRole("tablist", { name: "Administration sections" });

    for (const tab of TABS) {
      fireEvent.click(within(tablist).getByRole("tab", { name: tab.label }));
      await waitFor(() => expect(screen.getByText(tab.marker)).toBeInTheDocument());
      for (const other of TABS) {
        if (other.label === tab.label) continue;
        expect(screen.queryByText(other.marker)).toBeNull();
      }
    }
  });

  it("Project plan renders the real roadmap, not the earlier empty placeholder", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN }));
    render(withTheme(<AdministrationScreen />));
    fireEvent.click(screen.getByRole("tab", { name: "Project plan" }));
    await waitFor(() => expect(screen.getByText("Declared roadmap completion")).toBeInTheDocument());
    expect(screen.queryByText("No project plan")).toBeNull();
  });

  it("Project plan states when no source is configured", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...UNCONFIGURED_PROJECT_PLAN }));
    render(withTheme(<AdministrationScreen />));
    fireEvent.click(screen.getByRole("tab", { name: "Project plan" }));
    await waitFor(() => expect(screen.getByText("No project-plan source is configured.")).toBeInTheDocument());
    expect(screen.queryByText("Declared roadmap completion")).toBeNull();
  });

  it("defaults to the Device management tab, empty", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN }));
    render(withTheme(<AdministrationScreen />));
    await waitFor(() => expect(screen.getByText("Device registry · 0 entries")).toBeInTheDocument());
    expect(screen.queryByText("Declared roadmap completion")).toBeNull();
  });

  it("Device management renders the real device registry once GET /devices resolves", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({
        "/devices": {
          body: {
            devices: [
              {
                device_id: "dev-1",
                vendor_hint: "check_point",
                enrollment_state: "ENROLLED",
                hostname: "fw-edge-1",
                model: "Quantum",
                software_version: "R81.20",
                ha_role: "active",
                cluster_member_ref: null,
              },
            ],
          },
        },
      }),
    );
    render(withTheme(<AdministrationScreen />));
    await waitFor(() => expect(screen.getByText("Device registry · 1 entry")).toBeInTheDocument());
    expect(screen.getByText("fw-edge-1")).toBeInTheDocument();
    // "Enrolled" appears both as the device's own status chip and as the
    // Enrollment summary card's row label -- assert both are present rather
    // than picking one with an ambiguous getByText.
    expect(screen.getAllByText("Enrolled").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("1 device")).toBeInTheDocument();
  });
});

describe("AdministrationScreen Local identities tab", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows the empty state and a create action when no identity exists", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, "/local-identities": { body: { identities: [] } } }));
    render(withTheme(<AdministrationScreen />));

    fireEvent.click(screen.getByRole("tab", { name: "Local identities" }));

    await waitFor(() => expect(screen.getByText("No local identity yet")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Create identity" })).toBeInTheDocument();
  });

  it("lists an identity's 13G section 3 fields and never a password, verifier or salt", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({
        ...NO_DEVICES,
        "/local-identities": {
          body: {
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
          },
        },
      }),
    );
    render(withTheme(<AdministrationScreen />));

    fireEvent.click(screen.getByRole("tab", { name: "Local identities" }));

    await waitFor(() => expect(screen.getByText("alice")).toBeInTheDocument());
    expect(screen.getByText("Enabled")).toBeInTheDocument();
    expect(screen.getByText(/must change password at next sign-in/)).toBeInTheDocument();
    expect(document.body.textContent?.toLowerCase()).not.toMatch(/verifier|salt/);
  });

  it("shows the server's own refusal verbatim rather than inventing a different message", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, "/local-identities": { status: 403, body: { error: "ACTION_REFUSED" } } }));
    render(withTheme(<AdministrationScreen />));

    fireEvent.click(screen.getByRole("tab", { name: "Local identities" }));

    await waitFor(() => expect(screen.getByText("ACTION_REFUSED")).toBeInTheDocument());
  });
});
