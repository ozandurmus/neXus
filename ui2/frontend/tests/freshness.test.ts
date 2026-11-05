import { describe, expect, it } from "vitest";

import {
  AGEING_UNTIL_MS, FRESHNESS_LABEL, FRESHNESS_ROLE, FRESH_UNDER_MS, STALE_BANNER_MIN_FRESH_SHARE,
  evidenceIsStale, freshnessOf, freshnessOfAge, inFreshnessBucket, isPastFresh, staleSentence,
} from "../src/shell/freshness";

const NOW = new Date("2026-11-05T12:00:00Z");
const ago = (h: number) => new Date(NOW.getTime() - h * 3_600_000).toISOString();

describe("the one freshness scale (UI review 2026-11-05, B1)", () => {
  it("has the server's bucket edges: under 24 h fresh, up to and including 72 h ageing, older stale", () => {
    expect(FRESH_UNDER_MS).toBe(24 * 3_600_000);
    expect(AGEING_UNTIL_MS).toBe(72 * 3_600_000);
    expect(freshnessOf(ago(23.9), NOW)).toBe("fresh");
    expect(freshnessOf(ago(24), NOW)).toBe("ageing");
    expect(freshnessOf(ago(72), NOW)).toBe("ageing");
    expect(freshnessOf(ago(72.1), NOW)).toBe("stale");
  });

  it("calls a missing or unparseable time unknown, never fresh", () => {
    expect(freshnessOf(null, NOW)).toBe("unknown");
    expect(freshnessOf("", NOW)).toBe("unknown");
    expect(freshnessOf("not-a-time", NOW)).toBe("unknown");
    expect(freshnessOfAge(null)).toBe("unknown");
    expect(isPastFresh(null, NOW)).toBe(false);
    expect(isPastFresh(ago(25), NOW)).toBe(true);
  });

  it("gives each level one colour role and one label", () => {
    expect(FRESHNESS_ROLE).toEqual({ fresh: "ok", ageing: "warn", stale: "bad", unknown: "unknown" });
    expect(FRESHNESS_LABEL).toEqual({ fresh: "under 24 h", ageing: "24–72 h", stale: "over 72 h", unknown: "never read" });
  });

  it("files a device in the Overview's age buckets the way the server counts them", () => {
    const now = NOW.getTime();
    expect(inFreshnessBucket(ago(2), "lt24h", now)).toBe(true);
    expect(inFreshnessBucket(ago(30), "24h_72h", now)).toBe(true);
    expect(inFreshnessBucket(ago(80), "gt72h", now)).toBe(true);
    expect(inFreshnessBucket(null, "never", now)).toBe(true);
    // "stale" is everything not read in the last 24 h, never-read included
    expect(inFreshnessBucket(ago(30), "stale", now)).toBe(true);
    expect(inFreshnessBucket(null, "stale", now)).toBe(true);
    expect(inFreshnessBucket(ago(2), "stale", now)).toBe(false);
  });

  it("raises the stale-data banner below 80 % of active devices read in 24 h, never on an empty estate", () => {
    expect(STALE_BANNER_MIN_FRESH_SHARE).toBe(0.8);
    expect(evidenceIsStale({ fresh: 80, of: 100 })).toBe(false);
    expect(evidenceIsStale({ fresh: 79, of: 100 })).toBe(true);
    expect(evidenceIsStale({ fresh: 1, of: 132 })).toBe(true);
    expect(evidenceIsStale({ fresh: 0, of: 0 })).toBe(false);
  });

  it("says the stale share in plain words, never-read devices apart", () => {
    expect(staleSentence({ fresh: 1, of: 132 })).toBe("131 of 132 active devices were last read more than 24 h ago");
    expect(staleSentence({ fresh: 1, of: 132, never: 3 })).toBe("128 of 132 active devices were last read more than 24 h ago; 3 have never been read");
    expect(staleSentence({ fresh: 0, of: 1, never: 1 })).toBe("1 has never been read");
  });
});
