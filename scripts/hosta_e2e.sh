#!/usr/bin/env bash
# Deprecated compatibility entrypoint; use tools/e2e/hosta_e2e.sh.
exec bash "$(dirname "$0")/../tools/e2e/hosta_e2e.sh" "$@"
