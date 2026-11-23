import { render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ChangeFeedPage, CpFailoverSummary, DeviceSummary, LifecycleDevice, LifecycleFleet, OverviewView } from "../src/auth/adminApi";
import { readableEstate } from "../src/auth/adminApi";
import { EstateDetails, OverviewScreen, readAges } from "../src/screens/OverviewScreen";
import { buildAttention, RULES, unreadableOverviewParts, type AttentionInputs, type AttentionRow, type SourceRead } from "../src/screens/overview/attentionRules";
import { DisplayModeProvider } from "../src/shell/displayMode";

/**
 * The Overview never crashes on a null or missing field (incident 2026-11-23 13:15-15:09: a failed job without a target
 * device had device_id null). The server builds every section of the body on its own, so a section can arrive as
 * { state: "READ_FAILED" } without its fields (OverviewService.section); a column can be null. Here: each attention rule
 * and each screen figure against such payloads -- UNKNOWN wording, never a silent 0 or OK -- and one rule that throws
 * leaves one "could not be evaluated" row instead of a crashed list. Synthetic names only.
 */
afterEach(() => vi.unstubAllGlobals());

const NOW = new Date("2026-11-23T12:00:00Z");
const ago = (h: number) => new Date(NOW.getTime() - h * 3_600_000).toISOString();

type EstateDevice = NonNullable<NonNullable<OverviewView["estate"]>["devices"]>[number];
const dev = (n: number, extra: Partial<EstateDevice> = {}): EstateDevice => ({
  device_id: `dev-${n}-aaaa`, hostname: `FW-TANGO-0${n}`, vendor: "check_point", model: null, condition: "clear",
  no_archive: false, critical_fail: 0, last_read_at: ago(1), backup_target: true, last_backup_at: ago(10), ...extra,
});

function overview(devices: EstateDevice[], extra: Record<string, unknown> = {}): OverviewView {
  return {
    generated_at: NOW.toISOString(), masked: true,
    denominators: { active_devices: devices.length, gateways: devices.length, clusters: 0, backup_targets: devices.length },
    evidence: { inventory: { at: ago(1), state: "OK" }, configuration: { at: ago(1), state: "OK" }, compliance: { at: ago(2), state: "OK" },
      backup: { at: ago(3), state: "OK" }, jobs: { at: ago(1), state: "OK" }, platform_facts: { at: ago(1), state: "OK" } },
    attention: { failed_jobs_24h: { count: 0, terminal_24h: 0, state: "OK" }, stale_inventory: { count: 0, of: devices.length, state: "OK" },
      cluster_diff: { count: 0, of: 0, state: "OK" }, config_changed: { count: 0, of: 0, state: "OK" }, backup_missing: { count: 0, of: devices.length, state: "OK" } },
    exceptions: { failed_jobs: { total: 0, rows: [], reasons: [] }, config_changes: { total: 0, rows: [] }, cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] } },
    inventory_age: { lt24h: devices.length, h24_72: 0, gt72h: 0, never: 0, of: devices.length },
    compliance: { state: "OK" },
    platform: { devices: devices.length, clusters: 0, evidence_at: ago(1) },
    nexus: { completed_24h: 3, running: 0, oldest_running_submitted_at: null, last_inventory: { check_point: ago(1), palo_alto: ago(1) } },
    estate: { devices, counts: { critical: 0, ageing: 0, partly_assessed: 0, not_assessed: 0, clear: devices.length }, no_archive: 0, compliance_state: "OK",
      facts: { critical: { k: 0, n: devices.length }, backups: { a: devices.length, b: devices.length }, evidence: { r: devices.length, d: devices.length, never: 0 } } },
    ...extra,
  } as unknown as OverviewView;
}

const EMPTY_FAILOVER: SourceRead<{ devices: DeviceSummary[]; rows: CpFailoverSummary[] }> = { state: "ok", value: { devices: [], rows: [] } };
const EMPTY_LIFECYCLE: SourceRead<LifecycleFleet> = { state: "ok", value: { as_of: NOW.toISOString(), devices: [], summary: {} } };
const EMPTY_CHANGES = { state: "ok", value: { events: [] } as unknown as ChangeFeedPage } as SourceRead<ChangeFeedPage>;
const inputs = (o: OverviewView, extra: Partial<AttentionInputs> = {}): AttentionInputs =>
  ({ now: NOW, overview: o, failover: EMPTY_FAILOVER, lifecycle: EMPTY_LIFECYCLE, changes: EMPTY_CHANGES, controls: [], ...extra });
const keys = (rows: readonly AttentionRow[]) => rows.map((r) => r.key);
const find = (rows: readonly AttentionRow[], key: string) => rows.find((r) => r.key === key)!;
const FAILED_SECTION = { state: "READ_FAILED", reason: "SQLException" };

describe("attention rules: null fields in the overview body", () => {
  it("devices with a null hostname, read time, backup time and critical count: named by id, never a crash", () => {
    const o = overview([dev(1, { hostname: null, last_read_at: null, last_backup_at: null, no_archive: true, critical_fail: null })]);
    const r = buildAttention(inputs(o), undefined);
    expect(keys(r.rows)).toEqual(["backup-missing", "not-read"]);
    expect(find(r.rows, "backup-missing").names).toEqual(["dev-1-aa"]);
    expect(find(r.rows, "not-read").what).toBe("1 device has never been read");
  });

  it("a null critical count is not a failing critical check", () => {
    const o = overview([dev(1, { critical_fail: null }), dev(2, { critical_fail: 2 })]);
    expect(find(buildAttention(inputs(o), undefined).rows, "compliance-critical")).toMatchObject({ count: 1, names: ["FW-TANGO-02"] });
  });

  it("a failed estate section is UNKNOWN (overview + compliance), not an empty list of backup and read findings", () => {
    const o = overview([dev(1)], { estate: FAILED_SECTION });
    const r = buildAttention(inputs(o), undefined);
    expect(keys(r.rows)).toEqual(["unknown-overview", "unknown-compliance"]);
    expect(find(r.rows, "unknown-overview").what).toBe("The overview's device list could not be read");
    expect(r.sources.find((s) => s.key === "overview")?.state).toBe("failed");
    expect(unreadableOverviewParts(o)).toEqual(["device list"]);
  });

  it("a failed exceptions section is UNKNOWN, while the estate rules still run", () => {
    const o = overview([dev(1, { no_archive: true })], { exceptions: { state: "READ_FAILED" } });
    const r = buildAttention(inputs(o), undefined);
    expect(keys(r.rows)).toEqual(["backup-missing", "unknown-overview"]);
    expect(find(r.rows, "unknown-overview").what).toBe("The overview's failed jobs could not be read");
  });

  it("both parts failed are named together", () => {
    const o = overview([], { estate: FAILED_SECTION, exceptions: FAILED_SECTION });
    expect(find(buildAttention(inputs(o), undefined).rows, "unknown-overview").what).toBe("The overview's device list and failed jobs could not be read");
  });

  it("failed jobs counted but the row list missing: still a row, no names, no crash", () => {
    const o = overview([dev(1)], { exceptions: { failed_jobs: { total: 4 } } });
    expect(find(buildAttention(inputs(o), undefined).rows, "failed-jobs")).toMatchObject({ count: 4, names: [] });
  });

  it("an overview with every section failed and the other sources unread builds a list without throwing", () => {
    const o = { generated_at: NOW.toISOString(), masked: true, denominators: { active_devices: 2, gateways: 2, clusters: 0, backup_targets: 2 },
      evidence: FAILED_SECTION, attention: FAILED_SECTION, exceptions: FAILED_SECTION, inventory_age: FAILED_SECTION, compliance: FAILED_SECTION,
      platform: FAILED_SECTION, nexus: FAILED_SECTION, policy_install: FAILED_SECTION, estate: FAILED_SECTION } as unknown as OverviewView;
    const r = buildAttention(inputs(o, { failover: { state: "failed" }, lifecycle: { state: "failed" }, changes: { state: "failed" } }), undefined);
    expect(r.rows.every((row) => row.severity === "unknown")).toBe(true);
    expect(keys(r.rows)).toEqual(["unknown-overview", "unknown-failover", "unknown-compliance", "unknown-lifecycle", "unknown-changes"]);
  });

  it("readableEstate gives the estate only when the server built it", () => {
    expect(readableEstate(overview([dev(1)]).estate)).not.toBeNull();
    expect(readableEstate(FAILED_SECTION as unknown as OverviewView["estate"])).toBeNull();
    expect(readableEstate(undefined)).toBeNull();
  });

  it("readAges is null (UNKNOWN) for a failed section or a missing bucket, never zeros", () => {
    expect(readAges(FAILED_SECTION as unknown as OverviewView["inventory_age"])).toBeNull();
    expect(readAges({ lt24h: 1, h24_72: 0, gt72h: 0, of: 1 })).toBeNull();
    expect(readAges({ lt24h: 1, h24_72: 0, gt72h: 0, never: 0, of: 1 })).toEqual({ lt24h: 1, h24_72: 0, gt72h: 0, never: 0, of: 1 });
  });
});

describe("attention rules: one rule that throws leaves one 'could not be evaluated' row", () => {
  it("a null entry in the estate device list: each estate rule is UNKNOWN, the failed-jobs rule still answers", () => {
    const o = overview([dev(1)], {
      exceptions: { failed_jobs: { total: 2, rows: [], reasons: [] } },
    });
    (o.estate!.devices as unknown as unknown[]).push(null);
    const r = buildAttention(inputs(o), undefined);
    expect(keys(r.rows).sort()).toEqual(["failed-jobs", "unevaluated-backup-missing", "unevaluated-backup-stale", "unevaluated-compliance-critical",
      "unevaluated-not-read", "unevaluated-read-ageing"].sort());
    const bad = find(r.rows, "unevaluated-backup-stale");
    expect(bad).toMatchObject({ severity: "unknown", count: 0, names: [], href: RULES["backup-stale"].href, linkLabel: RULES["backup-stale"].linkLabel });
    expect(bad.what).toBe("This check could not be evaluated: backup targets with an old newest backup");
    expect(find(r.rows, "failed-jobs").count).toBe(2);
  });

  it("a license list that is not a list: the license rules are UNKNOWN, the advisory and Live Patch rules still answer", () => {
    const life = (n: number, extra: Record<string, unknown>): LifecycleDevice => ({
      device_id: `dev-${n}-aaaa`, hostname: `FW-TANGO-0${n}`, vendor: "check_point", licenses: [], ...extra } as unknown as LifecycleDevice);
    const lifecycle: SourceRead<LifecycleFleet> = { state: "ok", value: { as_of: NOW.toISOString(), summary: {}, devices: [
      life(1, { vulnerabilities: { state: "EVALUATED", affected_exploited: 1, affected_critical_high: 0 } }),
      life(2, { licenses: 5 }),
      life(3, { live_patch: { missing: true } }),
    ] } as unknown as LifecycleFleet };
    const r = buildAttention(inputs(overview([dev(1), dev(2), dev(3)]), { lifecycle }), undefined);
    expect(keys(r.rows).sort()).toEqual(["advisory-exploited", "live-patch-missing", "unevaluated-license-30", "unevaluated-license-60", "unevaluated-license-expired"].sort());
  });

  it("a lifecycle read whose device list is not a list: every lifecycle rule is UNKNOWN", () => {
    const lifecycle = { state: "ok", value: { as_of: NOW.toISOString(), devices: null, summary: {} } } as unknown as SourceRead<LifecycleFleet>;
    const r = buildAttention(inputs(overview([dev(1)]), { lifecycle }), undefined);
    expect(keys(r.rows).sort()).toEqual(["advisory-exploited", "advisory-critical-high", "license-expired", "license-30", "license-60", "live-patch-missing"]
      .map((k) => `unevaluated-${k}`).sort());
  });

  it("a failover summary with a null row: both HA rules are UNKNOWN", () => {
    const failover = { state: "ok", value: { devices: [], rows: [null] } } as unknown as AttentionInputs["failover"];
    const r = buildAttention(inputs(overview([dev(1)]), { failover }), undefined);
    expect(keys(r.rows).sort()).toEqual(["unevaluated-ha-not-ready", "unevaluated-ha-unknown"]);
  });

  it("a change feed with a null event: the change rule is UNKNOWN", () => {
    const changes = { state: "ok", value: { events: [null] } } as unknown as AttentionInputs["changes"];
    expect(keys(buildAttention(inputs(overview([dev(1)]), { changes }), undefined).rows)).toEqual(["unevaluated-change-unknown-actor"]);
  });

  it("a 'could not be evaluated' row is shown only to a session that may open the rule's screen", () => {
    const changes = { state: "ok", value: { events: [null] } } as unknown as AttentionInputs["changes"];
    expect(buildAttention(inputs(overview([dev(1)]), { changes }), ["Devices"]).rows).toEqual([]);
    expect(keys(buildAttention(inputs(overview([dev(1)]), { changes }), ["Policy"]).rows)).toEqual(["unevaluated-change-unknown-actor"]);
  });

  it("an UNKNOWN row sorts before Watch rows and after Act now / Review", () => {
    const o = overview([dev(1, { no_archive: true, last_read_at: ago(30) })]);
    const changes = { state: "ok", value: { events: [null] } } as unknown as AttentionInputs["changes"];
    expect(keys(buildAttention(inputs(o, { changes }), undefined).rows)).toEqual(["backup-missing", "unevaluated-change-unknown-actor", "read-ageing"]);
  });
});

describe("Overview screen: a failed or null section reads UNKNOWN", () => {
  function stubAll(body: unknown) {
    vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      const payload = url.includes("/compliance/controls") ? { total_controls: 0, controls: [] }
        : url === "/devices" ? { devices: [] }
        : url.includes("/cp-failover/summary") ? []
        : url.startsWith("/api/v2/lifecycle") ? { as_of: NOW.toISOString(), devices: [], summary: {} }
        : url.startsWith("/api/v2/changes/feed") ? { events: [] }
        : body;
      return Promise.resolve(new Response(JSON.stringify(payload), { status: 200 }));
    }));
  }
  const allFailed = () => ({ generated_at: NOW.toISOString(), masked: true, denominators: { active_devices: 4, gateways: 4, clusters: 0, backup_targets: 4 },
    evidence: FAILED_SECTION, attention: FAILED_SECTION, exceptions: FAILED_SECTION, inventory_age: FAILED_SECTION, compliance: FAILED_SECTION,
    platform: FAILED_SECTION, nexus: FAILED_SECTION, policy_install: FAILED_SECTION, estate: FAILED_SECTION });

  it("renders when every section failed: an UNKNOWN row, UNKNOWN fleet facts, no zeros", async () => {
    stubAll(allFailed());
    render(<OverviewScreen />);
    const list = await screen.findByTestId("attention-list");
    const row = (await within(list).findByText("The overview's device list and failed jobs could not be read")).closest("[data-attention]") as HTMLElement;
    expect(row.getAttribute("data-attention")).toBe("unknown-overview");
    const fleet = screen.getByTestId("fleet-summary");
    expect(fleet.querySelector('[data-fact="backups"]')?.textContent).toContain("UNKNOWN");
    expect(fleet.querySelector('[data-fact="freshness"]')?.textContent).toContain("UNKNOWN");
    expect(screen.getByText("Compliance could not be read — UNKNOWN.")).toBeInTheDocument();
    expect(screen.getByText("Data freshness UNKNOWN")).toBeInTheDocument();
  });

  it("the wall layout says UNKNOWN for the tiles, the queue and the failed jobs, never 0 or 'no failed jobs'", async () => {
    stubAll(allFailed());
    const { container } = render(<DisplayModeProvider search="?wall=1"><OverviewScreen /></DisplayModeProvider>);
    expect(await screen.findByText(/Failed jobs could not be read — UNKNOWN\./)).toBeInTheDocument();
    expect(screen.getByText("UNKNOWN — the failed jobs of the last 24 h could not be read.")).toBeInTheDocument();
    expect(screen.queryByText("No failed jobs in the last 24 h.")).toBeNull();
    expect(screen.queryByText("No job failed in 24 h.")).toBeNull();
    expect(screen.getByText(/Cluster member differences could not be read — UNKNOWN\./)).toBeInTheDocument();
    expect(screen.getByText(/Device read ages could not be read — UNKNOWN\./)).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/undefined|NaN/);
  });

  it("a failed-jobs row without a device or label renders in the card", async () => {
    const o = overview([dev(1)], { exceptions: { failed_jobs: { total: 1, reasons: [], rows: [
      { job_id: "j1", job_type: "cp_policy_collect", device_id: null, label: null, terminal_reason: null, finished_at: ago(1) },
    ] }, config_changes: { total: 0, rows: [] }, cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] } } });
    stubAll(o);
    render(<OverviewScreen />);
    expect(await screen.findByText("1 job failed in the last 24 h")).toBeInTheDocument();
  });

  it("the estate details say UNKNOWN for failed platform, read-age and queue sections", () => {
    const o = overview([dev(1)], { inventory_age: FAILED_SECTION, platform: FAILED_SECTION, nexus: FAILED_SECTION, policy_install: FAILED_SECTION });
    const { container } = render(<EstateDetails data={o} />);
    expect(screen.getByTestId("read-ages-unknown")).toBeInTheDocument();
    expect(screen.getAllByText("UNKNOWN devices").length).toBe(2);
    expect(container.textContent).toContain("completed 24 h UNKNOWN");
    expect(container.textContent).toContain("running UNKNOWN");
    expect(container.textContent).toContain("oldest running UNKNOWN");
    expect(container.textContent).not.toMatch(/undefined|NaN/);
  });

  it("a failed section whose read ages are fine elsewhere keeps its figures (no false UNKNOWN)", () => {
    const { container } = render(<EstateDetails data={overview([dev(1), dev(2)])} />);
    expect(screen.queryByTestId("read-ages-unknown")).toBeNull();
    expect(container.textContent).toContain("2 active devices");
    expect(container.textContent).toContain("completed 24 h 3");
  });
});

describe("failed jobs total absent or null: UNKNOWN in the list and on the card, never 'no failed jobs'", () => {
  const cases: [string, Record<string, unknown>][] = [
    ["absent", { rows: [], reasons: [] }],
    ["null", { total: null, rows: [], reasons: [] }],
  ];
  it.each(cases)("the attention list shows an unknown row naming failed jobs (total %s)", (_label, failed) => {
    const o = overview([dev(1)], { exceptions: { failed_jobs: failed, config_changes: { total: 0, rows: [] }, cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] } } });
    expect(unreadableOverviewParts(o)).toEqual(["failed jobs"]);
    const r = buildAttention(inputs(o), undefined);
    expect(keys(r.rows)).toContain("unknown-overview");
    expect(keys(r.rows)).not.toContain("failed-jobs");
    expect(find(r.rows, "unknown-overview").what).toBe("The overview's failed jobs could not be read");
    expect(JSON.stringify(r.rows)).not.toMatch(/No failed jobs/);
  });

  it.each(cases)("the FailedJobsCard shows UNKNOWN wording (total %s)", async (_label, failed) => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      const payload = url.includes("/compliance/controls") ? { total_controls: 0, controls: [] }
        : url === "/devices" ? { devices: [] }
        : url.includes("/cp-failover/summary") ? []
        : url.startsWith("/api/v2/lifecycle") ? { as_of: NOW.toISOString(), devices: [], summary: {} }
        : url.startsWith("/api/v2/changes/feed") ? { events: [] }
        : overview([dev(1)], { exceptions: { failed_jobs: failed, config_changes: { total: 0, rows: [] }, cluster_diff: { total: 0, unknown: 0, all_refs: [], rows: [] } } });
      return Promise.resolve(new Response(JSON.stringify(payload), { status: 200 }));
    }));
    render(<DisplayModeProvider search="?wall=1"><OverviewScreen /></DisplayModeProvider>);
    expect(await screen.findByText("UNKNOWN — the failed jobs of the last 24 h could not be read.")).toBeInTheDocument();
    expect(screen.queryByText("No failed jobs in the last 24 h.")).toBeNull();
  });
});
