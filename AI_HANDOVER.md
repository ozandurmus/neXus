# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal.

---

## 1. Snapshot

- Date: 2026-09-07. `GOV.SESSION.1` — **AUTOMATED_VALIDATED**, branch
  `governance/gov-session-1-transfer-protocol`, **PR #99 open** (`validate`
  CI passed), amended in place with one Product-Owner final-acceptance
  correction round — **awaiting user authorization to push the correction
  and update the PR; PO review/merge still pending**.
- Parallel `GOV` engineering track, unrelated to the `PCP.x`/`M8` product
  sequence — does not change `now_next.now`/`.next` (which correctly point
  at this build; see §5 note on why). `M8.2` (PR #98) is **merged** into
  `main`; `M8.3` stays `now_next.next`, untouched by this session.
- Protocol doc: `docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md` — **DRAFT,
  PO REVIEW PENDING**, not FROZEN, now at Revision 3.

## 2. What this session did

Two rounds in one continuous `SESSION: SAME` movement.

**Round 1 — design + implementation.** Audited `AGENTS.md`,
`AI_START_HERE.md`, `AI_HANDOVER.md`, `CURRENT_STATE.md` first; reused all
four by reference. Drafted a versioned, per-packet-id sentinel with a
compact `KEY: value` text payload, then corrected mid-session (PO
instruction) to the shipped design: one **permanent literal** sentinel
(`<<<NEXUS_SESSION_PACKET>>>`, identical open/close) wrapping a **strict
JSON** payload of exactly two closed message types (`SESSION_START` /
`SESSION_CLOSE`). Shipped `docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md`,
`docs/reference/gov_session_transfer_packet.schema.json` + two examples,
`scripts/gov_session_transfer.py` (dependency-free CLI:
`start`/`close`/`validate`/`render`/`extract`), and
`tests/test_gov_session_transfer.py` (55 tests). Pushed the branch, opened
PR #99, `validate` CI passed.

**Round 2 — final-acceptance correction (this round, PO instruction,
`SESSION: SAME`), applied to the same files in place — no second movement
record:**

1. **Per-message-type payload byte ceilings**, not one shared number:
   `SESSION_START` 4096 bytes, `SESSION_CLOSE` 8192 bytes
   (`PAYLOAD_BYTE_LIMITS`, `check_type_specific_size`). A coarse,
   type-agnostic gate at the larger value still runs first, before
   `message_type` is known. Exact-boundary and one-byte-over cases tested
   for both types.
2. **Required `git` object** on both message types
   (`additionalProperties:false`): `SESSION_START.git` =
   `{base_sha, branch}`; `SESSION_CLOSE.git` =
   `{base_sha, branch, head_sha, pr_number, merged, merge_sha}`, with
   `merged`/`merge_sha` SHA-or-null consistency enforced both natively
   (`_validate_git_close`) and in the schema (`if`/`then`/`else`). New CLI
   flags: `--base-sha`/`--branch` (`start`);
   `--base-sha`/`--branch`/`--head-sha`/`--pr-number`/`--merged`/`--no-merged`/`--merge-sha`
   (`close`).
3. **Packet is the canonical chat-transport form** of `SESSION
   START`/`SESSION CLOSE` — an agent that renders a valid packet does not
   also owe a separate unstructured prose narrative. Durable repository
   updates (`AGENTS.md` "Project-state update rule", `AI_HANDOVER.md`)
   remain mandatory and authoritative regardless — this was never in
   question, only the chat-narrative duplication was removed.
4. **Schema made normative**, not reference-only: `docs/reference/gov_session_transfer_packet.schema.json`'s
   own description now says so; `scripts/gov_session_transfer.py` is its
   conforming reference implementation (still no `jsonschema` dependency
   load — enforced natively). New CLI/schema parity tests assert required
   fields, `additionalProperties`, and every closed-vocabulary enum match
   between the two.
5. **Reconciled a real, verified drift**: `git log`/`gh pr view 98` at this
   session's own start already showed PR #98 merged
   (`16c39ab494b3eb0d2ed3b3583a470d754a9c4e9c`, 2026-09-06T21:57:27Z) — the
   "PR #98 (unmerged)" wording this session had copied from the docs it
   read was stale and never independently checked against git in round 1.
   Fixed in `CURRENT_STATE.md` (two spots) and here; left the `M8.2`
   `project/build_history.json` record's own historical evidence text
   unedited (it was accurate as of its own session-close time — "do not
   silently rewrite historical outcomes").
6. Extended `tests/test_gov_session_transfer.py` to 76 tests (git-object
   validation, exact/one-over size-boundary tests for both types via a
   byte-precise padding helper, CLI/schema parity). Updated both example
   packets and the protocol doc (§2, §4, §5, §7) to match.
7. `project/build_history.json`'s `gov_session_1_transfer_protocol` record
   and `project/roadmap.json`'s `now_next.now` entry amended **in place**
   with a correction-round note — no new build record, per explicit
   instruction.

## 3. Exact next action

Two independent threads, neither blocking the other:

- **This movement (`GOV.SESSION.1`):** push the correction commit, update
  PR #99, wait for `validate` again, then **stop for Product Owner review —
  do not merge**. The protocol stays DRAFT until the PO freezes it.
- **Product thread (unaffected by this session):** `M8.2` is merged;
  `now_next.next` remains `M8.3` — the read-only first-contact producer,
  real-environment gated, per
  `docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`.
  This session did not touch it and must not begin it.

## 4. Test delta

`tests/test_gov_session_transfer.py`: 55 → 76 tests, all passing —
`py -m pytest -q tests/test_gov_session_transfer.py`. No existing
non-`gov_session_transfer` test file changed; no vendor/collector/schema-
migration/device-contact code touched. Full re-validation before this
correction's push: `tests/test_architecture_convergence.py` +
`tests/test_application_package.py` green, `py scripts/build_history_index.py --check`
passes, `py main.py --repository-privacy-check` PASS/0 findings,
`git diff --check` clean.

## 5. Risks / notes forward

- The protocol document is explicitly **not FROZEN** — a future packet's
  *content* is never self-authorizing regardless of this movement's
  `automated_validated` status.
- No integration point in this repository actually emits or consumes a
  packet yet; `scripts/gov_session_transfer.py` is invoked manually. Wiring
  it into an actual `SESSION START`/`CLOSE` workflow step, if ever wanted,
  is a separate, later decision.
- `now_next.now`/`current_build` point at `gov_session_1_transfer_protocol`
  because `utils.project_plan`'s cross-authority gate requires `now.build`
  to equal `build_history.json`'s newest record unconditionally, regardless
  of track — this is a repository-wide invariant this session discovered
  and complied with, not a product-roadmap decision; it does not mean the
  product sequence's own "now" (`M8.2`, merged) or "next" (`M8.3`) changed
  in substance.
