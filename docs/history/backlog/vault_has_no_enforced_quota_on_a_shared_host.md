# The artefact/recovery store has no enforced quota: local-path ignores the PVC size, evidence and recovery planes share one directory tree, and the host also carries another product's production workload

status: planned · target: deploy/ui2/35-artefact-store-pvc.yaml; storage class local-path; PO decision needed on storage class or filesystem quota

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): The artefact/recovery store has no enforced quota: local-path ignores the PVC size, evidence and recovery planes share one directory tree, and the host also carries another product's production workload

Target (full): deploy/ui2/35-artefact-store-pvc.yaml; storage class local-path; PO decision needed on storage class or filesystem quota

2026-11-14 backlog truth: still open (P2, size M). Today: Backups can still fill the disk; retention (newest N, 30 days) is the only guard. Evidence: 35-artefact-store-pvc.yaml 400Gi default class; no quota code (only usable_bytes display); 2026-10-10 disk incident in CURRENT_STATE

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Commit 'Enforce a byte quota on the artefact store; refuse writes over it, warn near it': FileArtefactStore refuses writes over the quota (default 40 percent of the volume, max 400 GiB, UI2_ARTEFACT_STORE_QUOTA_BYTES), status view warns near it; tests FileArtefactStoreTest, SystemStatusStorageLivenessTest. Not a storage-class or filesystem quota: the PO decision on local-path remains. Usage is a cached tree walk, so another pod's write can go unseen briefly.
