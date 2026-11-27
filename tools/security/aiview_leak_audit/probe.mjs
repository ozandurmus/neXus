#!/usr/bin/env node
// aiview masking leak audit probe. Runs inside the cluster as the machine replay_viewer (aiview) session, walks the
// GET routes aiview may call (routes.json, derived from the route authorization golden), hashes every token of every
// response body and header with the salted normalization of export_reference.sql and counts matches against the raw
// reference hashes. Output: counts by route and category only -- never a token, a hash, a header, a URL with an
// identifier or a response fragment. Usage: see README.md.
import { parseArgs } from 'node:util';
import { readFileSync } from 'node:fs';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { realpathSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { Scanner, Report, parseReference, CATEGORIES } from './core.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_BASE = 'http://ui2-service.ui2.svc.cluster.local:8080';
const DEFAULT_MACHINE = 'http://ui2-service-internal.ui2.svc.cluster.local:8086/internal/machine-session';
const MAX_BODY_BYTES = 4 * 1024 * 1024;     // bytes scanned per response; the rest is counted as truncated
const SKIP_ABOVE_BYTES = 32 * 1024 * 1024;  // declared size above this is not downloaded
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
const ID_KEY = /^(id|uuid|key|ref|[a-z][a-z0-9_]*_(?:id|ref)|[a-z][A-Za-z0-9]*(?:Id|Ref))$/;
const ID_VALUE = /^[A-Za-z0-9][A-Za-z0-9_.:-]{1,63}$/;
const NON_ID_WORDS = new Set([
  'true', 'false', 'null', 'undefined', 'none', 'unknown', 'na', 'n_a',
  'yes', 'no', 'on', 'off', 'ok',
  'pass', 'fail', 'passed', 'failed', 'warn', 'warning', 'error', 'success',
  'pending', 'running', 'queued', 'completed', 'cancelled', 'canceled', 'skipped',
  'active', 'inactive', 'enabled', 'disabled', 'open', 'closed',
  'ready', 'healthy', 'unhealthy', 'up', 'down', 'degraded',
  'draft', 'published', 'archived', 'deleted',
]);
const GENERIC_ID_KEYS = new Set(['id', 'uuid', 'key', 'ref']);

const DEVICE_SPEC = { keys: ['device_id', 'deviceId', 'id'], sources: ['/devices', '/api/v2/policy/devices', '/api/v2/lifecycle'] };
const DETAIL_ROUTE_SPECS = {
  '/api/v2/policy/policies': { keys: ['policy_id', 'policyId', 'id'], sources: ['/api/v2/policy/devices', '/api/v2/zone-matrix/policies'] },
  '/api/v2/policy/domains': { keys: ['domain_id', 'domainId', 'domain_ref', 'domainRef'], sources: ['/api/v2/policy/devices'] },
  '/api/v2/policy/devices': DEVICE_SPEC,
  '/api/v2/devices': DEVICE_SPEC,
  '/devices': DEVICE_SPEC,
  '/api/v2/zone-matrix/versions': { keys: ['version_id', 'versionId', 'id'], sources: ['/api/v2/zone-matrix/versions'] },
  '/api/v2/scripts/runs': { keys: ['run_id', 'last_run_id', 'runId', 'lastRunId'], sources: ['/api/v2/scripts'] },
  '/api/v2/scripts': { keys: ['script_id', 'scriptId', 'id'], sources: ['/api/v2/scripts'] },
  '/clusters': { keys: ['cluster_id', 'clusterId', 'cluster_member_ref', 'clusterMemberRef', 'cluster_ref', 'id'], sources: ['/clusters', '/api/v2/cp-failover/units'] },
  '/api/v2/compliance/controls': { keys: ['control_id', 'controlId', 'id'], sources: ['/compliance/controls', '/api/v2/compliance/controls'] },
  '/api/v2/log-findings': { keys: ['finding_id', 'findingId', 'id'], sources: ['/api/v2/log-findings'] },
};

function harvestableId(value) {
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) && value > 0 ? String(value) : null;
  }
  if (typeof value === 'string') {
    if (!ID_VALUE.test(value) || NON_ID_WORDS.has(value.toLowerCase())) return null;
    return value;
  }
  return null;
}


/** Pools of identifiers seen in responses, used to fill the "synthetic-ref" placeholders. Never printed. */
export class IdPool {
  constructor() {
    this.byPrefix = new Map();
    this.byRoute = new Map();
    this.byKey = new Map();
    this.byRouteKey = new Map();
    this.global = new Set();
  }
  addTo(map, key, id) {
    let set = map.get(key);
    if (!set) map.set(key, set = new Set());
    if (set.size < 40) set.add(id);
  }
  harvest(route, contentType, text) {
    for (const u of text.matchAll(UUID)) if (this.global.size < 40) this.global.add(u[0].toLowerCase());
    if (/html/i.test(contentType)) {
      for (const m of text.matchAll(/(?:href|action)="(\/[^"?#]*)/g)) {
        const parts = m[1].split('/');
        for (let i = 1; i < parts.length; i++) {
          if (/^[0-9a-f]{8}-[0-9a-f]{4}-/i.test(parts[i])) this.addTo(this.byPrefix, parts.slice(0, i).join('/'), parts[i]);
        }
      }
    } else if (/json/i.test(contentType)) {
      let doc;
      try { doc = JSON.parse(text); } catch { return; }
      const walk = (node, depth) => {
        if (depth > 6 || node === null) return;
        if (Array.isArray(node)) { node.slice(0, 50).forEach(n => walk(n, depth + 1)); return; }
        if (typeof node !== 'object') return;
        for (const [k, v] of Object.entries(node)) {
          const id = ID_KEY.test(k) ? harvestableId(v) : null;
          if (id !== null) {
            this.addTo(this.byRoute, route, id);
            this.addTo(this.byKey, k, id);
            this.addTo(this.byRouteKey, `${route}\0${k}`, id);
          } else {
            walk(v, depth + 1);
          }
        }
      };
      walk(doc, 0);
    }
  }
  candidates(template, placeholder) {
    const prefix = template.slice(0, template.indexOf(placeholder)).replace(/\/$/, '');
    const out = [];
    const addAll = iter => {
      if (!iter) return;
      for (const id of iter) if (!out.includes(id)) out.push(id);
    };
    const spec = DETAIL_ROUTE_SPECS[prefix];
    if (spec) {
      for (const key of spec.keys) {
        for (const source of spec.sources) addAll(this.byRouteKey.get(`${source}\0${key}`));
      }
      for (const key of spec.keys) {
        if (!GENERIC_ID_KEYS.has(key)) addAll(this.byKey.get(key));
      }
    }
    addAll(this.byPrefix.get(prefix));
    if (!spec || !out.length) addAll(this.byRoute.get(prefix));
    if (!out.length) addAll(this.global);
    return out;
  }
}

export async function run(args = process.argv.slice(2), env = process.env,
  { request = fetch, pause = sleep, print = text => process.stdout.write(text), readText = path => readFileSync(path, 'utf8') } = {}) {
  const { values } = parseArgs({ args, options: {
    ref: { type: 'string' }, salt: { type: 'string' }, routes: { type: 'string' },
    'max-requests': { type: 'string', default: '400' }, concurrency: { type: 'string', default: '2' },
    'delay-ms': { type: 'string', default: '150' }, 'details-per-route': { type: 'string', default: '3' },
    json: { type: 'boolean' },
  } });
  const salt = values.salt ?? env.LEAK_AUDIT_SALT;
  const maxRequests = Math.min(Math.max(Number(values['max-requests']) || 400, 1), 1000);
  const concurrency = Math.min(Math.max(Number(values.concurrency) || 2, 1), 4);
  const delayMs = Math.min(Math.max(Number(values['delay-ms']) || 0, 0), 5000);
  const perDetail = Math.min(Math.max(Number(values['details-per-route']) || 3, 1), 10);
  if (!values.ref || !salt) throw new Error('Use --ref <reference file> and --salt <salt> (or LEAK_AUDIT_SALT)');
  const base = new URL(env.NEXUS_AUDIT_BASE_URL || DEFAULT_BASE);
  const machine = new URL(env.NEXUS_AUDIT_MACHINE_URL || DEFAULT_MACHINE);
  const token = env.T || env.NEXUS_PROBE_MACHINE_TOKEN || env.NEXUS_E2E_MACHINE_TOKEN;
  if (!token) throw new Error('Machine token is missing (env T)');

  const parsed = parseReference(readText(values.ref));
  const total = Object.values(parsed.perCategory).reduce((a, b) => a + b, 0);
  if (total < 1 || parsed.perCategory.HOST + parsed.perCategory.ADDR + parsed.perCategory.IPV4 + parsed.perCategory.SERIAL < 1) {
    throw new Error('Reference file has no identity hashes; refusing to report an empty comparison');
  }
  const scanner = new Scanner(parsed.reference, salt);
  const routeFile = JSON.parse(readText(values.routes ?? join(HERE, 'routes.json')));
  const placeholder = routeFile.placeholder ?? 'synthetic-ref';
  const routes = routeFile.routes;

  const login = await request(machine, { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(30_000),
    headers: { 'X-Nexus-Machine-Token': token, 'X-Nexus-Machine-Purpose': 'probe' } });
  if (!login.ok) throw new Error(`Machine session refused (HTTP ${login.status})`);
  const cookie = /^ui2_session=[^;]+/.exec(login.headers.get('set-cookie') ?? '')?.[0];
  if (!cookie) throw new Error('Machine session is incomplete');
  const statusResponse = await request(new URL('/session/status', base), { headers: { Cookie: cookie }, redirect: 'error', signal: AbortSignal.timeout(30_000) });
  const status = await statusResponse.json();
  if (status.authenticated !== true || status.must_change_password === true || !status.role_tokens?.includes('role:replay_viewer')) throw new Error('A masked (replay_viewer) session is required');

  const report = new Report();
  const pool = new IdPool();
  let sent = 0;

  async function fetchOne(template, path) {
    if (sent >= maxRequests) return false;
    sent++;
    let response, text = '', bytes = 0, truncated = false, skipped = false;
    try {
      response = await request(new URL(path, base), { headers: { Cookie: cookie }, redirect: 'manual', signal: AbortSignal.timeout(60_000) });
      const declared = Number(response.headers.get('content-length') ?? 0);
      if (declared > SKIP_ABOVE_BYTES) { skipped = true; }
      else {
        const buffer = Buffer.from(await response.arrayBuffer());
        truncated = buffer.length > MAX_BODY_BYTES;
        const slice = truncated ? buffer.subarray(0, MAX_BODY_BYTES) : buffer;
        bytes = slice.length;
        text = slice.toString('utf8');
      }
    } catch {
      report.add(template, { status: 0 });   // network failure or timeout: counted, no detail kept
      return true;
    }
    const matches = skipped ? new Map() : scanner.scan(text);
    let headerText = '';
    for (const [name, value] of response.headers) if (name.toLowerCase() !== 'set-cookie') headerText += `${value}\n`;
    const headerMatches = scanner.scan(headerText);
    report.add(template, { bytes, status: response.status, matches, headerMatches, skipped, truncated });
    if (!skipped && response.ok) pool.harvest(template, response.headers.get('content-type') ?? '', text);
    return true;
  }

  // Phase 1: routes without a placeholder; phase 2: routes with one, a few sampled identifiers each.
  const plain = routes.filter(r => !r.includes(placeholder));
  const templated = routes.filter(r => r.includes(placeholder));
  const queue = plain.map(r => [r, r]);
  async function drain(items) {
    let next = 0;
    await Promise.all(Array.from({ length: concurrency }, async () => {
      while (next < items.length) {
        const [template, path] = items[next++];
        if (!(await fetchOne(template, path))) return;
        if (delayMs) await pause(delayMs);
      }
    }));
  }
  await drain(queue);
  const detail = [];
  for (let round = 0; round < perDetail; round++) {
    for (const template of templated) {
      const id = pool.candidates(template, placeholder)[round];
      if (id !== undefined) detail.push([template, template.replaceAll(placeholder, encodeURIComponent(id))]);
    }
  }
  await drain(detail);
  for (const template of templated) if (!report.routes.has(template)) report.addUnprobed(template);

  const result = report.toJSON({
    entries_by_category: parsed.perCategory,
    source_columns: parsed.sources,
    request_budget: maxRequests, requests_sent: sent,
  });
  print(values.json ? JSON.stringify(result, null, 1) + '\n' : formatTable(result));
  return result;
}

export function formatTable(result) {
  const lines = [];
  const t = result.totals;
  lines.push(`requests=${t.requests} ok=${t.ok} bytes_scanned=${t.bytes_scanned} routes_with_matches=${t.routes_with_matches}`);
  lines.push(`reference entries: ${CATEGORIES.map(c => `${c}=${result.reference.entries_by_category?.[c] ?? 0}`).join(' ')}`);
  lines.push(`TOTAL matches: ${CATEGORIES.map(c => `${c}=${t.matches[c]}`).join(' ')}`);
  lines.push('route\trequests\tok\tbytes\tmatches');
  for (const [route, r] of Object.entries(result.routes)) {
    const m = CATEGORIES.filter(c => r.matches[c]).map(c => `${c}=${r.matches[c]}`).join(' ') || '-';
    lines.push(`${route}\t${r.requests}\t${r.ok}\t${r.bytes_scanned}\t${m}`);
  }
  if (result.routes_without_ok_response.length) lines.push(`routes probed without a 2xx response: ${result.routes_without_ok_response.length}`);
  if (result.routes_not_probed.length) lines.push(`routes not probed (no identifier found or budget spent): ${result.routes_not_probed.length}`);
  return lines.join('\n') + '\n';
}

if (process.argv[1] && realpathSync(fileURLToPath(import.meta.url)) === realpathSync(process.argv[1])) {
  run().catch(error => {
    // Only the configuration message of our own checks; never HTTP client text, URLs, tokens or response content.
    process.stderr.write(`Leak audit failed: ${error?.cause === undefined && typeof error?.message === 'string' && /^(Use |Machine|Reference|A masked)/.test(error.message) ? error.message : 'unexpected error'}\n`);
    process.exitCode = 1;
  });
}
