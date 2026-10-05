#!/bin/bash
# HOST-A build/rollout script (docs/design/HOST_REGISTER.md, PO Amendment 2026-09-19).
# Canonical copy lives here; HOST-A's ~/run_build.sh must be kept identical to this file
# (this script previously existed only as an unversioned host-local copy -- durability gap
# closed 2026-09-21). To deploy a change made to this script itself, copy it to HOST-A's
# home directory first, then run it from there as usual.
set -euo pipefail
export KUBECONFIG=~/.kube/config

CHANGES_STARTED=0
report_failure() {
  rc=$?
  if [ "$rc" -ne 0 ] && [ "$CHANGES_STARTED" = 0 ]; then
    echo "no changes applied" >&2
  fi
}
trap report_failure EXIT
REPO="${REPO:-$HOME/nexus}"
cd "$REPO"
NEXUS_DEPLOY_TARGETS="${NEXUS_DEPLOY_TARGETS:-service worker configuration compliance policy}"
git fetch
git checkout main
git pull origin main

# Use the updated checkout's scripts before any build or deployment changes.
python3 "$REPO/tools/delivery/module_deploy.py" --validate-targets "$NEXUS_DEPLOY_TARGETS"

# Capture the RUNNING release before security loaders, optional manifests or build mutations.
COMMIT_SHA="$(git rev-parse HEAD)"
RELEASE_SNAPSHOT=$(bash tools/delivery/release_snapshot.sh --commit "$COMMIT_SHA")
printf '%s\n' "$RELEASE_SNAPSHOT"
for manifest in ${NEXUS_DEPLOY_APPLY_FILES:-}; do
  CHANGES_STARTED=1
  kubectl apply -f "$manifest"
done

phase_start=$SECONDS
timing() { printf "TIMING %s %s\n" "$1" "$((SECONDS - $2))"; }
SECURITY_CONFIGURED=0
[ -f "$HOME/.config/nexus/security.json" ] && SECURITY_CONFIGURED=1
if [ -n "${NEXUS_SKIP_SECURITY_REASON:-}" ]; then
  printf 'EMERGENCY: SECURITY GATE SKIPPED: %s\n' "$NEXUS_SKIP_SECURITY_REASON"
elif [ "$SECURITY_CONFIGURED" = 0 ]; then
  echo '{"security_gate":"not_configured"}'
else
  CHANGES_STARTED=1
  python3 "$REPO/tools/security/security_host.py" snapshot --config "$HOME/.config/nexus/security.json" \
    --rules "$HOME/.config/nexus/security-rules"
  SECURITY_JOB=$(python3 "$REPO/tools/security/security_host.py" start --config "$HOME/.config/nexus/security.json" --commit "$COMMIT_SHA")
fi
timing source_snapshot "$phase_start"
phase_start=$SECONDS
BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Recording deploy_info.json: commit=$COMMIT_SHA built_at=$BUILT_AT"
printf '{\n  "commit": "%s",\n  "built_at": "%s"\n}\n' "$COMMIT_SHA" "$BUILT_AT" > project/deploy_info.json

echo "Starting loader..."
CHANGES_STARTED=1
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
python3 "$REPO/tools/e2e/hosta_e2e_image.py" start --commit "$COMMIT_SHA"

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
  python3 "$REPO/tools/security/security_host.py" gate --config "$HOME/.config/nexus/security.json" \
    --image "registry.kube-system.svc.cluster.local/nexus-ui2-service@$IMAGE_DIGEST" --commit "$COMMIT_SHA" --job-name "$SECURITY_JOB"
  timing security_gate_wait "$gate_start"
fi

python3 "$REPO/tools/e2e/hosta_e2e_image.py" ensure --commit "$COMMIT_SHA" >/dev/null
rollout_start=$SECONDS
echo "Updating selected deployments..."
SNAPSHOT_ID=$(printf '%s\n' "$RELEASE_SNAPSHOT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot"])')
python3 "$REPO/tools/delivery/module_deploy.py" --targets "$NEXUS_DEPLOY_TARGETS" \
  --image "registry.kube-system.svc.cluster.local/nexus-ui2-service@$IMAGE_DIGEST" \
  --snapshot "$HOME/release-snapshots/$SNAPSHOT_ID" --commit "$COMMIT_SHA" --wait-limit "${WORKER_WAIT_LIMIT_S:-14400}" \
  --authorization-ref "${NEXUS_MODULE_AUTHORIZATION_REF:-}"

timing rollout "$rollout_start"
python3 "$REPO/tools/e2e/hosta_e2e_image.py" ensure --commit "$COMMIT_SHA" >/dev/null
timing e2e_image_ready "$e2e_start"
echo "Done. Deployed commit: $COMMIT_SHA (built_at $BUILT_AT)"
