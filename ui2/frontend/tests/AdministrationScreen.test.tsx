import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { AdministrationScreen } from "../src/screens/AdministrationScreen";
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

const TABS = [
  { label: "Device management", marker: "Device registry · 0 entries" },
  { label: "Inventory exclusions", marker: "No device excluded" },
  { label: "Credentials", marker: "No credential stored" },
  { label: "Delivery plan", marker: "Declared roadmap completion" },
];

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

  it("renders each tab's own panel, and no other tab's panel, tab by tab", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN, "/credentials": { body: { credentials: [] } } }),
    );
    render(withTheme(<AdministrationScreen />));

    for (const tab of TABS) {
      fireEvent.click(screen.getByRole("tab", { name: tab.label }));
      await waitFor(() => expect(screen.getByText(tab.marker)).toBeInTheDocument());
      for (const other of TABS) {
        if (other.label === tab.label) continue;
        expect(screen.queryByText(other.marker)).toBeNull();
      }
    }
  });

  it("every group's sub-navigation stays reachable, and the header buttons show only for Registry", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN }));
    render(withTheme(<AdministrationScreen />));

    expect(screen.getByRole("tablist", { name: "Registry" })).toBeInTheDocument();
    expect(screen.getByRole("tablist", { name: "Access" })).toBeInTheDocument();
    expect(screen.getByRole("tablist", { name: "Platform" })).toBeInTheDocument();
    expect(screen.getByRole("tablist", { name: "Records" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import from manager" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add device" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "System" }));
    expect(screen.queryByRole("button", { name: "Import from manager" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Add device" })).toBeNull();
  });

  it("Delivery plan renders the real roadmap, not the earlier empty placeholder", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...EMPTY_PROJECT_PLAN }));
    render(withTheme(<AdministrationScreen />));
    fireEvent.click(screen.getByRole("tab", { name: "Delivery plan" }));
    await waitFor(() => expect(screen.getByText("Declared roadmap completion")).toBeInTheDocument());
    expect(screen.queryByText("No project plan")).toBeNull();
  });

  it("Delivery plan states when no source is configured", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, ...UNCONFIGURED_PROJECT_PLAN }));
    render(withTheme(<AdministrationScreen />));
    fireEvent.click(screen.getByRole("tab", { name: "Delivery plan" }));
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

  it("renders a 403/ACTION_REFUSED as the shared Restricted state, not the raw code, and with no Retry", async () => {
    vi.stubGlobal("fetch", routedFetch({ ...NO_DEVICES, "/local-identities": { status: 403, body: { error: "ACTION_REFUSED" } } }));
    render(withTheme(<AdministrationScreen />));

    fireEvent.click(screen.getByRole("tab", { name: "Local identities" }));

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

    render(withTheme(<AdministrationScreen />));
    fireEvent.click(screen.getByRole("tab", { name: "Sessions" }));

    await waitFor(() => expect(screen.getByText("2 active sessions")).toBeInTheDocument());
    expect(screen.getAllByText("alice · 2 active")).toHaveLength(2);
    expect(screen.getByText("Idle timeout: 30 minutes")).toBeInTheDocument();
    expect(screen.getByText("Absolute lifetime: 10 hours")).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("button", { name: "Revoke" })[0]);
    await waitFor(() => expect(revokeBodies).toEqual([{ sessionId: "session-1" }]));
    await waitFor(() => expect(listCalls).toBe(2));
  });
});
