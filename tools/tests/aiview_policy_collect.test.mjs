import { test } from 'node:test';
import assert from 'node:assert/strict';
import { run } from '../e2e/aiview_policy_collect.mjs';

const env = { NEXUS_E2E_BASE_URL: 'https://example.invalid',
  NEXUS_E2E_MACHINE_URL: 'https://example.invalid:8086/internal/machine-session',
  NEXUS_E2E_MACHINE_TOKEN: ['synthetic', 'token'].join('-') };
function fixture({ masked = true, unmaskedPath, duplicate = false, sourceName = 'FW-TANGO-04',
  role = 'role:replay_viewer', authenticated = true, canCollect = true, conflict,
  state = 'COMPLETED', reason = '', status = 200, pollOnce = false } = {}) {
  const calls = [], output = [], polls = new Map();
  const sources = [{ sourceId: 'source-1', sourceName, vendor: 'CP' },
    { sourceId: 'source-2', sourceName: 'FW-BRAVO-02', vendor: 'PAN' }];
  if (duplicate) sources.push(sources[0]);
  return { calls, output, io: { print: text => output.push(JSON.parse(text)), pause: async () => {},
    request: async (url, init) => {
      const path = new URL(url).pathname;
      calls.push({ path, init });
      let body = {}, code = status;
      if (path === '/internal/machine-session') body = { csrf_token: 'synthetic-csrf' };
      if (path === '/session/status') body = { authenticated, role_tokens: [role] };
      if (path === '/api/v2/policy/sources') body = { sources, canCollect };
      if (path.endsWith('/collect')) {
        if (conflict) { code = 409; body = { error: conflict }; }
        else { code = 202; body = { jobId: path.includes('source-1') ? 'job-1' : 'job-2' }; }
      }
      if (path.includes('/collections/')) {
        const count = (polls.get(path) ?? 0) + 1;
        polls.set(path, count);
        body = { state: pollOnce && count === 1 ? 'EXECUTING' : state, reason,
          transcript: 'Synthetic private transcript', sourceName: 'Synthetic raw name' };
      }
      if (path === '/api/v2/policy/devices') body = { policies: [
        { sourceId: 'source-1', name: 'Synthetic policy' }, { sourceId: 'source-1' }, { sourceId: 'source-2' },
      ] };
      return new Response(JSON.stringify(body), { status: code,
        headers: { 'set-cookie': 'ui2_session=synthetic-cookie; HttpOnly', 'X-Nexus-Masked': String(masked && path !== unmaskedPath) } });
    } } };
}
for (const vendor of ['cp', 'pan', 'all']) {
  test(`${vendor}: selects configured sources, submits once with CSRF and Origin, then polls`, async () => {
    const f = fixture({ pollOnce: true, reason: 'preflight target=source-1: HTTP_403' });
    await run(['--vendor', vendor, '--wait'], env, f.io);
    const ids = vendor === 'all' ? [1, 2] : [vendor === 'cp' ? 1 : 2];
    assert.deepEqual(f.output, ids.map(id => ({ pseudonym: id === 1 ? 'FW-TANGO-04' : 'FW-BRAVO-02',
      state: 'COMPLETED', terminalReasonCode: 'HTTP_403', snapshotCount: id === 1 ? 2 : 1 })));
    const posts = f.calls.filter(call => call.path.endsWith('/collect'));
    assert.deepEqual(posts.map(call => call.path), ids.map(id => `/api/v2/policy/sources/source-${id}/collect`));
    for (const { init } of posts) {
      assert.equal(init.headers.Origin, 'https://example.invalid');
      assert.equal(init.headers['X-CSRF-Token'], 'synthetic-csrf');
      assert.equal(init.headers.Cookie, 'ui2_session=synthetic-cookie');
      assert.deepEqual(JSON.parse(init.body), { domainRef: '' });
    }
    assert.ok(f.calls.every(call => call.init.redirect === 'error'));
    assert.ok(f.calls.findIndex(call => call.path.includes('/collections/')) > f.calls.findLastIndex(call => call.path.endsWith('/collect')));
  });
}
test('without --wait prints submission and current snapshot count without polling', async () => {
  const f = fixture();
  await run(['--vendor', 'cp'], env, f.io);
  assert.deepEqual(f.output, [{ pseudonym: 'FW-TANGO-04', state: 'SUBMITTED', terminalReasonCode: '', snapshotCount: 2 }]);
  assert.ok(!f.calls.some(call => call.path.includes('/collections/')));
});
test('busy or ineligible admission skips without retry; other conflicts fail closed', async () => {
  const f = fixture({ conflict: 'POLICY_SOURCE_BUSY_OR_INELIGIBLE' });
  await run(['--vendor', 'cp', '--wait'], env, f.io);
  assert.equal(f.output[0].state, 'SKIPPED_BUSY_OR_INELIGIBLE');
  assert.equal(f.calls.filter(call => call.path.endsWith('/collect')).length, 1);
  assert.ok(!f.calls.some(call => call.path.includes('/collections/')));
  const refused = fixture({ conflict: 'POLICY_GATE_UNAVAILABLE' });
  await assert.rejects(run(['--vendor', 'cp'], env, refused.io));
  assert.deepEqual(refused.output, []);
});
test('all terminal states report only allowed reason codes and counts', async () => {
  for (const state of ['COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', 'OUTCOME_UNKNOWN', 'RECONCILED']) {
    for (const reason of ['', 'TIMEOUT', 'PARTIAL_SNAPSHOT shared target=source-1: SIZE_LIMIT', 'Synthetic private text', 'SYNTHETIC_PRIVATE_NAME']) {
      const f = fixture({ state, reason });
      await run(['--vendor', 'cp', '--wait'], env, f.io);
      assert.deepEqual(f.output, [{ pseudonym: 'FW-TANGO-04', state,
        terminalReasonCode: reason === '' ? '' : reason === 'TIMEOUT' ? 'TIMEOUT' : reason.includes('SIZE_LIMIT') ? 'SIZE_LIMIT' : 'COLLECTION_FAILED',
        snapshotCount: 2 }]);
    }
  }
});
test('invalid arguments never log in', async () => {
  for (const args of [[], ['--vendor', 'other'], ['--vendor', 'cp', '--command', 'synthetic_read']]) {
    const f = fixture();
    await assert.rejects(run(args, env, f.io));
    assert.deepEqual(f.calls, []);
  }
});
test('unmasked, duplicate, raw-looking and unauthorized sources fail before submission', async () => {
  for (const options of [{ masked: false }, { duplicate: true }, { sourceName: 'synthetic.example.invalid' },
    { role: 'role:operator' }, { authenticated: false }, { canCollect: false }, { status: 403 }]) {
    const f = fixture(options);
    await assert.rejects(run(['--vendor', 'all'], env, f.io));
    assert.ok(!f.calls.some(call => call.path.endsWith('/collect')));
    assert.deepEqual(f.output, []);
  }
});
test('unknown state and timeout never print private status or resubmit', async () => {
  for (const state of ['Synthetic private state', 'EXECUTING']) {
    const f = fixture({ state });
    await assert.rejects(run(['--vendor', 'cp', '--wait'], env, f.io));
    assert.equal(f.calls.filter(call => call.path.endsWith('/collect')).length, 1);
    assert.deepEqual(f.output, []);
  }
});

test('unmasked collection status or catalog is refused without output or resubmission', async () => {
  for (const unmaskedPath of ['/api/v2/policy/collections/job-1', '/api/v2/policy/devices']) {
    const f = fixture({ unmaskedPath });
    await assert.rejects(run(['--vendor', 'cp', '--wait'], env, f.io), /Unmasked/);
    assert.deepEqual(f.output, []);
    assert.equal(f.calls.filter(call => call.path.endsWith('/collect')).length, 1);
  }
});
