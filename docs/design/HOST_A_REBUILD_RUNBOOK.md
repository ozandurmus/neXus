# HOST-A rebuild runbook — reinstall the host, bring neXus back

## Status

**DRAFT — WRITTEN 2026-09-24 BEFORE THE REINSTALL; STEPS ARE VERIFIED ONLY WHEN THE REBUILD RUNS.** The Product Owner is
reinstalling HOST-A from scratch (same machine, same address, fresh Ubuntu) because a co-hosted product filled the
shared root disk repeatedly and took neXus down (pods cannot start without space for `/var/log/pods`). Everything on
the host is erased, including neXus's 500 GB loop volume (a file on that disk).

## 1. What was exported, where

`~/nexus-export-20260924T2026Z/` on HOST-A (mode 700; the Product Owner copies it off the host before the reinstall),
made by `scripts/hosta_export_all.sh` with the application stopped:

| File | Content | Verified |
| --- | --- | --- |
| `db.dump` (172 MB) | `pg_dump -Fc` of `ui2`: devices, credentials (encrypted), users, RBAC, jobs, schedules, trust, discovery, baselines, audit | `pg_restore --list` reads it |
| `db-globals.sql` | roles (`pg_dumpall --globals-only`) | — |
| `secrets/*.yaml` (6) | `ui2-db`, `ui2-credential-store-key`, `ui2-role-binding-key`, `ui2-artefact-store-key`, `ui2-hostname-fingerprint-key`, `ui2-privacy-hmac-key` | — |
| `configmaps.yaml` | `ui2-cc-receiver` (receiver address), `ui2-config`, `ui2-migrations` | — |
| `host-files.tar` | `build-job-proxy.yaml`, `flyway-bootstrap.yaml`, `registry.yaml`, `run_build.sh`, `install-k3s.sh`, `.kube/config`, `/etc/ssh/sshd_config`, `/etc/fstab` | — |
| `artefact-store.tar` (98 GB) | the encrypted backup store, 1,515 files | file count equals the store's |
| `SHA256SUMS` | all of the above | `sha256sum -c`: 8 OK |

**The keys decide what survives.** `ui2-artefact-store-key` opens the backups; `ui2-credential-store-key` opens the
stored device credentials; `ui2-privacy-hmac-key` keeps the aiview pseudonyms (FW-TANGO-04 …) the same. Restore them
with the database, or the restored rows are unreadable. Keep `secrets/` apart from the rest (a vault).

## 2. What the repository already holds

The whole application (service, worker, frontend, migrations V1–V70), `deploy/ui2/*.yaml`, `deploy/ui2-image-build/*`
(namespace, context PVC and loader, build job, `run_build.sh`), and since 2026-09-24 the files that lived only on the
host: `deploy/ui2/05-flyway-bootstrap.yaml`, `deploy/ui2-image-build/05-registry.yaml`, and the proxy build job as a
template `deploy/ui2-image-build/31-build-job-proxy.yaml.template` (its real proxy values are in `host-files.tar`).
Versions running before the reinstall: **Ubuntu 24.04.4 LTS, k3s v1.36.4+k3s1**.

## 3. The reinstall itself (Product Owner, with sudo)

1. **Give neXus its own volume**, not a file on the shared root: at install time, a separate LVM logical volume (≥500 GB)
   mounted at `/var/lib/rancher`, and bind or mount `/var/lib/kubelet` and `/var/log/pods` onto it too — then a full
   root disk can no longer stop neXus pods from starting.
2. **Cap the co-hosted product's logs** before it runs: `/etc/docker/daemon.json`
   `{"log-driver":"json-file","log-opts":{"max-size":"100m","max-file":"3"}}` (the writer on 2026-09-24 was
   `opinnate_clickhouse`, ~6–8 GB/min).
3. The SSH user and its `~/.kube/config` (from k3s), and the neXus agent's SSH key as before.

## 4. Bring neXus back (agent, no sudo, except where marked)

1. **k3s** (sudo): `curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION=v1.36.4+k3s1 sh -`; copy
   `/etc/rancher/k3s/k3s.yaml` to `~/.kube/config` for the SSH user.
2. **Registry**: `kubectl apply -f deploy/ui2-image-build/05-registry.yaml`; then (sudo) `/etc/rancher/k3s/registries.yaml`
   mirroring `registry.kube-system.svc.cluster.local` to `http://<the registry Service ClusterIP>` and restart k3s.
3. **Build path**: `kubectl apply -f deploy/ui2-image-build/00-namespace.yaml -f deploy/ui2-image-build/10-context-pvc.yaml`;
   render the proxy job: `envsubst < deploy/ui2-image-build/31-build-job-proxy.yaml.template > ~/build-job-proxy.yaml`
   (values from `host-files.tar`); `cp deploy/ui2-image-build/run_build.sh ~/`; clone the repository to `~/nexus`.
4. **Namespace, secrets, config**: `kubectl apply -f deploy/ui2/00-namespace.yaml`; apply `secrets/*.yaml` from the
   export (strip `resourceVersion`, `uid`, `creationTimestamp` first); apply `configmaps.yaml` (same stripping).
   Do **not** apply `deploy/ui2/2x-secret*.yaml` over them — those would replace the keys.
5. **Database**: `kubectl apply -f deploy/ui2/30-database-pvc.yaml -f deploy/ui2/40-database-statefulset.yaml
   -f deploy/ui2/45-database-service.yaml`; when ready: `psql -f db-globals.sql` (roles), then
   `pg_restore -d ui2 --no-owner --role=<migrate user> db.dump` (copy the dump into the pod with `kubectl cp`). Then
   `deploy/ui2/05-flyway-bootstrap.yaml` must report schema 70 and nothing to apply.
6. **Backup store**: `kubectl apply -f deploy/ui2/35-artefact-store-pvc.yaml`; a temporary pod mounting it read-write
   (uid 185, gid 0, like the export pod in `scripts/hosta_export_all.sh`); `tar -xf artefact-store.tar -C /store`
   streamed in; count 1,515 files.
7. **Application**: `bash ~/run_build.sh` (builds and sets the image), then apply `deploy/ui2/50-…` to `60-…`;
   `scripts/hosta_deploy.sh` from then on.
8. **Cyber Controller receiver** (sudo; RADWARE_CYBER_CONTROLLER_OWN_BACKUP_RECEIVER.md): user `nexus-cc` (group
   `nexuscc`, **gid 2600** — the worker manifest's supplemental group), `/var/lib/nexus-cc` root:root 755 and
   `/var/lib/nexus-cc/in` nexus-cc:nexuscc 2770 **on the neXus volume**, the `Match User nexus-cc` block at the end of
   `sshd_config` (the old one is in `host-files.tar`), the same password as the credential-store entry
   "SFTP Receiver".

## 5. Done when

- site 200; schema 70; service, worker, compliance, configuration 1/1;
- Devices lists the 105 devices; aiview shows the same pseudonyms as before (privacy key restored);
- a Check Point and a Palo Alto collect succeed (credentials decrypt — credential key restored);
- a restored backup opens (Download or Contents — artefact key restored);
- a Cyber Controller Backup Now completes (receiver rebuilt);
- `df -h /` full does **not** stop a pod restart (the neXus volume is separate).
