# Cisco ASA over SSH: confirm, inventory, configuration-text backup (V78)

status: automated_validated · target: 

All reads over one SSH interactive shell (PO 2026-09-25: no HTTPS/ASDM on the ASAs). Contract docs/design/CISCO_ASA_CONTRACT.md (DRAFT until the first real run). Open PO decision: add the ASA 'backup' archive step (writes to flash, needs ssh scopy).

PO 2026-09-25: both backups in order -- text, then the ASA archive (backup to disk0, SCP pull, delete by own name); a failed archive stores the text and fails the job as partial. Gates V79. Contract FROZEN.
