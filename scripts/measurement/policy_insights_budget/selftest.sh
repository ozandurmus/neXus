#!/bin/bash
# Local self-test, no pod, no database, no device. Needs the classes built first:
#   cd ui2 && ./gradlew --no-daemon :service:classes :persistence:classes :platform-core:classes :worker:classes
# Usage: bash scripts/measurement/policy_insights_budget/selftest.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd); T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
# 1. show_safe.sh drops and counts an unsafe line, keeps a safe one.
printf 'engine\tp01.union_cover\tevaluated\t3\nengine\tFW-TANGO-04\tevaluated\t3\n' > "$T/in.tsv"
OUT=$(sh "$HERE/show_safe.sh" "$T/in.tsv")
[ "$OUT" = "$(printf 'engine\tp01.union_cover\tevaluated\t3\nfiltered_lines\t1')" ] || { echo "show_safe failed"; exit 1; }
echo "show_safe	ok"
# 2. The measurement code over inline synthetic Check Point snapshots, a tiny work limit included.
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
javac --release 21 -nowarn -parameters -cp "$CP" -d "$T/classes" "$HERE/PolicyInsightsBudgetMeasure.java" 2>&1 | grep -v "^Note:" || true
[ -f "$T/classes/nexusmeasure/PolicyInsightsBudgetMeasure.class" ] || { echo "compile failed"; exit 3; }
java -cp "$T/classes:$CP" nexusmeasure.PolicyInsightsBudgetMeasure --selftest
# 3. The synthetic report as the run would print it: every line safe, no planted name or address, both hit values present.
java -cp "$T/classes:$CP" nexusmeasure.PolicyInsightsBudgetMeasure --selftest-report > "$T/report.tsv"
SAFE=$(sh "$HERE/show_safe.sh" "$T/report.tsv")
[ "$(printf '%s\n' "$SAFE" | tail -1)" = "$(printf 'filtered_lines\t0')" ] || { echo "report has an unsafe line"; exit 1; }
for planted in tango 192.0.2 198.51.100 uuid pol- obj-; do
  if grep -qi -- "$planted" "$T/report.tsv"; then echo "planted text reached the report"; exit 1; fi
done
grep -q "$(printf 'budget\tfleet.union_cover\tany_hit\ttrue')" "$T/report.tsv" || { echo "no any_hit true line"; exit 1; }
grep -q "$(printf 'budget\tfleet.union_cover\tany_hit\tfalse')" "$T/report.tsv" || { echo "no any_hit false line"; exit 1; }
echo "report	ok"
