#!/usr/bin/env bash
# Preview by default. Applying a snapshot requires an explicit --apply.
set -euo pipefail
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"
exec python3 "$(dirname "$0")/release_snapshot.py" rollback "$@"
