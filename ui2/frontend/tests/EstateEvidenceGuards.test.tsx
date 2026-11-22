// Estate evidence-stamp and UNKNOWN-never-OK guards. Fixture copied from tests/EstateSlice2Screens.test.tsx (test-only).
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { StateBadge } from "../src/estate/EstatePrimitives";
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

// Guard tests for two concept open checks (docs/reviews/ESTATE_CONCEPT_GAPS_2026_11_22.md):
//  A. every figure carries an evidence stamp (CONCEPT.md design language)
//  B. UNKNOWN is never shown as OK
// Fixture = the slice-2 fixture above: each lens has at least one UNKNOWN unit
// (failover: CLS-SIERRA-02 readiness UNKNOWN; compliance: DEV-JULIET-06 not assessed;
// backups: FW-SIERRA-02 OUTCOME_UNKNOWN night cell).

// A section "has a count" when its text carries a percentage or a number followed by a word.
const COUNT_RE = /\d+\s*%|\b\d+\s+[A-Za-z]/;

const SCREEN_CASES = [
  { lens: "Wall", search: "/?screen=inventory", testId: "estate-wall-screen" },
  { lens: "Failover", search: "/?screen=operations", testId: "estate-failover-screen" },
  { lens: "Compliance", search: "/?screen=compliance", testId: "estate-compliance-screen" },
  { lens: "Backups", search: "/?screen=backups", testId: "estate-backups-screen" },
] as const;

describe("Estate evidence-stamp and UNKNOWN-never-OK guards", () => {
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

  for (const c of SCREEN_CASES) {
    it(`${c.lens} lens: every counted section carries an evidence stamp (guard A)`, async () => {
      window.localStorage.setItem("nx.view", "estate");
      window.history.replaceState(null, "", c.search);
      vi.stubGlobal("fetch", stubSlice2ApiFetch());
      stubViewport(1280);
      render(<App />);

      const root = await screen.findByTestId(c.testId);
      await waitFor(() => expect(root.querySelector("[data-evidence-stamp]")).not.toBeNull());

      const sections = Array.from(root.querySelectorAll("section"));
      const counted = sections.filter((s) => COUNT_RE.test(s.textContent ?? ""));
      expect(counted.length, `${c.lens}: no counted sections found to guard`).toBeGreaterThan(0);
      for (const s of counted) {
        const heading = s.querySelector("h1, h2, h3")?.textContent ?? "(untitled section)";
        expect(s.querySelector("[data-evidence-stamp]"), `${c.lens}: section "${heading}" shows a count without an EvidenceStamp`).not.toBeNull();
      }
    });

    it(`${c.lens} lens: the UNKNOWN count text is visible when the fixture has UNKNOWN units`, async () => {
      window.localStorage.setItem("nx.view", "estate");
      window.history.replaceState(null, "", c.search);
      vi.stubGlobal("fetch", stubSlice2ApiFetch());
      stubViewport(1280);
      render(<App />);

      const root = await screen.findByTestId(c.testId);
      await waitFor(() => expect(root.querySelector('[data-state-badge="unk"], [data-night-state="unknown"]')).not.toBeNull());
      expect(root.textContent ?? "", `${c.lens}: UNKNOWN count text missing`).toMatch(/UNKNOWN/);
    });

    const noOkOnUnknownRow = async () => {
      window.localStorage.setItem("nx.view", "estate");
      window.history.replaceState(null, "", c.search);
      vi.stubGlobal("fetch", stubSlice2ApiFetch());
      stubViewport(1280);
      render(<App />);

      const root = await screen.findByTestId(c.testId);
      await waitFor(() => expect(root.querySelector('[data-state-badge="unk"], [data-night-state="unknown"]')).not.toBeNull());
      const marks = Array.from(root.querySelectorAll('[data-state-badge="unk"], [data-night-state="unknown"]'));
      for (const mark of marks) {
        // The unit row = nearest ancestor that holds exactly one unit state badge.
        let row: Element | null = mark.parentElement;
        while (row && row !== root && row.querySelectorAll("[data-state-badge]").length !== 1) row = row.parentElement;
        const scope = (row && row !== root ? row : (mark.parentElement ?? root)) as HTMLElement;
        expect(scope.querySelector('[data-state-badge="ok"]'), `${c.lens}: state badge OK on a row holding an UNKNOWN mark`).toBeNull();
        expect(within(scope).queryAllByText(/^(OK|Fine)$/), `${c.lens}: OK/Fine on a row holding an UNKNOWN mark`).toHaveLength(0);
      }
    };

    if (c.lens === "Backups") {
      // KNOWN OPEN FINDING (test-only lane, screen not changed): FW-SIERRA-02 row in the Backups
      // lens (src/estate/EstateBackupsScreen.tsx, night strip + row state badge) shows "Fine"
      // next to a night cell whose attempt outcome is UNKNOWN. Expected to fail until the screen
      // is fixed; flip to `it` once it is.
      it.fails(`${c.lens} lens: no OK/Fine on a row holding an UNKNOWN mark (guard B, known open finding)`, noOkOnUnknownRow);
    } else {
      it(`${c.lens} lens: no OK/Fine on a row holding an UNKNOWN mark (guard B)`, noOkOnUnknownRow);
    }
  }
});

describe("StateBadge UNKNOWN primitive (guard C)", () => {
  it("state unk renders UNKNOWN and never OK", () => {
    const { container } = render(<StateBadge state="unk" />);
    expect(container).toHaveTextContent("UNKNOWN");
    expect(container.textContent ?? "").not.toMatch(/\bOK\b|\bFine\b/);
    expect(screen.queryByText(/^(OK|Fine)$/)).toBeNull();
  });
});
