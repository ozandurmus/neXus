#!/usr/bin/env node
// Uses NEXUS_E2E_BASE_URL, NEXUS_E2E_MACHINE_URL and NEXUS_E2E_MACHINE_TOKEN.
// node tools/e2e/aiview_readiness.mjs --vendor cp --target CLS-ROMEO-01 [--wait]
// node tools/e2e/aiview_readiness.mjs --vendor pan --all [--wait]
import { parseArgs } from 'node:util';
import { setTimeout as sleep } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const PSEUDONYM = /^[A-Z]{2,4}(-[A-Z0-9]+){1,4}$/;
const TERMINAL = new Set(['DONE', 'STOPPED']);

export async function run(args = process.argv.slice(2), env = process.env,
  { request = fetch, pause = sleep, print = text => process.stdout.write(text) } = {}) {
  const { values } = parseArgs({ args, options: {
    vendor: { type: 'string' }, target: { type: 'string' }, all: { type: 'boolean' }, wait: { type: 'boolean' },
  } });
  if (!['cp', 'pan'].includes(values.vendor) || Boolean(values.all) === (values.target !== undefined)
      || (values.target !== undefined && !PSEUDONYM.test(values.target))) {
    throw new Error('Use --vendor cp|pan and either --target <pseudonym> or --all, optionally --wait');
  }
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
    if (!response.ok) throw new Error(`Readiness API refused the request (HTTP ${response.status})`);
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
  const prefix = `/api/v2/${values.vendor}-failover`;
  const listing = await requireOk(await api(`${prefix}/summary`)).json();
  if (!Array.isArray(listing)) throw new Error('Invalid summary');
  const vendor = values.vendor === 'cp' ? 'check_point' : 'palo_alto';
  const units = listing.filter(unit => unit.vendor === vendor && (values.all
    || unit.cluster_member_ref === values.target || unit.members?.some(member => member.hostname === values.target)));
  if (!values.all && (!units.length || new Set(units.map(unit => unit.clusterId)).size !== 1)) {
    throw new Error('Target is unavailable or ambiguous');
  }
  const ids = new Set();
  for (const unit of units) {
    if (unit.masked !== true || !PSEUDONYM.test(unit.cluster_member_ref ?? '')
        || typeof unit.clusterId !== 'string' || !unit.clusterId || typeof unit.unitId !== 'string' || !unit.unitId
        || ids.has(unit.unitId)) throw new Error('Unmasked or ambiguous summary refused');
    ids.add(unit.unitId);
  }
  const submitted = [];
  const emit = (unit, status, failingChecks = []) => print(JSON.stringify({
    pseudonym: unit.cluster_member_ref, status, failingChecks,
  }) + '\n');
  for (const unit of units) {
    const response = await api(`${prefix}/units/${encodeURIComponent(unit.unitId)}/readiness`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf, Origin: base.origin },
      body: JSON.stringify({ clusterId: unit.clusterId, unitId: unit.unitId }),
    });
    if (response.status === 409) {
      const refused = await response.json();
      if (refused.code !== 'RUN_ALREADY_ACTIVE') throw new Error('Readiness admission refused');
      emit(unit, 'SKIPPED_ALREADY_RUNNING');
      continue;
    }
    const admitted = await requireOk(response).json();
    if (typeof admitted.runId !== 'string' || !admitted.runId) throw new Error('Readiness admission is incomplete');
    submitted.push({ unit, runId: admitted.runId });
    if (!values.wait) emit(unit, 'SUBMITTED');
  }
  if (!values.wait) return;
  for (let poll = 0; poll < 300 && submitted.length; poll++) {
    for (let i = 0; i < submitted.length;) {
      const { unit, runId } = submitted[i];
      const result = await requireOk(await api(`${prefix}/runs/${encodeURIComponent(runId)}`)).json();
      if (result.kind !== 'READINESS') throw new Error('Non-readiness run refused');
      if (!TERMINAL.has(result.state)) { i++; continue; }
      // Project numeric check IDs only; never print free-form messages or derived device evidence.
      const failingChecks = [...new Set((result.checks ?? []).filter(check => check.status !== 'PASS'
        && check.status !== 'OK').map(check => check.checkNo).filter(Number.isInteger))];
      emit(unit, result.state, failingChecks);
      submitted.splice(i, 1);
    }
    if (submitted.length) await pause(2000);
  }
  if (submitted.length) throw new Error('Timed out waiting for readiness; runs were not resubmitted');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  run().catch(() => {
    // Never print response bodies, URLs, tokens or HTTP-client exception text.
    process.stderr.write('AIView readiness failed. Check configuration, permissions and masked run history.\n');
    process.exitCode = 1;
  });
}
