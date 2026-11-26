import { describe, expect, it } from "vitest";

import {
  classifyBlockLine,
  decomposeBigIntRange,
  decomposeIpRange,
  formatIpAddress,
  parseIpv4,
  parseIpv6,
} from "../src/screens/zonematrix/cidrDecomposition";
import type { AddressFamily } from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * Verify that `cidrs` are canonical (host bits zero), sorted, contiguous with zero gaps or overlaps,
 * begin at `expectedStart`, end at `expectedEnd`, and sum in size to `expectedEnd - expectedStart + 1n`.
 */
function assertExactContiguousPartition(
  family: AddressFamily,
  cidrs: readonly string[],
  expectedStart: bigint,
  expectedEnd: bigint,
): void {
  const totalBits = family === "IPV4" ? 32 : 128;
  const parseFn = family === "IPV4" ? parseIpv4 : parseIpv6;
  expect(cidrs.length).toBeGreaterThan(0);

  let cursor = expectedStart;
  let totalAddresses = 0n;
  for (const cidr of cidrs) {
    const slash = cidr.indexOf("/");
    expect(slash).toBeGreaterThan(0);
    const addrText = cidr.slice(0, slash);
    const prefixLen = Number(cidr.slice(slash + 1));
    expect(Number.isInteger(prefixLen)).toBe(true);
    expect(prefixLen).toBeGreaterThanOrEqual(0);
    expect(prefixLen).toBeLessThanOrEqual(totalBits);

    const parsed = parseFn(addrText);
    expect(parsed).not.toBeNull();
    expect(formatIpAddress(family, parsed!)).toBe(addrText);

    const hostBits = BigInt(totalBits - prefixLen);
    const blockSize = 1n << hostBits;
    const hostMask = blockSize - 1n;
    // Canonical check: host bits are zero.
    expect(parsed! & hostMask).toBe(0n);
    // Contiguity check: starts right where previous block ended + 1.
    expect(parsed).toBe(cursor);

    cursor = parsed! + blockSize;
    totalAddresses += blockSize;
  }

  expect(cursor - 1n).toBe(expectedEnd);
  expect(totalAddresses).toBe(expectedEnd - expectedStart + 1n);
}

describe("cidrDecomposition — IPv4 parsing, formatting and exact range decomposition", () => {
  it("decomposes arbitrary non-aligned IPv4 ranges into minimal canonical CIDRs", () => {
    const out = decomposeIpRange("192.0.2.10", "192.0.2.20");
    expect(out.ok).toBe(true);
    if (!out.ok) return;
    expect(out.result.family).toBe("IPV4");
    expect(out.result.addressCount).toBe(11n);
    expect(out.result.cidrs).toEqual([
      "192.0.2.10/31",
      "192.0.2.12/30",
      "192.0.2.16/30",
      "192.0.2.20/32",
    ]);
    assertExactContiguousPartition("IPV4", out.result.cidrs, out.result.start, out.result.end);
  });

  it("decomposes 198.51.100.1..198.51.100.254 contiguously with exact address count 254", () => {
    const classified = classifyBlockLine("198.51.100.1..198.51.100.254");
    expect(classified.kind).toBe("range");
    if (classified.kind !== "range") return;
    expect(classified.decomposed.family).toBe("IPV4");
    expect(classified.decomposed.addressCount).toBe(254n);
    expect(classified.decomposed.cidrs).toEqual([
      "198.51.100.1/32",
      "198.51.100.2/31",
      "198.51.100.4/30",
      "198.51.100.8/29",
      "198.51.100.16/28",
      "198.51.100.32/27",
      "198.51.100.64/26",
      "198.51.100.128/26",
      "198.51.100.192/27",
      "198.51.100.224/28",
      "198.51.100.240/29",
      "198.51.100.248/30",
      "198.51.100.252/31",
      "198.51.100.254/32",
    ]);
    assertExactContiguousPartition(
      "IPV4",
      classified.decomposed.cidrs,
      classified.decomposed.start,
      classified.decomposed.end,
    );
  });

  it("handles single host (/32), exact /24 boundary, and full 0.0.0.0 - 255.255.255.255 (/0)", () => {
    const host = decomposeIpRange("203.0.113.7", "203.0.113.7");
    expect(host).toEqual({
      ok: true,
      result: {
        family: "IPV4",
        start: parseIpv4("203.0.113.7"),
        end: parseIpv4("203.0.113.7"),
        addressCount: 1n,
        cidrs: ["203.0.113.7/32"],
      },
    });

    const bareHost = classifyBlockLine("  203.0.113.7  ");
    expect(bareHost.kind).toBe("range");
    if (bareHost.kind === "range") {
      expect(bareHost.decomposed.cidrs).toEqual(["203.0.113.7/32"]);
      expect(bareHost.decomposed.addressCount).toBe(1n);
    }

    const slash24 = decomposeIpRange("192.0.2.0", "192.0.2.255");
    expect(slash24.ok).toBe(true);
    if (slash24.ok) {
      expect(slash24.result.cidrs).toEqual(["192.0.2.0/24"]);
      expect(slash24.result.addressCount).toBe(256n);
      assertExactContiguousPartition("IPV4", slash24.result.cidrs, slash24.result.start, slash24.result.end);
    }

    const fullSpace = decomposeIpRange("0.0.0.0", "255.255.255.255");
    expect(fullSpace.ok).toBe(true);
    if (fullSpace.ok) {
      expect(fullSpace.result.cidrs).toEqual(["0.0.0.0/0"]);
      expect(fullSpace.result.addressCount).toBe(4294967296n);
      assertExactContiguousPartition("IPV4", fullSpace.result.cidrs, fullSpace.result.start, fullSpace.result.end);
    }
  });
});

describe("cidrDecomposition — IPv6 parsing, RFC 5952 canonical formatting and exact range decomposition", () => {
  it("decomposes IPv6 ranges into minimal canonical RFC 5952 CIDRs", () => {
    const out = decomposeIpRange("2001:db8::1", "2001:db8::4");
    expect(out.ok).toBe(true);
    if (!out.ok) return;
    expect(out.result.family).toBe("IPV6");
    expect(out.result.addressCount).toBe(4n);
    expect(out.result.cidrs).toEqual([
      "2001:db8::1/128",
      "2001:db8::2/127",
      "2001:db8::4/128",
    ]);
    assertExactContiguousPartition("IPV6", out.result.cidrs, out.result.start, out.result.end);

    const wider = decomposeIpRange("2001:0DB8:0000:0000:0000:0000:0000:0003", "2001:db8::12");
    expect(wider.ok).toBe(true);
    if (wider.ok) {
      expect(wider.result.addressCount).toBe(16n);
      assertExactContiguousPartition("IPV6", wider.result.cidrs, wider.result.start, wider.result.end);
    }
  });

  it("formats RFC 5952 edge cases: lowercase, leading/trailing ::, single zero preserved, leftmost tie-break", () => {
    const fmt = (addr: string) => {
      const parsed = parseIpv6(addr);
      expect(parsed).not.toBeNull();
      return formatIpAddress("IPV6", parsed!);
    };

    expect(fmt("0:0:0:0:0:0:0:0")).toBe("::");
    expect(fmt("0:0:0:0:0:0:0:1")).toBe("::1");
    expect(fmt("2001:0DB8:0:0:0:0:0:0")).toBe("2001:db8::");
    // Single 0 hextet is never collapsed to `::`.
    expect(fmt("2001:db8:0:1:2:3:4:5")).toBe("2001:db8:0:1:2:3:4:5");
    expect(fmt("0:1:2:3:4:5:6:7")).toBe("0:1:2:3:4:5:6:7");
    expect(fmt("1:2:3:4:5:6:7:0")).toBe("1:2:3:4:5:6:7:0");
    // Tie-breaking: equal-length zero runs compress the leftmost run.
    expect(fmt("2001:db8:0:0:1:0:0:1")).toBe("2001:db8::1:0:0:1");
    // Strictly longer later run wins over shorter earlier run.
    expect(fmt("2001:0:0:1:0:0:0:1")).toBe("2001:0:0:1::1");
    // Leading zeros inside hextets stripped and uppercase hex lowercased.
    expect(fmt("2001:0DB8:000A:0000:0000:0000:00B0:0001")).toBe("2001:db8:a::b0:1");
  });

  it("offers /128 for a single IPv6 host and /0 for the full IPv6 space", () => {
    const bare = classifyBlockLine("2001:0DB8::ABCD");
    expect(bare.kind).toBe("range");
    if (bare.kind === "range") {
      expect(bare.decomposed.family).toBe("IPV6");
      expect(bare.decomposed.addressCount).toBe(1n);
      expect(bare.decomposed.cidrs).toEqual(["2001:db8::abcd/128"]);
    }

    const full = decomposeIpRange("::", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff");
    expect(full.ok).toBe(true);
    if (full.ok) {
      expect(full.result.cidrs).toEqual(["::/0"]);
      expect(full.result.addressCount).toBe(1n << 128n);
    }
  });
});

describe("cidrDecomposition — fail-closed validation and non-canonical CIDR pass-through", () => {
  it("fails closed on reversed ranges, mixed families, leading zeroes, out-of-range fields, double ::, and CIDR inputs", () => {
    expect(decomposeIpRange("192.0.2.20", "192.0.2.10").ok).toBe(false);
    expect(decomposeIpRange("2001:db8::9", "2001:db8::2").ok).toBe(false);
    expect(decomposeBigIntRange("IPV4", 20n, 10n).ok).toBe(false);
    expect(decomposeBigIntRange("IPV4", -1n, 5n).ok).toBe(false);
    expect(decomposeBigIntRange("IPV4", 0n, 1n << 32n).ok).toBe(false);
    expect(decomposeBigIntRange("IPV6", 0n, 1n << 128n).ok).toBe(false);

    // Mixed families
    expect(decomposeIpRange("192.0.2.1", "2001:db8::1")).toEqual({
      ok: false,
      reason: "Start and end addresses must belong to the same IP family (both IPv4 or both IPv6).",
    });

    // Leading zeroes in IPv4 octets
    expect(parseIpv4("192.0.02.1")).toBeNull();
    expect(parseIpv4("192.00.2.1")).toBeNull();
    expect(parseIpv4("198.051.100.1")).toBeNull();
    expect(decomposeIpRange("192.0.02.1", "192.0.2.10").ok).toBe(false);

    // Out-of-range octets and hextets
    expect(parseIpv4("192.0.2.256")).toBeNull();
    expect(parseIpv4("192.0.2")).toBeNull();
    expect(parseIpv4("192.0.2.1.5")).toBeNull();
    expect(parseIpv6("2001:db8:10000::1")).toBeNull();
    expect(parseIpv6("2001:db8:gggg::1")).toBeNull();

    // Double `::`, triple `:`, embedded IPv4, zone ID, too many groups with `::`
    expect(parseIpv6("2001:db8::1::2")).toBeNull();
    expect(parseIpv6("2001:db8:::1")).toBeNull();
    expect(parseIpv6("::ffff:192.0.2.1")).toBeNull();
    expect(parseIpv6("2001:db8::1%eth0")).toBeNull();
    expect(parseIpv6("2001:db8:1:2:3:4:5::6")).toBeNull();
    expect(parseIpv6("")).toBeNull();

    // CIDR prefix strings rejected by decomposeIpRange
    expect(decomposeIpRange("192.0.2.0/24", "192.0.2.255").ok).toBe(false);
    expect(decomposeIpRange("2001:db8::/64", "2001:db8::ffff").ok).toBe(false);
    expect(decomposeIpRange("", "192.0.2.5").ok).toBe(false);
  });

  it("classifies every '/' line as kind: 'cidr' FIRST so host-bits-set CIDRs like 192.0.2.7/24 are never silently corrected", () => {
    expect(classifyBlockLine("192.0.2.7/24")).toEqual({ kind: "cidr", line: "192.0.2.7/24" });
    expect(classifyBlockLine("  2001:db8::5/64  ")).toEqual({ kind: "cidr", line: "2001:db8::5/64" });
    expect(classifyBlockLine("192.0.2.0/24 - 192.0.2.255/24")).toEqual({
      kind: "cidr",
      line: "192.0.2.0/24 - 192.0.2.255/24",
    });

    const invalidDash = classifyBlockLine("192.0.2.20 - 192.0.2.10");
    expect(invalidDash.kind).toBe("invalid_range");

    const multiDash = classifyBlockLine("192.0.2.1 - 192.0.2.5 - 192.0.2.9");
    expect(multiDash.kind).toBe("invalid_range");

    const multiDots = classifyBlockLine("192.0.2.1..192.0.2.5..192.0.2.9");
    expect(multiDots.kind).toBe("invalid_range");

    // Non-IP, non-range garbage without '/', '..', or '-' stays `cidr` so server line validation reports it.
    expect(classifyBlockLine("invalid_token")).toEqual({ kind: "cidr", line: "invalid_token" });
  });
});
