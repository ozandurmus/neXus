# Radware Cyber Controller's own backup — push receiver and CLI gate entries

## Status

**APPROVED — PRODUCT OWNER, 2026-09-24** (the four CLI commands and the 03:00 schedule: "ok", after the design and
the measurements below; implemented as V69). **OPEN: the worker's mount of `/var/lib/nexus-cc/in` is a `hostPath`,
which the FROZEN deployment contract forbids (UI2_0_B1_01C OS-3) — not deployed until the Product Owner amends it.** Requested by the Product Owner on 2026-09-24:
the Cyber Controller should push its own backup into a directory neXus owns ("direk kendi sunucumuzdaki bir dizine
bırakıp"), not be pulled through an undocumented web download. He set the boundary: neXus takes no inbound traffic
except from permitted addresses, on a specific port ("izinli şekilde belirli IP'lerden kabul ediyor… spesifik bir
porta bağlarız"). This is a **security-boundary change** (the first inbound file path into neXus) and adds CLI commands
on a network device: it authorizes nothing until the Product Owner approves it. Everything marked MEASURE FIRST is
unverified until the first live run records it.

## Evidence (measured 2026-09-24, see RADWARE_CYBER_CONTROLLER_BACKUP_API_GATE_ENTRIES.md)

- `system backup config` backups are ~35–37 MB; `full` is ~59 GiB and took about an hour — **config only** here.
- `system backup config export <name> <target>` pushes only: `file://`, `ssh://`, `sftp://`, `ftp://`, `scp://`.
- SSH with the AD account lands in the restricted Cyber Controller CLI; there is no SFTP/SCP pull.

## Design (revised 2026-09-24 after measurement — the Product Owner's choice)

**Measured:** the export URL takes no port — `sftp://user@host:/path` always goes to TCP 22 (Palo Alto traffic log:
Cyber Controller → HOST-A, dst port 22, rule 333, allow). The Product Owner then chose **HOST-A's own SSH on its
existing address** ("varolan IP'den sftp alalım. Backbox da böyle çalışıyor"), accepting that the file rests briefly
unencrypted on HOST-A's disk and that HOST-A's sshd configuration changes (he configured it; the agent holds no sudo).

### The receiver (HOST-A, configured by the Product Owner)

- User `nexus-cc` (uid 1001, group `nexuscc` gid 2600, shell `/usr/sbin/nologin`), password in the neXus credential
  store only.
- Chroot `/var/lib/nexus-cc` (root:root 755; `/srv` was rejected by sshd — it is not root-owned and is left
  untouched), upload directory `/var/lib/nexus-cc/in` (nexus-cc:nexuscc 2770).
- `/etc/ssh/sshd_config`, appended at the end: `Match User nexus-cc Address <Cyber Controller>` → `ChrootDirectory`,
  `ForceCommand internal-sftp -u 0027`, password auth, no forwarding, no TTY; `Match User nexus-cc Address
  *,!<Cyber Controller>` → no password, no key, `ForceCommand /usr/sbin/nologin`. Backup copy
  `sshd_config.bak-nexus`.
- The worker mounts `/var/lib/nexus-cc/in` (hostPath) with supplemental group 2600: it reads the upload into the
  encrypted artefact store and deletes it. Plaintext on disk only between the upload and that read.

### The job (worker, nightly and on demand)

1. SSH to the Cyber Controller (its enrolled credential, the AD account), restricted CLI.
2. `system backup config create nexus-<token>`.
3. `system backup config export nexus-<token> sftp://nexus-cc@<HOST-A>:/in/<token>.tgz` — measured prompt
   `Password:` (no host-key question); answered from the Cyber Controller's "backup receiver" credential. Success
   text measured: `Export completed.` and `The configuration backup was successfully exported to <url>.tar` — the
   Cyber Controller **appends `.tar`**, so the file is `/in/<token>.tgz.tar`.
4. The worker streams `<token>.tgz.tar` into the artefact store (SHA-256 recorded), compares its size with
   `system backup config list`'s entry, and deletes the upload.
5. `system backup config delete nexus-<token>` — only after the file is stored.
6. Schedule: nightly, proposed **03:00 Europe/Istanbul**, plus Backup Now on the Cyber Controller row. The Cyber
   Controller's own scheduler-generated backups are never touched.

## CLI gate entries (Cyber Controller restricted CLI, interactive SSH session)

Ten items per entry: 1 why; 2 class; 3 context; 4 timeout; 5 retry; 6 frequency; 7 session reuse; 8 unsupported
behaviour; 9 secret-bearing output risk; 10 safe telemetry.

| # | Command | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `system backup config create nexus-<token>` | make the backup | CLASS_1 recovery-write (creates a backup file on the device) | Radware Cyber Controller 10.13, restricted CLI | 600 s | none | once per job | the job's session | error text or no prompt return → job failed, nothing to delete | none expected | outcome, duration |
| 2 | `system backup config export nexus-<token> sftp://…` | push it to neXus | CLASS_1 recovery-write (the device sends a file out) | same | 600 s | none | once per job | same | refused/timeout → job failed; the backup is deleted by #4 | the prompt answers carry the receiver password (never logged) | outcome, bytes received |
| 3 | `system backup config list` | size check of `nexus-<token>` | CLASS_0_READ | same | 60 s | none | once per job | same | name absent → size check UNKNOWN, recorded | backup names only | size of our entry |
| 4 | `system backup config delete nexus-<token>` | leave nothing behind | CLASS_1 recovery-write (deletes only the file this job created, by exact name) | same | 120 s | once | once per job, after #2 stored or after a failure | same | failure → job ends CLEANUP_FAILED, named in the reason | none | outcome |

## Measured (2026-09-24, run by the Product Owner)

1. Port in the export URL: not supported (always 22). → HOST-A's own SSH.
2. Prompts: `Password:` only; success text as above; `.tar` appended to the target name.
3. Network path Cyber Controller → HOST-A:22: open (rule 333), upload of a ~36 MB config backup in about 2 s.
4. `system backup config delete <name>`: prompt `Are you sure you want to delete this backup (Y/N)?`, answered `y`,
   then `Remove completed.` Still MEASURE FIRST: the failure text of an export (wrong password, full disk).

## First live run (2026-09-24, Backup Now by the Product Owner)

- Run 1: create (11 s, 36,136 K listed) → export answered `Password:` but HOST-A logged `Failed password for
  nexus-cc` (the stored credential did not match the account's password) → no upload → the run deleted its own
  backup (`Remove completed.`). Nothing left on either side. The PO reset the password on both sides.
- Run 2: **COMPLETED.** create → export `Export completed.` → upload present → **37,027,872 bytes stored** (the
  listed 36,136 K plus the `.tar` wrapper) → upload deleted (inbox empty afterwards) → `Remove completed.` on the
  Cyber Controller. Nightly 03:00 now runs for it (backup target on).

## Not in this document

- The `full` backup (59 GiB / 1 h) — parked by the Product Owner.
- Any other vendor pushing to the receiver; the receiver accepts Radware Cyber Controllers only.
