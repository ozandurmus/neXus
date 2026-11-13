import { describe, expect, it } from "vitest";

import {
  evidencedHeadline, evidencedShares, executiveVerdict, freshnessSentence,
  noEvidenceFromCoverage, primaryFrameworksFirst, type ExecutiveFigures,
} from "../src/screens/overview/executiveHome";

const CLEAN: ExecutiveFigures = {
  security: { failing: 0, of: 118 },
  backups: { archived: 130, of: 130 },
  ha: { total: 45, ready: 40, notReady: 0, unknown: 0, unsupported: 5 },
  freshness: { fresh: 130, of: 132, never: 0 },
};

describe("executive verdict (UI review 2026-11-05, C1)", () => {
  it("is HEALTHY only when every pillar is known, clean and fresh", () => {
    expect(executiveVerdict(CLEAN).word).toBe("HEALTHY");
  });

  it("is AT RISK as soon as the stored reads show a known defect, and names it", () => {
    const v = executiveVerdict({ ...CLEAN, security: { failing: 97, of: 118 }, backups: { archived: 126, of: 130 }, ha: { ...CLEAN.ha!, ready: 38, notReady: 2 } });
    expect(v.word).toBe("AT RISK");
    expect(v.reason).toBe("97 firewalls fail a critical check, 4 backup targets have no stored backup, 2 HA clusters are not ready for failover.");
  });

  it("stays AT RISK on stale data: the freshness sentence beside it says how old", () => {
    expect(executiveVerdict({ ...CLEAN, security: { failing: 1, of: 118 }, freshness: { fresh: 1, of: 132 } }).word).toBe("AT RISK");
  });

  it("is UNKNOWN, never HEALTHY, when a pillar could not be read, nothing is assessed, readiness is unknown or the data is stale", () => {
    for (const f of [
      { ...CLEAN, security: null }, { ...CLEAN, security: { failing: 0, of: 0 } }, { ...CLEAN, backups: null }, { ...CLEAN, ha: null },
      { ...CLEAN, ha: { ...CLEAN.ha!, ready: 37, unknown: 3 } }, { ...CLEAN, freshness: null }, { ...CLEAN, freshness: { fresh: 1, of: 132 } },
    ] as ExecutiveFigures[]) {
      const v = executiveVerdict(f);
      expect(v.word).toBe("UNKNOWN");
      expect(v.reason).toMatch(/^No known problem, but /);
    }
  });
});

describe("freshness sentence", () => {
  const now = new Date("2026-11-05T12:00:00Z");
  it("says the share read in 24 h, the newest and the oldest read", () => {
    expect(freshnessSentence({ fresh: 130, of: 132, never: 1 }, "2026-11-05T11:40:00Z", "2026-11-02T12:00:00Z", now))
      .toBe("130 of 132 active devices read in the last 24 h; 1 never read · newest read 20 min ago · oldest 3 d ago.");
  });
  it("leads with 'Data is out of date' below the banner threshold", () => {
    expect(freshnessSentence({ fresh: 1, of: 132 }, "2026-11-05T11:40:00Z", null, now))
      .toBe("Data is out of date: 131 of 132 active devices were last read more than 24 h ago · newest read 20 min ago.");
  });
  it("says UNKNOWN when the read times could not be read", () => {
    expect(freshnessSentence(null, null, null, now)).toMatch(/^Data freshness UNKNOWN/);
  });
});

describe("framework order and evidenced shares (B4)", () => {
  it("puts CIS, then NIST first and keeps the rest in the service's order", () => {
    expect(primaryFrameworksFirst(["PCI-DSS v4.0.1", "NIST SP 800-53", "Financial Baseline", "CIS Benchmark"], (n) => n))
      .toEqual(["CIS Benchmark", "NIST SP 800-53", "PCI-DSS v4.0.1", "Financial Baseline"]);
  });

  it("takes the pass share over evidenced checks only and never counts a check without evidence as failed", () => {
    expect(evidencedShares(749, 1246, 496)).toEqual({ passPct: 37.5, noEvidencePct: 19.9 });
    expect(evidencedShares(0, 0, 10)).toEqual({ passPct: null, noEvidencePct: 100 });
    expect(evidencedShares(0, 0, 0)).toEqual({ passPct: null, noEvidencePct: null });
  });

  it("words the headline and derives the no-evidence share from coverage without float noise", () => {
    expect(evidencedHeadline(37.5, 19.9)).toBe("37.5% of evidenced controls pass, 19.9% have no evidence");
    expect(evidencedHeadline(null, undefined)).toBe("UNKNOWN of evidenced controls pass, UNKNOWN have no evidence");
    expect(noEvidenceFromCoverage(80.1)).toBe(19.9);
    expect(noEvidenceFromCoverage(null)).toBeNull();
  });
});
