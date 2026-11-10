# Runs inside the ui2-service pod. Usage: sh run_in_pod.sh <label> <per-source-mb> <per-chunk-mb>
# LOGRISK_JAR selects another service jar (a branch build copied next to the payload); default the pod's own.
cd /app/tmp/logrisk || exit 2
find classes -name 'LogRiskMeasure.class' | head -1
nohup timeout 3000 java -Xmx3g -Djava.io.tmpdir=/app/tmp -Duser.home=/app/home -cp "${LOGRISK_JAR:-/app/service.jar}" \
  -Dloader.path=/app/tmp/logrisk/classes \
  -Dloader.main=com.securityexpert.nexus.ui2.service.systemlog.LogRiskMeasure \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --tech techvocab.txt --english english.txt --per-source-mb "$2" --per-chunk-mb "$3" > "$1.out" 2>/dev/null &
echo started $!
