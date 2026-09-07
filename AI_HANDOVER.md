# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-08. `gov_po_1_step_5_first_plan_episode` (`GOV.PO.1` §10
  step 5) — the first interactive Phase A `PLAN` episode. Governance only.
- `now_next.next` is no longer the deferred `M8.3`. It is
  **`m10_1_registry_evidence_reconciliation_projection`** — the `D4`
  registry↔evidence reconciliation producer, first slice of `M10`.
- `M8.3` stays **deferred** and `M7` stays **blocked**. Choosing `M10.1`
  pays none of that debt and must never be described as having done so.
- Predecessors all **MERGED**: PR #114 (`GOV_PO_1_GATE_1`, packet emission)
  and #115 (`GOV_PO_1_GATE_2`, branch self-sync) — the two gaps this episode
  hit and reported; #112 (record RATIFIED), #111, #110, #109. Phase A
  **authorized** (relay #5); Phase B closed until `T1`-`T6`.

## 2. What changed

Governance state only — no product source, test, template or static file.

- `project/roadmap.json`: `current_build` and `now_next.now` set to this
  episode; `now_next.next` replaced with `M10.1`; `M8.3` annotated in
  `upcoming` as retained deferred debt; new `upcoming` rows for `M10.2`,
  `M10.3`, `M11`, `M12`.
- `project/backlog.json`: two new items — `dlp_scanner_venv_exclusion`
  and `project_state_wording_drift_reconciliation`.
- `project/build_history.json` head record + `docs/history/INDEX.md`
  regenerated; `CURRENT_STATE.md` checkpoint/active-build/next rewritten
  and trimmed back inside its 200-line budget.
- `docs/design/PRODUCT_DIRECTION_RECORD.md` **deliberately untouched** —
  amending a RATIFIED record was not needed for this episode's outputs.

## 3. Exact next action

1. Merge this governance PR (#113) — needs its own recorded `RELAY_DECISION`
   on relay **#10**, this episode's issue.
2. Open `M10.1`'s own relay issue with its drafted `SESSION_START`, then
   run it in a **fresh engineering session** at **`Sonnet 5, normal`**
   (deterministic implementation against a frozen contract).
3. `REVIEW` episode after `M10.1`'s `SESSION_CLOSE` lands; the next slice's
   size follows that review's findings (`GOV.PO.1` §7).
4. Step 6 still owed: isolation `VALIDATION` `T1`–`T6` before any delegated
   (Phase B) episode. Extend it to cover **packet emission** — see §5.

## 4. Test delta

No new tests: this episode changed only governance metadata. Convergence +
`gov_po_role` + relay + session-transfer suites re-run after the sync merge;
build-history index `--check` current; `git diff --check` clean. The
repository privacy gate is delegated to the PR's `validate` CI job — the PO
role is denied the product CLI by design.

## 5. Risks / notes forward

- **Packet emission — reported, then fixed mid-episode.** The gate rejected
  the angle brackets the packet sentinel is built from and allowed no file
  outside the governance paths, so a gated PO session had neither a
  `--body` nor a `--body-file` route, while `GOV.PO.1` §5.1.1 requires a
  state-changing episode to post exactly those two packets. `GOV_PO_1_GATE_1`
  (relay #8, PR #114) fixed it; this episode then posted its own packets on
  relay #10 — first end-to-end proof of that path. **Residual:** `T1`–`T7`
  still do not exercise packet emission.
- **Nothing tests that `settings.local.json` cannot widen either enforcing
  layer**, though `GOV.PO.1` §6.2 `I3` requires it. Observed live: this
  session launched without `--settings`, so the role-scoped `deny` was
  absent while `settings.local.json` carried `Bash(gh pr merge:*)` in
  `allow`. No merge was attempted; the file is now `"allow": []`. A cheap
  `T7`-shaped repository test would close this.
- **Branch self-sync now exists** (`GOV_PO_1_GATE_2`, relay #9, PR #115):
  `git merge origin/<ref>` is allowlisted for the interactive form. This
  episode used it to sync and resolved the five governance-path conflicts
  itself — its first real exercise. Still a capability, not automatic: an
  episode has to know to run it. Worth a line in the `nexus-po` skill.
- **The PO gate still cannot discard a working-tree file.** No
  `git checkout -- <path>`, `git restore` or `git stash`, which is exactly
  what a stale local edit needs when a fix lands upstream. It cost one
  human command this episode.
- **Two verified state drifts, reported not fixed** — backlog
  `project_state_wording_drift_reconciliation`. Both live in free-text
  label/summary fields, so the cross-authority convergence check cannot see
  them.
- Documented ≠ demonstrated: `T1`–`T6` are `NOT_RUN`; Phase B stays closed.
