#!/usr/bin/env bash
# Run on the deployment host; never writes to the product checkout.
set -euo pipefail
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"
exec python3 "$(dirname "$0")/release_snapshot.py" snapshot "$@"
