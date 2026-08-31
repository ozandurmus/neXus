# `CON.3` — Console `operational-write` actions (the Back-Up action)

## Status

**CONTRACT FROZEN 2026-08-31**, alongside `docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md`
(`CON.0`). This is the phase where a browser action can cause a write to a
production firewall, so it is the phase where nothing is assumed: every gate is
restated, and every gate is tested.

`project/backlog.json` `operator_console` (P1), roadmap track `CON.x`.

**Hard preconditions — all of them, no partial start:**

1. `CON.2` AUTOMATED_VALIDATED **and** its owed real-environment run performed.
2. **`RB.3b` at `REAL_ENV_VALIDATED`** — the watched real R81.10/R81.20
   single-gateway run in `docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md`
   has happened. A UI must never be the first thing to run a device write that
   has never been run by hand.
3. `D4` credential material provisioned
   (`SECURITYEXPERT_CP_BACKUP_SSH_USERNAME` + `_PASSWORD_FILE`).
4. `SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES` populated with the pilot set.
5. Decisions `C-D4` (per-request target ceiling) and `C-D6` (mandatory reason)
   resolved.

If any precondition is unmet, this phase does not start. `CON.2`'s `C2-6`
refusal is the product's correct behaviour until then, and it is a shippable
state — not a gap to work around.

## Objective

Let an operator select an allowlisted Check Point gateway in the console and
take a Gaia system backup, with a confirmation path proportionate to the fact
that it writes to the device's disk — and with the resulting evidence, ledger
entry and audit record indistinguishable from the CLI path, because it *is* the
CLI path.

This is the BackBox-parity moment for the CP pilot set. It is also the smallest
possible version of that moment: one device at a time, inside an allowlist that
is empty by default.

## Scope

### In scope

1. Lift `C2-6` for `command_class == "operational-write"`, replacing it with the
   preflight/confirm flow (`C3-2`).
2. `POST /api/jobs/preflight` — returns what would happen, and issues a
   single-use confirmation token.
3. `cp_gaia_backup` becomes executable through
   `--recovery-collect --recovery-vendor checkpoint --recovery-gateways <id>`.
4. Mandatory operator `reason`, redaction-filtered, stored in the job record and
   carried into the `operational-write` ledger entry.
5. UI: a visually distinct `operational-write` treatment, the preflight summary,
   typed confirmation, and honest per-device gate states.
6. `tests/test_con3_console_operational_write.py`.

### Explicitly out of scope

- **Any restore, decrypt, or download path.** `RB.6` is hard-gated and is not
  approached from here (`BACKUP_AND_RECOVERY_ARCHITECTURE.md` §8).
- **Any unattended or scheduled backup.** `"recovery-cp"` stays out of
  `ALLOWLISTED_WORKFLOWS`; `D3` explicitly did not approve scheduling.
- Bulk or "select all" backup. Fleet-wide operation is exactly what the
  allowlist exists to prevent (`C3-5`).
- Editing the allowlist, the credential, or any gate from the UI (`C3-4`).
- PAN `operational-write` of any kind — `RB.2`'s device-state export is `read`
  class and stays in `CON.2`'s registry.
- Any new device command. The command set is exactly what `RB.3b` froze.

## Design decisions

### `C3-1` — three independent gates, none of them trusted alone

| Layer | Refusal | Where |
|---|---|---|
| UI | a non-allowlisted device renders `BLOCKED — not in the pilot allowlist`; no action control exists on that row | `console_actions.js` |
| API | `POST /api/jobs` re-checks the allowlist, the credential preflight and the ledger before queuing | `console/app.py` |
| Collector | `checkpoint_recovery_collector` refuses as it does for the CLI (`cp_backup_credentials_unavailable`, allowlist, ledger) | `RB.3b`, unchanged |

The API check is not redundant with the collector check: it exists so the
refusal is *fast, auditable and explainable* without opening an SSH session, and
so a UI bug can never turn into a device contact. AC-2 asserts each layer
independently by disabling the other two.

### `C3-2` — preflight, then a single-use confirmation token

```
POST /api/jobs/preflight  { job_type, targets[] }
  → { summary, confirm_token, expires_at }
POST /api/jobs            { job_type, targets[], reason, confirm_token, Idempotency-Key }
```

The preflight `summary` states, per target: the device, the command class, what
the command writes, the ledger state (last recorded `operational-write` and its
age), the retention/disk consequence, and every gate's current verdict. The
`confirm_token` is single-use, TTL ≤ 120 s, and cryptographically bound to
`(job_type, sorted(targets), session token)`. A `POST /api/jobs` for an
`operational-write` without a valid, matching, unused token is `409`.

This makes "accidentally backed up a device by double-clicking" structurally
impossible rather than unlikely, and it makes the operator read the consequence
before accepting it.

### `C3-3` — the ledger is the authority, and a skip is a success

The console displays `SKIPPED` (not an error) when
`RecoveryCollectionSkipped` reports the 24 h ledger window, and `BLOCKED` when
`OperationalLedgerUnreadableError` fails closed. The UI never offers a "force"
or "run anyway" control — there is no such capability in the engine, and adding
one is out of scope for this track and any successor without its own decision.

### `C3-4` — the console never widens a gate

No route reads, writes, or reveals credential material, and none modifies
`SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES`. The allowlist is deployment
configuration; the console is a consumer of it. The preflight may report *that*
an entity is not allowlisted, never the allowlist's contents in full.

### `C3-5` — one target per request during the pilot (`C-D4`)

`POST /api/jobs` for an `operational-write` accepts exactly one `entity_id`.
Two devices means two deliberate confirmations. Revisit only with real pilot
evidence and a recorded decision — not because the UI would be more convenient.

### `C3-6` — mandatory reason, filtered before persistence (`C-D6`)

`reason` is required, minimum 8 characters, maximum 200, and passes through the
redaction registry before it is written anywhere. It lands in the job record and
in the ledger entry. It is excluded from the support bundle along with the rest
of the job record. Operator free text is the one place in this design where a
human can put a secret or an identity into durable storage by accident, so it is
filtered, bounded, and never shared.

### `C3-7` — the produced artifact is never reachable over HTTP

The console shows the manifest — artifact class, size, validation verdict,
retention — and never the bytes, a download link, or a decrypt path
(`CON.0` §7.6). Asserted by a route-table test, the same way `CON.1`'s AC-2
asserts method restrictions.

### `C3-8` — no automatic retry, ever

A failed `operational-write` is terminal. The operator may deliberately submit a
new job with a new idempotency key. `RB.3b`'s `B5` ("no retry, enforced
structurally") is not weakened by putting a UI in front of it.

## Privacy and safety invariants

1. All `CON.1`/`CON.2` invariants hold unchanged.
2. Backup bytes never enter a response body, a job record, a log line, or the
   report volume.
3. The preflight performs **zero device contact** — it reads the allowlist, the
   ledger and the credential preflight only. Asserted (AC-5).
4. Every executed `operational-write` produces: a job record, a `RunContext`
   manifest with `provenance="console"`, and a ledger entry. A run that produces
   fewer than all three is a defect, not a degraded success.

## Acceptance criteria

- **AC-1** An allowlisted target, with credentials present and an empty ledger
  window, completes end-to-end from the UI and produces artifact, manifest,
  ledger entry and job record — with `provenance="console"`.
- **AC-2** Each gate refuses independently: allowlist (UI/API/collector, tested
  by disabling the other two), credential absent
  (`cp_backup_credentials_unavailable`, zero device contact), ledger inside the
  window (`SKIPPED`, zero device contact), ledger unreadable (`BLOCKED`, zero
  device contact).
- **AC-3** `POST /api/jobs` for an `operational-write` without a `confirm_token`,
  with an expired one, with one bound to different targets, or with one already
  used, returns `409` and contacts no device.
- **AC-4** More than one target in an `operational-write` request returns `400`
  (`C3-5`).
- **AC-5** The preflight opens no SSH session — asserted by failing the transport
  layer and showing the preflight still returns a complete summary.
- **AC-6** A missing, too-short, or over-long `reason` returns `400`; a `reason`
  containing a registered sensitive value is redacted in every persisted copy.
- **AC-7** No route returns recovery payload bytes or a download URL; asserted by
  route-table enumeration plus a negative fetch against a known artifact id.
- **AC-8** A failed `operational-write` is terminal — no automatic retry occurs
  and the job record shows exactly one attempt.
- **AC-9** The exported static report is unchanged and still has no action
  surface; render harness green.
- **AC-10** Full suite at or above baseline; privacy gate `PASS / 0`.

## Implementation plan

1. Preflight endpoint + summary assembly + AC-5 (no device contact) first — it
   is the phase's safety foundation.
2. Confirm-token issue/verify/consume + AC-3.
3. Lift `C2-6` for `operational-write` behind the token; API-side gate re-checks
   + AC-2's API layer.
4. `reason` validation, redaction and persistence + AC-6.
5. Ledger/skip/block state mapping in the UI + AC-2's UI layer.
6. UI `operational-write` treatment and typed confirmation.
7. AC-7 route-table negative tests; AC-8; AC-9 render harness; full suite,
   privacy gate, project metadata.

## Validation and merge gate

Automated evidence is **not sufficient** for this phase. Merge to `main` may
proceed on a green suite, but the status stops at `IMPLEMENTED` until a
**watched real-environment run** on the corporate laptop, against one pilot
gateway, performed by the product owner or a network-security lead, produces a
real artifact, manifest and ledger entry from the console — with the CLI run of
the same target as the comparison. `AGENTS.md`: never mark a network-facing
behaviour `DONE` from automated tests alone.

## Risks

- **This is the first browser-initiated device write in the product's history.**
  The mitigation is not any single control but the fact that three of them are
  independent and each is tested with the others disabled (AC-2).
- **Confirmation fatigue.** If the preflight summary becomes boilerplate the
  operator clicks through, the control is theatre. Keep the summary short,
  specific, and different per device — the ledger age and gate verdicts are what
  make it worth reading.
- **Pressure to add bulk.** The first real pilot day will produce a request for
  "select all". That is a decision (`C-D4` revisit), not a UI improvement.
- **Scope creep toward restore.** A console that shows backups will attract "add
  a restore button". `RB.6` is hard-gated behind `OP.2`; nothing in this track
  approaches it.

## Rollback

Reverting to `CON.2`'s `C2-6` refusal restores a fully functional read-only
console with the capability declared and blocked. The engine-side capability
(`RB.3b`) is untouched by this phase and remains available from the CLI.

## Definition of done

AC-1…AC-10 pass; the watched real-environment run is recorded with its evidence;
`project/*` metadata, `CURRENT_STATE.md` and `AI_HANDOVER.md` updated; the
`RB.3b` phase doc gains a pointer noting the console is now a second, gated
trigger for the same capability.

## Next movement / model

Contract review and implementation at **`Sonnet 5, extended thinking (high)`** —
this is a security boundary and the first UI-initiated device write; it is one of
the few places in this track where the strongest reasoning tier is genuinely
warranted rather than merely available. Validation and the real-environment run
that follows are `Sonnet 5, normal`.
