#!/bin/sh
# Check Point configuration measurement (14C section 3 "Check Point, configuration").
# Run ON the gateway or VSX host in Expert: sh cp_config_shape.sh [VSID]
# Prints SHAPE only: exit codes, line counts, 'set' line counts, how many lines
# carry a secret-bearing keyword (withheld), and the first word pair of each
# distinct 'set <section> <sub>' as a section histogram. No line content.
VSID="$1"
shape() {
  out=$(cat); rc=$?
  total=$(printf '%s\n' "$out" | wc -l | tr -d ' ')
  setl=$(printf '%s\n' "$out" | grep -c '^set ')
  secret=$(printf '%s\n' "$out" | grep -Eic 'password|passwd|secret|community|auth-key|private-key|pre-shared|psk|credential|token')
  bytes=$(printf '%s' "$out" | wc -c | tr -d ' ')
  hash=$(printf '%s' "$out" | (shasum -a 256 2>/dev/null || sha256sum) | cut -c1-16)
  printf '### lines=%s set_lines=%s secret_bearing_lines=%s bytes=%s sha256_16=%s\n' "$total" "$setl" "$secret" "$bytes" "$hash"
  printf '### section histogram (set <a> <b> : count):\n'
  printf '%s\n' "$out" | awk '/^set /{k=$2; if ($3 ~ /^[a-z][a-z-]*$/) k=k" "$3; c[k]++} END{for(k in c) printf "  %6d  set %s\n", c[k], k}' | sort -rn | head -40
  printf '### first 3 lines, values masked:\n'
  printf '%s\n' "$out" | head -3 | sed -E -e 's#([0-9]{1,3}\.){3}[0-9]{1,3}(/[0-9]{1,2})?#<ip4>#g' -e 's#"[^"]*"#"<str>"#g' -e 's#[A-Za-z0-9]+(-[A-Za-z0-9.]+){2,}#<name>#g' -e 's#[0-9A-Za-z]{10,}#<tok>#g'
}
run() { printf '\n### CMD: %s\n' "$1"; sh -c "$1" 2>&1 | shape; }
run 'clish -c "show hostname" | wc -l'
run 'clish -c "show version all"'
run 'clish -c "cpstat os -f hw_info"'
printf '\n### CMD: clish -c "show configuration"  (content never printed)\n'; clish -c "show configuration" 2>&1 | shape
printf '\n### CMD: clish -c "show configuration" second read (Q: identical hash?)\n'; clish -c "show configuration" 2>&1 | shape | head -1
if [ -n "$VSID" ]; then
  printf '\n### CMD: bash -lc "vsenv %s && clish -c \"show configuration\""\n' "$VSID"; bash -lc "vsenv $VSID && clish -c 'show configuration'" 2>&1 | shape
  printf '\n### CMD: one clish process: set virtual-system %s ; show configuration\n' "$VSID"; printf 'set virtual-system %s\nshow configuration\nexit\n' "$VSID" | clish 2>&1 | shape
fi
