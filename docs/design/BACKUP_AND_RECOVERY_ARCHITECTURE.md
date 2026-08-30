# Backup & Recovery — Architecture (design, phased; `RB.0`–`RB.2`/`RB.4` landed)

**Status:** `RB.0`, `RB.1` and `RB.4` AUTOMATED_VALIDATED; `RB.2` (PAN device-state
export + collection orchestration) IMPLEMENTED, real-environment validation
owed. `RB.3` (CP Gaia backup) is a blocked stub — P0 `cp_device_interaction_safety`
audit and open decision `D3` are unresolved. `RB.6` (restore) is hard-gated at
the `OP.2` bar and is explicitly not buildable. See §12 for the current
per-phase status table.
**Rebases:** the deferred `original 0.6.0B · rebase required` milestone
(`project/roadmap.json`, feature `native_backup_foundation`). That rebase is
this document.
**Maps onto roadmap features:** `native_backup_foundation` (0.6.0B, deferred),
`native_backup_validation` (0.9.x), `restore_readiness` (0.9.x).
**Sibling designs:** `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` (the other
write-gated capability; same "assess first, ship the read-only half" shape),
`docs/design/COMPLIANCE_CHECK_ENGINE.md` (§ command gate + primitive registry).
**Driver:** BackBox is not being renewed in 2027 (§2).

---

## PROJE ÖZETİ (Türkçe)

- **İhtiyaç:** BackBox 2027'de yenilenmeyecek. BackBox bugün cihazların
  **yedeğini** alıyor. SecurityExpert ise bugün cihazların **kanıtını**
  topluyor. Bu ikisi aynı şey değil — ve bu farkı bilmemek, felaket anında
  "yedeğimiz vardı sanıyorduk" demeye yol açar.
- **Neden aynı değil:** Bugün topladığımız Check Point yapılandırması
  **kasıtlı olarak sansürlü** (`secrets_redacted: True`) — parolalar, ön-paylaşımlı
  anahtarlar, sertifikalar bilerek çıkarılıyor. Bu, analiz için doğru ve
  güvenli; ama o dosyayla bir cihazı **geri yükleyemezsiniz**. Gerçek yedek,
  tam ve sırları içinde barındıran bir dosyadır.
- **Bu görev nedir:** Ürüne ikinci ve ayrı bir "kurtarma düzlemi" tasarlamak:
  yedekler şifreli, ayrı bir depoda, rapora ve paylaşılan pakete **asla**
  girmeden saklanır; kanıt düzlemi olduğu gibi kalır.
- **Önce ne yapılır (RB.0, hemen yapılabilir):** Tek satır cihaz komutu
  eklemeden, elimizdeki envanterle "bu cihaz şu an ölse, elimizde ne var?"
  sorusunu cevaplayan **kurtarmaya hazırlık** raporu. Hiç yedek almadan bile
  değer üretir ve mevcut boşlukları görünür kılar.
- **Sonra:** PAN yedeği (RB.2), Check Point yedeği (RB.3), yedeğin
  **doğrulanması** (RB.4), hazırlık skoru ve arayüz (RB.5).
- **Geri yükleme (RB.6) bu tasarımın kapsamında değildir** — cihaza yazma
  demektir, en ağır güvenlik kapısına tabidir ve bilerek ileriye bırakılmıştır.
- **Tür:** Büyük özellik / mimari (RECOVER hattı).
- **Dürüst uyarı:** SecurityExpert yalnızca Check Point ve Palo Alto biliyor.
  BackBox estate'te başka marka cihazları da yedekliyorsa (F5, Cisco, Fortinet…),
  bu ürün onların yerini **almaz**. 2027 kararından önce BackBox'ın gerçekte
  neyi yedeklediğinin envanteri çıkarılmalıdır (§2).

---

## 1. Framing — evidence is not a backup

This is the single most important sentence in this document, and
`project/roadmap.json` already states it:

> "configuration evidence is not a recovery backup."

The platform today has two planes. This design adds a third, and the boundary
between plane 2 and plane 3 is a **security boundary**, not a storage detail.

| | Plane 1 — Inventory (`SEE`) | Plane 2 — Evidence (`VERIFY`) | Plane 3 — Recovery (`RECOVER`, new) |
|---|---|---|---|
| Artifact | `unified.json` | `gaia_show_configuration_redacted`, PAN `effective-running` | vendor-native backup blob |
| Fidelity | normalized model | normalized + **redacted** | **byte-exact, full fidelity** |
| Secrets | never | **deliberately stripped** | **necessarily present** |
| Purpose | topology, discovery | diff, alignment, compliance | **restore a dead device** |
| Readable by the report | yes | yes (projected) | **never — metadata only** |
| In the support bundle | sanitized | sanitized | **never, in any form** |
| Store | `data/` | CAS under `data_root` | **separate encrypted volume** |
| If it leaks | topology exposure | configuration exposure | **full device compromise** |

The evidence plane is non-restorable **by design**:
`configuration/checkpoint_config_collector.py` writes
`"redaction_contract": "secret-bearing lines withheld; full raw canonical
SHA256 retained only as change fingerprint"`. That is correct behaviour for
evidence and must not be weakened. It also means the current platform, today,
has **zero** recovery capability — and the Configuration module's existence
makes it easy for an operator to assume otherwise. Closing that perception gap
is `RB.0`'s job (§7) and is worth shipping even if no backup is ever collected.

**Design principle:** the recovery plane is write-once, encrypted, egress-denied,
and never merged into the evidence plane. Nothing in the report, the support
bundle, or any repository metadata ever carries recovery-plane payload bytes.

---

## 2. The BackBox exit (2027) — what actually has to be replaced

`docs/design/COMPLIANCE_CHECK_ENGINE.md` already cites BackBox as the
comparator for the check-engine model. Here it is the incumbent being removed,
so the gap analysis has to be blunt.

**What BackBox provides that this design replaces:**

| BackBox capability | Replaced by | Phase |
|---|---|---|
| Scheduled config backup, CP + PAN | vendor-native collection | `RB.2`/`RB.3` |
| Backup integrity / "verified backup" | validation battery | `RB.4` |
| Config change tracking + diff | **already shipped** (`config_history`, Configuration module) | done |
| Compliance / health checks | **already shipped** (`compliance_posture`, check engine) | done |
| Inventory | **already shipped** (`SEE` plane) | done |
| Retention / archive | retention + immutability model | `RB.1` |

**What BackBox provides that this design does NOT replace — decision-relevant:**

1. **Multi-vendor breadth.** SecurityExpert is Check Point + Palo Alto only, and
   `project/roadmap.json` `architecture_review_notes` explicitly freezes that:
   *"Vendor scope intentionally limited to Check Point and Palo Alto; no
   additional vendors will be added at current maturity."* If BackBox currently
   backs up F5, Cisco, Fortinet, switches, load balancers or anything else,
   **this product does not replace BackBox for those devices.** This is the
   largest risk in the 2027 decision and it is not an engineering problem — it
   is a scope decision the product owner must take explicitly.
2. **One-click restore / DR orchestration.** `RB.6`, hard-gated (§8). BackBox
   restores; SecurityExpert will not, for a long time, and possibly never
   without a dedicated safety programme.
3. **Syslog-triggered change detection** (backup on change). Adjacent to the
   deferred `event_signal_intake` backlog item; not in this design.
4. **Vendor support contract.** An internal tool has no vendor SLA. Losing that
   is a real operational cost, not a rounding error.

**Mandatory pre-decision action (not an engineering task):** produce the
inventory of *what BackBox actually backs up today*, by vendor and device count.
Until that exists, "SecurityExpert replaces BackBox" is an unverified claim.
This is recorded as open decision **D1** (§13).

**Timeline reality.** Today is 2026-08-30; non-renewal lands in 2027. Between
here and a credible CP+PAN backup capability sit: `DEPLOY.1` (server, still
gated on hardware), `DEV.3.2/3.3` (job store + distributed lock), the P0
`cp_device_interaction_safety` audit, two command-gate reviews, and
real-environment validation for each collector. That is not a comfortable
runway. §12 sequences it; the honest read is that `RB.0`+`RB.1`+`RB.2` (PAN,
the easier vendor) is a realistic 2027 target and `RB.3` (CP) is the schedule
risk.

---

## 3. Per-vendor analysis

Backup is not one operation. Each vendor offers several artifacts with
**different restore semantics**, and choosing wrongly produces a file that
looks like a backup and cannot restore anything.

### 3.1 Check Point

| Artifact | Contains | Restores to | Size | Command |
|---|---|---|---|---|
| **Gaia snapshot** | entire root partition, part of `/var/log`, OS, product binaries, hotfixes | **same machine only** (same appliance *type* since R77.10); can cross versions | **≥ 2.5 GB** | `add snapshot <name>` |
| **Gaia system backup** | CP configuration + networking/OS parameters (routing, interfaces) — **not** OS, **not** binaries, **not** hotfixes | same version only; **cannot restore across software versions** | MB range | `add backup local` |
| **Management DB export** | management database + applicable CP configuration | management server | large | `migrate_server export` / `migrate export` |
| **MDS backup** | Multi-Domain Server + CMAs | MDS | large | `mds_backup` |

Consequences that drive the architecture:

- **The snapshot is not fleet-pullable.** At ≥2.5 GB per device, restorable only
  to the same box, a scheduled pull across the estate is a storage and network
  non-starter. **Decision: SecurityExpert does not pull snapshots.** It records
  their *existence and age* as restore-readiness evidence (§7). This is the
  honest split between "what we can hold" and "what we can only attest to".
- **The Gaia backup is version-locked.** A backup taken on R81.10 does not
  restore onto R81.20. Therefore every stored backup must carry the exact
  version it was taken from, and readiness must degrade when a device's running
  version has moved past its newest backup. This is a first-class field in the
  manifest contract, not a nice-to-have.
- **Management HA must be captured atomically.** Check Point's guidance is to
  collect from all Security Management / Multi-Domain servers **at the same
  time**. A per-device scheduler that walks servers sequentially produces a
  mutually inconsistent set. `RB.3` needs a *consistency group* concept — a set
  of endpoints backed up as one unit, marked `INCONSISTENT` if any member fails.
- **The backup file is created on the device.** `add backup local` writes to the
  device's disk before anything can be fetched. See §5 — this is not a read.

### 3.2 Palo Alto

| Artifact | Contains | Restores to | Retrieval |
|---|---|---|---|
| **Running configuration** | `running-config.xml` | config only; not identity/certs | `GET /api/?type=export&category=configuration&key=<key>` |
| **Device state** | configuration **plus dynamic information** — certificates, LSVPN satellite authentication, registered firewalls | **the RMA case**; full device identity | `GET /api/?type=export&category=device-state&key=<key>` |
| **Panorama partial device state** | generated by Panorama for a managed firewall | **lacks the dynamic information** above | Panorama-side generation |
| **Named config snapshot** | candidate configuration | config only | `save config to <name>` |

Consequences:

- **Device state is the real backup; configuration export is not.** For an RMA
  replacement, the configuration XML alone loses certificates and LSVPN
  satellite authentication. Panorama's partial device state is explicitly a
  degraded fallback. **Decision: `RB.2` collects device state as the primary
  recovery artifact and the configuration XML as a secondary, human-diffable
  companion.** Storing only the XML and calling it a backup would reproduce
  exactly the "evidence mistaken for backup" failure this design exists to
  prevent.
- **Device state export requires superuser on PAN-OS 7.1+.** A custom
  role-based read-only admin *cannot* perform it. The platform's current PAN
  credential is a collection identity; backup forces a **privilege increase of
  the platform's own service account**. That is a security-boundary decision for
  the product owner, not an implementation detail — open decision **D2** (§13).
  It also raises the blast radius of the credential store and makes
  `DEPLOY.1`'s "separate secrets vault component" a hard prerequisite rather
  than a preference.
- Panorama remains discovery/intent per `AGENTS.md`; the authoritative recovery
  artifact comes from the **firewall**, consistent with the existing
  "direct device = actual evidence" law.

### 3.3 Cross-vendor invariants

1. A recovery artifact is **opaque**. The platform stores and validates it; it
   does not parse it for evidence. Evidence comes from the evidence plane.
2. A recovery artifact is **version-bound and identity-bound**. Manifest must
   carry vendor, platform, exact software version, device identity and, for CP
   VSX, physical endpoint + VSID per `AGENTS.md`.
3. **Never claim restorability that has not been demonstrated.** §6.
4. Retrieval must not degrade the device. §5.

---

## 4. The recovery plane — storage architecture

`project/roadmap.json` already fixes the shape: *"a separate versioned
backup/policy-package volume kept independent from the evidence Postgres
instance per the existing `native_backup_foundation` distinction."* This
design honours that and makes it concrete.

```
RECOVERY ROOT  (separate volume; NOT data_root; NOT the CAS)
└── vault/
    ├── <vendor>/<entity_id>/
    │   ├── <utc_stamp>/
    │   │   ├── artifact.enc          # encrypted vendor-native blob
    │   │   └── manifest.json         # metadata only, no payload, no secrets
    │   └── ...
    ├── consistency_groups/<group_id>/manifest.json
    └── retention/ledger.json         # append-only; deletions are recorded
```

Design decisions:

- **Separate volume, separate mount, separate lifecycle** from `data_root`.
  A compromise or a wipe of the evidence store must not take recovery with it,
  and vice versa. In Compose terms this is a second named volume, not a
  subdirectory of `securityexpert-runtime`.
- **Encrypted at rest, envelope encryption.** Per-artifact data key, wrapped by
  a vault key that lives **outside the backup volume** — the same separation
  principle `DEV.2.2` (`deploy_persistent_secret_material`) established for the
  HMAC identity key. Keys on the backup volume would make the encryption
  decorative. Algorithm agility follows the existing `crypto_agility_pqc` work
  rather than hardcoding a cipher here.
- **Content-addressed *within* the recovery plane, never shared with the
  evidence CAS.** Backups dedupe well (an unchanged device produces an
  identical blob), so the CAS pattern is right — but a shared object store
  would let an evidence-plane reader reach recovery bytes. Separate store,
  same pattern.
- **The manifest is the only thing any other subsystem may read.** It carries
  digests, sizes, versions, validation verdicts and timestamps — never payload,
  never secrets. The Recovery UI module (§11), the readiness model (§7) and
  the compliance plane all consume manifests only. This is what makes
  "recovery data never reaches the browser" a structural guarantee instead of a
  code-review promise.
- **Append-only with a recorded-deletion ledger.** Retention deletes are real
  (storage is finite) but must leave a tombstone: what was deleted, when, under
  which policy. Silent disappearance of a backup is indistinguishable from
  never having taken one.

---

## 5. Command classification — the `operational-write` problem

`AGENTS.md`: *"No automatic network-device write/change operations at the
current maturity"* and *"New CP commands require the network-device command gate
before implementation."* Backup collides with this in a way the current binary
read/write taxonomy cannot express, and pretending otherwise would smuggle a
device mutation past the gate.

`add backup local` **writes a multi-megabyte file to the firewall's disk.** It
changes no configuration, installs no policy, and is reversible — but it
consumes `/var/log` space on a production firewall. A full `/var/log` on a Check
Point gateway is a genuine outage mode. It is emphatically not a read.

**This design introduces a third command class**, and proposes it as an
amendment to `docs/AI_DEVELOPMENT_PROTOCOL.md`'s gate:

| Class | Definition | Current maturity |
|---|---|---|
| `read` | no device state change | allowed, via the gate |
| `operational-write` | creates/removes a transient artifact, consumes a bounded resource; **no configuration change**; reversible | **new — requires the gate + explicit resource preconditions** |
| `config-write` | changes running configuration, policy, routing, credentials, or power state | **prohibited** at current maturity |

An `operational-write` command carries mandatory extra gate fields beyond the
standard ten:

11. **resource consumed** and its unit (disk bytes, CPU, session slot),
12. **precondition check** that must pass *before* execution (e.g. free space on
    the target partition ≥ N× expected artifact size),
13. **cleanup contract** — what is removed afterwards, and what happens to the
    on-device artifact if the transfer fails,
14. **device-impact assessment** signed off by the `cp_device_interaction_safety`
    (P0) audit.

Consequences: `RB.3` (Check Point) is blocked on the P0 CP device-interaction
safety audit — not merely adjacent to it. `RB.2` (PAN) is lighter: the device
state export streams over the API without a durable on-device artifact, so it
classifies as `read` with a resource caveat, which is a large part of why PAN
is sequenced first. The full ten/fourteen-point gate entries for every command
this design needs are written out in
`docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.

---

## 6. Verified backup — what "verified" is allowed to mean

"Verified backup" is BackBox's flagship claim and the thing most likely to be
repeated uncritically in a replacement project. This platform's own law —
*"Evidence over assumptions; explicit `UNKNOWN` over invented certainty"* —
requires being precise about it. A hash proves a file arrived intact. It proves
nothing whatsoever about whether the file can rebuild a firewall.

Four ascending levels, each explicitly labelled in the manifest:

| Level | Verdict | What is actually proven | Cost |
|---|---|---|---|
| **V1 Transport** | `INTACT` | SHA-256 matches, size within expected band, transfer completed | free |
| **V2 Structural** | `WELL_FORMED` | the blob is what it claims: valid gzip/tar with expected members; XML parses with the expected root and required sections present | cheap, offline |
| **V3 Semantic** | `CONSISTENT` | cross-checked against the inventory plane: interface count, vsys/VS count, HA role, hostname and version in the artifact agree with `unified.json` for that device | cheap, offline, **our differentiator** |
| **V4 Restore-proven** | `RESTORE_PROVEN` | an actual restore into a lab device succeeded and the result was verified | expensive, manual, rare |

**V3 is the interesting one and is only possible because this platform already
has a reconciled inventory.** A pure backup tool has nothing to cross-check
against; SecurityExpert can assert "this PAN device state claims 4 vsys, and
inventory says this firewall has 4 vsys" — catching truncated, partial and
wrong-device artifacts that pass V1 and V2 cleanly. That is a real capability
BackBox cannot easily match, and it falls out of work already done.

**Hard rule:** the UI, the manifest and any report never render the word
"verified" unqualified. The level is always shown. A V1/V2/V3 artifact is
`RESTORE_UNPROVEN` — full stop — until a V4 record exists for that
vendor/platform/version combination. Restore-proven status is *inherited by
class*, not by artifact: proving one R81.20 Gaia backup restores raises
confidence in the procedure, and the manifest records that lineage explicitly
rather than silently upgrading every artifact's verdict.

`RB.4` delivers V1–V3. V4 requires lab hardware and is a manual, recorded
procedure — not something the platform performs by itself (performing it
automatically would be a restore, i.e. `RB.6`).

---

## 7. Restore readiness — the model that ships first

`RB.0` answers one question per device, using **only data already collected**:

> If this device died right now, what do we actually have?

No new device command. No new credential. No backup collected. It reads the
inventory plane, the evidence plane and (once `RB.1` exists) recovery manifests,
and emits a per-device readiness record:

```
READY            a validated, version-current recovery artifact exists
STALE            an artifact exists but the device's running version or
                 configuration has moved past it
PARTIAL          some artifact classes present, required ones missing
                 (e.g. PAN configuration XML but no device state)
UNPROTECTED      no recovery artifact of any kind
UNKNOWN          insufficient evidence to judge — never inferred as ready
```

Readiness inputs, in order of strength: a validated recovery manifest (V3+) →
an unvalidated manifest → an attested-but-unheld artifact (a Gaia snapshot the
device reports having, §3.1) → nothing. The distinction between "we hold it"
and "the device says it has one" is preserved and displayed; collapsing them
would be exactly the invented certainty the engineering laws forbid.

**Why this ships first:** it is cheap, it is read-only, it needs no gate, and
its output is immediately actionable — it produces the list of devices that
would not survive a hardware failure, which is the business case for the rest of
the programme. It also quantifies the BackBox exit risk in §2 with real numbers
instead of assumption. `restore_readiness` currently sits at 0.9.x in the
feature registry; **this design recommends pulling it forward as `RB.0`**, since
it depends on nothing later in the sequence.

---

## 8. Restore — hard gate, designed but not buildable

Restore is a `config-write` at the highest blast radius available: it replaces a
production firewall's entire configuration and identity. It sits at the `OP.2`
bar defined in `FAILOVER_ENGINE_ARCHITECTURE.md` §10 and inherits every one of
its gates, plus two of its own.

Prerequisites, all mandatory, none waivable:

- mature `VERIFY` / `TRACE` / `RECOVER` planes,
- `DEPLOY.1A` OIDC boundary + an RBAC role that can hold the restore
  permission, with full audit,
- the P0 `cp_device_interaction_safety` audit complete,
- the network-device command gate completed for every restore primitive,
- a signed change-management / safety review with the network-security leads,
- **V4 restore-proven status for that exact vendor/platform/version class** —
  restoring from an artifact class never proven restorable is not a recovery,
  it is an experiment on a production device,
- **a demonstrated rollback path** — what happens when the restore itself fails
  halfway.

Per the roadmap's binding design consequence, the models here are shaped so
restore is an **additive layer, never a rework**: the manifest already reserves
a `restore` block that the `RB.1` validator **rejects** if present, exactly as
the check engine reserves and rejects its remediation block. That keeps the
door open without leaving it unlocked.

---

## 9. Scheduling, coordination and retention

Backup is the platform's first genuinely **recurring, mandatory-cadence**
workload; everything so far has been on-demand or opportunistically scheduled.

- **It must route through `utils/collection_executor.py`.** The per-endpoint lock
  and the concurrency-budget-of-1 are the CP device-safety mechanism; a backup
  job that bypasses the admission coordinator to "just fetch a file" reintroduces
  precisely the risk that gate exists to hold. Backup becomes a new entry in
  `ALLOWLISTED_WORKFLOWS`, not a side channel.
- **Correction 2026-08-30 (superseded text below):** an earlier draft of this
  section said scheduled backup "hard-depends on
  `distributed_endpoint_lock_and_job_store` (DEV.3.2/3.3)". That is only true
  once the platform splits into *multiple* worker containers
  (`per_vendor_worker_split`, DEV.3.4, explicitly deferred). Under the current
  DEV.3.1 single-container deployment, `utils/collection_executor.py`'s
  in-memory `CollectionCoordinator` already provides the per-endpoint lock
  correctly for every existing scheduled workflow (`checkpoint`/`cp`/`vsx`/
  `pan-config` already run under `--scheduler-once` today, single-process, no
  distributed store) — recovery collection scheduling is safe under the exact
  same model and does **not** need to wait for DEV.3.2/3.3. The distributed
  lock only becomes load-bearing if/when a future build actually splits
  collection across multiple processes/containers.
- **Consistency groups** (§3.1) are scheduled as a unit, not as members.
- **Retention** is Grandfather/Father/Son by default (dailies → weeklies →
  monthlies), per-device-class overridable, with a floor: **retention may never
  delete the only artifact for a device that is otherwise `UNPROTECTED`.** A
  policy that can drive a device to zero coverage is a bug.
- **Deletion is a destructive local-data operation** and per
  `docs/AI_DEVELOPMENT_PROTOCOL.md` requires explicit human approval; the
  scheduler proposes, `--apply` disposes, mirroring the existing
  `--storage-deduplicate` dry-run-by-default convention.

### 9.1 Recovery collection command infrastructure (added 2026-08-30, product owner request)

Explicit product direction: recovery collection must **not** be a block of
logic inlined in `main.py`. It must be (1) a standalone, importable
orchestration layer that a CLI, a future UI action, or the scheduler can all
call identically; (2) **selective** — an operator or a schedule can target
specific gateways/firewalls, not only "everything"; (3) scheduler-integrated
from day one, under the existing `collection_executor` admission model (§9
correction above).

**Module: `utils/recovery_collect.py`.** Owns the whole orchestration; `main.py`
is reduced to argument parsing that builds a request and calls in — the exact
shape `RB.5`'s Recovery UI (or any future internal API) will call too, so "UI
if needed" is a consequence of this layering, not a rewrite of it.

```
RecoveryCollectionTarget    # one resolved (vendor, entity_id) pair
RecoveryCollectionRequest   # vendor, target selector, provenance
select_recovery_targets(unified_devices, vendor, selector) -> [RecoveryCollectionTarget]
RecoveryCollector (protocol)  # .collect(target) -> plaintext bytes + artifact metadata
run_recovery_collection(request, services, *, store_writer) -> RecoveryCollectionResult
```

**Target selection** (`selector`) is one of:

- `"all"` — every admitted device of the given vendor in `unified.json`
  (today's only mode for the existing checkpoint/pan-config workflows).
- an explicit `entity_id` list — the "selective for gateways" requirement.
  Resolved the same way `restore_readiness` resolves entity identity (§7),
  including the VSX `<device>__vsid_<vs_id>` convention, so a specific virtual
  system can be targeted without pulling its whole physical host.

An unresolvable entity_id in an explicit list is a **request-time error**
(fails before any device is touched), not a silent skip — the same
fail-closed posture as `_require_bootstrap` for other modes.

**Vendor dispatch.** `RecoveryCollector` is a small protocol
(`collect(target) -> (plaintext_bytes, artifact_meta)`); vendor collectors
register against it. This keeps `recovery_collect.py` vendor-neutral: it does
target selection, admission-coordinator routing, and the encrypt-and-store
call into `utils/recovery_store.write_artifact`, but never speaks PAN XML or
CP Clish itself.

- **PAN — `panorama/panorama_recovery_collector.py` — implemented.** `D2` is
  resolved (§13) and `type=export&category=device-state` is `read` class, not
  the "no new write command" prohibition (`docs/AI_DEVELOPMENT_PROTOCOL.md`),
  and was already gate-documented in contract §7.1 before this build. Reuses
  `panorama_runtime_runner.get_api_key` / TLS-verify resolution verbatim (gate
  point 7: existing-session reuse). **Real-environment validation remains
  owed** — this cloud sandbox has no device reachability, the same gap class
  as every other `on_hardware_real_env_validation` item in this repository;
  automated tests exercise it against a fixture HTTP transport only, never a
  live firewall.
- **CP — still a typed, explicit stub.** `add backup local` is
  `operational-write` class and is blocked on the P0
  `cp_device_interaction_safety` audit and open decision `D3` — **neither is
  resolved by this build.** Calling the CP collector raises
  `RecoveryCollectionBlockedError` naming the exact blocker; it is wired into
  target selection and the store so only the device call itself is missing
  once the audit clears.

**Scheduler integration.** `ALLOWLISTED_WORKFLOWS` gains `"recovery-pan"`
(not `"recovery-cp"` — still blocked). The scheduler policy schema (contract
addendum below) gains an **optional, additive** `targets` field per scheduled
entry — omitted means `"all"`, present means an explicit gateway list —
preserving every existing policy file's meaning unchanged (no schema version
bump; `targets` absent is indistinguishable from today's behavior).

---

## 10. Security model (hard rules)

These are testable invariants, not aspirations. `BACKUP_RECOVERY_CONTRACTS.md`
§9 states each one as an automated test obligation.

1. Recovery payload bytes **never** enter `output/index.html`, any JSON payload
   embedded in it, the support bundle, or repository metadata. Manifests only.
2. Recovery artifacts are **encrypted at rest**; the wrapping key lives outside
   the recovery volume.
3. The recovery volume is **not** served by nginx, in any configuration. The
   DEV.3.1 nginx service mounts the runtime volume; it must never mount this one.
4. Backup credentials are **separate identities** from collection credentials and
   are held in the `DEPLOY.1` secrets vault, never in `.env` on the server.
5. The repository privacy gate must fail on any recovery artifact, key or
   manifest that appears inside the repository tree — the same way it already
   fails on `known_hosts` and `*.pem`.
6. Log redaction (`utils/logger.py`) applies to every recovery-plane code path;
   a backup filename may embed a hostname and is treated as an operational
   identity.
7. No recovery-plane operation is reachable from any unauthenticated surface,
   before or after `DEPLOY.1A`.
8. An `operational-write` command runs only after its precondition check passes
   (§5) — never optimistically.

---

## 11. UI — the Recovery module

A seventh module alongside Overview / Network Inventory / Configuration /
Compliance / Discovery / Project Plan. It renders **manifests and readiness
only** — never payload, never a download link, never a decrypt path. Per
`AGENTS.md`, adding it obliges a `tests/fixtures/uitest/` extension and a green
render harness.

- **Readiness roll-up:** fleet counts by `READY` / `STALE` / `PARTIAL` /
  `UNPROTECTED` / `UNKNOWN`, and the actionable list — the unprotected devices.
- **Per-device recovery timeline:** artifact classes held, ages, versions, and
  the validation level (§6) shown explicitly per artifact.
- **Coverage vs inventory:** devices in `unified.json` with no recovery record —
  the gap view, which is the honest answer to "are we backed up?".
- **Retention view:** what is scheduled for deletion and under which policy.

`Overview` gains a single recovery-posture tile. `compliance_posture` gains
readiness as an evidence source for a future backup-coverage control — additive,
consistent with the existing control model.

---

## 12. Phasing and roadmap placement

| Phase | Scope | Gate | Buildable |
|---|---|---|---|
| **`RB.0`** | Restore-readiness assessment over existing evidence (§7) | none — read-only, no new command | **AUTOMATED_VALIDATED 2026-08-30** |
| **`RB.1`** | Recovery-plane store: layout, encryption, manifest, retention, validator. No collection. | none — local/offline | **AUTOMATED_VALIDATED 2026-08-30** |
| **`RB.2`** | PAN device-state export + collection orchestration (target selection, scheduler) | command gate (documented §7.1, `read` class); **D2 RESOLVED 2026-08-30** | **IMPLEMENTED 2026-08-30 — real-env validation owed** (PAN configuration-XML export, §7.2, not yet implemented) |
| **`RB.3`** | CP Gaia backup + management export; consistency groups | command gate **+ `operational-write` class (§5) + P0 CP safety audit** | blocked stub only — after P0 audit |
| **`RB.4`** | Validation battery V1–V3 (§6) | none beyond `RB.1`–`RB.3` | **AUTOMATED_VALIDATED 2026-08-30** — built and tested against synthetic manifests ahead of `RB.2`/`RB.3` landing, same "offline-first, real-env validation owed" pattern already used for `RB.0` |
| **`RB.5`** | Readiness scoring + Recovery UI module (§11) | render harness + uitest fixtures | after `RB.4` |
| **`RB.6`** | Controlled restore | **`OP.2` bar (§8)** | **no** |

**RB.2/RB.3 real-environment note:** the PAN device-state collector
(`panorama/panorama_recovery_collector.py`) is implemented but this cloud
sandbox has no device reachability — the same
`on_hardware_real_env_validation` gap every other collector in this
repository carries. Automated tests exercise it against a fixture HTTP
transport only. Per `AGENTS.md`, this is `IMPLEMENTED`, not
`REAL_ENV_VALIDATED` — never mark a network-facing behavior `DONE` from
automated tests alone.

Scheduled fleet backup (as opposed to on-demand) additionally requires
`distributed_endpoint_lock_and_job_store` (DEV.3.2/3.3) per §9.

Roadmap mapping: `RB.0` rebases `restore_readiness` **forward** from 0.9.x;
`RB.1`–`RB.3` are the rebase of `native_backup_foundation` (original 0.6.0B);
`RB.4` is `native_backup_validation`; `RB.5` completes `restore_readiness`;
`RB.6` joins `failover_controlled_execution` behind the `OP.2` gate.

Against the 2027 BackBox deadline (§2): `RB.0` and `RB.1` are unblocked today
and independent of hardware. `RB.2` is achievable once `DEPLOY.1` lands and D2
is decided. `RB.3` is the schedule risk, because it is gated behind a P0 audit
that has not started.

---

## 13. Open decisions

| id | Decision | Owner | Blocks |
|---|---|---|---|
| **D1** | Inventory of what BackBox actually backs up today, by vendor and device count. Does the estate contain non-CP/PAN devices that need backup after 2027? | product owner | the entire "BackBox replacement" premise (§2) — **still open** |
| **D2** | ~~Is the platform's PAN service account permitted to hold **superuser**~~ **RESOLVED 2026-08-30 — approved by the product owner.** The platform's PAN service account is permitted to hold superuser for the sole purpose of `type=export&category=device-state`. Consequence accepted: this is a real privilege increase to the collection identity (§10 rule 4 still separates the *backup* credential from the *collection* credential — D4 — so the superuser grant lands on a distinct service account, not the read-only inventory one). `RB.2` PAN device-state export is unblocked on this axis; `DEPLOY.1`'s secrets-vault requirement (§2) is now load-bearing, not aspirational. | security lead — **approved** | `RB.2` — unblocked |
| **D3** | Is `add backup local` (writes to device disk) acceptable at current maturity as the new `operational-write` class (§5), or does the CP backup have to wait for full write-capability maturity? | network-security leads + P0 audit | `RB.3` |
| **D4** | Backup credential identity: separate service account per vendor, or reuse the collection identity with elevated rights? (§10 rule 4 assumes separate.) | security lead | `RB.2`, `RB.3` |
| **D5** | Recovery volume retention floor and total storage budget — drives GFS parameters and whether CP management exports (large) are held at the same depth as PAN device states. | product owner + infra | `RB.1` |
| **D6** | Does the `operational-write` class get adopted into `docs/AI_DEVELOPMENT_PROTOCOL.md` as a permanent taxonomy amendment, or stay local to this design? | product owner | §5, `RB.3` |
| **D7** | Is a V4 restore-proof lab (a spare appliance / VM per platform class) available? Without one, nothing ever leaves `RESTORE_UNPROVEN` (§6). | product owner + infra | `RB.6`, and the credibility of `RB.4` |

---

## Sources (vendor documentation consulted)

- [Check Point R81 — Backing Up and Restoring](https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_Installation_and_Upgrade_Guide/Topics-IUG/Backing-Up-and-Restoring.htm) — `migrate_server export`, `mds_backup`, Management HA simultaneity requirement.
- [Check Point sk108902 — Best Practices: Backup on Gaia OS](https://support.checkpoint.com/results/sk/sk108902) — snapshot vs backup contents, ≥2.5 GB snapshot floor, same-machine restore constraint, cross-version limits.
- [Check Point R81 — `migrate` / `migrate_server`](https://sc1.checkpoint.com/documents/R81/WebAdminGuides/EN/CP_R81_SecurityManagement_AdminGuide/Topics-SECMG/CLI/migrate_server.htm) — management database export semantics.
- [Palo Alto — Backing Up and Restoring Configurations](https://knowledgebase.paloaltonetworks.com/KCSArticleDetail?id=kA10g000000ClRcCAK) — named snapshots, config versions, `save device state`, restore paths.
- [Palo Alto — How to Export The Device State Using XML API](https://knowledgebase.paloaltonetworks.com/KCSArticleDetail?id=kA10g000000CldVCAS) — exact export URL, superuser requirement on PAN-OS 7.1+.
- [Palo Alto — Partial Device State Generation for Firewalls](https://docs.paloaltonetworks.com/panorama/administration/troubleshooting/replace-an-rma-firewall/partial-device-state-generation-for-firewalls) — RMA workflow, dynamic-information gap in Panorama-generated state.
