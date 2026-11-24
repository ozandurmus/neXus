#!/bin/bash
# Local self-test, no pod, no database, no device. Needs the classes built first:
#   cd ui2 && ./gradlew :worker:classes :persistence:classes :platform-core:classes :job-engine:classes
# Usage: bash scripts/measurement/pan_vsys_zone_sets_pre12/selftest.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd); T=$(mktemp -d)
# 1. show_safe.sh drops and counts an unsafe line, keeps a safe one.
printf 'device\tpan_01\tvsys_total\t3\ndevice\tFW-TANGO-04\tvsys_total\t3\n' > "$T/in.tsv"
OUT=$(sh "$HERE/show_safe.sh" "$T/in.tsv")
[ "$OUT" = "$(printf 'device\tpan_01\tvsys_total\t3\nfiltered_lines\t1')" ] || { echo "show_safe failed"; exit 1; }
echo "show_safe	ok"
# 2. The pure counting logic over inline synthetic documents.
PG=$(find ~/.gradle/caches -name 'postgresql-42.*.jar' -path '*org.postgresql*' | sort | tail -1)
CP=""; for m in worker persistence platform-core job-engine capability-registry; do
  [ -d "$ROOT/ui2/$m/build/classes/java/main" ] || { echo "build $m classes first"; exit 2; }
  CP="$CP$ROOT/ui2/$m/build/classes/java/main:"
done
CP="$CP$PG"
javac --release 21 -nowarn -cp "$CP" -d "$T/classes" "$HERE/PanPre12ZoneSetMeasure.java"
java -cp "$T/classes:$CP" nexusmeasure.PanPre12ZoneSetMeasure --selftest
rm -rf "$T"
