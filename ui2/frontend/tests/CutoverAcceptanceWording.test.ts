import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

import {
  DECISION, DECISIONS, DIRECTION, FORBIDDEN_PATTERN, FORBIDDEN_WORDS, READINESS, RESULT_TABS, STALE_REASON, TARGET_SOURCE,
  coverageText, decisionOf, directionOf, flagText, readinessOf, ruleText, targetSourceLabel,
} from "../src/screens/cutover/cutoverWording";

/**
 * ACI-1 (ACI segmentation design section 11, step 1): no verdict, state, flag, reason or coverage sentence of the cutover
 * wording says "safe", "ready", "pass", "healthy" or "symmetric", and no state is green; the best verdict is neutral and
 * the engine's coverage vocabulary is fully worded. The Java side (engine strings, stored summaries) is pinned by
 * CutoverAcceptanceCriteriaTest. The wording module is read-only here.
 */

const ENGINE = "../service/src/main/java/com/securityexpert/nexus/ui2/service/policy/CutoverAssessment.java";   // vitest runs from ui2/frontend

/** The quoted members of a `Set.of(...)` constant of the engine, read from its source so a new code cannot slip past the scan. */
function engineSet(name: string): string[] {
  const source = readFileSync(ENGINE, "utf8");
  const match = new RegExp(`${name}\\s*=\\s*Set\\.of\\(([^;]*?)\\);`, "s").exec(source);
  if (!match) throw new Error(`${name} not found in the engine source`);
  return [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

const everyText = (): string[] => {
  const coverage = engineSet("COVERAGE");
  const flags = engineSet("FLAGS");
  const texts: string[] = [];
  for (const entry of [...Object.values(READINESS), ...Object.values(DECISION)]) texts.push(entry.word);
  texts.push(...Object.values(TARGET_SOURCE), ...Object.values(DIRECTION), ...Object.values(STALE_REASON));
  texts.push(...RESULT_TABS.map((tab) => tab.label));
  texts.push(readinessOf("NOT_A_VERDICT").word, decisionOf("NOT_A_DECISION").word, targetSourceLabel("NOT_A_SOURCE"), directionOf(null));
  for (const code of coverage) {
    texts.push(coverageText({ code }), coverageText({ code, count: 7, periodStart: "2026-11-01T00:00:00Z", periodEnd: "2026-11-08T00:00:00Z" }));
  }
  for (const flag of flags) texts.push(flagText(flag), flagText(flag, { ruleId: "r1", number: 1, name: "RULE-ALPHA", action: "allow" }, ["time", "source-user"]));
  texts.push(ruleText({ ruleId: "r1", number: 1, name: "RULE-ALPHA", action: "drop" }));
  return texts;
};

describe("ACI-1 cutover wording", () => {
  it("ACI-1 no label, chip text, flag sentence or coverage sentence carries a forbidden word", () => {
    const texts = everyText();
    expect(texts.length).toBeGreaterThan(60);
    for (const text of texts) {
      expect(text, text).not.toMatch(FORBIDDEN_PATTERN);
      for (const word of FORBIDDEN_WORDS) expect(text.toLowerCase().split(/[^a-z]+/), text).not.toContain(word);
      expect(text.toLowerCase(), text).not.toMatch(/\bgreen\b/);
    }
    // The guard has teeth.
    for (const bad of ["Cutover is safe", "Ready to cut over", "checks pass", "Healthy", "Symmetric paths", "all green"]) {
      expect(`${FORBIDDEN_PATTERN.test(bad) || /\bgreen\b/i.test(bad)}`, bad).toBe("true");
    }
  });

  it("ACI-1 no readiness verdict and no decision is shown in the success (green) state; the best verdict is neutral", () => {
    for (const [code, entry] of Object.entries(READINESS)) expect(entry.tone, code).not.toBe("ok");
    for (const code of DECISIONS) expect(DECISION[code].tone, code).not.toBe("ok");
    expect(READINESS.NO_BLOCKERS_IN_EVALUATED_FLOWS.tone).toBe("neutral");
    expect(READINESS.NO_BLOCKERS_IN_EVALUATED_FLOWS.word).toBe("No blockers in evaluated flows");
    expect(DECISION.WOULD_ALLOW.tone).toBe("neutral");
    // An unknown code is UNKNOWN, never a good state.
    expect(readinessOf("SOMETHING_NEW")).toEqual({ tone: "unknown", word: "UNKNOWN" });
    expect(decisionOf(undefined)).toEqual({ tone: "unknown", word: "UNKNOWN" });
  });

  it("ACI-1 every readiness verdict, decision, flag and coverage code of the engine has its own words (nothing falls through to a bare code)", () => {
    for (const code of engineSet("READINESS")) expect(READINESS[code], code).toBeDefined();
    for (const code of engineSet("DIRECTIONS")) expect(DIRECTION[code], code).toBeDefined();
    for (const code of engineSet("COVERAGE")) {
      const sentence = coverageText({ code });
      expect(sentence, code).not.toBe(code);
      expect(sentence.length, code).toBeGreaterThan(20);
    }
    for (const flag of engineSet("FLAGS")) expect(flagText(flag), flag).not.toBe(flag);
    expect(coverageText({ code: "INVENTED_CODE" })).toBe("INVENTED_CODE");
  });

  it("ACI-1 the three coverage codes every verdict carries are worded as limits, not as assurances", () => {
    expect(coverageText({ code: "LOGGING_COMPLETENESS_UNKNOWN" })).toMatch(/unknown/i);
    expect(coverageText({ code: "UNLOGGED_FLOWS_INVISIBLE" })).toMatch(/cannot be assessed/i);
    expect(coverageText({ code: "LOG_PERIOD", periodStart: "2026-11-01T00:00:00Z", periodEnd: "2026-11-08T00:00:00Z" })).toMatch(/Only flows logged in this period/);
  });
});
