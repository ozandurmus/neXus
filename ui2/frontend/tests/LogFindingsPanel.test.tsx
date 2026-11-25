import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { JobWindowContext } from "../src/shell/JobWindow";
import { LogFindingChip } from "../src/screens/LogFindingChip";
import { LogFindingsPanel, chipFromAnswers } from "../src/screens/LogFindingsPanel";
import type { LogCoverageAnswer, LogFinding, LogFindingChipData, LogFindingsAnswer } from "../src/auth/adminApi";

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

  it("builds the chip from the findings and coverage answers: INFO is not counted, acknowledged findings leave the chip", () => {
    const f = findings([finding(), finding({ finding_id: "d".repeat(32), severity: "INFO" }), finding({ finding_id: "e".repeat(32), review_state: "ACKNOWLEDGED" })]);
    const chip = chipFromAnswers(DEVICE, f, coverage(4, 4));
    expect(chip.state).toBe("FINDINGS");
    expect(chip.count).toBe(1);
    const none = chipFromAnswers(DEVICE, findings([finding({ severity: "INFO" })]), coverage(4, 3));
    expect(none.state).toBe("UNKNOWN");
  });
});
