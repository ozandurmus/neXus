/**
 * Exact BigInt-based IPv4/IPv6 range-to-CIDR decomposition helper for the Zone Matrix Zones tab
 * (docs/design/ZONE_MATRIX_CONTRACT.md section 3.1, FROZEN 2026-11-13 rev 2).
 *
 * All addresses are parsed strictly without name resolution, embedded IPv4-in-IPv6, or zone IDs,
 * matching the server's ZoneBlock validation rules, and formatted in dotted-quad IPv4 or
 * canonical lowercase RFC 5952 IPv6. Invalid, mixed-family, or reversed ranges fail closed.
 */

export type IpFamily = "IPV4" | "IPV6";

export interface ParsedIpAddress {
  readonly family: IpFamily;
  readonly value: bigint;
  readonly canonical: string;
}

export interface DecomposedRange {
  readonly family: IpFamily;
  readonly start: string;
  readonly end: string;
  readonly addressCount: bigint;
  readonly cidrs: readonly string[];
}

export type DecomposeRangeResult =
  | { readonly ok: true; readonly result: DecomposedRange }
  | { readonly ok: false; readonly reason: string };

export type BlockLineClassification =
  | { readonly kind: "cidr"; readonly line: string }
  | { readonly kind: "range"; readonly line: string; readonly decomposed: DecomposedRange }
  | { readonly kind: "invalid_range"; readonly line: string; readonly reason: string };

const FAMILY_BITS: Readonly<Record<IpFamily, number>> = { IPV4: 32, IPV6: 128 };
const FAMILY_MAX: Readonly<Record<IpFamily, bigint>> = {
  IPV4: (1n << 32n) - 1n,
  IPV6: (1n << 128n) - 1n,
};

const IPV4_OCTET_RE = /^(0|[1-9][0-9]{0,2})$/;
const IPV6_CHARS_RE = /^[0-9A-Fa-f:]+$/;
const IPV6_GROUP_RE = /^[0-9A-Fa-f]{1,4}$/;

/** Parses one dotted-quad IPv4 address into an unsigned 32-bit BigInt, or `null` when invalid. */
export function parseIpv4(text: string): bigint | null {
  const parts = text.split(".");
  if (parts.length !== 4) return null;
  let value = 0n;
  for (const part of parts) {
    if (!IPV4_OCTET_RE.test(part)) return null;
    const octet = Number(part);
    if (octet > 255) return null;
    value = (value << 8n) | BigInt(octet);
  }
  return value;
}

function splitIpv6Groups(part: string): readonly string[] | null {
  if (part === "") return [];
  const groups = part.split(":");
  for (const group of groups) {
    if (!IPV6_GROUP_RE.test(group)) return null;
  }
  return groups;
}

/**
 * Parses one IPv6 address (RFC 4291 without embedded IPv4 or zone IDs) into an unsigned 128-bit BigInt,
 * or `null` when invalid.
 */
export function parseIpv6(text: string): bigint | null {
  if (!IPV6_CHARS_RE.test(text)) return null;
  const gap = text.indexOf("::");
  if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) return null;

  let head: readonly string[] | null;
  let tail: readonly string[] | null;
  if (gap >= 0) {
    head = splitIpv6Groups(text.slice(0, gap));
    tail = splitIpv6Groups(text.slice(gap + 2));
    if (!head || !tail || head.length + tail.length > 7) return null;
  } else {
    head = splitIpv6Groups(text);
    tail = [];
    if (!head || head.length !== 8) return null;
  }

  let value = 0n;
  for (const group of head) {
    value = (value << 16n) | BigInt(parseInt(group, 16));
  }
  value <<= BigInt(16 * (8 - head.length - tail.length));
  for (const group of tail) {
    value = (value << 16n) | BigInt(parseInt(group, 16));
  }
  return value;
}

/** Formats an unsigned 32-bit or 128-bit BigInt as dotted-quad IPv4 or canonical lowercase RFC 5952 IPv6. */
export function formatIpAddress(family: IpFamily, value: bigint): string {
  if (value < 0n || value > FAMILY_MAX[family]) {
    throw new RangeError(`Address value out of range for ${family}`);
  }
  if (family === "IPV4") {
    return `${(value >> 24n) & 255n}.${(value >> 16n) & 255n}.${(value >> 8n) & 255n}.${value & 255n}`;
  }
  const groups = new Array<number>(8);
  let cur = value;
  for (let i = 7; i >= 0; i--) {
    groups[i] = Number(cur & 0xffffn);
    cur >>= 16n;
  }
  let bestStart = -1;
  let bestLength = 0;
  for (let i = 0; i < 8; ) {
    if (groups[i] !== 0) {
      i++;
      continue;
    }
    let j = i;
    while (j < 8 && groups[j] === 0) j++;
    if (j - i > bestLength) {
      bestStart = i;
      bestLength = j - i;
    }
    i = j;
  }
  if (bestLength < 2) bestStart = -1;

  let out = "";
  for (let i = 0; i < 8; i++) {
    if (i === bestStart) {
      out += i === 0 ? "::" : ":";
      i += bestLength - 1;
      continue;
    }
    out += groups[i].toString(16);
    if (i < 7) out += ":";
  }
  return out;
}

/** Parses an IPv4 or IPv6 address (trimmed), returning its family, BigInt value, and canonical text, or `null`. */
export function parseIpAddress(text: string): ParsedIpAddress | null {
  const trimmed = text.trim();
  if (trimmed === "" || trimmed.length > 45 || trimmed.includes("/")) return null;
  if (trimmed.includes(":")) {
    const value = parseIpv6(trimmed);
    return value === null ? null : { family: "IPV6", value, canonical: formatIpAddress("IPV6", value) };
  }
  const value = parseIpv4(trimmed);
  return value === null ? null : { family: "IPV4", value, canonical: formatIpAddress("IPV4", value) };
}

/**
 * Computes the exact minimal list of canonical CIDR blocks covering `[start, end]` inclusive.
 * Throws if bounds are outside `[0, FAMILY_MAX[family]]` or `start > end`.
 */
export function decomposeBigIntRange(family: IpFamily, start: bigint, end: bigint): readonly string[] {
  const totalBits = FAMILY_BITS[family];
  const maxVal = FAMILY_MAX[family];
  if (start < 0n || end > maxVal || start > end) {
    throw new RangeError(`Invalid ${family} range bounds`);
  }
  const cidrs: string[] = [];
  let current = start;
  while (current <= end) {
    let tz = 0;
    while (tz < totalBits && ((current >> BigInt(tz)) & 1n) === 0n) {
      tz++;
    }
    const remaining = end - current + 1n;
    let hostBits = tz;
    while (hostBits > 0 && 1n << BigInt(hostBits) > remaining) {
      hostBits--;
    }
    const prefix = totalBits - hostBits;
    cidrs.push(`${formatIpAddress(family, current)}/${prefix}`);
    current += 1n << BigInt(hostBits);
  }
  return cidrs;
}

/**
 * Validates `startText` and `endText` and returns the exact minimal canonical CIDR decomposition,
 * failing closed when either address is invalid, families differ, or `start > end`.
 */
export function decomposeIpRange(startText: string, endText: string): DecomposeRangeResult {
  const sTrimmed = startText.trim();
  const eTrimmed = endText.trim();
  if (sTrimmed === "" || eTrimmed === "") {
    return { ok: false, reason: "Enter both start and end addresses." };
  }
  const start = parseIpAddress(sTrimmed);
  if (!start) {
    return { ok: false, reason: "Invalid start address (expected an IPv4 or IPv6 address without prefix)." };
  }
  const end = parseIpAddress(eTrimmed);
  if (!end) {
    return { ok: false, reason: "Invalid end address (expected an IPv4 or IPv6 address without prefix)." };
  }
  if (start.family !== end.family) {
    return { ok: false, reason: "Start and end addresses must belong to the same IP family." };
  }
  if (start.value > end.value) {
    return { ok: false, reason: "Start address must not be greater than end address." };
  }
  const cidrs = decomposeBigIntRange(start.family, start.value, end.value);
  return {
    ok: true,
    result: {
      family: start.family,
      start: start.canonical,
      end: end.canonical,
      addressCount: end.value - start.value + 1n,
      cidrs,
    },
  };
}

/**
 * Parses a range expression (`start - end`, `start..end`, or a single bare IP address) and returns
 * its exact minimal canonical CIDR decomposition, failing closed on invalid input.
 */
export function decomposeRangeExpression(expr: string): DecomposeRangeResult {
  const trimmed = expr.trim();
  if (trimmed === "") {
    return { ok: false, reason: "Enter an IPv4 or IPv6 address range." };
  }
  if (trimmed.includes("..")) {
    const parts = trimmed.split("..");
    if (parts.length !== 2) {
      return { ok: false, reason: "A range must have one start and one end address." };
    }
    return decomposeIpRange(parts[0], parts[1]);
  }
  if (trimmed.includes("-")) {
    const parts = trimmed.split("-");
    if (parts.length !== 2) {
      return { ok: false, reason: "A range must have one start and one end address." };
    }
    return decomposeIpRange(parts[0], parts[1]);
  }
  const single = parseIpAddress(trimmed);
  if (!single) {
    return { ok: false, reason: "Not a valid IPv4 or IPv6 address range." };
  }
  return decomposeIpRange(trimmed, trimmed);
}

/**
 * Classifies one non-empty line in the zone Blocks input:
 * - Explicit range syntax (`-` or `..`) is parsed as a range (never treated as a CIDR).
 * - Lines with `/` pass through untouched as `cidr` (host-bits-set CIDRs are never silently corrected).
 * - Bare IP addresses without `/` are offered as single-address `/32` or `/128` decompositions.
 * - Anything else passes through untouched so the server returns its normal line refusal.
 */
export function classifyBlockLine(line: string): BlockLineClassification {
  const trimmed = line.trim();
  if (trimmed.includes("..") || trimmed.includes("-")) {
    const res = decomposeRangeExpression(trimmed);
    return res.ok
      ? { kind: "range", line: trimmed, decomposed: res.result }
      : { kind: "invalid_range", line: trimmed, reason: res.reason };
  }
  if (trimmed.includes("/")) {
    return { kind: "cidr", line: trimmed };
  }
  if (parseIpAddress(trimmed) !== null) {
    const res = decomposeIpRange(trimmed, trimmed);
    if (res.ok) return { kind: "range", line: trimmed, decomposed: res.result };
  }
  return { kind: "cidr", line: trimmed };
}
