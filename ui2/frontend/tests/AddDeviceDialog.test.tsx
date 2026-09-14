import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { AddDeviceDialogTrigger } from "../src/shell/AddDeviceDialog";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const CREDENTIALS_ROUTE = {
  body: {
    credentials: [
      {
        credential_id: "c-1",
        credential_reference_id: "cred-ref-1",
        display_name: "Primary SSH",
        kind: "ssh_password",
        username: "admin",
        allows_check_point: true,
        allows_palo_alto: false,
        created_at: "2026-09-01T00:00:00Z",
        secret_set_at: "2026-09-01T00:00:00Z",
      },
    ],
  },
};

/** A per-path fetch router; GET /devices/{id} can be given a sequence of responses to cycle through. */
function routedFetch(
  routes: Readonly<Record<string, { readonly status?: number; readonly body: unknown } | ReadonlyArray<{ readonly status?: number; readonly body: unknown }>>>,
) {
  const counters: Record<string, number> = {};
  return vi.fn((input: RequestInfo | URL) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
    const path = url.split("?")[0];
    const route = routes[path];
    if (!route) return Promise.resolve(jsonResponse(404, { error: `NOT_MOCKED:${path}` }));
    if (Array.isArray(route)) {
      const idx = counters[path] ?? 0;
      counters[path] = idx + 1;
      const entry = route[Math.min(idx, route.length - 1)];
      return Promise.resolve(jsonResponse(entry.status ?? 200, entry.body));
    }
    const single = route as { readonly status?: number; readonly body: unknown };
    return Promise.resolve(jsonResponse(single.status ?? 200, single.body));
  });
}

async function openDialogAndFillAddress(address: string) {
  fireEvent.click(screen.getByRole("button", { name: "Add device" }));
  expect(screen.getByRole("dialog")).toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole("button", { name: "Enrol" })).toBeDisabled());
  fireEvent.change(screen.getByLabelText("Address"), { target: { value: address } });
  // Credential auto-selects the one eligible credential once GET /credentials resolves.
  await waitFor(() => expect(screen.getByRole("button", { name: "Enrol" })).not.toBeDisabled());
}

describe("AddDeviceDialog", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("submits, polls, and shows the success result once enrollment_state reaches ENROLLED", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({
        "/credentials": CREDENTIALS_ROUTE,
        "/session/status": { body: { csrf_token: "test-csrf" } },
        "/devices/add-single": { body: { device_id: "dev-1", job_id: "job-1", enrollment_state: "DRAFT" } },
        "/devices/dev-1": [
          {
            body: {
              device_id: "dev-1",
              vendor_hint: "check_point",
              enrollment_state: "DRAFT",
              disabled: false,
              facts: null,
              peer_follow_outcome: null,
              peer_follow_reason: null,
              identity_mismatch_state: "NONE",
              cluster_member_ref: null,
              job: { job_id: "job-1", state: "REQUESTED", outcome: null, terminal_reason: null },
            },
          },
          {
            body: {
              device_id: "dev-1",
              vendor_hint: "check_point",
              enrollment_state: "ENROLLED",
              disabled: false,
              facts: { hostname: "fw-edge-1", model: "Quantum", software_version: "R81.20", ha_role: "active" },
              peer_follow_outcome: "CORROBORATED",
              peer_follow_reason: null,
              identity_mismatch_state: "NONE",
              cluster_member_ref: null,
              job: { job_id: "job-1", state: "COMPLETED", outcome: "SUCCESS", terminal_reason: null },
            },
          },
        ],
      }),
    );

    render(withTheme(<AddDeviceDialogTrigger />));
    await openDialogAndFillAddress("192.0.2.10");

    fireEvent.click(screen.getByRole("button", { name: "Enrol" }));

    await waitFor(() => expect(screen.getByText("Enrolled")).toBeInTheDocument(), { timeout: 5000 });
    expect(screen.getByText(/fw-edge-1/)).toBeInTheDocument();
    expect(screen.getByText(/Quantum/)).toBeInTheDocument();
    expect(screen.getByText("Corroborated with peer")).toBeInTheDocument();
  }, 10000);

  it("shows the 422 reason_code inline and never opens a polling loop", async () => {
    const fetchMock = routedFetch({
      "/credentials": CREDENTIALS_ROUTE,
      "/session/status": { body: { csrf_token: "test-csrf" } },
      "/devices/add-single": { status: 422, body: { error: "VALIDATION_FAILED", reason_code: "ADDRESS_UNRESOLVABLE" } },
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<AddDeviceDialogTrigger />));
    await openDialogAndFillAddress("not-a-real-host");

    fireEvent.click(screen.getByRole("button", { name: "Enrol" }));

    await waitFor(() => expect(screen.getByText(/ADDRESS_UNRESOLVABLE/)).toBeInTheDocument());
    // Only /credentials, /session/status and /devices/add-single were ever called -- no /devices/{id} poll.
    const calledPaths = fetchMock.mock.calls.map(([input]) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      return url.split("?")[0];
    });
    expect(calledPaths.some((p) => p.startsWith("/devices/") && p !== "/devices/add-single")).toBe(false);
  });

  it("shows an identity-mismatch warning and does not show it when none is open", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({
        "/credentials": CREDENTIALS_ROUTE,
        "/session/status": { body: { csrf_token: "test-csrf" } },
        "/devices/add-single": { body: { device_id: "dev-2", job_id: "job-2", enrollment_state: "DRAFT" } },
        "/devices/dev-2": {
          body: {
            device_id: "dev-2",
            vendor_hint: "check_point",
            enrollment_state: "ENROLLED",
            disabled: false,
            facts: { hostname: "fw-2", model: null, software_version: null, ha_role: null },
            peer_follow_outcome: "NONE",
            peer_follow_reason: null,
            identity_mismatch_state: "OPEN",
            cluster_member_ref: null,
            job: { job_id: "job-2", state: "COMPLETED", outcome: "SUCCESS", terminal_reason: null },
          },
        },
      }),
    );

    render(withTheme(<AddDeviceDialogTrigger />));
    await openDialogAndFillAddress("192.0.2.20");
    fireEvent.click(screen.getByRole("button", { name: "Enrol" }));

    await waitFor(() => expect(screen.getByText("Identity mismatch open")).toBeInTheDocument());
  });
});
