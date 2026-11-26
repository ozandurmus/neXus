#!/bin/bash
# Local self-test, no pod, no database, no device. Needs the classes built first:
#   cd ui2 && ./gradlew --no-daemon :platform-core:classes :persistence:classes :service:classes
# Usage: bash scripts/measurement/fortigate_policy_evaluation/selftest.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd); T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

# 1. show_safe.sh drops and counts an unsafe line, keeps a safe one.
printf 'fgt_01.v01\trules\t3\nFW-TANGO-04\trules\t3\n' > "$T/in.tsv"
OUT=$(sh "$HERE/show_safe.sh" "$T/in.tsv")
[ "$OUT" = "$(printf 'fgt_01.v01\trules\t3\nfiltered_lines\t1')" ] || { echo "show_safe failed"; exit 1; }
echo "show_safe	ok"

# 2. Compile FortiGateEvaluationMeasure against :service, :persistence, :platform-core classes + cached jars, and run --selftest.
jar() { find ~/.gradle/caches -path "*$1*" -name "$2-*.jar" ! -name '*sources*' | sort -V | tail -1; }
CP=""
for m in service persistence platform-core; do
  [ -d "$ROOT/ui2/$m/build/classes/java/main" ] || { echo "build $m classes first"; exit 2; }
  CP="$CP$ROOT/ui2/$m/build/classes/java/main:"
done
for j in "org.postgresql postgresql" "org.slf4j slf4j-api"; do
  set -- $j; JAR=$(jar "$1" "$2"); [ -n "$JAR" ] || { echo "missing $2 jar in the gradle cache"; exit 2; }
  CP="$CP$JAR:"
done
mkdir -p "$T/classes"
javac --release 21 -nowarn -parameters -cp "$CP" -d "$T/classes" "$HERE/FortiGateEvaluationMeasure.java" 2>&1 | grep -v "^Note:" || true
[ -f "$T/classes/com/securityexpert/nexus/ui2/service/policy/FortiGateEvaluationMeasure.class" ] || { echo "compile failed"; exit 3; }
java -cp "$T/classes:$CP" com.securityexpert.nexus.ui2.service.policy.FortiGateEvaluationMeasure --selftest "$T/evaluation.out"

# 3. Verify show_safe.sh passes every line of the synthetic evaluation output with filtered_lines 0 and zero canary leaks.
SAFE_OUT=$(sh "$HERE/show_safe.sh" "$T/evaluation.out")
LAST_LINE=$(printf '%s\n' "$SAFE_OUT" | tail -1)
[ "$LAST_LINE" = "$(printf 'filtered_lines\t0')" ] || { echo "measurement output had unsafe lines: $LAST_LINE"; exit 4; }
for canary in SYNTH CANARY TANGO 192.0.2 198.51.100 203.0.113; do
  if grep -qi -- "$canary" "$T/evaluation.out"; then echo "canary leaked: $canary"; exit 5; fi
done
grep -q "$(printf 'summary\tvip_queries\t')" "$T/evaluation.out" || { echo "missing vip_queries"; exit 6; }
grep -q "$(printf 'vip_unknown_reason.fgt_vip_priority_unproven\t')" "$T/evaluation.out" || { echo "missing fgt_vip_priority_unproven"; exit 6; }
grep -q "$(printf 'failure.fgt_unsupported_mode\t')" "$T/evaluation.out" || { echo "missing fgt_unsupported_mode"; exit 6; }
echo "$LAST_LINE"
