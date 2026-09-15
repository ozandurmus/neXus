# PO Decision Record — 2026-09-15 C — The regression gate runs when the core changes

## Status

**FROZEN — PRODUCT OWNER DIRECTIVE, 2026-09-15.** Successor clause to the
`DEV.TEST.1` topology of 2026-09-06, recorded in `.github/workflows/validation.yml`
and in `docs/AI_DEVELOPMENT_PROTOCOL.md` under "CI validation policy". That
directive is not reversed; it is narrowed.

## 1. What the 2026-09-06 directive said, and why it was right

Every pull request ran the fast `validate` gate only. `full-regression` ran on
an explicit `workflow_dispatch` and never automatically. The reason was cost: a
GitHub-hosted run of the full suite is expensive, and the already-proven local
parallel run was treated as sufficient evidence.

That reasoning has not stopped being true.

## 2. What it cost, measured

On 2026-09-15, PR #359 merged with every check green while **five tests were
failing on `main`**. They had been failing since that merge and nothing
reported it, because the job that would have reported it was skipped. The five
were found by a session running the suite by hand, days later.

A gate that never runs is not a cheap gate. It is an absent one, and it is
worse than an absent one because the pull request page says green.

## 3. The decision

`full-regression` runs on a pull request **when the change touches anything the
suite can break**, and does not run when it cannot.

- It **runs** when the pull request touches source, tests, project state,
  workflows, or deployment manifests.
- It **does not run** when the pull request touches only documentation and
  records.
- `workflow_dispatch` remains available unchanged.

The implementing movement chooses the exact path expressions and states them in
its pull request. Two rules bound that choice: the default when a path is not
matched by any rule is to **run**, not to skip; and a documentation-only
exclusion is an exclusion of documentation, never of a directory that happens to
contain documentation among other things.

## 4. Why not the other two options

Running the full suite on every pull request was considered and rejected: it
pays the full cost to close a gap that a path condition closes for a fraction of
it, and the 2026-09-06 cost reasoning stands.

Leaving the job manual and tightening the merge gate instead was considered and
rejected: it puts the guarantee back on a human remembering, which is exactly
what failed here. A person did not forget to run the suite; nobody knew it
needed running.

## 5. What this does not decide

Whether `po-scope-check` should also broaden. It is a separate job with its own
condition, and the implementing movement reports what it found rather than
changing it under cover of this record.

Whether the suite should be split into a fast and a slow half. That would change
what "full regression" means and needs its own record.

## 6. Cross-references

- `.github/workflows/validation.yml` — the file that carries the condition.
- `docs/AI_DEVELOPMENT_PROTOCOL.md`, "CI validation policy" — the prose that
  must be corrected to match this record.
- `AGENTS.md` — automated validation is not real-environment validation; this
  record changes when the automated gate runs, and nothing about that boundary.
