# UI2 Backup Plane Separation — Contract

**Status: DRAFT — FOR PRODUCT OWNER FREEZE.** Not implementation authority until its
status line reads `FROZEN` (`AGENTS.md`, Contract-status law). Authorised by the Product
Owner decision of 2026-09-20 recorded at `relay/NXS-LOCAL-0347-…json` seq 5, which
directs that the backup plane be delivered as the independent service the reviewed
architecture specifies. That decision authorises planning and implementation; it does not
authorise deployment, does not lift CLASS 1 backup gating, and does not change the
failover engine's blocked status.

## 1. Why this needs a contract rather than a refactor

The two existing microservices are stateless by construction: `Ui2ComplianceServer` and
the configuration server hold, in their own words, zero database credentials and zero
device credentials. `ui2-service` hands them a payload and they answer. Separating the
backup plane cannot copy that shape. The recovery plane needs the vault volume, the
artefact manifest rows, and device credentials — including, per the agreed backup
baseline, a PAN-OS service account distinct from the read-only inventory account because
`export device-state` requires Superuser from PAN-OS 7.1 onward.

So this movement moves a credential boundary and a secret-bearing data plane. `AGENTS.md`
requires a frozen contract before implementation for exactly that.

## 2. What is true today (measured 2026-09-20, not assumed)

- No `ui2-backup` workload exists in the cluster. `kubectl -n ui2 get deploy,svc` returns
  service, worker, configuration, compliance only.
- The backup API is served by `BackupController` inside `ui2-service`; the executors
  (`BackupJobExecutor`, `BackupCapabilityExecutor`, the Check Point snapshot executor) run
  inside `ui2-worker`. Both answer today: `/api/v2/backups/policies` and
  `/api/v2/backups/deviations` return 403 and `/backups` returns 401 against the live
  ingress, so the routes are mounted and gated.
- `ui2-service` carries no backup service URL. There is nothing to forward to and no
  configuration that expects one.
- There is no `ui2-backup-vault-pvc`. Recovery artefacts share `ui2-artefact-store` with
  the evidence plane.
- That volume's `local-path` class does not enforce the requested size: the PVC requests
  5Gi, reports 5Gi, and mounts the host filesystem, which shows 1006G total and 756G
  available inside the worker. The declared 400Gi is nominal in both the manifest and the
  policy endpoint. Nothing bounds how much a backup run may consume, on a host that also
  carries another product's production workload.
- `Ui2WorkerMain` dispatches on `args[0]` for `configuration` and `compliance` only.
  There is no `backup` role, so the deleted `56-backup-deployment.yaml` — whose args were
  `["worker","backup"]` and which carried no database or secret environment — would have
  started a second generic worker and crash-looped.

## 3. Target

An independent `ui2-backup` workload in namespace `ui2`, serving the backup API on 8086,
owning the recovery plane end to end: the vault, the manifest rows that describe it, and
the vendor retrieval paths that fill it. `ui2-service` stops serving backup routes and
forwards them.

### 3.1 Boundary this movement must establish

The isolation the security review calls load-bearing is between the VERIFY plane
(redacted evidence, safe to render) and the RECOVER plane (full-fidelity configuration,
secret-bearing by definition). Today both are served from one process. After this
movement, recovery bytes must be reachable only from `ui2-backup`, and `ui2-service` must
hold no credential and no mount that can read them.

### 3.2 Required decisions before freeze

These are open and must be answered by the Product Owner, not inferred:

- **D-B1 — Manifest row ownership.** Does `ui2-backup` own the artefact manifest tables
  directly with its own database role, or does it call `ui2-service` for them? Direct
  ownership is the stronger boundary and the larger change.
- **D-B2 — Quota enforcement.** `local-path` enforces no quota, so a dedicated PVC alone
  does not bound consumption. Enforcement needs either a storage class that does, or a
  filesystem quota — both cluster or host scope, outside the agent's `kubectl -n ui2`
  authorisation. Until one is chosen, "dedicated vault" means a separate directory, not a
  separate budget. Tracked as `vault_has_no_enforced_quota_on_a_shared_host`.
- **D-B3 — Vendor credential separation.** The baseline requires a PAN-OS backup service
  account separate from the inventory account. Does `ui2-backup` hold it alone, and is
  the inventory account then denied `export device-state` outright?
- **D-B4 — Forwarding shape.** Does `ui2-service` proxy the backup routes transparently,
  or does the frontend address `ui2-backup` through the ingress? A proxy keeps one
  authenticated edge and one session; a direct route needs its own gate chain.
- **D-B5 — Migration of existing artefacts.** The store currently holds artefacts written
  under the shared layout. Are they moved to the new vault, re-manifested in place, or
  left where they are with the new vault starting empty?

## 4. Definition of done

1. `ui2-backup` runs as its own deployment and service on 8086 with its own manifests,
   carrying the database and secret environment the deleted manifests lacked.
2. A dedicated vault volume, mounted only by `ui2-backup`.
3. `ui2-service` serves no backup route and mounts no artefact store; it forwards per
   D-B4 and its removal of the mount is asserted by a test, not by inspection.
4. `Ui2WorkerMain` dispatches a `backup` role, or the workload is launched by its own
   entry point — whichever the implementation chooses, the deleted manifests' silent
   fall-through to the generic worker must be impossible by construction.
5. The security review is re-run against the built system. The existing review assessed
   the separated design and is not inherited (Product Owner decision, 2026-09-20).
6. Real-environment validation under `aiview`: the Backups screen reads from the
   separated service, and a device-level backup run completes end to end.

## 5. Out of scope

Restore (`RB.6`) stays hard-gated and untouched. This movement does not change the
validation ladder, the retention policy, the tombstone ledger, or the CLASS 1 gating of
backup collection. It moves where the plane runs, not what it is allowed to do.

## 6. Recommended movement

`ARCHITECTURE` to freeze this contract with D-B1 through D-B5 answered, then
`IMPLEMENTATION`. Tier: high for the freeze — it sets a credential and secret-data
boundary; normal-strong for the implementation once the boundary is fixed.
