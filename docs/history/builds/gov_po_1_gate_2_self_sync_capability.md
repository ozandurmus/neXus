# gov_po_1_gate_2_self_sync_capability — GOV_PO_1_GATE_2 -- allow git merge origin/<ref> so a PO branch can self-sync a landed fix

## Summary

A fix landed on origin/main (GOV_PO_1_GATE_1, PR #114) had no way to reach the already-checked-out working tree of the concurrent gov_po_1_step_5_first_plan_episode: git fetch updates only the remote-tracking ref, never the working tree, and git merge/git pull were not allowlisted -- an engineer had to fast-forward the shared directory by hand twice (once via a detached-worktree reconciliation push, once directly in the shared primary directory after discovering the same directory hosted both the PO's live session and this engineering session). Adds git merge origin/<ref> and git merge --ff-only origin/<ref> to the interactive form's allowlist, restricted to the origin remote-tracking namespace (git remote add stays unavailable, so no arbitrary remote or URL is ever reachable); chaining (;, &&, backtick, $() remains denied by the existing token-based checks regardless.

## Evidence

Isolated detached worktree from origin/main. The Product Owner applied the same two-line edit directly, by hand, in the shared primary working directory to unblock the live episode immediately (git merge --ff-only origin/gov/po-1-step-5-first-plan reported 'already up to date', confirming the directory was already synced by the engineer's own prior fast-forward); this movement lands the identical, tested change on main so it is durable and covered. Validation: tests/test_gov_po_role.py (67, incl. 6 new: origin-ref merge allowed in both plain and --ff-only form, denied for delegated, denied for a non-origin remote/URL/path and for any chained/substituted form) + relay + session-transfer + convergence: 219 passed; repository privacy gate PASS; git diff --check clean.

## Risks forward

Self-sync still requires the PO episode to know to run git merge origin/<ref> -- it is a capability, not an automatic behavior; the nexus-po skill should mention it. This does not solve the underlying git-worktree-per-branch limitation for an engineer's own future fixes to a live PO branch; the detached-worktree-then-push pattern used in this and the predecessor movement remains the safe approach when the two sessions' working directories are the same.
