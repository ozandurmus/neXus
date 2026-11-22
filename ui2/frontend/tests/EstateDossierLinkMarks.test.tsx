import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { buildEstateModel, type EstateInputs } from "../src/estate/estateModel";
import { EstateDossierScreen } from "../src/estate/EstateDossierScreen";
import type { EstateDataState } from "../src/estate/useEstateData";

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).
const NOW = new Date("2026-11-22T09:00:00.000Z");
const DEVICE_ID = "dev-tango-04";

const mockState = vi.hoisted(() => ({ model: null as unknown }));

vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: () => mockState.model,
}));

function buildFixture(): EstateDataState {
  const inputs: EstateInputs = {
    now: NOW,
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
  const model = buildEstateModel(inputs);
  return {
    loading: false,
    fatalError: null,
    overview: null,
    model,
  } as unknown as EstateDataState;
}

beforeEach(() => {
  mockState.model = buildFixture();
  // Every direct device read fails closed: the dossier shows UNKNOWN, which is not under test here.
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response("{}", { status: 500, headers: { "Content-Type": "application/json" } })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("Estate Dossier link marks (review fix 9, Dossier part)", () => {
  it("marks the policy row link as a standard-view link", async () => {
    render(<EstateDossierScreen deviceId={DEVICE_ID} />);
    await waitFor(() => expect(screen.getByText("FW-TANGO-04", { selector: "h1" })).toBeTruthy());

    const policy = await waitFor(() => {
      const anchors = Array.from(document.querySelectorAll("a")).filter((a) =>
        (a.getAttribute("href") ?? "").includes("screen=policy"),
      );
      expect(anchors.length).toBeGreaterThan(0);
      return anchors[0];
    });
    expect(policy.getAttribute("href")).toContain("screen=policy");
    expect(policy.textContent).toContain("(standard view)");
  });

  it("does not mark the lifecycle row link, which stays inside the Estate view", async () => {
    render(<EstateDossierScreen deviceId={DEVICE_ID} />);
    await waitFor(() => expect(screen.getByText("FW-TANGO-04", { selector: "h1" })).toBeTruthy());

    const lifecycle = await waitFor(() => {
      const anchors = Array.from(document.querySelectorAll("a")).filter((a) =>
        (a.getAttribute("href") ?? "").includes("view=lifecycle"),
      );
      expect(anchors.length).toBeGreaterThan(0);
      return anchors;
    });
    for (const anchor of lifecycle) {
      expect(anchor.textContent).not.toContain("(standard view)");
    }
  });

});
