import { describe, expect, it } from "vitest";
import type { CutoverSegment } from "../src/auth/adminApi";
import {
  buildCutoverSegmentsModel,
  buildFailedCutoverSegmentsModel,
  CUTOVER_FAILED_TEXT,
  CUTOVER_NOT_REPORTED,
  CUTOVER_SEGMENTS_SHOWN,
  CUTOVER_STANDARD_HREF,
  type CutoverSegmentsModel,
} from "../src/estate/estateCutoverSegmentsModel";

const readAt = new Date("2026-11-22T10:00:00Z");
const now = new Date("2026-11-22T10:02:00Z");

function segment(n: number, overrides: Partial<CutoverSegment> = {}): CutoverSegment {
  return {
    segmentId: `seg-tango-${String(n).padStart(2, "0")}`,
    name: `FW-TANGO-0${n} cutover`,
    revision: n,
    blocks: ["192.0.2.10", "192.0.2.11"],
    peerBlocks: ["198.51.100.20"],
    ...overrides,
  };
}

function okModel(model: CutoverSegmentsModel) {
  if (model.state !== "ok") throw new Error(`expected ok model, got ${model.state}`);
  return model;
}

describe("estateCutoverSegmentsModel constants", () => {
  it("keeps the cap, the standard-view link and the UNKNOWN wording fixed", () => {
    expect(CUTOVER_SEGMENTS_SHOWN).toBe(8);
    expect(CUTOVER_STANDARD_HREF).toBe("?screen=cutover&standard=1");
    expect(CUTOVER_NOT_REPORTED).toBe("not reported");
    expect(CUTOVER_FAILED_TEXT).toBe("Cutover segments could not be read");
  });
});

describe("buildCutoverSegmentsModel: ordering and rows", () => {
  it("keeps the response order of segments", () => {
    const model = okModel(buildCutoverSegmentsModel([segment(3), segment(1), segment(2)], readAt, now));
    expect(model.rows.map((row) => row.name)).toEqual([
      "FW-TANGO-03 cutover",
      "FW-TANGO-01 cutover",
      "FW-TANGO-02 cutover",
    ]);
    expect(model.rows.map((row) => row.segmentId)).toEqual(["seg-tango-03", "seg-tango-01", "seg-tango-02"]);
  });

  it("reports the total as the full list length and no overflow for a short list", () => {
    const model = okModel(buildCutoverSegmentsModel([segment(1), segment(2)], readAt, now));
    expect(model.total).toBe(2);
    expect(model.rows).toHaveLength(2);
    expect(model.rowsMore).toBe(0);
  });

  it("caps rows at CUTOVER_SEGMENTS_SHOWN and counts the rest in rowsMore", () => {
    const list = Array.from({ length: 11 }, (_, i) => segment(i + 1));
    const model = okModel(buildCutoverSegmentsModel(list, readAt, now));
    expect(model.total).toBe(11);
    expect(model.rows).toHaveLength(CUTOVER_SEGMENTS_SHOWN);
    expect(model.rows[0].name).toBe("FW-TANGO-01 cutover");
    expect(model.rows[CUTOVER_SEGMENTS_SHOWN - 1].name).toBe("FW-TANGO-08 cutover");
    expect(model.rowsMore).toBe(3);
  });

  it("exactly at the cap has no overflow", () => {
    const list = Array.from({ length: CUTOVER_SEGMENTS_SHOWN }, (_, i) => segment(i + 1));
    const model = okModel(buildCutoverSegmentsModel(list, readAt, now));
    expect(model.rows).toHaveLength(CUTOVER_SEGMENTS_SHOWN);
    expect(model.rowsMore).toBe(0);
  });

  it("emits the three count fields in the fixed order Blocks, Peer blocks, Revision", () => {
    const model = okModel(buildCutoverSegmentsModel([segment(1)], readAt, now));
    expect(model.rows[0].fields.map((field) => field.label)).toEqual(["Blocks", "Peer blocks", "Revision"]);
  });
});

describe("buildCutoverSegmentsModel: counts per segment", () => {
  it("counts block and peer-block arrays by length and reports the revision number", () => {
    const model = okModel(
      buildCutoverSegmentsModel(
        [segment(4, { blocks: ["a", "b", "c"], peerBlocks: [], revision: 7 })],
        readAt,
        now,
      ),
    );
    const values = Object.fromEntries(model.rows[0].fields.map((field) => [field.label, field.value]));
    expect(values).toEqual({ Blocks: "3", "Peer blocks": "0", Revision: "7" });
  });

  it("keeps revision 0 as a reported zero, not as not-reported", () => {
    const model = okModel(buildCutoverSegmentsModel([segment(1, { revision: 0 })], readAt, now));
    const revision = model.rows[0].fields.find((field) => field.label === "Revision");
    expect(revision?.value).toBe("0");
  });

  it("maps a negative, non-finite or non-numeric revision to 'not reported', never 0", () => {
    const bad: unknown[] = [-1, Number.NaN, Number.POSITIVE_INFINITY, "7", null];
    for (const revision of bad) {
      const model = okModel(
        buildCutoverSegmentsModel([{ ...segment(1), revision } as unknown as Partial<CutoverSegment>], readAt, now),
      );
      const field = model.rows[0].fields.find((item) => item.label === "Revision");
      expect(field?.value).toBe(CUTOVER_NOT_REPORTED);
    }
  });

  it("maps a missing block list to 'not reported', never to an empty count", () => {
    const partial = [{ segmentId: "seg-tango-09", name: "FW-TANGO-09 cutover" }] as Partial<CutoverSegment>[];
    const model = okModel(buildCutoverSegmentsModel(partial, readAt, now));
    const values = model.rows[0].fields.map((field) => field.value);
    expect(values).toEqual([CUTOVER_NOT_REPORTED, CUTOVER_NOT_REPORTED, CUTOVER_NOT_REPORTED]);
  });

  it("maps a blank or whitespace-only name and id to 'not reported'", () => {
    const model = okModel(
      buildCutoverSegmentsModel([segment(1, { segmentId: "   ", name: "" })], readAt, now),
    );
    expect(model.rows[0].segmentId).toBe(CUTOVER_NOT_REPORTED);
    expect(model.rows[0].name).toBe(CUTOVER_NOT_REPORTED);
  });
});

describe("buildCutoverSegmentsModel: stamps", () => {
  it("stamps every field and the section with the age of this read", () => {
    const model = okModel(buildCutoverSegmentsModel([segment(1), segment(2)], readAt, now));
    expect(model.stamp).toBe("read 2 min ago");
    for (const row of model.rows) {
      for (const field of row.fields) expect(field.stamp).toBe("read 2 min ago");
    }
  });
});

describe("buildCutoverSegmentsModel: empty input", () => {
  it("an empty successful list is state ok with total 0 and no rows, not a failure", () => {
    const model = okModel(buildCutoverSegmentsModel([], readAt, now));
    expect(model.total).toBe(0);
    expect(model.rows).toEqual([]);
    expect(model.rowsMore).toBe(0);
    expect(model.stamp).toBe("read 2 min ago");
  });
});

describe("buildFailedCutoverSegmentsModel: unreadable read is UNKNOWN", () => {
  it("returns state failed with the failure text and the attempt's own age", () => {
    const model = buildFailedCutoverSegmentsModel(readAt, now);
    expect(model).toEqual({ state: "failed", text: CUTOVER_FAILED_TEXT, stamp: "read 2 min ago" });
  });

  it("never carries a total, rows or ok state, so zero segments cannot read as OK", () => {
    const model = buildFailedCutoverSegmentsModel(readAt, now);
    expect(model.state).not.toBe("ok");
    expect("total" in model).toBe(false);
    expect("rows" in model).toBe(false);
  });

  it("stamps the attempt time, not a later time, when the read is old", () => {
    const later = new Date("2026-11-22T12:00:00Z");
    const model = buildFailedCutoverSegmentsModel(readAt, later);
    expect(model.state).toBe("failed");
    expect(model.stamp).toBe("read 2 h ago");
  });
});
