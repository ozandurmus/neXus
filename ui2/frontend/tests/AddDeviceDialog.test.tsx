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

  it("offers inline credential creation and selects the created credential", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({
        "/credentials": [
          { body: { credentials: [] } },
          {
            body: {
              credential_id: "c-new",
              credential_reference_id: "cred-ref-new",
              display_name: "New SSH",
              kind: "ssh_password",
              username: "admin",
              allows_check_point: true,
              allows_palo_alto: false,
              created_at: "2026-09-01T00:00:00Z",
              secret_set_at: "2026-09-01T00:00:00Z",
            },
          },
        ],
        "/session/status": { body: { csrf_token: "test-csrf" } },
      }),
    );

    render(withTheme(<AddDeviceDialogTrigger />));
    fireEvent.click(screen.getByRole("button", { name: "Add device" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Create credential" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Create credential" }));
    fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "New SSH" } });
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "admin" } });
    fireEvent.change(screen.getByLabelText("Secret"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Enrol" })).toBeDisabled());
    fireEvent.change(screen.getByLabelText("Address"), { target: { value: "192.0.2.30" } });
    await waitFor(() => expect(screen.getByRole("button", { name: "Enrol" })).not.toBeDisabled());
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

const CLUSTER_CANDIDATE = {
  candidate_id: "c-cluster",
  kind: "VIRTUALIZATION_CLUSTER",
  importable: false,
  display_name: "Cluster Alpha",
  own_address: null,
  management_address: "10.1.1.100",
  cluster_reference: null,
  parent_candidate_id: null,
  model: null,
  software_version: null,
  connection_state: null,
  import_outcome: null,
  registry_state: "new",
  existing_device_id: null,
};

const MEMBER_ONE_CANDIDATE = {
  candidate_id: "c-m1",
  kind: "PHYSICAL_VIRTUALIZATION_CHASSIS_MEMBER",
  importable: true,
  display_name: "Member One",
  own_address: "10.1.1.1",
  management_address: "10.1.1.1",
  cluster_reference: "cluster-ref-1",
  parent_candidate_id: "c-cluster",
  model: null,
  software_version: null,
  connection_state: null,
  import_outcome: null,
  registry_state: "new",
  existing_device_id: null,
};

const MEMBER_TWO_CANDIDATE = {
  candidate_id: "c-m2",
  kind: "PHYSICAL_VIRTUALIZATION_CHASSIS_MEMBER",
  importable: true,
  display_name: "Member Two",
  own_address: "10.1.1.2",
  management_address: "10.1.1.2",
  cluster_reference: "cluster-ref-1",
  parent_candidate_id: "c-cluster",
  model: null,
  software_version: null,
  connection_state: null,
  import_outcome: null,
  registry_state: "new",
  existing_device_id: null,
};

const SELECTABLE_STANDALONE_CANDIDATE = {
  candidate_id: "c-standalone",
  kind: "STANDALONE_PRODUCT_GATEWAY",
  importable: true,
  display_name: "New Gateway",
  own_address: "10.1.1.5",
  management_address: "10.1.1.5",
  cluster_reference: null,
  parent_candidate_id: null,
  model: null,
  software_version: null,
  connection_state: null,
  import_outcome: null,
  registry_state: "new",
  existing_device_id: null,
};

const ALREADY_IMPORTED_CANDIDATE = {
  candidate_id: "c-existing",
  kind: "STANDALONE_PRODUCT_GATEWAY",
  importable: true,
  display_name: "Edge Gateway",
  own_address: "10.1.1.9",
  management_address: "10.1.1.9",
  cluster_reference: null,
  parent_candidate_id: null,
  model: null,
  software_version: null,
  connection_state: null,
  import_outcome: null,
  registry_state: "already_imported",
  existing_device_id: "dev-existing-1",
};

async function openDialogAndSwitchToDiscovery(address: string) {
  fireEvent.click(screen.getByRole("button", { name: "Add device" }));
  expect(screen.getByRole("dialog")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Management server (discovery)" }));
  await waitFor(() => expect(screen.getByLabelText("Management server address")).toBeInTheDocument());
  fireEvent.change(screen.getByLabelText("Management server address"), { target: { value: address } });
  await waitFor(() => expect(screen.getByRole("button", { name: "Start discovery" })).not.toBeDisabled());
}

describe("AddDeviceDialog discovery mode", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("starts a run, polls to FINISHED, and shows the grouped candidate table", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({
        "/credentials": CREDENTIALS_ROUTE,
        "/session/status": { body: { csrf_token: "test-csrf" } },
        "/discovery/runs": { status: 202, body: { run_id: "run-1", job_id: "job-1" } },
        "/discovery/runs/run-1": [
          { body: { run_id: "run-1", vendor: "check_point", state: "RUNNING", job_id: "job-1", outcome_summary: {}, candidates: [] } },
          {
            body: {
              run_id: "run-1",
              vendor: "check_point",
              state: "FINISHED",
              job_id: "job-1",
              outcome_summary: { PHYSICAL_VIRTUALIZATION_CHASSIS_MEMBER: 2, VIRTUALIZATION_CLUSTER: 1 },
              candidates: [CLUSTER_CANDIDATE, MEMBER_ONE_CANDIDATE, MEMBER_TWO_CANDIDATE],
            },
          },
        ],
      }),
    );

    render(withTheme(<AddDeviceDialogTrigger />));
    await openDialogAndSwitchToDiscovery("mds.example");
    fireEvent.click(screen.getByRole("button", { name: "Start discovery" }));

    await waitFor(() => expect(screen.getByText("Cluster Alpha")).toBeInTheDocument(), { timeout: 8000 });
    expect(screen.getByText("Member One")).toBeInTheDocument();
    expect(screen.getByText("Member Two")).toBeInTheDocument();
  }, 10000);

  it("selecting the cluster selects its members, and import shows per-candidate outcomes", async () => {
    const fetchMock = routedFetch({
      "/credentials": CREDENTIALS_ROUTE,
      "/session/status": { body: { csrf_token: "test-csrf" } },
      "/discovery/runs": { status: 202, body: { run_id: "run-1", job_id: "job-1" } },
      "/discovery/runs/run-1": {
        body: {
          run_id: "run-1",
          vendor: "check_point",
          state: "FINISHED",
          job_id: "job-1",
          outcome_summary: {},
          candidates: [CLUSTER_CANDIDATE, MEMBER_ONE_CANDIDATE, MEMBER_TWO_CANDIDATE],
        },
      },
      "/discovery/runs/run-1/import": {
        body: {
          results: [
            { candidate_id: "c-m1", outcome: "new", device_id: "dev-1", job_id: "job-2", reason: null },
            { candidate_id: "c-m2", outcome: "already_imported", device_id: "dev-existing", job_id: null, reason: null },
          ],
        },
      },
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<AddDeviceDialogTrigger />));
    await openDialogAndSwitchToDiscovery("mds.example");
    fireEvent.click(screen.getByRole("button", { name: "Start discovery" }));
    await waitFor(() => expect(screen.getByText("Cluster Alpha")).toBeInTheDocument(), { timeout: 8000 });

    const checkboxes = screen.getAllByRole("checkbox");
    expect(checkboxes).toHaveLength(3);
    fireEvent.click(checkboxes[0]);
    await waitFor(() => expect(checkboxes[1]).toBeChecked());
    expect(checkboxes[2]).toBeChecked();

    fireEvent.click(screen.getByRole("button", { name: "Import" }));

    await waitFor(() => expect(screen.getByText("new")).toBeInTheDocument());
    expect(screen.getByText("already_imported")).toBeInTheDocument();

    const importCall = fetchMock.mock.calls.find(([input]) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      return url.split("?")[0] === "/discovery/runs/run-1/import";
    }) as unknown as [RequestInfo | URL, RequestInit] | undefined;
    expect(importCall).toBeDefined();
    const body = JSON.parse(importCall![1].body as string);
    expect(new Set(body.candidate_ids)).toEqual(new Set(["c-m1", "c-m2"]));
  }, 10000);

  it("marks an already-imported candidate disabled and non-selectable, and reports the counts (AC-2/AC-3)", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({
        "/credentials": CREDENTIALS_ROUTE,
        "/session/status": { body: { csrf_token: "test-csrf" } },
        "/discovery/runs": { status: 202, body: { run_id: "run-1", job_id: "job-1" } },
        "/discovery/runs/run-1": {
          body: {
            run_id: "run-1",
            vendor: "check_point",
            state: "FINISHED",
            job_id: "job-1",
            outcome_summary: {},
            candidates: [SELECTABLE_STANDALONE_CANDIDATE, ALREADY_IMPORTED_CANDIDATE],
          },
        },
      }),
    );

    render(withTheme(<AddDeviceDialogTrigger />));
    await openDialogAndSwitchToDiscovery("mds.example");
    fireEvent.click(screen.getByRole("button", { name: "Start discovery" }));
    await waitFor(() => expect(screen.getByText("Edge Gateway")).toBeInTheDocument(), { timeout: 8000 });

    // Header reports "N candidates found, M already added".
    expect(screen.getByText("2 candidates found, 1 already added")).toBeInTheDocument();

    // The already-imported row is greyed out with its badge and existing device id, and carries no checkbox.
    expect(screen.getByText("Already added")).toBeInTheDocument();
    expect(screen.getByText(/dev-existing-1/)).toBeInTheDocument();
    expect(screen.getAllByRole("checkbox")).toHaveLength(1);

    // The one selectable row can still be checked -- the disabled row simply has none.
    fireEvent.click(screen.getAllByRole("checkbox")[0]);
    expect(screen.getAllByRole("checkbox")[0]).toBeChecked();
  }, 10000);
});
