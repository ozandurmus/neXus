# D4 — Backup credential identity (decision brief)

**Status:** DECISION BRIEF — **SIGNED OFF 2026-08-31 (security lead).**
Option A adopted as the target model, Option C as the pilot mechanism
(§4 — the recommendation of this brief, accepted as written). No code.
Companion to `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §13 (row `D4`,
now resolved), §10 rule 4, and
`docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` (decision B11, AC-11).
This brief is retained as the record of what was put to the security lead and
what they accepted.

> **D4 SIGNED OFF 2026-08-31 (security lead).** Option A (distinct per-vendor
> backup service account, no fallback to the collection identity, fails the CP
> collection closed before any device contact when absent) + Option C (DEV.2.2
> read-only mounted-material custody for the pilot; `DEPLOY.1` vault later)
> approved as written. Option B (reuse the collection identity elevated)
> rejected. The PAN (`RB.2`) follow-up in §8 is accepted as an owed item on
> `RB.2`, not a blocker on `RB.3b`. `RB.3b` is unblocked on the credential-
> identity axis.

**Owner:** security lead. **Blocks:** `RB.3b` implementation (hard). Also names
an owed follow-up on `RB.2` (PAN), below.

---

## PROJE ÖZETİ (Türkçe)

- **Karar sorusu:** Check Point Gaia sistem yedeği alan komut (`add backup
  local` + SCP ile çekme) hangi kimlik bilgisiyle çalışmalı? Bugünkü envanter
  (salt-okunur toplama) hesabını mı kullansın, yoksa **ayrı bir yedek hesabı**
  mı olsun?
- **Öneri:** Ayrı, vendor'a özel bir yedek servis hesabı. Envanter hesabına
  **asla** geri düşmesin; yoksa hiçbir cihaza dokunmadan kapalı-hata versin.
- **Neden:** Yedek almak Gaia'da yükseltilmiş yetki ister. Bu yetkiyi her zaman
  açık olan envanter kimliğine eklemek, o kimliğin sızması hâlinde saldırı
  yüzeyini "cihaz yedeği oluştur/sil" seviyesine çıkarır — D2'nin PAN tarafında
  önlemek için sorulduğu tam da bu hata.

---

## 1. The question (verbatim from architecture §13)

> **D4** — Backup credential identity: separate service account per vendor, or
> reuse the collection identity with elevated rights? (§10 rule 4 assumes
> separate.)

Architecture §10 rule 4 already states the intended answer
("Backup credentials are **separate identities** from collection credentials and
are held in the `DEPLOY.1` secrets vault, never in `.env` on the server"), and
the resolved `D2` entry already leans on it ("the superuser grant lands on a
distinct service account, not the read-only inventory one"). `D4` is the point
where that assumption is either ratified as a binding precondition for `RB.3b`
or explicitly relaxed. This brief recommends ratifying it.

## 2. Why the default (silent reuse) is refused — RB.3b B11

If `RB.3b` shipped without `D4` answered and simply read the existing
`SECURITYEXPERT_CP_CONFIG_SSH_*` identity, the outcome would be a **quiet
privilege increase of the platform's always-on inventory credential**: a
credential that today can only run a fixed allow-list of `show` reads would, by
reuse, also be able to create and delete multi-megabyte archives on a
production firewall's `/var/log`. A leak or misuse of the inventory credential
would then carry that capability with it. That is precisely the failure mode
`D2` was raised to prevent on the PAN side, and §10 rule 4 exists to hold.

Consequences of reuse that make it the wrong default:

- **Blast radius.** The inventory credential is used on every routine
  collection run, fleet-wide. Attaching a device-mutating capability to it
  widens the impact of its compromise from "topology disclosure" to "create /
  delete backups on any reachable Gaia gateway".
- **No independent kill switch.** Backup could not be disabled without also
  disabling inventory collection, and vice versa.
- **Audit ambiguity.** Device-side logs could not distinguish an inventory
  session from a backup session by principal.
- **Rotation coupling.** The two capabilities would rotate on the same
  schedule and the same secret.

## 3. What `add backup local` + SCP fetch actually require on Gaia

The RB.3b device interaction (contracts §7.3 / §7.4 / §7.7 / §7.8) needs, in
one SSH session, the ability to:

1. read `/var/log` free space — `show diskspace` (Clish) or `df -P /var/log`
   (Expert) — a `read`;
2. run `add backup local` — an `operational-write`;
3. SCP-read the produced archive from `/var/log/CPbackup/backups/`;
4. run `delete backup <name>` (Clish) or `rm -f -- <path>` (Expert) — an
   `operational-write`.

Steps 2 and 4 are **not** available to a pure read-only Gaia role. Gaia gates
the backup feature set (`add backup` / `delete backup` / `set backup`) behind an
administrator role with those features assigned; the read-only inventory role
the platform uses today cannot invoke them. So `RB.3b` **forces an elevated
identity regardless** — the only open question is whether that identity is
*distinct from* or *fused with* the inventory identity. Distinct is strictly
safer and costs one service account.

## 4. Options

### Option A — distinct per-vendor backup service account *(RECOMMENDED)*

A separate Gaia user (suggested role name `securityexpert-backup`) provisioned
on each pilot-allowlisted gateway, holding **exactly** the backup feature set
(`add backup`, `delete backup`, `show backups`, `show diskspace`, and SCP/SFTP
read of `/var/log/CPbackup/backups/`) and **nothing that writes configuration,
policy, routing, SIC, clustering or credentials**. The platform reads it from:

| Purpose | Variable | Fallback |
|---|---|---|
| Backup principal | `SECURITYEXPERT_CP_BACKUP_SSH_USERNAME` | **none** — absence fails closed |
| Backup secret (server) | `SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD_FILE` (path to a read-only mounted file) | — |
| Backup secret (local dev only) | `SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD` | — |

Transport tunables are **not** duplicated — port, connect/command timeouts and
strict-host-key policy continue to come from `SECURITYEXPERT_CP_CONFIG_SSH_*`
(and `SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY`), because they describe the SSH
channel and the estate's host-key trust, not who authenticates. Only the
**principal and secret** are a distinct identity.

Pros: minimal blast radius; independent enable/disable, rotation and revocation;
device-side attribution; satisfies §10 rule 4 as written.
Cons: one service account to provision per pilot gateway; a second secret to
custody (mitigated by §5).

### Option B — reuse the collection identity with elevated rights *(REJECTED)*

Grant the existing `SECURITYEXPERT_CP_CONFIG_SSH_*` account the backup feature
set. Rejected for every reason in §2. Recorded here only so the review has the
rejected option in front of it.

### Option C — Option A, but credentials mounted read-only pending the DEPLOY.1 vault *(INTERIM, pilot-only)*

Identical to Option A except that until the `DEPLOY.1` secrets-vault component
exists, the secret is delivered by the DEV.2.2 mounted-material pattern
(§5) rather than pulled from a vault. This is the mechanism the **pilot** runs
under. On `DEPLOY.1` arrival the same distinct identity moves into the vault as
its own secret with no code change (the collector still reads one env var; only
its source changes).

**Recommendation: adopt Option A as the target and Option C as the pilot
mechanism.** They are the same identity model; they differ only in secret
custody.

## 5. How the credential is stored (DEV.2.2 secret-material pattern)

`deploy_persistent_secret_material` (DEV.2.2) established that server-grade
trust/secret material is **mounted, read-only, and non-default** — not left in
`.env`, not baked into an image (`docker-compose.prod.yml` mounts
`deploy/secrets/known_hosts` and `pan-ca-bundle.pem` `:ro`). The backup secret
follows that pattern:

- **Server / pilot:** a read-only bind-mounted or Docker-secret file, path in
  `SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD_FILE`. It lives beside the other
  mounted secrets, never in the compose `.env`, never in the repository tree
  (the repository privacy gate already fails on `*.pem` / `known_hosts` /
  recovery material inside the tree — the backup secret file joins that set).
- **Local dev:** `SECURITYEXPERT_CP_BACKUP_SSH_PASSWORD` direct, exactly as
  `SECURITYEXPERT_CP_CONFIG_SSH_PASSWORD` works today. The `_FILE` form wins
  when both are set.
- **Both forms** are passed to `utils.logger.register_sensitive_value` at
  construction (principal → `[USER:<fingerprint>]`, secret →
  `[AUTH_SECRET:REDACTED]`), identical to
  `checkpoint/checkpoint_recovery_attestation.CheckpointRecoveryAttester`.
- **DEPLOY.1:** the file/vault reference becomes a distinct vault secret. The
  identity is already separate, so this is a custody change only.

The backup secret is **rotatable and revocable independently** of the
inventory secret, which is the property Option B cannot provide.

## 6. How admission routes it

The admission coordinator (`utils.collection_executor.execute_admitted_collection`
via the `run_under_admission` hook in `utils.recovery_collect`) is
**credential-agnostic** — it gates the per-endpoint lock and the per-vendor
concurrency budget of 1, nothing else. It never sees the credential. Routing of
the backup identity is entirely the CP collector's responsibility, and mirrors
how `RB.3a` wires its attester:

1. **`main.py`** constructs the CP backup collector once per
   `--recovery-collect --recovery-vendor checkpoint` invocation, the same way it
   constructs `CheckpointRecoveryAttester` today.
2. The collector's **constructor** resolves the backup identity
   (`SECURITYEXPERT_CP_BACKUP_SSH_USERNAME` + `_PASSWORD_FILE` / `_PASSWORD`).
   If either is missing it raises immediately — **before target selection,
   before any device contact** — and the whole CP collection request is refused
   with an explicit reason (`cp_backup_credentials_unavailable`). It **never**
   reads `SECURITYEXPERT_CP_CONFIG_SSH_USERNAME` / `_PASSWORD` as a fallback.
   (Contrast `RB.3a`, which deliberately *does* fall back to the runtime
   principal because attestation is a `read`; a `read` fallback is acceptable,
   an `operational-write` fallback is not.)
3. Per-endpoint order (RB.3b correctness contract item 1), all inside the
   admission-held section: durable ledger check → pilot allowlist check
   (`SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES`) → platform check → free-space
   read (§7.7) → `add backup local` (§7.3) → SCP fetch (§7.4) → digest verify →
   `software_version` resolve → store write → `delete backup` (§7.8) → ledger
   write. The credential check sits **above** all of it, at construction time.
4. The SCP fetch (§7.4) and the deletion (§7.8) run in the **same session** as
   `add backup local`, so they carry the same backup identity by construction —
   there is no second credential path.

Net: an empty or absent backup credential produces **zero device contact** and
a clear operator-visible reason, exactly as an empty
`SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES` does (B10).

## 7. What sign-off accepts

Approving Option A/C means the platform holds a **second, more-privileged Check
Point identity**, delivered as read-only mounted material for the pilot. Its
capability ceiling on the pilot-allowlisted gateways is: create and delete Gaia
**system backups**, read `/var/log` free space, and SCP-read the backup
directory. It **cannot** change configuration, policy, routing, interfaces,
SIC, clustering, credentials or power state — those are `config-write` and
remain prohibited (§5, architecture §5). The blast radius of this identity is
bounded by the pilot allowlist's contents.

## 8. PAN (RB.2) implication — owed follow-up, not a blocker on this brief

Architecture §13 lists `D4` as also blocking `RB.2`. `RB.2` is already
`IMPLEMENTED` and currently reuses the inventory API-key session
(`panorama_runtime_runner.get_api_key`). `D4`'s resolution (distinct backup
identity per vendor) therefore implies a **retro-fit** on `RB.2`: PAN
device-state export should authenticate with a distinct PAN service-account
credential (the superuser grant `D2` approved), not the read-only inventory API
key. This is recorded as an owed follow-up on `RB.2` **before it advances past
`IMPLEMENTED`** — added to `project/backlog.json` `native_backup` /
`on_hardware_real_env_validation`. It does not block this brief and does not
block `RB.3b`; it is called out here so the scope is not silently narrowed to
Check Point.

## 9. Cross-references

- `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §13 (`D4`, `D2`), §10 rule 4, §5.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3, §7.4, §7.7, §7.8.
- `docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` B10, B11; AC-7, AC-11.
- `docs/history/phase/DEV2_2_PERSISTENT_SECRET_MATERIAL.md` (mounted-material pattern).
- `checkpoint/checkpoint_recovery_attestation.py` (`CheckpointRecoveryAttester.__init__` — the wiring this mirrors, minus the fallback).
