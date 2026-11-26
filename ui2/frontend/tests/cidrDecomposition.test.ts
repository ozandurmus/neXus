import { describe, expect, it } from "vitest";

import {
  classifyBlockLine, decomposeBigIntRange, decomposeIpRange, decomposeRangeExpression, formatIpAddress, parseIpAddress,
} from "../src/screens/zonematrix/cidrDecomposition";

/**
 * Exact BigInt range-to-CIDR decomposition unit tests (docs/design/ZONE_MATRIX_CONTRACT.md section 3.1).
 * Uses RFC 5737 / RFC 3849 documentation ranges and protocol boundary cases only.
 */
function cidrCoverageCount(family: "IPV4" | "IPV6", cidrs: readonly string[]): bigint {
  const totalBits = family === "IPV4" ? 32 : 128;
  return cidrs.reduce((sum, cidr) => {
    const prefix = Number(cidr.split("/")[1]);
    return sum + (1n << BigInt(totalBits - prefix));
  }, 0n);
}

describe("cidrDecomposition", () => {
  it("decomposes IPv4 single addresses, aligned blocks, unaligned ranges, and /0", () => {
    expect(decomposeIpRange("192.0.2.5", "192.0.2.5")).toEqual({
      ok: true,
      result: {
        family: "IPV4",
        start: "192.0.2.5",
        end: "192.0.2.5",
        addressCount: 1n,
        cidrs: ["192.0.2.5/32"],
      },
    });

    expect(decomposeIpRange("192.0.2.0", "192.0.2.255")).toEqual({
      ok: true,
      result: {
        family: "IPV4",
        start: "192.0.2.0",
        end: "192.0.2.255",
        addressCount: 256n,
        cidrs: ["192.0.2.0/24"],
      },
    });

    expect(decomposeIpRange("192.0.2.10", "192.0.2.20")).toEqual({
      ok: true,
      result: {
        family: "IPV4",
        start: "192.0.2.10",
        end: "192.0.2.20",
        addressCount: 11n,
        cidrs: ["192.0.2.10/31", "192.0.2.12/30", "192.0.2.16/30", "192.0.2.20/32"],
      },
    });

    const missingLast = decomposeIpRange("192.0.2.0", "192.0.2.254");
    expect(missingLast.ok).toBe(true);
    if (missingLast.ok) {
      expect(missingLast.result.cidrs).toEqual([
        "192.0.2.0/25",
        "192.0.2.128/26",
        "192.0.2.192/27",
        "192.0.2.224/28",
        "192.0.2.240/29",
        "192.0.2.248/30",
        "192.0.2.252/31",
        "192.0.2.254/32",
      ]);
      expect(cidrCoverageCount("IPV4", missingLast.result.cidrs)).toBe(255n);
    }

    expect(decomposeIpRange("0.0.0.0", "255.255.255.255")).toEqual({
      ok: true,
      result: {
        family: "IPV4",
        start: "0.0.0.0",
        end: "255.255.255.255",
        addressCount: 1n << 32n,
        cidrs: ["0.0.0.0/0"],
      },
    });

    const worstCaseV4 = decomposeIpRange("0.0.0.1", "255.255.255.254");
    expect(worstCaseV4.ok).toBe(true);
    if (worstCaseV4.ok) {
      expect(worstCaseV4.result.cidrs).toHaveLength(62);
      expect(worstCaseV4.result.cidrs[0]).toBe("0.0.0.1/32");
      expect(worstCaseV4.result.cidrs[worstCaseV4.result.cidrs.length - 1]).toBe("255.255.255.254/32");
      expect(cidrCoverageCount("IPV4", worstCaseV4.result.cidrs)).toBe((1n << 32n) - 2n);
    }
  });

  it("decomposes IPv6 single addresses, unaligned ranges, 64-bit boundaries, /0, and enforces RFC 5952", () => {
    expect(decomposeIpRange("2001:0DB8:0000:0000:0000:0000:0000:0001", "2001:db8::1")).toEqual({
      ok: true,
      result: {
        family: "IPV6",
        start: "2001:db8::1",
        end: "2001:db8::1",
        addressCount: 1n,
        cidrs: ["2001:db8::1/128"],
      },
    });

    expect(decomposeIpRange("2001:db8::1", "2001:db8::6")).toEqual({
      ok: true,
      result: {
        family: "IPV6",
        start: "2001:db8::1",
        end: "2001:db8::6",
        addressCount: 6n,
        cidrs: ["2001:db8::1/128", "2001:db8::2/127", "2001:db8::4/127", "2001:db8::6/128"],
      },
    });

    const crossBoundary = decomposeIpRange("2001:db8:0:0:ffff:ffff:ffff:fffe", "2001:db8:0:1::1");
    expect(crossBoundary.ok).toBe(true);
    if (crossBoundary.ok) {
      expect(crossBoundary.result.cidrs).toEqual([
        "2001:db8::ffff:ffff:ffff:fffe/127",
        "2001:db8:0:1::/127",
      ]);
      expect(cidrCoverageCount("IPV6", crossBoundary.result.cidrs)).toBe(4n);
    }

    expect(decomposeIpRange("::", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")).toEqual({
      ok: true,
      result: {
        family: "IPV6",
        start: "::",
        end: "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        addressCount: 1n << 128n,
        cidrs: ["::/0"],
      },
    });

    const worstCaseV6 = decomposeIpRange("::1", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffe");
    expect(worstCaseV6.ok).toBe(true);
    if (worstCaseV6.ok) {
      expect(worstCaseV6.result.cidrs).toHaveLength(254);
      expect(worstCaseV6.result.cidrs[0]).toBe("::1/128");
      expect(worstCaseV6.result.cidrs[worstCaseV6.result.cidrs.length - 1]).toBe("ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffe/128");
      expect(cidrCoverageCount("IPV6", worstCaseV6.result.cidrs)).toBe((1n << 128n) - 2n);
    }

    // RFC 5952 canonicalization details: single zero not compressed; longest run wins; tie goes leftmost.
    expect(parseIpAddress("2001:0db8:0000:0001:0001:0001:0001:0001")?.canonical).toBe("2001:db8:0:1:1:1:1:1");
    expect(parseIpAddress("2001:0:0:1:0:0:0:1")?.canonical).toBe("2001:0:0:1::1");
    expect(parseIpAddress("2001:db8:0:0:1:0:0:1")?.canonical).toBe("2001:db8::1:0:0:1");
    expect(formatIpAddress("IPV6", 0n)).toBe("::");
  });

  it("fails closed on reversed, mixed-family, and malformed inputs", () => {
    expect(decomposeIpRange("192.0.2.20", "192.0.2.10")).toEqual({
      ok: false,
      reason: "Start address must not be greater than end address.",
    });
    expect(decomposeIpRange("2001:db8::10", "2001:db8::1")).toEqual({
      ok: false,
      reason: "Start address must not be greater than end address.",
    });
    expect(decomposeIpRange("192.0.2.1", "2001:db8::1")).toEqual({
      ok: false,
      reason: "Start and end addresses must belong to the same IP family.",
    });
    expect(decomposeIpRange("2001:db8::1", "192.0.2.1")).toEqual({
      ok: false,
      reason: "Start and end addresses must belong to the same IP family.",
    });
    expect(decomposeIpRange("", "192.0.2.1").ok).toBe(false);
    expect(decomposeIpRange("192.0.2.1", "").ok).toBe(false);

    for (const bad of [
      "192.0.2.256",
      "192.0.2.01",
      "192.0.2",
      "192.0.2.1/24",
      ":::",
      "2001:db8::1::2",
      "1:2:3:4:5:6:7::8",
      ":1:2:3:4:5:6:7",
      "1:2:3:4:5:6:7:",
      "2001:db8::g",
      "fe80::1%eth0",
      "::ffff:192.0.2.1",
      "[2001:db8::1]",
    ]) {
      expect(parseIpAddress(bad), bad).toBeNull();
      expect(decomposeIpRange(bad, bad).ok, bad).toBe(false);
    }

    expect(decomposeRangeExpression("192.0.2.1 - 192.0.2.2 - 192.0.2.3").ok).toBe(false);
    expect(decomposeRangeExpression("192.0.2.1..192.0.2.2..192.0.2.3").ok).toBe(false);
    expect(() => decomposeBigIntRange("IPV4", 10n, 5n)).toThrow(RangeError);
    expect(() => decomposeBigIntRange("IPV4", -1n, 5n)).toThrow(RangeError);
    expect(() => decomposeBigIntRange("IPV6", 0n, 1n << 128n)).toThrow(RangeError);
  });

  it("classifies block lines without silently correcting host-bits-set CIDRs", () => {
    // Canonical or host-bits-set CIDRs stay `cidr` so server validation refuses invalid CIDRs rather than silently fixing them.
    expect(classifyBlockLine("192.0.2.0/24")).toEqual({ kind: "cidr", line: "192.0.2.0/24" });
    expect(classifyBlockLine("192.0.2.7/24")).toEqual({ kind: "cidr", line: "192.0.2.7/24" });

    const rangeLine = classifyBlockLine("198.51.100.2 - 198.51.100.5");
    expect(rangeLine.kind).toBe("range");
    if (rangeLine.kind === "range") {
      expect(rangeLine.decomposed.cidrs).toEqual(["198.51.100.2/31", "198.51.100.4/31"]);
    }

    const dotRangeLine = classifyBlockLine("2001:db8::2..2001:db8::3");
    expect(dotRangeLine.kind).toBe("range");
    if (dotRangeLine.kind === "range") {
      expect(dotRangeLine.decomposed.cidrs).toEqual(["2001:db8::2/127"]);
    }

    const bareV4 = classifyBlockLine("203.0.113.9");
    expect(bareV4.kind).toBe("range");
    if (bareV4.kind === "range") {
      expect(bareV4.decomposed.cidrs).toEqual(["203.0.113.9/32"]);
    }

    const bareV6 = classifyBlockLine("2001:0DB8::1");
    expect(bareV6.kind).toBe("range");
    if (bareV6.kind === "range") {
      expect(bareV6.decomposed.cidrs).toEqual(["2001:db8::1/128"]);
    }

    expect(classifyBlockLine("192.0.2.20 - 192.0.2.10")).toEqual({
      kind: "invalid_range",
      line: "192.0.2.20 - 192.0.2.10",
      reason: "Start address must not be greater than end address.",
    });
  });
});
