#!/bin/bash
# Usage: EVAL_WORK_DIR=<scratch dir with podjar/ from a previous run> evaluation_run.sh <tag>
# Compiles this worktree's ASA parser/mapper and policy engine (PolicyNormalization, PolicyLookup, PolicyHygiene and the
# sources they need) plus AsaEvaluationMeasure against the pod's service jar; runs in ui2-service; counters only.
set -e
W=${EVAL_WORK_DIR:?set EVAL_WORK_DIR}; HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
TAG=$1; H=$(head -1 ~/.config/nexus/hosta); K='KUBECONFIG=$HOME/.kube/config kubectl -n ui2'
cd $W; [ -d podjar ] || { echo "no podjar/ in work dir"; exit 4; }
CP="podjar/BOOT-INF/classes:$(ls podjar/BOOT-INF/lib/*.jar | tr '\n' ':')"
D=stage-$TAG/eval-$TAG; rm -rf stage-$TAG; mkdir -p $D/classes
SP=$ROOT/ui2/service/src/main/java:$ROOT/ui2/platform-core/src/main/java
P=$ROOT/ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/policy
javac --release 21 -nowarn -implicit:class -cp "$CP" -sourcepath "$SP" -d $D/classes $HERE/AsaEvaluationMeasure.java \
  $P/PolicyNormalization.java $P/PolicyLookup.java $P/PolicyHygiene.java $P/PolicyRuleMetrics.java \
  $ROOT/ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/policy/PolicySnapshot.java \
  $ROOT/ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/policy/asa/*.java 2>&1 | grep -v "^Note:" | head -5 || true
[ -f $D/classes/com/securityexpert/nexus/ui2/service/policy/AsaEvaluationMeasure.class ] || { echo "compile failed"; exit 5; }
mkdir -p $D/classes/policy/asa && cp $ROOT/ui2/platform-core/src/main/resources/policy/asa/*.tsv $D/classes/policy/asa/
cp $HERE/run_in_pod.sh $HERE/show_safe.sh $D/
(cd stage-$TAG && COPYFILE_DISABLE=1 tar --no-xattrs -cf - eval-$TAG) | ssh $H "$K exec -i deploy/ui2-service -- tar xf - -C /app/tmp 2>/dev/null; $K exec deploy/ui2-service -- sh /app/tmp/eval-$TAG/run_in_pod.sh >/dev/null; $K exec deploy/ui2-service -- sh -c 'n=0; while ! grep -q -E \"^(done|fatal)\" /app/tmp/eval-$TAG/evaluation.out 2>/dev/null; do sleep 5; n=\$((n+1)); [ \$n -gt 480 ] && break; done; sh /app/tmp/eval-$TAG/show_safe.sh /app/tmp/eval-$TAG/evaluation.out; rm -rf /app/tmp/eval-$TAG'" 2>&1 | grep -v -E "^\*|Authorized|Yalnizca" > eval-$TAG.tsv
echo "lines $(wc -l < eval-$TAG.tsv)"
