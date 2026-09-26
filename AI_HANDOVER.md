# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

# Snapshot (2026-09-26)
HOST-A (neXus's own host since the 2026-09-25 reinstall): site 200, **schema 86**, 122 devices, all pods 1/1. Vendors
live: Check Point (gateways, MDS), Palo Alto (firewalls, Panorama), Infoblox, Radware (Cyber Controller + DefensePro),
Symantec Management Center (+ ProxySG via the MC), FortiManager + 9 FortiGates (discovery-imported, inventory and
configuration plane). Implemented, awaiting the first real device: Cisco ASA, Pulse Secure. One device screen
(Configuration is a tab of Devices), executive Overview with the estate map, server-side onboarding flow (V77).
The full handover for the next engineering tool is `docs/design/CODEX_HANDOVER_2026_09_26.md` — read it.

# Who did this and how (2026-09-22 .. 26)
One Claude engineering session, hands-on with the Product Owner in Turkish chat (the "6-Poo"/"7-Poo" chats). Loop: PO
looks at aiview → agent changes code → tests → `hosta_deploy.sh` watched to 1/1 → set `ui2-configuration` to the same
digest → PO checks. PO decisions went into contracts/decision records in the commit that used them. Two external
reviews were run through `scripts/consult_*.py` (UI council; executive Overview design).

# How to work on HOST-A now
- Access `ssh aiadmin@<host>`; the host is the first line of `~/.config/nexus/hosta` (never echo it).
  `export KUBECONFIG=~/.kube/config`. Logged sudo; **ask the PO before any rm/truncate/DB drop/PVC delete**; never a
  jump server (`docs/design/PO_DECISION_RECORD_2026_09_24_HOST_A_OWNED_BY_NEXUS_AGENT_SUDO.md`).
- Deploy: `scripts/hosta_deploy.sh` only (exit 4 = job in flight, retry). Then `kubectl -n ui2 set image
  deploy/ui2-configuration …` to the service digest. Every new `V*.sql`: `BEGIN … ROLLBACK` dry-run on the live DB first.
- Privacy: no raw hostnames/addresses/serials/accounts in chat, commits, docs, logs. `scripts/repository_privacy_check.py`
  before each commit. Chat in Turkish; artefacts in English.

# Recent session changes (2026-09-25 .. 26)
V73–V86: Spark hostname gate; CP digest timeout; Symantec MC + ProxySG backup and (V82) ProxySG devices via the MC;
onboarding flow (V77); Cisco ASA reads + archive (V78/V79); Fortinet reads, FortiManager interfaces/routes/discovery,
FortiGate configuration plane and FMG SSH states (V80/V81/V85); Panorama inventory/backup (V83); Pulse Secure (V84);
configuration vendor CHECK widened (V86). UI: executive Overview (estate map), one device screen, aiview job-log
masking fix, Cyber Controller reads queued per host.

# Exact next action
1. FortiManager physical link state: gate and read `diagnose hardware info nic <port>` over SSH; today the tab shows
   the configured state (enable/disable → up/down, measured 2026-09-26).
2. First real runs when the PO adds them: Cisco ASA, Pulse Secure, SMC discovery → ProxySG import; read the
   measure-first logs, fix parsers, trigger and measure backups, note each run in the queue.
3. Cluster view for ASA failover pairs and FortiGate HA (`asa_fortinet_cluster_view`); ASA configuration plane.
4. Infoblox richer WAPI reads (propose to the PO first).

# Test delta
Worker 331+, service 379 (one pre-existing failure: `ProjectPlanReaderTest`), frontend 202 — all green at handover.

# New risks
- FortiGate admins' trusted-host lists are capped at 10 entries; HOST-A was added by merging two /32 into one /31 on
  four devices — repeat on any FortiGate that refuses fwadm from HOST-A.
- Unmeasured parsers (ProxySG `show interface all`/`show ip-route-table`, Pulse XML network part, ASA outputs beyond the
  Backbox trail) log shapes on the first run; expect one fix cycle each.
- The nightly 03:00 Cyber Controller self-backup still fails on the controller's stale known_hosts (Radware case).
