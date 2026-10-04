#!/usr/bin/env node
// Usage: NEXUS_E2E_BASE_URL, NEXUS_E2E_MACHINE_URL and NEXUS_E2E_MACHINE_TOKEN
// node scripts/aiview_diag.mjs --target FW-TANGO-04 --gate <gate_id> [--param <VS id>]
import { parseArgs } from 'node:util';
import { randomUUID } from 'node:crypto';
import { setTimeout as sleep } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const TERMINAL = new Set(['COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', 'OUTCOME_UNKNOWN', 'RECONCILED']);

export async function run(args = process.argv.slice(2), env = process.env,
  { request = fetch, pause = sleep, print = text => process.stdout.write(text) } = {}) {
  const { values } = parseArgs({ args, options: {
    target: { type: 'string' }, gate: { type: 'string' }, param: { type: 'string' },
  } });
  if (!/^FW-[A-Z]+-[0-9]+$/.test(values.target ?? '') || !/^[a-z0-9_]+$/.test(values.gate ?? '')
      || (values.param !== undefined && !/^[A-Za-z0-9_.-]{1,31}$/.test(values.param))) {
    throw new Error('Use --target <pseudonym> --gate <gate_id> [--param <VS id>]');
  }
  let base, machine;
  try {
    base = new URL(env.NEXUS_E2E_BASE_URL);
    machine = new URL(env.NEXUS_E2E_MACHINE_URL);
  } catch { throw new Error('Set NEXUS_E2E_BASE_URL and NEXUS_E2E_MACHINE_URL'); }
  if (![base, machine].every(url => /^https?:$/.test(url.protocol) && !url.username && !url.password
      && !url.search && !url.hash) || base.pathname !== '/' || machine.pathname !== '/internal/machine-session') {
    throw new Error('Invalid deployment origin or machine-session URL');
  }
  if (!env.NEXUS_E2E_MACHINE_TOKEN) throw new Error('Machine token is missing');
  async function send(url, init = {}) {
    const response = await request(url, { ...init, redirect: 'error', signal: AbortSignal.timeout(30_000) });
    if (!response.ok) throw new Error(`Diagnostic API refused the request (HTTP ${response.status})`);
    return response;
  }
  const login = await send(machine, { method: 'POST', headers: { 'X-Nexus-Machine-Token': env.NEXUS_E2E_MACHINE_TOKEN } });
  const cookie = /^ui2_session=[^;]+/.exec(login.headers.get('set-cookie') ?? '')?.[0];
  const { csrf_token: csrf } = await login.json();
  if (!cookie || typeof csrf !== 'string') throw new Error('Machine session is incomplete');
  const headers = { Cookie: cookie };
  async function api(path, init) {
    return (await send(new URL(path, base), { ...init, headers: { ...headers, ...init?.headers } })).json();
  }
  const status = await api('/session/status');
  if (status.authenticated === false || status.must_change_password === true
      || !status.role_tokens?.includes('role:replay_viewer')) throw new Error('A masked session is required');
  const listing = await api('/api/v2/diagnostics/targets');
  const targets = listing.targets?.filter(target => target.target === values.target) ?? [];
  if (targets.length !== 1 || listing.canExecute !== true) throw new Error('Target is unavailable or ambiguous');
  const target = targets[0];
  if (!target.commands?.some(command => command.gate_id === values.gate && command.runnable === true)) {
    throw new Error('Gate is not runnable for this target');
  }
  const admitted = await api('/api/v2/diagnostics', { method: 'POST', headers: {
    'Content-Type': 'application/json', 'X-CSRF-Token': csrf, Origin: base.origin,
  }, body: JSON.stringify({ device_id: target.deviceId, gate_id: values.gate,
    ...(values.param === undefined ? {} : { parameter: values.param }), request_id: randomUUID() }) });
  if (typeof admitted.job_id !== 'string') throw new Error('Diagnostic admission is incomplete');
  for (let poll = 0; poll < 300; poll++) {
    const result = await api(`/api/v2/diagnostics/${encodeURIComponent(admitted.job_id)}`);
    if (TERMINAL.has(result.state)) {
      if (result.masked !== true) throw new Error('Unmasked output was refused');
      if (typeof result.output === 'string') print(result.output + (result.output.endsWith('\n') ? '' : '\n'));
      if (result.state !== 'COMPLETED') throw new Error('Diagnostic did not complete successfully');
      return;
    }
    await pause(2000);
  }
  throw new Error('Timed out waiting for diagnostic; the job was not resubmitted');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  run().catch(() => {
    // Never print response bodies, URLs, tokens or exception text from the HTTP client.
    process.stderr.write('AIView diagnostic failed. Check configuration, permissions and masked job history.\n');
    process.exitCode = 1;
  });
}
