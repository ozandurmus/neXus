# Backup Engine Security Architecture Review
**Reviewer:** Fable, Enterprise Security Architect  
**Target:** `ui2-backup` Engine, neXus Project (2027 BackBox Replacement)  
**Verdict:** [APPROVED WITH RECOMMENDATIONS]

Following a rigorous review of `BACKUP_AND_RECOVERY_ARCHITECTURE.md`, `BACKUP_RECOVERY_CONTRACTS.md`, and `PRIVACY_AND_DATA_HANDLING.md`, the architectural design for the Recovery Plane is structurally sound and adheres to neXus engineering laws.

The following security blueprint and detailed recommendations must be enforced to maintain operational safety and compliance:

---

## 1. Plane 3 (Recovery) vs Plane 2 (Evidence) Isolation
**Finding:** The isolation defined between Plane 2 (`VERIFY` - redacted evidence) and Plane 3 (`RECOVER` - full-fidelity secrets) is correct and load-bearing.
**Recommendations:**
- **UI/Support Bundle Hardening:** The UI and support diagnostics must exclusively read `manifest.json`. Under no circumstances should `artifact.enc` bytes or plaintext streams traverse the browser or enter a support bundle. Any deviation here is a CLASS 3 (SECRET) data exposure.
- **Manifest Integrity:** `manifest.json` fields (like `detail`) must be rigorously value-free regarding secrets.

---

## 2. Target Firewall Safety & Preconditions
**Finding:** Backup operations constitute an `operational-write` and carry the risk of production resource exhaustion (e.g., `/var/log` filling up).
**Recommendations:**
- **Pre-flight Checks:** The 3x free-space precondition via `/var/log` probe (e.g., `show diskspace` or `df -P`) must be enforced globally as a blocking gate.
- **Fail-Closed Execution:** If disk space is `UNKNOWN`, the system MUST abort the `add backup local` sequence. No optimistic execution is permitted.
- **Strict Cleanup:** The deletion of the device-side local backup (`rm -f -- /var/log/CPbackup/backups/<name>`) must occur even if the SCP fetch fails.

---

## 3. Cryptographic Key Custody
**Finding:** Envelope encryption with an external key vault is specified and approved.
**Recommendations:**
- **Envelope Design:** Implement AES-256-GCM (for AEAD properties) per artifact as the DEK. The root KEK (vault key) MUST reside completely outside the `SECURITYEXPERT_RECOVERY_ROOT` and ideally interface with `DEPLOY.1` opt-in secret vaults.
- **Algorithm Agility:** Adhere strictly to the `crypto_agility_pqc` scheme.
- **Export & Restorations:** Even though restoration is currently deferred (`RB.6` OP.2 bar), any future mechanism that exports an artifact must force operator re-authentication and emit an unbypassable audit event.

---

## 4. Transport Hardening
**Finding:** The design leverages vendor-native retrieval (HTTPS streaming for PAN, SCP for CP Gaia).
**Recommendations:**
- **Transport Security:** For Check Point SCP, enforce `StrictHostKeyChecking=yes` reusing the exact pinned keys from the inventory lifecycle. For PAN, TLS certificate validation must remain strict.
- **Ingestion Security:** Execute decryption and archive parsing within a chrooted, network-isolated container boundary. This mitigates the risk of a compromised firewall returning a malicious payload (e.g., Zip Slip or exploit payloads masquerading as backups).

---

## 5. Audit & Compliance Invariants
**Finding:** The GFS retention policy and append-only tombstone ledger are well-defined.
**Recommendations:**
- **Append-only Tombstones:** Enforce the `retention/ledger.json` append-only requirement. A deleted backup must always leave a cryptographic tombstone identifying what was purged, when, and by what policy.
- **Fail-Closed Audit on Retrieval:** Any future retrieval, export, or decryption event MUST write a tamper-evident audit log. If the audit subsystem fails to write or is unreachable, the backup download/retrieval MUST fail-closed.