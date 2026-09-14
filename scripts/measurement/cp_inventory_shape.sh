#!/bin/sh
# Check Point inventory measurement (PO_DECISION_RECORD_2026_09_14C section 5, M-1 and M-2).
# Run this ON the gateway or VSX host after logging in over SSH (copy-paste the
# whole file into a shell, or scp it and run: sh cp_inventory_shape.sh [VSID]).
# It prints only SHAPE: shell type, exit codes, line counts, and each output
# line with every address, MAC, serial-like token and long number masked.
# Nothing it prints is an estate value. Paste the whole output back.
VSID="$1"
mask() {
  sed -E \
    -e 's#([0-9]{1,3}\.){3}[0-9]{1,3}(/[0-9]{1,2})?#<ip4>#g' \
    -e 's#([0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(/[0-9]{1,3})?#<ip6>#g' \
    -e 's#([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}#<mac>#g' \
    -e 's#[A-Za-z0-9]+(-[A-Za-z0-9.]+){2,}#<name>#g' \
    -e 's#(syslogd|Expert)@[^ :]+#\1@<host>#g' \
    -e 's#[0-9A-Za-z]{10,}#<tok>#g' \
    -e 's#[0-9]{5,}#<num>#g'
}
run() {
  printf '\n### CMD: %s\n' "$1"
  out=$(sh -c "$1" 2>&1); rc=$?
  printf '### rc=%s lines=%s\n' "$rc" "$(printf '%s\n' "$out" | wc -l | tr -d ' ')"
  printf '%s\n' "$out" | head -60 | mask
}
printf '### shell=%s user_shell_hint=%s\n' "$0" "$(ps -o comm= -p $$ 2>/dev/null)"
printf '### landing prompt hint: %s\n' "$(printf '%s' "$PS1" | mask)"
run 'id -un >/dev/null 2>&1 && echo expert_capable || echo no_expert'
run 'ip -details -4 addr show'
run 'ip -6 addr show'
run 'ip -4 route show table all'
run 'cphaprob stat'
run 'cphaprob -a -m if'
run 'vsx stat -v'
if [ -n "$VSID" ]; then
  run "vsenv $VSID; ip -4 addr show; ip -4 route show"
  run "vsenv $VSID; cphaprob stat"
  printf '\n### M-2 decisive step: now open a NEW ssh session to this host and run only: ip -4 addr show\n'
  printf '### Compare its interface names with the physical block above; report SAME or DIFFERENT.\n'
fi
