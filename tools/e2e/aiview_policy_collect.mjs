#!/usr/bin/env node
// Uses NEXUS_E2E_BASE_URL, NEXUS_E2E_MACHINE_URL and NEXUS_E2E_MACHINE_TOKEN.
// node tools/e2e/aiview_policy_collect.mjs --vendor cp|pan|all [--wait]
import { parseArgs } from 'node:util';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { realpathSync } from 'node:fs';

const PSEUDONYM = /^[A-Z]{2,4}(-[A-Z0-9]+){1,4}$/;
const STATES = new Set(['REQUESTED', 'CLAIMED', 'EXECUTING', 'RECONCILING', 'COMPLETED',
  'FAILED', 'REJECTED', 'CANCELLED', 'OUTCOME_UNKNOWN', 'RECONCILED']);
const TERMINAL = new Set(['COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', 'OUTCOME_UNKNOWN', 'RECONCILED']);
// Match the policy projection's codes; discard step labels, opaque targets and free-form text.
const REASON = /^(?:COLLECTION_FAILED|COLLECTION_PENDING|INLINE_LAYER_NAME_MISSING|HTTP_[0-9]{3}|API_ERROR_[0-9]{1,6}|EXIT_[0-9]+|TIMEOUT|SIZE_LIMIT|JOB_DEADLINE|LEASE_LOST|POLICY_GATE_UNAVAILABLE|PANORAMA_TARGET_NOT_FOUND|PANORAMA_COLLECTOR_NOT_WIRED|POLICY_REQUEST_NOT_FOUND|DISCOVERY_RUN_NOT_FOUND|POLICY_SOURCE_NOT_ELIGIBLE|CREDENTIAL_UNRESOLVABLE|CREDENTIAL_UNUSABLE|TRANSPORT_NOT_REGISTERED|TLS_TARGET_UNRESOLVABLE|TRANSPORT_FAILED|INTERRUPTED|XML_PARSE_OR_SIZE_FAILED|API_RESPONSE_ERROR|INVALID_OR_INCOMPLETE_RESPONSE|ChannelFailed|TimedOut|HostKeyRejected|AuthenticationFailed)$/;

export async function run(args = process.argv.slice(2), env = process.env,
  { request = fetch, pause = sleep, print = text => process.stdout.write(text) } = {}) {
  const { values } = parseArgs({ args, options: { vendor: { type: 'string' }, wait: { type: 'boolean' } } });
  if (!['cp', 'pan', 'all'].includes(values.vendor)) throw new Error('Use --vendor cp|pan|all, optionally --wait');
  let base, machine;
  try {
    base = new URL(env.NEXUS_E2E_BASE_URL);
    machine = new URL(env.NEXUS_E2E_MACHINE_URL);
  } catch { throw new Error('Set deployment origin and machine-session URL'); }
  if (![base, machine].every(url => /^https?:$/.test(url.protocol) && !url.username && !url.password
      && !url.search && !url.hash) || base.pathname !== '/' || machine.pathname !== '/internal/machine-session') {
    throw new Error('Invalid deployment origin or machine-session URL');
  }
  if (!env.NEXUS_E2E_MACHINE_TOKEN) throw new Error('Machine token is missing');
  async function send(url, init = {}) {
    return request(url, { ...init, redirect: 'error', signal: AbortSignal.timeout(30_000) });
  }
  function requireOk(response) {
    if (!response.ok) throw new Error(`Policy API refused the request (HTTP ${response.status})`);
    return response;
  }
  const login = requireOk(await send(machine, { method: 'POST', headers: {
    'X-Nexus-Machine-Token': env.NEXUS_E2E_MACHINE_TOKEN,
  } }));
  const cookie = /^ui2_session=[^;]+/.exec(login.headers.get('set-cookie') ?? '')?.[0];
  const { csrf_token: csrf } = await login.json();
  if (!cookie || typeof csrf !== 'string' || !csrf) throw new Error('Machine session is incomplete');
  async function api(path, init) {
    return send(new URL(path, base), { ...init, headers: { Cookie: cookie, ...init?.headers } });
  }
  const session = await requireOk(await api('/session/status')).json();
  if (session.authenticated !== true || session.must_change_password === true
      || !session.role_tokens?.includes('role:replay_viewer')) throw new Error('A masked session is required');
  async function masked(path) {
    const response = requireOk(await api(path));
    if (response.headers.get('X-Nexus-Masked') !== 'true') throw new Error('Unmasked policy response refused');
    return response.json();
  }
  const listing = await masked('/api/v2/policy/sources');
  if (!Array.isArray(listing.sources) || listing.canCollect !== true) throw new Error('Collection is unavailable');
  const sources = listing.sources.filter(source => values.vendor === 'all'
    || source.vendor === (values.vendor === 'cp' ? 'CP' : 'PAN'));
  const ids = new Set();
  for (const source of sources) {
    if (!['CP', 'PAN'].includes(source.vendor) || !PSEUDONYM.test(source.sourceName ?? '')
        || typeof source.sourceId !== 'string' || !source.sourceId || ids.has(source.sourceId)) {
      throw new Error('Unmasked or ambiguous source refused');
    }
    ids.add(source.sourceId);
  }
  async function emit(source, state, reason = '') {
    // Count current stored snapshots for this source, not historical revisions or this run's writes.
    const catalog = await masked('/api/v2/policy/devices');
    if (!Array.isArray(catalog.policies)) throw new Error('Invalid policy catalog');
    const code = reason.includes(': ') ? reason.slice(reason.lastIndexOf(': ') + 2) : reason;
    print(JSON.stringify({ pseudonym: source.sourceName, state,
      terminalReasonCode: reason ? (REASON.test(code) ? code : 'COLLECTION_FAILED') : '',
      snapshotCount: catalog.policies.filter(policy => policy.sourceId === source.sourceId).length,
    }) + '\n');
  }
  const submitted = [];
  for (const source of sources) {
    const response = await api(`/api/v2/policy/sources/${encodeURIComponent(source.sourceId)}/collect`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf, Origin: base.origin },
      body: JSON.stringify({ domainRef: '' }),
    });
    if (response.status === 409) {
      const refused = await response.json();
      if (refused.error !== 'POLICY_SOURCE_BUSY_OR_INELIGIBLE') throw new Error('Policy admission refused');
      await emit(source, 'SKIPPED_BUSY_OR_INELIGIBLE');
      continue;
    }
    const admitted = await requireOk(response).json();
    if (response.status !== 202 || typeof admitted.jobId !== 'string' || !admitted.jobId) {
      throw new Error('Policy admission is incomplete');
    }
    submitted.push({ source, jobId: admitted.jobId });
    if (!values.wait) await emit(source, 'SUBMITTED');
  }
  if (!values.wait) return;
  for (let poll = 0; poll < 300 && submitted.length; poll++) {
    for (let i = 0; i < submitted.length;) {
      const { source, jobId } = submitted[i];
      const result = await masked(`/api/v2/policy/collections/${encodeURIComponent(jobId)}`);
      if (!STATES.has(result.state) || typeof result.reason !== 'string') throw new Error('Invalid collection status');
      if (!TERMINAL.has(result.state)) { i++; continue; }
      await emit(source, result.state, result.reason);
      submitted.splice(i, 1);
    }
    if (submitted.length) await pause(2000);
  }
  if (submitted.length) throw new Error('Timed out waiting for policy collection; jobs were not resubmitted');
}

if (process.argv[1] && realpathSync(fileURLToPath(import.meta.url)) === realpathSync(process.argv[1])) {
  run().catch(() => {
    // Never print response bodies, URLs, tokens or HTTP-client exception text.
    process.stderr.write('AIView policy collection failed. Check configuration, permissions and masked collection history.\n');
    process.exitCode = 1;
  });
}
