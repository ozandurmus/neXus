# Python tooling validation (PR0)

The maintained product is `ui2/`. Python default discovery is the explicit
`testpaths` file list in root `pytest.ini`, not all of `tests/`. PR2 moved the
retired product tests to `history/tests/`; live paths stay here until PR3.
No historical device command is authorized by retaining a test or an old
implementation.

Install only `requirements-dev.txt` for live tooling. It contains the existing
pytest, xdist, PyYAML and Playwright dependencies; no Python product requirements
are needed. The synthetic Workbench browser tests require installed Chromium.
CI provisions it with `python3 -m playwright install --with-deps chromium`.

From the repository root:

```sh
python3 -m pytest --collect-only -q
python3 -m pytest -q -n auto --dist worksteal
python3 scripts/repository_privacy_check.py
# CI comparison: existing merge-base findings remain debt; new findings fail.
python3 scripts/repository_privacy_check.py --privacy-baseline-ref origin/main
git diff --check
```

No baseline, an unavailable baseline or an unresolved merge base cannot accept
a finding. As before, a clean scan does not need a baseline lookup. The scanner
still covers the repository; `history/` is not exempted by test exclusion.

## Discovery accounting

Baseline: `8f534a5d`; inventory: `docs/repo_inventory.csv` from `origin/main`.
Default discovery retains 43 of the inventory's 44 LIVE_TOOLING test files,
adds the standalone baseline suite extracted from the mixed privacy bridge,
and adds two PR0 tests: live project governance and discovery/import isolation.
That is **46 default test files**.

**160 existing test files leave default discovery**, without file moves:

- 156 files classified TEST_OF_LEGACY in the inventory, covering the retired
  Python product, collectors, console and renderer.
- Three UNSURE files: `test_architecture_convergence.py`,
  `test_gov_po_2_privacy_entrypoint.py`, and `test_ui2_b0_extraction_tooling.py`.
  The first's 14 independent project-state/governance checks are copied into
  `test_live_project_governance.py`; its Python taxonomy/console/failover checks
  stay historical. The second compares against the legacy CLI; equivalent
  live baseline behavior is covered by `test_gov_po_3_ci_privacy_gate_baseline.py`.
  The extraction helper still depends on the Python product.
- `test_action_taxonomy_java_parity.py`: Java became authoritative by PO
  decision 2026-10-04; no live tool imports `utils/action_taxonomy.py`.
  Its two comparison tests are replaced in place by a Java coverage note.

These are test-file counts, not parameter-expanded collected-case counts.
The legacy CLI argument guard is retained in the opt-in privacy bridge.
The CSV classification plus the explicit default manifest determines the exact
file set; list it without importing the legacy product with:

```sh
python3 - <<'PY'
import configparser
import subprocess
from pathlib import Path
config = configparser.ConfigParser()
config.read('pytest.ini')
live = set(config['pytest']['testpaths'].split())
paths = subprocess.check_output(['git', 'ls-files', 'tests/*.py', 'history/tests/*.py'], text=True).splitlines()
excluded = [p for p in paths if Path(p).name.startswith('test_') and p not in live]
print('\n'.join(excluded))
print(f'{len(excluded)} excluded files')
PY
```

## Historical tests: explicit opt-in

Only in a separately approved offline environment with synthetic fixtures and
legacy dependencies already available:

```sh
cd history
python3 -m pytest --collect-only
# Or select one historical file after reviewing its offline requirements:
python3 -m pytest -q tests/test_known_safety_gaps.py
```

Historical reproduction needs `history/requirements.txt`,
`history/requirements-console.txt` and root `requirements-dev.txt`.
See `history/README.md` for the retained UNSURE entry points and collection
limitations; the archive is not a standalone supported runtime.
Default tooling conftest retains only path setup,
relay-environment cleanup and the repository privacy lock; it does not import
or stub a legacy SSH client. Opt-in historical tests are not a live release gate.

## Java authority and retained coverage

`ActionClass` and service `ActionRegistry`, together with the capability command
gate registry, now define live action vocabulary and admission. Python parity
is historical, not a release constraint. Java coverage remains in
`GateChainInterceptorRouteResolutionTest`, `Ui2ArchitectureTest`, worker/service
failover tests and job-engine failover tests. The CI full route runs Java
`unitTest architectureTest` and frontend TypeScript/Vitest/build. PostgreSQL
integration and security source/history/image gates still require their
approved environment; a local tooling pass does not certify those gates.

`history/` and `tools/` changes, including Markdown, route to full live
regression. An unmapped path mixed with either root still blocks. Required
workflow job IDs are unchanged. The discovery route uses actual Gradle modules,
not the nonexistent `:discovery` module.

## PR0 local validation — 2026-10-04

Implementation is committed for review; acceptance is **BLOCKED**, not green.
No state, contract status, deployment or remote Git operation is changed.

- `python3 -m pytest --collect-only -q`: 1,272 cases from the 46-file manifest.
- `python3 -m pytest -q -n 2 --dist worksteal`: 1,248 passed, 14 failed,
  10 browser setup errors (151.85 seconds on the final full run).
- Focused PR0 suite: 55 passed, including baseline behavior, discovery/import
  isolation, routing/workflow, governance extraction and isolated ship state.
  After final workflow/governance edits, the affected subset passed 43 tests.
- `python3 scripts/repository_privacy_check.py`: PASS, zero findings.
- `git diff --check`, `python3 scripts/build_history_index.py --check`,
  `python3 scripts/project_queue.py check`: PASS. Changed tracked paths route
  to `full`.
- Frontend `npx tsc --noEmit -p .`, `npx vitest run` (51 files / 490 tests),
  `npm run build`: PASS. The attempted Vitest `--cacheDir` CLI option was
  unsupported; the successful plain invocation uses the existing Vite config's
  worktree-local cache path.
- Java Gradle, PostgreSQL integration and security source/history/image gates:
  UNVERIFIED; the approved environment is unavailable to this lane.

Remaining full-suite failures are not excluded or converted to skips:

| Surface | Failure / required follow-up |
| --- | --- |
| Cold-start budgets | Existing AGENTS/handover ceilings are exceeded; PR0 reduces AGENTS from 4,306 to 4,294 words, but the historical limit remains 3,528. Handover is outside lane scope. |
| Contract authority (2 cases) | Existing CP failover contract cites the draft readiness contract; requires owner resolution, not an agent freeze or allowlist expansion. |
| Deployment tooling (2 cases) | Existing integration manifest uses persistent storage where its test expects ephemeral storage; the JUnit summary includes synthetic failure detail where its test requires omission. |
| Successor index | Committed index differs from its generated projection. |
| Gitleaks scope | Existing configuration has more allowlist entries than its exact-scope test accepts. No acceptance was expanded. |
| Session heading uniqueness | Existing design review duplicates a session heading. |
| Project budgets (5 cases) | Backlog, terminal archive, feature registry and queue exceed their existing ceilings. Project state is outside lane scope. |
| Dashboard HTTP and browser (1 failure / 10 errors) | Sandbox denies local listener/browser startup; rerun in the authorized test environment. |

The referenced project, contract, manifest, summary, index and scanner-allowlist
inputs are unchanged from the baseline. Resolve these gates in the owning lane
and rerun before advancing to PR1; this checkpoint is not merge authorization.
