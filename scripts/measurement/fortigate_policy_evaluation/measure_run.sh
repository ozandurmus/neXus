#!/bin/bash
# PO-session wrapper. Usage: MEASURE_WORK_DIR=<scratch dir> measure_run.sh <tag>
# Compiles FortiGateEvaluationMeasure against the pod's own service jar, runs it in ui2-service over the stored FortiGate
# show_configuration artefacts (read only, counts only, no device command, no write), and prints only show_safe-filtered
# counters into fgteval-<tag>.tsv. Needs the PO session's go. Not run outside a PO-session window: /app/tmp is lost on a rollout, so never during ship/e2e.
set -e
W=${MEASURE_WORK_DIR:?set MEASURE_WORK_DIR}; mkdir -p $W; HERE=$(cd "$(dirname "$0")" && pwd)
TAG=$1; [ -n "$TAG" ] || { echo "usage: measure_run.sh <tag>"; exit 2; }
H=$(head -1 ~/.config/nexus/hosta); K='KUBECONFIG=$HOME/.kube/config kubectl -n ui2'
cd $W
if pgrep -fl hosta_e2e.sh >/dev/null; then echo "deploy e2e running; not starting"; exit 3; fi
WANT=$(ssh $H "$K exec deploy/ui2-service -- stat -c %s /app/service.jar" 2>/dev/null | tail -1)
for i in 1 2 3 4; do
  [ -f podjar.jar ] && [ "$(stat -f %z podjar.jar)" = "$WANT" ] && unzip -tq podjar.jar >/dev/null 2>&1 && break
  ssh $H "$K exec deploy/ui2-service -- cat /app/service.jar" > podjar.jar 2>/dev/null || true
done
[ "$(stat -f %z podjar.jar)" = "$WANT" ] || { echo "jar copy failed"; exit 4; }
if [ ! -d podjar ] || [ podjar.jar -nt podjar ]; then rm -rf podjar && mkdir podjar && (cd podjar && unzip -q ../podjar.jar 'BOOT-INF/*'); fi
CP="podjar/BOOT-INF/classes:$(ls podjar/BOOT-INF/lib/*.jar | tr '\n' ':')"
D=stage-$TAG/fgteval-$TAG; rm -rf stage-$TAG; mkdir -p $D/classes
# Only the harness is compiled: FortiGateConfigPolicyParser, FortiGatePolicyMapper, PolicyHygiene and PolicyLookup are the
# pod's own classes, reached through their public APIs. An older pod lacking them fails compilation (exit 5) before staging.
javac --release 21 -nowarn -cp "$CP" -d $D/classes $HERE/FortiGateEvaluationMeasure.java 2>&1 | grep -v "^Note:" || true
[ -f $D/classes/com/securityexpert/nexus/ui2/service/policy/FortiGateEvaluationMeasure.class ] || { echo "compile failed"; exit 5; }
cp $HERE/run_in_pod.sh $HERE/show_safe.sh $D/
(cd stage-$TAG && COPYFILE_DISABLE=1 tar --no-xattrs -cf - fgteval-$TAG) | ssh $H "$K exec -i deploy/ui2-service -- tar xf - -C /app/tmp 2>/dev/null; $K exec deploy/ui2-service -- sh /app/tmp/fgteval-$TAG/run_in_pod.sh >/dev/null; $K exec deploy/ui2-service -- sh -c 'n=0; while ! grep -q -E \"^(done|fatal)\" /app/tmp/fgteval-$TAG/evaluation.out 2>/dev/null; do sleep 5; n=\$((n+1)); [ \$n -gt 360 ] && break; done; sh /app/tmp/fgteval-$TAG/show_safe.sh /app/tmp/fgteval-$TAG/evaluation.out; rm -rf /app/tmp/fgteval-$TAG'" 2>&1 | grep -v -E "^\*|Authorized|Yalnizca" > fgteval-$TAG.tsv
echo "lines $(wc -l < fgteval-$TAG.tsv)"
