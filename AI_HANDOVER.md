# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-09. `UI2_0_ARCHITECTURE_REQUIREMENTS_DRAFT`
  (relay/NXS-LOCAL-0025) — produces
  `docs/design/UI2_0_ARCHITECTURE_REQUIREMENTS.md`, status
  `DRAFT — requirements and tradeoffs only, NOT a scope decision`.
- Own worktree/branch (`feature/ui2-0-architecture-requirements-draft`),
  standing `relay#13` merge authorization.
- Design-only: no code, no test, no `FROZEN` document changed, no live
  DB / LDAP / device call made.

## 2. What changed

- `docs/design/UI2_0_ARCHITECTURE_REQUIREMENTS.md` (new, DRAFT): the
  Product Owner's eleven functional points `F-1`…`F-11` as code-grounded
  requirements (what exists / what is new / what each depends on), a §2
  audit showing the static-export coupling is six `run_html_export` tail
  calls plus the eight payload builders while the registry / `M4` store /
  Postgres-capable evidence concerns / resolver chain are already DB-ready,
  four resolved sub-decisions `X-1`…`X-4` as recommendations (incremental;
  PostgreSQL; Python workers behind a queue-through-DB boundary; Java only
  for the new service layer if binding), one incremental path sketch (§7),
  six reported contradictions with frozen authority `C-1`…`C-6`, six
  unknowns `U-1`…`U-6`.
- `project/backlog.json`: new `ui2_0_architecture_requirements` item
  (`planned`, `P2`) pointing at the document. No other project-state file
  touched, per the movement's own AC-6.

## 3. Exact next action

1. Product Owner `DECIDE` episode on `C-1`…`C-4` and `U-1` (the exact Java
   requirement) — the document cannot be frozen while those stand.
2. Do **not** dispatch an implementation movement from this document.
   Freezing it would still not authorize one; the recommended first
   engineering step, if the Product Owner wants it, is an
   `ARCHITECTURE`/`CONTRACT` draft for §7 Stage A only (read API +
   projection schema over the existing evidence concerns).
3. Line-1 (`m7_real_device_targeted_collect_now`, next per
   `project/roadmap.json`) is untouched and unaffected.

## 4. Test delta

No test added or changed. Validation evidence for this movement is in the
`SESSION_CLOSE` packet on relay/NXS-LOCAL-0025 (full parallel regression,
`git diff --check`, repository privacy gate against the `origin/main`
baseline).

## 5. New risks

- The Java requirement's exact scope (`U-1`) is not in the repository; the
  `X-4` recommendation is conditional on it.
- `F-3` "role-based menu authorization" read as menu hiding contradicts
  frozen `AC-CS-33`; `F-9` backup-from-the-browser contradicts the class 1
  console refusal. Both are reported for decision, not resolved.
