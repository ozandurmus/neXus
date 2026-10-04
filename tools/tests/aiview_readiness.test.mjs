import { test } from 'node:test';
import assert from 'node:assert/strict';
import { run } from '../e2e/aiview_readiness.mjs';

const env = { NEXUS_E2E_BASE_URL: 'https://example.invalid',
  NEXUS_E2E_MACHINE_URL: 'https://example.invalid:8086/internal/machine-session',
  NEXUS_E2E_MACHINE_TOKEN: ['synthetic', 'token'].join('-') };
function fixture({ vendor = 'cp', masked = true, conflict, state = 'DONE', kind = 'READINESS',
  role = 'role:replay_viewer', duplicate = false, status = 200 } = {}) {
  const calls = [], output = [];
  const units = [1, 2].map(n => ({ vendor: vendor === 'cp' ? 'check_point' : 'palo_alto',
    masked, unitId: `unit-${n}`, clusterId: 'opaque-cluster', cluster_member_ref: 'CLS-ROMEO-01',
    members: [{ hostname: 'FW-TANGO-04' }, { hostname: 'FW-TANGO-05' }] }));
  if (duplicate) units.push(units[0]);
  return { calls, output, io: { print: text => output.push(JSON.parse(text)), pause: async () => {},
    request: async (url, init) => {
      const path = new URL(url).pathname;
      calls.push({ path, init });
      let body = {}, code = status;
      if (path === '/internal/machine-session') body = { csrf_token: 'synthetic-csrf' };
      if (path === '/session/status') body = { authenticated: true, role_tokens: [role] };
      if (path.endsWith('/summary')) body = units;
      if (path.endsWith('/readiness')) {
        if (conflict && path.includes('unit-1')) { code = 409; body = { code: conflict }; }
        else body = { runId: path.includes('unit-1') ? 'run-1' : 'run-2' };
      }
      if (path.includes('/runs/')) body = { state, kind, message: 'DO NOT PRINT',
        checks: [{ checkNo: 3, status: 'FAIL', derived: 'DO NOT PRINT' }, { checkNo: 4, status: 'PASS' }] };
      return new Response(JSON.stringify(body), { status: code,
        headers: { 'set-cookie': 'ui2_session=synthetic-cookie; HttpOnly' } });
    } } };
}
for (const vendor of ['cp', 'pan']) {
  test(`${vendor}: cluster and member targets submit each unit sequentially with CSRF then poll`, async () => {
    for (const target of ['CLS-ROMEO-01', 'FW-TANGO-04']) {
      const f = fixture({ vendor });
      await run(['--vendor', vendor, '--target', target, '--wait'], env, f.io);
      assert.deepEqual(f.output, [1, 2].map(() => ({ pseudonym: 'CLS-ROMEO-01', status: 'DONE', failingChecks: [3] })));
      const posts = f.calls.filter(call => call.path.endsWith('/readiness'));
      assert.equal(posts.length, 2);
      for (const [i, call] of posts.entries()) {
        assert.equal(call.path, `/api/v2/${vendor}-failover/units/unit-${i + 1}/readiness`);
        assert.equal(call.init.headers['X-CSRF-Token'], 'synthetic-csrf');
        assert.equal(call.init.headers.Origin, 'https://example.invalid');
        assert.equal(call.init.headers.Cookie, 'ui2_session=synthetic-cookie');
        assert.deepEqual(JSON.parse(call.init.body), { clusterId: 'opaque-cluster', unitId: `unit-${i + 1}` });
      }
      assert.ok(f.calls.every(call => call.init.redirect === 'error'));
      assert.ok(f.calls.findIndex(call => call.path.includes('/runs/')) > f.calls.findLastIndex(call => call.path.endsWith('/readiness')));
    }
  });
}
test('--all without --wait submits only, and active-unit 409 skips without retry', async () => {
  const f = fixture({ conflict: 'RUN_ALREADY_ACTIVE' });
  await run(['--vendor', 'cp', '--all'], env, f.io);
  assert.deepEqual(f.output, [
    { pseudonym: 'CLS-ROMEO-01', status: 'SKIPPED_ALREADY_RUNNING', failingChecks: [] },
    { pseudonym: 'CLS-ROMEO-01', status: 'SUBMITTED', failingChecks: [] },
  ]);
  assert.equal(f.calls.filter(call => call.path.endsWith('/readiness')).length, 2);
  assert.ok(!f.calls.some(call => call.path.includes('/runs/')));
});
test('wait skips active units and reports STOPPED with safe check numbers', async () => {
  const f = fixture({ conflict: 'RUN_ALREADY_ACTIVE', state: 'STOPPED' });
  await run(['--vendor', 'cp', '--all', '--wait'], env, f.io);
  assert.equal(f.output[1].status, 'STOPPED');
  assert.deepEqual(f.calls.filter(call => call.path.includes('/runs/')).map(call => call.path), ['/api/v2/cp-failover/runs/run-2']);
});
test('invalid arguments never log in', async () => {
  for (const args of [[], ['--vendor', 'other', '--all'], ['--vendor', 'cp'],
    ['--vendor', 'cp', '--target', 'synthetic.example.invalid'],
    ['--vendor', 'cp', '--all', '--target', 'CLS-ROMEO-01']]) {
    const f = fixture();
    await assert.rejects(run(args, env, f.io));
    assert.deepEqual(f.calls, []);
  }
});
test('unmasked summaries, duplicates, missing targets and wrong roles refuse before POST', async () => {
  for (const options of [{ masked: false }, { duplicate: true }, { role: 'role:operator' }]) {
    const f = fixture(options);
    await assert.rejects(run(['--vendor', 'cp', '--all'], env, f.io));
    assert.ok(!f.calls.some(call => call.path.endsWith('/readiness')));
    assert.deepEqual(f.output, []);
  }
  const f = fixture();
  await assert.rejects(run(['--vendor', 'cp', '--target', 'FW-BRAVO-02'], env, f.io));
  assert.deepEqual(f.output, []);
});
test('other conflicts, HTTP errors and non-readiness runs never print response data', async () => {
  for (const options of [{ conflict: 'WRONG_ROLE' }, { status: 403 }, { kind: 'FAILOVER' }]) {
    const f = fixture(options);
    await assert.rejects(run(['--vendor', 'cp', '--all', '--wait'], env, f.io));
    assert.deepEqual(f.output, []);
  }
});
test('polling timeout never resubmits', async () => {
  const f = fixture({ state: 'PRECHECK' });
  await assert.rejects(run(['--vendor', 'cp', '--all', '--wait'], env, f.io), /Timed out/);
  assert.equal(f.calls.filter(call => call.path.endsWith('/readiness')).length, 2);
  assert.deepEqual(f.output, []);
});
