import { describe, expect, it } from "vitest";
import { briefRowPrimary, rowCategory } from "../src/estate/EstateBriefScreen";
import { standardJobsHref } from "../src/estate/estateRouting";
import type { AttentionRow } from "../src/screens/overview/attentionRules";

// Synthetic row, shaped like the overview attention rule for failed jobs (no real estate data).
const FAILED_JOBS_ROW = {
  key: "failed-jobs",
  href: "?screen=operations&tab=jobs&state=FAILED&since_hours=24",
  linkLabel: "Open Jobs",
  what: "2 failed jobs in the last 24 h",
  detail: "FW-TANGO-04 backup job, 192.0.2.14",
  names: ["FW-TANGO-04"],
} as unknown as AttentionRow;

describe("Estate Brief failed-jobs row destination", () => {
  it("has a non-empty destination label that says it is the standard view", () => {
    const cat = rowCategory("failed-jobs");
    const { label } = briefRowPrimary(cat, FAILED_JOBS_ROW);
    expect(label.trim().length).toBeGreaterThan(0);
    expect(label).toMatch(/standard view/);
  });

  it("links to the explicit standard jobs list, not a bare attention href", () => {
    const { href } = briefRowPrimary(rowCategory("failed-jobs"), FAILED_JOBS_ROW);
    expect(href).toBe(standardJobsHref());
    expect(href).toContain("screen=operations");
    expect(href).toContain("tab=jobs");
    expect(href).toContain("standard=1");
    expect(href).not.toBe(FAILED_JOBS_ROW.href);
  });

  it("keeps the other rows' explicit primary destinations unchanged", () => {
    const cat = rowCategory("backup-missing");
    const { href } = briefRowPrimary(cat, { href: "?screen=backups&q=stale", linkLabel: "Open Backups" });
    expect(cat.primaryHref).not.toBeNull();
    expect(href).toBe(cat.primaryHref);
  });
});
