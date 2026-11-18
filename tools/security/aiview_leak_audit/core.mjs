// Core of the aiview masking leak audit: tokenization, normalization, salted hashing, matching and the report.
// Nothing here ever returns, logs or throws a token, a hash or a response fragment: results are counts by category.
import { createHash } from 'node:crypto';

export const CATEGORIES = ['HOST', 'ADDR', 'IPV4', 'SERIAL', 'CLUSTER', 'USER', 'NAME_WORD'];
const PLACEHOLDERS = new Set(['localhost', 'unknown', 'none', 'null', 'true', 'false', 'default', 'system', 'test']);
const MIN_LENGTH = 4;
const TOKEN = /[A-Za-z0-9][A-Za-z0-9._:@-]*/g;
const IPV4 = /(?<![0-9.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])/g;
const PURE_IPV4 = /^[0-9]+(?:\.[0-9]+){3}$/;

/** Same normalization as export_reference.sql: lower(btrim(value)). */
export function normalize(value) {
  return String(value).replace(/^ +| +$/g, '').toLowerCase();
}

export function digest(salt, normalized) {
  return createHash('sha256').update(`${salt}\n${normalized}`, 'utf8').digest('hex');
}

function eligible(n) {
  return n.length >= MIN_LENGTH && !PLACEHOLDERS.has(n) && !n.startsWith('127.') && n !== '0.0.0.0';
}

/** The normalized strings one token can stand for: itself, its host/port parts, dot prefixes, 4+ char words. */
export function tokenVariants(token) {
  const out = new Set();
  const lower = normalize(token).replace(/[._:@-]+$/, '');
  if (!lower || PURE_IPV4.test(lower)) return out;
  const add = value => { if (eligible(value)) out.add(value); };
  add(lower);
  for (const part of lower.split(/[:@]/)) {
    if (!part || PURE_IPV4.test(part)) continue;
    add(part);
    const labels = part.split('.');
    for (let i = 1; i < labels.length; i++) add(labels.slice(0, i).join('.'));
  }
  for (const word of lower.split(/[^a-z0-9]+/)) add(word);
  return out;
}

/** One entry per matched (occurrence, category); returns a Map category -> count. */
export class Scanner {
  /** @param {Map<string, Set<string>>} reference digest -> categories */
  constructor(reference, salt) {
    this.reference = reference;
    this.salt = salt;
    this.cache = new Map();
  }

  digestOf(normalized) {
    let h = this.cache.get(normalized);
    if (h === undefined) {
      if (this.cache.size > 200_000) this.cache.clear();
      h = digest(this.salt, normalized);
      this.cache.set(normalized, h);
    }
    return h;
  }

  scan(text) {
    const counts = new Map();
    const bump = categories => { for (const c of categories) counts.set(c, (counts.get(c) ?? 0) + 1); };
    for (const m of text.matchAll(TOKEN)) {
      const found = new Set();
      for (const variant of tokenVariants(m[0])) {
        const cats = this.reference.get(this.digestOf(variant));
        if (cats) for (const c of cats) found.add(c);
      }
      bump(found);
    }
    for (const m of text.matchAll(IPV4)) {
      const n = normalize(m[0]);
      if (!eligible(n)) continue;
      const cats = this.reference.get(this.digestOf(n));
      if (cats) bump(cats);
    }
    return counts;
  }
}

/** Parse the export file: "#src" meta lines and "CATEGORY<TAB>hex" lines. Returns counts only besides the map. */
export function parseReference(text) {
  const reference = new Map();
  const perCategory = Object.fromEntries(CATEGORIES.map(c => [c, 0]));
  const sources = [];
  for (const line of text.split('\n')) {
    if (line.startsWith('#src\t')) {
      const [, category, column, rows] = line.split('\t');
      sources.push({ category, column, rows: Number(rows) || 0 });
      continue;
    }
    const m = /^([A-Z0-9_]+)\t([0-9a-f]{64})$/.exec(line.trim());
    if (!m || !CATEGORIES.includes(m[1])) continue;
    let set = reference.get(m[2]);
    if (!set) reference.set(m[2], set = new Set());
    if (!set.has(m[1])) { set.add(m[1]); perCategory[m[1]]++; }
  }
  return { reference, perCategory, sources };
}

export class Report {
  constructor() { this.routes = new Map(); }

  /** @param route the route template from routes.json (never a resolved identifier) */
  add(route, { bytes = 0, status = 0, matches = new Map(), headerMatches = new Map(), skipped = false, truncated = false }) {
    let r = this.routes.get(route);
    if (!r) {
      r = { requests: 0, ok: 0, client_error: 0, server_error: 0, other: 0, skipped: 0, truncated: 0, bytes_scanned: 0,
        matches: Object.fromEntries(CATEGORIES.map(c => [c, 0])), header_matches: 0 };
      this.routes.set(route, r);
    }
    r.requests++;
    if (skipped) r.skipped++;
    else if (status >= 200 && status < 300) r.ok++;
    else if (status >= 400 && status < 500) r.client_error++;
    else if (status >= 500) r.server_error++;
    else r.other++;
    if (truncated) r.truncated++;
    r.bytes_scanned += bytes;
    for (const [c, n] of matches) r.matches[c] = (r.matches[c] ?? 0) + n;
    for (const n of headerMatches.values()) r.header_matches += n;
    for (const [c, n] of headerMatches) r.matches[c] = (r.matches[c] ?? 0) + n;
  }

  addUnprobed(route) { this.unprobed = [...(this.unprobed ?? []), route]; }

  toJSON(referenceSummary = {}) {
    const routes = {};
    const totals = { requests: 0, ok: 0, bytes_scanned: 0, routes_with_matches: 0, matches: Object.fromEntries(CATEGORIES.map(c => [c, 0])) };
    for (const [route, r] of [...this.routes].sort(([a], [b]) => (a < b ? -1 : 1))) {
      routes[route] = r;
      totals.requests += r.requests;
      totals.ok += r.ok;
      totals.bytes_scanned += r.bytes_scanned;
      let any = false;
      for (const c of CATEGORIES) { totals.matches[c] += r.matches[c] ?? 0; if (r.matches[c]) any = true; }
      if (any) totals.routes_with_matches++;
    }
    const routes_without_ok = [...this.routes].filter(([, r]) => r.ok === 0).map(([route]) => route).sort();
    return { reference: referenceSummary, totals, routes_without_ok_response: routes_without_ok,
      routes_not_probed: [...(this.unprobed ?? [])].sort(), routes };
  }
}
