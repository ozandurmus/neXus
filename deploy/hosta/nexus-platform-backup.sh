#!/bin/bash
# neXus HOST-A encrypted platform backup (PO 2026-09-27). Everything needed to rebuild the running state except the
# artefact store (already encrypted by neXus; copied off-host separately):
#   k3s datastore (consistent sqlite copy), k3s cred (incl. the Secrets encryption config), tls, token, k3s config,
#   /etc/nexus (local CA, firewall; NOT the backup identity), sshd/chrony drop-ins, systemd units, and a pg_dump of
#   the ui2 database, the database roles (pg_dumpall --globals-only), the six ui2 Secrets, the SSH host keys and the k3s
#   server/manifests (step S1, 2026-11-16; RECOVERY_OFFHOST_AND_KEY_CUSTODY_DESIGN section 7.1). The dump is verified
#   before encryption; any failed step fails the run and no archive is written. Everything secret stays inside the
#   work dir (umask 077, removed by the trap) and the age-encrypted archive. Encrypted with age to /etc/nexus/backup/age-recipient.txt; the identity that decrypts it must also
#   be kept off the host. Keeps the newest 7 (PO 2026-11-24; 14 held 178 GB).
set -euo pipefail
umask 077
OUT=/var/backups/nexus; K3S=/var/lib/rancher/k3s/server
TS=$(date -u +%Y%m%dT%H%M%SZ); W=$(mktemp -d "$OUT/.work.XXXXXX"); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/k3s" "$W/etc"
sqlite3 "$K3S/db/state.db" ".backup '$W/k3s/state.db'"
cp -a "$K3S/cred" "$K3S/tls" "$K3S/token" "$W/k3s/"
cp -a /etc/rancher/k3s "$W/etc/rancher-k3s"
mkdir -p "$W/etc/nexus"; (cd /etc/nexus && find . -path ./backup -prune -o -type f -print | cpio -pdm --quiet "$W/etc/nexus")
cp -a /etc/ssh/sshd_config.d "$W/etc/sshd_config.d"; cp -a /etc/chrony/sources.d "$W/etc/chrony-sources.d"
cp -a /etc/systemd/system/nexus-host-firewall.service "$W/etc/" 2>/dev/null || true
export KUBECONFIG=/home/aiadmin/.kube/config
kubectl -n ui2 exec ui2-db-0 -- sh -c 'pg_dump -U "$POSTGRES_USER" -d ui2 -Fc' > "$W/ui2.pgdump"
# S1.2: the dump must list table data before it is encrypted; an empty or truncated dump fails the run.
DATA_ENTRIES=$(kubectl -n ui2 exec -i ui2-db-0 -- pg_restore --list < "$W/ui2.pgdump" | grep -c ' TABLE DATA ' || true)
if [ "${DATA_ENTRIES:-0}" -lt 1 ]; then echo "backup aborted: dump lists no table data" >&2; exit 1; fi
# S1.1: database roles and globals (contains role password hashes; stays in the encrypted archive only).
kubectl -n ui2 exec ui2-db-0 -- sh -c 'pg_dumpall -U "$POSTGRES_USER" --globals-only' > "$W/ui2-globals.sql"
[ -s "$W/ui2-globals.sql" ] || { echo "backup aborted: empty globals dump" >&2; exit 1; }
# S1.3: explicit export of the six ui2 Secrets (names only on stdout; values never printed).
mkdir -p "$W/secrets"
for S in ui2-db ui2-credential-store-key ui2-role-binding-key ui2-artefact-store-key ui2-hostname-fingerprint-key ui2-privacy-hmac-key; do
  kubectl -n ui2 get secret "$S" -o yaml > "$W/secrets/$S.yaml"
  [ -s "$W/secrets/$S.yaml" ] || { echo "backup aborted: secret $S export empty" >&2; exit 1; }
done
# S1.4: SSH host keys and the k3s server manifests.
mkdir -p "$W/etc/ssh-host-keys"; cp -a /etc/ssh/ssh_host_* "$W/etc/ssh-host-keys/"
cp -a "$K3S/manifests" "$W/k3s/manifests"
tar -C "$W" -czf - . | age -R /etc/nexus/backup/age-recipient.txt > "$OUT/nexus-platform-$TS.tar.gz.age.part"
mv "$OUT/nexus-platform-$TS.tar.gz.age.part" "$OUT/nexus-platform-$TS.tar.gz.age"
ls -1t "$OUT"/nexus-platform-*.tar.gz.age | tail -n +8 | xargs -r rm -f
echo "backup $OUT/nexus-platform-$TS.tar.gz.age $(du -h "$OUT/nexus-platform-$TS.tar.gz.age" | cut -f1)"
