#!/bin/bash
# Local self-test, no pod, no database, no device. Needs the classes built first:
#   cd ui2 && ./gradlew --no-daemon :worker:classes :persistence:classes :platform-core:classes :job-engine:classes
# Usage: bash scripts/measurement/asa_syslog_hostdown/selftest.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd); T=$(mktemp -d)
# 1. show_safe.sh drops and counts an unsafe line, keeps a safe one.
printf 'device\tasa_01\tpermit_hostdown_present\t1\ndevice\tFW-TANGO-04\tpermit_hostdown_present\t1\n' > "$T/in.tsv"
OUT=$(sh "$HERE/show_safe.sh" "$T/in.tsv")
[ "$OUT" = "$(printf 'device\tasa_01\tpermit_hostdown_present\t1\nfiltered_lines\t1')" ] || { echo "show_safe failed"; exit 1; }
echo "show_safe	ok"
# 2. The pure line-scanner and fleet aggregation logic over inline synthetic ASA configs.
PG=$(find ~/.gradle/caches -name 'postgresql-42.*.jar' -path '*org.postgresql*' | sort | tail -1)
CP=""; for m in worker persistence platform-core job-engine capability-registry; do
  [ -d "$ROOT/ui2/$m/build/classes/java/main" ] || { echo "build $m classes first"; exit 2; }
  CP="$CP$ROOT/ui2/$m/build/classes/java/main:"
done
CP="$CP$PG"
javac --release 21 -nowarn -cp "$CP" -d "$T/classes" "$HERE/AsaSyslogHostdownMeasure.java"
java -cp "$T/classes:$CP" nexusmeasure.AsaSyslogHostdownMeasure --selftest
rm -rf "$T"
