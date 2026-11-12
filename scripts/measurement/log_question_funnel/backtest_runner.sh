#!/bin/bash
# Runs the backtest configurations of LogFunnelMeasure in the ui2-service pod, one after the other, and brings back only
# the filtered aggregates (labels and numbers). It starts only when the host is quiet: not before <not-before> Istanbul
# time, no ship or deploy running on this workstation, no e2e pod running, every ui2 pod Running, the service pod older
# than 10 minutes, all of it twice in a row. A run that loses its pod (a rollout) is repeated, at most three times.
# Usage: backtest_runner.sh <payload-dir> <out-dir> [not-before HHMM, default 0315] [set: backtest (default) | sweep]
# <payload-dir> holds classes/, techvocab.txt, show_safe.sh and run_in_pod.sh (see the record's "How it was run").
PAY=$1; OUT=$2; NB=${3:-0315}; SET=${4:-backtest}
H=$(head -1 ~/.config/nexus/hosta)
KEY=~/.ssh/id_ed25519
mkdir -p "$OUT"
remote() { ssh -T -o ConnectTimeout=20 -o ServerAliveInterval=30 -i "$KEY" "$H" "$@" 2>/dev/null | grep -v -e '\*\*\*' -e Authorized -e Yalnizca; }
log() { echo "$(TZ=Europe/Istanbul date +%H:%M:%S) $*" >> "$OUT/runner.log"; }

quiet() { # prints the service pod name when the host is quiet, nothing otherwise
  [ "$((10#$(TZ=Europe/Istanbul date +%H%M)))" -lt "$((10#$NB))" ] && return 1
  pgrep -f "hosta_deploy.sh|module_deploy.py|standalone_orchestrate.py ship|tools/e2e/hosta_e2e.sh" >/dev/null && return 1
  remote 'export KUBECONFIG=$HOME/.kube/config
    bad=$(kubectl -n ui2 get pods --no-headers | grep -v -E "ui2-e2e.*Completed|ui2-e2e.*Error" | grep -v -E " Running " | wc -l)
    e2e=$(kubectl -n ui2 get pods --no-headers | grep -E "ui2-e2e" | grep -E "Running|Pending|ContainerCreating" | wc -l)
    pod=$(kubectl -n ui2 get pods -l app.kubernetes.io/component=service -o name | head -1)
    start=$(kubectl -n ui2 get $pod -o jsonpath="{.status.startTime}")
    age=$(( $(date +%s) - $(date -d "$start" +%s) ))
    [ "$bad" = 0 ] && [ "$e2e" = 0 ] && [ "$age" -ge 600 ] && echo "${pod#pod/}"' | tail -1
}

wait_quiet() { # two quiet polls a minute apart, naming the same pod
  while :; do
    a=$(quiet); if [ -n "$a" ]; then sleep 60; b=$(quiet); [ "$a" = "$b" ] && { POD=$a; return 0; }; fi
    sleep 60
  done
}

run_config() { # label, program arguments ...
  label=$1; shift
  for attempt in 1 2 3; do
    wait_quiet
    log "$label attempt $attempt on the quiet host"
    tar -C "$PAY" -cf - . | remote "export KUBECONFIG=\$HOME/.kube/config; kubectl -n ui2 exec -i $POD -- sh -c 'rm -rf /app/tmp/logbt; mkdir -p /app/tmp/logbt && tar xf - -C /app/tmp/logbt 2>/dev/null; LOGFUNNEL_DIR=/app/tmp/logbt sh /app/tmp/logbt/run_in_pod.sh $label 0 2400 $*'" > "$OUT/$label.start"
    pid=$(grep -o 'started [0-9]*' "$OUT/$label.start" | tail -1 | cut -d' ' -f2)
    [ -z "$pid" ] && { log "$label did not start"; continue; }
    for i in $(seq 1 360); do   # up to 30 minutes of sleeping
      sleep 5
      state=$(remote "export KUBECONFIG=\$HOME/.kube/config; kubectl -n ui2 exec $POD -- sh -c '[ -d /proc/$pid ] && echo running || echo done'" | tail -1)
      [ "$state" = "done" ] && break
    done
    remote "export KUBECONFIG=\$HOME/.kube/config; kubectl -n ui2 exec $POD -- sh -c 'cd /app/tmp/logbt && sh show_safe.sh $label.out'" > "$OUT/$label.tsv"
    if grep -q "^meta.total_seconds" "$OUT/$label.tsv" && ! grep -q "^fatal" "$OUT/$label.tsv"; then log "$label finished"; return 0; fi
    log "$label incomplete (pod lost or run failed), repeating"
  done
  return 1
}

# The pool is young: before 2026-10-24 no device-source pair has the 7 learning days of the 2026-11-11 rule, so the
# configurations that look at the failover runs run with --signatures-ungated 1 (the rule off) to see what the detector finds.
if [ "$SET" = "sweep" ]; then
  # S1: the failover week, rule off, with the burst-threshold sweep and the pairs the rule would have let through
  # S2: the newest week with the real 7-day gate, whole fleet: the steady-state background and the crash alert
  run_config S1 --end-day 2026-10-24 --test-days 7 --min-learn-days 7 --backtest 1 --signatures-ungated 1
  run_config S2 --test-days 7 --min-learn-days 7 --backtest 1
else
  # A: no history before the test week (signatures only); B: the most history the pool has; C: the crash alert's week
  run_config A --end-day 2026-10-24 --test-days 7 --min-learn-days 7 --backtest 1 --signatures-ungated 1
  run_config B --end-day 2026-10-23 --test-days 3 --min-learn-days 2 --backtest 1 --signatures-ungated 1
  run_config C --test-days 7 --min-learn-days 7 --backtest 1 --only-event-devices 1 --signatures-ungated 1
fi
remote 'export KUBECONFIG=$HOME/.kube/config; P=$(kubectl -n ui2 get pods -l app.kubernetes.io/component=service -o name | head -1); kubectl -n ui2 exec $P -- rm -rf /app/tmp/logbt' >/dev/null
log "done"
echo DONE >> "$OUT/runner.log"
