# Radware Cyber Controller's own backup — push receiver and CLI gate entries

## Status

**DRAFT — PENDING PRODUCT OWNER APPROVAL AND A FIRST MEASUREMENT.** Requested by the Product Owner on 2026-09-24:
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

## Design

### The receiver

- **Where:** inside the neXus worker (the process that already runs every backup job), an SFTP-only endpoint built
  on Apache MINA SSHD (`sshd-core`, `sshd-sftp`; Apache Software Foundation, Maven Central — a new dependency).
  Not the host's own SSH: nothing is configured on HOST-A itself, no sudo.
- **Port:** one TCP port, exposed on HOST-A as a Kubernetes NodePort (proposed **30222**), with
  `externalTrafficPolicy: Local` so the caller's real source address reaches the check below.
- **Who may connect:** only the management address of an **enrolled, enabled Radware Cyber Controller** in neXus.
  Checked when the TCP connection is accepted, before the SSH handshake; any other source is closed and logged
  (address class only). The Product Owner's own network controls (firewall) stay in front of this.
- **Authentication:** one user, `nexus-receiver`, password only, the password being a credential-store entry the
  Product Owner names (never in the repository or a manifest). No keys, no keyboard-interactive.
- **What it can do:** the SFTP subsystem only — no shell, no exec, no port or agent forwarding, no X11. A write-only
  virtual file system: the one accepted operation is **create-and-write of `<token>.tgz`**, where `<token>` is a random
  value a running backup job is waiting for. No read, list, stat of other names, rename, delete or directory.
  Anything else is refused.
- **Where the bytes go:** straight into the encrypted artefact store as they arrive (the same sink every backup uses).
  **The backup never touches a disk in plaintext.** A size bound (proposed 2 GiB) refuses anything larger.
- **When:** a token is valid only while its job waits (at most the job's deadline, proposed 30 min), for one upload.
- **Host key:** the receiver's own ed25519 host key, generated once and kept in a Kubernetes Secret in `ui2`; its
  fingerprint is shown in the product so the operator can compare it with what the Cyber Controller presents.

### The job (worker, nightly and on demand)

1. SSH to the Cyber Controller with its enrolled credential (the AD account), restricted CLI.
2. `system backup config create nexus-<token>`.
3. `system backup config export nexus-<token> sftp://nexus-receiver@<HOST-A address>:<port>/<token>.tgz` — the CLI
   is expected to ask to trust the receiver's host key and for the password; the worker answers from the Secret's
   fingerprint and the credential store (MEASURE FIRST: the prompts, and whether the URL accepts a port).
4. The receiver streams the upload into the artefact store; the job compares the received size with
   `system backup config list`'s entry for `nexus-<token>`, and records the SHA-256 of what was stored.
5. `system backup config delete nexus-<token>` — only after the file is stored (nothing is deleted before).
6. Schedule: nightly, proposed **03:00 Europe/Istanbul** (after the 02:00 MDS export), plus Backup Now on the Cyber
   Controller row. The Cyber Controller's own scheduler-generated backups are never touched.

## CLI gate entries (Cyber Controller restricted CLI, interactive SSH session)

Ten items per entry: 1 why; 2 class; 3 context; 4 timeout; 5 retry; 6 frequency; 7 session reuse; 8 unsupported
behaviour; 9 secret-bearing output risk; 10 safe telemetry.

| # | Command | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `system backup config create nexus-<token>` | make the backup | CLASS_1 recovery-write (creates a backup file on the device) | Radware Cyber Controller 10.13, restricted CLI | 600 s | none | once per job | the job's session | error text or no prompt return → job failed, nothing to delete | none expected | outcome, duration |
| 2 | `system backup config export nexus-<token> sftp://…` | push it to neXus | CLASS_1 recovery-write (the device sends a file out) | same | 600 s | none | once per job | same | refused/timeout → job failed; the backup is deleted by #4 | the prompt answers carry the receiver password (never logged) | outcome, bytes received |
| 3 | `system backup config list` | size check of `nexus-<token>` | CLASS_0_READ | same | 60 s | none | once per job | same | name absent → size check UNKNOWN, recorded | backup names only | size of our entry |
| 4 | `system backup config delete nexus-<token>` | leave nothing behind | CLASS_1 recovery-write (deletes only the file this job created, by exact name) | same | 120 s | once | once per job, after #2 stored or after a failure | same | failure → job ends CLEANUP_FAILED, named in the reason | none | outcome |

## MEASURE FIRST

1. Whether the export URL accepts `:port` (if not, the design needs another answer before implementation).
2. The export's prompts (host-key trust, password) and its success/failure text.
3. That the Cyber Controller can reach HOST-A on the chosen port (network path).
4. That `delete` exists under `system backup config` and removes by exact name.

## Not in this document

- The `full` backup (59 GiB / 1 h) — parked by the Product Owner.
- Any other vendor pushing to the receiver; the receiver accepts Radware Cyber Controllers only.
