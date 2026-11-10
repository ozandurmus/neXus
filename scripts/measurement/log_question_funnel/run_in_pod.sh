# Runs inside the ui2-service pod.
# Usage: sh run_in_pod.sh <label> <devices-per-vendor, 0 = all> <max-seconds> [extra program arguments ...]
# The directory is /app/tmp/logfunnel unless LOGFUNNEL_DIR says otherwise.
dir=${LOGFUNNEL_DIR:-/app/tmp/logfunnel}
cd "$dir" || exit 2
label=$1
devices=$2
seconds=$3
shift 3
nohup timeout 3600 java -Xmx2g -Djava.io.tmpdir=/app/tmp -Duser.home=/app/home -cp /app/service.jar \
  -Dloader.path="$dir/classes" \
  -Dloader.main=com.securityexpert.nexus.ui2.service.systemlog.LogFunnelMeasure \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --tech techvocab.txt --devices "$devices" --max-seconds "$seconds" "$@" > "$label.out" 2>/dev/null &
echo started $!
