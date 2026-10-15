---
description: Ask Argon (Gemini 4 Argon via the agy CLI) a question, or give it a worktree task
argument-hint: <question>  |  task <worktree> <brief-file>
---

Per docs/design/PO_DECISION_RECORD_2026_10_15_ARGON_WORKER.md:

- If the arguments start with `task`, run
  `python3 scripts/argon_task.py --worktree <worktree> --brief <brief-file>` with the Bash tool (in the background for
  long lanes), then review the resulting diff and tests yourself before anything ships.
- Otherwise it is a question: run `agy -p "<the question, with the repository context it needs>" --model
  gemini-4-argon-high --mode plan` with the Bash tool from the repository root and relay the answer. If the answer is
  to be kept as a review, use `python3 scripts/consult_argon.py --brief <file> --output docs/design/<NAME>.md` instead.

Arguments: $ARGUMENTS
