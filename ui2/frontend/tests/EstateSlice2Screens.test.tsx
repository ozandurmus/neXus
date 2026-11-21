import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";

function jsonResponse(status: number, body: unknown, extraHeaders?: Record<string, string>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...extraHeaders },
  });
}

function stubViewport(widthPx: number, dark = false) {
  Object.defineProperty(window, "innerWidth", { writable: true, configurable: true, value: widthPx });
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => {
      const minMatch = query.match(/\(min-width:\s*(\d+)px\)/);
      const maxMatch = query.match(/\(max-width:\s*(\d+(?:\.\d+)?)px\)/);
      const darkMatch = query.includes("prefers-color-scheme: dark");
      let matches = false;
      if (minMatch) matches = widthPx >= Number(minMatch[1]);
      else if (maxMatch) matches = widthPx <= Number(maxMatch[1]);
      else if (darkMatch) matches = dark;
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

function stubSlice2ApiFetch() {
  const nowMs = Date.now();
  const nowIso = new Date(nowMs).toISOString();
  const twoHoursAgo = new Date(nowMs - 2 * 3_600_000).toISOString();
  const tenHoursAgo = new Date(nowMs - 10 * 3_600_000).toISOString();
  const yesterdayNight = new Date(nowMs - 34 * 3_600_000).toISOString();
  const twoDaysAgo = new Date(nowMs - 48 * 3_600_000).toISOString();

  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const headers = { "X-Nexus-Masked": "true" };

    if (url === "/devices") {
      return jsonResponse(
        200,
        {
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
              device_id: "dev-tango-05",
              vendor_hint: "check_point",
              role: "gateway",
              enrollment_state: "ENROLLED",
              hostname: "FW-TANGO-05",
              model: "26000",
              software_version: "R81.20",
              ha_role: "STANDBY",
              cluster_member_ref: "CLS-ROMEO-01",
              management_ip: "192.0.2.15",
              backup_target: true,
              inventory_collected_at: twoHoursAgo,
            },
            {
              device_id: "dev-sierra-01",
              vendor_hint: "palo_alto",
              role: "gateway",
              enrollment_state: "ENROLLED",
              hostname: "FW-SIERRA-01",
              model: "PA-5420",
              software_version: "11.1.10-h7",
              ha_role: "ACTIVE",
              cluster_member_ref: "CLS-SIERRA-02",
              management_ip: "198.51.100.21",
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
              latest_job_type: "backup",
              latest_job_state: "FAILED",
              latest_job_terminal_reason: "Credential rejected by device",
            },
            {
              device_id: "dev-bravo-02",
              vendor_hint: "infoblox",
              role: "appliance",
              enrollment_state: "ENROLLED",
              hostname: "DEV-BRAVO-02",
              model: "IB-2225",
              software_version: "9.0.5",
              ha_role: null,
              cluster_member_ref: null,
              backup_target: false,
              inventory_collected_at: null,
            },
          ],
        },
        headers,
      );
    }

    if (url === "/api/v2/overview") {
      return jsonResponse(
        200,
        {
          generated_at: nowIso,
          masked: true,
          denominators: { active_devices: 6, enrolled_devices: 6, gateways: 5, clusters: 2, backup_targets: 5 },
          evidence: {
            inventory: { at: twoHoursAgo, state: "OK" },
            configuration: { at: twoHoursAgo, state: "OK" },
            compliance: { at: tenHoursAgo, state: "OK" },
            backup: { at: tenHoursAgo, state: "OK" },
            jobs: { at: twoHoursAgo, state: "OK" },
            platform_facts: { at: twoHoursAgo, state: "OK" },
          },
          attention: {
            failed_jobs_24h: { count: 1, terminal_24h: 84, state: "OK" },
            stale_inventory: { count: 1, of: 6, state: "OK" },
            cluster_diff: { count: 0, of: 2, state: "OK" },
            config_changed: { count: 1, of: 5, state: "OK" },
            backup_missing: { count: 1, of: 5, state: "OK" },
          },
          exceptions: {
            failed_jobs: { total: 1, rows: [] },
            config_changes: { total: 1, rows: [] },
            cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] },
          },
          inventory_age: { lt24h: 5, h24_72: 0, gt72h: 0, never: 1, of: 6, state: "OK" },
          compliance: {
            state: "OK",
            evaluated: 4,
            of_firewalls: 5,
            coverage_pct: 75,
            assured_pct: 55,
            observed_pct: 73.3,
            critical_deficiencies: 2,
            data_gaps: 5,
            frameworks: [{ name: "CIS Benchmark", pass: 11, fail: 4, unavailable: 5, total: 20, score_pct: 73.3 }],
          },
          platform: { devices: 6, check_point: 2, palo_alto: 2, clusters: 2, hotfix_levels: [], evidence_at: twoHoursAgo },
          policy_install: { state: "OK", of: 5, today: 4, yesterday: 0, days_2_7: 0, older: 0, unknown: 1, last_read_at: twoHoursAgo },
          nexus: { completed_24h: 84, running: 0, oldest_running_submitted_at: null, last_inventory: {} },
          estate: {
            compliance_state: "OK",
            no_archive: 1,
            counts: { critical: 1, ageing: 0, partly_assessed: 1, not_assessed: 2, clear: 2 },
            facts: { critical: { k: 1, n: 5 }, backups: { a: 4, b: 5 }, evidence: { r: 5, d: 6, never: 1 } },
            devices: [
              { device_id: "dev-tango-04", hostname: "FW-TANGO-04", vendor: "check_point", model: "26000", condition: "critical", no_archive: false, critical_fail: 2, last_read_at: twoHoursAgo, backup_target: true, last_backup_at: tenHoursAgo },
              { device_id: "dev-tango-05", hostname: "FW-TANGO-05", vendor: "check_point", model: "26000", condition: "clear", no_archive: false, critical_fail: 0, last_read_at: twoHoursAgo, backup_target: true, last_backup_at: tenHoursAgo },
              { device_id: "dev-sierra-01", hostname: "FW-SIERRA-01", vendor: "palo_alto", model: "PA-5420", condition: "clear", no_archive: false, critical_fail: 0, last_read_at: twoHoursAgo, backup_target: true, last_backup_at: tenHoursAgo },
              { device_id: "dev-sierra-02", hostname: "FW-SIERRA-02", vendor: "palo_alto", model: "PA-5420", condition: "partly_assessed", no_archive: false, critical_fail: 0, last_read_at: twoHoursAgo, backup_target: true, last_backup_at: tenHoursAgo },
              { device_id: "dev-juliet-06", hostname: "FW-JULIET-06", vendor: "cisco_asa", model: "FPR-2130", condition: "not_assessed", no_archive: true, critical_fail: null, last_read_at: twoHoursAgo, backup_target: true, last_backup_at: null },
              { device_id: "dev-bravo-02", hostname: "DEV-BRAVO-02", vendor: "infoblox", model: "IB-2225", condition: "not_assessed", condition_reason: "no_critical_control", no_archive: false, critical_fail: null, last_read_at: null, backup_target: false, last_backup_at: null },
            ],
          },
        },
        headers,
      );
    }

    if (url === "/api/v2/cp-failover/summary") {
      return jsonResponse(
        200,
        [
          {
            unitId: "cls-romeo-01",
            clusterId: "cls-romeo-01",
            cluster_member_ref: "CLS-ROMEO-01",
            vendor: "check_point",
            unitKind: "VSX_CHASSIS",
            failoverRefusal: "VSX_CHASSIS_NOT_FAILOVER_UNIT",
            activeWindow: true,
            lastRunState: "DONE",
            lastRunOutcome: "SUCCEEDED",
            lastRunAt: twoHoursAgo,
            canRunReadiness: true,
            mode: "LOAD_SHARING",
            readiness: {
              status: "READY",
              observedAt: twoHoursAgo,
              failedCheck: "",
              checks: [
                { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", status: "PASS", summary: "Active", blocking: true, derived: {} },
                { checkNo: 1, title: "Cluster state", member: "Member 2", result: "PASS", status: "PASS", summary: "Standby", blocking: true, derived: {} },
              ],
            },
          },
          {
            unitId: "cls-romeo-01:vs-01",
            clusterId: "cls-romeo-01",
            cluster_member_ref: "CLS-ROMEO-01",
            vendor: "check_point",
            unitKind: "VIRTUAL_SYSTEM",
            virtual_system: "VS-ROMEO-01-01",
            activeWindow: true,
            lastRunState: "DONE",
            lastRunOutcome: "STOPPED",
            lastRunAt: twoHoursAgo,
            canRunReadiness: true,
            mode: "ACTIVE_STANDBY",
            readiness: {
              status: "NOT_READY",
              observedAt: twoHoursAgo,
              failedCheck: "Policy parity differs",
              checks: [
                { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", status: "PASS", summary: "Active", blocking: true, derived: {} },
                { checkNo: 3, title: "Policy parity", member: "Member 1", result: "FAIL", status: "FAIL", summary: "POL-ALPHA-01 vs POL-ALPHA-02", blocking: true, derived: {} },
              ],
            },
          },
          {
            unitId: "cls-sierra-02",
            clusterId: "cls-sierra-02",
            cluster_member_ref: "CLS-SIERRA-02",
            vendor: "palo_alto",
            activeWindow: true,
            lastRunState: "DONE",
            lastRunOutcome: "STOPPED",
            lastRunAt: twoDaysAgo,
            canRunReadiness: true,
            mode: "ACTIVE_PASSIVE",
            readiness: {
              status: "UNKNOWN",
              observedAt: twoDaysAgo,
              failedCheck: "",
              checks: [
                { checkNo: 1, title: "Cluster state", member: "Member 1", result: "PASS", status: "PASS", summary: "Active", blocking: true, derived: {} },
                { checkNo: 1, title: "Cluster state", member: "Member 2", result: "UNKNOWN", status: "UNKNOWN", summary: "Peer unreachable", blocking: true, derived: {} },
              ],
            },
          },
        ],
        headers,
      );
    }

    if (url.startsWith("/api/v2/policy/changes")) {
      return jsonResponse(
        200,
        {
          rows: [
            {
              revision: "rev-101",
              policyId: "pol-alpha",
              policyName: "POL-ALPHA-01",
              targets: [{ deviceId: "dev-tango-04", name: "FW-TANGO-04", context: "", syncStatus: "OK" }],
              ruleId: "rule-412",
              number: 412,
              name: "WEB-TO-CORE",
              changedOn: twoHoursAgo,
              collectedAt: twoHoursAgo,
              observedAtCollection: false,
              changedBy: "USR-BIRCH-01",
              changeType: "modified",
              changedFields: ["service"],
              changes: [{ field: "service", before: ["tcp/443"], after: ["tcp/443", "tcp/8443"] }],
            },
            {
              revision: "rev-102",
              policyId: "pol-edge",
              policyName: "POL-EDGE-02",
              targets: [{ deviceId: "dev-sierra-01", name: "FW-SIERRA-01", context: "", syncStatus: "OK" }],
              ruleId: "rule-207",
              number: 207,
              name: "LEGACY-ANY",
              changedOn: tenHoursAgo,
              collectedAt: tenHoursAgo,
              observedAtCollection: true,
              changedBy: "UNKNOWN (observed by collection)",
              changeType: "modified",
              changedFields: ["action"],
              changes: [{ field: "action", before: "accept", after: "drop" }],
            },
          ],
          summary: { total: 2, actors: 1, rules: 2, counts: { modified: 2 }, initialCaptures: 4 },
          nextCursor: "",
          rangeStart: twoDaysAgo,
          rangeEnd: nowIso,
        },
        headers,
      );
    }

    if (url.startsWith("/api/v2/changes/feed")) {
      return jsonResponse(
        200,
        {
          events: [
            {
              key: "pan_cfg:1",
              kind: "USER",
              source: "pan_config",
              time: tenHoursAgo,
              time_basis: "DEVICE_LOCAL",
              device_time: "2026/11/19 04:00:00",
              vendor: "palo_alto",
              device_id: "dev-sierra-01",
              device_name: "FW-SIERRA-01",
              actor: "USR-CEDAR-02",
              what: "edit system ntp-servers",
              detail: "client Web · result Submitted",
              tone: "NEUTRAL",
              diff: [
                { op: "-", text: "ntp-server 198.51.100.5" },
                { op: "+", text: "ntp-server 203.0.113.5" },
              ],
              links: { device: null, policy_id: null, rule_id: null, job_id: null, job_type: null },
            },
            {
              key: "evt:1",
              kind: "DEVICE_EVENT",
              source: "cp_cpwd_elg",
              time: twoDaysAgo,
              time_basis: "UTC",
              device_time: null,
              vendor: "check_point",
              device_id: "dev-tango-05",
              device_name: "FW-TANGO-05",
              actor: "device",
              what: "process FWD restarted",
              detail: "watchdog restart recorded",
              tone: "WARN",
              diff: [],
              links: { device: null, policy_id: null, rule_id: null, job_id: null, job_type: null },
            },
          ],
          next_cursor: "",
          range_start: twoDaysAgo,
          range_end: nowIso,
          kinds: ["USER", "DEVICE_EVENT", "NEXUS_JOB"],
          vendor: null,
          device_id: null,
          masked: true,
          ordering_note: "Device-clock times are ordered as Europe/Istanbul.",
          coverage: [
            { source: "policy_rule_history", kind: "USER", state: "OK", note: "stored rule revisions", events: 2 },
            { source: "device_crash_alert", kind: "DEVICE_EVENT", state: "UNAVAILABLE", note: "reader offline", events: 0 },
          ],
        },
        headers,
      );
    }

    if (url === "/compliance/overview") {
      return jsonResponse(
        200,
        {
          total_firewalls: 5,
          evaluated_firewalls: 4,
          assured_compliance_pct: 55,
          evidence_coverage_pct: 75,
          observed_compliance_pct: 73.3,
          critical_deficiencies: 2,
          data_gaps: 5,
          control_checks: 20,
          frameworks: [
            {
              framework: "CIS Benchmark",
              score_pct: 73.3,
              total_controls: 20,
              pass_count: 11,
              fail_count: 4,
              data_unavailable_count: 5,
            },
          ],
        },
        headers,
      );
    }

    if (url === "/compliance/controls") {
      return jsonResponse(
        200,
        {
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
              target_device_count: 4,
              pass_count: 2,
              fail_count: 2,
              data_unavailable_count: 0,
              affected_devices: ["FW-TANGO-04", "FW-SIERRA-01"],
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
              target_device_count: 5,
              pass_count: 0,
              fail_count: 0,
              data_unavailable_count: 5,
              missing_reason: "Command not in collection scope yet",
              affected_devices: [],
              vendors: ["cisco_asa"],
            },
          ],
        },
        headers,
      );
    }

    if (url === "/api/v2/compliance/controls/CIS-5.2.1") {
      return jsonResponse(
        200,
        {
          control_id: "CIS-5.2.1",
          title: "SSH protocol version 2 enforced",
          severity: "CRITICAL",
          status: "FAIL",
          rationale: "Prevent downgrade",
          frameworks: [],
          expected: [],
          devices: [
            {
              device_id: "dev-tango-04",
              hostname: "FW-TANGO-04",
              vendor: "check_point",
              status: "FAIL",
              observed_value: "ssh-v1-v2 enabled",
              message: "Protocol 1 enabled",
            },
          ],
        },
        headers,
      );
    }

    if (url === "/backups") {
      return jsonResponse(
        200,
        {
          backups: [
            {
              artefact_id: "bk-tango-04-prev",
              device_id: "dev-tango-04",
              collected_at: yesterdayNight,
              size_bytes: 120 * 1024 * 1024,
              digest_prefix: "11223344",
              validation_level: "V3",
              deviation_state: "unchanged",
            },
            {
              artefact_id: "bk-tango-04-curr",
              device_id: "dev-tango-04",
              collected_at: tenHoursAgo,
              size_bytes: 540 * 1024 * 1024,
              digest_prefix: "99887766",
              validation_level: "V3",
              deviation_state: "changed",
            },
            {
              artefact_id: "bk-tango-05-curr",
              device_id: "dev-tango-05",
              collected_at: tenHoursAgo,
              size_bytes: 118 * 1024 * 1024,
              digest_prefix: "55667788",
              validation_level: "V3",
              deviation_state: "unchanged",
            },
            {
              artefact_id: "bk-sierra-01-curr",
              device_id: "dev-sierra-01",
              collected_at: tenHoursAgo,
              size_bytes: 84 * 1024 * 1024,
              digest_prefix: "aabbccdd",
              validation_level: "V3",
              deviation_state: "unchanged",
            },
          ],
        },
        headers,
      );
    }

    if (url === "/api/v2/backups/deviations") {
      return jsonResponse(
        200,
        {
          active_major_deviations: [],
          total_deviations_checked: 4,
          size_anomalies: [
            {
              device_id: "dev-tango-04",
              hostname: "FW-TANGO-04",
              deviation_id: "anom-tango-04",
              artefact_id: "bk-tango-04-curr",
              last_good_artefact_id: "bk-tango-04-prev",
              direction: "growth",
              plaintext_bytes: 540 * 1024 * 1024,
              median_bytes: 120 * 1024 * 1024,
              ratio: 4.5,
              summary: "Archive grew 4.5x against 3-archive median",
              detected_at: tenHoursAgo,
              open_copies: 1,
            },
          ],
        },
        headers,
      );
    }

    if (url === "/api/v2/backups/policies") {
      return jsonResponse(
        200,
        {
          policy_id: "default",
          schedule_enabled: true,
          daily_backup_cron: "0 2 * * *",
          backup_retention_days: 7,
          snapshot_retention_depth: 4,
          last_scheduled_run_at: tenHoursAgo,
          updated_at: twoDaysAgo,
        },
        headers,
      );
    }

    if (url === "/api/v2/backups/store-quotas") {
      return jsonResponse(
        200,
        {
          store_mounted: true,
          stores: [
            {
              store: "check_point",
              used_bytes: 150 * 1024 ** 3,
              quota_bytes: 300 * 1024 ** 3,
              remaining_bytes: 150 * 1024 ** 3,
              percent: 50,
              near_limit: false,
              quota_exceeded: false,
            },
          ],
        },
        headers,
      );
    }

    if (url.startsWith("/api/v2/jobs")) {
      return jsonResponse(
        200,
        {
          items: [
            {
              job_id: "job-bk-unk",
              job_type: "backup",
              state: "OUTCOME_UNKNOWN",
              target_device_id: "dev-sierra-02",
              submitted_at: tenHoursAgo,
              finished_at: tenHoursAgo,
              terminal_reason: "transfer interrupted",
              has_transcript: false,
            },
          ],
          page: 1,
          page_size: 400,
          total: 1,
        },
        headers,
      );
    }

    if (url === "/api/v2/lifecycle") {
      return jsonResponse(200, { as_of: twoHoursAgo, summary: {}, devices: [] }, headers);
    }

    return jsonResponse(200, {}, headers);
  });
}

beforeEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
});

afterEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("Alternative Estate view, slice 2 screens", () => {
  it("renders the Failover board with HA pairs, VSX chassis + VS units, per-check matrix, hatched UNKNOWN, zero execution buttons, and standard HA link", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=operations");
    vi.stubGlobal("fetch", stubSlice2ApiFetch());
    stubViewport(1280);
    render(<App />);

    const failover = await screen.findByTestId("estate-failover-screen");
    expect(within(failover).getByRole("heading", { name: "Is each cluster safe to fail over?" })).toBeInTheDocument();

    // VSX chassis and child Virtual System unit appear distinctly
    expect(failover.querySelector('[data-failover-unit-kind="VSX_CHASSIS"]')).toHaveTextContent("VSX chassis");
    expect(failover.querySelector('[data-failover-unit-kind="VIRTUAL_SYSTEM"]')).toHaveTextContent("VS · VS-ROMEO-01-01");

    // Pre-check matrix has rows for VS-ROMEO-01-01, CLS-SIERRA-02, and CLS-ROMEO-01 chassis
    const matrix = within(failover).getByRole("table", { name: "Failover pre-check matrix" });
    expect(matrix.querySelector('[data-matrix-unit="cls-romeo-01"]')).not.toBeNull();
    expect(matrix.querySelector('[data-matrix-unit="cls-romeo-01:vs-01"]')).not.toBeNull();
    expect(matrix.querySelector('[data-matrix-unit="cls-sierra-02"]')).not.toBeNull();

    // Hatched UNKNOWN tile & badge present for CLS-SIERRA-02
    expect(failover.querySelectorAll('[data-hatched="true"]').length).toBeGreaterThan(0);
    expect(failover.querySelectorAll("[data-evidence-stamp]").length).toBeGreaterThanOrEqual(4);

    // Zero execution buttons in Estate view
    expect(within(failover).queryByRole("button", { name: /run readiness|re-check|start failover|approve|sync/i })).toBeNull();

    // Clicking "Open standard HA & readiness →" opens the untouched Standard Operations screen
    fireEvent.click(within(failover).getByTestId("failover-standard-link"));
    expect(window.location.search).toBe("?screen=operations&standard=1");
    expect(await screen.findByRole("navigation", { name: "Primary" })).toBeInTheDocument();

    // Clicking Estate in View switch strips standard=1 and returns to Estate Failover board
    const viewSwitch = screen.getByRole("group", { name: "View" });
    fireEvent.click(within(viewSwitch).getByRole("button", { name: "Estate" }));
    expect(window.location.search).toBe("?screen=operations");
    expect(await screen.findByTestId("estate-failover-screen")).toBeInTheDocument();
  });

  it("renders the Changes screen with changes per day, grouped by person swimlanes and sections, hatched UNKNOWN actor, and before/after diffs in dark mode", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.localStorage.setItem("nx.mode", "dark");
    window.history.replaceState(null, "", "/?screen=changes");
    vi.stubGlobal("fetch", stubSlice2ApiFetch());
    stubViewport(1280, true);
    render(<App />);

    const changes = await screen.findByTestId("estate-changes-screen");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(within(changes).getByRole("heading", { name: "Who changed what, and when" })).toBeInTheDocument();

    // Changes per day chart
    expect(within(changes).getByRole("img", { name: "Changes per day" })).toBeInTheDocument();

    // Grouped by person swimlanes + sections (USR-BIRCH-01, USR-CEDAR-02, UNKNOWN)
    expect(changes.querySelector('[data-person-lane="USR-BIRCH-01"]')).not.toBeNull();
    expect(changes.querySelector('[data-person-lane="USR-CEDAR-02"]')).not.toBeNull();
    expect(changes.querySelector('[data-person-lane="UNKNOWN"]')).not.toBeNull();
    expect(changes.querySelector('[data-person-group="USR-BIRCH-01"]')).not.toBeNull();

    // Hatched UNKNOWN badge for unrecorded actor and unavailable crash-alert source
    expect(changes.querySelectorAll('[data-state-badge="unk"][data-hatched="true"]').length).toBeGreaterThanOrEqual(2);

    // First change set's Before and after diff table is expanded with BEFORE and AFTER values
    const diffBox = within(changes).getByTestId("change-before-after");
    expect(within(diffBox).getByText("BEFORE")).toBeInTheDocument();
    expect(within(diffBox).getByText("AFTER")).toBeInTheDocument();
    expect(within(diffBox).getByText(/tcp\/8443/)).toBeInTheDocument();

    // Clicking USR-CEDAR-02 swimlane filters change sets to USR-CEDAR-02 and shows config diff
    fireEvent.click(changes.querySelector('[data-person-lane="USR-CEDAR-02"]') as HTMLElement);
    expect(within(changes).getByText(/edit system ntp-servers/)).toBeInTheDocument();
    expect(within(changes).getByText(/203\.0\.113\.5/)).toBeInTheDocument();
  });

  it("renders the Compliance lens screen with Honest bar, hatched no-evidence share, family-by-vendor heatmap, data gaps, and Fix once remediation drill-down", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=compliance");
    vi.stubGlobal("fetch", stubSlice2ApiFetch());
    stubViewport(1280);
    render(<App />);

    const comp = await screen.findByTestId("estate-compliance-screen");
    expect(
      within(comp).getByRole("heading", { name: "Which controls fail, where, and how much do we not know?" }),
    ).toBeInTheDocument();

    // Honest bar with hatched no-evidence share
    const honestBar = await within(comp).findByTestId("compliance-honest-bar");
    expect(honestBar.querySelector('[data-bar-segment="pass"]')).not.toBeNull();
    expect(honestBar.querySelector('[data-bar-segment="fail"]')).not.toBeNull();
    expect(honestBar.querySelector('[data-bar-segment="unavailable"][data-hatched="true"]')).not.toBeNull();

    // Family-by-vendor heatmap
    const heatmap = within(comp).getByRole("table", { name: "Control families by vendor" });
    expect(heatmap.querySelector('[data-family-row="IA"]')).not.toBeNull();
    expect(heatmap.querySelector('[data-family-row="AU"]')).not.toBeNull();

    // Data gap reason
    expect(within(comp).getByText("Command not in collection scope yet")).toBeInTheDocument();

    // Fix once, clear many expansion loads per-device detail
    const remRow = comp.querySelector('[data-remediation-row="CIS-5.2.1"]') as HTMLElement;
    expect(remRow).not.toBeNull();
    fireEvent.click(within(remRow).getByRole("button", { name: /Show 2 firewalls/i }));
    expect(await within(remRow).findByText("ssh-v1-v2 enabled")).toBeInTheDocument();
  });

  it("renders the Backups lens screen with 5-fact strip, 14-night strips, size deviation sparklines, hatched UNKNOWN job cell, and non-target filter", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=backups");
    vi.stubGlobal("fetch", stubSlice2ApiFetch());
    stubViewport(1280);
    render(<App />);

    const bk = await screen.findByTestId("estate-backups-screen");
    expect(
      within(bk).getByRole("heading", { name: "Is there a good, recent backup of everything that matters?" }),
    ).toBeInTheDocument();

    // 14-night strip for FW-TANGO-04 shows size deviation (~) and sparkline + Grew 4.5x
    const tangoStrip = await within(bk).findByTestId("night-strip-FW-TANGO-04");
    expect(tangoStrip.querySelectorAll("[data-night-state]")).toHaveLength(14);
    expect(tangoStrip.querySelector('[data-night-state="anomaly"]')).not.toBeNull();
    expect(within(bk).getAllByText("Grew 4.5x").length).toBeGreaterThanOrEqual(1);
    expect(within(bk).getByRole("img", { name: "FW-TANGO-04 archive size trend" })).toBeInTheDocument();

    // FW-SIERRA-02 had an OUTCOME_UNKNOWN job -> hatched unknown night cell
    const sierraStrip = within(bk).getByTestId("night-strip-FW-SIERRA-02");
    expect(sierraStrip.querySelector('[data-night-state="unknown"][data-hatched="true"]')).not.toBeNull();

    // Zero execution buttons ("Back up now" / "Make target" absent; links to standard Backups instead)
    expect(within(bk).queryByRole("button", { name: /back up|make target|start/i })).toBeNull();
    expect(within(bk).getByTestId("backups-standard-link")).toHaveAttribute("href", "?screen=backups&standard=1");

    // Filter to Not targets -> shows DEV-BRAVO-02
    fireEvent.click(within(bk).getByRole("button", { name: /Not targets · 1/i }));
    expect(within(bk).getByText("DEV-BRAVO-02")).toBeInTheDocument();
  });

  it("navigates across Estate lens bar pills (Failover, Backups, Controls) and top-nav Changes at 1280 px, and fails closed to hatched UNKNOWN when reads fail", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=inventory");
    vi.stubGlobal("fetch", stubSlice2ApiFetch());
    stubViewport(1280);
    const { unmount } = render(<App />);

    await screen.findByTestId("estate-wall-screen");

    // Click Failover in Estate lenses bar
    const lensNav = screen.getByRole("navigation", { name: "Estate lenses" });
    fireEvent.click(within(lensNav).getByRole("link", { name: /^Failover/i }));
    expect(await screen.findByTestId("estate-failover-screen")).toBeInTheDocument();
    expect(within(screen.getByRole("navigation", { name: "Estate lenses" })).getByRole("link", { name: /^Failover/i })).toHaveAttribute(
      "aria-current",
      "page",
    );

    // Click Backups in Estate lenses bar
    fireEvent.click(
      within(screen.getByRole("navigation", { name: "Estate lenses" })).getByRole("link", { name: /^Backups/i }),
    );
    expect(await screen.findByTestId("estate-backups-screen")).toBeInTheDocument();

    // Click Controls in Estate lenses bar
    fireEvent.click(
      within(screen.getByRole("navigation", { name: "Estate lenses" })).getByRole("link", { name: /^Controls/i }),
    );
    expect(await screen.findByTestId("estate-compliance-screen")).toBeInTheDocument();

    // Click Changes in top Estate navigation -> opens Estate Changes screen and hides lens bar
    const topNav = screen.getByRole("navigation", { name: "Estate navigation" });
    fireEvent.click(within(topNav).getByRole("link", { name: "Changes" }));
    expect(await screen.findByTestId("estate-changes-screen")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Estate lenses" })).toBeNull();

    unmount();

    // Fail-closed check: when failover summary returns 500, Failover board shows hatched UNKNOWN, never Ready
    resetSharedApiCache();
    const baseFetch = stubSlice2ApiFetch();
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        if (String(input) === "/api/v2/cp-failover/summary") {
          return jsonResponse(500, { error: "DOWNSTREAM_ERROR" }, { "X-Nexus-Masked": "true" });
        }
        return baseFetch(input);
      }),
    );
    window.history.replaceState(null, "", "/?screen=operations");
    render(<App />);

    const failedFo = await screen.findByTestId("estate-failover-screen");
    expect(within(failedFo).getByText(/every cluster stays UNKNOWN/i)).toBeInTheDocument();
    expect(failedFo.querySelector('[data-state-badge="unk"][data-hatched="true"]')).not.toBeNull();
  });
});
