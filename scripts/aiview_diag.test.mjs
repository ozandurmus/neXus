import { test } from 'node:test';
import assert from 'node:assert/strict';
import { run } from './aiview_diag.mjs';

const env = { NEXUS_E2E_BASE_URL: 'https://example.invalid',
  NEXUS_E2E_MACHINE_URL: 'https://example.invalid:8086/internal/machine-session',
  NEXUS_E2E_MACHINE_TOKEN: 'synthetic-token' };
const args = ['--target', 'FW-TANGO-04', '--gate', 'synthetic_read', '--param', '001'];
function fixture({ runnable = true, masked = true, terminal = 'COMPLETED', status = 200, duplicates = false,
  pseudonym = 'FW-TANGO-04', canExecute = true } = {}) {
  const calls = [], output = [];
  let polls = 0;
  const target = { target: pseudonym, deviceId: 'opaque-device', virtualSystems: ['001', '13'],
    commands: [{ gate_id: 'synthetic_read', runnable }, { gate_id: 'synthetic_disabled', runnable: false }] };
  return { calls, output, io: { print: text => output.push(text), pause: async () => {},
    request: async (url, init) => {
      calls.push({ url: String(url), init });
      const path = new URL(url).pathname;
      let body = {};
      if (path === '/internal/machine-session') body = { csrf_token: 'synthetic-csrf' };
      if (path === '/session/status') body = { authenticated: true, role_tokens: ['role:replay_viewer'] };
      if (path.endsWith('/targets')) body = { canExecute, targets: duplicates ? [target, target] : [target] };
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
test('member and other supported pseudonym shapes resolve exactly before submission', async () => {
  for (const pseudonym of ['FW-MIKE-07-M1', 'CLS-ROMEO-01', 'SMC-ALPHA-01', 'MDS-BRAVO-02']) {
    const f = fixture({ pseudonym });
    await run(['--target', pseudonym, '--gate', 'synthetic_read'], env, f.io);
    assert.deepEqual(f.output, ['Status: UP\n']);
    assert.equal(f.calls.filter(call => new URL(call.url).pathname === '/api/v2/diagnostics').length, 1);
  }
});
test('raw-looking hostnames and out-of-pattern targets are refused before any request', async () => {
  for (const pseudonym of ['synthetic-fw.example.invalid', 'fw-MIKE-07-M1', 'FW-MIKE-07-M1-EXTRA-SEGMENT', 'F-MIKE']) {
    const f = fixture();
    for (const mode of [['--gate', 'synthetic_read'], ['--list']]) {
      await assert.rejects(run(['--target', pseudonym, ...mode], env, f.io), /Use --target/);
    }
    assert.deepEqual(f.calls, []);
    assert.deepEqual(f.output, []);
  }
});
test('list prints only runnable gate ids and virtual systems for the exact member, without submitting', async () => {
  const f = fixture({ pseudonym: 'FW-MIKE-07-M1' });
  await run(['--list', '--target', 'FW-MIKE-07-M1'], env, f.io);
  assert.equal(f.output.length, 1);
  assert.deepEqual(JSON.parse(f.output[0]), { target: 'FW-MIKE-07-M1',
    runnableGateIds: ['synthetic_read'], virtualSystems: ['001', '13'] });
  assert.deepEqual(f.calls.map(call => new URL(call.url).pathname),
    ['/internal/machine-session', '/session/status', '/api/v2/diagnostics/targets']);
});
test('list refuses missing, ambiguous or unavailable targets without output or submission', async () => {
  for (const options of [{ pseudonym: 'FW-MIKE-07-M1' }, { duplicates: true }, { canExecute: false }]) {
    const f = fixture(options);
    await assert.rejects(run(['--list', '--target', 'FW-TANGO-04'], env, f.io), /unavailable or ambiguous/);
    assert.deepEqual(f.output, []);
    assert.ok(!f.calls.some(call => new URL(call.url).pathname === '/api/v2/diagnostics'));
  }
});
test('list returns an empty gate list when no command is runnable', async () => {
  const f = fixture({ runnable: false });
  await run(['--list', '--target', 'FW-TANGO-04'], env, f.io);
  assert.deepEqual(JSON.parse(f.output[0]).runnableGateIds, []);
  assert.equal(f.calls.length, 3);
});
test('list rejects execution arguments and a missing target before any request', async () => {
  for (const listArgs of [['--list'], ['--list', '--target', 'FW-TANGO-04', '--gate', 'synthetic_read'],
    ['--list', '--target', 'FW-TANGO-04', '--param', '001']]) {
    const f = fixture();
    await assert.rejects(run(listArgs, env, f.io), /Use --target/);
    assert.deepEqual(f.calls, []);
  }
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
