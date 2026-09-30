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
  trivy)
    export TRIVY_CACHE_DIR=/cache/trivy
    # Image layers are extracted under TMPDIR: keep them on the disk-backed cache, not the 1 GiB memory /tmp.
    mkdir -p /cache/tmp && export TMPDIR=/cache/tmp
    run trivy-fs trivy fs --scanners vuln --format json --no-progress .
    run trivy-config trivy config --format json deploy/
    # Prepared from Deployments by the host, never guessed from a tag or pod name.
    [ -f /work/images ] || exit 2
    i=0
    while IFS= read -r image; do
      run "trivy-image-$i" trivy image --scanners vuln --format json --no-progress --insecure "$image"
      trivy image --format cyclonedx --scanners vuln --no-progress --insecure "$image" >"/work/sbom-$i.json" 2>/dev/null
      printf '%s\n' "$?" >"/work/sbom-$i.json.exit"
      i=$((i + 1))
    done </work/images
    ;;
  *) exit 2 ;;
esac
exit 0
