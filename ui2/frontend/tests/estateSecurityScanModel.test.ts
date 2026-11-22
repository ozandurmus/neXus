import { describe, expect, it } from "vitest";
import type { SecurityScanSummaryView } from "../src/auth/adminApi";
import {
  openSeverityCounts,
  scanAgeBucket,
  scanCardStatus,
  severityCountLine,
} from "../src/estate/estateSecurityScanModel";

const NOW = new Date("2026-11-22T10:00:00Z");

function view(overrides: Partial<SecurityScanSummaryView> = {}): SecurityScanSummaryView {
  return {
    available: true,
    mail: { route_enabled: true, smtp_configured: true, recipients_configured: true, delivering: true, last_sent_at: null, last_error: null },
    stale_after_hours: 24,
    generated_at: "2026-11-22T02:30:00Z",
    age_hours: 8,
    stale: false,
    passed: true,
    counts: {
      semgrep: { HIGH: { new: 1, existing: 1, accepted: 3, fixed: 0 }, MEDIUM: { new: 2, existing: 0, accepted: 0, fixed: 4 } },
      trivy: { HIGH: { new: 0, existing: 0, accepted: 0, fixed: 1 }, MEDIUM: { new: 3, existing: 0, accepted: 0, fixed: 0 } },
    },
    ...overrides,
  } as SecurityScanSummaryView;
}

const ok = (value: SecurityScanSummaryView) => ({ state: "ok" as const, value });

describe("scan age bucket", () => {
  it("buckets the age of the scan date, and reports a missing date as never", () => {
    expect(scanAgeBucket(view({ generated_at: "2026-11-22T09:00:00Z" }), NOW)).toBe("fresh");
    expect(scanAgeBucket(view({ generated_at: "2026-11-21T20:00:00Z" }), NOW)).toBe("day");
    expect(scanAgeBucket(view({ generated_at: undefined }), NOW)).toBe("never");
  });
});

describe("severity counts", () => {
  it("sums open (new + existing) per severity across tools and leaves accepted and fixed out", () => {
    expect(openSeverityCounts(view())).toEqual([
      { severity: "HIGH", open: 2 },
      { severity: "MEDIUM", open: 5 },
    ]);
  });

  it("names each count, e.g. HIGH 2 · MEDIUM 5", () => {
    expect(severityCountLine(openSeverityCounts(view())!)).toBe("HIGH 2 · MEDIUM 5");
  });

  it("shows an unrecognised severity key as UNKNOWN, never dropped", () => {
    const counts = openSeverityCounts(
      view({ counts: { semgrep: { HIGH: { new: 1, existing: 0, accepted: 0, fixed: 0 }, SEVERE: { new: 2, existing: 1, accepted: 0, fixed: 0 } } } }),
    );
    expect(counts).toEqual([
      { severity: "HIGH", open: 1 },
      { severity: "UNKNOWN", open: 3 },
    ]);
    expect(severityCountLine(counts!)).toBe("HIGH 1 · UNKNOWN 3");
  });

  it("a cell with a missing existing count yields UNKNOWN n/a, never NaN", () => {
    const cell = { new: 2, accepted: 0, fixed: 0 } as never;
    const counts = openSeverityCounts(view({ counts: { semgrep: { MEDIUM: cell } } }));
    expect(counts).toEqual([
      { severity: "MEDIUM", open: 2 },
      { severity: "UNKNOWN", open: null },
    ]);
    const line = severityCountLine(counts!);
    expect(line).toBe("MEDIUM 2 · UNKNOWN n/a");
    expect(line).not.toContain("NaN");
  });

  it("a non-numeric count in a cell is UNKNOWN, never NaN", () => {
    const counts = openSeverityCounts(
      view({ counts: { trivy: { LOW: { new: "3" as unknown as number, existing: 1, accepted: 0, fixed: 0 } } } }),
    );
    expect(severityCountLine(counts!)).toBe("LOW 1 · UNKNOWN n/a");
  });

  it("keeps the known-severity sums unchanged when every cell is readable", () => {
    expect(severityCountLine(openSeverityCounts(view())!)).toBe("HIGH 2 · MEDIUM 5");
    expect(openSeverityCounts(view())).not.toContainEqual(expect.objectContaining({ severity: "UNKNOWN" }));
  });

  it("returns null when the summary carries no counts (UNKNOWN, not zero)", () => {
    expect(openSeverityCounts(view({ counts: undefined }))).toBeNull();
  });

  it("says no open findings when the counts carry none", () => {
    expect(severityCountLine([])).toBe("no open findings in the summary");
  });
});

describe("scan card status", () => {
  it("shows OK only when the summary explicitly passed and the scan is fresh", () => {
    const s = scanCardStatus(ok(view()), NOW);
    expect(s).toMatchObject({ kind: "populated", badge: "ok", word: "Passed", at: "2026-11-22T02:30:00Z" });
  });

  it("missing scan -> UNKNOWN-class no-scan state, never OK", () => {
    const s = scanCardStatus(ok(view({ available: false, reason: "NO_DAILY_SUMMARY" })), NOW);
    expect(s.kind).toBe("no-scan");
    expect(s.badge).toBe("unk");
    expect(s.badge).not.toBe("ok");
    expect(s.at).toBeNull();
  });

  it("unreadable summary -> UNKNOWN, never OK", () => {
    const s = scanCardStatus(ok(view({ available: false, reason: "SUMMARY_UNREADABLE" })), NOW);
    expect(s).toMatchObject({ kind: "unreadable", badge: "unk", word: "UNKNOWN" });
  });

  it("a failed or restricted read -> UNKNOWN, never OK", () => {
    expect(scanCardStatus({ state: "failed" }, NOW)).toMatchObject({ kind: "failed", badge: "unk" });
    expect(scanCardStatus({ state: "restricted" }, NOW)).toMatchObject({ kind: "restricted", badge: "unk" });
  });

  it("a scan with no stated pass result is UNKNOWN, never OK", () => {
    const s = scanCardStatus(ok(view({ passed: undefined })), NOW);
    expect(s).toMatchObject({ badge: "unk", word: "UNKNOWN" });
  });

  it("a summary without a scan date is UNKNOWN even when it says passed", () => {
    const s = scanCardStatus(ok(view({ generated_at: undefined })), NOW);
    expect(s).toMatchObject({ badge: "unk", at: null });
  });

  it("a stale scan is flagged, not OK", () => {
    const s = scanCardStatus(ok(view({ stale: true, age_hours: 30 })), NOW);
    expect(s).toMatchObject({ badge: "watch", word: "Stale" });
    expect(s.headline).toContain("30 h ago");
  });

  it("a failed scan is act, not OK", () => {
    expect(scanCardStatus(ok(view({ passed: false })), NOW)).toMatchObject({ badge: "act", word: "Failed" });
  });
});
