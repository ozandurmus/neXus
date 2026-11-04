# PO decision record - 2026-11-04: development-mode delegations

**Status: RATIFIED - PO DECISION (chat, 2026-11-04).** In force while the product runs in development mode
(scheduled jobs off, see the 2026-11-03 manifests); revisit when scheduled operation resumes.

## Context
The PO asked what kept the engineer waiting and offered to remove each rule on request ("neyin beklettiğini söyle onay
verirsem kaldıralım"). Four were listed; the PO chose three.

## PO answer
Selected: "Hızlı birleştirme", "Geliştirme modunda DB bakımı", "Dondurma ve backlog yetkisi".
Not selected: "Salt-okunur komut yetkisi" -- every new device command still needs the PO's exact-command approval
(AGENTS.md, 2026-09-26 amendment).

## Decisions
1. **Fast merge.** A pull request merges once `validate` and the regression-scope routing pass (`scope-blocked` still
   stops it). `full-regression` and `targeted-regression` keep running on the pull request after the merge; a red
   result is fixed forward at once. `tools/delivery/standalone_orchestrate.py` (`POST_MERGE_CHECKS`);
   `NEXUS_WAIT_FULL_REGRESSION=1` restores waiting. Local pre-tests, preview e2e, the security gate and the production
   e2e are unchanged.
2. **Development-mode DB maintenance.** The engineer may run maintenance writes on the live ui2 database without asking
   (for example closing stale rows), each in one transaction with `app.actor_fingerprint` and `app.action_id` set so the
   audit trail names it. Deleting backups or collected data still needs an explicit PO yes. First use, 2026-11-04: five
   `discovery_run` rows left REQUESTED since 2026-10-21 (jobs cancelled) set to FAILED
   (`app.action_id = dev-mode-close-stale-discovery-runs`).
3. **Freeze and backlog authority.** The engineer may freeze design and vendor-semantics records whose evidence is
   cited; anything the evidence does not establish stays UNKNOWN in the frozen text. The engineer may groom
   `project/backlog.json` (archive, merge, reprioritise) within the GOV.ORCH.9 budgets. First use, 2026-11-04:
   `PATH_ANALYSIS_VENDOR_SEMANTICS.md` (A0) and `PATH_ANALYSIS_DESIGN.md` frozen; the design's Check Point hop order now
   follows A0 (access rule evaluated on both pre- and post-NAT tuples when a destination NAT matches; differing verdicts
   are `UNKNOWN`).
