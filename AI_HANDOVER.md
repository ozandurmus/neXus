# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Date: 2026-08-31.
- Product baseline: `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
  Unchanged this session — this was a docs/contract-only session, no source
  touched.
- Engineering baseline: `DEV.1` complete; `DEV.2.1`, `DEV.2.2`, `DEV.3.1`,
  `DEV.3.2`, `DEV.3.3` all AUTOMATED_VALIDATED. Unchanged this session.
- **`origin/main` is at `0c0491a`. Working tree clean, nothing pending.**
  Merged and pushed this session (§2). `feature/rb-3-contracts` is fully merged
  and may be deleted whenever the user wants — no action needed unless asked.
- **No test suite run this session** — nothing in `checkpoint/`, `configuration/`,
  `panorama/`, `utils/`, `main.py`, `templates/`, or `static/` changed. Only
  `docs/design/*.md`, `docs/history/phase/RB_3*.md`, `CURRENT_STATE.md`, and
  `project/backlog.json` changed. Last known full-suite baseline (from the
  DEV.3.3 session, unaffected by this one): **788 passed / 3 skipped / 2
  failed** with a live PostgreSQL, **763 / 11 / 2** without. The 2 failures are
  documented pre-existing/unrelated ones.
- Repository privacy gate: **PASS / 0**, re-verified on the merged `main` tree
  this session (365 files scanned) after deleting gitignored `data/`/`logs/`.
- **Toolchain note:** this session ran on the user's own Windows box, not the
  cloud sandbox — `py -V:3.12` at
  `%LOCALAPPDATA%\Programs\Python\Python312\python.exe` used directly for the
  privacy-gate re-verification. `node` is installed but not on `PATH` via
  `which` (a quoting artifact in one PATH entry); reach it at
  `"/c/Program Files/nodejs/node.exe"` if a future session needs it (used this
  session only for an unrelated claude-mem worker-port lookup, not for
  anything in this repo).

## 2. What this session did

**RB.3 contract preparation, not implementation.** Read the seed prompt
(`docs/history/handover/RB3_NEXT_CHAT_PROMPT.md`, now superseded), the two
design docs, the existing recovery-plane code (`recovery_collect.py`,
`restore_readiness.py`, the CP stub, the PAN collector, `recovery_manifest.py`,
`collection_executor.py`, `main.py`'s recovery flags), and `AGENTS.md`'s
command-gate section. Wrote three phase-doc contracts, split by network-device
command gate class (this split is itself new — the seed prompt treated RB.3 as
one build):

- **`docs/history/phase/RB_3A_CP_GAIA_BACKUP_ATTESTATION.md`** — `show backups`
  / `show snapshots`, class `read`. **CONTRACT FROZEN — §7.5 gate SIGNED OFF by
  the product owner this session.** Cleared for implementation, not yet
  implemented. Populates the `attestations` argument
  `utils/restore_readiness.py` already accepts and nothing currently supplies.
- **`docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md`** — `add backup
  local` + SCP fetch, class `operational-write`. **`D3` RESOLVED this session
  — approved by the product owner, scoped to a named pilot set** (an
  allowlist, empty and fail-closed by default; scheduling explicitly not
  approved). Still blocked on `D4` (credential identity) and two gate entries
  (§7.7, §7.8) that §7.3's own points 12/13 require but §7 never wrote —
  drafted, not signed off, with two literal Gaia command strings marked owed at
  review rather than guessed.
- **`docs/history/phase/RB_3C_CP_MANAGEMENT_EXPORT_CONSISTENCY_GROUPS.md`** —
  `migrate_server export` / `mds_backup` + consistency groups. Blocked on `D5`
  and a **new open decision `E1`** this contract raised: §7.6 inherits
  `operational-write` from §7.3 without verification; if the commands degrade
  Multi-Domain Server / management processes, that classification is wrong and
  the contract needs re-cutting.

Two design-doc decisions were put to the product owner mid-session and
recorded durably (not left in chat):

- `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §13 — `D3` row struck
  through and resolved, same style as the existing resolved `D2` entry.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.5 — gained points 5–7 (frozen
  command tuple, platform gating, per-physical-endpoint rule) plus a signed-off
  note.

`CURRENT_STATE.md` and `project/backlog.json` (`native_backup`) were rewritten
to carry the full state, including the four findings below, so a fresh session
does not need this transcript.

### Findings surfaced while writing the contracts — read before touching RB.3b

- **§7.3 point 6 ("1 per 24 h, hard-enforced by the admission coordinator") is
  not currently true.** `utils.collection_executor.CollectionCoordinator` is
  process-local and in-memory — it stops two *concurrent* backups, not a
  second one minutes later, and a restart forgets. RB.3b design decision B4:
  needs a durable per-endpoint ledger on the DEV.3.3 evidence backend,
  fail-closed on an unreadable ledger (never fail-open to "back it up again").
  This is RB.3b's largest new engineering surface.
- **`software_version: "unknown"` is correct for PAN, wrong for CP.** A Gaia
  backup is version-locked (R81.10 does not restore onto R81.20). RB.3b design
  decision B8: if the collector can't resolve a real version from existing
  evidence, it must **refuse to store the artifact** — not store it as
  `"unknown"` and leave it permanently un-promotable past V2 while looking like
  a valid artifact.
- **§7.3's own points 12/13 (free-space precondition, cleanup-on-failure) have
  no gate entries.** §7 never wrote them. Drafted as new §7.7 (`/var/log`
  free-space read, `read` class) and §7.8 (backup deletion,
  `operational-write`) in the RB.3b contract. §7.8 point 12 is load-bearing:
  deletion targets only the exact name the same run's own `add backup local`
  returned — never a pattern, never a name read back from a listing.
- **§7.6's `operational-write` classification for the management commands is
  unverified (`E1`, new).** It was inherited from §7.3 by shape-similarity, not
  confirmed against vendor documentation or the estate's MDS version. RB.3c is
  written explicitly as disposable if `E1` answers "disruption is unavoidable".

## 3. Next work

**RB.3a is cleared and is the recommended next build** — the gate is signed
off, nothing else blocks it. `docs/history/phase/RB_3A_CP_GAIA_BACKUP_ATTESTATION.md`
has the full implementation plan (§"Implementation plan") and 10 acceptance
criteria. One module (`checkpoint/checkpoint_recovery_attestation.py`), one new
orchestration entry point (`run_recovery_attestation` alongside the existing
`run_recovery_collection`), a new evidence-plane state file, one `main.py`
flag. No device-write risk — `read` class, no artifact leaves any device.

Everything else in RB.3 stays blocked:

- **RB.3b** — put `D4` (backup credential identity: must a separate service
  account be provisioned, or is there a decision to make about it?) to the
  security lead, and get §7.3 point 14 / §7.7 / §7.8 gate-reviewed — including
  confirming the two literal Gaia command strings (free-space read form,
  deletion command form) against the R81 Gaia Administration Guide and the
  estate's actual Gaia release mix. Do not start implementation before that.
- **RB.3c** — answer `E1` (is `mds_backup`/`migrate_server export`
  service-disrupting?) and `D5` (recovery-volume storage budget) before
  freezing. Sequence after RB.3b has had its first watched real-environment run
  — the gateway case should prove the `operational-write` machinery before the
  management servers are the ones testing it.
- **CE.2** (`compliance_check_engine_primitives`) — still needs its own
  contract, unrelated to RB.3.
- Standing doable-now items unchanged from the prior handover:
  `immutable_store_permission` (P1), `html_render_performance` (P2),
  `inventory_exclusions_ui` / `overview_device_lifecycle_enrichment` (P1 UI).

## 3a. Architecture planning carried forward

A local-only productization and modularization review is now recorded in
`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`, with
tracked backlog, feature-registry, roadmap and history entries. It preserves
the current one-worker/static-report model; it does **not** authorize a
premature server/API/platform rewrite.

Doable locally, independently of RB.3a: remove the dormant remote-cleanup
helper, harden the browser rendering boundary, and extract responsibility-owned
frontend/workflow/collector modules while preserving behavior and passing
render, privacy and regression gates. Server-only gates are OIDC/RBAC, strict
CP/PAN trust, report-only publication storage, non-root restricted containers,
reviewed migrations and roles, release assurance, and off-host recovery key
custody plus a restore drill. These are preconditions for production reliance,
not reasons to start RB.3b.

## 3b. NOT YET DONE - real-environment / on-hardware validation

No change this session — nothing was implemented, so nothing new is owed.
Carried forward unchanged from the DEV.3.3 handover:

- `DEV.3.3` multi-container run; `DEV.3.2` real multi-container deployment.
- `RB.2` (PAN device-state export) implemented, never run against a real
  firewall.
- Everything under `on_hardware_real_env_validation` in `project/backlog.json`.
- **New, forward-looking:** whenever RB.3a is implemented, its real-environment
  validation needs an actual Check Point gateway with `show backups`/
  `show snapshots` output to confirm the parser against a real Gaia release —
  the contract explicitly flags parser format drift as the top risk.

## 4. Open risks / debt carried forward

Unchanged from the DEV.3.3 handover (mixed-backend fleet, the two DEV.3
knobs not implying each other, no backfill on backend switch, `D1`/`D4`–`D7`
still open, `D3` **now resolved and removed from this list**, PAN
`software_version` unresolved, `DEV.3.2` lock-key persistence dependency,
regex-linter best-effort posture, test-run repo-dir writes) — plus this
session's four findings in §2 above, which are now the load-bearing debt for
anyone opening RB.3b or RB.3c.

## 5. Exact next action

**Start a fresh chat.** Read `AI_START_HERE.md` → this file →
`CURRENT_STATE.md`, then `docs/history/phase/RB_3A_CP_GAIA_BACKUP_ATTESTATION.md`
directly (it is self-contained; no need to re-read the two design docs in full
— the contract already extracted what implementation needs). Open RB.3a as an
`IMPLEMENTATION` build. `docs/history/handover/RB3_NEXT_CHAT_PROMPT.md` is
superseded — do not seed from it again.

## 6. main merge decision + Git dispatch

- **Merged and pushed this session, at the user's explicit request** ("pull
  and merge"). `git fetch origin` first (local `main` was already even with
  `origin/main` — nothing to pull). `--no-ff` merge of
  `feature/rb-3-contracts` (`ebc66d0`) into `main`, no conflicts
  (`0c0491a`). Privacy gate re-verified PASS/0 on the merged tree before
  pushing (gitignored `data/`/`logs/` deleted first). No test suite run —
  docs-only change.
- `feature/rb-3-contracts` is fully contained in `main`; safe to delete
  locally and on `origin` whenever the user wants, no action taken.
- Next builds: branch off `main`, commit, `git merge --no-ff` + `git push
  origin main`, or `gh pr create --fill --base main` → `gh pr merge --merge`.
  Human-initiated per standing priority 4 — confirmed again this session (the
  merge itself waited for an explicit instruction before it ran).

## 7. Next movement / model

- **RB.3a implementation: `IMPLEMENTATION` at `Sonnet 5, normal`.** The
  orchestration, target selection, admission routing and readiness consumer
  all already exist; the new surface is one module with two frozen commands
  and a bounded parser. The contract's design decisions (A1–A10) already made
  every judgement call except parser tolerance, which A6 resolves in the
  fail-closed direction. Extended thinking would be more than this needs.
- **RB.3b's `D4` conversation + §7.3/§7.7/§7.8 gate write-up:** `Sonnet 5,
  normal` is enough for the write-up; the actual device-touching
  implementation step (once gated) is the one place in RB.3b that earns
  `Sonnet 5, extended thinking` — a production firewall's `/var/log`, not a
  test fixture, is where a mistake shows up.
- **RB.3c:** do not open yet. When `E1`/`D5` are answered, `ARCHITECTURE` at
  `Sonnet 5, extended thinking` to re-cut §7.6 against whatever `E1` decides.

## 8. Continue or fresh chat

**Fresh chat**, per §5. RB.3a is a clean, scoped implementation with its own
frozen contract; nothing in this session's remaining context (design-doc
authoring, the D3/§7.5 negotiation) is needed to build it — the contract
already carries what implementation needs, by design.

## 9. main.py / UI effect

**None.** This session changed only `docs/design/*.md`,
`docs/history/phase/RB_3*.md`, `CURRENT_STATE.md`, and `project/backlog.json`.
No payload builder, `templates/`, `static/app.js`, or `static/style.css` was
touched, and no `main.py` flag exists yet for anything in RB.3 — `--recovery-attest`
is contracted, not implemented. A normal `py .\main.py` checkpoint run today
looks and behaves exactly as it did before this session.
