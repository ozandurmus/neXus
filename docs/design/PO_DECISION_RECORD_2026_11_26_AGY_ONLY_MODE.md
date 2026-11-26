# PO decision record - 2026-11-26: AGY_ONLY mode (delivery pipeline on Antigravity alone)

**Status: RATIFIED - PO DECISION (chat, 2026-11-26 13:45).** Claude usage is at 100 % until the reset (~2026-11-27 evening).
The PO directed: "Buradaki eksiklikleri de antigravity'nin işlemesi için exception tanımla" (define exceptions so that
Antigravity can carry what is missing here). This record defines those exceptions. **Scope: only while the file
`$NEXUS_OPS_STATE/lane-queue/AGY_ONLY` exists.** Without the file every rule below is inert and the pipeline behaves as
before. Removing the file ends the mode; nothing here changes the constitution (AGENTS.md) outside that window.

## Exceptions while AGY_ONLY exists

| # | Area | Rule |
|---|---|---|
| 1 | Lane engines | Every lane runs on agy (Carbon -> Neon -> Hydrogen fallback as before), including `pin_claude=1` and `engine=claude` briefs. `ENGINE_CLAUDE` and `AGY_OFF` are ignored and never created; a quota/429 marks the model rate-limited, and when all are limited the brief stays queued. |
| 2 | Parallel slots | The half-and-half Antigravity cap is lifted: all MAX (3) slots may run on agy. |
| 3 | Explicit model | `model=gemini-4-*` in an agy lane's `.meta` is honored (effort = the model name's suffix). Name-based routing stays the default when `model=` is absent. This applies with or without AGY_ONLY. |
| 4 | Planner | The P1 planner runs on agy. Headless agy cannot read outside its worktree, so the lane runner appends (bounded to ~40 KB) the output of `status.sh`, the open brief names (queued, started, hold/) and `project/QUEUE.md` from origin/main to the brief. The planner writes its 3 briefs and metas into `planner-out/` of its own worktree; the lane runner moves them into the lane queue under standard names when the lane ends OK (or with no commit). Only the meta keys base, engine, model, claude_model, effort and ship survive; `allow_paths`, `adddir` and `pin_claude` can never be set by a planner. |
| 5 | Tier-B review | `b-review.sh` skips `claude -p` and reviews with `agy -p --model gemini-4-neon-xhigh --mode plan --print-timeout 1500` (diff file inside the worktree, deleted afterwards, up to 3 attempts without a VERDICT line). A PASS records `reviewed=neon-agy-only`; a returned `fix-blocked-*` brief carries no `pin_claude=1`. |
| 6 | Guarded-path holds | An item that the ship runner would hold for guarded paths is reviewed by `scripts/consult_argon.py` (Neon, xhigh) on its diff against origin/main. PASS -> the entry gets `allow_paths=1` and `approved=agy-neon-xhigh AGY_ONLY exception PO 2026-11-26` and proceeds; BLOCK or no verdict -> it stays held. |

## Exception to the exception: always held for a person

Whatever the Neon review says, these stay held:

1. any diff that adds or changes a `db/migration/V*.sql` (the live BEGIN/ROLLBACK dry-run is a PO-session duty);
2. any change under `deploy/` or `.github/`;
3. any change to the AGY_ONLY logic itself (a diff line mentioning AGY_ONLY, or this record).

## Independent of the mode

After a SHIPPED batch whose diff touches `tools/delivery/ops/`, the ship runner updates the runner checkout
(`git checkout --detach origin/main` with the ops directory made writable first and read-only after) and re-executes
itself only when no batch is in flight. The lane runner re-executes itself at its next loop when its script file changed;
running lanes are separate processes and are never killed. `status.sh` prints `AGY_ONLY on` while the mode is active.

## Not changed

Network-device command gates, the privacy gate, the AIView masking law, class 2+ prohibitions, HOST-A rules and the
requirement of real-environment validation are untouched. Review by a second model family is weaker than the Claude
review it replaces; the PO accepted that for the window of the mode.
