import { describe, expect, it } from "vitest";
import type { JobTranscriptEntry } from "../src/auth/adminApi";
import type { ScriptRunRead } from "../src/estate/estateSystemModel";
import {
  capTranscriptLines,
  latestTranscriptJobRows,
  moreRowsLabel,
  orderTranscriptLines,
  TRANSCRIPT_EMPTY_WORDING,
  TRANSCRIPT_LINE_CAP,
  transcriptFailedWording,
  transcriptLineCountWording,
  transcriptMoreLabel,
  transcriptStateWord,
} from "../src/estate/estateJobTranscriptModel";

function line(seq: number, kind = "response", text = `synthetic line ${seq}`): JobTranscriptEntry {
  return { seq, at: `2026-11-22T10:00:${String(seq % 60).padStart(2, "0")}Z`, elapsedMs: seq, channel: "job", kind, text };
}

describe("estate job transcript model", () => {
  it("orders lines by seq without mutating the input", () => {
    const input = [line(3), line(1), line(2)];
    expect(orderTranscriptLines(input).map((e) => e.seq)).toEqual([1, 2, 3]);
    expect(input.map((e) => e.seq)).toEqual([3, 1, 2]);
  });

  it("caps the lines and counts the lines left out", () => {
    const lines = Array.from({ length: TRANSCRIPT_LINE_CAP + 7 }, (_, i) => line(i + 1));
    const capped = capTranscriptLines(lines);
    expect(capped.shown).toHaveLength(TRANSCRIPT_LINE_CAP);
    expect(capped.hidden).toBe(7);
    expect(transcriptMoreLabel(capped.hidden)).toBe("+7 more lines not shown");
  });

  it("shows no more label when every line fits", () => {
    const capped = capTranscriptLines([line(1), line(2)]);
    expect(capped.shown).toHaveLength(2);
    expect(capped.hidden).toBe(0);
    expect(transcriptMoreLabel(0)).toBeNull();
  });

  it("counts lines in words that say what they count", () => {
    expect(transcriptLineCountWording(1)).toBe("1 line");
    expect(transcriptLineCountWording(0)).toBe("0 lines");
    expect(transcriptLineCountWording(5)).toBe("5 lines");
  });

  it("words the empty and failed states without OK", () => {
    expect(TRANSCRIPT_EMPTY_WORDING).toBe("No transcript lines");
    expect(transcriptFailedWording(false, "HTTP_500")).toContain("UNKNOWN");
    expect(transcriptFailedWording(true, "FORBIDDEN")).toContain("UNKNOWN");
    expect(`${TRANSCRIPT_EMPTY_WORDING} ${transcriptFailedWording(false, "X")} ${transcriptFailedWording(true, "X")}`).not.toMatch(/\bOK\b/);
  });

  it("never shows an entry state word as OK when the kind is absent", () => {
    expect(transcriptStateWord(line(1, "  "))).toBe("UNKNOWN");
    expect(transcriptStateWord(line(1, "command"))).toBe("COMMAND");
    expect(transcriptStateWord(line(1, "ok"))).toBe("OK");
  });

  it("labels the job rows past the list cap", () => {
    expect(moreRowsLabel(2)).toContain("+2 more jobs");
    expect(moreRowsLabel(0)).toBeNull();
  });

  it("picks the newest run per script and skips unread scripts", () => {
    const runs: ScriptRunRead[] = [
      {
        script: { name: "Synthetic script A" } as ScriptRunRead["script"],
        read: { state: "ok", value: [
          { job_id: "FW-TANGO-04-job-1", requested_at: "2026-11-20T08:00:00Z" },
          { job_id: "FW-TANGO-04-job-2", requested_at: "2026-11-21T08:00:00Z" },
        ] as never },
        readAt: "2026-11-22T09:00:00Z",
      },
      {
        script: { name: "Synthetic script B" } as ScriptRunRead["script"],
        read: { state: "failed" },
        readAt: null,
      },
    ];
    expect(latestTranscriptJobRows(runs)).toEqual([
      { scriptName: "Synthetic script A", jobId: "FW-TANGO-04-job-2", requestedAt: "2026-11-21T08:00:00Z" },
    ]);
  });
});
