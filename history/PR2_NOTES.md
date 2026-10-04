# PR2 review notes

This is a local engineering handoff, not a PR submission or merge approval.
Baseline: `64be075650968b21f67a9fa2369fdf92e6d6d8ee`; lane: `sa/restructure-pr2`.
Authority: the Product Owner PR2 brief approves this bounded relocation; the
planning document remains DRAFT and is not frozen by this change.

## Change and retained boundaries

- 364 exact renames (309 Python files): LEGACY_PRODUCT and TEST_OF_LEGACY rows,
  their non-Python assets and render harness, legacy requirements and taxonomy.
  No archived file content changes. Git provides the reversible move map.
- Retained all LIVE_TOOLING files except `utils/action_taxonomy.py`, whose move
  is explicitly directed by the brief following Java authority succession.
  The local AST import closure covers 81 files and reaches only `utils/__init__.py`,
  `utils/project_plan.py` and `utils/repository_privacy.py`; no live taxonomy import.
  The existing import-isolation test now includes every inventoried live Python tool.
- Kept the three project-plan/backlog JSON fixtures in `tests/fixtures/`; moved
  CP/PAN/VSX and legacy UI fixtures with their archived consumers.
- Retained root `requirements-dev.txt` and `pytest.ini`. The brief explicitly
  archives legacy `requirements.txt` despite its original UNSURE classification.
- Privacy recognizes only `tests/` and `history/tests/` as fixture roots. Other
  archive paths remain scanned normally, and forbidden binary artifacts remain
  forbidden even in archived tests. No gitleaks or security-baseline acceptance
  path moved; neither file changed.
- CI still selects full live regression for the rename diff. Added the retained
  privacy test file to the closed mapping. Required workflow jobs are unchanged.
- `ui2/`, Containerfile COPYs, the `ui2 project docs` build context, `deploy/`,
  `security/`, `docs/`, project state and handover files are unchanged.
- Root `main.py` and `scripts/ui2_extract_fixtures.py` remain UNSURE. Their legacy
  imports no longer resolve through ordinary live Python invocation. They are
  not live tooling and must not be used as live entry points. Mixed historical
  tests retained under `tests/` likewise remain outside default discovery.

## Retained UNSURE inventory rows

All rows below remain in place, without edits. Directory rows retain their
unresolved children; specifically classified children move as directed.
Local-only metadata is neither inspected nor moved.

- `.dockerignore`
- `.git`
- `.gitattributes`
- `.gitignore`
- `.nexus`
- `.standalone`
- `main.py`
- `pytest.ini`
- `relay`
- `requirements-dev.txt`
- `scripts`
- `scripts/consult_claude_backup_code_review.py`
- `scripts/consult_claude_compliance_architecture.py`
- `scripts/consult_claude_failover_code_review.py`
- `scripts/consult_claude_failover_engine_final_review.py`
- `scripts/consult_claude_failover_phase_c_review.py`
- `scripts/consult_claude_failover_phase_d_review.py`
- `scripts/consult_claude_failover_security.py`
- `scripts/consult_codex_architecture.py`
- `scripts/consult_codex_backup_code_review.py`
- `scripts/consult_codex_compliance_architecture.py`
- `scripts/consult_codex_failover_architecture.py`
- `scripts/consult_codex_failover_code_review.py`
- `scripts/consult_codex_failover_engine_final_review.py`
- `scripts/consult_codex_failover_phase_c_review.py`
- `scripts/consult_codex_failover_phase_d_review.py`
- `scripts/consult_codex_ui_visual_review.py`
- `scripts/consult_exec_overview_design.py`
- `scripts/consult_fable_overview_contract.py`
- `scripts/consult_fable_palo_alto_compliance.py`
- `scripts/consult_fable_resume_on_astra_review.py`
- `scripts/consult_fable_ui_visual_review.py`
- `scripts/consult_overview_design_council.py`
- `scripts/consult_ui_effectiveness_council.py`
- `scripts/measurement/pan_inventory_shape.py`
- `scripts/run_stage1_claude_sonnet_high.py`
- `scripts/run_stage2_claude_sonnet_high.py`
- `scripts/ui2_extract_fixtures.py`
- `tests`
- `tests/conftest.py`
- `tests/test_architecture_convergence.py`
- `tests/test_gov_po_2_privacy_entrypoint.py`
- `tests/test_gov_po_3_ci_privacy_gate_baseline.py`
- `tests/test_ui2_b0_extraction_tooling.py`
- `tools`
- `utils`

## Validation

- Root collection before/after: 1,273 identical node IDs; 46 default files.
- Focused privacy, import-isolation and routing checks: 20 passed. Existing
  tests were extended; no default test cases were added or removed.
- Default `python3 -m pytest -q -n 2 --dist worksteal --tb=no`: before AND after
  1,256 passed, 7 failed, 10 errors; the same 17 failing/error node IDs.
- Initial archive collection encountered 52 runtime-directory permission
  errors because historical imports probe the inherited runtime location.
  Archive-only conftest now uses fresh temporary runtime/recovery roots and
  restores environment/import state at shutdown; no live runtime is needed.
- Archive `python3 -m pytest --collect-only -q --tb=short`: 2,701 cases, one
  collection error. The historical workflow test reads `history/main.py` at
  import time; root `main.py` is retained as UNSURE. See README.
- Frontend `npx tsc --noEmit -p .`, `npx vitest run`, `npm run build`: PASS
  (51 Vitest files, 490 tests). Existing React act warnings; absent canary
  digest is not a live e2e pass. Frontend inputs did not change.
- Repository privacy: PASS, zero findings before and after relocation.
- `git diff --cached --check`: PASS. `git diff -M --cached --stat` and blob
  equality checks confirm 364 R100 renames; staged paths route to `full`.
  Inventory coverage and unchanged protected build/state paths: PASS.
- Java/Gradle, PostgreSQL integration, image/security integration and real
  environment validation: NOT RUN. This lane forbids sandbox Gradle and host,
  network-device or deployment access. No complete release-gate pass claimed.

Existing default-suite failures belong to the owning lane: cold-start and
project-file/queue budgets (six failures), dashboard HTTP listener (one failure)
and dashboard browser setup (ten errors). They are not suppressed or converted
to skips. No unrelated state/contract repair is included in PR2.

No push, PR, merge, deployment, device command or host access was performed.
The owning engineering session retains project-state closeout and gate resolution.

Recorded local work/validation window: approximately 11 minutes.
Account quota and exact per-task token usage are unavailable; no usage estimate
is presented as measured billing.
