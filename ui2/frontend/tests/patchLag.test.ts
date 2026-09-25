import { describe, expect, it } from "vitest";
import { checkPointLag, paloAltoLag } from "../src/screens/overview/patchLag";

describe("patch lag", () => {
  it("counts Check Point devices below the newest take of their major version", () => {
    const lag = checkPointLag([
      { label: "R81.20 Jumbo Take 119", count: 30 }, { label: "R81.20 · Take 161", count: 6 },
      { label: "R81.10 · Take 130", count: 7 }, { label: null, count: 10 },
    ]);
    expect(lag).toEqual({ behind: 30, of: 43, lines: ["30 on R81.20 below Take 161"] });
  });

  it("counts Palo Alto firewalls below the newest build of their feature release", () => {
    const lag = paloAltoLag([
      { label: "11.1.10-h7", count: 21 }, { label: "11.1.10-h4", count: 18 }, { label: "11.1.11", count: 1 },
    ]);
    expect(lag?.behind).toBe(39);
    expect(lag?.lines[0]).toBe("39 on 11.1 below 11.1.11");
  });

  it("gives nothing for a vendor without build facts", () => {
    expect(checkPointLag([{ label: null, count: 5 }])).toBeNull();
    expect(paloAltoLag(undefined)).toBeNull();
  });
});
