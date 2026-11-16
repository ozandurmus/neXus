# Module runtime landed; policy/configuration/compliance pods split, remaining roles pending

status: in_progress · target: docs/design/MODULE_POD_RUNTIME_CONTRACT.md

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Module runtime landed; policy/configuration/compliance pods split, remaining roles pending

2026-11-14 backlog truth: still open (P2, size L). Today: Remaining job modules still share the general worker; isolation incomplete but stable. Evidence: Pods: service, worker, configuration, compliance, policy, packet-analysis (deploy/ui2); discovery/backup/inventory still in general worker

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): step 2 landed at zero replicas: V226 seeds module_runtime_control('backup','general',TRUE) and allows owner_role backup; deploy/ui2/53-backup-deployment.yaml is committed at replicas 0 and is in SYNCED_MANIFESTS with the worker (tools/delivery/module_deploy.py); commit 'Harden backup module cutover failure paths' and 'Classify cp_mds_export as BACKUP and make the backup drain wait for general-held prune/purge leases'. Backup claims stay on the general worker until the guarded handover. Remaining: the cutover itself (scale the backup pod to 1 and hand ownership over), then discovery and inventory roles, which still run in the general worker.
