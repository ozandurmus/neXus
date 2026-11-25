import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { CATEGORIES, digest, normalize, tokenVariants, Scanner, parseReference } from '../security/aiview_leak_audit/core.mjs';
import { run } from '../security/aiview_leak_audit/probe.mjs';

const SALT = 'synthetic-salt';
// Synthetic identities (reserved documentation names and addresses only).
const HOST_FQDN = 'Fw-Zulu-Synth.corp.example.invalid';
const ADDR = '192.0.2.77';
const SERIAL = 'SYNTH0123456789';
const CLUSTER = 'Clus-Quebec-Synth';
const USER = 'Jane Quartz-Synth';
const SECRETS = [HOST_FQDN, HOST_FQDN.toLowerCase(), 'fw-zulu-synth', ADDR, SERIAL, CLUSTER, 'quartz-synth', USER];

function referenceText() {
  const lines = ['#src\tHOST\tdevices.observed_hostname\t1'];
  const add = (cat, value) => lines.push(`${cat}\t${digest(SALT, normalize(value))}`);
  add('HOST', HOST_FQDN); add('HOST', 'fw-zulu-synth');
  add('ADDR', ADDR); add('IPV4', ADDR);
  add('SERIAL', SERIAL); add('CLUSTER', CLUSTER); add('USER', USER); add('NAME_WORD', 'quartz-synth');
  return lines.join('\n') + '\n';
}

test('normalization and salted digest match the SQL export (lower, trim, sha256 of salt LF value)', () => {
  // Value produced by export_reference.sql on a synthetic row (salt S, value 192.0.2.17) in PostgreSQL 16.
  assert.equal(digest('S', normalize('  192.0.2.17 ')),
    '081fef3a776c75e5d54b9231589f6e58d0638a914f42fbfc09d2151b8f9ef16f');
});

test('token variants cover dotted names, host:port, words and skip placeholders and bare addresses', () => {
  const v = tokenVariants('FW-Zulu-Synth.corp.example.invalid:443');
  assert.ok(v.has('fw-zulu-synth.corp.example.invalid'));
  assert.ok(v.has('fw-zulu-synth'));
  assert.ok(v.has('fw-zulu-synth.corp'));
  assert.ok(v.has('zulu'));
  assert.equal(tokenVariants('192.0.2.77').size, 0);
  assert.equal(tokenVariants('localhost').size, 0);
  assert.equal(tokenVariants('abc').size, 0);
});

test('scanner counts raw values by category and ignores masked text', () => {
  const { reference } = parseReference(referenceText());
  const scanner = new Scanner(reference, SALT);
  const leaked = scanner.scan(`{"name":"${HOST_FQDN}","ip":"${ADDR}:443","serial":"${SERIAL.toLowerCase()}","by":"${USER}"}`);
  assert.ok(leaked.get('HOST') >= 1);
  assert.equal(leaked.get('IPV4'), 1);
  assert.equal(leaked.get('SERIAL'), 1);
  assert.ok(leaked.get('NAME_WORD') >= 1);  // multi-word names are matched word by word
  const masked = scanner.scan('{"name":"FW-TANGO-04","ip":"192.0.2.9","cluster":"CLS-ROMEO-01"}');
  assert.equal(masked.size, 0);
});

test('reference parsing keeps counts by category and ignores malformed lines', () => {
  const parsed = parseReference(referenceText() + 'garbage\nHOST\tnothex\n');
  assert.equal(parsed.perCategory.HOST, 2);
  assert.equal(parsed.perCategory.IPV4, 1);
  assert.equal(parsed.sources.length, 1);
});

function harness({ body, headers = {}, failWith } = {}) {
  const calls = [];
  const output = [];
  const io = {
    print: t => output.push(t), pause: async () => {},
    readText: path => (path.endsWith('routes.json') ? JSON.stringify({ placeholder: 'synthetic-ref',
      routes: ['/api/v2/things', '/api/v2/things/synthetic-ref', '/api/v2/other'] }) : referenceText()),
    request: async (url, init) => {
      const u = new URL(url);
      calls.push(u.pathname);
      if (failWith) throw new Error(failWith);
      if (u.pathname === '/internal/machine-session') {
        return new Response('{}', { status: 200, headers: { 'set-cookie': 'ui2_session=synthetic; HttpOnly' } });
      }
      if (u.pathname === '/session/status') return Response.json({ authenticated: true, role_tokens: ['role:replay_viewer'] });
      if (u.pathname === '/api/v2/things') return Response.json({ items: [{ id: 'opaque-id-0001', note: body }] }, { headers });
      if (u.pathname === '/api/v2/things/opaque-id-0001') return Response.json({ note: body }, { headers });
      return Response.json({ ok: true }, { headers });
    },
  };
  return { calls, output, io };
}
const args = ['--ref', 'ref.tsv', '--salt', SALT];
const env = { T: 'synthetic-token' };

test('probe output has counts only: no value, hash, identifier or header text', async () => {
  const body = `${HOST_FQDN} ${ADDR} ${SERIAL} ${CLUSTER} ${USER}`;
  const h = harness({ body, headers: { 'x-trace': HOST_FQDN, location: `/x/${SERIAL}` } });
  const result = await run(args, env, h.io);
  const text = h.output.join('');
  for (const secret of SECRETS) assert.ok(!text.toLowerCase().includes(secret.toLowerCase()), 'secret printed');
  assert.ok(!text.includes('opaque-id-0001'), 'identifier printed');
  assert.ok(!/[0-9a-f]{64}/.test(text), 'hash printed');
  assert.ok(result.totals.matches.HOST >= 2 && result.totals.matches.IPV4 >= 2 && result.totals.matches.SERIAL >= 2);
  assert.ok(result.routes['/api/v2/things'].header_matches >= 2);
  assert.match(text, /\/api\/v2\/things\/synthetic-ref\t/);
  const json = [];
  await run([...args, '--json'], env, { ...h.io, print: t => json.push(t) });
  const parsed = JSON.parse(json.join(''));
  for (const key of Object.keys(parsed.routes)) assert.ok(key.startsWith('/'));
  assert.ok(!json.join('').includes('opaque-id-0001'));
});

test('detail routes are filled from identifiers seen in list responses; the request cap holds', async () => {
  const h = harness({ body: 'clean' });
  await run(args, env, h.io);
  assert.ok(h.calls.includes('/api/v2/things/opaque-id-0001'));
  const capped = harness({ body: 'clean' });
  const result = await run([...args, '--max-requests', '2'], env, capped.io);
  assert.equal(result.totals.requests, 2);
});

test('a clean estate reports zero matches', async () => {
  const h = harness({ body: 'FW-TANGO-04 CLS-ROMEO-01 192.0.2.9' });
  const result = await run(args, env, h.io);
  assert.equal(Object.values(result.totals.matches).reduce((a, b) => a + b, 0), 0);
});

test('failures never expose client error text and an empty reference is refused', async () => {
  const h = harness({ failWith: `boom ${SERIAL} ${HOST_FQDN}` });
  await assert.rejects(run(args, env, h.io));
  const bad = harness();
  bad.io.readText = path => (path.endsWith('routes.json') ? '{"routes":[]}' : '#src\tHOST\tx.y\t0\n');
  await assert.rejects(run(args, env, bad.io), /no identity hashes/);
  await assert.rejects(run(args, {}, bad.io), /token is missing/);
});

// ---- P1 planes: export_reference.sql sources and CIDR / segment-style tokens ----

const SQL = readFileSync(fileURLToPath(new URL('../security/aiview_leak_audit/export_reference.sql', import.meta.url)), 'utf8');
// Strip "--" comments (the file has no "--" inside a string literal) so assertions read the statements only.
const SQL_CODE = SQL.split('\n').map(line => line.replace(/--.*$/, '')).join('\n');

test('export_reference.sql stays read-only, guarded and hash-only', () => {
  assert.match(SQL_CODE, /^\s*BEGIN READ ONLY;/m);
  assert.match(SQL_CODE, /^\s*ROLLBACK;\s*$/m);
  assert.ok(!/\bCOMMIT\b/i.test(SQL_CODE), 'never commits');
  assert.ok(!/\b(INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|GRANT|COPY)\b/i.test(SQL_CODE.replace(/'[^']*'/g, "''")),
    'no write, DDL or COPY statement');
  // Every source is joined to information_schema.columns before dynamic SQL is built.
  assert.match(SQL_CODE, /JOIN information_schema\.columns c\s+ON c\.table_schema = 'public' AND c\.table_name = s\.tbl AND c\.column_name = s\.col/);
  // Output: "#src" meta lines (counts) and category + salted sha256 lines; nothing else is selected for output.
  const outputs = [...SQL_CODE.matchAll(/^SELECT (?:DISTINCT )?(.+?)$/gm)].map(m => m[1]).filter(s => /E'\\t'/.test(s));
  assert.equal(outputs.length, 2);
  assert.ok(outputs.some(s => s.startsWith("'#src'")));
  assert.ok(outputs.some(s => /encode\(sha256\(convert_to\(:'salt' \|\| E'\\n' \|\| n, 'UTF8'\)\), 'hex'\)/.test(s)));
});

test('export_reference.sql covers the P1 tables with the right categories', () => {
  const plain = new Set([...SQL_CODE.matchAll(/\('([A-Z_]+)',\s*'([a-z_0-9]+)',\s*'([a-z_0-9]+)'\)/g)].map(m => `${m[1]} ${m[2]}.${m[3]}`));
  const json = new Set([...SQL_CODE.matchAll(/\('([A-Z_]+)',\s*'([a-z_0-9]+)',\s*'([a-z_0-9]+)',\s*'([a-z_]+)'\)/g)]
    .map(m => `${m[1]} ${m[2]}.${m[3]} ${m[4]}`));
  for (const expected of [
    'HOST cutover_segment.name', 'USER cutover_segment.created_by', 'USER cutover_segment.updated_by',
    'USER cutover_assessment.actor', 'USER zone_matrix_version.created_by', 'USER zone_matrix_version.published_by',
    'USER zone_matrix_revision.actor', 'ADDR static_route_preview.destination_cidr', 'ADDR static_route_preview.nexthop_ip',
    'ADDR static_route_preview.representative_host', 'USER static_route_preview.requested_by',
    'CLUSTER static_route_preview.unit_id',
    // pre-P1 sources stay
    'HOST devices.observed_hostname', 'ADDR endpoints.address_ref', 'SERIAL ha_pair_identity.unit_serial_low',
  ]) assert.ok(plain.has(expected), `plain source ${expected}`);
  for (const expected of [
    'ADDR cutover_segment.blocks elements', 'ADDR cutover_segment.peer_blocks elements',
    'ADDR cutover_assessment_flow.flow flow_addresses', 'HOST zone_matrix_version.content zone_names',
    'ADDR zone_matrix_version.content zone_blocks',
  ]) assert.ok(json.has(expected), `JSON source ${expected}`);
  for (const source of [...plain, ...json]) assert.ok(CATEGORIES.includes(source.split(' ')[0]), `category of ${source}`);
  // JSON shapes read only named keys, and invalid JSON is skipped rather than raising.
  assert.match(SQL_CODE, /pg_input_is_valid\(t\.%I::text, ''jsonb''\)/);
  for (const key of ["''zones''", "''blocks''", "''name''", "''flowSource''", "''flowDestination''", "''flowSources''", "''sourceRange''"]) {
    assert.ok(SQL_CODE.includes(key), `reads ${key}`);
  }
  // A block also yields its address part, trimmed the way the probe tokenizes.
  assert.match(SQL_CODE, /regexp_replace\(split_part\(n, '\/', 1\), '\[\._:@-\]\+\$', ''\)/);
});

/** What export_reference.sql emits for one value, mirrored in JS (normalize, block address part, each IPv4). */
function exported(cat, value) {
  const n = normalize(value);
  const out = [[cat, n]];
  if (cat === 'ADDR' && /^[0-9a-f.:]+\/[0-9]{1,3}$/.test(n)) out.push(['ADDR', n.split('/')[0].replace(/[._:@-]+$/, '')]);
  for (const m of n.matchAll(/((?:[0-9]{1,3}\.){3}[0-9]{1,3})/g)) out.push(['IPV4', m[1]]);
  return out;
}

test('scanner finds zone / segment names and CIDR blocks as the SQL exports them, and ignores masked forms', () => {
  const SEGMENT = 'Seg-Xa1b2c-Swift';
  const ZONE = 'zn-x0d0e0f-cardholder';
  const V4_BLOCK = '203.0.113.128/29';
  const V6_BLOCK = '2001:db8:4c1d::/48';
  const lines = ['#src\tHOST\tcutover_segment.name\t1'];
  for (const [cat, value] of [['HOST', SEGMENT], ['HOST', ZONE], ['ADDR', V4_BLOCK], ['ADDR', V6_BLOCK]]) {
    for (const [c, n] of exported(cat, value)) lines.push(`${c}\t${digest(SALT, n)}`);
  }
  const { reference } = parseReference(lines.join('\n') + '\n');
  const scanner = new Scanner(reference, SALT);
  const leaked = scanner.scan(JSON.stringify({ name: SEGMENT.toLowerCase(), zones: [{ name: ZONE }],
    blocks: [V4_BLOCK, V6_BLOCK.toUpperCase()] }));
  assert.equal(leaked.get('HOST'), 2);
  assert.equal(leaked.get('IPV4'), 1);   // 203.0.113.128 inside the IPv4 block
  assert.equal(leaked.get('ADDR'), 2);   // the address part of both blocks (IPv4 by the address scan, IPv6 by its token)
  const masked = scanner.scan(JSON.stringify({ name: 'SEGMENT-1', zones: [{ name: 'ZONE-1' }],
    blocks: ['10.77.4.128/29', 'Withheld in AIView'], actor: 'USER-1' }));
  assert.equal(masked.size, 0);
});
