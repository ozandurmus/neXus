# Cyber Controller SFTP push to HOST-A fails after the reinstall: stale known_hosts entry on the controller disables password auth; clear it (case with Radware) or switch the receiver address spelling; then delete leftover backup nexus-4ae0578d8cd2c6c4 and re-run Backup Now

status: in_progress · target: docs/design/HOST_A_REBUILD_RUNBOOK.md

2026-09-25 00:20: PO tried a fresh CLI user and the alternate address spellings; every attempt still closed at preauth without a password (109 attempts in 15 min). Parked by the PO: a Radware case will be opened tomorrow. Until then the 03:00 Cyber Controller backup fails the same way; backup nexus-4ae0578d8cd2c6c4 remains on the controller.
