/**
 * Deep link to Trace's Topology path mode carrying a flow four-tuple (`src`, `dst`, `protocol`, `port`).
 *
 * The tuple travels in the URL fragment (`?screen=trace&mode=path#tuple=<src>,<dst>,<tcp|udp>,<port>`), never in the
 * query string: browsers do not send a URL fragment to the server, so flow source and destination addresses do not land
 * in ingress access logs or the server audit trail (AGENTS.md privacy and sensitive identity-reporting laws).
 *
 * `parseTraceTuple` is strict and fail-closed: four comma-separated parts within a bounded fragment length; source and
 * destination dotted-quad IPv4 host addresses (octets `0..255`, no prefix, and no leading zeroes such as `192.0.2.010`
 * so identifiers stay opaque with no normalisation); protocol strictly `tcp` or `udp`; port an integer `1..65535` without
 * sign, whitespace or leading zeroes. Anything else returns `null` without logging or echoing the input.
 */

export type TraceTupleProtocol = "tcp" | "udp";

export interface TraceTuple {
  readonly src: string;
  readonly dst: string;
  readonly protocol: TraceTupleProtocol;
  readonly port: string;
}

export interface TraceTupleInput {
  readonly src: string;
  readonly dst: string;
  readonly protocol: string;
  readonly port: string | number;
}

const TRACE_PATH_BASE = "?screen=trace&mode=path";
const FRAGMENT_PREFIX = "#tuple=";
/** Longest valid `#tuple=255.255.255.255,255.255.255.255,tcp,65535` is 48 characters; refuse over-long inputs up front. */
const MAX_FRAGMENT_LENGTH = 64;

const IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]|[0-9])";
const STRICT_IPV4 = new RegExp(`^${IPV4_OCTET}(?:\\.${IPV4_OCTET}){3}$`);
const STRICT_PORT = /^[1-9][0-9]{0,4}$/;

export function traceTupleHref(tuple: TraceTupleInput): string {
  return `${TRACE_PATH_BASE}${FRAGMENT_PREFIX}${tuple.src},${tuple.dst},${tuple.protocol},${String(tuple.port)}`;
}

export function parseTraceTuple(hash: string | null | undefined): TraceTuple | null {
  if (typeof hash !== "string" || hash.length === 0 || hash.length > MAX_FRAGMENT_LENGTH) {
    return null;
  }
  if (!hash.startsWith(FRAGMENT_PREFIX)) {
    return null;
  }
  if (hash.includes("\r") || hash.includes("\n") || hash.includes("%")) {
    return null;
  }
  const parts = hash.slice(FRAGMENT_PREFIX.length).split(",");
  if (parts.length !== 4) {
    return null;
  }
  const [src, dst, protocol, port] = parts;
  if (!src || !dst || !protocol || !port) {
    return null;
  }
  if (!STRICT_IPV4.test(src) || !STRICT_IPV4.test(dst)) {
    return null;
  }
  if (protocol !== "tcp" && protocol !== "udp") {
    return null;
  }
  if (!STRICT_PORT.test(port)) {
    return null;
  }
  const portNumber = Number(port);
  if (!Number.isInteger(portNumber) || portNumber < 1 || portNumber > 65535) {
    return null;
  }
  return { src, dst, protocol, port };
}
