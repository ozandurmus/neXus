#!/usr/bin/env bash
# Deploy the current origin/main to HOST-A and watch it to an end state -- never leave it hanging.
#
#   scripts/hosta_deploy.sh [--apply <manifest.yaml> ...]
#
# What it does, every step with a hard limit:
#   1. refuses if a device job is CLAIMED/EXECUTING (a rollout would drain it mid-flight);
#   2. applies any --apply manifests (kubectl -n ui2 only);
#   3. starts ~/run_build.sh on the host (detached) and polls every 15 s:
#        - the NEWEST image-build pod (Completed / Error),
#        - the NEW ui2-service and ui2-worker pods (the ones that were not there before);
#   4. stops at the FIRST failure and prints the reason:
#        exit 1  build pod Error (last log lines shown)
#        exit 2  a new pod in CrashLoopBackOff / Error (its migration / startup error shown)
#        exit 3  over the time limit (12 min)
#        exit 4  jobs in flight, not started
#        exit 5  run_build.sh ended without "Done." (its last lines shown) -- e.g. the worker was left on the old
#                image because a job stayed in flight (run_build.sh waits for running jobs before replacing it)
#   5. on success prints the site status, the schema version and the image digest.
# PO rule 2026-09-22: every job I start is followed to an end state; a build is ~100 s, a deploy ~3 min.
set -uo pipefail
# The host is local configuration, never a repository literal (privacy gate): NEXUS_HOST, or the first line of
# ~/.config/nexus/hosta (e.g. user@address).
HOST="${NEXUS_HOST:-$(head -1 "$HOME/.config/nexus/hosta" 2>/dev/null)}"
if [ -z "$HOST" ]; then echo "set NEXUS_HOST or write user@host to ~/.config/nexus/hosta" >&2; exit 64; fi
LIMIT="${NEXUS_DEPLOY_LIMIT_S:-720}"
APPLY=()
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY+=("$2"); shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 64 ;;
  esac
done

for m in "${APPLY[@]:-}"; do
  [ -n "$m" ] && scp -q "$m" "$HOST:/tmp/$(basename "$m")"
done
APPLY_NAMES=""
for m in "${APPLY[@]:-}"; do [ -n "$m" ] && APPLY_NAMES="$APPLY_NAMES /tmp/$(basename "$m")"; done

ssh -o ConnectTimeout=10 "$HOST" "LIMIT=$LIMIT APPLY_NAMES='$APPLY_NAMES' bash -s" <<'REMOTE'
set -uo pipefail
export KUBECONFIG=$HOME/.kube/config
q() { kubectl -n ui2 exec ui2-db-0 -- sh -c "psql -U \"\$POSTGRES_USER\" -d ui2 -Atc \"$1\""; }
inflight=$(q "select count(*) from jobs where state in ('CLAIMED','EXECUTING')")
if [ "$inflight" != "0" ]; then echo "STOP: $inflight job(s) in flight; deploy not started"; exit 4; fi
for f in $APPLY_NAMES; do kubectl apply -f "$f" | sed 's/^/applied: /'; done
before=$(kubectl -n ui2 get pods --no-headers -o custom-columns=N:.metadata.name | tr '\n' ' ')
lastbuild=$(kubectl -n ui2-build get pods --no-headers --sort-by=.metadata.creationTimestamp 2>/dev/null | grep image-build | tail -1 | awk '{print $1}')
nohup bash ~/run_build.sh > /tmp/nexus_build.log 2>&1 &
start=$(date +%s)
while true; do
  sleep 15; t=$(( $(date +%s) - start ))
  b=$(kubectl -n ui2-build get pods --no-headers --sort-by=.metadata.creationTimestamp 2>/dev/null | grep image-build | tail -1)
  bname=$(echo "$b" | awk '{print $1}'); bstate=$(echo "$b" | awk '{print $3}')
  [ "$bname" = "$lastbuild" ] && bstate="(waiting for the new build pod)"
  new=$(kubectl -n ui2 get pods --no-headers 2>/dev/null | grep -E 'ui2-(service|worker)' | while read n r s rs rest; do case " $before " in *" $n "*) ;; *) echo "$n $r $s $rs";; esac; done)
  echo "t=${t}s build=$bstate new=[$(echo "$new" | tr '\n' ';')]"
  # run_build.sh ended without "Done." -> it failed or stopped on purpose; say why instead of waiting out the limit
  if ! pgrep -f "bash $HOME/run_build.sh" >/dev/null && ! pgrep -f "bash ~/run_build.sh" >/dev/null && ! grep -q '^Done\.' /tmp/nexus_build.log; then
    echo "STOP: run_build.sh ended without Done."; tail -4 /tmp/nexus_build.log | cut -c1-220; exit 5
  fi
  # waiting for in-flight jobs before the worker changes: not a hang, and not counted against the limit
  if tail -1 /tmp/nexus_build.log | grep -q '^Worker waiting'; then tail -1 /tmp/nexus_build.log; start=$(( $(date +%s) - t + 15 )); continue; fi
  if [ "$bname" != "$lastbuild" ] && [ "$bstate" = "Error" ]; then
    echo "STOP: build failed"; kubectl -n ui2-build logs "$bname" --tail=4 | cut -c1-220; exit 1
  fi
  if echo "$new" | grep -qE 'CrashLoopBackOff|Error'; then
    p=$(echo "$new" | grep -E 'CrashLoopBackOff|Error' | head -1 | awk '{print $1}')
    echo "STOP: new pod $p is crashing"
    kubectl -n ui2 logs "$p" --tail=400 2>/dev/null | grep -E '^Message|ERROR|Exception' | cut -c1-220 | head -5
    exit 2
  fi
  up=$(echo "$new" | grep -c ' 1/1 Running ')
  if [ "$up" -ge 2 ] && grep -q '^Done\.' /tmp/nexus_build.log; then echo "UP after ${t}s"; break; fi
  if [ $t -gt "$LIMIT" ]; then echo "STOP: over ${LIMIT}s"; tail -3 /tmp/nexus_build.log; exit 3; fi
done
echo "site $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/)"
echo "schema $(q "select version || ' ' || success from flyway_schema_history order by installed_rank desc limit 1")"
echo "image $(kubectl -n ui2 get deploy ui2-service -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's/.*@//' | cut -c1-19)"
REMOTE
