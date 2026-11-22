import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getSshHostKeyView, type SshHostKeyView } from "../src/auth/adminApi";
import { EstateDossierSshTrustBlock } from "../src/estate/EstateDossierSshTrustBlock";
import { EstateDossierScreen } from "../src/estate/EstateDossierScreen";
import { buildEstateModel, type EstateInputs } from "../src/estate/estateModel";
import type { EstateDataState } from "../src/estate/useEstateData";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, getSshHostKeyView: vi.fn() };
});

const mockedView = vi.mocked(getSshHostKeyView);
const mockState = vi.hoisted(() => ({ model: null as unknown }));

vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: () => mockState.model,
}));

// Synthetic identifiers only (FW-TANGO-04 style names, 192.0.2.x addresses). Key-shaped values are built at runtime.
const DEVICE_ID = "dev-tango-04";
const FP = ["SHA256", "y".repeat(43)].join(":");

const VIEW: SshHostKeyView = {
  mode: "STRICT",
  observations: [
    {
      observation_id: "obs-1",
      device_id: DEVICE_ID,
      hostname: "FW-TANGO-04",
      key_algorithm: "ssh-ed25519",
      presented_fingerprint: FP,
      expected_fingerprint_prefix: "SHA256:zz",
      first_seen: "2026-11-01T00:00:00Z",
      last_seen: "2026-11-20T08:00:00Z",
      seen_count: 2,
      status: "ACCEPTED",
      strict_refusal: false,
      ssh_identity_unverified: false,
      model: null,
      software_version: null,
      serial_number: null,
      management_source: "direct",
    },
    {
      observation_id: "obs-2",
      device_id: DEVICE_ID,
      hostname: "FW-TANGO-04",
      key_algorithm: "ssh-ed25519",
      presented_fingerprint: FP,
      expected_fingerprint_prefix: "SHA256:zz",
      first_seen: "2026-11-21T00:00:00Z",
      last_seen: "2026-11-21T09:00:00Z",
      seen_count: 1,
      status: "OPEN",
      strict_refusal: true,
      ssh_identity_unverified: false,
      model: null,
      software_version: null,
      serial_number: null,
      management_source: "direct",
    },
  ],
};

beforeEach(() => {
  mockedView.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("EstateDossierSshTrustBlock", () => {
  it("shows the loading word while the read is pending", () => {
    mockedView.mockReturnValue(new Promise(() => {}));
    render(<EstateDossierSshTrustBlock deviceId={DEVICE_ID} />);
    expect(screen.getByText("Reading SSH trust")).toBeTruthy();
  });

  it("a request error renders UNKNOWN and never OK or Fine", async () => {
    mockedView.mockRejectedValue(new Error("boom"));
    render(<EstateDossierSshTrustBlock deviceId={DEVICE_ID} />);
    await waitFor(() => expect(screen.getByText("SSH host-key status unavailable")).toBeTruthy());
    const block = screen.getByTestId("dossier-ssh-trust-block");
    expect(within(block).getByText("UNKNOWN")).toBeTruthy();
    expect(block.textContent).not.toMatch(/\bOK\b/);
    expect(block.textContent).not.toMatch(/\bFine\b/);
  });

  it("zero observations renders UNKNOWN, not Fine", async () => {
    mockedView.mockResolvedValue({ mode: "WARN", observations: [] });
    render(<EstateDossierSshTrustBlock deviceId={DEVICE_ID} />);
    await waitFor(() => expect(screen.getByText("No SSH host-key observation for this unit")).toBeTruthy());
    expect(screen.getByTestId("dossier-ssh-trust-block").textContent).not.toMatch(/\bFine\b/);
  });

  it("shows the trust mode, a status badge per observation, the evidence stamp, and the standard-view link only", async () => {
    mockedView.mockResolvedValue(VIEW);
    render(<EstateDossierSshTrustBlock deviceId={DEVICE_ID} />);
    const block = await waitFor(() => screen.getByTestId("dossier-ssh-trust-block"));
    expect(within(block).getByText("STRICT")).toBeTruthy();
    const rows = block.querySelectorAll("[data-ssh-trust-row]");
    expect(rows).toHaveLength(2);
    expect(rows[0].getAttribute("data-ssh-trust-row")).toBe("OPEN");
    expect(block.textContent).toContain("ACCEPTED");
    expect(block.textContent).toContain("2 observations · 1 open · 0 rejected · 1 accepted");
    expect(block.querySelectorAll("[data-evidence-stamp]")).toHaveLength(2);
    const link = within(block).getByText("Manage SSH trust in the standard view").closest("a");
    expect(link?.getAttribute("href")).toBe("?screen=administration&tab=ssh-trust&standard=1");
    // Sensitive identity law: no fingerprint or hostname reaches the page.
    expect(block.textContent).not.toContain(FP);
    expect(block.textContent).not.toContain("FW-TANGO-04");
  });

  it("an unreported seen count reads 'seen count not reported', never 'seen 0'", async () => {
    const { seen_count: _omitted, ...noCount } = VIEW.observations[0];
    mockedView.mockResolvedValue({ mode: "WARN", observations: [noCount as SshHostKeyView["observations"][number]] });
    render(<EstateDossierSshTrustBlock deviceId={DEVICE_ID} />);
    const block = await waitFor(() => screen.getByTestId("dossier-ssh-trust-block"));
    expect(block.textContent).toContain("seen count not reported");
    expect(block.textContent).not.toContain("seen 0");
  });

  it("carries no accept, reject or save button", async () => {
    mockedView.mockResolvedValue(VIEW);
    render(<EstateDossierSshTrustBlock deviceId={DEVICE_ID} />);
    await waitFor(() => screen.getByTestId("dossier-ssh-trust-block"));
    expect(screen.queryByRole("button", { name: /accept|reject|save/i })).toBeNull();
  });
});

describe("EstateDossierSshTrustBlock mounted in the dossier", () => {
  beforeEach(() => {
    mockState.model = buildDossierState();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("{}", { status: 500, headers: { "Content-Type": "application/json" } })),
    );
  });

  it("the SSH trust block mounts in the dossier and a failed read shows UNKNOWN", async () => {
    mockedView.mockRejectedValue(new Error("boom"));
    render(<EstateDossierScreen deviceId={DEVICE_ID} />);
    await waitFor(() => expect(screen.getByTestId("dossier-ssh-trust-block")).toBeTruthy());
    expect(mockedView).toHaveBeenCalledWith(DEVICE_ID);
    expect(screen.getByText("SSH host-key status unavailable")).toBeTruthy();
  });
});

function buildDossierState(): EstateDataState {
  const inputs: EstateInputs = {
    now: new Date("2026-11-22T09:00:00.000Z"),
    devices: {
      state: "ok",
      value: [
        {
          device_id: DEVICE_ID,
          role: "gateway",
          vendor_hint: "checkpoint",
          enrollment_state: "ENROLLED",
          hostname: "FW-TANGO-04",
          model: "SYNTH-GW-1",
          software_version: "R81.20",
          ha_role: null,
          cluster_member_ref: null,
          management_ip: "192.0.2.10",
          backup_target: false,
        },
      ],
    } as never,
    overview: { state: "unavailable" } as never,
    failover: { state: "ok", value: [] },
    backups: { state: "ok", value: { backups: [] } },
    lifecycle: { state: "ok", value: { devices: [] } } as never,
    controls: { state: "ok", value: [] },
    changes: { state: "ok", value: { items: [] } } as never,
    health: { state: "unavailable" } as never,
  };
  return {
    loading: false,
    fatalError: null,
    overview: null,
    model: buildEstateModel(inputs),
  } as unknown as EstateDataState;
}
