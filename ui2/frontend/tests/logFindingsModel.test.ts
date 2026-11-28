import { describe, expect, it } from "vitest";

import type { LogFinding, LogFindingChipData, LogFindingChipsAnswer } from "../src/auth/adminApi";
import {
  chipLabel, chipTone, clusterLogChip, countedFindings, coverageEvaluated, coverageLine, coverageWord, findingCounts, findingTitle, labelWord,
  layerBKindWord, layerBKinds, layerBStatsLine, normalizeLogChipsAnswer, scoreLine, severityTone, shadowNote,
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
    for (const s of ["GAP", "NOT_COLLECTED", "MASKING_UNAVAILABLE", "UNPARSED_HIGH", "EVALUATION_FAILED", "UNSUPPORTED", "SOMETHING_NEW"]) {
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

  it("formats Layer B kinds, composite 100 x S x R x C score, z, baseline and prevalence", () => {
    const bFinding = finding({
      layer: "B",
      rule_id: "layer-b-statistics",
      kind: "FLEET_RARE",
      marker_id: "tpl.cp_messages",
      key_token: "t-model-synth-a",
      coverage_state: "EVALUATED",
      score: { s: 0.8, r: 0.7312, c: 1.0, score: 58.5, note: "100 x S x R x C, used to order findings only; severity comes from rules" },
      counts: { lines: 45, peak_bucket_lines: 18, history_days: 21, baseline_days: 14, presence_days: 2, cohort_devices: 57, prevalence_devices: 1, counts: "" },
      labels: ["CATALOG_BASELINE", "CATALOG_PREVALENCE", "HA_ROLE_UNKNOWN", "STATISTICAL_FLOOR"],
      versions: { masking_rule_set: "logmask-v8", marker_list: "markers-p1-1", config: "p2-config-1", catalog: "cat-1", normaliser: "norm-p2-1" },
      layer_b: {
        template_id: "t-model-synth-a",
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
    expect(layerBKinds(bFinding)).toEqual(["FLEET_RARE", "RATE_SPIKE", "NEW_ON_DEVICE"]);
    expect(findingTitle(bFinding)).toBe("FLEET_RARE · RATE_SPIKE · NEW_ON_DEVICE (t-model-synth-a)");
    expect(scoreLine(bFinding)).toContain("score 58.5 (100 x S x R x C · S 0.8 · R 0.7312 · C 1; ordering only, severity by rule)");
    expect(scoreLine(finding())).toContain("S 0.8 (severity weight, provisional)");
    expect(layerBStatsLine(bFinding)).toBe(
      "baseline median 1/h, MAD 0 (peak 18/h) · z 8.45 · prevalence: 1 other of 57 cohort devices · first seen: device 2026-11-24, fleet 2026-11-10",
    );
    for (const k of ["NEW_ON_DEVICE", "FLEET_RARE", "RATE_SPIKE", "SILENCE", "BOOT_BURST"]) {
      expect(layerBKindWord(k).length).toBeGreaterThan(5);
    }
    expect(labelWord("BASELINE_LEARNING")).toMatch(/baseline learning/);
    expect(labelWord("PROVISIONAL_TEMPLATE")).toMatch(/provisional template/);
    expect(labelWord("HA_ROLE_UNKNOWN")).toMatch(/pooled baseline/);
    expect(labelWord("STATISTICAL_FLOOR")).toMatch(/statistical finding/);

    // During 7..13 day learning, baseline and z are null rather than fabricated zeros.
    const learning = finding({
      ...bFinding,
      kind: "NEW_ON_DEVICE",
      counts: { ...bFinding.counts, baseline_days: 9, peak_bucket_lines: 3 },
      layer_b: {
        ...bFinding.layer_b!,
        kinds: ["NEW_ON_DEVICE"],
        z: null,
        baseline_median: null,
        baseline_mad: null,
        device_first_seen: null,
        fleet_first_seen: null,
      },
    });
    expect(layerBStatsLine(learning)).toBe("baseline learning (9 usable days) · z not evaluable · prevalence: 1 other of 57 cohort devices");
  });

  it("shows UNKNOWN, not learning or new, for unevaluated Layer B statistics and missing fleet provenance", () => {
    const base = finding({
      layer: "B", labels: [], kind: "RATE_SPIKE",
      counts: { ...finding().counts, baseline_days: 20, peak_bucket_lines: 0 },
      layer_b: {
        kinds: ["RATE_SPIKE"], z: null, baseline_median: null, baseline_mad: null, prevalence: "2 other of 10 cohort devices",
        device_first_seen: "2026-10-01", fleet_first_seen: null,
      } as unknown as LogFinding["layer_b"],
    });
    const line = layerBStatsLine(base)!;
    expect(line).toContain("baseline not evaluable (UNKNOWN; 20 usable baseline days)");
    expect(line).not.toContain("baseline learning");
    expect(line).toContain("device 2026-10-01, fleet UNKNOWN");
    expect(line).not.toContain("new");
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

  it("downgrades NO_FINDINGS to UNKNOWN when /log-findings/chips is truncated and suppresses chips during shadow", () => {
    const base: LogFindingChipsAnswer = {
      enabled: true,
      shadow: false,
      shadow_until: null,
      phase: "P2",
      layers: ["A", "B"],
      time_basis: "DEVICE_LOCAL",
      window_days: 7,
      counts: "open findings",
      truncated: true,
      truncated_note: "more than 50000 open findings in the window; per-device counts are partial",
      chips: [
        chip({ device_id: "dev-a", state: "FINDINGS", severity: "CRITICAL", count: 2 }),
        chip({ device_id: "dev-b", state: "NO_FINDINGS", severity: null, count: 0, text: "No findings" }),
        chip({ device_id: "dev-c", state: "UNKNOWN", severity: null, count: 0, text: "UNKNOWN" }),
      ],
    };
    const res = normalizeLogChipsAnswer(base);
    expect(res.truncatedNote).toContain("50000 open findings");
    expect(res.chips.get("dev-a")?.state).toBe("FINDINGS");
    expect(res.chips.get("dev-a")?.text).toContain("partial");
    expect(res.chips.get("dev-b")?.state).toBe("UNKNOWN");
    expect(chipTone(res.chips.get("dev-b")!)).toBe("unknown");
    expect(res.chips.get("dev-c")?.state).toBe("UNKNOWN");

    expect(normalizeLogChipsAnswer({ ...base, shadow: true }).chips.size).toBe(0);
    expect(normalizeLogChipsAnswer({ ...base, enabled: false }).chips.size).toBe(0);
  });

  it("aggregates cluster member log chips: worst severity wins, NO_FINDINGS only when all members are NO_FINDINGS", () => {
    const members = [
      { device_id: "dev-m1", hostname: "FW-TANGO-04" },
      { device_id: "dev-m2", hostname: "FW-TANGO-05" },
    ];
    const withFindings = new Map<string, LogFindingChipData>([
      ["dev-m1", chip({ device_id: "dev-m1", state: "FINDINGS", severity: "CRITICAL", count: 2, text: "hw.edac.ue" })],
      ["dev-m2", chip({ device_id: "dev-m2", state: "FINDINGS", severity: "ATTENTION", count: 3, text: "hw.edac.ce" })],
    ]);
    const agg = clusterLogChip(members, withFindings);
    expect(agg?.state).toBe("FINDINGS");
    expect(agg?.severity).toBe("CRITICAL");
    expect(agg?.count).toBe(5);
    expect(agg?.coverage.windows_total).toBe(8);

    const allGreen = new Map<string, LogFindingChipData>([
      ["dev-m1", chip({ device_id: "dev-m1", state: "NO_FINDINGS", severity: null, count: 0 })],
      ["dev-m2", chip({ device_id: "dev-m2", state: "NO_FINDINGS", severity: null, count: 0 })],
    ]);
    expect(clusterLogChip(members, allGreen)?.state).toBe("NO_FINDINGS");

    const oneMissing = new Map<string, LogFindingChipData>([
      ["dev-m1", chip({ device_id: "dev-m1", state: "NO_FINDINGS", severity: null, count: 0 })],
    ]);
    expect(clusterLogChip(members, oneMissing)?.state).toBe("UNKNOWN");
    expect(clusterLogChip(members, oneMissing)?.text).toContain("1 member has no log coverage");

    const oneUnknown = new Map<string, LogFindingChipData>([
      ["dev-m1", chip({ device_id: "dev-m1", state: "NO_FINDINGS", severity: null, count: 0 })],
      ["dev-m2", chip({ device_id: "dev-m2", state: "UNKNOWN", severity: null, count: 0 })],
    ]);
    expect(clusterLogChip(members, oneUnknown)?.state).toBe("UNKNOWN");
    expect(clusterLogChip(members, new Map())).toBeUndefined();
  });
});
