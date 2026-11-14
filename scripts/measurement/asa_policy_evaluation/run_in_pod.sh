# Runs inside the ui2-service pod from its staged directory.
cd "$(dirname "$0")" || exit 2
nohup timeout 2400 java -Xmx3g -Djava.io.tmpdir=/app/tmp -Duser.home=/app/home -cp /app/service.jar \
  -Dloader.path="$(pwd)/classes" -Dloader.main=com.securityexpert.nexus.ui2.service.policy.AsaEvaluationMeasure \
  org.springframework.boot.loader.launch.PropertiesLauncher "$(pwd)" >/dev/null 2>&1 &
echo started $!
