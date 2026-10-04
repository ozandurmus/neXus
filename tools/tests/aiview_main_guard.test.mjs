import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, symlinkSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

for (const script of ['aiview_diag', 'aiview_readiness', 'aiview_policy_collect']) {
  test(`${script}: main runs through a ConfigMap-style symlink and import stays inert`, () => {
    const dir = mkdtempSync(join(tmpdir(), 'aiview-main-'));
    try {
      const target = fileURLToPath(new URL(`../e2e/${script}.mjs`, import.meta.url));
      // ConfigMap files resolve through a hidden data directory.
      mkdirSync(join(dir, 'revision'));
      symlinkSync(target, join(dir, 'revision', `${script}.mjs`));
      symlinkSync('revision', join(dir, '..data'));
      const mounted = join(dir, `${script}.mjs`);
      symlinkSync(`..data/${script}.mjs`, mounted);
      for (const file of [target, mounted]) {
        // No arguments: validation fails before any login/network request.
        const result = spawnSync(process.execPath, [file], { encoding: 'utf8', env: {} });
        assert.equal(result.status, 1);
        assert.match(result.stderr, /^AIView .* failed\. Check configuration/);
        assert.equal(result.stdout, '');
      }
      const imported = spawnSync(process.execPath,
        ['--input-type=module', '-e', 'await import(process.argv[2])', fileURLToPath(import.meta.url), mounted], { encoding: 'utf8', env: {} });
      assert.equal(imported.status, 0, imported.stderr);
      assert.equal(imported.stdout, '');
      assert.equal(imported.stderr, '');
    } finally { rmSync(dir, { recursive: true, force: true }); }
  });
}
