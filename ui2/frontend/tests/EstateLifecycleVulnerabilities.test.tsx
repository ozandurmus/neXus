import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { LifecycleDevice, LifecycleVulnerabilities } from "../src/auth/adminApi";
import { EstateLifecycleScreen } from "../src/estate/EstateLifecycleScreen";
import { LIFECYCLE_DEVICE_CAP } from "../src/estate/estateLifecycleCatalogModel";
import { standardLifecycleHref } from "../src/estate/estateRouting";
import { vulnerabilitySummary } from "../src/screens/LifecycleVulnerabilitiesPanel";

const fleetState = vi.hoisted(() => ({ value: null as unknown }));

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  getLifecycleAdvisories: vi.fn(async () => ({ as_of: "2026-11-21T08:00:00.000Z", advisories: [] })),
  getLifecycleCatalog: vi.fn(async () => ({ entries: [], can_manage: false })),
  getLifecycleCatalogSources: vi.fn(async () => ({ sources: [] })),
}));

vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: () => ({ lifecycle: fleetState.value }),
}));

const AS_OF = "2026-11-21T08:00:00.000Z";
const LOADED = "2026-11-20T08:00:00.000Z";

function device(id: string, hostname: string | null, vulnerabilities?: LifecycleVulnerabilities | null): LifecycleDevice {
  return {
    device_id: id,
    hostname,
    cluster_member_ref: null,
    vendor: "SYNTHETIC",
    model: null,
    software_version: null,
    hardware: { status: "MATCHED" } as never,
    software: { status: "MATCHED" } as never,
    status: "OK",
    risk: "LOW",
    support_days: null,
    next_milestone: null,
    nearest_license: null,
    licenses: [],
    license_status: "OK",
    support_contract_status: "OK",
    vulnerabilities,
  } as LifecycleDevice;
}

const EVALUATED_AFFECTED: LifecycleVulnerabilities = {
  state: "EVALUATED",
  affected: 2,
  affected_exploited: 1,
  affected_critical_high: 1,
  not_evaluable: 1,
  compared: { source: "synthetic", advisories: 40, oldest: null, newest: null, loaded_at: LOADED, age_days: 1 },
};

const EVALUATED_CLEAN: LifecycleVulnerabilities = {
  state: "EVALUATED",
  affected: 0,
  compared: { source: "synthetic", advisories: 40, oldest: null, newest: null, loaded_at: LOADED, age_days: 1 },
};

beforeEach(() => {
  fleetState.value = { state: "failed" };
});

afterEach(() => {
  vi.clearAllMocks();
});

function setFleet(devices: LifecycleDevice[]) {
  fleetState.value = { state: "ok", value: { as_of: AS_OF, devices, summary: {} } };
}

describe("Estate lifecycle device vulnerability cell", () => {
  it("renders an evaluated device with the same text as vulnerabilitySummary", async () => {
    setFleet([device("dev-syn-1", "FW-TANGO-04", EVALUATED_AFFECTED), device("dev-syn-2", "FW-JULIET-06", EVALUATED_CLEAN)]);
    render(<EstateLifecycleScreen />);
    const rows = await screen.findAllByTestId("estate-device-vulnerability-row");
    const affectedRow = rows.find((r) => within(r).queryByText("FW-TANGO-04"));
    expect(affectedRow).toBeDefined();
    expect(within(affectedRow!).getByTestId("estate-device-vulnerability-cell").textContent).toBe(
      vulnerabilitySummary(EVALUATED_AFFECTED),
    );
    expect(vulnerabilitySummary(EVALUATED_AFFECTED)).toBe("2 affected · 1 exploited · 1 could not be judged");
    const cleanRow = rows.find((r) => within(r).queryByText("FW-JULIET-06"));
    expect(within(cleanRow!).getByTestId("estate-device-vulnerability-cell").textContent).toBe("None affected");
  });

  it("renders a missing or unevaluated device as UNKNOWN, never a zero count or OK", async () => {
    setFleet([
      device("dev-syn-3", "FW-BRAVO-02", null),
      device("dev-syn-4", "FW-ROMEO-01", { state: "NOT_EVALUATED", reason: "SOURCE_NEVER_LOADED" }),
      device("dev-syn-5", "FW-KILO-05", { state: "VERSION_UNKNOWN", reason: "VERSION_MISSING" }),
    ]);
    render(<EstateLifecycleScreen />);
    const rows = await screen.findAllByTestId("estate-device-vulnerability-row");
    expect(rows).toHaveLength(3);
    for (const row of rows) {
      expect(row.getAttribute("data-state")).toBe("unknown");
      const cell = within(row).getByTestId("estate-device-vulnerability-cell").textContent ?? "";
      expect(cell).toMatch(/^UNKNOWN/);
      expect(cell).not.toMatch(/\b0\b|None affected/);
      expect(within(row).getAllByText("UNKNOWN").length).toBeGreaterThanOrEqual(1);
    }
  });

  it("caps the device list and prints +N more", async () => {
    const many = Array.from({ length: LIFECYCLE_DEVICE_CAP + 5 }, (_, i) =>
      device(`dev-syn-${i}`, `FW-SYN-${String(i).padStart(3, "0")}`, EVALUATED_CLEAN),
    );
    setFleet(many);
    render(<EstateLifecycleScreen />);
    const rows = await screen.findAllByTestId("estate-device-vulnerability-row");
    expect(rows).toHaveLength(LIFECYCLE_DEVICE_CAP);
    expect(screen.getByTestId("estate-device-vulnerability-more").textContent).toBe("+5 more devices");
    expect(screen.getByTestId("estate-device-vulnerability-count").textContent).toBe(
      `${LIFECYCLE_DEVICE_CAP} of ${LIFECYCLE_DEVICE_CAP + 5} devices shown (+5 more)`,
    );
  });

  it("links only to the standard Lifecycle view and issues no write call", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    setFleet([device("dev-syn-6", "FW-TANGO-04", EVALUATED_AFFECTED)]);
    render(<EstateLifecycleScreen />);
    await screen.findAllByTestId("estate-device-vulnerability-row");
    const links = document.querySelectorAll(`a[href="${standardLifecycleHref()}"]`);
    expect(links.length).toBeGreaterThan(0);
    expect(standardLifecycleHref()).toBe("?screen=inventory&view=lifecycle&standard=1");
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });
});
