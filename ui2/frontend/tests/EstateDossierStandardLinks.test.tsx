import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { buildEstateModel, type EstateInputs } from "../src/estate/estateModel";
import { leavesEstateView } from "../src/estate/estateRouting";
import { EstateDossierScreen } from "../src/estate/EstateDossierScreen";
import type { EstateDataState } from "../src/estate/useEstateData";

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).
// Review fix 9 (Dossier part): every Dossier link that leaves the Estate view carries "(standard view)";
// every link that stays inside the Estate view carries no marker. Assertion-only: this pins current marking.
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
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response("{}", { status: 500, headers: { "Content-Type": "application/json" } })),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/** Every in-page link (relative `?...` address) currently rendered in the Dossier. */
function dossierLinks(): HTMLAnchorElement[] {
  return Array.from(document.querySelectorAll("a")).filter((a) => (a.getAttribute("href") ?? "").startsWith("?"));
}

describe("Estate Dossier standard-view link marks (review fix 9, Dossier part)", () => {
  it("marks every rendered link that leaves the Estate view and no link that stays inside it", async () => {
    render(<EstateDossierScreen deviceId={DEVICE_ID} />);
    await waitFor(() => expect(screen.getByText("FW-TANGO-04", { selector: "h1" })).toBeTruthy());

    // Open the tabs that carry lens and standard-view links, so their links are rendered and checked too.
    for (const name of [/^Backups/, /^Controls$/]) {
      const tab = screen.queryByRole("tab", { name });
      if (tab) fireEvent.click(tab);
    }

    const links = dossierLinks();
    expect(links.length).toBeGreaterThan(0);

    const leaving = links.filter((a) => leavesEstateView(a.getAttribute("href") ?? ""));
    const inside = links.filter((a) => !leavesEstateView(a.getAttribute("href") ?? ""));
    expect(leaving.length).toBeGreaterThan(0);
    expect(inside.length).toBeGreaterThan(0);

    for (const a of leaving) {
      expect(a.textContent ?? "", `href ${a.getAttribute("href")}`).toContain("(standard view)");
    }
    for (const a of inside) {
      expect(a.textContent ?? "", `href ${a.getAttribute("href")}`).not.toContain("(standard view)");
    }
  });

  it("marks the Backups standard-view link and leaves the Backups lens link unmarked", async () => {
    render(<EstateDossierScreen deviceId={DEVICE_ID} />);
    await waitFor(() => expect(screen.getByText("FW-TANGO-04", { selector: "h1" })).toBeTruthy());
    const backupsTab = screen.queryByRole("tab", { name: /^Backups/ });
    if (backupsTab) fireEvent.click(backupsTab);

    const standard = dossierLinks().filter((a) => (a.getAttribute("href") ?? "").includes("screen=backups") && a.getAttribute("href")?.includes("standard=1"));
    const lens = dossierLinks().filter((a) => (a.getAttribute("href") ?? "").includes("screen=backups") && !a.getAttribute("href")?.includes("standard=1"));
    for (const a of standard) expect(a.textContent).toContain("(standard view)");
    for (const a of lens) expect(a.textContent).not.toContain("(standard view)");
  });
});
