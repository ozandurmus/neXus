/**
 * Pure IPv4 / IPv6 inclusive range-to-canonical-CIDR decomposition (ZONE_MATRIX_CONTRACT.md section 3.1).
 * Zero DOM, React, or network dependencies; uses BigInt for 32-bit IPv4 and 128-bit IPv6 arithmetic.
 */

export type IpFamily = "IPV4" | "IPV6";

export type DecomposeIpRangeResult =
  | {
      readonly ok: true;
      readonly family: IpFamily;
      readonly cidrs: readonly string[];
      readonly addressCount: bigint;
    }
  | {
      readonly ok: false;
      readonly error: string;
    };

const IPV4_BITS = 32;
const IPV6_BITS = 128;
const IPV4_MAX_CIDRS = 62;
const IPV6_MAX_CIDRS = 254;
const BYTE_MASK = 0xffn;
const HEXTET_MASK = 0xffffn;

/** Parse a strict dotted-quad IPv4 address (no leading zeroes on multi-digit octets) into a 32-bit BigInt. */
function parseIpv4(raw: string): bigint | null {
  const parts = raw.split(".");
  if (parts.length !== 4) return null;
  let value = 0n;
  for (const part of parts) {
    if (!/^(0|[1-9]\d{0,2})$/.test(part)) return null;
    const octet = Number(part);
    if (octet < 0 || octet > 255) return null;
    value = (value << 8n) | BigInt(octet);
  }
  return value;
}

/** Format a 32-bit BigInt as a canonical dotted-quad IPv4 address. */
function formatIpv4(ip: bigint): string {
  const octets: string[] = [];
  for (let shift = 24n; shift >= 0n; shift -= 8n) {
    octets.push(String((ip >> shift) & BYTE_MASK));
  }
  return octets.join(".");
}

/** Parse a standard or `::`-compressed IPv6 address (without zone IDs or CIDR suffixes) into a 128-bit BigInt. */
function parseIpv6(raw: string): bigint | null {
  if (!raw || raw.includes("%") || raw.includes(".")) return null;
  const doubleColonParts = raw.split("::");
  if (doubleColonParts.length > 2) return null;

  let groups: number[];
  if (doubleColonParts.length === 2) {
    const leftRaw = doubleColonParts[0] === "" ? [] : doubleColonParts[0].split(":");
    const rightRaw = doubleColonParts[1] === "" ? [] : doubleColonParts[1].split(":");
    const missing = 8 - (leftRaw.length + rightRaw.length);
    // `::` must compress at least one 16-bit group per RFC 4291.
    if (missing < 1) return null;
    const left = parseHextets(leftRaw);
    const right = parseHextets(rightRaw);
    if (!left || !right) return null;
    groups = [...left, ...new Array<number>(missing).fill(0), ...right];
  } else {
    const rawGroups = raw.split(":");
    if (rawGroups.length !== 8) return null;
    const parsed = parseHextets(rawGroups);
    if (!parsed) return null;
    groups = parsed;
  }

  let value = 0n;
  for (const group of groups) {
    value = (value << 16n) | BigInt(group);
  }
  return value;
}

function parseHextets(parts: readonly string[]): number[] | null {
  const out: number[] = [];
  for (const part of parts) {
    if (!/^[0-9a-fA-F]{1,4}$/.test(part)) return null;
    out.push(Number.parseInt(part, 16));
  }
  return out;
}

/** Format a 128-bit BigInt as canonical RFC 5952 lowercase compressed IPv6 text. */
function formatIpv6(ip: bigint): string {
  const groups: number[] = [];
  for (let i = 7; i >= 0; i -= 1) {
    groups.push(Number((ip >> BigInt(i * 16)) & HEXTET_MASK));
  }

  let bestStart = -1;
  let bestLen = 0;
  let curStart = -1;
  let curLen = 0;
  for (let i = 0; i < 8; i += 1) {
    if (groups[i] === 0) {
      if (curStart === -1) curStart = i;
      curLen += 1;
      if (curLen > bestLen) {
        bestStart = curStart;
        bestLen = curLen;
      }
    } else {
      curStart = -1;
      curLen = 0;
    }
  }

  // RFC 5952: never compress a single 16-bit 0 group.
  if (bestLen < 2) {
    return groups.map((g) => g.toString(16)).join(":");
  }

  const left = groups.slice(0, bestStart).map((g) => g.toString(16)).join(":");
  const right = groups.slice(bestStart + bestLen).map((g) => g.toString(16)).join(":");
  return `${left}::${right}`;
}

/** Compute the exact minimal covering list of canonical CIDR blocks for `[start, end]` inclusive. */
function decomposeInterval(
  start: bigint,
  end: bigint,
  bits: typeof IPV4_BITS | typeof IPV6_BITS,
  family: IpFamily,
): DecomposeIpRangeResult {
  const maxCidrs = bits === IPV4_BITS ? IPV4_MAX_CIDRS : IPV6_MAX_CIDRS;
  const format = bits === IPV4_BITS ? formatIpv4 : formatIpv6;
  const cidrs: string[] = [];
  let cur = start;

  while (cur <= end) {
    let maxStepBits = 0;
    if (cur === 0n) {
      maxStepBits = bits;
    } else {
      let tmp = cur;
      while ((tmp & 1n) === 0n && maxStepBits < bits) {
        tmp >>= 1n;
        maxStepBits += 1;
      }
    }

    const remaining = end - cur + 1n;
    let stepBits = maxStepBits;
    while (stepBits > 0 && (1n << BigInt(stepBits)) > remaining) {
      stepBits -= 1;
    }

    const prefix = bits - stepBits;
    cidrs.push(`${format(cur)}/${prefix}`);
    if (cidrs.length > maxCidrs) {
      return { ok: false, error: `Decomposed range exceeded the ${maxCidrs}-CIDR limit.` };
    }

    cur += 1n << BigInt(stepBits);
  }

  return {
    ok: true,
    family,
    cidrs,
    addressCount: end - start + 1n,
  };
}

/**
 * Parse an inclusive `start-end` IPv4 or IPv6 range and decompose it into its exact minimal list of canonical CIDR
 * blocks. Refuses CIDR `/prefix` strings, mixed families, reversed ranges, invalid syntax, and empty input.
 */
export function decomposeIpRange(input: string): DecomposeIpRangeResult {
  const trimmed = input.trim();
  if (trimmed === "") {
    return { ok: false, error: "Enter an IP range as start-end (for example, 192.0.2.10-192.0.2.25)." };
  }

  if (trimmed.includes("/")) {
    return {
      ok: false,
      error: "CIDR notation (/prefix) is not an IP range. Enter canonical CIDR blocks directly in the Blocks field, or enter a start-end range.",
    };
  }

  const parts = trimmed.split("-");
  if (parts.length < 2) {
    return { ok: false, error: "Enter an IP range with a hyphen between the start and end addresses (start-end)." };
  }
  if (parts.length > 2) {
    return { ok: false, error: "Enter a single start-end range with one hyphen." };
  }

  const startRaw = parts[0].trim();
  const endRaw = parts[1].trim();
  if (startRaw === "" || endRaw === "") {
    return { ok: false, error: "Both start and end addresses are required (start-end)." };
  }

  const startV4 = parseIpv4(startRaw);
  const endV4 = parseIpv4(endRaw);
  const startV6 = parseIpv6(startRaw);
  const endV6 = parseIpv6(endRaw);

  if ((startV4 !== null && endV6 !== null) || (startV6 !== null && endV4 !== null)) {
    return { ok: false, error: "Start and end addresses must belong to the same IP family (both IPv4 or both IPv6)." };
  }

  if (startV4 !== null && endV4 !== null) {
    if (startV4 > endV4) {
      return { ok: false, error: "The start address must be less than or equal to the end address." };
    }
    return decomposeInterval(startV4, endV4, IPV4_BITS, "IPV4");
  }

  if (startV6 !== null && endV6 !== null) {
    if (startV6 > endV6) {
      return { ok: false, error: "The start address must be less than or equal to the end address." };
    }
    return decomposeInterval(startV6, endV6, IPV6_BITS, "IPV6");
  }

  return {
    ok: false,
    error: "Enter valid IPv4 or IPv6 addresses for both start and end (for example, 192.0.2.10-192.0.2.25).",
  };
}
