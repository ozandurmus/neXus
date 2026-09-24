# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

# Snapshot (2026-09-25)
HOST-A was reinstalled for neXus alone (Ubuntu 26.04.1, k3s v1.36.4) and neXus restored from the 2026-09-24 export:
site 200 (80/443), schema 70, 110 devices (108 enrolled), 901 artefact rows / 1,515 store files, no startup errors.
Pod limits raised (service/worker 8Gi·4CPU, compliance/configuration 2Gi·1CPU) after an OOM at 2Gi.
Local `main`, `origin/main` and the host's `~/nexus.git` are the same commit (2dfb6b9).
Not yet checked in the browser by the Product Owner (see "Exact next action").

# How to work on HOST-A now (read these first)
- Access: `ssh aiadmin@<host>` — the host is the first line of `~/.config/nexus/hosta`. Never echo the address.
- Rules: `docs/design/PO_DECISION_RECORD_2026_09_24_HOST_A_OWNED_BY_NEXUS_AGENT_SUDO.md` — logged sudo
  (`journalctl _COMM=sudo`); **ask the PO in chat before any rm/truncate/mkfs/DB drop/PVC-namespace-backup delete**;
  reboot, proxy config and inbound 443 forwarding need no ask; **never** use HOST-A as a jump server.
- `kubectl` on the host: `export KUBECONFIG=~/.kube/config`. Setup log the PO can watch: `~/nexus-setup.log`.
- Deploy: `scripts/hosta_deploy.sh` only. It pushes `main` to the host's bare repo `~/nexus.git` (no GitHub
  credential on the host), runs `~/run_build.sh`, updates service and compliance, and replaces the worker only when no
  job is CLAIMED/EXECUTING. After a build set `ui2-configuration` to the same digest (run_build.sh does not).
- Every new `V*.sql`: dry-run in `BEGIN … ROLLBACK` against the live DB first.
- Outbound internet only via the corporate proxy (`tekprxv2:80`); direct GitHub TLS is re-signed by the corporate
  firewall. The build needs the git-ignored `ui2/.ca/*.pem` and the Gradle zip under `ui2/.gradle-home/…` (both on
  the host) and the `corp-ca` ConfigMap in `ui2-build` — details: `docs/design/HOST_A_REBUILD_RUNBOOK.md` §6.
- Watching long work: start it detached on the host, then poll in calls of **under 9 minutes** each and report
  progress between polls. Do not chain `sleep`s or block for 10 minutes — the PO reads that as "stuck".
- Chat in **Turkish**; repository artefacts in English. aiview/masking law: no raw hostnames, IPs, serials in chat.

# Recent session changes (2026-09-23 .. 25)
- Radware: DefensePro backup and confirm through the Cyber Controller (V67/V68); Cyber Controller discovery tree and
  DefensePro import; Cyber Controller's own config backup pushed by SFTP to the host receiver `nexus-cc` (V69,
  first live run completed 37 MB); OS-3a push-inbox hostPath amendment.
- Backups: orphan-backup delete screen (V70); summary cards moved below the table.
- Devices/Config: compact filter row, one global search that also filters lists; credential change after add;
  atomic add-single; vendor-free credentials (V66); role "appliance" (V65).
- Overview no longer browser-cached (aiview could briefly see an admin's unmasked copy).
- Deploy safety: worker never replaced under a running job; run_build.sh failures stop the watcher at once.
- Host rebuild: `scripts/hosta_export_all.sh`, `docs/design/HOST_A_REBUILD_RUNBOOK.md` (executed), host decision record.

# Exact next action
1. PO, in the browser as **aiview**: pseudonyms unchanged from before the rebuild; a Check Point and a Palo Alto
   **Collect** succeed; an **old backup's Contents** opens; a **Cyber Controller Backup Now** completes
   (`nexus-cc` has a password now; it must equal the credential "SFTP Receiver").
2. Watch tonight's schedules: 01:00 discovery refresh, 02:00 MDS export, 03:00 Cyber Controller backup, 23:00 inventory.
3. Radware follow-ups: measure `getcfg` with a DefensePro Backup Now; give the Cyber Controller device a name
   (no documented REST identity endpoint — research summary pending to the PO); DefensePro REST reads worth gating
   (policies `rsIDSNewRulesTable`, profiles, signature DB version via `/monitor?prop=`) — not yet proposed.

# Backlog
`project/QUEUE.md` is the planning source (write only through `scripts/project_queue.py`). Added 2026-09-25 from this
session: `hosta_rebuild_real_env_acceptance` (P0, in progress), `scheduled_fleet_backup_enable_decision`,
`cp_backup_empty_status_poll_loop`, `radware_defensepro_getcfg_measurement` (P1), `radware_cc_identity_and_defensepro_reads`,
`mds_ha_second_mds_read`, `backup_remaining_vendors`, `hosta_export_include_build_namespace`,
`deploy_manifest_tests_eight_failures` (P2), `run_build_sets_configuration_image`, `corporate_inspection_ca_verify_with_it`,
`old_export_copies_disposal` (P3). Older open P0/P1 items (PAN serial identity, private replay, off-host key custody,
job failure reasons, aiview masking audit, cluster DIFF tuning, CP backup free-space parse, ...) are unchanged there.

# Open items / risks
- `scripts/hosta_export_all.sh` does not export the `ui2-build` namespace (`corp-ca`); add it.
- `tests/test_ui2_deployment_manifests.py`: 8 pre-existing failures (configuration/compliance manifests) — separate task.
- The 98 GB export still sits in `/home/aiadmin/nexus-export-20260924T2026Z/` and on the PO's transfer server;
  deleting either is the PO's call (ask first).
- The corporate inspection CA in `ui2/.ca/` was taken from the chain the firewall presents (SHA-256 10:BF:DE:4E…29:42);
  the PO may want it checked against the one IT publishes.
- In the previous chat session, browser tools were blocked by the auto-mode safety check for the rest of that
  conversation; a fresh session does not carry that block.

# Test delta
- Service, worker, persistence suites green at the last run; frontend vitest 205 tests green.
