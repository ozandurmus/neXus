#!/bin/bash
# HOST-A build/rollout script (docs/design/HOST_REGISTER.md, PO Amendment 2026-09-19).
# Canonical copy lives here; HOST-A's ~/run_build.sh must be kept identical to this file
# (this script previously existed only as an unversioned host-local copy -- durability gap
# closed 2026-09-21). To deploy a change made to this script itself, copy it to HOST-A's
# home directory first, then run it from there as usual.
set -euo pipefail
export KUBECONFIG=~/.kube/config

cd ~/nexus
git fetch
git checkout main
git pull origin main

COMMIT_SHA="$(git rev-parse HEAD)"
phase_start=$SECONDS
timing() { printf "TIMING %s %s\n" "$1" "$((SECONDS - $2))"; }
SECURITY_CONFIGURED=0
[ -f "$HOME/.config/nexus/security.json" ] && SECURITY_CONFIGURED=1
if [ -n "${NEXUS_SKIP_SECURITY_REASON:-}" ]; then
  printf 'EMERGENCY: SECURITY GATE SKIPPED: %s\n' "$NEXUS_SKIP_SECURITY_REASON"
elif [ "$SECURITY_CONFIGURED" = 0 ]; then
  echo '{"security_gate":"not_configured"}'
else
  python3 tools/security/security_host.py snapshot --config "$HOME/.config/nexus/security.json" \
    --rules "$HOME/.config/nexus/security-rules"
  SECURITY_JOB=$(python3 tools/security/security_host.py start --config "$HOME/.config/nexus/security.json" --commit "$COMMIT_SHA")
fi
timing source_snapshot "$phase_start"
phase_start=$SECONDS
BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Recording deploy_info.json: commit=$COMMIT_SHA built_at=$BUILT_AT"
printf '{\n  "commit": "%s",\n  "built_at": "%s"\n}\n' "$COMMIT_SHA" "$BUILT_AT" > project/deploy_info.json

echo "Starting loader..."
kubectl apply -f deploy/ui2-image-build/00-namespace.yaml
kubectl apply -f deploy/ui2-image-build/10-context-pvc.yaml
kubectl delete pod ui2-build-context-loader -n ui2-build --ignore-not-found --force --grace-period=0 || true
kubectl apply -f deploy/ui2-image-build/20-context-loader.yaml
kubectl wait --for=condition=Ready pod/ui2-build-context-loader -n ui2-build --timeout=60s

echo "Streaming context..."
tar -cf - ui2 project docs | kubectl exec -i -n ui2-build ui2-build-context-loader -- sh -c "rm -rf /workspace/ui2 /workspace/project /workspace/docs && tar -xf - -C /workspace"

echo "Deleting loader to free PVC..."
kubectl delete -f deploy/ui2-image-build/20-context-loader.yaml

echo "Starting build job..."
kubectl -n ui2-build create configmap ui2-build-config --from-literal=image_tag="$(git rev-parse --short=12 HEAD)" --from-literal=commit="$COMMIT_SHA" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n ui2-build delete job ui2-image-build --ignore-not-found
timing build_context "$phase_start"
image_start=$SECONDS
kubectl apply -f ~/build-job-proxy.yaml
# Start from the same immutable context while the service image is building.
e2e_start=$SECONDS
python3 tools/e2e/hosta_e2e_image.py start --commit "$COMMIT_SHA"

echo "Waiting for build pod to be scheduled..."
for i in {1..30}; do
  POD=$(kubectl -n ui2-build get pod -l job-name=ui2-image-build -o jsonpath="{.items[0].metadata.name}" 2>/dev/null || true)
  if [ -n "$POD" ]; then
    break
  fi
  sleep 1
done

echo "Streaming build logs from pod $POD..."
kubectl -n ui2-build logs -f "$POD" || true

echo "Checking build job completion status..."
# The log stream can end before the job does (a dropped stream, or a retried pod after a transient
# base-image pull timeout -- backoffLimit 2). Wait for the job itself: complete, or failed for good.
for i in $(seq 1 180); do
  if kubectl -n ui2-build get job ui2-image-build -o jsonpath='{.status.conditions[?(@.type=="Complete")].status}' 2>/dev/null | grep -q True; then
    break
  fi
  if kubectl -n ui2-build get job ui2-image-build -o jsonpath='{.status.conditions[?(@.type=="Failed")].status}' 2>/dev/null | grep -q True; then
    echo "Build job failed after its retries. Last pod:"
    kubectl -n ui2-build logs "$(kubectl -n ui2-build get pod -l job-name=ui2-image-build -o name | tail -1)" --tail=5 || true
    exit 1
  fi
  sleep 10
done
if ! kubectl -n ui2-build get job ui2-image-build -o jsonpath='{.status.conditions[?(@.type=="Complete")].status}' | grep -q True; then
  echo "Build job did not finish within 30 minutes."
  exit 1
fi

timing image_build "$image_start"
echo "Starting loader again to read digest..."
kubectl delete pod ui2-build-context-loader -n ui2-build --ignore-not-found --force --grace-period=0 || true
kubectl apply -f deploy/ui2-image-build/20-context-loader.yaml
kubectl wait --for=condition=Ready pod/ui2-build-context-loader -n ui2-build --timeout=60s
IMAGE_DIGEST="$(kubectl -n ui2-build exec ui2-build-context-loader -- cat /workspace/image-digest.txt)"
echo "Digest is $IMAGE_DIGEST"

if [ -z "${NEXUS_SKIP_SECURITY_REASON:-}" ] && [ "$SECURITY_CONFIGURED" = 1 ]; then
  gate_start=$SECONDS
  echo "Running security-gate on the freshly built digest..."
  python3 tools/security/security_host.py gate --config "$HOME/.config/nexus/security.json" \
    --image "registry.kube-system.svc.cluster.local/nexus-ui2-service@$IMAGE_DIGEST" --commit "$COMMIT_SHA" --job-name "$SECURITY_JOB"
  timing security_gate_wait "$gate_start"
fi

rollout_start=$SECONDS
echo "Updating deployments..."
kubectl -n ui2 set image deployment/ui2-service service="registry.kube-system.svc.cluster.local/nexus-ui2-service@$IMAGE_DIGEST"
kubectl -n ui2 set image deployment/ui2-compliance compliance="registry.kube-system.svc.cluster.local/nexus-ui2-service@$IMAGE_DIGEST"

# PO, 2026-09-24: never replace the worker under a running job (a rollout drains it and a long job -- an MDS export --
# is cut off). The check sits here, right before the worker changes, not at the start: a job started during the build
# is seen. Waits up to WORKER_WAIT_LIMIT_S (default 4 h), then leaves the worker on the old image and says so.
inflight() {
  kubectl -n ui2 exec ui2-db-0 -- sh -c "psql -U \"\$POSTGRES_USER\" -d ui2 -Atc \"select count(*) from jobs where state in ('CLAIMED','EXECUTING')\""
}
waited=0
while n="$(inflight)" && [ "$n" != "0" ]; do
  if [ "$waited" -ge "${WORKER_WAIT_LIMIT_S:-14400}" ]; then
    echo "Worker NOT updated: $n job(s) still in flight after ${waited}s; service and compliance are on the new image, worker and configuration are not."
    exit 5
  fi
  echo "Worker waiting: $n job(s) in flight; checking again in 30 s (waited ${waited}s)"
  sleep 30
  waited=$((waited + 30))
done
kubectl -n ui2 set image deployment/ui2-worker worker="registry.kube-system.svc.cluster.local/nexus-ui2-service@$IMAGE_DIGEST"
# ui2-configuration is the second worker (args: worker configuration); it was left on a 2026-09-24 image until the
# 2026-10-01 security scan found it, so it follows the worker under the same in-flight check.
kubectl -n ui2 set image deployment/ui2-configuration configuration="registry.kube-system.svc.cluster.local/nexus-ui2-service@$IMAGE_DIGEST"

echo "Waiting for rollout..."
kubectl -n ui2 rollout status deployment/ui2-service --timeout=600s
kubectl -n ui2 rollout status deployment/ui2-worker --timeout=600s
kubectl -n ui2 rollout status deployment/ui2-compliance --timeout=600s
kubectl -n ui2 rollout status deployment/ui2-configuration --timeout=600s

timing rollout "$rollout_start"
python3 tools/e2e/hosta_e2e_image.py ensure --commit "$COMMIT_SHA" >/dev/null
timing e2e_image_ready "$e2e_start"
echo "Done. Deployed commit: $COMMIT_SHA (built_at $BUILT_AT)"
