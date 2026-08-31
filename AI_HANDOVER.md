# AI_HANDOVER

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase doc.
Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-08-31.
- Product baseline `0.7.7` — AUTOMATED_VALIDATED. Engineering `DEV.3.3` —
  AUTOMATED_VALIDATED. `RB.3b` — `in_progress` (all 7 implementation steps
  landed; only the real-environment run remains — hardware-gated, not
  engineering). None of these changed this session.
- `remove_dormant_remote_cleanup` — DONE (previous session in this chat).
- **This session's build: `frontend_rendering_boundary` — CONTRACT FROZEN,
  not implemented.** Docs-only scoping/audit pass; the next session
  implements against the frozen contract.
- Branch: `claude/frontend-rendering-boundary-contract`, merged to `main`
  via PR (squash) this session — see the commit this handover ships with.

## 2. What changed this session

**No source file touched.** This was a `SCOPE → AUDIT → CONTRACT` pass, not
`IMPLEMENT` — deliberately, so a fresh chat gets a ready-to-execute plan
instead of re-deriving one.

- New `docs/history/phase/FRONTEND_RENDERING_BOUNDARY.md` — the frozen
  contract. Read it before doing anything on this item; this handover does
  not restate its content. Key shape: a `<meta>`-tag CSP (`default-src
  'none'`, `'unsafe-inline'` only for the report's inlined script/style —
  it has no server to set an HTTP header and no external resources at all),
  an escaping rule (every dynamic value into `innerHTML` must go through
  `escapeHtml()`), AC-1…AC-7, a 6-step implementation plan, and an honest
  "risks" section on what a CSP with `unsafe-inline` does and does not buy.
- Real audit findings behind that contract (grounded, not assumed):
  `escapeHtml()` (`static/app.js:27`) exists and is used pervasively;
  `utils/html_export.py::_script_json` already neutralizes `</script>`
  breakout in every embedded JSON payload; no CSP exists anywhere today; the
  report has zero external resources and zero network calls (`fetch`/`XHR`/
  `WebSocket`), which is what makes a strict CSP low-risk to add. A
  heuristic scan found 97 `.innerHTML` sinks in `static/app.js`, flagged 28
  as lacking a nearby `escapeHtml()` call; manual sampling of ~10 (including
  the device-name tree renderer, the highest-value target since it
  interpolates a live device's own configured name) found the existing
  discipline sound where checked — **but 87 of 97 sinks were never manually
  reviewed.** Do not mistake this sampling for AC-2's required exhaustive
  audit.
- `project/build_history.json` — new `frontend_rendering_boundary-contract`
  entry (`status: complete`, `movement: ARCHITECTURE` — the *scoping* work is
  complete; the backlog item itself stays `planned`).
- `project/backlog.json` — `frontend_rendering_boundary` note appended (not
  rewritten) with the contract summary; status unchanged (`planned` —
  correct, since nothing is implemented yet).
- `CURRENT_STATE.md` — one paragraph added pointing at the frozen contract.

## 3. Exact next action

**Implement `docs/history/phase/FRONTEND_RENDERING_BOUNDARY.md`.** Read that
doc first — it is the spec, this section is only the pointer. In order,
per its "Implementation plan":

1. The exhaustive sink audit (AC-2) — all ~97 `.innerHTML` sites in
   `static/app.js`, not just the 28 heuristic candidates. This is the actual
   security work; fix whatever gaps are found.
2. Add the CSP `<meta>` tag (AC-1) and **manually open the rendered report
   in a real browser** to confirm nothing silently breaks — a CSP violation
   often fails with no visible error, so the automated harness alone is not
   sufficient here.
3. Hostile-label fixture + regression test (AC-3, AC-4) via
   `tests/fixtures/uitest/build_fixture.py`.
4. `_script_json` breakout regression test (AC-5).
5. Full suite + privacy gate + render harness green (AC-6, AC-7) — the
   render harness run is **mandatory**, not optional, since this build
   touches `templates/`/`static/` (`AGENTS.md` Project-state update rule).
6. Project metadata: `build_history.json`, `backlog.json` → `done`,
   `CURRENT_STATE.md`, the phase doc's Status → `IMPLEMENTED`, this file.

Recommended tier: **`Sonnet 5, normal`** for all six steps — deterministic
against a frozen contract, no new architecture decision. The one place to
slow down without needing a *higher* tier is step 1: exhaustive, not fast.

If the sink audit (step 1) finds something that contradicts this contract's
CSP design (e.g. a resource load this session's grep missed) or finds an
actual exploitable gap that changes the risk picture, that is a reason to
pause and re-check with the user before proceeding — not a reason to
silently reinterpret the contract.

## 4. Test delta

None. No source, test, or fixture file changed this session — purely
`docs/history/phase/FRONTEND_RENDERING_BOUNDARY.md` (new) +
`project/build_history.json` + `project/backlog.json` + `CURRENT_STATE.md`
text edits. Last recorded evidence (unchanged, still authoritative):
**881 passed / 23 skipped / 2 failed** (the 2 are the documented
pre-existing test-order pollution, unrelated to anything in this chat).
Repository privacy gate: **PASS / 0** — docs-only.

## 5. New risks / debt

- The 28-candidate heuristic sink list in the phase doc is a starting
  point, explicitly **not** the audit. A future session must not treat a
  partial review of just those 28 as satisfying AC-2.
- The proposed CSP uses `'unsafe-inline'` for script/style (required by the
  single-portable-file architecture — see the phase doc's D-CSP2 for why a
  nonce/hash approach was considered and rejected). It is defense-in-depth
  against exfiltration/framing/embedding, **not** a backstop for a missed
  escaping gap. Do not present it to the user as "fixes XSS" — it narrows
  the blast radius of a script that does execute; AC-2's exhaustiveness is
  what prevents one from executing at all.
- No generic tooling in this repo enforces the escaping rule (D-ESC1) going
  forward — it is a written convention, not a linted one. Worth flagging to
  the user as a possible follow-up (e.g. an ESLint rule or a repo-hygiene
  regression test scanning for new unescaping `.innerHTML` sinks) once this
  build lands, not before.

## 6. Continue or fresh chat

**Fresh chat**, per the user's explicit instruction this session. Read
`AI_START_HERE.md` → `CURRENT_STATE.md` (hot section) → this file →
`docs/history/phase/FRONTEND_RENDERING_BOUNDARY.md` in full before touching
`static/app.js`.

## 7. main.py / UI effect

**No change yet.** This session added no CSP, fixed no escaping gap, and
changed no template/script/style file — the frozen contract describes work
for the *next* session. Once implemented: the report will carry a new CSP
`<meta>` tag (invisible in normal use, only observable via dev-tools/
browser CSP violation reporting) and any escaping fixes found in step 1
should be behaviorally invisible for all non-hostile input (AC-6) — a
normal render's visible output does not change.
