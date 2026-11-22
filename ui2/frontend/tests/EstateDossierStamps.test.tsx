import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DeviceSummary } from "../src/auth/adminApi";
import { buildEstateModel, type EstateModel } from "../src/estate/estateModel";

const fixture = vi.hoisted(() => ({ model: null as EstateModel | null }));

vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: () => ({
    loading: false,
    fatalError: null,
    overview: null,
    devices: { state: "failed" },
    failover: { state: "failed" },
    backups: { state: "failed" },
    lifecycle: { state: "failed" },
    controls: { state: "failed" },
    changes: { state: "failed" },
    model: fixture.model,
    refresh: () => undefined,
  }),
}));

vi.mock("../src/estate/EstateDossierLogsBlock", () => ({
  EstateDossierLogsBlock: () => null,
  loadDeviceLogsReads: () => Promise.reject(new Error("synthetic logs failure")),
}));

const backupsMock = vi.hoisted(() => ({ list: vi.fn() }));
const inventoryMock = vi.hoisted(() => ({ read: vi.fn() }));

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  const failed = () => Promise.reject(new Error("synthetic read failure"));
  return {
    ...actual,
    getDeviceInventory: inventoryMock.read,
    getDeviceCompliance: failed,
    getDeviceConfiguration: failed,
    getDeviceLifecycle: failed,
    getDeviceSoftwareHistory: failed,
    getDeviceWorkspace: failed,
    getManagementTree: failed,
    listDeviceBackups: backupsMock.list,
    listStaticRoutePreviews: () => Promise.resolve({ targets: [] }),
    listFmgDiagnosticTargets: () => Promise.resolve({ targets: [], canExecute: false }),
  };
});

import { EstateDossierScreen } from "../src/estate/EstateDossierScreen";

// Synthetic device only (FW-TANGO-04 style name, 192.0.2.x address).
const DEVICE_ID = "dev-tango-04";
const MINUTE_MS = 60_000;
const DAY_MS = 86_400_000;

function devices(inventoryReadAt: string): readonly DeviceSummary[] {
  return [
    {
      device_id: DEVICE_ID,
      vendor_hint: "check_point",
      role: "gateway",
      enrollment_state: "ENROLLED",
      hostname: "FW-TANGO-04",
      model: "26000",
      software_version: "R81.20",
      ha_role: null,
      cluster_member_ref: null,
      management_ip: "192.0.2.14",
      backup_target: false,
      inventory_collected_at: inventoryReadAt,
    },
  ];
}

function modelWithInventoryReadAt(inventoryReadAt: string): EstateModel {
  return buildEstateModel({
    now: new Date(),
    devices: { state: "ok", value: devices(inventoryReadAt) },
    overview: { state: "failed" },
    failover: { state: "failed" },
    backups: { state: "failed" },
    lifecycle: { state: "failed" },
  });
}

/** Evidence stamps whose visible source label is `source` (exact match). */
function stampsLabelled(source: string): HTMLElement[] {
  return screen
    .queryAllByText(source, { exact: true })
    .map((el) => el.closest("[data-evidence-stamp]"))
    .filter((el): el is HTMLElement => el instanceof HTMLElement);
}

describe("EstateDossierScreen evidence stamps (no inventory age under non-inventory headings)", () => {
  beforeEach(() => {
    backupsMock.list.mockReset();
    backupsMock.list.mockRejectedValue(new Error("synthetic backups failure"));
    inventoryMock.read.mockReset();
    inventoryMock.read.mockRejectedValue(new Error("synthetic inventory failure"));
  });

  it("inventory read 3 min old, no lens evidence: events and lens stamps say unknown, not the inventory age", async () => {
    const inventoryReadAt = new Date(Date.now() - 3 * MINUTE_MS).toISOString();
    fixture.model = modelWithInventoryReadAt(inventoryReadAt);

    render(<EstateDossierScreen deviceId={DEVICE_ID} />);

    // Events section stamp: no stored event to show, so the stamp must not borrow the inventory read.
    const eventsStamp = (await screen.findByText("newest stored event")).closest("[data-evidence-stamp]");
    expect(eventsStamp).toBeTruthy();
    expect(eventsStamp?.textContent ?? "").toContain("evidence time unknown");
    expect(eventsStamp?.textContent ?? "").not.toMatch(/3 min/);

    // Lifecycle lens (support row) has no own evidence time: unknown, never the inventory age.
    await waitFor(() => expect(stampsLabelled("lifecycle").length).toBeGreaterThan(0));
    for (const el of stampsLabelled("lifecycle")) {
      expect(el.textContent ?? "").toContain("evidence time unknown");
      expect(el.textContent ?? "").not.toMatch(/3 min/);
    }

    // Every stamp that is not labelled as the inventory read must avoid the inventory age.
    const nonInventory = Array.from(document.querySelectorAll<HTMLElement>("[data-evidence-stamp]")).filter(
      (el) => !(el.textContent ?? "").includes("inventory"),
    );
    expect(nonInventory.length).toBeGreaterThan(0);
    for (const el of nonInventory) {
      expect(el.textContent ?? "").not.toMatch(/3 min/);
    }
  });

  it("stored backup 6 days old: events stamp shows that backup's evidence age, not the 3 min inventory read", async () => {
    const inventoryReadAt = new Date(Date.now() - 3 * MINUTE_MS).toISOString();
    const backupAt = new Date(Date.now() - 6 * DAY_MS).toISOString();
    fixture.model = modelWithInventoryReadAt(inventoryReadAt);
    backupsMock.list.mockResolvedValue({
      backups: [
        {
          artefact_id: "art-synthetic-1",
          device_id: DEVICE_ID,
          collected_at: backupAt,
          size_bytes: 2048,
          deviation_state: "unchanged",
        },
      ],
    });

    render(<EstateDossierScreen deviceId={DEVICE_ID} />);

    const eventsStamp = (await screen.findByText("newest stored event")).closest("[data-evidence-stamp]");
    await waitFor(() => expect(eventsStamp?.textContent ?? "").toMatch(/6 d/));
    expect(eventsStamp?.textContent ?? "").not.toMatch(/3 min/);
    expect(eventsStamp?.textContent ?? "").not.toContain("evidence time unknown");
  });

  it("device inventory read 3 min old is the only dated item: events stamp says unknown, never the inventory age", async () => {
    const inventoryReadAt = new Date(Date.now() - 3 * MINUTE_MS).toISOString();
    fixture.model = modelWithInventoryReadAt(inventoryReadAt);
    inventoryMock.read.mockResolvedValue({
      device_id: DEVICE_ID,
      collected_at: inventoryReadAt,
      job: null,
      contexts: [],
    });

    render(<EstateDossierScreen deviceId={DEVICE_ID} />);

    const eventsStamp = (await screen.findByText("newest stored event")).closest("[data-evidence-stamp]");
    await waitFor(() => expect(inventoryMock.read).toHaveBeenCalled());
    await waitFor(() => expect(eventsStamp?.textContent ?? "").toContain("evidence time unknown"));
    expect(eventsStamp?.textContent ?? "").not.toMatch(/3 min/);
  });
});
