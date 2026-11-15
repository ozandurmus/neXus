# Separating the legacy Python from the neXus repository -- measured plan

## Status

**Steps 1-4 APPROVED by the Product Owner on 2026-11-15 (about 12:00); steps 5-8 are not yet approved.** Step 2
(the section 6 decisions) is recorded in `PO_DECISION_RECORD_2026_11_15_LEGACY_PYTHON_SEPARATION.md`; the
per-step status is in section 9. Backlog `legacy_python_separation` (P3). It replaces the 2026-09-23 draft of the
same name, whose premises are out of date (section 1). Not implementation authority beyond what the decision
record approves (`AGENTS.md`, authority hierarchy item 2).

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

## 8. Step 1 execution record (2026-11-15, lane legacypy-1115)

Step 1 as written in section 5 is "this document (docs only)": it is complete. Steps 2 (PO decisions,
section 6) and 3 (deletions) are gated on the Product Owner, and the only pure move step (4) needs an
`AGENTS.md` line 399 amendment with a recorded PO decision. So this lane **moved and deleted nothing**; it
re-verified the references that would block the first move/delete steps, at `batch/b9-1115` cf8669be:

- **29 one-shot consult scripts (step 4).** `git grep -w <name>` over `ui2/`, `tools/`, `.github/`, `deploy/`
  (excluding `history/`, `docs/history`) finds no caller for 27 of 28. Exception:
  `consult_claude_log_trial_judge` is called by `tools/llm_trial/judge.py` (step 5, class c-evidence), so it must
  move with `tools/llm_trial`, not in step 4. Other path mentions that a move must rewrite: `consult_fable_resume_on_astra_review`
  (named in `consult_codex_ui_visual_review.py`, itself a one-shot), `consult_fable_ui_review_2026_10_17`
  (`docs/design/UI_REVIEW_2026_10_18_FABLE_PART1.md`), `consult_fable_ui_visual_review`
  (`docs/design/OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md`), `consult_ui_effectiveness_council`
  (`docs/design/FAILOVER_PILOT_COUNCIL_2026_10_06.md`). No k8s manifest or workflow references any of them.
  Recount: `scripts/consult_*.py` is 30 files; minus the two kept (`consult_argon`, `consult_fable_design`)
  leaves 28, of which 27 are step 4 and the judge belongs to step 5 (the plan's "29" was off by one or two).
- **Step 3 touches ship tooling, so it is not a pure delete.** `tools/delivery/orchestrator_verify.py` line 119
  runs `tests/test_architecture_convergence.py` as a merge-mode check (red today, 10 failures);
  `tools/delivery/ci_regression_scope.py` lines 85-91 list `tests/test_action_taxonomy_java_parity.py` and
  `tests/test_gov_po_2_privacy_entrypoint.py`; `tools/tests/test_live_tooling_discovery.py` lines 13, 31-32 and
  `tools/README.md` line 47 name `utils/__init__.py` and the parity test. Deleting any of these requires the
  matching edits and tests in the same PR, as section 5 step 3 already says.
- **Nothing under `ui2/`, `.github/` or `deploy/` references `main.py`, `utils/__init__.py` or the dead root tests.**

Left for later steps: all of steps 2-8.

## 9. Step status and PO approval (2026-11-15)

The Product Owner approved steps 2-4 on 2026-11-15 and took the section 6 recommendations as the step-2
decisions (decisions 1, 2, 3, 4, 5 as recommended; decision 3 only prepares step 5, which is not yet approved).
Record: `PO_DECISION_RECORD_2026_11_15_LEGACY_PYTHON_SEPARATION.md`.

| Step | Status |
|---|---|
| 1. This document | Done (2026-11-14). |
| 2. PO decisions | Done (2026-11-15): decisions 1, 2, 4, 5 as recommended; 3 recorded, executes with step 5. |
| 3. Dead-code removal | Approved 2026-11-15; executed in lane legacypy2-1115 (see 9.2). |
| 4. Archive the one-shot consult scripts | Approved 2026-11-15; executed in lane legacypy2-1115 (see 9.3). |
| 5-8 | Not approved yet; unchanged. |

### 9.1 Reference greps re-run on this exact tree (lane legacypy2-1115, base `origin/main` 31babb26)

Command shape: `git grep -n <pattern> -- ui2 tools .github deploy scripts project` (plus `.githooks`, `.claude`,
`config`, `bin`, `AGENTS.md`, `CLAUDE.md`, `AI_START_HERE.md`), excluding `history/`.

- **`main.py`, `utils/__init__.py`, the two dead root tests: nothing under `ui2/`, `.github/workflows`, `deploy/`
  manifests or `scripts/` executes or imports them.** `deploy/secrets/README.md` line 47 shows a stale
  `docker compose exec worker python main.py ...` command (documentation of the retired product, not run by
  any manifest). All other `main.py` hits are string tokens, not file uses: the PO tool gate denies the token
  `main.py` (`nexus_po_tool_gate.py`, `.claude/nexus-po.settings.json`; denial stays correct after deletion),
  test fixtures that write a temporary `main.py`, `ci_regression_scope`/`test_ship_speed` file lists, prose in
  `scripts/ui2_extract_fixtures.py`, and `project/*.json` history records.
- **Ship-tooling references that the removal must fix:** `tools/delivery/ci_regression_scope.py` lines 90-91 (the
  two dead tests in `TOOLING_FILES`); `tools/tests/test_live_tooling_discovery.py` lines 13 and 31-32
  (`tests/test_action_taxonomy_java_parity.py`, `utils/__init__.py`); `tools/README.md` line 47
  (`utils/__init__.py remains in place`); `tools/tests/test_ship_speed.py` line 54 (a changed-file fixture
  naming `main.py`); `docs/repo_inventory.csv` rows 102, 217, 260, 450; `AI_START_HERE.md` lines 71-90.
- **Correction to section 3.3 and 8.** `tests/test_architecture_convergence.py` on this tree has 14 tests and all
  14 pass (`pytest tests/test_architecture_convergence.py`); the legacy-import tests were already moved to
  `history/tests/test_architecture_convergence_legacy.py`, so the file needs no split and
  `orchestrator_verify.py` line 119 needs no change. Only its docstring names the retired parity test and is
  updated.
- **One-shot consult scripts.** `scripts/consult_*.py` is 30 files; minus `consult_argon` and
  `consult_fable_design` (kept) and `consult_claude_log_trial_judge` (called by `tools/llm_trial/judge.py` and
  its README; stays until step 5) leaves **27**. The grep over `ui2 tools .github deploy scripts project` finds
  no caller for any of the 27 except `consult_fable_resume_on_astra_review`, named only inside
  `consult_codex_ui_visual_review.py`, itself one of the 27 (both move together). Whole-repository path mentions
  outside `history/` and `docs/history` are limited to `docs/repo_inventory.csv`, this plan,
  `docs/design/UI_REVIEW_2026_10_18_FABLE_PART1.md` (`consult_fable_ui_review_2026_10_17`),
  `docs/design/OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md` (`consult_fable_ui_visual_review`) and
  `docs/design/FAILOVER_PILOT_COUNCIL_2026_10_06.md` (`consult_ui_effectiveness_council`). No workflow,
  manifest, ship tool or `ui2/` file names any of the 27. `AGENTS.md` line 399 names the class
  `scripts/consult_*.py`.

### 9.2 Step 3 result (lane legacypy2-1115)

Deleted (4 files, 174 lines): `main.py` (52), `utils/__init__.py` (0), `tests/test_gov_po_2_privacy_entrypoint.py`
(114), `tests/test_action_taxonomy_java_parity.py` (8). The `utils/` directory is gone. References fixed in the
same change: `tools/delivery/ci_regression_scope.py` (two `TOOLING_FILES` entries),
`tools/tests/test_live_tooling_discovery.py` (the parity-test `remove` and the two exclusions),
`tools/README.md`, `tests/README.md`, `tests/test_architecture_convergence.py` (docstring),
`AI_START_HERE.md` (retired CLI matrix replaced by a pointer to `history/README.md`; the privacy-gate command
row is kept), `deploy/secrets/README.md` (retired `main.py` command), `docs/repo_inventory.csv` (4 rows).
No split of the convergence test was needed (section 9.1).

### 9.3 Step 4 result (lane legacypy2-1115)

Moved with `git mv` (history kept) to `history/scripts/`: **27** `scripts/consult_*.py` files (the plan's "29" was
a miscount, section 9.1). Kept in `scripts/`: `consult_argon.py`, `consult_fable_design.py` and, until step 5,
`consult_claude_log_trial_judge.py`. Path mentions rewritten to `history/scripts/...`:
`docs/design/FAILOVER_PILOT_COUNCIL_2026_10_06.md`, `OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md`,
`UI_REVIEW_2026_10_18_FABLE_PART1.md`, the cross-reference inside the moved
`consult_codex_ui_visual_review.py`, and 23 `docs/repo_inventory.csv` rows (4 moved scripts post-date the
inventory and have no row). `AGENTS.md` (External model and second-opinion consultation law) now names
`consult_argon.py` and `consult_fable_design.py` and cites PO 2026-11-15; `CLAUDE.md` and
`.github/copilot-instructions.md` example lists no longer point at moved or retired scripts. Dated records that
cite the old paths as history (`history/PR2_NOTES.md`, handovers, other PO decision records) are not rewritten.
The moved scripts compute paths from `scripts/` and are archived, not runnable from `history/`
(`history/README.md`).

Verification (2026-11-15): full `tools/tests` run, 5 failed, 2047 passed, 17 skipped, 10 errors. The 10 errors are
`test_orchestrator_dashboard_browser.py` (Playwright Chromium is not installed on this Mac, same as before the
change). Of the 5 failures, `test_cold_start_word_count_ceilings` was real (the amendment pushed `AGENTS.md` 3
words over its 4,362 ceiling; the amendment was shortened to fit, no ceiling was raised) and the other four
(`test_integration_cleanup_and_fail_closed_result[inconsistent]`, `test_installed_hook_allows_an_ordinary_fast_forward_push`,
`test_run_preflight_passes_against_a_fully_valid_fixture_repo`, `test_verify_movement_fails_when_a_validation_plan_command_fails`)
are git/subprocess timeouts under machine load; rerun alone they pass.

