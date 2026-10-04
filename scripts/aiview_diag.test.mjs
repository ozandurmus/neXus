import { test } from 'node:test';
import assert from 'node:assert/strict';
import { run } from './aiview_diag.mjs';

const env = { NEXUS_E2E_BASE_URL: 'https://example.invalid',
  NEXUS_E2E_MACHINE_URL: 'https://example.invalid:8086/internal/machine-session',
  NEXUS_E2E_MACHINE_TOKEN: 'synthetic-token' };
const args = ['--target', 'FW-TANGO-04', '--gate', 'synthetic_read', '--param', '001'];
function fixture({ runnable = true, masked = true, terminal = 'COMPLETED', status = 200, duplicates = false } = {}) {
  const calls = [], output = [];
  let polls = 0;
  const target = { target: 'FW-TANGO-04', deviceId: 'opaque-device', commands: [{ gate_id: 'synthetic_read', runnable }] };
  return { calls, output, io: { print: text => output.push(text), pause: async () => {},
    request: async (url, init) => {
      calls.push({ url: String(url), init });
      const path = new URL(url).pathname;
      let body = {};
      if (path === '/internal/machine-session') body = { csrf_token: 'synthetic-csrf' };
      if (path === '/session/status') body = { authenticated: true, role_tokens: ['role:replay_viewer'] };
      if (path.endsWith('/targets')) body = { canExecute: true, targets: duplicates ? [target, target] : [target] };
      if (path === '/api/v2/diagnostics') body = { job_id: 'opaque-job' };
      if (path.endsWith('/opaque-job')) body = { state: ++polls === 1 ? 'REQUESTED' : terminal, masked, output: 'Status: UP' };
      return new Response(JSON.stringify(body), { status, headers: { 'set-cookie': 'ui2_session=synthetic-cookie; HttpOnly' } });
    } } };
}
test('machine login resolves a pseudonym, submits once with CSRF, polls and prints only masked output', async () => {
  const f = fixture();
  await run(args, env, f.io);
  assert.deepEqual(f.output, ['Status: UP\n']);
  const submitted = f.calls.filter(call => new URL(call.url).pathname === '/api/v2/diagnostics');
  assert.equal(submitted.length, 1);
  assert.equal(submitted[0].init.headers.Origin, 'https://example.invalid');
  assert.equal(submitted[0].init.headers['X-CSRF-Token'], 'synthetic-csrf');
  assert.equal(submitted[0].init.headers.Cookie, 'ui2_session=synthetic-cookie');
  assert.equal(JSON.parse(submitted[0].init.body).parameter, '001');
  assert.equal(JSON.parse(submitted[0].init.body).device_id, 'opaque-device');
  assert.ok(f.calls.every(call => call.init.redirect === 'error'));
});
test('non-runnable or ambiguous targets are refused before submission', async () => {
  for (const options of [{ runnable: false }, { duplicates: true }]) {
    const f = fixture(options);
    await assert.rejects(run(args, env, f.io));
    assert.ok(!f.calls.some(call => new URL(call.url).pathname === '/api/v2/diagnostics'));
    assert.deepEqual(f.output, []);
  }
});
test('unmasked output and HTTP errors are never printed or retried', async () => {
  for (const options of [{ masked: false }, { status: 403 }]) {
    const f = fixture(options);
    await assert.rejects(run(args, env, f.io));
    assert.deepEqual(f.output, []);
    if (options.status) assert.equal(f.calls.length, 1);
  }
});
test('failed jobs report masked output then fail; timeout never resubmits', async () => {
  const failed = fixture({ terminal: 'FAILED' });
  await assert.rejects(run(args, env, failed.io));
  assert.deepEqual(failed.output, ['Status: UP\n']);
  const waiting = fixture({ terminal: 'EXECUTING' });
  await assert.rejects(run(args, env, waiting.io), /Timed out/);
  assert.equal(waiting.calls.filter(call => new URL(call.url).pathname === '/api/v2/diagnostics').length, 1);
});
