# Runs inside the ui2-service pod. Usage: sh run_in_pod.sh <label> <devices-per-vendor, 0 = all> <max-seconds>
cd /app/tmp/logfunnel || exit 2
nohup timeout 3600 java -Xmx2g -Djava.io.tmpdir=/app/tmp -Duser.home=/app/home -cp /app/service.jar \
  -Dloader.path=/app/tmp/logfunnel/classes \
  -Dloader.main=com.securityexpert.nexus.ui2.service.systemlog.LogFunnelMeasure \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --tech techvocab.txt --devices "$2" --max-seconds "$3" > "$1.out" 2>/dev/null &
echo started $!
