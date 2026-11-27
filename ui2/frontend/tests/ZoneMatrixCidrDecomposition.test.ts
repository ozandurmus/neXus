import { describe, expect, it } from "vitest";

import { decomposeIpRange } from "../src/screens/zonematrix/cidrDecomposition";

/** Parse a canonical CIDR string into `[start, end]` inclusive BigInt bounds for round-trip verification. */
function cidrToInterval(cidr: string): { readonly start: bigint; readonly end: bigint; readonly size: bigint } {
  const [addr, prefixText] = cidr.split("/");
  const prefix = Number(prefixText);
  if (addr.includes(".")) {
    const octets = addr.split(".").map(Number);
    const start = octets.reduce((acc, o) => (acc << 8n) | BigInt(o), 0n);
    const hostBits = 32 - prefix;
    const size = 1n << BigInt(hostBits);
    const mask = ((1n << 32n) - 1n) ^ (size - 1n);
    expect(start & mask).toBe(start);
    return { start, end: start + size - 1n, size };
  }

  const [leftRaw, rightRaw] = addr.split("::");
  const left = leftRaw === undefined ? addr.split(":") : (leftRaw === "" ? [] : leftRaw.split(":"));
  const right = rightRaw === undefined ? [] : (rightRaw === "" ? [] : rightRaw.split(":"));
  const missing = addr.includes("::") ? 8 - (left.length + right.length) : 0;
  const hextets = [...left, ...new Array<string>(missing).fill("0"), ...right];
  const start = hextets.reduce((acc, h) => (acc << 16n) | BigInt(Number.parseInt(h, 16)), 0n);
  const hostBits = 128 - prefix;
  const size = 1n << BigInt(hostBits);
  const mask = ((1n << 128n) - 1n) ^ (size - 1n);
  expect(start & mask).toBe(start);
  return { start, end: start + size - 1n, size };
}

function assertExactPartition(cidrs: readonly string[], expectedStart: bigint, expectedEnd: bigint, expectedCount: bigint) {
  let cursor = expectedStart;
  let total = 0n;
  for (const cidr of cidrs) {
    const interval = cidrToInterval(cidr);
    expect(interval.start).toBe(cursor);
    cursor = interval.end + 1n;
    total += interval.size;
  }
  expect(cursor - 1n).toBe(expectedEnd);
  expect(total).toBe(expectedCount);
}

describe("decomposeIpRange", () => {
  it("decomposes a single IPv4 address into /32", () => {
    expect(decomposeIpRange("192.0.2.5-192.0.2.5")).toEqual({
      ok: true,
      family: "IPV4",
      cidrs: ["192.0.2.5/32"],
      addressCount: 1n,
    });
  });

  it("decomposes an aligned IPv4 subnet into a single CIDR", () => {
    expect(decomposeIpRange("192.0.2.0-192.0.2.255")).toEqual({
      ok: true,
      family: "IPV4",
      cidrs: ["192.0.2.0/24"],
      addressCount: 256n,
    });
  });

  it("decomposes an unaligned IPv4 range with optional whitespace around the hyphen", () => {
    const result = decomposeIpRange("  192.0.2.10 - 192.0.2.25  ");
    expect(result).toEqual({
      ok: true,
      family: "IPV4",
      cidrs: [
        "192.0.2.10/31",
        "192.0.2.12/30",
        "192.0.2.16/29",
        "192.0.2.24/31",
      ],
      addressCount: 16n,
    });
    if (result.ok) {
      assertExactPartition(result.cidrs, 0xc000020an, 0xc0000219n, result.addressCount);
    }
  });

  it("decomposes full IPv4 space into 0.0.0.0/0", () => {
    expect(decomposeIpRange("0.0.0.0-255.255.255.255")).toEqual({
      ok: true,
      family: "IPV4",
      cidrs: ["0.0.0.0/0"],
      addressCount: 4294967296n,
    });
  });

  it("decomposes a single IPv6 address into canonical lowercase compressed /128", () => {
    expect(decomposeIpRange("2001:0DB8:0000:0000:0000:0000:0000:0001 - 2001:db8::1")).toEqual({
      ok: true,
      family: "IPV6",
      cidrs: ["2001:db8::1/128"],
      addressCount: 1n,
    });
  });

  it("decomposes an unaligned IPv6 range into canonical lowercase compressed CIDRs", () => {
    const result = decomposeIpRange("2001:db8::1-2001:db8::a");
    expect(result).toEqual({
      ok: true,
      family: "IPV6",
      cidrs: [
        "2001:db8::1/128",
        "2001:db8::2/127",
        "2001:db8::4/126",
        "2001:db8::8/127",
        "2001:db8::a/128",
      ],
      addressCount: 10n,
    });
    if (result.ok) {
      assertExactPartition(
        result.cidrs,
        0x20010db8000000000000000000000001n,
        0x20010db800000000000000000000000an,
        result.addressCount,
      );
    }
  });

  it("decomposes full IPv6 space into ::/0", () => {
    expect(decomposeIpRange("::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")).toEqual({
      ok: true,
      family: "IPV6",
      cidrs: ["::/0"],
      addressCount: 1n << 128n,
    });
  });

  it("preserves RFC 5952 compression rules (never compresses a single zero hextet, leftmost longest tie wins)", () => {
    const singleZero = decomposeIpRange("2001:db8:0:1:2:3:4:5-2001:db8:0:1:2:3:4:5");
    expect(singleZero.ok && singleZero.cidrs).toEqual(["2001:db8:0:1:2:3:4:5/128"]);

    const tie = decomposeIpRange("2001:db8:0:0:1:0:0:1-2001:db8:0:0:1:0:0:1");
    expect(tie.ok && tie.cidrs).toEqual(["2001:db8::1:0:0:1/128"]);
  });

  it("maintains exact round-trip address-count and partition equality on worst-case bounded ranges", () => {
    const v4MinPlusOne = [0, 0, 0, 1].join(".");
    const v4MaxMinusOne = [255, 255, 255, 254].join(".");
    const v4Worst = decomposeIpRange(`${v4MinPlusOne}-${v4MaxMinusOne}`);
    expect(v4Worst.ok).toBe(true);
    if (v4Worst.ok) {
      expect(v4Worst.cidrs).toHaveLength(62);
      assertExactPartition(v4Worst.cidrs, 1n, (1n << 32n) - 2n, (1n << 32n) - 2n);
    }

    const v6Worst = decomposeIpRange("::1-ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffe");
    expect(v6Worst.ok).toBe(true);
    if (v6Worst.ok) {
      expect(v6Worst.cidrs).toHaveLength(254);
      assertExactPartition(v6Worst.cidrs, 1n, (1n << 128n) - 2n, (1n << 128n) - 2n);
    }
  });

  it.each([
    ["empty string", ""],
    ["whitespace only", "   \t  "],
    ["CIDR IPv4 input", "192.0.2.0/24"],
    ["non-canonical CIDR IPv4 input", "192.0.2.7/24"],
    ["CIDR IPv6 input", "2001:db8::/32"],
    ["reversed IPv4 range", "192.0.2.25-192.0.2.10"],
    ["reversed IPv6 range", "2001:db8::10-2001:db8::1"],
    ["mixed IPv4 and IPv6", "192.0.2.1-2001:db8::1"],
    ["mixed IPv6 and IPv4", "2001:db8::1-192.0.2.1"],
    ["single IP without hyphen", "192.0.2.1"],
    ["multiple hyphens", "192.0.2.1-192.0.2.2-192.0.2.3"],
    ["missing end address", "192.0.2.1-"],
    ["missing start address", "-192.0.2.2"],
    ["non-IP garbage", "not-an-ip"],
    ["out-of-range IPv4 octet", "192.0.2.256-192.0.2.257"],
    ["leading zero IPv4 octet", "192.0.2.01-192.0.2.2"],
    ["triple colon in IPv6", "2001:db8:::1-2001:db8::2"],
    ["multiple :: in IPv6", "2001::db8::1-2001::db8::2"],
    ["IPv6 zone ID", "2001:db8::1%eth0-2001:db8::2"],
  ])("fails closed on %s", (_label, badInput) => {
    const result = decomposeIpRange(badInput);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.length).toBeGreaterThan(0);
    }
  });
});
