# Separating the legacy Python from the neXus repository -- measured plan

## Status

**DRAFT -- step 1 of the separation: measured inventory and proposal only (2026-11-14).** Awaiting Product
Owner decision. Nothing is moved or deleted by this document. Backlog `legacy_python_separation` (P3).
It replaces the 2026-09-23 draft of the same name, whose premises are out of date (section 1). Not
implementation authority (`AGENTS.md`, authority hierarchy item 2).

## 1. Headline

The independent review's "about 152k lines of legacy Python" is correct as a count (152,289 lines in 517
tracked `*.py` files at `origin/main` 8142fd43) but misleading as a diagnosis:

- **99,239 lines (65 %, 328 files) already sit under `history/`**, the in-repository archive created by the
  repo restructure (`docs/REPO_RESTRUCTURE_PLAN.md`, PR1-PR3). `pytest.ini` has `testpaths = tools/tests` and
  `norecursedirs = history`, so no default test run, CI job or build touches it.
- **53,050 lines (189 files) sit outside `history/`.** Of those only about 3.5k lines are dead code; the
  rest is live ship/governance tooling, its tests, and the PacketBreaker parity tooling.
- Nothing under `ui2/` imports Python. The only `ui2/` to `tools/` links are comments and READMEs plus the
  parity fixture generators for the Java Packet Analysis and sanitizer tests (class a2 below).

Stale premises of the 2026-09-23 draft: root clutter (`patch_*.py` and similar) is gone (the root has one
`.py`, `main.py`); `utils/failover` and `utils/action_taxonomy.py` no longer exist outside `history/`
(`tests/test_action_taxonomy_java_parity.py` is an 8-line retirement note; Java `ActionRegistry` is the
authority); a separate repository was superseded by in-repository `history/`.

## 2. Measured inventory

Command: `git ls-files '*.py' | xargs wc -l`, grouped by top-level directory (blank lines and comments
counted). Total 152,289 lines, 517 files.

| Tree | Files | Lines | Class |
|---|---:|---:|---|
| `history/` (`tests` 157 files / 46,314 lines, `utils` 83, `scratch` 18, `configuration`, `application`, `checkpoint`, `console`, `panorama`, `replay`, `signal_intake`, `scripts`) | 328 | 99,239 | c (archived in place) |
| `tools/tests/` | 69 | 21,552 | b |
| `tools/delivery/` | 27 | 13,179 | a |
| `tools/packet_analysis/` (`parity` 21 files, `numeric` 3) | 24 | 7,004 | a2 |
| `scripts/` (consult one-shots 29 files; measurement 5; live 6; see 3.5) | 41 | 4,897 | a / c / c-evidence |
| `tools/llm_trial/` | 10 | 2,630 | c-evidence |
| `tools/e2e/` | 3 | 1,379 | a |
| `tools/security/` | 6 | 950 | a |
| `tests/` (repo root) | 4 | 768 | b / c |
| `tools/privacy/` | 2 | 451 | a |
| `tools/sanitizer/` | 1 | 188 | a2 |
| `main.py` | 1 | 52 | c |
| `utils/__init__.py` | 1 | 0 | c |
| **Total** | **517** | **152,289** | |

Per class (sums verified to the total):

| Class | Lines | Share | Meaning |
|---|---:|---:|---|
| a. live pipeline tooling | 16,459 | 10.8 % | ship, preview e2e, security scans, privacy gate, Argon |
| a2. port and parity reference tooling | 7,192 | 4.7 % | PacketBreaker parity and sanitizer parity generators |
| b. governance and test tooling | 22,198 | 14.6 % | `tools/tests` plus the two live root tests |
| c. legacy, no callers | 102,787 | 67.5 % | `history/` 99,239; one-shot consult scripts 3,374; dead root tests 122; `main.py` 52 |
| c-evidence. no callers, cited as evidence | 3,653 | 2.4 % | `tools/llm_trial` 2,630; log trial judge 74; `scripts/measurement` Python 949 |

## 3. Classification with evidence

### 3.1 Class a -- live pipeline tooling (stays)

Evidence (callers found by grep, `git grep -w <module>` outside `history/`, `docs/history`, `relay`):

- `tools/privacy/repository_privacy_check.py`: the push gate. `.github/workflows/validation.yml` lines 204 and
  295 run it; `scripts/repository_privacy_check.py` is a 7-line shim for it; `nexus_po_tool_gate.py` and
  `ci_regression_scope.py` name it.
- `tools/delivery/`: `.githooks/pre-push` runs `nexus_worker_prepush.py`; the workflow runs
  `nexus_po_scope_check.py`, `ci_regression_scope.py`, `build_history_index.py --check`, `project_queue.py
  check`; `orchestrator.py` and `standalone_orchestrate.py` (also shimmed from `scripts/`) are the ship
  chain; `hosta_deploy.sh` and `hosta_integration.sh` call `hosta_integration_remote`; `hosta_restamp_image.py`
  backs `ui2/Containerfile.restamp`.
  Lowest-caller files, all still used: `dispatch_ledger.py` (roles/PO.md, own test),
  `successor_index.py` (generates `docs/design/DECISION_RECORD_SUCCESSOR_INDEX.md`),
  `workbench_companion_launchagent.py` (own test only, 98 lines; the one candidate for later review).
- `tools/e2e/hosta_preview_e2e.py`, `e2e_canary_digests.py`: preview e2e of every ship; named in
  `ui2/Containerfile.restamp`; `deploy/ui2-image-build/run_build.sh` runs `tools/e2e/hosta_e2e_image.py`; six
  `tools/tests` files.
- `tools/security/`: renders the `ui2-security` ConfigMap and CronJobs (`security_manifests.py`);
  `deploy/security/README.md` documents running it and `tools/tests/test_security_scans.py` tests it.
- `scripts/`: `argon_task.py` and `consult_argon.py` (named in `AGENTS.md`, `CLAUDE.md`,
  `.claude/commands/argon.md`, `tools/tests/test_argon_task.py`); `consult_fable_design.py`
  (`ci_regression_scope.py` line 82); `ui2_extract_fixtures.py` (root test `test_ui2_b0_extraction_tooling.py`,
  UI2 B1 contracts); the two shims above. Total 500 lines.

### 3.2 Class a2 -- PacketBreaker port and sanitizer parity tooling (stays until the PO closes the port)

- `tools/packet_analysis/parity/*` and `numeric/*` generate the frozen reference vectors that Java tests
  assert against (`ui2/packet-analysis/src/test/.../PathParityTest`, `CPythonDifferentialTest`,
  `MetadataVectorsTest`, `PacketAnalysisEndToEndTest`; `ui2/packet-analysis/README.md` and `DETERMINISM.md`
  give the exact commands, run with the `~/Codo/PacketBreaker/.venv` interpreter).
- `tools/sanitizer/pcap_parity_reference.py` feeds `ui2/sanitizer/src/test/.../PcapParityTest` and its
  `pcap-parity/manifest.json`; `docs/design/DATA_SANITIZER_CONTRACT.md` names it.
- These are not part of the default Python run (they need the PacketBreaker interpreter), which is why
  they look unreferenced from CI. They are the evidence that the Java port equals the frozen Python
  reference; removing them would orphan the "parity harness gates each stage" law of
  `docs/design/PACKET_ANALYSIS_PORT_CONTRACT.md`.

### 3.3 Class b -- governance and test tooling (stays)

- `tools/tests/` (69 files, 21,552 lines) is pytest `testpaths`; CI runs it (workflow lines 117, 210, 221,
  234, 301, 312, 324). Largest: `test_orchestrator.py` 2,129; `test_preview_e2e.py` 1,513.
- `tests/test_architecture_convergence.py` (427) is run by `orchestrator_verify.py` line 119 and listed in
  `ci_regression_scope.py` TOOLING_FILES; `tests/test_ui2_b0_extraction_tooling.py` (219) tests
  `scripts/ui2_extract_fixtures.py`. Both stay.
- Note: root `tests/` is outside `testpaths`, so these run only through the orchestrator and by path.
- **Measured defect (2026-11-14, `origin/main` 8142fd43):** `tests/test_architecture_convergence.py` is
  57 tests, 47 pass and **10 fail**. Nine import `utils.action_taxonomy` and `utils.failover`, which exist only
  under `history/utils/` (`ImportError: cannot import name 'action_taxonomy' from 'utils'`); one
  (`test_every_build_history_doc_link_resolves`) trips on a stale `utils/repository_privacy.py` reference.
  `orchestrator_verify.py` line 119 runs this file as a merge-mode check, so that path would fail today.
  This is the same coupling the 2026-09-23 draft warned about, now showing up as red tests. The 47 passing
  tests are live governance (cross-authority, build history); the 9 legacy-import tests are class c.

### 3.4 Class c -- legacy product code nothing calls (archive or delete)

- **`history/` Python, 99,239 lines.** `history/README.md` states it is not part of the Java build or default
  test discovery. Evidence of no callers: `pytest.ini` excludes it; `grep -rn 'history/'` over
  `.github/workflows`, `ui2/` build files and `deploy/` finds no execution path (only
  `deploy/security/README.md` saying scanners still scan it); `tools/` mentions are the
  `docs/history/` path and a `history/` entry in `ci_regression_scope.py` MAJOR_PREFIXES (a path rule,
  not a caller). Its 156 test files (46,314 lines) need the retired product's dependencies.
- **`main.py` (52 lines).** "SecurityExpert CLI entrypoint"; imports `config` and `application.cli`,
  neither of which exists outside `history/`, so it cannot run. Only prose (`AI_START_HERE.md` lines 71-90,
  the retired CLI mode matrix) mentions it.
- **`tests/test_gov_po_2_privacy_entrypoint.py` (114)** does `import main` and
  `from application.workflows import maintenance`: it cannot import. **`tests/test_action_taxonomy_java_parity.py`
  (8)** is a retirement note with no test. **`utils/__init__.py`** is an empty package marker.
- **One-shot consultation scripts, 29 files, 3,374 lines** (`scripts/consult_{claude,codex,fable}_*.py`
  for dated reviews such as `failover_phase_c`, `ui_review_2026_11_05`, `run_stage{1,2}_claude_sonnet_high.py`).
  Each has zero references outside itself, `docs/repo_inventory.csv` and history records. Exceptions kept in
  class a: `consult_argon`, `consult_fable_design`. `AGENTS.md` line 399 names "the authorized repository
  orchestrator scripts (`scripts/consult_*.py`)" as a class, so moving them needs a wording amendment.

### 3.5 Class c-evidence -- no callers, but cited as evidence

- `tools/llm_trial/` (2,630 lines, plus a recorded `results/run-2026-11-10`): its README says "not product
  code"; the only caller is `tools/tests/test_llm_trial.py` (377) and
  `docs/design/LOG_AGENT_CLAUDE_TRIAL_2026_11_10.md`. `scripts/consult_claude_log_trial_judge.py` (74) feeds it.
- `scripts/measurement/*.py` (949): table generators for the Java measurement runners, cited by the
  `PO_DECISION_RECORD_2026_11_11_LOG_FUNNEL_NOT_EVALUABLE_RULE` and `LOG_FUNNEL_BACKTEST_2026_11_11` records
  (the "measurement record before contract" law: commands stay next to the decision they support).

## 4. Proposed separation

**Stays in place (class a, a2, b; 45,849 lines, 30 %):** `tools/delivery`, `tools/e2e`, `tools/security`,
`tools/privacy`, `tools/tests`, live `scripts/`, the two live root tests, `tools/packet_analysis`,
`tools/sanitizer`. No move: they are the working ship pipeline, and the push gate has a frozen CI baseline
(`tools/tests/test_gov_po_3_ci_privacy_gate_baseline.py`).

**Moves to the archive (class c-evidence and the one-shots; 7,027 lines):** into `history/tools/` and
`history/scripts/` (the in-repository archive already exists and already holds 3 scripts). Design records
that cite these paths are updated in the same commit.

**Deleted after PO approval (class c; 174 lines now, then `history/`):** `main.py`, `utils/__init__.py`,
the two dead root tests (the convergence test is split, not deleted). Then, as a later and separate decision, `history/` itself (99,239 lines of Python
plus its non-Python files, about 448 tracked files) either stays as the in-repo archive or leaves for a
private archive repository with full history preserved (`git filter-repo`). Recommendation: leave it in
place for now. It costs no CI time, and removing it saves lines, not risk.

## 5. Order of steps

Each step is one pull request through the normal ship path, with `tools/tests`, the root architecture test
and the docs checks run before the ship.

1. **This document** (docs only).
2. **PO decisions** (section 6).
3. **Dead-code removal and the red convergence test:** split `tests/test_architecture_convergence.py` (keep
   the 47 live governance tests, drop the 9 legacy-import ones, fix the stale path in the tenth, or point the
   taxonomy checks at `ui2/architecture-tests`); delete `main.py`, `utils/__init__.py`, `tests/test_gov_po_2_privacy_entrypoint.py`,
   `tests/test_action_taxonomy_java_parity.py`; rewrite `AI_START_HERE.md` lines 71-90 (retired CLI matrix)
   to point to `history/README.md`; update `tools/delivery/ci_regression_scope.py` (the `tests/...` entries)
   and `tools/tests/test_ci_regression_scope.py` together.
4. **Archive the one-shots** (29 consult scripts) to `history/scripts/`; amend `AGENTS.md` line 399 to name
   `scripts/consult_argon.py` and `scripts/consult_fable_design.py` instead of the `scripts/consult_*.py` class
   (`tools/README.md` already lists the rest as UNSURE and non-live); refresh
   `docs/repo_inventory.csv`.
5. **Archive `tools/llm_trial` and `scripts/measurement` Python** only after the cross-reference test
   (`tools/tests/test_design_cross_references_resolve.py`) passes with the cited paths rewritten.
6. **Review `workbench_companion_launchagent.py`** (98 lines, test-only caller) with the owner of the
   workbench companion; archive if unused.
7. **Decide the fate of `history/`** (stay, or private archive repository). Not before 3-6 are done and
   one full release cycle has passed with no reach into `history/`.
8. **Parity tooling (a2) is reviewed only when the PO closes the PacketBreaker port**; then `tools/packet_analysis`
   and `tools/sanitizer` move to `history/tools/` together with the PacketBreaker reference note.

## 6. Decisions needed from the Product Owner

| # | Question | Recommendation |
|---|---|---|
| 1 | Delete `main.py`, `utils/__init__.py` and the two uncollectable root tests (174 lines)? | Yes. |
| 2 | Move the 29 one-shot consult scripts into `history/scripts/` and amend `AGENTS.md` line 399? | Yes. |
| 3 | Move `tools/llm_trial` and `scripts/measurement` Python into `history/`? | Yes, after the cross-reference rewrite. |
| 4 | Keep `history/` in this repository or move it to a private archive repository? | Keep for now; decide after step 6. |
| 5 | Keep the PacketBreaker parity tooling (7,192 lines) until the port closes? | Yes; never move it mid-port. |

## 7. Risks

- **PacketBreaker port.** The parity generators are not run by CI and need `~/Codo/PacketBreaker/.venv`;
  they look dead to a grep for callers. Moving them breaks the documented regeneration commands in
  `ui2/packet-analysis/README.md` and the "parity gates each stage" law. Mitigation: class a2 is out of
  scope for steps 3-6.
- **Privacy gate.** `tools/privacy` and `scripts/repository_privacy_check.py` are the push gate and the
  CI baseline comparison. They must not move, and a change to any path rule (`ci_regression_scope.py`
  MAJOR_PREFIXES, TOOLING_FILES) needs its test updated in the same commit, or CI picks a wrong regression
  scope silently.
- **Path-keyed governance.** `docs/repo_inventory.csv`, `project/build_history.json`, `relay/*` and
  `docs/design/*` cite paths; moves must keep `test_design_cross_references_resolve` and
  `test_contract_authority_status` green. History records stay valid as history and are not rewritten.
- **Scanners.** `history/` is deliberately still scanned (`deploy/security/README.md`); moving more code
  there adds scan findings, not new runtime risk.
- **Constitution text.** `AGENTS.md` line 399 and `AI_START_HERE.md` name moved paths; amendments need a
  recorded PO decision. Sections of this plan never authorize a change by themselves.
- **Counting.** Lines include blanks and comments; the 152k figure and the 53k outside `history/` both
  repeat if the review counts again with `cloc`, but class sizes shift by a few percent.
