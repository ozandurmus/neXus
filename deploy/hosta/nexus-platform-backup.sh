#!/bin/bash
# neXus HOST-A encrypted platform backup (PO 2026-09-27). Everything needed to rebuild the running state except the
# artefact store (already encrypted by neXus; copied off-host separately):
#   k3s datastore (consistent sqlite copy), k3s cred (incl. the Secrets encryption config), tls, token, k3s config,
#   /etc/nexus (local CA, firewall; NOT the backup identity), sshd/chrony drop-ins, systemd units, and a pg_dump of
#   the ui2 database. Encrypted with age to /etc/nexus/backup/age-recipient.txt; the identity that decrypts it must also
#   be kept off the host. Keeps the newest 14.
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
tar -C "$W" -czf - . | age -R /etc/nexus/backup/age-recipient.txt > "$OUT/nexus-platform-$TS.tar.gz.age.part"
mv "$OUT/nexus-platform-$TS.tar.gz.age.part" "$OUT/nexus-platform-$TS.tar.gz.age"
ls -1t "$OUT"/nexus-platform-*.tar.gz.age | tail -n +15 | xargs -r rm -f
echo "backup $OUT/nexus-platform-$TS.tar.gz.age $(du -h "$OUT/nexus-platform-$TS.tar.gz.age" | cut -f1)"
