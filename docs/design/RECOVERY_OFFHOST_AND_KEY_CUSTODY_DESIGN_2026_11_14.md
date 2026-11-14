# Off-host recovery copies and encryption-key custody - architecture design

## Status

DRAFT - 2026-11-14. Architecture proposal for Product Owner review; **not implementation authority** (AGENTS.md
"Contract-status law"). It answers backlog item `recovery_offhost_key_custody` (P0, `project/QUEUE.md`) and the open
custody items it inherits: `UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §6.3 / §10 item 3,
`UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` §4.4 / §9.7, `PO_DECISION_RECORD_2026_09_14I_...` AL-4 and
`PCP_STORAGE_ENGINE_DECISION.md` gap #9. It decides none of them. The work introduces a new credential boundary
(off-host storage accounts), a new key-custody model and new host services. Under AGENTS.md "Mandatory build
lifecycle" that is a major security boundary: a FROZEN contract must come after the Product Owner answers §8 and before
any step in §7 is implemented.

Written without any host, device or database contact. Every size and duration below comes from a cited repository
record. Values that are not in the repository are marked `UNKNOWN`. All names are role names or synthetic
(`HOST-A`, `HOST-B`, `192.0.2.x`, `*.example.invalid`).

## Amendment 2026-11-14 evening: target is CyberArk custody, no off-host copy

The Product Owner decided on 2026-11-14 (`PO_DECISION_RECORD_2026_11_14_EVENING_NAME_BACKUPS_CYBERARK_DB_POLICY.md`):

- **Backups stay on HOST-A.** PO: "yedekler sunucuda kalacak" ("the backups will stay on the server"). No off-host
  copy is built. The PO accepts the risk that a lost HOST-A disk loses the database, the platform backups and the
  device backups together. Section 3 (off-host target) and the off-host steps of sections 5 to 7 are therefore not
  pursued.
- **Keys go to CyberArk.** PO: "Anahtar, key, şifre, account için CyberArk var" ("For keys, secrets, passwords and
  accounts there is CyberArk"). The target of section 4 is CyberArk custody: the archive and master keys (artefact
  store, platform backup identity, failover and schedule keys) are held in CyberArk, neXus fetches a key when it needs
  it, and nothing is kept on HOST-A. Device credentials also move to CyberArk.

What remains useful here: the inventory of what exists (section 1), the key list and today's custody gaps (section 4),
and restore testing (section 6), now against HOST-A only. Backlog: `cyberark_archive_key_custody` (P1) and
`cyberark_device_credentials` (P2); `recovery_offhost_key_custody` is closed as superseded. The rest of this document
stays DRAFT and is kept as the record of the options that were weighed.

## 0. Summary for the decision maker

- **Today, one lost disk loses everything.** The database, the daily encrypted platform archives, every device backup
  and the live application keys sit on one disk of one host (§1). The only copies off the host are a few hand-carried
  ones the Product Owner holds. One person holds the archive decryption key, and a copy of that key also sits on
  HOST-A.
- **Recommended target:** bank-operated, S3-compatible object storage with versioning and Object Lock in *compliance*
  mode, in a different failure domain from HOST-A. HOST-A gets a write-only account. Where bank policy asks for a second
  medium or an air gap, the bank's own backup system copies that bucket to tape or vault (§3.7).
- **Recommended custody:** the archive decryption key never lives on HOST-A. Every archive is encrypted to two
  independent recipients:
  - an *operational restore key* under dual control (one custodian holds the key, a second holds its passphrase or PIN);
  - an *escrow key* split 2-of-3 among three role holders (§4).
  This key matters as much as the firewall estate's admin credentials: the archive contains the credential-store key
  (§1.2).
- **What it buys:** root on HOST-A, ransomware, an operator mistake or a lost disk can no longer destroy recovery
  history. A restore becomes a tested runbook (§5, §6) with measured RPO/RTO, not an improvisation.
- **What it does not buy:** confidentiality against an attacker who already holds root on HOST-A. The running product
  needs its keys in memory. Compromise recovery therefore includes credential and key rotation (§5.3).

## 1. Current state

### 1.1 What exists and where it lives

| # | Recovery-relevant state | Where (HOST-A) | Protection | In platform archive? | Off-host today |
|---|---|---|---|---|---|
| S1 | PostgreSQL `ui2` database: devices, encrypted credentials, users, RBAC, jobs, audit, `backup_artefact` manifest rows (incl. wrapped data keys) | `ui2-db` StatefulSet on the k3s default (local-path) class, `deploy/ui2/30-database-pvc.yaml` | application-level encryption of credential fields only; no disk encryption (`HOST_A_HARDENING_DRAFT.md` L2) | yes, `pg_dump -Fc` of `ui2` (`deploy/hosta/nexus-platform-backup.sh:20`); **roles/globals not dumped** | no (hand copies only, see S6) |
| S2 | k3s server state: datastore (all Secrets, aescbc-encrypted), `cred` (incl. the Secrets encryption config), `tls`, `token`, `/etc/rancher/k3s` | `/var/lib/rancher/k3s/server` | k3s secrets encryption (`HOST_A_HARDENING_DRAFT.md` §5 item 5, H3) | yes (`nexus-platform-backup.sh:13-15`) | no |
| S3 | Application keys as k3s Secrets (§1.2) | inside S2 | as S2 | yes, implicitly via S2 (no explicit export) | no |
| S4 | Device backup artefacts (ciphertext), `<vendor>/<device_id>/<sha256-of-ciphertext>.enc` | `ui2-artefact-store` PVC, 400 Gi request, default class (`deploy/ui2/35-artefact-store-pvc.yaml`); layout `FileArtefactStore.java:22-33,136-139` | per-artefact AES-256-GCM data key, wrapped by the artefact-store master key (`ArtefactStoreCipher.java:19-46`) | **no**, excluded by design (`nexus-platform-backup.sh:2-3`) | **no**. The script header says "copied off-host separately"; no such mechanism exists in the repository, and `HOST_A_HARDENING_DRAFT.md` §5 item 6 records "needs an off-host target -- PO to name it" |
| S5 | Daily platform archive `nexus-platform-<UTC>.tar.gz.age`, newest 14 kept | `/var/backups/nexus` (same disk) | age, to the recipient in `/etc/nexus/backup/age-recipient.txt` (`nexus-platform-backup.sh:21-23`) | n/a | no |
| S6 | Ad-hoc pre-maintenance exports | `/var/backups/nexus-prekernel-20261031`; hand copy to the PO's workstation with matching SHA-256 (`docs/operations/HOST_LEDGER_HOST-A.md:311-314`); `rwsafe-final-...pgdump.age` (`:389-390`) | mixed; the pre-kernel copy is an unencrypted `pg_dump` | n/a | partly (PO workstation; not managed, not immutable, not tested) |
| S7 | Archive decryption identity (age private key) | on HOST-A, root-only; one copy with the PO (`HOST_A_HARDENING_DRAFT.md:69-76`) | file permissions | excluded (`./backup` pruned, `nexus-platform-backup.sh:16`) | one copy, one person |
| S8 | SSH host keys (pinned by the SFTP push senders) | `/etc/ssh/ssh_host_*` | file permissions | **no** (only `sshd_config.d`, `:17`) | no. The rebuild needed them (`HOST_A_REBUILD_RUNBOOK.md:113`) |
| S9 | Packet-analysis volume, report store | `deploy/ui2/36-packet-analysis-pvc.yaml`, `37-report-store-pvc.yaml` | - | no | no (scope question Q19) |
| S10 | Code, manifests, migrations | Git origin plus a bare repository on HOST-A (`HOST_A_REBUILD_RUNBOOK.md` §2, §6) | Git | n/a | yes (origin) |

Measured sizes (repository records only):
- Platform archive: 198 MB on 2026-09-27; 7.8 GB on 2026-11-09 (`HOST_LEDGER_HOST-A.md:360-361`).
- Database dump: 6.7 GB on 2026-10-31 (`:311`).
- Artefact store: 98 GB on 2026-09-24 (`HOST_A_REBUILD_RUNBOOK.md` §1) and 117 GB on 2026-09-27
  (`HOST_A_HARDENING_DRAFT.md:74`). Current size `UNKNOWN`.
- Daily new-artefact volume `UNKNOWN`. Upper reference: about 222 GB/day at four full fleet runs a day, before the fix
  (`INCIDENT_2026_10_10_BACKUP_FREQUENCY_DISK_FULL.md`).

Operational facts that matter here:
- The timer runs daily at 01:30 UTC with a randomized delay of up to 15 minutes and `Persistent=true`
  (`deploy/hosta/nexus-platform-backup.timer`).
- The service unit has no `OnFailure=` (`nexus-platform-backup.service`), so a failed run is visible only in the
  journal.
- The script uses one named user's kubeconfig (`nexus-platform-backup.sh:19`).
- Nothing verifies the dump before it is encrypted. The export script does verify (`scripts/hosta_export_all.sh`
  step 1, `pg_restore --list`); the daily backup does not.
- The host firewall filters input only; it has no output (egress) policy (`deploy/hosta/nexus-host-firewall.nft:30-46`).
- Outbound HTTPS to external sites goes through the corporate proxy (`HOST_A_REBUILD_RUNBOOK.md:93`).

### 1.2 Key inventory

| Key | Purpose | Lives | Readable by | In platform archive | Escrow today |
|---|---|---|---|---|---|
| K1 age identity | decrypts every platform archive | HOST-A `/etc/nexus/backup/` (root) plus the PO | root on HOST-A, the PO | no | PO copy only |
| K2 `ui2-credential-store-key` | decrypts stored device credentials (`deploy/ui2/21-secret-credential-store-key.yaml`) | k3s Secret, mounted in worker | root on HOST-A; worker pod | yes (via S2) | via K1 only |
| K3 `ui2-artefact-store-key` | wraps every artefact data key (`23-secret-artefact-store-key.yaml`) | k3s Secret, worker only (`UI2_LOCAL_KUBERNETES_DEPLOYMENT.md` §5a) | root; worker | yes (via S2) | via K1 only |
| K4 `ui2-role-binding-key`, `ui2-hostname-fingerprint-key` | role-binding integrity; manifest hostname HMAC | k3s Secrets | root; worker | yes | via K1 only |
| K5 `ui2-privacy-hmac-key` | aiview pseudonyms; rotation renames every pseudonym (`UI2_LOCAL_KUBERNETES_DEPLOYMENT.md` §5c) | k3s Secret, service | root; service | yes | via K1 only |
| K6 k3s secrets-encryption key | encrypts Secrets in the datastore | `server/cred` | root | yes (alongside the data it protects) | via K1 only |
| K7 DB role passwords (`ui2-db`) | migrate/app roles (`20-secret.yaml`) | k3s Secret | root | yes | via K1 only |

**Consequence:** K1 plus any one platform archive yields K2. K2 decrypts the stored admin credentials for the managed
firewall estate. K1 is therefore a crown-jewel key, and its custody must meet the bank's tier for privileged
credentials, not the tier for backup media.

**Reading a device backup takes three things together:** the artefact file (S4), its `backup_artefact` row with
`key_id` and `wrapped_data_key` (in S1; C7 §3.2, §4.1) and K3. An off-host copy of S4 alone cannot be read. An off-host
copy of S1+S2 without S4 opens nothing either: every row reports `artefact_unreachable` (14I AL-3). The design must
ship both, in a consistent order (§3.1).

### 1.3 Gaps found in source and records

| Gap | Evidence |
|---|---|
| G1 No off-host copy of S1-S5; one disk, one host, one failure domain | §1.1; backlog statement |
| G2 K1 sits on the host it protects; one human holds the only other copy; no dual control, no escrow, no rotation | `HOST_A_HARDENING_DRAFT.md:73-74` |
| G3 Archive is not self-sufficient: no DB roles/globals, no SSH host keys, no explicit Secret export, no k3s `server/manifests` | `nexus-platform-backup.sh:13-20` versus `hosta_export_all.sh` and `HOST_A_REBUILD_RUNBOOK.md` §1, §6 |
| G4 No integrity evidence beyond age's own authentication: no signed manifest, no hash list, no chain linking consecutive archives | script |
| G5 No pre-encryption verification of the dump; no failure alert; nothing checks freshness | `nexus-platform-backup.service`; no freshness check found in the repository |
| G6 Envelope rotation exists only as a primitive (`ArtefactStoreCipher.rewrap`); no operator entry point drives it | `ArtefactStoreCipher.java:39-46`; C7 §4.3 |
| G7 Restore of the platform archive has never been drilled end to end. "Decryption verified" happened once at first run; the 2026-09-25 rebuild restored from an export, not from this archive | `HOST_A_HARDENING_DRAFT.md:73`; `HOST_A_REBUILD_RUNBOOK.md` Status |
| G8 Retention lives only on HOST-A (14 archives, same disk, deletable by root or a mistaken `rm`) | `nexus-platform-backup.sh:23` |
| G9 The privileged agent account has logged `NOPASSWD` sudo, so it is equivalent to root in this threat model | `HOST_A_REBUILD_RUNBOOK.md` §3; PO decision 2026-09-24 |

### 1.4 Failure scenarios

| Scenario | Today | RPO / RTO today | With this design (§3.7 + §4) |
|---|---|---|---|
| F1 Disk or VM-datastore loss (incl. the hypervisor datastore hosting HOST-A) | Total loss of S1-S8. The only partial survivors are the PO's hand copies (S6, newest 2026-10-31 for the DB). Device backup history is gone. That history is the product's purpose as the BackBox successor (`BACKUP_AND_RECOVERY_ARCHITECTURE.md` §2) | RPO: weeks or total; RTO: undefined (rebuild about one day per the 2026-09-24/25 record, but without data) | Restore from the off-host bucket on a fresh host (§5.2). RPO ≤ 24 h platform, ≤ 1 h artefacts; RTO target ≤ 1 working day |
| F2 Host compromise (root) | Attacker reads every live key and every archive (K1 is on the host), deletes S4/S5, can poison future backups | Confidentiality lost; availability depends on attacker restraint | Live data is still exposed (unavoidable, §4.7). History survives (write-only account, compliance lock). Restore from a pre-compromise point with full rotation (§5.3) |
| F3 Ransomware on HOST-A | Encrypts or deletes the DB volume, artefact store and `/var/backups/nexus` together | Total loss | As F2. The off-host copy cannot be encrypted or deleted from HOST-A, and object-lock timestamps identify pre-infection objects |
| F4 Operator or agent error | Real precedents: a Secret wiped by `kubectl apply` on 2026-09-14 (`UI2_LOCAL_KUBERNETES_DEPLOYMENT.md:237-238`); PVCs with reclaim `Delete` removing their data directories (`HOST_LEDGER_HOST-A.md:392`); disk full stopping all backups for about 56 h (incident 2026-10-10) | Up to total for the affected object | Same-day restore of the object from the newest archive or bucket. Deletion on HOST-A does not propagate (copy, never mirror) |
| F5a Loss of K1 | PO copy plus the host copy; losing both makes every archive permanently unreadable | Permanent | Two independent recipients plus 2-of-3 escrow; losing one custodian or one envelope is survivable (§4.2) |
| F5b Loss of K2/K3 (live Secret wiped, see F4) | Recoverable only from an archive, which needs K1 | as F5a | Restore the Secret value from the explicit Secret export in the archive (step S1.3) |
| F6 Silent backup failure (timer stopped, disk full, kubeconfig user removed) | Nobody is told | RPO grows unbounded | Off-host freshness alarm, raised outside HOST-A (§6.1) |
| F7 Site or hypervisor-cluster loss | As F1 | as F1 | Covered only if the bucket sits in another site (question Q3) |

## 2. Requirements

- RQ1 **3-2-1-1-0:** at least three copies (live, local, off-host), two media or systems, one off-site, one immutable
  against HOST-A root, zero unverified restores (each drill must pass, §6).
- RQ2 **Separation of duties:** no identity that operates HOST-A (root, agent account, deploy pipeline) can delete,
  overwrite or shorten the retention of an off-host copy, or decrypt one without a second person.
- RQ3 **Ciphertext only leaves the host.** Platform archives leave age-encrypted. Artefacts leave as the ciphertext
  files that already exist. No plaintext, no temp file and no key material may sit outside a sealed archive (C7 §3.6).
- RQ4 **No identities in off-host metadata.** Object keys, tags, logs and manifests carry timestamps, opaque
  `device_id`s and digests, never hostnames, addresses or serials (masking law; `FileArtefactStore` already keys by
  opaque `device_id`).
- RQ5 **No new path to devices.** The shipper never talks to a network device. A restored or drill instance never
  starts device work without explicit enablement (§5, §6).
- RQ6 **Integrity is verifiable without decryption** (hashes, signatures, chain) and again after decryption (dump
  listing, row-to-file reconciliation).
- RQ7 **Fail closed and loud.** A failed or unverified shipment raises an alert and is never reported as success. A
  missing shipment is detected off the host.
- RQ8 **Key-format agility.** The custody model must survive a switch from age to an algorithm or tool the bank's
  crypto standard mandates (§4.6).

## 3. Off-host target

### 3.1 Common shipping model (applies to every option)

What ships, in this order every day:

1. **Platform archive** `platform/<UTC>/nexus-platform-<UTC>.tar.gz.age`, made complete by step S1, plus:
   - `...manifest.json`: archive SHA-256 and size, schema version, `pg_restore --list` table-data count, artefact-row
     count, the previous manifest's SHA-256 (the chain);
   - `...manifest.json.sig`: a detached signature (§3.8).
2. **Artefacts.** Every `<vendor>/<device_id>/<sha256>.enc` not yet shipped goes to
   `artefacts/<vendor>/<device_id>/<sha256>.enc`. It is copied only after verifying that `sha256(file)` equals its
   name. `artefact-*.tmp` files are never copied.
3. **Daily index** `index/<UTC>.json` (+ `.sig`): the platform object, the artefact objects added since the last index,
   and the previous index's SHA-256.

Rules:

- **The DB dump comes before the artefact sync.** Every `backup_artefact` row in a dump then has its file off-host by
  the time that day's index is written. Files written after the dump stay as orphan ciphertext until the next dump
  (unreadable without a row; harmless). The reverse order would leave dumps pointing at missing files.
- **Copy, never mirror.** A delete or prune on HOST-A (C7 A-2 automatic pruning, local archive rotation) never deletes
  off-host. Off-host expiry is governed only by the target's own retention (§3.9).
- **Content addressing makes artefacts write-once.** An artefact name is its ciphertext digest
  (`FileArtefactStore.java:136-139`), so an object is never legitimately rewritten. A second write to the same key is
  either identical or an attack, and the verifier flags it.
- The shipper runs on HOST-A as a dedicated systemd unit started after the platform backup. It reads the local-path
  directory of the artefact PVC read-only and never decrypts. It logs only object keys, sizes and digests.

### 3.2 Option A - bank SFTP server or NAS share

- **Integrity:** the shipped SHA-256 manifest and signature; server-side re-hash only if the bank runs one; no
  transport checksum beyond SSH's own.
- **Immutability:** none from SFTP itself. OpenSSH's `sftp-server` can refuse named requests such as remove and rename
  (`-P`), but cannot stop an existing file being truncated by an open-for-write. Real immutability needs either:
  - a server-side mover that takes files out of the upload chroot into an area the upload account cannot reach; or
  - read-only NAS snapshots on a schedule owned by the storage team, with snapshot deletion outside neXus's reach.
  Both are bank-side controls neXus cannot verify.
- **Network:** TCP/22 from HOST-A to the SFTP server (synthetic `sftp-backup.example.invalid`, `192.0.2.20`), direct,
  not through the HTTP proxy. The server host key is pinned (`StrictHostKeyChecking yes`, in line with the repository's
  host-key posture). NFS/SMB mounting is rejected: a mounted share is writable and deletable by root on HOST-A, so it
  fails RQ2.
- **Authentication:** an ed25519 key on HOST-A for a chrooted, upload-only account.
- **RPO/RTO:** RPO matches the shipping cadence. RTO depends on link speed (§3.11).
- **Cost and burden:** low for the bank if an SFTP landing service exists. neXus burden low to medium (a shipper using
  `sftp` in batch mode, plus a key). The verification job cannot list or read back unless a separate read account exists.

### 3.3 Option B - S3-compatible object storage with versioning and Object Lock (recommended)

- **Integrity:**
  - per-object `x-amz-checksum-sha256` or `Content-MD5`; S3 requires one on PUTs to Object Lock buckets, and the
    server rejects a mismatch;
  - artefact object keys carry their own SHA-256;
  - signed manifests and index.
- **Immutability:** bucket created with versioning plus Object Lock, default retention in **compliance** mode (no
  principal, including the storage administrator, can shorten or remove it before expiry). HOST-A's account policy:
  - allowed: `PutObject`, `GetObject`/`HeadObject` (read-back verification of ciphertext), `ListBucket`;
  - denied: every `Delete*`, `PutBucket*`, `PutObjectRetention` shortening and `BypassGovernanceRetention`.
  A second PUT to an existing key makes a new version; the locked original survives.
  The storage team must confirm that its product implements compliance mode with these semantics. S3-compatible
  products differ, so this is `UNKNOWN` until confirmed (question Q4).
- **Network:** HTTPS/443 from HOST-A to an internal endpoint (synthetic `s3-backup.example.invalid`, `192.0.2.30`).
  - The endpoint goes on the shipper's `NO_PROXY` (internal address, same handling as the k3s proxy environment,
    `HOST_A_REBUILD_RUNBOOK.md:93`).
  - TLS is verified against the corporate CA bundle and is **never** disabled. The Palo Alto verification waiver
    (`PO_DECISION_RECORD_2026_09_21_PAN_TLS_VERIFICATION_DISABLED.md`) is scoped to that vendor and does not extend here.
- **RPO/RTO:** RPO ≤ 24 h platform (daily) and ≤ 1 h artefacts (hourly sync), or near-zero DB RPO later with WAL
  archiving to the same bucket (step S9). Parallel ranged GETs keep the RTO low.
- **Cost and burden:**
  - bank: storage chargeback plus one bucket, one policy, two accounts;
  - neXus: low (a shipper using a packaged S3 client or `curl --aws-sigv4`; tool choice in the contract).
- **Strategic fit:** the enterprise target already places the artefact store under "DaaS, as file or object storage"
  (`ENTERPRISE_STACK_COMPARISON_2026_11_10.md:181`). 14I AL-1 keeps the product root-path agnostic, so the same bucket
  family survives the OpenShift move.

### 3.4 Option C - a second neXus-owned host (HOST-B)

- **Integrity:** as §3.1, verified by HOST-B.
- **Immutability:** only filesystem snapshots on HOST-B, deletable by HOST-B's root. A pull model (HOST-B pulls over
  SSH with a read-only account on HOST-A) keeps HOST-A's attacker away from the copy. A push model gives HOST-A a
  credential into HOST-B.
- **Trust domain:** the same people and the same agent (G9) administer both hosts. A credential or tooling compromise,
  or a mistaken bulk command, reaches both. That fails RQ2.
- **Network:** TCP/22 from HOST-B to HOST-A (an `ssh_allowed` set change in `nexus-host-firewall.nft`).
- **RPO/RTO:** best RTO of all (HOST-B can be a warm standby).
- **Cost and burden:** one VM plus disk plus a second host to harden, patch and register in `HOST_REGISTER.md`; highest
  neXus burden.
- **Verdict:** not acceptable as the only off-host copy for a bank. A valid later addition for RTO.

### 3.5 Option D - tape or vault through the bank's backup system

- **D1, agent on HOST-A:** the bank's backup agent backs up `/var/backups/nexus` and the artefact directory.
  - Needs an inbound port on HOST-A from the backup server.
  - The agent runs as root and can read *everything* on the host, live keys included. An include list is
    configuration, not enforcement.
  - A new party gains read access to HOST-A.
- **D2, bank copies the Option B bucket** (or the Option A landing area) to tape or vault: no change on HOST-A.
- **Integrity:** the backup system's own checks plus the shipped manifests, which neXus cannot check inside the vault.
- **Immutability:** strongest (offline or WORM, air gap).
- **RPO/RTO:** RPO follows the bank's backup schedule (often daily). RTO is hours to days (recall from vault), and
  restores depend on another team's queue.
- **Cost and burden:** low for neXus with D2; for the bank, standard but process-heavy.
- **Verdict:** right as the *second medium* (D2). D1 is rejected because it widens access to HOST-A.

### 3.6 Comparison

| | A SFTP/NAS | B Object Lock | C Second host | D2 Tape/vault (of B) |
|---|---|---|---|---|
| Survives HOST-A root / ransomware | only with bank-side mover or snapshots | **yes** (compliance lock) | weak (same admins) | yes |
| Separation of duties (RQ2) | partial | **yes** | no | yes |
| Integrity checkable by neXus | manifest only | **checksums + manifest + read-back** | yes | manifest only |
| Firewall opening | 22 out | 443 out | 22 in (pull) | none on HOST-A |
| RPO achievable | 1-24 h | **≤1 h; minutes with WAL** | ≤1 h | ~24 h |
| RTO | link-bound | **link-bound, parallel** | best | hours-days |
| neXus code/ops burden | low-med | **low** | high | none |
| Bank burden | low | low-med | low | med |
| OpenShift fit | neutral | **good** | none | neutral |

### 3.7 Recommendation

**Option B as the primary off-host copy. D2 as the second medium if bank policy requires an air gap or tape. Option A
as the fallback only if the bank has no object store with compliance-mode Object Lock**, and then only with a bank-side
mover or snapshot regime (Q5). Option C is not chosen for this item.

Reasons:
- B is the only option where immutability against HOST-A root is enforced by a party neXus does not control, and
  neXus can still verify it (HEAD returns retention mode and date).
- One outbound 443 opening, write-only credentials, and content-addressed artefacts that map one-to-one onto write-once
  objects.
- It supports later WAL archiving and the OpenShift target without a second migration.

### 3.8 Integrity design

| Layer | Mechanism | Verifies | Where checked |
|---|---|---|---|
| I1 Content | age's authenticated encryption (per-chunk AEAD) | the archive was not altered after encryption | on decrypt |
| I2 Origin | detached signature over each manifest and index (`ssh-keygen -Y sign`, OpenSSH, already on the host; signing key root-only on HOST-A, §4.5) | the manifest came from HOST-A's shipper, not an outside writer. age alone cannot prove origin: anyone holding the public recipient can make a valid archive | verifier, restore |
| I3 Transport | S3 checksum header (B) / SSH (A) | bytes arrived intact | target |
| I4 Completeness and order | hash chain across manifests and indexes | no archive or index silently removed, replaced or reordered | verifier |
| I5 Time | object-lock retention start or `Last-Modified` from the target (not from HOST-A) | which objects predate a compromise | restore (§5.3) |
| I6 Semantic | `pg_restore --list` count before encryption; post-restore reconciliation of `backup_artefact` rows against files (`MATCH`/`MISSING`/`ORPHAN` counts); sample decrypt comparing `plaintext_sha256` | the archive is usable, not merely intact | shipper (S1), drill (§6) |

A HOST-A attacker can sign forged *future* manifests with the host's signing key. I5 bounds the damage: objects locked
before the first indicator of compromise are trusted, and the signing key is rotated during compromise recovery.

### 3.9 Retention and immutability (proposed defaults; the PO and the bank decide, Q7)

- Platform archives: compliance lock 35 days; lifecycle keeps 35 dailies plus 12 month-end archives (month-end objects
  re-locked to 400 days by a lock *extension*, which compliance mode allows).
- Artefacts: a lock of at least the assumed ransomware dwell time (proposed 35 days).
  - The shipper re-extends retention daily for every artefact still in the live retained set. A device's last archive
    (the C7 A-2 floor) therefore never expires off-host while it is still retained on-host.
  - Extension needs `PutObjectRetention` limited to extension. If the product cannot express "extend only", the
    extension job runs under a separate account that HOST-A does not hold (Q4).
- Local rotation on HOST-A stays (14 archives) as the fast tier for F4.

Sizing formula: off-host capacity ≈ platform (dailies + monthlies) × archive size + live artefact set + daily artefact
delta × lock days. With the recorded 7.8 GB archive, 47 platform objects take about 370 GB. The artefact part needs the
current live size and daily delta, both `UNKNOWN` (step S0). The bucket should be planned at about 3 TB initially and
resized after S0.

### 3.10 Network path summary (synthetic values)

| Flow | Option | Source → destination | Port | Via | Bank opening needed |
|---|---|---|---|---|---|
| Ship | B | HOST-A → `192.0.2.30` | 443/tcp | direct (NO_PROXY), corporate CA | perimeter or segment rule |
| Ship | A | HOST-A → `192.0.2.20` | 22/tcp | direct, pinned host key | perimeter or segment rule |
| Restore read | B/A | replacement host or restore workstation → target | 443 / 22 | direct | same rule for the replacement host's address range |
| Vault copy | D2 | bank backup system → bucket | bank-internal | - | none on HOST-A |
| Alerts | all | verifier / bucket events → SIEM | bank-defined | - | SIEM target still open (`HOST_A_HARDENING_DRAFT.md` M4) |

An egress allowlist on HOST-A (`nexus-host-firewall.nft` output chain) is recommended but has a large blast radius
(proxy, DNS, NTP, devices, receivers). It is a separate step (S6), not a precondition.

### 3.11 RPO / RTO targets (proposed)

| | RPO | RTO | Basis |
|---|---|---|---|
| F1/F7 disk or site loss | platform ≤ 24 h, artefacts ≤ 1 h after a backup run | ≤ 1 working day to a running neXus with history; device work re-enabled by the PO | 2026-09-24/25 rebuild took about a day with surprises the runbook now records |
| F2/F3 compromise | the last pre-compromise point (I5) | ≤ 2 working days for neXus. Fleet credential rotation is a bank process, and its duration is `UNKNOWN` | §5.3 |
| F4 object-level error | ≤ 24 h | ≤ 4 h | local archive tier |

Transfer time for about 120 GB of artefacts: about 16 min at an effective 1 Gbit/s, about 80 min at 200 Mbit/s. The
link speed between HOST-A's segment and the target is `UNKNOWN` (Q8).

## 4. Key custody

### 4.1 Key classes and tiers

| Class | Keys | Must be available to | Custody tier | Location |
|---|---|---|---|---|
| KC-A Archive decryption | K1 (age identities, two recipients) | restore and drill only | **dual control; never on HOST-A** | §4.2 |
| KC-B Application runtime | K2-K7 | the running pods, always | host-resident by necessity; escrowed only inside archives (KC-A protects them) | k3s Secrets |
| KC-C Shipping | off-host writer credential, signing key | HOST-A shipper | host-resident, least privilege | `/etc/nexus/offhost/` root 0600 |
| KC-D Restore read | off-host reader credential | restore operators | bank PAM vault, checked out per incident or drill | bank |
| KC-E Storage administration | bucket policy, lifecycle, lock | bank storage team only | outside neXus | bank |

### 4.2 Archive decryption key (KC-A)

1. **Generation ceremony** on an offline, bank-managed workstation, never on HOST-A. Two people are present, and the
   minutes go to the custody register (§4.8). Output: two age identities and their public recipients.
2. **Recipient R-OPS, operational restore.** The identity file is passphrase-protected (age supports encrypted
   identity files). Custodian 1 holds the file on removable media in a safe. Custodian 2 holds the passphrase. Neither
   can decrypt alone. Alternative: a hardware token with PIN (a PIV token through an age plugin), token with custodian
   1, PIN with custodian 2. That needs the plugin on the restore workstation (Q12).
3. **Recipient R-ESC, escrow / break-glass.** The identity is split with a bank-approved threshold secret-sharing tool,
   2-of-3. Shares go in tamper-evident envelopes to three distinct roles in separate safes: proposed neXus platform
   owner, bank information-security key custodian, bank BCM/DR officer. If the bank runs an HSM or PAM vault with
   quorum approval, R-ESC may live there instead (Q11).
4. **HOST-A holds only `age-recipient.txt` with both public recipients** (age encrypts the file key to every
   recipient; either identity decrypts). After the ceremony and a successful drill, the existing identity on HOST-A is
   destroyed. Removing it is a deleting command and needs the PO's explicit go (AGENTS.md, PO amendment 2026-09-24).
   The PO's current single copy is retired into R-ESC handling, or destroyed after every archive encrypted to it has
   expired off-host.
5. **Archives already made** stay encrypted to the old identity. That identity is kept under R-ESC-equivalent custody
   until the last such archive leaves retention.

### 4.3 Application runtime keys (KC-B)

- K2-K7 must be in memory on HOST-A for the product to run. No custody design removes them from a compromised host.
- Their escrow is the archive. Step S1 adds an explicit `secrets/` export (the six Secrets named in
  `HOST_A_REBUILD_RUNBOOK.md` §1) *inside* the encrypted archive, so a clean-cluster restore (§5.3) does not need to
  boot the old k3s datastore just to read a key.
- Envelope rotation for K3 follows C7 §4.3: a new `key_id`, re-wrap of `wrapped_data_key` rows, no artefact
  re-encryption. The primitive exists (`ArtefactStoreCipher.rewrap`); an operator entry point does not (G6, step S7.2).
- K2 rotation tooling: `UNKNOWN`. It needs inspection of `CredentialStoreCipher` in the contract movement.
- Rotating K5 renames every pseudonym (`UI2_LOCAL_KUBERNETES_DEPLOYMENT.md` §5c). It is restored, not rotated, unless
  that key itself is suspected exposed.

### 4.4 Off-host credentials (KC-C, KC-D, KC-E)

- **Writer on HOST-A:** put, read-back and list, no delete, no retention shortening, no bucket administration (§3.3).
  Rotated every 90 days or per bank policy. The value never enters the repository, logs or chat.
- **Reader for restore:** a separate account in the bank's PAM vault, checked out with an approver for a drill or an
  incident. Read-only.
- **Administration:** bank storage team. neXus people hold no administration rights, so the agent account (G9) cannot
  weaken retention.

### 4.5 Signing key

An ed25519 SSH signing key in `/etc/nexus/offhost/`, root 0600. Its public half is published in the custody register
and in the restore runbook. It is rotated yearly and on any compromise. The trust anchor for verifying a signature is
that published public key, never a key read from the bucket.

### 4.6 Rotation and algorithm agility

| Key | Routine | Triggered by |
|---|---|---|
| R-OPS / R-ESC | yearly ceremony; new recipients into `age-recipient.txt` | custodian leaves or changes role, suspected exposure, failed envelope inspection |
| K2/K3 | per bank policy once tooling exists | host compromise (§5.3) |
| Writer credential | 90 days | compromise, staff change |
| Signing key | yearly | compromise |

If the bank's crypto standard rejects age (for example, it mandates FIPS-validated modules or HSM-held keys), the
design keeps its shape and swaps the archive encryption tool (Q10). Candidates:
- AES-256-GCM via a validated library, with the data key wrapped by an HSM key over PKCS#11 or KMIP;
- OpenPGP with smartcard-held keys.
Newer age releases advertise a hybrid post-quantum recipient type. Whether to adopt it ties to the repository's
`crypto_agility_pqc` theme and must be checked against the official age release notes before anyone relies on it.

### 4.7 What an attacker on HOST-A can and cannot do (after the design)

| Can | Cannot |
|---|---|
| Read the live DB, K2-K7 and current device credentials (true today too; the reason for §5.3 rotation) | Decrypt *historical* archives without K1 (K1 never on the host) |
| Stop the shipper or timer (detected off-host within 26 h, §6.1) | Delete, overwrite or shorten retention of any shipped object (compliance lock, no delete permission) |
| Upload garbage or forged archives signed with the host key | Make a forgery look older than the compromise (target-side time, I5) or break the hash chain without detection |
| Read back shipped ciphertext with the writer account | Read the bucket with any rights beyond the writer's; change bucket policy or lifecycle |
| Exfiltrate the writer credential and the signing key | Use them to destroy history; both are rotated in §5.3 |

An attacker holding the bucket alone, or one custodian's material, gets nothing. Reading the archives takes K1, which
takes two people (R-OPS) or two of three shares (R-ESC).

### 4.8 Custody register and audit

The bank holds the register (personal names do not belong in the repository). The repository holds only role names,
dates and the public recipient and signing-key fingerprints. Every check-out, ceremony, drill, envelope inspection and
rotation is logged with two signatures. Envelope integrity is inspected every quarter during the drill.

## 5. Restore when HOST-A is gone - runbook outline

The detailed runbook is written in the contract movement and extends `HOST_A_REBUILD_RUNBOOK.md`. It never contacts a
device until step 11.

### 5.1 Decide (incident lead + PO)

1. Classify the event: **R1** (F1/F4/F7, no compromise suspected) or **R2** (F2/F3, compromise suspected or not ruled
   out). If in doubt, treat it as R2.
2. Choose the restore point. R1: the newest index whose chain and signature verify. R2: the newest index locked
   *before* the earliest indicator of compromise (I5).

### 5.2 R1 - disk or site loss

1. Provision the replacement host per `HOST_A_REBUILD_RUNBOOK.md` §3-4 (OS, pinned k3s version, proxy environment,
   corporate CA). Register it in `HOST_REGISTER.md` under the HOST-A entry's ceiling.
2. Check out the reader credential from PAM (approver required).
3. Fetch the chosen index, manifest, signature and archive. Verify signature, chain and SHA-256 against the published
   signing key. Record `MATCH`/`MISMATCH` only.
4. **Key ceremony:** custodians 1 and 2 (R-OPS), or two of three escrow holders (R-ESC), decrypt on the replacement
   host. The identity is used in memory or on tmpfs and is removed after use.
5. Restore k3s server state (datastore, `cred`, `tls`, `token`, config) before the first k3s start. Restore SSH host
   keys from the archive (S1.4), so pinned SFTP senders keep working.
6. Restore DB roles, then `pg_restore`. Flyway must report the expected schema and nothing to apply.
7. Pull artefacts for the restore point into the artefact PVC directory (owner 185:0, 0600, per
   `HOST_A_REBUILD_RUNBOOK.md:110`). Verify each file's digest against its name.
8. Run reconciliation (S7.1): rows against files, as counts of `MATCH`, `MISSING` and `ORPHAN`.
9. Start service and worker with **schedules, device jobs and receivers disabled**.
10. Validate under `aiview` (masked), following `HOST_A_REBUILD_RUNBOOK.md` §5:
    - pseudonyms unchanged;
    - a stored backup opens;
    - credentials decrypt, checked offline; no device contact yet.
11. The PO re-enables device work. Then confirm the first collection, a Cyber Controller push, and the first new
    shipment plus index.
12. Close: measured RPO (age of data) and RTO (wall time), and any deviations, recorded per §6.3.

### 5.3 R2 - compromise or ransomware (additional or different steps)

- **Clean rebuild, data-only restore.** Do *not* restore the k3s datastore or SSH host keys: they may carry attacker
  persistence or exposed keys. Apply repository manifests to a fresh cluster. Recreate Secrets from the archive's
  `secrets/` export (S1.3). Restore DB and artefacts as in R1, steps 6-8.
- **Rotate after restore, before re-enabling device work:**
  - every device credential in the credential store (the bank process; assume all exposed);
  - K2 and K3 (re-wrap, S7.2), K4, K7;
  - the writer credential, the signing key, the SSH host keys (re-pin on the SFTP senders);
  - the age recipients, if the compromise could have reached a restore or custodian workstation.
  K5 is kept unless that key itself is suspected exposed.
- **Preserve evidence:** the compromised disk is preserved for forensics per bank procedure, never reused.
- **Review the restore point with security:** before re-enabling, review DB audit rows (`audit_log`) for the window
  between the restore point and detection.

## 6. Restore testing

### 6.1 Cadence

| When | What | Who | Pass criteria |
|---|---|---|---|
| Every shipment | digest = name (artefacts); checksum accepted; HEAD shows lock mode and retain-until; manifest signed; chain extends | shipper (automatic) | all `MATCH`; else alert, shipment not reported as success |
| Daily | freshness: newest index ≤ 26 h old; lock present | verifier **outside HOST-A** (bank monitoring on the bucket, or SIEM heartbeat rule) | alert on breach. A dead HOST-A must still raise this alert |
| Monthly | sample restore of artefacts without the custodians: fetch N random off-host objects, verify digest, decrypt in memory on HOST-A with the live K3 and the row's wrapped key, compare `plaintext_sha256` | neXus ops | all `MATCH`; proves off-host copies are usable with today's key |
| Quarterly | full R1 drill into an isolated, disposable environment (scratch VM or isolated namespace, egress to devices denied by NetworkPolicy and firewall), with the R-OPS ceremony; envelope inspection | neXus ops + 2 custodians + PO witness | §5.2 steps 3-10 pass; RTO/RPO measured; environment destroyed afterwards |
| Yearly | R-ESC reconstruction (2-of-3) on a test archive; R2 tabletop including the credential-rotation plan; custodian roll-call; key rotation | all custodians, security, BCM | reconstruction works; gaps logged |

The drill environment holds real credentials while it exists. It gets HOST-A's protection level, never touches a
device, runs no receiver, and is destroyed with the destruction recorded.

### 6.2 What the drill must never do

Contact a network device; start schedules, failover or collection; expose unmasked identities in evidence; leave
decrypted material on persistent storage.

### 6.3 Evidence record (masked)

One file per drill under `docs/operations/` (proposed `RESTORE_DRILL_<YYYY_MM_DD>.md`), containing only:
- date, drill type, restore-point timestamp, opaque object keys;
- signature, chain and digest results as `MATCH`/`MISMATCH`;
- `pg_restore` table-data count, schema version;
- reconciliation counts (`MATCH`/`MISSING`/`ORPHAN`), sample-decrypt count and result;
- measured RPO and RTO;
- custodian *roles* present, deviations, follow-ups.
No hostnames, addresses, serials, usernames or key material appear in it.

## 7. Change plan - small steps

Each step is separately reviewable and reversible. Host-side steps need PO authorization under the HOST-A ceiling, and
deleting steps need an explicit PO go. Code steps need targeted tests plus the gates named in AGENTS.md. Nothing below
is authorized by this DRAFT.

### 7.1 Common to every option

| Step | Change | Where | Verification | Rollback |
|---|---|---|---|---|
| S0 | Measure: current artefact store size, daily delta, archive size and duration, link throughput to the chosen target | host read-only, recorded in `HOST_LEDGER_HOST-A.md` (masked) | numbers recorded | n/a |
| S1.1 | Add `pg_dumpall --globals-only` to the archive | `deploy/hosta/nexus-platform-backup.sh` | drill restores roles | revert script |
| S1.2 | Verify the dump (`pg_restore --list` table-data count > 0) before encrypting; fail otherwise | same | forced-failure test | revert |
| S1.3 | Explicit `secrets/` export of the six ui2 Secrets inside the encrypted archive (never written outside the work dir; work dir under `umask 077`, removed by trap) | same | R2 drill recreates Secrets | revert |
| S1.4 | Include `/etc/ssh/ssh_host_*` and k3s `server/manifests` | same | R1 drill | revert |
| S1.5 | Write `manifest.json` (+ chain) and sign it | same + new signing key (S2.2) | verifier checks | revert |
| S1.6 | `OnFailure=` alert unit; replace the personal kubeconfig with a dedicated read-scoped one | `nexus-platform-backup.service`, new unit | forced failure alerts | revert units |
| S1.7 | Static tests for the script and units (presence of the steps above; no identity file referenced; recipients file only) | `tools/tests/` (pattern of `test_ui2_deployment_manifests.py`) | pytest | revert |
| S2.1 | Custody ceremony; `age-recipient.txt` with R-OPS and R-ESC | host file + bank register | quarterly drill decrypts with each recipient | old recipient kept until S2.3 |
| S2.2 | Generate the signing key; publish its public half in the register and runbook | host | signature verifies off-host | regenerate |
| S2.3 | Destroy the identity on HOST-A (PO go) after the first successful drill under both recipients | host | `UNKNOWN` until done; ledger entry | none (by design) |
| S3 | Shipper unit (`nexus-offhost-ship.service` + timer, after the backup; hourly artefact pass). Order per §3.1; copy-only; digest check; skip `.tmp`; masked log lines | `deploy/hosta/` new files | first shipment + read-back; static tests | disable timer; objects stay (locked) |
| S4 | Daily signed index + chain | shipper | verifier | revert |
| S5 | Off-host freshness alert and an "Off-host copy" status in System health (status written by the shipper to a root-owned file a small read-only hostPath mount exposes, or SIEM only, per Q15) | bank monitoring / neXus service (optional code) | alert on stale index | remove |
| S6 | HOST-A egress allowlist (output chain) | `deploy/hosta/nexus-host-firewall.nft` | all existing outbound flows inventoried first | reload previous ruleset |
| S7.1 | Reconciliation tool (rows against files, counts only, masked) | worker CLI or ops script; code + tests | drill output | remove |
| S7.2 | Operator entry point for K3 re-wrap (C7 §4.3) over `ArtefactStoreCipher.rewrap`; ledgered, audited | worker; code + tests (`:worker:test`, architecture tests) | rotate in a drill environment | old `key_id` stays valid until all rows re-wrapped |
| S8 | First quarterly drill + evidence file; update `HOST_A_REBUILD_RUNBOOK.md` into the §5 runbook | docs | §6 criteria | n/a |
| S9 (later, optional) | Continuous WAL archiving to the same bucket for DB RPO in minutes (a sidecar archiver; separate contract) | `deploy/ui2/40-database-statefulset.yaml` | PITR drill | remove sidecar |

### 7.2 Option-specific deltas

| Option | Extra neXus changes | Bank provides |
|---|---|---|
| B (recommended) | S3 client choice (packaged client or `curl --aws-sigv4`); per-PUT checksum and lock headers; retention-extension job for the artefact live set (§3.9) | endpoint, bucket with versioning + compliance Object Lock, writer and reader accounts, policy per §3.3, firewall rule 443, CA chain, lifecycle rules, capacity |
| A (fallback) | `sftp -b` batch shipper, pinned host key, ed25519 key; no read-back unless a reader account exists | SFTP landing service, upload-only chrooted account (remove/rename/setstat refused), server-side mover or NAS snapshot regime, rule 22 |
| C (not chosen) | HOST-B build and hardening, pull job, snapshots, register entry | VM, disk, rule 22 inbound from HOST-B |
| D2 (second medium) | none | backup job over the bucket, WORM or offline media, restore SLA, quarterly test-recall participation |

## 8. Product Owner questions (what the bank must provide or decide)

**Target and storage**
- Q1 Does the bank offer an internal S3-compatible object store with versioning and Object Lock? If not, which landing
  service: SFTP, or NAS with storage-team snapshots?
- Q2 Who in the bank owns the bucket (storage team, contact role), and what is the chargeback model?
- Q3 Is the target in a different site or failure domain from HOST-A's hypervisor cluster and datastore?
- Q4 Does the product implement *compliance*-mode retention that no administrator can shorten, and can a policy allow
  lock *extension* while denying shortening? If not, may a separate retention account outside HOST-A run the extension
  job?
- Q5 For the SFTP fallback: is a server-side mover or a snapshot regime available, and who controls snapshot deletion?
- Q6 Initial capacity: about 3 TB proposed, to be revised after S0. Is that acceptable?
- Q7 Retention: 35 days of dailies, 12 month-ends, artefact lock 35 days with live-set extension. Does bank policy
  require anything longer (regulatory record-keeping)?

**Network**
- Q8 Firewall rule from HOST-A's address to the target (443 for B, 22 for A); the same rule for the replacement-host
  range and the restore workstation; expected link throughput.
- Q9 Confirm the target is reachable without the HTTP proxy, and which CA signs its certificate.

**Keys and custody**
- Q10 Does the bank's crypto standard accept age (X25519, ChaCha20-Poly1305)? Or must archive encryption use
  FIPS-validated modules or HSM-held keys?
- Q11 Who are the custodian roles: R-OPS custodian 1 and 2, and the three R-ESC share holders? Is there a bank HSM or
  PAM vault with quorum approval that should hold R-ESC instead of envelopes?
- Q12 Passphrase-protected identity file or hardware token for R-OPS? If a token, is the plugin approved for the
  restore workstation?
- Q13 Which bank-approved tool performs the 2-of-3 split?
- Q14 May the agent destroy the age identity on HOST-A after the first two-recipient drill (S2.3), and should the PO's
  existing copy be retired into escrow or destroyed after its archives expire?

**Operations**
- Q15 Where do backup alerts go (SIEM target, still open as `HOST_A_HARDENING_DRAFT.md` M4), and may the bank's
  monitoring watch bucket freshness?
- Q16 Is a scratch VM or isolated environment available quarterly for restore drills, and who witnesses them?
- Q17 Should the bank backup system copy the bucket to tape or vault (D2), and what is its restore SLA?
- Q18 RPO/RTO targets in §3.11: accept, or does BCM classify neXus differently? Is WAL archiving (S9) wanted?
- Q19 Scope: do packet-analysis captures and the report store need off-host copies, or are they excluded as
  regenerable or retention-bound?
- Q20 On compromise (R2), who in the bank owns the fleet credential-rotation process, and what is its expected
  duration?

## 9. Acceptance criteria for the future FROZEN contract

- OHK-1 Each daily platform archive appears off-host within 2 h of creation with a valid signature, chain and target
  checksum. A failure alerts and is not reported as success.
- OHK-2 Every artefact present on HOST-A for more than 1 h has an off-host object whose digest equals its name.
- OHK-3 HOST-A's credential cannot delete or shorten retention of any object (tested negatively in a drill).
- OHK-4 No age identity exists on HOST-A. Both recipients decrypt in a drill. 2-of-3 reconstruction is proven yearly.
- OHK-5 R1 drill meets the RTO and RPO in §3.11. R2 tabletop has a written rotation plan.
- OHK-6 Off-host object keys, metadata and logs contain no hostname, address or serial (privacy gate plus a sample
  inspection in the drill).
- OHK-7 No drill or restore contacts a network device before the PO re-enables device work.
- OHK-8 The freshness alert fires when shipping stops, including when HOST-A is powered off.

## 10. Risks and UNKNOWNs

- `UNKNOWN`: current artefact store size and daily delta; link throughput; whether the bank's object store has
  compliance-mode semantics; K2 rotation tooling; the bank's crypto standard. Each one blocks freezing the parameters
  it governs.
- A drill environment holding real credentials is itself a high-value target (§6.1 controls).
- Off-host copies extend where sensitive ciphertext lives. Its confidentiality rests entirely on K1/K3 custody, which
  is why §4 is not optional.
- Today's single-custodian K1 is a live risk until S2. If the PO's copy and HOST-A are lost together before S2, every
  archive is unrecoverable.
- Privacy finding while researching (reported as file + location + classification, values not repeated):
  `docs/design/HOST_A_REBUILD_RUNBOOK.md` §6, first bullet, contains a real organisation name and an internal proxy
  hostname (classification: environment identity). This design does not touch it. A separate DOCS clean-up is
  recommended.

## 11. Repository sources

- `deploy/hosta/nexus-platform-backup.sh`, `nexus-platform-backup.service`, `nexus-platform-backup.timer`,
  `nexus-host-firewall.nft`, `nexus-aide.conf`
- `deploy/ui2/20-secret.yaml` … `25-secret-privacy-hmac-key.yaml`, `30-database-pvc.yaml`, `35-artefact-store-pvc.yaml`,
  `36-packet-analysis-pvc.yaml`, `37-report-store-pvc.yaml`, `52-worker-deployment.yaml`
- `scripts/hosta_export_all.sh`; `tools/delivery/release_snapshot.py`
- `ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/ArtefactStoreCipher.java`
- `ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/artefact/FileArtefactStore.java`
- `docs/design/HOST_A_HARDENING_DRAFT.md` (DRAFT), `docs/design/HOST_A_REBUILD_RUNBOOK.md` (EXECUTED record),
  `docs/operations/HOST_LEDGER_HOST-A.md`, `docs/operations/UI2_LOCAL_KUBERNETES_DEPLOYMENT.md`
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §6.3, §10 (FROZEN);
  `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` §3.2, §3.5, §3.6, §4, §9.7 (FROZEN)
- `docs/design/PO_DECISION_RECORD_2026_09_14I_ARTEFACT_LOCATION_OPERATOR_RETRIEVAL_DEVIATION_AND_MANAGEMENT_SERVERS.md`
  AL-1..AL-4 (FROZEN); `PO_DECISION_RECORD_2026_09_22_BACKUP_HTTP_DOWNLOAD_WITH_RBAC.md` (RATIFIED);
  `PO_DECISION_RECORD_2026_09_15A_THE_DEVELOPMENT_HOST_AND_WHAT_AN_AGENT_MAY_DO_ON_IT.md` (FROZEN);
  `PO_DECISION_RECORD_2026_09_21_PAN_TLS_VERIFICATION_DISABLED.md` (RATIFIED, scope only)
- `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §2; `docs/design/PCP_STORAGE_ENGINE_DECISION.md` gap #9 (DRAFT);
  `docs/design/BACKUP_LINE_IMPLEMENTATION_AUDIT_2026_09_15.md` AL-4 row (DRAFT);
  `docs/design/FABLE_BACKUP_ENGINE_SECURITY_REVIEW.md` §3; `docs/design/ENTERPRISE_STACK_COMPARISON_2026_11_10.md`
  lines 181, 185; `docs/design/INCIDENT_2026_10_10_BACKUP_FREQUENCY_DISK_FULL.md`
- `project/QUEUE.md` (backlog line for `recovery_offhost_key_custody`)
