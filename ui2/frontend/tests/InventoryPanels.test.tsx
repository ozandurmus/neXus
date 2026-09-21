import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { InventoryScreen } from "../src/screens/InventoryScreen";
import { BackupPanel, buildUnifiedContextTabs } from "../src/screens/InventoryPanels";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const STANDALONE_DEVICE = {
  device_id: "dev-1",
  vendor_hint: "check_point",
  enrollment_state: "ENROLLED",
  hostname: "fw-edge-1",
  model: "Quantum",
  software_version: "R81.20",
  ha_role: null,
  cluster_member_ref: null,
};

const CLUSTER_MEMBER_A = {
  device_id: "dev-a",
  vendor_hint: "check_point",
  enrollment_state: "ENROLLED",
  hostname: "member-a",
  model: "Quantum",
  software_version: "R81.20",
  ha_role: "active",
  cluster_member_ref: "cluster-1",
};

const CLUSTER_MEMBER_B = {
  ...CLUSTER_MEMBER_A,
  device_id: "dev-b",
  hostname: "member-b",
  ha_role: "standby",
};

describe("InventoryScreen device selection and panels", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetches and renders a selected standalone device's interfaces and routes", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/devices") {
        return Promise.resolve(jsonResponse(200, { devices: [STANDALONE_DEVICE] }));
      }
      if (url === "/devices/dev-1/inventory") {
        return Promise.resolve(
          jsonResponse(200, {
            device_id: "dev-1",
            collected_at: "2026-09-14T12:00:00Z",
            job: { job_id: "job-1", state: "COMPLETED", outcome: "SUCCESS", terminal_reason: null },
            contexts: [
              {
                context: "physical",
                interfaces: [
                  {
                    name: "eth0",
                    parent: null,
                    kind: "physical",
                    state: "up",
                    addresses: [{ address: "198.51.100.5/24", family: "ipv4", role: "member" }],
                  },
                ],
                routes: [
                  { destination: "0.0.0.0/0", next_hop: "198.51.100.1", interface: "eth0", protocol: "default", table: null },
                ],
              },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    fireEvent.click(screen.getByText("fw-edge-1"));

    await waitFor(() => expect(screen.getByText("eth0")).toBeInTheDocument());
    expect(screen.getByText("198.51.100.5/24")).toBeInTheDocument();
    expect(screen.getByText("dev-1")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Routing" }));
    await waitFor(() => expect(screen.getByText("0.0.0.0/0")).toBeInTheDocument());
  });

  it("renders the VLAN id column and the context's HA role when the run recorded them", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/devices") {
        return Promise.resolve(jsonResponse(200, { devices: [STANDALONE_DEVICE] }));
      }
      if (url === "/devices/dev-1/inventory") {
        return Promise.resolve(
          jsonResponse(200, {
            device_id: "dev-1",
            collected_at: "2026-09-14T12:00:00Z",
            job: { job_id: "job-1", state: "COMPLETED", outcome: "SUCCESS", terminal_reason: null },
            contexts: [
              {
                context: "physical",
                ha: { role: "ACTIVE", cluster_mode: "High Availability" },
                interfaces: [
                  {
                    name: "eth0.100",
                    parent: "eth0",
                    kind: "vlan",
                    state: "up",
                    vlan_id: 100,
                    addresses: [],
                  },
                ],
                routes: [],
              },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    fireEvent.click(screen.getByText("fw-edge-1"));

    await waitFor(() => expect(screen.getByText("eth0.100")).toBeInTheDocument());
    expect(screen.getByText("100")).toBeInTheDocument();
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
    expect(screen.getByText("High Availability")).toBeInTheDocument();
  });

  it("renders the cluster unified view with a member marker and VIP once for a cluster device", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/devices") {
        return Promise.resolve(jsonResponse(200, { devices: [CLUSTER_MEMBER_A, CLUSTER_MEMBER_B] }));
      }
      if (url === "/clusters/cluster-1/inventory") {
        return Promise.resolve(
          jsonResponse(200, {
            cluster_member_ref: "cluster-1",
            members: [
              { device_id: "dev-a", hostname: "member-a" },
              { device_id: "dev-b", hostname: "member-b" },
            ],
            contexts: [
              {
                context: "physical",
                interfaces: [
                  {
                    name: "eth0",
                    kind: "physical",
                    addresses: [{ address: "192.0.2.100/24", family: "ipv4", role: "cluster_virtual" }],
                    presence: "all",
                    differences: [{ device_id: "dev-b", field: "state", value: "down" }],
                  },
                ],
                routes: [],
              },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("member-a")).toBeInTheDocument());
    fireEvent.click(screen.getByText("member-a"));

    // The membership marker names both members, and the VIP appears exactly once.
    await waitFor(() => expect(screen.getAllByText("member-b").length).toBeGreaterThan(0));
    expect(screen.getAllByText("192.0.2.100/24")).toHaveLength(1);
    expect(screen.getByText("VIP")).toBeInTheDocument();
    expect(screen.getByText(/state = down/)).toBeInTheDocument();
  });

  it("Collect now submits, polls the device, and refreshes the inventory once the job is terminal", async () => {
    let devicePollCount = 0;
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/devices") {
        return Promise.resolve(jsonResponse(200, { devices: [STANDALONE_DEVICE] }));
      }
      if (url === "/devices/dev-1/inventory") {
        return Promise.resolve(jsonResponse(200, { device_id: "dev-1", collected_at: null, job: null, contexts: [] }));
      }
      if (url === "/session/status") {
        return Promise.resolve(jsonResponse(200, { csrf_token: "token-1" }));
      }
      if (url === "/devices/dev-1/inventory/collect" && init?.method === "POST") {
        return Promise.resolve(jsonResponse(202, { job_id: "job-2" }));
      }
      if (url === "/devices/dev-1") {
        devicePollCount += 1;
        const terminal = devicePollCount >= 2;
        return Promise.resolve(
          jsonResponse(200, {
            device_id: "dev-1",
            vendor_hint: "check_point",
            enrollment_state: "ENROLLED",
            disabled: false,
            facts: null,
            peer_follow_outcome: null,
            peer_follow_reason: null,
            identity_mismatch_state: "NONE",
            cluster_member_ref: null,
            job: { job_id: "job-2", state: terminal ? "COMPLETED" : "EXECUTING", outcome: terminal ? "SUCCESS" : null, terminal_reason: null },
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(withTheme(<InventoryScreen />));

    await waitFor(() => expect(screen.getByText("fw-edge-1")).toBeInTheDocument());
    fireEvent.click(screen.getByText("fw-edge-1"));

    const collectButton = await screen.findByRole("button", { name: "Collect now" });
    fireEvent.click(collectButton);

    await waitFor(() =>
      expect(fetchMock.mock.calls.some((call) => String(call[0]) === "/devices/dev-1/inventory/collect")).toBe(true),
    );
    await waitFor(() => expect(devicePollCount).toBeGreaterThanOrEqual(2), { timeout: 5000 });
  });
});

describe("BackupPanel", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders a loading state rather than no-backups while the request is in flight", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === "/devices/dev-1/backups") {
        return new Promise(() => {}); // request never resolves
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<BackupPanel deviceId="dev-1" />));

    expect(screen.getByText("Loading…")).toBeInTheDocument();
    expect(screen.queryByText("No backups")).not.toBeInTheDocument();
  });

  it("renders an empty panel when no backups are retained", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === "/devices/dev-1/backups") {
        return Promise.resolve(jsonResponse(200, { backups: [] }));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<BackupPanel deviceId="dev-1" />));
    await waitFor(() => expect(screen.getByText("No backups")).toBeInTheDocument());
    expect(screen.getByText("No backup has been retained for this device.")).toBeInTheDocument();
  });

  it("renders a list of backups with their properties, translating a null deviation state to 'not evaluated'", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === "/devices/dev-1/backups") {
        return Promise.resolve(jsonResponse(200, {
          backups: [
            {
              artefact_id: "art-1",
              device_id: "dev-1",
              collected_at: "2026-09-14T12:00:00Z",
              size_bytes: 1024,
              digest_prefix: "abcd123",
              validation_level: "trusted",
              deviation_state: null
            },
            {
              artefact_id: "art-2",
              device_id: "dev-1",
              collected_at: "2026-09-13T12:00:00Z",
              size_bytes: 1024,
              digest_prefix: "9876xyz",
              validation_level: "trusted",
              deviation_state: "changed"
            },
            {
              artefact_id: "art-3",
              device_id: "dev-1",
              collected_at: "2026-09-12T12:00:00Z",
              size_bytes: 1024,
              digest_prefix: "3456abc",
              validation_level: "trusted",
              deviation_state: "unchanged"
            },
            {
              artefact_id: "art-4",
              device_id: "dev-1",
              collected_at: "2026-09-11T12:00:00Z",
              size_bytes: 1024,
              digest_prefix: "7890def",
              validation_level: "trusted",
              deviation_state: "first"
            }
          ]
        }));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<BackupPanel deviceId="dev-1" />));

    await waitFor(() => expect(screen.getByText("abcd123")).toBeInTheDocument());
    expect(screen.getByText("9876xyz")).toBeInTheDocument();

    expect(screen.getByText("not evaluated")).toBeInTheDocument();
    expect(screen.getByText("changed")).toBeInTheDocument();
    expect(screen.getByText("unchanged")).toBeInTheDocument();
    expect(screen.getByText("first")).toBeInTheDocument();
    expect(screen.getByText(/Backup archives are compared by digest only/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows the error rather than an empty table on failure", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === "/devices/dev-1/backups") {
        return Promise.resolve(jsonResponse(500, { error: "Failed to connect to storage" }));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<BackupPanel deviceId="dev-1" />));

    await waitFor(() => expect(screen.getByText("Failed to connect to storage")).toBeInTheDocument());
    expect(screen.getByText("Backup unavailable")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("submits the backup request with the typed reason when reason is long enough", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/devices/dev-1/backups") {
        return Promise.resolve(jsonResponse(200, { backups: [] }));
      }
      if (url === "/session/status") {
        return Promise.resolve(jsonResponse(200, { csrf_token: "tok-1" }));
      }
      if (url === "/devices/dev-1/backup/collect" && init?.method === "POST") {
        return Promise.resolve(jsonResponse(202, { job_id: "bkjob-1" }));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<BackupPanel deviceId="dev-1" />));
    await waitFor(() => expect(screen.queryByText("Loading…")).not.toBeInTheDocument());

    const field = screen.getByRole("textbox", { name: /Backup reason/i });
    fireEvent.change(field, { target: { value: "scheduled maintenance window" } });

    const button = screen.getByRole("button", { name: "Request backup" });
    fireEvent.click(button);

    await waitFor(() =>
      expect(fetchMock.mock.calls.some((call) => String(call[0]) === "/devices/dev-1/backup/collect")).toBe(true),
    );
    const collectCall = fetchMock.mock.calls.find((call) => String(call[0]) === "/devices/dev-1/backup/collect");
    const body = JSON.parse(collectCall?.[1]?.body as string);
    expect(body.reason).toBe("scheduled maintenance window");

    await waitFor(() =>
      expect(screen.getByText(/Backup request accepted/i)).toBeInTheDocument(),
    );
  });

  it("refuses locally without sending a request when the reason is shorter than 8 characters", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === "/devices/dev-1/backups") {
        return Promise.resolve(jsonResponse(200, { backups: [] }));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<BackupPanel deviceId="dev-1" />));
    await waitFor(() => expect(screen.queryByText("Loading…")).not.toBeInTheDocument());

    const field = screen.getByRole("textbox", { name: /Backup reason/i });
    fireEvent.change(field, { target: { value: "short" } });

    // Both the helper text and the inline validation message mention "at least 8 characters".
    const minMessages = screen.getAllByText(/at least 8 characters/i);
    expect(minMessages.length).toBeGreaterThanOrEqual(1);

    const button = screen.getByRole("button", { name: "Request backup" });
    expect(button).toBeDisabled();

    // No collect call should have been made.
    expect(
      fetchMock.mock.calls.some((call) => String(call[0]) === "/devices/dev-1/backup/collect"),
    ).toBe(false);
  });

  it("shows the server's own code and reason verbatim on a 409 refusal", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/devices/dev-1/backups") {
        return Promise.resolve(jsonResponse(200, { backups: [] }));
      }
      if (url === "/session/status") {
        return Promise.resolve(jsonResponse(200, { csrf_token: "tok-1" }));
      }
      if (url === "/devices/dev-1/backup/collect" && init?.method === "POST") {
        return Promise.resolve(
          jsonResponse(409, {
            error: "ADMISSION_REFUSED",
            code: "DEVICE_NOT_IN_BACKUP_PILOT_ALLOWLIST",
            reason: "device dev-1 is not on the backup pilot allowlist (14H BK-1) -- refused, never a silent skip",
          }),
        );
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<BackupPanel deviceId="dev-1" />));
    await waitFor(() => expect(screen.queryByText("Loading…")).not.toBeInTheDocument());

    const field = screen.getByRole("textbox", { name: /Backup reason/i });
    fireEvent.change(field, { target: { value: "scheduled maintenance" } });
    fireEvent.click(screen.getByRole("button", { name: "Request backup" }));

    await waitFor(() =>
      expect(
        screen.getByText(
          "DEVICE_NOT_IN_BACKUP_PILOT_ALLOWLIST: device dev-1 is not on the backup pilot allowlist (14H BK-1) -- refused, never a silent skip",
        ),
      ).toBeInTheDocument(),
    );
  });

  it("refreshes the backup list after a 202 admission", async () => {
    let listCallCount = 0;
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/devices/dev-1/backups") {
        listCallCount += 1;
        const second = listCallCount > 1;
        return Promise.resolve(
          jsonResponse(200, {
            backups: second
              ? [
                  {
                    artefact_id: "art-fresh",
                    device_id: "dev-1",
                    collected_at: "2026-09-15T00:00:00Z",
                    size_bytes: 512,
                    digest_prefix: "newone1",
                    validation_level: "trusted",
                    deviation_state: "first",
                  },
                ]
              : [],
          }),
        );
      }
      if (url === "/session/status") {
        return Promise.resolve(jsonResponse(200, { csrf_token: "tok-1" }));
      }
      if (url === "/devices/dev-1/backup/collect" && init?.method === "POST") {
        return Promise.resolve(jsonResponse(202, { job_id: "bkjob-2" }));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<BackupPanel deviceId="dev-1" />));
    await waitFor(() => expect(screen.queryByText("Loading…")).not.toBeInTheDocument());

    const field = screen.getByRole("textbox", { name: /Backup reason/i });
    fireEvent.change(field, { target: { value: "post-change verification" } });
    fireEvent.click(screen.getByRole("button", { name: "Request backup" }));

    await waitFor(() => expect(screen.getByText("newone1")).toBeInTheDocument());
    expect(listCallCount).toBeGreaterThanOrEqual(2);
  });

  it("has no restore, download, or decrypt control", () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === "/devices/dev-1/backups") {
        return Promise.resolve(jsonResponse(200, { backups: [] }));
      }
      return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(withTheme(<BackupPanel deviceId="dev-1" />));

    // None of these words should ever appear as a button or label.
    expect(screen.queryByRole("button", { name: /restore/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /download/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /decrypt/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/restore/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/download/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/decrypt/i)).not.toBeInTheDocument();
  });
});

describe("buildUnifiedContextTabs", () => {
  it("formats Check Point virtual systems without duplicating (VSID n)", () => {
    const contexts = [
      { context: "physical", interfaces: [] },
      { context: "2", vs_name: "vs-finance (VSID 2)", interfaces: [] },
      { context: "3", vs_name: "vs-dmz", interfaces: [] },
    ];
    const virtualSystems = ["vs-finance (VSID 2)", "vs-dmz"];

    const { tabs, resolveContext } = buildUnifiedContextTabs(contexts, virtualSystems);

    expect(tabs).toHaveLength(3);
    expect(tabs[0].label).toBe("Physical / VS0");
    // Should NOT be "VS: vs-finance (VSID 2) (VSID 2)"
    expect(tabs[1].label).toBe("VS: vs-finance (VSID 2)");
    // Should append (VSID 3) because vs-dmz doesn't have parens
    expect(tabs[2].label).toBe("VS: vs-dmz (VSID 3)");

    expect(resolveContext("vs-finance (VSID 2)")).toBe(contexts[1]);
    expect(resolveContext("2")).toBe(contexts[1]);
  });

  it("formats Palo Alto virtual systems with VR name first and VSYS prefix", () => {
    const contexts = [
      { context: "vsys1", vs_name: "default (vsys1)", interfaces: [] },
      { context: "vsys2", vs_name: "VR-DMZ (vsys2)", interfaces: [] },
    ];
    const virtualSystems = ["default (vsys1)", "VR-DMZ (vsys2)"];

    const { tabs, resolveContext } = buildUnifiedContextTabs(contexts, virtualSystems);

    expect(tabs).toHaveLength(2);
    expect(tabs[0].label).toBe("VSYS: default (vsys1)");
    expect(tabs[1].label).toBe("VSYS: VR-DMZ (vsys2)");

    expect(resolveContext("default (vsys1)")).toBe(contexts[0]);
    expect(resolveContext("vsys1")).toBe(contexts[0]);
  });
});
