# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase
doc. Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-05. Branch `claude/left-nav-vertical-redesign-e673q6`,
  **unmerged**, `origin/main` (`310593e`) + **4 commits**, **no pull request**.
  `origin/main` is an ancestor of HEAD; no divergence, nothing rewritten.
- Build: `pcp_2_local_control_plane_sequencing_po_review` (`M0`) — **COMPLETE.
  ARCHITECTURE FROZEN 2026-09-05** by Product Owner approval at reviewed head
  `ba56d2b`.
- **Freeze is not implementation authority.** `M1`…`M14` remain separately
  authorized bounded movements, and the `NAV.1` prototype is **not** mergeable
  until `M2` closes `AC-A11Y-1`…`4`.

## 2. What is frozen

- **`docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md`** — FROZEN — PRODUCT
  OWNER APPROVED. Fifteen `D-NAV` decisions, **no operative row provisional**;
  three-layer IA; the four-predicate capability model; the capability-state
  presentation matrix over existing canonical states; the logical-entity-first
  workspace; the preservation, shell-parity and accessibility contracts; the
  eight `PO-NAV` decisions; and **§19 acceptance criteria**
  (`AC-NAV-*`, `AC-WS-*`, `AC-DIF-*`, `AC-SH-*`, `AC-A11Y-*`).
- **`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md`** — FROZEN.
  Runtime lifecycle, background typed jobs, device-targeted execution, local
  storage **Option A** with its ownership boundary, credential/trust
  references, UI-first enrollment under **seventeen** conditions, the
  `M1`…`M14` sequence with §12.1 clarifications, and **§15 acceptance
  criteria** (`AC-RT-*`, `AC-TGT-*`, `AC-ST-*`, `AC-EN-*`).
- **`docs/design/research/NSPM_NAVIGATION_BENCHMARK.md`** — deliberately **not**
  a frozen contract. Screenshot evidence is **excluded from authority** because
  its provenance is not durably auditable from the repository; the appendix
  asserts no provenance in either direction, and **no frozen decision depends
  on it**. neXus behaviours are preserved through `REPO-VERIFIED` source rows.

### 2b. Parent-contract amendments applied

- **`CON.0` §4.1** (new) — the typed enrollment intent: closed schema
  (endpoint, opaque credential-profile ref, opaque trust-profile ref, tags,
  and/or a closed candidate id) and nothing else; no credential payload,
  command, argv or path; **no device I/O in the request**; first contact as a
  separate queued CLASS 0 job; **trust before credentials**, candidates
  included; positive-evidence identity; ambiguity persists nothing; operator
  review and **explicit confirmation**; **immutable audit before mutation**;
  the one existing `DeviceRegistry` path with duplicate/lock rules unchanged;
  **no candidate exemption**; **loopback only**; server mode blocked on
  `DEPLOY.1A`; navigation is never the authorization boundary.
- **`CON.0` §7.11** (new) — the same rules restated as security rules.
- **`PCP.0` §19** — decided block for the four `pcp_*` decisions.
- **`PCP.0` §10** — local storage sequencing decided (Option A); the production
  engine decision explicitly still deferred.
- **`PCP.0` §20.1** (new) — local control-plane runtime as a movement distinct
  from `PCP.2`'s enrollment providers; `M5` as the critical prerequisite;
  plane-wide-then-filter never counts as device-targeted.
- **`A5`/`A6` remain recorded and unapplied** — `M3` must inventory the
  existing vocabularies first.

### 2c. Decision register — scoped, not overloaded

| id | Status |
| --- | --- |
| `pcp_console_registry_write_gate` | **decided** — local loopback profile only, both intents, under all frozen conditions |
| `pcp_server_enrollment_exposure` | **open** (new) — production exposure, blocked on `DEPLOY.1A` |
| `pcp_local_control_plane_storage` | **decided** (new) — Option A |
| `pcp_storage_engine` | **open** — production-scoped; **SQLite is not the production engine** |
| `pcp_first_contact_trust_policy` | **decided** — strict trust before credentials, every endpoint |
| `pcp_auto_enrollment_policy` | **decided for the current horizon** — no automatic persistent enrollment |

## 3. Exact next action

1. **`M1` — `pcp1_registry_uuid_call_count_test_defect_repair`.** A **new
   session**, branching from **current `main`**, as a **clean narrow PR**. It
   repairs the two `PCP.1` registry `uuid4` call-count tests independently of
   NAV. After it merges, this branch incorporates the new `main` before any
   later validation or PR.
2. **`M2`** is required before the `NAV.1` prototype can merge.
3. Do **not**: open a PR for this branch, merge, implement SQLite, enrollment,
   jobs, collectors, schedules or RBAC, or start any movement without its own
   authorization.

## 4. Test delta

- **The full suite is not green and is not claimed to be.** The two `PCP.1`
  registry failures remain on `main` and here, untouched — they are `M1`'s
  exact input.
- Freeze validation: `tests/test_architecture_convergence.py` **20 passed**
  (document links, frozen-vs-terminal record consistency, `CURRENT_STATE`/
  roadmap agreement, build-history index, checkpoint line cap);
  `tests/test_navigation_information_architecture.py` **20 passed** including
  both navigation authority guards (now inverted: the contract must record a
  *Product-Owner-approved* freeze with its audit trail, and no source may claim
  the prototype is finished); `tests/test_frontend_module_composition.py`
  green; both render harnesses green; `metadata_warnings == []`; privacy gate
  PASS / 0 findings; `git diff --check` clean.
- **No executable product behaviour changed.** The only source file touched is
  `static/navigation_ui.js`, comment- and non-rendered-string-only, verified
  line by line.

## 5. New risks / notes forward

- **Architecture frozen ≠ feature delivered.** The navigation feature stays
  `in_progress` with `accessibility_closure` pending; guard 2 now fails if any
  source or the feature registry claims otherwise.
- **`M5` is the critical path.** Every collection job type is still
  `target_mode="none"`; plane-wide-then-filter must never be recorded as
  device-targeted.
- **The local enrollment permission is conditioned on the loopback binding
  itself.** `M14` does not retroactively validate it; server exposure is a
  separate open decision.
- **SQLite is local-only.** `pcp_storage_engine` stays open; a future registry
  backend migration must prove semantic parity and is a governed movement.
- The benchmark's NOT ESTABLISHED rows should be re-run by a session on an
  unrestricted network.
