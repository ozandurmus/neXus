# PO Decision Record — 2026-09-15 B — The idle timeout is five minutes

## Status

**FROZEN — PRODUCT OWNER DIRECTIVE, 2026-09-15.** Successor clause to
`UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §7s session-duration paragraph,
which fixes the idle timeout at thirty minutes. That document is not edited in
place; this record supersedes the single value and nothing else in it.

## 1. The decision

The idle timeout is **five minutes** from `last_seen_at`, recomputed into
`idle_deadline_at` on every request that passes `E1`, exactly as `C3` already
describes. A session idle past that deadline is expired immediately, with
`end_reason = idle_timeout`, by the same reconciler and the same closed
vocabulary `C3` §3.5 already fixes.

Nothing else moves. The absolute lifetime is unchanged. The state machine, the
`end_reason` vocabulary, the heartbeat rule and the single-active-session rule
are untouched.

## 2. Why the value changed

Thirty minutes was chosen before the product had an operator using it. The
Product Owner, who is the security authority here, asked for five: an
unattended browser on a corporate desktop is the exposure this timeout exists
to bound, and thirty minutes is most of a meeting.

## 3. What this does not decide

Whether the value should be configurable. It is a constant today and stays one;
a deployment-time setting is a separate decision with its own record, and a
constant that an operator can lengthen is a different security property from a
constant they cannot.

Whether the absolute lifetime should also shorten. It was not raised and is not
changed here.

Whether the browser warns before expiry. `C3` §5 already refuses to infer intent
from silence; a warning is a user-experience addition, not a change to when the
session ends, and it is not decided here.

## 4. Cross-references

- `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §3.4, §3.5, §7 — the session
  model this record leaves intact, and the one value it replaces.
- `AGENTS.md` — authority hierarchy: a FROZEN contract is superseded by a later
  record, never edited in place.
