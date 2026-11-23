import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { getLifecycleAdvisories, listLatestJobsByDevice, resetSharedApiCache, type LifecycleAdvisory } from "../src/auth/adminApi";
import { EstateBackupsDeviationsQuotas } from "../src/estate/EstateBackupsDeviationsQuotas";
import type { BackupDeviationOverview } from "../src/estate/estateBackupsParityModel";
import { EstateLifecycleScreen } from "../src/estate/EstateLifecycleScreen";
import { useEstateData } from "../src/estate/useEstateData";

// Synthetic data only: FW-TANGO-NN / FW-BRAVO-NN style names and 192.0.2.x addresses. No real hosts, serials or users.
vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  getLifecycleAdvisories: vi.fn(),
  listLatestJobsByDevice: vi.fn(),
}));
// The lifecycle screen's device-vulnerability card reads the lifecycle fleet the Estate provider loads; no provider here.
vi.mock("../src/estate/useEstateData", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/estate/useEstateData")>();
  return { ...actual, useEstateData: vi.fn(actual.useEstateData) };
});
// The real hook (inside App) is restored before every test; only the direct lifecycle render swaps in a stub.
const actualUseEstateData = (await vi.importActual<typeof import("../src/estate/useEstateData")>("../src/estate/useEstateData")).useEstateData;

const headers = { "X-Nexus-Masked": "true" };
const NOW = Date.now();
const TWO_HOURS_AGO = new Date(NOW - 2 * 3_600_000).toISOString();
const TEN_HOURS_AGO = new Date(NOW - 10 * 3_600_000).toISOString();
const FOUR_HOURS_AGO = new Date(NOW - 4 * 3_600_000).toISOString();
const NOW_DATE = new Date(NOW);
const EVIDENCE_AT = "2026-11-22T02:00:00Z";
const CHANGED_AT = "2026-11-22T06:00:00Z";
const AS_OF = "2026-11-21T08:00:00.000Z";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}

function stubViewport(widthPx: number) {
  Object.defineProperty(window, "innerWidth", { writable: true, configurable: true, value: widthPx });
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => {
      const minMatch = query.match(/\(min-width:\s*(\d+)px\)/);
      const maxMatch = query.match(/\(max-width:\s*(\d+(?:\.\d+)?)px\)/);
      const matches = minMatch ? widthPx >= Number(minMatch[1]) : maxMatch ? widthPx <= Number(maxMatch[1]) : false;
      return {
        matches, media: query, onchange: null,
        addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
      };
    }),
  );
}

beforeEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  window.localStorage.setItem("nx.view", "estate");
  vi.mocked(getLifecycleAdvisories).mockReset();
  vi.mocked(listLatestJobsByDevice).mockReset();
  vi.mocked(useEstateData).mockImplementation(actualUseEstateData);
});

afterEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

// ---------------------------------------------------------------------------
// A. Changes feed
// ---------------------------------------------------------------------------

function changeEvent(i: number) {
  return {
    key: `pan_cfg:${i}`,
    kind: "USER",
    source: "pan_config",
    time: new Date(NOW - (i + 1) * 60_000).toISOString(),
    time_basis: "UTC",
    device_time: "",
    vendor: "palo_alto",
    device_id: "dev-tango-04",
    device_name: "FW-TANGO-04",
    actor: `USR-SYN-${String(i).padStart(2, "0")}`,
    what: "edit system ntp-servers",
    detail: "client Web · result Submitted",
    tone: "NEUTRAL",
    diff: [{ op: "+", text: "ntp-server 192.0.2.5" }],
    links: { device: null, policy_id: null, rule_id: null, job_id: null, job_type: null },
  };
}

function stubChangesFetch(count: number) {
  const events = Array.from({ length: count }, (_, i) => changeEvent(i));
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/devices") {
      return jsonResponse(200, {
        devices: [{
          device_id: "dev-tango-04", vendor_hint: "palo_alto", role: "gateway", enrollment_state: "ENROLLED",
          hostname: "FW-TANGO-04", management_ip: "192.0.2.14", backup_target: true,
          inventory_collected_at: new Date(NOW - 3 * 60_000).toISOString(),
        }],
      });
    }
    if (url.startsWith("/api/v2/changes/feed")) {
      return jsonResponse(200, {
        events, next_cursor: "", range_start: "", range_end: "", kinds: ["USER"],
        vendor: null, device_id: null, masked: true, ordering_note: "", coverage: [],
      });
    }
    return jsonResponse(500, { error: "DOWNSTREAM_ERROR" });
  });
}

describe("Estate Changes feed: capped list prints the true remainder", () => {
  it("52 synthetic change sets with a cap of 40 print +12 more change sets (total minus shown)", async () => {
    window.history.replaceState(null, "", "/?screen=changes");
    vi.stubGlobal("fetch", stubChangesFetch(52));
    stubViewport(1280);
    render(<App />);
    const changes = await screen.findByTestId("estate-changes-screen");
    const footer = await within(changes).findByText(/\+\d+ more change sets/);
    expect(footer).toHaveTextContent("+12 more change sets");
  });

  it("40 synthetic change sets fit the cap and print no remainder", async () => {
    window.history.replaceState(null, "", "/?screen=changes");
    vi.stubGlobal("fetch", stubChangesFetch(40));
    stubViewport(1280);
    render(<App />);
    const changes = await screen.findByTestId("estate-changes-screen");
    // Wait for the feed to render before asserting the absence of a footer.
    expect((await within(changes).findAllByText(/USR-SYN-00/)).length).toBeGreaterThan(0);
    expect(within(changes).queryByText(/\+\d+ more change sets/)).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// B. Backups: failed-units list and major deviations
// ---------------------------------------------------------------------------

function failedUnit(i: number) {
  const id = `dev-tango-${String(i).padStart(2, "0")}`;
  const name = `FW-TANGO-${String(i).padStart(2, "0")}`;
  return {
    id,
    name,
    device: {
      device_id: id, vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
      hostname: name, management_ip: `192.0.2.${i + 20}`, backup_target: true,
      inventory_collected_at: TWO_HOURS_AGO,
      latest_job_type: "backup", latest_job_state: "FAILED", latest_job_terminal_reason: "Credential rejected by device",
    },
    artefact: {
      artefact_id: `bk-${id}`, device_id: id, collected_at: TEN_HOURS_AGO, size_bytes: 118 * 1024 * 1024,
      digest_prefix: "55667788", validation_level: "V3", deviation_state: "unchanged",
    },
    overview: {
      device_id: id, hostname: name, vendor: "check_point", model: "26000", condition: "critical",
      no_archive: false, critical_fail: 1, last_read_at: TWO_HOURS_AGO, backup_target: true, last_backup_at: TEN_HOURS_AGO,
    },
    job: {
      job_id: `job-${id}`, job_type: "backup", state: "FAILED", terminal_reason: "Credential rejected by device",
      submitted_at: FOUR_HOURS_AGO, target_device_id: id, has_transcript: false,
    },
  };
}

function stubBackupsFetch(units: ReturnType<typeof failedUnit>[]) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/devices") return jsonResponse(200, { devices: units.map((u) => u.device) });
    if (url === "/api/v2/overview") {
      return jsonResponse(200, {
        generated_at: NOW_DATE.toISOString(),
        masked: true,
        denominators: { active_devices: units.length, enrolled_devices: units.length, gateways: units.length, clusters: 0, backup_targets: units.length },
        evidence: {
          inventory: { at: TWO_HOURS_AGO, state: "OK" },
          configuration: { at: TWO_HOURS_AGO, state: "OK" },
          compliance: { at: TEN_HOURS_AGO, state: "OK" },
          backup: { at: TEN_HOURS_AGO, state: "OK" },
          jobs: { at: TWO_HOURS_AGO, state: "OK" },
          platform_facts: { at: TWO_HOURS_AGO, state: "OK" },
        },
        estate: {
          compliance_state: "OK",
          no_archive: 0,
          counts: { critical: units.length, ageing: 0, partly_assessed: 0, not_assessed: 0, clear: 0 },
          facts: { critical: { k: units.length, n: units.length }, backups: { a: units.length, b: units.length }, evidence: { r: units.length, d: units.length, never: 0 } },
          devices: units.map((u) => u.overview),
        },
      });
    }
    if (url === "/backups") return jsonResponse(200, { backups: units.map((u) => u.artefact) });
    return jsonResponse(500, { error: "DOWNSTREAM_ERROR" });
  });
}

describe("Estate Backups: failed-units list prints the true remainder", () => {
  it("15 failed units show 12 rows and print +3 more failed units (total minus shown)", async () => {
    window.history.replaceState(null, "", "/?screen=backups");
    const units = Array.from({ length: 15 }, (_, i) => failedUnit(i + 1));
    vi.stubGlobal("fetch", stubBackupsFetch(units));
    vi.mocked(listLatestJobsByDevice).mockResolvedValue({ items: units.map((u) => u.job), total: units.length, page: 1, page_size: 400 } as never);
    stubViewport(1280);
    render(<App />);
    const bk = await screen.findByTestId("estate-backups-screen");
    const card = await within(bk).findByTestId("latest-failed-attempts");
    expect(await within(card).findByTestId("failed-attempts-more")).toHaveTextContent("+3 more failed units");
    expect(card.querySelectorAll("[data-failed-attempt-row]")).toHaveLength(12);
  });

  it("an unreadable attempt read keeps the true count and labels the attempts UNKNOWN, never a number", async () => {
    window.history.replaceState(null, "", "/?screen=backups");
    const units = Array.from({ length: 14 }, (_, i) => failedUnit(i + 1));
    vi.stubGlobal("fetch", stubBackupsFetch(units));
    vi.mocked(listLatestJobsByDevice).mockRejectedValue(new Error("synthetic read failure"));
    stubViewport(1280);
    render(<App />);
    const bk = await screen.findByTestId("estate-backups-screen");
    const card = await within(bk).findByTestId("latest-failed-attempts");
    expect(await within(card).findByText("the latest attempts could not be read")).toBeInTheDocument();
    expect(within(card).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(card).getByTestId("failed-attempts-more")).toHaveTextContent("+2 more failed units");
  });
});

function majorGroup(i: number) {
  const num = String(i + 1).padStart(2, "0");
  return {
    device_id: `dev-tango-${num}`,
    hostname: `FW-TANGO-${num}`,
    vendor: "check_point",
    category: "routing",
    changed_at: CHANGED_AT,
    count: 1,
    sections: ["static-route"],
  };
}

describe("Estate Backups: major deviations list prints the true remainder or UNKNOWN", () => {
  it("13 major deviation groups show 10 and print +3 more major deviation groups", () => {
    const deviations: BackupDeviationOverview = {
      active_major_deviations: Array.from({ length: 13 }, (_, i) => majorGroup(i)),
      size_anomalies: [],
      total_deviations_checked: 13,
      devices_known: 13,
      window_days: 7,
    };
    render(
      <EstateBackupsDeviationsQuotas
        deviations={deviations}
        backupsState="ok"
        quotas={{ state: "ok", value: { store_mounted: true, stores: [] } }}
        backupEvidenceAt={EVIDENCE_AT}
        now={NOW_DATE}
      />,
    );
    const devCard = screen.getByTestId("estate-backups-deviations-card");
    expect(screen.getByTestId("backup-major-deviations-more")).toHaveTextContent("+3 more major deviation groups");
    expect(devCard.querySelectorAll("[data-major-deviation-group]")).toHaveLength(10);
  });

  it("no deviation overview means UNKNOWN wording and no numeric remainder footer", () => {
    render(
      <EstateBackupsDeviationsQuotas
        deviations={null}
        backupsState="failed"
        quotas={{ state: "restricted" }}
        backupEvidenceAt={null}
        now={NOW_DATE}
      />,
    );
    const devCard = screen.getByTestId("estate-backups-deviations-card");
    expect(within(devCard).getByText("UNKNOWN")).toBeInTheDocument();
    expect(screen.queryByTestId("backup-major-deviations-more")).toBeNull();
    expect(within(devCard).queryByText(/\+\d+ more/)).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// C. Lifecycle: advisories over the cap, and a missing pending count
// ---------------------------------------------------------------------------

function advisory(i: number, overrides: Partial<LifecycleAdvisory> = {}): LifecycleAdvisory {
  return {
    advisory_id: `paloalto:SYN-${String(i).padStart(4, "0")}`,
    source: "paloalto",
    title: `Synthetic advisory ${i} (FW-TANGO-04)`,
    severity: "HIGH",
    cvss_score: 7.5,
    cve_ids: [],
    exploited: false,
    exploited_since: null,
    published_on: "2026-11-01",
    updated_on: "2026-11-10",
    source_url: null,
    parse_state: "PARSED",
    parse_note: null,
    devices: { affected: 1, fixed: 0, not_affected: 0, not_evaluable: 0, pending: 0 },
    ...overrides,
  };
}

describe("EstateLifecycleScreen: capped advisories and the pending count", () => {
  beforeEach(() => {
    // The device-vulnerability card reads the lifecycle fleet the Estate provider loads; no provider is mounted here.
    vi.mocked(useEstateData).mockImplementation(() => ({ lifecycle: { state: "failed" } }) as never);
  });

  it("53 synthetic advisories with a cap of 50 print +3 more advisories", async () => {
    const advisories = Array.from({ length: 53 }, (_, i) => advisory(i));
    vi.mocked(getLifecycleAdvisories).mockResolvedValue({ as_of: AS_OF, advisories });
    render(<EstateLifecycleScreen />);
    const screenEl = await screen.findByTestId("estate-lifecycle-screen");
    expect(await within(screenEl).findByText(/\+3 more advisories/)).toBeInTheDocument();
    expect(within(screenEl).getAllByTestId("estate-lifecycle-row")).toHaveLength(50);
  });

  it("an advisory whose pending count is missing reads UNKNOWN, never OK", async () => {
    const missing = { affected: 2, fixed: 0, not_affected: 0, not_evaluable: 0 } as unknown as LifecycleAdvisory["devices"];
    vi.mocked(getLifecycleAdvisories).mockResolvedValue({
      as_of: AS_OF,
      advisories: [advisory(1, { devices: missing })],
    });
    render(<EstateLifecycleScreen />);
    const row = await screen.findByTestId("estate-lifecycle-row");
    // The row is UNKNOWN (never OK) because the pending tally is missing; the affected figure is kept as given.
    expect(within(row).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(row).queryByText("OK")).toBeNull();
    expect(within(row).getByTestId("estate-lifecycle-count")).toHaveTextContent("2 devices affected");
  });
});
