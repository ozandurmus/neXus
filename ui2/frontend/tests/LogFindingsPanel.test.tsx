import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { JobWindowContext } from "../src/shell/JobWindow";
import { SessionContext } from "../src/auth/SessionContext";
import { InventoryScreen } from "../src/screens/InventoryScreen";
import { LogFindingChip } from "../src/screens/LogFindingChip";
import { LogFindingsPanel, chipFromAnswers } from "../src/screens/LogFindingsPanel";
import type { LogCoverageAnswer, LogFinding, LogFindingChipData, LogFindingChipsAnswer, LogFindingsAnswer } from "../src/auth/adminApi";

/**
 * The log findings panel and chip of the detection engine's phase P1. Masked synthetic state only (FW-TANGO-04): the
 * trigger case G1 as the screen shows it, a switched-off engine, a failed read, and the shadow banner.
 */
const DEVICE = "FW-TANGO-04";
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
afterEach(() => vi.unstubAllGlobals());

const envelope = { enabled: true, shadow: false, shadow_until: null, phase: "P1", layers: ["A"], time_basis: "DEVICE_LOCAL" };
const finding = (over: Partial<LogFinding> = {}): LogFinding => ({
  finding_id: "a".repeat(32), device_id: DEVICE, source_key: "cp_messages", layer: "A", rule_id: "layer-a-severity", kind: "MARKER",
  marker_id: "hw.edac.ce", key_token: "CPU_SrcID#1_MC#0_Chan#2_DIMM#0", window_day: "2026-11-24", window_start: "2026-11-24T00:30:00",
  window_end: "2026-11-24T04:54:00", time_basis: "DEVICE_LOCAL", severity: "ATTENTION", severity_rule: "WEIGHT_AT_LEAST_0_5",
  score: { s: 0.8, r: null, c: null, score: null, note: "" }, provenance: "PROVISIONAL",
  counts: { lines: 45, peak_bucket_lines: 10, history_days: 1, baseline_days: 0, presence_days: 0, cohort_devices: 0, prevalence_devices: 0, counts: "" },
  chronic: false, fleet_background: false, labels: ["HISTORY_UNDER_7_DAYS"], incident_group: "0123456789abcdef", coverage_state: "NOT_EVALUABLE",
  evidence_pointers: [{ chunk_id: "chunk-1", offset: 120, length: 160 }],
  versions: { masking_rule_set: "logmask-v8", marker_list: "markers-p1-1", config: "p1-config-1", catalog: null },
  review_state: "OPEN", review_at: null, first_raised_at: null, updated_at: null, ...over,
});
const findings = (list: LogFinding[], over: Partial<LogFindingsAnswer> = {}): LogFindingsAnswer => ({
  ...envelope, device_id: DEVICE, days: 7, truncated: false, findings: list, ...over,
});
const summary = (total: number, evaluated: number, by: Record<string, number> = {}) => ({ windows_total: total, windows_evaluated: evaluated, by_state: by, counts: "recorded windows" });
const coverage = (total: number, evaluated: number, by: Record<string, number> = {}): LogCoverageAnswer => ({
  ...envelope, device_id: DEVICE, window_days: 7, summary: summary(total, evaluated, by),
  sources: [{ source_key: "cp_messages", ...summary(total, evaluated, by) }, { source_key: "cp_fwk_elg", ...summary(1, 0, { UNSUPPORTED: 1 }) }], windows: [],
});

function stub(f: LogFindingsAnswer | number, c: LogCoverageAnswer | number, ack?: () => Promise<Response>) {
  vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
    if (url.includes("/acknowledge")) return ack ? ack() : json({ ...envelope, finding: finding({ review_state: "ACKNOWLEDGED" }) });
    if (url.startsWith("/api/v2/log-findings/coverage")) return typeof c === "number" ? json({ error: "X" }, c) : json(c);
    if (url.startsWith("/api/v2/log-findings?")) return typeof f === "number" ? json({ error: "X" }, f) : json(f);
    if (url.includes("csrf") || url.includes("/session")) return json({ token: "t", csrf_token: "t" });
    void init;
    return json({}, 404);
  }));
}

const view = () => render(
  <ThemeProvider theme={m3Theme}>
    <JobWindowContext.Provider value={{ open: true, label: null }}><LogFindingsPanel deviceId={DEVICE} /></JobWindowContext.Provider>
  </ThemeProvider>);

describe("LogFindingsPanel", () => {
  it("shows the trigger case: the CE burst as an ATTENTION finding with its slot token, counts, history label and coverage", async () => {
    stub(findings([finding(), finding({ finding_id: "b".repeat(32), marker_id: "hw.mce", key_token: "", evidence_pointers: [] })]), coverage(4, 4));
    view();
    await screen.findByText("hw.edac.ce on CPU_SrcID#1_MC#0_Chan#2_DIMM#0");
    expect(screen.getAllByText("45 lines on 2026-11-24, 00:30 to 04:54 (device-local)").length).toBe(2);
    expect(screen.getAllByText("history under 7 days").length).toBe(2);
    expect(screen.getAllByText("ATTENTION").length).toBeGreaterThan(0);
    expect(screen.getByTestId("log-findings-coverage").textContent).toContain("4 of 4 recorded windows evaluated");
    // the panel's own chip: worst open finding with its count
    expect(screen.getByTestId("log-finding-chip").getAttribute("data-state")).toBe("FINDINGS");
    expect(screen.getByText("ATTENTION 2")).toBeTruthy();
    // evidence is a pointer, never text
    expect(screen.getByText(/evidence: 1 pointer \(chunk chunk-1, offset 120\)/)).toBeTruthy();
    expect(document.body.textContent).not.toMatch(/memory read error/);
  });

  it("never reads a device with unevaluated windows as healthy", async () => {
    stub(findings([]), coverage(3, 2, { EVALUATED: 2, GAP: 1 }));
    view();
    const empty = await screen.findByTestId("log-findings-empty");
    expect(empty.textContent).toContain("2 of 3 recorded windows evaluated; 1 UNKNOWN");
    expect(empty.textContent).toContain("not a statement of health");
    expect(screen.getByTestId("log-finding-chip").getAttribute("data-state")).toBe("UNKNOWN");
  });

  it("says 'No findings' only when every recorded window was evaluated", async () => {
    stub(findings([]), coverage(4, 4));
    view();
    const empty = await screen.findByTestId("log-findings-empty");
    expect(empty.textContent).toBe("No findings; 4 of 4 recorded windows evaluated.");
    expect(screen.getByTestId("log-finding-chip").getAttribute("data-state")).toBe("NO_FINDINGS");
  });

  it("explains a switched-off engine instead of showing it as healthy", async () => {
    stub(findings([], { enabled: false }), coverage(0, 0));
    view();
    await screen.findByText("Log detection is switched off");
    expect(screen.getByTestId("log-finding-chip").getAttribute("data-state")).toBe("UNKNOWN");
  });

  it("shows the shadow banner while the shadow period runs", async () => {
    stub(findings([finding()], { shadow: true, shadow_until: "2026-12-08T10:00:00Z" }), coverage(4, 4));
    view();
    const banner = await screen.findByTestId("log-findings-shadow");
    expect(banner.textContent).toContain("2026-12-08");
  });

  it("shows a failed read as UNKNOWN, not as no findings", async () => {
    stub(503, coverage(4, 4));
    view();
    await screen.findByText("Log findings: UNKNOWN");
    expect(screen.queryByTestId("log-findings-empty")).toBeNull();
  });

  it("acknowledges an open finding through the server and shows the server's refusal when it says no", async () => {
    const ack = vi.fn(() => json({ error: "LOG_FINDING_ACK_NOT_PERMITTED" }, 403));
    stub(findings([finding()]), coverage(4, 4), ack);
    view();
    const button = await screen.findByRole("button", { name: /Acknowledge/ });
    fireEvent.click(button);
    await waitFor(() => expect(ack).toHaveBeenCalled());
    await screen.findByRole("alert");
    expect(screen.getByRole("alert").textContent).toContain("LOG_FINDING_ACK_NOT_PERMITTED");
  });

  it("offers no acknowledge button for an INFO or an already acknowledged finding", async () => {
    stub(findings([finding({ severity: "INFO" }), finding({ finding_id: "c".repeat(32), review_state: "ACKNOWLEDGED" })]), coverage(4, 4));
    view();
    await screen.findAllByText("hw.edac.ce on CPU_SrcID#1_MC#0_Chan#2_DIMM#0");
    expect(screen.queryByRole("button", { name: /Acknowledge/ })).toBeNull();
  });

  it("renders P2 Layer-B findings (FLEET_RARE, RATE_SPIKE, NEW_ON_DEVICE, SILENCE) with 100 x S x R x C score, z, baseline, prevalence and vocabulary template_text only", async () => {
    const spikeAndRare = finding({
      finding_id: "1".repeat(32),
      phase: "P2",
      layer: "B",
      rule_id: "layer-b-statistics",
      rule_version: "p2-1",
      kind: "FLEET_RARE",
      marker_id: "tpl.cp_messages",
      key_token: "t-edac0123456789ab",
      severity: "ATTENTION",
      severity_rule: "B_FLEET_RARE_S_AT_LEAST_0_3",
      coverage_state: "EVALUATED",
      score: { s: 0.8, r: 0.7312, c: 1.0, score: 58.5, note: "100 x S x R x C, used to order findings only; severity comes from rules" },
      counts: { lines: 45, peak_bucket_lines: 18, history_days: 21, baseline_days: 14, presence_days: 1, cohort_devices: 57, prevalence_devices: 1, counts: "" },
      labels: ["CATALOG_BASELINE", "CATALOG_PREVALENCE", "HA_ROLE_UNKNOWN"],
      versions: { masking_rule_set: "logmask-v8", marker_list: "markers-p1-1", config: "p2-config-1", catalog: "cat-1", normaliser: "norm-p2-1" },
      layer_b: {
        template_id: "t-edac0123456789ab",
        template_text: "kernel: EDAC MC<NUM>: <NUM> CE <W> on <DIMM_LABEL> (channel:<NUM> slot:<NUM>)",
        kinds: ["FLEET_RARE", "RATE_SPIKE", "NEW_ON_DEVICE"],
        z: 8.45,
        baseline_median: 1,
        baseline_mad: 0,
        device_first_seen: "2026-11-24",
        fleet_first_seen: "2026-11-10",
        prevalence: "1 other of 57 cohort devices",
      },
    });
    const silenceFinding = finding({
      finding_id: "2".repeat(32),
      phase: "P2",
      layer: "B",
      rule_id: "layer-b-statistics",
      rule_version: "p2-1",
      kind: "SILENCE",
      marker_id: "tpl.pan_system",
      source_key: "pan_system",
      key_token: "t-hb9876543210fedc",
      severity: "INFO",
      severity_rule: "B_SILENCE_NOT_HEALTH_CLASSED",
      coverage_state: "EVALUATED",
      score: { s: 0.3, r: 0.12, c: 1.0, score: 3.6, note: "100 x S x R x C, used to order findings only; severity comes from rules" },
      counts: { lines: 0, peak_bucket_lines: 0, history_days: 21, baseline_days: 14, presence_days: 14, cohort_devices: 38, prevalence_devices: 30, counts: "" },
      labels: ["HA_ROLE_UNKNOWN"],
      evidence_pointers: [],
      versions: { masking_rule_set: "logmask-v8", marker_list: "markers-p1-1", config: "p2-config-1", catalog: "cat-1", normaliser: "norm-p2-1" },
      layer_b: {
        template_id: "t-hb9876543210fedc",
        template_text: "system:general:informational <W> heartbeat check <NUM>",
        kinds: ["SILENCE"],
        z: -4.0,
        baseline_median: 4,
        baseline_mad: 0,
        device_first_seen: "2026-10-20",
        fleet_first_seen: "2026-10-18",
        prevalence: "30 other of 38 cohort devices",
      },
    });

    stub(findings([spikeAndRare, silenceFinding], { phase: "P2", layers: ["A", "B"], p2_enabled: true }), coverage(4, 4));
    view();

    await screen.findByText(/layers A & B: severity markers, template catalog, rarity and rate/);
    expect(screen.getByText("FLEET_RARE")).toBeTruthy();
    expect(screen.getByText("RATE_SPIKE")).toBeTruthy();
    expect(screen.getByText("NEW_ON_DEVICE")).toBeTruthy();
    expect(screen.getByText("SILENCE")).toBeTruthy();

    const templates = screen.getAllByTestId("finding-template-text").map((el) => el.textContent);
    expect(templates).toEqual([
      "kernel: EDAC MC<NUM>: <NUM> CE <W> on <DIMM_LABEL> (channel:<NUM> slot:<NUM>)",
      "system:general:informational <W> heartbeat check <NUM>",
    ]);
    expect(screen.getAllByText(/catalog cat-1 · norm-p2-1/).length).toBe(2);

    const stats = screen.getAllByTestId("finding-layer-b-stats").map((el) => el.textContent);
    expect(stats[0]).toContain("baseline median 1/h, MAD 0 (peak 18/h)");
    expect(stats[0]).toContain("z 8.45");
    expect(stats[0]).toContain("prevalence: 1 other of 57 cohort devices");
    expect(stats[1]).toContain("baseline median 4/h, MAD 0");
    expect(stats[1]).toContain("z -4");
    expect(stats[1]).toContain("prevalence: 30 other of 38 cohort devices");

    const scores = screen.getAllByTestId("finding-score").map((el) => el.textContent);
    expect(scores[0]).toContain("score 58.5 (100 x S x R x C · S 0.8 · R 0.7312 · C 1; ordering only, severity by rule)");
    expect(scores[1]).toContain("score 3.6 (100 x S x R x C · S 0.3 · R 0.12 · C 1; ordering only, severity by rule)");

    // Vocabulary placeholders only: no raw IP, serial or unmasked hostname appears.
    expect(document.body.textContent).not.toMatch(/\b10\.\d+\.\d+\.\d+\b/);
  });
});

describe("LogFindingChip and chipFromAnswers", () => {
  const data = (over: Partial<LogFindingChipData>): LogFindingChipData => ({
    device_id: DEVICE, state: "FINDINGS", severity: "CRITICAL", count: 1, text: "hw.edac.ue", coverage: summary(4, 4), ...over,
  });

  it("renders the severity and count, or the coverage word", () => {
    const { rerender } = render(<ThemeProvider theme={m3Theme}><LogFindingChip chip={data({})} /></ThemeProvider>);
    expect(screen.getByText("CRITICAL 1")).toBeTruthy();
    rerender(<ThemeProvider theme={m3Theme}><LogFindingChip chip={data({ state: "UNKNOWN", severity: null, count: 0 })} /></ThemeProvider>);
    expect(screen.getByText("UNKNOWN")).toBeTruthy();
  });

  it("builds the chip from the findings and coverage answers: INFO is not counted, acknowledged findings leave the chip, higher Layer-B score wins ties", () => {
    const lowScore = finding({ finding_id: "1".repeat(32), marker_id: "hw.mce", score: { s: 0.8, r: 0.3, c: 1.0, score: 24, note: "" } });
    const highScore = finding({ finding_id: "2".repeat(32), marker_id: "hw.edac.ce", score: { s: 0.8, r: 0.73, c: 1.0, score: 58.4, note: "" } });
    const f = findings([lowScore, highScore, finding({ finding_id: "d".repeat(32), severity: "INFO" }), finding({ finding_id: "e".repeat(32), review_state: "ACKNOWLEDGED" })]);
    const chip = chipFromAnswers(DEVICE, f, coverage(4, 4));
    expect(chip.state).toBe("FINDINGS");
    expect(chip.count).toBe(2);
    expect(chip.text).toBe("hw.edac.ce on CPU_SrcID#1_MC#0_Chan#2_DIMM#0");
    const none = chipFromAnswers(DEVICE, findings([finding({ severity: "INFO" })]), coverage(4, 3));
    expect(none.state).toBe("UNKNOWN");
  });
});

describe("InventoryScreen Devices-list LogFindingChip", () => {
  const devicesBody = {
    devices: [
      { device_id: "dev-tango", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-TANGO-04", model: "Quantum", software_version: "R81.20", ha_role: null, cluster_member_ref: null },
      { device_id: "dev-bravo", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-BRAVO-02", model: "PA-5220", software_version: "11.1", ha_role: null, cluster_member_ref: null },
      { device_id: "dev-sierra", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: "FW-SIERRA-06", model: "Quantum", software_version: "R81.20", ha_role: null, cluster_member_ref: null },
    ],
  };

  const chipsAnswer = (over: Partial<LogFindingChipsAnswer> = {}): LogFindingChipsAnswer => ({
    ...envelope,
    phase: "P2",
    layers: ["A", "B"],
    window_days: 7,
    counts: "open findings of ATTENTION or higher",
    truncated: false,
    chips: [
      { device_id: "dev-tango", state: "FINDINGS", severity: "CRITICAL", count: 2, text: "hw.edac.ue: 45 lines on 2026-11-24", coverage: summary(4, 4) },
      { device_id: "dev-bravo", state: "UNKNOWN", severity: null, count: 0, text: "No findings in the evaluated windows; 1 of 4 windows UNKNOWN", coverage: summary(4, 3) },
      { device_id: "dev-sierra", state: "NO_FINDINGS", severity: null, count: 0, text: "No findings; 4 of 4 recorded windows evaluated", coverage: summary(4, 4) },
    ],
    ...over,
  });

  function stubInventory(chips: LogFindingChipsAnswer) {
    const fetchMock = vi.fn((url: string) => {
      if (url === "/devices") return json(devicesBody);
      if (url === "/api/v2/log-findings/chips") return json(chips);
      return json({});
    });
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
  }

  it("renders LogFindingChip in the Devices list when enabled and outside the shadow period", async () => {
    const fetchMock = stubInventory(chipsAnswer({ enabled: true, shadow: false }));
    render(<ThemeProvider theme={m3Theme}><InventoryScreen /></ThemeProvider>);
    await screen.findByText("FW-TANGO-04");
    await screen.findByText("CRITICAL 2");
    expect(screen.getByText("UNKNOWN")).toBeTruthy();
    expect(screen.getByText("NO FINDINGS")).toBeTruthy();
    expect(screen.getAllByTestId("log-finding-chip").length).toBe(3);
    expect(fetchMock).toHaveBeenCalledWith("/api/v2/log-findings/chips", expect.anything());
  });

  it("suppresses LogFindingChip in the Devices list while the shadow period is active or detection is switched off", async () => {
    stubInventory(chipsAnswer({ enabled: true, shadow: true, shadow_until: "2026-12-08T10:00:00Z" }));
    const { unmount } = render(<ThemeProvider theme={m3Theme}><InventoryScreen /></ThemeProvider>);
    await screen.findByText("FW-TANGO-04");
    await waitFor(() => expect(screen.queryAllByTestId("log-finding-chip")).toHaveLength(0));
    unmount();

    stubInventory(chipsAnswer({ enabled: false, shadow: false }));
    render(<ThemeProvider theme={m3Theme}><InventoryScreen /></ThemeProvider>);
    await screen.findByText("FW-TANGO-04");
    await waitFor(() => expect(screen.queryAllByTestId("log-finding-chip")).toHaveLength(0));
  });

  it("does not request /api/v2/log-findings/chips when the session lacks log-read plane access", async () => {
    const fetchMock = stubInventory(chipsAnswer({ enabled: true, shadow: false }));
    render(
      <ThemeProvider theme={m3Theme}>
        <SessionContext.Provider value={{ displayName: "Synthetic viewer", roleTokens: [], permissions: ["Devices"], canReadAdministration: false, onSignOut: () => {} }}>
          <InventoryScreen />
        </SessionContext.Provider>
      </ThemeProvider>,
    );
    await screen.findByText("FW-TANGO-04");
    expect(fetchMock.mock.calls.some(([u]) => u === "/api/v2/log-findings/chips")).toBe(false);
    expect(screen.queryAllByTestId("log-finding-chip")).toHaveLength(0);
  });

  it("renders LogFindingChip on VSX and collapsed cluster header rows and on management server rows in By manager view", async () => {
    const clusterDevices = {
      devices: [
        {
          device_id: "dev-vsx-1", vendor_hint: "check_point", enrollment_state: "ENROLLED",
          hostname: "FW-TANGO-04", model: "Quantum", software_version: "R81.20",
          ha_role: "active", cluster_member_ref: "CLS-TANGO", virtual_systems: "VS-1, VS-2",
        },
        {
          device_id: "dev-vsx-2", vendor_hint: "check_point", enrollment_state: "ENROLLED",
          hostname: "FW-TANGO-05", model: "Quantum", software_version: "R81.20",
          ha_role: "standby", cluster_member_ref: "CLS-TANGO", virtual_systems: "VS-1, VS-2",
        },
        {
          device_id: "dev-ha-1", vendor_hint: "palo_alto", enrollment_state: "ENROLLED",
          hostname: "FW-BRAVO-02", model: "PA-5220", software_version: "11.1",
          ha_role: "active", cluster_member_ref: "CLS-BRAVO", virtual_systems: null,
        },
        {
          device_id: "dev-ha-2", vendor_hint: "palo_alto", enrollment_state: "ENROLLED",
          hostname: "FW-BRAVO-03", model: "PA-5220", software_version: "11.1",
          ha_role: "passive", cluster_member_ref: "CLS-BRAVO", virtual_systems: null,
        },
      ],
    };
    const clusterChips = chipsAnswer({
      enabled: true,
      shadow: false,
      chips: [
        { device_id: "dev-vsx-1", state: "FINDINGS", severity: "CRITICAL", count: 2, text: "hw.edac.ue: 45 lines", coverage: summary(4, 4) },
        { device_id: "dev-vsx-2", state: "NO_FINDINGS", severity: null, count: 0, text: "No findings", coverage: summary(4, 4) },
        { device_id: "dev-ha-1", state: "FINDINGS", severity: "ATTENTION", count: 1, text: "pan.hw: 3 lines", coverage: summary(4, 4) },
        { device_id: "dev-ha-2", state: "NO_FINDINGS", severity: null, count: 0, text: "No findings", coverage: summary(4, 4) },
      ],
    });
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url === "/devices") return json(clusterDevices);
      if (url === "/api/v2/log-findings/chips") return json(clusterChips);
      return json({});
    }));
    const { unmount } = render(<ThemeProvider theme={m3Theme}><InventoryScreen /></ThemeProvider>);
    // Both the VSX cluster (memberMode === false) and the collapsed non-VSX cluster show their chip immediately on the cluster row.
    await screen.findByText("CRITICAL 2");
    expect(screen.getByText("ATTENTION 1")).toBeTruthy();
    expect(document.querySelectorAll('[data-row="cluster"] [data-testid="log-finding-chip"]').length).toBe(2);
    unmount();

    // Management server row in "By manager" view also renders its LogFindingChip.
    window.localStorage.setItem("nexus.deviceListView", "manager");
    const mgrDevices = {
      devices: [
        { device_id: "dev-mds", vendor_hint: "check_point", role: "management_server", enrollment_state: "ENROLLED", hostname: "FW-ALPHA-01", model: "MDS", software_version: "R81.20", ha_role: null, cluster_member_ref: null },
        { device_id: "dev-gw", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", hostname: "FW-SIERRA-06", model: "Quantum", software_version: "R81.20", ha_role: null, cluster_member_ref: null },
      ],
    };
    const mgrChips = chipsAnswer({
      enabled: true,
      shadow: false,
      chips: [
        { device_id: "dev-mds", state: "FINDINGS", severity: "CRITICAL", count: 1, text: "hw.mce: 2 lines", coverage: summary(4, 4) },
      ],
    });
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url === "/devices") return json(mgrDevices);
      if (url === "/api/v2/log-findings/chips") return json(mgrChips);
      if (url.includes("/management-tree")) return json({ domains: [{ domain: "DOM-1", nodes: [{ device_id: "dev-gw", children: [] }] }] });
      return json({});
    }));
    render(<ThemeProvider theme={m3Theme}><InventoryScreen /></ThemeProvider>);
    await screen.findByText("FW-ALPHA-01");
    await screen.findByText("CRITICAL 1");
    expect(document.querySelectorAll('[data-row="manager-head"] [data-testid="log-finding-chip"]').length).toBe(1);
    window.localStorage.removeItem("nexus.deviceListView");
  });

  it("surfaces /api/v2/log-findings/chips errors instead of swallowing them and downgrades NO_FINDINGS to UNKNOWN on truncation", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url === "/devices") return json(devicesBody);
      if (url === "/api/v2/log-findings/chips") {
        return Promise.resolve(new Response(JSON.stringify({ error: "BACKEND_UNAVAILABLE" }), {
          status: 500,
          headers: { "content-type": "application/json" },
        }));
      }
      return json({});
    }));
    const { unmount } = render(<ThemeProvider theme={m3Theme}><InventoryScreen /></ThemeProvider>);
    await screen.findByText("FW-TANGO-04");
    expect(await screen.findByTestId("log-chips-error")).toHaveTextContent(/LOG FINDINGS UNKNOWN/);
    expect(screen.getByTestId("log-chips-error")).toHaveTextContent(/BACKEND_UNAVAILABLE/);
    unmount();

    stubInventory(chipsAnswer({
      enabled: true,
      shadow: false,
      truncated: true,
      truncated_note: "more than 50000 open findings in the window; per-device counts are partial",
    }));
    render(<ThemeProvider theme={m3Theme}><InventoryScreen /></ThemeProvider>);
    await screen.findByText("FW-TANGO-04");
    expect(await screen.findByTestId("log-chips-truncated")).toHaveTextContent(/50000 open findings/);
    expect(screen.getByText("CRITICAL 2")).toBeTruthy();
    // dev-sierra was NO_FINDINGS in the raw answer, but truncation must never render green NO FINDINGS.
    expect(screen.queryByText("NO FINDINGS")).toBeNull();
    expect(screen.getAllByText("UNKNOWN").length).toBe(2);
  });
});
