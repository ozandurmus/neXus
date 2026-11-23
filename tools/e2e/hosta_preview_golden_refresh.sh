#!/usr/bin/env bash
# Nightly golden preview database refresh (docs/operations/PREVIEW_GOLDEN_DATABASE.md). Runs on the host, from the
# deployed tree, under the deploy account's crontab. Only timings and verdicts are kept; the step log stays private.
set -euo pipefail
export PATH=/usr/local/bin:/usr/bin:/bin:${PATH:-}
export KUBECONFIG=${KUBECONFIG:-$HOME/.kube/config}
tree=${NEXUS_TREE:-/opt/nexus}
state=$HOME/.local/state/nexus-preview
mkdir -p "$state"
chmod 700 "$state"
{
  printf 'refresh-golden start %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  python3 "$tree/tools/e2e/hosta_preview_e2e.py" refresh-golden --repo "$tree" 2>/dev/null | \
    awk '/^TIMING preview_[a-z_]+ [0-9.]+$/ || /^PREVIEW (GOLDEN|STEP|E2E): /'
} >> "$state/golden-refresh.out"
