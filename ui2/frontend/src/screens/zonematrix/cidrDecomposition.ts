import type { AddressFamily } from "./zoneMatrixApi";

/**
 * Pure exact IPv4 (32-bit) and IPv6 (128-bit) range-to-CIDR decomposition for the Zone Matrix editor
 * (docs/design/ZONE_MATRIX_CONTRACT.md section 3.1, 7.1).
 *
 * No external libraries, no DNS lookups, no embedded IPv4-in-IPv6, no IPv6 zone identifiers, and no
 * silent correction of CIDR lines with host bits set.
 */

const IPV4_BITS = 32;
const IPV6_BITS = 128;
const MAX_IPV4 = (1n << 32n) - 1n;
const MAX_IPV6 = (1n << 128n) - 1n;

const IPV4_OCTET_RE = /^(?:0|[1-9][0-9]{0,2})$/;
const IPV6_HEXTET_RE = /^[0-9a-fA-F]{1,4}$/;

export interface DecomposedIpRange {
  readonly family: AddressFamily;
  readonly start: bigint;
  readonly end: bigint;
  readonly addressCount: bigint;
  readonly cidrs: readonly string[];
}

export type DecompositionOutcome =
  | { readonly ok: true; readonly result: DecomposedIpRange }
  | { readonly ok: false; readonly reason: string };

export type ClassifiedBlockLine =
  | { readonly kind: "cidr"; readonly line: string }
  | { readonly kind: "range"; readonly line: string; readonly decomposed: DecomposedIpRange }
  | { readonly kind: "invalid_range"; readonly line: string; readonly reason: string };

/**
 * Parse a dotted-quad IPv4 address into a 32-bit unsigned bigint.
 * Rejects leading zeroes, non-decimal tokens, CIDR prefixes, and octets outside 0..255.
 */
export function parseIpv4(text: string): bigint | null {
  const s = text.trim();
  if (s === "" || s.includes("/") || s.includes(":")) return null;
  const parts = s.split(".");
  if (parts.length !== 4) return null;
  let value = 0n;
  for (const part of parts) {
    if (!IPV4_OCTET_RE.test(part)) return null;
    const octet = Number(part);
    if (!Number.isInteger(octet) || octet < 0 || octet > 255) return null;
    value = (value << 8n) | BigInt(octet);
  }
  return value;
}

/**
 * Parse an RFC 4291 hex-group IPv6 address into a 128-bit unsigned bigint.
 * Rejects embedded IPv4 (`::ffff:192.0.2.1`), zone IDs (`%eth0`), CIDR prefixes, and multiple `::`.
 */
export function parseIpv6(text: string): bigint | null {
  const s = text.trim();
  if (s === "" || s.includes(".") || s.includes("%") || s.includes("/")) return null;
  const halves = s.split("::");
  if (halves.length > 2) return null;

  let fullGroups: string[];
  if (halves.length === 2) {
    const [leftRaw, rightRaw] = halves;
    const leftGroups = leftRaw === "" ? [] : leftRaw.split(":");
    const rightGroups = rightRaw === "" ? [] : rightRaw.split(":");
    const missing = 8 - (leftGroups.length + rightGroups.length);
    if (missing < 1) return null;
    for (const g of leftGroups) {
      if (!IPV6_HEXTET_RE.test(g)) return null;
    }
    for (const g of rightGroups) {
      if (!IPV6_HEXTET_RE.test(g)) return null;
    }
    const zeros = Array.from({ length: missing }, () => "0");
    fullGroups = [...leftGroups, ...zeros, ...rightGroups];
  } else {
    const groups = s.split(":");
    if (groups.length !== 8) return null;
    for (const g of groups) {
      if (!IPV6_HEXTET_RE.test(g)) return null;
    }
    fullGroups = groups;
  }

  let value = 0n;
  for (const group of fullGroups) {
    const hextet = Number.parseInt(group, 16);
    value = (value << 16n) | BigInt(hextet);
  }
  return value;
}

function parseIpAddress(text: string): { readonly family: AddressFamily; readonly value: bigint } | null {
  const v4 = parseIpv4(text);
  if (v4 !== null) return { family: "IPV4", value: v4 };
  const v6 = parseIpv6(text);
  if (v6 !== null) return { family: "IPV6", value: v6 };
  return null;
}

/**
 * Format an unsigned bigint IP address in canonical dotted-quad IPv4 or lowercase RFC 5952 IPv6 form.
 */
export function formatIpAddress(family: AddressFamily, value: bigint): string {
  if (family === "IPV4") {
    if (value < 0n || value > MAX_IPV4) {
      throw new RangeError("IPv4 value out of 32-bit range");
    }
    const b0 = Number((value >> 24n) & 0xffn);
    const b1 = Number((value >> 16n) & 0xffn);
    const b2 = Number((value >> 8n) & 0xffn);
    const b3 = Number(value & 0xffn);
    return `${b0}.${b1}.${b2}.${b3}`;
  }
  if (value < 0n || value > MAX_IPV6) {
    throw new RangeError("IPv6 value out of 128-bit range");
  }
  const hextets: number[] = [];
  for (let i = 7; i >= 0; i--) {
    hextets.push(Number((value >> BigInt(i * 16)) & 0xffffn));
  }

  let bestStart = -1;
  let bestLen = 0;
  let curStart = -1;
  let curLen = 0;
  for (let i = 0; i < 8; i++) {
    if (hextets[i] === 0) {
      if (curLen === 0) curStart = i;
      curLen++;
      if (curLen >= 2 && curLen > bestLen) {
        bestStart = curStart;
        bestLen = curLen;
      }
    } else {
      curLen = 0;
    }
  }

  if (bestStart === -1) {
    return hextets.map((h) => h.toString(16)).join(":");
  }
  const left = hextets.slice(0, bestStart).map((h) => h.toString(16)).join(":");
  const right = hextets.slice(bestStart + bestLen).map((h) => h.toString(16)).join(":");
  return `${left}::${right}`;
}

function trailingZeroBits(value: bigint, maxBits: number): number {
  if (value === 0n) return maxBits;
  let count = 0;
  let temp = value;
  while (count < maxBits && (temp & 1n) === 0n) {
    count++;
    temp >>= 1n;
  }
  return count;
}

function floorLog2(positiveValue: bigint): number {
  let bits = 0;
  let temp = positiveValue >> 1n;
  while (temp > 0n) {
    bits++;
    temp >>= 1n;
  }
  return bits;
}

/**
 * Decompose an inclusive [start, end] integer address interval into its exact minimal list of canonical CIDRs.
 */
export function decomposeBigIntRange(family: AddressFamily, start: bigint, end: bigint): DecompositionOutcome {
  if (family !== "IPV4" && family !== "IPV6") {
    return { ok: false, reason: "Unrecognised address family." };
  }
  const totalBits = family === "IPV4" ? IPV4_BITS : IPV6_BITS;
  const maxVal = family === "IPV4" ? MAX_IPV4 : MAX_IPV6;
  if (start < 0n || end < 0n || start > maxVal || end > maxVal) {
    return { ok: false, reason: "Address is outside the valid range for its family." };
  }
  if (start > end) {
    return { ok: false, reason: "Start address must not be greater than end address." };
  }

  const cidrs: string[] = [];
  let cur = start;
  while (cur <= end) {
    const remaining = end - cur + 1n;
    const kAlign = trailingZeroBits(cur, totalBits);
    const kSize = floorLog2(remaining);
    const hostBits = Math.min(kAlign, kSize);
    const prefixLen = totalBits - hostBits;
    cidrs.push(`${formatIpAddress(family, cur)}/${prefixLen}`);
    cur += 1n << BigInt(hostBits);
  }

  return {
    ok: true,
    result: {
      family,
      start,
      end,
      addressCount: end - start + 1n,
      cidrs,
    },
  };
}

/**
 * Parse two plain IPv4 or IPv6 endpoint strings and return their exact minimal canonical CIDR decomposition.
 */
export function decomposeIpRange(startText: string, endText: string): DecompositionOutcome {
  const s = startText.trim();
  const e = endText.trim();
  if (s === "" || e === "") {
    return { ok: false, reason: "Enter both a start address and an end address." };
  }
  if (s.includes("/") || e.includes("/")) {
    return { ok: false, reason: "Range endpoints must be plain IP addresses without a CIDR prefix length." };
  }
  const parsedStart = parseIpAddress(s);
  const parsedEnd = parseIpAddress(e);
  if (!parsedStart && !parsedEnd) {
    return { ok: false, reason: "Start and end addresses are not valid IPv4 or IPv6 addresses." };
  }
  if (!parsedStart) {
    return { ok: false, reason: `Start address "${s}" is not a valid IPv4 or IPv6 address.` };
  }
  if (!parsedEnd) {
    return { ok: false, reason: `End address "${e}" is not a valid IPv4 or IPv6 address.` };
  }
  if (parsedStart.family !== parsedEnd.family) {
    return { ok: false, reason: "Start and end addresses must belong to the same IP family (both IPv4 or both IPv6)." };
  }
  if (parsedStart.value > parsedEnd.value) {
    return { ok: false, reason: "Start address must not be greater than end address." };
  }
  return decomposeBigIntRange(parsedStart.family, parsedStart.value, parsedEnd.value);
}

/**
 * Classify one line from the zone editor's Blocks textarea.
 *
 * Any line containing "/" is classified as `cidr` FIRST so non-canonical CIDRs with host bits set
 * (such as `192.0.2.7/24`) are never silently corrected or intercepted as ranges (contract section 3.1).
 */
export function classifyBlockLine(line: string): ClassifiedBlockLine {
  const trimmed = line.trim();
  if (trimmed.includes("/")) return { kind: "cidr", line: trimmed };

  if (trimmed.includes("..")) {
    const sepIndex = trimmed.indexOf("..");
    const startText = trimmed.slice(0, sepIndex);
    const endText = trimmed.slice(sepIndex + 2);
    if (endText.includes("..")) {
      return {
        kind: "invalid_range",
        line: trimmed,
        reason: "Range line must contain a single '..' separator between start and end addresses.",
      };
    }
    const outcome = decomposeIpRange(startText, endText);
    return outcome.ok
      ? { kind: "range", line: trimmed, decomposed: outcome.result }
      : { kind: "invalid_range", line: trimmed, reason: outcome.reason };
  }

  if (trimmed.includes("-")) {
    const dashIndex = trimmed.indexOf("-");
    const startText = trimmed.slice(0, dashIndex);
    const endText = trimmed.slice(dashIndex + 1);
    if (endText.includes("-")) {
      return {
        kind: "invalid_range",
        line: trimmed,
        reason: "Range line must contain a single '-' separator between start and end addresses.",
      };
    }
    const outcome = decomposeIpRange(startText, endText);
    return outcome.ok
      ? { kind: "range", line: trimmed, decomposed: outcome.result }
      : { kind: "invalid_range", line: trimmed, reason: outcome.reason };
  }

  const singleHost = parseIpAddress(trimmed);
  if (singleHost !== null) {
    const outcome = decomposeBigIntRange(singleHost.family, singleHost.value, singleHost.value);
    if (outcome.ok) {
      return { kind: "range", line: trimmed, decomposed: outcome.result };
    }
  }

  return { kind: "cidr", line: trimmed };
}
