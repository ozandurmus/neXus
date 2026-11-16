# Separate the legacy Python product from the neXus repository: delete root clutter, move the action taxonomy to the Java registry (AGENTS.md amendment), keep governance tooling, archive the rest to a private repository

status: planned · target: docs/design/LEGACY_PYTHON_SEPARATION_PLAN.md -- four PO decisions

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Separate the legacy Python product from the neXus repository: delete root clutter, move the action taxonomy to the Java registry (AGENTS.md amendment), keep governance tooling, archive the rest to a private repository

Target (full): docs/design/LEGACY_PYTHON_SEPARATION_PLAN.md -- four PO decisions

2026-11-14 backlog truth: still open (P3, size M). Today: Repo clutter only; governance tooling still depends on Python. Evidence: Plan is DRAFT awaiting PO; main.py, utils/, tests/, plugins/ still at repo root

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): steps 1-4 done and on main: plan and PO approval recorded (bd6b10ca), step 3 deleted main.py, utils/__init__.py and two dead root tests (dc57e9f5), step 4 moved 27 one-shot consult scripts to history/scripts (b99829c5). Steps 5-8 are not approved by the PO and stay open; consult_claude_log_trial_judge.py waits for step 5.
