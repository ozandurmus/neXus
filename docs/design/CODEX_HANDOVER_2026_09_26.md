# neXus — handover to Codex (2026-09-26)

**Status:** handover record, non-authoritative (authority: `AGENTS.md`, FROZEN contracts in `docs/design/`,
`project/QUEUE.md`). Written by the Claude engineering session that worked with the Product Owner in the "6-Poo" and
"7-Poo" chats (2026-09-22 → 2026-09-26). The prompt to start Codex with is the last section.

## 1. What neXus is, in one paragraph
On-premises firewall-estate operations for a bank: 122 devices under management (63 Check Point gateways/ClusterXL/VSX
and a Multi-Domain Server; 40 Palo Alto firewalls and a Panorama; an Infoblox Grid Manager with 7 members; a Radware
Cyber Controller with 4 DefensePros; a Symantec Management Center with 7 ProxySG/Reporter/WSS devices; a FortiManager
with 9 FortiGates; Cisco ASA and Pulse Secure ready to add). Principle SEE → VERIFY → TRACE → RECOVER → OPERATE. Every
read is an audited job; nothing is written to a device except the gated backup writes (Check Point `add backup local`,
the ASA `backup` archive, DefensePro export). The Product Owner (PO) is a security expert, not a developer; he chats in
Turkish, decides fast, and looks at the product every day under the masked `aiview` persona.

## 2. How we work (unchanged for Codex)
- **Read first:** `AGENTS.md` (constitution), `AI_START_HERE.md`, `CURRENT_STATE.md`, `AI_HANDOVER.md`, then the one
  contract the task names. `roles/ENGINEER.md` gives the reading order. Chat with the PO in **Turkish**; every
  repository artefact in **English**.
- **Privacy law:** never echo raw hostnames, addresses, serials or account names in chat, commits, docs or logs
  (`TopologyNamePseudonymizer` masks them for aiview). Query the live DB with counts and opaque ids. The gate
  `python3 scripts/repository_privacy_check.py` must PASS before every commit.
- **Every device command needs a gate row** (`gate_registry`, migration + `gate_registry_fixture.yaml`) before it is
  issued. A parse-scope extension of a gated command needs no new gate. Secrets never leave the encrypted artefact.
- **Measure first:** an unmeasured vendor output is logged as shape/field names (letters→`a`, digits→`9`, no values) on
  the first run, then parsed. Never guess a vendor semantic; write `UNKNOWN`.
- **Queue:** `project/QUEUE.md` via `python3 scripts/project_queue.py add|status|note` (never edit the JSON).
- **Commits:** end with the Co-Authored-By attribution line the harness gives (model name and its no-reply address); no "Generated with" footer. Push to
  `origin` (GitHub) and `hosta` (the host's bare repo) — `git push origin main && git push hosta main`.
- **External reviews** only through `scripts/consult_*.py` (Astra = `codex exec -m gpt-6-astra`, Fable =
  `claude -p --model claude-fable-5-1`); never simulate a reviewer.

## 3. HOST-A: where it runs, how to deploy
- Host: `ssh aiadmin@<host>`, the address is the first line of `~/.config/nexus/hosta` (never echo it). k3s namespaces
  `ui2` (service, worker, configuration, compliance, db) and `ui2-build` (kaniko). `export KUBECONFIG=~/.kube/config`.
- DB: `kubectl -n ui2 exec -i ui2-db-0 -- sh -c 'psql -U "$POSTGRES_USER" -d ui2 -At'`. Audit triggers need
  `set_config('app.actor_fingerprint', …, true)` and `set_config('app.action_id', …, true)` in any manual write.
- **Every new `V*.sql` is dry-run first:** pipe `BEGIN; <file>; ROLLBACK;` into the psql above with `-v ON_ERROR_STOP=1`.
- **Deploy:** `bash scripts/hosta_deploy.sh` (exit 4 = a job in flight, retry after 25 s; the loop we used:
  `for i in $(seq 1 30); do bash scripts/hosta_deploy.sh; rc=$?; [ $rc -ne 4 ] && break; sleep 25; done`). It pushes
  `main`, builds in-cluster, rolls service/worker/compliance and waits for 1/1. **Then** set `ui2-configuration` to the
  same digest by hand (`kubectl -n ui2 set image deploy/ui2-configuration <container>=<service image>`), because
  `run_build.sh` does not. Verify: `site 200`, `schema <n> true`.
- Worker logs: `kubectl -n ui2 logs deploy/ui2-worker --since=5m | grep '\[FMG\]'` etc. Logs are masked-safe by
  construction (counts, shapes, class names); keep them so.
- Tests: `cd ui2 && ./gradlew :worker:test :service:test :capability-registry:test :job-engine:test --continue -q`
  (the one pre-existing failure is `ProjectPlanReaderTest`, a queue-classification check, ignore);
  `cd ui2/frontend && npx tsc --noEmit -p . && npx vitest run`. Integration tests need a real Postgres 16 (none on the
  Mac; they fail by design there).
- Browser checks: the Claude-in-Chrome extension with the PO's Edge (the `aiview` session) — it drops sometimes; the PO
  reconnects it. Screenshots: only under aiview, addresses additionally masked in-page before capture.

## 4. What was built in 6-Poo and 7-Poo (chronological, with the migration that carries it)
1. **HOST-A rebuilt** (2026-09-25) for neXus alone; runbook `docs/design/HOST_A_REBUILD_RUNBOOK.md`; pod limits raised.
2. **Infoblox** Grid Manager: backup (V64 path), Collect with grid members and member facts (V71/V72).
3. **Observed facts follow every read** (PO rule): hostname/model/version refreshed on every job, never fill-if-absent.
4. **HTTPS inventory job** (`https_inventory_collect`): Infoblox members, Cyber Controller device tree, DefensePro via CC.
5. **Check Point management server Collect** 243 s → 40 s; hostname read with `show hostname`; Spark identity left to
   discovery; backup digest timeout scales with archive size (V74); Gaia Embedded refused honestly.
6. **Symantec Management Center** (V75/V76): add, confirm, Collect (managed devices), ProxySG backup through the MC
   (`show version`, `show configuration`; 4/4, 9 s). **V82:** ProxySGs importable by discovery, read through the MC
   (`show interface all`, `show ip-route-table`, tolerant parsers, shapes logged first), MC's own identity from
   `/api/system/version` (name, version, build — measured 2026-09-26, wired in the last commit).
7. **Device onboarding flow (V77, FROZEN):** identity → inventory → configuration as one server-side flow, 5 s
   scheduler (`OnboardingFlowService`), STOPPED with reason + retry endpoint, "Onboarding · n/3" chip, backup opt-in at
   the end, a step the vendor lacks is skipped and **not shown**. Contract `docs/design/DEVICE_ONBOARDING_FLOW_CONTRACT.md`.
8. **Cisco ASA (V78/V79, FROZEN):** SSH interactive shell, privilege-15 check, confirm (`show version`), inventory
   (interfaces, routes, failover, mode), backup = configuration text **then** the ASA archive (`backup /noconfirm
   location disk0:/nexus-<hex>.tar.gz` → SCP pull → delete by own name); partial when the archive fails. Not yet run
   on a real ASA. `docs/design/CISCO_ASA_CONTRACT.md`.
9. **UI council** (`UI_EFFECTIVENESS_COUNCIL_2026_09_25_*`): Astra + Fable; decisions: one device screen, executive
   Overview, vendor-native facts, ASA both backups.
10. **Overview = executive dashboard** (`EXEC_OVERVIEW_DESIGN_2026_09_25_FABLE.md`, amendment D of the Overview
    contract): estate map (every device a square, vendor groups, worst evidenced condition, red ring = no archive),
    three facts, compliance by framework, most widespread critical failures keyed by vendor+control, change/cluster/
    software population bars. Service adds `estate` and per-device critical counts; controls carry `vendors`.
11. **Fortinet (V80/V81/V85, FROZEN):** FortiGate over SSH (status, interfaces + routes per VDOM, backup = top-level
    `show`, `--More--` answered in-session, context prompts recognised); FortiManager over JSON-RPC (status, managed
    devices, its own interfaces/routes, **discovery** of ADOMs and FortiGates with HA members); **FortiGate
    configuration plane** (V85): `show` parsed to sections per VDOM, secrets withheld — validated live: 104 sections,
    4,496 settings, 48 withheld. `docs/design/FORTINET_CONTRACT.md`. 9 FortiGates imported and onboarded (4 needed
    HOST-A added to the FortiGate admins' trusted hosts — a 10-entry limit; merged two /32 into one /31).
12. **Panorama (V83):** inventory (management interface, default gateway, HA) and backup (device-state, else
    running-config XML; set-format CLI; partial when missing).
13. **Pulse Secure / Ivanti (V84):** confirm, inventory (network part of the XML export), backup (the four exports of
    Backbox trail 34411066, export passphrase from the credential store). Not yet run on a real appliance.
14. **One device screen** (PO 2026-09-25): Configuration is a tab of Devices (device and cluster; Check Point, Palo
    Alto, Fortinet), the Config rail item is gone, old `?screen=configuration` links redirect with the tab open,
    change/cluster-diff filters on the Devices list, "Read configuration, all" next to Bulk Collect.
15. **Fixes found by the full tour (2026-09-25):** aiview job-log leak (JobPage record bypassed masking), Cyber
    Controller HTTP 500 under five concurrent sessions (reads now queue per host), Management Center row "not
    enrolled" on Backups, FortiManager HA member duplicate identifiers, management tree keyed FortiGates as Check Point,
    configuration vendor CHECK (V86) refusing FortiGate.

Live state at handover: schema **86**, 122 devices, Bulk Collect 120/120 admitted (3 unreachable CP gateways), Read
configuration 102/120 (18 vendors without a read), all HOST-A pods 1/1.

## 5. Open items, in priority order (what Codex should pick up)
1. **FortiManager physical link state:** the Interfaces tab now shows the configured state (`status: enable` over SSH,
   measured 2026-09-26, mapped to up/down like a FortiGate's `set status`). The physical link state is a separate read,
   `diagnose hardware info nic <port>` per port — gate it (one row), read it in the same SSH session, prefer it over the
   configured state when present.
2. **First real runs still pending:** Cisco ASA (PO adds it with fwadm), Pulse Secure (PO adds it), SMC discovery →
   ProxySG import (parsers measure-first), Panorama backup (device-state vs XML), FortiGate backup.
3. **Cluster view for ASA failover pairs and FortiGate HA** (`asa_fortinet_cluster_view`): group members and compare
   like ClusterXL/PAN HA; FortiManager discovery already carries the HA members.
4. **ASA configuration plane** (`asa_fortinet_configuration_plane`, FortiGate half done).
5. **Infoblox richer data** (PO asked 2026-09-26 what else is meaningful): candidates over WAPI — DNS views and
   authoritative zone counts, DHCP network/range counts, grid NTP/DNS resolver settings, license state, HA pair state
   per member, per-member service health beyond the current up/down. Propose, then gate and read.
6. **UI leftovers from the council:** a standalone device still shows a "Cluster members" tab; aiview's Add device
   dialog shows "no permission" beside a "Create credential" link; software "behind newest" needs a vendor-recommended
   build register before it returns; nightly snapshot table for trends.
7. **Cyber Controller known_hosts** after the host rebuild (Radware case, PO) — the 03:00 CC self-backup fails.
8. Older P0s in the queue (job failure reason recording, cluster membership at enrollment, aiview masking audit,
   off-host key custody, TLS trust) are unchanged.

## 6. Where things are (files)
- Worker vendor executors: `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/backup/{https,asa,fortinet,pan,radware}`;
  discovery mappers in `worker/discovery`; configuration processors in `worker/configuration/{cp,pan,fortinet}`;
  `WorkerClaimLoop` routes capability ids → executors; `Ui2WorkerMain` wires them.
- Service: `service/device/{DeviceAddSingleService, onboarding/OnboardingFlowService, inventory/InventoryCollectService,
  configuration/ConfigurationCollectService, backup/BackupCollectService}`, `service/discovery/DiscoveryRunService`,
  `service/management/ManagementTreeService`, `service/overview/OverviewService` (estate), privacy in
  `service/privacy/PrivacyMaskingResponseBodyAdvice`. Routes gated in `security/SecurityWebMvcConfig`.
- Frontend: `ui2/frontend/src/screens/{InventoryScreen, InventoryPanels, ConfigurationDetail, configurationProjection,
  OverviewScreen, overview/EstateMap, BackupScreen}`, `shell/AddDeviceDialog`, `auth/adminApi.ts`.
- Migrations: `ui2/service/src/main/resources/db/migration/V64…V86`; gate fixture
  `ui2/capability-registry/src/main/resources/capabilities/gate_registry_fixture.yaml`.
- Contracts: `docs/design/VENDOR_BACKUP_CONTRACTS_2026_09_22.md`, `DEVICE_ONBOARDING_FLOW_CONTRACT.md`,
  `CISCO_ASA_CONTRACT.md`, `FORTINET_CONTRACT.md`, `OVERVIEW_EXCEPTION_SCREEN_CONTRACT.md` (amendments C, D),
  `EXEC_OVERVIEW_DESIGN_2026_09_25_{ASTRA,FABLE}.md`, `UI_EFFECTIVENESS_COUNCIL_2026_09_25_*.md`.
- Backbox trails the PO shared (outside the repo, `~/Downloads/trail_*.log`): 34409263 ASA, 23488971 FortiGate,
  34411066 Pulse. They are the measured request sequences; never copy their values.

## 7. The prompt to start Codex with
```
You are the neXus ENGINEER (roles/ENGINEER.md). Read, in this order: AGENTS.md, AI_START_HERE.md, CURRENT_STATE.md,
AI_HANDOVER.md, docs/design/CODEX_HANDOVER_2026_09_26.md. Then say "SESSION START" per AI_START_HERE.md.

Ground rules you must keep: chat with the Product Owner in Turkish, repository artefacts in English; never echo a raw
hostname, address, serial or account name anywhere (aiview masking law); every new device command gets a gate row in a
migration plus gate_registry_fixture.yaml before it runs; every new V*.sql is dry-run in BEGIN/ROLLBACK on the live DB
before deploy; deploy only with scripts/hosta_deploy.sh and then set ui2-configuration to the same image digest; watch
every deploy and job you start to its end state in the same turn; ask the PO before any deleting or irreversible
command on HOST-A; never use HOST-A as a jump server; run scripts/repository_privacy_check.py before each commit;
commits end with a Co-Authored-By line and carry no "Generated with" footer; push to origin and hosta; measure a vendor
output's shape before parsing it and write UNKNOWN rather than a guess; external reviews only via scripts/consult_*.py.

Current live state: HOST-A schema 86, 122 devices, all pods 1/1, one device screen (Configuration is a tab of Devices),
executive Overview with the estate map, onboarding flow V77, vendors: Check Point, Palo Alto (+Panorama), Infoblox,
Radware (CC + DefensePro), Symantec MC (+ProxySG via MC), FortiManager + FortiGate (with configuration plane), Cisco ASA
and Pulse Secure implemented but not yet run on a real device.

Your first tasks, in order (details in CODEX_HANDOVER §5):
1. FortiManager physical link state: gate and read `diagnose hardware info nic <port>` over SSH (see CODEX_HANDOVER §5.1),
   deploy, verify the Interfaces tab shows the link state.
2. When the PO adds a Cisco ASA / Pulse Secure / runs SMC discovery: follow the onboarding jobs, read the measure-first
   logs, fix parsers, then trigger and measure the backups; record each first run as a queue note.
3. Cluster view for ASA failover pairs and FortiGate HA clusters.
4. Infoblox richer data: propose the WAPI reads to the PO, gate, implement.
Everything else: project/QUEUE.md (write only via scripts/project_queue.py).
```
