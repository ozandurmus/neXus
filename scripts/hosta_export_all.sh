#!/usr/bin/env bash
# Export everything neXus needs, ON HOST-A itself, before the host is reinstalled (PO, 2026-09-24: "sunucuda bir yerde
# tut, ben ortamıma çekerim sonra yeni sunucuya koyarım"; nothing is copied to the workstation running this script).
#
#   scripts/hosta_export_all.sh [<directory on the host>]      (default: ~/nexus-export-<UTC date> in the SSH user's home)
#
# Run it with the application stopped (service, worker, compliance, configuration scaled to 0; the database pod up).
# It writes on the host, each part verified before the next:
#   db.dump                 pg_dump -Fc of the ui2 database           verified: pg_restore --list reads it
#   db-globals.sql          pg_dumpall --globals-only (roles)
#   secrets/*.yaml          the ui2 Secrets -- the keys that decrypt the backup store and the credential store.
#                           Without them the backups cannot be opened. Keep them apart from the rest (a vault).
#   configmaps.yaml         ui2 ConfigMaps (e.g. ui2-cc-receiver)
#   host-files.tar          host-local files not in git (build-job-proxy.yaml, registry.yaml, ...) + sshd_config and
#                           fstab for reference (world-readable; no sudo)
#   artefact-store.tar      the encrypted backup store (PVC ui2-artefact-store), via a temporary read-only pod
#                           verified: file count equals the store's
#   SHA256SUMS
# The directory is mode 700 (only the SSH user). Stops before the store copy if the free space would not hold it.
set -euo pipefail
HOST="${NEXUS_HOST:-$(head -1 "$HOME/.config/nexus/hosta" 2>/dev/null)}"
[ -n "$HOST" ] || { echo "set NEXUS_HOST or ~/.config/nexus/hosta" >&2; exit 64; }
DIR="${1:-}"
ssh -o ConnectTimeout=10 -o ServerAliveInterval=30 "$HOST" "DIR='$DIR' bash -s" <<'REMOTE'
set -euo pipefail
export KUBECONFIG=$HOME/.kube/config
DIR="${DIR:-$HOME/nexus-export-$(date -u +%Y%m%dT%H%MZ)}"
mkdir -p "$DIR/secrets" && chmod 700 "$DIR" "$DIR/secrets"
echo "destination (on the host): $DIR"
running=$(kubectl -n ui2 get pods --no-headers | grep -cE 'ui2-(service|worker|compliance|configuration)' || true)
[ "$running" = "0" ] || { echo "STOP: application pods still running ($running); scale them to 0 first"; exit 2; }

echo "1/6 database dump..."
kubectl -n ui2 exec ui2-db-0 -- sh -c 'pg_dump -Fc -U "$POSTGRES_USER" ui2' > "$DIR/db.dump"
kubectl -n ui2 exec ui2-db-0 -- sh -c 'pg_dumpall --globals-only -U "$POSTGRES_USER"' > "$DIR/db-globals.sql"
# verified inside the database pod (streaming 170+ MB through exec stdin times out): copy, list, remove
kubectl -n ui2 cp "$DIR/db.dump" ui2-db-0:/tmp/nexus-export-check.dump >/dev/null
tables=$(kubectl -n ui2 exec ui2-db-0 -- pg_restore --list /tmp/nexus-export-check.dump | grep -c ' TABLE DATA ' || true)
kubectl -n ui2 exec ui2-db-0 -- rm -f /tmp/nexus-export-check.dump
[ "$tables" -gt 0 ] || { echo "STOP: the dump does not list any table data"; exit 3; }
echo "    db.dump $(du -h "$DIR/db.dump" | cut -f1), $tables tables with data (pg_restore reads it)"

echo "2/6 secrets and configmaps..."
for s in $(kubectl -n ui2 get secrets --no-headers -o custom-columns=N:.metadata.name | grep '^ui2-'); do
  kubectl -n ui2 get secret "$s" -o yaml > "$DIR/secrets/$s.yaml"
done
chmod 600 "$DIR"/secrets/*.yaml
kubectl -n ui2 get configmaps -o yaml > "$DIR/configmaps.yaml"
echo "    $(ls "$DIR/secrets" | wc -l) secrets"

echo "3/6 host-local files..."
(cd ~ && tar -cf "$DIR/host-files.tar" --ignore-failed-read build-job-proxy.yaml flyway-bootstrap.yaml install-k3s.sh \
  registry.yaml run_build.sh .kube/config -C / etc/ssh/sshd_config etc/fstab 2>/dev/null) || true
echo "    host-files.tar $(tar -tf "$DIR/host-files.tar" | wc -l) files"

echo "4/6 artefact store (temporary read-only pod)..."
kubectl -n ui2 delete pod ui2-export --ignore-not-found >/dev/null
kubectl -n ui2 apply -f - >/dev/null <<'POD'
apiVersion: v1
kind: Pod
metadata: {name: ui2-export, namespace: ui2}
spec:
  restartPolicy: Never
  securityContext: {runAsUser: 185, runAsGroup: 0}
  containers:
    - name: export
      image: quay.io/sclorg/postgresql-16-c9s@sha256:fbef891ec464ee8d20332c7f9a4a68decc0b6db8460e7e875df6f33cac5003dc
      command: ["sleep", "86400"]
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      volumeMounts: [{name: store, mountPath: /store, readOnly: true}]
  volumes:
    - name: store
      persistentVolumeClaim: {claimName: ui2-artefact-store, readOnly: true}
POD
kubectl -n ui2 wait --for=condition=Ready pod/ui2-export --timeout=180s >/dev/null
expected=$(kubectl -n ui2 exec ui2-export -- sh -c 'find /store -type f | wc -l')
need_kb=$(kubectl -n ui2 exec ui2-export -- sh -c 'du -sk /store | cut -f1')
free_kb=$(df -Pk "$DIR" | awk 'NR==2 {print $4}')
if [ "$free_kb" -lt $(( need_kb + 20*1024*1024 )) ]; then
  kubectl -n ui2 delete pod ui2-export --wait=false >/dev/null
  echo "STOP: the store needs $((need_kb/1024/1024)) GB (+20 GB margin); $((free_kb/1024/1024)) GB free here"; exit 4
fi
echo "    $expected files, $((need_kb/1024/1024)) GB; copying..."
kubectl -n ui2 exec ui2-export -- tar -C /store -cf - . > "$DIR/artefact-store.tar"
kubectl -n ui2 delete pod ui2-export --wait=false >/dev/null
got=$(tar -tf "$DIR/artefact-store.tar" | grep -vc '/$' || true)
echo "    artefact-store.tar $(du -h "$DIR/artefact-store.tar" | cut -f1), $got of $expected files"
[ "$got" = "$expected" ] || { echo "STOP: artefact file count differs ($got of $expected)"; exit 5; }

echo "5/6 checksums..."
(cd "$DIR" && find . -type f ! -name SHA256SUMS -print0 | xargs -0 sha256sum > SHA256SUMS)

echo "6/6 done: $DIR"
du -sh "$DIR"
REMOTE
