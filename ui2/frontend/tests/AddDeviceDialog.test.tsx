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
  return vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
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
    const fetchMock = routedFetch({
      "/credentials": CREDENTIALS_ROUTE,
      "/session/status": { body: { csrf_token: "test-csrf" } },
      "/devices/add-single": { body: { device_id: "dev-1", job_id: "job-1", enrollment_state: "DRAFT" } },
      "/devices/dev-1/inventory/collect": { status: 202, body: { job_id: "job-2" } },
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
            job: { job_id: "job-2", state: "EXECUTING", outcome: null, terminal_reason: null },
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
            job: { job_id: "job-2", state: "COMPLETED", outcome: "SUCCESS", terminal_reason: null },
          },
        },
      ],
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<AddDeviceDialogTrigger />));
    await openDialogAndFillAddress("192.0.2.10");

    fireEvent.click(screen.getByRole("button", { name: "Enrol" }));

    await waitFor(() => expect(screen.getByText("Enrolled")).toBeInTheDocument(), { timeout: 5000 });
    expect(screen.getByText(/fw-edge-1/)).toBeInTheDocument();
    expect(screen.getByText(/Quantum/)).toBeInTheDocument();
    expect(screen.getByText("Corroborated with peer")).toBeInTheDocument();

    const addCall = fetchMock.mock.calls.find(([input]) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      return url.split("?")[0] === "/devices/add-single";
    }) as unknown as [RequestInfo | URL, RequestInit] | undefined;
    expect(addCall).toBeDefined();
    const body = JSON.parse(addCall![1].body as string);
    expect(body.role).toBe("gateway");
    expect(fetchMock.mock.calls.some(([input]) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      return url.split("?")[0] === "/devices/dev-1/inventory/collect";
    })).toBe(true);
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

  it("shows the creation control on both paths when the credential list fails to load", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({
        "/credentials": { status: 500, body: { error: "INTERNAL_ERROR" } },
        "/session/status": { body: { csrf_token: "test-csrf" } },
      }),
    );

    render(withTheme(<AddDeviceDialogTrigger />));
    fireEvent.click(screen.getByRole("button", { name: "Add device" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Create credential" })).toBeInTheDocument());
    expect(screen.getByText(/INTERNAL_ERROR/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Management server (discovery)" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Create credential" })).toBeInTheDocument());
    expect(screen.getByText(/INTERNAL_ERROR/)).toBeInTheDocument();
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
    expect(checkboxes).toHaveLength(4);
    fireEvent.click(checkboxes[1]);
    await waitFor(() => expect(checkboxes[2]).toBeChecked());
    expect(checkboxes[3]).toBeChecked();
    expect(checkboxes[0]).toBeChecked();

    fireEvent.click(screen.getByRole("button", { name: "Import (2)" }));

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

  it("master checkbox and select all button select all candidates, clear selection deselects all", async () => {
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
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<AddDeviceDialogTrigger />));
    await openDialogAndSwitchToDiscovery("mds.example");
    fireEvent.click(screen.getByRole("button", { name: "Start discovery" }));
    await waitFor(() => expect(screen.getByText("Cluster Alpha")).toBeInTheDocument(), { timeout: 8000 });

    const selectAllBtn = screen.getByRole("button", { name: "Select all (2)" });
    const clearBtn = screen.getByRole("button", { name: "Clear selection" });
    const masterCheckbox = screen.getByRole("checkbox", { name: "Select all candidates" });

    expect(clearBtn).toBeDisabled();
    expect(selectAllBtn).not.toBeDisabled();

    // Click "Select all (2)"
    fireEvent.click(selectAllBtn);
    expect(selectAllBtn).toBeDisabled();
    expect(clearBtn).not.toBeDisabled();
    expect(masterCheckbox).toBeChecked();
    expect(screen.getByRole("button", { name: "Import (2)" })).not.toBeDisabled();

    // Click "Clear selection"
    fireEvent.click(clearBtn);
    expect(clearBtn).toBeDisabled();
    expect(selectAllBtn).not.toBeDisabled();
    expect(masterCheckbox).not.toBeChecked();

    // Click master checkbox in header
    fireEvent.click(masterCheckbox);
    expect(masterCheckbox).toBeChecked();
    expect(screen.getByRole("button", { name: "Import (2)" })).not.toBeDisabled();

    // Toggle master checkbox off
    fireEvent.click(masterCheckbox);
    expect(masterCheckbox).not.toBeChecked();
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
    // 1 master checkbox in header + 1 candidate checkbox for Edge Gateway = 2 checkboxes
    const checkboxes = screen.getAllByRole("checkbox");
    expect(checkboxes).toHaveLength(2);

    // The one selectable row can still be checked -- the disabled row simply has none.
    fireEvent.click(checkboxes[1]);
    expect(checkboxes[1]).toBeChecked();
    expect(checkboxes[0]).toBeChecked();
  }, 10000);

  it("offers inline credential creation on discovery path and selects the created credential", async () => {
    vi.stubGlobal(
      "fetch",
      routedFetch({
        "/credentials": [
          { body: { credentials: [] } },
          {
            body: {
              credential_id: "c-new-discovery",
              credential_reference_id: "cred-ref-new",
              display_name: "New Discovery SSH",
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
    fireEvent.click(screen.getByRole("button", { name: "Management server (discovery)" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Create credential" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Create credential" }));
    fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "New Discovery SSH" } });
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "admin" } });
    fireEvent.change(screen.getByLabelText("Secret"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Start discovery" })).toBeDisabled());
    fireEvent.change(screen.getByLabelText("Management server address"), { target: { value: "mds.example" } });
    await waitFor(() => expect(screen.getByRole("button", { name: "Start discovery" })).not.toBeDisabled());
  });
});


describe("C10 SSH trust enrollment", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("makes SSH trust authorization available before direct Check Point enrollment", async () => {
    vi.stubGlobal("fetch", routedFetch({ "/credentials": CREDENTIALS_ROUTE }));
    render(withTheme(<AddDeviceDialogTrigger />));
    await openDialogAndFillAddress("fixture-gateway");
    expect(screen.getByRole("button", { name: "SSH trust authorization" })).toBeInTheDocument();
  });

  it("requires explicit independent verification, sends the gated action, and displays only MATCH", async () => {
    const fetchMock = routedFetch({
      "/credentials": CREDENTIALS_ROUTE,
      "/session/status": { body: { csrf_token: "test-csrf" } },
      "/discovery/ssh-trust/enroll": { body: { relationship: "MATCH", fingerprint: "sensitive-response-marker" } },
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<AddDeviceDialogTrigger />));
    await openDialogAndSwitchToDiscovery("fixture-management");
    fireEvent.click(screen.getByRole("button", { name: "SSH trust authorization" }));
    const button = screen.getByRole("button", { name: "Authorize SSH trust" });
    expect(button).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Independently verified SHA-256 key (lowercase hex)"), { target: { value: "0".repeat(64) } });
    fireEvent.change(screen.getByLabelText("Key observed at"), { target: { value: "2026-09-01T10:00" } });
    expect(button).toBeDisabled();
    fireEvent.click(screen.getByLabelText("I independently verified the observed key"));
    expect(button).not.toBeDisabled();
    fireEvent.click(button);
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("SSH trust: MATCH"));
    expect(screen.queryByText("sensitive-response-marker")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Independently verified SHA-256 key (lowercase hex)")).toHaveValue("");
    const call = fetchMock.mock.calls.find(([input]) => String(input) === "/discovery/ssh-trust/enroll")!;
    const init = call[1] as RequestInit;
    expect(init.headers).toEqual({ "Content-Type": "application/json", "X-CSRF-Token": "test-csrf" });
    expect(JSON.parse(init.body as string)).toMatchObject({ management_port: 22, key_algorithm: "ssh-rsa", independently_verified: true });
  });

  it.each([
    ["TRUST_ENTRY_MISSING", "SSH trust: MISSING."],
    ["TRUST_MISMATCH", "SSH trust: MISMATCH."],
    ["AUTH_FAILED", "SSH authentication failed."],
    ["CONNECT_TIMEOUT", "SSH connection timed out"],
  ])("displays the safe %s failure without management identity", async (failure, copy) => {
    vi.stubGlobal("fetch", routedFetch({
      "/credentials": CREDENTIALS_ROUTE,
      "/session/status": { body: { csrf_token: "test-csrf" } },
      "/discovery/runs": { status: 202, body: { run_id: "run-safe", job_id: "job-safe" } },
      "/discovery/runs/run-safe": { body: { run_id: "run-safe", vendor: "check_point", state: "FAILED",
        outcome_summary: { [failure]: 1 }, candidates: [], error: "sensitive-response-marker" } },
    }));
    render(withTheme(<AddDeviceDialogTrigger />));
    await openDialogAndSwitchToDiscovery("fixture-management");
    fireEvent.click(screen.getByRole("button", { name: "Start discovery" }));
    await waitFor(() => expect(screen.getByText(new RegExp(copy))).toBeInTheDocument());
    expect(screen.queryByText("fixture-management")).not.toBeInTheDocument();
    expect(screen.queryByText("sensitive-response-marker")).not.toBeInTheDocument();
  });
});

describe("Palo Alto certificate trust", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("does not render certificate trust authorization", async () => {
    const panCredentials = { body: { credentials: [{
      credential_id: "pan-c-1", credential_reference_id: "pan-ref-1", display_name: "PAN API",
      kind: "api_key", username: "admin", allows_check_point: false, allows_palo_alto: true,
      created_at: "2026-09-01T00:00:00Z", secret_set_at: "2026-09-01T00:00:00Z",
    }] } };
    vi.stubGlobal("fetch", routedFetch({
      "/credentials": panCredentials,
    }));
    render(withTheme(<AddDeviceDialogTrigger />));
    fireEvent.click(screen.getByRole("button", { name: "Add device" }));
    fireEvent.mouseDown(screen.getByLabelText("Vendor"));
    fireEvent.click(await screen.findByRole("option", { name: "Palo Alto" }));

    expect(screen.queryByRole("button", { name: "Certificate trust authorization" })).not.toBeInTheDocument();
  });
});
