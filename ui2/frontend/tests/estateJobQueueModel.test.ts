import { describe, expect, it } from "vitest";
import type { JobStatsView } from "../src/auth/adminApi";
import type { TimedRead } from "../src/estate/estateSystemModel";
import {
  JOB_QUEUE_FAILED_TEXT,
  JOB_QUEUE_NOT_REPORTED,
  JOB_QUEUE_RESTRICTED_TEXT,
  mapJobQueue,
  mapJobQueueFigures,
} from "../src/estate/estateJobQueueModel";

// Synthetic test numbers only; they are not production figures.
const FULL: JobStatsView = {
  total: 40,
  total_24h: 12,
  completed_24h: 9,
  failed_24h: 2,
  outcome_unknown_24h: 1,
  running: 3,
};

function okRead(value: unknown, readAt: string | null = "2026-11-23T08:00:00Z"): TimedRead<JobStatsView> {
  return { read: { state: "ok", value: value as JobStatsView }, readAt };
}

function figureValues(stats: JobStatsView) {
  return mapJobQueueFigures(stats).map((f) => [f.id, f.value] as const);
}

describe("mapJobQueue: unreadable reads", () => {
  it("maps a failed read to the failed text, never to a zero or OK section", () => {
    const section = mapJobQueue({ read: { state: "failed" }, readAt: null });
    expect(section).toEqual({ kind: "unreadable", text: JOB_QUEUE_FAILED_TEXT });
    expect(JSON.stringify(section)).not.toMatch(/"kind":"ok"/);
  });

  it("maps a skipped read to the failed text as well (not a zero count)", () => {
    const section = mapJobQueue({ read: { state: "skipped" }, readAt: null });
    expect(section).toEqual({ kind: "unreadable", text: JOB_QUEUE_FAILED_TEXT });
  });

  it("maps a restricted read to the restricted text", () => {
    const section = mapJobQueue({ read: { state: "restricted" }, readAt: null });
    expect(section).toEqual({ kind: "unreadable", text: JOB_QUEUE_RESTRICTED_TEXT });
  });

  it.each([null, undefined, [], "text", 7])("treats an ok read with a non-object payload (%j) as unreadable", (payload) => {
    const section = mapJobQueue(okRead(payload));
    expect(section).toEqual({ kind: "unreadable", text: JOB_QUEUE_FAILED_TEXT });
  });
});

describe("mapJobQueue: ok reads and badge", () => {
  it("shows the four figures with their own counts and an OK-free watch badge when failures exist", () => {
    const section = mapJobQueue(okRead(FULL));
    expect(section.kind).toBe("ok");
    if (section.kind !== "ok") return;
    expect(section.badge).toBe("watch");
    expect(section.figures.map((f) => f.value)).toEqual(["3 jobs", "2 of 12 jobs", "9 jobs", "1 job"]);
  });

  it("is OK when nothing failed or is unknown and every figure is reported", () => {
    const section = mapJobQueue(okRead({ ...FULL, failed_24h: 0, outcome_unknown_24h: 0 }));
    expect(section.kind === "ok" && section.badge).toBe("ok");
  });

  it("is UNKNOWN (never OK) when a figure is not reported", () => {
    const { outcome_unknown_24h: _omitted, ...withoutOutcome } = FULL;
    const section = mapJobQueue(okRead({ ...withoutOutcome, failed_24h: 0 }));
    expect(section.kind).toBe("ok");
    if (section.kind !== "ok") return;
    expect(section.badge).toBe("unk");
    expect(section.figures.find((f) => f.id === "outcome_unknown")?.value).toBeNull();
  });
});

describe("mapJobQueueFigures: each figure keeps its own count", () => {
  it("gives running, failed, completed and outcome_unknown their own values", () => {
    expect(figureValues(FULL)).toEqual([
      ["running", "3 jobs"],
      ["failed", "2 of 12 jobs"],
      ["completed", "9 jobs"],
      ["outcome_unknown", "1 job"],
    ]);
  });

  it("uses the singular for a count of one and the plural otherwise", () => {
    const one = mapJobQueueFigures({ ...FULL, running: 1, completed_24h: 0 });
    expect(one.find((f) => f.id === "running")?.value).toBe("1 job");
    expect(one.find((f) => f.id === "completed")?.value).toBe("0 jobs");
  });

  it.each([
    ["running", "running"],
    ["completed_24h", "completed"],
    ["outcome_unknown_24h", "outcome_unknown"],
  ] as const)("reports a missing %s as not reported (null), never 0", (field, id) => {
    const stats: Record<string, unknown> = { ...FULL };
    delete stats[field];
    const figure = mapJobQueueFigures(stats as unknown as JobStatsView).find((f) => f.id === id);
    expect(figure?.value).toBeNull();
    expect(figure?.value).not.toBe("0 jobs");
  });

  it("reports the failed figure as not reported when its total is missing, keeping the other figures", () => {
    const stats: Record<string, unknown> = { ...FULL };
    delete stats.total_24h;
    const figures = mapJobQueueFigures(stats as unknown as JobStatsView);
    expect(figures.find((f) => f.id === "failed")?.value).toBeNull();
    expect(figures.find((f) => f.id === "running")?.value).toBe("3 jobs");
  });

  it.each([Number.NaN, Number.POSITIVE_INFINITY, "4", null])("treats a non-finite or non-number count (%s) as not reported", (bad) => {
    const figures = mapJobQueueFigures({ ...FULL, running: bad } as unknown as JobStatsView);
    expect(figures.find((f) => f.id === "running")?.value).toBeNull();
  });

  it("keeps a real zero count as 0 and not as not reported", () => {
    const figures = mapJobQueueFigures({ ...FULL, failed_24h: 0, total_24h: 0 });
    expect(figures.find((f) => f.id === "failed")?.value).toBe("0 of 0 jobs");
  });

  it("exposes the not-reported constant text the view renders for a null value", () => {
    expect(JOB_QUEUE_NOT_REPORTED).toBe("not reported");
  });
});

describe("mapJobQueueFigures: stable order", () => {
  it("returns the figures in the fixed screen order regardless of input key order", () => {
    const reordered = {
      outcome_unknown_24h: 1,
      running: 3,
      completed_24h: 9,
      total_24h: 12,
      failed_24h: 2,
    } as unknown as JobStatsView;
    expect(mapJobQueueFigures(reordered).map((f) => f.id)).toEqual(["running", "failed", "completed", "outcome_unknown"]);
    expect(mapJobQueueFigures(FULL).map((f) => f.id)).toEqual(["running", "failed", "completed", "outcome_unknown"]);
  });

  it("returns the same order on repeated calls", () => {
    const first = mapJobQueueFigures(FULL).map((f) => f.id);
    const second = mapJobQueueFigures(FULL).map((f) => f.id);
    expect(second).toEqual(first);
  });
});
