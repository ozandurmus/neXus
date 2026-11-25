#!/bin/bash
# Local self-test, no pod, no database, no device. Needs the classes built first:
#   cd ui2 && ./gradlew :service:classes :persistence:classes :platform-core:classes :worker:classes
# Usage: bash scripts/measurement/policy_collection_gaps/selftest.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd); T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
# 1. show_safe.sh drops and counts an unsafe line, keeps a safe one.
printf 'engine\tp01.sound\tunknown_total\t3\nengine\tFW-TANGO-04\tunknown_total\t3\n' > "$T/in.tsv"
OUT=$(sh "$HERE/show_safe.sh" "$T/in.tsv")
[ "$OUT" = "$(printf 'engine\tp01.sound\tunknown_total\t3\nfiltered_lines\t1')" ] || { echo "show_safe failed"; exit 1; }
echo "show_safe	ok"
# 2. The engine counters and the gateway completeness reasons over inline synthetic snapshots and inventory nodes.
jar() { find ~/.gradle/caches -path "*$1*" -name "$2-*.jar" ! -name '*sources*' | sort -V | tail -1; }
CP=""; for m in service persistence platform-core worker; do
  [ -d "$ROOT/ui2/$m/build/classes/java/main" ] || { echo "build $m classes first"; exit 2; }
  CP="$CP$ROOT/ui2/$m/build/classes/java/main:"
done
for j in "com.fasterxml.jackson.core jackson-databind" "com.fasterxml.jackson.core jackson-core" \
         "com.fasterxml.jackson.core jackson-annotations" "com.fasterxml.jackson.datatype jackson-datatype-jsr310" \
         "org.postgresql postgresql" "org.slf4j slf4j-api"; do
  set -- $j; JAR=$(jar "$1" "$2"); [ -n "$JAR" ] || { echo "missing $2 jar in the gradle cache"; exit 2; }
  CP="$CP$JAR:"
done
javac --release 21 -nowarn -parameters -cp "$CP" -d "$T/classes" "$HERE/GapEngineMeasure.java" 2>&1 | grep -v "^Note:" || true
[ -f "$T/classes/nexusmeasure/GapEngineMeasure.class" ] || { echo "compile failed"; exit 3; }
java -cp "$T/classes:$CP" nexusmeasure.GapEngineMeasure --selftest
