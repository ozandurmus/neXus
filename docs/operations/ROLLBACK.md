# Release snapshots and rollback

Before every build/deployment, `run_build.sh` captures the **running** release,
including before optional `hosta_deploy.sh --apply` manifests or security/build
loader changes. Failure to capture a complete snapshot stops deployment.
The printed `{"snapshot": "<id>"}` record is preserved in the orchestrator ship log,
including deployment failures. No automatic rollback is performed.

## Capture

On the deployment host, from its checkout:

```bash
bash tools/delivery/release_snapshot.sh
```

Snapshots live outside Git at `~/release-snapshots/<UTC timestamp>_<deployed-git-short>/`.
The root and snapshot directories are mode 0700, files are private under umask
0077; only the newest 20 completed snapshots are retained. Failed captures are
removed and never selected as `latest`. The root lock serializes snapshot and
rollback operations. Do not run another deployment concurrently with rollback.

Each snapshot contains JSON-form YAML Lists from `ui2`, `ui2-security`, and
`ui2-build`: namespace metadata, Deployments, StatefulSets, DaemonSets,
CronJobs, Job templates, Services, ConfigMaps, NetworkPolicies, ServiceAccounts,
Roles, RoleBindings and PodDisruptionBudgets. Standalone loader Pods are evidence
only. Optional namespaces that do not exist are recorded as absent.
Server metadata, status and kubectl last-applied annotations are removed.
Secret inventory uses a server-side projection of **names and resourceVersion
only**; Secret contents are never fetched. Auxiliary namespace PVC definitions are captured
as evidence only (the build touches context/cache claims); PVC/PV resources are
never restored, and no volume bytes are captured.

Container and init-container references are pinned to SHA-256 digests. Existing
immutable references remain pinned; tag references require one unambiguous
observed imageID from namespace Pods. Missing/ambiguous digests (including a
dormant tagged CronJob without evidence) or an unfinished controller rollout
refuse capture. Fix the workload pinning/stability, rather than bypassing capture.
The deployed commit is read from the running service's `/app/project/deploy_info.json`,
not from the next source checkout. The current successful Flyway version and a
compressed `pg_dump --schema-only --no-owner --no-privileges` are saved.
`manifest.json` records capture coverage, images, commit, schema version and
SHA-256 checksums for every payload file; rollback verifies these before use.

These are local operational artifacts, **not shareable support bundles**.
ConfigMaps, manifests, schema definitions and diffs can contain local environment
information: never paste them into chat, ship logs or Git. Secret contents must
remain in Secret references, never inline ConfigMaps or workload environments.

## Preview and restore

Run on the deployment host:

```bash
bash ~/nexus/tools/delivery/rollback.sh latest
bash ~/nexus/tools/delivery/rollback.sh ~/release-snapshots/<snapshot-id>
bash ~/nexus/tools/delivery/rollback.sh ~/release-snapshots/<snapshot-id> --apply
```

Default behavior is read-only: verify checksums and schema, print `kubectl diff`
locally plus the resources added to `ui2` since capture. Diff exit 1 means
changes; other diff errors stop before any apply. Review the exact snapshot ID
before applying, especially after multiple deployments.

Apply restores captured configuration and controller templates with their exact
image digests. Added `ui2` Deployments/DaemonSets/CronJobs are removed first;
added Services/ConfigMaps/RBAC/policies/PDBs are removed after restored rollouts.
This stops new module pods introduced by a structural refactor. A newly added
StatefulSet refuses rollback for manual storage-safe review. A StatefulSet
scale-down with a live `whenScaled: Delete` PVC retention policy also refuses. The namespace is
dedicated to neXus: do not add unrelated resources there. In `ui2-build` and
`ui2-security`, captured resources are restored but added resources are retained.
Jobs and standalone Pods are never applied/deleted: migration/build/scanning
Jobs must not be replayed. Absent auxiliary namespaces are not deleted.

Executing/claimed product jobs refuse apply. Controller rollouts are awaited
(up to 600 seconds each), then the host-local HTTPS site must return 200
(the same local certificate handling as the existing deploy wrapper).
Site 200 is a liveness check, not authenticated acceptance: the deployment owner
must run the masked AIView in-cluster e2e gate after restore. A partial apply or
failed rollout is reported, not concealed; preview again and resolve the failure.

## Schema and data boundary

Rollback **never restores DB data, schema SQL, PVC/PV resources, Secret contents,
or registry/storage bytes**. The schema-only dump is reference evidence for a
separately approved manual recovery, not an automatic downgrade script.
Snapshots do not preserve image registry blobs: retain those digests in the
registry for the required recovery window.

A snapshot schema lower than live refuses rollback by default. For the current
additive migration policy, after confirming that the old images remain compatible:

```bash
bash ~/nexus/tools/delivery/rollback.sh <snapshot-dir> --allow-newer-schema
bash ~/nexus/tools/delivery/rollback.sh <snapshot-dir> --allow-newer-schema --apply
```

The flag permits old images on a newer schema; it does not change the schema.
A live schema older than the snapshot always refuses. Non-additive migrations
require a separate compatibility/recovery decision before using this procedure.
Snapshot capture is sequential, not a transactional cluster backup; keep the
running release stable during capture. Fresh installations without a running
service and Flyway history cannot use this pre-deploy recovery path.
