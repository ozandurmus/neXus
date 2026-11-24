# Runs inside the ui2-service pod (the pod that mounts the privacy key, the artefact-store key and the database credentials).
# Usage: sh run_in_pod.sh <label> --from YYYY-MM-DD --to YYYY-MM-DD --sources cp_messages,pan_system [--max-lines N ...]
# The report is written to /app/tmp/<label>.json; progress goes to /app/tmp/<label>.progress (counts only).
# CENSUS_JAR selects another boot jar (a branch build copied into the pod); default the pod's own.
label=$1
shift
cd /app/tmp || exit 2
nohup timeout 7000 java -Xmx3g -XX:-UseBASE64Intrinsics -Djava.io.tmpdir=/app/tmp -Duser.home=/app/home -cp "${CENSUS_JAR:-/app/service.jar}" \
  -Dloader.main=com.securityexpert.nexus.ui2.worker.diagnostic.SystemLogTemplateCensusMain \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  "$@" --out "/app/tmp/$label.json" > /dev/null 2> "/app/tmp/$label.progress" &
echo started $!
