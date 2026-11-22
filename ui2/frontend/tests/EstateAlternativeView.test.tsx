import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";

function jsonResponse(status: number, body: unknown, extraHeaders?: Record<string, string>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...extraHeaders },
  });
}

function stubViewport(widthPx: number) {
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
      else if (darkMatch) matches = false;
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

function stubEstateApiFetch() {
  const nowIso = new Date().toISOString();
  const twoHoursAgo = new Date(Date.now() - 2 * 3_600_000).toISOString();
  const tenHoursAgo = new Date(Date.now() - 10 * 3_600_000).toISOString();
  const fiveDaysAgo = new Date(Date.now() - 5 * 86_400_000).toISOString();

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
              model: "16200",
              software_version: "R81.20",
              hotfix_level: "Take 119",
              ha_role: "ACTIVE",
              cluster_member_ref: "CLS-ROMEO-01",
              management_ip: "192.0.2.14",
              backup_target: true,
              inventory_collected_at: twoHoursAgo,
              platform_facts_source: "cp_gaia_facts",
              platform_facts_observed_at: twoHoursAgo,
              policy_name: "POL-ALPHA-01",
              policy_installed_at: tenHoursAgo,
              policy_read_at: twoHoursAgo,
              identity_check: {
                state: "VERIFIED",
                basis: "SSH_HOST_KEY",
                first_use: false,
                checked_at: twoHoursAgo,
              },
            },
            {
              device_id: "dev-tango-05",
              vendor_hint: "check_point",
              role: "gateway",
              enrollment_state: "ENROLLED",
              hostname: "FW-TANGO-05",
              model: "16200",
              software_version: "R81.20",
              ha_role: "STANDBY",
              cluster_member_ref: "CLS-ROMEO-01",
              management_ip: "192.0.2.15",
              backup_target: true,
              inventory_collected_at: twoHoursAgo,
              policy_name: "POL-ALPHA-02",
              policy_installed_at: tenHoursAgo,
              policy_read_at: twoHoursAgo,
            },
            {
              device_id: "dev-juliet-06",
              vendor_hint: "palo_alto",
              role: "gateway",
              enrollment_state: "ENROLLED",
              hostname: "FW-JULIET-06",
              model: "PA-5420",
              software_version: "11.1.10-h7",
              ha_role: null,
              cluster_member_ref: null,
              management_ip: "198.51.100.26",
              backup_target: true,
              inventory_collected_at: fiveDaysAgo,
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
          denominators: { active_devices: 4, enrolled_devices: 4, gateways: 3, clusters: 1, backup_targets: 3 },
          evidence: {
            inventory: { at: twoHoursAgo, state: "OK" },
            configuration: { at: twoHoursAgo, state: "OK" },
            compliance: { at: tenHoursAgo, state: "OK" },
            backup: { at: tenHoursAgo, state: "OK" },
            jobs: { at: twoHoursAgo, state: "OK" },
            platform_facts: { at: twoHoursAgo, state: "OK" },
          },
          attention: {
            failed_jobs_24h: { count: 0, terminal_24h: 84, previous: 1, state: "OK" },
            stale_inventory: { count: 2, of: 4, state: "OK" },
            cluster_diff: { count: 1, of: 1, previous: 1, state: "OK" },
            config_changed: { count: 1, of: 3, previous: 0, state: "OK" },
            backup_missing: { count: 1, of: 3, previous: 2, state: "OK" },
          },
          exceptions: {
            failed_jobs: { total: 0, rows: [] },
            config_changes: { total: 1, rows: [] },
            cluster_diff: {
              total: 1,
              unknown: 0,
              all_refs: ["CLS-ROMEO-01"],
              rows: [
                {
                  cluster_ref: "CLS-ROMEO-01",
                  diff_section_count: 1,
                  diff_setting_count: 3,
                  diff_sections: ["ntp"],
                  computed_at: twoHoursAgo,
                },
              ],
            },
          },
          inventory_age: { lt24h: 2, h24_72: 0, gt72h: 1, never: 1, of: 4, state: "OK" },
          compliance: {
            state: "OK",
            evaluated: 3,
            of_firewalls: 3,
            coverage_pct: 75,
            assured_pct: 60,
            observed_pct: 80,
            critical_deficiencies: 2,
            data_gaps: 3,
            frameworks: [{ name: "CIS Benchmarks", pass: 12, fail: 3, unavailable: 5, total: 20, score_pct: 80 }],
          },
          platform: {
            devices: 4,
            check_point: 2,
            palo_alto: 1,
            clusters: 1,
            hotfix_levels: [],
            evidence_at: twoHoursAgo,
          },
          policy_install: {
            state: "OK",
            of: 3,
            today: 2,
            yesterday: 0,
            days_2_7: 0,
            older: 0,
            unknown: 1,
            last_read_at: twoHoursAgo,
          },
          nexus: {
            completed_24h: 84,
            running: 0,
            oldest_running_submitted_at: null,
            last_inventory: { check_point: twoHoursAgo, palo_alto: fiveDaysAgo },
          },
          estate: {
            compliance_state: "OK",
            no_archive: 1,
            counts: { critical: 1, ageing: 0, partly_assessed: 1, not_assessed: 1, clear: 1 },
            facts: { critical: { k: 1, n: 3 }, backups: { a: 2, b: 3 }, evidence: { r: 2, d: 4, never: 1 } },
            devices: [
              {
                device_id: "dev-tango-04",
                hostname: "FW-TANGO-04",
                vendor: "check_point",
                model: "16200",
                condition: "critical",
                no_archive: false,
                critical_fail: 2,
                last_read_at: twoHoursAgo,
                backup_target: true,
                last_backup_at: tenHoursAgo,
              },
              {
                device_id: "dev-tango-05",
                hostname: "FW-TANGO-05",
                vendor: "check_point",
                model: "16200",
                condition: "clear",
                no_archive: false,
                critical_fail: 0,
                last_read_at: twoHoursAgo,
                backup_target: true,
                last_backup_at: tenHoursAgo,
              },
              {
                device_id: "dev-juliet-06",
                hostname: "FW-JULIET-06",
                vendor: "palo_alto",
                model: "PA-5420",
                condition: "partly_assessed",
                no_archive: true,
                critical_fail: 0,
                last_read_at: fiveDaysAgo,
                backup_target: true,
                last_backup_at: null,
              },
              {
                device_id: "dev-bravo-02",
                hostname: "DEV-BRAVO-02",
                vendor: "infoblox",
                model: "IB-2225",
                condition: "not_assessed",
                condition_reason: "no_critical_control",
                no_archive: false,
                critical_fail: null,
                last_read_at: null,
                backup_target: false,
                last_backup_at: null,
              },
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
            activeWindow: true,
            lastRunState: "DONE",
            lastRunOutcome: "SUCCEEDED",
            lastRunAt: twoHoursAgo,
            canRunReadiness: true,
            mode: "ACTIVE_STANDBY",
            readiness: {
              status: "NOT_READY",
              observedAt: twoHoursAgo,
              failedCheck: "Policy parity failed",
              checks: [
                {
                  checkNo: 1,
                  title: "Policy parity",
                  member: "CLS-ROMEO-01",
                  result: "FAIL",
                  status: "FAIL",
                  summary: "POL-ALPHA-01 vs POL-ALPHA-02",
                  blocking: true,
                  derived: {},
                },
                {
                  checkNo: 2,
                  title: "Peer corroboration",
                  member: "CLS-ROMEO-01",
                  result: "UNKNOWN",
                  status: "UNKNOWN",
                  summary: "Standby counter unconfirmed",
                  blocking: false,
                  derived: {},
                },
              ],
            },
          },
        ],
        headers,
      );
    }

    if (url === "/backups") {
      return jsonResponse(
        200,
        {
          backups: [
            {
              artefact_id: "bk-tango-04",
              device_id: "dev-tango-04",
              collected_at: tenHoursAgo,
              size_bytes: 150 * 1024 * 1024,
              digest_prefix: "11223344",
              validation_level: "V3",
              deviation_state: "unchanged",
            },
            {
              artefact_id: "bk-tango-05",
              device_id: "dev-tango-05",
              collected_at: tenHoursAgo,
              size_bytes: 148 * 1024 * 1024,
              digest_prefix: "55667788",
              validation_level: "V3",
              deviation_state: "unchanged",
            },
          ],
        },
        headers,
      );
    }

    if (url === "/api/v2/backups/deviations") {
      return jsonResponse(200, { active_major_deviations: [], size_anomalies: [], total_deviations_checked: 2 }, headers);
    }

    if (url === "/api/v2/lifecycle") {
      return jsonResponse(
        200,
        {
          as_of: twoHoursAgo,
          summary: {},
          devices: [
            {
              device_id: "dev-tango-04",
              hostname: "FW-TANGO-04",
              cluster_member_ref: "CLS-ROMEO-01",
              vendor: "check_point",
              model: "16200",
              software_version: "R81.20",
              hardware: { status: "MATCHED", reason: null, basis: "model", catalog: null },
              software: { status: "MATCHED", reason: null, basis: "version", catalog: null },
              status: "OK",
              risk: "HIGH",
              support_days: 500,
              next_milestone: { name: "Software EOS", date: "2028-05-31", days_remaining: 550 },
              nearest_license: null,
              licenses: [],
              license_status: "OK",
              support_contract_status: "OK",
              vulnerabilities: {
                state: "EVALUATED",
                affected: 1,
                affected_exploited: 1,
                affected_critical_high: 1,
                compared: { source: "checkpoint", advisories: 90, oldest: null, newest: null, loaded_at: twoHoursAgo, age_days: 0 },
              },
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
          controls: [
            {
              control_id: "CIS-5.2.1",
              title: "SSH protocol version 2 enforced",
              severity: "CRITICAL",
              fail_count: 2,
              target_device_count: 3,
            },
          ],
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
              id: "chg-1",
              time: twoHoursAgo,
              kind: "USER",
              actor: "UNKNOWN (observed by collection)",
              device_id: "dev-tango-04",
              device_name: "FW-TANGO-04",
            },
          ],
        },
        headers,
      );
    }

    // Per-device dossier endpoints
    if (url === "/devices/dev-tango-04/inventory") {
      return jsonResponse(
        200,
        {
          device_id: "dev-tango-04",
          collected_at: twoHoursAgo,
          job: null,
          contexts: [
            {
              context: "0",
              vs_name: "gw-core",
              ha: { role: "ACTIVE", cluster_mode: "High Availability" },
              interfaces: [
                {
                  name: "eth1-01",
                  parent: null,
                  kind: "physical",
                  state: "UP",
                  vlan_id: null,
                  zone: "external",
                  addresses: [{ address: "198.51.100.10/27", family: "ipv4", role: "member" }],
                },
              ],
              routes: [
                {
                  destination: "0.0.0.0/0",
                  next_hop: "198.51.100.1",
                  interface: "eth1-01",
                  protocol: "static",
                  table: "main",
                },
              ],
            },
          ],
        },
        headers,
      );
    }

    if (url === "/devices/dev-tango-04/backups") {
      return jsonResponse(
        200,
        {
          backups: [
            {
              artefact_id: "bk-tango-04",
              device_id: "dev-tango-04",
              collected_at: tenHoursAgo,
              size_bytes: 150 * 1024 * 1024,
              digest_prefix: "11223344",
              validation_level: "V3",
              deviation_state: "unchanged",
            },
          ],
        },
        headers,
      );
    }

    if (url === "/devices/dev-tango-04/compliance") {
      return jsonResponse(
        200,
        {
          device_id: "dev-tango-04",
          hostname: "FW-TANGO-04",
          vendor: "check_point",
          totalAssigned: 10,
          passCount: 6,
          failCount: 2,
          dataUnavailableCount: 2,
          observedCompliance: 75,
          evidenceCoverage: 80,
          assuredCompliance: 60,
          items: [
            {
              controlId: "CIS-5.2.1",
              title: "SSH protocol version 2 enforced",
              severity: "CRITICAL",
              frameworks: [],
              verdict: "FAIL",
              reasonCode: "MISMATCH",
              displayStatus: "FAIL",
            },
            {
              controlId: "AU-9",
              title: "Remote syslog over TLS",
              severity: "HIGH",
              frameworks: [],
              verdict: "UNAVAILABLE",
              reasonCode: "NO_EVIDENCE",
              displayStatus: "UNAVAILABLE",
            },
          ],
        },
        headers,
      );
    }

    if (url === "/devices/dev-tango-04/configuration") {
      return jsonResponse(
        200,
        {
          device_id: "dev-tango-04",
          collected_at: twoHoursAgo,
          change_state: "changed",
          change_deviation_summary: { entries: [{ section: "ntp", context: "shared" }] },
        },
        headers,
      );
    }

    if (url === "/devices/dev-tango-04/lifecycle") {
      return jsonResponse(
        200,
        {
          device_id: "dev-tango-04",
          hostname: "FW-TANGO-04",
          cluster_member_ref: "CLS-ROMEO-01",
          vendor: "check_point",
          model: "16200",
          software_version: "R81.20",
          hardware: { status: "MATCHED", reason: null, basis: "model", catalog: null },
          software: { status: "MATCHED", reason: null, basis: "version", catalog: null },
          status: "OK",
          risk: "HIGH",
          support_days: 500,
          next_milestone: { name: "Software EOS", date: "2028-05-31", days_remaining: 550 },
          nearest_license: null,
          licenses: [],
          license_status: "OK",
          support_contract_status: "OK",
          vulnerabilities: {
            state: "EVALUATED",
            affected: 1,
            affected_exploited: 1,
            affected_critical_high: 1,
            compared: { source: "checkpoint", advisories: 90, oldest: null, newest: null, loaded_at: twoHoursAgo, age_days: 0 },
          },
        },
        headers,
      );
    }

    if (url === "/devices/dev-tango-04/software-history") {
      return jsonResponse(
        200,
        {
          device_id: "dev-tango-04",
          known: true,
          stretches: [
            {
              device_id: "dev-tango-04",
              vendor: "check_point",
              software_version: "R81.20",
              hotfix_level: "Take 119",
              first_seen_at: fiveDaysAgo,
              last_seen_at: twoHoursAgo,
              previous_software_version: null,
              previous_hotfix_level: null,
              window_start: null,
              window_end: null,
              source: "inventory",
            },
          ],
          install_events: [],
        },
        headers,
      );
    }

    return jsonResponse(200, {}, headers);
  });
}

beforeEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  window.history.replaceState(null, "", "/?screen=overview");
});

afterEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("Alternative Estate view, slice 1", () => {
  it("defaults to Standard view untouched and persists View: Standard / Estate in localStorage alongside light/dark", async () => {
    vi.stubGlobal("fetch", stubEstateApiFetch());
    stubViewport(1280);
    render(<App />);

    // Standard view by default: left NavigationRail is present, View switch has Standard pressed
    expect(screen.getByRole("navigation", { name: "Primary" })).toBeInTheDocument();
    const viewSwitch = screen.getByRole("group", { name: "View" });
    expect(within(viewSwitch).getByRole("button", { name: "Standard" })).toHaveAttribute("aria-pressed", "true");
    expect(within(viewSwitch).getByRole("button", { name: "Estate" })).toHaveAttribute("aria-pressed", "false");

    // Switch to Estate view
    fireEvent.click(within(viewSwitch).getByRole("button", { name: "Estate" }));
    expect(window.localStorage.getItem("nx.view")).toBe("estate");
    expect(document.documentElement.dataset.view).toBe("estate");

    // Shift brief renders in Estate shell without the standard left rail
    expect(await screen.findByTestId("estate-brief-screen")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Primary" })).toBeNull();

    // Toggle dark mode inside Estate shell and verify dark theme applies cleanly
    fireEvent.click(screen.getByRole("button", { name: "Switch to dark mode" }));
    expect(window.localStorage.getItem("nx.mode")).toBe("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(screen.getByTestId("estate-brief-screen")).toBeInTheDocument();

    // Switch back to Standard view
    const estateViewSwitch = screen.getByRole("group", { name: "View" });
    fireEvent.click(within(estateViewSwitch).getByRole("button", { name: "Standard" }));
    expect(window.localStorage.getItem("nx.view")).toBe("standard");
    expect(await screen.findByRole("navigation", { name: "Primary" })).toBeInTheDocument();
  });

  it("renders the Shift brief with sentence headline, affected pair/unit tiles, hatched UNKNOWN badges, evidence stamps, Ask inside the Estate view, and standard links for Rewind/Cases", async () => {
    window.localStorage.setItem("nx.view", "estate");
    vi.stubGlobal("fetch", stubEstateApiFetch());
    stubViewport(1280);
    render(<App />);

    const brief = await screen.findByTestId("estate-brief-screen");
    expect(within(brief).getByTestId("brief-headline")).toHaveTextContent(/\d+ items? on \d+ units?(, \d+ with no unit)?: \d+ act now/i);

    // Hatched UNKNOWN badge is present and never folded into fine
    const unkBadges = brief.querySelectorAll('[data-state-badge="unk"][data-hatched="true"]');
    expect(unkBadges.length).toBeGreaterThan(0);

    // Evidence age stamps appear on figures
    const stamps = brief.querySelectorAll("[data-evidence-stamp]");
    expect(stamps.length).toBeGreaterThanOrEqual(6);

    // Joined HA pair appears in the failover item scope
    expect(brief.querySelector('[data-ha-pair="CLS-ROMEO-01"]')).not.toBeNull();

    // Mask chip surfaces automatically from X-Nexus-Masked response header
    expect(await screen.findByText("aiview · names masked")).toBeInTheDocument();

    // Rewind has its own Estate view; Cases leaves for the standard Packet analysis screen.
    // Ask is an Estate screen (EstateAskScreen), so its link stays inside the Estate view: no standard=1 marker.
    expect(screen.getByTestId("estate-rewind-link")).toHaveAttribute("href", "?screen=inventory&view=rewind");
    const estateNav = screen.getByRole("navigation", { name: "Estate navigation" });
    const askLink = within(estateNav).getByRole("link", { name: "Ask" });
    expect(askLink.getAttribute("href")).not.toContain("standard=1");
    expect(askLink).not.toHaveTextContent(/standard view/i);
    expect(within(estateNav).getByRole("link", { name: "Cases" })).toHaveAttribute("href", "?screen=packet_analysis");

    // Clicking Ask opens the Estate Ask workbench (the standard Trace screen stays one link away)
    fireEvent.click(within(estateNav).getByRole("link", { name: "Ask" }));
    expect(window.location.search).toBe("?screen=trace");
    expect(await screen.findByTestId("estate-ask-screen")).toBeInTheDocument();
  });

  it("renders the Estate wall at 1280 px in light and dark modes, joins HA pairs, switches lenses, and opens a unit dossier", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=inventory");
    vi.stubGlobal("fetch", stubEstateApiFetch());
    stubViewport(1280);
    render(<App />);

    const wall = await screen.findByTestId("estate-wall-screen");
    expect(within(wall).getByRole("heading", { name: /4 units, one wall · 1 HA pair joined/i })).toBeInTheDocument();

    // Joined HA pair has dashed cut link because CLS-ROMEO-01 is NOT_READY
    const pairEl = wall.querySelector('[data-ha-pair="CLS-ROMEO-01"]') as HTMLElement;
    expect(pairEl).not.toBeNull();
    expect(pairEl).toHaveAttribute("data-pair-state", "act");
    expect(pairEl.querySelector('[data-pair-link="cut"]')).not.toBeNull();

    // Switch lens to Failover
    const lensControl = within(wall).getByRole("group", { name: "Colour the wall by" });
    fireEvent.click(within(lensControl).getByRole("button", { name: "Failover" }));
    expect(window.location.search).toContain("lens=failover");
    expect(within(wall).getByText("Can each HA cluster fail over cleanly?")).toBeInTheDocument();

    // Switch lens to Backups
    fireEvent.click(within(lensControl).getByRole("button", { name: "Backups" }));
    expect(window.location.search).toContain("lens=backups");
    expect(within(wall).getByText("Is there a stored, validated backup?")).toBeInTheDocument();

    // Switch lens to Evidence age
    fireEvent.click(within(lensControl).getByRole("button", { name: "Evidence age" }));
    expect(window.location.search).toContain("lens=age");
    expect(within(wall).getByText("How old is what we know?")).toBeInTheDocument();
    // DEV-BRAVO-02 was never read -> hatched UNKNOWN tile
    const bravoTile = wall.querySelector('[data-unit-tile="DEV-BRAVO-02"]') as HTMLElement;
    expect(bravoTile).toHaveAttribute("data-tile-state", "unk");
    expect(bravoTile).toHaveAttribute("data-hatched", "true");

    // Click FW-TANGO-04 tile once to pin in inspector
    const tangoTile = wall.querySelector('[data-unit-tile="FW-TANGO-04"]') as HTMLElement;
    act(() => {
      fireEvent.click(tangoTile);
    });
    const inspector = within(wall).getByTestId("estate-unit-inspector");
    expect(within(inspector).getByText("FW-TANGO-04")).toBeInTheDocument();

    // Click Open dossier in inspector -> navigates to Unit Dossier
    fireEvent.click(within(inspector).getByRole("link", { name: "Open dossier" }));
    expect(window.location.search).toBe("?screen=inventory&device_id=dev-tango-04");

    const dossier = await screen.findByTestId("estate-dossier-screen");
    expect(within(dossier).getByRole("heading", { name: "FW-TANGO-04" })).toBeInTheDocument();

    // Pair bar shows peer link FW-TANGO-05
    const pairBar = within(dossier).getByTestId("dossier-pair-bar");
    expect(within(pairBar).getByRole("link", { name: "FW-TANGO-05" })).toHaveAttribute(
      "href",
      "?screen=inventory&device_id=dev-tango-05",
    );

    // Pinned identity shows relationship word MATCH, never a raw key fingerprint
    expect(within(dossier).getByText("MATCH (SSH HOST KEY)")).toBeInTheDocument();

    // Eight answers grid has all 8 cells, each with a state badge and an evidence stamp
    const answersGrid = within(dossier).getByTestId("dossier-eight-answers");
    const answerCards = answersGrid.querySelectorAll("[data-dossier-answer]");
    expect(answerCards).toHaveLength(8);
    for (const card of answerCards) {
      expect(card.querySelector("[data-state-badge]")).not.toBeNull();
      expect(card.querySelector("[data-evidence-stamp]")).not.toBeNull();
    }

    // Detail tabs work (Interfaces, Routes, Backups, Controls, Failover pre-checks)
    await waitFor(() => {
      expect(within(dossier).getByRole("table", { name: "Unit interfaces" })).toBeInTheDocument();
    });
    expect(within(dossier).getByText("198.51.100.10/27")).toBeInTheDocument();

    fireEvent.click(within(dossier).getByRole("tab", { name: /Controls/i }));
    const controlMatches = await within(dossier).findAllByText(/SSH protocol version 2 enforced/);
    expect(controlMatches.length).toBeGreaterThanOrEqual(2);
    expect(within(dossier).getByText(/Remote syslog over TLS/)).toBeInTheDocument();
    // Unavailable control renders with hatched UNKNOWN badge
    expect(dossier.querySelector('[data-state-badge="unk"][data-hatched="true"]')).not.toBeNull();

    fireEvent.click(within(dossier).getByRole("tab", { name: /Failover pre-checks/i }));
    expect(await within(dossier).findByRole("table", { name: "Cluster failover pre-checks" })).toBeInTheDocument();
  });

  it("opens the Estate Backups lens screen directly when switching View to Estate from ?screen=backups in dark mode", async () => {
    window.localStorage.setItem("nx.mode", "dark");
    window.history.replaceState(null, "", "/?screen=backups");
    vi.stubGlobal("fetch", stubEstateApiFetch());
    stubViewport(1280);
    render(<App />);

    expect(document.documentElement.dataset.theme).toBe("dark");
    const viewSwitch = screen.getByRole("group", { name: "View" });
    fireEvent.click(within(viewSwitch).getByRole("button", { name: "Estate" }));

    expect(window.location.search).toBe("?screen=backups");
    const backupsScreen = await screen.findByTestId("estate-backups-screen");
    expect(
      within(backupsScreen).getByRole("heading", { name: "Is there a good, recent backup of everything that matters?" }),
    ).toBeInTheDocument();
  });

  it("keeps ?wall=1 unattended kiosk display in Standard wall mode even when nx.view is estate", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/?screen=overview&wall=1");
    vi.stubGlobal("fetch", stubEstateApiFetch());
    render(<App />);

    expect(await screen.findByRole("link", { name: "Exit wall display" })).toBeInTheDocument();
    expect(screen.queryByTestId("estate-brief-screen")).toBeNull();
  });

  it("opens a /devices/<id> path directly in the Unit Dossier under Estate view", async () => {
    window.localStorage.setItem("nx.view", "estate");
    window.history.replaceState(null, "", "/devices/dev-tango-04");
    vi.stubGlobal("fetch", stubEstateApiFetch());
    stubViewport(1280);
    render(<App />);

    const dossier = await screen.findByTestId("estate-dossier-screen");
    expect(within(dossier).getByRole("heading", { name: "FW-TANGO-04" })).toBeInTheDocument();
  });
});
