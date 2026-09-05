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
  **unmerged**, `origin/main` (`310593e`) + 3 commits, **no pull request**.
  `origin/main` is an ancestor of HEAD; no divergence, nothing rewritten.
  (The container's initial clone stored a stale shallow ref, which is why the
  first `git fetch` reported a "forced update" — not a rewritten `main`.)
- Build: `pcp_2_local_control_plane_sequencing_po_review` — **`in_progress`**,
  a Product Owner architecture review producing **DRAFTS ONLY**.
  **Review round 1 has been applied** (commit 3): the Product Owner
  substantially accepted both DRAFTs' direction, closed fourteen decisions, and
  forced one **material evidence correction**. Both documents remain
  **DRAFT — PRODUCT OWNER REVIEW REQUIRED**; neither is frozen.
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
  per-claim evidence grades, source links and access dates. *(Its revision 1
  screenshot attribution was withdrawn in the same session — see §2b.)*
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

### 2b. Review round 1, applied (commit 3)

- **Benchmark evidence correction — the material one.** Revision 1 of the
  research appendix attributed a set of Product-Owner-supplied screenshots to
  Tufin, AlgoSec, FireMon, BackBox and OPNsense under a `PO-SCREENSHOT` grade
  billed as its strongest evidence. Those screenshots were of the
  **current/previous neXus UI and its mock-data rendering**, supplied to
  identify behaviours to preserve. The attribution is **withdrawn in full**:
  the grade is gone, every observation derived solely from the images is
  **deleted rather than softened**, and three conclusions built on them are
  withdrawn with them (competitor corroboration for domain group headings;
  competitor "explicit third state" vocabularies as the basis for the
  capability-state matrix; a competitor Schedules/Jobs sibling split). The
  appendix is rebuilt on `PO-SOURCED-OFFICIAL` / `DOC-EXCERPT` /
  `URL-STRUCTURE` / `VENDOR-MARKETING` / `REPO-VERIFIED` / `NOT ESTABLISHED`
  and is **deliberately thinner**; four of five products now carry NOT
  ESTABLISHED navigation structure. Tufin's six menu names (Dashboard,
  Browsers, Reports, Map, Monitoring, Admin) are recorded as
  `PO-SOURCED-OFFICIAL`. The preservation evidence the screenshots were
  actually for is re-recorded as **REPO-VERIFIED** rows citing exact source
  symbols — stronger and re-checkable.
- **Fourteen decisions recorded** across both DRAFTs (`PO-NAV-1…8`, the
  enrollment write gate, first-contact trust, auto-enrollment, storage Option A,
  the movement order). Both files now separate *closed in direction* from
  *still open*, and no decided question remains in an open table.
- **Corrections returned rather than approved:** the pale yellow/gold member
  emphasis is **preserved** (with an explicit label and no warning iconography)
  instead of adopting Configuration's blue; the two proposed states stay **out**
  of the global canonical vocabulary and move to the schedule/capability-policy
  contract and the registry/evidence reconciliation projection with provisional
  names; Jobs loses its automatic root-promotion promise; `D-NAV13` now keeps a
  structurally inapplicable tab **visible and selectable** with a
  `NOT_APPLICABLE` explanation, omitting only on a product-surface (P1) failure;
  the four accessibility gaps block **implementation merge**, not architecture
  freeze; and revision 1's claim that a future registry backend migration would
  "not be a contract change" is **withdrawn** — it must prove semantic parity
  and is a governed storage movement.

## 3. Exact next action

1. **Product Owner reviews the corrected DRAFTs.** Round 1 closed direction;
   freezing the contracts is the remaining `M0` step. Nothing downstream is
   authorized until then.
2. The one build that needs **no** PO decision is `now_next.next`:
   `pcp1_registry_uuid_call_count_test_defect_repair` (movement `M1`). It runs
   in a **new session**, branching from **current `main`**, as a **clean narrow
   PR** — not from this branch. After it merges, this branch must incorporate
   the new `main` before any later validation or PR.
3. Do **not**: open a PR for this branch, merge, freeze any document, implement
   SQLite, enrollment, jobs, collectors, schedules, accessibility fixes or RBAC,
   or start any `M2`…`M14` movement.

## 4. Test delta

- **The full suite is not green and is not claimed to be.** The same two
  `PCP.1` registry failures remain on `main` and on this branch, untouched:
  `test_duplicate_enroll_refused_before_device_id_generated` and
  `test_lock_contention_on_enroll_never_generates_a_device_id`.
- Targeted evidence for this documentation/state movement:
  `tests/test_navigation_information_architecture.py` **20 passed** (18
  prototype AC checks unchanged + 2 DRAFT/authority guards; four drive a real
  Chromium), `tests/test_architecture_convergence.py` **20 passed** (document
  links, draft-vs-terminal consistency, `CURRENT_STATE`/roadmap agreement,
  build-history index), both render harnesses green, repository privacy gate
  PASS / 0 findings, `metadata_warnings == []`, `git diff --check` clean.
- **Runtime behaviour verified unchanged**: the diff from `ace9813` touches no
  executable JS, CSS, template or Python behaviour.

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
- **The approved enrollment direction is not approval to implement.** `M9`
  must implement all **seventeen** conditions in companion §9.1, and amendments
  `A1`/`A2` to `CON.0` §4/§7 must be applied first. They are recorded and
  **unapplied**.
- **Four accessibility gaps** (`M2`) block **implementation merge**, not
  architecture freeze: icon-only accessible names, `prefers-reduced-motion`,
  focus transfer to the activated workspace heading, labelled group semantics.
- **R-1 is now live**: the approved local-loopback permission is conditioned on
  the loopback binding itself and must not survive into server mode. `M14` does
  **not** retroactively validate any local shortcut.
- **The benchmark's NOT ESTABLISHED rows** should be re-run by a session on an
  unrestricted network; this environment can reach no vendor documentation host.
- No device contact, no real-environment claim, no production authorization.
