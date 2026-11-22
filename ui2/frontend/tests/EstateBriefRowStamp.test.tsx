import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DeviceSummary, LifecycleFleet, OverviewView } from "../src/auth/adminApi";
import { buildEstateModel, type EstateModel, type EstateUnit, type LensAnswer } from "../src/estate/estateModel";
import { relativeAge } from "../src/shell/time";

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).
const NOW = new Date();
const minutesAgo = (minutes: number) => new Date(NOW.getTime() - minutes * 60_000).toISOString();
const daysAgo = (days: number) => new Date(NOW.getTime() - days * 86_400_000).toISOString();

// The Brief reads its data through this hook; the test supplies a model directly.
const hookState: { model: EstateModel | null } = { model: null };

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

const DEVICES = [
  {
    device_id: "dev-tango-04",
    vendor_hint: "check_point",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04",
    management_ip: "192.0.2.14",
    backup_target: true,
    inventory_collected_at: minutesAgo(3),
  },
] as unknown as readonly DeviceSummary[];

const OVERVIEW = {
  generated_at: minutesAgo(1),
  masked: true,
  denominators: { active_devices: 1, enrolled_devices: 1, gateways: 1, clusters: 0, clusters_enrolled: 0, backup_targets: 1 },
  evidence: {
    inventory: { at: minutesAgo(3), state: "OK" },
    configuration: { at: minutesAgo(3), state: "OK" },
    compliance: { at: minutesAgo(3), state: "OK" },
    backup: { at: minutesAgo(3), state: "OK" },
    jobs: { at: minutesAgo(3), state: "OK" },
    platform_facts: { at: minutesAgo(3), state: "OK" },
  },
  attention: {},
  exceptions: {},
  estate: { devices: [] },
} as unknown as OverviewView;

const LIFECYCLE = { as_of: minutesAgo(3), devices: [] } as unknown as LifecycleFleet;

/** Builds a one-unit model whose worst lens answer is replaced by the given evidence time. */
function modelWithWorstLens(worstEvidenceAt: string | null): EstateModel {
  const base = buildEstateModel({
    now: NOW,
    devices: { state: "ok", value: DEVICES },
    overview: { state: "ok", value: OVERVIEW },
    failover: { state: "ok", value: [] },
    backups: { state: "ok", value: { backups: [] } },
    lifecycle: { state: "ok", value: LIFECYCLE },
  });
  const unit = base.units.find((u) => u.name === "FW-TANGO-04");
  if (!unit) throw new Error("fixture missing FW-TANGO-04");
  const worst: LensAnswer = {
    ...unit.lenses.worst,
    tileState: "watch",
    badgeState: "watch",
    badgeWord: "Review",
    summary: "Backup evidence needs review",
    evidenceAt: worstEvidenceAt,
    evidenceSource: "backup",
  };
  const patched: EstateUnit = {
    ...unit,
    lastReadAt: minutesAgo(3),
    lenses: { ...unit.lenses, worst },
  };
  return {
    ...base,
    units: [patched],
    byId: new Map([[patched.deviceId, patched]]),
    byName: new Map([[patched.name, patched]]),
  };
}

/** The row stamp is the first evidence stamp inside the unit row that carries the unit's name link. */
async function renderRowStamp(): Promise<HTMLElement> {
  const { EstateBriefScreen } = await import("../src/estate/EstateBriefScreen");
  render(<EstateBriefScreen />);
  const link = screen.getAllByRole("link", { name: "FW-TANGO-04" })[0];
  let node: HTMLElement | null = link;
  while (node && !node.querySelector("[data-evidence-stamp]")) node = node.parentElement;
  const stamp = node?.querySelector<HTMLElement>("[data-evidence-stamp]");
  if (!stamp) throw new Error("no row stamp rendered for FW-TANGO-04");
  return stamp;
}

describe("Estate Brief unit row stamp", () => {
  beforeEach(() => {
    hookState.model = null;
  });

  it("shows the worst lens evidence age (6 days, Backups), never the inventory read (3 minutes)", async () => {
    hookState.model = modelWithWorstLens(daysAgo(6));
    const stamp = await renderRowStamp();

    const sixDays = relativeAge(daysAgo(6), NOW).replace(/\s+ago$/, "");
    const threeMinutes = relativeAge(minutesAgo(3), NOW).replace(/\s+ago$/, "");
    expect(stamp.textContent).toContain(sixDays);
    expect(stamp.textContent).not.toContain(threeMinutes);
    expect(stamp.getAttribute("data-evidence-stamp")).toBe("a4");
  });

  it("shows 'evidence time unknown' when the worst lens has no evidence time, never the inventory age", async () => {
    hookState.model = modelWithWorstLens(null);
    const stamp = await renderRowStamp();

    expect(stamp.textContent).toContain("evidence time unknown");
    expect(stamp.textContent).not.toContain(relativeAge(minutesAgo(3), NOW).replace(/\s+ago$/, ""));
    expect(stamp.getAttribute("data-evidence-stamp")).toBe("a0");
  });
});
