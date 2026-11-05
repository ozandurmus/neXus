import { describe, expect, it } from "vitest";

import {
  attentionLines, evidencedHeadline, evidencedShares, executiveVerdict, freshnessSentence, MAX_ATTENTION_LINES,
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

describe("needs attention", () => {
  const ages = { haNotReadyAt: "2026-11-05T10:00:00Z", backupAt: "2026-11-04T02:00:00Z", complianceAt: "2026-11-05T09:00:00Z", oldestReadAt: "2026-11-03T12:00:00Z" };
  const all = {
    ...CLEAN, security: { failing: 97, of: 118 }, backups: { archived: 126, of: 130 }, ha: { total: 45, ready: 35, notReady: 2, unknown: 3, unsupported: 5 },
    freshness: { fresh: 1, of: 132 }, ages, topControl: { id: "cp-1", title: "Account lockout", failing: 62, of: 62 },
  };

  it("keeps at most five lines, most severe first, each with an age and a link to the filtered problem", () => {
    const lines = attentionLines(all);
    expect(MAX_ATTENTION_LINES).toBe(5);
    expect(lines).toHaveLength(5);
    expect(lines.map((l) => l.key)).toEqual(["ha-not-ready", "backup-missing", "critical-failing", "top-control", "stale-devices"]);
    expect(lines[0]).toMatchObject({ text: "2 of 45 HA clusters not ready for failover", href: "?screen=operations&readiness=NOT_READY", at: ages.haNotReadyAt });
    expect(lines[1]).toMatchObject({ text: "4 of 130 backup targets have no stored backup", href: "?screen=backups&artefact=none", at: ages.backupAt });
    expect(lines[2]).toMatchObject({ href: "?screen=compliance&severity=critical&result=fail", at: ages.complianceAt });
    expect(lines[3]).toMatchObject({ href: "?screen=compliance&q=cp-1" });
    expect(lines[4]).toMatchObject({ text: "131 of 132 active devices not read in the last 24 h", href: "?screen=inventory&inventory_age=stale", at: ages.oldestReadAt });
  });

  it("lists nothing for a clean, fresh estate, and never invents a line from an unread figure", () => {
    expect(attentionLines({ ...CLEAN, freshness: { fresh: 132, of: 132 }, ages: {} })).toEqual([]);
    // two devices not read in 24 h, above the banner threshold: one warning line, not a serious one
    expect(attentionLines({ ...CLEAN, ages: {} })).toMatchObject([{ key: "stale-devices", severity: "warning", text: "2 of 132 active devices not read in the last 24 h" }]);
    expect(attentionLines({ security: null, backups: null, ha: null, freshness: null, ages: {} })).toEqual([]);
  });

  it("carries a missing age as null (the screen writes UNKNOWN)", () => {
    expect(attentionLines({ ...CLEAN, backups: { archived: 1, of: 2 }, ages: {} })[0].at).toBeNull();
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
