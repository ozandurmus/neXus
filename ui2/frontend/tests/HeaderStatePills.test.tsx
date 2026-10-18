import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme, m3 } from "../src/theme/m3Theme";
import { DeviceInventoryPanels, ClusterDetailPanels } from "../src/screens/InventoryPanels";
import { DeviceConfigurationDetail } from "../src/screens/ConfigurationDetail";
import {
  HeaderStateRow, backupFact, clusterComplianceFact, clusterConfigChangedFact, complianceCounts, complianceFact, configChangedFact, readinessFact,
} from "../src/screens/HeaderStatePills";
import type { BackupArtefact, CpFailoverSummary, DeviceConfiguration, DeviceSummary } from "../src/auth/adminApi";

/**
 * The object header's state row, part 2 (UI product review 2026-10-18 §4.2, roadmap 2.1): Compliance · Config changed ·
 * Backup · Readiness · Certificate, in that order, each a word with a count or an age, each a link to the filtered fleet
 * screen. No composite score; only a problem word is tinted; an absent read is an absent pill. Synthetic ids only.
 */
const H = 3_600_000;
const ago = (ms: number) => new Date(Date.now() - ms).toISOString();
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
afterEach(() => vi.unstubAllGlobals());

const device = (over: Partial<DeviceSummary> = {}): DeviceSummary => ({
  device_id: "dev-1", hostname: "FW-SYNTH-01", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", model: "Quantum",
  software_version: "R81.20", ha_role: null, cluster_member_ref: null, ip_addresses: "192.0.2.10", backup_target: true, ...over,
});
const item = (severity: string, displayStatus: string) => ({ controlId: `c-${severity}-${displayStatus}-${Math.random()}`, title: "t", severity, frameworks: [], verdict: displayStatus, reasonCode: "", displayStatus });
const items = (critical: number, fail: number, pass: number, unavailable: number, na = 0) => [
  ...Array.from({ length: critical }, () => item("CRITICAL", "FAIL")), ...Array.from({ length: fail }, () => item("HIGH", "FAIL")),
  ...Array.from({ length: pass }, () => item("LOW", "PASS")), ...Array.from({ length: unavailable }, () => item("MEDIUM", "DATA_UNAVAILABLE")),
  ...Array.from({ length: na }, () => item("LOW", "NOT_APPLICABLE")),
];
const backup = (collectedAt: string, validation = "V1"): BackupArtefact => ({ artefact_id: `a-${collectedAt}`, device_id: "dev-1", collected_at: collectedAt, size_bytes: 10, digest_prefix: "ab", validation_level: validation, deviation_state: null });
const summaryRow = (status: "READY" | "NOT_READY" | "UNKNOWN" | null, observedAt = ago(4 * H)): CpFailoverSummary => ({
  unitId: "u-1", clusterId: "u-1", cluster_member_ref: "CLS-SYNTH-01", vendor: "check_point", activeWindow: false, lastRunState: null, lastRunOutcome: null, lastRunAt: null, canRunReadiness: false,
  readiness: status ? { status, observedAt, failedCheck: status === "NOT_READY" ? "Check 3" : "", stopCode: status === "NOT_READY" ? "CLUSTER_STATE_NOT_READY" : undefined, checks: [] } : null,
});
const configuration = (over: Partial<DeviceConfiguration> = {}): DeviceConfiguration => ({ device_id: "dev-1", collected_at: ago(10 * H), vendor: "check_point", read_kind: "cp_show_configuration", canonical_hash: "h",
  change_state: "changed", change_deviation_summary: { status: "CHANGED", entries: [{ context: "physical", section: "interfaces", kind: "modified", old_count: 1, new_count: 2 }, { context: "physical", section: "routes", kind: "added", old_count: 0, new_count: 1 }, { context: "physical", section: "interfaces", kind: "removed", old_count: 1, new_count: 0 }] },
  withheld_line_count: 0, sanitized_text_available: false, text_readable: false, index: [], overrides: [], supplementary_runs: [], ...over });

describe("the fact words", () => {
  it("counts compliance checks as the fleet does: NOT_APPLICABLE is not a check, anything but PASS/FAIL is a gap, critical = CRITICAL and FAIL", () => {
    expect(complianceCounts({ items: items(3, 2, 50, 4, 8) })).toEqual({ critical: 3, pass: 50, fail: 5, unavailable: 4, checked: 59 });
    const fact = complianceFact({ items: items(3, 2, 50, 4, 8) }, { configurationRead: true, vendor: "check_point" })!;
    expect(fact.label).toBe("Compliance 3 critical failing · 59 checked");
    expect(fact.tone).toBe("bad");
    expect(fact.href).toBe("?screen=compliance&result=fail&severity=critical&vendor=check_point");
    expect(fact.title).toBe("50 passing · 5 failing · 4 without evidence");
  });

  it("writes zero critical as a grey 0, never green, and says Not read yet / Not assessed instead of 0 checked", () => {
    expect(complianceFact({ items: items(0, 1, 10, 0) }, { configurationRead: true })).toMatchObject({ label: "Compliance 0 critical failing · 11 checked", tone: "neutral" });
    expect(complianceFact({ items: [] }, { configurationRead: false })).toMatchObject({ label: "Compliance · Not read yet", tone: "neutral" });
    expect(complianceFact({ items: [] }, { configurationRead: true })).toMatchObject({ label: "Compliance · Not assessed", tone: "neutral" });
    expect(complianceFact(null, { configurationRead: true })).toBeNull();
  });

  it("pools a cluster's checks over its members with the split on hover", () => {
    const fact = clusterComplianceFact([
      { label: "M1", result: { items: items(3, 0, 56, 0) }, configurationRead: true },
      { label: "M2", result: { items: items(0, 1, 58, 0) }, configurationRead: true },
    ], "check_point")!;
    expect(fact.label).toBe("Compliance 3 critical failing · 118 checked");
    expect(fact.title).toBe("M1: 3 critical failing · 59 checked · M2: 0 critical failing · 59 checked");
    expect(clusterComplianceFact([{ label: "M1", result: null, configurationRead: false }], "check_point")).toBeNull();
  });

  it("counts the distinct sections a configuration change touched, stays grey, and leaves out an unread configuration", () => {
    expect(configChangedFact(configuration())).toMatchObject({ label: "Config changed · 2 sections", tone: "neutral", href: "?screen=inventory&change_state=changed" });
    expect(configChangedFact(configuration({ change_deviation_summary: null }))).toMatchObject({ label: "Config changed" });
    expect(configChangedFact(configuration({ change_state: "unchanged" }))).toMatchObject({ label: "Config unchanged" });
    expect(configChangedFact(configuration({ change_state: "first_run" }))).toMatchObject({ label: "Config first read" });
    expect(configChangedFact(configuration({ collected_at: null }))).toBeNull();
    expect(configChangedFact(null)).toBeNull();
    expect(clusterConfigChangedFact([{ label: "M1", configuration: configuration() }, { label: "M2", configuration: configuration({ change_state: "unchanged" }) }])?.label).toBe("Config changed · M1 2 sections");
    expect(clusterConfigChangedFact([{ label: "M1", configuration: configuration({ change_state: "unchanged" }) }])?.label).toBe("Config unchanged");
  });

  it("words the newest backup with its age and validation word, no archive in amber for a target, not a target in grey, and nothing for a failed read", () => {
    const now = new Date();
    expect(backupFact([backup(ago(30 * H)), backup(ago(8 * H)), backup(ago(56 * H), "NONE")], device(), "?screen=backups&q=dev-1", "", now))
      .toMatchObject({ label: "Backup 8 h ago · Checked", tone: "neutral", href: "?screen=backups&q=dev-1" });
    expect(backupFact([], device(), "?x")).toMatchObject({ label: "Backup · no archive", tone: "warn" });
    expect(backupFact([], device({ backup_target: false }), "?x")).toMatchObject({ label: "Backup · not a target", tone: "neutral" });
    expect(backupFact([backup(ago(H))], device(), "?x", "M2")).toMatchObject({ label: "Backup M2 1 h ago · Checked", key: "backup-M2" });
    expect(backupFact(null, device(), "?x")).toBeNull();
    expect(backupFact(undefined, device(), "?x")).toBeNull();
  });

  it("words readiness as Ready / Not ready / Unknown with its age, Not read yet without a record, nothing without rows", () => {
    expect(readinessFact([summaryRow("READY")], "CLS-SYNTH-01")).toMatchObject({ label: "Readiness Ready · 4 h ago", tone: "ok", href: "?screen=operations&cluster_ref=CLS-SYNTH-01" });
    const notReady = readinessFact([summaryRow("NOT_READY")], "CLS-SYNTH-01")!;
    expect(notReady).toMatchObject({ label: "Readiness Not ready · 4 h ago", tone: "bad" });
    expect(notReady.title).toContain("Cluster state not ready");
    expect(readinessFact([summaryRow("UNKNOWN")], "CLS-SYNTH-01")).toMatchObject({ label: "Readiness Unknown · 4 h ago", tone: "neutral" });
    expect(readinessFact([summaryRow(null)], "CLS-SYNTH-01")).toMatchObject({ label: "Readiness · Not read yet", tone: "neutral" });
    expect(readinessFact([], "CLS-SYNTH-01")).toMatchObject({ label: "Readiness · Not read yet" });
    expect(readinessFact(null, "CLS-SYNTH-01")).toBeNull();
    // a virtual system's unit never stands in for the cluster's own
    const vs = { ...summaryRow("NOT_READY"), unitId: "vs-13" };
    expect(readinessFact([vs, summaryRow("READY")], "CLS-SYNTH-01")?.label).toBe("Readiness Ready · 4 h ago");
  });

  it("renders the pills in the order given, as links, and leaves absent facts out", () => {
    render(<HeaderStateRow facts={[null, { key: "a", label: "A word", tone: "bad", href: "?a" }, null, { key: "b", label: "B word", tone: "neutral" }]} />);
    const row = screen.getByTestId("header-state-row");
    expect(row.textContent).toBe("A wordB word");
    expect(within(row).getByRole("link", { name: "A word" })).toHaveAttribute("href", "?a");
    expect(within(row).getByText("A word")).toHaveStyle({ backgroundColor: m3.errorContainer });
    expect(within(row).getByText("B word")).toHaveStyle({ backgroundColor: m3.scHigh });
  });
});

const mount = (node: React.ReactElement) => render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>);
function stub(routes: Record<string, () => Promise<Response>>) {
  const fetchMock = vi.fn((url: string) => (routes[url] ?? (() => json({ error: "NOT_FOUND" }, 404)))());
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}
const base = (d: DeviceSummary): Record<string, () => Promise<Response>> => ({
  [`/devices/${d.device_id}/inventory`]: () => json({ device_id: d.device_id, collected_at: ago(2 * H), contexts: [] }),
  [`/devices/${d.device_id}/https-certificate`]: () => json({ available: false }),
  [`/devices/${d.device_id}/lifecycle`]: () => json({ device_id: d.device_id, risk: "LOW", status: "MATCHED", hardware: {}, software: {} }),
});
const row = async () => screen.findByTestId("header-state-row");
const pillTexts = (el: HTMLElement) => Array.from(el.querySelectorAll("[data-fact]")).map((p) => p.textContent);

describe("the device header's state row", () => {
  it("shows Compliance · Config changed · Backup on a standalone firewall, in plane order, each linking to its fleet screen", async () => {
    const d = device({ platform_facts_observed_at: ago(4 * H) });
    stub({ ...base(d),
      "/devices/dev-1/configuration": () => json(configuration()),
      "/devices/dev-1/compliance": () => json({ device_id: "dev-1", hostname: "FW-SYNTH-01", vendor: "check_point", totalAssigned: 59, passCount: 56, failCount: 3, dataUnavailableCount: 0, observedCompliance: 0, evidenceCoverage: 0, assuredCompliance: 0, items: items(3, 0, 56, 0) }),
      "/devices/dev-1/backups": () => json({ backups: [backup(ago(8 * H))] }),
    });
    mount(<DeviceInventoryPanels device={d} configuration={<DeviceConfigurationDetail device={d} embedded />} />);
    const r = await row();
    await waitFor(() => expect(pillTexts(r)).toEqual(["Compliance 3 critical failing · 59 checked", "Config changed · 2 sections", "Backup 8 h ago · Checked"]));
    expect(within(r).getByRole("link", { name: /^Compliance/ })).toHaveAttribute("href", "?screen=compliance&result=fail&severity=critical&vendor=check_point");
    expect(within(r).getByRole("link", { name: /^Config changed/ })).toHaveAttribute("href", "?screen=inventory&change_state=changed");
    expect(within(r).getByRole("link", { name: /^Backup/ })).toHaveAttribute("href", "?screen=backups&q=dev-1");
    expect(within(r).getByText(/^Compliance/)).toHaveStyle({ backgroundColor: m3.errorContainer });
    // no readiness on a standalone device; the read ages stay on the same row
    expect(within(r).queryByText(/^Readiness/)).toBeNull();
    expect(screen.getByTestId("header-read-ages")).toHaveTextContent("Platform read 4 h ago");
  });

  it("adds Readiness for a cluster member and links its cluster chip to the parent, and the certificate pill closes the row", async () => {
    const d = device({ cluster_member_ref: "CLS-SYNTH-01", ha_role: "ACTIVE" });
    const fetchMock = stub({ ...base(d),
      "/clusters/CLS-SYNTH-01/inventory": () => json({ cluster_member_ref: "CLS-SYNTH-01", members: [{ device_id: "dev-1", hostname: "FW-SYNTH-01" }], contexts: [], virtual_systems: [] }),
      "/devices/dev-1/https-certificate": () => json({ available: true, strict: false, certificates: [{ trust_entry_id: "t-1", status: "ACTIVE", fingerprint_sha256: "00", subject_cn: "FW-SYNTH-01", issuer_cn: "Synthetic CA", not_after: ago(-223 * 24 * H) }] }),
      "/devices/dev-1/configuration": () => json(configuration({ change_state: "unchanged" })),
      "/devices/dev-1/backups": () => json({ backups: [] }),
      "/api/v2/cp-failover/summary": () => json([summaryRow("NOT_READY")]),
      "/devices/dev-1/compliance": () => json({ items: [] }),
    });
    const onOpenCluster = vi.fn();
    mount(<DeviceInventoryPanels device={d} onOpenCluster={onOpenCluster} configuration={<DeviceConfigurationDetail device={d} embedded />} />);
    const r = await row();
    await waitFor(() => expect(pillTexts(r)).toEqual(["Compliance · Not assessed", "Config unchanged", "Backup · no archive", "Readiness Not ready · 4 h ago"]));
    expect(within(r).getByText("Backup · no archive")).toHaveStyle({ backgroundColor: m3.warningContainer });
    expect(within(r).getByText(/^Readiness/)).toHaveStyle({ backgroundColor: m3.errorContainer });
    expect(within(r).getByRole("link", { name: /^Readiness/ })).toHaveAttribute("href", "?screen=operations&cluster_ref=CLS-SYNTH-01");
    expect(within(r).getByRole("link", { name: /^Backup/ })).toHaveAttribute("href", "?screen=backups&q=CLS-SYNTH-01");
    // the certificate state is the last pill of the row, not an identity chip
    const chips = Array.from(r.querySelectorAll(".MuiChip-root")).map((c) => c.textContent);
    expect(chips.some((c) => /^Certificate expires in/.test(c ?? ""))).toBe(true);
    expect(within(screen.getByTestId("entity-header")).getAllByText(/^Certificate expires in/)).toHaveLength(1);
    expect(fetchMock.mock.calls.some(([u]) => u === "/api/v2/cp-failover/summary")).toBe(true);
    const parent = screen.getByRole("button", { name: "Check Point ClusterXL · CLS-SYNTH-01" });
    fireEvent.click(parent);
    expect(onOpenCluster).toHaveBeenCalledWith("CLS-SYNTH-01");
    expect(screen.queryByRole("tab", { name: "Cluster members" })).toBeNull();
  });

  it("gives a management server Config changed and Backup only: no compliance (never evaluated), no readiness", async () => {
    const d = device({ role: "management_server", vendor_hint: "palo_alto", hostname: "PANO-SYNTH-01" });
    const fetchMock = stub({ ...base(d),
      "/devices/dev-1/configuration": () => json(configuration({ vendor: "palo_alto", read_kind: "pan_effective_running" })),
      "/devices/dev-1/backups": () => json({ backups: [backup(ago(23 * 24 * H))] }),
      "/devices/dev-1/compliance": () => json({ items: items(5, 0, 0, 0) }),
    });
    mount(<DeviceInventoryPanels device={d} configuration={<DeviceConfigurationDetail device={d} embedded />} />);
    const r = await row();
    await waitFor(() => expect(pillTexts(r)).toEqual(["Config changed · 2 sections", "Backup 23 d ago · Checked"]));
    expect(fetchMock.mock.calls.some(([u]) => u === "/devices/dev-1/compliance")).toBe(false);
    expect(fetchMock.mock.calls.some(([u]) => u === "/api/v2/cp-failover/summary")).toBe(false);
  });

  it("makes no claim for a refused backup or compliance read: those pills are absent, the others stay", async () => {
    const d = device();
    stub({ ...base(d),
      "/devices/dev-1/configuration": () => json(configuration({ change_state: "first_run" })),
      "/devices/dev-1/backups": () => json({ error: "ACTION_REFUSED" }, 403),
      "/devices/dev-1/compliance": () => json({ error: "ACTION_REFUSED" }, 403),
    });
    mount(<DeviceInventoryPanels device={d} configuration={<DeviceConfigurationDetail device={d} embedded />} />);
    const r = await row();
    await waitFor(() => expect(pillTexts(r)).toEqual(["Config first read"]));
  });

  it("shows only the Backup pill on an appliance without a configuration read, and says Not read yet for compliance before the first configuration read", async () => {
    const appliance = device({ vendor_hint: "infoblox", role: "appliance" });
    stub({ ...base(appliance), "/devices/dev-1/backups": () => json({ backups: [] }) });
    const first = mount(<DeviceInventoryPanels device={appliance} />);
    await waitFor(() => expect(pillTexts(screen.getByTestId("header-state-row"))).toEqual(["Backup · no archive"]));
    first.unmount();
    const unread = device();
    stub({ ...base(unread),
      "/devices/dev-1/configuration": () => json(configuration({ collected_at: null, change_state: null })),
      "/devices/dev-1/compliance": () => json({ items: [] }),
      "/devices/dev-1/backups": () => json({ backups: [] }),
    });
    mount(<DeviceInventoryPanels device={unread} configuration={<DeviceConfigurationDetail device={unread} embedded />} />);
    await waitFor(() => expect(pillTexts(screen.getByTestId("header-state-row"))).toEqual(["Compliance · Not read yet", "Backup · no archive"]));
  });
});

describe("the cluster header's state row and its one Collect now", () => {
  const members: DeviceSummary[] = [
    device({ device_id: "m-1", hostname: "FW-SYNTH-M1", cluster_member_ref: "CLS-SYNTH-01", ha_role: "ACTIVE" }),
    device({ device_id: "m-2", hostname: "FW-SYNTH-M2", cluster_member_ref: "CLS-SYNTH-01", ha_role: "STANDBY" }),
  ];
  const inventory = () => json({ cluster_member_ref: "CLS-SYNTH-01", members: members.map((m) => ({ device_id: m.device_id, hostname: m.hostname })), virtual_systems: [], contexts: [] });

  it("pools compliance, names the member that changed, gives one Backup pill per member and the cluster's readiness, all as links", async () => {
    stub({
      "/clusters/CLS-SYNTH-01/inventory": inventory,
      "/devices/m-1/backups": () => json({ backups: [backup(ago(8 * H))] }),
      "/devices/m-2/backups": () => json({ backups: [backup(ago(9 * H), "NONE")] }),
      "/devices/m-1/configuration": () => json(configuration()),
      "/devices/m-2/configuration": () => json(configuration({ change_state: "unchanged" })),
      "/devices/m-1/compliance": () => json({ items: items(3, 0, 56, 0) }),
      "/devices/m-2/compliance": () => json({ items: items(0, 0, 59, 0) }),
      "/api/v2/cp-failover/summary": () => json([summaryRow("READY")]),
    });
    mount(<ClusterDetailPanels clusterRef="CLS-SYNTH-01" members={members} configuration={<div>cfg</div>} />);
    const r = await row();
    await waitFor(() => expect(pillTexts(r)).toEqual([
      "Compliance 3 critical failing · 118 checked", "Config changed · M1 2 sections", "Backup M1 8 h ago · Checked", "Backup M2 9 h ago · NONE", "Readiness Ready · 4 h ago",
    ]));
    expect(within(r).getByRole("link", { name: /^Readiness/ })).toHaveAttribute("href", "?screen=operations&cluster_ref=CLS-SYNTH-01");
    expect(within(r).getByRole("link", { name: /^Backup M1/ })).toHaveAttribute("href", "?screen=backups&q=CLS-SYNTH-01");
    expect(within(r).getByText(/^Readiness/)).toHaveStyle({ backgroundColor: m3.successContainer });
    // the plain Backups / Readiness links are not repeated once the pills carry them
    expect(within(r).queryByRole("link", { name: "Backups" })).toBeNull();
    expect(within(r).queryByRole("link", { name: "Readiness" })).toBeNull();
  });

  it("falls back to the plain Backups and Readiness links when those reads are refused, so both ways in stay", async () => {
    stub({ "/clusters/CLS-SYNTH-01/inventory": inventory });
    mount(<ClusterDetailPanels clusterRef="CLS-SYNTH-01" members={members} />);
    const header = await screen.findByTestId("entity-header");
    await waitFor(() => expect(within(header).getByRole("link", { name: "Backups" })).toHaveAttribute("href", "?screen=backups&q=CLS-SYNTH-01"));
    expect(within(header).getByRole("link", { name: "Readiness" })).toHaveAttribute("href", "?screen=operations&cluster_ref=CLS-SYNTH-01");
    expect(header.querySelectorAll("[data-fact]")).toHaveLength(0);
  });

  it("keeps the plain Readiness link for a vendor without a readiness check, instead of an UNKNOWN pill", async () => {
    const fortinet = members.map((m) => ({ ...m, vendor_hint: "fortinet" }));
    const fetchMock = stub({ "/clusters/CLS-SYNTH-01/inventory": inventory, "/devices/m-1/backups": () => json({ backups: [] }), "/devices/m-2/backups": () => json({ backups: [] }) });
    mount(<ClusterDetailPanels clusterRef="CLS-SYNTH-01" members={fortinet} />);
    const r = await row();
    await waitFor(() => expect(pillTexts(r)).toEqual(["Backup M1 · no archive", "Backup M2 · no archive"]));
    expect(within(r).getByRole("link", { name: "Readiness" })).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([u]) => u === "/api/v2/cp-failover/summary")).toBe(false);
  });

  it("has exactly one Collect now, in the header, aimed at the member the M1 / M2 pills pick; the member cards carry none", async () => {
    stub({ "/clusters/CLS-SYNTH-01/inventory": inventory });
    mount(<ClusterDetailPanels clusterRef="CLS-SYNTH-01" members={members} />);
    const header = await screen.findByTestId("entity-header");
    expect(screen.getAllByRole("button", { name: "Collect now" })).toHaveLength(1);
    const pills = within(header).getByRole("group", { name: "Collect from member" });
    const [m1, m2] = within(pills).getAllByRole("button");
    expect(m1).toHaveAttribute("aria-pressed", "true");
    expect(m1).toHaveAttribute("title", "FW-SYNTH-M1");
    fireEvent.click(m2);
    expect(m2).toHaveAttribute("aria-pressed", "true");
    expect(m2).toHaveAttribute("title", "FW-SYNTH-M2");
    for (const card of Array.from(header.querySelectorAll("[data-member-card]"))) {
      expect(within(card as HTMLElement).queryByRole("button", { name: /Collect now/ })).toBeNull();
    }
  });
});
