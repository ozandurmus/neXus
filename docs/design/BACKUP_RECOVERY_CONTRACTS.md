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
5. `software_version` is mandatory. Tightened by amendment **C4** (RB.3b prep,
   2026-08-31):
   - **Version-locked CP classes** (`cp_gaia_backup`, `cp_mgmt_export`,
     `cp_mds_backup`): if the exact software version cannot be resolved from
     **existing evidence** (`unified.json` / the configuration evidence store —
     `configuration/checkpoint_config_collector._parse_gaia_version` already
     produces it), the collector **refuses to store the artifact**. No new
     device command is issued to obtain a version. A Gaia backup is
     version-locked (architecture §3.1) — a backup with no recorded version is
     not a degraded record, it is an unrestorable file that would sit at V2
     forever (RB.4 V3 treats an `"unknown"` version as `NOT_APPLICABLE`, not
     `FAIL`) while presenting in every view as a valid recovery artifact.
     Rationale: RB.3b decision B8.
   - **PAN classes** (`pan_device_state`, `pan_running_config`): `unified.json`
     carries no PAN version field and no device command is invented to fetch
     one, so the honest `"unknown"` sentinel is retained, the artifact **is**
     stored, and readiness is `UNKNOWN` by §5 until a version is available.
     Unchanged.
   - Consequence: a stored artifact whose `software_version` is `"unknown"` is
     therefore only ever a PAN artifact. A CP artifact either carries its real
     version or was never stored.

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
5. **`attested_not_held[].age_days` is nullable.** (Amendment C1, RB.3a,
   2026-08-31.) `null` means "the device reported an artifact whose date did
   not parse into an unambiguous UTC calendar date" (RB.3a decision A6) and is
   distinct from the key being **absent**. Readiness classification tests only
   the *presence* of an attestation, never its age, so `null` changes no
   `state`. `held_artifacts[].age_days` is unaffected and stays non-null.

The RB.3a attestation producer (`show backups` / `show snapshots`, §7.5) is a
`RecoveryAttester`, not a `RecoveryCollector` — see §10.3. It writes
`attested_not_held` entries via `data/state/recovery_attestations.json`
(`securityexpert-recovery-attestations-v1`: `{schema, generated_at,
attestations: {entity_id: [{class, age_days, source}, ...]}}`), which
`--restore-readiness-check` reads and passes straight into
`compute_restore_readiness(attestations=)`. No backup/snapshot **name** ever
appears in that file (decision A5).

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

Sign-off state: **§7.1 / §7.2** (PAN) documented, `D2` resolved; **§7.5**
(CP attestation, `read`) **SIGNED OFF 2026-08-31**; **§7.3 / §7.4** (CP backup,
`operational-write` / `read`) — `D3` resolved pilot-scoped, points 1–13 drafted,
**point 14 written 2026-08-31 (RB.3b prep), awaiting sign-off**; **§7.7 / §7.8**
(CP free-space read / backup deletion) **PREPARED FOR GATE REVIEW 2026-08-31
(RB.3b prep) — not signed off**, two literal Gaia command strings carried with
an explicit "confirm exact token at sign-off" marker; **§7.6** (CP management
export) blocked on `D5` + `E1`.

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
6. **Max frequency per endpoint:** 1 per 24 h. Enforced from **durable
   per-endpoint state**, not from the in-memory admission coordinator — amended
   by **C3** (RB.3b prep, 2026-08-31). `CollectionCoordinator` is process-local
   and does not survive a restart: it prevents a *concurrent* second backup, not
   one ten minutes later. The durable record is `utils/recovery_operational_ledger.py`
   on the DEV.3.3 evidence backend — see
   `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`. The ledger is read
   **inside** the admission-held section, before any device contact; an endpoint
   inside its window is skipped with zero SSH, and the entry is written once,
   after `add backup local` is sent. An **unreadable** ledger fails closed —
   the backup does not run (§9.13, RB.3b B4). A legitimately **absent** ledger
   (no prior execution) is not an error and the backup proceeds.
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
14. **Device-impact assessment:** written 2026-08-31 (RB.3b prep); **awaiting
    sign-off**. `add backup local` invokes the Gaia backup subsystem, which
    reads configuration and OS-parameter files and writes one compressed archive
    under `/var/log/CPbackup/backups/`. It does **not** restart a process, and
    does not touch the security policy, routing table, interfaces, SIC,
    clustering or credentials; it holds no global lock, so a concurrent policy
    install or operator session is unaffected. Measurable load is disk I/O plus
    transient CPU for compression, for the backup's duration (bounded at 900 s
    by point 4). No HA/failover interaction: a backup on the active member does
    not trigger failover and is not synced to the standby. The only failure mode
    that reaches the data plane is `/var/log` exhaustion, fully covered by
    point 12 (3× free-space precondition, abort-on-unknown) and point 13
    (cleanup, including on fetch failure). Assessed as consistent with the
    `operational-write` class (§5, architecture §5): bounded, reversible,
    non-configuration resource consumption. Residual risk: a gateway whose
    `/var/log` fills from its own logging between the precondition read and
    backup completion — not eliminable off-device, bounded by the 24 h ceiling
    (point 6) and single-archive cleanup. **Superseded gate note:** the P0
    `cp_device_interaction_safety` audit this point originally deferred to
    closed 2026-08-25; this assessment stands in its place and is what the gate
    signs off.

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
5. **Frozen command set:** exactly `("show backups", "show snapshots")`. The
   implementing module carries this as a literal tuple, **not** a `show `
   prefix test — a prefix test is how an ungated command arrives in a later
   edit unnoticed. Widening the tuple is a visible diff that re-trips this gate.
6. **Platform gating:** Spark / Gaia Embedded is `UNSUPPORTED` and receives no
   command. The determination comes from the discovery-lifecycle platform
   classification, **never** from whether the device landed directly in Clish
   (`AGENTS.md`).
7. **Per physical endpoint only.** A VSX virtual-system entity
   (`<device>__vsid_<vs_id>`) is never contacted and is never credited with its
   host's attestation — a Gaia snapshot of a VSX host is not a per-virtual-system
   recovery artifact.

> **GATE SIGNED OFF 2026-08-31 (product owner).** Points 1-7 approved as
> written. `RB.3a` is cleared for implementation and is **not** gated on `D3`.
> Contract: `docs/history/phase/RB_3A_CP_GAIA_BACKUP_ATTESTATION.md`.

### 7.6 `migrate_server export` / `mds_backup` (CP management) — class: `operational-write`

Same shape as 7.3 with larger bounds (timeout 3600 s, frequency 1 per 24 h) and
one additional constraint: **Management HA consistency.** Check Point requires
backups collected from all management servers at the same time; these run as a
**consistency group** (§2 `groups/`), and a group with any failed member is
marked `INCONSISTENT` and is **not** counted as readiness evidence.

### 7.7 `/var/log` free-space read (CP Gaia) — class: `read`

**Status: PREPARED FOR GATE REVIEW (RB.3b prep, 2026-08-31). Not signed off.**
Added by amendment **C1**; supersedes the draft in
`docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` §B2.

1. **Why required:** §7.3 point 12's free-space precondition cannot be satisfied
   without it. Architecture §10 rule 8 — an `operational-write` runs only after
   its precondition passes, never optimistically.
2. **Read/write:** `read` — reports filesystem utilisation; changes nothing on
   the device.
3. **Vendor/platform/shell/context:** Check Point Gaia.
   - **Literal command, primary form (Clish):** `show diskspace`
   - **Literal command, fallback form (Expert):** `df -P /var/log`
   `show diskspace` is primary because it needs no Expert shell; `df -P
   /var/log` is used only where `show diskspace` is absent or its output does
   not parse on a given Gaia release. **The exact form per release is confirmed
   at sign-off** against the R81 Gaia Administration Guide and the estate's
   actual Gaia mix — not assumed here. Not valid inside a VSX virtual-system
   context (as §7.3 point 3).
   - If the Expert `df -P /var/log` form is adopted for any release it becomes
     the **second** literal non-`show` exception in the CP read vocabulary,
     alongside `cpstat os -f hw_info`. It is added as an explicit literal string
     to the RB.3b collector's own frozen command set (or
     `configuration/checkpoint_config_probe.EXPERT_READ_ONLY_COMMANDS`),
     **never** as a relaxation of the `show `/`clish -c 'show …'` prefix rule
     (RB.3b B1).
4. **Timeout:** 30 s.
5. **Retry:** 1 retry (transport error only).
6. **Max frequency per endpoint:** once immediately before each §7.3 attempt,
   plus ad-hoc operator use. Not ledger-tracked — it is a `read`.
7. **Existing session reuse:** the same SSH session as §7.3 / §7.4 / §7.8 — one
   session does precondition, backup, fetch and cleanup. No new login; the
   §7.3 backup identity (`D4`) carries it, no separate credential.
8. **Unsupported behavior:** if neither form returns a parseable free-space
   figure for the filesystem backing `/var/log` (its own mount, or `/` when
   `/var/log` is not separately mounted), the result is `UNKNOWN` and §7.3
   point 12 **aborts the backup**. An unparseable or absent disk reading is
   never treated as "probably fine". Spark / Gaia Embedded: `UNSUPPORTED`, no
   command sent — platform from the discovery-lifecycle classification, never
   from shell behaviour (`AGENTS.md`).
9. **Secret-bearing output risk:** none. Filesystem names, block counts and
   mount points only.
10. **Safe telemetry:** free bytes, total bytes, used percent, partition/mount
    name. No file listing, no path contents.
11. **Resource consumed:** none (read).
12. **Free-space threshold:** free space on the `/var/log` filesystem ≥ **3×**
    the largest prior `cp_gaia_backup` for this `entity_id` (read from the
    recovery store's manifests). With no prior backup the floor is
    `SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB` (**default 3072**, hard floor 1024) —
    the default is a proposal for sign-off, to be reviewed against the estate's
    real backup sizes at the first real-environment run.
13. **Parser contract:** bounded and fail-closed. It selects the row for the
    mount backing `/var/log`; if `/var/log` is not its own mount it uses `/`;
    if it can identify neither, `UNKNOWN` (→ abort). It never infers a figure
    from a partial or truncated line.

### 7.8 backup-file deletion (CP Gaia) — class: `operational-write`

**Status: PREPARED FOR GATE REVIEW (RB.3b prep, 2026-08-31). Not signed off.**
Added by amendment **C2**; supersedes the draft in
`docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` §B3.

1. **Why required:** §7.3 point 13's cleanup contract. Without it every backup
   run leaves a multi-MB archive on the firewall and the platform becomes the
   disk-consumption problem the gate exists to prevent.
2. **Read/write:** `operational-write` — removes an artifact this platform
   created, in the same session that created it. Consumes no resource; releases
   disk.
3. **Vendor/platform/shell/context:** Check Point Gaia, Clish.
   - **Literal command, primary form (Clish):** `delete backup <name>`, where
     `<name>` is the exact archive name returned by the `add backup local` in
     §7.3 of the **same session**.
   - **Literal command, fallback form (Expert):**
     `rm -f -- /var/log/CPbackup/backups/<name>` — POSIX `--` end-of-options
     guard, one literal path, **no glob**.
   The exact Clish token (`delete backup` vs `delete backups`, and whether a
   `file` keyword is required) is **confirmed at sign-off** against the R81 Gaia
   Administration Guide for each Gaia release in the estate; the collector
   carries whichever single literal form the review fixes, per release. Not
   valid inside a VSX virtual-system context.
4. **Timeout:** 60 s.
5. **Retry:** **1 retry** — unlike §7.3, retrying a *delete* is strictly safer
   than not retrying.
6. **Max frequency per endpoint:** bounded by §7.3's own 1-per-24 h ceiling — a
   deletion only ever follows a backup in the same session. Not separately
   ledger-tracked.
7. **Existing session reuse:** the same SSH session as §7.3 — the session that
   created the archive is the session that deletes it. If that session is lost
   before cleanup, see point 12.
8. **Unsupported behavior:** same platform gating as §7.3 (Spark / Gaia
   Embedded → `UNSUPPORTED`). If neither literal form is available for a Gaia
   release, §7.3 is not cleared for that release either — a backup with no gated
   cleanup path is not run.
9. **Secret-bearing output risk:** the archive **name** is an operational
   identity (it embeds the hostname and a timestamp) — redacted in every log
   line per architecture §10 rule 6. The command produces only a status line;
   no payload.
10. **Safe telemetry:** status; `/var/log` free space after deletion.
11. **Resource consumed:** none — it **releases** disk.
12. **Precondition — the load-bearing rule:** the target name is the archive
    this run created, held in memory from §7.3's own output. **Never a pattern,
    never a wildcard, never a name obtained by listing (`show backups`), never a
    name supplied by config or CLI.** A deletion driven by a listing or a
    pattern could remove an operator's own backup. If the run cannot produce the
    exact name it created (e.g. the SSH session dropped after `add backup local`
    and before cleanup), it does **not** fall back to a discovery-based delete:
    it reports `CLEANUP_FAILED` loudly and marks the endpoint ineligible for
    further backup until an operator clears it (RB.3b correctness contract
    item 3; AC-3 / AC-4).
13. **Cleanup contract:** n/a — this *is* the cleanup.
14. **Device-impact assessment:** removes one file under
    `/var/log/CPbackup/backups/`. Touches no configuration, process, policy,
    routing or clustering state; the only device effect is freeing disk.
    Reversibility: the file is this run's own transient artifact; "reversal"
    would be re-running the backup. No HA interaction. Assessed as within the
    `operational-write` class (§5) — a bounded, non-configuration change that
    only releases a resource. Reviewed together with §7.3 point 14, same
    sign-off.

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
| 9.13 | `operational-write` 24 h ceiling is enforced from durable state; an unreadable ledger blocks the run | `RB.3` | 2nd run inside the window ⇒ zero device contact; corrupt/unreachable ledger ⇒ abort, no command sent; **absent** ledger ⇒ proceed; filesystem and Postgres backends decide identically; ledger read+write occur inside the admission-held section; entry written iff `add backup local` was sent (amendment C3; `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §10) |

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
| `checkpoint` (CP Gaia backup *collection*, §7.3) | **blocked stub** | `D3` **resolved 2026-08-31** (pilot-scoped, fail-closed allowlist). Still blocked on: `D4` (backup credential identity — decision brief `docs/design/D4_BACKUP_CREDENTIAL_IDENTITY_DECISION.md`, recommended, awaiting security-lead sign-off), §7.3 point 14 (written, awaiting sign-off), §7.7 / §7.8 gate sign-off (two literal Gaia strings owed at review), and the durable operational-write ledger (`docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`). The P0 `cp_device_interaction_safety` audit closed 2026-08-25 and is **not** a blocker. |
| `checkpoint` attestation (CP Gaia `show backups` / `show snapshots`, §7.5) | **implemented** (RB.3a, 2026-08-31) | none — `read` class, command gate signed off 2026-08-31. **Not** a `RecoveryCollector`: it is a `RecoveryAttester` (amendment C2). An attestation has no plaintext, and `run_recovery_collection` calls `write_artifact` unconditionally on every success — forcing an attestation through `collect() -> (bytes, meta)` would mean fabricating bytes or special-casing the vendor-neutral orchestrator on vendor behaviour (RB.3a decision A2). It has its own `run_recovery_attestation` entry point and writes nothing to the recovery store. |

Calling the CP *collector* raises `RecoveryCollectionBlockedError` naming the
exact blocker (`D3`), not a generic `NotImplementedError` — an
operator or a future UI must be able to show *why*, not just *that it
failed*. The CP *attester* is unrelated to that block: `show backups` /
`show snapshots` change no device state and are gated as `read` under §7.5.

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
  `INCONSISTENT` propagation; Spark/Embedded `UNSUPPORTED`. **RB.3b adds:** the
  durable-ledger battery 9.13 (`docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`
  §10); §3 rule 5 refusal — a version-locked CP class with no resolvable
  `software_version` is not stored (AC-10); the D4 credential guard — no backup
  identity ⇒ fail closed, no fallback (AC-11); §7.8 deletes only the exact name
  this run created (AC-4).
- **`RB.4`** — V1–V3 batteries incl. deliberately truncated and wrong-device
  artifacts (which must fail V3 while passing V1/V2 — the differentiator case);
  9.7, 9.8.
- **`RB.5`** — `recovery_ui` payload shape; `available: false` empty state;
  render harness green; `tests/fixtures/uitest/` extended per its README growth
  rule; 9.3.

Every phase additionally runs the full suite and the repository privacy gate,
per the standing close checklist.
