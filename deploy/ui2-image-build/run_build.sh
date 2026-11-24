#!/bin/bash
# HOST-A build/rollout script (docs/design/HOST_REGISTER.md, PO Amendment 2026-09-19).
# Canonical copy lives here; HOST-A's ~/run_build.sh must be kept identical to this file
# (this script previously existed only as an unversioned host-local copy -- durability gap
# closed 2026-09-21). To deploy a change made to this script itself, copy it to HOST-A's
# home directory first, then run it from there as usual.
set -euo pipefail
export KUBECONFIG=~/.kube/config

CHANGES_STARTED=0
SECURITY_PID=""
SECURITY_JOB_FILE=""
report_failure() {
  rc=$?
  # Ignore repeated TERM/INT while draining the background security snapshot so a second signal cannot orphan it.
  trap '' TERM INT
  # Remove the gate Job name file before waiting so the background subshell sees the abort and skips
  # `security_host.py start` once `snapshot` finishes (releasing security.lock and security-source-loader
  # without launching an abandoned security-gate Job).
  if [ -n "$SECURITY_JOB_FILE" ]; then rm -f "$SECURITY_JOB_FILE"; fi
  # A deploy that stops early never leaves the background security source snapshot half done: it is waited for (its
  # own command limits bound it), so the security tooling finds the same end state as when it ran in the foreground.
  if [ -n "$SECURITY_PID" ]; then wait "$SECURITY_PID" 2>/dev/null || true; fi
  # Remove it again after wait in case `start` was already running and recreated the file via redirection.
  if [ -n "$SECURITY_JOB_FILE" ]; then rm -f "$SECURITY_JOB_FILE"; fi
  if [ "$rc" -ne 0 ] && [ "$CHANGES_STARTED" = 0 ]; then
    echo "no changes applied" >&2
  fi
}
trap report_failure EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
# The deploy tree is /opt/nexus, owned by the non-login service account `nexus` (docs/operations/HOST_A_RUNBOOK.md §2).
# This script runs as the SSH deploy account, which only reads the tree: every write (git, deploy_info.json) goes
# through sudo -u nexus, so ownership never drifts and each write is in the sudo log.
REPO="${REPO:-/opt/nexus}"
NEXUS_TREE_OWNER="${NEXUS_TREE_OWNER:-nexus}"
# umask 0022: what the owner writes is world-readable inside the closed top directory (2750). Image builds copy
# file modes into the image, where the runtime user is neither owner nor group: a 0640 file broke the e2e suite
# on 2026-11-13 (EACCES on /suite/playwright.config.ts).
as_tree_owner() {
  if [ "$(id -un)" = "$NEXUS_TREE_OWNER" ]; then (umask 0022 && "$@"); else (umask 0022 && sudo -n -u "$NEXUS_TREE_OWNER" "$@"); fi
}
# The deploy account cannot write the tree, so Python must not try to cache bytecode there.
export PYTHONDONTWRITEBYTECODE=1
cd "$REPO"
NEXUS_DEPLOY_TARGETS="${NEXUS_DEPLOY_TARGETS:-service worker configuration compliance policy backup script-runner packet-analysis}"
as_tree_owner git fetch
as_tree_owner git checkout main
as_tree_owner git pull origin main

# Use the updated checkout's scripts before any build or deployment changes.
python3 "$REPO/tools/delivery/module_deploy.py" --validate-targets "$NEXUS_DEPLOY_TARGETS"
# Stop before any change if a tracked file or directory lost its world-read bit (images would carry the mode).
python3 "$REPO/tools/delivery/tree_modes.py" check --repo "$REPO"

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
  # PO 2026-11-23: when the pre-merge gate (security_host.py pregate, run by the ship beside the preview) left a PASS for
  # this commit's TREE under the same gate policy and gate day, the Job started below skips only the tree-determined
  # scans (semgrep, gitleaks over the tree, trivy fs/config); the Git history and EVERY image built below are still
  # scanned. `gate` re-checks the verdict after the Job passes and runs the full gate otherwise.
  # Ship deploy speed (PO 2026-11-23): the security source snapshot and the gate Job start (about 40 s) run in the
  # background, beside the build context and the image builds, instead of before them. The commands, their order and
  # their arguments are unchanged; nothing reaches the security gate or the rollout before both have finished (the
  # join before the gate below), and a failure of either stops the deploy there, before any rollout, as before.
  # The subshell prints `TIMING source_snapshot` itself when it is done.
  SECURITY_JOB_FILE="$(mktemp "${TMPDIR:-/tmp}/nexus-security-job.XXXXXX")"
  (
    python3 "$REPO/tools/security/security_host.py" snapshot --config "$HOME/.config/nexus/security.json" \
      --rules "$HOME/.config/nexus/security-rules"
    if [ -f "$SECURITY_JOB_FILE" ]; then
      python3 "$REPO/tools/security/security_host.py" start --config "$HOME/.config/nexus/security.json" \
        --commit "$COMMIT_SHA" --repo "$REPO" --rules "$HOME/.config/nexus/security-rules" > "$SECURITY_JOB_FILE"
      timing source_snapshot "$phase_start"
    fi
  ) &
  SECURITY_PID=$!
fi
[ -n "$SECURITY_PID" ] || timing source_snapshot "$phase_start"
phase_start=$SECONDS
BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Recording deploy_info.json: commit=$COMMIT_SHA built_at=$BUILT_AT"
printf '{\n  "commit": "%s",\n  "built_at": "%s"\n}\n' "$COMMIT_SHA" "$BUILT_AT" | as_tree_owner tee project/deploy_info.json >/dev/null

echo "Starting loader..."
CHANGES_STARTED=1
kubectl apply -f deploy/ui2-image-build/00-namespace.yaml
kubectl apply -f deploy/ui2-image-build/10-context-pvc.yaml
kubectl delete pod ui2-build-context-loader -n ui2-build --ignore-not-found --force --grace-period=0 || true
kubectl apply -f deploy/ui2-image-build/20-context-loader.yaml
kubectl wait --for=condition=Ready pod/ui2-build-context-loader -n ui2-build --timeout=60s

echo "Streaming context..."
# The chmod after extraction makes every image independent of host file modes: the context is world-readable
# whatever the tree's modes, so a COPY never yields a file the image's runtime user cannot read.
tar -cf - ui2 project docs | kubectl exec -i -n ui2-build ui2-build-context-loader -- sh -c "rm -rf /workspace/ui2 /workspace/project /workspace/docs && tar -xf - -C /workspace && chmod -R a+rX /workspace/ui2 /workspace/project /workspace/docs"

echo "Deleting loader to free PVC..."
# The loader's only process is `sleep infinity` as PID 1, which ignores SIGTERM: the default 30 s grace period was
# always waited out in full (most of the ~37 s build_context). One second, then SIGKILL; the delete still waits until
# the pod is gone, so the claim is free exactly as before. The loader holds no state (the stream above has returned).
kubectl delete -f deploy/ui2-image-build/20-context-loader.yaml --grace-period=1

echo "Starting build job..."
kubectl -n ui2-build create configmap ui2-build-config --from-literal=image_tag="$(git rev-parse --short=12 HEAD)" --from-literal=commit="$COMMIT_SHA" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n ui2-build delete job ui2-image-build --ignore-not-found
timing build_context "$phase_start"
image_start=$SECONDS
# The runner image is built from the same immutable context, concurrently with the service image.
e2e_start=$SECONDS
python3 "$REPO/tools/e2e/hosta_e2e_image.py" start --commit "$COMMIT_SHA"

# A passing preview of an IDENTICAL git tree already built and tested this service image. Production adds only
# project/deploy_info.json (the deployed commit), so re-stamp that one layer instead of rebuilding everything
# (tools/delivery/hosta_restamp_image.py, ui2/Containerfile.restamp). Any problem -- no record, stale, the registry
# lost the digest, the Job failed -- leaves REUSED_IMAGE empty and the normal full build below runs. The security gate
# scans whichever digest results. NEXUS_REUSE_PREVIEW_IMAGE=0 disables the reuse.
REUSED_IMAGE=""
if [ "${NEXUS_REUSE_PREVIEW_IMAGE:-1}" = 1 ]; then
  REUSE_RC=0
  REUSED_IMAGE="$(python3 "$REPO/tools/delivery/hosta_restamp_image.py" build --commit "$COMMIT_SHA" 2>/dev/null)" || REUSE_RC=$?
  if ! printf '%s' "$REUSED_IMAGE" | grep -Eq '^registry\.kube-system\.svc\.cluster\.local/nexus-ui2-service@sha256:[a-f0-9]{64}$'; then
    REUSED_IMAGE=""
  fi
  case "$REUSE_RC" in
    0) REUSE_WHY="tool returned no digest" ;;
    3) REUSE_WHY="no fresh preview record for this tree, or the re-stamp job did not complete" ;;
    *) REUSE_WHY="re-stamp tool error (exit $REUSE_RC)" ;;
  esac
else
  REUSE_WHY="NEXUS_REUSE_PREVIEW_IMAGE=0"
fi
if [ -n "$REUSED_IMAGE" ]; then
  IMAGE_DIGEST="${REUSED_IMAGE##*@}"
  echo "IMAGE PATH: reuse (preview-verified image of this tree, re-stamped with deploy_info.json; the new digest is scanned below)"
  timing image_build "$image_start"
  echo "Digest is $IMAGE_DIGEST"
else
  echo "IMAGE PATH: full build (${REUSE_WHY:-reuse not attempted})"
  kubectl apply -f ~/build-job-proxy.yaml

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
fi

# The side images, both FROM the main image just built, by digest, and both scanned by the same gate below:
# - the Packet Analysis engine image (docs/design/PACKET_ANALYSIS_ENGINE_IMAGE.md): plus tshark, so tshark never enters
#   the main image. Tag <commit12>-pa, rolled out to ui2-packet-analysis only (module_deploy.py --packet-analysis-image;
#   the main image has no tshark). NEXUS_BUILD_PACKET_ANALYSIS_IMAGE=0 skips it and drops packet-analysis from the
#   rollout. A failed build fails the deploy.
# - the script execution image (SCRIPT_EXECUTION_CONTRACT.md Amendment 2026-11-18 (b) item 8): plus the section 7
#   interpreters; it runs only in the per-run script-run-<id> pods. Tag <commit12>-sx, handed to the ui2-script-runner
#   broker as UI2_SCRIPT_EXEC_IMAGE (module_deploy.py --script-exec-image). NEXUS_BUILD_SCRIPT_EXEC_IMAGE=0 skips it; the
#   broker then keeps its current execution image. A failed build is optional (2026-11-20: the UBI package CDN answered
#   503 and stopped two releases): script-runner is dropped from the rollout and the broker keeps its current execution
#   image (or stays absent, so the product reports the runner unavailable). Everything else still rolls out.
# Since 2026-11-23 the two build at the same time (hosta_side_images.py; they were one after the other, about 41 s
# each). Neither can be skipped: the main digest is new on every deploy and a side image carries the main image's jar.
# The tool prints TIMING packet_analysis_image / script_exec_image / side_images itself (stderr, so the lines reach
# the log even when it fails) and PA_IMAGE= / SX_IMAGE= on stdout.
PA_IMAGE=""
SX_IMAGE=""
SIDE_FLAGS=""
[ "${NEXUS_BUILD_PACKET_ANALYSIS_IMAGE:-1}" != 0 ] && SIDE_FLAGS="--packet-analysis"
[ "${NEXUS_BUILD_SCRIPT_EXEC_IMAGE:-1}" != 0 ] && SIDE_FLAGS="${SIDE_FLAGS:+$SIDE_FLAGS }--script-exec"
if [ -n "$SIDE_FLAGS" ]; then
  # shellcheck disable=SC2086 # SIDE_FLAGS is one or two fixed option words.
  SIDE_IMAGES="$(python3 "$REPO/tools/delivery/hosta_side_images.py" build --commit "$COMMIT_SHA" --repo "$REPO" \
    --service-image "registry.kube-system.svc.cluster.local/nexus-ui2-service@$IMAGE_DIGEST" $SIDE_FLAGS)"
  PA_IMAGE="$(printf '%s\n' "$SIDE_IMAGES" | sed -n 's/^PA_IMAGE=//p')"
  SX_IMAGE="$(printf '%s\n' "$SIDE_IMAGES" | sed -n 's/^SX_IMAGE=//p')"
  if [ "${NEXUS_BUILD_PACKET_ANALYSIS_IMAGE:-1}" != 0 ]; then
    [ -n "$PA_IMAGE" ] || { echo "PACKET ANALYSIS IMAGE: no image reported; deploy stopped" >&2; exit 1; }
    echo "Packet analysis image is $PA_IMAGE"
  fi
  if [ "${NEXUS_BUILD_SCRIPT_EXEC_IMAGE:-1}" != 0 ]; then
    if [ -n "$SX_IMAGE" ]; then
      echo "Script execution image is $SX_IMAGE"
    else
      SX_FAILED=1
      echo "WARNING: script execution image build failed or unavailable; script-runner is left out of this rollout"
    fi
  fi
fi

# The background security source snapshot and gate Job start (above) must have finished before the gate.
if [ -n "$SECURITY_PID" ]; then
  join_start=$SECONDS
  if ! wait "$SECURITY_PID"; then
    SECURITY_PID=""
    echo "security source snapshot or gate start failed; rollout refused" >&2
    exit 1
  fi
  SECURITY_PID=""
  SECURITY_JOB="$(cat "$SECURITY_JOB_FILE")"
  rm -f "$SECURITY_JOB_FILE"
  SECURITY_JOB_FILE=""
  timing security_snapshot_wait "$join_start"
fi

if [ -z "${NEXUS_SKIP_SECURITY_REASON:-}" ] && [ "$SECURITY_CONFIGURED" = 1 ]; then
  gate_start=$SECONDS
  echo "Running security-gate on the freshly built digests..."
  python3 "$REPO/tools/security/security_host.py" gate --config "$HOME/.config/nexus/security.json" \
    --rules "$HOME/.config/nexus/security-rules" --repo "$REPO" \
    --image "registry.kube-system.svc.cluster.local/nexus-ui2-service@$IMAGE_DIGEST" ${PA_IMAGE:+--image "$PA_IMAGE"} \
    ${SX_IMAGE:+--image "$SX_IMAGE"} \
    --commit "$COMMIT_SHA" --job-name "$SECURITY_JOB"
  timing security_gate_wait "$gate_start"
fi

python3 "$REPO/tools/e2e/hosta_e2e_image.py" ensure --commit "$COMMIT_SHA" >/dev/null
rollout_start=$SECONDS
# Without an engine image (NEXUS_BUILD_PACKET_ANALYSIS_IMAGE=0) the packet-analysis target is dropped: it must never
# be rolled to the main image, which has no tshark.
if [ -z "$PA_IMAGE" ]; then
  KEPT_TARGETS=""
  for target in ${NEXUS_DEPLOY_TARGETS//,/ }; do
    if [ "$target" = packet-analysis ]; then
      echo "NOTE: packet-analysis removed from the rollout targets: no Packet Analysis engine image was built" \
           "(NEXUS_BUILD_PACKET_ANALYSIS_IMAGE=0); ui2-packet-analysis keeps running its current image."
    else
      KEPT_TARGETS="${KEPT_TARGETS:+$KEPT_TARGETS }$target"
    fi
  done
  NEXUS_DEPLOY_TARGETS="$KEPT_TARGETS"
fi
if [ "${SX_FAILED:-0}" = 1 ]; then
  KEPT_TARGETS=""
  for target in ${NEXUS_DEPLOY_TARGETS//,/ }; do
    [ "$target" = script-runner ] || KEPT_TARGETS="${KEPT_TARGETS:+$KEPT_TARGETS }$target"
  done
  NEXUS_DEPLOY_TARGETS="$KEPT_TARGETS"
fi
echo "Updating selected deployments..."
SNAPSHOT_ID=$(printf '%s\n' "$RELEASE_SNAPSHOT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot"])')
if [ -z "$NEXUS_DEPLOY_TARGETS" ]; then
  echo "No deployment targets left after dropping packet-analysis; nothing to roll out."
else
  python3 "$REPO/tools/delivery/module_deploy.py" --targets "$NEXUS_DEPLOY_TARGETS" \
    --image "registry.kube-system.svc.cluster.local/nexus-ui2-service@$IMAGE_DIGEST" \
    ${PA_IMAGE:+--packet-analysis-image "$PA_IMAGE"} \
    ${SX_IMAGE:+--script-exec-image "$SX_IMAGE"} \
    --snapshot "$HOME/release-snapshots/$SNAPSHOT_ID" --commit "$COMMIT_SHA" --wait-limit "${WORKER_WAIT_LIMIT_S:-14400}" \
    --authorization-ref "${NEXUS_MODULE_AUTHORIZATION_REF:-}"
fi

timing rollout "$rollout_start"
python3 "$REPO/tools/e2e/hosta_e2e_image.py" ensure --commit "$COMMIT_SHA" >/dev/null
timing e2e_image_ready "$e2e_start"
echo "Done. Deployed commit: $COMMIT_SHA (built_at $BUILT_AT)"
