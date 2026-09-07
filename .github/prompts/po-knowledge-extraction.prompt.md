---
description: "One-time extraction of Product Owner assistant knowledge with provenance, for PRODUCT_DIRECTION_RECORD.md"
---

You have acted as the Product Owner assistant for the neXus / SecurityExpert
project. That role is moving to a repository-defined assistant that starts
every episode from a fresh context and reads only repository files. This is
a one-time, read-only extraction of what you know. You supply **evidence and
recommendations**; you do not ratify anything. The human Product Owner
ratifies the resulting record.

## Ground rules

- English. Markdown. One document. First line exactly:
  `**Status: DRAFT — extracted from the previous Product Owner assistant, <date>. Not ratified.**`
- The repository is the primary source. Chat recollection is secondary
  evidence, never unique or authoritative knowledge. Where a claim is already
  recorded in the repository, write `see <path>` and add only what the file
  does **not** say (the why, the rejected alternative, the doubt).
- **Every item carries one provenance tag**, no exceptions:
  - `[REPO <path>]` — a verified repository decision or fact, linked to its
    source file (and section/id when one exists).
  - `[PO-DIRECTION <date or period>]` — an explicit human Product Owner
    direction you received that is **not yet durably recorded** in the
    repository. Quote or closely paraphrase; say where it was given
    (chat, relay comment, PR review).
  - `[ASSISTANT]` — your own observation, inference, or unresolved
    hypothesis. Add `confidence: high|medium|low`.
- No credentials, management addresses, hostnames, serials, usernames,
  host-key material, or any raw device identity. Describe relationships and
  classes, never values (`AGENTS.md` "Sensitive identity reporting law").
- Prefer `UNKNOWN` to invented certainty.
- If the answer exceeds one reply, split by section and continue in the next
  reply with `continued: §N`; do not compress to fit.

## Produce these sections, in this order

### §1 Product thesis and non-negotiables
What neXus is and is not. The principles you would refuse to trade away
under schedule pressure. Tag each `[REPO]` (cite `AGENTS.md`,
`PROJECT_VISION.md`, `AI_START_HERE.md`) or `[PO-DIRECTION]` or `[ASSISTANT]`.

### §2 Decision record
Every product or governance decision you took part in. For each: `id` (reuse
the `project/roadmap.json` `open_decisions` id when one exists), date or
period, the question, the options actually on the table, what was chosen,
what was rejected **and why**, the current status as you believe it
(`decided` / `open` / `superseded` / `UNKNOWN`), and the provenance tag.
Decisions you consider settled but never wrote down are the valuable ones;
they are `[PO-DIRECTION]` if the human said it, `[ASSISTANT]` if you
inferred it.

### §3 Rejected directions
Directions, features, architectures or vendors proposed and turned down, the
reason each stays rejected, and who rejected it (provenance tag).

### §4 Review heuristics
The ordered checklist you actually apply to a `SESSION_CLOSE`, a PR, or a
contract before advising a decision, in the form "look for X; if Y, it is a
finding". Include the heuristics that found the M9 round-1 gaps.
`[ASSISTANT]` unless a heuristic is already a repository rule (`[REPO]`).

### §5 Recurring engineering failure patterns
Patterns you corrected more than once (self-authorized exceptions, scope
widening inside a fix, `DONE` from automated evidence, stale handovers,
invented vendor semantics, others). For each: how you detect it, the
correction you give, provenance tag.

### §6 Sequencing rationale
Why the tracks and the backlog are ordered as they are: what must precede
what and why. Where you disagree with the current `project/roadmap.json`
order, say so as `[ASSISTANT]`; where the order came from the human, tag
`[PO-DIRECTION]`.

### §7 Council
How the `nexus-decision-council` was meant to work: seat definitions, what
each seat protects, when it should and should not be invoked, and any
verdict or dissent you remember that never reached a document. Note: the
repository records that no council skill was ever installed and that past
rounds were single-author self-critique; do not describe them otherwise.

### §8 Real-environment constraints (sanitized)
Platform classes, shell/context behaviors, trust and network constraints,
corporate DLP/Git constraints, validation logistics. Relationships and
classes only. Provenance tag on each.

### §9 Stakeholders and external constraints
Who signs what, which corporate policies bind the project, what
"production" means here, sign-off steps outside the repository.

### §10 Things you believe are in the repository but may not be
Claims you have relied on without being sure any file states them. Tag
`[ASSISTANT]`; the successor will verify each against the repository.

### §11 Open doubts
Questions never resolved, risks you kept meaning to raise, places where you
suspect repository and reality have drifted.

### §12 Advice to your successor
The three mistakes a fresh assistant will make first, and the three
questions it should ask the Product Owner before its first planning episode.

## After the document

Reply once more with a **second pass**: re-read your output and list any
item you skipped because it felt obvious, sensitive, or too small, with its
provenance tag. Small `[PO-DIRECTION]` items are the ones most likely to be
missing from the repository.
