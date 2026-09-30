# PO decision record 2026-09-30: every change reaches main through a pull request

**Status:** RATIFIED -- Product Owner, 2026-09-30 (chat): "PR açsak ilerlesek daha iyi ... a ile ilerle."

## Context
Since the standalone orchestration mode (2026-09-26) the ship step fast-forwarded `main` and pushed it directly to
GitHub, and reviewer hotfixes were pushed the same way. Nothing was hidden (every commit is on GitHub), but there was
no pull request per change, so an SDLC audit could not point at a review record for each change.

## Decision
1. Every change -- a worker lane or a reviewer hotfix -- is pushed as a branch and merged into `main` through a GitHub
   pull request. `scripts/standalone_orchestrate.py ship --task <lane>` / `ship --branch <branch>` open the PR (what
   changed, the checks run, notes), merge it with a rebase merge, deploy from the merged `main` and comment the
   in-cluster e2e result on the PR. `ship` refuses to run without a task or branch.
2. **Option (a):** the engineering session merges once its checks pass (Java suites incl. architecture tests,
   frontend checks, privacy gate, migration dry-run). The PO reviews any PR when he wants; the record stays.
3. PR descriptions carry no "Generated with" footer.
4. Past direct pushes to `main` stay as they are (no retroactive PRs).
