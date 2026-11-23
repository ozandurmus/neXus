import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";
import { FLEET_OVERVIEW_STAMP_SOURCE } from "../src/estate/EstateComplianceScreen";

// Synthetic data only (FW-TANGO-04 style names, 192.0.2.x / 198.51.100.x addresses).
const headers = { "X-Nexus-Masked": "true" };

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

function stubViewport(widthPx: number) {
  Object.defineProperty(window, "innerWidth", { writable: true, configurable: true, value: widthPx });
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => {
      const minMatch = query.match(/\(min-width:\s*(\d+)px\)/);
      const maxMatch = query.match(/\(max-width:\s*(\d+(?:\.\d+)?)px\)/);
      const matches = minMatch
        ? widthPx >= Number(minMatch[1])
        : maxMatch
          ? widthPx <= Number(maxMatch[1])
          : false;
      return {
        matches,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      };
    }),
  );
}

function stubComplianceFetch(options: { readonly complianceAt: string | null }) {
  const nowMs = Date.now();
  const nowIso = new Date(nowMs).toISOString();
  const twoHoursAgo = new Date(nowMs - 2 * 3_600_000).toISOString();

  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);

    if (url === "/devices") {
      return jsonResponse(200, {
        devices: [
          {
            device_id: "dev-tango-04",
            vendor_hint: "check_point",
            role: "gateway",
            enrollment_state: "ENROLLED",
            hostname: "FW-TANGO-04",
            model: "26000",
            software_version: "R81.20",
            ha_role: "ACTIVE",
            cluster_member_ref: "CLS-ROMEO-01",
            management_ip: "192.0.2.14",
            backup_target: true,
            inventory_collected_at: twoHoursAgo,
          },
          {
            device_id: "dev-sierra-02",
            vendor_hint: "palo_alto",
            role: "gateway",
            enrollment_state: "ENROLLED",
            hostname: "FW-SIERRA-02",
            model: "PA-5420",
            software_version: "11.1.10-h7",
            ha_role: "PASSIVE",
            cluster_member_ref: "CLS-SIERRA-02",
            management_ip: "198.51.100.22",
            backup_target: true,
            inventory_collected_at: twoHoursAgo,
          },
          {
            device_id: "dev-juliet-06",
            vendor_hint: "cisco_asa",
            role: "gateway",
            enrollment_state: "ENROLLED",
            hostname: "FW-JULIET-06",
            model: "FPR-2130",
            software_version: "9.20(3)",
            ha_role: null,
            cluster_member_ref: null,
            management_ip: "198.51.100.36",
            backup_target: true,
            inventory_collected_at: twoHoursAgo,
          },
        ],
      });
    }

    if (url === "/api/v2/overview") {
      return jsonResponse(200, {
        generated_at: nowIso,
        masked: true,
        denominators: { active_devices: 3, enrolled_devices: 3, gateways: 3, clusters: 2, backup_targets: 3 },
        evidence: {
          inventory: { at: twoHoursAgo, state: "OK" },
          configuration: { at: twoHoursAgo, state: "OK" },
          compliance: {
            at: options.complianceAt,
            state: options.complianceAt ? "OK" : "UNKNOWN",
          },
          backup: { at: twoHoursAgo, state: "OK" },
          jobs: { at: twoHoursAgo, state: "OK" },
          platform_facts: { at: twoHoursAgo, state: "OK" },
        },
        attention: {},
        exceptions: {},
        inventory_age: { lt24h: 3, h24_72: 0, gt72h: 0, never: 0, of: 3, state: "OK" },
        compliance: {
          state: "OK",
          evaluated: 2,
          of_firewalls: 3,
          coverage_pct: 66.7,
          assured_pct: 33.3,
          observed_pct: 50,
          critical_deficiencies: 1,
          data_gaps: 1,
          frameworks: [
            { name: "CIS Benchmark", pass: 1, fail: 1, unavailable: 1, total: 3, score_pct: 50 },
          ],
        },
        platform: { devices: 3, check_point: 1, palo_alto: 1, clusters: 2, hotfix_levels: [], evidence_at: twoHoursAgo },
        policy_install: { state: "OK", of: 3, today: 2, yesterday: 0, days_2_7: 0, older: 0, unknown: 1, last_read_at: twoHoursAgo },
        nexus: { completed_24h: 10, running: 0, oldest_running_submitted_at: null, last_inventory: {} },
        estate: {
          compliance_state: "OK",
          no_archive: 0,
          counts: { critical: 1, ageing: 0, partly_assessed: 1, not_assessed: 1, clear: 0 },
          facts: { critical: { k: 1, n: 3 }, backups: { a: 3, b: 3 }, evidence: { r: 3, d: 3, never: 0 } },
          devices: [
            {
              device_id: "dev-tango-04",
              hostname: "FW-TANGO-04",
              vendor: "check_point",
              model: "26000",
              condition: "critical",
              no_archive: false,
              critical_fail: 1,
              last_read_at: twoHoursAgo,
              backup_target: true,
              last_backup_at: twoHoursAgo,
            },
            {
              device_id: "dev-sierra-02",
              hostname: "FW-SIERRA-02",
              vendor: "palo_alto",
              model: "PA-5420",
              condition: "partly_assessed",
              no_archive: false,
              critical_fail: 0,
              last_read_at: twoHoursAgo,
              backup_target: true,
              last_backup_at: twoHoursAgo,
            },
            {
              device_id: "dev-juliet-06",
              hostname: "FW-JULIET-06",
              vendor: "cisco_asa",
              model: "FPR-2130",
              condition: "not_assessed",
              no_archive: false,
              critical_fail: null,
              last_read_at: twoHoursAgo,
              backup_target: true,
              last_backup_at: twoHoursAgo,
            },
          ],
        },
      });
    }

    if (url === "/compliance/overview") {
      return jsonResponse(200, {
        total_firewalls: 3,
        evaluated_firewalls: 2,
        assured_compliance_pct: 33.3,
        evidence_coverage_pct: 66.7,
        observed_compliance_pct: 50,
        critical_deficiencies: 1,
        data_gaps: 1,
        control_checks: 3,
        frameworks: [
          {
            framework: "CIS Benchmark",
            score_pct: 50,
            total_controls: 3,
            pass_count: 1,
            fail_count: 1,
            data_unavailable_count: 1,
          },
        ],
      });
    }

    if (url === "/compliance/controls") {
      return jsonResponse(200, {
        total_controls: 2,
        controls: [
          {
            control_id: "CIS-5.2.1",
            title: "SSH protocol version 2 enforced",
            description: "Allow SSHv2 only",
            family: "IA",
            severity: "CRITICAL",
            category: "Authentication",
            frameworks: [{ framework: "CIS Benchmark", reference: "5.2.1" }],
            status: "FAIL",
            compliance_pct: 50,
            target_device_count: 2,
            pass_count: 1,
            fail_count: 1,
            data_unavailable_count: 0,
            affected_devices: ["FW-TANGO-04"],
            vendors: ["check_point"],
          },
          {
            control_id: "AU-9.1",
            title: "Remote syslog over TLS",
            description: "Encrypt remote logs",
            family: "AU",
            severity: "HIGH",
            category: "Audit",
            frameworks: [{ framework: "CIS Benchmark", reference: "4.1.2" }],
            status: "DATA_UNAVAILABLE",
            compliance_pct: 0,
            target_device_count: 1,
            pass_count: 0,
            fail_count: 0,
            data_unavailable_count: 1,
            missing_reason: "Command not in collection scope yet",
            affected_devices: [],
            vendors: ["cisco_asa"],
          },
        ],
      });
    }

    return jsonResponse(200, {});
  });
}

// A bare age string without any source label (e.g. "10 h", "3 min", "just now").
const BARE_AGE_RE = /^(just now|\d+\s*(min|h|d)(\s+ago)?)$/i;

beforeEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  window.localStorage.setItem("nx.view", "estate");
  window.history.replaceState(null, "", "/?screen=compliance");
});

afterEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("EstateComplianceScreen fleet-overview stamp label", () => {
  it("labels every stamp with the fleet overview read source and never renders a bare age on any control row", async () => {
    const tenHoursAgo = new Date(Date.now() - 10 * 3_600_000).toISOString();
    vi.stubGlobal("fetch", stubComplianceFetch({ complianceAt: tenHoursAgo }));
    stubViewport(1280);
    render(<App />);

    const comp = await screen.findByTestId("estate-compliance-screen");
    await within(comp).findByTestId("compliance-honest-bar");

    const allStamps = Array.from(comp.querySelectorAll<HTMLElement>("[data-evidence-stamp]"));
    expect(allStamps.length).toBeGreaterThanOrEqual(8);
    for (const stamp of allStamps) {
      const text = (stamp.textContent ?? "").trim();
      expect(text).toContain(FLEET_OVERVIEW_STAMP_SOURCE);
      expect(text).toMatch(/10\s*h/);
      // Never borrows the 2-hour device inventory read age.
      expect(text).not.toMatch(/2\s*h/);
      expect(BARE_AGE_RE.test(text)).toBe(false);
    }

    const controlRows = Array.from(comp.querySelectorAll<HTMLElement>("[data-remediation-row]"));
    expect(controlRows).toHaveLength(2);
    for (const row of controlRows) {
      const rowStamps = Array.from(row.querySelectorAll<HTMLElement>("[data-evidence-stamp]"));
      expect(rowStamps.length).toBeGreaterThan(0);
      for (const stamp of rowStamps) {
        const text = (stamp.textContent ?? "").trim();
        expect(text).toContain(FLEET_OVERVIEW_STAMP_SOURCE);
        expect(BARE_AGE_RE.test(text)).toBe(false);
      }
    }

    // Verdict logic is unchanged: critical failure is Act ('act'), un-evidenced control is UNKNOWN ('unk'),
    // and partly-assessed / not-assessed firewalls (FW-SIERRA-02, FW-JULIET-06) remain listed as un-assessed.
    const critRow = comp.querySelector('[data-remediation-row="CIS-5.2.1"]') as HTMLElement;
    expect(critRow.querySelector('[data-state-badge="act"]')).not.toBeNull();
    const unkRow = comp.querySelector('[data-remediation-row="AU-9.1"]') as HTMLElement;
    expect(unkRow.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(within(comp).getByText("FW-SIERRA-02")).toBeInTheDocument();
    expect(within(comp).getByText("FW-JULIET-06")).toBeInTheDocument();
  });

  it("renders UNKNOWN (never OK) with the fleet-overview label when the fleet compliance time is absent", async () => {
    vi.stubGlobal("fetch", stubComplianceFetch({ complianceAt: null }));
    stubViewport(1280);
    render(<App />);

    const comp = await screen.findByTestId("estate-compliance-screen");
    await within(comp).findByTestId("compliance-honest-bar");

    const allStamps = Array.from(comp.querySelectorAll<HTMLElement>("[data-evidence-stamp]"));
    expect(allStamps.length).toBeGreaterThanOrEqual(8);
    for (const stamp of allStamps) {
      const text = (stamp.textContent ?? "").trim();
      expect(stamp.getAttribute("data-evidence-stamp")).toBe("a0");
      expect(text).toContain("UNKNOWN");
      expect(text).toContain(FLEET_OVERVIEW_STAMP_SOURCE);
      expect(text).not.toMatch(/\bOK\b|\bFine\b/);
      // Never falls back to the 2-hour device inventory read age when compliance time is absent.
      expect(text).not.toMatch(/2\s*h/);
    }

    const controlRows = Array.from(comp.querySelectorAll<HTMLElement>("[data-remediation-row]"));
    expect(controlRows).toHaveLength(2);
    for (const row of controlRows) {
      const rowStamps = Array.from(row.querySelectorAll<HTMLElement>("[data-evidence-stamp]"));
      expect(rowStamps.length).toBeGreaterThan(0);
      for (const stamp of rowStamps) {
        const text = (stamp.textContent ?? "").trim();
        expect(stamp.getAttribute("data-evidence-stamp")).toBe("a0");
        expect(text).toContain("UNKNOWN");
        expect(text).toContain(FLEET_OVERVIEW_STAMP_SOURCE);
        expect(BARE_AGE_RE.test(text)).toBe(false);
      }
    }
  });
});
