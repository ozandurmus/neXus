import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { AdministrationScreen, resolveAdminRoute } from "../src/screens/AdministrationScreen";
import { LEGACY_ADMIN_TABS } from "../src/shell/legacyRoutes";
import { URL_CHANGE_EVENT, writeUrlParams } from "../src/shell/urlParams";
import { SessionContext } from "../src/auth/SessionContext";
import { CredentialsPanel } from "../src/screens/CredentialsPanel";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

// AdministrationScreen now writes its selected tab into the URL (`history.replaceState`) so the selection is a
// real deep link (review §3). jsdom's `window.location` is shared across every test in this file, so without a
// reset the next test's initial render would read the previous test's leftover `?tab=...` and start on the
// wrong panel.
afterEach(() => {
  window.history.replaceState(null, "", "/");
});

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

// Administration is seven pages (PO amendment 2026-11-13 added Scheduled jobs and Platform status), each at its own address and chosen in the left navigation (navigation redesign 2026-11-12);
// a page opens its first tab, the others open on a click. Each entry: the page's address, the tab to open (null = the one
// the page opens, or a page without tabs), and that panel's marker.
const PAGE_SECTIONS = [
  { tab: "registry", section: null, marker: "Device registry · 0 entries" },
  { tab: "registry", section: "Inventory exclusions", marker: "No device excluded" },
  { tab: "registry", section: "Credentials", marker: "No credential stored" },
  { tab: "product", section: null, marker: "Declared roadmap completion" },
  { tab: "scheduled-jobs", section: null, marker: "Discovery refresh" },
  { tab: "platform-status", section: null, marker: "System status could not be read (status 503)" },
];

/** Scheduled jobs as a viewer reads it: one family, masked, no switch. Synthetic values only. */
const SCHEDULED_JOBS = {
  "/api/v2/config/scheduled-jobs": { body: { master_enabled: true, master_env: "UI2_SCHEDULING_ENABLED", can_write: false, masked: true, business_hours: null,
    families: [{ family: "discovery_refresh", label: "Discovery refresh", description: "Synthetic description.", cadence: "Every 6 hours",
      enabled: true, state: "ON", will_run: true, lock_reason: null, note: null, window_open_now: false, next_window_at: "2026-11-11T00:00:00+03:00",
      last_run_at: null, changed_by: "USR-0a1b2c3d4e", changed_at: "2026-11-10T18:05:00Z", reason: null }] } },
};
/** neXus' own status with no server behind it: the panel states the failed read. */
const NO_SYSTEM_STATUS = {
  "/api/v2/system/pods": { status: 503, body: { code: "SYSTEM_STATUS_READ_FAILED", message: "The system status read failed." } },
  "/api/v2/system/storage": { status: 503, body: { code: "SYSTEM_STATUS_READ_FAILED", message: "The system status read failed." } },
};

/** Renders the screen as the shell does after a click in the navigation: the page is named by the address. */
function renderAt(query: string, node: React.ReactElement = <AdministrationScreen />) {
  window.history.replaceState(null, "", `/?screen=administration${query ? `&${query}` : ""}`);
  return render(withTheme(node));
}

function credential(kind: "ssh_password" | "ssh_private_key" | "api_password") {
  return {
    credential_id: "cred-1",
    credential_reference_id: "ref-1",
    display_name: "Synthetic credential",
    kind,
    username: "svc-account",
    allows_check_point: true,
    allows_palo_alto: false,
    created_at: "2026-09-21T00:00:00Z",
    secret_set_at: "2026-09-21T00:00:00Z",
  };
}

describe("CredentialsPanel replace secret", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each(["ssh_password", "api_password"] as const)("hides passphrase for %s", async (kind) => {
    vi.stubGlobal("fetch", routedFetch({ "/credentials": { body: { credentials: [credential(kind)] } } }));
    render(withTheme(<CredentialsPanel />));

    await waitFor(() => expect(screen.getByText("Synthetic credential")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Replace secret" }));

    expect(screen.queryByLabelText("Passphrase (optional)")).toBeNull();
  });

  it("shows and sends a passphrase for ssh_private_key", async () => {
    const requests: unknown[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") requests.push(JSON.parse(init.body as string));
      return jsonResponse(200, init?.method === "POST"
        ? credential("ssh_private_key")
        : { credentials: [credential("ssh_private_key")] });
    }));
    render(withTheme(<CredentialsPanel />));

    await waitFor(() => expect(screen.getByText("Synthetic credential")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Replace secret" }));
    fireEvent.change(screen.getByLabelText("New secret"), { target: { value: "replacement-key" } });
    fireEvent.change(screen.getByLabelText("Passphrase (optional)"), { target: { value: "key-passphrase" } });
    fireEvent.click(screen.getByRole("button", { name: "Replace secret" }));

    await waitFor(() => expect(requests).toEqual([{
      credential_id: "cred-1",
      secret: "replacement-key",
      passphrase: "key-passphrase",
    }]));
  });
});

describe("AdministrationScreen tabs", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders each page's own tabs, and no other page's, page by page", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN, ...SCHEDULED_JOBS, ...NO_SYSTEM_STATUS, "/credentials": { body: { credentials: [] } } }),
    );
    for (const entry of PAGE_SECTIONS) {
      const { unmount } = renderAt(`tab=${entry.tab}`);
      if (entry.section) fireEvent.click(screen.getByRole("tab", { name: entry.section }));
      await waitFor(() => expect(screen.getByText(entry.marker)).toBeInTheDocument());
      // Only the open tab is mounted.
      for (const other of PAGE_SECTIONS) if (other !== entry) expect(screen.queryByText(other.marker)).toBeNull();
      unmount();
    }
  });

  it.each([
    ["tab=registry", "Device registry", "Device registry sections", ["Device management", "Inventory exclusions", "Credentials", "SSH trust"]],
    ["tab=access", "Access and identity", "Access and identity sections", ["Local identities", "Sessions", "Roles and permissions", "LDAP settings"]],
    ["tab=platform", "Notifications and logging", "Notifications and logging sections", ["Notifications", "Log collection"]],
  ])("%s is one screen: its own heading and exactly one tab strip, with no second side menu", (query, heading, tabList, tabs) => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN }));
    renderAt(query);
    expect(screen.getByRole("heading", { level: 2, name: heading })).toBeInTheDocument();
    expect(screen.getByTestId("screen-header-overline")).toHaveTextContent("Administration");
    expect(screen.getAllByRole("tablist")).toHaveLength(1);
    expect(within(screen.getByRole("tablist", { name: tabList })).getAllByRole("tab").map(t => t.textContent)).toEqual(tabs);
    // The pages are entries of the left navigation now, not tabs of this screen; the Product heading and Job Logs are gone.
    expect(screen.queryByRole("navigation", { name: "Administration navigation" })).toBeNull();
    for (const page of ["Device registry", "Access and identity", "Scheduled jobs", "Notifications and logging", "Platform", "Platform status", "System", "Audit log", "Delivery plan", "Product"]) {
      expect(screen.queryByRole("tab", { name: page })).toBeNull();
    }
    expect(screen.queryByRole("tab", { name: /Job Logs/i })).toBeNull();
    expect(screen.getAllByRole("tabpanel")).toHaveLength(1);
  });

  it.each([["tab=audit", "Audit log"], ["tab=product", "Delivery plan"], ["tab=scheduled-jobs", "Scheduled jobs"], ["tab=platform-status", "Platform status"]])("%s has a single section and therefore no tab strip", (query, heading) => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN, ...SCHEDULED_JOBS, ...NO_SYSTEM_STATUS, "/audit-events": { body: { events: [] } } }));
    renderAt(query);
    expect(screen.getByRole("heading", { level: 2, name: heading })).toBeInTheDocument();
    expect(screen.queryByRole("tablist")).toBeNull();
    expect(screen.getByRole("region", { name: heading })).toBeInTheDocument();
  });

  it("opens the tab the address names, and writes the tab back to the address on a switch", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN, "/credentials": { body: { credentials: [] } } }));
    renderAt("tab=registry&section=inventory-exclusions");
    expect(screen.getByRole("tab", { name: "Inventory exclusions" })).toHaveAttribute("aria-selected", "true");
    await waitFor(() => expect(screen.getByText("No device excluded")).toBeInTheDocument());
    const changes: string[] = [];
    const onChange = () => changes.push(window.location.search);
    window.addEventListener(URL_CHANGE_EVENT, onChange);
    try {
      fireEvent.click(screen.getByRole("tab", { name: "Credentials" }));
    } finally {
      window.removeEventListener(URL_CHANGE_EVENT, onChange);
    }
    // replace, not push: the address names the open tab, and the navigation is told
    expect(window.location.search).toBe("?screen=administration&tab=registry&section=credentials");
    expect(changes).toEqual(["?screen=administration&tab=registry&section=credentials"]);
    expect(screen.getByRole("tab", { name: "Credentials" })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByText("No device excluded")).toBeNull();
  });

  it("an unknown section falls back to the page's first tab", () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES }));
    renderAt("tab=registry&section=nonsense");
    expect(screen.getByRole("tab", { name: "Device management" })).toHaveAttribute("aria-selected", "true");
  });

  it("shows the header buttons on the Device management tab of Device registry only", () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN, "/credentials": { body: { credentials: [] } } }));
    const buttons = () => ["Import from manager", "Add device"].map(name => screen.queryByRole("button", { name }) !== null);
    const registry = renderAt("");
    expect(buttons()).toEqual([true, true]);
    // They leave with the tab and come back with it.
    for (const other of ["Inventory exclusions", "Credentials", "SSH trust"]) {
      fireEvent.click(screen.getByRole("tab", { name: other }));
      expect(buttons()).toEqual([false, false]);
    }
    fireEvent.click(screen.getByRole("tab", { name: "Device management" }));
    expect(buttons()).toEqual([true, true]);
    registry.unmount();

    for (const query of ["tab=registry&section=credentials", "tab=ssh-trust", "tab=access", "tab=platform", "tab=audit", "tab=product"]) {
      const { unmount } = renderAt(query);
      expect(buttons()).toEqual([false, false]);
      unmount();
    }
  });

  it("follows the address: Back, Forward and an in-app link move the page and the tab without a reload", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN, "/credentials": { body: { credentials: [] } } }));
    const heading = () => screen.getByRole("heading", { level: 2 }).textContent;
    const selected = () => screen.queryAllByRole("tab").find(t => t.getAttribute("aria-selected") === "true")?.textContent ?? null;
    const go = (search: string) => act(() => {
      window.history.replaceState(null, "", `/${search}`);
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    renderAt("tab=registry");
    expect([heading(), selected()]).toEqual(["Device registry", "Device management"]);

    // the browser's Back or Forward to another tab of the page ...
    go("?screen=administration&tab=registry&section=credentials");
    expect([heading(), selected()]).toEqual(["Device registry", "Credentials"]);
    await waitFor(() => expect(screen.getByText("No credential stored")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "Add device" })).toBeNull();

    // ... to another page, with and without a tab strip, and to an address from before the regrouping
    go("?screen=administration&tab=platform&section=system-logs");
    expect([heading(), selected()]).toEqual(["Notifications and logging", "Log collection"]);
    expect(screen.queryByText("No credential stored")).toBeNull();
    go("?screen=administration&tab=product");
    expect([heading(), selected()]).toEqual(["Delivery plan", null]);
    await waitFor(() => expect(screen.getByText("Declared roadmap completion")).toBeInTheDocument());
    go("?screen=administration&tab=sessions");
    expect([heading(), selected()]).toEqual(["Access and identity", "Sessions"]);

    // an in-app link that writes the address (the notice the navigation also listens to)
    act(() => writeUrlParams({ screen: "administration", tab: "registry", section: "ssh-trust" }, "push"));
    expect([heading(), selected()]).toEqual(["Device registry", "SSH trust"]);

    // a click still wins over the address it came from, and the address then names it
    fireEvent.click(screen.getByRole("tab", { name: "Inventory exclusions" }));
    expect(selected()).toBe("Inventory exclusions");
    expect(window.location.search).toBe("?screen=administration&tab=registry&section=inventory-exclusions");
    go("?screen=administration");
    expect([heading(), selected()]).toEqual(["Device registry", "Device management"]);
    expect(screen.getByRole("button", { name: "Add device" })).toBeInTheDocument();
  });

  it.each(Object.entries(LEGACY_ADMIN_TABS))("keeps the former address tab=%s: it lands on its page with that tab open", (slug, home) => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN }));
    renderAt(`tab=${slug}`);
    const resolved = resolveAdminRoute(slug, null);
    expect(resolved.page.slug).toBe(home.group);
    expect(screen.getByRole("heading", { level: 2, name: resolved.page.label })).toBeInTheDocument();
    const label = resolved.page.sections.find(s => s.slug === home.section)!.label;
    expect(screen.getByRole("region", { name: label })).toBeInTheDocument();
    if (resolved.page.sections.length > 1) expect(screen.getByRole("tab", { name: label })).toHaveAttribute("aria-selected", "true");
  });

  it("keeps every former Administration address: an old tab slug lands on its group with that section open", () => {
    expect(resolveAdminRoute("ssh-trust", null)).toMatchObject({ page: { slug: "registry" }, section: "ssh-trust" });
    expect(resolveAdminRoute("sessions", null)).toMatchObject({ page: { slug: "access" }, section: "sessions" });
    expect(resolveAdminRoute("system-logs", null)).toMatchObject({ page: { slug: "platform" }, section: "system-logs" });
    expect(resolveAdminRoute("delivery-plan", null)).toMatchObject({ page: { slug: "product" } });
    expect(resolveAdminRoute("audit", "audit-logs")).toMatchObject({ page: { slug: "audit" }, section: "audit-logs" });
    expect(resolveAdminRoute(null, null).page.slug).toBe("registry");
  });

  it("Delivery plan renders the real roadmap, not the earlier empty placeholder", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN }));
    renderAt("tab=product");
    await waitFor(() => expect(screen.getByText("Declared roadmap completion")).toBeInTheDocument());
    expect(screen.queryByText("No project plan")).toBeNull();
  });

  it("Delivery plan states when no source is configured", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...UNCONFIGURED_PROJECT_PLAN }));
    renderAt("tab=product");
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

  it("reads 'UNKNOWN hostname' with a reason chip instead of a device id when hostname evidence is missing", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({
        "/devices": {
          body: {
            devices: [
              {
                device_id: "opaque-device-id",
                vendor_hint: "check_point",
                enrollment_state: "DRAFT",
                hostname: null,
                model: null,
                software_version: null,
                ha_role: null,
                cluster_member_ref: null,
              },
            ],
          },
        },
      }),
    );
    render(withTheme(<AdministrationScreen />));

    await waitFor(() => expect(screen.getByText("UNKNOWN hostname")).toBeInTheDocument());
    expect(screen.getByText("draft")).toBeInTheDocument();
    expect(screen.queryByText("opaque-device-id")).toBeNull();
  });

  it("moves Delete into the row's overflow menu, behind a confirmation dialog naming the device", async () => {
    const deleteBodies: unknown[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      const path = url.split("?")[0];
      if (path === "/session/status") return jsonResponse(200, {});
      if (path === "/devices" && (!init?.method || init.method === "GET")) {
        return jsonResponse(200, {
          devices: [{
            device_id: "dev-1",
            vendor_hint: "check_point",
            enrollment_state: "ENROLLED",
            hostname: "FW-TANGO-04",
            model: "Quantum",
            software_version: "R81.20",
            ha_role: "active",
            cluster_member_ref: null,
          }],
        });
      }
      if (path === "/devices/dev-1/delete") {
        deleteBodies.push(init?.body ? JSON.parse(init.body as string) : undefined);
        return deleteBodies.length === 1
          ? jsonResponse(409, { error: "BACKUP_DISPOSITION_REQUIRED", backup_artefact_count: 3 })
          : jsonResponse(200, { deleted: true, device_id: "dev-1", backup_artefact_count: 3 });
      }
      return jsonResponse(404, { error: `NOT_MOCKED:${path}` });
    }));

    render(withTheme(<AdministrationScreen />));
    await waitFor(() => expect(screen.getByText("FW-TANGO-04")).toBeInTheDocument());

    // No filled Delete button on the row any more -- it is behind the overflow menu.
    expect(screen.queryByRole("button", { name: "Delete" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "FW-TANGO-04 actions" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Delete" }));

    // The confirmation dialog names the device before anything is sent.
    const dialog = await waitFor(() => screen.getByRole("dialog"));
    expect(within(dialog).getByRole("heading", { name: "Delete this device?" })).toBeInTheDocument();
    expect(within(dialog).getByText(/FW-TANGO-04/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(screen.getByText(/This device has 3 backup artefacts/)).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Keep artefacts" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove artefacts" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Keep artefacts" }));
    await waitFor(() => expect(deleteBodies).toEqual([undefined, { backup_disposition: "KEEP" }]));
  });

  it("bulk-deletes a checkbox selection behind a confirmation dialog naming the devices", async () => {
    const deleteBodies: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      const path = url.split("?")[0];
      if (path === "/session/status") return jsonResponse(200, {});
      if (path === "/devices" && (!init?.method || init.method === "GET")) {
        return jsonResponse(200, {
          devices: [
            { device_id: "dev-1", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-TANGO-04", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
            { device_id: "dev-2", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-JULIET-06", model: null, software_version: null, ha_role: null, cluster_member_ref: null },
          ],
        });
      }
      if (path === "/devices/dev-1/delete" || path === "/devices/dev-2/delete") {
        deleteBodies.push(path);
        return jsonResponse(200, { deleted: true });
      }
      return jsonResponse(404, { error: `NOT_MOCKED:${path}` });
    }));

    render(withTheme(<AdministrationScreen />));
    await waitFor(() => expect(screen.getByText("FW-TANGO-04")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("checkbox", { name: "Select FW-TANGO-04" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Select FW-JULIET-06" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete selected (2)" }));

    await waitFor(() => expect(screen.getByRole("heading", { name: "Delete 2 devices?" })).toBeInTheDocument());
    expect(screen.getByText(/FW-TANGO-04, FW-JULIET-06/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(deleteBodies.sort()).toEqual(["/devices/dev-1/delete", "/devices/dev-2/delete"]));
  });
});

describe("AdministrationScreen Local identities tab", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows the empty state and a create action when no identity exists", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, "/local-identities": { body: { identities: [] } } }));
    renderAt("tab=access");

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
    renderAt("tab=access");

    await waitFor(() => expect(screen.getByText("alice")).toBeInTheDocument());
    expect(screen.getByText("Enabled")).toBeInTheDocument();
    expect(screen.getByText(/must change password at next sign-in/)).toBeInTheDocument();
    expect(document.body.textContent?.toLowerCase()).not.toMatch(/verifier|salt/);
  });

  it("renders a 403/ACTION_REFUSED as the shared Restricted state, not the raw code, and with no Retry", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, "/local-identities": { status: 403, body: { error: "ACTION_REFUSED" } } }));
    renderAt("tab=access");

    await waitFor(() => expect(screen.getByText(/This account can not view or change it/)).toBeInTheDocument());
    expect(screen.getByText(/This needs the Security Admin role/)).toBeInTheDocument();
    expect(screen.queryByText("ACTION_REFUSED")).toBeNull();
    expect(screen.queryByText("Loading…")).toBeNull();
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  });
});

describe("AdministrationScreen Sessions tab", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows active and per-identity counts, configured lifetimes, and refreshes after revoke", async () => {
    let listCalls = 0;
    const revokeBodies: unknown[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      const path = url.split("?")[0];
      if (path === "/devices") return jsonResponse(200, { devices: [] });
      if (path === "/session/status") return jsonResponse(200, { csrf_token: "csrf-fixture" });
      if (path === "/sessions" && init?.method === "GET") {
        listCalls += 1;
        return jsonResponse(200, {
          sessions: [
            {
              session_id: "session-1",
              actor_fingerprint: "actor-a",
              created_at: "2026-09-21T08:00:00Z",
              last_seen_at: "2026-09-21T08:05:00Z",
              idle_deadline_at: "2026-09-21T08:35:00Z",
              absolute_expires_at: "2026-09-21T18:00:00Z",
              state: "ACTIVE",
              end_reason: null,
              ended_by_actor_fingerprint: null,
              superseded_by_session_id: null,
            },
            {
              session_id: "session-2",
              actor_fingerprint: "actor-a",
              created_at: "2026-09-21T08:10:00Z",
              last_seen_at: "2026-09-21T08:15:00Z",
              idle_deadline_at: "2026-09-21T08:45:00Z",
              absolute_expires_at: "2026-09-21T18:10:00Z",
              state: "ACTIVE",
              end_reason: null,
              ended_by_actor_fingerprint: null,
              superseded_by_session_id: null,
            },
          ],
          identity_labels: { "session-1": "alice", "session-2": "alice" },
          idle_timeout_seconds: 1800,
          absolute_lifetime_seconds: 36000,
        });
      }
      if (path === "/sessions/revoke" && init?.method === "POST") {
        revokeBodies.push(JSON.parse(init.body as string));
        return jsonResponse(200, { ok: true });
      }
      return jsonResponse(404, { error: `NOT_MOCKED:${path}` });
    }));

    renderAt("tab=access");
    fireEvent.click(screen.getByRole("tab", { name: "Sessions" }));

    await waitFor(() => expect(screen.getByText("2 active sessions")).toBeInTheDocument());
    expect(screen.getAllByText("alice · 2 active")).toHaveLength(2);
    expect(screen.getByText("Idle timeout: 30 minutes")).toBeInTheDocument();
    expect(screen.getByText("Absolute lifetime: 10 hours")).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("button", { name: /^Actions for row / })[0]);
    fireEvent.click(screen.getByRole("menuitem", { name: "Revoke" }));
    await waitFor(() => expect(revokeBodies).toEqual([{ sessionId: "session-1" }]));
    await waitFor(() => expect(listCalls).toBe(2));
  });
});


describe("restricted Administration reads", () => {
  afterEach(() => vi.unstubAllGlobals());
  it.each(["credentials", "local-identities", "sessions", "roles-permissions", "ldap-settings", "notifications", "system-logs", "audit-logs"])(
    "does not mount the restricted %s panel for aiview", async slug => {
      window.history.replaceState(null, "", `/?screen=administration&tab=${slug}`);
      const fetch = vi.fn();
      vi.stubGlobal("fetch", fetch);
      render(withTheme(<SessionContext.Provider value={{ displayName: "Synthetic viewer", roleTokens: [],
        permissions: [], canReadAdministration: false, onSignOut: () => {} }}>
        <AdministrationScreen />
      </SessionContext.Provider>));
      // Locked, not hidden (C4): the section keeps its place and opens to the shared Restricted state.
      expect(await screen.findByText(/This account can not view or change it/)).toBeInTheDocument();
      expect(document.querySelector("section[data-locked='true']")).not.toBeNull();
      // On a page with tabs the locked tab keeps its place and carries the lock; Audit log has no tab strip.
      if (slug !== "audit-logs") expect(document.querySelector("[role='tab'][aria-selected='true'] [data-lock-badge]")).not.toBeNull();
      await waitFor(() => expect(fetch).not.toHaveBeenCalled());
    });
  // PO 2026-11-13 amendment: the two pages that came back from Operations keep the access they had there.
  it("opens Scheduled jobs for a session without the administration-read grant: readable, masked, no switch, no lock", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...SCHEDULED_JOBS }));
    window.history.replaceState(null, "", "/?screen=administration&tab=scheduled-jobs");
    render(withTheme(<SessionContext.Provider value={{ displayName: "Synthetic viewer", roleTokens: [],
      permissions: ["Devices"], canReadAdministration: false, onSignOut: () => {} }}>
      <AdministrationScreen />
    </SessionContext.Provider>));
    const page = within(screen.getByRole("region", { name: "Scheduled jobs" }));
    expect(await page.findByRole("heading", { name: "Discovery refresh" })).toBeInTheDocument();
    expect(page.getByText("Reason withheld (masked view)")).toBeInTheDocument();
    expect(page.queryByRole("switch")).toBeNull();
    expect(document.querySelector("section[data-locked='true']")).toBeNull();
  });

  it("opens Platform status for a session without the administration-read grant and states the server's answer", async () => {
    const fetch = routedFetch({ ...NO_SYSTEM_STATUS });
    vi.stubGlobal("fetch", fetch);
    window.history.replaceState(null, "", "/?screen=administration&tab=platform-status");
    render(withTheme(<SessionContext.Provider value={{ displayName: "Synthetic viewer", roleTokens: [],
      permissions: ["Devices"], canReadAdministration: false, onSignOut: () => {} }}>
      <AdministrationScreen />
    </SessionContext.Provider>));
    expect(screen.getByRole("heading", { level: 2, name: "Platform status" })).toBeInTheDocument();
    expect(await within(screen.getByRole("region", { name: "Platform status" })).findByRole("alert")).toBeInTheDocument();
    expect(document.querySelector("section[data-locked='true']")).toBeNull();
    expect(fetch).toHaveBeenCalled();
  });

  it("mounts Credentials when the server permits its read", async () => {
    const fetch = routedFetch({ "/credentials": { body: { credentials: [] } } });
    vi.stubGlobal("fetch", fetch);
    window.history.replaceState(null, "", "/?screen=administration&tab=credentials");
    render(withTheme(<SessionContext.Provider value={{ displayName: "Synthetic administrator", roleTokens: [],
      permissions: [], canReadAdministration: true, onSignOut: () => {} }}>
      <AdministrationScreen />
    </SessionContext.Provider>));
    expect(await screen.findByText("No credential stored")).toBeInTheDocument();
    expect(fetch).toHaveBeenCalled();
  });
});
