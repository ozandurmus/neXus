# Prompt for the PacketBreaker chat — handover and freeze before the neXus port

Paste everything below the line into the PacketBreaker chat.

---

PacketBreaker is being ported into neXus as a native Java feature (Product Owner decision 2026-10-15). From now on
there is **no new feature development in this repository**. Your last task is a complete handover and a freeze, so the
port can reproduce PacketBreaker's results exactly. Time box: 45 minutes. English only in every file.

## 1. Land all work

- Commit any uncommitted work on its branch. For every open branch and PR, list: name, purpose, state (mergeable /
  unfinished / abandoned). Merge only PRs that are finished and CI-green; leave the rest open and say why.
- Do not start or finish new features.

## 2. Write `docs/HANDOVER_TO_NEXUS.md`

Only what is NOT already in README.md, docs/ARCHITECTURE.md, docs/DECISIONS.md, docs/VALIDATION.md (point to those
for the rest; do not copy them):

1. **Current state**: version, what works end to end, what is partial, known bugs (with the test or case that shows
   each), anything in the docs that is out of date.
2. **Pipeline map**: the exact order of stages from upload to findings (ingest -> per-capture indexes -> matching ->
   topology -> analysis -> findings), with the module/function that owns each stage and the DuckDB tables each stage
   reads and writes. Which tshark fields and display filters each stage uses, and which tshark versions were tested.
3. **Invariants that are easy to break in a rewrite**: ordering assumptions, timestamp/clock handling, NaN/NULL
   semantics, integer vs float, tie-breaking rules, how `unknown` reasons are chosen, deterministic sorting of
   findings. Name the tests that guard each one.
4. **Output contracts**: which findings schema version is current, which API responses the UI depends on, which
   fields are stable for a parity comparison and which are volatile (timestamps, paths, run ids, durations).
5. **Parity corpus**: how to regenerate every synthetic fixture deterministically (commands), which fixtures cover
   which detectors, and the command that produces the findings JSON for one fixture set without the UI. If the slow
   suite (`-m slow`) needs anything special, say how to run it.
6. **Real cases**: what each real case taught (no capture contents, no IPs, no host names, no customer names) and
   where the captures live on this Mac. They stay local and are never committed.
7. **Performance and memory**: measured times and peak memory for the largest captures tried (sizes, packet counts,
   `memory_limit_mb`), and what fails first under constrained memory.
8. **Unrecorded decisions and open questions**: anything decided in chat but not written in DECISIONS.md.
9. **Frontend**: which screens are essential for an operator, which are developer/diagnostic only.

## 3. Freeze and back up

- Commit the handover on a branch, open the PR, merge it once CI is green.
- On `main` after that merge, create an annotated tag `freeze-before-nexus-port` with the message
  "Frozen 2026-10-15: PacketBreaker is ported into neXus; no further development here." and push it.
- Create a full local backup: `git bundle create ~/Codo/backups/PacketBreaker-freeze-2026-10-15.bundle --all`
  (create `~/Codo/backups` if missing), then `git bundle verify` it.
- Add one line at the top of README.md (same PR as the handover): "Frozen 2026-10-15 — being ported into neXus. See
  docs/HANDOVER_TO_NEXUS.md."

## 4. Report back (at most 15 lines)

Tag commit hash, bundle path and its verify result, handover PR link, list of open/unfinished branches, the three
riskiest things for the port, and anything you could not finish.
