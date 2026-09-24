# NXS-LOCAL-0369 — Radware via Cyber Controller, device-list and backup UI, deploy safety, HOST-A rebuilt as neXus's own host

status: automated_validated · started: 2026-09-23 · completed: 2026-09-25 · movement: IMPLEMENTATION

Radware DefensePro backup/discovery through the Cyber Controller and the controller's own SFTP-pushed backup
(V65–V70), device-list/backup/add-device UI rework, deploy that never replaces the worker under a running job, and
HOST-A reinstalled for neXus alone and restored from a verified export. Deployed; the Product Owner's aiview
acceptance on the rebuilt host is pending.

## How this build was run

One engineering session (Claude, `ENGINEER` role) worked directly with the Product Owner over three days, hands-on and
sequential: the Product Owner reported what he saw in the browser (aiview), the agent changed code, deployed to HOST-A
with `scripts/hosta_deploy.sh`, watched the rollout and answered in chat (Turkish). No workers were dispatched and the
`nexus-po` skill was not used; decisions were taken by the Product Owner in chat and written into decision records or
contract amendments in the same commit that used them. Commits `e24965b` .. `ce0802f` on `main` (pushed to `origin` and
to the host's bare repository).

## What changed, by area

**Devices and Configuration lists.** Vendor / Scope / State filters are one compact row of dropdowns above the list, a
gear menu holds View and Sort, and the top-bar search is the only search box: on a list screen it filters the list
(`shell/listSearch.ts`, `deviceMatchesSearch`).

**Add device and credentials.** Role "Firewall" reads "Security device"; role `appliance` for Infoblox and Radware
(V65); a credential no longer carries a vendor (V66); add-single is all-or-nothing (secrets written in the same
transaction before the confirm job is admitted); a device's login credential, export passphrase or backup-receiver
credential can be changed after adding it ("Change credentials…"), and a failed add can be retried with another
credential or abandoned. A route with two wildcards (not resolvable by the gate chain) was replaced by explicit routes
and a guard test.

**Radware.** Per `docs/design/RADWARE_CYBER_CONTROLLER_BACKUP_API_GATE_ENTRIES.md` (APPROVED): DefensePro confirm and
backup go through the Cyber Controller REST API with the existing AD credential (V67; logout path fixed V68); the
Cyber Controller's device list is a manager tree in discovery and DefensePro devices import from it (keyed
`radware|ormId`, passphrase required). Per `docs/design/RADWARE_CYBER_CONTROLLER_OWN_BACKUP_RECEIVER.md` (APPROVED): the
Cyber Controller's own configuration backup is pushed by its restricted CLI over SFTP to a receiver account on HOST-A
(`nexus-cc`, chrooted, SFTP-only, allowed from the controller's address only), picked up by the worker through a
hostPath inbox (contract amendment OS-3a in UI2_0_B1_01C), and scheduled nightly at 03:00 (V69).

**Backups.** Summary cards moved below the fleet table; the Cyber Controller is listed; backups of a device no longer in
neXus ("orphans") can be deleted from the screen with a reason, recorded in `backup_artefact_deletion_request` and
purged by the worker (V70).

**Privacy.** The Overview response is no longer browser-cacheable (`no-cache`, `Vary: Cookie`): aiview could briefly see
an administrator's unmasked copy.

**Deploy safety.** `run_build.sh` updates service and compliance, then waits until no job is CLAIMED/EXECUTING before
replacing the worker (a deploy had rolled the worker during a running MDS export); `scripts/hosta_deploy.sh` stops at
once when the build fails and pushes the code to the host first.

**HOST-A rebuilt.** A co-hosted product filled the shared root disk repeatedly and took neXus down. The Product Owner
reinstalled the host for neXus alone (`docs/design/PO_DECISION_RECORD_2026_09_24_HOST_A_OWNED_BY_NEXUS_AGENT_SUDO.md`,
RATIFIED). neXus was exported with `scripts/hosta_export_all.sh` (database, keys, configuration, 98 GB encrypted store,
checksums verified), the export stayed off the workstation, and the host was rebuilt per
`docs/design/HOST_A_REBUILD_RUNBOOK.md` (EXECUTED; §6 records what the steps needed in practice: proxy-only outbound,
corporate inspection CA, the Gradle distribution, `corp-ca`, the bare repository). Pod limits were raised after an
out-of-memory at 2 Gi.

## Migrations

V65 role `appliance` · V66 credential not tied to a vendor · V67 Cyber Controller HTTPS gate rows · V68 logout path ·
V69 `backup_receiver` secret purpose and the Cyber Controller CLI gate rows · V70 `backup_artefact_deletion_request`.
Each was dry-run in `BEGIN … ROLLBACK` against the live database before rollout.

## Validation

- Automated: service, worker and persistence suites green at the last run; frontend vitest 205 tests green;
  `tests/test_ui2_deployment_manifests.py` has 8 failures that predate this build (backlog
  `deploy_manifest_tests_eight_failures`).
- Real environment (HOST-A, before the rebuild): a Cyber Controller own backup completed (37 MB, stored encrypted);
  Cyber Controller discovery listed its DefensePro devices; an MDS export completed while the deploy waited.
- Real environment (after the rebuild, 2026-09-25): site 200 on 80/443, schema 70, 110 devices (108 enrolled),
  901 artefact rows / 1,515 store files, all pods 1/1, no startup errors, receiver inbox writable by the worker.
- Not yet: the Product Owner's aiview acceptance on the rebuilt host (pseudonyms unchanged, a Check Point and a Palo
  Alto collect, an old backup opens, a Cyber Controller Backup Now) — backlog `hosta_rebuild_real_env_acceptance`
  (P0). A DefensePro `getcfg` backup has not run against a real DefensePro — `radware_defensepro_getcfg_measurement`.

## What owning HOST-A changes from now on

- The agent works through its own account with sudo; every privileged call is in the journal
  (`journalctl _COMM=sudo`). Deleting or irreversible commands still need the Product Owner's yes in chat first; a
  reboot, the host proxy and inbound 443 forwarding do not. HOST-A is never a jump server.
- There is no co-hosted workload any more: the whole 1 TB disk is neXus's, no separate loop volume, no shared log
  pressure. The 2026-09-19 "no sudo, incumbent untouched" limits are superseded for this host.
- Host-level pieces are now the agent's to maintain and are recorded in the repository: k3s and its proxy
  environment, the registry mirror, the SFTP receiver accounts for pushing products, the build inputs.
- The code reaches the host through its own bare repository (no GitHub credential on the host).
- Follow-ups: export the `ui2-build` namespace too, let `run_build.sh` set the configuration image, verify the
  inspection CA with IT, decide on the old export copies (backlog).
