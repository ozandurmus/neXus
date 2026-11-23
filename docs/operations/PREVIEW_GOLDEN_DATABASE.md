# Preview golden database

Status: IMPLEMENTED (automated tests only). Host timings are UNKNOWN until the first nightly refresh and the first
golden preview run; record them here.

## Why

Every ship's isolated preview (`tools/e2e/hosta_preview_e2e.py`) copied the live database: a read-only exported
snapshot of `ui2-db-0`, `pg_dump | pg_restore` plus three filtered `COPY` streams. Measured 388 s of a 543 s preview,
and one full read of production per ship. Most previews bring no migration, so they gain nothing from data newer than
last night.

## Design

**Golden namespace `ui2-preview-golden`** (labels `nexus/preview-golden=true`, `nexus/preview-run=golden`) holds two
slots, `a` and `b`. A slot is a PVC `golden-<slot>-data` (local-path), a one-replica StatefulSet `golden-<slot>` of the
pinned PostgreSQL image (same template, hardening and resources as production, no priority class, no service account
token), and a Secret `golden-<slot>` with random role passwords plus a replication password. The ConfigMap
`golden-marker` names the serving slot and records `created` (epoch), `image` (the pinned digest), `migrations` (every
applied Flyway version) and `flyway_version`.

Network policy in the namespace: deny all, except TCP 5432 into golden pods from pods labelled
`app.kubernetes.io/component=database` + `nexus/preview-run` in namespaces labelled `nexus/preview-run` and *not*
`nexus/preview-golden`, which means preview database pods only. No DNS, no egress.

**Nightly refresh** (`hosta_preview_e2e.py refresh-golden --repo /opt/nexus`, wrapped by
`tools/e2e/hosta_preview_golden_refresh.sh`):

1. Refuses inside the collection windows (00/06/12/18, from :20 of the hour before) and the 02:00 backup hour
   (Europe/Istanbul); single-flight through a host file lock.
2. Checks pinned image equality with `ui2-db-0` (pod spec through the API), as the preview does.
3. Clears leftovers in the idle slot, then creates its Secret, PVC and StatefulSet.
4. Runs today's `copy_database()` into it unchanged: the same snapshot, read-only options, filters (audit 7 days, latest
   backup entries, named policy-chunk generations), and the same temporary NetworkPolicy pair to `ui2-db-0`. The
   source credential Secret exists in the golden namespace only during the copy (the env refs are `optional`).
5. `vacuumdb --analyze`, reads the applied Flyway versions (refused if any failed row exists), adds one replication
   role and one `pg_hba` line (`host replication ui2_golden_repl all md5`), and proves a replication login works.
6. **Promotion = one write of `golden-marker`.** Before it, the serving slot is untouched. On any failure the marker is
   not written and only the staging slot is deleted, so the previous golden keeps serving.
7. After promotion, it waits `GOLDEN_RETIRE_GRACE_SECONDS` (420 s, more than a preview's copy deadline) and deletes
   the old slot.

**Per preview** (`setup_database()`): `select_database_source()` takes the golden only when all of these hold:
marker present and valid; age 0 to 36 h; marker image equals the pinned image; the candidate's
`V*__*.sql` versions equal the marker's applied versions; the slot's pod is Ready on the pinned image.
Otherwise it takes today's live copy. It prints `PREVIEW DB SOURCE: golden|live reason=<TOKEN>` (passed through by
the wrapper and the orchestrator) and `TIMING preview_db_golden <s>` or `TIMING preview_db_copy <s>`.

The golden preview pod is today's preview database pod plus one init container (same image, same security context)
that runs `pg_basebackup --wal-method=stream --checkpoint=fast --no-sync --no-manifest` from the golden pod's IP into
the pod's own emptyDir, under a temporary egress policy to that pod only. PostgreSQL then starts on the copy; the
image's start-up and `create-app-role.sh` already set this run's passwords, and the script then sets both again as the
local superuser and drops the replication role. The golden-copy policy and Secret are deleted right after. If any
golden step fails, the partial pod is deleted and the live copy runs (`reason=GOLDEN_COPY_FAILED`); an interrupt
(SIGTERM/SIGINT) does not fall back.

### Migration safety

Comparing version sets, not a Git diff, decides it: the host receives a `git archive` with no history. Any version the
candidate has and the golden lacks (a new migration in the batch, or one shipped since last night) means Flyway would
run on that copy, so the preview takes a fresh live copy as before. A golden ahead of the candidate also takes the
live copy. Nothing on this path reads the live database: equal sets plus a candidate rebased on main imply the
golden's schema is production's schema.

### Why physical copy (pg_basebackup), not the alternatives

- **hostPath / a second PV on the golden directory**: crosses the namespace boundary through the node filesystem and
  breaks the "no hostPath, no shared state" rule the preview checks. Rejected.
- **CSI or local-path snapshot**: local-path has no snapshot support.
- **`CREATE DATABASE ... TEMPLATE` in a shared golden server**: fast, but every preview would run its candidate's
  migrations inside one long-lived server with shared roles; teardown becomes `DROP DATABASE`, and a faulty candidate
  could reach other previews. Isolation is weaker than today. Rejected.
- **Golden pg_dump archive + `pg_restore -j`**: still rebuilds every index and table; estimated 150-300 s.
- **pg_basebackup**: byte copy of data files at disk/pod-network speed, no restore, no index build. Bytes stay on the
  pod network; the preview gets its own emptyDir, namespace, passwords and teardown as today. Estimate for the
  ~6-7 GB filtered copy (data files with indexes, perhaps 10-12 GB on disk): 15-40 s copy plus 5-10 s PostgreSQL
  start, so roughly 25-50 s instead of 388 s. UNKNOWN until measured.

## Roll-out on the host (operator)

The code ships with the normal PR/deploy. Then, on the host as the deploy account, outside the windows:

```bash
export KUBECONFIG=$HOME/.kube/config
timedatectl | grep 'Time zone'          # expect Europe/Istanbul; otherwise convert 03:30 below
bash /opt/nexus/tools/e2e/hosta_preview_golden_refresh.sh
tail -20 ~/.local/state/nexus-preview/golden-refresh.out   # expect PREVIEW GOLDEN: PROMOTED ... and PASS
kubectl -n ui2-preview-golden get configmap golden-marker -o jsonpath='{.data.slot} {.data.flyway_version} {.data.created}{"\n"}'
kubectl -n ui2-preview-golden get pods,pvc
( crontab -l 2>/dev/null; echo '30 3 * * * bash /opt/nexus/tools/e2e/hosta_preview_golden_refresh.sh' ) | crontab -
crontab -l
```

The next ship shows `PREVIEW DB SOURCE: golden reason=GOLDEN_CURRENT` and `TIMING preview_db_golden`. Record the
refresh (`preview_golden_copy`, `preview_golden_vacuum`) and preview timings here.

Rollback: `crontab -e` (remove the line) and `kubectl delete namespace ui2-preview-golden`. Previews then report
`reason=GOLDEN_MISSING` and copy live exactly as before. No code change needed.

## Risks and checks

- **Disk**: during a refresh two slots exist (serving + staging), plus each preview's emptyDir copy. Expect up to
  ~3x the filtered size. Check `df` headroom before enabling; `NEXUS_PREVIEW_GOLDEN_SIZE` (default 48Gi) only sizes
  the claim request (local-path does not enforce it).
- **Memory**: the serving golden PostgreSQL runs all day (pinned limits, `shared_buffers` 1 GB, mostly idle).
- **Image assumptions (UNKNOWN until the first run)**: the sclorg data directory is `/var/lib/pgsql/data/userdata`;
  `pg_basebackup` and `timeout` exist in the image; the image keeps a `pg_hba.conf` line added after initdb;
  `wal_level`/`max_wal_senders` allow a base backup (the refresh's replication probe fails closed if not).
- **Freshness**: previews may run on data up to 36 h old. Tests that need today's rows must not rely on it.
- **Golden credential in a preview**: the replication password reaches the preview namespace for the copy only. It
  reads the golden copy, which the preview receives in full anyway; no production credential is involved.
- **A refresh that overruns** into a window keeps running; it reads production once (as one preview did before).
- **A refresh during a preview's copy**: the old slot is retired only after the grace period.
