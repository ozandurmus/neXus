import { describe, expect, it } from "vitest";

import type { LogFinding, LogFindingChipData } from "../src/auth/adminApi";
import {
  chipLabel, chipTone, countedFindings, coverageEvaluated, coverageLine, coverageWord, findingCounts, findingTitle, labelWord, severityTone,
  shadowNote,
} from "../src/screens/logFindingsModel";

const summary = (total: number, evaluated: number) => ({ windows_total: total, windows_evaluated: evaluated, by_state: {}, counts: "recorded windows" });
const chip = (over: Partial<LogFindingChipData> = {}): LogFindingChipData => ({
  device_id: "FW-TANGO-04", state: "FINDINGS", severity: "ATTENTION", count: 2, text: "hw.edac.ce", coverage: summary(4, 4), ...over,
});
const finding = (over: Partial<LogFinding> = {}): LogFinding => ({
  finding_id: "a".repeat(32), device_id: "FW-TANGO-04", source_key: "cp_messages", layer: "A", rule_id: "layer-a-severity", kind: "MARKER",
  marker_id: "hw.edac.ce", key_token: "CPU_SrcID#1_MC#0_Chan#2_DIMM#0", window_day: "2026-11-24", window_start: "2026-11-24T00:30:00",
  window_end: "2026-11-24T04:54:00", time_basis: "DEVICE_LOCAL", severity: "ATTENTION", severity_rule: "WEIGHT_AT_LEAST_0_5",
  score: { s: 0.8, r: null, c: null, score: null, note: "" }, provenance: "PROVISIONAL",
  counts: { lines: 45, peak_bucket_lines: 10, history_days: 1, baseline_days: 0, presence_days: 0, cohort_devices: 0, prevalence_devices: 0, counts: "" },
  chronic: false, fleet_background: false, labels: ["HISTORY_UNDER_7_DAYS"], incident_group: "0123456789abcdef", coverage_state: "NOT_EVALUABLE",
  evidence_pointers: [{ chunk_id: "c1", offset: 120, length: 160 }],
  versions: { masking_rule_set: "logmask-v8", marker_list: "markers-p1-1", config: "p1-config-1", catalog: null },
  review_state: "OPEN", review_at: null, first_raised_at: null, updated_at: null, ...over,
});

describe("log findings model", () => {
  it("colours severities and never reads UNKNOWN as healthy", () => {
    expect(severityTone("CRITICAL")).toBe("bad");
    expect(severityTone("ATTENTION")).toBe("warn");
    expect(severityTone("INFO")).toBe("neutral");
    expect(severityTone("UNKNOWN")).toBe("unknown");
  });

  it("words the chip from the worst open finding, else from the coverage", () => {
    expect(chipLabel(chip())).toBe("ATTENTION 2");
    expect(chipTone(chip())).toBe("warn");
    expect(chipLabel(chip({ state: "NO_FINDINGS", severity: null, count: 0 }))).toBe("NO FINDINGS");
    expect(chipTone(chip({ state: "NO_FINDINGS", severity: null, count: 0 }))).toBe("ok");
    expect(chipLabel(chip({ state: "UNKNOWN", severity: null, count: 0 }))).toBe("UNKNOWN");
    expect(chipTone(chip({ state: "UNKNOWN", severity: null, count: 0 }))).toBe("unknown");
  });

  it("says what the coverage counts and names the unknown windows", () => {
    expect(coverageLine(summary(4, 4))).toBe("4 of 4 recorded windows evaluated");
    expect(coverageLine(summary(4, 3))).toBe("3 of 4 recorded windows evaluated; 1 UNKNOWN");
    expect(coverageLine(summary(0, 0))).toBe("UNKNOWN: no window has been evaluated yet");
  });

  it("treats only the three evaluated states as evaluated", () => {
    for (const s of ["EVALUATED", "BASELINE_LEARNING", "NOT_EVALUABLE"]) expect(coverageEvaluated(s)).toBe(true);
    for (const s of ["GAP", "NOT_COLLECTED", "MASKING_UNAVAILABLE", "UNPARSED_HIGH", "UNSUPPORTED", "SOMETHING_NEW"]) {
      expect(coverageEvaluated(s)).toBe(false);
      expect(coverageWord(s)).toMatch(/^UNKNOWN/);
    }
  });

  it("titles and counts a finding from its closed-vocabulary parts", () => {
    expect(findingTitle(finding())).toBe("hw.edac.ce on CPU_SrcID#1_MC#0_Chan#2_DIMM#0");
    expect(findingTitle(finding({ key_token: "" }))).toBe("hw.edac.ce");
    expect(findingCounts(finding())).toBe("45 lines on 2026-11-24, 00:30 to 04:54 (device-local)");
    expect(labelWord("HISTORY_UNDER_7_DAYS")).toBe("history under 7 days");
    expect(labelWord("PROVISIONAL_CAP")).toMatch(/capped to ATTENTION/);
  });

  it("counts ATTENTION and above only", () => {
    const list = [finding(), finding({ severity: "INFO" }), finding({ severity: "CRITICAL" }), finding({ severity: "UNKNOWN" })];
    expect(countedFindings(list).map((f) => f.severity)).toEqual(["ATTENTION", "CRITICAL"]);
  });

  it("explains the shadow period only while it runs", () => {
    expect(shadowNote(false, null)).toBeNull();
    expect(shadowNote(true, "2026-12-08T10:00:00Z")).toMatch(/until 2026-12-08/);
    expect(shadowNote(true, null)).toMatch(/Shadow period/);
  });
});
