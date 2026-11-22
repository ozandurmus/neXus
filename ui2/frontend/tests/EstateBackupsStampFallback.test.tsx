import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DeviceSummary, LifecycleFleet, OverviewView } from "../src/auth/adminApi";
import { buildEstateModel, type EstateModel } from "../src/estate/estateModel";

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).
// Guards the Backups stamp fallback: with an inventory read time but no backup
// evidence time, the Backups lens must say "evidence time unknown" and must
// never print the inventory read age.
const NOW = new Date("2026-11-22T12:00:00Z");
const READ_AT = "2026-11-22T10:00:00Z"; // inventory read, 2 h before NOW

const hookState: { model: EstateModel | null } = { model: null };

vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: () => ({
    loading: false,
    fatalError: null,
    overview: OVERVIEW_NO_BACKUP,
    devices: { state: "failed" },
    failover: { state: "failed" },
    backups: { state: "failed" },
    lifecycle: { state: "failed" },
    controls: { state: "failed" },
    changes: { state: "failed" },
    health: { state: "failed" },
    model: hookState.model,
    refresh: () => undefined,
  }),
}));
vi.mock("../src/estate/estatePlatformFindings", () => ({
  usePlatformFindings: () => ({ loading: false, items: [], refresh: () => undefined }),
}));
vi.mock("../src/estate/EstatePlatformCard", () => ({ EstatePlatformCard: () => null }));
vi.mock("../src/estate/EstateSecurityScanCard", () => ({ EstateSecurityScanCard: () => null }));
vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  const unavailable = () => Promise.reject(new Error("unavailable in test"));
  return {
    ...actual,
    getBackupPolicy: unavailable,
    getBackupStoreQuotas: unavailable,
    listJobs: unavailable,
    listLatestJobsByDevice: unavailable,
  };
});

const DEVICES = [
  {
    device_id: "dev-tango-04",
    vendor_hint: "check_point",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04",
    management_ip: "192.0.2.14",
    backup_target: true,
    inventory_collected_at: READ_AT,
  },
] as unknown as readonly DeviceSummary[];

// Overview with no backup evidence time: evidence.backup.at is null.
const OVERVIEW_NO_BACKUP = {
  generated_at: READ_AT,
  masked: true,
  denominators: { active_devices: 1, enrolled_devices: 1, gateways: 1, clusters: 0, clusters_enrolled: 0, backup_targets: 1 },
  evidence: {
    inventory: { at: READ_AT, state: "OK" },
    configuration: { at: READ_AT, state: "OK" },
    compliance: { at: READ_AT, state: "OK" },
    backup: { at: null, state: "UNKNOWN" },
    jobs: { at: null, state: "UNKNOWN" },
    platform_facts: { at: READ_AT, state: "OK" },
  },
  attention: {},
  exceptions: {},
  estate: { devices: [] },
} as unknown as OverviewView;

const LIFECYCLE = { as_of: READ_AT, devices: [] } as unknown as LifecycleFleet;

describe("Estate Backups stamp fallback (no inventory age under a Backups heading)", () => {
  beforeEach(() => {
    hookState.model = buildEstateModel({
      now: NOW,
      devices: { state: "ok", value: DEVICES },
      overview: { state: "ok", value: OVERVIEW_NO_BACKUP },
      failover: { state: "ok", value: [] },
      backups: { state: "ok", value: { backups: [] } },
      lifecycle: { state: "ok", value: LIFECYCLE },
    });
  });

  it("prints 'evidence time unknown' and never the inventory read age when no backup evidence time exists", async () => {
    const { EstateBackupsScreen } = await import("../src/estate/EstateBackupsScreen");
    const { container } = render(<EstateBackupsScreen />);
    expect(screen.getAllByText("evidence time unknown").length).toBeGreaterThan(0);
    const html = container.innerHTML;
    expect(html).not.toContain("2 h ago");
    expect(html).not.toContain("2 h");
  });
});
