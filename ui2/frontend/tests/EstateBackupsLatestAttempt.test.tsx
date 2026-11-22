import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { listLatestJobsByDevice, resetSharedApiCache } from "../src/auth/adminApi";

// Synthetic data only: FW-TANGO-04 style names and 192.0.2.x addresses. No real hosts, serials or users.
vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, listLatestJobsByDevice: vi.fn() };
});

const headers = { "X-Nexus-Masked": "true" };

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

const NOW = Date.now();
const TEN_HOURS_AGO = new Date(NOW - 10 * 3_600_000).toISOString();
const TWO_HOURS_AGO = new Date(NOW - 2 * 3_600_000).toISOString();
const FOUR_HOURS_AGO = new Date(NOW - 4 * 3_600_000).toISOString();

/** Synthetic unit i: FW-TANGO-NN with a stored archive (ten hours old) whose latest backup attempt FAILED. */
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

function stubFetch(units: ReturnType<typeof failedUnit>[]) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/devices") return jsonResponse(200, { devices: units.map((u) => u.device) });
    if (url === "/api/v2/overview") {
      return jsonResponse(200, {
        generated_at: new Date(NOW).toISOString(),
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

function stubLatestAttempts(units: ReturnType<typeof failedUnit>[]) {
  vi.mocked(listLatestJobsByDevice).mockResolvedValue({
    items: units.map((u) => u.job),
    total: units.length,
    page: 1,
    page_size: 400,
  } as never);
}

async function renderBackups() {
  window.history.replaceState(null, "", "/?screen=backups");
  render(<App />);
  return screen.findByTestId("estate-backups-screen");
}

beforeEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  window.localStorage.setItem("nx.view", "estate");
  vi.mocked(listLatestJobsByDevice).mockReset();
});
afterEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("Estate Backups: units whose latest attempt failed", () => {
  it("names the three failed units, each with its attempt", async () => {
    const units = [1, 2, 3].map(failedUnit);
    vi.stubGlobal("fetch", stubFetch(units));
    stubLatestAttempts(units);
    stubViewport(1280);

    const bk = await renderBackups();
    const card = await within(bk).findByTestId("latest-failed-attempts");
    expect(within(card).getByRole("heading", { name: "Latest attempt failed" })).toBeInTheDocument();
    for (const u of units) {
      const named = within(card).getByText(u.name);
      expect(named.closest("[data-failed-attempt-row]")).toHaveAttribute("data-failed-attempt-row", u.name);
    }
    expect(within(card).getAllByText(/^Failed/).length).toBeGreaterThanOrEqual(3);
    expect(within(card).queryByTestId("failed-attempts-more")).toBeNull();
  });

  it("an error from the attempts read shows UNKNOWN and never 'no failure'", async () => {
    const units = [1, 2].map(failedUnit);
    vi.stubGlobal("fetch", stubFetch(units));
    vi.mocked(listLatestJobsByDevice).mockRejectedValue(new Error("read failed"));
    stubViewport(1280);

    const bk = await renderBackups();
    const card = await within(bk).findByTestId("latest-failed-attempts");
    expect(await within(card).findByText("the latest attempts could not be read")).toBeInTheDocument();
    expect(within(card).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(bk).queryByText(/no failure/i)).toBeNull();
  });

  it("13 failed units show 12 rows and '+1 more'", async () => {
    const units = Array.from({ length: 13 }, (_, i) => failedUnit(i + 1));
    vi.stubGlobal("fetch", stubFetch(units));
    stubLatestAttempts(units);
    stubViewport(1280);

    const bk = await renderBackups();
    const card = await within(bk).findByTestId("latest-failed-attempts");
    expect(await within(card).findByTestId("failed-attempts-more")).toHaveTextContent("+1 more failed units");
    expect(card.querySelectorAll("[data-failed-attempt-row]")).toHaveLength(12);
  });
});
