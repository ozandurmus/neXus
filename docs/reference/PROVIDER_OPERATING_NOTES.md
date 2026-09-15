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

- **Linked-worktree commits need the real Git directory granted.** A linked
  worktree's `.git` is a pointer; its index and `index.lock` live in the
  directory returned by `git rev-parse --git-dir`, outside the worktree. The
  dispatcher grants that one derived directory alongside the worktree, so an
  engineer can commit without access to the main checkout.
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

In trial since 2026-09-15 as a third participant, able to hold either the
engineer or the Product Owner role. Measured, not adapted:
`docs/design/ANTIGRAVITY_PROVIDER_MEASUREMENT_3_2026_09_15.md` supersedes the
two earlier records, whose claim that no command-line interface exists was
wrong.

- **How it takes work today: the hybrid relay path, not the orchestrator.**
  It is not a `--provider` value and no adapter exists. The assistant prepares
  the movement, its worktree and its `.nexus/WORKER.md` exactly as a dispatch
  would, then hands the participant the worktree instead of spawning a
  process; the participant closes the relay with its own `SESSION_CLOSE`; the
  assistant runs `orchestrator.py verify`, reviews and merges as usual.
- **How the bridge is set up, so it survives a new session.** Two files in a
  `700` directory outside the repository (`~/.nexus-antigravity/`): an `env`
  file, mode `600`, holding exactly two names -- the language server's address
  and its CSRF token -- and an executable wrapper that sources that file and
  execs the desktop application's bundled `language_server` binary with its
  `agentapi` subcommand. Neither value is a credential of the vendor's: both
  are handles to the desktop session running on this machine, both change when
  it restarts, and both are re-read from the running application rather than
  stored anywhere durable. Nothing about this bridge belongs in the
  repository -- not the path to the binary, not the address, not the token --
  which is why only its shape is written here. The project id
  (`ANTIGRAVITY_PROJECT_ID`) is a third value, required to create a
  conversation and absent from the help text; read it from an existing
  conversation's metadata, as above.
- **When the desktop session restarts, the bridge is stale, not broken.**
  Rewrite the `env` file's two values from the running application. A wrapper
  that exits with `ANTIGRAVITY_LS_ADDRESS is not set` means the file was not
  sourced; one that fails to reach the server means the values are from a
  previous session.
- **Its import path is `google.antigravity`.** A bare `import antigravity`
  resolves to CPython's standard-library easter egg and opens a comic in the
  operator's browser.
- **No credential and no outbound path of its own.** Its command-line tool is
  a client of the running desktop session, so it needs no API key. It does
  need that session to be running: from an ordinary shell it exits with
  `ANTIGRAVITY_LS_ADDRESS is not set`.
- **It reports no tokens, no cost and no turns.** Measured over a real
  transcript: zero of 86 events carry a usage field. Its ledger row is
  `unknown` in every derived column, and the Product Owner has accepted that
  for the trial -- following the work is the requirement, not accounting for
  it. Do not estimate a figure to fill the gap; an invented number is worse
  than an honest `unknown`.
- **It ignores the working directory it was started from.** It is anchored to
  its editor's workspace, so a process launched inside a worktree still reads
  and writes in the main checkout. Every prompt must name the worktree's
  absolute path and forbid the main checkout in words (`roles/PO.md` §3).
- **Its command line selects only its own model tiers.** Other vendors' models
  are selectable in its interface but not through the command line, so a
  mixed-vendor run means pairing it with an orchestrator dispatch, not
  choosing a different model inside it.
- **A conversation cannot be created without a project id**, which its own
  help text does not mention; read it from an existing conversation's
  metadata.
- **Follow it through the worktree, not the workbench.** Commits on the lane
  and `git status` in the worktree are the progress signal, since no event
  stream reaches the orchestrator.
- It has no pre-push git gate, so were it ever adapted it would join
  `FORCED_MERGE_MODE` for the same reason Codex did.

## Cost per turn, by movement class

Contract and domain-core movements run about $0.05 a turn; transport and
multi-layer implementation movements about $0.08. Budget the class, not the
average.
