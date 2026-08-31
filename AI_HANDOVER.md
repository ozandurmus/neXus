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
  IMPLEMENTED (real-env owed + a D4 PAN credential follow-up owed — §3b);
  `RB.3a` AUTOMATED_VALIDATED. **`RB.3b` — prep REVIEWED AND SIGNED OFF
  2026-08-31. No longer blocked. Next step is implementation.** `RB.3c` blocked
  (`D5` + `E1`).
- This session did **ARCHITECTURE / decision + DOCS** work only: a decision
  walk-through of the five RB.3b prep artifacts with the product owner / security
  lead / network-security leads, then recorded the sign-off outcomes in the
  design docs and project metadata. No code, no device call, no test-logic
  change.
- The RB.3b unblocking prep was already merged to `main` (`7f5a023`, merge
  `d67ea52`). This session's sign-off edits are **uncommitted working-tree
  changes on `main`** (10 files, all docs / metadata). Corporate Git
  commit/push remains **human-controlled** (standing priority 4) — not done.
- `origin/main` HEAD unchanged this session (`e0799fc` = the RB.3a merge line;
  `d67ea52` = the RB.3b unblocking-prep merge per `CURRENT_STATE.md`).
- Test baseline unchanged and **not re-run** (docs-only change): last full-suite
  evidence `804 passed / 20 skipped / 2 failed` (the two documented pre-existing
  test-order-pollution failures). Privacy gate not re-run — no tracked-source
  secret pattern was touched. `py -m pytest -q -k "project_plan or
  feature_registry or build_history or roadmap or metadata"` → **26 passed / 1
  skipped** after this session's metadata edits.
- Toolchain: `py` works directly; `json.load` needs `encoding="utf-8"` for
  `project/*.json` (they contain `§` / `—`).

## 2. What this session did

**RB.3b prep review + sign-off.** Walked the product owner / security lead /
network-security leads through the five prep artifacts and recorded the
decisions. All five cleared 2026-08-31:

1. **D4 — backup credential identity → SIGNED OFF (security lead).** Option A
   adopted as the target (a **distinct per-vendor backup service account**,
   `SECURITYEXPERT_CP_BACKUP_SSH_USERNAME` + `_PASSWORD_FILE` / `_PASSWORD`,
   **no fallback** to `SECURITYEXPERT_CP_CONFIG_SSH_*`, resolved in the CP
   collector constructor and failing the whole CP collection closed — zero
   device contact — when absent). Option C (DEV.2.2 read-only mounted-material
   custody) is the pilot mechanism; `DEPLOY.1` vault later (custody change only).
   Recorded: `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §13 `D4` row
   struck through to a resolved entry; `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`
   status header + a `> **D4 SIGNED OFF …**` blockquote.

2. **§7.3 point 14 — device-impact assessment → SIGNED OFF (product owner /
   network-security leads).** `> **GATE SIGNED OFF 2026-08-31 …**` blockquote
   added after `BACKUP_RECOVERY_CONTRACTS.md` §7.3.

3. **§7.7 (`/var/log` free-space read, `read`) + §7.8 (backup deletion,
   `operational-write`) → SIGNED OFF (product owner / network-security leads).**
   Status lines flipped from *PREPARED FOR GATE REVIEW* to *SIGNED OFF*;
   `> **GATE SIGNED OFF …**` blockquotes added; the §7 preamble sign-off
   paragraph rewritten and a new sign-off-state table added; §10.3 CP-collector
   row → *"blocked stub — implementation-cleared 2026-08-31"*.
   - **Estate confirmed R81.10 + R81.20 only.**
   - **Gaia command-string documentation check** (WebFetch/WebSearch against the
     R81 Gaia Administration Guide — a doc/estate check, not a device run):
     `show diskspace` is **not** in any published Gaia Clish command list
     (R80.20.M2 / R80.30 full lists, R81 Clish summary); the only documented
     `show disk*` form is `show disk-usage` on **SMB/Spark** (which §7.7 already
     gates `UNSUPPORTED`). R81 documents backup **deletion** as **Gaia
     Portal-only**; R80.30 lists `delete backup` with **no name argument**; no
     `delete backups` (plural) form is documented anywhere. The **Expert forms
     `df -P /var/log` and `rm -f -- /var/log/CPbackup/backups/<name>` are exact**
     and the `/var/log/CPbackup/backups/` path is confirmed (sk108902, R81.10 /
     R82 System Backup pages).
   - **Decision (product owner): keep the literal strings as written +
     confirm-on-hardware.** The exact-token check moves to the **first watched
     real R81.10 / R81.20 gateway run**; if a Clish form is absent there, the
     Expert form is the sole and primary form (still an explicit literal in the
     collector's frozen set, never a prefix-rule relaxation). This finding is
     recorded in the §7.7 / §7.8 sign-off blockquotes and the phase doc.
   - `SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB` **default 3072** accepted as an
     interim value, to be revisited at the first real-env measurement.

4. **Operational-write ledger design → DESIGN ACCEPTED (product owner).**
   `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`: the fail-closed-on-
   unreadable posture (§5) and evidence-plane placement (§2) approved **as
   written** — false-refusal-over-false-proceed understood. Status header +
   `> **DESIGN ACCEPTED …**` blockquote added.

5. **§3 rule 5 (C4) — version-locked CP class with no resolvable
   `software_version` not stored → ACCEPTED AS WRITTEN (product owner).** Inline
   note added to `BACKUP_RECOVERY_CONTRACTS.md` §3 rule 5.

**Files changed this session (all docs / metadata):**
`docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`,
`docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`,
`docs/design/BACKUP_RECOVERY_CONTRACTS.md` (§7 preamble + table, §7.3 blockquote,
§7.7 / §7.8 status + blockquotes, §3 rule 5, §10.3),
`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` (§13 D4 row),
`docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` (status, amendment
status, DoD item 1, next-movement),
`CURRENT_STATE.md`, `project/roadmap.json` (`now_next.next` → `planned`, goal
text; `upcoming` RB.3b notes), `project/backlog.json` (`native_backup`),
`project/build_history.json` (new `RB.3b-prep-signoff` entry), this file.

**C6** (contracts §9.12 CP-path test for a workflow with no scheduler entry)
remains the one outstanding amendment — folded into RB.3b implementation
(steps 5–6).

## 3. Next work

**RB.3b implementation** per the phase doc's implementation plan
(`docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` §"Implementation plan"):

- **Step 2** — `utils/recovery_operational_ledger.py` + tests (a fifth DEV.3.3
  evidence-backend concern; filesystem + Postgres; read inside the admission-held
  section; fail-closed on an unreadable ledger). Pure local state, no device.
- **Steps 3–4** — pilot allowlist (`SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES`,
  empty + fail-closed), platform gating (Spark / Gaia Embedded → `UNSUPPORTED`),
  VSX `__vsid_` rejection at request time, `software_version` resolution from
  existing evidence (refuse to store when unresolvable — §3 rule 5), and the
  §7.7 free-space read + parser against fixture output.
  → **`Sonnet 5, normal`** for steps 2–4 (offline, well specified).
- **Step 5** — the device-touching core: `add backup local`, SCP fetch into the
  encrypting writer (no plaintext temp file), digest verify, deletion (§7.8).
  → **`Sonnet 5, extended thinking`** (failure modes on a production firewall).
- **Step 6** — wire into `main.py`'s existing `checkpoint` branch; remove the
  blocked stub. **C6** (§9.12 CP-path test) lands here.
- The D4 credential guard (AC-11) and the §7.8 delete-only-the-name-we-made
  rule (AC-4) are correctness-contract items; AC-1…AC-14 are exercised against
  a **fixture SSH/SCP transport — never a live device in CI**.
- **Command-string confirm-on-hardware:** the §7.7 / §7.8 literal Clish forms
  (`show diskspace`, `delete backup <name>`) are unverified against the public
  R81 docs; the collector must carry the Expert forms (`df -P /var/log`,
  `rm -f -- /var/log/CPbackup/backups/<name>`) as exact, and the first watched
  real R81.10 / R81.20 run decides whether a Clish form is usable on that build.

**Parallel local option (not blocked):** `codebase_modularization` +
`browser_render_boundary` from
`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`.

## 3a. Architecture planning carried forward

Unchanged: the local-only productization / modularization review in
`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md` is doable
locally, independently of RB.3b, with render / privacy / regression gates green
per extraction. It does **not** authorize a premature server/API rewrite.

## 3b. NOT YET DONE — real-environment / on-hardware validation

- **RB.3b — first real run is mandatory before `IMPLEMENTED` → anything
  further.** A single, named, non-production-critical R81.10 / R81.20 gateway,
  watched, `/var/log` free space observed before and after, deletion confirmed.
  This run also resolves the §7.7 / §7.8 command-string confirm-on-hardware
  question.
- **RB.2 PAN credential follow-up (D4 consequence):** device-state export must
  authenticate with a **distinct PAN service account**, not the inventory API
  key (`panorama_runtime_runner.get_api_key`), before `RB.2` advances past
  `IMPLEMENTED`. Code change owed on `panorama/panorama_recovery_collector.py`.
  Recorded in `project/backlog.json` `on_hardware_real_env_validation` +
  `native_backup`; design in `D4` doc §8. Not a blocker on RB.3b.
- Carried forward unchanged: `RB.3a` real Gaia `--recovery-attest` run; `RB.2`
  real firewall run; `DEV.3.2` / `DEV.3.3` multi-container runs; everything else
  under `on_hardware_real_env_validation`.

## 4. Open risks / debt carried forward

- **The §7.7 / §7.8 literal Clish command strings are in the contract but
  unverified against the public R81 Gaia Administration Guide.** The doc check
  this session found `show diskspace` in no Gaia Clish command list and R81
  documenting Portal-only backup deletion; the Expert forms are the solid ones.
  Sign-off is explicitly conditional on the first watched real-gateway run
  confirming (or replacing) the Clish forms.
- **The operational-write ledger is new correctness-critical state.** Its
  fail-closed-on-unreadable posture produces a false refusal (missed backup,
  recoverable) before a false proceed (double disk write) — the correct
  direction, but operators must understand "couldn't read the ledger" blocks
  the backup.
- **`D4` for PAN retro-fits `RB.2`.** Recorded, not silently absorbed.
- Unchanged from prior handovers: mixed-backend fleet; `D1`, `D5`–`D7` open;
  `D6` (adopt `operational-write` into `AI_DEVELOPMENT_PROTOCOL.md`) still open;
  the clean privacy-gate re-run owed on a tree without gitignored `data/` /
  `logs/`.

## 5. Exact next action

**Start a fresh chat.** Read `AI_START_HERE.md` → `CURRENT_STATE.md` → this
file. Then:

- **RB.3b implementation** (the actual next step): phase doc implementation plan,
  steps 2–4 at `Sonnet 5, normal`, step 5 at `Sonnet 5, extended thinking`,
  step 6 + C6 with step 5. All device interaction against fixture transports;
  first real R81.10 / R81.20 gateway run owed before `IMPLEMENTED` advances.
- **or** the modularization work in §3a.
- **or** the RB.2 PAN credential follow-up (`Sonnet 5, normal`, real-env owed).

## 6. main merge decision + Git dispatch

- The RB.3b unblocking prep is already on `main` (`d67ea52`). This session's
  sign-off outcomes are **uncommitted doc/metadata edits on `main`** (10 files;
  see §2). They record: the `D2`/`D3`-style struck-through resolution for `D4`
  in architecture §13; the §7.5-style `> **GATE SIGNED OFF 2026-08-31 …**`
  blockquotes for §7.3 point 14 / §7.7 / §7.8; the `> **DESIGN ACCEPTED …**`
  blockquote for the ledger; the `> **D4 SIGNED OFF …**` blockquote in the D4
  brief.
- Corporate Git commit/push remains **human-controlled** (standing priority 4)
  — **not done this session.** Suggested commit when the human is ready:

  ```
  git add -A
  git commit -m "docs(recovery): RB.3b prep sign-off — D4, §7.3 p14, §7.7/§7.8 gates, operational-write ledger, §3 rule 5"
  git push origin main
  ```

## 7. Next movement / model

- **RB.3b implementation:** `Sonnet 5, normal` for the ledger, gating and
  precondition parser (steps 2–4 — offline, well specified); `Sonnet 5, extended
  thinking` for step 5 only (the device-touching core, on a real `/var/log`).
- **RB.2 PAN credential follow-up:** `Sonnet 5, normal` — a bounded collector
  change against a fixture transport, real-env owed.
- **Modularization (§3a):** `Sonnet 5, extended thinking` for the seam plan;
  `Sonnet 5, normal` per mechanical extraction.

## 8. Continue or fresh chat

**Fresh chat.** This session's outcome is fully captured in the design docs and
metadata. Nothing in this chat's context is needed to start RB.3b
implementation — the phase doc carries the plan and the acceptance criteria.

## 9. main.py / UI effect

**None.** No `main.py`, `templates/`, `static/` or payload-builder change. No
CLI flag added, no runtime behavior changed. A normal `py .\main.py` checkpoint
run looks and behaves exactly as before. `SECURITYEXPERT_CP_BACKUP_SSH_*`,
`SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB`, `SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES`
and `utils/recovery_operational_ledger.py` are **designed and signed off, not
implemented** — they do nothing until RB.3b is built.
