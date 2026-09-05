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
  **unmerged**, `origin/main` (`310593e`) + 2 commits, **no pull request**.
  `origin/main` is an ancestor of HEAD; no divergence, nothing rewritten.
  (The container's initial clone stored a stale shallow ref, which is why the
  first `git fetch` reported a "forced update" — not a rewritten `main`.)
- Build: `pcp_2_local_control_plane_sequencing_po_review` — **`in_progress`**,
  a Product Owner architecture review producing **DRAFTS ONLY**.
- **`NAV.1` is not frozen, not merge-approved, and does not replace the roadmap
  movement.** The earlier self-declared `FROZEN` contract and the
  `automated_validated` build record were written without Product Owner
  authority and are withdrawn/corrected on this branch.

## 2. What changed this session

- **`docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md`** — rewritten:
  **DRAFT — PRODUCT OWNER REVIEW REQUIRED**. Adds a challenge review of the
  prototype (what to keep / what is shell detail / what was pre-decided / what
  breaks under SQLite, enrollment, schedules, recovery, RBAC), a **three-layer
  IA**, the **four-predicate** capability model that replaces the DOM-only
  rule, a **capability-state presentation matrix** over existing canonical
  states, the **logical-entity-first workspace**, a **preservation matrix**,
  shell-parity, accessibility and security sections, and **`PO-NAV-1…8`**.
  `D-NAV1…D-NAV9` re-graded (ACCEPTED DIRECTION / PROVISIONAL / OPEN);
  `D-NAV10…D-NAV14` added.
- **`docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md`** (new, DRAFT)
  — runtime lifecycle, background typed jobs, device-targeted execution, the
  **SQLite decision matrix (A/B/C) recommending A**, the required future SQLite
  contract, credential/trust references, **UI-first enrollment**, the
  `pcp_console_registry_write_gate` analysis, schedules ownership, the
  **`M1`…`M14` movement sequence**, and **proposed-but-unapplied** amendments
  to frozen `CON.0` §4 and `PCP.0` §8/§9/§19/§20.
- **`docs/design/research/NSPM_NAVIGATION_BENCHMARK.md`** (new) — Tufin,
  AlgoSec, FireMon, BackBox, Opinnate (+ SmartConsole/Panorama secondary), with
  per-claim evidence grades, source links and access dates, and the five
  **PO-supplied screenshots** recorded as the primary observed evidence.
- **Comment-only / documentation corrections** in `static/navigation_ui.js`
  (FROZEN → DRAFT; D-NAV6 downgraded to a last-mile check; the `add_device`
  reason string no longer pre-decides the open write gate — that entry is never
  rendered, so no behaviour changed) and in
  `tests/test_navigation_information_architecture.py` (docstrings; the frozen
  assertion became a **DRAFT** assertion; two new guards that nothing calls the
  contract frozen).
- **State**: `project/build_history.json` head record **amended in place** (no
  duplicate) to the review movement, `status: in_progress`;
  `project/roadmap.json` `now`/`next` corrected; `project/feature_registry.json`
  nav feature → `in_progress` with honest criterion states;
  `CURRENT_STATE.md`; `docs/history/INDEX.md` regenerated.

**Runtime behaviour is unchanged.** The prototype at `5a5a1f7` is preserved and
still runs identically.

## 3. Exact next action

1. **Product Owner reviews the two DRAFTs** and answers `PO-NAV-1…8`, the
   storage sequencing option (A/B/C) and the movement order. Nothing downstream
   is authorized until then.
2. The one build that needs **no** PO decision is `now_next.next`:
   `pcp1_registry_uuid_call_count_test_defect_repair` (movement `M1`) — the two
   `PCP.1` registry tests that fail deterministically on `main`.
3. Do **not**: open a PR, merge, freeze any document, implement SQLite,
   enrollment, jobs, collectors, schedules or RBAC, or start any `M2`…`M14`
   movement.

## 4. Test delta

- **The full suite is not green and is not claimed to be.** The same two
  `PCP.1` registry failures remain on `main` and on this branch, untouched:
  `test_duplicate_enroll_refused_before_device_id_generated` and
  `test_lock_contention_on_enroll_never_generates_a_device_id`.
- Targeted evidence for this documentation/state movement:
  `tests/test_navigation_information_architecture.py` **20 passed** (18
  prototype AC checks unchanged + 2 new DRAFT/authority guards; four drive a
  real Chromium), `tests/test_architecture_convergence.py` green, both render
  harnesses green, repository privacy gate PASS / 0 findings,
  `metadata_warnings == []`, build-history index clean, `git diff --check` clean.

## 5. New risks / notes forward

- **The prototype must not be read as an approved architecture.** Six roots,
  the availability rule, Jobs' placement and the enrollment location are all
  PROVISIONAL or OPEN.
- **`M5` is the critical path.** Every collection job type today is
  `target_mode="none"` (plane-wide). Per-device collection cannot be offered
  honestly until one collector gains a target-selection seam — and a plane-wide
  run must never be relabelled as a device-targeted job.
- **Most of the requested runtime already exists.** `--console` is already a
  long-running, authenticated, job-executing local service with durable records
  and crash reconciliation. The gap is targeting, registry-keyed targets,
  enrollment and schedules — not a new control plane.
- **`pcp_console_registry_write_gate` option (b) needs a `CON.0` §4
  amendment** (manual enrollment necessarily transmits an endpoint from the
  browser). Proposed, not applied.
- **Four accessibility gaps** must close before any freeze: icon-only
  accessible names, `prefers-reduced-motion`, focus management, group semantics.
- **R-1**: if local loopback enrollment is approved, that permission must not
  survive into server mode — `CON.6` re-asks the question.
- No device contact, no real-environment claim, no production authorization.
