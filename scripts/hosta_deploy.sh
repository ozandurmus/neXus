#!/usr/bin/env bash
# Deprecated compatibility entrypoint; use tools/delivery/hosta_deploy.sh.
exec bash "$(dirname "$0")/../tools/delivery/hosta_deploy.sh" "$@"
