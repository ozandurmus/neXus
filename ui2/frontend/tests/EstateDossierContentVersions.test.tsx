import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { buildEstateModel, type EstateInputs } from "../src/estate/estateModel";
import { EstateDossierScreen } from "../src/estate/EstateDossierScreen";
import type { EstateDataState } from "../src/estate/useEstateData";

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).
// The Dossier identity area shows the Palo Alto content versions the device reported (standard device identity parity).
const NOW = new Date("2026-11-22T09:00:00.000Z");
const DEVICE_ID = "dev-tango-04";
const VERSION_VALUE = "9000-1234";

const mockState = vi.hoisted(() => ({ model: null as unknown }));

vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: () => mockState.model,
}));

function buildFixture(
  contentVersions: Readonly<Record<string, string>> | null | undefined,
  vendorHint: string = "palo_alto",
): EstateDataState {
  const device: Record<string, unknown> = {
    device_id: DEVICE_ID,
    role: "gateway",
    vendor_hint: vendorHint,
    enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04",
    model: "SYNTH-PA-1",
    software_version: "10.2.4",
    ha_role: null,
    cluster_member_ref: null,
    management_ip: "192.0.2.10",
    backup_target: false,
    platform_facts_observed_at: "2026-11-22T08:00:00.000Z",
  };
  if (contentVersions !== undefined) device.content_versions = contentVersions;
  const inputs: EstateInputs = {
    now: NOW,
    devices: { state: "ok", value: [device] } as never,
    overview: { state: "unavailable" } as never,
    failover: { state: "ok", value: [] },
    backups: { state: "ok", value: { backups: [] } },
    lifecycle: { state: "ok", value: { devices: [] } } as never,
    controls: { state: "ok", value: [] },
    changes: { state: "ok", value: { items: [] } } as never,
    health: { state: "unavailable" } as never,
  };
  const model = buildEstateModel(inputs);
  return { loading: false, fatalError: null, overview: null, model } as unknown as EstateDataState;
}

beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response("{}", { status: 500, headers: { "Content-Type": "application/json" } })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/** The identity line that carries the "Content versions" label. */
async function renderLine(contentVersions: Readonly<Record<string, string>> | null | undefined): Promise<HTMLElement> {
  mockState.model = buildFixture(contentVersions);
  render(<EstateDossierScreen deviceId={DEVICE_ID} />);
  await waitFor(() => expect(screen.getByText("FW-TANGO-04", { selector: "h1" })).toBeTruthy());
  const label = screen.getByText("Content versions", { selector: "span" });
  return label.parentElement as HTMLElement;
}

describe("Estate Dossier content versions row", () => {
  it("renders the reported content version parts for a Palo Alto device", async () => {
    const line = await renderLine({ app: VERSION_VALUE, threat: "9100-5678", av: "4400-0001", wildfire: "700-0002", url: "8800-0003" });
    expect(line.textContent).toContain("Applications");
    expect(line.textContent).toContain(VERSION_VALUE);
    expect(line.textContent).toContain("Threats");
    expect(line.textContent).toContain("9100-5678");
    expect(line.textContent).not.toContain("Not collected");
  });

  it("shows Not collected (UNKNOWN) for a null content_versions map, never OK or 0", async () => {
    const line = await renderLine(null);
    expect(line.textContent).toContain("Not collected");
    expect(line.textContent).not.toContain("OK");
    expect(line.textContent).not.toMatch(/\b0\b/);
  });

  it("shows Not collected when the content_versions key is absent", async () => {
    const line = await renderLine(undefined);
    expect(line.textContent).toContain("Not collected");
    expect(line.textContent).not.toMatch(/\b0\b/);
  });

  it("does not show a missing key as zero in a partial map", async () => {
    const line = await renderLine({ app: VERSION_VALUE });
    expect(line.textContent).toContain("Applications");
    expect(line.textContent).toContain(VERSION_VALUE);
    expect(line.textContent).not.toContain("Threats 0");
    expect(line.textContent).not.toContain("Antivirus 0");
    expect(line.textContent).toContain("4 not reported");
  });

  it("links to the standard device view with standard=1 and marks it", async () => {
    const line = await renderLine({ app: VERSION_VALUE });
    const link = line.querySelector("a");
    expect(link).not.toBeNull();
    expect(link?.getAttribute("href")).toContain("standard=1");
    expect(link?.getAttribute("href")).toContain(`device_id=${DEVICE_ID}`);
    expect(link?.textContent).toContain("(standard view)");
  });

  it("does not render the Content versions row for a Check Point device (Palo Alto packs only)", async () => {
    mockState.model = buildFixture(null, "check_point");
    render(<EstateDossierScreen deviceId={DEVICE_ID} />);
    await waitFor(() => expect(screen.getByText("FW-TANGO-04", { selector: "h1" })).toBeTruthy());
    expect(screen.queryByText("Content versions", { selector: "span" })).toBeNull();
    expect(screen.queryByText(/Content versions \(standard view\)/)).toBeNull();
    expect(screen.queryByText(/UNKNOWN · Not collected: no content version/)).toBeNull();
  });

  it("does not render the Content versions row for Cisco ASA or FortiGate devices", async () => {
    for (const vendor of ["cisco_asa", "fortinet"]) {
      mockState.model = buildFixture(undefined, vendor);
      const { unmount } = render(<EstateDossierScreen deviceId={DEVICE_ID} />);
      await waitFor(() => expect(screen.getByText("FW-TANGO-04", { selector: "h1" })).toBeTruthy());
      expect(screen.queryByText("Content versions", { selector: "span" })).toBeNull();
      expect(screen.queryByText(/UNKNOWN · Not collected: no content version/)).toBeNull();
      unmount();
    }
  });
});
