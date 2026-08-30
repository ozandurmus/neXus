# Backup & Recovery — Frozen Contracts

**Status:** CONTRACT. Companion to `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md`
(read that first — it carries the reasoning; this file carries the shapes).
Nothing here is implemented. Freezing these before implementation is what lets
`RB.0`–`RB.5` be built as deterministic work against a fixed contract
(`Sonnet 5, normal` tier) rather than re-litigated design.

**Change rule:** a phase may *add* optional fields. Removing or repurposing a
field, or relaxing a §9 invariant, is a contract break and needs an explicit
rebase entry here, per `AGENTS.md` ("Do not silently rewrite historical
outcomes. Append/rebase explicitly.").

---

## 1. Scope

| Contract | Consumed by | Frozen for |
|---|---|---|
| §2 storage layout | `RB.1` | recovery-plane store |
| §3 `manifest.json` | `RB.1`–`RB.5` | every recovery artifact |
| §4 validation result | `RB.4` | V1–V4 verdicts |
| §5 readiness record | `RB.0`, `RB.5` | per-device readiness |
| §6 `recovery_ui` payload | `RB.5` | HTML module |
| §7 command gate entries | `RB.2`, `RB.3` | every new device command |
| §8 retention policy | `RB.1` | GFS + deletion ledger |
| §9 security invariants | all | automated test obligations |
| §10 collection command contract | `RB.2`, `RB.3` | orchestration, selection, scheduler |

---

## 2. Storage layout

Recovery root resolves from `SECURITYEXPERT_RECOVERY_ROOT`, mandatory and
absolute, validated by the same `utils/runtime_paths.py` separation rules that
already govern `runtime_root` — plus one more: **it must not be inside
`runtime_root` either.** Evidence and recovery are separate volumes (architecture
§4, `roadmap.json` DEPLOY.1 note).

```
<recovery_root>/
├── vault/<vendor>/<entity_id>/<utc_stamp>/
│   ├── artifact.enc
│   └── manifest.json
├── groups/<group_id>/manifest.json        # consistency groups (CP mgmt HA)
├── retention/ledger.json                  # append-only, tombstones
└── .vault_id                              # opaque store identity, no secrets
```

- `entity_id` follows the existing evidence-plane convention
  (`utils/config_evidence.safe_component`), and for CP VSX is
  `<physical_endpoint>/<vsid>` per `AGENTS.md`.
- `utc_stamp` is `YYYYMMDD_HHMMSS` UTC, matching `config_evidence.utc_stamp()`.
- `artifact.enc` is the encrypted vendor-native blob. **No plaintext artifact is
  ever written to disk**, including transiently — decrypt-to-memory or
  decrypt-to-`tmpfs` only.
- The wrapping key is **not** under `<recovery_root>` (§9.2).

---

## 3. `manifest.json`

The only recovery-plane object any other subsystem may read. Payload bytes and
secrets never appear here.

```json
{
  "schema": "securityexpert-recovery-manifest-v1",
  "artifact_id": "<sha256 of ciphertext>",
  "created_at": "2026-08-30T12:00:00Z",

  "device": {
    "vendor": "checkpoint | panorama",
    "entity_id": "<safe_component>",
    "physical_endpoint": "<safe_component|null>",
    "vsid": "<int|null>",
    "hostname_fingerprint": "<hmac, never the raw hostname>",
    "platform": "gaia | pan-os",
    "software_version": "R81.20 | 11.1.4",
    "ha_role": "active | standby | standalone | unknown"
  },

  "artifact": {
    "class": "pan_device_state | pan_running_config | cp_gaia_backup | cp_mgmt_export | cp_mds_backup",
    "is_rma_grade": true,
    "vendor_native_filename": "device_state_cfg.tgz",
    "plaintext_sha256": "<hex>",
    "plaintext_bytes": 1234567,
    "ciphertext_sha256": "<hex>",
    "ciphertext_bytes": 1234800,
    "compression": "gzip | none",
    "collected_via": "pan_xml_api_export | cp_ssh_scp_fetch",
    "collection_duration_ms": 8421
  },

  "crypto": {
    "scheme": "<agility id from crypto_agility_pqc>",
    "wrapped_data_key": "<base64 wrapped DEK; useless without the vault key>",
    "vault_key_id": "<opaque id>"
  },

  "validation": { "...": "see §4" },

  "restore_constraints": {
    "restores_to_same_version_only": true,
    "restores_to_same_appliance_only": false,
    "requires_superuser_to_apply": true,
    "known_gaps": ["hotfixes not included", "product binaries not included"]
  },

  "consistency_group": "<group_id|null>",
  "retention": { "policy": "gfs-default", "tier": "daily|weekly|monthly", "expires_at": "..." },

  "restore": null
}
```

Frozen rules:

1. **`restore` must be `null`.** The field is reserved so `RB.6` is an additive
   layer, never a rework (architecture §8, roadmap binding design consequence).
   The `RB.1` validator **rejects a manifest with a non-null `restore` block** —
   exactly the check-engine remediation-block pattern.
2. `hostname_fingerprint` is HMAC'd with the support-bundle identity key
   (`DEV.2.2`), never a raw hostname. A manifest is metadata, but it is metadata
   about a production firewall.
3. `is_rma_grade` is derived, not asserted: `true` only for `pan_device_state`,
   `cp_gaia_backup` (within version constraints) and the management exports.
   `pan_running_config` is **always `false`** — architecture §3.2.
4. `restore_constraints.known_gaps` is mandatory and non-empty where the vendor
   documents an exclusion (CP Gaia backup excludes OS/binaries/hotfixes).
   Silence here would read as "complete", which is the failure mode §1 of the
   architecture exists to prevent.
5. `software_version` is mandatory. A CP artifact without it cannot be
   version-matched and is `UNKNOWN` readiness by definition (§5).

---

## 4. Validation result

Embedded as `manifest.validation`. Levels per architecture §6.

```json
{
  "level": "V1 | V2 | V3 | V4",
  "verdict": "INTACT | WELL_FORMED | CONSISTENT | RESTORE_PROVEN | FAILED",
  "restore_proven": false,
  "checked_at": "2026-08-30T12:00:05Z",
  "checks": [
    { "id": "sha256_match",        "level": "V1", "result": "PASS", "detail": null },
    { "id": "size_band",           "level": "V1", "result": "PASS", "detail": null },
    { "id": "archive_openable",    "level": "V2", "result": "PASS", "detail": null },
    { "id": "expected_members",    "level": "V2", "result": "PASS", "detail": null },
    { "id": "xml_root_valid",      "level": "V2", "result": "NOT_APPLICABLE", "detail": null },
    { "id": "inventory_interface_count", "level": "V3", "result": "PASS", "detail": "16 == 16" },
    { "id": "inventory_vsys_count",      "level": "V3", "result": "PASS", "detail": "4 == 4" },
    { "id": "inventory_version_match",   "level": "V3", "result": "FAIL", "detail": "artifact R81.10 != inventory R81.20" }
  ],
  "restore_proof": null
}
```

Frozen rules:

1. `level` is the **highest level fully passed**; any `FAIL` caps it at the
   level below. A V3 failure does not invalidate V1/V2 — it is reported, not
   swallowed.
2. `restore_proven` is `false` unless `restore_proof` carries a V4 record
   (`{ proven_at, platform_class, operator, procedure_ref, result }`).
   **No code path may set `restore_proven: true` without a `restore_proof`.**
3. V3 checks compare against `unified.json` for the same device. When inventory
   for that device is absent or stale, the check is `NOT_APPLICABLE` with a
   reason — **never `PASS`**. This is the "explicit `UNKNOWN` over invented
   certainty" law applied to recovery.
4. Every `detail` string is value-free with respect to secrets and follows the
   existing redaction contract; counts and versions are safe, configuration
   content is not.

---

## 5. Readiness record (`RB.0`, no new device command)

One per device in `unified.json`. Written to
`<data_root>/state/restore_readiness.json` — this lives in the **evidence**
plane deliberately: it is derived metadata containing no recovery payload, and
the UI must be able to render it before any recovery volume exists.

```json
{
  "schema": "securityexpert-restore-readiness-v1",
  "generated_at": "2026-08-30T12:00:00Z",
  "devices": [
    {
      "entity_id": "<safe_component>",
      "vendor": "checkpoint",
      "state": "READY | STALE | PARTIAL | UNPROTECTED | UNKNOWN",
      "reason": "no_recovery_artifact_of_any_class",
      "held_artifacts": [
        { "class": "cp_gaia_backup", "age_days": 3, "validation_level": "V3",
          "version_matches_running": true }
      ],
      "attested_not_held": [
        { "class": "cp_gaia_snapshot", "age_days": 41, "source": "device_reported" }
      ],
      "missing_required": ["cp_mgmt_export"],
      "evidence_basis": "recovery_manifest | device_attestation | none"
    }
  ],
  "summary": { "READY": 0, "STALE": 0, "PARTIAL": 0, "UNPROTECTED": 0, "UNKNOWN": 0 }
}
```

Frozen rules:

1. `held_artifacts` and `attested_not_held` are **never merged**. Holding a
   backup and a device claiming to have one locally are different facts with
   different recovery value (architecture §7).
2. `state: READY` requires a held artifact at validation level ≥ V3 **and**
   `version_matches_running: true`. Anything weaker is at most `STALE`.
3. A device present in inventory with no recovery record is `UNPROTECTED`, not
   `UNKNOWN`. `UNKNOWN` is reserved for devices whose *inventory* is
   insufficient to judge — the two are operationally different and the
   distinction drives the §6 gap view.
4. `RB.0` computes this with zero network access and zero credentials, like
   `--repository-privacy-check`.

---

## 6. `recovery_ui` payload

Injected into the report as a sixth/seventh JSON payload, same mechanism as
`configuration_ui` / `crypto_ui` / `discovery_ui`.

```json
{
  "available": true,
  "generated_at": "...",
  "readiness_summary": { "READY": 12, "STALE": 3, "UNPROTECTED": 5, "PARTIAL": 1, "UNKNOWN": 0 },
  "coverage": { "devices_in_inventory": 21, "devices_with_any_artifact": 16, "coverage_percent": 76 },
  "devices": [ { "entity_id": "...", "state": "...", "artifacts": [ { "class": "...", "age_days": 3, "validation_level": "V3", "restore_proven": false } ] } ],
  "retention_pending_deletion": [ { "entity_id": "...", "artifact_id": "...", "expires_at": "..." } ]
}
```

Frozen rules:

1. **No payload bytes, no download URL, no decrypt affordance, ever.** The UI is
   a posture view. A "download backup" button in a browser-rendered report would
   defeat the entire §9 model.
2. `restore_proven` renders as an explicit badge; the word "verified" never
   appears unqualified (architecture §6).
3. `available: false` renders an explicit empty state — never a silent blank
   module, matching the existing Compliance/Discovery convention.
4. Adding this payload obliges `tests/fixtures/uitest/` growth and a green
   render harness per `AGENTS.md`.

---

## 7. Network-device command gate entries

Per `docs/AI_DEVELOPMENT_PROTOCOL.md` (10 points) plus points 11–14 for the
`operational-write` class introduced in architecture §5. **These are drafts for
gate review, not approvals.** No command here is implemented.

### 7.1 `GET /api/?type=export&category=device-state` (PAN) — class: `read`

1. **Why required:** the only PAN artifact that restores a firewall's full
   identity (certificates, LSVPN satellite auth) in an RMA. The configuration
   XML alone is not RMA-grade.
2. **Read/write:** `read` — streams a generated response; no durable on-device
   artifact.
3. **Vendor/platform/shell/context:** PAN-OS ≥ 7.1, HTTPS XML API, direct to
   firewall (not Panorama — Panorama's partial state lacks dynamic info).
4. **Timeout:** 300 s (large tgz; well above the existing API defaults).
5. **Retry:** 1 retry on transport error only; **never** on HTTP 403 (a
   privilege failure is a decision, not a transient).
6. **Max frequency per endpoint:** 1 per 24 h scheduled; 1 per hour manual ceiling.
7. **Existing session reuse:** reuses the existing API-key session
   (`panorama_runtime_runner.get_api_key`); no new auth transport.
8. **Unsupported behavior:** PAN-OS < 7.1 privilege semantics differ; treat as
   `UNSUPPORTED`, never silently fall back to configuration-only.
9. **Secret-bearing output risk:** **maximum.** The response *is* the secret
   material. Never logged, never buffered to disk in plaintext, never echoed.
10. **Safe telemetry:** byte count, duration, SHA-256, HTTP status. Never
    content, never filename with hostname unredacted.

> **Blocked on open decision D2** — requires superuser on PAN-OS 7.1+.

### 7.2 `GET /api/?type=export&category=configuration` (PAN) — class: `read`

As 7.1 with: timeout 120 s; **not RMA-grade** (`is_rma_grade: false` is frozen
in §3.3); secondary/companion artifact only. Same secret-bearing risk — a
running-config contains hashed credentials and PSKs.

### 7.3 `add backup local` (CP Gaia) — class: **`operational-write`**

1. **Why required:** the only supported Gaia system backup; produces the
   restorable configuration+OS-parameter archive.
2. **Read/write:** **`operational-write`** — writes a multi-MB archive to the
   device's `/var/log` partition. No configuration change.
3. **Vendor/platform/shell/context:** Check Point Gaia, Clish (`clish -c`) per
   `AGENTS.md`; not valid inside a VSX virtual-system context.
4. **Timeout:** 900 s (backup generation is slow on large gateways).
5. **Retry:** **none.** A retry risks a second concurrent backup and doubled
   disk consumption.
6. **Max frequency per endpoint:** 1 per 24 h, hard-enforced by the admission
   coordinator, not by convention.
7. **Existing session reuse:** reuses the established SSH session; no new login.
8. **Unsupported behavior:** Spark / Gaia Embedded — do **not** infer platform
   from direct-Clish behaviour (`AGENTS.md`); treat as `UNSUPPORTED`.
9. **Secret-bearing output risk:** the produced file is maximally
   secret-bearing; command *output* is only a job status line.
10. **Safe telemetry:** job status, duration, resulting size, free-space before
    and after.
11. **Resource consumed:** disk bytes on `/var/log`.
12. **Precondition:** free space on `/var/log` ≥ **3×** the largest prior backup
    for this device (or a conservative default on first run). **Abort, do not
    proceed, if unknown.** A full `/var/log` on a production gateway is an
    outage mode.
13. **Cleanup contract:** the on-device archive is deleted after a verified
    fetch (digest match). On fetch failure the archive is **still deleted** and
    the job reports failure — leaving orphaned multi-MB archives on firewalls is
    itself the resource risk this gate exists to prevent.
14. **Device-impact assessment:** **owed — blocked on the P0
    `cp_device_interaction_safety` audit and open decision D3.**

### 7.4 SCP fetch of the Gaia backup file — class: `read`

Read of a known path produced by 7.3; timeout 900 s; 1 retry; digest verified
against the device-reported size before deletion (7.3 point 13). Transfers
maximum-sensitivity bytes — TLS/SSH transport only, straight into the encrypting
writer, never to a plaintext temp file (§9.1).

### 7.5 `show backups` / `show snapshots` (CP Gaia) — class: `read`

1. **Why required:** the *attestation* path for architecture §7 — lets `RB.0`
   report "the device says it has a snapshot from 41 days ago" without pulling
   2.5 GB.
2. `read`; Clish; timeout 60 s; 1 retry; max 1 per hour; reuses session.
3. **Secret-bearing risk:** low — filenames and dates. Filenames may embed
   hostnames → redaction applies.
4. **This is the cheapest and safest command in the set and unblocks real
   `RB.0`/`RB.5` value on its own.** Worth gating first, independently of `RB.3`.

### 7.6 `migrate_server export` / `mds_backup` (CP management) — class: `operational-write`

Same shape as 7.3 with larger bounds (timeout 3600 s, frequency 1 per 24 h) and
one additional constraint: **Management HA consistency.** Check Point requires
backups collected from all management servers at the same time; these run as a
**consistency group** (§2 `groups/`), and a group with any failed member is
marked `INCONSISTENT` and is **not** counted as readiness evidence.

---

## 8. Retention

```json
{
  "schema": "securityexpert-recovery-retention-v1",
  "policy": "gfs-default",
  "daily": 7, "weekly": 4, "monthly": 6,
  "floor": { "never_reduce_device_below": 1, "never_delete_only_rma_grade": true },
  "deletion_requires_apply_flag": true
}
```

Frozen rules:

1. **The floor is absolute.** Retention may never drive a device to zero held
   artifacts, and may never delete the last `is_rma_grade: true` artifact even
   if newer non-RMA-grade ones exist. A policy that can produce an
   `UNPROTECTED` device is a bug, not a configuration choice.
2. Deletion is dry-run by default and requires `--apply`, mirroring
   `--storage-deduplicate` and the `docs/AI_DEVELOPMENT_PROTOCOL.md` approval
   boundary for destructive local-data operations.
3. Every deletion appends a tombstone to `retention/ledger.json`
   (`artifact_id`, `entity_id`, `deleted_at`, `policy`, `operator`). The ledger
   is append-only; a missing backup must always be distinguishable from a
   backup that was never taken.

---

## 9. Security invariants — automated test obligations

Each is a test that must exist before the corresponding phase closes.

| # | Invariant | Phase | Test shape |
|---|---|---|---|
| 9.1 | No plaintext recovery artifact is ever written under any root | `RB.1` | write a backup through the store; assert no plaintext bytes on disk, incl. temp paths |
| 9.2 | The wrapping key is not under `<recovery_root>` | `RB.1` | resolver rejects a key path inside the recovery root |
| 9.3 | Recovery payload never reaches `output/index.html` | `RB.5` | render with recovery data present; grep rendered HTML for artifact bytes/digests-of-payload |
| 9.4 | Recovery payload never reaches the support bundle | `RB.1` | build a bundle with a populated recovery root; assert exclusion |
| 9.5 | Privacy gate fails on recovery artifacts/keys in the repo tree | `RB.1` | extend `utils/repository_privacy.py`; `.enc`/vault paths join `*.pem`/`known_hosts` |
| 9.6 | `restore` block non-null ⇒ manifest rejected | `RB.1` | validator raises |
| 9.7 | `restore_proven: true` without `restore_proof` ⇒ rejected | `RB.4` | validator raises |
| 9.8 | V3 check with absent inventory ⇒ `NOT_APPLICABLE`, never `PASS` | `RB.4` | fixture with no matching inventory device |
| 9.9 | Retention floor cannot produce an `UNPROTECTED` device | `RB.1` | property test over policies |
| 9.10 | `operational-write` refuses when the precondition is unknown | `RB.3` | free-space probe returns `None` ⇒ abort, no command sent |
| 9.11 | nginx never mounts the recovery volume | `RB.1` | assert against committed compose files |
| 9.12 | Backup workflow is admission-coordinated, not a side channel | `RB.2` | assert the workflow is in `ALLOWLISTED_WORKFLOWS` and acquires the endpoint lock |

---

## 10. Recovery collection command contract (added 2026-08-30)

Architecture §9.1. Owns how `RB.2`/`RB.3` are actually invoked — CLI,
scheduler, and a future UI all call the same orchestration, per explicit
product direction (not a `main.py`-inlined operation).

### 10.1 `RecoveryCollectionRequest`

```json
{
  "vendor": "panorama | checkpoint",
  "selector": {"mode": "all"} ,
  "_or_": {"mode": "targets", "entity_ids": ["fw-01", "fw-02__vsid_10"]},
  "provenance": "manual | scheduled"
}
```

- `selector.mode: "all"` — every admitted device of `vendor` in `unified.json`.
- `selector.mode: "targets"` — an explicit `entity_id` list (the "selective
  for gateways" requirement). Entity identity follows the same
  `<device>__vsid_<vs_id>` convention as `restore_readiness` (§5) and
  `configuration/checkpoint_config_collector.py`.
- An `entity_id` in `selector.entity_ids` that does not resolve against
  `unified.json` is a **request-time error** — raised before any device is
  contacted, never a silent skip.

### 10.2 `RecoveryCollector` protocol

A vendor collector implements one method: `collect(target) -> (plaintext:
bytes, artifact_meta: dict)`, where `artifact_meta` supplies the
`artifact.class` / `vendor_native_filename` / `collected_via` fields §3
requires. `recovery_collect.py` never constructs vendor protocol/shell calls
itself — it only does target selection, admission-coordinator routing
(§9.12), and the encrypt-and-store call into `write_artifact`.

### 10.3 Collector availability

| Vendor | Status | Blocker |
|---|---|---|
| `panorama` (PAN device-state, §7.1) | **implemented** | `D2` resolved 2026-08-30; `read` class, gate-documented in §7.1 before implementation |
| `checkpoint` (CP Gaia backup, §7.3) | **blocked stub** | P0 `cp_device_interaction_safety` audit + open decision `D3` (architecture §13) — **neither resolved** |

Calling the CP collector raises `RecoveryCollectionBlockedError` naming the
exact blocker (audit + `D3`), not a generic `NotImplementedError` — an
operator or a future UI must be able to show *why*, not just *that it
failed*.

### 10.4 Scheduler policy schema (additive)

Extends `utils.collection_executor`'s scheduler policy
(`SCHEDULER_POLICY_SCHEMA_VERSION` unchanged — this is additive per the
`AGENTS.md` "phase may add optional fields" rule, not a version bump):

```json
{
  "version": 1,
  "enabled": true,
  "schedule": [
    {"workflow": "recovery-pan", "interval_minutes": 1440, "targets": ["fw-01", "fw-02"]},
    {"workflow": "recovery-pan", "interval_minutes": 1440}
  ]
}
```

- `"recovery-pan"` joins `ALLOWLISTED_WORKFLOWS`. `"recovery-cp"` does **not**
  — scheduling a blocked collector is refused at policy-load time, the same
  fail-closed posture `SchedulerPolicyError` already applies to an
  unallowlisted workflow name.
- `targets` (optional, per schedule entry): an explicit `entity_id` list.
  Omitted (the field does not exist) means `selector.mode: "all"` —
  indistinguishable from every scheduled entry that predates this contract,
  so no existing policy file changes meaning.
- Scheduling still routes through `collection_executor.execute_admitted_collection`
  (§9.12) — the per-endpoint lock and concurrency budget apply to a
  scheduled recovery job exactly as they do to `checkpoint`/`cp`/`vsx`/
  `pan-config` today. Architecture §9's correction: this does **not** wait on
  `distributed_endpoint_lock_and_job_store` under the current single-container
  deployment — the in-memory coordinator already serializes per endpoint.

---

## 11. Test contract per phase

- **`RB.0`** — readiness state machine over synthetic inventory fixtures
  (all five states, both evidence bases); zero network, zero credentials;
  `attested_not_held` never promotes to `READY`.
- **`RB.1`** — store round-trip; encryption invariants 9.1/9.2; manifest
  validator accept/reject incl. 9.6; retention floor 9.9; bundle/privacy/nginx
  exclusions 9.4/9.5/9.11.
- **`RB.2`** — PAN export against recorded fixtures (never a live device in CI);
  403-no-retry; `is_rma_grade` correctness per class; 9.12.
- **`RB.3`** — precondition abort 9.10; cleanup-on-failure; consistency-group
  `INCONSISTENT` propagation; Spark/Embedded `UNSUPPORTED`.
- **`RB.4`** — V1–V3 batteries incl. deliberately truncated and wrong-device
  artifacts (which must fail V3 while passing V1/V2 — the differentiator case);
  9.7, 9.8.
- **`RB.5`** — `recovery_ui` payload shape; `available: false` empty state;
  render harness green; `tests/fixtures/uitest/` extended per its README growth
  rule; 9.3.

Every phase additionally runs the full suite and the repository privacy gate,
per the standing close checklist.
