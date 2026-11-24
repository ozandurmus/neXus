#!/bin/bash
# Tier B pre-merge review (PO 2026-11-23): a lane that changes production code outside the Estate UI is reviewed by
# Sonnet before the ship runner may batch it. Usage: b-review.sh <lane-name>
# The lane's ship-queue entry carries review=pending until this script writes a verdict:
#   PASS  -> the review=pending line is removed and reviewed=sonnet added; the ship runner then batches it.
#   BLOCK -> FIRST block (2026-11-24 review): the review goes back to the author once. The entry moves to
#            ship-queue/returned/ and a fix lane is queued on the SAME branch (base = the lane's branch) with the review
#            in its brief; the fix lane's ship entry carries review_returned=1, so its review runs again.
#            SECOND block (review_returned=1), or a diff too large to review: the entry moves to ship-queue/held/ with the
#            review in logs/review-<name>.md (a person decides).
. "$(dirname "${BASH_SOURCE[0]}")/ops_lib.sh"
J=$OPS_STATE; SQ=$J/ship-queue; L=$J/logs; R=$OPS_REPO

# review_decide <name> <ship-entry> <branch> <review-file>: act on the verdict in the review file.
review_decide() {
  local n=$1 f=$2 br=$3 out=$4 desc
  if grep -qE '^VERDICT: PASS$' "$out" || { grep -qE '^VERDICT: PASS' "$out" && ! grep -q 'BLOCK (Neon)' "$out"; }; then
    sed_inplace '/^review=pending$/d' "$f"; echo "reviewed=sonnet" >> "$f"
    echo "$(date '+%F %T') B-REVIEW PASS $n" >> "$L/ship-runner.log"
    return 0
  fi
  mkdir -p "$SQ/held" "$SQ/returned"
  if grep -q '^review_returned=1' "$f" || grep -q 'too large for a pre-merge review' "$out"; then
    mv "$f" "$SQ/held/"
    echo "$(date '+%F %T') B-REVIEW BLOCK $n: held, see $out" >> "$L/ship-runner.log"
    return 0
  fi
  desc=$(echo "$n" | sed -E 's/^([0-9]{8}-[0-9]{4}-G[0-9]+-D[0-9]+-|[0-9]{1,5}-)//')
  sed_inplace '/^review=pending$/d' "$f"; echo "review_returned=1" >> "$f"
  local extra=(review_returned=1 pin_claude=1)
  grep -q '^allow_paths=1' "$f" && extra+=(allow_paths=1)
  lane_brief "fix-blocked-$desc" "$br" "# Task: fix the pre-merge review BLOCK of lane $n
This worktree is branch $br (finished lane $n, a clone of its commits). A pre-merge review blocked it. Fix each concrete
defect it names in this branch's changes, run the tests of the touched module and commit on this branch. Never weaken or
delete a test to make the review pass; if a finding is not a real defect, say why in the commit message. The result is
reviewed once more: a second BLOCK goes to a person.
Review ($out, first 6000 characters):
$(head -c 6000 "$out")" "${extra[@]}" >> "$L/ship-runner.log"
  mv "$f" "$SQ/returned/"
  git -C "$R" worktree remove --force "$OPS_CODO/nexus-q-$n" 2>/dev/null
  echo "$(date '+%F %T') B-REVIEW RETURNED $n: first block, fix lane ${LAST_LANE_BRIEF:-?} queued on the same branch; see $out" >> "$L/ship-runner.log"
}

review_main() {
  set -uo pipefail
  local n=$1 f br mb diff lines out src wt wt2
  f=$SQ/$n.ship
  [ -e "$f" ] || exit 0
  br=$(grep '^branch=' "$f" | cut -d= -f2); mb=$(git -C "$R" merge-base "$br" origin/main)
  diff=$(git -C "$R" diff "$mb" "$br" -- . ':(exclude)*.lock' ':(exclude)*lockfile*')
  lines=$(printf '%s\n' "$diff" | wc -l | tr -d ' ')
  out=$L/review-$n.md
  # A large change whose production part fits is reviewed on that part (tests listed by name only), 2026-11-23 lane 390.
  if [ "$lines" -gt 4000 ]; then
    src=$(git -C "$R" diff "$mb" "$br" -- . ':(exclude)*.lock' ':(exclude)*lockfile*' ':(exclude)*/tests/*' ':(exclude)*/test/*' ':(exclude)*.test.*')
    if [ "$(printf '%s\n' "$src" | wc -l | tr -d ' ')" -le 4000 ]; then
      diff="$src
(Test files changed but not shown: $(git -C "$R" diff --name-only "$mb" "$br" | grep -E '/tests?/|\.test\.' | tr '\n' ' '))"
      lines=$(printf '%s\n' "$diff" | wc -l | tr -d ' ')
    fi
  fi
  if [ "$lines" -gt 4000 ]; then
    echo "VERDICT: BLOCK (diff of $lines lines is too large for a pre-merge review)" > "$out"
  else
    wt=$OPS_CODO/nexus-q-$n; [ -d "$wt" ] || wt=$R
    (cd "$wt" && claude -p "You review one change before it is merged to main of a bank firewall-operations product (Java/React).
It was written by a small model without human review. Look only for concrete defects that matter: wrong behaviour, a
broken invariant, UNKNOWN or missing data shown as OK/zero/green, a security or privacy regression (credentials,
device commands, authorization, masking), a weakened or deleted test, concurrency/transaction mistakes, error paths that
swallow failures, real-looking hostnames/IPs/serials or secret-shaped literals. Read surrounding code in this worktree
when needed. Ignore style. End with exactly one line: 'VERDICT: PASS' or 'VERDICT: BLOCK' and, for BLOCK, the defects
with file:line above it.

The change (git diff against main):
$diff" --model sonnet --effort medium --permission-mode plan --output-format text) > "$out" 2>&1
  fi
  [ -e "$f" ] || exit 0
  # Two eyes from two model families (PO 2026-11-23 "çift göz analiz değerli"): a lane written by Claude also gets a
  # Gemini Neon review; one written on Antigravity already has a different family (Sonnet) as its reviewer.
  if grep -qE '^VERDICT: PASS' "$out" && ! grep -q "Gemini 4" <(git -C "$R" log -1 --format=%B "$br"); then
    wt2=$OPS_CODO/nexus-q-$n; [ -d "$wt2" ] || wt2=$R
    printf '%s\n' "$diff" > "$wt2/.review-$n.diff"   # inside the worktree: headless agy cannot read outside it
    (cd "$wt2" && agy -p "You review one change before it is merged (bank firewall-operations product, Java/React), written by a small model without human review. Report only concrete defects that matter: wrong behaviour, broken invariant, UNKNOWN shown as OK/zero, security or privacy regression, weakened/deleted test, concurrency mistakes, swallowed errors, real-looking hostnames/IPs/secrets. Ignore style. End with exactly one line 'VERDICT: PASS' or 'VERDICT: BLOCK'. The diff is in the file .review-$n.diff in this directory." \
       --model gemini-4-neon-high --mode plan --print-timeout 1500 >> "$out.neon" 2>&1) || true
    rm -f "$wt2/.review-$n.diff"
    if grep -qE '^VERDICT: BLOCK' "$out.neon"; then cat "$out.neon" >> "$out"; sed_inplace 's/^VERDICT: PASS/VERDICT: PASS (Sonnet); BLOCK (Neon)/' "$out"; fi
  fi
  review_decide "$n" "$f" "$br" "$out"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then review_main "$@"; fi
