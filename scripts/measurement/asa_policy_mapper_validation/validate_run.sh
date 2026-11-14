#!/bin/bash
# Usage: VALIDATE_WORK_DIR=<scratch dir> validate_run.sh <tag>
# Compiles the ASA parser/mapper of this worktree plus AsaMapperValidation against the pod's service jar, runs it in
# ui2-service over the stored ASA configuration artefacts, and prints only show_safe-filtered counters.
set -e
W=${VALIDATE_WORK_DIR:?set VALIDATE_WORK_DIR}; mkdir -p $W; HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
TAG=$1; H=$(head -1 ~/.config/nexus/hosta); K='KUBECONFIG=$HOME/.kube/config kubectl -n ui2'
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
D=stage-$TAG/val-$TAG; rm -rf stage-$TAG; mkdir -p $D/classes
P=$ROOT/ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/policy
javac --release 21 -nowarn -cp "$CP" -d $D/classes $HERE/AsaMapperValidation.java $P/asa/*.java $P/PolicySnapshot.java 2>&1 | grep -v "^Note:" || true
[ -f $D/classes/nexusmeasure/AsaMapperValidation.class ] || { echo "compile failed"; exit 5; }
mkdir -p $D/classes/policy/asa && cp $ROOT/ui2/platform-core/src/main/resources/policy/asa/*.tsv $D/classes/policy/asa/
cp $HERE/run_in_pod.sh $HERE/show_safe.sh $D/
(cd stage-$TAG && COPYFILE_DISABLE=1 tar --no-xattrs -cf - val-$TAG) | ssh $H "$K exec -i deploy/ui2-service -- tar xf - -C /app/tmp 2>/dev/null; $K exec deploy/ui2-service -- sh /app/tmp/val-$TAG/run_in_pod.sh >/dev/null; $K exec deploy/ui2-service -- sh -c 'n=0; while ! grep -q -E \"^(done|fatal)\" /app/tmp/val-$TAG/validation.out 2>/dev/null; do sleep 5; n=\$((n+1)); [ \$n -gt 360 ] && break; done; sh /app/tmp/val-$TAG/show_safe.sh /app/tmp/val-$TAG/validation.out; rm -rf /app/tmp/val-$TAG'" 2>&1 | grep -v -E "^\*|Authorized|Yalnizca" > val-$TAG.tsv
echo "lines $(wc -l < val-$TAG.tsv)"
