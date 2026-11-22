import type { CutoverSegment } from "../auth/adminApi";
import { relativeAge } from "../shell/time";

/** Segments listed under the Cutover segments section before the section prints "+N more segments". */
export const CUTOVER_SEGMENTS_SHOWN = 8;
/** Standard-view cutover screen; the Estate section only links here, it never edits segments. */
export const CUTOVER_STANDARD_HREF = "?screen=cutover&standard=1";
export const CUTOVER_NOT_REPORTED = "not reported";
export const CUTOVER_FAILED_TEXT = "Cutover segments could not be read";

/** One count-style field of a segment row, with the age of the read that produced it. */
export interface CutoverSegmentField {
  readonly label: string;
  readonly value: string;
  readonly stamp: string;
}

export interface CutoverSegmentRow {
  readonly segmentId: string;
  readonly name: string;
  readonly fields: readonly CutoverSegmentField[];
}

export type CutoverSegmentsModel =
  | { readonly state: "failed"; readonly text: string; readonly stamp: string }
  | {
      readonly state: "ok";
      readonly total: number;
      readonly rows: readonly CutoverSegmentRow[];
      readonly rowsMore: number;
      readonly stamp: string;
    };

/** A list-response count: a finite number, or an array (its length). Anything else is not reported, never 0. */
function countValue(value: unknown): string {
  if (Array.isArray(value)) return String(value.length);
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) return String(value);
  return CUTOVER_NOT_REPORTED;
}

function textValue(value: unknown): string {
  return typeof value === "string" && value.trim() !== "" ? value : CUTOVER_NOT_REPORTED;
}

/**
 * Maps the segment list response to read-only rows. A field the response does not carry shows "not reported";
 * every field carries the age of this read (`readAt`, relative to `now`).
 */
export function buildCutoverSegmentsModel(
  segments: readonly Partial<CutoverSegment>[],
  readAt: Date,
  now: Date,
): CutoverSegmentsModel {
  const stamp = `read ${relativeAge(readAt.toISOString(), now)}`;
  const rows: CutoverSegmentRow[] = segments.slice(0, CUTOVER_SEGMENTS_SHOWN).map((segment) => ({
    segmentId: textValue(segment.segmentId),
    name: textValue(segment.name),
    fields: [
      { label: "Blocks", value: countValue(segment.blocks), stamp },
      { label: "Peer blocks", value: countValue(segment.peerBlocks), stamp },
      { label: "Revision", value: countValue(segment.revision), stamp },
    ],
  }));
  return {
    state: "ok",
    total: segments.length,
    rows,
    rowsMore: Math.max(0, segments.length - rows.length),
    stamp,
  };
}

/** A failed read is UNKNOWN: no rows, no count, and the attempt's own time. */
export function buildFailedCutoverSegmentsModel(readAt: Date, now: Date): CutoverSegmentsModel {
  return {
    state: "failed",
    text: CUTOVER_FAILED_TEXT,
    stamp: `read ${relativeAge(readAt.toISOString(), now)}`,
  };
}
