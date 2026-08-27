#!/bin/bash

OUT="/var/tmp/vsx_dump_$(hostname)_$(date +%s)"
mkdir -p "$OUT"

VS_LIST=$(vsx stat | awk '/^[0-9]+/ {print $1}')

for VS in $VS_LIST; do

    vsenv $VS >/dev/null 2>&1

    ip -4 addr show > "$OUT/vs_${VS}_interfaces.txt"
    ip route > "$OUT/vs_${VS}_routes.txt"

done

echo "OUT_DIR=$OUT"