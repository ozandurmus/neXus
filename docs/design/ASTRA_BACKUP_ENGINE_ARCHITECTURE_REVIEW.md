# Backup Engine Architecture Review
**Reviewer:** Astra, Lead Software Architect  
**Target:** `ui2-backup` Engine, neXus Project (2027 BackBox Replacement)  
**Verdict:** [APPROVED WITH RECOMMENDATIONS]

Following an evaluation of the Product Owner's requirements against the repository's architecture contracts (`BACKUP_AND_RECOVERY_ARCHITECTURE.md`, `BACKUP_RECOVERY_CONTRACTS.md`) and vendor administration manuals (Check Point Gaia R80.40/R81.x, PAN-OS 11.x), here is the architectural blueprint:

---

## 1. Microservice Structure & K3s Deployment
- **Component:** Independent microservice `ui2-backup` (Service + Engine, port 8086) within the K3s cluster namespace `ui2`.
- **Storage:** Dedicated `ui2-backup-vault-pvc` PersistentVolumeClaim mounted at `/vault/recovery` (completely decoupled from `ui2-db` and evidence stores).
- **Communication:**
  - `ui2-service` forwards frontend backup requests to `ui2-backup`.
  - `ui2-backup` orchestrates backup tasks, schedule management, retention purging, and deviation diffs.

---

## 2. Ingestion Pipeline & Transport Mechanics
- **Palo Alto Networks (PAN-OS Firewalls & Panorama):**
  - **Artifact:** Device State Bundle (`export device-state` .tgz) as primary recovery artifact, plus `running-config.xml` as human-readable companion.
  - **Transport:** Streamed directly from the device via HTTPS XML API (`GET /api/?type=export&category=device-state&key=<key>`).
  - **Advantage:** Zero disk write on the firewall itself. In-memory stream to encrypted vault storage.
- **Check Point Gateways (Gaia R80.40/R81.x, VSX):**
  - **Artifact:** Gaia Backup (`.tgz`) for OS/network/product configuration; Gaia Snapshot for weekly full OS rollback.
  - **Transport (Worker Pull vs Device Push):**
    - *Astra Recommendation (BackBox Parity):* Worker Pull via SCP/SFTP over the established SSH session.
    - *Sequence:* Pre-flight disk space check (`df -P /var/log` ≥ 3x backup size) -> Issue Clish `add backup local` -> Poll status -> Pull `.tgz` archive via SCP -> Post-flight delete local file from firewall (`delete backup <name>`).
    - *Reason:* Opening inbound SFTP/SCP listener ports on the K3s cluster accessible from dozens of remote firewall management networks violates zero-trust egress/ingress architecture. Pull over outbound management SSH preserves existing firewall pinholes.
- **Check Point Management / MDS:**
  - **Artifact:** `mds_backup` / `migrate export` archive.
  - **Transport:** Triggered via SSH script, pulled via SCP, and verified.

---

## 3. Cadence, Retention & Pruning Engine
- **Cadence:**
  - Daily Backup: Configurable cron (default: `0 2 * * *` at 02:00 UTC).
  - Weekly Snapshot (Check Point): Configurable cron (default: `0 3 * * 0` Sunday 03:00 UTC).
- **Retention Pruning:**
  - Daily Backups: Retained for 30 days (1 month). Background retention job runs daily to purge expired backups.
  - Weekly Snapshots: Retained with depth of 2 snapshots (older than 2 deleted).
  - Deletions are append-only recorded in `retention/ledger.json` (tombstone records with reason, timestamp, and policy id).

---

## 4. Deviation & Comparison Engine (Major vs Minor)
- **Problem Statement:** Binary backup archives (`.tgz`) are opaque recovery blobs. Extracting multi-gigabyte archives for diffing is heavy and breaks recovery plane opacity.
- **Astra Architectural Solution:**
  - Every backup execution pairs with an evidence-plane snapshot (`running-config.xml` for PAN; `show configuration` / Gaia clish text for CP).
  - The Deviation Engine performs semantic AST diffing on the paired configuration text:
    - **MAJOR Deviations (Alert Triggered):** Interface changes (IP/subnet/VIP/down), Static/BGP/OSPF routing changes, Access Rule additions/deletions, NAT modifications, Admin user additions/role escalations, Cluster/HA state changes.
    - **MINOR Deviations (Normal):** Timestamp updates, dynamic sequence numbers, session counters.
  - When a major deviation occurs between the current backup and previous backup, an alert is recorded in `backup_deviations` and flagged in the UI.

---

## 5. UI Integration & Manual Operations
- **API Contracts:**
  - `POST /api/v2/backup/devices/{id}/run-now`: Triggers on-demand backup (type: `backup` or `snapshot`).
  - `GET /api/v2/backup/devices/{id}/history`: Lists historical backups with verification badges (V1-V3), size, and deviation status.
  - `GET /api/v2/backup/artefacts/{id}/export`: Streams decrypted archive for operator inspection with mandatory audit logging.
  - `PUT /api/v2/backup/policies`: Configures schedule intervals and retention periods.