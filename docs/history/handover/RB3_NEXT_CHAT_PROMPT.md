# Next-chat prompt — `RB.3` (CP Gaia backup) and the `§7.5` attestation slice

Paste the block below into a fresh chat. Everything it asserts was verified
against the repository on 2026-08-31, after DEV.3.3 merged to `main`
(`ae10bf7`). Written to be self-contained but not to replace the reading order
in `AI_START_HERE.md`.

---

## Prompt

```
Continue work on ozandurmus/neXus. `main` is at ae10bf7 — DEV.3.3
(distributed_evidence_store_migration) merged there, AUTOMATED_VALIDATED, no
build currently open. Read AI_START_HERE.md, then CURRENT_STATE.md and
AI_HANDOVER.md before anything else.

Objective: the RECOVER track's `RB.3` (Check Point Gaia backup collection).

Read first, in this order:
  1. docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md §7, §13 (decision D3)
  2. docs/design/BACKUP_RECOVERY_CONTRACTS.md §7.3, §7.4, §7.5 (the drafted
     command-gate entries) and §5 (the `operational-write` class)
  3. checkpoint/checkpoint_recovery_collector.py (the typed blocked stub)
  4. utils/recovery_collect.py (target selection, RecoveryCollector protocol,
     admission-coordinated execution — already built for CP by RB.2)
  5. utils/restore_readiness.py — note its `attestations` parameter, which
     nothing currently populates

State of play, verified — do not re-derive, and do not contradict without
checking the source first:

- `RB.3` proper (`add backup local`, contract §7.3) is **blocked on open
  decision D3 alone**: is `add backup local` acceptable at current maturity as
  the new `operational-write` command class, or must it wait for full
  write-capability maturity? That is a product-owner decision, not
  engineering. Its own network-device command gate entry is drafted at §7.3
  but **not approved** — points 1-13 are written, point 14
  (device-impact assessment) is explicitly owed and itself gated on D3.
- The P0 `cp_device_interaction_safety` audit **closed 2026-08-25**. Several
  documents wrongly cited it as an open co-blocker until a correction on
  2026-08-30. Do not reintroduce that claim. If you need its status, read
  project/backlog.json's `cp_device_interaction_safety` note directly rather
  than trusting any summary, including this one.
- Everything around the missing device call already exists: target selection
  (including the VSX `__vsid_` convention), the `RecoveryCollector` protocol,
  admission-coordinated batch execution where one gateway's failure does not
  abort the batch, the encrypted recovery store (RB.1) and the V1-V3
  validation battery (RB.4). `collection_executor.ALLOWLISTED_WORKFLOWS`
  deliberately contains `"recovery-pan"` and **not** `"recovery-cp"`, so a
  scheduled CP recovery workflow fails closed at policy-load time.

There is a real, unblocked slice — start here unless the user redirects:

  Contract §7.5 (`show backups` / `show snapshots`, class **`read`**) is the
  attestation path: it lets RB.0 report "the device says it has a snapshot
  from 41 days ago" without pulling 2.5 GB. The contract itself says, at §7.5
  point 4, that this is the cheapest and safest command in the set and is
  "worth gating first, independently of RB.3". It does **not** depend on D3.
  It is still a new CP command, so it needs its own network-device command
  gate sign-off (drafted at §7.5) before implementation — a much smaller ask
  than D3. `utils/restore_readiness.py` already accepts an `attestations`
  argument that nothing populates; this is what would populate it, turning
  RB.0's current "14 UNPROTECTED + 1 UNKNOWN" into evidence-backed states.

Suggested opening moves:
  1. Produce the SESSION START required by AGENTS.md (Turkish PROJE ÖZETİ
     first, then the English block).
  2. Put **D3** to the user as a decision, with the §7.3 device-impact
     analysis they need to answer it — specifically the `/var/log` free-space
     precondition (§7.3 point 12: abort if free space is unknown or below 3×
     the largest prior backup) and the cleanup contract (point 13: the
     on-device archive is deleted after a verified fetch, and still deleted
     on fetch failure). A full `/var/log` on a production gateway is an
     outage mode; that is the whole reason this is `operational-write`.
  3. In the same message, offer the §7.5 attestation slice as the work that
     can proceed now regardless of how D3 lands, and ask for its command-gate
     sign-off.
  4. Only after D3 is answered and the relevant gate is signed off, implement.
     Do not implement a device-touching CP command before then.

Constraints that apply to any implementation here:
  - No retry on `add backup local` (§7.3 point 5) — a retry risks a second
    concurrent backup and doubled disk consumption.
  - Max 1 per endpoint per 24 h, enforced by the admission coordinator, not
    by convention (point 6).
  - Not valid inside a VSX virtual-system context (point 3).
  - Spark / Gaia Embedded is `UNSUPPORTED`, and platform must not be inferred
    from direct-Clish behaviour (AGENTS.md).
  - Backup bytes never reach output/index.html, the support bundle, or
    repository metadata — only manifests may be read by other subsystems.

Movement + model: this opens as ARCHITECTURE/decision work, not coding.
**Sonnet 5, normal** is right for putting D3 to the user, the §7.5 gate
write-up, and the implementation once approved — the orchestration, store and
admission wiring already exist and are shared with RB.2, so only the device
call itself is new. Escalate to extended thinking only if the D3 answer opens
a genuinely new safety design question.

Test economy: one-shot, file-backed — `py -m pytest -q > pytest_result.log 2>&1`.
Current baseline on main: 788 passed / 3 skipped / 2 failed with a live
PostgreSQL available, 763 / 11 / 2 without one. The 2 failures are
pre-existing and unrelated (discovery-UI placeholder, compliance-trend
checkpoint record). Privacy gate: PASS / 0 — delete gitignored data/ and
logs/ before running it.
```

---

## Why this shape

`RB.3` cannot be opened as a normal implementation build: its blocker is a
decision only the product owner can make, and the repository has already been
burned once by handovers that restated a stale blocker instead of re-reading
the source. So this prompt (a) names D3 as the single real blocker and tells
the next session to verify it rather than trust the summary, (b) carries the
§7.3 facts needed to *answer* D3 rather than just to describe it, and (c)
points at the §7.5 `read`-class slice so the session has real work available
whichever way D3 goes.
