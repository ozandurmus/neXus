#!/usr/bin/env bash
# Runs the in-cluster e2e screen suite on HOST-A (docs/design/E2E_MACHINE_IDENTITY_DRAFT.md, FROZEN):
# builds the runner image from the context the last deploy streamed, runs the Job with that image, prints a summary.
# Exit 0 = all tests passed, 1 = test failures, 2 = the build or the Job could not run.
set -uo pipefail
MODE=${NEXUS_E2E_MODE:-quick}
case "${1:-}" in
  --help|-h) echo "Usage: bash scripts/hosta_e2e.sh [--quick|--full]"; echo "NEXUS_E2E_MODE=quick|full (default quick); nightly CronJob uses full."; exit 0 ;;
  --full) MODE=full ;;
  --quick) MODE=quick ;;
  "") ;;
  *) echo "Unknown option" >&2; exit 2 ;;
esac
if [[ $# -gt 1 || ! "$MODE" =~ ^(quick|full)$ ]]; then echo "Invalid E2E mode or arguments" >&2; exit 2; fi
cd "$(dirname "$0")/.."
HOST=$(head -1 ~/.config/nexus/hosta)
scp -q deploy/ui2/70-e2e-job.yaml "$HOST":/tmp/ || exit 2
ssh "$HOST" "bash -s -- $MODE" <<'REMOTE' 2>&1 | grep -vE "Authorized|Yalnizca|^\*{10}"
set -euo pipefail
MODE=$1
export KUBECONFIG=$HOME/.kube/config
build_start=$SECONDS
digest=$(python3 "$HOME/nexus/scripts/hosta_e2e_image.py" ensure) || exit 2
printf 'TIMING e2e_image_wait %s\n' "$((SECONDS - build_start))"
python3 - "$digest" "$MODE" <<'PY'
import re,sys
s=open('/tmp/70-e2e-job.yaml').read()
job=[d for d in s.split('\n---\n') if re.search(r'^kind: Job$',d,re.M)][0]
job=job.replace('nexus-ui2-e2e:SET_AT_DEPLOY','nexus-ui2-e2e@'+sys.argv[1]).replace('  suspend: true\n','  suspend: false\n',1)
job=job.replace('value: quick', 'value: '+sys.argv[2])
open('/tmp/70-e2e-job.run.yaml','w').write(job)
# The nightly full suite follows the newest runner image.
cron=[d for d in s.split('\n---\n') if re.search(r'^kind: CronJob$',d,re.M)][0]
cron=cron.replace('nexus-ui2-e2e:SET_AT_DEPLOY','nexus-ui2-e2e@'+sys.argv[1]).replace('  suspend: true\n','  suspend: false\n',1)
open('/tmp/70-e2e-cron.run.yaml','w').write(cron)
PY
run_start=$SECONDS
kubectl -n ui2 delete job ui2-e2e --ignore-not-found >/dev/null
kubectl apply -f /tmp/70-e2e-job.run.yaml >/dev/null
kubectl apply -f /tmp/70-e2e-cron.run.yaml >/dev/null
rm -f /tmp/70-e2e-job.yaml /tmp/70-e2e-job.run.yaml /tmp/70-e2e-cron.run.yaml
for i in $(seq 1 360); do s=$(kubectl -n ui2 get job ui2-e2e -o jsonpath='{.status.succeeded}{.status.failed}'); [ -n "$s" ] && break; sleep 10; done
kubectl -n ui2 logs job/ui2-e2e --tail=300 2>&1 | sed -E 's/[0-9]{1,3}(\.[0-9]{1,3}){3}/<ip>/g' | grep -E '✘|[0-9]+ passed|[0-9]+ failed|flaky|^\s+Error:' | cut -c1-170 | head -40
printf 'TIMING e2e_run %s\n' "$((SECONDS - run_start))"
if [ "$(kubectl -n ui2 get job ui2-e2e -o jsonpath='{.status.succeeded}')" = "1" ]; then echo "E2E: PASS"; else echo "E2E: FAIL"; exit 1; fi
REMOTE
