# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Date: 2026-08-31.
- Product baseline: `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
  Unchanged this session.
- Engineering baseline: `DEV.1` complete; `DEV.2.1`, `DEV.2.2`, `DEV.3.1`,
  `DEV.3.2`, `DEV.3.3` all AUTOMATED_VALIDATED. Unchanged this session.
- Recovery track: `RB.0` / `RB.1` / `RB.4` AUTOMATED_VALIDATED; `RB.2`
  IMPLEMENTED (real-env owed, plus a new D4 credential follow-up owed — §2);
  `RB.3a` AUTOMATED_VALIDATED. **`RB.3b` still BLOCKED — but its five blockers
  now each have a reviewable artifact (§2). It stays blocked pending
  product-owner / security-lead SIGN-OFF, not pending design.** `RB.3c` blocked
  (`D5` + `E1`).
- This session did **CONTRACT / ARCHITECTURE** work only: no code, no device
  call, no test-logic change. Branch **`feature/rb-3b-gate-prep`** off `main`
  (`e0799fc`). **Not merged** — this branch is the review artifact.
- `main` / `origin/main` unchanged this session (`e0799fc` = the RB.3a merge).
- Test baseline unchanged and **not re-run** (docs-only change): last evidence
  `804 passed / 20 skipped / 2 failed` (the two documented pre-existing
  unrelated test-order-pollution failures). Privacy gate not re-run — no
  tracked-source secret pattern was touched. `tests -k "project_plan or
  feature_registry or build_history or roadmap or metadata"` → 37 passed after
  the metadata edits.
- Toolchain: `py` works directly (Python 3.14 default); `json.load` needs
  `encoding="utf-8"` for `project/*.json` (they contain `§` / `—`).

## 2. What this session did

**RB.3b unblocking prep — the five deliverables from the previous handover §3.**
`ARCHITECTURE` movement. All five now exist as reviewable artifacts:

1. **D4 — backup credential identity. Decision brief:**
   `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md` (new). Recommends
   **Option A** — a distinct per-vendor backup service account
   (`SECURITYEXPERT_CP_BACKUP_SSH_USERNAME` + `_PASSWORD_FILE` / `_PASSWORD`),
   **no fallback** to `SECURITYEXPERT_CP_CONFIG_SSH_*`; the CP collector
   resolves it in its constructor and fails the whole CP collection closed —
   zero device contact — when it is absent. Transport tunables (port, timeouts,
   strict-host-key) stay shared with the collection config; only principal +
   secret are distinct. Secret custody: DEV.2.2 read-only mounted-material for
   the pilot, `DEPLOY.1` vault later. Option B (reuse the collection identity
   elevated) rejected — it is the `D2` failure mode. **Awaiting security-lead
   sign-off.** Architecture §13 `D4` row, §10 rule 4 and §5 updated.
   **PAN follow-up recorded:** `RB.2` currently reuses the inventory API key;
   `D4` implies a distinct PAN service-account credential for device-state
   export before `RB.2` advances past `IMPLEMENTED`
   (`project/backlog.json` `on_hardware_real_env_validation` + `native_backup`).

2. **§7.3 point 14 — device-impact assessment.** Written into
   `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 14 (and §7.8 point 14).
   `add backup local` touches no config / process / policy / routing /
   clustering / HA state — disk I/O + transient CPU only; the sole data-plane
   failure mode is `/var/log` exhaustion, covered by points 12/13. The closed
   P0-audit dependency is superseded. **Awaiting sign-off.**

3. **§7.7 and §7.8 gate entries.** Landed in `BACKUP_RECOVERY_CONTRACTS.md` as
   *PREPARED FOR GATE REVIEW*, each with a **literal Gaia command string** and
   an explicit "confirm exact token at sign-off" marker:
   - **§7.7** `/var/log` free-space read, class `read`: `show diskspace` (Clish,
     primary) / `df -P /var/log` (Expert, fallback — an explicit second literal
     non-`show` exception, never a prefix relaxation). Unparseable → `UNKNOWN`
     → §7.3 point 12 aborts.
   - **§7.8** backup deletion, class `operational-write`: `delete backup <name>`
     (Clish) / `rm -f -- /var/log/CPbackup/backups/<name>` (Expert). **Deletes
     only the exact name this run created in the same session** — never a
     pattern, wildcard, listing-derived or config-supplied name; if the name is
     unavailable it reports `CLEANUP_FAILED` and marks the endpoint ineligible,
     never a discovery-based delete.
   §7 preamble gained a sign-off state table; §10.3 CP-collector blocker row
   refreshed.

4. **Durable per-endpoint operational-write ledger. Design:**
   `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` (new). Module
   `utils/recovery_operational_ledger.py` as a **fifth DEV.3.3 evidence-backend
   concern** (`OperationalWriteLedgerBackend` abstract + filesystem + Postgres
   impls + a `select_operational_write_ledger_backend` factory, reusing
   `_ensure_schema` / `_write_json_atomic` / `EvidenceBackendError`;
   `verify_evidence_backend_ready` gains a line). Filesystem:
   `<data_root>/state/recovery_operational_ledger.json`
   (`securityexpert-recovery-operational-ledger-v1`, append = read + append +
   atomic replace). Postgres: `recovery_operational_write_ledger`, **INSERT-only**,
   index `(entity_id, command_class, executed_at DESC)`. **Fail-closed:** absent
   ledger → proceed (first backup); readable + entry inside 24 h → skip with
   **zero device contact**; readable + stale → proceed; **unreadable / unreachable
   → `OperationalLedgerUnreadableError`, run BLOCKED, no command sent**. Read and
   write both occur **inside** the `run_under_admission` callable so two
   containers racing one endpoint are serialised by the per-endpoint lock.
   `record_execution` fires iff `add backup local` was actually sent. Contract
   §7.3 point 6 tightened (**C3**); new **§9.13** test obligation; architecture
   §9 coordination bullet added.

5. **Refuse to store a version-unknown CP artifact.** Contract §3 frozen rule 5
   tightened (**C4**): a version-locked CP class (`cp_gaia_backup`,
   `cp_mgmt_export`, `cp_mds_backup`) with no `software_version` resolvable from
   existing evidence (`unified.json` /
   `checkpoint_config_collector._parse_gaia_version`) is **not stored** — no new
   device command for version. PAN classes keep the honest `"unknown"` sentinel
   and are stored (`UNKNOWN` readiness by §5). A stored `"unknown"`-version
   artifact is therefore only ever PAN.

**Amendment status:** C1–C5 landed in the design docs; **C6** (contracts §9.12
for a workflow with no scheduler entry) deferred into RB.3b implementation,
where the CP-path §9.12 test is written.

**Files changed this session (all docs / metadata):**
`docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md` (new),
`docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` (new),
`docs/design/BACKUP_RECOVERY_CONTRACTS.md` (§3 r5, §7 preamble, §7.3 p6/p14,
§7.7 + §7.8 new, §9.13 new, §10.3),
`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` (§5, §9, §10 r4, §13 D4),
`docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` (status + amendments),
`CURRENT_STATE.md`, `project/roadmap.json`, `project/backlog.json`,
`project/build_history.json`, `project/feature_registry.json`, this file.

## 3. Next work

**`RB.3b` remains BLOCKED — the next action is not implementation, it is
review + sign-off.** Put the five prep artifacts to the product owner and the
security lead:

1. `D4` — approve/adjust `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`
   (Option A recommended). Security lead.
2. §7.3 point 14 + §7.7 + §7.8 — network-device command gate sign-off,
   **including confirming the two literal Gaia command strings** (`show
   diskspace` / `df -P /var/log`; `delete backup <name>` / `rm -f -- …`) against
   the R81 Gaia Administration Guide and the estate's Gaia mix. Product owner /
   network-security leads.
3. Confirm `SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB` default 3072 (§7.7 point 12)
   is acceptable pending first real-env measurement.

**Once signed off**, RB.3b implementation per the phase doc's implementation
plan: step 2 (`utils/recovery_operational_ledger.py` + tests) and steps 3–4
(pilot allowlist / platform gating / VSX rejection / `software_version`
resolution / free-space parser) at `Sonnet 5, normal`; **step 5 only** (the
device-touching core — `add backup local`, SCP fetch, digest verify, delete) at
`Sonnet 5, extended thinking`. C6 (§9.12 CP-path test) lands with step 5–6.

**Parallel local option (not blocked):** `codebase_modularization` +
`browser_render_boundary` from
`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`.

## 3a. Architecture planning carried forward

Unchanged from the prior handover: the local-only productization /
modularization review in
`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md` is
doable locally, independently of RB.3b, with render / privacy / regression
gates green per extraction. It does **not** authorize a premature server/API
rewrite.

## 3b. NOT YET DONE — real-environment / on-hardware validation

- **`RB.3b` prep — nothing to validate on hardware** (no code, no device call).
  The two literal Gaia command strings are the thing that needs a
  documentation/estate check at gate review, not a device run.
- **`RB.2` PAN credential follow-up (new, D4 consequence):** device-state
  export must authenticate with a distinct PAN service account, not the
  inventory API key, before `RB.2` advances past `IMPLEMENTED`. Code change owed
  on `panorama/panorama_recovery_collector.py`. In
  `on_hardware_real_env_validation`.
- Carried forward unchanged: `RB.3a` real Gaia `--recovery-attest` run;
  `RB.2` real firewall run; `DEV.3.2` / `DEV.3.3` multi-container runs;
  everything else under `on_hardware_real_env_validation`.

## 4. Open risks / debt carried forward

- **The two §7.7 / §7.8 literal command strings are committed to the contract
  with a confirm-at-sign-off marker.** House law is "no invented certainty" —
  `show diskspace` and `df -P` are high-confidence; the exact §7.8 Clish token
  (`delete backup` vs `delete backups`, `file` keyword) is the one to verify at
  review. The `rm` Expert fallback is exact.
- **The operational-write ledger is new correctness-critical state.** Its
  fail-closed-on-unreadable posture produces false refusals before it produces
  a double backup — the correct direction, but operators must understand a
  "couldn't read the ledger" outcome blocks the backup.
- **`D4` for PAN retro-fits `RB.2`.** Recorded, not silently absorbed.
- Unchanged from prior handovers: mixed-backend fleet; `D1`, `D5`–`D7` open;
  `D6` (adopt `operational-write` into `AI_DEVELOPMENT_PROTOCOL.md`) still open;
  the clean privacy-gate re-run owed on a tree without gitignored `data/` /
  `logs/`.

## 5. Exact next action

**Start a fresh chat.** Read `AI_START_HERE.md` → `CURRENT_STATE.md` → this
file. Then:

- **RB.3b review + sign-off** (the actual next step): walk the product owner /
  security lead through `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`,
  `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 14 / §7.7 / §7.8, and
  `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`. Movement: `ARCHITECTURE` /
  decision. No model call needed for the walk-through itself; recording the
  outcome is `DOCS`.
- **or** RB.3b implementation once signed off (see §3).
- **or** the modularization work in §3a.

## 6. main merge decision + Git dispatch

- **`main` merge: BLOCKED.** This branch (`feature/rb-3b-gate-prep`) is a
  review artifact for the RB.3b blockers, not a merge candidate on landing.
  Merge it to `main` only after the product owner / security lead have reviewed
  the five artifacts and the sign-off outcomes are recorded in the docs
  (the `D3`-style inline resolution for `D4`; the `§7.5`-style
  `> **GATE SIGNED OFF …**` blockquote for §7.3 point 14 / §7.7 / §7.8).
- Corporate Git push/merge remains **human-controlled** (standing priority 4).
- Exact non-interactive Git dispatch when review is done and outcomes recorded:

  ```
  git add -A
  git commit -m "docs(recovery): RB.3b unblocking prep — D4 brief, §7.7/§7.8 gate entries, operational-write ledger design, §3 rule 5 tightening"
  git push -u origin feature/rb-3b-gate-prep
  # after sign-off outcomes are committed on this branch:
  git checkout main && git merge --no-ff feature/rb-3b-gate-prep && git push origin main
  ```

## 7. Next movement / model

- **RB.3b review + sign-off:** `ARCHITECTURE` / decision — the walk-through
  needs no strong model; `Sonnet 5, normal` to record outcomes.
- **RB.3b implementation (post sign-off):** `Sonnet 5, normal` for the ledger,
  gating, precondition parser (offline, well specified); `Sonnet 5, extended
  thinking` for step 5 only (the device-touching core, on a real `/var/log`).
- **RB.2 PAN credential follow-up:** `Sonnet 5, normal` — a bounded collector
  change against a fixture transport, real-env owed.
- **Modularization (§3a):** `Sonnet 5, extended thinking` for the seam plan;
  `Sonnet 5, normal` per mechanical extraction.

## 8. Continue or fresh chat

**Fresh chat.** This session's prep is self-contained in the five artifacts and
the metadata. Nothing in this chat's context (which Gaia command forms were
weighed, the ledger fail-closed reasoning) is needed to run the review — it is
all in the docs.

## 9. main.py / UI effect

**None.** No `main.py`, `templates/`, `static/` or payload-builder change. No
CLI flag added, no runtime behavior changed. A normal `py .\main.py` checkpoint
run looks and behaves exactly as before. `SECURITYEXPERT_CP_BACKUP_SSH_*`,
`SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB` and `utils/recovery_operational_ledger.py`
are **designed, not implemented** — they do nothing until RB.3b is signed off
and built.
