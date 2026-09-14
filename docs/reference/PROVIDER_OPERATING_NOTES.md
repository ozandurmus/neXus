# PROVIDER_OPERATING_NOTES.md — per-provider behaviour the loop has measured

Companion to `docs/reference/MODEL_TIER_MAP.md`. That file maps neutral tiers
to current model names; this one records how each provider actually behaves
when dispatched, so `roles/PO.md` can state the rules vendor-neutrally and
point here for the names. Nothing here is authority: the rules live in
`roles/PO.md` and `AGENTS.md`, the contracts in `docs/design/GOV_ORCH_*`.

## Why the default is Codex

Since 2026-09-14 the Product Owner's Claude credit is metered and their Codex
use is not. Every engineering dispatch therefore goes to Codex unless the
Product Owner says otherwise in writing, in the packet. Claude stays the
Product Owner assistant's own model and the dispatch fallback when a Codex
dispatch has failed twice for a reason that is not a defect in the packet.
Judging a packet risky is not grounds to switch: say so and let the Product
Owner choose. Silent drift back to Claude happened once, on movement
`NXS-LOCAL-0175`, and cost credit the Product Owner had asked to save.

## Codex

- **It cannot always commit.** Its sandbox is frequently refused `index.lock`
  in a linked worktree, so a movement finishes green and uncommitted and the
  orchestrator records it `failed`. Read `.nexus/engineer_last_message.txt`
  and `git status` in the worktree before concluding anything.
- **Its event stream carries no model field**, so cost is estimated from the
  requested model and the figure is marked with a trailing asterisk — a
  comparable, never billed spend (GOV.ORCH.4-A CU-4). Every Codex dispatch is
  recorded with `audit_exception: "provider_default_used"`.
- **It proves no budget exhaustion.** `CodexAdapter.budget_exhausted` returns
  `None` by the vendor-semantics law rather than inferring one from cost.
- Resume path: `codex exec resume <id>` (GOV.ORCH.2 §2.3).
- It has no `PreToolUse` hook, so it is forced to `--merge-mode orchestrator`
  and never reaches `gh pr merge` itself.

Claude can prove ceiling exhaustion from its observed terminal reason; Codex cannot, because it supplies no equivalent evidence. For a provider that cannot prove exhaustion, the recorded ceiling is an intention rather than a control; the governing requirements remain in `AGENTS.md` and `roles/PO.md`.

## Claude

- Its `stream-json` `system`/`init` event carries the model actually served,
  so `observed_model` is real rather than assumed.
- Its `result` event carries `terminal_reason: "budget_exhausted"`, so
  `--max-budget-usd` exhaustion is an observed fact, not an inference.
- Resume path: `claude --resume <session id>`.

## Antigravity

Not a provider yet. Measured, not adapted:
`docs/design/ANTIGRAVITY_PROVIDER_MEASUREMENT_2026_09_14.md` and its successor
`..._MEASUREMENT_2_2026_09_14.md`. No CLI; an in-process SDK over a bundled
harness binary. It would need `FORCED_MERGE_MODE` like Codex, for the same
reason.

## Cost per turn, by movement class

Contract and domain-core movements run about $0.05 a turn; transport and
multi-layer implementation movements about $0.08. Budget the class, not the
average.
