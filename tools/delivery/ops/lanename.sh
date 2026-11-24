#!/bin/bash
# Standard lane name (PO 2026-11-24): <YYYYMMDD>-<HHMM>-G<all-time>-D<day seq>-<description>, given when the lane is
# QUEUED, so briefs, ship entries, branches and reports carry one name from the start.
# Usage: lanename.sh <description>   (a legacy "NNN-" or an existing standard prefix is stripped first)
set -e
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
desc=$(echo "${1:?description}" | sed -E 's/^([0-9]{8}-[0-9]{4}-G[0-9]+-D[0-9]+-|[0-9]{1,5}-)//')
read -r lbl tot < <(bash "$OPS_DIR/counter.sh" L "$desc")
echo "$(date +%Y%m%d)-$(date +%H%M)-G${tot}-D${lbl##*-}-${desc}"
