# Quantum Spark (Gaia Embedded) backup: 'backup settings to sftp server <HOST-A> filename <f> file-encryption on password <p> username <u> password <p>' pushed to a HOST-A SFTP receiver (Cyber Controller model); needs gate entries, a receiver Match block for the Spark addresses, and a first measured run -- 5 Spark gateways are backup targets without an archive

status: planned · target: docs/design/VENDOR_BACKUP_CONTRACTS_2026_09_22.md

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Quantum Spark (Gaia Embedded) backup: 'backup settings to sftp server <HOST-A> filename <f> file-encryption on password <p> username <u> password <p>' pushed to a HOST-A SFTP receiver (Cyber Controller model); needs gate entries, a receiver Match block for the Spark addresses, and a first measured run -- 5 Spark gateways are backup targets without an archive

Target (full): docs/design/VENDOR_BACKUP_CONTRACTS_2026_09_22.md

2026-11-14 backlog truth: still open (P2, size S). Today: 2 Spark gateways have no backup; job fails daily until the receiver credential is set. Evidence: Code shipped 688bafe4 (V105) but live jobs: 78 FAILED credential_unresolvable, 0 cp_spark_sftp_backup ok, 0 Spark archives

2026-11-14 verified live: last 7 days 10 cp_spark_sftp_backup jobs, all FAILED credential_unresolvable (Spark backup or receiver credential missing), none succeeded.
