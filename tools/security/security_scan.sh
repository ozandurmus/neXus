#!/bin/sh
# All scanners finish independently; only the summary container decides the Job outcome.
set -u
umask 077
export HOME=/work/home
export TMPDIR=/tmp
mkdir -p "$HOME"
source_dir="/source/$(cat /work/source-commit)"
cd "$source_dir" || exit 2
run() {
    name="$1"; shift
    "$@" >"/work/$name.json" 2>/dev/null
    printf '%s\n' "$?" >"/work/$name.json.exit"
}
# BEGIN trivy-retry
# Trivy downloads its vulnerability DB and image layers through the proxy on every Job, so one network blip fails a
# whole scan. Each trivy invocation is retried a bounded number of times; the exit code of the LAST attempt is what
# the summary sees, so an exhausted retry is still a scan error and never a pass. Only a short failure class is
# printed (never raw scanner output).
TRIVY_ATTEMPTS=${TRIVY_ATTEMPTS:-3}
TRIVY_RETRY_SLEEP=${TRIVY_RETRY_SLEEP:-20}
trivy_class() {
    if [ "$1" = 137 ]; then echo oom
    elif grep -qiE 'toomanyrequests|429|rate limit' "$2" 2>/dev/null; then echo rate_limit
    elif grep -qiE 'timeout|deadline|timed out' "$2" 2>/dev/null; then echo timeout
    elif grep -qiE 'no such host|connection (refused|reset)|unexpected EOF|proxy|tls|bad gateway|50[0-9]' "$2" 2>/dev/null; then echo network
    elif grep -qiE 'db|database|bundle' "$2" 2>/dev/null; then echo db
    else echo unknown; fi
}
# trivy_try <report> <exit-file> <command...>
trivy_try() {
    report="$1"; exitfile="$2"; shift 2
    attempt=1
    while :; do
        "$@" >"$report" 2>"$report.err"
        rc=$?
        [ "$rc" -ne 0 ] || break
        printf 'TRIVY_FAILED %s attempt=%s/%s rc=%s class=%s\n' "${report##*/}" "$attempt" "$TRIVY_ATTEMPTS" "$rc" "$(trivy_class "$rc" "$report.err")"
        [ "$attempt" -lt "$TRIVY_ATTEMPTS" ] || break
        sleep $((TRIVY_RETRY_SLEEP * attempt))
        attempt=$((attempt + 1))
    done
    rm -f "$report.err"
    printf '%s\n' "$rc" >"$exitfile"
}
# END trivy-retry
case "$1" in
  semgrep)
    XDG_CACHE_HOME=/cache/semgrep run semgrep semgrep scan --metrics=off --disable-version-check --json \
      --config /rules/java.yaml --config /rules/typescript.yaml \
      --config /rules/owasp-top-ten.yaml --config /rules/secrets.yaml .
    ;;
  gitleaks)
    gitleaks dir --redact=100 --report-format=json --report-path=/work/gitleaks-tree.json . >/dev/null 2>&1
    printf '%s\n' "$?" >/work/gitleaks-tree.json.exit
    ;;
  history)
    gitleaks git --redact=100 --report-format=json --report-path=/work/gitleaks-history.json --log-opts=--all . >/dev/null 2>&1
    printf '%s\n' "$?" >/work/gitleaks-history.json.exit
    ;;
  trivy|trivy-source|trivy-image)
    started=$(date +%s)
    export TRIVY_CACHE_DIR=/cache/trivy
    # Image layers are extracted under TMPDIR: keep them on the disk-backed cache, not the 1 GiB memory /tmp.
    mkdir -p /cache/tmp && export TMPDIR=/cache/tmp
    if [ "$1" != trivy-image ]; then
      trivy_try /work/trivy-fs.json /work/trivy-fs.json.exit trivy fs --scanners vuln --format json --no-progress .
      trivy_try /work/trivy-config.json /work/trivy-config.json.exit trivy config --format json deploy/
    fi
    [ "$1" != trivy-source ] || exit 0
    # Prepared from Deployments by the host, never guessed from a tag or pod name.
    [ -f /work/images ] || exit 2
    i=0
    while IFS= read -r image; do
      trivy_try "/work/trivy-image-$i.json" "/work/trivy-image-$i.json.exit" trivy image --scanners vuln --format json --no-progress --insecure "$image"
      trivy_try "/work/sbom-$i.json" "/work/sbom-$i.json.exit" trivy image --format cyclonedx --scanners vuln --no-progress --insecure "$image"
      i=$((i + 1))
    done </work/images
    [ "$1" != trivy-image ] || printf "TIMING security_image %s\n" "$(( $(date +%s) - started ))"
    ;;
  *) exit 2 ;;
esac
exit 0
