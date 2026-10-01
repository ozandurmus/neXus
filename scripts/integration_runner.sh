#!/usr/bin/env bash
# Runs only in the integration Job. All raw diagnostics stay in its emptyDir.
set -Eeuo pipefail
exec 2>/tmp/integration-setup.log
trap 'echo "INTEGRATION: ERROR (runner/setup failed; raw output withheld)"; exit 2' ERR
cd /workspace/ui2
mkdir -p "$GRADLE_USER_HOME" /tmp/anchors
# Preserve the image trust roots and add the same corp-ca bundle as the build.
cp "$JAVA_HOME/lib/security/cacerts" /tmp/cacerts
for bundle in /run/corp-ca/*; do
  [ -f "$bundle" ] || continue
  awk '/-----BEGIN CERTIFICATE-----/{n++} n {print > ("/tmp/anchors/cert-" n ".pem")}' "$bundle"
  for cert in /tmp/anchors/*.pem; do
    [ -f "$cert" ] || continue
    keytool -importcert -noprompt -keystore /tmp/cacerts -storepass changeit \
      -alias "corp-$(basename "$bundle")-$(basename "$cert")" -file "$cert" >/dev/null 2>&1
    rm "$cert"
  done
done
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/tmp/cacerts -Duser.home=$HOME"
# Wrapper and daemon both use the host build's proxy system properties.
for option in ${GRADLE_OPTS:-}; do
  case "$option" in
    -Dhttp.proxyHost=*|-Dhttp.proxyPort=*|-Dhttps.proxyHost=*|-Dhttps.proxyPort=*)
      printf 'systemProp.%s\n' "${option#-D}" >> "$GRADLE_USER_HOME/gradle.properties" ;;
  esac
done
status=0
./gradlew --no-daemon --console=plain -PfrontendPrebuilt=true :integration-tests:test --rerun-tasks >/tmp/gradle.log 2>&1 || status=$?
# A separate JVM parses only counters and safe identifiers, never diagnostic text.
trap - ERR
java /workspace/scripts/IntegrationSummary.java "$status" integration-tests/build/test-results/test
