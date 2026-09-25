# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

# Snapshot (2026-09-25, morning)
HOST-A was reinstalled for neXus alone (Ubuntu 26.04.1, k3s v1.36.4) and neXus restored from the 2026-09-24 export:
site 200, **schema 72**, 111 devices, all pods 1/1. Pod limits raised (service/worker 8Gi·4CPU, compliance/configuration
2Gi·1CPU) after an OOM at 2Gi. Seven deploys ran during the night of 2026-09-25 (see "Recent session changes"): Infoblox
Grid Manager enrolled, backed up and collected for real; observed device facts now follow every read (the upgraded MDS
shows R82); HTTPS vendors have their own inventory job; backups only back up. Build `NXS-LOCAL-0369` (automated_validated)
plus these night changes -- a new build record is still to be written by the next session.
PO acceptance of the rebuild: 3 of 4 checks passed; the Cyber Controller Backup Now is blocked by the controller's stale
known_hosts entry (case with Radware).

# Who did this and how (2026-09-23 .. 25)
One Claude engineering session (`ENGINEER` role), hands-on with the Product Owner in Turkish chat — not the `nexus-po`
skill, no workers. Loop: PO reports what aiview shows → agent changes code → tests → `hosta_deploy.sh` watched → PO
checks. PO decisions went straight into decision records / contract amendments in the commit that used them.
Relay record: `relay/NXS-LOCAL-0366-radware-ui-deploy-safety-hosta-rebuild.json` (movement NXS-LOCAL-0369; the
SESSION CLOSE report is `relay/NXS-LOCAL-0366-session-close-report.json`, next actor: PO).

# What owning HOST-A changes
No other product and no shared disk any more (the cause of the 2026-09-24 outages is gone). The host layer — k3s and
its proxy env, registry mirror, SFTP receiver accounts for pushing products, build inputs — is now neXus's to keep and
is recorded in the runbook. The agent has logged sudo, asks first before deleting anything, never jumps onward.

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
1. PO, in the browser: the Grid Manager (`GarantiDNS`) after deploy 7 -- run Collect now once more so the device name
   returns to the grid's own name (the first Collect had renamed it to the Grid Master's host name); check Interfaces
   / Routing per member and the Grid members tab. Then decide what else the member data should say.
2. Radware: open the Radware case for the Cyber Controller's known_hosts (HOST-A's host key changed with the reinstall;
   its lftp/OpenSSH client disables password auth on the mismatch). Until then the 03:00 Cyber Controller backup fails
   and backup `nexus-4ae0578d8cd2c6c4` sits on the controller. DefensePro inventory reads need PO approval and gate
   entries (backlog `https_inventory_collect_radware`); the Cyber Controller's own Collect (managed device list) is built
   but not yet run for real.
3. Write the build record for the night's changes (V71 grid members, V72 member facts, observed facts refresh,
   https_inventory_collect) and advance the queue; run the full test suites once more in daylight.
4. Left to the PO: `https_configuration_collect` design (DefensePro getcfg as a configuration run), the fleet backup
   schedule decision, the 98 GB export copies, the corporate CA check.

# Backlog
`project/QUEUE.md` is the planning source (write only through `scripts/project_queue.py`). Added 2026-09-25 from this
session: `hosta_rebuild_real_env_acceptance` (P0, in progress), `scheduled_fleet_backup_enable_decision`,
`cp_backup_empty_status_poll_loop`, `radware_defensepro_getcfg_measurement` (P1), `radware_cc_identity_and_defensepro_reads`,
`mds_ha_second_mds_read`, `backup_remaining_vendors`, `hosta_export_include_build_namespace`,
`deploy_manifest_tests_eight_failures` (P2), `run_build_sets_configuration_image`, `corporate_inspection_ca_verify_with_it`,
`old_export_copies_disposal` (P3). Older open P0/P1 items (PAN serial identity, private replay, off-host key custody,
job failure reasons, aiview masking audit, cluster DIFF tuning, CP backup free-space parse, ...) are unchanged there.

# Open items / risks
- Infoblox (2026-09-25, night): Grid Manager enrolled, backed up (1,019,189 bytes, gzip verified; fixes: WAPI version
  from the page title, `application/force-download` on the download GET) and collected for real through the new
  `https_inventory_collect` job (7 members, 17 interfaces, 1 static route, member facts V72 shown in the Grid members
  tab). Backups no longer read members. `~/wapi_member_shape.sh` and `~/wapi_member_ifaces.sh` on HOST-A are the
  masked measurement scripts the PO ran / can run.
- Observed facts follow every read (PO decision record 2026-09-25): confirm, inventory and configuration runs refresh
  hostname / model / version; the fill-if-absent rule is gone. Validated on the upgraded MDS (R81.20 -> R82).
- **Cyber Controller Backup Now fails after the rebuild (2026-09-25):** the Cyber Controller's known_hosts holds
  HOST-A's old host key, so its OpenSSH client disables password auth and the SFTP push to `nexus-cc` never sends a
  password (sshd DEBUG3: `next methods="publickey,password"`, then the client closes). Fix is on the Cyber Controller
  (remove the stale known_hosts entry for HOST-A's address) -- PO's call; the old host keys are not in the export.
  The 03:00 scheduled Cyber Controller backup will fail the same way until then. Runbook §6 records it.
- aiview now also holds `role:backup_admin` and `role:compliance_admin` (PO 2026-09-25: aiview's boundary is
  masking, not admin actions); `role:security_admin` deliberately withheld because it could revoke its own
  `role:replay_viewer` binding and unmask itself.
- ui2 pod limits raised on the dedicated host (service/worker 8Gi/4 CPU, compliance/configuration 2Gi/1 CPU) after
  ui2-service was OOM-killed at 2Gi opening a device page; manifests in `deploy/ui2/` carry the same values.
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
