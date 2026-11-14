#!/bin/bash
# Usage: SHAPE_WORK_DIR=<empty scratch dir> measure_run.sh <tag>
# Compiles AsaFgtShapeMeasure against the pod's own service jar, stages it in ui2-service /app/tmp, runs it, and prints
# only the show_safe-filtered output into shape-<tag>.tsv. No device contact; reads stored configuration artefacts only.
set -e
W=${SHAPE_WORK_DIR:?set SHAPE_WORK_DIR to an empty scratch directory}; mkdir -p $W; HERE=$(cd "$(dirname "$0")" && pwd)
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
D=stage-$TAG/shape-$TAG; rm -rf stage-$TAG; mkdir -p $D/classes
JAVAC=$(command -v javac || echo $HOME/.local/jdk/bin/javac)
$JAVAC --release 21 -nowarn -cp "$CP" -d $D/classes $HERE/AsaFgtShapeMeasure.java 2>&1 | grep -v "^Note:" || true
[ -f $D/classes/nexusmeasure/AsaFgtShapeMeasure.class ] || { echo "compile failed"; exit 5; }
cp $HERE/run_in_pod.sh $HERE/show_safe.sh $HERE/cisco_port_literals.tsv $HERE/cisco_protocol_literals.tsv $D/
(cd stage-$TAG && COPYFILE_DISABLE=1 tar --no-xattrs -cf - shape-$TAG) | ssh $H "$K exec -i deploy/ui2-service -- tar xf - -C /app/tmp 2>/dev/null; $K exec deploy/ui2-service -- sh /app/tmp/shape-$TAG/run_in_pod.sh >/dev/null; $K exec deploy/ui2-service -- sh -c 'n=0; while ! grep -q -E \"^(done|fatal)\" /app/tmp/shape-$TAG/shape.out 2>/dev/null; do sleep 5; n=\$((n+1)); [ \$n -gt 360 ] && break; done; sh /app/tmp/shape-$TAG/show_safe.sh /app/tmp/shape-$TAG/shape.out; rm -rf /app/tmp/shape-$TAG'" 2>&1 | grep -v -E "^\*|Authorized|Yalnizca" > shape-$TAG.tsv
echo "lines $(wc -l < shape-$TAG.tsv)"
