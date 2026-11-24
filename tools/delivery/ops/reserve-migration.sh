#!/bin/bash
# Hands out the next Flyway migration number (PO 2026-11-23: lanes must not pick their own; V264/V265 clashed).
# Usage: reserve-migration.sh <lane-name>   -> prints e.g. 267
# The number is above main's highest V, above every open lane branch's V and above every earlier reservation.
# Reservations live in $NEXUS_OPS_STATE/migration-reservations.txt ("<number> <lane> <date>"), under a lock.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
lane=${1:?lane name}; F=$OPS_STATE/migration-reservations.txt; R=$OPS_REPO
MIG=${NEXUS_MIGRATION_DIR:-ui2/service/src/main/resources/db/migration/}
mkdir -p "$OPS_STATE"
exec 9>"$OPS_STATE/.migration-reservations.lock"; /usr/bin/lockf 9 2>/dev/null || true
touch "$F"
prev=$(awk -v l="$lane" '$2==l{print $1}' "$F" | tail -1); [ -n "$prev" ] && { echo "$prev"; exit 0; }
git -C "$R" fetch -q origin main 2>/dev/null || true
max_main=$(git -C "$R" ls-tree --name-only origin/main "$MIG" | grep -oE '/V[0-9]+__' | tr -dc '0-9\n' | sort -n | tail -1 || true)
max_lanes=$(for b in $(git -C "$R" for-each-ref --format='%(refname:short)' refs/heads/lane/ refs/heads/fix/ 2>/dev/null); do
  git -C "$R" diff --name-only --diff-filter=A "origin/main...$b" -- "$MIG" 2>/dev/null; done | grep -oE '/V[0-9]+__' | tr -dc '0-9\n' | sort -n | tail -1 || true)   # no open lane branch with a migration: not an error
max_res=$(awk '{print $1}' "$F" | sort -n | tail -1)
n=$(printf '%s\n' "${max_main:-0}" "${max_lanes:-0}" "${max_res:-0}" | sort -n | tail -1); n=$((n+1))
echo "$n $lane $(date '+%F %T')" >> "$F"; echo "$n"
