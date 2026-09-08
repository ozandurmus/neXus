# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-08. `m14_local_ldap_d7_authorization_architecture_draft`
  (relay/NXS-LOCAL-0024) — produces
  `docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md`, status
  `DRAFT — discussion proposal`.
- Own worktree/branch
  (`feature/m14-local-ldap-authorization-architecture-draft`), standing
  `relay#13` merge authorization.
- Design-only: no code, no test, no `FROZEN` document changed, no live
  LDAP/AD call made.

## 2. What changed

- `docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md` (new, DRAFT):
  a `D7` actor-authorization producer that binds to the operator's own
  corporate AD Domain Controller and authorizes from AD group membership,
  explicitly decoupled from the `DEPLOY.1A` server/OIDC track. Splits the
  work into `A1` (actor binding + session admission — augments `E1`, and is
  deliberately **not** `D7`) and `A2` (the `local_ldap` `D7`/`E4` producer),
  so the frozen contract's own "a session gate is not `D7`" rule is kept.
  Seven resolved decisions `LD-1`…`LD-7`, twelve acceptance gates
  `AG-1`…`AG-12`, four named unknowns `U-1`…`U-4`, three delivery slices.
- `project/backlog.json`: new `m14_local_ldap_d7_authorization_architecture`
  item (`planned`, `P2`) pointing at the document. No other project-state
  file touched, per the movement's own AC-6.

## 3. Exact next action

1. Product Owner review of the DRAFT. Two things need a Product Owner
   decision before anything else moves: the §7 authority contradiction
   (`M14` vs a new `M14L` id — see Risks) and whether to freeze.
2. Do **not** dispatch an implementation movement from this document.
   Freezing it would still not authorize slice 1; that is a separate
   go-ahead, and `LD-5`'s `ldap3` dependency needs its own approval under
   `docs/AI_DEVELOPMENT_PROTOCOL.md` "Approval boundaries".
3. `U-1` (nested-group matching semantics, Microsoft documentation) must be
   closed **before** any freeze — it determines whether the membership check
   is correct.

## 4. Test delta

No test added or changed. Full parallel regression run once on this branch
with the main checkout's interpreter:
`/Users/OzanDur/Codo/neXus/.venv/bin/python -m pytest -q -n auto --dist
worksteal` → **3022 passed, 25 skipped, 2 failed** in 167.79s. Both failures
are the pre-existing DLP-token collision tests in
`tests/test_dev_0_5b_auth_consumer_canonical_config.py`; their findings are
`project/build_history.json` + two `relay/*.json` files, none of them touched
by this movement, and neither new file appears in either finding list.
`git diff --check` clean. Repository privacy gate against the `origin/main`
baseline: **PASS, 0 new findings** (5 pre-existing, all in files this
movement did not touch).

## 5. Risks / notes forward

- `DRAFT` only. Do not report `D7` as having a producer, and do not report
  `M14` as unblocked, on the strength of this document.
- **Authority contradiction, reported not resolved** (§7): the FROZEN
  `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md` §12/§12.1
  defines `M14` as *production OIDC/RBAC* with a `DEPLOY.1` prerequisite.
  This draft proposes different content under the same id. Recommendation is
  option (1): give this work a distinct id (`M14L`) so `M14` keeps its frozen
  meaning and §11.3's "does not retroactively validate local shortcuts" rule
  stays intact. Product Owner decides; this draft amends nothing.
- The most likely implementation defect is named as `AG-4`: a configured but
  unreachable `local_ldap` authority silently degrading to
  `NO_APPLICABLE_AUTHORITY`, which the frozen contract makes non-blocking —
  i.e. a fail-open with no visible symptom. It must be tested directly.
- This worktree has no `.venv` of its own, and the exported `VIRTUAL_ENV`
  points at a non-existent `.venv312`. Use the main checkout's already-
  validated interpreter (`/Users/OzanDur/Codo/neXus/.venv/bin/python`)
  directly; do not bootstrap an environment (`AGENTS.md` "Context/token
  discipline").
