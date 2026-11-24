# Runs inside the ui2-service pod from its staged directory. Usage: sh <dir>/run_in_pod.sh
cd "$(dirname "$0")" || exit 2
nohup timeout 1800 java -Xmx2g -Djava.io.tmpdir=/app/tmp -Duser.home=/app/home -cp /app/service.jar \
  -Dloader.path="$(pwd)/classes" -Dloader.main=nexusmeasure.PanPre12ZoneSetMeasure \
  org.springframework.boot.loader.launch.PropertiesLauncher "$(pwd)" >/dev/null 2>&1 &
echo started $!
